package com.fareed.auto

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    private lateinit var tvRecordStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        findViewById<Button>(R.id.btnEnableRecording).setOnClickListener {
            startActivity(Intent(this, ScreenRecordPermissionActivity::class.java))
        }
        
        val swDashboard = findViewById<SwitchCompat>(R.id.swDashboard)
        swDashboard.isChecked = prefs.getBoolean("dashboard_enabled", true)

        val swMcp = findViewById<SwitchCompat>(R.id.swMcp)
        swMcp.isChecked = prefs.getBoolean("mcp_enabled", true)

        val etPort = findViewById<EditText>(R.id.etPort)
        etPort.setText(prefs.getInt("dashboard_port", 8080).toString())

        val etToken = findViewById<EditText>(R.id.etToken)
        etToken.setText(prefs.getString("dashboard_token", ""))

        val etBanner = findViewById<EditText>(R.id.etBanner)
        etBanner.setText(prefs.getString("dashboard_banner", ""))
        findViewById<Button>(R.id.btnResetBanner).setOnClickListener {
            etBanner.setText("")
            prefs.edit().remove("dashboard_banner").apply()
            Toast.makeText(this, "Banner reset to default", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnSaveSettings).setOnClickListener {
            val port = etPort.text.toString().toIntOrNull() ?: 8080
            val token = etToken.text.toString().trim()
            val dashboardEnabled = swDashboard.isChecked
            val mcpEnabled = swMcp.isChecked
            val banner = etBanner.text.toString()

            prefs.edit().apply {
                putBoolean("dashboard_enabled", dashboardEnabled)
                putBoolean("mcp_enabled", mcpEnabled)
                putInt("dashboard_port", port)
                putString("dashboard_token", token)
                // Empty banner = revert to the dashboard's built-in default.
                if (banner.isBlank()) remove("dashboard_banner") else putString("dashboard_banner", banner)
                apply()
            }
            
            // Signal service to restart server with new config
            val intent = android.content.Intent(this, ScriptExecutionService::class.java).apply {
                action = "com.fareed.auto.RESTART_SERVER"
            }
            startForegroundService(intent)

            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ScreenRecordService.isReady()) {
            tvRecordStatus.text = "Enabled — scripts can record the screen this session."
        } else {
            tvRecordStatus.text = "Grant one-time consent so scripts can record the screen."
        }
    }
}
