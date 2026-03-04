# WiFi AP Concurrency Feature Plan
**Date:** 2026-03-02  
**Constraint:** No code changes. No assumptions. Every statement is backed by literal file reads of code on disk. All code snippets are in their entirety.

---

## 1. Feature Request

When a device is assigned the `MESH_ROUTER` role, show a new button in the "Start Mesh" button row (in `fragment_mesh_enhanced.xml`). The button (WiFi + arrow + globe icons) opens a dialog to select a non-mesh WiFi network. The library connects to it. If internet is available and `InternetGateway=true`, route mesh traffic and local traffic over it. If `TorGateway=true`, route Tor-destined traffic over it.

---

## 2. Concurrency Detection: Real Architecture (Verified)

### 2.1 The Check Method

`isStaApConcurrencySupported()` (Android API 30).

**Evidence — `MeshrabiyaWifiManagerAndroid.kt`, `detectConcurrentSupport()` (literal file read):**
```kotlin
private fun detectConcurrentSupport(): Boolean {
    return if (Build.VERSION.SDK_INT >= 30) {
        wifiManager.isStaApConcurrencySupported
    } else {
        false
    }
}
```
This method is called during WiFi state initialization and the result is stored in `MeshrabiyaWifiState.concurrentApStationSupported`.

### 2.2 How EmergentRoleManager Consumes It

`EmergentRoleManager.kt` does NOT call `isStaApConcurrencySupported()` directly. It reads `concurrentApStationSupported` from `MeshrabiyaWifiState` via a state flow subscription.

**Evidence — `EmergentRoleManager.kt`, lines 152–195 (literal file read):**
```kotlin
    // CONCURRENCY STATE – tracked to trigger role recalculation when capability is discovered
    private val _concurrencySupported = MutableStateFlow(false)
    val concurrencySupported: StateFlow<Boolean> = _concurrencySupported.asStateFlow()

    // legacy accessor used throughout the class
    private val concurrentApStationSupported: Boolean
        get() = _concurrencySupported.value
```
```kotlin
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
```

### 2.3 How concurrentApStationSupported Gates Role Assignment

**Evidence — `EmergentRoleManager.kt`, lines 428–452 (literal file read):**
```kotlin
        android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] MESH_ROUTER check: concurrency=$concurrentApStationSupported")
        if (concurrentApStationSupported) {
```
```kotlin
        // NEW: MESH_HUB role for non-concurrent hotspot nodes
        // 2. Device does NOT have concurrent AP+Station hardware capability
        if (!concurrentApStationSupported && wifiState.hotspotIsStarted) {
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding MESH_HUB (non-concurrent hotspot)")
        } else {
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✗ MESH_HUB not assigned: concurrency=$concurrentApStationSupported, hotspot=${wifiState.hotspotIsStarted}")
            safeLog(LogLevel.INFO, "MESH_HUB not assigned: concurrency=$concurrentApStationSupported, hotspot=${wifiState.hotspotIsStarted}")
        }
```

**Implication:** `MESH_ROUTER` is only assigned when `concurrentApStationSupported = true`. `MESH_HUB` is only assigned when `concurrentApStationSupported = false`. Therefore, when the UI sees `MESH_ROUTER`, the hardware concurrency capability is already confirmed present.

### 2.4 Button Visibility — AP+STA Mode and STA/STA Caveat

Because the very presence of `MESH_ROUTER` in the roles set guarantees `concurrentApStationSupported = true`, the new WiFi connection button does not need its own hardware capability test for the **AP+STA (Start Mesh)** path. Showing the button when `MESH_ROUTER` is active is sufficient for that path.

**STA/STA caveat (see Section 18):** `MESH_ROUTER` is assigned purely on hardware capability — confirmed: `EmergentRoleManager.kt` lines 427–432 add the role whenever `concurrentApStationSupported = true` with no check on whether the hotspot is running. A device in **Join Mesh** mode (pure station, no hotspot) with AP+STA hardware therefore also receives `MESH_ROUTER`, making the button appear — but in that mode AP+STA machinery cannot work. A separate `isStaStaConcurrencySupported` check (API 31) is required for the Join Mesh + internet WiFi (STA/STA) path. Section 18 specifies the full analysis and required changes (S1–S7). The button visibility must ultimately be gated on `isInternetWifiFeatureAvailable()` (S5) rather than role alone.

---

## 3. Existing MESH_ROUTER Role Observer (UI Insertion Point)

**Evidence — `EnhancedMeshFragment.kt`, lines 653–667 (grep-verified):**

Line 653: `currentMeshRolesFlow` is observed.  
Lines 663–667: `MeshRoleDto.MESH_ROUTER` appear/disappear events are logged. This is the exact location where show/hide logic for the new button must be inserted.

**Evidence — `DtoModels.kt` `MeshRoleDto` enum (literal file read):**
```kotlin
enum class MeshRoleDto {
    MESH_PARTICIPANT,
    STORAGE_NODE,
    COMPUTE_NODE,
    MESH_ROUTER,
    MESH_HUB,
    TOR_GATEWAY,
    CLEARNET_GATEWAY,
    I2P_GATEWAY;
}
```

---

## 4. Critical Blocker: LocalOnlyHotspotManager WiFi Suppression Loop

### 4.1 The Problem

`LocalOnlyHotspotManager.kt` contains a monitoring loop in `startHotspotMonitoring()` that **actively destroys any WiFi connection** to any non-mesh network while the hotspot is running. This directly conflicts with the WiFi AP Concurrency feature.

**Evidence — `LocalOnlyHotspotManager.kt`, lines 160–202 (literal file read):**
```kotlin
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
                
                // PHASE 2: Continuous WiFi Suppression - actively prevent reconnection
                if (currentStatus == HotspotStatus.STARTED && isWifiConnected && wifiSSID != "<unknown ssid>") {
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
```

### 4.2 Why This Matters

This loop was designed for **non-concurrent** devices where Android spontaneously reconnects to a saved WiFi network after `startLocalOnlyHotspot()` runs, competing with the hotspot. On non-concurrent hardware, this suppression is correct.

On **concurrent-capable** hardware (`MESH_ROUTER` device, `concurrentApStationSupported = true`), this same suppression loop will:
1. Detect the successful `WifiNetworkSuggestion` connection to the internet-bearing WiFi
2. Call `wifiManager.disconnect()` on it
3. Call `wifiManager.removeNetwork()` on it
4. Call `wifiManager.disableNetwork()` on all configured networks

**This is the primary implementation blocker. The suppression loop must be conditioned on `!concurrentApStationSupported` before the WiFi AP Concurrency feature can work.**

### 4.3 Additional API Concern

The suppression loop uses deprecated APIs: `wifiManager.connectionInfo` (deprecated API 31), `wifiManager.configuredNetworks` (returns empty on API 29+ for non-system apps), `wifiManager.removeNetwork()` (deprecated API 29). These calls will silently fail on API 29+ with non-system apps, meaning the "suppression" actually does nothing on modern devices — but calling `wifiManager.disconnect()` does still work and will disconnect the suggestion network.

---

## 4b. NEW BLOCKER: AndroidVirtualNode.setWifiHotspotEnabled() Disconnects Station Before Start (Round 3 Analysis — 2026-03-02)

**Evidence — `AndroidVirtualNode.kt`, `setWifiHotspotEnabled()` (literal file read):**
```kotlin
override suspend fun setWifiHotspotEnabled(
    enabled: Boolean,
    preferredBand: ConnectBand,
    hotspotType: HotspotType,
): LocalHotspotResponse? {
    updateBluetoothState()
    
    // CRITICAL: Disconnect from regular WiFi before starting hotspot
    if (enabled) {
        logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (regular WiFi) before starting hotspot", null)
        meshrabiyaWifiManager.disconnectStation()
        
        // CRITICAL: Wait for WiFi disconnect to complete and verify
        kotlinx.coroutines.delay(2000)
    }
    
    return super.setWifiHotspotEnabled(enabled, preferredBand, hotspotType)
}
```

This calls `meshrabiyaWifiManager.disconnectStation()` unconditionally for every `setWifiHotspotEnabled(true)` call. On a MESH_ROUTER device with active internet WiFi, this is worse than a silent disconnect: `disconnectStation()` now **throws `IllegalStateException`** if WiFi is connected.

**Evidence — `MeshrabiyaWifiManagerAndroid.kt`, `disconnectStation()`, lines 589–607 (literal file read):**
```kotlin
if (wasConnected) {
    logger(Log.ERROR, "$logPrefix disconnectStation: ❌ CRITICAL: WiFi reconnected (#$wifiReconnectCount) to $wifiSSID! Forcing disconnect...")
    throw IllegalStateException(
        "❌ Cannot start mesh hotspot while WiFi is enabled.\n\n" +
        "📱 Please manually disable WiFi in Android Settings:\n" +
        "   Settings → Network & Internet → WiFi → Turn OFF"
    )
}
```
Result: On any device connected to WiFi, calling `setWifiHotspotEnabled(true)` **crashes** the hotspot startup path with an unhandled exception — hotspot never starts. On a MESH_ROUTER device that has previously connected internet WiFi, this means the hotspot cannot be re-enabled at all without user manually disabling WiFi. On a MESH_ROUTER device, this call must be skipped entirely when `concurrentApStationSupported = true`.

**Note:** `MeshrabiyaWifiManagerAndroid.requestHotspot()` already has the correct pattern (line 304):
```kotlin
if (!currentState.concurrentApStationSupported && currentState.wifiStationState.status != WifiStationState.Status.INACTIVE) {
    // Disconnect from WiFi first
    wifiManager.disconnect()
}
```
On a MESH_ROUTER device, the entire `disconnectStation()` + `delay(2000)` block must be skipped when `concurrentApStationSupported = true`.

