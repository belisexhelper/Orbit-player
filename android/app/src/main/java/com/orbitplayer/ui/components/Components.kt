package com.orbitplayer.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.orbitplayer.ui.theme.LocalIsTv

/**
 * Osnovni element koji se može izabrati i daljinskim i dodirom.
 * Kada je u fokusu (TV), dobija okvir u boji akcenta i blago se uveća.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    background: Color = MaterialTheme.colorScheme.surface,
    focusedBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    scaleOnFocus: Boolean = true,
    focusRequester: FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && scaleOnFocus) 1.05f else 1f, label = "focus-scale")
    var m = modifier
    if (focusRequester != null) m = m.focusRequester(focusRequester)
    Box(
        m
            .scale(scale)
            .clip(shape)
            .background(if (focused) focusedBackground else background)
            .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) { content(focused) }
}

@Composable
fun Poster(title: String, image: String, modifier: Modifier = Modifier, subtitle: String = "", progress: Float = -1f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Focusable(onClick = onClick, onLongClick = onLongClick, modifier = modifier) { _ ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (image.isNotBlank()) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(title, Modifier.align(Alignment.Center).padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                }
                if (progress in 0f..1f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).height(4.dp))
                }
            }
            Text(title, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Velika pločica na početnom ekranu (Live TV, Filmovi, aplikacije...) */
@Composable
fun Tile(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, drawable: Drawable? = null, accent: Boolean = false, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    Focusable(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        background = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
    ) { _ ->
        Column(Modifier.padding(if (tv) 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(if (tv) 40.dp else 32.dp), tint = MaterialTheme.colorScheme.primary)
            if (drawable != null) {
                val bmp = remember(drawable) { runCatching { drawable.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                if (bmp != null) Image(bmp, null, Modifier.size(if (tv) 44.dp else 36.dp))
            }
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ActionButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Focusable(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(10.dp),
        background = if (enabled) bg else bg.copy(alpha = 0.4f),
        focusedBackground = if (primary) bg else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        scaleOnFocus = false,
    ) { _ ->
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Red u listi (kanal, podešavanje...) */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Focusable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        shape = RoundedCornerShape(10.dp),
        background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        scaleOnFocus = false,
    ) { _ ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (leading != null) leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun ChannelLogo(url: String, name: String, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
        else Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
fun Loading(text: String = "") {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        if (text.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, text: String = "", action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** QR kod (npr. adresa za uparivanje) */
@Composable
fun QrCode(text: String, size: Dp) {
    val bmp = remember(text) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300, hints)
            val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
            for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            b.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, contentDescription = "QR kod", modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)))
}

/** Numerička tastatura za TV (unos koda i PIN-a bez sistemske tastature) */
@Composable
fun NumericKeypad(onDigit: (Char) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null) {
    val rows = listOf("123", "456", "789")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { ri, r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEachIndexed { ci, c -> Key(c.toString(), if (ri == 0 && ci == 0) firstFocus else null) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(64.dp))
            Key("0", null) { onDigit('0') }
            Focusable(onClick = onDelete, modifier = Modifier.size(64.dp, 56.dp), scaleOnFocus = false) { _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Backspace, "Obriši") }
            }
        }
    }
}

@Composable
private fun Key(label: String, fr: FocusRequester?, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.size(64.dp, 56.dp), scaleOnFocus = false, focusRequester = fr) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Prikaz koda u grupama od po 4 cifre, kao na prednjoj ploči prijemnika */
@Composable
fun CodeDisplay(code: String, length: Int = 8) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until length) {
            if (i == 4) Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF121924)),
                contentAlignment = Alignment.Center,
            ) {
                Text(code.getOrNull(i)?.toString() ?: "", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF2B33D))
            }
        }
    }
}

val ContentPadding = PaddingValues(16.dp)
