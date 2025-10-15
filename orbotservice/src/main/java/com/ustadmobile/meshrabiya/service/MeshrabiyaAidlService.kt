package com.ustadmobile.meshrabiya.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import com.ustadmobile.meshrabiya.api.IMeshrabiyaService
import com.ustadmobile.meshrabiya.api.MeshStatus
import com.ustadmobile.meshrabiya.api.IOperationCallback
/**
 * Minimal AIDL service skeleton implementing IMeshrabiyaService.Stub.
 * This file provides placeholder implementations that can be expanded later.
 */
class MeshrabiyaAidlService : Service() {

    // Simple test-mode store (blobId -> byte[]). Only used when test mode enabled.
    private val testBlobStore = mutableMapOf<String, ByteArray>()
    private var testMode: Boolean = false

    private val binder = object : IMeshrabiyaService.Stub() {
        override fun getOnionPubKey(): String? {
            // In test mode return a deterministic onion pubkey so tests are stable
            return if (testMode) "TEST_ONION_PUBLIC_KEY_BASE64" else ""
        }

        override fun getKeyAlgorithm(): String? {
            return "Ed25519"
        }

        override fun getApiVersion(): Int {
            return 1
        }

        override fun signData(data: ByteArray?): ByteArray? {
            // Sign not implemented; return null to indicate failure
            return null
        }

        override fun ensureMeshActive(): MeshStatus? {
            // Return a simple MeshStatus parcelable instance
            return MeshStatus(true, false, false, 1, System.currentTimeMillis(), true)
        }

        override fun publishToMesh(data: ParcelFileDescriptor?, topic: String?): Int {
            // Placeholder: accept but do nothing
            return 0
        }

        override fun storeBlob(blob: ParcelFileDescriptor?): String? {
            if (!testMode || blob == null) return ""
                return try {
                    val fd = blob.fileDescriptor
                    val bytes = FileInputStream(fd).use { it.readBytes() }
                    val id = java.util.UUID.randomUUID().toString()
                    testBlobStore[id] = bytes
                    id
                } catch (t: Throwable) {
                    ""
                }
        }

        override fun openBlob(blobId: String?): ParcelFileDescriptor? {
            if (!testMode || blobId == null) return null
            val data = testBlobStore[blobId] ?: return null
            return try {
                // Create a pipe: [0] = read side, [1] = write side
                val pipe = ParcelFileDescriptor.createPipe()
                val readSide = pipe[0]
                val writeSide = pipe[1]

                // Write the in-memory bytes to the write side on a background thread
                Thread {
                    try {
                        val fd = writeSide.fileDescriptor
                        FileOutputStream(fd).use { os ->
                            os.write(data)
                            os.flush()
                        }
                    } catch (_: Throwable) {
                        // ignore write errors for test-mode
                    } finally {
                        try {
                            writeSide.close()
                        } catch (_: Exception) {
                        }
                    }
                }.start()

                readSide
            } catch (t: Throwable) {
                null
            }
        }

        override fun readBlobRange(blobId: String?, offset: Long, length: Int): ByteArray? {
            if (!testMode || blobId == null) return null
            val data = testBlobStore[blobId] ?: return null
            val off = offset.toInt().coerceAtLeast(0)
            val len = length.coerceAtLeast(0)
            if (off >= data.size) return ByteArray(0)
            val end = minOf(data.size, off + len)
            return data.sliceArray(off until end)
        }

        override fun requestCompute(taskSpec: ByteArray?, cb: IOperationCallback?): Int {
            // Not implemented; immediately reply failure via callback if available
            try {
                cb?.onFailure(1, "Not implemented")
            } catch (_: Exception) {
            }
            return 1
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Allow tests to enable test-mode by providing intent extra
        testMode = intent?.getBooleanExtra("meshrabiya_test_mode", false) == true
        // Enforce permission at runtime as extra defense (manifest already requires signature)
        val perm = "com.ustadmobile.meshrabiya.permission.BIND_MESHRABIYA"
        return if (checkCallingOrSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            binder
        } else {
            null
        }
    }
}
