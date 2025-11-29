package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verify that ComputeMeshNetworkAdapter delegates to a Meshrabiya MeshNetworkInterface when provided
 * and converts TaskExecutionRequest into DistributedFileInfo via MeshrabiyaInterop.
 */
internal class ComputeMeshNetworkAdapterTest {

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

        // Implement other MeshNetworkInterface members with correct signatures
        override suspend fun queryFileAvailability(path: String): List<String> = emptyList()
        override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? = null
        override suspend fun getAvailableStorageNodes(): List<String> = emptyList()
        override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
    }

    @Test
    fun `adapter delegates compute request to meshrabiya delegate`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val adapter = org.torproject.android.ui.mesh.ComputeMeshNetworkAdapter(meshrabiyaDelegate = fake)

        // Create a simple TaskExecutionRequest with small payload and timeout
        val req = IntelligentDistributedComputeService.TaskExecutionRequest(
            taskId = "t1",
            taskData = "hello".toByteArray(),
            timeoutMs = 5000L
        )

        val resp = adapter.executeRemoteTask("node-1", req)

        // Adapter returns a success object (mock) if delegate invoked successfully
        assertNotNull(resp)

        // Ensure the fake delegate recorded a call
        assertEquals("node-1", fake.lastTarget)
        assertNotNull(fake.lastFileInfo)
        assertEquals(com.ustadmobile.meshrabiya.storage.StorageOperation.REPLICATE, fake.lastOperation)

        // DistributedFileInfo should reference a local file path via localReference.localPath
        val path = fake.lastFileInfo?.localReference?.localPath ?: fail("DistributedFileInfo.localReference.localPath missing")
        assertTrue(File(path).exists())
    }
}
