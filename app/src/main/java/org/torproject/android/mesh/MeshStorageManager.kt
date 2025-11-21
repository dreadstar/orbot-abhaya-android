package org.torproject.android.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.nio.file.*
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.security.MessageDigest

/**
 * Manages drop folder monitoring, storage node discovery, chunked file transfer, retrieval, and replication.
 * Integrates with UI via MeshStorageUiCallback.
 * 
 * TODO: This class needs comprehensive refactoring to use MeshrabiyaApi high-level methods
 * (storeFile, retrieveFile, deleteFile) instead of manually managing chunks and broadcasts.
 * Currently uses internal mesh services which violates separation of concerns.
 * NOTE: Appears to be unused in active codebase - only referenced in MeshFragment.md documentation.
 * 
 * TEMPORARY: Using MeshrabiyaApi for initialization, but still accessing internal MeshGossipService
 * for backward compatibility until comprehensive refactoring is complete.
 */
class MeshStorageManager private constructor(private val context: Context) {

    private var dropFolderWatcherJob: Job? = null
    private var selectedDropFolder: Path? = null

    // Get MeshrabiyaApi for mesh operations (proper abstraction layer)
    private val meshrabiyaApi: MeshrabiyaApi = MeshrabiyaApiImpl.getInstance().apply {
        initMesh(context)
    }


    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiCallback: MeshStorageUiCallback? = null

    companion object {
        @Volatile private var instance: MeshStorageManager? = null
        fun getInstance(context: Context): MeshStorageManager =
            instance ?: synchronized(this) {
                instance ?: MeshStorageManager(context.applicationContext).also { instance = it }
            }
        // RETRY_DELAY_MS is now internal, not from MeshrabiyaConstants
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
     * Handles new file: delegates storage to MeshrabiyaApi.
     */
    private fun handleDropFile(file: File) {
        meshrabiyaApi.storeFile(file, object : MeshrabiyaApi.StoreFileCallback {
            override fun onSuccess(fileId: String) {
                uiHandler.post { uiCallback?.showFileStored(fileId) }
            }
            override fun onFailure(error: Throwable) {
                uiHandler.post { uiCallback?.showTransferFailedAlert() }
            }
        })
    }

    /**
     * Chunks a file and returns a list of MeshChunk objects.
     */
    // chunkFile and all chunking logic should be handled by the API; this method is now obsolete.
    // If needed, implement chunking via MeshrabiyaApi utility methods.

    /**
     * Processes responses from candidate storage nodes for a chunk, selects best, and initiates chunk transfer.
     */
    private fun processChunkStorageNodeResponses(chunk: MeshChunk, responses: List<StorageNodeResponse>) {
        if (responses.isEmpty()) {
            uiHandler.post { uiCallback?.showNoStorageNodesAlert() }
            CoroutineScope(Dispatchers.IO).launch {
                delay(RETRY_DELAY_MS)
                meshGossipService.broadcastStorageNodeRequest(
                    StorageNodeRequest(chunk.chunkSize, chunk.fileName, chunk.fileId),
                    timeoutMs = 5000
                ) { retryResponses ->
                    processChunkStorageNodeResponses(chunk, retryResponses)
                }
            }
            return
        }
        val selectedNode = selectBestNode(responses, chunk.chunkSize)
        if (selectedNode == null) {
            uiHandler.post { uiCallback?.showInsufficientSpaceAlert() }
            CoroutineScope(Dispatchers.IO).launch {
                delay(RETRY_DELAY_MS)
                meshGossipService.broadcastStorageNodeRequest(
                    StorageNodeRequest(chunk.chunkSize, chunk.fileName, chunk.fileId),
                    timeoutMs = 5000
                ) { retryResponses ->
                    processChunkStorageNodeResponses(chunk, retryResponses)
                }
            }
            return
        }
        // Read chunk bytes from local storage
        val chunkFile = File(selectedDropFolder?.toFile(), chunk.fileName)
        val chunkBytes = chunkFile.readBytes()
        meshGossipService.sendChunk(
            selectedNode.url,
            chunk.chunkId,
            chunk.fileId,
            chunk.chunkIndex,
            chunk.totalChunks,
            chunk.fileName,
            chunk.relativePath,
            chunkBytes
        ) { result ->
            if (result.success) {
                uiHandler.post { uiCallback?.showFileStored(chunk.fileId) }
                replicateChunk(chunk)
            } else {
                uiHandler.post { uiCallback?.showTransferFailedAlert() }
                CoroutineScope(Dispatchers.IO).launch {
                    delay(RETRY_DELAY_MS)
                    processChunkStorageNodeResponses(chunk, responses)
                }
            }
        }
    }

    /**
     * Selects the best candidate node for storage.
     */
    private fun selectBestNode(responses: List<StorageNodeResponse>, chunkSize: Long): StorageNodeResponse? {
        return responses
            .filter { it.availableSpace >= chunkSize && it.systemState == "healthy" }
            .sortedWith(compareBy({ -it.availableSpace }, { it.latency }))
            .firstOrNull()
    }

    /**
     * Initiates replication of the chunk to additional storage nodes.
     */
    private fun replicateChunk(chunk: MeshChunk) {
    val desiredReplicas = MeshrabiyaConstants.getReplicaCount()
        queryChunkReplicaCount(chunk.chunkId) { currentReplicas ->
            if (currentReplicas >= desiredReplicas) return@queryChunkReplicaCount

            val request = StorageNodeRequest(chunk.chunkSize, chunk.fileName, chunk.fileId)
            meshGossipService.broadcastStorageNodeRequest(request, timeoutMs = 5000) { candidates ->
                val replicasNeeded = desiredReplicas - currentReplicas
                val selectedNodes = candidates
                    .filter { it.availableSpace >= chunk.chunkSize }
                    .sortedWith(compareBy({ -it.availableSpace }, { it.latency }))
                    .take(replicasNeeded)

                selectedNodes.forEach { node ->
                    val chunkFile = File(selectedDropFolder?.toFile(), chunk.fileName)
                    val chunkBytes = chunkFile.readBytes()
                    meshGossipService.sendChunk(
                        node.url,
                        chunk.chunkId,
                        chunk.fileId,
                        chunk.chunkIndex,
                        chunk.totalChunks,
                        chunk.fileName,
                        chunk.relativePath,
                        chunkBytes
                    ) { /* Optionally handle completion */ }
                }
            }
        }
    }

    /**
     * Queries the mesh for the current replica count of a chunk.
     * Calls the callback with the count.
     */
    private fun queryChunkReplicaCount(chunkId: String, callback: (Int) -> Unit) {
        meshGossipService.queryFileReplicas(chunkId, timeoutMs = 3000) { replicaNodes ->
            callback(replicaNodes.size)
        }
    }

    /**
     * Retrieves a file using MeshrabiyaApi.
     */
    fun retrieveFile(fileId: String, onComplete: (Boolean) -> Unit) {
        meshrabiyaApi.retrieveFile(fileId, object : MeshrabiyaApi.RetrieveFileCallback {
            override fun onSuccess(file: File) {
                uiHandler.post { uiCallback?.showFileStored(fileId) }
                onComplete(true)
            }
            override fun onFailure(error: Throwable) {
                uiHandler.post { uiCallback?.showTransferFailedAlert() }
                onComplete(false)
            }
        })
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