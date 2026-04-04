## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt
package com.ustadmobile.meshrabiya.vnet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.vnet.VirtualPacket



class AndroidVirtualNode(
    appContext: Context,
    port: Int = 0,
    json: Json = Json,
    logger: MNetLogger = MNetLoggerStdout(),
    dataStore: DataStore<Preferences>,
    address: InetAddress = randomApipaInetAddr(),
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
) : VirtualNode(
    port = port,
    logger = logger,
    address = address,
    json = json,
    config = config,
    appContext = appContext,
) {
    
    /**
     * Provides context for service initialization (EmergentRoleManager, IntelligentDistributedComputeService).
     */
    override fun getContext(): Context  {
        Log.d("AndroidVirtualNode", "getContext() called, returning: $appContext")
        return appContext
    }

    private val bluetoothManager: BluetoothManager by lazy {
        appContext.getSystemService(BluetoothManager::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private val newWifiConnectionListener = MeshrabiyaWifiManagerAndroid.OnNewWifiConnectionListener {
        addNewNeighborConnection(
            address = it.neighborInetAddress,
            port = it.neighborPort,
            neighborNodeVirtualAddr = it.neighborVirtualAddress,
            socket = it.socket,
        )
    }

    override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid = MeshrabiyaWifiManagerAndroid(
        appContext = appContext,
        logger = logger,
        localNodeAddr = addressAsInt,
        router = this,
        chainSocketFactory = chainSocketFactory,
        ioExecutor = connectionExecutor,
        dataStore = dataStore,
        json = json,
        onNewWifiConnectionListener = newWifiConnectionListener,
    )

    private val clearnetGatewayForwarder: ClearnetGatewayForwarder = ClearnetGatewayForwarder(
        logger = logger,
        logPrefix = "ClearnetGateway",
        onResponsePacket = { packet -> route(packet, null, null) },
    )

    private val torGatewayForwarder: TorGatewayForwarder = TorGatewayForwarder(
        logger = logger,
        logPrefix = "TorGateway",
        onResponsePacket = { packet -> route(packet, null, null) },
    )

    override fun onTorGatewayPacket(packet: VirtualPacket): Boolean {
        torGatewayForwarder.forward(packet)
        return true
    }

    init {
        // Start WiFi state monitoring after all properties initialized
        emergentRoleManager.startWifiStateMonitoring()
    }

    private val _bluetoothState = MutableStateFlow(MeshrabiyaBluetoothState())


    private fun updateBluetoothState() {
        try {
            val deviceName = bluetoothAdapter?.name
            _bluetoothState.takeIf { it.value.deviceName != deviceName }?.value =
                MeshrabiyaBluetoothState(deviceName = deviceName)
        } catch (e: SecurityException) {
            logger(Log.WARN, "Could not get device name", e)
        }
    }

    private val bluetoothStateBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        updateBluetoothState()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        _bluetoothState.value = MeshrabiyaBluetoothState(
                            deviceName = null
                        )
                    }
                }
            }
        }
    }

    private val receiverRegistered = AtomicBoolean(false)

    init {
        Log.d("AndroidVirtualNode", "Constructed with appContext: $appContext")
        if (appContext == null) {
            Log.e("AndroidVirtualNode", "appContext is NULL in constructor!")
        }
        appContext.registerReceiver(
            bluetoothStateBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        receiverRegistered.set(true)

        coroutineScope.launch {
            meshrabiyaWifiManager.state.combine(_bluetoothState) { wifiState, bluetoothState ->
                wifiState to bluetoothState
            }.collect {
                _state.update { prev ->
                    prev.copy(
                        wifiState = it.first,
                        bluetoothState = it.second,
                        connectUri = generateConnectLink(
                            hotspot = it.first.connectConfig,
                            bluetoothConfig = it.second,
                        ).uri
                    )
                }
            }
        }
    }

    override fun close() {
        super.close()

        if (receiverRegistered.getAndSet(false)) {
            appContext.unregisterReceiver(bluetoothStateBroadcastReceiver)
        }
        // scheduledExecutorService.shutdown()  TODO find out where this goes
    }

    suspend fun connectAsStation(
        config: WifiConnectConfig,
    ) {
        meshrabiyaWifiManager.connectToHotspot(config)
    }

    suspend fun disconnectWifiStation() {
        meshrabiyaWifiManager.disconnectStation()
    }

    override suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand,
        hotspotType: HotspotType,
        preferredPassphrase: String?,
    ): LocalHotspotResponse? {
        updateBluetoothState()
        
        if (enabled) {
            // On concurrent AP+STA capable devices (API 30+), do NOT disconnect the station.
            // The station WiFi is the internet connection that MESH_ROUTER is designed to keep.
            // On non-concurrent devices (or devices where this hasn't been detected yet),
            // the existing disconnect-before-hotspot behavior is preserved.
            if (!meshrabiyaWifiManager.currentWifiState.concurrentApStationSupported) {
                logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (non-concurrent device)", null)
                meshrabiyaWifiManager.disconnectStation()
                logger(Log.INFO, "setWifiHotspotEnabled: Waiting 2 seconds for WiFi disconnect to stabilize...", null)
                kotlinx.coroutines.delay(2000)
                logger(Log.INFO, "setWifiHotspotEnabled: Proceeding with hotspot creation", null)
            } else {
                logger(Log.INFO, "setWifiHotspotEnabled: AP+STA concurrent device — keeping internet WiFi, proceeding directly", null)
            }
        }
        
        return super.setWifiHotspotEnabled(enabled, preferredBand, hotspotType, preferredPassphrase)
    }

    suspend fun lookupStoredBssid(ssid: String): String? {
        return meshrabiyaWifiManager.lookupStoredBssid(ssid)
    }

    /**
     * Store the BSSID for the given SSID. This ensures that when we make subsequent connection
     * attempts we don't need to use the companiondevicemanager again. The BSSID must be provided
     * when reconnecting on Android 10+ if we want to avoid a confirmation dialog.
     */
    fun storeBssid(ssid: String, bssid: String?) {
        logger(Log.DEBUG, "AndroidVirtualNode: storeBssid: Store BSSID for $ssid : $bssid")
        if (bssid != null) {
            coroutineScope.launch {
                meshrabiyaWifiManager.storeBssidForAddress(ssid, bssid)
            }
        } else {
            logger(Log.WARN, "AndroidVirtualNode: storeBssid: BSSID for $ssid is NULL, can't save to avoid prompts on reconnect")
        }
    }

    override fun notifyHotspotInterference(reconnectionCount: Int) {
        super.notifyHotspotInterference(reconnectionCount)
        logger(Log.WARN, "[HOTSPOT ALERT] WiFi interference detected: $reconnectionCount reconnection attempts suppressed", null)
    }

    override fun notifyHotspotLost(reason: String) {
        super.notifyHotspotLost(reason)
        logger(Log.ERROR, "[HOTSPOT ALERT] Hotspot lost: $reason", null)
    }

    override fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean {
        val internetNetwork = meshrabiyaWifiManager.internetWifiNetwork
        return if (internetNetwork != null) {
            clearnetGatewayForwarder.forward(packet, internetNetwork)
            true
        } else {
            logger(Log.WARN, "$logPrefix CLEARNET gateway: no internet WiFi network bound, dropping packet", null)
            false
        }
    }

    
}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalOnlyHotspotManager.kt
package com.ustadmobile.meshrabiya.vnet.wifi
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.content.Context
import android.net.MacAddress
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ustadmobile.meshrabiya.ext.encodeAsHex
import com.ustadmobile.meshrabiya.ext.prettyPrint
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.wifi.UnhiddenSoftApConfigurationBuilder.Companion.RANDOMIZATION_NONE
import com.ustadmobile.meshrabiya.vnet.wifi.UnhiddenSoftApConfigurationBuilder.Companion.SECURITY_TYPE_WPA2_PSK
import com.ustadmobile.meshrabiya.vnet.wifi.state.LocalOnlyHotspotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration

