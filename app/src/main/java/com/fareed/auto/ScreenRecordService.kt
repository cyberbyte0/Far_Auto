package com.fareed.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Foreground service (type mediaProjection) that owns the screen-capture session.
 *
 * Lifecycle:
 *  - ACTION_START_PROJECTION (from [ScreenRecordPermissionActivity]) creates and
 *    holds a [MediaProjection]. It is kept alive for the whole session so multiple
 *    recordings can start/stop without re-prompting for consent.
 *  - startRecording()/stopRecording() spin a [ScreenRecorder] (H264 video + AAC
 *    internal audio, muxed to MP4) up and down against that single projection.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START_PROJECTION = "com.fareed.auto.START_PROJECTION"
        const val ACTION_STOP_SERVICE = "com.fareed.auto.STOP_RECORD_SERVICE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 2

        @Volatile
        var instance: ScreenRecordService? = null
            private set

        /** True once consent is granted and the projection is live. */
        fun isReady(): Boolean = instance?.mediaProjection != null

        /** True while a recording is actively being written. */
        fun isRecording(): Boolean = instance?.recording == true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var recorder: ScreenRecorder? = null
    private var recording = false
    private var currentPath: String? = null
    private var dispW = 0
    private var dispH = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System or user revoked the projection — tear everything down.
            stopRecordingInternal()
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            mediaProjection = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> startProjection(intent)
            ACTION_STOP_SERVICE -> {
                stopRecordingInternal()
                releaseProjection()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        // The mediaProjection FGS must be foregrounded before getMediaProjection() on API 34+.
        val notif = buildNotification("Screen recording ready")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Log.e("FarAuto", "Screen record: missing projection token")
            stopSelf()
            return
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e("FarAuto", "Screen record: getMediaProjection returned null")
            stopSelf()
            return
        }
        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection

        // Android 14+ allows createVirtualDisplay only ONCE per projection. Create it
        // here (with no surface yet) and reuse it for every recording by swapping the
        // surface via VirtualDisplay.setSurface().
        val (w, h, dpi) = screenMetrics()
        dispW = w
        dispH = h
        virtualDisplay = projection.createVirtualDisplay(
            "FarAutoRecord", w, h, dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, handler,
        )
        Log.i("FarAuto", "Screen record: projection + virtual display ready (${w}x$h)")
    }

    /** Starts a recording to [filename] inside the recordings dir. Runs on the main thread. */
    fun startRecording(filename: String): Boolean {
        val projection = mediaProjection ?: return false
        val display = virtualDisplay ?: return false
        if (recording) return false
        try {
            val dir = MainActivity.getStorageDir(this, "recordings")
            val safeName = if (filename.endsWith(".mp4")) filename else "$filename.mp4"
            val path = File(dir, safeName).absolutePath

            // Internal audio is best-effort and only attempted if RECORD_AUDIO was granted.
            val withAudio = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            // Reuse the single VirtualDisplay (dims fixed at consent); recorder just
            // attaches its encoder surface to it.
            val rec = ScreenRecorder(projection, display, dispW, dispH, path, withAudio)
            if (!rec.start()) return false
            recorder = rec
            recording = true
            currentPath = path
            Log.i("FarAuto", "Screen record: started -> $path")
            return true
        } catch (e: Exception) {
            Log.e("FarAuto", "Screen record start failed", e)
            stopRecordingInternal()
            return false
        }
    }

    /** Stops the active recording and returns the saved file path (keeps projection alive). */
    fun stopRecording(): String? {
        val path = currentPath
        return if (stopRecordingInternal()) path else null
    }

    private fun stopRecordingInternal(): Boolean {
        if (!recording) return false
        return try {
            recorder?.stop()
            true
        } catch (e: Exception) {
            Log.e("FarAuto", "Screen record stop failed", e)
            false
        } finally {
            recorder = null
            recording = false
        }
    }

    private fun releaseProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi = resources.configuration.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            // H264 requires even dimensions.
            Triple(bounds.width() and 1.inv(), bounds.height() and 1.inv(), dpi)
        } else {
            val dm = resources.displayMetrics
            Triple(dm.widthPixels and 1.inv(), dm.heightPixels and 1.inv(), dpi)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Far_Auto Screen Recorder")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Recording", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopRecordingInternal()
        releaseProjection()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
