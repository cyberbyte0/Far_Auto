package com.fareed.auto

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.NetworkInterface

class McpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mcp)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnSendToWeb).setOnClickListener {
            if (ScriptExecutionService.isRunning.get()) {
                Toast.makeText(this, "Stop the running script first!", Toast.LENGTH_SHORT).show()
            } else {
                sendSetupToWeb()
            }
        }
    }

    private fun sendSetupToWeb() {
        val ip = getDeviceIp()
        val token = DashboardServer.authToken
        val bridgeScript = loadAsset("far_auto_mcp.py")
        
        // Custom markers for the web UI to catch
        val payload = """
            FAR_AUTO_SETUP_PACKAGE_START
            {
                "ip": "$ip",
                "token": "$token",
                "script": ${JSONObject.quote(bridgeScript)},
                "claude_config": {
                    "mcpServers": {
                        "android": {
                            "command": "python3",
                            "args": ["/path/to/far_auto_mcp.py"],
                            "env": { "DEVICE_IP": "$ip", "AUTH_TOKEN": "$token" }
                        }
                    }
                }
            }
            FAR_AUTO_SETUP_PACKAGE_END
        """.trimIndent()

        // Push to the memory log buffer
        ScriptExecutionService.clearLogs()
        // We bypass the formatter to send raw payload
        val bridge = AutomatorBridge()
        // We'll just print it so the console sees it
        // But since we removed logging, we need a direct way.
        // I will add a 'injectLog' method to ScriptExecutionService.
        ScriptExecutionService.injectLog(payload)
        
        Toast.makeText(this, "Setup sent to Web Console!", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun getDeviceIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        val currentIp = addr.hostAddress ?: continue
                        if (iface.name.contains("wlan", ignoreCase = true)) return currentIp
                    }
                }
            }
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    private fun loadAsset(name: String): String {
        return try {
            // Since far_auto_mcp.py is in the root or elsewhere, 
            // for the app to find it easily, it should be in assets/web/
            // I will move it there later.
            assets.open("web/$name").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "# Error loading script: ${e.message}"
        }
    }
}
