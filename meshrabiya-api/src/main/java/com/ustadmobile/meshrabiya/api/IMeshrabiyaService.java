package com.ustadmobile.meshrabiya.api;

import android.os.Binder;
import android.os.ParcelFileDescriptor;

/**
 * Minimal handwritten shim of the AIDL-generated binder interface.
 * This file is a temporary shim to unblock compilation; the canonical
 * AIDL should generate an equivalent Java class eventually.
 */
public interface IMeshrabiyaService {
    String getOnionPubKey();
    String getKeyAlgorithm();
    int getApiVersion();
    byte[] signData(byte[] data);
    MeshStatus ensureMeshActive();
    int publishToMesh(ParcelFileDescriptor data, String topic);
    String storeBlob(ParcelFileDescriptor blob);
    ParcelFileDescriptor openBlob(String blobId);
    byte[] readBlobRange(String blobId, long offset, int length);
    int requestCompute(byte[] taskSpec, IOperationCallback cb);

    abstract class Stub extends Binder implements IMeshrabiyaService {
        public Stub() {
            super();
        }

        // Declare abstract methods so Kotlin implementation can override them.
        @Override public abstract String getOnionPubKey();
        @Override public abstract String getKeyAlgorithm();
        @Override public abstract int getApiVersion();
        @Override public abstract byte[] signData(byte[] data);
        @Override public abstract MeshStatus ensureMeshActive();
        @Override public abstract int publishToMesh(ParcelFileDescriptor data, String topic);
        @Override public abstract String storeBlob(ParcelFileDescriptor blob);
    @Override public abstract ParcelFileDescriptor openBlob(String blobId);
    @Override public abstract byte[] readBlobRange(String blobId, long offset, int length);
        @Override public abstract int requestCompute(byte[] taskSpec, IOperationCallback cb);
        
        // Minimal helper so Kotlin/Java callers can use Stub.asInterface(binder)
        public static IMeshrabiyaService asInterface(android.os.IBinder binder) {
            if (binder == null) return null;
            if (binder instanceof IMeshrabiyaService) return (IMeshrabiyaService) binder;
            // No real proxy implementation provided in this shim; return null to indicate unavailable
            return null;
        }
    }
}
