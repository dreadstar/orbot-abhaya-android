package org.torproject.android.service.compute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class FakeMeshNetwork : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
    val sent = mutableListOf<com.ustadmobile.meshrabiya.storage.DistributedFileInfo>()

    override suspend fun sendStorageRequest(targetNodeId: String, fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo, operation: com.ustadmobile.meshrabiya.storage.StorageOperation) {
        sent.add(fileInfo)
    }

    override suspend fun queryFileAvailability(path: String): List<String> = emptyList()
    override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? = null
    override suspend fun getAvailableStorageNodes(): List<String> = listOf("nodeA", "nodeB", "nodeC")
    override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
}

class DistributedStorageAgentReplTest {

    @Test
    fun testReplicateReplJobForBlob() = runBlocking {
        val tmpDir: Path = Files.createTempDirectory("dropfolder_test")
        try {
            val blobId = "testblob123"
            val blobFile = tmpDir.resolve("$blobId.blob")
            val content = "hello world".toByteArray()
            Files.write(blobFile, content)

            val metaFile = tmpDir.resolve("$blobId.json")
            val metaJson = "{\"id\":\"$blobId\",\"size\":${content.size}}"
            Files.write(metaFile, metaJson.toByteArray())

            val replFile = tmpDir.resolve("$blobId.repl.json")
            val replJson = "{\"id\":\"$blobId\",\"blob_path\":\"${blobFile.toAbsolutePath()}\",\"meta_path\":\"${metaFile.toAbsolutePath()}\",\"target_replicas\":1}"
            Files.write(replFile, replJson.toByteArray())

            val fakeMesh = FakeMeshNetwork()
            val agent = DistributedStorageAgent(meshNetwork = fakeMesh,
                ioDispatcher = Dispatchers.IO,
                localStoragePath = Paths.get("build", "test_local_storage"),
                dropFolderPath = tmpDir,
                maxStorageGB = 0.1f)

            val result = agent.replicateReplJobForBlob(blobId, replicationFactor = 1)

            // Debug: print the whole result to help diagnose failures in CI/JVM test
            println("DistributedStorageAgent.replicateReplJobForBlob result = $result")

            when (result) {
                is DistributedStorageAgent.ReplicationResult.Success -> assertEquals(blobId, result.fileId)
                is DistributedStorageAgent.ReplicationResult.Error -> fail("Replication returned error: $result")
            }
        } finally {
            try { tmpDir.toFile().deleteRecursively() } catch (_: Exception) {}
        }
    }
}
