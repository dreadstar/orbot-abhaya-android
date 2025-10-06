package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class DistributedStorageAgentDiskStreamTest {

    @Test
    fun testStoreBlobToDiskFromStream_writesFileAndAttemptsReplication() = runBlocking {
        var sendCalled = false
        var sentLocalPath: String? = null

        val meshNetwork = object : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
            override suspend fun sendStorageRequest(targetNodeId: String, fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo, operation: com.ustadmobile.meshrabiya.storage.StorageOperation) {
                // record that sendStorageRequest was invoked and capture localPath
                sendCalled = true
                sentLocalPath = fileInfo.localReference.localPath
            }

            override suspend fun queryFileAvailability(path: String): List<String> = emptyList()
            override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? = null
            override suspend fun getAvailableStorageNodes(): List<String> = listOf("nodeA")
            override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
        }

        val tmpDir = Files.createTempDirectory("dstest")
        val agent = DistributedStorageAgent(meshNetwork, localStoragePath = tmpDir, dropFolderPath = Paths.get("/tmp/drop"))

        val message = "disk stream hello"
        val bytes = message.toByteArray(StandardCharsets.UTF_8)

        val output = PipedOutputStream()
        val input = PipedInputStream(output)

        val writer = Thread {
            try {
                output.write(bytes)
                output.flush()
                output.close()
            } catch (e: Exception) { }
        }
        writer.start()

        val fileId = "disk-stream-file-1"
        val resp = agent.storeBlobToDiskFromStream(fileId, "streamfile.txt", input)

        assertTrue(resp.success)

        // Check file exists on disk
        val path = tmpDir.resolve(fileId)
        assertTrue(Files.exists(path))

        // Check file contents
        val read = Files.readAllBytes(path)
        assertArrayEquals(bytes, read)

        // Ensure meshNetwork.sendStorageRequest was called and localPath captured
        assertTrue(sendCalled)
        assertNotNull(sentLocalPath)
        assertEquals(path.toString(), sentLocalPath)
    }
}
