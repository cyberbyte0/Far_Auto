package com.fareed.auto

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Transparent, no-UI activity whose only job is to request the MediaProjection
 * screen-capture consent dialog. MediaProjection consent can only be requested
 * from an Activity, so the "Enable Screen Recording" button (and any future
 * dashboard trigger) routes through here. On grant, the consent token is handed
 * to [ScreenRecordService], which holds the projection alive for the session.
 *
 * Extends plain [Activity] (not AppCompatActivity) so it can use a translucent,
 * non-AppCompat theme without crashing.
 */
class ScreenRecordPermissionActivity : Activity() {

    private val requestCode = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mpManager.createScreenCaptureIntent(), requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture unavailable on this device", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onActivityResult(rc: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(rc, resultCode, data)
        if (rc == requestCode) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Hand the consent token to the foreground service that owns the projection.
                val intent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START_PROJECTION
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Screen recording enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
