package org.torproject.android.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.nio.file.*
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.storage.MeshChunk
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.SyncPriority
import com.ustadmobile.meshrabiya.storage.ReplicationLevel
import com.ustadmobile.meshrabiya.storage.StorageConfiguration
import java.io.File

/**
 * Manages drop folder monitoring, delegates storage, chunking, replication, and retrieval to DistributedStorageManager.
 * Integrates with UI via MeshStorageUiCallback.
 */
class DropFileManager private constructor(
    private val context: Context,
    private val distributedStorageManager: DistributedStorageManager
) {

    private var dropFolderWatcherJob: Job? = null
    private var selectedDropFolder: Path? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiCallback: MeshStorageUiCallback? = null

    companion object {
        @Volatile private var instance: DropFileManager? = null
        fun getInstance(context: Context, distributedStorageManager: DistributedStorageManager): DropFileManager =
            instance ?: synchronized(this) {
                instance ?: DropFileManager(context.applicationContext, distributedStorageManager).also { instance = it }
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
     * Handles new file: delegates chunking, storage, replication to DistributedStorageManager.
     */
    private fun handleDropFile(file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            val fileBytes = file.readBytes()
            val fileRef = distributedStorageManager.storeFile(
                file.absolutePath,
                fileBytes,
                priority = SyncPriority.NORMAL,
                replicationLevel = ReplicationLevel.STANDARD
            )
            uiHandler.post {
                if (fileRef != null) {
                    uiCallback?.showFileStored(fileRef.id)
                } else {
                    uiCallback?.showTransferFailedAlert()
                }
            }
        }
    }

    /**
     * Retrieves a file by requesting all chunks and recomposing via DistributedStorageManager.
     * Places the recomposed file in the drop folder or subfolder as indicated by relativePath.
     */
    fun retrieveFile(fileId: String, onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val fileInfo = distributedStorageManager.storageStats.value
            val fileRef = distributedStorageManager.storageStats.value.let {
                // Find FileReference by fileId (id == fileId)
                // This assumes you have a way to map fileId to FileReference, adapt as needed
                // For demo, just create a FileReference with path from drop folder
                FileReference(fileId, selectedDropFolder?.resolve(fileId)?.toString() ?: fileId, 0L)
            }
            val fileBytes = distributedStorageManager.retrieveFile(fileRef)
            if (fileBytes != null) {
                val outFile = File(selectedDropFolder?.toFile(), File(fileRef.path).name)
                outFile.writeBytes(fileBytes)
                uiHandler.post { uiCallback?.showFileStored(fileId) }
                onComplete(true)
            } else {
                uiHandler.post { uiCallback?.showTransferFailedAlert() }
                onComplete(false)
            }
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