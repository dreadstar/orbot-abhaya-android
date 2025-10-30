package org.torproject.android

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import org.torproject.android.service.OrbotMeshService
import org.torproject.android.service.OrbotConstants
import org.torproject.android.service.util.Prefs
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole

/**
 * Manages gateway capabilities for sharing Internet and Tor connections
 * through the mesh network. Handles state persistence, capability validation,
 * and listener notifications.
 */
class GatewayCapabilitiesManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "GatewayCapabilitiesManager"
        private const val PREFS_NAME = "gateway_capabilities"
        private const val KEY_SHARE_INTERNET = "share_internet"
        private const val KEY_SHARE_TOR = "share_tor"
        
        @Volatile
        private var INSTANCE: GatewayCapabilitiesManager? = null
        
        fun getInstance(context: Context): GatewayCapabilitiesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GatewayCapabilitiesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var emergentRoleManager: EmergentRoleManager? = null

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val listeners = CopyOnWriteArrayList<GatewayCapabilityListener>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var _shareInternet = prefs.getBoolean(KEY_SHARE_INTERNET, false)
    private var _shareTor = prefs.getBoolean(KEY_SHARE_TOR, false)
    
    var shareInternet: Boolean
        get() = _shareInternet
        set(value) {
            if (_shareInternet != value) {
                _shareInternet = value
                prefs.edit().putBoolean(KEY_SHARE_INTERNET, value).apply()
                notifyListeners()
                Log.d(TAG, "Share Internet capability changed to: $value")
            }
        }
    
    var shareTor: Boolean
        get() = _shareTor
        set(value) {
            if (_shareTor != value) {
                _shareTor = value
                prefs.edit().putBoolean(KEY_SHARE_TOR, value).apply()
                notifyListeners()
                Log.d(TAG, "Share Tor capability changed to: $value")
            }
        }
    
    /**
     * Data class representing current gateway capabilities and status
     */
    data class GatewayStatus(
        val shareInternet: Boolean,
        val shareTor: Boolean,
        val hasInternetConnection: Boolean,
        val isTorAvailable: Boolean,
        val canShareInternet: Boolean,
        val canShareTor: Boolean
    )
    
     /**
     * Initializes the EmergentRoleManager for the current node.
     * Call this after node creation or app startup.
     */
    fun initializeRoleManager(node: Any) {
        emergentRoleManager = EmergentRoleManager(node, context)
    }

    /**
     * Returns true if storage participation is enabled in user settings.
     */
    fun isStorageParticipationEnabled(): Boolean {
        return prefs.getBoolean("storage_participation", false)
    }

    /**
     * Enables or disables storage participation and updates EmergentRoleManager.
     * --- NEW CODE ---
     */
    fun setStorageParticipation(enabled: Boolean) {
        prefs.edit().putBoolean("storage_participation", enabled).apply()
        emergentRoleManager?.let { manager ->
            val preferredRoles = if (enabled) setOf(MeshRole.STORAGE_NODE) else emptySet()
            manager.setPreferredRoles(preferredRoles)
        }
    }

    /**
     * Returns the current preferred mesh roles.
     * --- NEW CODE ---
     */
    fun getPreferredRoles(): Set<MeshRole> {
        return emergentRoleManager?.getPreferredRoles() ?: emptySet()
    }

    /**
     * Returns the current EmergentRoleManager instance.
     * --- NEW CODE ---
     */
    fun getRoleManager(): EmergentRoleManager? = emergentRoleManager


    /**
     * Interface for listening to gateway capability changes
     */
    interface GatewayCapabilityListener {
        fun onCapabilityChanged(status: GatewayStatus)
    }
    
    fun addListener(listener: GatewayCapabilityListener) {
        listeners.add(listener)
        // Immediately notify the new listener of current status
        scope.launch {
            listener.onCapabilityChanged(getCurrentStatus())
        }
    }
    
    fun removeListener(listener: GatewayCapabilityListener) {
        listeners.remove(listener)
    }
    
    /**
     * Gets the current gateway status including capability validation
     */
    fun getCurrentStatus(): GatewayStatus {
        val hasInternet = hasInternetConnection()
        val torAvailable = isTorServiceAvailable()
        
        return GatewayStatus(
            shareInternet = _shareInternet,
            shareTor = _shareTor,
            hasInternetConnection = hasInternet,
            isTorAvailable = torAvailable,
            canShareInternet = hasInternet,
            canShareTor = torAvailable
        )
    }
    
    /**
     * Checks if device has active internet connection
     */
    private fun hasInternetConnection(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking internet connection: ${e.message}", e)
            false
        }
    }
    
    /**
     * Checks if Tor service is available and running
     */
    private fun isTorServiceAvailable(): Boolean {
        return try {
            // For now, assume Tor is available if OrbotMeshService is accessible
            // In a real implementation, this would check the actual service status
            // via a broadcast receiver or service binding
            true // Placeholder - will be enhanced when service integration is complete
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Tor service: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get the current mesh virtual node if available
     */
    fun getVirtualNode(): AndroidVirtualNode? {
        return try {
            val app = context.applicationContext as? OrbotApp
            app?.virtualNode
        } catch (e: Exception) {
            Log.w(TAG, "Error getting virtual node: ${e.message}", e)
            null
        }
    }
    
    /**
     * Validates and updates gateway capabilities based on current system state
     */
    fun validateCapabilities(): GatewayStatus {
        val status = getCurrentStatus()
        
        // Auto-disable capabilities if requirements aren't met
        if (_shareInternet && !status.canShareInternet) {
            shareInternet = false
            Log.i(TAG, "Auto-disabled Internet sharing - no connection available")
        }
        
        if (_shareTor && !status.canShareTor) {
            shareTor = false
            Log.i(TAG, "Auto-disabled Tor sharing - service not available")
        }
        
        return getCurrentStatus()
    }
    
    /**
     * Sets both capabilities at once with validation
     */
    fun setCapabilities(internet: Boolean, tor: Boolean): GatewayStatus {
        val status = getCurrentStatus()
        
        // Validate before setting
        if (internet && !status.canShareInternet) {
            Log.i(TAG, "Cannot enable Internet sharing - no connection available")
        } else {
            shareInternet = internet
        }
        
        if (tor && !status.canShareTor) {
            Log.i(TAG, "Cannot enable Tor sharing - service not available")
        } else {
            shareTor = tor
        }
        
        return getCurrentStatus()
    }
    
    /**
     * Gets a human-readable status description
     */
    fun getStatusDescription(): String {
        val status = getCurrentStatus()
        val capabilities = mutableListOf<String>()
        
        if (status.shareInternet) {
            capabilities.add("Internet Gateway")
        }
        if (status.shareTor) {
            capabilities.add("Tor Gateway")
        }
        
        return when {
            capabilities.isEmpty() -> "Standard Node"
            capabilities.size == 1 -> capabilities[0]
            else -> capabilities.joinToString(" + ")
        }
    }
    
    private fun notifyListeners() {
        scope.launch {
            val status = getCurrentStatus()
            listeners.forEach { listener ->
                try {
                    listener.onCapabilityChanged(status)
                } catch (e: Exception) {
                    Log.w(TAG, "Error notifying listener: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        listeners.clear()
    }
    
    // Legacy methods for backward compatibility
    @Deprecated("Use shareInternet property instead", ReplaceWith("shareInternet = true"))
    fun enableGateway(): Boolean {
        shareInternet = true
        return getCurrentStatus().canShareInternet
    }
    
    @Deprecated("Use shareInternet property instead", ReplaceWith("shareInternet = false"))
    fun disableGateway(): Boolean {
        shareInternet = false
        shareTor = false
        return true
    }
    
    @Deprecated("Use getCurrentStatus().shareInternet instead", ReplaceWith("getCurrentStatus().shareInternet"))
    fun isGatewayEnabled(): Boolean = _shareInternet || _shareTor
}
