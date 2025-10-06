package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

class DistributedStorageAgentStreamTest {

    @Test
    fun testStoreBlobFromStream_savesDataToTestStore() = runBlocking {
        val meshNetwork = object : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
            override suspend fun sendStorageRequest(targetNodeId: String, fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo, operation: com.ustadmobile.meshrabiya.storage.StorageOperation) {
                // no-op for test
            }

            override suspend fun queryFileAvailability(path: String): List<String> = emptyList()
            override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? = null
            override suspend fun getAvailableStorageNodes(): List<String> = emptyList()
            override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {}
        }

        val agent = DistributedStorageAgent(meshNetwork)

        val message = "hello mesh streaming"
        val bytes = message.toByteArray(StandardCharsets.UTF_8)

        // Use piped streams to simulate streaming input
        val output = PipedOutputStream()
        val input = PipedInputStream(output)

        // Write data in another thread to simulate streaming
        val writer = Thread {
            try {
                output.write(bytes)
                output.flush()
                output.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        writer.start()

        val fileId = "test-stream-file-1"
        val resp = agent.storeBlobFromInputStreamSupplier(fileId, "stream.txt") { input }

        assertTrue("Store response should be success", resp.success)
        // Ensure testStoredData contains the bytes stored
        val stored = agent.testStoredData[fileId]
        assertNotNull("Stored data should be present in in-memory test store", stored)
        assertArrayEquals(bytes, stored)
    }
}
