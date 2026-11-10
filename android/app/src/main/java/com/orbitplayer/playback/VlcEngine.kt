package com.orbitplayer.playback

import android.content.Context
import android.net.Uri
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.Net
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Second playback engine based on libVLC. It plays almost any container and codec in software,
 * so it is used when ExoPlayer can't play a stream (or always, if the user picks it).
 */
class VlcEngine(private val context: Context, private val events: Events) {
    interface Events {
        fun onPlaying(playing: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onReady()
        fun onEnded()
        fun onError()
        fun onTracks()
    }

    private var lib: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var attached: VLCVideoLayout? = null
    var hasMedia = false
        private set

    /** false when the native library can't load on this device */
    val available: Boolean get() = ensure() != null

    private fun ensure(): MediaPlayer? {
        mp?.let { return it }
        return try {
            val tv = com.orbitplayer.core.DeviceInfo.isTv(context)
            val l = LibVLC(context, arrayListOf(
                "--http-reconnect",
                "--audio-time-stretch",
                "--no-stats",
                "--avcodec-hw=any",
                "--avcodec-threads=0",
                // TV: full-quality picture (16-bit output shows banding on a big screen).
                // Phone: like the VLC app (skip the deblocking filter on non-reference frames), so software
                // decoding of HD series doesn't stutter; on a small screen it looks the same.
                "--android-display-chroma=RV32",
                "--avcodec-skiploopfilter=${if (tv) 0 else 1}",
                "--avcodec-hurry-up",
                "--drop-late-frames",
                "--skip-frames",
                "-v",
            ))
            val p = MediaPlayer(l)
            p.setEventListener { e -> onEvent(e) }
            lib = l
            mp = p
            p
        } catch (t: Throwable) {
            AppLog.w("VLC not available: ${t.message}")
            null
        }
    }

    private fun onEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                events.onPlaying(true)
                events.onBuffering(false)
                events.onReady()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> events.onPlaying(false)
            MediaPlayer.Event.Buffering -> {
                bufferPercent = e.buffering
                events.onBuffering(e.buffering < 100f)
            }
            MediaPlayer.Event.EndReached -> events.onEnded()
            MediaPlayer.Event.EncounteredError -> events.onError()
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> events.onTracks()
        }
    }

    /** last buffering progress reported by VLC (0–100) */
    @Volatile var bufferPercent: Float = 0f
        private set

    fun prepare(url: String, startMs: Long, subtitles: List<SubtitleSource>, hardware: Boolean, cachingMs: Int, vod: Boolean = false): Boolean {
        val p = ensure() ?: return false
        val l = lib ?: return false
        val media = Media(l, Uri.parse(url))
        media.setHWDecoderEnabled(hardware, false)
        // live: the Live TV buffer (how far behind live it plays = cushion for a bad connection);
        // movies, episodes, catch-up: the Movies & series buffer (VLC keeps at most 20 s, more only delays seeking)
        media.addOption(":network-caching=${if (vod) cachingMs.coerceIn(3_000, 20_000) else cachingMs.coerceIn(1_000, 30_000)}")
        media.addOption(":file-caching=3000")
        media.addOption(":http-user-agent=${Net.USER_AGENT}")
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        p.setMedia(media)
        media.release()
        subtitles.forEach { s -> runCatching { p.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(s.uri), true) } }
        p.play()
        hasMedia = true
        events.onBuffering(true)
        return true
    }

    /** Views that want the picture; the newest one shows it (full screen above a preview) */
    private val layouts = ArrayList<VLCVideoLayout>()

    fun attach(layout: VLCVideoLayout) {
        if (layouts.lastOrNull() !== layout) {
            layouts.remove(layout)
            layouts.add(layout)
        }
        show(layout)
    }

    fun detach(layout: VLCVideoLayout) {
        layouts.remove(layout)
        if (attached === layout) {
            runCatching { mp?.detachViews() }
            attached = null
            layouts.lastOrNull()?.let { show(it) }
        }
    }

    private fun show(layout: VLCVideoLayout) {
        val p = ensure() ?: return
        if (attached === layout) return
        if (attached != null) runCatching { p.detachViews() }
        runCatching { p.attachViews(layout, null, true, false) }
        attached = layout
    }

    private var scale: String? = null

    fun setScale(aspect: String) {
        if (scale == aspect && attached != null) return
        scale = aspect
        mp?.setVideoScale(when (aspect) {
            "fill" -> MediaPlayer.ScaleType.SURFACE_FILL
            "zoom" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        })
    }

    val isPlaying: Boolean get() = mp?.isPlaying == true
    fun play() { mp?.play() }
    fun pause() { mp?.pause() }

    fun stop() {
        hasMedia = false
        runCatching { mp?.stop() }
    }

    val positionMs: Long get() = mp?.time?.coerceAtLeast(0) ?: 0
    val durationMs: Long get() = mp?.length?.takeIf { it > 0 } ?: 0
    fun seekTo(ms: Long) { mp?.setTime(ms.coerceAtLeast(0)) }

    // ---- tracks ----
    fun audioTracks(): List<TrackOption> {
        val p = mp ?: return emptyList()
        val cur = p.audioTrack
        return (p.audioTracks ?: emptyArray()).filter { it.id >= 0 }.map { TrackOption(it.id, 0, it.name ?: "Track ${it.id}", "", it.id == cur) }
    }

    fun textTracks(): List<TrackOption> {
        val p = mp ?: return emptyList()
        val cur = p.spuTrack
        return (p.spuTracks ?: emptyArray()).filter { it.id >= 0 }.map { TrackOption(it.id, 0, it.name ?: "Subtitles ${it.id}", "", it.id == cur) }
    }

    val subtitlesOff: Boolean get() = (mp?.spuTrack ?: -1) < 0
    fun selectAudio(id: Int) { mp?.setAudioTrack(id) }
    fun selectText(id: Int) { mp?.setSpuTrack(id) }
    fun disableSubtitles() { mp?.setSpuTrack(-1) }
    fun addSubtitle(s: SubtitleSource) { runCatching { mp?.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(s.uri), true) } }

    fun videoSize(): Pair<Int, Int> {
        val t = runCatching { mp?.currentVideoTrack }.getOrNull() ?: return 0 to 0
        return t.width to t.height
    }
}