class LocalOnlyHotspotManager(
    appContext: Context,
    private val logger: MNetLogger,
    name: String,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val dataStore: DataStore<Preferences>,
    // Returns true when the device supports concurrent AP+STA.
    // When true, active WiFi connections are intentional (internet WiFi in MESH_ROUTER mode)
    // and must not be suppressed by the hotspot monitor.
    // Read at runtime (not construction time) to reflect live detection state.
    private val concurrentApStationSupported: () -> Boolean = { false },
) {
    private val appContext = appContext
    private val logPrefix: String = "[LocalOnlyHotspotManager: $name]"

    private val _state = MutableStateFlow(LocalOnlyHotspotState())

    val state: Flow<LocalOnlyHotspotState> = _state.asStateFlow()

    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val macAddrPrefKey = stringPreferencesKey("localonly_macaddr")

    private val localOnlyHotspotCallback = object: WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStarted", null)
            localOnlyHotspotReservation = reservation
            val hotspotConfig = reservation?.toLocalHotspotConfig(
                nodeVirtualAddr = localNodeAddr,
                port = router.localDatagramPort,
                logger = logger,
            )
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onstarted: config=$hotspotConfig")

            _state.takeIf { reservation != null }?.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTED,
                    config = hotspotConfig,
                )
            }
            
            // Start monitoring hotspot state to detect if it stops
            startHotspotMonitoring()
        }

        override fun onStopped() {
            logger(Log.DEBUG, "$logPrefix localonlyhotspotcallback: onStopped", null)
            localOnlyHotspotReservation = null
            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STOPPED,
                    config = null,
                )
            }
        }

        override fun onFailed(reason: Int) {
            logger(Log.ERROR, "$logPrefix localOnlyhotspotcallback : onFailed: " +
                    LocalOnlyHotspotState.errorCodeToString(reason), null
            )

            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STOPPED,
                    error = reason,
                )
            }
        }
    }

    private val wifiManager: WifiManager = appContext.getSystemService(WifiManager::class.java)
    
    private var hotspotMonitoringJob: kotlinx.coroutines.Job? = null

    suspend fun startLocalOnlyHotspot(
        preferredBand: ConnectBand,
        passphrase: String? = null,
    ) {
        logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: band=$preferredBand passphrase=${if (passphrase != null) "***provided***" else "default(meshtest12)"}")
        if(Build.VERSION.SDK_INT >= 33) {
            val macAddr = dataStore.data.map {
                it[macAddrPrefKey]
            }.first()?.let { MacAddress.fromString(it) } ?: MacAddressUtils.createRandomUnicastAddress().also { newMac ->
                dataStore.edit {
                    it[macAddrPrefKey] = newMac.toString()
                }
            }

            val config = UnhiddenSoftApConfigurationBuilder()
                .setAutoshutdownEnabled(false)
                .apply {
                    if(preferredBand == ConnectBand.BAND_5GHZ) {
                        setBand(ScanResult.WIFI_BAND_5_GHZ)
                    }else if(preferredBand == ConnectBand.BAND_2GHZ) {
                        setBand(ScanResult.WIFI_BAND_24_GHZ)
                    }
                }
                .setSsid("meshr-${localNodeAddr.encodeAsHex()}")
                .setPassphrase(passphrase ?: "meshtest12", SECURITY_TYPE_WPA2_PSK)
                .setBssid(macAddr)
                .setMacRandomizationSetting(RANDOMIZATION_NONE)
                .build()

            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTING
                )
            }

            logger(Log.DEBUG, "$logPrefix startLocalOnlyHotsopt: config = ${config.prettyPrint()}")
            wifiManager.startLocalOnlyHotspotWithConfig(config, null, localOnlyHotspotCallback)
            logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: request submitted")
            _state.filter { it.status.isSettled() }.first()
        } else {
            _state.update { prev ->
                prev.copy(
                    status = HotspotStatus.STARTING
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
                    logger(Log.ERROR, "$logPrefix Missing CHANGE_WIFI_STATE permission for startLocalOnlyHotspot", null)
                } else if (Build.VERSION.SDK_INT >= 28 && passphrase != null) {
                    // Tier 2 (SDK 28–32): reflection to access the hidden @SystemApi overload
                    // WifiManager#startLocalOnlyHotspot(WifiConfiguration, Handler, Callback)
                    // This is the only path on SDK 28–32 to set SSID + passphrase so all
                    // mesh extender nodes share the same credentials for seamless roaming.
                    startLocalOnlyHotspotWithWifiConfig(passphrase)
                } else if (passphrase == null) {
                    // Tier 3a (passphrase not provided): OS assigns SSID/passphrase.
                    // The system-assigned credentials are captured from SoftApConfiguration
                    // in onStarted and used for the QR code. Extender roaming requires re-scan.
                    logger(Log.INFO, "$logPrefix SDK ${Build.VERSION.SDK_INT}: no passphrase provided — OS will assign SSID/passphrase")
                    wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
                } else {
                    // Tier 3b (SDK 26–27, passphrase provided but cannot be set):
                    // API < 28 has no way to set custom SSID/passphrase for LOHS.
                    logger(Log.WARN, "$logPrefix SDK ${Build.VERSION.SDK_INT} < 28: cannot set SSID/passphrase — extender roaming degraded")
                    wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
                }
            } else {
                logger(Log.ERROR, "$logPrefix startLocalOnlyHotspot requires API 26+", null)
            }
        }
    }

    /**
     * Tier 2 SSID/passphrase path for SDK 28–32.
     *
     * Calls the hidden @SystemApi method:
     *   WifiManager#startLocalOnlyHotspot(WifiConfiguration, Handler, LocalOnlyHotspotCallback)
     * via reflection. This method exists on API 28+ but was not made public until API 33.
     * It may be blocked by non-SDK interface restrictions on some SDK 29+ devices; if so,
     * we fall back to the OS-assigned LOHS.
     */
    @SuppressLint("PrivateApi")
    @androidx.annotation.RequiresApi(28)
    private fun startLocalOnlyHotspotWithWifiConfig(passphrase: String) {
        val ssid = "meshr-${localNodeAddr.encodeAsHex()}"
        try {
            @Suppress("DEPRECATION")
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$passphrase\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            }
            val method = WifiManager::class.java.getDeclaredMethod(
                "startLocalOnlyHotspot",
                WifiConfiguration::class.java,
                android.os.Handler::class.java,
                WifiManager.LocalOnlyHotspotCallback::class.java,
            )
            method.isAccessible = true
            method.invoke(wifiManager, wifiConfig, null, localOnlyHotspotCallback)
            logger(Log.INFO, "$logPrefix Tier-2 reflection LOHS invoked: SSID=$ssid")
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix Tier-2 reflection LOHS failed (${e.message}) — falling back to OS-assigned SSID")
            wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, null)
        }
    }

    private fun startHotspotMonitoring() {
        hotspotMonitoringJob?.cancel()
        hotspotMonitoringJob = CoroutineScope(Dispatchers.Default).launch {
            var checkCount = 0
            var wifiReconnectCount = 0
            
            while (isActive) {
                delay(2000) // Check every 2 seconds
                checkCount++
                
                val currentStatus = _state.value.status
                val wifiInfo = wifiManager.connectionInfo
                val isWifiConnected = wifiInfo?.networkId != -1
                val wifiSSID = wifiInfo?.ssid ?: "null"
                
                logger(Log.DEBUG, "$logPrefix [HOTSPOT MONITOR #$checkCount] Hotspot: $currentStatus | WiFi: $isWifiConnected | SSID: $wifiSSID")
                
                // PHASE 2: Continuous WiFi Suppression - actively prevent reconnection.
                // SKIP suppression when concurrent AP+STA is supported: the WiFi connection
                // is the intentional MESH_ROUTER internet link and must not be removed.
                if (currentStatus == HotspotStatus.STARTED && isWifiConnected && wifiSSID != "<unknown ssid>") {
                    if (concurrentApStationSupported()) {
                        // AP+STA mode: WiFi connection is the internet link. Log and do NOT suppress.
                        logger(Log.DEBUG, "$logPrefix [HOTSPOT MONITOR] AP+STA mode: WiFi ($wifiSSID) is internet link — suppression skipped")
                    } else {
                        wifiReconnectCount++
                        logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] CRITICAL: WiFi reconnected (#$wifiReconnectCount) to $wifiSSID! Forcing disconnect...")
                        
                        try {
                            // Use removeNetwork() to force disconnection
                            val reconnectedNetworkId = wifiInfo.networkId
                            wifiManager.disconnect()
                            wifiManager.removeNetwork(reconnectedNetworkId)
                            wifiManager.configuredNetworks?.forEach { config ->
                                wifiManager.disableNetwork(config.networkId)
                            }
                            logger(Log.INFO, "$logPrefix [HOTSPOT MONITOR] WiFi disconnected, removed network, and disabled all networks")
                            
                            // Alert every 3 reconnections
                            if (wifiReconnectCount % 3 == 0) {
                                logger(Log.WARN, "$logPrefix [HOTSPOT MONITOR] WiFi interference: $wifiReconnectCount reconnection attempts suppressed")
                                // Trigger UI notification
                                router.notifyHotspotInterference(wifiReconnectCount)
                            }
                        } catch (e: Exception) {
                            logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] Failed to disconnect WiFi", e)
                        }
                    }
                }
                
                // PHASE 2: Detect if hotspot was lost/stopped unexpectedly
                if (currentStatus == HotspotStatus.STARTED) {
                    // Check if hotspot is actually active by verifying the reservation is still valid
                    val reservation = localOnlyHotspotReservation
                    if (reservation == null) {
                        logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] CRITICAL: Hotspot reservation lost while status is STARTED!")
                        router.notifyHotspotLost("Hotspot reservation lost unexpectedly")
                        break
                    }
                }
                
                // Stop monitoring if hotspot stopped
                if (currentStatus == HotspotStatus.STOPPED) {
                    logger(Log.INFO, "$logPrefix [HOTSPOT MONITOR] Hotspot stopped, ending monitoring. WiFi reconnections suppressed: $wifiReconnectCount")
                    break
                }
            }
            
            logger(Log.INFO, "$logPrefix [HOTSPOT MONITOR] Monitoring ended")
        }
    }
    
    suspend fun stopLocalOnlyHotspot(
        waitForStop: Boolean = true,
    ) {
        logger(Log.DEBUG, "$logPrefix stopLocalOnlyHotspot")
        hotspotMonitoringJob?.cancel()
        hotspotMonitoringJob = null
        val prevState = _state.getAndUpdate { prev ->
            if(prev.status == HotspotStatus.STARTED) {
                prev.copy(status = HotspotStatus.STOPPING)
            }else {
                prev
            }
        })

        if(prevState.status == HotspotStatus.STARTED) {
            val reservationVal = localOnlyHotspotReservation
            if(reservationVal != null) {
                try {
                    logger(Log.DEBUG, "$logPrefix stopLocalOnlyHotspot - closing reservation")
                    reservationVal.close()
                    localOnlyHotspotReservation = null
                    _state.value = LocalOnlyHotspotState(
                        status = HotspotStatus.STOPPED,
                        config = null,
                        error = 0,
                    )
                }catch(e: Exception) {
                    logger(Log.ERROR, "$logPrefix : exception closing reservation", e)
                    _state.update { prev ->
                        prev.copy(
                            error = 1042,
                        )
                    }
                }

            }else {
                logger(Log.ERROR, "$logPrefix: stopLocalOnlyhotspot - status was started but reservation is null!")
            }
        }else {
            logger(Log.DEBUG, "$logPrefix: stopLocalOnlyhotspot: nothing to do - status is ${prevState.status}")
        }

        if(waitForStop) {
            logger(Log.DEBUG, "$logPrefix: stopLocalOnlyhotspot: waiting for stop to complete")
            _state.filter {
                it.status == HotspotStatus.STOPPED
            }.first()
        }
    }

}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt
package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectState
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.hardware.DeviceCapabilityManager
import com.ustadmobile.meshrabiya.vnet.hardware.AndroidDeviceCapabilityManager
import com.ustadmobile.meshrabiya.vnet.hardware.MLCapabilityDetector
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid
import androidx.datastore.preferences.core.stringSetPreferencesKey
// UPDATED: MeshRole moved from mmcp to vnet package (canonical location)
import com.ustadmobile.meshrabiya.vnet.MeshRole

