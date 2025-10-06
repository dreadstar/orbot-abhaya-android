package com.ustadmobile.meshrabiya.service

import java.io.File

object TestArtifactUtil {
    /**
     * Copy the given file into both canonical workspace artifact path and module-local
     * artifact path to make artifacts discoverable from the host workspace regardless
     * of Robolectric's runtime working directory.
     */
    fun copyToArtifacts(file: File) {
        try {
            val out1 = File("orbotservice/build/test-artifacts")
            out1.mkdirs()
            file.copyTo(File(out1, file.name), overwrite = true)
        } catch (_: Exception) {}

        try {
            val out2 = File("orbotservice/orbotservice/build/test-artifacts")
            out2.mkdirs()
            file.copyTo(File(out2, file.name), overwrite = true)
        } catch (_: Exception) {}
    }
}
