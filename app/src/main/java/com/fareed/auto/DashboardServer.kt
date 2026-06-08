package com.fareed.auto

import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class DashboardServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    companion object {
        var authToken: String = ""
        fun generateToken(): String {
            val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            val sr = SecureRandom()
            return (1..12)
                .map { _ -> sr.nextInt(charPool.size) }
                .map(charPool::get)
                .joinToString("")
        }
    }

    private val runRateLimits = ConcurrentHashMap<String, Long>()

    private fun getSafeFile(baseDir: File, fileName: String): File {
        val sanitized = fileName.substringAfterLast("/").substringAfterLast("\\")
        return File(baseDir, sanitized)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val providedToken = session.headers["x-automator-token"] ?: session.parms["token"]
        
        if (uri == "/" || uri == "/index.html") {
            return newFixedLengthResponse(Response.Status.OK, "text/html", getDashboardHtml())
        }

        if (!MessageDigest.isEqual(authToken.toByteArray(), providedToken?.toByteArray() ?: ByteArray(0))) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"unauthorized\"}")
        }

        val response = when (uri) {
            "/scripts" -> handleGetScripts()
            "/run" -> handleRunScript(session)
            "/stop" -> handleStopScript()
            "/status" -> handleGetStatus()
            "/logs" -> handleGetLogs()
            "/delete" -> handleDeleteScript(session)
            "/reset" -> handleResetScript(session)
            "/rename" -> handleRenameScript(session)
            "/input" -> handleInput(session)
            "/script_content" -> handleGetScriptContent(session)
            "/save_script" -> handleSaveScript(session)
            "/clear_logs" -> handleClearLogs()
            "/api/rpc" -> {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (prefs.getBoolean("mcp_enabled", true)) {
                    handleRpc(session)
                } else {
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"MCP disabled\"}")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not found\"}")
        }

        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleGetScriptContent(session: IHTTPSession): Response {
        val scriptName = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val scriptFile = getSafeFile(scriptsDir, scriptName)
        return if (scriptFile.exists()) {
            newFixedLengthResponse(Response.Status.OK, "text/plain", scriptFile.readText())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }

    private fun handleSaveScript(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        try { session.parseBody(map) } catch (e: Exception) {}
        val scriptName = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val content = map["postData"] ?: session.parms["postData"] ?: ""
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val scriptFile = getSafeFile(scriptsDir, scriptName)
        
        return try {
            scriptFile.writeText(content)
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"saved\"}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleInput(session: IHTTPSession): Response {
        val data = session.parms["data"] ?: ""
        ScriptExecutionService.pythonInputBuffer = data
        ScriptExecutionService.clearLogs()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"input_received\"}")
    }

    private fun handleGetScripts(): Response {
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val scripts = scriptsDir.listFiles()?.filter { it.extension == "py" }?.map { it.name } ?: emptyList()
        return newFixedLengthResponse(Response.Status.OK, "application/json", JSONArray(scripts).toString())
    }

    private fun handleRunScript(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress
        val lastRun = runRateLimits[clientIp] ?: 0L
        if (System.currentTimeMillis() - lastRun < 5000) {
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", "{\"error\":\"rate limit exceeded\"}")
        }
        runRateLimits[clientIp] = System.currentTimeMillis()

        val scriptName = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val scriptFile = getSafeFile(scriptsDir, scriptName)
        if (!scriptFile.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"script not found\"}")

        val intent = Intent(context, ScriptExecutionService::class.java).apply {
            putExtra("SCRIPT_PATH", scriptFile.absolutePath)
        }
        context.startForegroundService(intent)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"started\"}")
    }

    private fun handleStopScript(): Response {
        val intent = Intent("com.fareed.auto.ACTION_KILL_SCRIPT").apply { setPackage(context.packageName) }
        context.sendBroadcast(intent)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"stopped\"}")
    }

    private fun handleGetStatus(): Response {
        val status = JSONObject().apply {
            put("running", ScriptExecutionService.isRunning.get())
            put("current_script", ScriptExecutionService.currentScriptPath?.substringAfterLast("/"))
            put("accessibility_enabled", FarAutoAccessibilityService.instance != null)
            put("recording", ScreenRecordService.isRecording())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    private fun handleDeleteScript(session: IHTTPSession): Response {
        val name = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        if (name == "ui_explorer.py") return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"cannot delete system scripts\"}")
        val file = getSafeFile(MainActivity.getStorageDir(context, "scripts"), name)
        return if (file.exists() && file.delete()) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"deleted\"}")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"failed\"}")
        }
    }

    private fun handleResetScript(session: IHTTPSession): Response {
        val name = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptFile = getSafeFile(MainActivity.getStorageDir(context, "scripts"), name)
        return try {
            context.assets.open("scripts/${scriptFile.name}").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"restored\"}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleRenameScript(session: IHTTPSession): Response {
        val oldName = session.parms["old_name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing old_name\"}")
        var newName = session.parms["new_name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing new_name\"}")
        if (!newName.endsWith(".py")) newName += ".py"
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val oldFile = getSafeFile(scriptsDir, oldName)
        val newFile = getSafeFile(scriptsDir, newName)
        if (oldName == "ui_explorer.py") return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"forbidden\"}")
        return if (oldFile.exists() && oldFile.renameTo(newFile)) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"renamed\"}")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"failed\"}")
        }
    }

    private fun handleGetLogs(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/plain", ScriptExecutionService.getLogs())
    }

    private fun handleClearLogs(): Response {
        ScriptExecutionService.clearLogs()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"logs cleared\"}")
    }

    private fun handleRpc(session: IHTTPSession): Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{\"error\":\"method not allowed\"}")
        }
        
        val map = HashMap<String, String>()
        var body: String? = null
        
        try {
            session.parseBody(map)
            // Try form data first, then fallback to raw body
            body = map["postData"] ?: session.queryParameterString
            
            // If body is still null, read the raw input stream (standard for JSON-RPC)
            if (body.isNullOrEmpty()) {
                val size = session.headers["content-length"]?.toInt() ?: 0
                if (size > 0) {
                    val buffer = ByteArray(size)
                    session.inputStream.read(buffer, 0, size)
                    body = String(buffer)
                }
            }
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"parsing failed\"}")
        }

        if (body.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"empty request body\"}")
        }
        
        try {
            val json = JSONObject(body)
            val method = json.optString("method")
            val params = json.optJSONObject("params") ?: JSONObject()
            
            val result = when (method) {
                "get_screen_info" -> handleGetScreenInfo()
                "take_screenshot" -> handleTakeScreenshot()
                "perform_action" -> handlePerformAction(params)
                "click_and_wait" -> handleClickAndWait(params)
                else -> return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"unknown method $method\"}")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleGetScreenInfo(): JSONObject {
        val bridge = AutomatorBridge()
        val nodesStr = bridge.getInteractableNodes()
        val sizeStr = bridge.getScreenSize()
        
        return JSONObject().apply {
            put("elements", JSONArray(nodesStr))
            put("screen_size", sizeStr)
        }
    }

    private fun handleTakeScreenshot(): JSONObject {
        val bridge = AutomatorBridge()
        val base64 = bridge.takeScreenshot()
        return JSONObject().apply {
            put("success", base64 != null)
            if (base64 != null) {
                put("image", base64)
            }
        }
    }

    private fun handlePerformAction(params: JSONObject): JSONObject {
        val action = params.optString("action", "")
        val bridge = AutomatorBridge()
        val success = when (action) {
            "click" -> {
                val x = params.optDouble("x", 0.0).toFloat()
                val y = params.optDouble("y", 0.0).toFloat()
                bridge.click(x, y)
            }
            "swipe" -> {
                val x1 = params.optDouble("x1", 0.0).toFloat()
                val y1 = params.optDouble("y1", 0.0).toFloat()
                val x2 = params.optDouble("x2", 0.0).toFloat()
                val y2 = params.optDouble("y2", 0.0).toFloat()
                val duration = params.optLong("duration", 300)
                bridge.swipe(x1, y1, x2, y2, duration)
            }
            "input" -> {
                val text = params.optString("text", "")
                val clear = params.optBoolean("clear", true)
                bridge.inputText(text, clear)
            }
            "key_press" -> {
                val key = params.optString("key", "")
                when (key) {
                    "back" -> bridge.pressBack()
                    "home" -> bridge.pressHome()
                    "recents" -> bridge.pressRecent()
                    else -> false
                }
            }
            "launch_app" -> {
                val pkg = params.optString("package", "")
                bridge.launchApp(pkg)
            }
            else -> false
        }
        return JSONObject().apply { put("success", success) }
    }

    private fun handleClickAndWait(params: JSONObject): JSONObject {
        val x = params.optDouble("x", 0.0).toFloat()
        val y = params.optDouble("y", 0.0).toFloat()
        val bridge = AutomatorBridge()
        val success = bridge.click(x, y)
        if (success) {
            Thread.sleep(800) // Slightly longer wait for real devices
        }
        return JSONObject().apply { put("success", success) }
    }

    private fun getDashboardHtml(): String {
        return try {
            context.assets.open("web/dashboard.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Dashboard Error</h1><p>${e.message}</p></body></html>"
        }
    }
}
