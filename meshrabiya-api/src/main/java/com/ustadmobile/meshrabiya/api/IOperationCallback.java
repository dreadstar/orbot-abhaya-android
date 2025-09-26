package com.ustadmobile.meshrabiya.api;

import android.os.IInterface;
import android.os.Binder;

public interface IOperationCallback extends IInterface {
    void onSuccess(byte[] result);
    void onFailure(int errorCode, String message);

    abstract class Stub extends Binder implements IOperationCallback {
        public Stub() {
            super();
        }

        public static IOperationCallback asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}
