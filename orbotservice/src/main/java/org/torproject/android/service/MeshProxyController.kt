package org.torproject.android.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Observes [MeshrabiyaApi] state flows and triggers Orbot's VPN to rebuild in "mesh proxy mode"
 * when the local device has no direct internet access but a CLEARNET_GATEWAY is reachable via mesh.
 *
 * Sends [OrbotConstants.LOCAL_ACTION_MESH_PROXY_CHANGED] via [LocalBroadcastManager] so that
 * [OrbotService] (which owns [OrbotVpnManager]) can rebuild the VPN without a direct dependency
 * on [MeshrabiyaApi].
 *
 * Lives in [OrbotMeshService].
 */
class MeshProxyController(
    private val context: Context,
    private val meshrabiyaApi: MeshrabiyaApi,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
                val packages = meshrabiyaApi.getMeshProxyApps()
                val active = gatewayAvailable && packages.isNotEmpty()
                Log.d(TAG, "Mesh proxy state: gatewayAvailable=$gatewayAvailable packages=${packages.size} active=$active")
                if (active) {
                    meshrabiyaApi.startMeshProxyServer()
                    val port = meshrabiyaApi.getMeshProxySocksPort()
                    broadcastMeshProxyChanged(active = true, socksPort = port, packages = packages)
                } else {
                    meshrabiyaApi.stopMeshProxyServer()
                    broadcastMeshProxyChanged(active = false, socksPort = 0, packages = emptySet())
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        meshrabiyaApi.stopMeshProxyServer()
        broadcastMeshProxyChanged(active = false, socksPort = 0, packages = emptySet())
    }

    private fun broadcastMeshProxyChanged(active: Boolean, socksPort: Int, packages: Set<String>) {
        val intent = Intent(OrbotConstants.LOCAL_ACTION_MESH_PROXY_CHANGED)
            .putExtra(OrbotConstants.EXTRA_MESH_PROXY_ACTIVE, active)
            .putExtra(OrbotConstants.EXTRA_MESH_PROXY_SOCKS_PORT, socksPort)
            .putStringArrayListExtra(
                OrbotConstants.EXTRA_MESH_PROXY_PACKAGES,
                ArrayList(packages)
            )
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "MeshProxyController"
    }
}
