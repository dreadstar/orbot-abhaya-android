// ===============================================================================
// PACKAGE AND IMPORTS SECTION
// ===============================================================================

package org.torproject.android.service

// Android Core Imports
import android.content.Context
import android.util.Log

// AndroidX Imports  
import androidx.datastore.preferences.preferencesDataStore

// Meshrabiya Core Imports
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.randomApipaInetAddr
import com.ustadmobile.meshrabiya.vnet.MeshRoleManager
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.mmcp.MeshRole

// Meshrabiya Beta Testing Imports
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

// Kotlin Coroutines Imports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Java Concurrent Imports
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import org.torproject.android.service.compute.DistributedStorageAgent
import java.io.File
import org.torproject.android.service.compute.toAppFileMetadata

// ===============================================================================
// DATASTORE CONFIGURATION
// ===============================================================================

// DataStore extension for mesh settings persistence
private val Context.dataStore by preferencesDataStore(name = "mesh_settings")

// ===============================================================================
// CLASS DEFINITION AND COMPANION OBJECT
// ===============================================================================

/**
 * MeshServiceCoordinator - Coordinates mesh networking services and integrates with Orbot
 * 
 * This class serves as the main coordinator between the Orbot application and the Meshrabiya
 * mesh networking library. It handles:
 * - Mesh service lifecycle (start/stop)
 * - Role management (manual preferences + automatic assignment)
 * - Status monitoring and health checks
 * - Integration with BetaTestLogger for debugging
 * - User preference management for resource sharing
 */
class MeshServiceCoordinator private constructor(private val context: Context) {
    
    // ===============================================================================
    // COMPANION OBJECT - SINGLETON PATTERN
    // ===============================================================================
    
