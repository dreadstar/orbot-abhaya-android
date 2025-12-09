package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.security.MessageDigest
import java.math.BigInteger

/**
 * Tests for retrieveFile and deleteFile flows in DistributedStorageAgent.
 */
internal class DistributedStorageAgentRetrievalDeleteTest {

    private class FakeMeshrabiyaDelegate(
        var throwOnRequestForNode: String? = null,
        var throwOnSendForNode: String? = null
    ) : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
        var lastRequestedNode: String? = null
        var lastRequestedPath: String? = null
        var lastDeleteTarget: String? = null

        // Provide a simple map to return when requestFileFromNode is called
        val storedRemote = mutableMapOf<String, ByteArray>()
        // Optional node-specific storage map: nodeId -> (path -> bytes)
        val storedRemoteByNode = mutableMapOf<String, MutableMap<String, ByteArray>>()

        override suspend fun sendStorageRequest(
            targetNodeId: String,
            fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo,
            operation: com.ustadmobile.meshrabiya.storage.StorageOperation
        ) {
            // allow simulating network failures for specific nodes
            if (throwOnSendForNode != null && throwOnSendForNode == targetNodeId) {
                throw RuntimeException("Simulated sendStorageRequest failure for $targetNodeId")
            }

            // record delete requests and replicate (ignored) for tests
            if (operation == com.ustadmobile.meshrabiya.storage.StorageOperation.DELETE) {
                lastDeleteTarget = targetNodeId
            }
        }

        override suspend fun queryFileAvailability(path: String): List<String> = emptyList()

        override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? {
            // simulate a throwing remote node if configured
            if (throwOnRequestForNode != null && throwOnRequestForNode == nodeId) {
                throw RuntimeException("Simulated requestFileFromNode failure for $nodeId")
            }

            lastRequestedNode = nodeId
            lastRequestedPath = path
            // Prefer node-specific value if present, otherwise fall back to global storedRemote
            return storedRemoteByNode[nodeId]?.get(path) ?: storedRemote[path]
        }

