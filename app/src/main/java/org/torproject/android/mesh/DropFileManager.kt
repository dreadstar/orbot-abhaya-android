package org.torproject.android.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.nio.file.*
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import java.io.File

/**
 * Manages drop folder monitoring, delegates storage, chunking, replication, and retrieval to DistributedStorageManager.
 * Integrates with UI via MeshStorageUiCallback.
 */
class DropFileManager private constructor(
    private val context: Context
) {

    private var selectedDropFolder: Path? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiCallback: MeshStorageUiCallback? = null

    companion object {
        @Volatile private var instance: DropFileManager? = null
        fun getInstance(context: Context): DropFileManager =
            instance ?: synchronized(this) {
                instance ?: DropFileManager(context.applicationContext).also { instance = it }
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
        selectedDropFolder = newPath
        // Folder monitoring for mesh events is handled by MeshrabiyaApiImpl
    }

    /**
     * Watches the drop folder for new files and triggers storage lifecycle.
     */


    /**
     * Handles new file: delegates chunking, storage, replication to DistributedStorageManager.
     */


    /**
     * Retrieves a file by requesting all chunks and recomposing via DistributedStorageManager.
     * Places the recomposed file in the drop folder or subfolder as indicated by relativePath.
     */
    fun retrieveFile(fileId: String, onComplete: (Boolean) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        val fileBytes = MeshrabiyaApiImpl.getInstance().retrieveFile(fileId)
        withContext(Dispatchers.Main) {
            if (fileBytes != null) {
                uiCallback?.showFileStored(fileId)
                onComplete(true)
            } else {
                uiCallback?.showTransferFailedAlert()
                onComplete(false)
            }
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