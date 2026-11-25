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
                LauncherScreen(onOrbit = { startActivity(Intent(this, MainActivity::class.java)) })
            }
        }
    }
}

@Composable
private fun LauncherScreen(onOrbit: () -> Unit) {
    val c = Orbit.colors
    val ctx = LocalContext.current
    var tiles by remember { mutableStateOf(Launcher.tiles(ctx)) }
    var picking by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Text("Orbit", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = c.text)
            Spacer(Modifier.height(24.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(176.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    LauncherTile(label = "Live TV", icon = { Icon(Icons.Filled.LiveTv, null, Modifier.size(52.dp), tint = c.onAccent) }, bg = c.accent, onClick = onOrbit)
                }
                items(tiles, key = { it.pkg }) { t ->
                    LauncherTile(label = t.label, icon = { AppIcon(t.pkg, 52.dp) }, bg = c.surface, onClick = { Launcher.launch(ctx, t.pkg) })
                }
                item {
                    LauncherTile(label = "Add apps", icon = { Icon(Icons.Filled.Add, null, Modifier.size(52.dp), tint = c.textMuted) }, bg = c.surface, dashed = true, onClick = { picking = true })
                }
            }
        }
        DeviceIdBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
    }

    if (picking) AppPicker(
        selected = tiles.map { it.pkg }.toSet(),
        onToggle = { Launcher.toggleTile(ctx, it); tiles = Launcher.tiles(ctx) },
        onClose = { picking = false },
    )
}

@Composable
private fun LauncherTile(label: String, icon: @Composable () -> Unit, bg: Color, dashed: Boolean = false, onClick: () -> Unit) {
    val c = Orbit.colors
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(1.3f), color = bg, scaleOnFocus = 1.06f) { _ ->
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(14.dp))
            Text(label, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) { Launcher.appIcon(ctx, pkg)?.toBitmap(96, 96)?.asImageBitmap() }
    if (bmp != null) {
        androidx.compose.foundation.Image(bmp, null, Modifier.size(size))
    } else {
        Icon(Icons.Filled.LiveTv, null, Modifier.size(size), tint = Orbit.colors.textMuted)
    }
}

/** Full-screen list of installed apps; a check marks the ones shown as tiles. */
@Composable
private fun AppPicker(selected: Set<String>, onToggle: (AppTile) -> Unit, onClose: () -> Unit) {
    val c = Orbit.colors
    val ctx = LocalContext.current
    val apps = remember { Launcher.installedApps(ctx) }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)).padding(40.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose apps for the launcher", Modifier.weight(1f), color = c.text, style = MaterialTheme.typography.titleLarge)
                Focusable(onClick = onClose, color = c.surface) { _ -> Text("Done", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = c.text) }
            }
            Spacer(Modifier.height(16.dp))
            if (apps.isEmpty()) {
                Text("No other apps found on this device.", color = c.textMuted, style = MaterialTheme.typography.bodyLarge)
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                listItems(apps, key = { it.pkg }) { app ->
                    val on = app.pkg in selected
                    Focusable(onClick = { onToggle(app) }, modifier = Modifier.fillMaxWidth(), color = c.surface) { _ ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.pkg, 32.dp)
                            Spacer(Modifier.size(14.dp))
                            Text(app.label, Modifier.weight(1f), color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (on) Icon(Icons.Filled.Check, "Shown", tint = c.accent) else Text("", color = c.textMuted)
                        }
                    }
                }
            }
        }
    }
}
