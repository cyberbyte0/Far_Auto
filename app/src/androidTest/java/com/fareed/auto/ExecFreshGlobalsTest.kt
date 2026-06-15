package com.fareed.auto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the exec() fresh-globals fix (H3).
 *
 * Before the fix: scripts shared __main__.__dict__ — a global defined in script A
 * was visible in script B.
 * After the fix: each run gets a fresh dict; exec() auto-injects __builtins__.
 */
@RunWith(AndroidJUnit4::class)
class ExecFreshGlobalsTest {

    private lateinit var py: Python
    private lateinit var builtins: com.chaquo.python.PyObject

    @Before
    fun startPython() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(ctx))
        }
        py = Python.getInstance()
        builtins = py.getModule("builtins")!!
    }

    /**
     * Runs [script] in a fresh globals dict.
     * Uses callAttr("exec") so Chaquopy dispatches via __call__ not getattr.
     * Returns the globals dict after execution.
     */
    private fun runInFreshGlobals(script: String): com.chaquo.python.PyObject {
        val globals = builtins.callAttr("dict")
        globals.callAttr("__setitem__", "__name__", "__main__")
        builtins.callAttr("exec", script, globals)
        return globals
    }

    /** Gets a dict item safely: callAttr("get", key) maps to Python dict.get(key). */
    private fun dictGet(d: com.chaquo.python.PyObject, key: String) =
        d.callAttr("get", key)

    // ── isolation tests ───────────────────────────────────────────────────────

    @Test
    fun variable_from_script_A_not_visible_in_script_B() {
        runInFreshGlobals("secret = 42")

        val globals2 = runInFreshGlobals("""
            try:
                _ = secret
                found = True
            except NameError:
                found = False
        """.trimIndent())

        assertEquals(
            "Variable 'secret' from script A must not be visible in script B's fresh globals",
            false, dictGet(globals2, "found")?.toJava(Boolean::class.java),
        )
    }

    @Test
    fun import_from_script_A_not_visible_in_script_B() {
        runInFreshGlobals("import os")

        val globals2 = runInFreshGlobals("result = 'os' in dir()")
        assertEquals(
            "Import 'os' from script A must not appear in script B's fresh globals",
            false, dictGet(globals2, "result")?.toJava(Boolean::class.java),
        )
    }

    @Test
    fun each_run_starts_with_only_builtins() {
        val globals = runInFreshGlobals("") // empty script
        // list(dict) returns keys; __builtins__ is the only key exec() adds
        val keys = builtins.callAttr("list", globals).asList().map { it.toString() }
        assertTrue("Fresh globals must contain __builtins__", keys.contains("__builtins__"))
        assertTrue("Fresh globals must contain __name__", keys.contains("__name__"))
        assertEquals(
            "Fresh globals must contain exactly two keys (__builtins__ + __name__)",
            2, keys.size,
        )
    }

    @Test
    fun script_B_can_define_its_own_variable_with_same_name() {
        runInFreshGlobals("x = 100")

        val globals2 = runInFreshGlobals("x = 999")
        assertEquals(999, dictGet(globals2, "x")?.toJava(Int::class.java))
    }

    @Test
    fun builtins_available_in_fresh_globals() {
        val globals = runInFreshGlobals("result = len([1, 2, 3])")
        assertEquals(3, dictGet(globals, "result")?.toJava(Int::class.java))
    }

    @Test
    fun import_works_in_fresh_globals() {
        val globals = runInFreshGlobals("import os; result = os.path.sep")
        val sep = dictGet(globals, "result")?.toString()
        assertNotNull(sep)
        assertTrue("Path separator must be / or \\", sep == "/" || sep == "\\")
    }

    @Test
    fun main_guard_executes_when_name_is_main() {
        // Regression test: before the __name__ fix, `if __name__ == "__main__":` blocks
        // never ran because __name__ was not set in fresh globals.
        val globals = runInFreshGlobals("""
            executed = False
            if __name__ == "__main__":
                executed = True
        """.trimIndent())
        assertEquals(
            "`if __name__ == \"__main__\":` block must execute in fresh globals",
            true, dictGet(globals, "executed")?.toJava(Boolean::class.java),
        )
    }
}
