package com.ustadmobile.meshrabiya.testutils

import java.io.File

/**
 * Polls for a file to exist up to the given timeout (ms). Returns true if the file exists within the
 * timeout window, false otherwise. Poll interval is 50ms.
 */
fun waitForFile(file: File, timeoutMs: Long = 5_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() <= deadline) {
        if (file.exists()) return true
        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
    }
    return file.exists()
}
