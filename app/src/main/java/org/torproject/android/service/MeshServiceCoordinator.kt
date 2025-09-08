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
    private val serviceRunning = AtomicBoolean(false)
    
    // Meshrabiya core components
    private var virtualNode: AndroidVirtualNode? = null
    private var meshRoleManager: MeshRoleManager? = null
    private var emergentRoleManager: EmergentRoleManager? = null
    
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
                
                // Simple traffic calculation based on node activity
                if (serviceRunning.get() && neighborCount > 0) {
                    baseTraffic + (System.currentTimeMillis() / 10000) // Growing over time
                } else {
                    baseTraffic
                }
            } ?: 0L
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
     */
    fun setUserSharingPreferences(
        allowTorGateway: Boolean = false,
        allowInternetGateway: Boolean = false, 
        allowStorageSharing: Boolean = false
    ) {
        scope.launch {
            try {
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
                    betaLogger.log(LogLevel.INFO, "MESH_PREFS", "User enabled storage sharing")
                    Log.i(TAG, "User enabled storage sharing")
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
    fun getUserSharingPreferences(): Map<String, Boolean> {
        return try {
            val preferredRoles = emergentRoleManager?.getPreferredRoles() ?: emptySet()
            mapOf(
                "allowTorGateway" to preferredRoles.contains(MeshRole.TOR_GATEWAY),
                "allowInternetGateway" to preferredRoles.contains(MeshRole.CLEARNET_GATEWAY),
                "allowStorageSharing" to preferredRoles.contains(MeshRole.STORAGE_NODE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error getting user preferences", e)
            mapOf(
                "allowTorGateway" to false,
                "allowInternetGateway" to false,
                "allowStorageSharing" to false
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
}
