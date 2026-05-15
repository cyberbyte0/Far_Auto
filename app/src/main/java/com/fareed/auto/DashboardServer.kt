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
            val sr = java.security.SecureRandom()
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
            "/logs" -> handleGetLogs(session)
            "/delete" -> handleDeleteScript(session)
            "/reset" -> handleResetScript(session)
            "/rename" -> handleRenameScript(session)
            "/input" -> handleInput(session)
            "/script_content" -> handleGetScriptContent(session)
            "/save_script" -> handleSaveScript(session)
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
        try { 
            session.parseBody(map) 
        } catch (e: Exception) {
            android.util.Log.e("FarAuto", "Error parsing body: ${e.message}")
        }
        
        val scriptName = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        
        // Check both the map and parms (NanoHTTPD merges them sometimes)
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
        try { session.parseBody(HashMap()) } catch (e: Exception) {}
        val data = session.parms["data"] ?: ""
        ScriptExecutionService.pythonInputBuffer = data
        ScriptExecutionService.clearLogs() // Clear terminal as soon as input is received
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
        if (System.currentTimeMillis() - lastRun < 6000) {
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
        val intent = Intent("com.fareed.auto.ACTION_KILL_SCRIPT").apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"stopped\"}")
    }

    private fun handleGetStatus(): Response {
        val status = JSONObject().apply {
            put("running", ScriptExecutionService.isRunning.get())
            put("current_script", ScriptExecutionService.currentScriptPath?.substringAfterLast("/"))
            put("accessibility_enabled", isAccessibilityServiceEnabled())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return FarAutoAccessibilityService.instance != null
    }

    private fun handleDeleteScript(session: IHTTPSession): Response {
        val name = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        if (name == "ui_explorer.py") return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"cannot delete system scripts\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val file = getSafeFile(scriptsDir, name)
        return if (file.exists() && file.delete()) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"deleted\"}")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"failed to delete\"}")
        }
    }

    private fun handleResetScript(session: IHTTPSession): Response {
        val name = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val scriptFile = getSafeFile(scriptsDir, name)
        try {
            context.assets.open("scripts/${scriptFile.name}").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"restored\"}")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"failed to restore: ${e.message}\"}")
        }
    }

    private fun handleRenameScript(session: IHTTPSession): Response {
        val oldName = session.parms["old_name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing old_name\"}")
        var newName = session.parms["new_name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing new_name\"}")
        
        if (!newName.endsWith(".py")) newName += ".py"
        
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val oldFile = getSafeFile(scriptsDir, oldName)
        val newFile = getSafeFile(scriptsDir, newName)
        
        if (oldName == "ui_explorer.py") return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"cannot rename system scripts\"}")
        
        return if (oldFile.exists() && oldFile.renameTo(newFile)) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"renamed\"}")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"rename failed\"}")
        }
    }

    private fun handleGetLogs(session: IHTTPSession): Response {
        val logs = ScriptExecutionService.getLogs()
        return newFixedLengthResponse(Response.Status.OK, "text/plain", logs)
    }

    private fun getDashboardHtml(): String {
        return try {
            context.assets.open("web/dashboard.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Dashboard Error</h1><p>${e.message}</p></body></html>"
        }
    }
}