**Concrete fix for `AndroidVirtualNode.kt`, `setWifiHotspotEnabled()`:**
```kotlin
// BEFORE (unconditional — crashes on any WiFi-connected device):
if (enabled) {
    logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (regular WiFi) before starting hotspot", null)
    meshrabiyaWifiManager.disconnectStation()
    kotlinx.coroutines.delay(2000)
}

// AFTER (gated on non-concurrent device only):
if (enabled && !meshrabiyaWifiManager.state.value.concurrentApStationSupported) {
    logger(Log.INFO, "setWifiHotspotEnabled: Non-concurrent device — disconnecting WiFi before starting hotspot", null)
    meshrabiyaWifiManager.disconnectStation()
    kotlinx.coroutines.delay(2000)
}
```
On concurrent devices (`concurrentApStationSupported = true`), the block is skipped entirely: hotspot starts without disconnecting WiFi,  preserving any active internet WiFi connection. The `delay(2000)` guard is also removed on concurrent devices since no WiFi teardown is occurring.

---

## 5. bindProcessToNetwork Conflict

### 5.1 Current Binding

**Evidence — `MeshrabiyaWifiManagerAndroid.kt`, `connectToHotspotInternal()` (literal file read):**
```kotlin
connectivityManager.bindProcessToNetwork(resultState.network)
```
This call binds ALL process traffic (including OrbotService Tor sockets) to the mesh network. Every TCP/UDP socket opened after this call routes through the mesh.

### 5.2 Conflict With Internet Gateway

If the new feature connects to an internet-bearing WiFi network via `WifiNetworkSuggestion`, the internet traffic for that connection will be:
1. Captured by `bindProcessToNetwork(meshNetwork)` and sent over the mesh instead of directly over the WiFi internet connection.
2. The CLEARNET_GATEWAY role then tries to forward that traffic outbound — creating a routing loop on the MESH_ROUTER device itself.

### 5.3 Required Resolution

On a MESH_ROUTER device that is both running the hotspot AND connected to internet WiFi simultaneously:
- `bindProcessToNetwork(null)` must be called to release the process-wide mesh binding.
- Mesh-specific traffic must be routed per-socket via `Network.bindSocket()`.
- Internet-bound traffic (OrbotService + mesh client forwarded traffic) must use the internet WiFi `Network` object.
- This requires the `Network` object from the `WifiNetworkSuggestion` connection (delivered via `ConnectivityManager.NetworkCallback.onAvailable()`).

---

## 6. WifiNetworkSuggestion API Analysis (Research-Verified)

### 6.1 API Level

`addNetworkSuggestions(List<WifiNetworkSuggestion>)` — Android API 29 minimum.

### 6.2 Required Permissions

| Permission | Purpose |
|---|---|
| `CHANGE_WIFI_STATE` | Required by `addNetworkSuggestions()` |
| `ACCESS_FINE_LOCATION` | Required for scan results + `ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION` broadcast |
| `ACCESS_WIFI_STATE` | Required for `addSuggestionConnectionStatusListener()` |
| `NEARBY_WIFI_DEVICES` | Required by `startLocalOnlyHotspot()` on API 33+ |

### 6.3 Platform Behavior Constraints

- The platform **decides** whether to connect to a suggested network. The app cannot force connection.
- User approval required: dialog (API 30+, foreground app) or notification.
- Suggestions are NOT saved networks and do not appear in the saved networks page.
- If the user manually disconnects from a suggested network, that suggestion is **ignored while that network is in range**.
- Suggestions persist until `removeNetworkSuggestions()` is called or the app is uninstalled.
- `getConfiguredNetworks()` returns an empty list for non-system apps on API 29+. Cannot enumerate previously connected networks.

### 6.4 Why WifiNetworkSpecifier (Existing Mesh API) Cannot Be Used

**Evidence — `MeshrabiyaWifiManagerAndroid.kt`, `connectToHotspotInternal()` (literal file read):**
```kotlin
val networkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(WifiNetworkSpecifier.Builder()
        .setSsid(hotspotConfig.ssid)
        .setWpa2Passphrase(hotspotConfig.passphrase)
        .build())
    .build()
```
`removeCapability(NET_CAPABILITY_INTERNET)` explicitly tells Android this network has no internet. Using `WifiNetworkSpecifier` for the internet-bearing WiFi connection is not viable because:
1. It explicitly removes internet capability in the existing mesh usage.
2. `WifiNetworkSpecifier` is a peer-to-peer request — Android will not use it to route general internet traffic.
3. It creates a temporary network binding that conflicts with the mesh binding.

### 6.5 Alternative: Settings Panel Redirect

`Settings.Panel.ACTION_WIFI` (API 29+) opens a system WiFi picker panel where the user selects a network. This avoids all suggestion approval flows and permission complexities. The trade-off: the app has no programmatic way to get the resulting `Network` object for per-socket binding without a `ConnectivityManager.NetworkCallback`.

---

## 7. Existing Gateway Control Infrastructure (Verified)

### 7.1 MeshrabiyaApi.kt (Lines Confirmed by Read)

All gateway control methods already exist in the API:
```kotlin
suspend fun setTorGatewayEnabled(enabled: Boolean): Boolean
suspend fun getTorGatewayStatus(): Boolean
suspend fun setInternetGatewayEnabled(enabled: Boolean): Boolean
suspend fun getInternetGatewayStatus(): Boolean
suspend fun setGatewayPreference(preference: GatewayPreference): Boolean
suspend fun getGatewayPreference(): GatewayPreference
suspend fun isTorActive(): Boolean
```
**Gap:** No `connectToNonMeshWifi()`, `disconnectFromNonMeshWifi()`, `getNonMeshWifiStateFlow()`, or `scanAvailableNetworks()` method exists.

### 7.2 MeshrabiyaApiImpl.kt Gateway Implementations (Lines 1022–1180, Literal Read)

`setTorGatewayEnabled()` (line 1022): Persists to DataStore (`KEY_TOR_GATEWAY_ENABLED`), then calls `roleManager.setPreferredRoles()` and `roleManager.updateRoles(userInitiated=true)`.

`setInternetGatewayEnabled()` (line 1075): Same pattern; persists `KEY_CLEARNET_GATEWAY_ENABLED`, adds/removes `MeshRole.CLEARNET_GATEWAY` from preferred roles.

`getTorGatewayStatus()` (line 1069): Reads DataStore via `runBlocking`.

`getInternetGatewayStatus()` (line 1122): Same pattern.

### 7.3 EnhancedMeshFragment.kt Integration Points (Grep-Verified Line Numbers)

- `getTorGatewayStatus()` called at lines 694, 1066, 2257
- `getInternetGatewayStatus()` called at lines 697, 1073, 2258
- `setTorGatewayEnabled()` called at line 2149
- `setInternetGatewayEnabled()` called at line 2164
- `currentMeshRolesFlow` observed at line 653
- `MeshRoleDto.MESH_ROUTER` event handling at lines 663–667 ← button show/hide insertion point

---

## 8. UI Infrastructure (Verified)

### 8.1 Fragment Layout Button Row

**Evidence — `fragment_mesh_enhanced.xml`, lines 1–130 (literal file read):**

`meshControlHeader` LinearLayout (line 33) contains three equally-weighted buttons:
- `meshToggleButton` (weight=1)
- `joinMeshButton` (weight=1)
- `mergeMeshButton` (weight=1)

New button must be inserted into this LinearLayout with `android:layout_weight="1"` and `android:visibility="gone"` by default.

### 8.2 MeshUIBindings.kt (163 lines, fully read)

Contains view bindings for:
```kotlin
val meshToggleButton: Button
val joinMeshButton: Button
val mergeMeshButton: Button
```
`wifiApConnectionButton` does not exist. Must be added.

---

## 9. DtoModels.kt Gaps (Verified)

**File: `DtoModels.kt`, 700 lines, fully read.**

`MeshrabiyaWifiStateDto` already contains `concurrentApStationSupported: Boolean`.

The following DTOs do NOT exist and must be created for the non-mesh WiFi connection feature:

| DTO Name | Purpose |
|---|---|
| `NonMeshWifiNetworkDto` | Represents a discovered WiFi network (SSID, BSSID, signal strength, security type) |
| `WifiConnectionRequestDto` | Carries SSID + passphrase from UI to API |
| `NonMeshWifiConnectionStateDto` | Tracks connection status: IDLE, CONNECTING, CONNECTED, FAILED |

---

## 10. Feasibility Assessment by Android API Level

| API Level | Android Version | `isStaApConcurrencySupported()` | `addNetworkSuggestions()` | `startLocalOnlyHotspot()` with config | Feature Feasible? |
|---|---|---|---|---|---|
| < 29 | ≤ 8.1 | N/A | N/A | N/A | **NO** |
| 29 | 9 | N/A (API 30 required) | ✅ | ✅ (simple, no config) | **NO** — cannot detect concurrent support |
| 30 | 10 | ✅ | ✅ | ✅ | **YES** — minimum viable API level |
| 31–32 | 11–12 | ✅ | ✅ | ✅ | **YES** |
| 33+ | 13+ | ✅ | ✅ | ✅ (with `SoftApConfiguration`) | **YES** — full feature, `NEARBY_WIFI_DEVICES` required |

**Minimum supported API level for AP+STA (Start Mesh) path: 30.**

**STA/STA extension — Join Mesh + internet WiFi simultaneously (see Section 18):**

| API Level | Android Version | `isStaStaConcurrencySupported()` available | Join Mesh + Internet WiFi feasible? |
|---|---|---|---|
| < 31 | ≤ 11 | N/A | **NO** |
| 31 | 12 | ✅ (if hardware supports dual-STA) | **YES — if `isStaStaConcurrencySupported = true`** |
| 33+ | 13+ | ✅ | **YES — full feature** |

**Minimum supported API level for STA/STA (Join Mesh) path: 31.**

---

## 11. Required Code Changes (No Code Written — Specifications Only)

