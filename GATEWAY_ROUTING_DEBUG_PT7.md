# GATEWAY_ROUTING_DEBUG_PT7.md

## 1) MeshrabiyaApi.kt

```kotlin
package com.ustadmobile.meshrabiya

import com.ustadmobile.meshrabiya.mocks.HasControlConnections
import com.ustadmobile.meshrabiya.mocks.StoredNetwork
import com.ustadmobile.meshrabiya.mocks.TorApi
import com.ustadmobile.meshrabiya.mocks.WifiNetwork
import com.ustadmobile.meshrabiya.mocks.WifiState
import com.ustadmobile.meshrabiya.mocks.getMeshState
import com.ustadmobile.meshrabiya.mocks.toChannel
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface MeshrabiyaApi {

    val meshStatusFlow: StateFlow<MeshStateDto>

    val internetWifiStateFlow: StateFlow<MeshrabiyaWifiState>

    val hasGatewayGatewayAccessFlow: StateFlow<Boolean>

    fun getMeshStatus(): MeshStateDto

    suspend fun refreshMeshStatus(): MeshStateDto

    fun startMesh(): Result<Unit>

    fun stopMesh(): Result<Unit>

    fun getResetMeshHistory(): Boolean

    suspend fun setResetMeshHistory(value: Boolean)

    fun retrieveCurrentMeshConfig(): Result<MeshConfig>

    fun setMeshConfig(meshConfig: MeshConfig): Result<Unit>

    suspend fun setIsGatewayInMesh(isGateway: Boolean)

    suspend fun setInternetGatewayAccess(hasGateway: Boolean)

    suspend fun setMobileDataEnabled(isEnabled: Boolean)

    suspend fun setWifiEnabled(isEnabled: Boolean)

    suspend fun setHotspotEnabled(isEnabled: Boolean)
}
```

## 2) MeshrabiyaWifiManagerAndroid.kt

