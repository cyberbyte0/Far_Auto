package com.fareed.auto

import java.io.File

/** Strips path separators, control characters (including null bytes), and caps to 128 chars. */
internal fun sanitizeFileName(fileName: String): String =
    fileName
        .substringAfterLast("/")
        .substringAfterLast("\\")
        .replace(Regex("[\\x00-\\x1f]"), "")
        .take(128)
        .let { if (it == "." || it == "..") "unnamed" else it }
        .ifEmpty { "unnamed" }

internal fun safeFileIn(baseDir: File, fileName: String): File =
    File(baseDir, sanitizeFileName(fileName))

/**
 * Resolves a profile (sub)folder under [scriptsDir]. A blank profile means the root itself
 * (uncategorized scripts). The name is sanitized to a single safe segment, so traversal
 * outside [scriptsDir] is impossible.
 */
internal fun safeProfileDir(scriptsDir: File, profile: String): File =
    if (profile.isBlank()) scriptsDir else File(scriptsDir, sanitizeFileName(profile))

/** Resolves a script identified by ([profile], [fileName]) safely under [scriptsDir]. */
internal fun safeScriptFile(scriptsDir: File, profile: String, fileName: String): File =
    File(safeProfileDir(scriptsDir, profile), sanitizeFileName(fileName))