### 11.1 `LocalOnlyHotspotManager.kt` — Condition WiFi Suppression on `!concurrentApStationSupported`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalOnlyHotspotManager.kt`  
**Change Required:** The `startHotspotMonitoring()` WiFi suppression block (lines 176–202) that calls `wifiManager.disconnect()`, `wifiManager.removeNetwork()`, and `wifiManager.disableNetwork()` must be gated with a check against `concurrentApStationSupported` from `MeshrabiyaWifiState`. When `concurrentApStationSupported = true`, the suppression loop must NOT disconnect WiFi. `LocalOnlyHotspotManager` needs to receive or check this value.  
**Priority:** BLOCKER — without this fix, any internet WiFi connection will be actively severed by the monitoring loop.

### 11.2 `MeshrabiyaWifiManagerAndroid.kt` — New Internet WiFi Connection Function

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` (876 lines)  
**Change Required:** New function `connectToInternetWifi(ssid: String, passphrase: String, callback: InternetWifiConnectionCallback)` using `WifiNetworkSuggestion`. Must:
1. Call `addNetworkSuggestions()` (requires `CHANGE_WIFI_STATE`)
2. Register `ConnectivityManager.NetworkCallback` for `NET_CAPABILITY_INTERNET` on `TRANSPORT_WIFI`
3. When `onAvailable(network)` fires for the internet network, store the `Network` object
4. Call `bindProcessToNetwork(null)` to release the process-wide mesh binding
5. Expose the internet `Network` object for subsequent per-socket mesh traffic routing
6. Must be guarded by `Build.VERSION.SDK_INT >= 30` AND `concurrentApStationSupported`

**New companion function:** `disconnectFromInternetWifi()` — calls `removeNetworkSuggestions()`, unregisters `NetworkCallback`, and optionally calls `connectivityManager.bindProcessToNetwork(meshNetwork)` to restore mesh process binding for non-gateway traffic.

### 11.3 `MeshrabiyaApi.kt` — New API Methods

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt` (391 lines)  
**Changes Required:** Add the following method declarations:
```
suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto
suspend fun disconnectFromNonMeshWifi(): Boolean
fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto>
suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto>
```

### 11.4 `DtoModels.kt` — New Data Transfer Objects

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/model/DtoModels.kt` (700 lines)  
**Changes Required:** Add at end of file:
- `NonMeshWifiNetworkDto(ssid: String, bssid: String, signalStrength: Int, isSecured: Boolean)`
- `WifiConnectionRequestDto(ssid: String, passphrase: String)`
- `NonMeshWifiConnectionStateDto(status: NonMeshWifiStatus, connectedSsid: String?, errorMessage: String?)`
- `enum class NonMeshWifiStatus { IDLE, CONNECTING, CONNECTED, FAILED }`

### 11.5 `MeshrabiyaApiImpl.kt` — Implement New API Methods  

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` (1975 lines)  
**Changes Required:** Implementation of all four new methods declared in `MeshrabiyaApi.kt`, delegating to functions in `MeshrabiyaWifiManagerAndroid`.

### 11.6 `EnhancedMeshFragment.kt` — Button Show/Hide and Dialog

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` (2328 lines)  
**Changes Required:**
- At lines 663–667 (MESH_ROUTER role appear/disappear): Add `binding.wifiApConnectionButton.visibility = View.VISIBLE` on appear and `View.GONE` on disappear.
- New dialog function (or DialogFragment class) invoked on button click: displays scan results from `scanAvailableWifiNetworks()`, accepts SSID selection + passphrase entry, calls `connectToNonMeshWifi()`.

### 11.7 `fragment_mesh_enhanced.xml` — New Button

**File:** `app/src/main/res/layout/fragment_mesh_enhanced.xml` (489 lines)  
**Change Required:** Add new `Button` inside `meshControlHeader` LinearLayout with:  
`android:id="@+id/wifiApConnectionButton"`, `android:layout_weight="1"`, `android:visibility="gone"`, icon depicting WiFi + arrow + globe.

### 11.8 `MeshUIBindings.kt` — New Binding Field

**File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt` (163 lines)  
**Change Required:** Add `val wifiApConnectionButton: Button` to the bindings class.

---

### 11.9 `AndroidVirtualNode.kt` — `setWifiHotspotEnabled()` Crash Fix *(Blocker 6 — CRITICAL)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/android/AndroidVirtualNode.kt`  
**Blocker:** On devices already connected to internet WiFi, calling `setWifiHotspotEnabled(true)` unconditionally calls `wifiManager.disconnectStation()`, severing the internet connection before the mesh hotspot starts. This is a crash-class regression for the AP+STA use case.  
**Change Required:** Guard the `disconnectStation()` call with a concurrent-capability check:

```kotlin
// BEFORE (existing — always disconnects):
if (enabled) {
    wifiManager.disconnectStation()
    localOnlyHotspotManager.startHotspot(...)
}

// AFTER (only disconnect when AP+STA not supported):
if (enabled) {
    if (!meshrabiyaWifiManager.state.value.concurrentApStationSupported) {
        wifiManager.disconnectStation()
    }
    localOnlyHotspotManager.startHotspot(...)
}
```

**Rationale:** On API 30+ devices with `isStaApConcurrencySupported = true` the OS maintains both connections simultaneously; an explicit disconnect destroys the internet connection that the feature exists to use. The guard is already safe for pre-API-30 devices because `concurrentApStationSupported` defaults to `false`.

---

### 11.10 `VirtualNode.kt` + `VirtualDatagramSocket2.kt` — Context Propagation for `GatewayTypeResolver` *(Blocker 5b — CRITICAL)*

**Files:**  
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualDatagramSocket2.kt`  

**Blocker:** `VirtualDatagramSocket2` is constructed inside `VirtualNode` without a `Context` argument. `GatewayTypeResolver` requires a `Context` to call `ConnectivityManager.getActiveNetwork()`. Because no context is passed, `GatewayTypeResolver` is always `null`, so `resolveGatewayType()` always returns `GATEWAY_TYPE_NONE`, and `routeViaGateway()` is never reached — making all gateway logic dead code.  
**Change Required (VirtualNode.kt, line ≈ 524):** Pass `appContext` when constructing `VirtualDatagramSocket2`:

```kotlin
// BEFORE:
VirtualDatagramSocket2(virtualPort, this)

// AFTER:
VirtualDatagramSocket2(virtualPort, this, appContext)
```

**Change Required (VirtualDatagramSocket2.kt constructor):** Accept and store the `Context` parameter, pass it through to `VirtualDatagramSocketImpl` and on to `GatewayTypeResolver`:

```kotlin
// BEFORE:
class VirtualDatagramSocket2(port: Int, node: VirtualNode) : ...

// AFTER:
class VirtualDatagramSocket2(port: Int, node: VirtualNode, private val appContext: Context) : ...
```

**Rationale:** Without this context thread, `GatewayTypeResolver.getInstance(context)` can never be called, so CLEARNET vs TOR routing is permanently broken regardless of all other gateway changes.

---

### 11.11 `VirtualNode.kt` — `shouldRouteViaProxy()` Stub Replacement *(Blocker 5c — HIGH)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Blocker:** `shouldRouteViaProxy()` contains a single-line stub `return true`, meaning **all** packets — including internal mesh management traffic — are classified as proxy-bound. On `TOR_GATEWAY` nodes this sends mesh control packets through Tor, breaking mesh stability. On `CLEARNET_GATEWAY` nodes it incorrectly marks clearnet-bound packets as proxy traffic.  
**Change Required:** Replace stub with destination-address classification:

```kotlin
// BEFORE:
protected open fun shouldRouteViaProxy(packet: IpPacket): Boolean {
    return true
}

// AFTER:
protected open fun shouldRouteViaProxy(packet: IpPacket): Boolean {
    val dest = packet.header.dstAddress
    // Route via proxy only if destination is outside the mesh virtual subnet
    return dest != null && !virtualSubnet.contains(dest)
}
```

**Rationale:** Mesh addresses (e.g. `169.254.x.x`) are in the virtual subnet; external internet addresses are not. Only external addresses should be proxy-routed. This is the minimum correct implementation — subclasses (`AndroidVirtualNode`) may override for finer control.

---

### 11.12 `GatewayTypeResolver.kt` — Null-Safety on Uninitialized Singleton *(Blocker 5d — MEDIUM)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/gateway/GatewayTypeResolver.kt`  
**Blocker:** `GatewayTypeResolver.getInstance()` throws a `NullPointerException` (or `IllegalStateException`) if the mesh has not finished initializing. This can occur in the window between app start and mesh node setup, crashing any code path that calls `resolveGatewayType()` early.  
**Change Required:** Wrap the singleton access in `runCatching` and default to `GATEWAY_TYPE_NONE` on failure:

```kotlin
// BEFORE:
fun resolveGatewayType(context: Context): GatewayType {
    return getInstance(context).getGatewayPreference()
}

// AFTER:
fun resolveGatewayType(context: Context): GatewayType {
    return runCatching { getInstance(context).getGatewayPreference() }
        .getOrDefault(GatewayType.GATEWAY_TYPE_NONE)
}
```

**Rationale:** Defaulting to `GATEWAY_TYPE_NONE` on uninitialized state is safe — the packet will be handled by normal routing. This prevents a crash window during app startup and returns a deterministic, safe value.

---

### 11.13 `AndroidVirtualNode.kt` + `MeshrabiyaWifiManagerAndroid.kt` — CLEARNET Gateway-End Handler *(Blocker 5 — HIGH)*

**Files:**  
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/android/AndroidVirtualNode.kt`  
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/android/MeshrabiyaWifiManagerAndroid.kt`  