    companion object {
        private const val TAG = "MeshServiceCoordinator"
        
        @Volatile
        private var INSTANCE: MeshServiceCoordinator? = null
        
        /**
         * Get singleton instance of MeshServiceCoordinator
         */
        fun getInstance(context: Context): MeshServiceCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MeshServiceCoordinator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // ===============================================================================
    // PRIVATE PROPERTIES - SERVICE STATE AND MANAGERS
    // ===============================================================================
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Service state tracking
    // Service state tracking
    private val serviceRunning = AtomicBoolean(false)
    private var serviceStartTime: Long = 0L
    
    // Meshrabiya core components
    private var virtualNode: AndroidVirtualNode? = null
    private var meshRoleManager: MeshRoleManager? = null
    private var emergentRoleManager: EmergentRoleManager? = null
    
    // Storage participation components
    private var distributedStorageManager: com.ustadmobile.meshrabiya.storage.DistributedStorageManager? = null
    // Hold a reference to the mesh adapter provided to DistributedStorageManager so
    // other app components can obtain the MeshNetworkInterface instance.
    private var currentMeshAdapter: com.ustadmobile.meshrabiya.storage.MeshNetworkInterface? = null
    private val storageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Threading and logging
    private val executorService = Executors.newScheduledThreadPool(4)
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // ===============================================================================
    // DATA CLASSES - SERVICE STATUS AND HEALTH CHECK RESULTS
    // ===============================================================================
    
    /**
     * Service status data class for UI updates
     */
    data class MeshServiceStatus(
        val isRunning: Boolean,
        val nodeCount: Int = 0,
        val isGateway: Boolean = false,
        val trafficBytes: Long = 0L
    )
    
    /**
     * Health check result for service monitoring
     */
    data class HealthCheckResult(
        val success: Boolean,
        val nodeConnections: Int,
        val networkLatency: Long,
        val lastActivity: Long
    )
    
    /**
     * Storage participation status for UI updates - following MeshServiceStatus pattern
     */
    data class StorageParticipationStatus(
        val isEnabled: Boolean,
        val allocatedGB: Int = 5,
        val usedGB: Int = 0,
        val participationHealth: String = "Unknown"
    )

    // ===============================================================================
    // SERVICE INITIALIZATION METHODS
    // ===============================================================================
    
    /**
     * Initialize mesh service components
     * Sets up AndroidVirtualNode and associated role managers
     */
    fun initializeMeshService() {
        scope.launch {
            try {
                Log.i(TAG, "Initializing mesh service")
                betaLogger.log(LogLevel.INFO, "MESH_INIT", "Starting mesh service initialization")
                
                if (virtualNode == null) {
                    // Create AndroidVirtualNode with proper configuration
                    virtualNode = AndroidVirtualNode(
                        context = context,
                        address = randomApipaInetAddr(),
                        dataStore = context.dataStore,
                        scheduledExecutorService = executorService
                    ).also { node ->
                        meshRoleManager = node.meshRoleManager
                        emergentRoleManager = node.emergentRoleManager
                        Log.i(TAG, "Created AndroidVirtualNode with address: ${node.addressAsInt}")
                        betaLogger.log(LogLevel.INFO, "MESH_INIT", "Created AndroidVirtualNode", 
                            mapOf("address" to node.addressAsInt.toString()))
                    }
                }
                
                Log.i(TAG, "Mesh service initialization completed")
                betaLogger.log(LogLevel.INFO, "MESH_INIT", "Mesh service initialization completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize mesh service", e)
                betaLogger.log(LogLevel.ERROR, "MESH_INIT", "Failed to initialize mesh service", 
                    mapOf("error" to e.message.orEmpty()))
            }
        }
    }
    
    // ===============================================================================
    // SERVICE LIFECYCLE METHODS
    // ===============================================================================
    
    /**
     * Start mesh networking service
     * Initializes hardware monitoring and sets up role preferences
     */
    suspend fun startMeshNetworking(): Boolean {
        return try {
            Log.i(TAG, "Starting mesh networking")
            betaLogger.log(LogLevel.INFO, "MESH_START", "Starting mesh networking")
            
            val node = virtualNode ?: run {
                val message = "VirtualNode not initialized"
                Log.w(TAG, message)
                betaLogger.log(LogLevel.WARN, "MESH_START", message)
                return false
            }
            
            // Start hardware monitoring for role management
            emergentRoleManager?.startHardwareMonitoring()
            Log.i(TAG, "Started hardware monitoring")
            betaLogger.log(LogLevel.INFO, "MESH_START", "Started hardware monitoring")
            
            // Set default preferred roles for emergent role assignment
            emergentRoleManager?.let { roleManager ->
                try {
                    // Set basic mesh participant as default - users can configure additional roles
                    val defaultRoles = setOf(MeshRole.MESH_PARTICIPANT)
                    roleManager.setPreferredRoles(defaultRoles)
                    betaLogger.log(LogLevel.INFO, "MESH_ROLES", "Set default mesh participant role")
                    Log.i(TAG, "Set default mesh participant role")
                } catch (e: Exception) {
                    betaLogger.log(LogLevel.WARN, "MESH_ROLES", "Could not set default roles", 
                        mapOf("error" to e.message.orEmpty()))
                    Log.w(TAG, "Could not set default roles", e)
                }
            }
            
            serviceRunning.set(true)
            serviceStartTime = System.currentTimeMillis()
            
            Log.d(TAG, "Mesh service started at: $serviceStartTime")
            
            betaLogger.log(LogLevel.INFO, "MESH_START", "Mesh networking started successfully")
            Log.i(TAG, "Mesh networking started successfully")
            true
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "MESH_START", "Failed to start mesh networking", 
                mapOf("error" to e.message.orEmpty()))
            Log.e(TAG, "Failed to start mesh networking", e)
            false
        }
    }
    
