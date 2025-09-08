package org.torproject.android.service.routing

/**
 * Interface for routing mesh traffic through different gateway modes
 */
interface MeshTrafficRouter {
    
    enum class GatewayMode {
        NONE,
        TOR_GATEWAY,
        CLEARNET_GATEWAY
    }
    
    /**
     * Enable gateway routing with specified mode
     */
    fun enableGatewayRouting(mode: GatewayMode)
    
    /**
     * Check if gateway is currently active
     */
    fun isGatewayActive(): Boolean
    
    /**
     * Get current gateway mode
     */
    fun getCurrentGatewayMode(): GatewayMode
    
    /**
     * Route a packet through the configured gateway
     */
    fun routePacket(packet: ByteArray): Boolean
    
    /**
     * Clean up resources
     */
    fun cleanup()
}
