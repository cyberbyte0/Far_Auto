package com.fareed.auto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers fix M2: getSafeFile / sanitizeFileName strips control chars and limits length.
 * Also covers M1: Python save_screenshot / start_screen_record use os.path.basename —
 * the equivalent Kotlin sanitization logic is tested here.
 */
class FilenameSanitizationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── sanitizeFileName ──────────────────────────────────────────────────────

    @Test
    fun forward_slash_traversal_is_stripped() {
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
    }

    @Test
    fun absolute_unix_path_keeps_only_last_segment() {
        assertEquals("script.py", sanitizeFileName("/absolute/path/script.py"))
    }

    @Test
    fun windows_backslash_traversal_is_stripped() {
        assertEquals("passwd", sanitizeFileName("..\\..\\etc\\passwd"))
    }

    @Test
    fun null_byte_is_stripped() {
        val name = "script" + 0.toChar() + ".py" // 0x00 null byte
        assertEquals("script.py", sanitizeFileName(name))
    }

    @Test
    fun newline_is_stripped() {
        val name = "script" + '\n' + ".py"
        assertEquals("script.py", sanitizeFileName(name))
    }

    @Test
    fun carriage_return_is_stripped() {
        val name = "script" + '\r' + ".py"
        assertEquals("script.py", sanitizeFileName(name))
    }

    @Test
    fun all_control_chars_stripped() {
        // Build a name with every char in 0x00-0x1F, then append a safe suffix
        val dirty = (0..31).map { it.toChar() }.joinToString("") + "ok"
        assertEquals("ok", sanitizeFileName(dirty))
    }

    @Test
    fun name_over_128_chars_is_truncated() {
        val long = "a".repeat(200) + ".py"
        assertTrue(sanitizeFileName(long).length <= 128)
    }

    @Test
    fun name_exactly_128_chars_is_not_truncated() {
        val exact = "a".repeat(128)
        assertEquals(128, sanitizeFileName(exact).length)
    }

    @Test
    fun empty_result_after_stripping_returns_unnamed() {
        // String made entirely of control characters → stripped to "" → "unnamed"
        val onlyControlChars = (1..5).map { it.toChar() }.joinToString("")
        assertEquals("unnamed", sanitizeFileName(onlyControlChars))
    }

    @Test
    fun empty_string_returns_unnamed() {
        assertEquals("unnamed", sanitizeFileName(""))
    }

    @Test
    fun normal_filename_is_unchanged() {
        assertEquals("my_script.py", sanitizeFileName("my_script.py"))
    }

    @Test
    fun filename_with_spaces_is_unchanged() {
        // Spaces (0x20) are not control chars — they must be preserved
        assertEquals("my script.py", sanitizeFileName("my script.py"))
    }

    @Test
    fun filename_with_dots_is_unchanged() {
        assertEquals("script.v2.py", sanitizeFileName("script.v2.py"))
    }

    // ── safeFileIn ────────────────────────────────────────────────────────────

    @Test
    fun result_is_always_within_base_dir() {
        val base = tmp.newFolder("scripts")
        val file = safeFileIn(base, "../../escape.py")
        assertTrue(
            "File '${file.canonicalPath}' must be inside '${base.canonicalPath}'",
            file.canonicalPath.startsWith(base.canonicalPath + File.separator) ||
                file.canonicalPath == base.canonicalPath,
        )
    }

    @Test
    fun safe_result_is_a_direct_child_of_base_dir() {
        val base = tmp.newFolder("scripts")
        val file = safeFileIn(base, "../../dangerous/script.py")
        assertEquals(base.canonicalPath, file.canonicalFile.parentFile.canonicalPath)
    }

    @Test
    fun normal_name_produces_expected_path() {
        val base = tmp.newFolder("scripts")
        val file = safeFileIn(base, "hello.py")
        assertEquals(File(base, "hello.py").canonicalPath, file.canonicalPath)
    }
}