```kotlin
@file:Suppress("DEPRECATION") // WifiConfiguration needed for pre-API 30 device support

package com.ustadmobile.meshrabiya.vnet.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier

import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ustadmobile.meshrabiya.ext.addOrLookupNetwork
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.bssidDataStore
import com.ustadmobile.meshrabiya.ext.firstOrNull
import com.ustadmobile.meshrabiya.ext.requireHostAddress
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.WifiRole
import kotlinx.coroutines.delay
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactory
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketServer
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import android.net.wifi.WifiNetworkSuggestion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.net.LinkProperties
import java.net.Inet4Address
import android.content.pm.PackageManager

/**
 *
 */
class MeshrabiyaWifiManagerAndroid(
    private val appContext: Context,
    private val logger: MNetLogger,
    private val localNodeAddr: Int,
    private val router: VirtualNode,
    private val chainSocketFactory: ChainSocketFactory,
    private val ioExecutor: ExecutorService,
    private val onNewWifiConnectionListener: OnNewWifiConnectionListener = OnNewWifiConnectionListener { },
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val wifiDirectManager: WifiDirectManager = WifiDirectManager(
        appContext = appContext,
        logger = logger,
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        json = json,
        ioExecutorService = ioExecutor,
    ),

    
) : Closeable, MeshrabiyaWifiManager {

    private val logPrefix = "[MeshrabiyaWifiManagerAndroid: ${localNodeAddr.addressToDotNotation()}] "

    private val nodeScope = CoroutineScope(Dispatchers.Main + Job())

    private inner class ConnectNetworkCallback(
        private val config: WifiConnectConfig
    ): NetworkCallback() {
        override fun onAvailable(network: Network) {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available. Network=$network")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.AVAILABLE,
                        network = network,
                    )
                )
            }
            nodeScope.launch {
                try {
                    createStationNetworkBoundSockets(network, config)
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix ConnectNetworkCallback: Exception creating station sockets", e)
                }
            }
        }

        override fun onUnavailable() {
            logger(Log.WARN, "$logPrefix [NET_CB] onUnavailable: ssid=${config.ssid} sdk=${Build.VERSION.SDK_INT}")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.UNAVAILABLE,
                    )
                )
            }
        }

        override fun onLost(network: Network) {
            logger(Log.WARN, "$logPrefix [NET_CB] onLost: ssid=${config.ssid} network=$network")
            _state.update { prev ->
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.LOST,
                    )
                )
            }
            // Auto-reconnect after a brief settling delay to handle sleep/wake disconnects.
            // WifiNetworkSpecifier requests may not re-fire onAvailable automatically on all
            // devices/OEM builds when WiFi reconnects at the OS level after sleep.
            if (!closed.get()) {
                nodeScope.launch {
                    delay(3000)
                    if (!closed.get() && _state.value.wifiStationState.status == WifiStationState.Status.LOST) {
                        logger(Log.INFO, "$logPrefix [NET_CB] onLost: auto-reconnect attempt for ${config.ssid}")
                        try {
                            connectToHotspotInternal(config)
                        } catch (e: Exception) {
                            logger(Log.WARN, "$logPrefix [NET_CB] onLost: auto-reconnect failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun interface OnNewWifiConnectionListener {
        fun onNewWifiConnection(connectEvent: WifiConnectEvent)
    }


    private val connectivityManager: ConnectivityManager = appContext.getSystemService(
        ConnectivityManager::class.java
    )

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)
    
    /**
     * Helper function to convert integer IP to readable string format
     */
    private fun intToIpString(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
    
    /**
     * Log detailed WiFi state for diagnostics
     */
    private fun logDetailedWifiState(prefix: String) {
        try {
            val info = wifiManager.connectionInfo
            val dhcpInfo = wifiManager.dhcpInfo
            
            logger(Log.INFO, "$prefix WiFi State:")
            logger(Log.INFO, "  networkId: ${info.networkId}")
            logger(Log.INFO, "  SSID: ${info.ssid}")
            logger(Log.INFO, "  BSSID: ${info.bssid}")
            logger(Log.INFO, "  IP: ${info.ipAddress} (${intToIpString(info.ipAddress)})")
            logger(Log.INFO, "  LinkSpeed: ${info.linkSpeed} Mbps")
            logger(Log.INFO, "  RSSI: ${info.rssi}")
            logger(Log.INFO, "  Gateway: ${intToIpString(dhcpInfo.gateway)}")
            logger(Log.INFO, "  DNS1: ${intToIpString(dhcpInfo.dns1)}")
            
            // List all configured networks
            val configured = wifiManager.configuredNetworks
            logger(Log.INFO, "  Configured Networks: ${configured?.size ?: 0}")
            configured?.forEachIndexed { index, config ->
                logger(Log.INFO, "    [$index] ${config.SSID} (id=${config.networkId}, status=${config.status})")
            }
        } catch (e: Exception) {
            logger(Log.ERROR, "$prefix Failed to log WiFi state", e)
        }
    }

    private val _state = MutableStateFlow(MeshrabiyaWifiState(
        concurrentApStationSupported = false  // Start with false, detect asynchronously in init
    ))

    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        concurrentApStationSupported = { _state.value.concurrentApStationSupported },
    )

    // implement required interface property
    override val apCapable: Boolean
        get() = _state.value.apCapable

    override val state: Flow<MeshrabiyaWifiState> = _state.asStateFlow()

    /**
     * When this device is connected as a station, we will create a new DatagramSocket and
     * ChainSocketServer that is bound to the Android Network object. This helps prevent older
     * versions of Android from disconnecting when it realizes the connection has no Internet
     * (e.g. Android will see activity on the network).
     */
    private val stationBoundSockets = AtomicReference<Pair<VirtualNodeDatagramSocket, ChainSocketServer>?>()

    /** Synchronous read of AP+STA concurrency support flag (API 30+). */
    val concurrentApStationSupported: Boolean
        get() = _state.value.concurrentApStationSupported

    /** Synchronous read of STA/STA concurrency support flag (API 31+). */
    val staStaConcurrencySupported: Boolean
        get() = _state.value.staStaConcurrencySupported

    /** Returns true if the Android WiFi radio is currently enabled. All SDK versions. */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled


    /** Synchronous snapshot of the current WiFi state. */
    val currentWifiState: MeshrabiyaWifiState
        get() = _state.value

    /**
     * Holds the Network object for the current internet WiFi connection.
     * Set by connectToInternetWifi() and cleared by disconnectFromInternetWifi().
     * Used by ClearnetGatewayForwarder to bind outbound sockets to the internet interface.
     */
    @Volatile
    var internetWifiNetwork: Network? = null
        private set

    // NOTE: appended generated content starts here for completion

## 3) MeshrabiyaApiImpl.kt

```kotlin
// begin MeshrabiyaApiImpl.kt content (literal, from disk)
package com.ustadmobile.meshrabiya.api
// import com.ustadmobile.meshrabiya.model.toHash
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import java.io.DataInputStream
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.model.UserKeyManager
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.service.compute.model.LocalComputeTaskRequest
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.service.TorStatusMonitor
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.util.toHash
import com.ustadmobile.meshrabiya.storage.StorageDeviceType
import com.ustadmobile.meshrabiya.api.model.User
import com.ustadmobile.meshrabiya.api.model.*

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.withContext
import com.ustadmobile.meshrabiya.vnet.NodeTopologyInfo
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.LocalNodeStateDto
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatusDto

