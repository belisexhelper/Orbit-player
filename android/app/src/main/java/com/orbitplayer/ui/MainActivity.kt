package com.orbitplayer.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.orbitplayer.core.DeviceInfo
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
            if (isTv && vm.settings.value.startOnLastChannel) vm.playLastChannel(fullscreen = true)
        }
        lifecycleScope.launch { vm.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() } }
        // keep the screen on while something plays; phone full screen goes landscape and hides system bars
        lifecycleScope.launch {
            combine(vm.playerOpen, vm.playback.playing) { open, playing -> open to playing }.collect { (open, playing) ->
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isTv) setFullscreen(open)
            }
        }
        setContent {
            val settings by vm.settings.collectAsState()
            val playerOpen by vm.playerOpen.collectAsState()
            OrbitTheme(settings.theme, isTv, settings.fontScale) {
                Box(Modifier.fillMaxSize().background(Orbit.colors.background)) {
                    if (inPip.value) {
                        PlayerScreen(vm, inPip = true)
                    } else {
                        if (isTv) TvApp(vm, onExit = { finish() }) else MobileApp(vm, onExit = { finish() })
                        if (playerOpen) PlayerScreen(vm, inPip = false, onEnterPip = { enterPip() })
                    }
                }
            }
        }
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
