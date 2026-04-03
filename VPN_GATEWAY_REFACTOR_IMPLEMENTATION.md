# VPN Gateway Refactor — Implementation Reference
**Created:** 2026-04-03
**Source plan:** `VPN_GATEWAY_REFACTOR_PLAN.md`
**Rule:** Changes listed **bottom-to-top** within each file (highest line number first) so edits do not shift line numbers for subsequent changes in the same file.

All BEFORE/AFTER snippets are verified against disk. Each shows ≥5 lines of verbatim surrounding context.

---

## Table of Contents

1. [`DtoModels.kt` — add `VpnStateDto`](#1-dtomodelskt--add-vpnstatedto)
2. [TorStatusMonitor.kt](#2-torstatusmonitorkts)
3. [DtoModels.kt](#3-dtomodelskt)
4. [MeshrabiyaApi.kt](#4-meshrabiyaapikt)
5. [MeshrabiyaApiImpl.kt](#5-meshrabiyaapiimplkt)
6. [EmergentRoleManager.kt](#6-emergentrolemanagerkt)
7. [AndroidVirtualNode.kt](#7-androidvirtualnodekt)
8. [ClearnetGatewayForwarder.kt](#8-clearnetgatewayforwarderkt)
9. [EnhancedMeshFragment.kt](#9-enhancedmeshfragmentkt)

---

## 1. `DtoModels.kt` — add `VpnStateDto`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/model/DtoModels.kt`

### Change 1-A — Append `VpnStateDto` at end of file (after line 797)

**BEFORE (lines 792–797 — last block in file):**
```kotlin
enum class MeshExtenderHotspotStateDto {
    INACTIVE,
    STARTING,
    ACTIVE,
    STOPPING
}
```

**AFTER:**
```kotlin
enum class MeshExtenderHotspotStateDto {
    INACTIVE,
    STARTING,
    ACTIVE,
    STOPPING
}

/**
 * Snapshot of the Orbot VPN state as seen by Meshrabiya.
 * Pushed by the app layer via MeshrabiyaApi.notifyVpnStateChanged().
 */
data class VpnStateDto(
    /** True when Orbot VPN tunnel is fully active ("ON"). */
    val active: Boolean,
    /**
     * True when the VPN tunnel's underlying transport is WiFi (not cellular).
     * Determined by NetworkCapabilities.hasTransport(TRANSPORT_VPN) &&
     * hasTransport(TRANSPORT_WIFI) on the active network.
     * When true the mesh cannot use the WiFi radio simultaneously on single-radio devices.
     */
    val vpnOverWifi: Boolean = false,
    /** SOCKS port Orbot is listening on, or null if not active. */
    val socksPort: Int? = null,
) {
    companion object {
        val INACTIVE = VpnStateDto(active = false)
    }
}
```

## 2. `TorStatusMonitor.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/TorStatusMonitor.kt`

### Change 2-A — Extend `onReceive()` to also push `VpnStateDto`  (lines 166–186)

**BEFORE (lines 160–192):**
```kotlin
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_TOR_STATUS) {
            Log.w(TAG, "Received non-status intent: ${intent?.action}")
            return
        }

        val status = intent.getStringExtra(EXTRA_TOR_STATUS)
        val isTorActive = (status == STATUS_ON)

        Log.i(TAG, "Tor status update: status='$status' → isTorActive=$isTorActive")

        // Update MeshrabiyaApiImpl status
        try {
            val api = MeshrabiyaApiImpl.getInstance()
            api.updateTorStatus(isTorActive)
            
            Log.d(TAG, "Updated MeshrabiyaApi.isTorActive = $isTorActive")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update MeshrabiyaApi Tor status", e)
        }
    }
```

**AFTER (lines 160–206 after change):**
```kotlin
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_TOR_STATUS) {
            Log.w(TAG, "Received non-status intent: ${intent?.action}")
            return
        }

        val status = intent.getStringExtra(EXTRA_TOR_STATUS)
        val isTorActive = (status == STATUS_ON)

        Log.i(TAG, "Tor status update: status='$status' → isTorActive=$isTorActive")

        // Update MeshrabiyaApiImpl status
        try {
            val api = MeshrabiyaApiImpl.getInstance()
            api.updateTorStatus(isTorActive)

            // Determine whether the VPN tunnel is riding over WiFi.
            val vpnOverWifi = if (isTorActive && context != null) {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true &&
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } else false

            val socksPort = if (isTorActive)
                intent.getIntExtra("org.torproject.android.intent.extra.SOCKS_PROXY_PORT", -1)
                    .takeIf { it > 0 }
            else null

            api.notifyVpnStateChanged(
                com.ustadmobile.meshrabiya.api.model.VpnStateDto(
                    active = isTorActive,
                    vpnOverWifi = vpnOverWifi,
                    socksPort = socksPort,
                )
            )

            Log.d(TAG, "Updated MeshrabiyaApi.isTorActive = $isTorActive vpnOverWifi=$vpnOverWifi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update MeshrabiyaApi Tor status", e)
        }
    }
```

**Import to add** at top of file (after `import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl`):
```kotlin
// (android.net.ConnectivityManager and android.net.NetworkCapabilities are accessed
//  via fully-qualified names inside the method body — no additional imports needed)
```

---

## 3. `DtoModels.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/model/DtoModels.kt`

Changes listed bottom-to-top.

---

### Change 3-A — Delete `NonMeshWifiStatusDto` enum (lines 774–784)

**BEFORE (lines 770–790):**
```kotlin
fun InternetWifiNetworkState.toDto(): NonMeshWifiConnectionStateDto =
    NonMeshWifiConnectionStateDto(
        status = if (network != null) NonMeshWifiStatusDto.CONNECTED else NonMeshWifiStatusDto.IDLE,
        connectedSsid = null,
        errorMessage = null,
        hasInternetAccess = hasInternetAccess,
        internetConnectionIpAddress = ipAddress
    )

/**
 * Status values for the non-mesh WiFi internet connection lifecycle.
 * Named with Dto suffix following [MeshStateDto] convention — observed-state enum in api.model.
 * Contrast with [GatewayPreference] (api package) which is a user policy enum.
 */
enum class NonMeshWifiStatusDto {
    /** No internet WiFi connection active or attempted. */
    IDLE,
    /** Connection attempt is in progress (WifiNetworkSuggestion submitted, awaiting onAvailable). */
    CONNECTING,
    /** Connected and internet WiFi Network object is available. */
    CONNECTED,
    /** Connection attempt failed (onUnavailable or addNetworkSuggestions returned error). */
    FAILED,
}
```

**AFTER:**
```kotlin
fun InternetWifiNetworkState.toDto(): NonMeshWifiConnectionStateDto =
    NonMeshWifiConnectionStateDto(
        status = if (network != null) NonMeshWifiStatusDto.CONNECTED else NonMeshWifiStatusDto.IDLE,
        connectedSsid = null,
        errorMessage = null,
        hasInternetAccess = hasInternetAccess,
        internetConnectionIpAddress = ipAddress
    )

// NonMeshWifiStatusDto intentionally deleted — no longer used after VPN gateway refactor.
```

> **Note:** `InternetWifiNetworkState.toDto()` itself is also deleted in the same sweep (it references `NonMeshWifiConnectionStateDto`). Keep the stub until `MeshrabiyaApiImpl` no longer references `_nonMeshWifiState` / `internetWifiNetworkStateFlow.map { it.toDto() }` (those are removed in Change 5-B).

---

### Change 3-B — Delete `NonMeshWifiConnectionStateDto` data class (lines 743–772)

**BEFORE (lines 737–773):**
```kotlin
/**
 * State of the current non-mesh WiFi internet connection.
 * Observed via MeshrabiyaApi.getNonMeshWifiStateFlow().
 */
data class NonMeshWifiConnectionStateDto(
    val status: NonMeshWifiStatusDto,
    val connectedSsid: String? = null,
    val errorMessage: String? = null,
    /**
     * True when Android's ConnectivityService has confirmed this network has internet access
     * via NET_CAPABILITY_VALIDATED (HTTP 204 probe to connectivitycheck.gstatic.com succeeded).
     * Updated asynchronously after onCapabilitiesChanged() fires following connection.
     */
    val hasInternetAccess: Boolean = false,
    /**
     * IPv4 address assigned to the device on this internet WiFi connection, or null if
     * not yet available or not connected. Extracted from LinkProperties.linkAddresses.
     */
    val internetConnectionIpAddress: String? = null,
)
```

**AFTER:** Delete the entire `data class NonMeshWifiConnectionStateDto` block.

---

### Change 3-C — Remove three `nonMesh*` fields from `NetworkInfoDto` and add two VPN fields (lines 80–93)

**BEFORE (lines 78–111):**
```kotlin
// NetworkInfo DTO
data class NetworkInfoDto(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val connectedPeers: Int,
    val isConnected: Boolean,
    val nonMeshSsid: String? = null,
    val nonMeshIpAddress: String? = null,
    val nonMeshHasInternet: Boolean? = null,
    val torGateways: Int,
    val clearnetGateways: Int,
    val meshProxyActive: Boolean = false,
)

fun NetworkInfo.toDto(
    nonMeshSsid: String? = null,
    nonMeshIpAddress: String? = null,
    nonMeshHasInternet: Boolean? = null,
    meshProxyActive: Boolean = false,
) = NetworkInfoDto(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    nonMeshSsid,
    nonMeshIpAddress,
    nonMeshHasInternet,
    torGateways,
    clearnetGateways,
    meshProxyActive,
)

fun NetworkInfoDto.toInternal() = NetworkInfo(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    torGateways,
    clearnetGateways
)
```

**AFTER:**
```kotlin
// NetworkInfo DTO
data class NetworkInfoDto(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val connectedPeers: Int,
    val isConnected: Boolean,
    /** True when the Orbot VPN tunnel is active and has confirmed internet access. */
    val vpnHasInternet: Boolean = false,
    /**
     * True when the VPN tunnel's underlying transport is WiFi (determined in TorStatusMonitor).
     * When true on a single-radio device the mesh cannot run simultaneously.
     */
    val vpnOverWifi: Boolean = false,
    val torGateways: Int,
    val clearnetGateways: Int,
    val meshProxyActive: Boolean = false,
)

fun NetworkInfo.toDto(
    vpnHasInternet: Boolean = false,
    vpnOverWifi: Boolean = false,
    meshProxyActive: Boolean = false,
) = NetworkInfoDto(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    vpnHasInternet,
    vpnOverWifi,
    torGateways,
    clearnetGateways,
    meshProxyActive,
)

fun NetworkInfoDto.toInternal() = NetworkInfo(
    ssid,
    bssid,
    ipAddress,
    connectedPeers,
    isConnected,
    torGateways,
    clearnetGateways
)
```

---

## 4. `MeshrabiyaApi.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`

Changes listed bottom-to-top.

---

### Change 4-A — Delete `scanAvailableWifiNetworks` (line 474)

**BEFORE (lines 470–479):**
```kotlin
    /**
     * Scan for available WiFi networks.
     * Requires ACCESS_FINE_LOCATION permission.
     * @return List of discovered networks, ordered by signal strength descending.
     */
    suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto>
```

**AFTER:** Delete the entire javadoc + declaration block above.

---

### Change 4-B — Delete `getNonMeshWifiStateFlow` (line 467)

**BEFORE (lines 462–470):**
```kotlin
    /**
     * Observe the current non-mesh WiFi connection state.
     * Emits [NonMeshWifiConnectionStateDto] updates as connection state changes.
     */
    fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto>
```

**AFTER:** Delete the entire javadoc + declaration block above.

---

### Change 4-C — Delete `disconnectFromNonMeshWifi` (line 436)

**BEFORE (lines 431–440):**
```kotlin
    /**
     * Disconnect from the non-mesh internet WiFi.
     * Removes the WifiNetworkSuggestion and releases the internet Network object.
     * @return true if disconnection was performed, false if no connection was active.
     */
    suspend fun disconnectFromNonMeshWifi(): Boolean
```

**AFTER:** Delete the entire javadoc + declaration block above.

---

### Change 4-D — Delete `connectToNonMeshWifi` and replace with `notifyVpnStateChanged` + `getVpnStateFlow` (lines 395–410)

**BEFORE (lines 388–412):**
```kotlin
    // ========================================
    // WiFi Internet Connection API (WIFI_AP_CON)
    // ========================================

    /**
     * Connect to a non-mesh WiFi network while the mesh remains active.
     *
     * Requires AP+STA concurrency (hotspot mode, API 30+) or STA/STA concurrency
     * (Join Mesh mode, API 31+). Returns failure if hardware does not support the
     * required mode.
     *
     * @param ssid Target WiFi network SSID.
     * @param passphrase WPA2 passphrase. Pass empty string for open networks.
     * @return NonMeshWifiConnectionStateDto with status CONNECTED on success, FAILED on failure.
     */
    suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto

    /**
     * Returns true if this device is capable of hosting a Wi‑Fi hotspot / AP.
     */
    fun isApCapable(): Boolean
```

**AFTER:**
```kotlin
    // ========================================
    // VPN State API
    // ========================================

    /**
     * Called by the app layer whenever Orbot VPN transitions: started → active, or active → stopped.
     * Meshrabiya uses this to gate gateway functionality and update NetworkInfoDto.vpnHasInternet.
     */
    fun notifyVpnStateChanged(vpnState: VpnStateDto)

    /**
     * Observe the current Orbot VPN state.
     * Emits [VpnStateDto] updates as VPN transitions.
     */
    fun getVpnStateFlow(): StateFlow<VpnStateDto>

    /**
     * Returns true if this device is capable of hosting a Wi‑Fi hotspot / AP.
     */
    fun isApCapable(): Boolean
```

**Import to add** in `MeshrabiyaApi.kt` imports block:
```kotlin
import com.ustadmobile.meshrabiya.api.model.VpnStateDto
```

---

## 5. `MeshrabiyaApiImpl.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

Changes listed bottom-to-top.

---

### Change 5-A — Update `NetworkInfoDto` construction inside `combine()` block (lines 355–369)

**BEFORE (lines 354–376):**
```kotlin
                val meshInternetGatewayAvailable = meshViaGatewayConfirmed
                Pair(
                    NetworkInfoDto(
                        bssid = "",
                        ssid = "",
                        ipAddress = localState.address.addressToDotNotation(),
                        isConnected = true,
                        connectedPeers = neighborCount,
                        torGateways = torGateways,
                        clearnetGateways = clearnetGateways,
                        nonMeshSsid = nonMeshSsid,
                        nonMeshIpAddress = internetWifiState.internetConnectionIpAddress,
                        nonMeshHasInternet = nonMeshHasInternet
                    ),
                    meshInternetGatewayAvailable
                )
            }
            .distinctUntilChanged()
            .collect { (dto, meshInternetGatewayAvailable) ->
```

**AFTER:**
```kotlin
                val meshInternetGatewayAvailable = meshViaGatewayConfirmed
                val currentVpn = _vpnStateFlow.value
                Pair(
                    NetworkInfoDto(
                        bssid = "",
                        ssid = "",
                        ipAddress = localState.address.addressToDotNotation(),
                        isConnected = true,
                        connectedPeers = neighborCount,
                        torGateways = torGateways,
                        clearnetGateways = clearnetGateways,
                        vpnHasInternet = currentVpn.active,
                        vpnOverWifi = currentVpn.vpnOverWifi,
                    ),
                    meshInternetGatewayAvailable
                )
            }
            .distinctUntilChanged()
            .collect { (dto, meshInternetGatewayAvailable) ->
```

---

### Change 5-B — Replace 6-input `combine()` block inputs to remove nonMesh flows (lines 318–335)

**BEFORE (lines 316–336):**
```kotlin
        // Reactively derive NetworkInfoDto from topology + wifi + non-mesh state — no polling
        // _nonMeshInternetConfirmed is the 5th input: persists green dot through transient VALIDATED dropouts
        eventMonitoringScope.launch {
            combine(
                node.state.map { it.toDto() },
                node.originatingMessageManager.topologyMapFlow,
                _nonMeshWifiState,
                node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.map { it.toDto() },
                combine(_nonMeshInternetConfirmed, _currentMeshRolesFlow) { confirmed: Boolean, roles: Set<MeshRoleDto> -> Pair(confirmed, roles) },
                _meshInternetViaGatewayConfirmed
            ) { args: Array<Any?> ->
                val localState = args[0] as LocalNodeStateDto
                val topology = args[1] as Map<Int, NodeTopologyInfo>
                val nonMeshWifi = args[2] as NonMeshWifiConnectionStateDto
                val internetWifiState = args[3] as NonMeshWifiConnectionStateDto
                val confirmedAndRoles = args[4] as Pair<Boolean, Set<MeshRoleDto>>
                val meshViaGatewayConfirmed = args[5] as Boolean

                val (internetConfirmed, localRoles) = confirmedAndRoles
                val neighborCount = localState.originatorMessages.count { it.value.hopCount == 1.toByte() }
```

**AFTER:**
```kotlin
        // Reactively derive NetworkInfoDto from topology + wifi + vpn state — no polling
        eventMonitoringScope.launch {
            combine(
                node.state.map { it.toDto() },
                node.originatingMessageManager.topologyMapFlow,
                _vpnStateFlow,
                combine(_currentMeshRolesFlow, _meshInternetViaGatewayConfirmed) { roles: Set<MeshRoleDto>, confirmed: Boolean -> Pair(roles, confirmed) }
            ) { args: Array<Any?> ->
                val localState = args[0] as LocalNodeStateDto
                val topology = args[1] as Map<Int, NodeTopologyInfo>
                @Suppress("UNCHECKED_CAST")
                val rolesAndConfirmed = args[3] as Pair<Set<MeshRoleDto>, Boolean>
                val meshViaGatewayConfirmed = rolesAndConfirmed.second
                val localRoles = rolesAndConfirmed.first

                val neighborCount = localState.originatorMessages.count { it.value.hopCount == 1.toByte() }
```

---

### Change 5-C — Update the `args` destructuring block that references the now-removed nonMesh locals (lines 336–356 after Change 5-B)

After applying Change 5-B the old variables `nonMeshWifi`, `internetWifiState`, `internetConfirmed`, `nonMeshSsid`, `nonMeshHasInternet`, `localHasInternet` are gone from the lambda. Replace the whole lambda body continuation:

**BEFORE (lines 335–355 — in original file):**
```kotlin
                val remoteTorGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val remoteClearnetGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val torGateways = remoteTorGateways + (if (MeshRoleDto.TOR_GATEWAY in localRoles) 1 else 0)
                val clearnetGateways = remoteClearnetGateways + (if (MeshRoleDto.CLEARNET_GATEWAY in localRoles) 1 else 0)
                val nonMeshSsid = nonMeshWifi.connectedSsid
                val nonMeshHasInternet = (internetWifiState.hasInternetAccess || internetConfirmed)
                    .takeIf { nonMeshWifi.status == NonMeshWifiStatusDto.CONNECTED }
                val hasRemoteClearnetGateway = remoteClearnetGateways > 0
                val isLocalClearnetGateway = MeshRoleDto.CLEARNET_GATEWAY in localRoles
                val localHasInternet = nonMeshHasInternet == true
                // meshInternetGatewayAvailable is driven solely by _meshInternetViaGatewayConfirmed
                // for all gateway cases. This decouples the mesh green dot from the non-mesh HTTP
                // probe result, preventing the mesh dot from disappearing when the VPN causes
                // the non-mesh probe to fail transiently.
                val meshInternetGatewayAvailable = meshViaGatewayConfirmed
```

**AFTER:**
```kotlin
                val remoteTorGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val remoteClearnetGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val torGateways = remoteTorGateways + (if (MeshRoleDto.TOR_GATEWAY in localRoles) 1 else 0)
                val clearnetGateways = remoteClearnetGateways + (if (MeshRoleDto.CLEARNET_GATEWAY in localRoles) 1 else 0)
                // meshInternetGatewayAvailable is driven solely by _meshInternetViaGatewayConfirmed.
                val meshInternetGatewayAvailable = meshViaGatewayConfirmed
```

---

### Change 5-D — Replace `_nonMeshWifiState` field declaration with `_vpnStateFlow` (lines 172–174)

**BEFORE (lines 169–178):**
```kotlin
    // Non-mesh WiFi connection state Flow — updated by connectToNonMeshWifi/disconnectFromNonMeshWifi
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE))

    private val _meshExtenderHotspotState = MutableStateFlow(MeshExtenderHotspotStateDto.INACTIVE)
    override val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto> = _meshExtenderHotspotState.asStateFlow()

    private val _meshApActiveFlow = MutableStateFlow(false)
    override val meshApActiveFlow: StateFlow<Boolean> = _meshApActiveFlow.asStateFlow()
```

**AFTER:**
```kotlin
    // VPN state — pushed by TorStatusMonitor via notifyVpnStateChanged()
    private val _vpnStateFlow = MutableStateFlow(VpnStateDto.INACTIVE)

    private val _meshExtenderHotspotState = MutableStateFlow(MeshExtenderHotspotStateDto.INACTIVE)
    override val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto> = _meshExtenderHotspotState.asStateFlow()

    private val _meshApActiveFlow = MutableStateFlow(false)
    override val meshApActiveFlow: StateFlow<Boolean> = _meshApActiveFlow.asStateFlow()
```

---

### Change 5-E — Add `notifyVpnStateChanged()` and `getVpnStateFlow()` implementations (insert after `isTorActive()` implementation)

Locate `fun isTorActive(): Boolean` in the file and append the two new methods immediately after it:

**BEFORE (the `isTorActive` block and the line after it):**
```kotlin
    override fun isTorActive(): Boolean = isTorRunning

     // --- Proxy Controls ---
```

**AFTER:**
```kotlin
    override fun isTorActive(): Boolean = isTorRunning

    override fun notifyVpnStateChanged(vpnState: VpnStateDto) {
        _vpnStateFlow.value = vpnState
    }

    override fun getVpnStateFlow(): StateFlow<VpnStateDto> = _vpnStateFlow.asStateFlow()

     // --- Proxy Controls ---
```

**Import to add** in `MeshrabiyaApiImpl.kt` imports block:
```kotlin
import com.ustadmobile.meshrabiya.api.model.VpnStateDto
```

---

### Change 5-F — Remove dead `_nonMeshInternetConfirmed` / `nonMeshInternetCheckJob` / `_nonMeshWifiState` polling blocks from `startEventMonitoring()`

These are the two `eventMonitoringScope.launch` blocks that:
1. Watch `_nonMeshWifiState` and drive `nonMeshInternetCheckJob` / `_nonMeshInternetConfirmed`
2. Immediately confirm internet from `internetWifiNetworkStateFlow`

Locate and delete both blocks (they begin around lines 461–510 of the original file). The exact anchors:

**BEFORE — block 1 (lines 455–466):**
```kotlin
        // Immediately confirm internet when OS validates (for fast initial green dot appearance)
        eventMonitoringScope.launch {
            node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.collect { state ->
                if (state.hasInternetAccess) {
                    _nonMeshInternetConfirmed.value = true
                }
            }
        }

        // Periodic active internet probe: keeps green dot alive through transient VALIDATED dropouts
        // (e.g., caused by VPN activation). Cancels and resets on disconnect.
        eventMonitoringScope.launch {
            _nonMeshWifiState.collect { nonMeshState ->
                nonMeshInternetCheckJob?.cancel()
                nonMeshInternetCheckJob = null
                if (nonMeshState.status == NonMeshWifiStatusDto.CONNECTED) {
                    nonMeshInternetCheckJob = launch {
                        while (true) {
                            delay(NONMESH_INTERNET_CHECK_INTERVAL_MS)
                            val confirmed = checkNonMeshInternetAccess(node)
                            if (confirmed) {
                                _nonMeshInternetConfirmed.value = true
                            }
                        }
                    }
                } else {
                    _nonMeshInternetConfirmed.value = false
                }
            }
        }
```

**AFTER:** Delete both `eventMonitoringScope.launch` blocks shown above entirely.

---

### Change 5-G — Remove dead field declarations (lines 228–232)

**BEFORE:**
```kotlin
    // Confirmed internet access on non-mesh WiFi; persists across transient VALIDATED dropouts
    private val _nonMeshInternetConfirmed = MutableStateFlow(false)
    private var nonMeshInternetCheckJob: Job? = null
    // Confirmed internet access via a remote CLEARNET_GATEWAY (mesh-side probe). Set by periodic checkInternetViaMeshGateway().
    private val _meshInternetViaGatewayConfirmed = MutableStateFlow(false)
    private var meshInternetCheckJob: Job? = null
```

**AFTER (keep `_meshInternetViaGatewayConfirmed` — it is still used; remove the nonMesh pair):**
```kotlin
    // Confirmed internet access via a remote CLEARNET_GATEWAY (mesh-side probe). Set by periodic checkInternetViaMeshGateway().
    private val _meshInternetViaGatewayConfirmed = MutableStateFlow(false)
    private var meshInternetCheckJob: Job? = null
```

---

## 6. `EmergentRoleManager.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

Changes listed bottom-to-top.

---

### Change 6-A — Fix fallback path `hasNonMeshInternetAccess` copy (line 720)

**BEFORE (lines 714–726):**
```kotlin
            NodeCapabilitySnapshot(
                nodeId = virtualNode.addressAsInt.toString(),
                resources = resources,
                batteryInfo = batteryInfo,
                thermalState = ThermalState.COOL, // Fallback: assume cool
                networkQuality = (fitnessScore.signalStrength / 100.0f).coerceIn(0.0f, 1.0f),
                stability = 0.8f, // Fallback: assume good stability
                hasNonMeshInternetAccess = (virtualNode.meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
                    ?.internetWifiNetworkStateFlow?.value?.hasInternetAccess ?: false,
            )
```

**AFTER:**
```kotlin
            NodeCapabilitySnapshot(
                nodeId = virtualNode.addressAsInt.toString(),
                resources = resources,
                batteryInfo = batteryInfo,
                thermalState = ThermalState.COOL, // Fallback: assume cool
                networkQuality = (fitnessScore.signalStrength / 100.0f).coerceIn(0.0f, 1.0f),
                stability = 0.8f, // Fallback: assume good stability
                hasVpnInternetAccess = com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
                    .getInstance().getVpnStateFlow().value.active,
            )
```

---

### Change 6-B — Fix main path `enhancedSnapshot.copy(hasNonMeshInternetAccess = ...)` (lines 676–678)

**BEFORE (lines 673–682):**
```kotlin
            val nonMeshInternetAccess = (virtualNode.meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
                ?.internetWifiNetworkStateFlow?.value?.hasInternetAccess ?: false
            enhancedSnapshot.copy(hasNonMeshInternetAccess = nonMeshInternetAccess)
            
        } catch (e: Exception) {
            // Fallback to legacy implementation if hardware manager fails
```

**AFTER:**
```kotlin
            val vpnActive = com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
                .getInstance().getVpnStateFlow().value.active
            enhancedSnapshot.copy(hasVpnInternetAccess = vpnActive)
            
        } catch (e: Exception) {
            // Fallback to legacy implementation if hardware manager fails
```

---

### Change 6-C — Rename `hasNonMeshInternetAccess` field in `NodeCapabilitySnapshot` (line 68)

**BEFORE (lines 59–76):**
```kotlin
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
```

**AFTER:**
```kotlin
data class NodeCapabilitySnapshot(
    val nodeId: String,
    val resources: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float, // 0.0-1.0
    val stability: Float, // 0.0-1.0 based on uptime/connectivity history
    val timestamp: Long = System.currentTimeMillis(),
    /** True if the Orbot VPN tunnel is active (internet access via VPN). */
    val hasVpnInternetAccess: Boolean = false,
) {
    fun hasStableConnection(): Boolean = networkQuality > 0.7f && stability > 0.6f
```

---

## 7. `AndroidVirtualNode.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

### Change 7-A — Gate `onClearnetGatewayPacket` on VPN active (lines 245–254)

**BEFORE (lines 244–259):**
```kotlin
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
```

**AFTER:**
```kotlin
    override fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean {
        // Route clearnet packets through the Orbot VPN tunnel if it is active.
        // When the VPN is active, all sockets on the default network route through
        // the Orbot TUN interface automatically — no explicit bindSocket() needed.
        val vpnActive = com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
            .getInstance().getVpnStateFlow().value.active
        return if (vpnActive) {
            clearnetGatewayForwarder.forwardViaTun(packet)
            true
        } else {
            logger(Log.WARN, "$logPrefix CLEARNET gateway: VPN not active, dropping clearnet packet", null)
            false
        }
    }


}
```

---

## 8. `ClearnetGatewayForwarder.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/ClearnetGatewayForwarder.kt`

### Change 8-A — Replace `forward(packet, internetWifiNetwork)` with `forwardViaTun(packet)` (no Network param)

The existing `fun forward(packet, internetWifiNetwork: Network)` binds to the WiFi NIC. Replace it with `forwardViaTun` that uses the default socket (routes through TUN automatically).

**BEFORE (lines 32–86):**
```kotlin
    fun forward(packet: VirtualPacket, internetWifiNetwork: Network) {
        scope.launch {
            try {
                val header = packet.header
                val destIpBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(header.toAddr)
                    .array()
                val destInetAddr = InetAddress.getByAddress(destIpBytes)
                val destPort = header.toPort.toInt() and 0xFFFF
                val payloadSize = header.payloadSize
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset,
                    packet.payloadOffset + payloadSize
                )

                logger(Log.DEBUG, "$logPrefix forward: dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                val socket = DatagramSocket()
                internetWifiNetwork.bindSocket(socket)
                socket.soTimeout = 5_000
                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(destInetAddr, destPort)))

                val responseBuffer = ByteArray(65_535)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                try {
                    socket.receive(responsePacket)
                    val responseData = responsePacket.data.copyOf(responsePacket.length)
                    val returnHeader = VirtualPacketHeader(
                        toAddr = header.fromAddr,
                        toPort = header.fromPort,
                        fromAddr = header.toAddr,
                        fromPort = destPort,
                        lastHopAddr = 0,
                        hopCount = 0,
                        maxHops = header.maxHops,
                        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                        payloadSize = responseData.size,
                    )
                    onResponsePacket(VirtualPacket.fromHeaderAndPayloadData(returnHeader, responseData, 0))
                    logger(Log.DEBUG, "$logPrefix response ${responsePacket.length} bytes → ${header.fromAddr}")
                } catch (e: java.net.SocketTimeoutException) {
                    logger(Log.WARN, "$logPrefix response timeout for $destInetAddr:$destPort")
                } finally {
                    socket.close()
                }
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix forward error: ${e.message}")
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix unexpected error: ${e.message}", e)
            }
        }
    }
```

**AFTER:**
```kotlin
    /**
     * Forward a CLEARNET-routed packet over the default network (Orbot VPN TUN interface).
     * Does NOT call bindSocket() — the OS routes sockets through the VPN TUN automatically
     * when the VPN is active, matching the behaviour described in VPN_GATEWAY_REFACTOR_PLAN §8.
     */
    fun forwardViaTun(packet: VirtualPacket) {
        scope.launch {
            try {
                val header = packet.header
                val destIpBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(header.toAddr)
                    .array()
                val destInetAddr = InetAddress.getByAddress(destIpBytes)
                val destPort = header.toPort.toInt() and 0xFFFF
                val payloadSize = header.payloadSize
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset,
                    packet.payloadOffset + payloadSize
                )

                logger(Log.DEBUG, "$logPrefix forwardViaTun: dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                // Plain DatagramSocket — OS routes through VPN TUN automatically.
                val socket = DatagramSocket()
                socket.soTimeout = 5_000
                socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(destInetAddr, destPort)))

                val responseBuffer = ByteArray(65_535)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                try {
                    socket.receive(responsePacket)
                    val responseData = responsePacket.data.copyOf(responsePacket.length)
                    val returnHeader = VirtualPacketHeader(
                        toAddr = header.fromAddr,
                        toPort = header.fromPort,
                        fromAddr = header.toAddr,
                        fromPort = destPort,
                        lastHopAddr = 0,
                        hopCount = 0,
                        maxHops = header.maxHops,
                        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                        payloadSize = responseData.size,
                    )
                    onResponsePacket(VirtualPacket.fromHeaderAndPayloadData(returnHeader, responseData, 0))
                    logger(Log.DEBUG, "$logPrefix response ${responsePacket.length} bytes → ${header.fromAddr}")
                } catch (e: java.net.SocketTimeoutException) {
                    logger(Log.WARN, "$logPrefix response timeout for $destInetAddr:$destPort")
                } finally {
                    socket.close()
                }
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix forwardViaTun error: ${e.message}")
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix unexpected error: ${e.message}", e)
            }
        }
    }
```

**Import to remove** (no longer needed after removing `Network` parameter):
```kotlin
import android.net.Network
```

---

## 9. `EnhancedMeshFragment.kt`

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

Changes listed bottom-to-top.

---

### Change 9-A — Delete `connectToInternetWifi()` (lines 2666–2683)

**BEFORE (lines 2660–2685):**
```kotlin
    private fun showPassphraseDialog(ssid: String) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "WiFi Password"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Connect to $ssid")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                connectToInternetWifi(ssid, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToInternetWifi(ssid: String, passphrase: String) {
        lifecycleScope.launch {
            val result = meshrabiyaApi.connectToNonMeshWifi(ssid, passphrase)
            // Avoid importing NonMeshWifiStatus enum — use DTO field checks instead.
            val message = when {
                result.connectedSsid != null -> "Connected to $ssid"
                result.errorMessage != null  -> "Connection failed: ${result.errorMessage}"
                else                         -> "Connecting to $ssid..."
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
```

**AFTER:**
```kotlin
    private fun showPassphraseDialog(ssid: String) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "WiFi Password"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Connect to $ssid")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                connectToInternetWifi(ssid, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // connectToInternetWifi() deleted — nonMeshWifi feature removed (VPN gateway refactor)
}
```

Then also delete `showPassphraseDialog` and `showInternetWifiConnectionDialog` in the same sweep (they have no callers after the button and dialog methods are removed):

**Delete `showPassphraseDialog` (lines 2650–2665)** — entire method body.

**Delete `showInternetWifiConnectionDialog` (lines 2610–2648)** — entire method body.

---

### Change 9-B — Delete `setupNonMeshWifiObserver()` and its call site, add `setupVpnStatusObserver()` call site (lines 850, 416)

First the **method body** (lines 850–870):

**BEFORE (lines 849–877):**
```kotlin
    private fun setupNonMeshWifiObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getNonMeshWifiStateFlow().collect { nonMeshState ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    val connected = nonMeshState.status.name == "CONNECTED"
                    if (connected) {
                        MeshUIBindings.wifiApConnectionButton.setText(R.string.wifi_internet)
                        
                        MeshUIBindings.wifiApConnectionButton.setIconResource(R.drawable.ic_stop)
                        MeshUIBindings.wifiApConnectionButton.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                        MeshUIBindings.wifiApConnectionButton.setTextColor(android.graphics.Color.WHITE)
                    } else {
                        MeshUIBindings.wifiApConnectionButton.setText(R.string.wifi_internet)
                        MeshUIBindings.wifiApConnectionButton.setIconResource(R.drawable.ic_wifi)
                    }
                }
            }
        }
    }

    private fun setupMeshExtenderObserver() {
```

**AFTER:**
```kotlin
    private fun setupVpnStatusObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getVpnStateFlow().collect { vpnState ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    MeshUIBindings.vpnStatusRow.visibility =
                        if (vpnState.active) View.VISIBLE else View.GONE
                    if (vpnState.active) {
                        val portText = vpnState.socksPort?.let { ":$it" } ?: ""
                        MeshUIBindings.vpnStatusText.text = "Orbot VPN$portText"
                        MeshUIBindings.vpnTransportChip.visibility = View.VISIBLE
                        MeshUIBindings.vpnStatusGreenDot.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupMeshExtenderObserver() {
```

Now the **call site** (line 416):

**BEFORE (lines 413–420):**
```kotlin
		setupMeshInternetGreenDotObserver()
        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")
        observeGatewayAvailability()
		// setupNetworkInfoObserver()
        setupNonMeshWifiObserver()
        setupMeshExtenderObserver()
```

**AFTER:**
```kotlin
		setupMeshInternetGreenDotObserver()
        android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")
        observeGatewayAvailability()
		// setupNetworkInfoObserver()
        setupVpnStatusObserver()
        setupMeshExtenderObserver()
```

---

### Change 9-C — Rewire `setupMeshInternetGreenDotObserver()` to use VPN state (lines 823–836)

**BEFORE (lines 823–842):**
```kotlin
	private fun setupMeshInternetGreenDotObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
                if (!deferredViewsInitialized) return@collect
                val nonMeshInternet = (meshrabiyaApi as? MeshrabiyaApiImpl)
                    ?.networkInfoFlow?.value?.nonMeshHasInternet == true
                val hasAnyInternet = nonMeshInternet || gatewayAvailable
                activity?.runOnUiThread {
                    MeshUIBindings.meshInternetGreenDot.visibility =
                        if (hasAnyInternet) View.VISIBLE else View.GONE
                }
            }
        }
    }
```

**AFTER:**
```kotlin
	private fun setupMeshInternetGreenDotObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                meshrabiyaApi.getMeshInternetGatewayAvailableFlow(),
                meshrabiyaApi.getVpnStateFlow()
            ) { gatewayAvailable, vpnState ->
                gatewayAvailable || vpnState.active
            }.collect { hasAnyInternet ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    MeshUIBindings.meshInternetGreenDot.visibility =
                        if (hasAnyInternet) View.VISIBLE else View.GONE
                }
            }
        }
    }
```

---

### Change 9-D — Remove `internetWifiRow`/`internetWifiIpText`/`internetWifiChipSta`/`internetWifiGreenDot` references from `setupNetworkInfoObserver()` (lines 808–815)

**BEFORE (lines 800–820):**
```kotlin
                        if (!networkInfo.nonMeshSsid.isNullOrEmpty()) {
                            android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] non‑mesh SSID present: ${networkInfo.nonMeshSsid}")
                            MeshUIBindings.internetWifiRow.visibility = View.VISIBLE
                            MeshUIBindings.internetWifiIpText.text = networkInfo.nonMeshIpAddress ?: "--"
                            MeshUIBindings.internetWifiChipSta.visibility = View.VISIBLE
                            MeshUIBindings.internetWifiGreenDot.visibility =
                                if (networkInfo.nonMeshHasInternet == true) View.VISIBLE else View.GONE
                        } else {
                            android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] non‑mesh SSID empty – hiding row")
                            MeshUIBindings.internetWifiRow.visibility = View.GONE
                        }
```

**AFTER:**
```kotlin
                        // vpnStatusRow is driven exclusively by setupVpnStatusObserver().
                        // No nonMeshSsid / nonMeshIpAddress references remain here.
```

---

### Change 9-E — Delete `wifiApConnectionButton` click handler (lines 1079–1090)

**BEFORE (lines 1078–1095):**
```kotlin
		MeshUIBindings.wifiApConnectionButton.setOnClickListener {
			val wifiStatus = meshrabiyaApi.getNonMeshWifiStateFlow().value.status
			if (wifiStatus.name == "CONNECTED") {
				lifecycleScope.launch {
					meshrabiyaApi.disconnectFromNonMeshWifi()
				}
			} else {
				showInternetWifiConnectionDialog()
			}
		}

		MeshUIBindings.meshExtenderApButton.setOnClickListener {
```

**AFTER:**
```kotlin
		// wifiApConnectionButton removed — nonMeshWifi feature replaced by VPN gateway.

		MeshUIBindings.meshExtenderApButton.setOnClickListener {
```

---

### Change 9-F — Delete `wifiApConnectionButton` visibility assignment in role observer (lines 737–738)

**BEFORE (lines 733–742):**
```kotlin
                    val isWifiConcurrentCapable = meshrabiyaApi.isApStaConcurrentCapable() || meshrabiyaApi.isStaStaConcurrentCapable()
                    MeshUIBindings.wifiApConnectionButton.visibility =
                        if (isWifiConcurrentCapable) View.VISIBLE else View.GONE
                    MeshUIBindings.meshExtenderApButton.visibility =
                        if (showButtons) View.VISIBLE else View.GONE
```

**AFTER:**
```kotlin
                    MeshUIBindings.meshExtenderApButton.visibility =
                        if (showButtons) View.VISIBLE else View.GONE
```

---

### Change 9-G — Add `setupVpnStatusObserver()` import for `VpnStateDto` and add `VpnStateDto` to import block (line ~30)

**BEFORE (existing imports block excerpt, lines 26–34):**
```kotlin
import com.ustadmobile.meshrabiya.api.model.MeshStateDto
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiNetworkDto
import com.ustadmobile.meshrabiya.api.model.DropFolderItemDto
import com.ustadmobile.meshrabiya.api.model.NetworkInfoDto
import com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.MeshExtenderHotspotStateDto
```

**AFTER:**
```kotlin
import com.ustadmobile.meshrabiya.api.model.MeshStateDto
import com.ustadmobile.meshrabiya.api.model.DropFolderItemDto
import com.ustadmobile.meshrabiya.api.model.NetworkInfoDto
import com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.MeshExtenderHotspotStateDto
import com.ustadmobile.meshrabiya.api.model.VpnStateDto
```

(`NonMeshWifiNetworkDto` import deleted — no longer referenced after Change 9-A.)

---

## Layout XML changes (§12 of plan)

**File:** `app/src/main/res/layout/fragment_enhanced_mesh.xml` (or equivalent layout containing these views)

These are ID renames - no logic change, but required so `MeshUIBindings.*` references compile:

| Old ID | New ID | Action |
|--------|--------|--------|
| `internetWifiRow` | `vpnStatusRow` | rename `android:id` |
| `internetWifiIpText` | `vpnStatusText` | rename `android:id` |
| `internetWifiChipSta` | `vpnTransportChip` | rename `android:id`; change chip text to `"VPN"` |
| `internetWifiGreenDot` | `vpnStatusGreenDot` | rename `android:id` |
| `wifiApConnectionButton` | *(delete entire View)* | remove the `<com.google.android.material.button.MaterialButton>` element |

Also add a new `<TextView>` for the VPN-over-WiFi warning (add inside the mesh control card, above `meshToggleButton`):
```xml
<TextView
    android:id="@+id/vpnMeshWarningText"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="⚠ VPN over WiFi detected — mesh unavailable on single-radio devices"
    android:textColor="@color/warning_orange"
    android:visibility="gone"
    android:padding="8dp" />
```

---

## MeshUIBindings rename checklist

In `MeshUIBindings.kt` (or wherever the binding object is declared), rename:

| Old binding field | New binding field |
|-------------------|-------------------|
| `internetWifiRow` | `vpnStatusRow` |
| `internetWifiIpText` | `vpnStatusText` |
| `internetWifiChipSta` | `vpnTransportChip` |
| `internetWifiGreenDot` | `vpnStatusGreenDot` |

Delete: `wifiApConnectionButton` binding field.

Add: `vpnMeshWarningText` binding field (wired to the new `<TextView>` added above).

---

## Dead code to delete (§14 of plan)

| Symbol | File | Lines | Reason |
|--------|------|-------|--------|
| `MeshInternetRelayServer.kt` | `Meshrabiya/lib-meshrabiya/.../vnet/` | entire file | never instantiated |
| `connectToNonMeshWifi()` impl | `MeshrabiyaApiImpl.kt` | search for `override fun connectToNonMeshWifi` | replaced by VPN path |
| `disconnectFromNonMeshWifi()` impl | `MeshrabiyaApiImpl.kt` | search for `override fun disconnectFromNonMeshWifi` | replaced by VPN path |
| `getNonMeshWifiStateFlow()` impl | `MeshrabiyaApiImpl.kt` | search for `override fun getNonMeshWifiStateFlow` | replaced by `getVpnStateFlow()` |
| `scanAvailableWifiNetworks()` impl | `MeshrabiyaApiImpl.kt` | search for `override fun scanAvailableWifiNetworks` | no UI consumer remains |
| `isInternetWifiFeatureAvailable()` impl | `MeshrabiyaApiImpl.kt` | search for `override fun isInternetWifiFeatureAvailable` | feature removed |
| `checkNonMeshInternetAccess()` | `MeshrabiyaApiImpl.kt` | `private suspend fun checkNonMeshInternetAccess` | no caller remains |
| `_nonMeshWifiState` | `MeshrabiyaApiImpl.kt` | replaced by `_vpnStateFlow` | done in Change 5-D |
| `_nonMeshInternetConfirmed` | `MeshrabiyaApiImpl.kt` | removed in Change 5-G | no consumer remains |
| `nonMeshInternetCheckJob` | `MeshrabiyaApiImpl.kt` | removed in Change 5-G | no consumer remains |
| `internetWifiNetworkStateFlow` usages | `MeshrabiyaWifiManagerAndroid.kt` calls | all call sites in ApiImpl removed | field may remain in manager |
| `bindSocket(socket)` | `ClearnetGatewayForwarder.kt` | removed in Change 8-A | replaced by TUN routing |

---

## Implementation order

Apply changes in this sequence to minimise mid-refactor compile errors:

1. Add `VpnStateDto` to `DtoModels.kt` (Change 1-A — append after line 797)
2. Rename `DtoModels.kt` `NetworkInfoDto` fields (Change 3-C) — all other files depend on the new field names
3. Add `VpnStateDto` import + `notifyVpnStateChanged` / `getVpnStateFlow` to `MeshrabiyaApi.kt` (Change 4-D); delete the 4 nonMesh declarations (Changes 4-A/B/C)
4. Apply all `MeshrabiyaApiImpl.kt` changes (Changes 5-D through 5-G, then 5-B/C, then 5-A, then 5-E)
5. Apply `EmergentRoleManager.kt` changes bottom-to-top (6-A, 6-B, 6-C)
6. Apply `AndroidVirtualNode.kt` change (7-A)
7. Apply `ClearnetGatewayForwarder.kt` change (8-A)
8. Rename layout XML view IDs + delete `wifiApConnectionButton`
9. Update `MeshUIBindings` field names
10. Apply `EnhancedMeshFragment.kt` changes bottom-to-top (9-A through 9-G)
11. Extend `TorStatusMonitor.onReceive()` (Change 2-A)
12. Delete `NonMeshWifiConnectionStateDto` and `NonMeshWifiStatusDto` from `DtoModels.kt` (Changes 3-A/B) — safe only after all call sites removed
13. Delete `MeshInternetRelayServer.kt`
14. Compile and verify
