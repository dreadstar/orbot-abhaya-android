package org.torproject.android.service

import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.datastore.preferences.preferencesDataStore
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import java.io.File
import com.ustadmobile.meshrabiya.api.RecipientEntryDto
import com.ustadmobile.meshrabiya.api.RecipientTypeDto

/**
 * OrbotMeshService - Handles mesh operations through MeshrabiyaApi.
 * 
 * Section 8: Refactored to include:
 * - Binder interface for client access to MeshrabiyaApi
 * - Tor proxy port integration via LocalBroadcastReceiver
 * - Proper lifecycle management
 */
class OrbotMeshService : Service() {

    private val Context.dataStore by preferencesDataStore(name = "orbot_mesh_settings")
    
    // Reference to MeshrabiyaApi for mesh operations
    private lateinit var meshrabiyaApi: MeshrabiyaApiImpl
    
    // Section 8: Binder interface for client access
    private val binder = MeshBinder()
    
    // Section 8: Tor proxy settings
    private var socksPort: Int = 9050  // Default
    private var httpPort: Int = 8118   // Default
    private var dnsPort: Int = 5400    // Default
    private var portsReceiver: BroadcastReceiver? = null
    
    companion object {
        private const val TAG = "OrbotMeshService"
    }
    
    /**
     * Section 8.2: Binder implementation for client access to MeshrabiyaApi
     */
    inner class MeshBinder : Binder() {
        fun getApi(): MeshrabiyaApi = meshrabiyaApi
        fun getSocksPort(): Int = socksPort
        fun getHttpPort(): Int = httpPort
        fun getDnsPort(): Int = dnsPort
    }

    override fun onCreate() {
        super.onCreate()
        
        // Get MeshrabiyaApi singleton and initialize if needed
        meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
        meshrabiyaApi.initMesh(applicationContext)
        
        // Section 8.3: Register Tor port broadcast receiver
        registerTorPortReceiver()
        
        Log.i(TAG, "OrbotMeshService created and initialized")
    }
    
    /**
     * Section 8.3: Register LocalBroadcastReceiver for Tor proxy port updates
     */
    private fun registerTorPortReceiver() {
        portsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                socksPort = intent.getIntExtra(OrbotConstants.EXTRA_SOCKS_PROXY_PORT, 9050)
                httpPort = intent.getIntExtra(OrbotConstants.EXTRA_HTTP_PROXY_PORT, 8118)
                dnsPort = intent.getIntExtra(OrbotConstants.EXTRA_DNS_PORT, 5400)
                
                Log.d(TAG, "Tor ports received: SOCKS=$socksPort, HTTP=$httpPort, DNS=$dnsPort")
                
                // TODO: Configure mesh proxy settings if API supports it
                // configureTorProxy()
            }
        }
        
        val filter = IntentFilter(OrbotConstants.LOCAL_ACTION_PORTS)
        LocalBroadcastManager.getInstance(this).registerReceiver(portsReceiver!!, filter)
        
        Log.d(TAG, "Tor port receiver registered")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Client binding to OrbotMeshService")
        return binder
    }
    
    override fun onDestroy() {
        // Unregister Tor port receiver
        portsReceiver?.let {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
        
        Log.i(TAG, "OrbotMeshService destroyed")
        super.onDestroy()
    }

    /**
     * Store a received file using MeshrabiyaApi.
     * The API handles internal storage, fileId generation, and mesh coordination.
     * Returns the fileId via callback.
     */
    fun storeReceivedFile(file: File, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        // TODO: Replace with actual recipient construction logic as needed
        val recipients = listOf(
            RecipientEntryDto(
                publicKey = "demo-public-key", // Replace with actual public key
                recipientType = RecipientTypeDto.USER,
                recipientId = "demo-user-id" // Replace with actual user id
            )
        )
        meshrabiyaApi.setOnFileStored { fileId, fileObj, result ->
            result.fold(
                onSuccess = { id ->
                    Log.i(TAG, "File stored successfully with fileId $id via MeshrabiyaApi")
                    onSuccess(id)
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to store file ${file.absolutePath} via MeshrabiyaApi: ${exception.message}")
                    onFailure(exception as Exception)
                }
            )
        }
        meshrabiyaApi.storeFile(file, recipients)
    }

    /**
     * Handle incoming mesh file transfer requests.
     * Entry point for mesh file reception.
     * Refactored to use MeshrabiyaApi for proper separation of concerns.
     */
    fun onMeshFileReceived(file: File, senderNodeId: String) {
        try {
            storeReceivedFile(
                file = file,
                onSuccess = { fileId ->
                    Log.i(TAG, "File received and stored with fileId $fileId from sender $senderNodeId")
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to process received file from $senderNodeId: ${exception.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process received file from $senderNodeId: ${e.message}")
        }
    }
}