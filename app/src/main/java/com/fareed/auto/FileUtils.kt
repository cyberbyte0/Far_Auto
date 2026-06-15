package com.fareed.auto

import java.io.File

/** Strips path separators, control characters (including null bytes), and caps to 128 chars. */
internal fun sanitizeFileName(fileName: String): String =
    fileName
        .substringAfterLast("/")
        .substringAfterLast("\\")
        .replace(Regex("[\\x00-\\x1f]"), "")
        .take(128)
        .ifEmpty { "unnamed" }

internal fun safeFileIn(baseDir: File, fileName: String): File =
    File(baseDir, sanitizeFileName(fileName))