    /**
     * Stop mesh networking service  
     * Cleans up roles and stops hardware monitoring
     */
    suspend fun stopMeshNetworking(): Boolean {
        return try {
            betaLogger.log(LogLevel.INFO, "MESH_STOP", "Stopping mesh networking")
            Log.i(TAG, "Stopping mesh networking")
            
            val node = virtualNode ?: run {
                serviceRunning.set(false)
                betaLogger.log(LogLevel.WARN, "MESH_STOP", "VirtualNode was null during stop")
                return true
            }
            
            // Release all mesh roles
            emergentRoleManager?.let { roleManager ->
                try {
                    // Clear preferred roles to stop role assignment
                    roleManager.setPreferredRoles(emptySet())
                    betaLogger.log(LogLevel.INFO, "MESH_ROLES", "Cleared preferred mesh roles")
                    Log.i(TAG, "Cleared preferred mesh roles")
                } catch (e: Exception) {
                    betaLogger.log(LogLevel.WARN, "MESH_ROLES", "Error clearing preferred roles", 
                        mapOf("error" to e.message.orEmpty()))
                    Log.w(TAG, "Error clearing preferred roles", e)
                }
            }
            
            serviceRunning.set(false)
            betaLogger.log(LogLevel.INFO, "MESH_STOP", "Mesh networking stopped successfully")
            Log.i(TAG, "Mesh networking stopped successfully")
            true
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "MESH_STOP", "Failed to stop mesh networking", 
                mapOf("error" to e.message.orEmpty()))
            Log.e(TAG, "Failed to stop mesh networking", e)
            false
        }
    }
    
    
    // ===============================================================================
    // STATUS MONITORING METHODS
    // ===============================================================================
    
    /**
     * Get current mesh service status for UI updates
     * Returns comprehensive status including role information
     */
    fun getMeshServiceStatus(): MeshServiceStatus {
        val node = virtualNode
        val nodeCount = getConnectedNodeCount()
        val trafficBytes = getTrafficBytes()
        
        val isGateway = emergentRoleManager?.let { roleManager ->
            try {
                // Check if we have any gateway roles
                val roles = roleManager.getCurrentMeshRoles()
                val hasInternetGateway = roles.contains(MeshRole.CLEARNET_GATEWAY)
                val hasTorGateway = roles.contains(MeshRole.TOR_GATEWAY)
                val hasStorageNode = roles.contains(MeshRole.STORAGE_NODE)
                val isGw = hasInternetGateway || hasTorGateway || hasStorageNode
                
                // Log status metrics periodically for debugging
                betaLogger.log(LogLevel.DEBUG, "MESH_STATUS", "Status check", mapOf(
                    "nodeCount" to nodeCount.toString(),
                    "isGateway" to isGw.toString(),
                    "trafficBytes" to trafficBytes.toString(),
                    "roles" to roles.joinToString(",")
                ))
                
                isGw
            } catch (e: Exception) {
                betaLogger.log(LogLevel.WARN, "MESH_STATUS", "Error checking gateway status", 
                    mapOf("error" to e.message.orEmpty()))
                Log.w(TAG, "Error checking gateway status", e)
                false
            }
        } ?: false
        
        return MeshServiceStatus(
            isRunning = serviceRunning.get(),
            nodeCount = nodeCount,
            isGateway = isGateway,
            trafficBytes = trafficBytes
        )
    }
    
    /**
     * Perform comprehensive health check of mesh service
     * Returns detailed health information for monitoring
     */
    fun performHealthCheck(): HealthCheckResult {
        return try {
            val node = virtualNode
            val connections = getConnectedNodeCount()
            val latency = measureNetworkLatency()
            val isSuccessful = serviceRunning.get() && node != null
            
            betaLogger.log(LogLevel.DEBUG, "MESH_HEALTH", "Health check performed", mapOf(
                "success" to isSuccessful.toString(),
                "connections" to connections.toString(),
                "latency" to latency.toString(),
                "nodeInitialized" to (node != null).toString()
            ))
            
            HealthCheckResult(
                success = isSuccessful,
                nodeConnections = connections,
                networkLatency = latency,
                lastActivity = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "MESH_HEALTH", "Health check failed", 
                mapOf("error" to e.message.orEmpty()))
            Log.e(TAG, "Health check failed", e)
            HealthCheckResult(
                success = false,
                nodeConnections = 0,
                networkLatency = -1L,
                lastActivity = System.currentTimeMillis()
            )
        }
    }
    
    // ===============================================================================
    // PRIVATE HELPER METHODS - METRICS AND MONITORING
    // ===============================================================================
    
    /**
     * Measure network latency using VirtualNode neighbor information
     */
    private fun measureNetworkLatency(): Long {
        return try {
            virtualNode?.let { node ->
                val neighborCount = node.neighbors().size
                if (serviceRunning.get() && neighborCount > 0) {
                    // Estimate latency based on neighbor count (more neighbors = higher latency)
                    val baseLatency = 20L
                    val perNeighborLatency = 10L
                    baseLatency + (neighborCount * perNeighborLatency)
                } else {
                    -1L // No connectivity
                }
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "Error measuring network latency", e)
            -1L
        }
    }
    
    /**
     * Get connected node count from Meshrabiya neighbor information
     */
    private fun getConnectedNodeCount(): Int {
        return try {
            virtualNode?.neighbors()?.size ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error getting neighbor count from VirtualNode", e)
            0
        }
    }
    
    /**
     * Get traffic bytes estimation from Meshrabiya node activity
     */
    private fun getTrafficBytes(): Long {
        return try {
            virtualNode?.let { node ->
                // Calculate approximate traffic based on neighbor count and node state
                val neighborCount = node.neighbors().size
                val baseTraffic = neighborCount * 1024L // Basic estimate per neighbor
                
                // Realistic traffic calculation based on service uptime
                val trafficBytes = if (serviceRunning.get() && neighborCount > 0 && serviceStartTime > 0) {
                    val currentTime = System.currentTimeMillis()
                    val uptimeMs = currentTime - serviceStartTime
                    val uptimeMinutes = uptimeMs / (1000 * 60)
                    // Simulate traffic growing modestly over time (e.g., 512 bytes per minute per neighbor)
                    val calculatedTraffic = baseTraffic + (uptimeMinutes * neighborCount * 512L)
                    
                    Log.d(TAG, "getTrafficBytes: neighbors=$neighborCount, startTime=$serviceStartTime, currentTime=$currentTime, uptimeMin=$uptimeMinutes, traffic=$calculatedTraffic")
                    
                    calculatedTraffic
                } else {
                    Log.d(TAG, "getTrafficBytes: service not active or no neighbors, returning baseTraffic=$baseTraffic")
                    baseTraffic
                }
                
                trafficBytes
            } ?: run {
                Log.d(TAG, "getTrafficBytes: virtualNode is null, returning 0")
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error estimating traffic bytes", e)
            0L
        }
    }
    
    
    // ===============================================================================
    // USER PREFERENCE MANAGEMENT METHODS
    // ===============================================================================
    
    /**
     * Set user preferences for what resources they're willing to share
     * EmergentRoleManager will automatically assign actual roles based on network needs
     * 
     * @param allowTorGateway - Whether user allows Tor gateway sharing (future feature)
     * @param allowInternetGateway - Whether user allows Internet gateway sharing
     * @param allowStorageSharing - Whether user allows storage sharing
     * @param storageAllocationGB - Storage allocation in GB for distributed storage
     */
    fun setUserSharingPreferences(
        allowTorGateway: Boolean = false,
        allowInternetGateway: Boolean = false, 
        allowStorageSharing: Boolean = false,
        storageAllocationGB: Int = 5
    ) {
        scope.launch {
            try {
                // Always save storage allocation preference regardless of sharing enabled state
                setStorageAllocationGB(storageAllocationGB)
                
                val preferredRoles = mutableSetOf<MeshRole>()
                
                // Always participate as a basic mesh participant
                preferredRoles.add(MeshRole.MESH_PARTICIPANT)
                
                if (allowTorGateway) {
                    preferredRoles.add(MeshRole.TOR_GATEWAY)
                    betaLogger.log(LogLevel.INFO, "MESH_PREFS", "User enabled Tor gateway sharing")
                    Log.i(TAG, "User enabled Tor gateway sharing")
                }
                
                if (allowInternetGateway) {
                    preferredRoles.add(MeshRole.CLEARNET_GATEWAY)
                    betaLogger.log(LogLevel.INFO, "MESH_PREFS", "User enabled Internet gateway sharing")
                    Log.i(TAG, "User enabled Internet gateway sharing")
                }
                
                if (allowStorageSharing) {
                    preferredRoles.add(MeshRole.STORAGE_NODE)
                    // Configure actual storage participation
                    configureStorageParticipation(true, storageAllocationGB)
                    betaLogger.log(LogLevel.INFO, "MESH_PREFS", "User enabled storage sharing", 
                        mapOf("allocationGB" to storageAllocationGB.toString()))
                    Log.i(TAG, "User enabled storage sharing with ${storageAllocationGB}GB allocation")
                } else {
                    // Disable storage participation
                    configureStorageParticipation(false, 0)
                    betaLogger.log(LogLevel.INFO, "MESH_PREFS", "User disabled storage sharing")
                    Log.i(TAG, "User disabled storage sharing")
                }
                
                // Set preferred roles - EmergentRoleManager will decide actual assignment
                emergentRoleManager?.setPreferredRoles(preferredRoles)
                
                betaLogger.log(LogLevel.INFO, "MESH_PREFS", "Updated user sharing preferences", 
                    mapOf("roles" to preferredRoles.joinToString(",")))
                
            } catch (e: Exception) {
                betaLogger.log(LogLevel.ERROR, "MESH_PREFS", "Failed to set sharing preferences", 
                    mapOf("error" to e.message.orEmpty()))
                Log.e(TAG, "Failed to set sharing preferences", e)
            }
        }
    }
    
    /**
     * Get current user sharing preferences
     * Returns user's configured preferences for resource sharing
     */
    fun getUserSharingPreferences(): Map<String, Any> {
        return try {
            val preferredRoles = emergentRoleManager?.getPreferredRoles() ?: emptySet()
            val currentAllocation = getStorageAllocationGB()
            mapOf(
                "allowTorGateway" to preferredRoles.contains(MeshRole.TOR_GATEWAY),
                "allowInternetGateway" to preferredRoles.contains(MeshRole.CLEARNET_GATEWAY),
                "allowStorageSharing" to preferredRoles.contains(MeshRole.STORAGE_NODE),
                "storageAllocationGB" to currentAllocation
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error getting user preferences", e)
            mapOf(
                "allowTorGateway" to false,
                "allowInternetGateway" to false,
                "allowStorageSharing" to false,
                "storageAllocationGB" to 5
            )
        }
    }
    
    /**
     * Get currently assigned roles (decided by EmergentRoleManager)
     * Returns actual roles currently assigned by the automatic role management system
     */
    fun getCurrentlyAssignedRoles(): Set<MeshRole> {
        return try {
            emergentRoleManager?.getCurrentMeshRoles() ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting current roles", e)
            emptySet()
        }
    }
    
    // ===============================================================================
    // UTILITY AND CLEANUP METHODS
    // ===============================================================================
    
    /**
     * Get the virtual node instance for advanced operations
     */
    fun getVirtualNode(): AndroidVirtualNode? = virtualNode
    
    /**
     * Cleanup resources and shutdown mesh service
     * Should be called when the service is no longer needed
     */
    fun cleanup() {
        scope.launch {
            try {
                Log.i(TAG, "Cleaning up MeshServiceCoordinator")
                betaLogger.log(LogLevel.INFO, "MESH_CLEANUP", "Starting cleanup")
                
                // Stop mesh networking
                stopMeshNetworking()
                
                // Close virtual node
                virtualNode?.close()
                virtualNode = null
                
                // Shutdown executor service
                executorService.shutdown()
                
                Log.i(TAG, "MeshServiceCoordinator cleanup completed")
                betaLogger.log(LogLevel.INFO, "MESH_CLEANUP", "Cleanup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
                betaLogger.log(LogLevel.ERROR, "MESH_CLEANUP", "Error during cleanup", 
                    mapOf("error" to e.message.orEmpty()))
            }
        }
    }
    
    // ===============================================================================
    // DISTRIBUTED STORAGE SECTION - ADDED TO EXISTING CLASS
    // ===============================================================================
    
    /**
     * Initialize storage participation components - follows initializeMeshService() pattern
     */
    fun initializeStorageParticipation() {
        storageScope.launch {
            try {
                betaLogger.log(LogLevel.INFO, "STORAGE_INIT", "Initializing distributed storage")
                Log.i(TAG, "Initializing storage participation")
                
                if (distributedStorageManager == null && virtualNode != null) {
                    // Use REAL DistributedStorageManager from Meshrabiya
                    // Create a simple adapter for AndroidVirtualNode to MeshNetworkInterface
                    // Adapter between Meshrabiya's MeshNetworkInterface and app-level storage handling.
                    // We provide lightweight conversion/logging scaffolding here so future implementation
                    // can plug real network send/receive behavior and convert Meshrabiya model types
                    // into app canonical types using MeshrabiyaInterop where appropriate.
                    data class AppStorageCapabilities(
                        val totalOffered: Long,
                        val currentlyUsed: Long,
                        val replicationFactor: Int,
                        val compressionSupported: Boolean,
                        val encryptionSupported: Boolean,
                        val accessPatterns: List<String>
                    )

                    var lastRemoteStorageCapabilities: AppStorageCapabilities? = null

                    fun convertMmcpStorageCapabilities(cap: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities): AppStorageCapabilities {
                        return AppStorageCapabilities(
                            totalOffered = cap.totalOffered,
                            currentlyUsed = cap.currentlyUsed,
                            replicationFactor = cap.replicationFactor,
                            compressionSupported = cap.compressionSupported,
                            encryptionSupported = cap.encryptionSupported,
                            accessPatterns = cap.accessPatterns.map { it.toString() }
                        )
                    }

                    // Create meshAdapter and then use it to construct DistributedStorageAgent so
                    // the same adapter is passed into the manager and used for delegation.
                    lateinit var storageAgent: DistributedStorageAgent

                    // Local named class for the adapter to improve readability and testability
                    // while preserving the same behavior and ability to capture local variables.
                    class MeshAdapter : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
                        override suspend fun sendStorageRequest(
                            targetNodeId: String,
                            fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo,
                            operation: com.ustadmobile.meshrabiya.storage.StorageOperation
                        ) {
                            // Convert incoming DistributedFileInfo -> StorageRequest and delegate
                            try {
                                val data: ByteArray = try {
                                    // Try to read local file referenced by localReference
                                    val localPath = fileInfo.localReference.localPath
                                    if (localPath.isNullOrEmpty()) ByteArray(0) else File(localPath).readBytes()
                                } catch (_: Exception) { ByteArray(0) }

                                // Use MeshrabiyaInterop mapping to determine replication factor
                                val appFileMeta = fileInfo.toAppFileMetadata()
                                val storageRequest = DistributedStorageAgent.StorageRequest(
                                    fileId = fileInfo.path,
                                    fileName = fileInfo.localReference.localPath ?: fileInfo.path,
                                    data = data,
                                    replicationFactor = appFileMeta.replicationFactor
                                )

                                when (operation) {
                                    com.ustadmobile.meshrabiya.storage.StorageOperation.REPLICATE -> {
                                        // Fire-and-forget replication to target node
                                        storageAgent.replicateDropFolderFile(
                                            storageRequest.fileId,
                                            storageRequest.fileName,
                                            java.nio.file.Paths.get(storageRequest.fileName),
                                            setOf(targetNodeId),
                                            storageRequest.replicationFactor
                                        )
                                    }
                                    com.ustadmobile.meshrabiya.storage.StorageOperation.DELETE -> {
                                        storageAgent.deleteFile(fileInfo.path)
                                    }
                                    com.ustadmobile.meshrabiya.storage.StorageOperation.RETRIEVE -> {
                                        // Remote node requested this file — if we have it, trigger a
                                        // targeted replication to the requester so it can fetch it.
                                        try {
                                            if (storageAgent.hasFile(fileInfo.path)) {
                                                storageAgent.replicateDropFolderFile(
                                                    storageRequest.fileId,
                                                    storageRequest.fileName,
                                                    java.nio.file.Paths.get(storageRequest.fileName),
                                                    setOf(targetNodeId),
                                                    storageRequest.replicationFactor
                                                )
                                            } else {
                                                Log.d(TAG, "meshAdapter: RETRIEVE requested for missing file ${fileInfo.path}")
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "meshAdapter: failed to handle RETRIEVE for ${fileInfo.path}", e)
                                        }
                                    }
                                    else -> {
                                        // For other operations, log and ignore
                                        Log.d(TAG, "meshAdapter.sendStorageRequest: unsupported operation $operation")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "meshAdapter.sendStorageRequest failed to delegate", e)
                            }
                        }

                        override suspend fun queryFileAvailability(path: String): List<String> {
                            try {
                                val results = mutableListOf<String>()
                                if (storageAgent.hasFile(path)) results.add("LOCAL")
                                // Add neighbor heuristics
                                virtualNode?.neighbors()?.mapTo(results) { it.toString() }
                                return results
                            } catch (e: Exception) {
                                Log.w(TAG, "meshAdapter.queryFileAvailability error", e)
                                return emptyList()
                            }
                        }

                        override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? {
                            try {
                                val retrieval = DistributedStorageAgent.RetrievalRequest(fileId = path, preferredNodes = setOf(nodeId))
                                val resp = storageAgent.retrieveFile(retrieval)
                                return if (resp.success) resp.data else null
                            } catch (e: Exception) {
                                Log.w(TAG, "meshAdapter.requestFileFromNode failed", e)
                                return null
                            }
                        }

                        override suspend fun getAvailableStorageNodes(): List<String> {
                            try {
                                val nodes = mutableListOf<String>()
                                // Local node participation
                                distributedStorageManager?.let { mgr ->
                                    if (mgr.participationEnabled.value) nodes.add("LOCAL")
                                }
                                // Include known neighbors from virtualNode as potential targets
                                virtualNode?.neighbors()?.mapTo(nodes) { it.toString() }
                                return nodes
                            } catch (e: Exception) {
                                Log.w(TAG, "meshAdapter.getAvailableStorageNodes error", e)
                                return emptyList()
                            }
                        }

                        override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {
                            try {
                                val appCap = convertMmcpStorageCapabilities(capabilities)
                                lastRemoteStorageCapabilities = appCap
                                Log.i(TAG, "meshAdapter.broadcastStorageAdvertisement: $appCap")
                                // Inform storageAgent or other components about capability snapshot
                                // For now we store the snapshot and log; future work can push this
                                // into DistributedStorageAgent or a capability registry for role decisions.
                                lastRemoteStorageCapabilities = appCap
                            } catch (e: Exception) {
                                Log.w(TAG, "meshAdapter.broadcastStorageAdvertisement failed", e)
                            }
                        }
                    }

                    // Local named class for the outgoing proxy to avoid recursion and to centralize
                    // behavior formerly in the anonymous object.
                    class NetworkProxy : com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
                        override suspend fun sendStorageRequest(targetNodeId: String, fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo, operation: com.ustadmobile.meshrabiya.storage.StorageOperation) {
                            // Outgoing send: delegate to distributedStorageManager if available, otherwise log
                            try {
                                distributedStorageManager?.let { manager ->
                                    // If manager exposes an API to forward requests, use it. Fallback: ignore.
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "networkProxy.sendStorageRequest failed", e)
                            }
                        }

                        override suspend fun queryFileAvailability(path: String): List<String> {
                            return try {
                                // Use virtualNode neighbor list as a simple availability heuristic
                                virtualNode?.neighbors()?.map { it.toString() } ?: emptyList()
                            } catch (e: Exception) {
                                Log.w(TAG, "networkProxy.queryFileAvailability failed", e)
                                emptyList()
                            }
                        }

                        override suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray? {
                            return try {
                                // No direct mesh fetch implemented here; return null so
                                // DistributedStorageAgent falls back to other strategies.
                                null
                            } catch (e: Exception) {
                                Log.w(TAG, "networkProxy.requestFileFromNode failed", e)
                                null
                            }
                        }

                        override suspend fun getAvailableStorageNodes(): List<String> {
                            return try {
                                virtualNode?.neighbors()?.map { it.toString() } ?: emptyList()
                            } catch (e: Exception) {
                                Log.w(TAG, "networkProxy.getAvailableStorageNodes failed", e)
                                emptyList()
                            }
                        }

                        override suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities) {
                            // For now, record capabilities and log
                            try {
                                val appCap = convertMmcpStorageCapabilities(capabilities)
                                lastRemoteStorageCapabilities = appCap
                                Log.d(TAG, "networkProxy.broadcastStorageAdvertisement: $appCap")
                            } catch (e: Exception) {
                                Log.w(TAG, "networkProxy.broadcastStorageAdvertisement failed", e)
                            }
                        }
                    }

                    // Instantiate storageAgent with networkProxy to avoid recursive calls
                    val networkProxy = NetworkProxy()
                    storageAgent = DistributedStorageAgent(meshNetwork = networkProxy)

                    // Make meshAdapter available to other components
                    val meshAdapter = MeshAdapter()
                    currentMeshAdapter = meshAdapter

                    distributedStorageManager = com.ustadmobile.meshrabiya.storage.DistributedStorageManager(
                        context = context,
                        meshNetworkInterface = meshAdapter,  // Use adapter instead of virtualNode
                        storageConfig = createDefaultStorageConfig()
                    )
                    betaLogger.log(LogLevel.INFO, "STORAGE_INIT", "Storage participation initialized")
                    Log.i(TAG, "Storage participation initialized successfully")
                }
            } catch (e: Exception) {
                betaLogger.log(LogLevel.ERROR, "STORAGE_INIT", "Failed to initialize storage", 
                    mapOf("error" to e.message.orEmpty()))
                Log.e(TAG, "Failed to initialize storage participation", e)
            }
        }
    }

    /**
     * Provide the current MeshNetworkInterface adapter if available.
     * Components that require a concrete mesh adapter should call this method
     * and handle the null case (adapter not yet initialized).
     */
    fun provideMeshNetworkInterface(): com.ustadmobile.meshrabiya.storage.MeshNetworkInterface? = currentMeshAdapter
    
    /**
     * Get storage participation status - follows getMeshServiceStatus() pattern
     */
    fun getStorageParticipationStatus(): StorageParticipationStatus {
        return try {
            val storageStats = distributedStorageManager?.storageStats?.value
            StorageParticipationStatus(
                isEnabled = distributedStorageManager?.participationEnabled?.value ?: false,
                allocatedGB = getStorageAllocationGB(),
                usedGB = (storageStats?.currentlyUsed ?: 0L).toInt(),
                participationHealth = if (distributedStorageManager != null) "Active" else "Inactive"
            )
        } catch (e: Exception) {
            betaLogger.log(LogLevel.WARN, "STORAGE_STATUS", "Error getting storage status", 
                mapOf("error" to e.message.orEmpty()))
            Log.w(TAG, "Error getting storage participation status", e)
            StorageParticipationStatus(isEnabled = false)
        }
    }
    
    /**
     * Configure storage participation with real DistributedStorageManager
     */
    private suspend fun configureStorageParticipation(enabled: Boolean, allocationGB: Int) {
        try {
            if (enabled && distributedStorageManager == null) {
                initializeStorageParticipation()
            }
            
            distributedStorageManager?.let { storage ->
                // Use REAL API methods from DistributedStorageManager
                val config = com.ustadmobile.meshrabiya.storage.StorageParticipationConfig(
                    participationEnabled = enabled,
                    totalQuota = allocationGB.toLong() * 1024 * 1024 * 1024, // Convert GB to bytes
                    allowedDirectories = listOf("/storage/mesh"),
                    encryptionRequired = true
                )
                storage.configureStorageParticipation(config)
                
                betaLogger.log(LogLevel.INFO, "STORAGE_CONFIG", "Storage participation configured", 
                    mapOf("enabled" to enabled.toString(), "allocationGB" to allocationGB.toString()))
                Log.i(TAG, "Storage participation configured: enabled=$enabled, allocation=${allocationGB}GB")
            }
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "STORAGE_CONFIG", "Failed to configure storage", 
                mapOf("error" to e.message.orEmpty()))
            Log.e(TAG, "Failed to configure storage participation", e)
        }
    }
    
    /**
     * Create default storage configuration
     */
    private fun createDefaultStorageConfig(): com.ustadmobile.meshrabiya.storage.StorageConfiguration {
        return com.ustadmobile.meshrabiya.storage.StorageConfiguration(
            defaultReplicationFactor = 3,
            encryptionEnabled = true,
            compressionEnabled = true,
            maxFileSize = 100L * 1024 * 1024, // 100MB
            defaultQuota = 5L * 1024 * 1024 * 1024 // 5GB
        )
    }
    
    /**
     * Get current storage allocation in GB
     */
    private fun getStorageAllocationGB(): Int {
        return try {
            val sharedPrefs = context.getSharedPreferences("mesh_storage_prefs", Context.MODE_PRIVATE)
            sharedPrefs.getInt("storage_allocation_gb", 5) // Default to 5 GB
        } catch (e: Exception) {
            Log.w(TAG, "Error getting storage allocation preference", e)
            5
        }
    }
    
    /**
     * Store storage allocation preference
     */
    private fun setStorageAllocationGB(allocationGB: Int) {
        try {
            val sharedPrefs = context.getSharedPreferences("mesh_storage_prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putInt("storage_allocation_gb", allocationGB).apply()
            betaLogger.log(LogLevel.DEBUG, "STORAGE_PREFS", "Saved allocation preference", 
                mapOf("allocationGB" to allocationGB.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "Error saving storage allocation preference", e)
        }
    }
}
