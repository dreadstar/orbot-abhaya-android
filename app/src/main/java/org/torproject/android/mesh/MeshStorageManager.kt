package org.torproject.android.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.nio.file.*
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshRoleManager
import com.ustadmobile.meshrabiya.storage.DataStore
import com.ustadmobile.meshrabiya.vnet.StorageNodeRequest
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.storage.MeshChunk
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.security.MessageDigest

/**
 * Manages drop folder monitoring, storage node discovery, chunked file transfer, retrieval, and replication.
 * Integrates with UI via MeshStorageUiCallback.
 */
class MeshStorageManager private constructor(private val context: Context) {

    private var dropFolderWatcherJob: Job? = null
    private var selectedDropFolder: Path? = null

    // Setup all required parameters for MeshGossipService
    private val virtualNode: VirtualNode = VirtualNode.getInstance(context)
    private val meshRoleManager: MeshRoleManager = MeshRoleManager.getInstance(context)
    private val dataStore: DataStore = DataStore.getInstance(context)
    private val scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    private val meshGossipService: MeshGossipService = MeshGossipService.getInstance(
        
        virtualNode,
        meshRoleManager,
        context,
        dataStore,
        scheduledExecutorService
    )

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
     * Handles new file: chunking, broadcasts storage node request, processes responses, transfers chunks, and triggers replication.
     */
    private fun handleDropFile(file: File) {
    val chunkSizeKb = MeshrabiyaConstants.getChunkSizeKb()
        val chunkSize = chunkSizeKb * 1024
        val fileId = sha256File(file)
        val relativePath = selectedDropFolder?.relativize(file.toPath())?.toString() ?: ""
        val chunks = chunkFile(file, fileId, chunkSize, relativePath)
        val totalChunks = chunks.size

        // Store chunk metadata locally using direct SQLite API
        CoroutineScope(Dispatchers.IO).launch {
            chunks.forEach { chunk ->
                dataStore.addMeshChunk(chunk)
            }
        }

        // For each chunk, discover storage nodes and transfer
        chunks.forEach { chunk ->
            val request = StorageNodeRequest(chunk.chunkSize, chunk.fileName, fileId)
            meshGossipService.broadcastStorageNodeRequest(request, timeoutMs = 5000) { responses ->
                processChunkStorageNodeResponses(chunk, responses)
            }
        }
    }

    /**
     * Chunks a file and returns a list of MeshChunk objects.
     */
    private fun chunkFile(file: File, fileId: String, chunkSize: Int, relativePath: String): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        val fileName = file.name
        val totalChunks = ((file.length() + chunkSize - 1) / chunkSize).toInt()
        FileInputStream(file).use { fis ->
            var chunkIndex = 0
            var bytesRead: Int
            val buffer = ByteArray(chunkSize)
            while (fis.read(buffer).also { bytesRead = it } > 0) {
                val chunkBytes = if (bytesRead < chunkSize) buffer.copyOf(bytesRead) else buffer.clone()
                val chunkId = sha256(chunkBytes)
                val hash = chunkId
                chunks.add(
                    MeshChunk(
                        chunkId = chunkId,
                        fileId = fileId,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        chunkSize = bytesRead.toLong(),
                        fileName = fileName,
                        relativePath = relativePath,
                        hash = hash
                    )
                )
                chunkIndex++
            }
        }
        return chunks
    }

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
     * Retrieves a file by requesting all chunks and recomposing.
     * Places the recomposed file in the drop folder or subfolder as indicated by relativePath.
     */
    fun retrieveFile(fileId: String, onComplete: (Boolean) -> Unit) {
    val timeoutMs = MeshrabiyaConstants.getTimeoutMs()
        meshGossipService.broadcastChunkRetrievalRequest(fileId, timeoutMs) { chunkInfos ->
            if (chunkInfos.isEmpty()) {
                uiHandler.post { uiCallback?.showTransferFailedAlert() }
                onComplete(false)
                return@broadcastChunkRetrievalRequest
            }
            val totalChunks = chunkInfos.size
            val retrievedChunks = Array<ByteArray?>(totalChunks) { null }
            val jobs = mutableListOf<Job>()

            chunkInfos.forEach { chunkInfo ->
                val job = CoroutineScope(Dispatchers.IO).launch {
                    var attempt = 0
                    val maxRetries = MeshrabiyaConstants.getMaxRetries()
                    var success = false
                    while (attempt < maxRetries && !success) {
                        meshGossipService.requestChunk(chunkInfo.chunkId, chunkInfo.nodeId, timeoutMs) { chunkBytes ->
                            if (chunkBytes != null) {
                                retrievedChunks[chunkInfo.chunkIndex] = chunkBytes
                                success = true
                            }
                        }
                        attempt++
                        if (!success) delay(RETRY_DELAY_MS)
                    }
                }
                jobs.add(job)
            }

            CoroutineScope(Dispatchers.IO).launch {
                jobs.forEach { it.join() }
                if (retrievedChunks.any { it == null }) {
                    uiHandler.post { uiCallback?.showTransferFailedAlert() }
                    onComplete(false)
                } else {
                    // Place recomposed file in drop folder/subfolder using relativePath
                    val firstChunk = chunkInfos.first()
                    val targetFolder = if (firstChunk.relativePath.isNotEmpty()) {
                        File(selectedDropFolder?.toFile(), firstChunk.relativePath)
                    } else {
                        selectedDropFolder?.toFile()
                    }
                    if (targetFolder != null && !targetFolder.exists()) targetFolder.mkdirs()
                    val outFile = File(targetFolder, firstChunk.fileName)
                    val fileBytes = retrievedChunks.filterNotNull().reduce { acc, bytes -> acc + bytes }
                    outFile.writeBytes(fileBytes)
                    uiHandler.post { uiCallback?.showFileStored(fileId) }
                    onComplete(true)
                }
            }
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } > 0) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
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