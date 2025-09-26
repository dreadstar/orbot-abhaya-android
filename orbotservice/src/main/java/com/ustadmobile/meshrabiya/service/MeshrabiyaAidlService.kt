package com.ustadmobile.meshrabiya.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.ustadmobile.meshrabiya.api.IMeshrabiyaService
import com.ustadmobile.meshrabiya.api.MeshStatus
import com.ustadmobile.meshrabiya.api.IOperationCallback
/**
 * Minimal AIDL service skeleton implementing IMeshrabiyaService.Stub.
 * This file provides placeholder implementations that can be expanded later.
 */
class MeshrabiyaAidlService : Service() {

    private val binder = object : IMeshrabiyaService.Stub() {
        override fun getOnionPubKey(): String? {
            // Placeholder: return empty string for now
            return ""
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
            // Not implemented
            return ""
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
        // Enforce permission at runtime as extra defense (manifest already requires signature)
        val perm = "com.ustadmobile.meshrabiya.permission.BIND_MESHRABIYA"
        return if (checkCallingOrSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            binder
        } else {
            null
        }
    }
}