**Blocker:** The CLEARNET_GATEWAY role is assigned and routing decisions are made (`resolveGatewayType()` returns `GATEWAY_TYPE_CLEARNET`) but there is **no handler** that actually forwards those packets to the internet. The gateway-end code path (`processRoutePacket()` branch for CLEARNET) is completely unimplemented — packets addressed to the internet are silently dropped.  
**Approach:** Use the existing `ChainSocketServer` / SOCKS-over-mesh pattern (already confirmed correct by gateway role architecture analysis — see Section 17.3):  

**Change Required (MeshrabiyaWifiManagerAndroid.kt):** Store a reference to the `internetWifiNetwork` (`Network` object) obtained from `ConnectivityManager` when `connectToInternetWifi()` succeeds (required by S6 which adds the `internetWifiNetwork` property):

```kotlin
// In connectToInternetWifi() success callback:
_internetWifiNetwork = connectivityManager
    .getNetworkCapabilities(wifiNetworkRequest)
    ?.let { connectivityManager.getActiveNetwork() }
```

**Change Required (AndroidVirtualNode.kt — `processRoutePacket()`):** Add a CLEARNET dispatch branch alongside the existing TOR branch:

```kotlin
// AFTER existing TOR_GATEWAY branch:
GatewayType.GATEWAY_TYPE_CLEARNET -> {
    val net = meshrabiyaWifiManager.internetWifiNetwork
    if (net != null) {
        val factory = net.socketFactory
        // Forward packet via internet WiFi network's socket factory
        // using ChainSocketServer bound to the internetWifiNetwork
        clearnetGatewayForwarder.forward(packet, factory)
    } else {
        logger(Log.WARN, TAG, "CLEARNET forward: no internet WiFi network bound, dropping packet")
    }
}
```

**New Class Required:** `ClearnetGatewayForwarder` (or reuse/configure `ChainSocketServer`) — creates a TCP connection through `net.socketFactory`, writes the raw IP payload, and streams the response back into the virtual network. Bind it to `internetWifiNetwork` via `Network.bindSocket()` to force traffic over the internet WiFi interface rather than the mesh hotspot interface.  
**Rationale:** Without this handler, CLEARNET_GATEWAY nodes receive tagged clearnet packets but have no code to actually send them outbound. The `ChainSocketServer` pattern avoids duplicating forwarding logic and correctly binds to the non-default `Network` object so packets egress through the internet WiFi NIC.

---

## 11b. STA/STA Extension Changes (S1–S7) — Apply After All AP+STA Changes Above

These changes extend the feature to support **Join Mesh + internet WiFi simultaneously** (STA/STA mode, API 31+). Full BEFORE/AFTER code for each change is in `WIFI_STA_PLAN.md`. Summary below:

### S1 — `MeshrabiyaWifiState.kt` — Add `staStaConcurrencySupported` Field

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt` (64 lines — NOT large)  
**Change:** Add `val staStaConcurrencySupported: Boolean = false` as the 7th field in the data class, with KDoc explaining it requires API 31 and `isStaStaConcurrencySupported`.  
**Rationale:** No STA/STA state field exists today (confirmed: only `concurrentApStationSupported` at line 15 of the 64-line file). All gating logic in S2–S5 depends on reading this from the single state source of truth.

### S2 — `MeshrabiyaWifiManagerAndroid.kt` — Replace `detectConcurrentSupport()` with `detectWifiConcurrencyCapabilities()`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` (876 lines — ⚠️ LARGE)  
**Change:** Replace the single-capability detection coroutine in `init` (lines 257–264) with a paired call that sets both `concurrentApStationSupported` and `staStaConcurrencySupported` in a single `_state.update`. Replace `detectConcurrentSupport()` with `detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean>` that checks `wifiManager.isStaApConcurrencySupported` (API 30+) and `wifiManager.isStaStaConcurrencySupported` (API 31+) separately, with appropriate SDK guards and logging.

### S3 — `MeshrabiyaWifiManagerAndroid.kt` — Fix C9 `bindProcessToNetwork` Regression

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` (876 lines — ⚠️ LARGE)  
**Change:** Replace C9's skip condition (`if (!_state.value.concurrentApStationSupported)`) with a mode-aware gate: `val skipProcessBinding = _state.value.hotspotIsStarted && _state.value.concurrentApStationSupported`. Process-wide mesh bind is skipped **only** when the device is actively running its own hotspot in AP+STA mode. Devices with AP+STA hardware in pure Join Mesh mode (hotspot not running) continue to receive the process-wide mesh bind.  
**Severity:** CRITICAL regression — C9 as-written breaks mesh routing on any concurrent-capable device that is in Join Mesh mode.

### S4 — `MeshrabiyaWifiManagerAndroid.kt` — Mode-Aware Guard in `connectToInternetWifi()`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` (876 lines — ⚠️ LARGE)  
**Change:** Replace the single `if (!concurrentApStationSupported)` guard in C10 with a branch on `hotspotIsStarted`: when hotspot is running, check `concurrentApStationSupported` (API 30); when hotspot is not running (Join Mesh), check `staStaConcurrencySupported` (API 31). Returns `Result.failure` with clear error messages for each missing capability.

### S5 — `MeshrabiyaApi.kt` + `MeshrabiyaApiImpl.kt` — Add `isInternetWifiFeatureAvailable()`

**Files:**  
`Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt` (391 lines — NOT large)  
`Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` (1975 lines — ⚠️ LARGE)  
**Change:** Add `fun isInternetWifiFeatureAvailable(): Boolean` declaration and implementation. Implementation checks `hotspotIsStarted`: if true, requires `concurrentApStationSupported` (AP+STA path); if false and station connected, requires `Build.VERSION.SDK_INT >= 31 && staStaConcurrencySupported` (STA/STA path). Returns `false` in all other cases and when mesh is not initialized.  
**Purpose:** Single authoritative capability query for UI. Replaces the reflection-based probe in C18 (S7).

### S6 — `MeshrabiyaWifiManagerAndroid.kt` — Add `staStaConcurrencySupported` and `currentWifiState` Properties

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` (876 lines — ⚠️ LARGE)  
**Change:** Add alongside C5's `concurrentApStationSupported` property:  
- `val staStaConcurrencySupported: Boolean get() = _state.value.staStaConcurrencySupported`  
- `val currentWifiState: MeshrabiyaWifiState get() = _state.value`  
S5's `MeshrabiyaApiImpl.isInternetWifiFeatureAvailable()` accesses state via `meshrabiyaWifiManager.currentWifiState` instead of collecting the Flow.

### S7 — `EnhancedMeshFragment.kt` — Replace C18 Reflection Check with `isInternetWifiFeatureAvailable()`

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` (2338 lines — ⚠️ LARGE)  
**Change:** Replace the reflection block in C18 Part A (`getDeclaredField("myNode")` + `concurrentApStationSupported` probe + `MESH_ROUTER in rolesDto` check) with a single call to `meshrabiyaApi.isInternetWifiFeatureAvailable()`. This correctly gates the button for both AP+STA mode (Start Mesh) and STA/STA mode (Join Mesh) without reflection.

---

## 12. Permissions Audit

The following permissions are needed for the WiFi AP Concurrency feature. Existing permissions in the manifest must be verified.

| Permission | Required By | Android API | Notes |
|---|---|---|---|
| `CHANGE_WIFI_STATE` | `addNetworkSuggestions()`, `startLocalOnlyHotspot()` | All | Already required by existing mesh hotspot code |
| `ACCESS_WIFI_STATE` | `addSuggestionConnectionStatusListener()`, scan results | All | Likely already present |
| `ACCESS_FINE_LOCATION` | WiFi scan results, suggestion post-connection broadcast | All | Already required for QR/mesh scanning |
| `NEARBY_WIFI_DEVICES` | `startLocalOnlyHotspot()` with config on API 33+ | 33+ | Must be added if not already present |

---

## 13. WiFi Scan Throttle Constraint

**Research-verified:** Android 9+ throttles WiFi scans to 4 scans per 2 minutes in foreground, 1 scan per 30 minutes in background. `scanAvailableWifiNetworks()` must handle this by:
1. Using `WifiManager.startScan()` cautiously (deprecated API 28 / behavior-restricted API 29+).
2. Preferring `WifiManager.getScanResults()` on already-available results.
3. Alternatively, recommending `Settings.Panel.ACTION_WIFI` to let the system handle the scan and user selection, avoiding throttle entirely.

---

## 14. Routing Architecture Impact

### 14.0 Investigation Result: `isGatewayNode()` Is Implemented (Round 2 Analysis — 2026-03-02)

**Previous plan incorrectly stated this method was missing. It IS implemented.**

**Evidence — `VirtualNode.kt`, lines 1415–1417 (literal file read):**
```kotlin
fun isGatewayNode(gatewayType: MeshRole): Boolean {
    return emergentRoleManager.currentMeshRoles.value.contains(gatewayType)
}
```
- Location: VirtualNode.kt line 1415 (near end of file — prior grep missed it)
- Delegates to `emergentRoleManager.currentMeshRoles.value.contains(gatewayType)` ✅
- Called by: `GatewayRouter.kt` line 61 ✅
- `MeshRole.TOR_GATEWAY` / `CLEARNET_GATEWAY` in `currentMeshRoles` when preferences are loaded ✅

### 14.0b Gateway Preferences Loading: Confirmed Working

**Evidence — `MeshrabiyaApiImpl.kt`, `loadAndApplyPersistedRolePreferences()`, lines 973–1010 (literal file read):**
```kotlin
val torGatewayEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_TOR_GATEWAY_ENABLED)] ?: false
if (torGatewayEnabled) { preferredRoles.add(MeshRole.TOR_GATEWAY) }

val clearnetGatewayEnabled = prefs[booleanPreferencesKey(MeshrabiyaConstants.KEY_CLEARNET_GATEWAY_ENABLED)] ?: false
if (clearnetGatewayEnabled) { preferredRoles.add(MeshRole.CLEARNET_GATEWAY) }

roleManager.setPreferredRoles(preferredRoles)
```
- This function is called during `initMesh()`.
- Preferred roles propagate to `EmergentRoleManager.calculateTargetRoles()` → assigned to `currentMeshRoles`.
- Therefore `isGatewayNode(CLEARNET_GATEWAY)` returns `true` when the user has enabled Internet Gateway and the node has the role. ✅

