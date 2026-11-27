package com.orbitplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orbitplayer.core.Banner
import kotlinx.coroutines.delay

/** Wraps a screen and overlays the controllable message bar from the panel at the very top. */
@Composable
fun BannerHost(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val data by Banner.state.collectAsState()

    LaunchedEffect(data?.expiresAt) {
        val exp = data?.expiresAt ?: 0L
        while (exp != 0L) {
            delay(1000)
            Banner.pruneExpired(ctx)
            if (Banner.state.value?.expiresAt != exp) break
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()
        val d = data
        AnimatedVisibility(
            visible = d != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val shown = d ?: Banner.Data("", "info", 0)
            val bg = when (shown.severity) {
                "urgent" -> Color(0xFFDC2626)
                "warn" -> Color(0xFFD97706)
                else -> Color(0xFF2563EB)
            }
            Row(
                Modifier.fillMaxWidth().background(bg).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shown.text, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
