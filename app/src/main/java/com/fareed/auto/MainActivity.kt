package com.fareed.auto

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ScriptAdapter
    private val scripts = mutableListOf<File>()      // displayed (filtered + sorted)
    private val allScripts = mutableListOf<File>()   // master, unfiltered
    private val profiles = mutableListOf<String>()   // folder names under scripts/
    private var activeFolder: String? = null         // null = All, "" = Uncategorized, else folder name
    private var searchQuery = ""
    private var sortMode = SortMode.NAME_ASC

    private enum class SortMode { NAME_ASC, NAME_DESC, MODIFIED_DESC, MODIFIED_ASC }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importFromUri(it) }
    }

    companion object {
        // Single app folder on storage; subfolders: scripts, logs, files.
        const val APP_DIR = "Far_Auto"

        /** Root that holds the [APP_DIR] folder: shared storage if we have access, else app-private. */
        fun storageRoot(context: Context): File {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                Environment.getExternalStorageDirectory()
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                Environment.getExternalStorageDirectory()
            } else {
                context.filesDir
            }
        }

        /** Returns (and creates) Far_Auto/<folder> — folder is "scripts", "logs", or "files". */
        fun getStorageDir(context: Context, folder: String): File {
            val dir = File(File(storageRoot(context), APP_DIR), folder)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!com.chaquo.python.Python.isStarted()) {
            com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(this))
        }

        requestBasicPermissions()
        checkAndRequestManageStorage()
        migrateLegacyStorage()
        setupStorage()
        setupUI()
        
        // Ensure background service is running for the dashboard
        startForegroundService(Intent(this, ScriptExecutionService::class.java))
    }

    private fun requestBasicPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
    }

    private fun checkAndRequestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("To save scripts to the public '/sdcard/Far_Auto' folder, you must grant 'All files access'.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivityForResult(intent, 102)
                        } catch (e: Exception) {
                            startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 102)
                        }
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            setupStorage()
            refreshScripts()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (FarAutoAccessibilityService.isRunning()) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName && 
                service.resolveInfo.serviceInfo.name == FarAutoAccessibilityService::class.java.name) {
                return true
            }
        }
        return false
    }

    private fun syncToken() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val customToken = prefs.getString("dashboard_token", "")?.trim()
        
        if (!customToken.isNullOrEmpty()) {
            DashboardServer.authToken = customToken!!
        } else {
            if (DashboardServer.authToken.isEmpty()) {
                DashboardServer.authToken = DashboardServer.generateToken()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        val isEnabled = isAccessibilityServiceEnabled()
        findViewById<View>(R.id.accessibilityBanner).visibility = if (isEnabled) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(this, FarAutoAccessibilityService::class.java).flattenToString()
            intent.putExtra(":settings:fragment_args_key", componentName)
            intent.putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", componentName)
            })
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "Tap 3-dots -> Allow restricted settings", Toast.LENGTH_LONG).show()
        }

        syncToken()
        updateDashboardInfo()
        refreshScripts()
    }

    private fun updateDashboardInfo() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("dashboard_enabled", true)) {
            findViewById<TextView>(R.id.tvDashboardInfo).text = "Dashboard: Disabled"
            return
        }

        val port = prefs.getInt("dashboard_port", 8080)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var ip = "localhost"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        val currentIp = addr.hostAddress ?: continue
                        if (iface.name.contains("wlan", ignoreCase = true)) {
                            ip = currentIp
                            break 
                        }
                        ip = currentIp
                    }
                }
                if (ip != "localhost" && iface.name.contains("wlan", ignoreCase = true)) break
            }
            findViewById<TextView>(R.id.tvDashboardInfo).text = 
                "Dashboard: http://$ip:$port\nToken: ${DashboardServer.authToken}"
        } catch (e: Exception) {
            findViewById<TextView>(R.id.tvDashboardInfo).text = "Dashboard Error: ${e.message}"
        }
    }

    /**
     * One-time move from the old flat layout (FAR_auto scripts / FAR_auto logs /
     * FAR_auto recordings) to Far_Auto/{scripts,logs,files}. Screenshots that lived
     * in the old scripts folder (non-.py files) move into files/. Runs every launch
     * but is a no-op once the old folders are gone, so no flag is needed.
     */
    private fun migrateLegacyStorage() {
        try {
            val root = storageRoot(this)
            val oldScripts = File(root, "FAR_auto scripts")
            val oldLogs = File(root, "FAR_auto logs")
            val oldRecordings = File(root, "FAR_auto recordings")
            if (!oldScripts.isDirectory && !oldLogs.isDirectory && !oldRecordings.isDirectory) return

            val newScripts = getStorageDir(this, "scripts")
            val newLogs = getStorageDir(this, "logs")
            val newFiles = getStorageDir(this, "files")

            // Old scripts folder: .py -> scripts, anything else (screenshots) -> files.
            if (oldScripts.isDirectory) {
                oldScripts.listFiles()?.forEach { f ->
                    moveInto(f, if (f.isFile && f.extension.equals("py", true)) newScripts else newFiles)
                }
                oldScripts.delete()
            }
            if (oldLogs.isDirectory) {
                oldLogs.listFiles()?.forEach { moveInto(it, newLogs) }
                oldLogs.delete()
            }
            if (oldRecordings.isDirectory) {
                oldRecordings.listFiles()?.forEach { moveInto(it, newFiles) }
                oldRecordings.delete()
            }
            if (BuildConfig.DEBUG) Log.i("FarAuto", "Storage migrated to $APP_DIR/")
        } catch (e: Exception) {
            Log.e("FarAuto", "Storage migration failed", e)
        }
    }

    /** Moves [src] into [destDir], preferring a rename; never overwrites an existing file. */
    private fun moveInto(src: File, destDir: File) {
        val dest = File(destDir, src.name)
        if (dest.exists()) { src.delete(); return }
        if (src.renameTo(dest)) return
        try {
            src.copyTo(dest, overwrite = false)
            src.delete()
        } catch (e: Exception) {
            Log.e("FarAuto", "Could not migrate ${src.name}", e)
        }
    }

    private fun setupStorage() {
        val scriptsDir = getStorageDir(this, "scripts")
        val systemScript = File(scriptsDir, "ui_explorer.py")
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val lastUpdate = prefs.getInt("system_script_version", 0)
        val currentVersion = 6 // Added screen-record shortcuts ([sr]/[srs])

        if (!systemScript.exists() || lastUpdate < currentVersion) {
            restoreScript("ui_explorer.py")
            prefs.edit().putInt("system_script_version", currentVersion).apply()
        }
    }

    private fun restoreScript(name: String) {
        val scriptsDir = getStorageDir(this, "scripts")
        try {
            assets.open("scripts/$name").use { input ->
                File(scriptsDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e("FarAuto", "Error restoring $name", e)
        }
    }

    private fun setupUI() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val fabMenu = findViewById<View>(R.id.fabMenu)
        val btnNew = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnNew)
        var isMenuOpen = false

        adapter = ScriptAdapter(scripts,
            onRun = { runScript(it) },
            onEdit = { editScript(it) },
            onDelete = { file ->
                if (file.name == "ui_explorer.py") {
                    AlertDialog.Builder(this)
                        .setTitle("Restore System Script")
                        .setMessage("Reset 'ui_explorer.py'?")
                        .setPositiveButton("Restore") { _, _ ->
                            restoreScript(file.name)
                            refreshScripts()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Delete script")
                        .setMessage("Delete \"${file.name}\"? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ -> file.delete(); refreshScripts() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
            onOptions = { file, view -> showScriptOptions(file, view) },
            subtitleProvider = { f ->
                // In the "All" view, show which folder a script lives in.
                if (activeFolder == null) profileOf(f).let { if (it.isEmpty()) "Python Script" else "📁 $it" }
                else "Python Script"
            },
            onSelectionChanged = { updateSelectionCount() }
        )
        recyclerView.adapter = adapter

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { searchQuery = s?.toString() ?: ""; applyView() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, before: Int) {}
        })
        findViewById<View>(R.id.btnSort).setOnClickListener { v ->
            val popup = android.widget.PopupMenu(this, v)
            popup.menu.add(0, 0, 0, "Name (A–Z)")
            popup.menu.add(0, 1, 1, "Name (Z–A)")
            popup.menu.add(0, 2, 2, "Newest first")
            popup.menu.add(0, 3, 3, "Oldest first")
            popup.setOnMenuItemClickListener { item ->
                sortMode = when (item.itemId) {
                    1 -> SortMode.NAME_DESC
                    2 -> SortMode.MODIFIED_DESC
                    3 -> SortMode.MODIFIED_ASC
                    else -> SortMode.NAME_ASC
                }
                applyView()
                true
            }
            popup.show()
        }

        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshScripts() }
        findViewById<View>(R.id.btnMcp).setOnClickListener {
            startActivity(Intent(this, McpActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        btnNew.setOnClickListener {
            isMenuOpen = !isMenuOpen
            if (isMenuOpen) {
                btnNew.animate().rotation(135f).setDuration(300).start()
                fabMenu.visibility = View.VISIBLE
                fabMenu.translationY = 50f
                fabMenu.alpha = 0f
                fabMenu.animate().alpha(1f).translationY(0f).setDuration(300).start()
            } else {
                closeFabMenu(btnNew, fabMenu)
            }
        }

        findViewById<View>(R.id.menuRunSeq).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; enterSelectionMode()
        }
        findViewById<View>(R.id.menuCreate).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; createNewScript()
        }
        findViewById<View>(R.id.menuNewFolder).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; createFolder()
        }
        findViewById<View>(R.id.menuImport).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; importLauncher.launch("*/*")
        }
        findViewById<View>(R.id.menuExport).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; chooseExport()
        }

        findViewById<View>(R.id.btnSelectCancel).setOnClickListener { exitSelectionMode() }
        findViewById<View>(R.id.btnSelectNext).setOnClickListener { showRunSequenceDialog() }
        findViewById<View>(R.id.btnSelectDelete).setOnClickListener { bulkDeleteSelected() }
    }

    private fun enterSelectionMode() {
        adapter.selectionMode = true
        findViewById<View>(R.id.btnNew).visibility = View.GONE
        findViewById<View>(R.id.selectionBar).visibility = View.VISIBLE
        updateSelectionCount()
    }

    private fun exitSelectionMode() {
        adapter.selectionMode = false
        findViewById<View>(R.id.selectionBar).visibility = View.GONE
        findViewById<View>(R.id.btnNew).visibility = View.VISIBLE
    }

    private fun updateSelectionCount() {
        val n = adapter.selectedOrder.size
        findViewById<TextView>(R.id.selectionCount).text = "$n selected"
        findViewById<Button>(R.id.btnSelectNext).isEnabled = n > 0
        findViewById<View>(R.id.btnSelectDelete).apply {
            isEnabled = n > 0
            alpha = if (n > 0) 1f else 0.4f
        }
    }

    private fun bulkDeleteSelected() {
        val selected = adapter.selectedOrder.toList()
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select scripts to delete", Toast.LENGTH_SHORT).show()
            return
        }
        val deletable = selected.filter { it.name != "ui_explorer.py" }
        if (deletable.isEmpty()) {
            Toast.makeText(this, "'ui_explorer.py' is a system script — use Reset instead", Toast.LENGTH_LONG).show()
            return
        }
        val skipped = selected.size - deletable.size
        val msg = buildString {
            append("Delete ${deletable.size} script(s)? This cannot be undone.\n\n")
            append(deletable.joinToString("\n") { "• ${it.name}" })
            if (skipped > 0) append("\n\n('ui_explorer.py' will be skipped — it's a system script.)")
        }
        AlertDialog.Builder(this)
            .setTitle("Delete selected")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                deletable.forEach { it.delete() }
                exitSelectionMode()
                refreshScripts()
                Toast.makeText(this, "Deleted ${deletable.size} script(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRunSequenceDialog() {
        val selected = adapter.selectedOrder.toMutableList()
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one script", Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_run_sequence, null)
        val list = view.findViewById<RecyclerView>(R.id.sequenceList)
        val switchStop = view.findViewById<SwitchMaterial>(R.id.switchStopOnError)
        val inputDelay = view.findViewById<EditText>(R.id.inputDelay)

        list.layoutManager = LinearLayoutManager(this)
        val seqAdapter = SequenceAdapter(selected)
        list.adapter = seqAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                seqAdapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun isLongPressDragEnabled() = true
        })
        touchHelper.attachToRecyclerView(list)

        AlertDialog.Builder(this)
            .setTitle("Run Sequence (${selected.size})")
            .setView(view)
            .setPositiveButton("Run") { _, _ ->
                // Drop any scripts deleted/renamed while selection mode was open.
                val paths = ArrayList(seqAdapter.items.filter { it.exists() }.map { it.absolutePath })
                if (paths.isEmpty()) {
                    Toast.makeText(this, "No selected scripts still exist", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val delayMs = inputDelay.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                runSequence(paths, switchStop.isChecked, delayMs)
                exitSelectionMode()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runSequence(paths: ArrayList<String>, stopOnError: Boolean, delayMs: Long) {
        val intent = Intent(this, ScriptExecutionService::class.java).apply {
            putStringArrayListExtra("SCRIPT_PATHS", paths)
            putExtra("STOP_ON_ERROR", stopOnError)
            putExtra("INTER_DELAY_MS", delayMs)
        }
        startForegroundService(intent)
    }

    private fun closeFabMenu(fab: com.google.android.material.floatingactionbutton.FloatingActionButton, menu: View) {
        fab.animate().rotation(0f).setDuration(300).start()
        menu.animate().alpha(0f).translationY(50f).setDuration(300).withEndAction { menu.visibility = View.GONE }.start()
    }

    private fun createNewScript() {
        // Create inside the active folder (if one is selected) so it lands where the user is looking.
        val dir = if (activeFolder != null && activeFolder != "") File(scriptsDir(), activeFolder!!).apply { mkdirs() }
                  else scriptsDir()
        val file = File(dir, "script_${System.currentTimeMillis()}.py")
        file.writeText("# New Script\nimport automator\n")
        refreshScripts()
        editScript(file)
    }

    // ---------- Import (single .py or .zip backup, with conflict resolution) ----------

    private fun importFromUri(uri: Uri) {
        try {
            val name = queryDisplayName(uri) ?: "import"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: run {
                Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show(); return
            }
            val isZip = name.endsWith(".zip", true) ||
                (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            if (isZip) importZip(bytes) else importSinglePy(name, bytes)
        } catch (e: Exception) {
            Log.e("FarAuto", "Import failed", e)
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) { null }

    private fun importSinglePy(name: String, bytes: ByteArray) {
        val base = if (name.endsWith(".py", true)) name.substringAfterLast('/')
                   else "imported_${System.currentTimeMillis()}.py"
        processIncoming(linkedMapOf(base to bytes), emptyMap(), null)
    }

    private fun importZip(bytes: ByteArray) {
        val scriptsIn = LinkedHashMap<String, ByteArray>()
        val logsIn = LinkedHashMap<String, ByteArray>()
        var manifestDate: Long? = null
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val base = entryName.substringAfterLast('/')
                // "Folder/name.py" restores into that folder; deeper paths collapse to one level.
                val segs = entryName.split('/').filter { it.isNotEmpty() && it != ".." && it != "." }
                val profile = if (segs.size >= 2 && segs[segs.size - 2] != "logs") segs[segs.size - 2] else ""
                val rel = if (profile.isEmpty()) base else "$profile/$base"
                if (!entry.isDirectory) {
                    when {
                        entryName.endsWith(".py", true) -> scriptsIn[rel] = zis.readBytes()
                        entryName.startsWith("logs/") && base.isNotEmpty() -> logsIn[base] = zis.readBytes()
                        base == "manifest.json" -> manifestDate = try {
                            org.json.JSONObject(String(zis.readBytes())).optLong("exported_at", 0L).takeIf { it > 0 }
                        } catch (e: Exception) { null }
                    }
                }
                entry = zis.nextEntry
            }
        }
        if (scriptsIn.isEmpty() && logsIn.isEmpty()) {
            Toast.makeText(this, "No scripts found in ZIP", Toast.LENGTH_SHORT).show(); return
        }
        processIncoming(scriptsIn, logsIn, manifestDate)
    }

    /** Classifies incoming scripts vs. existing files and writes them, prompting on name conflicts. */
    private fun processIncoming(scriptsIn: Map<String, ByteArray>, logsIn: Map<String, ByteArray>, manifestDate: Long?) {
        val scriptsDir = getStorageDir(this, "scripts")
        val added = mutableListOf<String>()
        var unchanged = 0
        val conflicts = mutableListOf<String>()
        for ((base, content) in scriptsIn) {
            val dest = File(scriptsDir, base)
            when {
                !dest.exists() -> added.add(base)
                dest.readBytes().contentEquals(content) -> unchanged++
                else -> conflicts.add(base)
            }
        }

        val finalUnchanged = unchanged
        val apply = { mode: String ->
            for (base in added) File(scriptsDir, base).apply { parentFile?.mkdirs() }.writeBytes(scriptsIn[base]!!)
            var overwritten = 0
            var copied = 0
            for (base in conflicts) {
                val content = scriptsIn[base]!!
                if (mode == "copy") {
                    uniqueCopyName(scriptsDir, base).apply { parentFile?.mkdirs() }.writeBytes(content); copied++
                } else {
                    File(scriptsDir, base).apply { parentFile?.mkdirs() }.writeBytes(content); overwritten++
                }
            }
            var logsRestored = 0
            if (logsIn.isNotEmpty()) {
                val logsDir = getStorageDir(this, "logs")
                for ((base, content) in logsIn) { File(logsDir, base).writeBytes(content); logsRestored++ }
            }
            refreshScripts()
            showImportSummary(added.size, overwritten, copied, finalUnchanged, logsRestored, manifestDate)
        }

        if (conflicts.isEmpty()) apply("overwrite") else confirmConflicts(conflicts, apply)
    }

    private fun confirmConflicts(conflicts: List<String>, onChoice: (String) -> Unit) {
        val list = conflicts.joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle("Import conflicts")
            .setMessage("${conflicts.size} script(s) already exist with different content:\n\n$list\n\nWhat should happen to them?")
            .setPositiveButton("Overwrite") { _, _ -> onChoice("overwrite") }
            .setNegativeButton("Keep both") { _, _ -> onChoice("copy") }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /** Next free "name (n).ext" so a kept copy never clobbers an existing file. */
    private fun uniqueCopyName(dir: File, fileName: String): File {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var n = 1
        var candidate = File(dir, "$base ($n)$ext")
        while (candidate.exists()) { n++; candidate = File(dir, "$base ($n)$ext") }
        return candidate
    }

    private fun showImportSummary(added: Int, overwritten: Int, copied: Int, unchanged: Int, logs: Int, manifestDate: Long?) {
        val sb = StringBuilder("• $added added")
        if (overwritten > 0) sb.append("\n• $overwritten overwritten")
        if (copied > 0) sb.append("\n• $copied kept as copy")
        if (unchanged > 0) sb.append("\n• $unchanged unchanged (identical, skipped)")
        if (logs > 0) sb.append("\n• $logs log file(s) restored")
        if (manifestDate != null) {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(manifestDate))
            sb.append("\n\nBackup from: $d")
        }
        AlertDialog.Builder(this).setTitle("Import complete").setMessage(sb.toString()).setPositiveButton("OK", null).show()
    }

    // ---------- Export (ZIP backup with manifest + optional logs) ----------

    /** Writes a copy of [zipFile] into the public Downloads folder via MediaStore; returns the visible path or null. */
    private fun saveToDownloads(zipFile: File): String? {
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, zipFile.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(itemUri)?.use { out -> zipFile.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(itemUri, values, null, null)
            "Downloads/${zipFile.name}"
        } catch (e: Exception) {
            Log.e("FarAuto", "Save to Downloads failed", e)
            null
        }
    }

    private fun chooseExport() {
        AlertDialog.Builder(this)
            .setTitle("Export backup")
            .setItems(arrayOf("Scripts only", "Scripts + logs")) { _, which -> exportAllScripts(which == 1) }
            .show()
    }

    private fun exportAllScripts(includeLogs: Boolean) {
        val zipFile = File(getExternalFilesDir("exports"), "far_auto_backup.zip")
        try {
            // (profile, file) pairs — folder scripts are zipped under "Folder/name.py" so the backup
            // round-trips the layout; uncategorized scripts stay at the archive root.
            val sDir = getStorageDir(this, "scripts")
            val scriptEntries = ArrayList<Pair<String, File>>()
            sDir.listFiles()?.forEach { entry ->
                when {
                    entry.isFile && entry.extension == "py" -> scriptEntries.add(entry.name to entry)
                    entry.isDirectory -> entry.listFiles()?.filter { it.isFile && it.extension == "py" }
                        ?.forEach { scriptEntries.add("${entry.name}/${it.name}" to it) }
                }
            }
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                for ((entryPath, file) in scriptEntries) {
                    zos.putNextEntry(java.util.zip.ZipEntry(entryPath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                if (includeLogs) {
                    getStorageDir(this, "logs").listFiles()?.filter { it.isFile }?.forEach { file ->
                        zos.putNextEntry(java.util.zip.ZipEntry("logs/${file.name}"))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val manifest = org.json.JSONObject().apply {
                    put("app", "Far_Auto")
                    put("manifest_version", 1)
                    put("exported_at", System.currentTimeMillis())
                    put("script_count", scriptEntries.size)
                    put("scripts", org.json.JSONArray(scriptEntries.map { it.first }))
                    put("includes_logs", includeLogs)
                }
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write(manifest.toString(2).toByteArray())
                zos.closeEntry()
            }
            // Auto-save a visible copy to the public Downloads folder (survives uninstall, no special permission).
            val saved = saveToDownloads(zipFile)
            Toast.makeText(
                this,
                if (saved != null) "Saved to $saved" else "Saved to app storage (share to keep a copy)",
                Toast.LENGTH_LONG,
            ).show()

            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", zipFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Backup"))
        } catch (e: Exception) {
            Log.e("FarAuto", "Export failed", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scriptsDir() = getStorageDir(this, "scripts")

    /** Folder a script lives in: "" for the scripts/ root (Uncategorized), else the subfolder name. */
    private fun profileOf(file: File): String =
        if (file.parentFile?.absolutePath == scriptsDir().absolutePath) "" else (file.parentFile?.name ?: "")

    private fun refreshScripts() {
        allScripts.clear()
        profiles.clear()
        // Scripts may live in the root (uncategorized) or in one-level profile subfolders.
        // Flatten both so nothing vanishes from the in-app list; collect folder names for the chips.
        scriptsDir().listFiles()?.forEach { entry ->
            when {
                entry.isFile && entry.extension == "py" -> allScripts.add(entry)
                entry.isDirectory -> {
                    profiles.add(entry.name)
                    entry.listFiles()?.filter { it.isFile && it.extension == "py" }?.let { allScripts.addAll(it) }
                }
            }
        }
        profiles.sortBy { it.lowercase() }
        // If the active folder was deleted/renamed away, fall back to All.
        if (activeFolder != null && activeFolder != "" && activeFolder !in profiles) activeFolder = null
        applyView()
    }

    /** Rebuilds the displayed list from [allScripts] applying the active folder, search, and sort. */
    private fun applyView() {
        buildFolderChips()
        val q = searchQuery.trim().lowercase()
        var filtered = allScripts.toList()
        if (activeFolder != null) filtered = filtered.filter { profileOf(it) == activeFolder }
        if (q.isNotEmpty()) filtered = filtered.filter { it.name.lowercase().contains(q) }
        val sorted = when (sortMode) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortMode.MODIFIED_DESC -> filtered.sortedByDescending { it.lastModified() }
            SortMode.MODIFIED_ASC -> filtered.sortedBy { it.lastModified() }
        }
        scripts.clear()
        scripts.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    // ---- Folders (profiles) ----

    /** Rebuilds the folder filter chip row: All · Uncategorized · <each folder> · + Folder. */
    private fun buildFolderChips() {
        val group = findViewById<ChipGroup>(R.id.folderChips) ?: return
        group.removeAllViews()
        val accent = ContextCompat.getColor(this, R.color.accent)
        val surface = ContextCompat.getColor(this, R.color.surface)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val white = ContextCompat.getColor(this, R.color.white)

        fun makeChip(label: String, value: String?, count: Int?): Chip {
            val active = activeFolder == value
            return Chip(this).apply {
                text = if (count != null) "$label  $count" else label
                isCheckable = false
                isClickable = true
                chipStrokeWidth = 0f
                chipBackgroundColor = ColorStateList.valueOf(if (active) accent else surface)
                setTextColor(if (active) white else textPrimary)
                setOnClickListener { if (activeFolder != value) { activeFolder = value; applyView() } }
            }
        }

        val uncategorized = allScripts.count { profileOf(it).isEmpty() }
        group.addView(makeChip("All", null, allScripts.size))
        group.addView(makeChip("Uncategorized", "", uncategorized))
        profiles.forEach { name ->
            val count = allScripts.count { profileOf(it) == name }
            group.addView(makeChip(name, name, count).apply {
                setOnLongClickListener { showFolderOptions(this, name); true }
            })
        }
        // Trailing "+ Folder" chip to create a new one.
        group.addView(Chip(this).apply {
            text = "+ Folder"
            isCheckable = false
            chipStrokeWidth = 0f
            chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent_dim))
            setTextColor(accent)
            setOnClickListener { createFolder() }
        })
    }

    private fun showFolderOptions(anchor: View, name: String) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "Rename folder")
        popup.menu.add(0, 1, 1, "Delete folder")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> renameFolder(name)
                1 -> deleteFolder(name)
            }
            true
        }
        popup.show()
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> toast("Folder name can't be empty")
                    profiles.any { it.equals(name, ignoreCase = true) } -> toast("A folder named \"$name\" already exists")
                    File(scriptsDir(), name).mkdirs() -> { activeFolder = name; refreshScripts(); toast("Folder created") }
                    else -> toast("Could not create folder")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameFolder(oldName: String) {
        val input = EditText(this).apply { setText(oldName) }
        AlertDialog.Builder(this)
            .setTitle("Rename folder")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() || newName == oldName -> {}
                    profiles.any { it != oldName && it.equals(newName, ignoreCase = true) } -> toast("A folder named \"$newName\" already exists")
                    File(scriptsDir(), oldName).renameTo(File(scriptsDir(), newName)) -> {
                        if (activeFolder == oldName) activeFolder = newName
                        refreshScripts(); toast("Folder renamed")
                    }
                    else -> toast("Could not rename folder")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFolder(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete folder")
            .setMessage("Delete the folder \"$name\"? Any scripts inside it will be moved to Uncategorized (not deleted).")
            .setPositiveButton("Delete") { _, _ ->
                val dir = File(scriptsDir(), name)
                var moved = 0
                dir.listFiles()?.filter { it.isFile }?.forEach { if (moveScriptSafely(it, scriptsDir())) moved++ }
                dir.deleteRecursively()
                if (activeFolder == name) activeFolder = null
                refreshScripts()
                toast(if (moved > 0) "Folder deleted — $moved script(s) moved to Uncategorized" else "Folder deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Move dialog for a single script: pick Uncategorized, an existing folder, or a new one. */
    private fun showMoveDialog(file: File) {
        val current = profileOf(file)
        val destinations = ArrayList<Pair<String, String?>>() // label -> profile value ("" root, name, null=new)
        if (current.isNotEmpty()) destinations.add("Uncategorized" to "")
        profiles.filter { it != current }.forEach { destinations.add(it to it) }
        destinations.add("+ New folder…" to null)
        val labels = destinations.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move \"${file.name}\" to")
            .setItems(labels) { _, which ->
                val dest = destinations[which].second
                if (dest == null) {
                    promptNewFolderThenMove(file)
                } else {
                    moveScriptToFolder(file, dest)
                }
            }
            .show()
    }

    private fun promptNewFolderThenMove(file: File) {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create & move") { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> toast("Folder name can't be empty")
                    profiles.any { it.equals(name, ignoreCase = true) } -> toast("A folder named \"$name\" already exists")
                    File(scriptsDir(), name).mkdirs() || File(scriptsDir(), name).isDirectory -> moveScriptToFolder(file, name)
                    else -> toast("Could not create folder")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveScriptToFolder(file: File, destProfile: String) {
        val destDir = if (destProfile.isEmpty()) scriptsDir() else File(scriptsDir(), destProfile)
        if (file.parentFile?.absolutePath == destDir.absolutePath) return
        if (moveScriptSafely(file, destDir)) {
            activeFolder = if (destProfile.isEmpty()) "" else destProfile
            refreshScripts()
            toast(if (destProfile.isEmpty()) "Moved to Uncategorized" else "Moved to \"$destProfile\"")
        } else toast("Move failed")
    }

    /** Move [src] into [destDir], picking a non-clobbering "name (n).py" if needed. */
    private fun moveScriptSafely(src: File, destDir: File): Boolean {
        destDir.mkdirs()
        var dest = File(destDir, src.name)
        if (dest.exists()) {
            val dot = src.name.lastIndexOf('.')
            val base = if (dot > 0) src.name.substring(0, dot) else src.name
            val ext = if (dot > 0) src.name.substring(dot) else ""
            var n = 1
            while (dest.exists()) { dest = File(destDir, "$base ($n)$ext"); n++ }
        }
        if (src.renameTo(dest)) return true
        return try { src.copyTo(dest, overwrite = false); src.delete(); true }
        catch (e: Exception) { Log.e("FarAuto", "Move failed: ${src.name}", e); false }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Long-press menu on a script row: Rename / Move to folder (system script: nothing). */
    private fun showScriptOptions(file: File, anchor: View) {
        if (file.name == "ui_explorer.py") return // system utility — not renamable/movable
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "Rename")
        popup.menu.add(0, 1, 1, "Move to folder…")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> showRenameDialog(file)
                1 -> showMoveDialog(file)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(file: File) {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val fixed = if (newName.endsWith(".py")) newName else "$newName.py"
                    if (file.renameTo(File(file.parentFile, fixed))) refreshScripts()
                    else toast("Could not rename")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runScript(file: File) {
        val intent = Intent(this, ScriptExecutionService::class.java).apply { putExtra("SCRIPT_PATH", file.absolutePath) }
        startForegroundService(intent)
    }

    private fun editScript(file: File) {
        startActivity(Intent(this, EditorActivity::class.java).apply { putExtra("FILE_PATH", file.absolutePath) })
    }

    class ScriptAdapter(
        private val scripts: List<File>,
        private val onRun: (File) -> Unit,
        private val onEdit: (File) -> Unit,
        private val onDelete: (File) -> Unit,
        private val onOptions: (File, View) -> Unit,
        private val subtitleProvider: (File) -> String = { "Python Script" },
        private val onSelectionChanged: () -> Unit = {}
    ) : RecyclerView.Adapter<ScriptAdapter.ViewHolder>() {

        /** Insertion-ordered selection — order of ticking is the default run order. */
        val selectedOrder = LinkedHashSet<File>()
        var selectionMode: Boolean = false
            set(value) {
                field = value
                if (!value) selectedOrder.clear()
                notifyDataSetChanged()
            }

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.scriptName)
            val subtitle: TextView = v.findViewById(R.id.scriptSubtitle)
            val check: CheckBox = v.findViewById(R.id.scriptSelect)
            val btnRun: View = v.findViewById(R.id.btnRun)
            val btnEdit: View = v.findViewById(R.id.btnEdit)
            val btnDel: View = v.findViewById(R.id.btnDel)
        }
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_script, parent, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val f = scripts[p]
            val isSystem = f.name == "ui_explorer.py"
            h.name.text = f.name
            h.subtitle.text = if (isSystem) "System utility" else subtitleProvider(f)
            if (isSystem) (h.btnDel as? android.widget.ImageButton)?.setImageResource(android.R.drawable.ic_menu_revert)
            else (h.btnDel as? android.widget.ImageButton)?.setImageResource(android.R.drawable.ic_menu_delete)

            val actionVis = if (selectionMode) View.GONE else View.VISIBLE
            h.btnRun.visibility = actionVis
            h.btnEdit.visibility = actionVis
            h.btnDel.visibility = actionVis
            h.check.visibility = if (selectionMode) View.VISIBLE else View.GONE

            // ui_explorer.py is a system utility — not selectable for sequence runs or bulk delete.
            if (isSystem) selectedOrder.remove(f)
            h.check.setOnCheckedChangeListener(null)
            h.check.isEnabled = !isSystem
            h.check.alpha = if (isSystem) 0.4f else 1f
            h.check.isChecked = selectedOrder.contains(f)
            h.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedOrder.add(f) else selectedOrder.remove(f)
                onSelectionChanged()
            }

            if (selectionMode) {
                h.itemView.setOnClickListener { if (!isSystem) h.check.isChecked = !h.check.isChecked }
                h.itemView.setOnLongClickListener(null)
            } else {
                h.btnRun.setOnClickListener { onRun(f) }
                h.btnEdit.setOnClickListener { onEdit(f) }
                h.btnDel.setOnClickListener { onDelete(f) }
                h.itemView.setOnClickListener(null)
                h.itemView.setOnLongClickListener { onOptions(f, h.itemView); true }
            }
        }
        override fun getItemCount() = scripts.size
    }

    /** Reorderable list of the selected scripts shown in the Run Sequence dialog. */
    class SequenceAdapter(val items: MutableList<File>) : RecyclerView.Adapter<SequenceAdapter.ViewHolder>() {
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val position: TextView = v.findViewById(R.id.seqPosition)
            val name: TextView = v.findViewById(R.id.seqName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sequence, parent, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            h.position.text = "${p + 1}."
            h.name.text = items[p].name
        }
        override fun getItemCount() = items.size

        fun move(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
            notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
        }
    }
}
