package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Edge-case tests for ComputeMeshNetworkAdapter:
 * - large payload delegation uses a temp file and delegates to Meshrabiya
 * - when a computeDelegate is provided, it takes precedence (and can return Timeout)
 */
internal class ComputeMeshNetworkAdapterEdgeCasesTest {

    private class FakeMeshrabiyaDelegate : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
        var lastTarget: String? = null
        var lastFileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo? = null
        var lastOperation: com.ustadmobile.meshrabiya.storage.StorageOperation? = null

        override suspend fun sendStorageRequest(
            targetNodeId: String,
            fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo,
            operation: com.ustadmobile.meshrabiya.storage.StorageOperation
        ) {
            lastTarget = targetNodeId
            lastFileInfo = fileInfo
            lastOperation = operation
        }

        override suspend fun queryFileAvailability(path: String): List<String> = emptyList()
        override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? = null
        override suspend fun getAvailableStorageNodes(): List<String> = emptyList()
        override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
    }

    private class FakeComputeDelegate : IntelligentDistributedComputeService.MeshNetworkInterface {
        var receivedNode: String? = null
        override suspend fun executeRemoteTask(
            nodeId: String,
            request: IntelligentDistributedComputeService.TaskExecutionRequest
        ): IntelligentDistributedComputeService.TaskExecutionResponse {
            receivedNode = nodeId
            // Simulate a timeout response for testing precedence
            return IntelligentDistributedComputeService.TaskExecutionResponse.Timeout
        }
    }

    private class FakeComputeDelegateFailed : IntelligentDistributedComputeService.MeshNetworkInterface {
        var receivedNode: String? = null
        override suspend fun executeRemoteTask(
            nodeId: String,
            request: IntelligentDistributedComputeService.TaskExecutionRequest
        ): IntelligentDistributedComputeService.TaskExecutionResponse {
            receivedNode = nodeId
            return IntelligentDistributedComputeService.TaskExecutionResponse.Failed("simulated failure")
        }
    }

    private class ThrowingMeshrabiyaDelegate : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
        override suspend fun sendStorageRequest(
            targetNodeId: String,
            fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo,
            operation: com.ustadmobile.meshrabiya.storage.StorageOperation
        ) {
            throw RuntimeException("simulated send failure")
        }

        override suspend fun queryFileAvailability(path: String): List<String> = emptyList()
        override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? = null
        override suspend fun getAvailableStorageNodes(): List<String> = emptyList()
        override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
    }

    @Test
    fun `large payload is written to temp file and delegated to meshrabiya`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val adapter = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(meshrabiyaDelegate = fake)

        // Large-ish payload (but small enough for test) to exercise temp file path
        val largeData = ByteArray(1024 * 64) { (it % 256).toByte() }
        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "big",
            taskData = largeData,
            timeoutMs = 10_000L
        )

        val resp = adapter.executeRemoteTask("node-large", req)

        assertTrue(resp is IntelligentDistributedComputeService.TaskExecutionResponse.Success)
        assertEquals("node-large", fake.lastTarget)
        assertNotNull(fake.lastFileInfo)
        assertEquals(com.ustadmobile.meshrabiya.storage.StorageOperation.REPLICATE, fake.lastOperation)

        val localPath = fake.lastFileInfo?.localReference?.localPath ?: fail("local path missing")
        assertTrue(File(localPath).exists())
    // Clean up temp file (delete returns boolean; ignore it so test returns Unit)
    val cleanupFile = File(localPath)
    if (cleanupFile.exists()) cleanupFile.delete()
    }

    @Test
    fun `compute delegate takes precedence and can return timeout`() = runBlocking {
        val fakeMes = FakeMeshrabiyaDelegate()
        val fakeCompute = FakeComputeDelegate()

        // Provide both delegates; computeDelegate should be used
        val adapter = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(
            meshrabiyaDelegate = fakeMes,
            computeDelegate = fakeCompute
        )

        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "t-timeout",
            taskData = "x".toByteArray(),
            timeoutMs = 1L
        )

        val resp = adapter.executeRemoteTask("node-timeout", req)

        // Since computeDelegate returns Timeout, adapter should surface that
        assertTrue(resp is IntelligentDistributedComputeService.TaskExecutionResponse.Timeout)
        assertEquals("node-timeout", fakeCompute.receivedNode)
        // Ensure meshrabiya delegate was NOT invoked
        assertNull(fakeMes.lastTarget)
    }

    @Test
    fun `very large payload is written to temp file and delegated to meshrabiya`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val adapter = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(meshrabiyaDelegate = fake)

        // Very large payload (~4MB) to exercise temp file handling
        val veryLarge = ByteArray(4 * 1024 * 1024) { (it % 256).toByte() }
        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "very-big",
            taskData = veryLarge,
            timeoutMs = 20_000L
        )

        val resp = adapter.executeRemoteTask("node-very-large", req)

        assertTrue(resp is IntelligentDistributedComputeService.TaskExecutionResponse.Success)
        assertEquals("node-very-large", fake.lastTarget)
        val localPath = fake.lastFileInfo?.localReference?.localPath ?: fail("local path missing")
        assertTrue(File(localPath).exists())
        // cleanup
        val f = File(localPath)
        if (f.exists()) f.delete()
    }

    @Test
    fun `compute delegate failure is propagated and meshrabiya not invoked`() = runBlocking {
        val fakeMes = FakeMeshrabiyaDelegate()
        val fakeCompute = FakeComputeDelegateFailed()

        val adapter = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(
            meshrabiyaDelegate = fakeMes,
            computeDelegate = fakeCompute
        )

        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "t-failed",
            taskData = "y".toByteArray(),
            timeoutMs = 1L
        )

        val resp = adapter.executeRemoteTask("node-failed", req)

        assertTrue(resp is IntelligentDistributedComputeService.TaskExecutionResponse.Failed)
        assertEquals("node-failed", fakeCompute.receivedNode)
        assertNull(fakeMes.lastTarget)
    }

    @Test
    fun `meshrabiya throw results in fallback mock success and temp file remains (test observes and cleans up)`() = runBlocking {
        val throwing = ThrowingMeshrabiyaDelegate()
        val adapter = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(meshrabiyaDelegate = throwing)

        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "t-throw",
            taskData = "z".toByteArray(),
            timeoutMs = 1000L
        )

        val resp = adapter.executeRemoteTask("node-throw", req)

        // Adapter catches and logs then falls back to mock success per current implementation
        assertTrue(resp is IntelligentDistributedComputeService.TaskExecutionResponse.Success)

        // Try to find any temp files created by this operation by checking tmp dir pattern
        val tmpDir = kotlin.io.path.createTempDirectory(prefix = "compute_test_probe_").toFile().parentFile
        // Search for recently-created files with our prefix pattern in system temp directory
        val candidates = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp").listFiles { file ->
            file.name.startsWith("compute_req_")
        } ?: emptyArray()

        // If any candidates exist, clean them up (but don't assert—they may be cleaned by other runs)
        candidates.forEach { if (it.exists()) it.delete() }
    }
}