// UPDATED: Device capability types extracted to vnet/hardware/DeviceMetrics.kt
// Original location (mmcp/EnhancedGossipMessage.kt) deprecated to .md
// import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement

import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Data class capturing comprehensive node capabilities for role assignment
 */
data class NodeCapabilitySnapshot(
    val nodeId: String,
    val resources: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float, // 0.0-1.0
    val stability: Float, // 0.0-1.0 based on uptime/connectivity history
    val timestamp: Long = System.currentTimeMillis(),
    /** True if device has non-mesh WiFi with validated internet access. */
    val hasNonMeshInternetAccess: Boolean = false,
) {
    fun hasStableConnection(): Boolean = networkQuality > 0.7f && stability > 0.6f
    
    val availableCPU: Float get() = resources.availableCPU
    val storageOffered: Long get() = resources.storageOffered
    val batteryLevel: Int get() = batteryInfo.level
    val isCharging: Boolean get() = batteryInfo.isCharging
}

/**
 * Device capabilities for role assignment calculation
 */
data class DeviceCapabilities(
    val storageAvailable: Long,
    val processingPower: Float, // 0.0-1.0
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float, // 0.0-1.0
    val stability: Float // 0.0-1.0
)

/**
 * Global mesh intelligence for informed role decisions
 */
