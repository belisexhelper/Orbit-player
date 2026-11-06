package com.orbitplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbitplayer.diag.DiagItem
import com.orbitplayer.diag.Diagnostics
import com.orbitplayer.diag.SpeedResult
import com.orbitplayer.diag.SpeedTest
import com.orbitplayer.ui.MainViewModel
import com.orbitplayer.ui.ScreenHeader
import com.orbitplayer.ui.components.ActionButton
import com.orbitplayer.ui.theme.LocalIsTv
import com.orbitplayer.ui.theme.OrbitColors
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val tv = LocalIsTv.current
    var diagItems by remember { mutableStateOf<List<DiagItem>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        running = true
        diagItems = Diagnostics.items(vm.app) { step = it }
        running = false
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Dijagnostika", vm) { ActionButton("Ponovi", enabled = !running) { run++ } }
        if (running) Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Text(step, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = if (tv) 32.dp else 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(diagItems) { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (d.ok) {
                        true -> Icons.Filled.CheckCircle to OrbitColors.Ok
                        false -> Icons.Filled.Error to OrbitColors.Bad
                        null -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, null, tint = tint)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(d.title, style = MaterialTheme.typography.bodyLarge)
                        Text(d.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!running && diagItems.isNotEmpty()) item {
                Text("Ako nešto nije u redu, pošaljite ovaj ekran podršci. Admin može da pokrene istu proveru i iz panela.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
fun SpeedTestScreen() {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("idle") }
    var speed by remember { mutableDoubleStateOf(0.0) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    fun start() {
        result = null
        speed = 0.0
        scope.launch {
            val r = SpeedTest.run { p, mbps -> phase = p; speed = mbps }
            result = r
            phase = "done"
        }
    }
    LaunchedEffect(Unit) { start() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Test brzine") { ActionButton("Ponovi", enabled = phase == "done" || phase == "idle") { start() } }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                // skala do 100 Mb/s (log-skala za prikaz)
                val frac = (kotlin.math.ln(1 + speed) / kotlin.math.ln(101.0)).toFloat().coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 18.dp.toPx()
                    val d = size.minDimension - stroke
                    val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                    drawArc(track, 135f, 270f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(primary, 135f, 270f * frac, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.1f".format(result?.downloadMbps ?: speed), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text("Mb/s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                when (phase) {
                    "ping" -> "Merim odziv…"
                    "download" -> "Merim brzinu preuzimanja…"
                    else -> ""
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result?.let { r ->
                if (r.error.isNotBlank()) Text("Test nije uspeo: ${r.error}", color = MaterialTheme.colorScheme.error)
                else {
                    Text("Ping ${r.pingMs} ms · Jitter ${r.jitterMs} ms", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            r.downloadMbps >= 25 -> "Odlično: dovoljno za 4K."
                            r.downloadMbps >= 12 -> "Dobro: dovoljno za Full HD."
                            r.downloadMbps >= 5 -> "Dovoljno za HD, 4K može da zastajkuje."
                            else -> "Sporo: moguća zastajkivanja. Probajte kabl umesto Wi-Fi-ja."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
