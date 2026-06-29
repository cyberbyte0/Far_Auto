package com.fareed.auto

import android.content.Context
import android.content.Intent
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class DashboardServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    companion object {
        var authToken: String = ""
        fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private val runRateLimits = ConcurrentHashMap<String, Long>()

    private fun getSafeFile(baseDir: File, fileName: String): File = safeFileIn(baseDir, fileName)

    /** All scripts as (profile, file) pairs — "" profile = uncategorized (scripts/ root). */
    private fun allScripts(): List<Pair<String, File>> {
        val dir = MainActivity.getStorageDir(context, "scripts")
        val out = ArrayList<Pair<String, File>>()
        dir.listFiles()?.filter { it.isFile && it.extension == "py" }
            ?.sortedBy { it.name.lowercase() }?.forEach { out.add("" to it) }
        dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { p ->
            p.listFiles()?.filter { it.isFile && it.extension == "py" }
                ?.sortedBy { it.name.lowercase() }?.forEach { out.add(p.name to it) }
        }
        return out
    }

    /**
     * Resolves a script by name. When [profile] is non-null (the route supplied a `profile`
     * param, even empty for root) it is honored exactly. When null (no param — e.g. legacy MCP
     * callers), prefer the root, then fall back to the first match in any profile folder.
     */
    private fun resolveScriptFile(name: String, profile: String?): File {
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        if (profile != null) return safeScriptFile(scriptsDir, profile, name)
        val rootFile = safeScriptFile(scriptsDir, "", name)
        if (rootFile.exists()) return rootFile
        val safeName = sanitizeFileName(name)
        scriptsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { dir ->
            val f = File(dir, safeName)
            if (f.exists()) {
                Log.i("FarAuto", "Resolved '$name' to profile '${dir.name}' (no profile param)")
                return f
            }
        }
        return rootFile
    }

    /** Moves [src] into [destDir], picking a non-clobbering name and falling back to copy+delete. */
    private fun moveFileInto(src: File, destDir: File): Boolean {
        destDir.mkdirs()
        var dest = File(destDir, src.name)
        if (dest.exists()) dest = uniqueScriptName(destDir, src.name)
        if (src.renameTo(dest)) return true
        return try { src.copyTo(dest, overwrite = false); src.delete(); true }
        catch (e: Exception) { Log.e("FarAuto", "Move failed: ${src.name}", e); false }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val providedToken = session.headers["x-automator-token"]
        
        if (uri == "/" || uri == "/index.html") {
            val resp = newFixedLengthResponse(Response.Status.OK, "text/html", getDashboardHtml())
            resp.addHeader("X-Content-Type-Options", "nosniff")
            resp.addHeader("X-Frame-Options", "DENY")
            resp.addHeader("Content-Security-Policy",
                "default-src 'self' https://fonts.googleapis.com https://fonts.gstatic.com https://cdnjs.cloudflare.com; " +
                "script-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com; " +
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://fonts.gstatic.com; " +
                "font-src https://fonts.gstatic.com; " +
                "connect-src 'self';")
            return resp
        }

        if (!MessageDigest.isEqual(authToken.toByteArray(), providedToken?.toByteArray() ?: ByteArray(0))) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"unauthorized\"}")
        }

        val response = when (uri) {
            "/scripts" -> handleGetScripts()
            "/profiles" -> handleListProfiles()
            "/create_profile" -> handleCreateProfile(session)
            "/rename_profile" -> handleRenameProfile(session)
            "/delete_profile" -> handleDeleteProfile(session)
            "/move_scripts" -> handleMoveScripts(session)
            "/run" -> handleRunScript(session)
            "/run_batch" -> handleRunBatch(session)
            "/stop" -> handleStopScript()
            "/skip" -> handleSkipScript()
            "/status" -> handleGetStatus()
            "/logs" -> handleGetLogs()
            "/delete" -> handleDeleteScript(session)
            "/reset" -> handleResetScript(session)
            "/rename" -> handleRenameScript(session)
            "/input" -> handleInput(session)
            "/script_content" -> handleGetScriptContent(session)
            "/save_script" -> handleSaveScript(session)
            "/clear_logs" -> handleClearLogs()
            "/banner" -> handleBanner(session)
            "/export_scripts" -> handleExportScripts(session)
            "/import_script" -> handleImportScript(session)
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
        val scriptFile = resolveScriptFile(scriptName, session.parms["profile"])
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
        val scriptFile = safeScriptFile(scriptsDir, session.parms["profile"] ?: "", scriptName)

        return try {
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(content)
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"saved\"}")
        } catch (e: Exception) {
            Log.e("FarAuto", "Save script failed: ${scriptFile.name}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"save failed\"}")
        }
    }

    private fun handleInput(session: IHTTPSession): Response {
        val data = session.parms["data"] ?: ""
        ScriptExecutionService.pythonInputBuffer = data
        ScriptExecutionService.clearLogs()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"input_received\"}")
    }

    private fun handleGetScripts(): Response {
        val arr = JSONArray()
        allScripts().forEach { (profile, f) ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("profile", profile)
                put("modified", f.lastModified())
                put("size", f.length())
            })
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun handleListProfiles(): Response {
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val arr = JSONArray()
        scriptsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { dir ->
            arr.put(JSONObject().apply {
                put("name", dir.name)
                put("script_count", dir.listFiles { f -> f.isFile && f.extension == "py" }?.size ?: 0)
            })
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
    }

    private fun handleCreateProfile(session: IHTTPSession): Response {
        val name = session.parms["name"]?.takeIf { it.isNotBlank() }
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val dir = safeProfileDir(scriptsDir, name)
        if (dir == scriptsDir) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"invalid name\"}")
        return when {
            profileNameTaken(scriptsDir, dir.name) -> newFixedLengthResponse(Response.Status.CONFLICT, "application/json", "{\"error\":\"profile exists\"}")
            dir.mkdirs() -> newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"created\",\"name\":${JSONObject.quote(dir.name)}}")
            else -> newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"create failed\"}")
        }
    }

    /** True if a profile folder named [name] already exists (case-insensitive), ignoring [except]. */
    private fun profileNameTaken(scriptsDir: File, name: String, except: File? = null): Boolean =
        scriptsDir.listFiles()?.any { it.isDirectory && it != except && it.name.equals(name, ignoreCase = true) } == true

    private fun handleRenameProfile(session: IHTTPSession): Response {
        val oldName = session.parms["old_name"]?.takeIf { it.isNotBlank() }
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing old_name\"}")
        val newName = session.parms["new_name"]?.takeIf { it.isNotBlank() }
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing new_name\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val oldDir = safeProfileDir(scriptsDir, oldName)
        val newDir = safeProfileDir(scriptsDir, newName)
        if (oldDir == scriptsDir || newDir == scriptsDir) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"invalid name\"}")
        if (!oldDir.exists() || !oldDir.isDirectory) return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"profile not found\"}")
        // Case-insensitive collision check, but allow a pure case change (e.g. "work" -> "Work").
        if (profileNameTaken(scriptsDir, newDir.name, except = oldDir)) return newFixedLengthResponse(Response.Status.CONFLICT, "application/json", "{\"error\":\"target exists\"}")
        return if (oldDir.renameTo(newDir)) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"renamed\",\"name\":${JSONObject.quote(newDir.name)}}")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"rename failed\"}")
        }
    }

    private fun handleDeleteProfile(session: IHTTPSession): Response {
        val name = session.parms["name"]?.takeIf { it.isNotBlank() }
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val dir = safeProfileDir(scriptsDir, name)
        if (dir == scriptsDir) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"invalid name\"}")
        if (!dir.exists() || !dir.isDirectory) return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"profile not found\"}")
        // Non-destructive: relocate contained scripts to root (uncategorized) before removing.
        var moved = 0
        dir.listFiles()?.filter { it.isFile }?.forEach { if (moveFileInto(it, scriptsDir)) moved++ }
        dir.deleteRecursively()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"deleted\",\"moved\":$moved}")
    }

    private fun handleMoveScripts(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        try { session.parseBody(map) } catch (e: Exception) {}
        val body = map["postData"] ?: session.parms["postData"] ?: ""
        val json = try { JSONObject(body) } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"invalid json body\"}")
        }
        val items = json.optJSONArray("scripts")
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"no scripts provided\"}")
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val destDir = safeProfileDir(scriptsDir, json.optString("to", ""))
        destDir.mkdirs()
        var moved = 0
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isEmpty()) continue
            val src = safeScriptFile(scriptsDir, o.optString("profile", ""), name)
            if (!src.exists()) continue
            if (src.parentFile == destDir) { moved++; continue }
            if (moveFileInto(src, destDir)) moved++
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"moved\",\"moved\":$moved}")
    }

    private fun handleRunScript(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress
        val lastRun = runRateLimits[clientIp] ?: 0L
        if (System.currentTimeMillis() - lastRun < 5000) {
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", "{\"error\":\"rate limit exceeded\"}")
        }
        runRateLimits[clientIp] = System.currentTimeMillis()

        val scriptName = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptFile = resolveScriptFile(scriptName, session.parms["profile"])
        if (!scriptFile.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"script not found\"}")

        val intent = Intent(context, ScriptExecutionService::class.java).apply {
            putExtra("SCRIPT_PATH", scriptFile.absolutePath)
        }
        context.startForegroundService(intent)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"started\"}")
    }

    private fun handleRunBatch(session: IHTTPSession): Response {
        val clientIp = session.remoteIpAddress
        val lastRun = runRateLimits[clientIp] ?: 0L
        if (System.currentTimeMillis() - lastRun < 5000) {
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", "{\"error\":\"rate limit exceeded\"}")
        }
        runRateLimits[clientIp] = System.currentTimeMillis()

        val map = HashMap<String, String>()
        try { session.parseBody(map) } catch (e: Exception) {}
        val body = map["postData"] ?: session.parms["postData"] ?: ""
        val json = try { JSONObject(body) } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"invalid json body\"}")
        }

        val names = json.optJSONArray("scripts")
        if (names == null || names.length() == 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"no scripts provided\"}")
        }
        val stopOnError = json.optBoolean("stop_on_error", false)
        val delayMs = json.optLong("delay_ms", 0L)

        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val paths = ArrayList<String>()
        for (i in 0 until names.length()) {
            // Items may be {name, profile} objects (preferred) or bare name strings (legacy → resolve).
            val obj = names.optJSONObject(i)
            val file = if (obj != null) {
                val name = obj.optString("name", "")
                if (name.isEmpty()) continue
                safeScriptFile(scriptsDir, obj.optString("profile", ""), name)
            } else {
                val name = names.optString(i, "")
                if (name.isEmpty()) continue
                resolveScriptFile(name, null)
            }
            if (file.exists()) paths.add(file.absolutePath)
        }
        if (paths.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"no valid scripts found\"}")
        }

        val intent = Intent(context, ScriptExecutionService::class.java).apply {
            putStringArrayListExtra("SCRIPT_PATHS", paths)
            putExtra("STOP_ON_ERROR", stopOnError)
            putExtra("INTER_DELAY_MS", delayMs)
        }
        context.startForegroundService(intent)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"started\",\"count\":${paths.size}}")
    }

    private fun handleStopScript(): Response {
        val intent = Intent("com.fareed.auto.ACTION_KILL_SCRIPT").apply { setPackage(context.packageName) }
        context.sendBroadcast(intent)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"stopped\"}")
    }

    private fun handleSkipScript(): Response {
        ScriptExecutionService.skipCurrentScript()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"skipped\"}")
    }

    private fun handleGetStatus(): Response {
        val status = JSONObject().apply {
            put("running", ScriptExecutionService.isRunning.get())
            put("current_script", ScriptExecutionService.currentScriptPath?.substringAfterLast("/"))
            put("batch_index", ScriptExecutionService.batchIndex.get())
            put("batch_total", ScriptExecutionService.batchTotal)
            put("accessibility_enabled", FarAutoAccessibilityService.instance != null)
            put("recording", ScreenRecordService.isRecording())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString())
    }

    private fun handleDeleteScript(session: IHTTPSession): Response {
        val name = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        if (sanitizeFileName(name) == "ui_explorer.py") return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"cannot delete system scripts\"}")
        val file = resolveScriptFile(name, session.parms["profile"])
        return if (file.exists() && file.delete()) {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"deleted\"}")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"failed\"}")
        }
    }

    private fun handleResetScript(session: IHTTPSession): Response {
        val name = session.parms["name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing name\"}")
        val scriptFile = resolveScriptFile(name, session.parms["profile"])
        return try {
            context.assets.open("scripts/${scriptFile.name}").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"restored\"}")
        } catch (e: Exception) {
            Log.e("FarAuto", "Reset script failed: ${scriptFile.name}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"reset failed\"}")
        }
    }

    private fun handleRenameScript(session: IHTTPSession): Response {
        val oldName = session.parms["old_name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing old_name\"}")
        var newName = session.parms["new_name"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing new_name\"}")
        if (!newName.endsWith(".py")) newName += ".py"
        if (sanitizeFileName(oldName) == "ui_explorer.py") return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"forbidden\"}")
        val oldFile = resolveScriptFile(oldName, session.parms["profile"])
        val newFile = File(oldFile.parentFile, sanitizeFileName(newName))
        if (newFile.exists()) return newFixedLengthResponse(Response.Status.CONFLICT, "application/json", "{\"error\":\"target exists\"}")
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

    /**
     * Terminal welcome banner persisted on the device so every browser/device that
     * connects sees the same text.
     *  - GET  /banner            → { "banner": <string|null> }  (null = use built-in default)
     *  - POST /banner            → save the form field `postData` as the banner
     *  - POST /banner?reset=1    → clear the saved banner (revert to default)
     */
    private fun handleBanner(session: IHTTPSession): Response {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (session.method == NanoHTTPD.Method.POST) {
            if (session.parms["reset"] == "1") {
                prefs.edit().remove("dashboard_banner").apply()
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"reset\"}")
            }
            val map = HashMap<String, String>()
            try { session.parseBody(map) } catch (e: Exception) {}
            val banner = map["postData"] ?: session.parms["postData"] ?: ""
            prefs.edit().putString("dashboard_banner", banner).apply()
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"saved\"}")
        }
        val banner = prefs.getString("dashboard_banner", null)
        val obj = JSONObject().apply { put("banner", banner ?: JSONObject.NULL) }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
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
                "get_screen_size" -> handleGetScreenSize()
                "find_element" -> handleFindElement(params)
                "dump_ui_tree" -> handleDumpUiTree()
                "get_last_toast" -> handleGetLastToast()
                "is_secure_window" -> handleIsSecureWindow()
                "open_app_settings" -> handleOpenAppSettings(params)
                "close_app_from_recents" -> handleCloseAppFromRecents()
                "force_stop_app" -> handleForceStopApp(params)
                else -> return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"unknown method $method\"}")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        } catch (e: Exception) {
            Log.e("FarAuto", "RPC error", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"rpc error\"}")
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
            "long_press" -> {
                val x = params.optDouble("x", 0.0).toFloat()
                val y = params.optDouble("y", 0.0).toFloat()
                bridge.swipe(x, y, x, y, 800L)
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

    private fun handleExportScripts(session: IHTTPSession): Response {
        // (profile, file) pairs — profile-folder scripts are zipped under "Profile/name.py" so a
        // backup round-trips the folder layout; uncategorized scripts stay at the archive root.
        val scripts = allScripts()
        val includeLogs = session.parms["include_logs"] == "1"
        return try {
            val baos = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(baos).use { zos ->
                for ((profile, file) in scripts) {
                    val entryPath = if (profile.isEmpty()) file.name else "$profile/${file.name}"
                    zos.putNextEntry(java.util.zip.ZipEntry(entryPath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                if (includeLogs) {
                    val logFiles = MainActivity.getStorageDir(context, "logs")
                        .listFiles()?.filter { it.isFile } ?: emptyList()
                    for (file in logFiles) {
                        zos.putNextEntry(java.util.zip.ZipEntry("logs/${file.name}"))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val manifest = JSONObject().apply {
                    put("app", "Far_Auto")
                    put("manifest_version", 1)
                    put("exported_at", System.currentTimeMillis())
                    put("script_count", scripts.size)
                    put("scripts", JSONArray(scripts.map { (profile, file) -> if (profile.isEmpty()) file.name else "$profile/${file.name}" }))
                    put("includes_logs", includeLogs)
                }
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write(manifest.toString(2).toByteArray())
                zos.closeEntry()
            }
            val bytes = baos.toByteArray()
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                "application/zip",
                java.io.ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
            resp.addHeader("Content-Disposition", "attachment; filename=\"far_auto_backup.zip\"")
            resp
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    // Finds the next free "name (n).ext" in dir so an imported copy never clobbers an existing file.
    private fun uniqueScriptName(dir: File, fileName: String): File {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var n = 1
        var candidate = getSafeFile(dir, "$base ($n)$ext")
        while (candidate.exists()) { n++; candidate = getSafeFile(dir, "$base ($n)$ext") }
        return candidate
    }

    private fun handleImportScript(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"parse failed\"}")
        }
        val tempPath = files["file"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"no file\"}"
        )
        val originalName = session.parms["file"] ?: "upload.py"
        // mode: "detect" (classify only, write nothing), "overwrite", or "copy" (keep both)
        val mode = session.parms["mode"] ?: "overwrite"
        val detect = mode == "detect"
        val scriptsDir = MainActivity.getStorageDir(context, "scripts")
        val tempFile = java.io.File(tempPath)

        // Classifies one incoming script and (unless detecting) writes it per the chosen mode.
        // [profile] places it into a folder ("" = root); names are reported with the folder prefix
        // so conflict dialogs can disambiguate same-named scripts in different profiles.
        fun handleScript(baseName: String, profile: String, incoming: ByteArray, addedNames: JSONArray, unchanged: IntArray,
                         conflictNames: JSONArray, overwrittenNames: JSONArray, copiedNames: JSONArray) {
            val dest = safeScriptFile(scriptsDir, profile, baseName)
            val label = { f: File -> if (profile.isEmpty()) f.name else "$profile/${f.name}" }
            if (!detect) dest.parentFile?.mkdirs()
            when {
                !dest.exists() -> { addedNames.put(label(dest)); if (!detect) dest.writeBytes(incoming) }
                dest.readBytes().contentEquals(incoming) -> unchanged[0]++
                else -> {
                    conflictNames.put(label(dest))
                    if (!detect) {
                        if (mode == "copy") {
                            val copy = uniqueScriptName(dest.parentFile ?: scriptsDir, dest.name)
                            copy.writeBytes(incoming)
                            copiedNames.put(label(copy))
                        } else {
                            dest.writeBytes(incoming)
                            overwrittenNames.put(label(dest))
                        }
                    }
                }
            }
        }

        return try {
            val addedNames = JSONArray()
            val conflictNames = JSONArray()
            val overwrittenNames = JSONArray()
            val copiedNames = JSONArray()
            val unchanged = intArrayOf(0)
            var logsRestored = 0
            var manifest: JSONObject? = null

            if (originalName.endsWith(".zip", ignoreCase = true)) {
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        val baseName = entryName.substringAfterLast("/")
                        // One-level folder ("Profile/name.py") restores into that profile; deeper
                        // paths collapse to their immediate parent. "logs/" is handled separately.
                        val segments = entryName.split("/").filter { it.isNotEmpty() }
                        val profile = if (segments.size >= 2 && segments[segments.size - 2] != "logs") segments[segments.size - 2] else ""
                        when {
                            entry.isDirectory -> {}
                            entryName.endsWith(".py", ignoreCase = true) ->
                                handleScript(baseName, profile, zis.readBytes(), addedNames, unchanged,
                                    conflictNames, overwrittenNames, copiedNames)
                            entryName.startsWith("logs/") && baseName.isNotEmpty() -> {
                                if (!detect) {
                                    val dest = getSafeFile(MainActivity.getStorageDir(context, "logs"), baseName)
                                    dest.outputStream().use { zis.copyTo(it) }
                                    logsRestored++
                                }
                            }
                            baseName == "manifest.json" -> {
                                manifest = try {
                                    JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                                } catch (e: Exception) { null }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            } else if (originalName.endsWith(".py", ignoreCase = true)) {
                handleScript(originalName, "", tempFile.readBytes(), addedNames, unchanged,
                    conflictNames, overwrittenNames, copiedNames)
            } else {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"unsupported file type\"}")
            }

            val result = JSONObject().apply {
                put("mode", mode)
                put("added", addedNames.length())
                put("added_names", addedNames)
                put("unchanged", unchanged[0])
                put("conflicts", conflictNames.length())
                put("conflict_names", conflictNames)
                put("overwritten", overwrittenNames.length())
                put("overwritten_names", overwrittenNames)
                put("copied", copiedNames.length())
                put("copied_names", copiedNames)
                put("logs_restored", logsRestored)
                put("imported", if (detect) 0 else addedNames.length() + overwrittenNames.length() + copiedNames.length())
                manifest?.let { put("manifest", it) }
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleGetScreenSize(): JSONObject {
        val bridge = AutomatorBridge()
        val size = bridge.getScreenSize()
        val parts = size.split(",")
        return JSONObject().apply {
            put("width", parts.getOrNull(0)?.toIntOrNull() ?: 1080)
            put("height", parts.getOrNull(1)?.toIntOrNull() ?: 2400)
        }
    }

    private fun handleFindElement(params: JSONObject): JSONObject {
        val bridge = AutomatorBridge()
        val resourceId = params.optString("resource_id").ifEmpty { null }
        val text = params.optString("text").ifEmpty { null }
        val results = bridge.findNodes(resourceId, text)
        return JSONObject().apply { put("elements", org.json.JSONArray(results)) }
    }

    private fun handleDumpUiTree(): JSONObject {
        val bridge = AutomatorBridge()
        return JSONObject(bridge.dumpTree())
    }

    private fun handleGetLastToast(): JSONObject {
        val bridge = AutomatorBridge()
        val text = bridge.getLastToast()
        val pkg = bridge.getLastToastPackage()
        return JSONObject().apply {
            put("toast", text ?: JSONObject.NULL)
            put("package", pkg ?: JSONObject.NULL)
        }
    }

    private fun handleIsSecureWindow(): JSONObject {
        val bridge = AutomatorBridge()
        return JSONObject().apply { put("secure", bridge.isSecureWindow()) }
    }

    private fun handleOpenAppSettings(params: JSONObject): JSONObject {
        val bridge = AutomatorBridge()
        val pkg = params.optString("package", "")
        return JSONObject().apply { put("success", bridge.openAppSettings(pkg)) }
    }

    private fun handleCloseAppFromRecents(): JSONObject {
        val bridge = AutomatorBridge()
        bridge.pressRecent()
        Thread.sleep(1000)
        val parts = bridge.getScreenSize().split(",")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: 1080
        val h = parts.getOrNull(1)?.toIntOrNull() ?: 2400
        val success = bridge.swipe(w / 2f, h / 2f, w / 2f, h / 4f, 200L)
        return JSONObject().apply { put("success", success) }
    }

    private fun handleForceStopApp(params: JSONObject): JSONObject {
        val pkg = params.optString("package", "")
        val bridge = AutomatorBridge()
        if (!bridge.openAppSettings(pkg)) return JSONObject().apply { put("success", false) }
        Thread.sleep(1500)

        fun firstNode(resourceId: String?, text: String?): JSONObject? {
            val arr = org.json.JSONArray(bridge.findNodes(resourceId, text))
            return if (arr.length() > 0) arr.getJSONObject(0) else null
        }
        fun centerOf(node: JSONObject): Pair<Float, Float> {
            val b = node.optString("bounds").split(",")
            val x = ((b.getOrNull(0)?.toIntOrNull() ?: 0) + (b.getOrNull(2)?.toIntOrNull() ?: 0)) / 2f
            val y = ((b.getOrNull(1)?.toIntOrNull() ?: 0) + (b.getOrNull(3)?.toIntOrNull() ?: 0)) / 2f
            return Pair(x, y)
        }

        val btn = firstNode("com.android.settings:id/force_stop_button", null)
            ?: firstNode(null, "Force stop")
            ?: firstNode(null, "FORCE STOP")
            ?: return JSONObject().apply { put("success", false) }

        val (bx, by) = centerOf(btn)
        bridge.click(bx, by)
        Thread.sleep(800)

        // Confirm dialog
        val ok = firstNode("android:id/button1", null)
            ?: firstNode(null, "OK")
        if (ok != null) {
            val (ox, oy) = centerOf(ok)
            bridge.click(ox, oy)
        }
        return JSONObject().apply { put("success", true) }
    }

    private fun getDashboardHtml(): String {
        return try {
            context.assets.open("web/dashboard.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Dashboard Error</h1><p>${e.message}</p></body></html>"
        }
    }
}