data class MeshIntelligence(
    val totalNodes: Int,
    val activeGateways: Int,
    val activeStorageNodes: Int,
    val activeComputeNodes: Int,
    val networkLoad: Float, // 0.0-1.0
    val storageUtilization: Float, // 0.0-1.0
    val computeUtilization: Float, // 0.0-1.0
    val timestamp: Long = System.currentTimeMillis()
) {
    val needsMoreGateways: Boolean get() = activeGateways < (totalNodes * 0.2f) || networkLoad > 0.8f
    val needsMoreStorage: Boolean get() = activeStorageNodes < (totalNodes * 0.3f) || storageUtilization > 0.8f
    val needsMoreCompute: Boolean get() = activeComputeNodes < (totalNodes * 0.25f) || computeUtilization > 0.8f
}

/**
 * Represents a planned transition in roles
 */
data class RoleTransition(
    val toAdd: Set<MeshRole>,
    val toRemove: Set<MeshRole>
)

/**
 * Complete plan for role transitions with timing and fallbacks
 */
data class RoleTransitionPlan(
    val addRoles: Set<MeshRole>,
    val removeRoles: Set<MeshRole>,
    val transitionDeadline: Long,
    val fallbackNodes: Map<MeshRole, List<String>>
)

/**
 * Enhanced emergent role manager for intelligent, decentralized role assignment
 * 
 * Features:
 * - Hardware-aware role assignment (battery, thermal, CPU monitoring)
 * - Multi-role support (MESH_PARTICIPANT, TOR_GATEWAY, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, COORDINATOR)
 * - Power constraint management with graceful transitions
 * - User preference integration (Tor proxy, preferred roles)
 * - Real-time mesh intelligence updates
 * 
 * Replaces deprecated MeshRoleManager with superior architecture.
 */
