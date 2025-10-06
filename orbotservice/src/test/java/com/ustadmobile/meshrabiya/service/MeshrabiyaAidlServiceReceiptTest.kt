package com.ustadmobile.meshrabiya.service

import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileOutputStream
import java.io.File
import android.util.Base64
import com.ustadmobile.meshrabiya.testutils.waitForFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MeshrabiyaAidlServiceReceiptTest {

    @Test
    fun storeBlob_records_receipt_and_truststore_loads_it() {
        val controller = Robolectric.buildService(MeshrabiyaAidlService::class.java).create()
        val service = controller.get()

    val payload = "receipt-test-payload".toByteArray()
    val tmpDir = File(System.getProperty("java.io.tmpdir"))
    val tmpFile = File.createTempFile("meshrabiya-receipt-", ".blob", tmpDir)
    tmpFile.writeBytes(payload)
    tmpFile.deleteOnExit()

    val blobId = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY).use { readFd ->
        service.storeBlobInternal(readFd)
    }
        assertNotNull(blobId)

        val blobsBase = service.filesDir?.resolve("meshrabiya_blobs")
            ?: File(System.getProperty("java.io.tmpdir")).resolve("meshrabiya_blobs")
        val receiptsFile = blobsBase.resolve("receipts.txt")
        assertTrue("Receipts file should exist: ${receiptsFile.absolutePath}", waitForFile(receiptsFile, 5_000))

    val lines = receiptsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val match = lines.find { it.startsWith("${blobId}|") }
        assertNotNull("receipts file should contain an entry for blobId", match)

    // Validate the receipts file contains a signer (base64) after the pipe
    val parts = match!!.split('|', limit = 2)
    assertEquals(2, parts.size)
    val signerB64 = parts[1].trim()
    assertTrue(signerB64.isNotEmpty())
    // Validate it's decodeable base64
    val decoded = try { Base64.decode(signerB64, Base64.NO_WRAP) } catch (e: Exception) { null }
    assertNotNull("Signer should be valid base64", decoded)
    }
}
