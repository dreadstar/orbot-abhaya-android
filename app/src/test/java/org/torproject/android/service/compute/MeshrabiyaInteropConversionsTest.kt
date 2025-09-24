package org.torproject.android.service.compute

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for conversion helpers in MeshrabiyaInterop.kt
 */
class MeshrabiyaInteropConversionsTest {

    @Test
    fun `resource requirements round trip`() {
        val original = ResourceRequirements(
            minRAMMB = 64,
            preferredRAMMB = 128,
            cpuIntensity = CPUIntensity.MODERATE,
            requiresGPU = false,
            requiresNPU = false,
            requiresStorage = true,
            minStorageGB = 0.5f
        )

        val um = original.toMeshrabiya()
        val round = um.toAppResourceRequirements()

        assertEquals(original.minRAMMB, round.minRAMMB)
        assertEquals(original.requiresStorage, round.requiresStorage)
    }

    @Test
    fun `task execution request to distributed file info conversion`() {
        val payload = "payload-data".toByteArray()
        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "task-42",
            taskData = payload,
            timeoutMs = 2000L
        )

        // write to temp file and convert
        val temp = File.createTempFile("taskreq", ".bin")
        temp.writeBytes(payload)
        val df = req.toDistributedFileInfo(temp.absolutePath)

        // Basic sanity checks on converted DistributedFileInfo
        assertNotNull(df)
        assertEquals("compute/requests/${req.taskId}", df.path)
        assertNotNull(df.localReference)
        assertTrue(df.localReference.localPath.isNotEmpty())
    }
}
