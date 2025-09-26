package com.ustadmobile.meshrabiya.api;

import com.ustadmobile.meshrabiya.api.IOperationCallback;
import com.ustadmobile.meshrabiya.api.MeshStatus;
import android.os.ParcelFileDescriptor;

interface IMeshrabiyaService {
    String getOnionPubKey();
    String getKeyAlgorithm();
    int getApiVersion();
    byte[] signData(in byte[] data);
    MeshStatus ensureMeshActive();
    int publishToMesh(in ParcelFileDescriptor data, String topic);
    String storeBlob(in ParcelFileDescriptor blob);
    int requestCompute(in byte[] taskSpec, in IOperationCallback cb);
}
package com.ustadmobile.meshrabiya.api;

import com.ustadmobile.meshrabiya.api.IOperationCallback;
import com.ustadmobile.meshrabiya.api.MeshStatus;
import android.os.ParcelFileDescriptor;

interface IMeshrabiyaService {
    String getOnionPubKey();
    String getKeyAlgorithm();
    int getApiVersion();
    byte[] signData(in byte[] data);
    MeshStatus ensureMeshActive();
    int publishToMesh(in ParcelFileDescriptor data, String topic);
    String storeBlob(in ParcelFileDescriptor blob);
    int requestCompute(in byte[] taskSpec, in IOperationCallback cb);
}
