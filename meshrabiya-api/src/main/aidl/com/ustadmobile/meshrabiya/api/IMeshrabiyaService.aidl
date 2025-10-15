package com.ustadmobile.meshrabiya.api;

import com.ustadmobile.meshrabiya.api.IOperationCallback;
import com.ustadmobile.meshrabiya.api.MeshStatus;
import android.os.ParcelFileDescriptor;

interface IMeshrabiyaService {
    /**
     * Returns the onion public key (PEM/base64) used for tagging data from this node.
     */
    String getOnionPubKey();

    /**
     * Returns the key algorithm, e.g. "Ed25519" or "RSA".
     */
    String getKeyAlgorithm();

    /**
     * API version for client compatibility checks.
     */
    int getApiVersion();

    /**
     * Sign provided data using the node's private key. Returns signature bytes, or
     * null on error/permission denied.
     */
    byte[] signData(in byte[] data);

    /**
     * Ensure mesh services are active. Returns a MeshStatus describing local and
     * mesh-wide availability (storage/compute reachable on the mesh) so clients
     * can decide if they may use remote nodes when local resources are absent.
     */
    MeshStatus ensureMeshActive();

    /**
     * Publish a stream or blob to the mesh under the given topic. Large payloads
     * should be provided via the ParcelFileDescriptor pattern on the client side.
     * Returns 0 on success or a non-zero error code.
     */
    int publishToMesh(in ParcelFileDescriptor data, String topic);

    /**
     * Store a blob in distributed storage. Returns a stable blob id or empty string
     * on error.
     */
    String storeBlob(in ParcelFileDescriptor blob);

    /**
     * Open a previously stored blob and return a ParcelFileDescriptor for reading.
     * Returns null if the blob does not exist or access is denied.
     */
    ParcelFileDescriptor openBlob(String blobId);

    /**
     * Read a range from a stored blob. Returns the requested bytes or null on error.
     */
    byte[] readBlobRange(String blobId, long offset, int length);

    /**
     * Request a compute task on the mesh. The request is asynchronous; results are
     * delivered via the provided IOperationCallback. Returns 0 on accepted, or error code.
     */
    int requestCompute(in byte[] taskSpec, in IOperationCallback cb);
}
package com.ustadmobile.meshrabiya.api;

import com.ustadmobile.meshrabiya.api.IOperationCallback;
import com.ustadmobile.meshrabiya.api.MeshStatus;
import android.os.ParcelFileDescriptor;

interface IMeshrabiyaService {
    /**
     * Returns the onion public key (PEM/base64) used for tagging data from this node.
     */
    String getOnionPubKey();

    /**
     * Returns the key algorithm, e.g. "Ed25519" or "RSA".
     */
    String getKeyAlgorithm();

    /**
     * API version for client compatibility checks.
     */
    int getApiVersion();

    /**
     * Sign provided data using the node's private key. Returns signature bytes, or
     * null on error/permission denied.
     */
    byte[] signData(in byte[] data);

    /**
     * Ensure mesh services are active. Returns a MeshStatus describing local and
     * mesh-wide availability (storage/compute reachable on the mesh) so clients
     * can decide if they may use remote nodes when local resources are absent.
     */
    MeshStatus ensureMeshActive();

    /**
     * Publish a stream or blob to the mesh under the given topic. Large payloads
     * should be provided via the ParcelFileDescriptor pattern on the client side.
     * Returns 0 on success or a non-zero error code.
     */
    int publishToMesh(in ParcelFileDescriptor data, String topic);

    /**
     * Store a blob in distributed storage. Returns a stable blob id or empty string
     * on error.
     */
    String storeBlob(in ParcelFileDescriptor blob);

    /**
     * Open a previously stored blob and return a ParcelFileDescriptor for reading.
     * Returns null if the blob does not exist or access is denied.
     */
    ParcelFileDescriptor openBlob(String blobId);

    /**
     * Read a range from a stored blob. Returns the requested bytes or null on error.
     */
    byte[] readBlobRange(String blobId, long offset, int length);

    /**
     * Request a compute task on the mesh. The request is asynchronous; results are
     * delivered via the provided IOperationCallback. Returns 0 on accepted, or error code.
     */
    int requestCompute(in byte[] taskSpec, in IOperationCallback cb);
}
