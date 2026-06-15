package com.fareed.auto

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ScriptAdapter
    private val scripts = mutableListOf<File>()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importScript(it) }
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
                    file.delete()
                    refreshScripts()
                }
            },
            onRename = { file ->
                if (file.name != "ui_explorer.py") {
                    val input = android.widget.EditText(this).apply { setText(file.name) }
                    AlertDialog.Builder(this).setTitle("Rename").setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val newName = input.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                val fixed = if (newName.endsWith(".py")) newName else "$newName.py"
                                if (file.renameTo(File(file.parentFile, fixed))) refreshScripts()
                            }
                        }.show()
                }
            }
        )
        recyclerView.adapter = adapter

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

        findViewById<View>(R.id.menuCreate).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; createNewScript()
        }
        findViewById<View>(R.id.menuImport).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; importLauncher.launch("*/*")
        }
        findViewById<View>(R.id.menuExport).setOnClickListener {
            closeFabMenu(btnNew, fabMenu); isMenuOpen = false; exportAllScripts()
        }
    }

    private fun closeFabMenu(fab: com.google.android.material.floatingactionbutton.FloatingActionButton, menu: View) {
        fab.animate().rotation(0f).setDuration(300).start()
        menu.animate().alpha(0f).translationY(50f).setDuration(300).withEndAction { menu.visibility = View.GONE }.start()
    }

    private fun createNewScript() {
        val dir = getStorageDir(this, "scripts")
        val file = File(dir, "script_${System.currentTimeMillis()}.py")
        file.writeText("# New Script\nimport automator\n")
        refreshScripts()
        editScript(file)
    }

    private fun importScript(uri: Uri) {
        try {
            val name = "imported_${System.currentTimeMillis()}.py"
            val dest = File(getStorageDir(this, "scripts"), name)
            contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
            refreshScripts()
        } catch (e: Exception) { Log.e("FarAuto", "Import failed", e) }
    }

    private fun exportAllScripts() {
        val zipFile = File(getExternalFilesDir("exports"), "Backup.zip")
        try {
            java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
                getStorageDir(this, "scripts").listFiles()?.forEach { file ->
                    zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", zipFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Backup"))
        } catch (e: Exception) { Log.e("FarAuto", "Export failed", e) }
    }

    private fun refreshScripts() {
        scripts.clear()
        getStorageDir(this, "scripts").listFiles()?.filter { it.extension == "py" }?.let { scripts.addAll(it) }
        adapter.notifyDataSetChanged()
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
        private val onRename: (File) -> Unit
    ) : RecyclerView.Adapter<ScriptAdapter.ViewHolder>() {
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.scriptName)
            val btnRun: View = v.findViewById(R.id.btnRun)
            val btnEdit: View = v.findViewById(R.id.btnEdit)
            val btnDel: View = v.findViewById(R.id.btnDel)
        }
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_script, parent, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val f = scripts[p]
            h.name.text = f.name
            if (f.name == "ui_explorer.py") (h.btnDel as? android.widget.ImageButton)?.setImageResource(android.R.drawable.ic_menu_revert)
            else (h.btnDel as? android.widget.ImageButton)?.setImageResource(android.R.drawable.ic_menu_delete)
            h.btnRun.setOnClickListener { onRun(f) }
            h.btnEdit.setOnClickListener { onEdit(f) }
            h.btnDel.setOnClickListener { onDelete(f) }
            h.itemView.setOnLongClickListener { onRename(f); true }
        }
        override fun getItemCount() = scripts.size
    }
}
