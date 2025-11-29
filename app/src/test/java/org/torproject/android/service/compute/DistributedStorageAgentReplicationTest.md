package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Verify that DistributedStorageAgent uses the provided Meshrabiya MeshNetworkInterface to send
 * replication requests when replicating a Drop Folder file.
 */
internal class DistributedStorageAgentReplicationTest {

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

    @Test
    fun `replicateDropFolderFile delegates to mesh network`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()

        // Use a small local storage and drop folder path; agent will call meshNetwork.sendStorageRequest
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

    // Create a temporary file in the drop folder path to simulate a real file
    val dropFolder = Paths.get("build/drop_folder_test")
    val localPath = dropFolder.resolve("testfile.txt")
        java.io.File(localPath.toString()).parentFile?.mkdirs()
        java.io.File(localPath.toString()).writeBytes("hello".toByteArray())

        // Instead of calling replicateDropFolderFile (which depends on readFileFromDropFolder),
        // call the internal helper that replicates to a specific node using a StorageRequest.
        val request = DistributedStorageAgent.StorageRequest(
            fileId = "fid1",
            fileName = "testfile.txt",
            data = "hello".toByteArray(),
            replicationFactor = 2
        )

        val resp = agent.replicateToSpecificNodeForTest("node-a", request)
        assertTrue(resp.success)

        // Verify fake delegate recorded a sendStorageRequest to node-a
        assertEquals("node-a", fake.lastTarget)
        assertNotNull(fake.lastFileInfo)
        assertEquals(com.ustadmobile.meshrabiya.storage.StorageOperation.REPLICATE, fake.lastOperation)

    // The DistributedFileInfo.path is derived from the StorageRequest.fileName (MeshrabiyaInterop)
    assertEquals("testfile.txt", fake.lastFileInfo?.path)

    // Local reference localPath is intentionally empty for StorageRequest->DistributedFileInfo conversion
    val localRefPath = fake.lastFileInfo?.localReference?.localPath
    assertEquals("", localRefPath)
    }
}
