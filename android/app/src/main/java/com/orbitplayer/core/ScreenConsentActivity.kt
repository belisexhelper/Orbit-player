package com.orbitplayer.core

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Transparent one-shot activity that asks the system for screen-capture permission, then hands the
 * grant to [ScreenCaptureService]. Needed because MediaProjection can only be obtained via a user
 * consent result. A visible indicator is shown by the service for the whole viewing session.
 */
class ScreenConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }.onFailure { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("resultData", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        }
        finish()
    }

    companion object {
        private const val REQ = 7001
    }
}
