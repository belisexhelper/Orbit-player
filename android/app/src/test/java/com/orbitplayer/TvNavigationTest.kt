package com.orbitplayer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.orbitplayer.data.db.CategoryEntity
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.PlaylistEntity
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import com.orbitplayer.ui.AppViewModel
import com.orbitplayer.ui.theme.OrbitTheme
import com.orbitplayer.ui.tv.TvApp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the TV interface with remote-control keys and records where the focus is after every key
 * (text log + screenshot), so focus problems can be seen without a TV.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = App::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class TvNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/outputs/roborazzi").apply { mkdirs() }
    private val log = StringBuilder()
    private var step = 0

    private fun seed(app: App) = runBlocking {
        val now = System.currentTimeMillis()
        app.db.playlists().upsert(PlaylistEntity("p", "Test", "m3u", url = "http://example.invalid/list.m3u", lastSyncAt = now, lastEpgAt = now, liveCount = 40, vodCount = 60, seriesCount = 20))
        val groups = listOf("News", "Sport", "Movies", "Kids", "Music")
        app.db.content().insertCategories(
            groups.mapIndexed { i, g -> CategoryEntity("p:live:c$i", "p", "live", "c$i", g, i) } +
                listOf("Action", "Comedy", "Drama").mapIndexed { i, g -> CategoryEntity("p:vod:v$i", "p", "vod", "v$i", g, i) } +
                listOf("Crime", "Family").mapIndexed { i, g -> CategoryEntity("p:series:s$i", "p", "series", "s$i", g, i) },
        )
        app.db.content().insertChannels((0 until 40).map { i ->
            ChannelEntity("p:$i", "p", "$i", i + 1, "${groups[i % 5]} channel ${i + 1}", "", "c${i % 5}", "http://example.invalid/$i.ts", "p:e$i", archive = i % 4 == 0, archiveDays = 3)
        })
        app.db.content().insertVod((0 until 60).map { i ->
            VodEntity(key = "p:v$i", playlistId = "p", streamId = "$i", name = "Movie number $i", poster = "", categoryId = "v${i % 3}", ext = "mkv", url = "", rating = "${(i % 9) + 1}.5", added = now / 1000 - i * 3600)
        })
        app.db.content().insertSeries((0 until 20).map { i ->
            SeriesEntity(key = "p:s$i", playlistId = "p", seriesId = "$i", name = "Series number $i", cover = "", categoryId = "s${i % 2}", plot = "", rating = "7.${i % 10}", added = now / 1000 - i * 7200)
        })
        app.store.activePlaylistId = "p"
    }

    private fun focusedText(): String = runCatching {
        val n = compose.onNode(isFocused()).fetchSemanticsNode()
        val texts = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text }
        val desc = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")
        "${texts ?: desc ?: "(no text)"} @ ${n.boundsInRoot}"
    }.getOrElse { "NOTHING FOCUSED (${it.javaClass.simpleName})" }

    private fun settle(ms: Long = 600) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun shot(label: String) {
        step++
        val f = focusedText()
        log.append(String.format("%02d %-28s -> %s\n", step, label, f))
        compose.onRoot().captureRoboImage(File(out, String.format("nav-%02d-%s.png", step, label.replace(Regex("[^a-zA-Z0-9]+"), "-"))).path)
    }

    private fun key(k: Key, label: String, times: Int = 1) {
        repeat(times) {
            val target = runCatching { compose.onNode(isFocused()) .also { it.fetchSemanticsNode() } }.getOrNull()
            if (target == null) {
                log.append("   (no focused node, key ${label} not sent)\n")
                return@repeat
            }
            target.performKeyInput { pressKey(k) }
            settle(400)
        }
        shot(label)
    }

    @Test
    fun walkThroughTv() {
        val app = ApplicationProvider.getApplicationContext<App>()
        seed(app)
        val vm = AppViewModel(app)
        var crash: Throwable? = null
        try {
            compose.setContent { OrbitTheme("slate", isTv = true) { TvApp(vm, onExit = {}) } }
            settle(2500)
            shot("start")
            if (focusedText().startsWith("NOTHING")) {
                log.append("   !! nothing focused at start, waiting for the safety net\n")
                settle(1500)
                shot("start after wait")
            }
            if (focusedText().startsWith("NOTHING")) {
                log.append("   !! still nothing focused, focusing the first channel from the test\n")
                runCatching { compose.onNode(androidx.compose.ui.test.hasText("News channel 1") and androidx.compose.ui.test.hasClickAction()).requestFocus() }
                    .onFailure { log.append("   requestFocus failed: ${it.message?.take(200)}\n") }
                settle(500)
                shot("start forced")
            }
            key(Key.DirectionDown, "down x2", 2)
            key(Key.DirectionLeft, "left to groups")
            key(Key.DirectionLeft, "left to menu")
            key(Key.DirectionDown, "menu down")
            key(Key.DirectionUp, "menu up")
            key(Key.DirectionRight, "right into screen")
            key(Key.DirectionDown, "down in screen")
            key(Key.DirectionLeft, "left")
            key(Key.DirectionLeft, "left to menu 2")
            key(Key.DirectionDown, "menu to Movies", 2)
            key(Key.DirectionCenter, "open Movies")
            settle(1500)
            shot("movies loaded")
            key(Key.DirectionRight, "right in movies")
            key(Key.DirectionDown, "down in movies")
            key(Key.DirectionRight, "right in grid")
            key(Key.DirectionUp, "up to toolbar", 3)
            key(Key.DirectionLeft, "left in movies", 6)
            key(Key.DirectionDown, "menu to Series")
            key(Key.DirectionCenter, "open Series")
            settle(1500)
            shot("series loaded")
            key(Key.DirectionLeft, "series to menu", 3)
            key(Key.DirectionDown, "menu to Settings", 3)
            key(Key.DirectionCenter, "open Settings")
            settle(800)
            shot("settings loaded")
            key(Key.DirectionDown, "settings down", 3)
            key(Key.DirectionRight, "into options")
            key(Key.DirectionDown, "options down", 2)
            key(Key.DirectionRight, "right again")
            key(Key.DirectionRight, "right again 2")
            key(Key.DirectionLeft, "back to sections")
            key(Key.DirectionLeft, "back to menu")
            key(Key.DirectionRight, "menu right")
        } catch (t: Throwable) {
            crash = t
            log.append("\nCRASH: ").append(t.stackTraceToString().lines().take(40).joinToString("\n"))
        } finally {
            File(out, "nav-log.txt").writeText(log.toString())
        }
        crash?.let { throw it }
    }
}
