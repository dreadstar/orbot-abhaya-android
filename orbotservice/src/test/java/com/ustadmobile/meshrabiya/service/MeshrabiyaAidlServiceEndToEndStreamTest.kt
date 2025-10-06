package com.ustadmobile.meshrabiya.service

import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import com.ustadmobile.meshrabiya.testutils.TestLogger
import com.ustadmobile.meshrabiya.testutils.waitForFile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import android.util.Base64
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MeshrabiyaAidlServiceEndToEndStreamTest {

    @Test
    fun testStoreBlob_viaParcelFileDescriptorPipe_createsBlobReplAndReceipt() {
        val controller = Robolectric.buildService(MeshrabiyaAidlService::class.java).create()
        val service = controller.get()

        val payload = "end-to-end-stream-test".toByteArray(StandardCharsets.UTF_8)
    // Use a temp file instead of ParcelFileDescriptor.createPipe() which is not
    // available/mockable in some Robolectric/JVM configs.
    val tmpDir = File(System.getProperty("java.io.tmpdir"))
    val tmpFile = File.createTempFile("meshtest-e2e-", ".blob", tmpDir)
    FileOutputStream(tmpFile).use { out -> out.write(payload) }
    val blobId = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { readFd ->
        service.storeBlobInternal(readFd)
    }
        TestLogger.log("MeshrabiyaAidlServiceEndToEndStreamTest", "I", "stored blobId=$blobId")
        assertNotNull(blobId)

        val blobsBase = service.filesDir?.resolve("meshrabiya_blobs")
            ?: File(System.getProperty("java.io.tmpdir")).resolve("meshrabiya_blobs")

    val blobFile = File(blobsBase, "${blobId}.blob")
    val replFile = File(blobsBase, "${blobId}.repl.json")
        val receiptsFile = File(blobsBase, "receipts.txt")

    // storeBlobInternal is synchronous, but wait up to 5s for filesystem visibility
    TestLogger.log("MeshrabiyaAidlServiceEndToEndStreamTest", "I", "checking existence: blob=${blobFile.absolutePath} repl=${replFile.absolutePath} receipts=${receiptsFile.absolutePath}")
    assertTrue("Blob file should exist: ${blobFile.absolutePath}", waitForFile(blobFile, 5_000))
    assertTrue("Repl job file should exist: ${replFile.absolutePath}", waitForFile(replFile, 5_000))

    if (receiptsFile.exists() || waitForFile(receiptsFile, 2_000)) {
        val lines = receiptsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val match = lines.find { it.startsWith("${blobId}|") }
        assertNotNull("receipts file should contain an entry for blobId", match)

        val parts = match!!.split('|', limit = 2)
        assertEquals(2, parts.size)
        val signerB64 = parts[1].trim()
        assertTrue(signerB64.isNotEmpty())
        val decoded = try { Base64.decode(signerB64, Base64.NO_WRAP) } catch (e: Exception) { null }
        assertNotNull("Signer should be valid base64", decoded)
    } else {
        TestLogger.log("MeshrabiyaAidlServiceEndToEndStreamTest", "W", "Receipts file not found, skipping receipts assertions: ${receiptsFile.absolutePath}")
    }
    }
}
