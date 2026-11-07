package com.orbitplayer.ui.common

import android.graphics.Color as AColor
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.orbitplayer.core.AppSettings
import com.orbitplayer.playback.PlaybackManager

fun resizeModeOf(aspect: String): Int = when (aspect) {
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** Video picture of the shared player. Controls are drawn in Compose on top of it. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(playback: PlaybackManager, settings: AppSettings, aspect: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(AColor.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
                player = playback.player
                styleSubtitles(this, settings)
            }
        },
        update = { v ->
            if (v.player !== playback.player) v.player = playback.player
            v.resizeMode = resizeModeOf(aspect)
            styleSubtitles(v, settings)
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        onRelease = { v -> v.player = null },
        modifier = modifier,
    )
}

@OptIn(UnstableApi::class)
private fun styleSubtitles(v: PlayerView, s: AppSettings) {
    val sv = v.subtitleView ?: return
    sv.setStyle(
        CaptionStyleCompat(
            AColor.WHITE,
            if (s.subtitleBackground) AColor.argb(170, 0, 0, 0) else AColor.TRANSPARENT,
            AColor.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            AColor.BLACK,
            null,
        ),
    )
    val f = when (s.subtitleSize) {
        "small" -> 0.8f
        "large" -> 1.35f
        else -> 1.05f
    }
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * f)
}
