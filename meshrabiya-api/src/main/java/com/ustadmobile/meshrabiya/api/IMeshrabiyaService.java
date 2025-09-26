package com.ustadmobile.meshrabiya.api;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Binder;
import android.os.ParcelFileDescriptor;

/**
 * Minimal hand-written stub matching AIDL-generated shape so consumers can compile.
 * This is a short-term shim until the AIDL-generated binder classes are produced
 * by the Android build. Methods are abstract so implementations may extend
 * IMeshrabiyaService.Stub and provide concrete behavior.
 */
public interface IMeshrabiyaService extends IInterface {
    String getOnionPubKey();
    String getKeyAlgorithm();
    int getApiVersion();
    byte[] signData(byte[] data);
    MeshStatus ensureMeshActive();
    int publishToMesh(ParcelFileDescriptor data, String topic);
    String storeBlob(ParcelFileDescriptor blob);
    int requestCompute(byte[] taskSpec, IOperationCallback cb);

    abstract class Stub extends Binder implements IMeshrabiyaService {
        public Stub() {
            super();
        }

        public static IMeshrabiyaService asInterface(IBinder obj) {
            // Minimal implementation; real AIDL-generated code will return a proxy.
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        // onTransact would be implemented by AIDL-generated code; leave unimplemented.
        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            return super.onTransact(code, data, reply, flags);
        }
    }
}