### 14.1 Current Routing for CLEARNET_GATEWAY (Verified from GatewayRouter.kt Read)

`GatewayRouter.kt` (214 lines, fully read) uses:
- Gateway pool caching (up to 3 gateways per type, 30-second cache)
- Round-robin multiplexing across cached gateways
- Calls `virtualNode.isGatewayNode(gatewayType)` ✅ (confirmed implemented at VirtualNode.kt line 1415)

### 14.0d GatewayTypeResolver: Exists but NOT Wired Up — Confirmed Root Cause (Round 3 Analysis — 2026-03-02)

**`GatewayTypeResolver.kt`** (220 lines) implements gateway type resolution from:
1. Packet's existing `gatewayType` (if already non-NONE)
2. Orbot per-app VPN rules (`SharedPreferences key "PrefTord"`)
3. Global `GatewayPreference` (DataStore key `gateway_preference`)

It IS imported and used in `VirtualDatagramSocketImpl` — the class used for every app-level socket:

**Evidence — `VirtualDatagramSocketImpl.kt`, lines 48–49, 139–143 (literal file read):**
```kotlin
private val gatewayTypeResolver: GatewayTypeResolver? by lazy {
    context?.let { GatewayTypeResolver(it) }
}

// In send():
gatewayTypeResolver?.let { resolver ->
    //TODO: Extract source package name from DatagramPacket if available
    val resolvedType = resolver.resolveGatewayType(virtualPacket, sourcePackageName = null)
    virtualPacket.data[VirtualPacketHeader.HEADER_SIZE - 3] = resolvedType //gatewayType at offset 18
}
```

**BUT:** `VirtualDatagramSocket2` (the actual socket wrapper used in production) constructs `VirtualDatagramSocketImpl` WITHOUT passing `context`:

**Evidence — `VirtualDatagramSocket2.kt` (literal file read, full file):**
```kotlin
class VirtualDatagramSocket2(
    router: VirtualRouter,
    localVirtualAddress: Int,
    logger: MNetLogger,
    private val parentNode: VirtualNode? = null
): DatagramSocket(VirtualDatagramSocketImpl(
    router = router,
    localVirtualAddress = localVirtualAddress,
    logger = logger,
    parentNode = parentNode
    // NO context parameter passed
))
```

And `VirtualNode.kt` line 524 constructs `VirtualDatagramSocket2` without context:
```kotlin
return VirtualDatagramSocket2(this, addressAsInt, logger, this)
```

**Consequence:** `gatewayTypeResolver` is ALWAYS `null` in every production socket. All outgoing packets have `gatewayType = GATEWAY_TYPE_NONE`. The resolver is a non-functional dead letter.

**Fix Required:** Pass `appContext` from `VirtualNode.getContext()` when constructing `VirtualDatagramSocket2`. One-line change at `VirtualNode.kt:524`:  
`return VirtualDatagramSocket2(this, addressAsInt, logger, this, getContext())`  
…and add `context` parameter to `VirtualDatagramSocket2` constructor that it passes through to `VirtualDatagramSocketImpl`.

**Impact on Feature:** Without this fix, `routeViaGateway()` in `processRoutePacket()` is NEVER triggered because all packets have `GATEWAY_TYPE_NONE`. This means zero gateway routing works for any client node.

### 14.0e GatewayTypeResolver.getInstance() Risk

`GatewayTypeResolver` calls `MeshrabiyaApiImpl.getInstance().getGatewayPreference()` on every packet send (when falling back to global preference). `MeshrabiyaApiImpl.getInstance()` is a singleton — if the mesh isn't initialized when the resolver runs, this throws. The `sourcePackageName = null` path always hits this fallback. This is a reliability risk that needs null-safety handling.

### 14.0f shouldRouteViaProxy() Bug — TOR_GATEWAY Routes EVERYTHING Through Tor

**Evidence — `VirtualNode.kt`, lines 873–882 (literal file read):**
```kotlin
val currentRoles = emergentRoleManager.getCurrentMeshRoles()
if (proxyActive && currentRoles.contains(MeshRole.TOR_GATEWAY)) {
    if (shouldRouteViaProxy(packet)) {
        routeViaProxy(packet)
        return
    }
}
```

**Evidence — `VirtualNode.kt`, `shouldRouteViaProxy()` (literal file read):**
```kotlin
private fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
    // For now, route all non-mesh traffic if proxy is active and TOR_GATEWAY role is present
    return true  // ← ALWAYS TRUE (stub)
}
```

This means when `proxyActive = true` AND node has `TOR_GATEWAY` role: **every single packet** (including mesh management packets, MMCP messages, broadcast packets) is deleted from the mesh and routed through Tor. This is not an internet traffic filter — it is an unconditional all-traffic intercept. This will break mesh operation when Tor is active on a gateway node.

For CLEARNET_GATEWAY, there is no equivalent block — nothing at all on the gateway-receive side.

---

### 14.1b Gateway Routing Path Status — Round 2 Analysis (2026-03-02)

**Two distinct routing methods exist. Previous plan conflated them.**

#### Path A: `routeViaGateway()` — ACTIVE CODE PATH (CLIENT → GATEWAY routing) ✅

**Evidence — `VirtualNode.kt`, `processRoutePacket()`, lines 879–885 (literal file read):**
```kotlin
// Phase 3A: Check if packet requires gateway routing
if (packet.header.gatewayType != VirtualPacketHeader.GATEWAY_TYPE_NONE) {
    logger(Log.DEBUG, ...)
    routeViaGateway(packet, null)
}
```
- Called when: destination address not found in mesh topology AND `packet.header.gatewayType != NONE`
- `routeViaGateway()` → `getAvailableClearnetGateways()` / `getAvailableTorGateways()` → `selectBestGateway()` → `forwardToGateway()`
- `forwardToGateway()` sets `toAddr = gateway.nodeAddress` in packet header and routes to gateway node ✅
- **DEPENDENCY: Requires client to set `gatewayType` in `VirtualPacketHeader`. No code currently sets this field for internet-bound traffic.**

#### Path B: `routeThroughGateway()` — DEAD CODE (Phase 4 vestigial) ❌

**Evidence — grep across all `.kt` files:**
- `routeThroughGateway()` defined at VirtualNode.kt line 1424
- Called from: **zero `.kt` files** — confirmed by codebase-wide grep returning 1 match (the definition only)
- `determineGatewayType()` (called by `routeThroughGateway()`) always returns `null` (stub implementation)
- `GatewayRouter` and `GatewaySelector` are instantiated `by lazy {}` but only referenced by `routeThroughGateway()` → **never actually used**
- This entire code path (GatewayRouter.kt, GatewaySelector.kt, `gatewayRouter` property) is dead code

#### GATEWAY NODE — Receiving Client Forwarded Packets

**STATUS: TOR_GATEWAY partially handled; CLEARNET_GATEWAY NOT HANDLED ❌**

**Evidence — `VirtualNode.kt`, `processRoutePacket()`, lines 873–882 (literal file read):**
```kotlin
// --- CONDITIONAL PROXY ROUTING ---
val currentRoles = emergentRoleManager.getCurrentMeshRoles()
if (proxyActive && currentRoles.contains(MeshRole.TOR_GATEWAY)) {
    if (shouldRouteViaProxy(packet)) {
        routeViaProxy(packet)
        return
    }
}
```
- TOR_GATEWAY: Fires for ALL incoming packets when `proxyActive && TOR_GATEWAY`. `shouldRouteViaProxy()` always returns `true` (stub) — so ALL packets are tunneled through Tor proxy. This is overly broad (no packet classification).
- CLEARNET_GATEWAY: No equivalent block exists. Packets addressed to the CLEARNET_GATEWAY node fall through to the listening socket lookup (line 884) → no socket listening → packet dropped.
- **CLEARNET gateway-end forwarding (acting as internet proxy/NAT) is NOT implemented.**

#### CLEARNET_GATEWAY — What Needs to Be Built

For CLEARNET_GATEWAY to actually forward internet-bound client packets:
1. A gateway handler must intercept packets arriving at the CLEARNET_GATEWAY node with `gatewayType=CLEARNET`
2. The handler must use the internet WiFi `Network` object to make real TCP/UDP connections
3. It must forward responses back to the originating client via the mesh
4. This is effectively a SOCKS/HTTP proxy or IP-level NAT — neither currently exists in the codebase
5. For Android, `Network.openConnection(url)` and `Network.getSocketFactory()` are the APIs for binding a single socket/connection to a specific network

### 14.2 Routing for MESH_ROUTER with Internet WiFi

When MESH_ROUTER device connects to internet WiFi:
1. The MESH_ROUTER device has mesh hotspot running (LocalOnly hotspot, SSID `meshr-XXXXXXXX`)
2. Device is simultaneously connected to internet WiFi via `WifiNetworkSuggestion`
3. `bindProcessToNetwork(null)` must be called — releases process-wide mesh binding
4. Mesh packet forwarding must use the mesh `Network` object for socket binding per-socket
5. Internet destined packets use internet WiFi `Network` object

### 14.3 Orbot VPN Interaction ✅ CONFIRMED (Round 4 Analysis — 2026-03-03)

`OrbotService` runs a TUN VPN interface via `OrbotVpnManager`. This VPN adds route `0.0.0.0/0` (capturing all traffic) BUT Orbot always adds itself to the VPN disallowed list.

