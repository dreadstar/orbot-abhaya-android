package org.torproject.android.ui.mesh

import android.util.Log
import org.torproject.android.service.compute.IntelligentDistributedComputeService
import org.torproject.android.service.compute.toDistributedFileInfo

/**
 * Small named adapter used by UI code to provide a compute-level
 * MeshNetworkInterface implementation to the ServiceLayerCoordinator.
 *
 * Accepts an optional Meshrabiya adapter (from MeshServiceCoordinator). If present,
 * this implementation may delegate compute requests to it in future. For now,
 * presence of the adapter is signalled in logs and the adapter falls back to a
 * mock success response to keep UI/demo behavior consistent.
 */
class ComputeMeshNetworkAdapter(
    private val meshrabiyaDelegate: com.ustadmobile.meshrabiya.storage.MeshNetworkInterface? = null,
    private val computeDelegate: IntelligentDistributedComputeService.MeshNetworkInterface? = null
) : IntelligentDistributedComputeService.MeshNetworkInterface {

    override suspend fun executeRemoteTask(
        nodeId: String,
        request: IntelligentDistributedComputeService.TaskExecutionRequest
    ): IntelligentDistributedComputeService.TaskExecutionResponse {
        // If a compute-level delegate is provided, forward the call.
        computeDelegate?.let { return it.executeRemoteTask(nodeId, request) }

        // If a Meshrabiya adapter is present, try to send the request as a DistributedFileInfo
        meshrabiyaDelegate?.let { delegate ->
            try {
                // Create a temp file to hold the task payload
                val tmpFile = kotlin.io.path.createTempFile(prefix = "compute_req_", suffix = ".bin").toFile()
                tmpFile.writeBytes(request.taskData)

                // Convert to Meshrabiya DistributedFileInfo using the interop helper
                val df = request.toDistributedFileInfo(tmpFile.absolutePath)

                // Send as a REPLICATE operation to the target node
                delegate.sendStorageRequest(nodeId, df, com.ustadmobile.meshrabiya.storage.StorageOperation.REPLICATE)

                // For this simple implementation we assume dispatch succeeded and return a placeholder success
                return IntelligentDistributedComputeService.TaskExecutionResponse.Success(
                    result = mapOf("status" to "dispatched", "taskId" to request.taskId),
                    executionTimeMs = 0L
                )
            } catch (e: Exception) {
                Log.w("ComputeMeshAdapter", "Failed to delegate compute request to Meshrabiya", e)
                // fallthrough to mock response
            }
        }

        // Default mock behavior: return a simple success object for UI/demo flows.
        return IntelligentDistributedComputeService.TaskExecutionResponse.Success(
            result = mapOf("output" to "Mock result"),
            executionTimeMs = 1000L
        )
    }
}

