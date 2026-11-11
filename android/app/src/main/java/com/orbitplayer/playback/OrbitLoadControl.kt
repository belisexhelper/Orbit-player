package com.orbitplayer.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.orbitplayer.core.AppSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * ExoPlayer buffering that follows the two buffer settings, read on every decision (a change applies
 * right away, no new player needed):
 *
 *  - Live TV: a live stream arrives only as fast as it is broadcast, so the cushion against a bad
 *    connection or server is how much the player collects BEFORE it starts the picture (and after a stall).
 *    It starts with the Live TV buffer setting. Some servers stop sending for many seconds now and then;
 *    every stall shows how long such a gap is, so the cushion for that server grows by the length of the
 *    stall (up to 30 s). After one or two stalls it covers the server's gaps; good servers stay at the setting.
 *    What was learned is kept per server until the app closes.
 *  - Movies & series: they download faster than they play, so the player starts quickly (2.5 s) and keeps
 *    loading ahead up to the Movies & series buffer.
 *
 * Everything else (memory limit, allocator, back buffer) is ExoPlayer's own DefaultLoadControl;
 * its time limits are only the outer bounds (4 minutes).
 */
@OptIn(UnstableApi::class)
class OrbitLoadControl(
    private val settings: () -> AppSettings,
    private val isLive: () -> Boolean,
    /** catch-up (going back in time on a channel): part of Live TV, so it follows the Live TV buffer */
    private val isCatchup: () -> Boolean = { false },
    /** the server of the current stream (what is learned is kept per server) */
    private val serverKey: () -> String,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    240_000, // min buffer
    240_000, // max buffer
    1_000, // start (our rule below decides)
    1_000, // start after a drop (our rule below decides)
    C.LENGTH_UNSET, // memory limit: ExoPlayer's default for the tracks
    false,
    0,
    false,
) {
    companion object {
        private const val MAX_LIVE_CUSHION_MS = 30_000L
    }

    /** movies: what the player waits for right now (2.5 s at the start, the setting after a stall) */
    @Volatile var currentNeedMs: Long = 2_500L
        private set

    /** extra cushion learned per server from its stalls */
    private val extraMs = ConcurrentHashMap<String, Long>()

    /** when the current stall began (0 = not stalled) */
    @Volatile private var stallStartedAt = 0L

    /** live: what the player collects before the picture starts (setting + what this server needed) */
    val liveTargetMs: Long
        get() = (settings().liveBufferMs + learnedExtraMs).coerceAtMost(MAX_LIVE_CUSHION_MS)

    /** movies, series and catch-up: the Movies & series buffer */
    val aheadTargetMs: Long
        get() = settings().vodBufferMs.toLong() // catch-up too: it comes from the server's archive, not live

    /** extra seconds learned for the current server (0 = none, or Auto buffer is off) */
    val learnedExtraMs: Long get() = if (settings().autoLiveBuffer) extraMs[serverKey()] ?: 0L else 0L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        if (!isLive()) {
            stallStartedAt = 0L
            // movies: start fast; after a stall collect the Movies & series buffer before going on, so a slow
            // connection plays a long while between stalls (if memory fills up first, ExoPlayer goes on by itself)
            val needMs = if (parameters.rebuffering) aheadTargetMs else 2_500L
            currentNeedMs = needMs
            return bufferedUs >= needMs * 1000L
        }
        val now = SystemClock.elapsedRealtime()
        if (parameters.rebuffering && stallStartedAt == 0L) stallStartedAt = now
        var needUs = liveTargetMs * 1000L
        // HLS live starts a fixed distance behind live: more than that can never be buffered
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) needUs = minOf(needUs, parameters.targetLiveOffsetUs * 8 / 10)
        if (bufferedUs < needUs) return false
        // a stall is over: the server's gap was as long as the stall, add it to this server's cushion
        if (stallStartedAt != 0L && !settings().autoLiveBuffer) stallStartedAt = 0L
        if (stallStartedAt != 0L) {
            val stalled = now - stallStartedAt
            val key = serverKey()
            val learned = ((extraMs[key] ?: 0L) + stalled).coerceAtMost(MAX_LIVE_CUSHION_MS)
            extraMs[key] = learned
            PlaybackStats.learnedBuffer[key] = learned
            PlaybackStats.event("Live buffer for $key raised to %.0f s".format((settings().liveBufferMs + learned) / 1000f))
            stallStartedAt = 0L
        }
        return true
    }

    /** last known buffer (ms), readable from any thread (the live data source uses it) */
    @Volatile var lastBufferedMs: Long = 0L
        private set

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        lastBufferedMs = parameters.bufferedDurationUs / 1000
        val more = super.shouldContinueLoading(parameters) // keeps the memory limit
        if (isLive()) return more
        return more && parameters.bufferedDurationUs < aheadTargetMs * 1000L
    }

    /** a new stream starts: a stall of the previous one doesn't count */
    fun newStream() {
        stallStartedAt = 0L
    }
}