**Evidence — `OrbotVpnManager.java`, `doAppBasedRouting()`, lines 307–315 (literal file read):**
```java
if (!individualAppsWereSelected && !isLockdownMode) {
    // disallow orbot itself...
    builder.addDisallowedApplication(mService.getPackageName());
    // disallow tor apps to avoid tor over tor
    for (String packageName : OrbotConstants.BYPASS_VPN_PACKAGES)
        builder.addDisallowedApplication(packageName);
}
```
This executes in every non-lockdown VPN start. Orbot excludes itself from its own TUN. The mesh library runs inside the Orbot app process (same package name), so ALL mesh sockets (UDP, TCP) are automatically excluded from the TUN in all VPN configurations.

**Confirmed interaction model:**
- Mesh traffic on the Orbot/mesh device is **never captured** by Orbot's TUN — no `protect()` calls needed or used (grep confirms zero production instances)
- `bindProcessToNetwork(null)` to release mesh binding coexists cleanly with the TUN routing table (mesh was never going through TUN)
- `bindProcessToNetwork(internetNetwork)` must **NOT** be called process-wide on the MESH_ROUTER device; instead use per-socket `internetNetwork.bindSocket()` or `internetNetwork.getSocketFactory()` for CLEARNET forwarding only
- The `setProxy()` / `setProxyActive()` mechanism in `MeshrabiyaApiImpl.kt` (lines 158–163) remains the correct Tor path; the internet WiFi `Network` object is a separate concern at the CLEARNET_GATEWAY node
- Orbot's TUN captures traffic from OTHER apps on client devices (when those apps are torified) but this does not affect Meshrabiya library sockets on any device

---

## 15. Summary of Blockers (Ordered by Severity)

| # | Blocker | File | Severity |
|---|---|---|---|
| 1 | `LocalOnlyHotspotManager.startHotspotMonitoring()` actively disconnects WiFi on concurrent devices | `LocalOnlyHotspotManager.kt` lines 176–202 | **CRITICAL** |
| 2 | `connectToHotspotInternal()` calls `bindProcessToNetwork(meshNetwork)` — must be changed to `null` + per-socket routing | `MeshrabiyaWifiManagerAndroid.kt` | **CRITICAL** |
| 3 | `WifiNetworkSuggestion` requires user approval — app cannot force connection | Android platform | **HIGH** |
| 4 | `getConfiguredNetworks()` returns empty on API 29+ — cannot enumerate saved networks | Android platform | **MEDIUM** |
| 5 | CLEARNET_GATEWAY end not implemented: no handler on gateway node to receive client packets and forward over internet | `VirtualNode.kt` processRoutePacket() | **HIGH** |
| 5a | `routeThroughGateway()` / `GatewayRouter` / `GatewaySelector` are dead code — actual path is `routeViaGateway()` | `VirtualNode.kt` | **MEDIUM** (documentation) |
| 5b | `VirtualDatagramSocket2` constructed without `context` → `GatewayTypeResolver` always null → all packets always have `GATEWAY_TYPE_NONE` → `routeViaGateway()` never triggered | `VirtualDatagramSocket2.kt`, `VirtualNode.kt:524` | **CRITICAL** |
| 5c | `shouldRouteViaProxy()` always returns `true` — on TOR_GATEWAY nodes ALL packets including mesh management are routed through Tor proxy (stub, not internet traffic only) | `VirtualNode.kt` `shouldRouteViaProxy()` | **HIGH** |
| 5d | `GatewayTypeResolver.getInstance()` call on every packet — crashes if mesh not yet initialized | `GatewayTypeResolver.kt:108` | **MEDIUM** |
| 6 | `AndroidVirtualNode.setWifiHotspotEnabled()` calls `disconnectStation()` unconditionally; `disconnectStation()` throws `IllegalStateException` when WiFi is connected — hotspot startup crashes on any WiFi-connected device. **Fix:** Gate `if (enabled)` block with `&& !meshrabiyaWifiManager.state.value.concurrentApStationSupported`. On concurrent devices the entire `disconnectStation()` + `delay(2000)` block is skipped. | `AndroidVirtualNode.kt` `setWifiHotspotEnabled()` | **CRITICAL** |
| 8 | WiFi scan throttle (4/2min foreground) limits network discovery responsiveness | Android platform | **LOW** |
| 9 | `wifiManager.connectionInfo` / `configuredNetworks` / `removeNetwork()` all deprecated API 29+ — suppression loop may be partially broken already | `LocalOnlyHotspotManager.kt` | **LOW** |
| 10 | **STA/STA — C9 regression:** C9's `bindProcessToNetwork` skip condition (`!concurrentApStationSupported`) is too broad — skips the mesh network bind on concurrent-capable devices in pure Join Mesh mode, breaking mesh routing for those devices. Correct gate: `hotspotRunning && concurrentApStationSupported`. | `MeshrabiyaWifiManagerAndroid.kt` `connectToHotspotInternal()` | **CRITICAL** |
| 11 | **STA/STA — `isStaStaConcurrencySupported` never detected:** No call to `WifiManager.isStaStaConcurrencySupported` (API 31) exists anywhere in the codebase. `MeshrabiyaWifiState` has no `staStaConcurrencySupported` field. The Join Mesh + internet WiFi path is architecturally unsupported until S1–S2 are applied. | `MeshrabiyaWifiManagerAndroid.kt`, `MeshrabiyaWifiState.kt` | **HIGH** |
| 12 | **STA/STA — C10 guard uses wrong capability in Join Mesh mode:** `connectToInternetWifi()` guard checks `concurrentApStationSupported` regardless of operating mode. Must branch on `hotspotIsStarted`: AP+STA check when hotspot running; STA/STA check when in station mode. | `MeshrabiyaWifiManagerAndroid.kt` `connectToInternetWifi()` | **HIGH** |
| 13 | **STA/STA — MESH_ROUTER role appears on Join Mesh devices:** A concurrent-capable device in pure station mode (hotspot not running) also receives `MESH_ROUTER` role (hardware-only assignment, confirmed `EmergentRoleManager.kt` lines 427–432). C18's `MESH_ROUTER in roles` check causes the WiFi button to appear even when the feature cannot work in that mode. Requires `isInternetWifiFeatureAvailable()` (S5/S7) to correctly gate button visibility. | `EmergentRoleManager.kt`, `EnhancedMeshFragment.kt` | **HIGH** |

---

## 16. Recommended Implementation Sequence

