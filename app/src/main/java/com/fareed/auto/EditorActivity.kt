package com.fareed.auto

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var editor: CodeEditor
    private lateinit var currentFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val path = intent.getStringExtra("FILE_PATH") ?: finish().run { return }
        currentFile = File(path)
        
        findViewById<TextView>(R.id.tvFileName).text = currentFile.name

        editor = findViewById<CodeEditor>(R.id.editor)
        editor.setText(currentFile.readText())
        
        editor.isLineNumberEnabled = true

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

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

    private fun saveFile() {
        currentFile.writeText(editor.text.toString())
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