class EmergentRoleManager(
    private val virtualNode: VirtualNode,
    private val context: Context,
    private val getTopologyMap: (() -> Map<Int, NodeTopologyInfo>)? = null,  // NEW: Callback - Updated to NodeTopologyInfo
    private val getCurrentNodeCapabilities: (() -> NodeCapabilitySnapshot)? = null,  // NEW: Callback
    private val meshTrafficRouter: Any? = null, // Accept any traffic router for integration
    private val distributedStorageManager: Any? = null, // Accept storage manager for integration
    private val deviceCapabilityManager: DeviceCapabilityManager? = null, // Hardware metrics collector
    private val meshInternetRelayServer: com.ustadmobile.meshrabiya.vnet.gateway.MeshInternetRelayServer? = null
) {
    companion object {
        private const val TAG = "EmergentRoleManager"
    }
    private val logger = try { BetaTestLogger.getInstance(context) } catch (e: Exception) { null }

    // Coroutine scope for WiFi state monitoring (lifecycle-managed)
    private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

     // CONCURRENCY STATE – tracked to trigger role recalculation when capability is discovered
    private val _concurrencySupported = MutableStateFlow(false)
    val concurrencySupported: StateFlow<Boolean> = _concurrencySupported.asStateFlow()

    // legacy accessor used throughout the class
    private val concurrentApStationSupported: Boolean
        get() = _concurrencySupported.value

    // Authoritative wifi state snapshot — updated by the hotspot monitor coroutine BEFORE
    // calling updateRoles(). Prevents race with AndroidVirtualNode's combine() coroutine,
    // which may not have flushed the new state into currentNodeState.wifiState yet.
    @Volatile
    private var _cachedWifiState: MeshrabiyaWifiState? = null

    init {
        Log.d("EmergentRoleManager", "Initialized with virtualNode: $virtualNode, context: $context")
        if (context == null) {
            Log.e("EmergentRoleManager", "context is NULL in constructor!")
        }

        
    }

    /**
     * Start WiFi state monitoring. Must be called after AndroidVirtualNode fully initializes.
     * This monitors hotspot state changes and triggers role recalculation when hotspot starts.
     */
    fun startWifiStateMonitoring() {
        Log.d(TAG, "[WIFI_STATE] ===== startWifiStateMonitoring() CALLED =====")
        
        // Monitor AP+station concurrency capability
        monitoringScope.launch {
            Log.d(TAG, "[CONCURRENCY] Concurrency monitoring coroutine STARTED")
            try {
                virtualNode.meshrabiyaWifiManager.state
                    .map { it.concurrentApStationSupported }
                    .distinctUntilChanged()
                    .collect { support ->
                        Log.d(TAG, "[CONCURRENCY] AP+Station support = $support")
                        _concurrencySupported.value = support
                        safeLog(LogLevel.INFO, "[CONCURRENCY] AP+Station support = $support")
                        if (support) {
                            Log.d(TAG, "[CONCURRENCY] capability arrived, recalculating roles")
                            updateRoles(userInitiated = false)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "[CONCURRENCY] Concurrency monitor FAILED", e)
            }
        }

        // Monitor station connection state
        monitoringScope.launch {
            Log.d(TAG, "[WIFI_STATE] Station monitoring coroutine STARTED")
            try {
                virtualNode.meshrabiyaWifiManager.state
                    .map { 
                        val status = it.wifiStationState.status
                        val isAvailable = status == WifiStationState.Status.AVAILABLE
                        Log.v(TAG, "[WIFI_STATE] Station status: $status, isAvailable: $isAvailable")
                        isAvailable
                    }
                    .distinctUntilChanged()
                    .collect { isConnected ->
                        Log.d(TAG, "[WIFI_STATE] Station connection CHANGED to: isConnected=$isConnected")
                        if (isConnected) {
                            Log.d(TAG, "[WIFI_STATE] Station connected (AVAILABLE), triggering role recalculation in 2s")
                            delay(2000) // Allow neighbors to be discovered
                            Log.d(TAG, "[WIFI_STATE] Calling updateRoles() after station connection")
                            updateRoles(userInitiated = false)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "[WIFI_STATE] Station monitor FAILED", e)
            }
        }

        // Monitor hotspot state – triggers MESH_HUB assignment when hotspot starts
        monitoringScope.launch {
            Log.d(TAG, "[HOTSPOT_STATE] Hotspot monitoring coroutine STARTED")
            try {
                virtualNode.meshrabiyaWifiManager.state
                    .distinctUntilChanged { a, b -> a.hotspotIsStarted == b.hotspotIsStarted }
                    .collect { wifiState ->
                        // Cache BEFORE calling updateRoles() so calculateTargetRoles()
                        // sees the state that triggered this event, not the stale
                        // currentNodeState.wifiState from AndroidVirtualNode's coroutine.
                        _cachedWifiState = wifiState
                        Log.d(TAG, "[HOTSPOT_STATE] hotspotIsStarted changed to: ${wifiState.hotspotIsStarted}")
                        if (wifiState.hotspotIsStarted) {
                            Log.d(TAG, "[HOTSPOT_STATE] Hotspot started, recalculating roles")
                            updateRoles(userInitiated = false)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "[HOTSPOT_STATE] Hotspot monitor FAILED", e)
            }
        }

        // Monitor internet WiFi access — triggers gateway role re-evaluation when non-mesh internet connects
        monitoringScope.launch {
            Log.d(TAG, "[INTERNET_WIFI] Internet WiFi monitor coroutine STARTED")
            try {
                (virtualNode.meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
                    ?.internetWifiNetworkStateFlow
                    ?.map { it.hasInternetAccess }
                    ?.distinctUntilChanged()
                    ?.collect { hasInternet ->
                        Log.d(TAG, "[INTERNET_WIFI] hasInternetAccess changed to: $hasInternet")
                        if (hasInternet) {
                            Log.d(TAG, "[INTERNET_WIFI] Internet access arrived, recalculating roles")
                            updateRoles(userInitiated = false)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "[INTERNET_WIFI] Internet WiFi monitor FAILED", e)
            }
        }

        Log.d(TAG, "[WIFI_STATE] All monitoring coroutines launched successfully")
    }

    /**
     * Stop WiFi state monitoring and cleanup coroutines.
     * Should be called when EmergentRoleManager is no longer needed.
     */
    fun stopWifiStateMonitoring() {
        Log.d(TAG, "[WIFI_STATE] Stopping WiFi state monitoring")
        monitoringScope.cancel()
    }
    
    // Initialize hardware capability manager if not provided
    private val hardwareManager: DeviceCapabilityManager by lazy {
        deviceCapabilityManager ?: AndroidDeviceCapabilityManager(context, logger ?: BetaTestLogger.getInstance(context))
    }
    
    private fun safeLog(level: LogLevel, message: String, throwable: Throwable? = null) {
        try {
            logger?.log(level, message, throwable)
        } catch (e: Exception) {
            // Ignore logging errors in test environment
        }
    }
    
    // Initialize with empty roles - MESH_PARTICIPANT is added when mesh connects via updateRoles()
    private val _currentMeshRoles = MutableStateFlow<Set<MeshRole>>(setOf(MeshRole.MESH_PARTICIPANT))
    val currentMeshRoles: StateFlow<Set<MeshRole>> = _currentMeshRoles.asStateFlow()
    
    private val _meshIntelligence = MutableStateFlow(
        MeshIntelligence(
            totalNodes = 1,
            activeGateways = 0,
            activeStorageNodes = 0,
            activeComputeNodes = 0,
            networkLoad = 0.0f,
            storageUtilization = 0.0f,
            computeUtilization = 0.0f
        )
    )
    val meshIntelligence: StateFlow<MeshIntelligence> = _meshIntelligence.asStateFlow()

    private val _isRoleTransitionInProgress = MutableStateFlow(false)
    val isRoleTransitionInProgress: StateFlow<Boolean> = _isRoleTransitionInProgress.asStateFlow()

    private val _preferredRoles = MutableStateFlow<Set<MeshRole>>(emptySet())

    /**
     * User preference for allowing Tor proxy gateway mode
     * When true, node will prefer TOR_GATEWAY role over CLEARNET_GATEWAY
     */
    private val _userAllowsTorProxy = MutableStateFlow(false)
    val userAllowsTorProxy: StateFlow<Boolean> = _userAllowsTorProxy.asStateFlow()

    fun setUserAllowsTorProxy(allowed: Boolean) {
        _userAllowsTorProxy.value = allowed
        safeLog(LogLevel.INFO, "User Tor proxy preference set to: $allowed")
    }

    /**
     * Legacy fitness score structure for fallback compatibility
     * Used when hardware capability manager is unavailable
     */
    private data class LegacyFitnessScore(
        val signalStrength: Int,
        val batteryLevel: Float,
        val clientCount: Int
    )

    /**
     * Main entry point: determine optimal roles based on capabilities and mesh needs
     */
    fun determineOptimalRoles(
        nodeCapabilities: NodeCapabilitySnapshot = getCurrentCapabilities(),
        meshIntelligence: MeshIntelligence = this.meshIntelligence.value,
        currentRoles: Set<MeshRole> = currentMeshRoles.value,
        userInitiated: Boolean = false
    ): RoleTransitionPlan {
        android.util.Log.i("EmergentRoleManager", "[DETERMINE_ROLES] Starting (userInitiated=$userInitiated)")
        android.util.Log.i("EmergentRoleManager", "[DETERMINE_ROLES] Current roles: $currentRoles")
        android.util.Log.i("EmergentRoleManager", "[DETERMINE_ROLES] Preferred roles: ${_preferredRoles.value}")
        
        val targetRoles = calculateTargetRoles(nodeCapabilities, meshIntelligence)
        android.util.Log.i("EmergentRoleManager", "[DETERMINE_ROLES] Target roles calculated: $targetRoles")
        
        val transitions = planGracefulTransitions(currentRoles, targetRoles, userInitiated)
        android.util.Log.i("EmergentRoleManager", "[DETERMINE_ROLES] Transitions planned: add=${transitions.toAdd}, remove=${transitions.toRemove}")
        
        return RoleTransitionPlan(
            addRoles = transitions.toAdd,
            removeRoles = transitions.toRemove,
            transitionDeadline = calculateTransitionTime(transitions),
            fallbackNodes = identifyFallbackNodes(meshIntelligence, transitions.toRemove)
        )
    }
    
    /**
     * Core algorithm: calculate target roles based on fitness and mesh needs
     */
    private fun calculateTargetRoles(
        node: NodeCapabilitySnapshot, 
        mesh: MeshIntelligence
    ): Set<MeshRole> {
        val roles = mutableSetOf<MeshRole>()
        val userPreferences = _preferredRoles.value
        
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] Starting calculation")
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] User preferences: $userPreferences")
        
        // Base participation - everyone gets this
        roles.add(MeshRole.MESH_PARTICIPANT)
        
        // Calculate normalized fitness score (0.0-1.0)
        val fitness = calculateNormalizedFitness(node)
        
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] Fitness: $fitness")
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] Mesh needs: gateways=${mesh.needsMoreGateways}, storage=${mesh.needsMoreStorage}")
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] Node stable: ${node.hasStableConnection()}")
        
        safeLog(LogLevel.INFO, "[ROLE_CALC] ===== Starting role calculation =====")
        safeLog(LogLevel.INFO, "[ROLE_CALC] Node fitness: $fitness")
        safeLog(LogLevel.INFO, "[ROLE_CALC] Mesh needs: gateways=${mesh.needsMoreGateways}, storage=${mesh.needsMoreStorage}, compute=${mesh.needsMoreCompute}")
        safeLog(LogLevel.INFO, "[ROLE_CALC] User preferences: $userPreferences")
        safeLog(LogLevel.INFO, "[ROLE_CALC] Node stable connection: ${node.hasStableConnection()}")
        
        // Gateway roles: respect user preferences as filters
        // Only assign gateway roles if user has enabled them AND device meets criteria
        if (node.hasStableConnection() && node.hasNonMeshInternetAccess && fitness > 0.6 && mesh.needsMoreGateways) {
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] Gateway criteria MET, checking user preferences...")
            safeLog(LogLevel.INFO, "[ROLE_CALC] Gateway criteria met, checking user preferences...")
            // Check each gateway type individually
            if (MeshRole.TOR_GATEWAY in userPreferences) {
                roles.add(MeshRole.TOR_GATEWAY)
                android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding TOR_GATEWAY")
                safeLog(LogLevel.INFO, "[ROLE_CALC] ✓ Assigned TOR_GATEWAY (user enabled + device meets criteria)")
            } else {
                android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✗ Skipping TOR_GATEWAY (not in preferences)")
                safeLog(LogLevel.INFO, "[ROLE_CALC] ✗ Skipping TOR_GATEWAY (not in user preferences)")
            }
            if (MeshRole.CLEARNET_GATEWAY in userPreferences) {
                roles.add(MeshRole.CLEARNET_GATEWAY)
                android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding CLEARNET_GATEWAY")
                safeLog(LogLevel.INFO, "[ROLE_CALC] ✓ Assigned CLEARNET_GATEWAY (user enabled + device meets criteria)")
            } else {
                android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✗ Skipping CLEARNET_GATEWAY (not in preferences)")
                safeLog(LogLevel.INFO, "[ROLE_CALC] ✗ Skipping CLEARNET_GATEWAY (not in user preferences)")
            }
            if (MeshRole.I2P_GATEWAY in userPreferences) {
                roles.add(MeshRole.I2P_GATEWAY)
                android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding I2P_GATEWAY")
                safeLog(LogLevel.INFO, "[ROLE_CALC] ✓ Assigned I2P_GATEWAY (user enabled + device meets criteria)")
            } else {
                android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✗ Skipping I2P_GATEWAY (not in preferences)")
                safeLog(LogLevel.INFO, "[ROLE_CALC] ✗ Skipping I2P_GATEWAY (not in user preferences)")
            }
        } else {
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] Gateway criteria NOT MET (stable=${node.hasStableConnection()}, fitness=$fitness, needsGateways=${mesh.needsMoreGateways})")
            safeLog(LogLevel.INFO, "[ROLE_CALC] Gateway criteria NOT met (stable=${node.hasStableConnection()}, fitness=$fitness, needsGateways=${mesh.needsMoreGateways})")
        }
        
        // Storage role: only if user enabled AND device meets criteria
        safeLog(LogLevel.INFO, "[ROLE_CALC] Evaluating STORAGE_NODE...")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - In preferences: ${MeshRole.STORAGE_NODE in userPreferences}")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Storage offered: ${node.storageOffered} bytes (need > 1MB)")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Fitness: $fitness (need > 0.4)")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Mesh needs storage: ${mesh.needsMoreStorage}")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Thermal state: ${node.thermalState} (must not be THROTTLING/CRITICAL)")
        
        if (MeshRole.STORAGE_NODE in userPreferences &&
            node.storageOffered > 1_000_000L && // At least 1MB offered
            fitness > 0.4 && 
            mesh.needsMoreStorage &&
            node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL)) {
            roles.add(MeshRole.STORAGE_NODE)
            safeLog(LogLevel.INFO, "[ROLE_CALC] ✓ Assigned STORAGE_NODE (user enabled + device meets criteria)")
        } else {
            safeLog(LogLevel.INFO, "[ROLE_CALC] ✗ Skipping STORAGE_NODE (user disabled OR device criteria not met)")
        }
        
        // Compute role: only if user enabled AND device meets criteria
        safeLog(LogLevel.INFO, "[ROLE_CALC] Evaluating COMPUTE_NODE...")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - In preferences: ${MeshRole.COMPUTE_NODE in userPreferences}")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Available CPU: ${node.availableCPU} (need > 0.3)")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Thermal state: ${node.thermalState} (must not be THROTTLING/CRITICAL)")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Charging: ${node.isCharging}, Battery: ${node.batteryLevel}% (need charging OR >30%)")
        safeLog(LogLevel.INFO, "[ROLE_CALC]   - Mesh needs compute: ${mesh.needsMoreCompute}")
        
        if (MeshRole.COMPUTE_NODE in userPreferences &&
            node.availableCPU > 0.3f && 
            node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL) && 
            (node.isCharging || node.batteryLevel > 30) &&
            mesh.needsMoreCompute) {
            roles.add(MeshRole.COMPUTE_NODE)
            safeLog(LogLevel.INFO, "[ROLE_CALC] ✓ Assigned COMPUTE_NODE (user enabled + device meets criteria)")
        } else {
            safeLog(LogLevel.INFO, "[ROLE_CALC] ✗ Skipping COMPUTE_NODE (user disabled OR device criteria not met)")
        }
        
        // Router roles based on connectivity, graph centrality, AND WiFi concurrency capability
        // Use BFS centrality to identify nodes in structurally important positions
        // Nodes with AP+Station concurrency can forward traffic while maintaining connections
        val centralityResult = calculateBFSCentrality()
        val centralityThreshold = 3.0f // Minimum centrality score for router role
        // Use _cachedWifiState (set by hotspot monitor before calling updateRoles) as the
        // authoritative source. Falls back to currentNodeState only if called before
        // the first hotspot event (e.g., initial MESH_PARTICIPANT assignment).
        val wifiState = _cachedWifiState ?: virtualNode.currentNodeState.wifiState

        // MESH_ROUTER: assign whenever AP+Station concurrency support is true
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] MESH_ROUTER check: concurrency=$concurrentApStationSupported")
        if (concurrentApStationSupported) {
            roles.add(MeshRole.MESH_ROUTER)
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding MESH_ROUTER (hardware concurrency detected)")
            safeLog(LogLevel.INFO, "[ROLE_CALC] Assigned MESH_ROUTER – concurrency support present")
        } else {
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✗ MESH_ROUTER NOT assigned (no concurrency)")
            safeLog(LogLevel.INFO, "[ROLE_CALC] No router – concurrency=false")
        }
        
        // MESH_HUB role: assigned to any node running a mesh hotspot, regardless of AP+STA concurrency.
        // Concurrent devices (MESH_ROUTER) also act as MESH_HUB since they run a hotspot.
        // Non-concurrent devices get MESH_HUB only (no MESH_ROUTER since they can't run station simultaneously).
        
        if (wifiState.hotspotIsStarted) {
            roles.add(MeshRole.MESH_HUB)
            safeLog(LogLevel.INFO, "Assigned MESH_HUB role (hotspot active, concurrency=$concurrentApStationSupported)")
        }

        return roles
    }

}

