package com.fareed.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScriptExecutionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var server: DashboardServer? = null

    companion object {
        const val CHANNEL_ID = "ScriptExecutionChannel"
        const val NOTIFICATION_ID = 1
        val isRunning = AtomicBoolean(false)
        @Volatile var currentScriptPath: String? = null
        @Volatile var pythonInputBuffer: String? = null
        val executionSessionId = AtomicInteger(0)
        private var activeThread: Thread? = null
        private val logBuffer = StringBuilder()

        fun clearLogs() {
            synchronized(logBuffer) {
                logBuffer.setLength(0)
            }
        }

        fun getLogs(): String {
            synchronized(logBuffer) {
                return logBuffer.toString()
            }
        }

        fun injectLog(message: String) {
            synchronized(logBuffer) {
                logBuffer.append(message).append("\n")
            }
        }

        fun stopScript() {
            isRunning.set(false)
            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    py.getModule("automator").put("last_stopped_session", executionSessionId.get())
                }
            } catch (e: Exception) {}
            activeThread?.interrupt()
            activeThread = null
        }

        private var lastNotifTime: Long = 0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServer()
    }

    private fun startServer() {
        syncToken() // Ensure token is fresh
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("dashboard_enabled", true)) return
        
        val port = prefs.getInt("dashboard_port", 8080)
        server = DashboardServer(this, port)
        try {
            server?.start()
            if (BuildConfig.DEBUG) Log.i("FarAuto", "Dashboard Server started on port $port")
        } catch (e: Exception) {
            Log.e("FarAuto", "Failed to start server", e)
        }
    }

    private fun syncToken() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val customToken = prefs.getString("dashboard_token", "")?.trim()
        if (!customToken.isNullOrEmpty()) {
            DashboardServer.authToken = customToken
        } else if (DashboardServer.authToken.isEmpty()) {
            DashboardServer.authToken = DashboardServer.generateToken()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle Server Restart Request
        if (intent?.action == "com.fareed.auto.RESTART_SERVER") {
            server?.stop()
            syncToken()
            startServer()
            val notif = createNotification("Dashboard Restarted")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            return START_STICKY
        }

        // Android 14 requirement: Show notification immediately
        val initialNotif = createNotification("Service Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        val scriptPath = intent?.getStringExtra("SCRIPT_PATH")
        if (scriptPath == null) return START_STICKY

        // Kill existing script if running
        if (isRunning.get()) {
            killCurrentScript()
        }

        currentScriptPath = scriptPath
        pythonInputBuffer = null
        executionSessionId.incrementAndGet()
        
        acquireWakeLock()
        updateNotification("Initializing: ${scriptPath.substringAfterLast("/")}")
        
        isRunning.set(true)
        val thread = Thread {
            try {
                runScript(scriptPath)
            } finally {
                isRunning.set(false)
                releaseWakeLock()
                updateNotification("Script Idle")
            }
        }
        activeThread = thread
        thread.start()

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FarAuto::ScriptWakeLock")
        wakeLock?.acquire(8 * 60 * 60 * 1000L) // 8-hour ceiling; always released in finally when script ends
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onDestroy() {
        server?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun killCurrentScript() {
        isRunning.set(false)
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                py.getModule("automator").put("last_stopped_session", executionSessionId.get())
            }
        } catch (e: Exception) {}
        activeThread?.interrupt()
        activeThread = null
    }

    private fun runScript(path: String) {
        val scriptFile = java.io.File(path)
        clearLogs()

        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

        // Per-run log file: <script_name>_<yyyy-MM-dd_HH-mm-ss>.txt in the logs folder
        var logFile: File? = null
        var logWriter: java.io.BufferedWriter? = null
        try {
            val logsDir = MainActivity.getStorageDir(this, "logs")
            pruneOldLogs(logsDir)
            val runStamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            logFile = File(logsDir, "${scriptFile.name.removeSuffix(".py")}_$runStamp.txt")
            logWriter = logFile.bufferedWriter()
        } catch (e: Exception) {
            Log.e("FarAuto", "Could not create run log file", e)
        }

        fun log(message: String) {
            val timestamp = sdf.format(java.util.Date())
            val formattedMessage = "[$timestamp] $message"

            if (BuildConfig.DEBUG) Log.d("FarAuto", formattedMessage)
            synchronized(logBuffer) {
                logBuffer.append(formattedMessage).append("\n")
                if (logBuffer.length > 100_000) {
                    logBuffer.delete(0, 20_000)
                }
                try {
                    logWriter?.apply {
                        append(formattedMessage).append("\n")
                        flush() // Flush per line so the log survives a kill mid-run
                    }
                } catch (e: Exception) {
                    Log.e("FarAuto", "Run log write failed", e)
                }
            }

            updateNotification(message)
        }

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val py = Python.getInstance()
            
            val loggerUtil = py.getModule("logger_util")
            loggerUtil.callAttr("setup_logger", 
                { msg: String -> log(msg) },
                { 
                    val input = pythonInputBuffer
                    pythonInputBuffer = null
                    input
                }
            )
            
            log("Starting script: ${scriptFile.name}")

            val automatorModule = py.getModule("automator")
            automatorModule?.put("current_session", executionSessionId.get())
            val automatorBridge = AutomatorBridge()
            automatorBridge.setToastPackageFilter(null) // Filters do not persist across runs
            automatorModule?.put("bridge", automatorBridge)
            automatorModule?.put("scripts_dir", MainActivity.getStorageDir(this, "scripts").absolutePath)
            automatorModule?.put("files_dir", MainActivity.getStorageDir(this, "files").absolutePath)

            // exec() automatically inserts __builtins__ into freshGlobals when absent.
            // __name__ must be set so `if __name__ == "__main__":` blocks execute correctly.
            val builtins = py.getModule("builtins")!!
            val freshGlobals = builtins.callAttr("dict")
            freshGlobals.callAttr("__setitem__", "__name__", "__main__")
            builtins.callAttr("exec", scriptFile.readText(), freshGlobals)
            log("Script finished successfully")
        } catch (e: Exception) {
            if (e.message?.contains("Script stopped") == true || e is InterruptedException) {
                log("Script stopped by user")
            } else {
                log("Script Error: ${e.message}")
                e.printStackTrace()
            }
        } finally {
            logFile?.let { log("Log saved: ${it.absolutePath}") }
            try {
                logWriter?.close()
            } catch (e: Exception) {
                Log.e("FarAuto", "Run log close failed", e)
            }
        }
    }

    private fun pruneOldLogs(logsDir: File, keep: Int = 50) {
        try {
            logsDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e("FarAuto", "Log pruning failed", e)
        }
    }

    private fun updateNotification(lastLog: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifTime < 1000) return 
        lastNotifTime = now

        val notification = createNotification(lastLog.takeLast(50))
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, KillSwitchReceiver::class.java).apply {
            action = "com.fareed.auto.ACTION_KILL_SCRIPT"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Far_Auto Background Engine")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Background Automation", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }
}
