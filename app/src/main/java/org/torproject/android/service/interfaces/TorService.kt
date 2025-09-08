package org.torproject.android.service.interfaces

/**
 * Interface for Tor service operations within the mesh networking context.
 * Provides methods for checking Tor status and enabling mesh gateway functionality.
 */
interface TorService {
    
    /**
     * Check if the Tor service is currently running.
     * @return true if Tor is running, false otherwise
     */
    fun isTorRunning(): Boolean
    
    /**
     * Check if Tor is ready to handle mesh network traffic.
     * @return true if Tor is ready for mesh integration, false otherwise
     */
    fun isTorReadyForMesh(): Boolean
    
    /**
     * Enable or disable mesh gateway functionality in Tor.
     * @param enable true to enable mesh gateway, false to disable
     */
    fun enableMeshGateway(enable: Boolean)
    
    /**
     * Stop the Tor service completely.
     */
    fun stopTorService()
    
    /**
     * Get the current Tor service status.
     * @return status information about the Tor service
     */
    fun getTorStatus(): String
}
