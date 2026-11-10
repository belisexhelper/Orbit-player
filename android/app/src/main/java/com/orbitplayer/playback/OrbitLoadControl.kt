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

/**
 * ExoPlayer buffering that follows the two buffer settings, read on every decision (a change applies
 * right away, no new player needed):
 *
 *  - Live TV: a live stream arrives only as fast as it is broadcast, so the cushion against a bad
 *    connection is how much the player collects BEFORE it starts the picture.
 *    Many IPTV servers don't send evenly but in chunks (e.g. 20 s at once, then nothing until it is used up).
 *    Starting the moment a chunk arrives means the next one arrives exactly when the picture runs out:
 *    every tiny delay of the server is a stall, again and again. So once a stall was seen, the player
 *    waits the Live TV buffer time AFTER the buffer is filled before it plays: it then runs that much
 *    behind the chunks, and the next one arrives while there is still picture left.
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
    /** a live stream stalled while playing (this app session): from now on live starts wait a little longer */
    @Volatile var stallSeen = false
        private set

    /** when the buffer reached the target in the current buffering period (0 = not yet) */
    @Volatile private var filledAt = 0L

    /** live, after a stall: how long the player still waits before the picture starts (for the indicator) */
    val holdRemainingMs: Long
        get() {
            val f = filledAt
            if (f == 0L || !stallSeen) return 0L
            return (settings().liveBufferMs - (SystemClock.elapsedRealtime() - f)).coerceAtLeast(0L)
        }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val s = settings()
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        if (!isLive()) {
            filledAt = 0L
            return bufferedUs >= if (parameters.rebuffering) 5_000_000L else 2_500_000L
        }
        if (parameters.rebuffering) stallSeen = true
        var needUs = s.liveBufferMs * 1000L
        // HLS live starts a fixed distance behind live: more than that can never be buffered
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) needUs = minOf(needUs, parameters.targetLiveOffsetUs * 8 / 10)
        if (bufferedUs < needUs) {
            filledAt = 0L
            return false
        }
        if (!stallSeen) return true
        // after a stall: hold the Live TV buffer time once the buffer is full, then play
        val now = SystemClock.elapsedRealtime()
        if (filledAt == 0L) filledAt = now
        if (now - filledAt < s.liveBufferMs) return false
        filledAt = 0L
        return true
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val more = super.shouldContinueLoading(parameters) // keeps the memory limit
        if (isLive()) return more
        return more && parameters.bufferedDurationUs < settings().vodBufferMs * 1000L
    }
}