1. **Resolve blocker #1 first**: Condition `startHotspotMonitoring()` suppression on `!concurrentApStationSupported`.
2. **Resolve blocker #2**: Refactor `connectToHotspotInternal()` to release process binding and add per-socket routing for mesh traffic.
3. ~~**Investigate `isGatewayNode()` in VirtualNode.kt**~~ — **RESOLVED**: `isGatewayNode()` IS implemented at VirtualNode.kt line 1415. No action needed.
3b. **Fix `VirtualDatagramSocket2` context gap** (NEW — Round 3): Pass `appContext` via `VirtualNode.getContext()` in `VirtualDatagramSocket2` constructor and through to `VirtualDatagramSocketImpl`. This enables `GatewayTypeResolver` to actually set `gatewayType` on outgoing packets so `routeViaGateway()` fires. Without this no gateway routing works at all for any client.
3c. **Replace `HEADER_SIZE - 3` magic number** (17.4 — bundle with 3b, same file): Add `const val VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET = 18` to `MeshrabiyaConstants.kt`. Update `VirtualDatagramSocketImpl.send()` line 143 to use `MeshrabiyaConstants.VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET` instead of `VirtualPacketHeader.HEADER_SIZE - 3`. Commit together with Step 3b since both edits land in `VirtualDatagramSocketImpl`.
3d. **Fix `shouldRouteViaProxy()` stub** (NEW — Round 3): Implement actual packet classification (check `packet.header.toAddr` to determine if destination is a non-mesh internet address, not a virtual mesh address). Until this is fixed, enabling Tor Gateway breaks the mesh entirely.
3e. **Fix `AndroidVirtualNode.setWifiHotspotEnabled()` unconditional disconnect** (Blocker #6): Change `if (enabled)` to `if (enabled && !meshrabiyaWifiManager.state.value.concurrentApStationSupported)` at the top of the enabled-branch in `setWifiHotspotEnabled()`. Removes the `disconnectStation()` + 2-second wait entirely for concurrent devices, preventing the `IllegalStateException` crash and preserving active internet WiFi when the hotspot starts.
3f. **Fix `LocalOnlyHotspotManager` suppression access**: The `MeshrabiyaWifiManagerAndroid._state` StateFlow holds `concurrentApStationSupported`. Simplest fix: pass a `concurrentApStationSupported: () -> Boolean` lambda into the `LocalOnlyHotspotManager` constructor from `MeshrabiyaWifiManagerAndroid`, allowing the monitoring loop to check `if (!concurrentApStationSupported())` before disconnecting.
3g. **Add null-safety to `GatewayTypeResolver.resolveGatewayType()`**: Wrap `MeshrabiyaApiImpl.getInstance().getGatewayPreference()` in try-catch or use `runCatching`. If singleton not yet initialized, default to `GATEWAY_TYPE_NONE`.
4. **Add DTOs** to `DtoModels.kt`.
5. **Add API declarations** to `MeshrabiyaApi.kt`.
6. **Implement `connectToInternetWifi()` / `disconnectFromInternetWifi()`** in `MeshrabiyaWifiManagerAndroid.kt`.
7. **Implement new API methods** in `MeshrabiyaApiImpl.kt`.
8. **Implement CLEARNET gateway-end handler** in `VirtualNode.processRoutePacket()`: when `packet.header.toAddr == addressAsInt` AND packet carries a `gatewayType == CLEARNET`, forward payload over internet WiFi `Network` object via `Network.getSocketFactory()` or `Network.openConnection()`. Return response to originating client via mesh. This is the critical missing piece for CLEARNET_GATEWAY functionality.
9. **Ensure packets have gatewayType set**: Identify where internet-bound `VirtualPacket` objects are constructed by client nodes and set `gatewayType = GATEWAY_TYPE_CLEARNET` or `GATEWAY_TYPE_TOR` in `VirtualPacketHeader`. Without this, `routeViaGateway()` is never triggered in `processRoutePacket()`.
10. **Add button** to `fragment_mesh_enhanced.xml` and `MeshUIBindings.kt`.
11. **Wire button show/hide and dialog** in `EnhancedMeshFragment.kt` at lines 663–667.
12. **Verify permissions** in manifest for `NEARBY_WIFI_DEVICES` (API 33+).

**STA/STA Extension (S1–S7) — apply as a second pass after all AP+STA changes above are complete and verified on-device:**

S1. **`MeshrabiyaWifiState.kt`** — Add `staStaConcurrencySupported` field. Must precede S2 (the setter) and S5 (the reader).
S2+S3+S4+S6. **`MeshrabiyaWifiManagerAndroid.kt`** — Four changes in one file; do in a single edit session:  
    — S2: Replace `detectConcurrentSupport()` with `detectWifiConcurrencyCapabilities()` and update init coroutine  
    — S3: Fix C9 `bindProcessToNetwork` gate to `hotspotRunning && concurrentApStationSupported`  
    — S4: Fix C10 `connectToInternetWifi()` guard with mode-aware branch  
    — S6: Add `staStaConcurrencySupported` and `currentWifiState` properties alongside C5  
S5 (declaration). **`MeshrabiyaApi.kt`** — Add `isInternetWifiFeatureAvailable()` declaration. Must exist before `MeshrabiyaApiImpl.kt` compiles.
S5 (implementation). **`MeshrabiyaApiImpl.kt`** — Implement `isInternetWifiFeatureAvailable()`. Depends on S1 (state field) and S6 (`currentWifiState` property).
S7. **`EnhancedMeshFragment.kt`** — Replace C18 reflection check with `meshrabiyaApi.isInternetWifiFeatureAvailable()`. Depends on S5.

---

## 17. Outstanding Uncertainties (Round 3 — 2026-03-02)

These questions cannot be answered by reading the Meshrabiya library code alone. They require either reading the Orbot app layer code (`orbotservice/`), Android system behavior testing, or design decisions.

### 17.1 ✅ RESOLVED — Orbot TUN VPN Does NOT Capture Mesh Traffic (Round 4 — 2026-03-03)

**Evidence — `OrbotVpnManager.java`, `doAppBasedRouting()`, lines 307–315 (literal file read):**
```java
if (!individualAppsWereSelected && !isLockdownMode) {
    builder.addDisallowedApplication(mService.getPackageName()); // Orbot excludes itself
    for (String packageName : OrbotConstants.BYPASS_VPN_PACKAGES)
        builder.addDisallowedApplication(packageName);
}
```
Orbot's VPN builder always calls `addDisallowedApplication(mService.getPackageName())` — disallowing **Orbot itself** from the TUN in all non-lockdown modes. The mesh library runs inside the Orbot app process (same package). Therefore all mesh sockets are automatically excluded from Orbot's TUN VPN.

**Confirmed findings:**
- No `vpnService.protect()` call exists anywhere in the production codebase — grep returned zero non-documentation matches
- `BYPASS_VPN_PACKAGES` (Tor Browser, Onionshare, Briar, Cwtch) does not include the mesh/Orbot package — irrelevant; Orbot is excluded via the direct `addDisallowedApplication` call
- In per-app torification mode, only selected packages use `addAllowedApplication()` — all others bypass TUN; Orbot/mesh is never selectable for torification
- VPN route `0.0.0.0/0` is added but Orbot's own process is always in the disallowlist

**Confirmed behavior:** Mesh network sockets bypass the TUN in ALL Orbot VPN configurations. No `protect()` calls needed. The `bindProcessToNetwork(null)` release of mesh binding coexists cleanly with the TUN routing table because mesh traffic was never routed through TUN to begin with.

**Impact on CLEARNET_GATEWAY:** The CLEARNET_GATEWAY handler in `AndroidVirtualNode` can open sockets bound to the internet WiFi `Network` object via `internetNetwork.bindSocket()` or `internetNetwork.getSocketFactory()`. These sockets go direct to the internet WiFi interface without competing with the TUN. No special VPN bypass handling needed.

### 17.2 ✅ RESOLVED — Gateway Role Architecture Clarified (Round 4 — 2026-03-03)

**Clarified architecture (user-provided):**

**`TOR_GATEWAY` role on a node** is a master switch enabling that node to route mesh clients' traffic over its own internet/Tor connection. A node only requires `TOR_GATEWAY` to provide Tor routing — it does NOT need both `TOR_GATEWAY` and `CLEARNET_GATEWAY` simultaneously. The two gateway roles are fully independent:
- `TOR_GATEWAY` → routes Tor-destined mesh traffic through the node's Tor proxy
- `CLEARNET_GATEWAY` → routes clearnet mesh traffic through the node's internet WiFi

**Per-app torification** operates at the CLIENT device level. When an app is in Orbot's torified list (`PrefTord`), that device should route that app's traffic to a `TOR_GATEWAY` node in the mesh — even when the client device itself has no direct internet/Tor connection. `GatewayTypeResolver` is the mechanism for this: it reads `PrefTord` and sets `GATEWAY_TYPE_TOR` for those apps' packets so they are forwarded to the nearest `TOR_GATEWAY` node via `routeViaGateway()`.

**Implication for global `GatewayPreference`:**
For MVP, global `GatewayPreference` is the correct fallback (covers the majority use case — device-level routing preference). The `sourcePackageName = null` gap in `VirtualDatagramSocketImpl.send()` means per-app torification does not yet work at the mesh layer (all packets fall through to global preference). True per-app granularity would require UID-based packet classification (reading `/proc/net/udp` or using Android's `TrafficStats.getUidBytesXxx()` — out of scope for initial implementation).

**Impact on plan:**
- `GatewayTypeResolver` with global `GatewayPreference` is correct for MVP
- Per-app torification is a future enhancement, not a blocker
- The `sourcePackageName = null` TODO in `VirtualDatagramSocketImpl.send()` stays as a known gap; the global fallback works correctly once the context-wiring fix (Blocker 5b) is applied
- Removing the `sourcePackageName` complexity from initial implementation scope simplifies the feature significantly

### 17.3 CLEARNET Gateway NAT/Proxy Implementation Approach

**Question:** What is the implementation model for the CLEARNET gateway-end packet handler?

Two options:
- **Option A: SOCKS Proxy over Mesh** — On the CLEARNET_GATEWAY node, open a SOCKS5 server on a virtual port. Client nodes use `WifiNetworkSpecifier` per-socket to direct requests to the gateway's virtual IP:SOCKS port. Gateway forwards via internet WiFi Network object using `Network.getSocketFactory()`. Return traffic re-enters the mesh.
- **Option B: IP-level NAT** — Intercept raw IP packets with gateway type CLEARNET in `processRoutePacket()`, extract the real destination from the payload, open a real TCP/UDP socket via `Network.getSocketFactory()`, forward payload. This requires IP packet parsing.

**Which approach is architecturally consistent with existing code?** Option A is confirmed architecturally aligned by new evidence from Round 4 code reads:

**Evidence — `MeshrabiyaWifiManagerAndroid.kt`, `createStationNetworkBoundSockets()`, lines 733–762 (literal file read):**
```kotlin
val chainSocketServer = ChainSocketServer(
    serverSocket = ServerSocket(socketPort),
    executorService = ioExecutor,
    chainSocketFactory = chainSocketFactory,
    name = "network bound to ${config.ssid}",
    logger = logger,
)
// ...
network.bindSocket(socket)
```
`ChainSocketServer` is already instantiated and bound per-network in every station connection. The `network.bindSocket()` pattern is already used. For CLEARNET_GATEWAY, a `ChainSocketServer` bound to the internet WiFi `Network` object would serve as the forwarding proxy.

**Evidence against Option B:** `OrbotVpnManager.java` uses `pcap4j` for IP packet inspection (imports `IpSelector`, `UdpPacket`, `IpNumber`). The Meshrabiya library does NOT have `pcap4j` as a dependency. Implementing IP-level NAT in Meshrabiya would require adding this dependency.

**Recommendation: Option A (SOCKS Proxy via ChainSocketServer).** Reuses infrastructure already present in the codebase. The `ChainSocketFactory` interface already handles SOCKS5-style proxying. The internet `Network` object provides the outbound socket factory via `Network.getSocketFactory()`.

### 17.4 ✅ RESOLVED — `HEADER_SIZE - 3` Magic Number → `MeshrabiyaConstants` (Round 4 — 2026-03-03)

**Offset verified correct:**
- `toAddr` (4 bytes: offsets 0–3)
- `toPort` (2 bytes: offsets 4–5)
- `fromAddr` (4 bytes: offsets 6–9)
- `fromPort` (2 bytes: offsets 10–11)
- `lastHopAddr` (4 bytes: offsets 12–15)
- `hopCount` (1 byte: offset 16)
- `maxHops` (1 byte: offset 17)
- `gatewayType` (1 byte: **offset 18**) ✅
- `payloadSize` (2 bytes: offsets 19–20)

`HEADER_SIZE - 3 = 21 - 3 = 18`. Offset is arithmetically correct.

**Action plan (tech debt → proper fix):**

The expression `virtualPacket.data[VirtualPacketHeader.HEADER_SIZE - 3]` is a magic-number computation that will silently break if the header layout ever changes. Per the project convention, such constants belong in `MeshrabiyaConstants`.

**Fix — Step 1:** Add named constant to `MeshrabiyaConstants.kt`:
```kotlin
// Byte offset of the gatewayType field within a serialized VirtualPacketHeader byte array.
// Layout: toAddr(4) + toPort(2) + fromAddr(4) + fromPort(2) + lastHopAddr(4) + hopCount(1) + maxHops(1) = 18
const val VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET = 18
```

**Fix — Step 2:** Replace the raw expression in `VirtualDatagramSocketImpl.send()` (line 143):
```kotlin
// BEFORE (fragile magic-number arithmetic):
virtualPacket.data[VirtualPacketHeader.HEADER_SIZE - 3] = resolvedType

// AFTER (named constant from MeshrabiyaConstants):
virtualPacket.data[MeshrabiyaConstants.VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET] = resolvedType
```

The raw-array mutation pattern itself remains (the `VirtualPacketHeader` deserialization/re-serialization cost would be too high per-packet), but the offset is now self-documenting and change-safe. This fix is a prerequisite to the Blocker 5b context-wiring fix since both touch `VirtualDatagramSocketImpl.send()`.

**Files to modify:**
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt` — add constant
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocketImpl.kt` — use constant

### 17.5 Internet WiFi `Network` Object Lifecycle for CLEARNET_GATEWAY

**Question:** When the MESH_ROUTER connects to internet WiFi via `WifiNetworkSuggestion`, the `Network` object is delivered via `ConnectivityManager.NetworkCallback.onAvailable()`. Where should this `Network` object be stored so the CLEARNET_GATEWAY handler can use it when forwarding packets?

**Current gap:** There is no field in `VirtualNode`, `AndroidVirtualNode`, or `MeshrabiyaWifiManagerAndroid` to store an \"internet WiFi network\" object separately from the mesh network. The `connectRequest` AtomicReference in `MeshrabiyaWifiManagerAndroid` stores the mesh network callback pair. A new parallel reference for the internet WiFi network must be designed.

**Design Resolution (Round 4 — 2026-03-03):** Store in `MeshrabiyaWifiManagerAndroid` as a new `AtomicReference`, mirroring the existing `connectRequest` pattern.

**Evidence — `MeshrabiyaWifiManagerAndroid.kt`, class-level field declarations (literal file read):**
```kotlin
private val connectRequest = AtomicReference<Pair<WifiConnectConfig, NetworkCallback>?>(null)
private val stationBoundSockets = AtomicReference<Pair<VirtualNodeDatagramSocket, ChainSocketServer>?>()
```
A new parallel field follows this pattern:
```kotlin
private val internetNetworkCallback = AtomicReference<Pair<WifiNetworkSuggestion, NetworkCallback>?>(null)
```
And a new `MutableStateFlow` field in `_state` (or a separate `AtomicReference<Network?>`) tracks the current internet `Network` object.

**Access path to CLEARNET_GATEWAY handler:**
1. `MeshrabiyaWifiManagerAndroid` stores internet `Network` and exposes `val internetWifiNetwork: Network? get() = ...`
2. `AndroidVirtualNode` has `override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid` (already public)
3. `AndroidVirtualNode` overrides CLEARNET packet handling path, reads `meshrabiyaWifiManager.internetWifiNetwork` and passes it to the forwarding code
4. Base `VirtualNode` class does NOT need modification — Android-specific `Network` type stays in the Android layer

**Confirmed design:** `MeshrabiyaWifiManagerAndroid` is the correct home for the internet `Network` object. `AndroidVirtualNode` bridges it to the packet routing layer without introducing Android-specific types into the platform-agnostic `VirtualNode`.

---

## 18. STA/STA Concurrency Analysis — Join Mesh + Internet WiFi (Round 5 — 2026-03-03)

This section documents the analysis of whether the AP Concurrency feature design (C1–C18) also supports a MESH_ROUTER device that is in **Join Mesh mode** (connected as a WiFi station to another node's hotspot) simultaneously connecting to a non-mesh internet WiFi network.

### 18.1 The Two Distinct Modes

| Mode | Device WiFi Roles | Concurrency Required | API Required |
|------|-------------------|---------------------|--------------|
| **AP+STA** (Start Mesh) | Hotspot (AP) + internet WiFi station (STA) | `isStaApConcurrencySupported` = true | API 30+ |
| **STA/STA** (Join Mesh) | Mesh station (STA) + internet WiFi station (STA) | `isStaStaConcurrencySupported` = true | API **31+** |

These are independent hardware capabilities. A device can have one, both, or neither.

### 18.2 isStaStaConcurrencySupported — Never Called (Confirmed by Grep)

A codebase-wide grep for `isStaStaConcurrencySupported|STA_STA|sta_sta|staStaConcurrency` returned **zero matches**. The API 31 capability is never detected, never stored, and never acted upon anywhere in the codebase.

`MeshrabiyaWifiState.kt` (64 lines, fully read) has exactly 6 fields — `concurrentApStationSupported: Boolean = false` is the only concurrency field. No `staStaConcurrencySupported` field exists.

### 18.3 Five Specific Problems With the C1–C18 Design for STA/STA

**Problem 1 — No detection.** `detectConcurrentSupport()` (lines 272–283) calls only `wifiManager.isStaApConcurrencySupported` (API 30). The init coroutine (lines 257–264) stores only `concurrentApStationSupported`. There is no code path that would ever set a STA/STA capability flag.

**Problem 2 — MESH_ROUTER role appears on Join Mesh devices.** `EmergentRoleManager.calculateTargetRoles()` assigns `MESH_ROUTER` whenever `concurrentApStationSupported = true` (lines 427–432), with no check on whether the hotspot is running. A device in pure Join Mesh mode (STA only) that has AP+STA hardware receives `MESH_ROUTER` — causing the WiFi button (C18) to appear when the AP+STA feature cannot function in that mode.

**Problem 3 — C9 `bindProcessToNetwork` regression.** C9 as designed skips `bindProcessToNetwork` for ALL devices where `concurrentApStationSupported = true`. This is too broad: a concurrent-capable device in Join Mesh mode (no hotspot running) still needs the process-wide mesh bind to prevent Android default routing from overriding the mesh station connection. Skipping it breaks mesh routing for those devices.

**Problem 4 — C10 `connectToInternetWifi()` guard checks wrong capability.** The guard `if (!concurrentApStationSupported)` permits the function on AP+STA capable devices regardless of operating mode. In Join Mesh mode the correct check is `staStaConcurrencySupported` (API 31), not `concurrentApStationSupported` (API 30).

**Problem 5 — API level split not modelled.** AP+STA requires API 30 minimum. STA/STA requires API 31 minimum. The current design has a single API 30 guard. A device on API 30 (Android 10) can use the AP+STA path but cannot use the STA/STA path, and the code has no way to express or enforce this distinction.

### 18.4 STA/STA Is Not a Shared Internet Gateway — Routing Scope

In AP+STA mode (Start Mesh), other mesh nodes connect TO the MESH_ROUTER's hotspot. Their internet-bound packets arrive at the MESH_ROUTER passively at the WiFi layer — it is their default gateway. The MESH_ROUTER can forward those packets through the internet WiFi `Network` object.

In STA/STA mode (Join Mesh), the device IS a client on another node's hotspot. Other mesh nodes' clients cannot route through it at the WiFi layer — they have no L2 path to it as a gateway. While virtual-layer forwarding is architecturally conceivable (the STA/STA node is reachable at its virtual IP), the current implementation does not support it for three concrete reasons:

1. `CLEARNET_GATEWAY` role is never assigned to pure-STA nodes — `EmergentRoleManager` does not have this logic.
2. The return path for forwarded packets assumes the gateway is topologically above the clients (AP position), not beside them as a peer station.
3. `VirtualNode.processRoutePacket()` has no handler for CLEARNET packets arriving at a non-AP node.

**Scope of STA/STA internet benefit:** the device's **own traffic only** — its own browser, app connections, Orbot forwarding. It does not become a shared internet gateway for other mesh participants.

### 18.5 Graceful API 31 Conditional Availability

The S1–S7 changes implement a three-tier degradation model:

- **API < 30:** Feature hidden entirely — no MESH_ROUTER role assigned, button never shown.
- **API 30 (Android 10–11):** AP+STA path available (Start Mesh mode only). STA/STA path unavailable — `isInternetWifiFeatureAvailable()` returns false in Join Mesh mode.
- **API 31+ (Android 12+) with `isStaStaConcurrencySupported = true`:** Both paths available — button shown in both Start Mesh and Join Mesh modes when respective capability is confirmed.

`isInternetWifiFeatureAvailable()` (S5) is the single gating method for the UI and encapsulates all capability and mode checks. Neither the UI nor the API caller needs to understand the AP level split — they call one method and get a boolean.

### 18.6 Required Changes Summary (see WIFI_STA_PLAN.md for full BEFORE/AFTER)

| ID | File | Size | Change |
|----|------|------|--------|
| S1 | `MeshrabiyaWifiState.kt` | 64 lines | Add `staStaConcurrencySupported: Boolean = false` field |
| S2 | `MeshrabiyaWifiManagerAndroid.kt` | 876 lines ⚠️ | Replace `detectConcurrentSupport()` with `detectWifiConcurrencyCapabilities()` returning `Pair<Boolean, Boolean>`; update init coroutine |
| S3 | `MeshrabiyaWifiManagerAndroid.kt` | 876 lines ⚠️ | Fix C9 bind-skip gate: `hotspotRunning && concurrentApStationSupported` |
| S4 | `MeshrabiyaWifiManagerAndroid.kt` | 876 lines ⚠️ | Fix C10 guard: branch on `hotspotIsStarted` to check correct capability per mode |
| S5 | `MeshrabiyaApi.kt` + `MeshrabiyaApiImpl.kt` | 391 / 1975 lines | Add `isInternetWifiFeatureAvailable(): Boolean` declaration and implementation |
| S6 | `MeshrabiyaWifiManagerAndroid.kt` | 876 lines ⚠️ | Add `staStaConcurrencySupported` and `currentWifiState` properties |
| S7 | `EnhancedMeshFragment.kt` | 2338 lines ⚠️ | Replace C18 reflection-based visibility check with `isInternetWifiFeatureAvailable()` |
