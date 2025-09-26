package com.ustadmobile.meshrabiya.api;

/**
 * Minimal handwritten shim for the AIDL-generated callback interface.
 * Temporary; will be removed once AIDL-generated classes are visible to consumers.
 */
public interface IOperationCallback {
    void onSuccess(byte[] result);
    void onFailure(int errorCode, String message);
}
