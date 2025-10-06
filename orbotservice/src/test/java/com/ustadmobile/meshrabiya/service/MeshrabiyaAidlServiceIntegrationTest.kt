package com.ustadmobile.meshrabiya.service

import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.ustadmobile.meshrabiya.testutils.waitForFile
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MeshrabiyaAidlServiceIntegrationTest {

    @Test
    fun testStoreBlobInternal_writesBlobAndRecordsReceipt() {
        val controller = Robolectric.buildService(MeshrabiyaAidlService::class.java).create()
        val service = controller.get()

        val message = "integration test blob"
        val bytes = message.toByteArray(StandardCharsets.UTF_8)

        val output = PipedOutputStream()
        val input = PipedInputStream(output)

        val writer = Thread {
            try {
                output.write(bytes)
                output.flush()
                output.close()
            } catch (e: Exception) {
            }
        }
        writer.start()

    // Create a temp file and open a read ParcelFileDescriptor for it.
    val tmpDir = File(System.getProperty("java.io.tmpdir"))
    val tmpFile = File.createTempFile("meshtest-int-", ".blob", tmpDir)
    tmpFile.writeBytes(bytes)
    val blobId = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { readPfd ->
        service.storeBlobInternal(readPfd)
    }
        assertNotNull(blobId)

        val blobsBase = service.filesDir?.let { File(it, "meshrabiya_blobs") }
            ?: File(tmpDir, "meshrabiya_blobs")

        // Locate receipts file under blobsBase (or created location)
        val receiptsFile = File(blobsBase, "receipts.txt")

        // Wait up to 5s for blob and receipts visibility
    val blobFile = File(blobsBase, "${blobId}.blob")
    assertTrue("Blob file should exist: ${blobFile.absolutePath}", waitForFile(blobFile, 5_000))

        // Check receipts file contains the blobId|pubB64 line (wait up to 3s)
        assertTrue("Receipts file should exist", waitForFile(receiptsFile, 3_000))
        val receipts = receiptsFile.readLines()
        val matching = receipts.any { it.startsWith("${blobId}|") }
        assertTrue("Receipts should contain blobId entry", matching)
    }
}
