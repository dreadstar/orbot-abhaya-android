package org.torproject.android.service.routing

import android.content.Context

/**
 * Simple implementation of MeshTrafficRouter for initial integration
 */
class MeshTrafficRouterImpl(private val context: Context) : MeshTrafficRouter {
    
    private var currentMode = MeshTrafficRouter.GatewayMode.NONE
    private var isActive = false
    
    override fun enableGatewayRouting(mode: MeshTrafficRouter.GatewayMode) {
        currentMode = mode
        isActive = mode != MeshTrafficRouter.GatewayMode.NONE
    }
    
    override fun isGatewayActive(): Boolean = isActive
    
    override fun getCurrentGatewayMode(): MeshTrafficRouter.GatewayMode = currentMode
    
    override fun routePacket(packet: ByteArray): Boolean {
        // TODO: Implement actual packet routing
        return false
    }
    
    override fun cleanup() {
        isActive = false
        currentMode = MeshTrafficRouter.GatewayMode.NONE
    }
}
