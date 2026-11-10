package com.orbitplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import com.orbitplayer.core.AppSettings

/**
 * ExoPlayer buffering that follows the two buffer settings, read on every decision (a change applies
 * right away, no new player needed):
 *
 *  - Live TV: a live stream arrives only as fast as it is broadcast, so the cushion against a bad
 *    connection is how much the player collects BEFORE it starts the picture (and again after a drop).
 *    That is the Live TV buffer: longer = the channel starts a little later, but plays through short drops.
 *  - Movies & series: they download faster than they play, so the player starts quickly (2.5 s) and keeps
 *    loading ahead up to the Movies & series buffer.
 *
 * The wrapped DefaultLoadControl only sets the outer limits (memory size and 4 minutes at most).
 */
@OptIn(UnstableApi::class)
class OrbitLoadControl(
    private val settings: () -> AppSettings,
    private val isLive: () -> Boolean,
    private val base: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(240_000, 240_000, 1_000, 1_000)
        .build(),
) : LoadControl by base {

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val s = settings()
        var needUs = if (isLive()) {
            s.liveBufferMs * 1000L
        } else {
            if (parameters.rebuffering) 5_000_000L else 2_500_000L
        }
        // HLS live starts a fixed distance behind live: more than that can never be buffered
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) needUs = minOf(needUs, parameters.targetLiveOffsetUs * 8 / 10)
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return bufferedUs >= needUs
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val more = base.shouldContinueLoading(parameters) // keeps the memory limit
        if (isLive()) return more
        return more && parameters.bufferedDurationUs < settings().vodBufferMs * 1000L
    }
}
