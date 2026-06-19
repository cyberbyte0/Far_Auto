package com.fareed.auto

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var editor: CodeEditor
    private lateinit var currentFile: File
    private var savedContent = ""
    private var isDirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val path = intent.getStringExtra("FILE_PATH") ?: finish().run { return }
        currentFile = File(path)

        editor = findViewById<CodeEditor>(R.id.editor)
        val initial = currentFile.readText()
        editor.setText(initial)
        savedContent = initial
        editor.isLineNumberEnabled = true
        updateTitle()

        // Subscribed after setText so the initial load isn't counted; compares against the saved
        // content so typing back to the original clears the dirty state too.
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            setDirty(editor.text.toString() != savedContent)
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { attemptExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { attemptExit() }
        })

        findViewById<View>(R.id.btnSave).setOnClickListener {
            saveFile()
        }

        findViewById<View>(R.id.btnCheck).setOnClickListener {
            checkSyntax()
        }

        findViewById<View>(R.id.btnRun).setOnClickListener {
            saveFile()
            runScript()
        }
    }

    private fun updateTitle() {
        findViewById<TextView>(R.id.tvFileName).text =
            if (isDirty) "• ${currentFile.name}" else currentFile.name
    }

    private fun setDirty(value: Boolean) {
        if (isDirty == value) return
        isDirty = value
        updateTitle()
    }

    private fun attemptExit() {
        if (!isDirty) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("You have unsaved changes. Save before leaving?")
            .setPositiveButton("Save") { _, _ -> saveFile(); finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun saveFile() {
        val text = editor.text.toString()
        currentFile.writeText(text)
        savedContent = text
        setDirty(false)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun runScript() {
        if (FarAutoAccessibilityService.instance == null) {
            Toast.makeText(this, "Enable Accessibility first!", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ScriptExecutionService::class.java).apply {
            putExtra("SCRIPT_PATH", currentFile.absolutePath)
        }
        startForegroundService(intent)
        Toast.makeText(this, "Running...", Toast.LENGTH_SHORT).show()
    }

    private fun checkSyntax() {
        saveFile()
        try {
            val py = Python.getInstance()
            py.getModule("builtins")!!.get("compile")!!.call(
                editor.text.toString(),
                currentFile.name,
                "exec"
            )
            Toast.makeText(this, "Syntax OK", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Syntax Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
