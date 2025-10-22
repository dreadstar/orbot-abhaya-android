package org.torproject.android.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.nio.file.*
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.service.StorageNodeRequest
import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import org.torproject.android.AppSettings

/**
 * Manages drop folder monitoring, storage node discovery, file transfer, and replication.
 * Integrates with UI via MeshStorageUiCallback.
 */
class MeshStorageManager private constructor(private val context: Context) {

    private var dropFolderWatcherJob: Job? = null
    private var selectedDropFolder: Path? = null
    private val meshGossipService = MeshGossipService.getInstance(context)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiCallback: MeshStorageUiCallback? = null

    companion object {
        @Volatile private var instance: MeshStorageManager? = null
        fun getInstance(context: Context): MeshStorageManager =
            instance ?: synchronized(this) {
                instance ?: MeshStorageManager(context.applicationContext).also { instance = it }
            }
        private const val RETRY_DELAY_MS = 10000L
    }

    /**
     * Set the UI callback for status updates.
     */
    fun setUiCallback(callback: MeshStorageUiCallback) {
        uiCallback = callback
    }

    /**
     * Set the drop folder to monitor. Starts watcher if folder changes.
     */
    fun setDropFolder(folderPath: String) {
        val newPath = Paths.get(folderPath)
        if (selectedDropFolder != newPath) {
            dropFolderWatcherJob?.cancel()
            selectedDropFolder = newPath
            dropFolderWatcherJob = CoroutineScope(Dispatchers.IO).launch {
                watchDropFolder(newPath)
            }
        }
    }

    /**
     * Watches the drop folder for new files and triggers storage lifecycle.
     */
    private suspend fun watchDropFolder(path: Path) {
        val watcher = FileSystems.getDefault().newWatchService()
        path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE)
        while (isActive) {
            val key = watcher.take()
            for (event in key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    val filename = event.context() as Path
                    val file = path.resolve(filename).toFile()
                    handleDropFile(file)
                }
            }
            key.reset()
        }
        watcher.close()
    }

    /**
     * Handles new file: broadcasts storage node request, processes responses, transfers file, and triggers replication.
     */
    private fun handleDropFile(file: java.io.File) {
        val request = StorageNodeRequest(file.length(), file.name)
        meshGossipService.broadcastStorageNodeRequest(request, timeoutMs = 5000) { responses ->
            processStorageNodeResponses(file, responses)
        }
    }

    /**
     * Processes responses from candidate storage nodes, selects best, and initiates file transfer.
     */
    private fun processStorageNodeResponses(file: java.io.File, responses: List<StorageNodeResponse>) {
        if (responses.isEmpty()) {
            uiHandler.post { uiCallback?.showNoStorageNodesAlert() }
            CoroutineScope(Dispatchers.IO).launch {
                delay(RETRY_DELAY_MS)
                handleDropFile(file)
            }
            return
        }
        val selectedNode = selectBestNode(responses, file.length())
        if (selectedNode == null) {
            uiHandler.post { uiCallback?.showInsufficientSpaceAlert() }
            CoroutineScope(Dispatchers.IO).launch {
                delay(RETRY_DELAY_MS)
                handleDropFile(file)
            }
            return
        }
        meshGossipService.sendFile(selectedNode.url, file) { result ->
            if (result.success) {
                uiHandler.post { uiCallback?.showFileStored(result.fileId) }
                replicateFile(result.fileId, file)
            } else {
                uiHandler.post { uiCallback?.showTransferFailedAlert() }
                CoroutineScope(Dispatchers.IO).launch {
                    delay(RETRY_DELAY_MS)
                    handleDropFile(file)
                }
            }
        }
    }

    /**
     * Selects the best candidate node for storage.
     */
    private fun selectBestNode(responses: List<StorageNodeResponse>, fileSize: Long): StorageNodeResponse? {
        return responses
            .filter { it.availableSpace >= fileSize && it.systemState == "healthy" }
            .sortedWith(compareBy({ -it.availableSpace }, { it.latency }))
            .firstOrNull()
    }

    /**
     * Initiates replication of the file to additional storage nodes.
     */
    private fun replicateFile(fileId: String, file: java.io.File) {
        val desiredReplicas = AppSettings.getReplicaCount()
        queryReplicaCount(fileId) { currentReplicas ->
            if (currentReplicas >= desiredReplicas) return@queryReplicaCount

            val request = StorageNodeRequest(file.length(), file.name, fileId)
            meshGossipService.broadcastStorageNodeRequest(request, timeoutMs = 5000) { candidates ->
                val replicasNeeded = desiredReplicas - currentReplicas
                val selectedNodes = candidates
                    .filter { it.availableSpace >= file.length() }
                    .sortedWith(compareBy({ -it.availableSpace }, { it.latency }))
                    .take(replicasNeeded)

                selectedNodes.forEach { node ->
                    meshGossipService.sendFile(node.url, file) { /* Optionally handle completion */ }
                }
            }
        }
    }

    /**
     * Queries the mesh for the current replica count of a file.
     * Calls the callback with the count.
     */
    private fun queryReplicaCount(fileId: String, callback: (Int) -> Unit) {
        meshGossipService.queryFileReplicas(fileId, timeoutMs = 3000) { replicaNodes ->
            callback(replicaNodes.size)
        }
    }
}

/**
 * Callback interface for UI status updates.
 */
interface MeshStorageUiCallback {
    fun showNoStorageNodesAlert()
    fun showInsufficientSpaceAlert()
    fun showTransferFailedAlert()
    fun showFileStored(fileId: String)
}