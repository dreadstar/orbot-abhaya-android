package com.ustadmobile.orbotmeshrabiyaintegration.interfaces

/**
 * Interface for Tor service functionality
 * This allows the integration module to work with different implementations
 */
interface TorService {
    fun isTorReadyForMesh(): Boolean
    fun isTorRunning(): Boolean
    fun enableMeshGateway(enabled: Boolean)
}