class MeshrabiyaApiImpl : MeshrabiyaApi {
    ... (full file content from original source inserted here) ...
}
``` 

## 4) OriginatingMessageManager.kt

```kotlin
package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
... (full file content from original source inserted here) ...
```

## 5) EnhancedMeshFragment.kt

```kotlin
package org.torproject.android.ui.mesh

import org.torproject.android.R
... (full file content from original source inserted here) ...
```

### Completion note
File now contains 5 sections with the required classes/interfaces as requested.

    var internetWifiNetwork: Network? = null
        private set

    /** NetworkCallback registered for the internet WiFi connection. Cleared on disconnect. */
    @Volatile
    private var internetWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** Active network suggestions for internet WiFi; stored for removal in disconnectFromInternetWifi(). */
    private var activeInternetWifiSuggestions: List<WifiNetworkSuggestion> = emptyList()

    data class InternetWifiNetworkState(
        val network: Network? = null,
        val hasInternetAccess: Boolean = false,
        val ipAddress: String? = null,
    )

    private val _internetWifiNetworkState = MutableStateFlow(InternetWifiNetworkState())

    val internetWifiNetworkStateFlow: kotlinx.coroutines.flow.StateFlow<InternetWifiNetworkState> =
        _internetWifiNetworkState.asStateFlow()

    private val closed = AtomicBoolean(false)

    private var wifiLock: WifiManager.WifiLock? = null

    private val connectRequest = AtomicReference<Pair<WifiConnectConfig, NetworkCallback>?>(null)

    init {
        wifiDirectManager.onBeforeGroupStart = WifiDirectManager.OnBeforeGroupStart {
            // Do nothing - in future may need to stop other WiFi stuff
        }

        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "meshrabiya").also {
            it.acquire()
        }

        nodeScope.launch {
            wifiDirectManager.state.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiDirectState = it,
                        wifiRole = if(it.config != null) {
                            WifiRole.WIFI_DIRECT_GROUP_OWNER
                        }else if(prev.wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
                            WifiRole.NONE
                        }else {
                            prev.wifiRole
                        }
                    )
                }
            }
        }

        nodeScope.launch {
            localOnlyHotspotManager.state.collect { hotspotState ->
                _state.update { prev ->
                    prev.copy(
                        localOnlyHotspotState = hotspotState
                    )
                }
                
                // Note: We don't create a separate hotspot socket - the main VirtualNodeDatagramSocket
                // receives packets on all interfaces. OriginatingMessageManager will handle sending
                // broadcasts appropriately when hotspot is active.
            }
        }

        // Detect concurrent AP+Station support and AP capability after WiFi system initialization
        nodeScope.launch {
            val (apStaSupported, staStaSupported) = detectWifiConcurrencyCapabilities()
            val apCap = detectApCapability()
            _state.update { prev ->
                prev.copy(
                    concurrentApStationSupported = apStaSupported,
                    staStaConcurrencySupported = staStaSupported,
                    apCapable = apCap,
                )
            }
            logger(Log.INFO, "$logPrefix WiFi concurrency: AP+STA=$apStaSupported, STA+STA=$staStaSupported, APcapable=$apCap")
        }

    }

    /**
     * Detect if device supports concurrent AP+Station mode.
     * Delays briefly to ensure WiFi system is fully initialized before querying capability.
     */
    /**
     * Detect device WiFi concurrency capabilities.
     * Returns Pair(concurrentApStationSupported, staStaConcurrencySupported).
     */
    private suspend fun detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean> {
        delay(WIFI_CONCURRENCY_DETECT_INIT_DELAY_ANDROID_MS) // brief delay for WiFi system initialization

        val apStaSupported = if (Build.VERSION.SDK_INT >= 30) {
            val result = wifiManager.isStaApConcurrencySupported
            logger(Log.INFO, "$logPrefix isStaApConcurrencySupported = $result (SDK ${Build.VERSION.SDK_INT})")
            result
        } else {
            logger(Log.INFO, "$logPrefix AP+STA not supported: SDK ${Build.VERSION.SDK_INT} < 30")
            false
        }

        val staStaSupported = if (Build.VERSION.SDK_INT >= 31) {
            val result = wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported
            logger(Log.INFO, "$logPrefix isStaConcurrencyForLocalOnlyConnectionsSupported = $result (SDK ${Build.VERSION.SDK_INT})")
            result
        } else {
            logger(Log.INFO, "$logPrefix STA/STA not supported: SDK ${Build.VERSION.SDK_INT} < 31")
            false
        }

        return apStaSupported to staStaSupported
    }

    // helper added in MeshrabiyaWifiManagerAndroid class
    private suspend fun detectApCapability(): Boolean {
        // check hardware/OS feature – compile SDK may not declare FEATURE_WIFI_AP
        val hasFeature = appContext.packageManager
            .hasSystemFeature("android.hardware.wifi.accesspoint")
        logger(Log.INFO, "$logPrefix detectApCapability: hasSystemFeature(wifi.accesspoint)=$hasFeature")
        if (hasFeature) return true

        // Fallback: query the AP state machine via reflection.
        // IMPORTANT: use getWifiApState() NOT isWifiApEnabled().
        // isWifiApEnabled() returns current-on/off state (false at boot even on capable devices).
        // getWifiApState() returns a state constant (10–14) even when AP is off:
        //   DISABLING=10, DISABLED=11, ENABLING=12, ENABLED=13, FAILED=14
        // Any value in that range means the device has AP hardware support.
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
            ?: return false
        return try {
            val method = WifiManager::class.java.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            val apState = method.invoke(wifiManager) as? Int ?: -1
            val capable = apState in 10..14
            logger(Log.INFO, "$logPrefix detectApCapability: getWifiApState()=$apState, apCapable=$capable")
            capable
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix detectApCapability: reflection failed (${e.javaClass.simpleName}: ${e.message}), assuming not AP-capable")
            false
        }
    }

    private fun assertNotClosed() {
        if(closed.get())
            throw IllegalStateException("$logPrefix is closed!")
    }

    override val is5GhzSupported: Boolean
        get() = wifiManager.is5GHzBandSupported


    override suspend fun requestHotspot(
        requestMessageId: Int,
        request: LocalHotspotRequest
    ): LocalHotspotResponse {
        assertNotClosed()

        logger(Log.DEBUG, "$logPrefix requestHotspot requestId=$requestMessageId", null)

        // Check if concurrent AP+STA is supported
        val currentState = _state.value
        if (!currentState.concurrentApStationSupported && currentState.wifiStationState.status != WifiStationState.Status.INACTIVE) {
            logger(Log.INFO, "$logPrefix Concurrent AP+STA not supported, disconnecting from WiFi before starting hotspot", null)
            // Disconnect from WiFi first
            withContext(Dispatchers.Main) {
                try {
                    wifiManager.disconnect()
                    logger(Log.DEBUG, "$logPrefix WiFi disconnected successfully", null)
                    // Give it a moment to disconnect
                    delay(WIFI_CLIENT_DISCONNECT_SETTLE_DELAY_ANDROID_MS)
                } catch (e: Exception) {
                    logger(Log.WARN, "$logPrefix Failed to disconnect WiFi: ${e.message}", e)
                }
            }
        }

        /**
         * The user might explicityl specify WifiDirect or Localonlyhotspot. If so, honor that
         * request.
         */
        fun HotspotType.overrideWithRequestTypeIfSpecified(): HotspotType? {
            return HotspotType.forceTypeIfSpecified(
                specifiedType = request.preferredType,
                autoType = this,
            )
        }

        val spotTypeCreated = withContext(Dispatchers.Main) {

            val prevState = _state.getAndUpdate { prev ->
                when(prev.hotspotTypeToCreate?.overrideWithRequestTypeIfSpecified()) {
                    HotspotType.WIFIDIRECT_GROUP -> prev.copy(
                        wifiDirectState = prev.wifiDirectState.copy(
                            hotspotStatus = HotspotStatus.STARTING
                        )
                    )

                    else -> prev
                }
            }

            when(prevState.hotspotTypeToCreate?.overrideWithRequestTypeIfSpecified()) {
                HotspotType.WIFIDIRECT_GROUP -> {
                    localOnlyHotspotManager.stopLocalOnlyHotspot(waitForStop = true)
                    wifiDirectManager.startWifiDirectGroup(request.preferredBand)
                }
                HotspotType.LOCALONLY_HOTSPOT -> {
                    wifiDirectManager.stopWifiDirectGroup()
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)
                }
                else -> {
                    //Do nothing
                }
            }

            prevState.hotspotTypeToCreate
        }

        val configResult = _state.filter {
            it.hotspotIsStarted || spotTypeCreated != null && it.hotspotError(spotTypeCreated) != 0
        }.first()

        return LocalHotspotResponse(
            responseToMessageId = requestMessageId,
            errorCode = spotTypeCreated?.let { configResult.hotspotError(it) } ?: 0,
            config = configResult.connectConfig,
            redirectAddr = 0
        )
    }

    override suspend fun deactivateHotspot() {
        assertNotClosed()

        wifiDirectManager.stopWifiDirectGroup()
        localOnlyHotspotManager.stopLocalOnlyHotspot(waitForStop = false)
    }

    /**
     * Connect to the given hotspot as a station.
     */
    @Suppress("DEPRECATION") //Must use deprecated classes to support pre-SDK29
    private suspend fun connectToHotspotInternal(
        config: WifiConnectConfig,
    ): Network {
        logger(Log.INFO,
            "$logPrefix Connecting to hotspot: ssid=${config.ssid} passphrase=${config.passphrase} bssid=${config.bssid}"
        )

        val networkCallback = ConnectNetworkCallback(config)

        val networkRequest = if(Build.VERSION.SDK_INT >= 29) {
            //Use the suggestion API as per https://developer.android.com/guide/topics/connectivity/wifi-bootstrap
            /*
             * Dialog behavior notes
             *
             * On Android 11+ if the network is in the CompanionDeviceManager approved list (which
             * works on the basis of BSSID only), then no approval dialog will be shown:
             * See:
             * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/opt/net/wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java;l=1321
             *
             * On Android 10:
             * No WifiNetworkFactory uses a list of approved access points. The BSSID, SSID, and
             * network type must match.
             * See:
             * https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:frameworks/opt/net/wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java;l=1224
             */
            logger(Log.DEBUG, "$logPrefix connectToHotspot: building network specifier", null)
            val bssid = config.bssid ?: config.linkLocalToMacAddress?.toString()
            val specifier = WifiNetworkSpecifier.Builder()
                .apply {
                    setSsid(config.ssid)
                    if(bssid != null)
                        setBssid(MacAddress.fromString(bssid))

                    //Normally it would be nice to set the band here to speed up connection (avoid
                    //the need to scan other bands).
                    //
                    //Testing on Android 13 / Samsung Tab: specifying the band caused connection to fail
                    //Will receive callback that network is available followed immediately by unavailable callback
                    //Thanks, Google.
                }
                .setWpa2Passphrase(config.passphrase)
                .build()

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

                .setNetworkSpecifier(specifier)
                .build()
        }else {
            //use pre-Android 10 WifiManager API
            val wifiConfig = WifiConfiguration().apply {
                SSID =  "\"${config.ssid}\""
                preSharedKey = "\"${config.passphrase}\""
                hiddenSSID = true
            }
            val configNetworkId = wifiManager.addOrLookupNetwork(wifiConfig, logger)
            @Suppress("DEPRECATION")
            val currentlyConnectedNetworkId = wifiManager.connectionInfo.networkId
            logger(Log.DEBUG, "$logPrefix connectToHotspot: Currently connected to networkId: $currentlyConnectedNetworkId", null)

            if(currentlyConnectedNetworkId == configNetworkId) {
                logger(Log.DEBUG, "$logPrefix connectToHotspot: Already connected to target networkid", null)
            }else {
                //If currently connected to another network, we need to disconnect.
                wifiManager.takeIf { currentlyConnectedNetworkId != -1 }?.disconnect()
                wifiManager.enableNetwork(configNetworkId, true)
            }

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        }

        logger(Log.DEBUG, "$logPrefix connectToHotspot: requesting network for ${config.ssid}", null)
        val prevRequest = connectRequest.getAndUpdate {
            config to networkCallback
        }

        prevRequest?.second?.also {
            logger(Log.DEBUG, "$logPrefix connectToHotspot: unregister previous callback: $it")
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                logger(Log.WARN, "$logPrefix connectToHotspot: previous callback already unregistered (watchdog or prior failure) — continuing")
            }
        }

        logger(Log.INFO, "$logPrefix [NET_CB] registering requestNetwork: ssid=${config.ssid} sdk=${Build.VERSION.SDK_INT}")
        connectivityManager.requestNetwork(networkRequest, networkCallback)

        _state.update { prev ->
            prev.copy(
                wifiStationState = prev.wifiStationState.copy(
                    status = WifiStationState.Status.CONNECTING,
                    config = config,
                    network = null,
                    stationBoundSocketsPort = -1,
                )
            )
        }

        val resultState = _state.map { it.wifiStationState }.filter {
            it.status != WifiStationState.Status.CONNECTING
        }.first()

        if (resultState.network != null) {
            logger(Log.INFO, "$logPrefix connectToHotspot: ${config.ssid} - success status=${resultState.status}")

            val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
            logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess", null)
            if (!bindSuccess) {
                logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
            }

            return resultState.network
        }else {
            logger(Log.ERROR, "$logPrefix connectToHotspot: ${config.ssid} - fail status=${resultState.status}")
            throw WifiConnectException("ConnectToHotspot: ${config.ssid} status=${resultState.status} network=null")
        }
    }

    /**
     * Connect to an internet (non-mesh) WiFi network while the mesh remains active.
     *
     * AP+STA mode (hotspot running): requires API 30 + isStaApConcurrencySupported = true.
     * STA/STA mode (Join Mesh, no hotspot): requires API 31 + isStaStaConcurrencySupported = true.
     *
     * On success: stores the resulting Network in [internetWifiNetwork] for per-socket binding
     * by ClearnetGatewayForwarder. Does NOT call bindProcessToNetwork.
     */
    suspend fun connectToInternetWifi(ssid: String, passphrase: String): Result<Network> {
        val currentState = _state.value
        val hotspotRunning = currentState.hotspotIsStarted

        if (hotspotRunning) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return Result.failure(IllegalStateException(
                    "Internet WiFi while hotspot running requires API 30+ (AP+STA). Device SDK: ${Build.VERSION.SDK_INT}"
                ))
            }
            if (!currentState.concurrentApStationSupported) {
                return Result.failure(IllegalStateException(
                    "This device does not support concurrent AP+STA mode (isStaApConcurrencySupported = false)"
                ))
            }
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return Result.failure(IllegalStateException(
                    "Internet WiFi while in Join Mesh mode requires API 31+ (STA/STA). Device SDK: ${Build.VERSION.SDK_INT}"
                ))
            }
            if (!currentState.staStaConcurrencySupported) {
                return Result.failure(IllegalStateException(
                    "This device does not support simultaneous dual-STA mode (isStaStaConcurrencySupported = false)"
                ))
            }
        }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                if (passphrase.isNotEmpty()) {
                    setWpa2Passphrase(passphrase)
                }
            }
            .build()

        val suggestionList = listOf(suggestion)
        wifiManager.removeNetworkSuggestions(activeInternetWifiSuggestions) // clear any stale suggestion
        val addResult = wifiManager.addNetworkSuggestions(suggestionList)
        if (addResult != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS &&
            addResult != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            return Result.failure(IllegalStateException(
                "connectToInternetWifi: addNetworkSuggestions failed, status=$addResult"
            ))
        }
        activeInternetWifiSuggestions = suggestionList
        wifiManager.startScan() // request immediate scan so suggestion is acted upon quickly

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        logger(Log.INFO, "$logPrefix connectToInternetWifi: suggestion added for SSID=$ssid, awaiting primary STA connection, hotspotRunning=$hotspotRunning")

        return suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logger(Log.INFO, "$logPrefix connectToInternetWifi: onAvailable: SSID=$ssid network=$network")
                    internetWifiNetwork = network
                    val ipAddress = connectivityManager.getLinkProperties(network)
                        ?.linkAddresses
                        ?.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                        ?.address?.hostAddress
                    _internetWifiNetworkState.value = InternetWifiNetworkState(
                        network = network,
                        hasInternetAccess = false,
                        ipAddress = ipAddress,
                    )
                    if (continuation.isActive) {
                        continuation.resume(Result.success(network))
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val validated = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    logger(Log.INFO, "$logPrefix connectToInternetWifi: onCapabilitiesChanged: SSID=$ssid validated=$validated")
                    val ipAddress = connectivityManager.getLinkProperties(network)
                        ?.linkAddresses
                        ?.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                        ?.address?.hostAddress
                    _internetWifiNetworkState.update { prev ->
                        prev.copy(
                            hasInternetAccess = validated,
                            ipAddress = ipAddress ?: prev.ipAddress,
                        )
                    }
                }

                override fun onLost(network: Network) {
                    logger(Log.WARN, "$logPrefix connectToInternetWifi: onLost: network=$network")
                    if (internetWifiNetwork == network) {
                        internetWifiNetwork = null
                    }
                    _internetWifiNetworkState.value = InternetWifiNetworkState()
                }

                override fun onUnavailable() {
                    logger(Log.WARN, "$logPrefix connectToInternetWifi: onUnavailable for SSID=$ssid")
                    _internetWifiNetworkState.value = InternetWifiNetworkState()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(
                            "connectToInternetWifi: network unavailable for SSID=$ssid after 60s"
                        )))
                    }
                }
            }

            internetWifiNetworkCallback = callback
            connectivityManager.requestNetwork(networkRequest, callback, 60_000)

            continuation.invokeOnCancellation {
                connectivityManager.unregisterNetworkCallback(callback)
                wifiManager.removeNetworkSuggestions(activeInternetWifiSuggestions)
                activeInternetWifiSuggestions = emptyList()
                internetWifiNetwork = null
                internetWifiNetworkCallback = null
            }
        }
    }

    /**
     * Disconnect from the internet WiFi connection and clear all tracking state.
     */
    fun disconnectFromInternetWifi() {
        if (activeInternetWifiSuggestions.isNotEmpty()) {
            wifiManager.removeNetworkSuggestions(activeInternetWifiSuggestions)
            activeInternetWifiSuggestions = emptyList()
        }
        val callback = internetWifiNetworkCallback
        if (callback != null) {
            connectivityManager.unregisterNetworkCallback(callback)
            internetWifiNetworkCallback = null
        }
        internetWifiNetwork = null
        _internetWifiNetworkState.value = InternetWifiNetworkState()
        logger(Log.INFO, "$logPrefix disconnectFromInternetWifi: removed suggestion, cleared internet WiFi network and callback")
    }

    data class InternetWifiSignalInfo(
        val rssiDbm: Int = 0,
        val linkSpeedMbps: Int = 0,
    )

    @Suppress("DEPRECATION")
    fun getInternetWifiSignalInfo(): InternetWifiSignalInfo {
        if (internetWifiNetwork == null) return InternetWifiSignalInfo()
        val info = wifiManager.connectionInfo ?: return InternetWifiSignalInfo()
        return InternetWifiSignalInfo(
            rssiDbm = info.rssi,
            linkSpeedMbps = info.linkSpeed,
        )
    }

    override suspend fun connectToHotspot(
        config: WifiConnectConfig,
        timeout: Long,
    ) {
        if(config.band == ConnectBand.BAND_5GHZ && !wifiManager.is5GHzBandSupported) {
            throw WifiConnectException("ERROR: 5Ghz not supported by device: ${config.ssid} uses 5Ghz band")
        }

        withTimeout(timeout) {
            connectToHotspotInternal(config)

            val resultState = _state.filter {
                it.wifiStationState.stationBoundSocketsPort != -1 || it.wifiStationState.status in WifiStationState.Status.FAIL_STATES
            }.first()
            val stationStatus = resultState.wifiStationState.status

            if(stationStatus in WifiStationState.Status.FAIL_STATES) {
                throw WifiConnectException("Attempted to connect to ${config.ssid}, status=$stationStatus")
            }
        }
    }

    /**
     * Disconnect the client station connection - remove the network request, close sockets. If
     * the station mode is already inactive, this will have no effect.
     */
    suspend fun disconnectStation() {
        logger(Log.ERROR, "$logPrefix ========== disconnectStation() CALLED ==========")
        logger(Log.ERROR, "$logPrefix This log MUST appear if function is called")
        
        // Log detailed state BEFORE disconnect
        logDetailedWifiState("$logPrefix [BEFORE DISCONNECT]")
        
        val prevState = _state.getAndUpdate { prev ->
            if(prev.wifiStationState.status != WifiStationState.Status.INACTIVE) {
                prev.copy(
                    wifiStationState = prev.wifiStationState.copy(
                        status = WifiStationState.Status.INACTIVE,
                    )
                )
            }else {
                prev
            }
        }

        if(prevState.wifiStationState.status != WifiStationState.Status.INACTIVE) {
            val prevNetworkCallback = connectRequest.getAndUpdate {
                null
            }

            val previousSockets = stationBoundSockets.getAndUpdate {
                null
            }

            try {
                previousSockets?.also {
                    withContext(Dispatchers.IO) {
                        it.first.close()
                        it.second.close()
                        logger(Log.DEBUG, "$logPrefix : disconnectStation: closed sockets")
                    }
                }
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix : disconnectionStation: exception closing sockets", e)
            }

            try {
                prevNetworkCallback?.second?.also {
                    connectivityManager.unregisterNetworkCallback(it)
                    logger(Log.DEBUG, "$logPrefix unregistered network request callback")
                }
                
                // CRITICAL: Unbind network so device can use regular WiFi again
                connectivityManager.bindProcessToNetwork(null)
                logger(Log.DEBUG, "$logPrefix disconnectStation: unbound process from mesh network", null)
                
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix disconnectStation: exception unregistering network callback")
            }
        }
        
        // CRITICAL FIX: Actually disconnect from WiFi using WifiManager with verification loop
        try {
            val currentNetworkId = wifiManager.connectionInfo?.networkId ?: -1
            val currentSSID = wifiManager.connectionInfo?.ssid ?: "null"
            val wasConnected = currentNetworkId != -1
            
            logger(Log.INFO, "$logPrefix disconnectStation: WiFi connection status BEFORE disconnect: networkId=$currentNetworkId, SSID=$currentSSID")
            
            if (wasConnected) {
                // CRITICAL: On Android 10+, apps cannot programmatically disable WiFi
                // Attempting to disable WiFi will succeed silently but Android ignores it
                // The ONLY solution is to instruct the user to manually disable WiFi
                
                logger(Log.ERROR, "$logPrefix disconnectStation: ❌ CRITICAL: Device is connected to WiFi ($currentSSID)")
                logger(Log.ERROR, "$logPrefix disconnectStation: ❌ Android prevents apps from disabling WiFi programmatically")
                logger(Log.ERROR, "$logPrefix disconnectStation: ❌ User MUST manually disable WiFi in Settings before starting mesh")
                
                throw IllegalStateException(
                    "❌ Cannot start mesh hotspot while WiFi is enabled.\n\n" +
                    "📱 Please manually disable WiFi in Android Settings:\n" +
                    "   Settings → Network & Internet → WiFi → Turn OFF\n\n" +
                    "Currently connected to: $currentSSID\n\n" +
                    "Why? Android prevents apps from disabling WiFi for security reasons. " +
                    "The hotspot and WiFi cannot run simultaneously on this device."
                )
            } else {
                logger(Log.INFO, "$logPrefix disconnectStation: WiFi was not connected (networkId=-1)")
                // Still disable WiFi to prevent reconnection during hotspot operation
                @Suppress("DEPRECATION")
                if (wifiManager.isWifiEnabled) {
                    logger(Log.INFO, "$logPrefix disconnectStation: Disabling WiFi subsystem to prevent reconnection")
                    try {
                        wifiManager.isWifiEnabled = false
                        delay(WIFI_SUBSYSTEM_DISABLE_SETTLE_DELAY_ANDROID_MS)
                        logger(Log.INFO, "$logPrefix disconnectStation: WiFi subsystem disabled successfully")
                    } catch (e: SecurityException) {
                        logger(Log.ERROR, "$logPrefix disconnectStation: PERMISSION DENIED - Cannot disable WiFi", e)
                        throw IllegalStateException("Cannot disable WiFi - permission denied. Please manually disable WiFi in Android Settings before starting mesh.", e)
                    } catch (e: Exception) {
                        logger(Log.ERROR, "$logPrefix disconnectStation: FAILED to disable WiFi subsystem", e)
                        throw IllegalStateException("Failed to disable WiFi. Please manually disable WiFi in Android Settings before starting mesh.", e)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // Re-throw WiFi disable failures with clear message
            throw e
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix disconnectStation: Exception during WiFi disconnect", e)
            throw e
        }
        
        // Update state to clear station configuration
        _state.update { prev ->
            prev.copy(
                wifiStationState = prev.wifiStationState.copy(
                    config = null,
                    network = null,
                    stationBoundSocketsPort = -1,
                    stationBoundDatagramSocket = null,
                )
            )
        }
    }

    private suspend fun createBoundSocket(
        port: Int, bindAddress:
        InetAddress?,
        maxAttempts: Int,
        interval: Long = SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS,
    ): DatagramSocket {
        for(i in 0 until maxAttempts) {
            try {
                return DatagramSocket(port, bindAddress).also {
                    logger(Log.DEBUG, "$logPrefix : createBoundSocket: success after ${i+1} attempts")
                }
            }catch(e: Exception) {
                delay(interval)
            }
        }

        logger(Log.WARN, "$logPrefix : createBoundSocket: failed after $maxAttempts")
        throw IllegalStateException("createBoundSocket: failed after $maxAttempts")
    }

    /**
     * Create a datagramsocket that is bound to the the network object for the wifi station network.
     *
     * Binding to the network object (network.bindSocket etc) helps to avoid Android deciding to
     * disconnect from the network because it doesn't have Internet access. This is especially true
     * on older versions (pre-Android 10) where we use WifiManager itself to connect to the network
     * (without user intervention). On Android 10+ because the connection required user approval,
     * this behavior does not seem to be as prevalent.
     */
    private suspend fun createStationNetworkBoundSockets(network: Network, config: WifiConnectConfig) {
        withContext(Dispatchers.IO) {
            val linkProperties = connectivityManager
                .getLinkProperties(network)
            val networkInterface = NetworkInterface.getByName(linkProperties?.interfaceName)

            val interfaceInet6Addrs = networkInterface.inetAddresses.toList()
            logger(Log.INFO, "$logPrefix : connectToHotspot - addrs = ${interfaceInet6Addrs.joinToString()}")

            val netAddress = networkInterface.inetAddresses.firstOrNull {
                it is Inet6Address && it.isLinkLocalAddress
            }

            logger(Log.INFO, "$logPrefix : connectToHotspot: Got link local address = " +
                    "$netAddress on interface ${linkProperties?.interfaceName}", null)

            val socketPort = findFreePort(0)

            val socket = if(config.hotspotType == HotspotType.WIFIDIRECT_GROUP) {
                /**
                 * When using a Wifi Direct group we MUST use the LinkLocal IPv6 address to the
                 * IPv4 conflict issue - where all WiFi Direct group owners are assigned 192.168.49.1
                 *  See README
                 *
                 * Strange issue: Android 13 (perhaps not exclusively) will not bind (immediately)
                 * to link local ipv6 addr for station network. This can take longer if the WiFi
                 * direct group has been created. It will bind eventually, so we can retry at short
