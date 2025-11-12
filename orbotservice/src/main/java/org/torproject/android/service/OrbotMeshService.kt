package org.torproject.android.service

import android.app.Service
import java.util.UUID
import android.content.Intent
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import com.ustadmobile.meshrabiya.storage.DataStore
import com.ustadmobile.meshrabiya.storage.MeshFile
import com.ustadmobile.meshrabiya.storage.ReplicationManager
import java.io.File
import android.os.IBinder
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreference
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * OrbotMeshService - Handles mesh file reception, storage, replication, and completion notification.
 * Implements robust error handling, atomic file operations, and mesh messaging.
 * Refactored to use MeshrabiyaApi for proper separation of concerns.
 */
class OrbotMeshService : Service() {

    private val Context.dataStore by preferencesDataStore(name = "orbot_mesh_settings")
    // Reference to MeshrabiyaApi for mesh operations
    private lateinit var meshrabiyaApi: MeshrabiyaApi
    // Reference to ReplicationManager for replication functionality
    private lateinit var replicationManager: ReplicationManager
    // Reference to DataStore for direct SQLite operations
    private lateinit var dataStore: DataStore

    override fun onCreate() {
        super.onCreate()
        // Get MeshrabiyaApi singleton and initialize if needed
        meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
        meshrabiyaApi.initMesh(applicationContext)
        dataStore = DataStore.getInstance(applicationContext)

        replicationManager = ReplicationManager(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Store a received file using MeshrabiyaApi.
     * The API handles internal storage, fileId generation, and mesh coordination.
     * Returns the fileId via callback for replication.
     */
    fun storeReceivedFile(file: File, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        meshrabiyaApi.storeFile(file) { result ->
            result.fold(
                onSuccess = { fileId ->
                    println("INFO: File stored successfully with fileId $fileId via MeshrabiyaApi")
                    onSuccess(fileId)
                },
                onFailure = { exception ->
                    println("ERROR: Failed to store file ${file.absolutePath} via MeshrabiyaApi: ${exception.message}")
                    onFailure(exception as Exception)
                }
            )
        }
    }

    /**
     * Handle incoming mesh file transfer requests.
     * Entry point for mesh file reception and triggers replication.
     * Refactored to use MeshrabiyaApi for proper separation of concerns.
     */
    fun onMeshFileReceived(file: File, senderNodeId: String) {
        try {
            storeReceivedFile(
                file = file,
                onSuccess = { fileId ->
                    // Trigger replication using ReplicationManager
                    replicationManager.replicateFile(fileId, file)
                    println("INFO: File received, stored, and replication triggered with fileId $fileId from sender $senderNodeId")
                },
                onFailure = { exception ->
                    println("ERROR: Failed to process received file from $senderNodeId: ${exception.message}")
                }
            )
        } catch (e: Exception) {
            println("ERROR: Failed to process received file from $senderNodeId: ${e.message}")
        }
    }
}