package com.orbitplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.orbitplayer.core.AppSettings
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.ui.ChannelGroup
import com.orbitplayer.ui.NowNext
import com.orbitplayer.ui.common.EpgGrid
import com.orbitplayer.ui.common.guideStart
import com.orbitplayer.ui.mobile.MobileChannelRow
import com.orbitplayer.ui.mobile.MobileHomeContent
import com.orbitplayer.ui.theme.Orbit
import com.orbitplayer.ui.theme.OrbitTheme
import com.orbitplayer.ui.tv.TvLiveContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val NAMES = listOf("Orbit News", "Planet Sport 1", "Planet Sport 2", "Cinema One", "Cinema Action", "Kids World", "Music Hits", "Nature HD", "History Plus", "Comedy Central Point", "Travel Now", "Weather 24")
private val SHOWS = listOf("Morning briefing", "Champions highlights", "Tennis: quarter final", "The long road home", "Night pursuit", "Cartoon hour", "Top 40 countdown", "Wild oceans", "Ancient empires", "Stand-up special", "City breaks", "Forecast")

private val now = System.currentTimeMillis()
private val hour = 3_600_000L
private val base = now - now % (30 * 60_000L)

private val channels = NAMES.mapIndexed { i, n ->
    ChannelEntity(key = "p:$i", playlistId = "p", streamId = "$i", num = i + 1, name = n, logo = "", categoryId = "c", url = "", epgKey = "p:e$i", archive = i % 3 == 0, archiveDays = 3)
}

private fun prog(i: Int, start: Long, len: Long, title: String) = ProgramEntity(id = i.toLong() * 100 + start / 60_000 % 100, epgKey = "p:e$i", playlistId = "p", start = start, stop = start + len, title = title, description = "")

private val nowNext = channels.mapIndexed { i, ch ->
    val s = base - (i % 4) * 15 * 60_000L
    ch.epgKey to NowNext(prog(i, s, hour, SHOWS[i]), prog(i, s + hour, hour, SHOWS[(i + 3) % SHOWS.size]))
}.toMap()

private val groups = listOf(
    ChannelGroup("fav", "Favorites", 3, special = true),
    ChannelGroup("recent", "Recently watched", 5, special = true),
    ChannelGroup("all", "All channels", 1240, special = true),
    ChannelGroup("c1", "News", 42), ChannelGroup("c2", "Sport", 118), ChannelGroup("c3", "Movies", 96),
    ChannelGroup("c4", "Kids", 31), ChannelGroup("c5", "Music", 25), ChannelGroup("c6", "Documentary", 58), ChannelGroup("c7", "Entertainment", 77),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun tv(name: String, content: @Composable () -> Unit) {
        compose.setContent { OrbitTheme("slate", isTv = true) { Box(Modifier.fillMaxSize().background(Orbit.colors.background)) { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun phone(name: String, content: @Composable () -> Unit) {
        compose.setContent { OrbitTheme("slate", isTv = false) { Box(Modifier.fillMaxSize().background(Orbit.colors.background)) { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun tvLive() = tv("tv-live") {
        Row {
            Box(Modifier.width(76.dp).fillMaxHeight().background(Orbit.colors.surface))
            TvLiveContent(
                groups = groups, selectedGroup = "c2", channels = channels, nowNext = nowNext, favorites = setOf("p:1"),
                settings = AppSettings(), playingKey = "p:1", focused = channels[1], loadingText = "", refocusKey = null,
                displayName = { it.name }, onGroupFocus = {}, onGroupLongPress = {}, onChannelFocus = {}, onChannelClick = {}, onChannelLongPress = {}, preview = null,
            )
        }
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun tvGuide() = tv("tv-guide") {
        Row {
            Box(Modifier.width(76.dp).fillMaxHeight().background(Orbit.colors.surface))
            EpgGrid(
                channels = channels, windowStart = guideStart(), visibleMinutes = 120, displayName = { it.name },
                loadPrograms = { ch, _, _ -> val i = ch.streamId.toInt(); val s = base - (i % 4) * 15 * 60_000L - hour; (0 until 5).map { k -> prog(i, s + k * hour, hour, SHOWS[(i + k) % SHOWS.size]) } },
                onWindowChange = {}, canGoBack = { true }, onProgramFocus = { _, _ -> }, onProgramClick = { _, _ -> }, onProgramLongClick = { _, _ -> }, onChannelClick = {},
            )
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneHome() = phone("phone-home") {
        Column {
            Box(Modifier.weight(1f)) {
                MobileHomeContent("My IPTV", now + 120 * 24 * hour, Triple(1240, 8600, 1450), "", false, {}, {}, {}) {}
            }
            FakeBar(0)
        }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLive() = phone("phone-live") {
        Column {
            LazyColumn(Modifier.weight(1f)) {
                items(channels) { ch -> MobileChannelRow(ch, ch.name, nowNext[ch.epgKey], true, ch.key == "p:1", ch.key == "p:2", {}) {} }
            }
            FakeBar(1)
        }
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun phoneHomeLandscape() = phone("phone-home-land") {
        MobileHomeContent("My IPTV", 0, Triple(1240, 8600, 1450), "Loading TV guide…", true, {}, {}, {}) {}
    }
}

@Composable
private fun FakeBar(sel: Int) {
    val items = listOf("Home" to Icons.Filled.Home, "Live" to Icons.Filled.LiveTv, "Guide" to Icons.Filled.DateRange, "Movies" to Icons.Filled.Movie, "Series" to Icons.Filled.VideoLibrary)
    NavigationBar(containerColor = Orbit.colors.surface) {
        items.forEachIndexed { i, (l, ic) ->
            NavigationBarItem(selected = i == sel, onClick = {}, icon = { Icon(ic, null) }, label = { Text(l) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Orbit.colors.onAccent, indicatorColor = Orbit.colors.accent, unselectedIconColor = Orbit.colors.textMuted, unselectedTextColor = Orbit.colors.textMuted, selectedTextColor = Orbit.colors.text))
        }
    }
}