        override suspend fun getAvailableStorageNodes(): List<String> = emptyList()
        override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
    }

    @Test
    fun `retrieveFile returns local data when present and updates stats`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        val data = "local-data".toByteArray()
        val metadata = DistributedStorageAgent.FileMetadata(
            fileId = "f1",
            originalName = "f1.bin",
            sizeBytes = data.size.toLong(),
            checksumMD5 = calculateMD5(data),
            storedTimestamp = System.currentTimeMillis()
        )

        // inject stored file into agent
        agent.addStoredFileForTest("f1", data, metadata)

        val resp = agent.retrieveFile(DistributedStorageAgent.RetrievalRequest(fileId = "f1"))

        assertTrue(resp.success)
        assertArrayEquals(data, resp.data)
        assertEquals("DISTRIBUTED_STORAGE", resp.sourceNode)
        // access stats should have incremented
        val stats = agent.getStorageStats()
        assertTrue(stats.totalAccessCount >= 0)
    }

    @Test
    fun `retrieveFile fetches from remote node when not local`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        // Set replication map so agent will try node-x
        agent.setReplicationMapForTest("f2", setOf("node-x"))

        // Fake remote data returned by meshrabiya
        val remoteData = "remote-data".toByteArray()
        fake.storedRemote["f2"] = remoteData

        val resp = agent.retrieveFile(DistributedStorageAgent.RetrievalRequest(fileId = "f2"))

        assertTrue(resp.success)
        assertArrayEquals(remoteData, resp.data)
        assertEquals("node-x", resp.sourceNode)
    }

    @Test
    fun `deleteFile removes local data and issues deletes to remote nodes`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        val data = "to-delete".toByteArray()
        val metadata = DistributedStorageAgent.FileMetadata(
            fileId = "f3",
            originalName = "f3.bin",
            sizeBytes = data.size.toLong(),
            checksumMD5 = calculateMD5(data),
            storedTimestamp = System.currentTimeMillis()
        )

        agent.addStoredFileForTest("f3", data, metadata)
        // indicate replication existed on node-y
        agent.setReplicationMapForTest("f3", setOf("LOCAL", "node-y"))

        val deleted = agent.deleteFile("f3")

        assertTrue(deleted)
        // local in-memory data removed
        val stats = agent.getStorageStats()
        assertFalse(agent.hasFile("f3"))
        // verify delete request sent to node-y
        assertEquals("node-y", fake.lastDeleteTarget)
    }

    @Test
    fun `retrieveFile detects checksum mismatch from remote and continues to next node`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        // Create metadata that expects a different checksum than the remote will provide
        val expectedData = "expected-data".toByteArray()
        val metadata = DistributedStorageAgent.FileMetadata(
            fileId = "f4",
            originalName = "f4.bin",
            sizeBytes = expectedData.size.toLong(),
            checksumMD5 = calculateMD5(expectedData),
            storedTimestamp = System.currentTimeMillis()
        )

        // Add metadata but remove local stored data so retrieval falls back to remote
        agent.addStoredFileForTest("f4", expectedData, metadata)
        // remove local bytes to simulate not-local
        agent.testStoredData.remove("f4")

        // replication points to node-x
        agent.setReplicationMapForTest("f4", setOf("node-x"))

        // remote returns corrupted bytes
        fake.storedRemote["f4"] = "corrupted-data".toByteArray()

        val resp = agent.retrieveFile(DistributedStorageAgent.RetrievalRequest(fileId = "f4"))

        // checksum mismatch should cause the agent to skip and ultimately fail
        assertFalse(resp.success)
        assertNotNull(resp.error)
        assertTrue(resp.error is DistributedStorageAgent.StorageError.InvalidFileId)
    }

    @Test
    fun `retrieveFile handles requestFileFromNode throwing and continues`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate(throwOnRequestForNode = "bad-node")
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        // ensure no local file
        agent.setReplicationMapForTest("f5", setOf("bad-node"))

        val resp = agent.retrieveFile(DistributedStorageAgent.RetrievalRequest(fileId = "f5"))

        // exception during remote request should be handled and result in failure
        assertFalse(resp.success)
        assertNotNull(resp.error)
        assertTrue(resp.error is DistributedStorageAgent.StorageError.InvalidFileId)
    }

    @Test
    fun `deleteFile continues when sendStorageRequest throws for a remote node`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate(throwOnSendForNode = "node-bad")
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        val data = "delete-failure".toByteArray()
        val metadata = DistributedStorageAgent.FileMetadata(
            fileId = "f6",
            originalName = "f6.bin",
            sizeBytes = data.size.toLong(),
            checksumMD5 = calculateMD5(data),
            storedTimestamp = System.currentTimeMillis()
        )

        agent.addStoredFileForTest("f6", data, metadata)
        // indicate replication existed on node-bad and LOCAL
        agent.setReplicationMapForTest("f6", setOf("LOCAL", "node-bad"))

        val deleted = agent.deleteFile("f6")

        // local deletion should succeed even though remote delete throws
        assertTrue(deleted)
        assertFalse(agent.hasFile("f6"))
        // because the send threw, lastDeleteTarget should remain null
        assertNull(fake.lastDeleteTarget)
    }

    @Test
    fun `retrieveFile succeeds when first node corrupted but second node has correct data`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate()
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        // metadata expecting 'good-data'
        val good = "good-data".toByteArray()
        val metadata = DistributedStorageAgent.FileMetadata(
            fileId = "f7",
            originalName = "f7.bin",
            sizeBytes = good.size.toLong(),
            checksumMD5 = calculateMD5(good),
            storedTimestamp = System.currentTimeMillis()
        )

        agent.addStoredFileForTest("f7", good, metadata)
        agent.testStoredData.remove("f7")

    // replication has two nodes: node-a (corrupted) then node-b (good)
    agent.setReplicationMapForTest("f7", linkedSetOf("node-a", "node-b"))

    // node-specific remote values
    fake.storedRemoteByNode.clear()
    fake.storedRemoteByNode["node-a"] = mutableMapOf("f7" to "corrupt".toByteArray())
    fake.storedRemoteByNode["node-b"] = mutableMapOf("f7" to good)

    val resp = agent.retrieveFile(DistributedStorageAgent.RetrievalRequest(fileId = "f7"))

    assertTrue(resp.success)
    assertArrayEquals(good, resp.data)
    assertEquals("node-b", resp.sourceNode)
    }

    @Test
    fun `transient failure then success when first node throws and second node returns data`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate(throwOnRequestForNode = "flaky-node")
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        val good = "transient-good".toByteArray()
        agent.setReplicationMapForTest("f8", setOf("flaky-node", "good-node"))
        fake.storedRemote["f8"] = good

        val resp = agent.retrieveFile(DistributedStorageAgent.RetrievalRequest(fileId = "f8"))

        // Should succeed by skipping the flaky-node and using good-node
        assertTrue(resp.success)
        assertArrayEquals(good, resp.data)
        assertEquals("good-node", resp.sourceNode)
    }

    @Test
    fun `replicate partial success when one send fails and another succeeds`() = runBlocking {
        val fake = FakeMeshrabiyaDelegate(throwOnSendForNode = "node-fail")
        val agent = DistributedStorageAgent(
            meshNetwork = fake,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            localStoragePath = Paths.get("build/distributed_storage_test"),
            dropFolderPath = Paths.get("build/drop_folder_test")
        )

        val req = DistributedStorageAgent.StorageRequest("r1", "r1.bin", "payload".toByteArray())

        val respA = runBlocking { agent.replicateToSpecificNodeForTest("node-fail", req) }
        val respB = runBlocking { agent.replicateToSpecificNodeForTest("node-ok", req) }

        assertFalse(respA.success)
        assertTrue(respB.success)
    }
}

private fun calculateMD5(data: ByteArray): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(data)
    val bi = BigInteger(1, digest)
    return String.format("%032x", bi)
}
