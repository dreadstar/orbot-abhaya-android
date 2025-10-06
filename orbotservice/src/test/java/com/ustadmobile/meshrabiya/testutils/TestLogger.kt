package com.ustadmobile.meshrabiya.testutils

import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight test logging helper for JVM unit tests.
 * - Writes to build/logs/test-ulog.log (appends)
 * - Prints to stderr
 * - Truncates messages longer than DEFAULT_MAX_CHARS (keeps prefix and suffix)
 */
object TestLogger {
    private val logFile = File("build/logs/test-ulog.log")
    private val maxChars = System.getProperty("testlogger.maxchars")?.toIntOrNull() ?: 8000
    private val reservedSuffix = 512
    private val counter = AtomicLong(0)

    init {
        try {
            if (!logFile.parentFile.exists()) logFile.parentFile.mkdirs()
        } catch (_: Exception) {}
    }

    fun log(tag: String, level: String = "I", msg: String) {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val id = counter.incrementAndGet()
        val out = formatMessage(now, id, tag, level, msg)

        try {
            logFile.appendText(out + "\n")
        } catch (_: Exception) {}

        // Also print to stderr so Gradle captures it in console
        System.err.println(out)
    }

    private fun formatMessage(ts: String, id: Long, tag: String, level: String, msg: String): String {
        val truncated = if (msg.length <= maxChars) {
            msg
        } else {
            val keepPrefix = (maxChars - reservedSuffix - 40).coerceAtLeast(200)
            val prefix = msg.substring(0, keepPrefix)
            val suffix = msg.takeLast(reservedSuffix)
            "${prefix}\n---- TRUNCATED (len=${msg.length}) ----\n${suffix}"
        }
        return "[$ts] [$id] [$level] $tag: $truncated"
    }
}
