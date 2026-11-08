package com.orbitplayer.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.orbitplayer.App
import com.orbitplayer.core.DeviceInfo
import com.orbitplayer.takeLastCrash
import com.orbitplayer.ui.common.PlayerScreen
import com.orbitplayer.ui.mobile.MobileApp
import com.orbitplayer.ui.theme.Orbit
import com.orbitplayer.ui.theme.OrbitTheme
import com.orbitplayer.ui.tv.TvApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val inPip = mutableStateOf(false)
    private var isTv = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceInfo.isTv(this)
        if (!isTv) enableEdgeToEdge()
        val firstStart = savedInstanceState == null
        if (firstStart) {
            vm.startup()
            if (vm.settings.value.startOnLastChannel) vm.playLastChannelWhenReady(fullscreen = true)
        }
        // keep the screen on while something plays; phone full screen goes landscape and hides system bars
        lifecycleScope.launch {
            combine(vm.playerOpen, vm.playback.playing) { open, playing -> open to playing }.collect { (open, playing) ->
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isTv) setFullscreen(open)
            }
        }
        val lastCrash = (application as App).takeLastCrash()
        setContent {
            var crashText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(lastCrash) }
            val settings by vm.settings.collectAsState()
            val playerOpen by vm.playerOpen.collectAsState()
            OrbitTheme(settings.theme, isTv, settings.fontScale) {
                Box(Modifier.fillMaxSize().background(Orbit.colors.background)) {
                    if (inPip.value) {
                        PlayerScreen(vm, inPip = true)
                    } else {
                        if (isTv) TvApp(vm, onExit = { exitApp() }) else MobileApp(vm, onExit = { exitApp() })
                        if (playerOpen) PlayerScreen(vm, inPip = false, onEnterPip = { enterPip() })
                        com.orbitplayer.ui.components.MessageHost(vm.messages)
                        crashText?.let { CrashReport(it) { crashText = null } }
                    }
                }
            }
        }
    }

    /** Exit chosen by the user: stop playback and remove the app from the recent apps list */
    private fun exitApp() {
        vm.playback.stop()
        finishAndRemoveTask()
    }

    private fun setFullscreen(on: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun canPip(): Boolean =
        !isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature("android.software.picture_in_picture")

    fun enterPip() {
        if (!canPip() || vm.playback.item.value == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val live = vm.playback.item.value?.isLive == true
        val visible = vm.playerOpen.value || vm.route == Route.ChannelView || (vm.wideLayout && vm.route == Route.Live && live)
        if (vm.playback.playing.value && visible) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) vm.playerOpen.value = true
        // the small window was closed with its X: stop instead of playing on in the background
        else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.closePlayer(stop = true)
    }

    override fun onStop() {
        super.onStop()
        val pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!pip) {
            vm.playback.saveProgress()
            vm.playback.pause()
        }
    }

    override fun onDestroy() {
        // leaving the app for good: nothing should keep playing
        if (isFinishing) vm.playback.stop()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        vm.playback.resumeIfReady()
    }
}

/** Shown once after a crash: the error, so it can be photographed and reported */
@androidx.compose.runtime.Composable
private fun CrashReport(text: String, onClose: () -> Unit) {
    com.orbitplayer.ui.components.OrbitDialog(onClose, "Orbit Player closed unexpectedly last time") {
        androidx.compose.foundation.layout.Column {
            androidx.compose.material3.Text(
                "Please send a photo of this screen so the problem can be fixed.",
                color = Orbit.colors.textMuted,
            )
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.padding(vertical = 10.dp).heightIn(max = 300.dp),
            ) {
                item {
                    androidx.compose.material3.Text(
                        text.lines().filter { it.isNotBlank() }.take(28).joinToString("\n"),
                        color = Orbit.colors.text,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
            com.orbitplayer.ui.components.OrbitButton("Close", primary = true, focusRequester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }.also { fr ->
                androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { fr.requestFocus() } }
            }) { onClose() }
        }
    }
}
