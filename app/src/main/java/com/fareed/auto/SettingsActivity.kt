package com.fareed.auto

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        
        val swDashboard = findViewById<SwitchCompat>(R.id.swDashboard)
        swDashboard.isChecked = prefs.getBoolean("dashboard_enabled", true)

        val etPort = findViewById<EditText>(R.id.etPort)
        etPort.setText(prefs.getInt("dashboard_port", 8080).toString())

        val etToken = findViewById<EditText>(R.id.etToken)
        etToken.setText(prefs.getString("dashboard_token", ""))
        
        findViewById<View>(R.id.btnSaveSettings).setOnClickListener {
            val port = etPort.text.toString().toIntOrNull() ?: 8080
            val token = etToken.text.toString().trim()
            val dashboardEnabled = swDashboard.isChecked
            
            prefs.edit().apply {
                putBoolean("dashboard_enabled", dashboardEnabled)
                putInt("dashboard_port", port)
                putString("dashboard_token", token)
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
}
