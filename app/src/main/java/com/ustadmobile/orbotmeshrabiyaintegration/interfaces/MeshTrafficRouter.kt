package com.ustadmobile.orbotmeshrabiyaintegration.interfaces

/**
 * Interface for mesh traffic routing functionality
 */
interface MeshTrafficRouter {
    enum class GatewayMode {
        NONE,
        CLEARNET_GATEWAY,
        TOR_GATEWAY
    }
    fun enableGatewayRouting(mode: GatewayMode)
    fun isGatewayActive(): Boolean
    fun getCurrentGatewayMode(): GatewayMode
    fun routePacket(packet: ByteArray): Boolean
    fun cleanup()
}
