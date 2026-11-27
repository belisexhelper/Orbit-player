package com.orbitplayer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.orbitplayer.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Captures the screen and uploads ~1 frame/sec to the panel while a viewing session is active.
 * A visible on-screen indicator ("Ekran se gleda") is shown the whole time — on every kiosk level.
 * If the indicator cannot be shown, capturing does not start (no silent viewing, by design).
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TextView? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())

        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("resultData")
        if (code == 0 || data == null) { stopAll(); return START_NOT_STICKY }

        // The indicator must be visible; if we can't show it, we do not capture.
        if (!showIndicator()) {
            AppLog.w("Screen view: cannot show the on-screen indicator — not capturing")
            stopAll()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (projection == null) { stopAll(); return START_NOT_STICKY }
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, null)

        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val maxW = 720
        val scale = if (metrics.widthPixels > maxW) maxW.toFloat() / metrics.widthPixels else 1f
        val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
        val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
        val dpi = metrics.densityDpi.coerceAtLeast(96)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vdisplay = projection?.createVirtualDisplay(
            "orbit-screen", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null,
        )
        running = true
        val app = applicationContext
        job = scope.launch {
            while (running) {
                delay(1100)
                val jpeg = runCatching { grabJpeg(w) }.getOrNull() ?: continue
                runCatching { ServerClient.uploadFrame(app, jpeg) }
            }
        }
    }

    private fun grabJpeg(targetW: Int): ByteArray? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 45, out)
            bmp.recycle(); cropped.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun showIndicator(): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return false
        return runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = TextView(this).apply {
                text = "  ●  Ekran se gleda  "
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xE6DC2626.toInt())
                textSize = 13f
                setPadding(20, 8, 20, 8)
            }
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 16 }
            wm.addView(v, lp)
            overlay = v
            true
        }.getOrDefault(false)
    }

    private fun stopAll() {
        running = false
        job?.cancel(); job = null
        runCatching { vdisplay?.release() }; vdisplay = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        overlay?.let { v -> runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } }
        overlay = null
        stopForeground(if (Build.VERSION.SDK_INT >= 24) STOP_FOREGROUND_REMOVE else 0)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        job?.cancel()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Pregled ekrana", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Ekran se gleda").setContentText("Daljinski pregled je aktivan")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "com.orbitplayer.SCREEN_STOP"
        private const val NOTIF_ID = 42
        private const val CHANNEL = "orbit_screen"

        fun start(ctx: Context) {
            val i = Intent(ctx, ScreenConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
    }
}