## FILE: /home/d8rkl3ft/workspace/orbot-abhaya-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt
package com.ustadmobile.meshrabiya.vnet.wifi.state

import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType


data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val wifiStationState: WifiStationState = WifiStationState(),
    val localOnlyHotspotState: LocalOnlyHotspotState = LocalOnlyHotspotState(),
    val errorCode: Int = 0,
    val concurrentApStationSupported: Boolean = false,
    // True if the device can hold two simultaneous WiFi station (STA) connections.
    // Detected via WifiManager.isStaStaConcurrencySupported() at API 31+.
    // When true, a device in pure station mode (Join Mesh) can simultaneously connect
    // to an internet WiFi network via WifiNetworkSuggestion without dropping the mesh.
    // When false (default), the STA/STA path in connectToInternetWifi() is unavailable.
    val staStaConcurrencySupported: Boolean = false,
    val apCapable: Boolean = false, // new capability flag
) {

    /**
     * The configuration that another device should use to connect to this device (if any)
     */
    val connectConfig: WifiConnectConfig?
        get() = wifiDirectState.config ?: localOnlyHotspotState.config

    val hotspotIsStarting: Boolean
        get() = wifiDirectState.hotspotStatus == HotspotStatus.STARTING
                || localOnlyHotspotState.status == HotspotStatus.STARTING

    val hotspotIsStarted: Boolean
        get() = wifiDirectState.hotspotStatus == HotspotStatus.STARTED
                || localOnlyHotspotState.status == HotspotStatus.STARTED

    fun hotspotError(hotspotType: HotspotType) : Int {
        return when(hotspotType) {
            HotspotType.LOCALONLY_HOTSPOT -> localOnlyHotspotState.error
            HotspotType.WIFIDIRECT_GROUP -> wifiDirectState.error
            HotspotType.AUTO -> 0
        }
    }


    /**
     * Determine the type of hotspot that should be created if a request is made to start one.
     * Currently only WifiDirect group is supported.
     */
    val hotspotTypeToCreate: HotspotType?
        get() {
            return if(connectConfig != null)
                //Hotspot already available- nothing to create
                null
            else if(
                //WifiDirect Group or Local Only hotspot already being created, do nothing
                hotspotIsStarting
            ) {
                null
            } else if(concurrentApStationSupported){
                HotspotType.LOCALONLY_HOTSPOT
            }else {
                HotspotType.WIFIDIRECT_GROUP
            }

        }

}
