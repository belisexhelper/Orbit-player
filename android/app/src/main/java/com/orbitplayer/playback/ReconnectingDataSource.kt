package com.orbitplayer.playback

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.orbitplayer.core.AppLog
import java.io.IOException

/**
 * Live channels (one long HTTP stream, not HLS): some servers stop sending on a connection and leave it
 * hanging. Instead of waiting until the buffer is empty, a connection that sends nothing for a few seconds
 * (the short read timeout of [live]) is replaced by a new one right away, while the picture keeps playing
 * from the buffer. The new connection continues filling the same buffer (MPEG-TS finds its way back in;
 * only what the server never sent is skipped).
 *
 * Everything else (movies, HLS segments, subtitles…) goes through [normal] unchanged.
 */
@OptIn(UnstableApi::class)
class ReconnectingDataSource(
    private val normal: DataSource,
    /** live connections by how long they wait without data before counting as stuck (seconds → source) */
    private val lives: List<Pair<Int, DataSource>>,
    /** true when this address is the live channel being played and fast reconnect is on */
    private val reconnectFor: (Uri) -> Boolean,
    /** how much the player has buffered (ms): with a big buffer a pause of the server is no reason for a new connection */
    private val bufferedMs: () -> Long,
) : DataSource {

    class Factory(
        private val normal: DataSource.Factory,
        private val lives: List<Pair<Int, DataSource.Factory>>,
        private val reconnectFor: (Uri) -> Boolean,
        private val bufferedMs: () -> Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ReconnectingDataSource(normal.createDataSource(), lives.map { it.first to it.second.createDataSource() }, reconnectFor, bufferedMs)
    }

    /** the live connection in use (its wait-for-data time fits the buffer when it was opened) */
    private var live: DataSource = lives.first().second

    /** wait longer for data when more is buffered: about half the buffer, between 5 and 20 s */
    private fun pickLive(): DataSource {
        val waitS = (bufferedMs() / 2000).toInt()
        live = (lives.lastOrNull { it.first <= waitS } ?: lives.first()).second
        return live
    }
    private var gateUri: Uri? = null

    private var current: DataSource? = null
    private var spec: DataSpec? = null
    private var reconnecting = false
    /** times of the recent reconnects (to give up when the server is really gone) */
    private val recent = ArrayDeque<Long>()
    /** keeps out what the player already has when a new connection starts in the past */
    private var gate = TsGate()
    private val tmp = ByteArray(64 * 1024)

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        lives.forEach { it.second.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        reconnecting = reconnectFor(dataSpec.uri)
        val src = if (reconnecting) pickLive() else normal
        current = src
        if (!reconnecting) return src.open(dataSpec)
        // the same channel opened again by ExoPlayer (after an error): keep what was learned, so the part the
        // server sends again is left out here too; another channel: start fresh
        if (gateUri != dataSpec.uri) {
            gate = TsGate()
            gateUri = dataSpec.uri
        } else gate.reconnected()
        // some servers take a few seconds to start a channel: a timeout here is tried again (the stream is warm by then)
        var attempt = 0
        while (true) {
            try {
                live.open(dataSpec)
                return C.LENGTH_UNSET.toLong() // a live stream has no length (and the gate may leave bytes out)
            } catch (e: IOException) {
                if (attempt >= 2 || !isTimeout(e) || Thread.currentThread().isInterrupted) throw e
                attempt++
                AppLog.w("Live stream slow to start, trying again ($attempt)")
                runCatching { live.close() }
            }
        }
    }

    private fun isTimeout(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.net.SocketTimeoutException || t is java.io.InterruptedIOException) return true
            t = t.cause
        }
        return false
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val src = current ?: throw IOException("Not open")
        if (!reconnecting) return src.read(buffer, offset, length)
        if (length == 0) return 0
        var attempt = 0
        while (true) {
            if (gate.pending > 0) return gate.drain(buffer, offset, length)
            try {
                val n = live.read(tmp, 0, minOf(length, tmp.size))
                if (n == C.RESULT_END_OF_INPUT) {
                    // a live stream doesn't end: the server closed the connection
                    throw IOException("Live stream closed by the server")
                }
                gate.feed(tmp, 0, n)
                continue // hand over what may pass (or read more when everything was left out)
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                val now = SystemClock.elapsedRealtime()
                while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
                if (recent.size >= 12 || attempt >= 3) throw e // really gone: let the player's own reconnect handle it
                recent.addLast(now)
                attempt++
                AppLog.w("Live stream stopped sending (${e.message}), new connection #$attempt")
                PlaybackStats.newConnection(if (isTimeout(e)) "server silent" else e.message ?: "connection closed")
                runCatching { live.close() }
                try {
                    Thread.sleep(300L * attempt)
                    val s = spec ?: throw e
                    // the same address again, from "now" (no range: it's live)
                    pickLive()
                    current = live
                    live.open(s.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
                    gate.reconnected()
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (oe: IOException) {
                    AppLog.w("Reconnect failed: ${oe.message}")
                    // loop: next attempt (or give up above)
                }
            }
        }
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
