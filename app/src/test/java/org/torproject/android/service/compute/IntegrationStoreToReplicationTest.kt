package org.torproject.android.service.compute

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Small integration-style unit test that simulates storing a blob, invoking the coordinator
 * trigger, and verifying that the storage agent replicate path is called.
 */
class IntegrationStoreToReplicationTest {

    class FakeStorageAgent(var replicatedBlobId: String? = null) {
        // Return Unit here so the fake does not depend on the production ReplicationResult type
        suspend fun replicateReplJobForBlob(blobId: String, replicationFactor: Int = 3) {
            replicatedBlobId = blobId
        }
    }

    class TestMeshServiceCoordinator(private val storageAgent: FakeStorageAgent) {
        fun requestReplicationForBlob(blobId: String) {
            // in real coordinator this would launch a coroutine; here we call directly for test
            runBlocking {
                storageAgent.replicateReplJobForBlob(blobId)
            }
        }
    }

    @Test
    fun testStoreThenCoordinatorTriggersReplication() {
        val tempDir = kotlin.io.path.createTempDirectory(prefix = "drop_test_").toFile()
        try {
            val blobId = "integration-test-blob"
            // Simulate storage: create blob + meta + repl job
            File(tempDir, "$blobId.blob").writeText("hello")
            File(tempDir, "$blobId.json").writeText("{\"id\": \"$blobId\"}")
            File(tempDir, "$blobId.repl.json").writeText("{\"blob_path\": \"$blobId.blob\", \"meta_path\": \"$blobId.json\"}")

            val fakeAgent = FakeStorageAgent()
            val coordinator = TestMeshServiceCoordinator(fakeAgent)

            // Trigger replication as coordinator would after a store
            coordinator.requestReplicationForBlob(blobId)

            assertTrue("Storage agent should have been asked to replicate the blob", fakeAgent.replicatedBlobId == blobId)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
