package com.orbitplayer.playback

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
 *    connection is how much the player collects BEFORE it starts the picture (and again after a drop).
 *    That is the Live TV buffer: longer = the channel starts a little later, but plays through short drops.
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
        val more = super.shouldContinueLoading(parameters) // keeps the memory limit
        if (isLive()) return more
        return more && parameters.bufferedDurationUs < settings().vodBufferMs * 1000L
    }
}
