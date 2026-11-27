package com.orbitplayer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.orbitplayer.core.AppTile
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.core.Launcher
import com.orbitplayer.ui.components.DeviceIdBadge
import com.orbitplayer.ui.components.Focusable
import com.orbitplayer.ui.theme.Orbit
import com.orbitplayer.ui.theme.OrbitTheme

/** The Orbit home screen (launcher): a grid of app tiles. Registered as HOME in the manifest. */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTv = DeviceInfo.isTv(this)
        val theme = runCatching {
            org.json.JSONObject(getSharedPreferences("orbit", MODE_PRIVATE).getString("settings", "{}") ?: "{}").optString("theme", "slate")
        }.getOrDefault("slate")
        setContent {
            OrbitTheme(theme, isTv) {
                com.orbitplayer.ui.components.BannerHost {
                    LauncherScreen(onOrbit = { startActivity(Intent(this, MainActivity::class.java)) })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.orbitplayer.core.DeviceAgent.reportForeground("launcher")
        // Enforce kiosk (lock task / pinning) to the degree the device allows.
        com.orbitplayer.core.Kiosk.apply(this)
    }
}

@Composable
private fun LauncherScreen(onOrbit: () -> Unit) {
    val c = Orbit.colors
    val ctx = LocalContext.current
    // tiles, title and look all come from the panel (device config), never edited on the device
    val tiles = Launcher.tiles(ctx)
    val title = Launcher.title(ctx)
    val scale = Launcher.iconScale(ctx)
    val accent = Launcher.accentColor(ctx)?.let { Color(it) } ?: c.accent
    val tileW = (150 * scale).dp
    val iconSize = (46 * scale).dp

    // A calm dark backdrop that reads well on any TV, independent of the app theme.
    val bg = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0E1116), Color(0xFF151A22)))
    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp)) {
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = c.text)
                Spacer(Modifier.height(22.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(tileW),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    LauncherTile(
                        label = "Live TV",
                        icon = { Icon(Icons.Filled.LiveTv, null, Modifier.size(iconSize), tint = accent) },
                        onClick = onOrbit,
                    )
                }
                items(tiles, key = { it.pkg }) { t ->
                    LauncherTile(
                        label = t.label,
                        icon = { AppIcon(t.pkg, t.label, iconSize) },
                        onClick = { Launcher.launch(ctx, t.pkg) },
                    )
                }
            }
        }
        DeviceIdBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
    }
}

@Composable
private fun LauncherTile(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    // Soft raised card; the focused one gets an accent ring (handled by Focusable) and a slight lift.
    val base = Color(0xFF1C222C)
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(1.25f), color = base, scaleOnFocus = 1.04f) { _ ->
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(14.dp))
            Text(label, color = Color(0xFFE7ECF3), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppIcon(pkg: String, label: String, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx().toInt().coerceIn(48, 192) }
    val bmp = remember(pkg, px) { Launcher.appIcon(ctx, pkg)?.toBitmap(px, px)?.asImageBitmap() }
    if (bmp != null) {
        androidx.compose.foundation.Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(10.dp)))
    } else {
        // Neutral placeholder with the app's first letter, instead of a blank white square.
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A313C)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.trim().take(1).uppercase().ifBlank { "?" },
                color = Color(0xFF9FB0C3), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
