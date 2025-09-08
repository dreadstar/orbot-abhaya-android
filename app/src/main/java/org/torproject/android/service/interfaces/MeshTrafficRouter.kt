package org.torproject.android.service.interfaces

/**
 * Interface for mesh traffic routing operations.
 * Handles routing between mesh networks and different gateway modes.
 */
interface MeshTrafficRouter {
    
    /**
     * Gateway modes for mesh traffic routing.
     */
    enum class GatewayMode {
        NONE,           // No gateway functionality
        TOR_GATEWAY,    // Route mesh traffic through Tor
        INTERNET_GATEWAY // Route mesh traffic directly to internet
    }
    
    /**
     * Enable gateway routing with the specified mode.
     * @param mode the gateway mode to enable
     */
    fun enableGatewayRouting(mode: GatewayMode)
    
    /**
     * Check if the gateway is currently active.
     * @return true if gateway is active, false otherwise
     */
    fun isGatewayActive(): Boolean
    
    /**
     * Get the current gateway mode.
     * @return the current gateway mode
     */
    fun getCurrentGatewayMode(): GatewayMode
    
    /**
     * Route a packet through the mesh network.
     * @param packet the packet data to route
     * @return true if routing successful, false otherwise
     */
    fun routePacket(packet: String): Boolean
    
    /**
     * Clean up resources and stop routing operations.
     */
    fun cleanup()
    
    /**
     * Get routing statistics.
     * @return statistics about packet routing
     */
    fun getRoutingStats(): Map<String, Any>
}
