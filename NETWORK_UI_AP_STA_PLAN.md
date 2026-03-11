# NETWORK_UI_AP_STA_PLAN.md

**Date:** 2026-03-04  
**Status:** Implementation in progress — Phases 1–3 (small files) complete as of 2026-03-04. Phases 4–7 (LARGE files) require manual BEFORE/AFTER edits.  
**Constraint:** No assumptions. Every claim backed by file path + line number evidence from literal reads.

---

## 1. Goals

1. **WiFi button disable logic** — Disable `wifiApConnectionButton` when the device's STA connection is already in use for the mesh and no concurrent second-STA or AP+STA path is available. Note: a MESH_ROUTER device that joins the mesh (STA to mesh AP + its own hotspot running) CAN still use the WiFi button via AP+STA.
2. **Internet connectivity detection** — In `connectToInternetWifi()`, detect whether the new internet WiFi network has real internet access (NET_CAPABILITY_VALIDATED). Propagate this flag to the UI via API/DTO patterns.
3. **Mesh IP row chips** — Replace the `nodeInfoText` TextView with a horizontal layout row: IP address text + "Mesh" chip always + "STA" chip if device is connected as a station to the mesh + "AP" chip if device is running its own hotspot.
4. **Internet WiFi row** — When an internet WiFi connection is active (via the WiFi button), show a second row in the Network Information Card: internet IP + "STA" chip always + "Web" chip if `NET_CAPABILITY_VALIDATED` is confirmed.

---

## 2. Verified Current State of All Touched Files

### 2.1 File Inventory

| File | Absolute Path | Lines | Large? |
|------|--------------|-------|--------|
| `WifiRole.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/WifiRole.kt` | ~10 | NOT |
| `WifiStationState.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/WifiStationState.kt` | ~35 | NOT |
| `MeshrabiyaWifiState.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt` | 64 | NOT |
| `DtoModels.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt` | 700 | NOT |
| `MeshrabiyaApi.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt` | 440 | NOT |
| `MeshrabiyaWifiManagerAndroid.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` | 1087 | ⚠️ LARGE |
| `MeshrabiyaApiImpl.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` | 2062 | ⚠️ LARGE |
| `fragment_mesh_enhanced_deferred.xml` | `app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml` | 546 | NOT |
| `MeshUIBindings.kt` | `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt` | 165 | NOT |
| `EnhancedMeshFragment.kt` | `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` | 2419 | ⚠️ LARGE |

---

### 2.2 Goal 1 Analysis — WiFi Button Disable Logic

**Verified fact:** `isInternetWifiFeatureAvailable()` in `MeshrabiyaApiImpl.kt` at lines 2047–2062 (literal read):

```kotlin
override fun isInternetWifiFeatureAvailable(): Boolean {
    val node = myNode ?: return false
    val wifiState = node.meshrabiyaWifiManager.currentWifiState
    if (wifiState.hotspotIsStarted && wifiState.concurrentApStationSupported) {
        return true
    }
    if (!wifiState.hotspotIsStarted &&
        wifiState.wifiStationState.status == WifiStationState.Status.AVAILABLE &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        wifiState.staStaConcurrencySupported) {
        return true
    }
    return false
}
```

**Verified fact:** In `EnhancedMeshFragment.kt` at lines 714–718 (literal read):

```kotlin
val featureAvailable = meshrabiyaApi.isInternetWifiFeatureAvailable()
MeshUIBindings.wifiApConnectionButton.visibility =
    if (featureAvailable) View.VISIBLE else View.GONE
```

**Verified fact:** `MESH_ROUTER` is assigned in `EmergentRoleManager.kt` at lines 427–430 (literal read):
```kotlin
// MESH_ROUTER: assign whenever AP+Station concurrency support is true
if (concurrentApStationSupported) {
    roles.add(MeshRole.MESH_ROUTER)
```
MESH_ROUTER requires `concurrentApStationSupported = true` — it is NOT assigned to STA-only devices.

**Analysis of cases:**

| Device State | hotspotIsStarted | concurrentApStationSupported | staStaConcurrencySupported | isInternetWifiFeatureAvailable() | Button |
|---|---|---|---|---|---|
| STA to mesh only, no STA+STA | false | false | false | false | GONE ✓ |
| STA to mesh only, has STA+STA (API 31+) | false | false | true | true | VISIBLE ✓ |
| Running hotspot (Start Mesh), no AP+STA | true | false | any | false | GONE ✓ |
| Running hotspot (Start Mesh) + AP+STA (MESH_ROUTER) | true | true | any | true | VISIBLE ✓ |
| MESH_ROUTER joined mesh (STA + hotspot running) | true | true | any | true | VISIBLE ✓ |

**Conclusion on Goal 1:** The existing `isInternetWifiFeatureAvailable()` logic correctly handles all cases including the MESH_ROUTER exception. **No change required to the button disable logic.** The user's requirement "disable if STA in use for mesh" is already implemented — `isInternetWifiFeatureAvailable()` returns false when STA is in use and no second STA/AP+STA path exists.

The plan notes this as **verified-correct existing behavior**, not a new code change.

---

### 2.3 Goal 2 Analysis — Internet Connectivity Detection

**The Android mechanism (verified via external research):**
- `ConnectivityService` sends HTTP/HTTPS probes to `http://connectivitycheck.gstatic.com/generate_204` after a network connects
- If it returns HTTP 204 → `NET_CAPABILITY_VALIDATED` is added to the network's capabilities
- This capability can be observed via `ConnectivityManager.NetworkCallback.onCapabilitiesChanged()`
- Apps check it via `networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)`
- **Critical:** `NET_CAPABILITY_VALIDATED` must NOT be added to the `NetworkRequest` (that was the bug fixed previously — it would cause `onUnavailable` on networks that haven't been validated yet). It must only be READ in `onCapabilitiesChanged()`.

**Verified fact:** Current `connectToInternetWifi()` in `MeshrabiyaWifiManagerAndroid.kt` at lines 627–661 (literal read) uses:

```kotlin
val networkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    .build()
```

The callback only overrides `onAvailable()`, `onLost()`, and `onUnavailable()`. There is NO `onCapabilitiesChanged()` override.

**Verified fact:** `NetworkCapabilities` is already imported in `MeshrabiyaWifiManagerAndroid.kt` (line 10 of imports).

**Verified fact:** `android.net.LinkProperties` is NOT currently imported in `MeshrabiyaWifiManagerAndroid.kt`. Must be added.

**Verified fact:** `java.net.Inet4Address` is NOT imported (only `Inet6Address` at line 56). Must be added.

**Verified fact:** `connectivityManager.getLinkProperties(network)` pattern is already used at lines 861–862:
```kotlin
val linkProperties = connectivityManager
    .getLinkProperties(network)
```

**The design for internet IP extraction:**
```kotlin
connectivityManager.getLinkProperties(network)
    ?.linkAddresses
    ?.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }
    ?.address?.hostAddress
```

**Propagation design:** Add an `InternetWifiNetworkState` data class and expose a `StateFlow` from `MeshrabiyaWifiManagerAndroid` so `MeshrabiyaApiImpl` can observe it reactively:

```kotlin
data class InternetWifiNetworkState(
    val network: Network? = null,
    val hasInternetAccess: Boolean = false,
    val ipAddress: String? = null,
)
```

This follows the existing pattern (e.g., `_state: MutableStateFlow<MeshrabiyaWifiState>`).

---

### 2.4 Goal 3 Analysis — Mesh IP Row Chips

**Verified fact:** `nodeInfoText` is in `fragment_mesh_enhanced_deferred.xml` at lines 32–39 (literal read):
```xml
<TextView
    android:id="@+id/nodeInfoText"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Node: Not initialized"
    android:textAppearance="@style/TextAppearance.Material3.BodySmall"
    android:fontFamily="monospace"
    android:layout_marginBottom="8dp" />
```

**Verified fact:** `nodeInfoText` is bound in `MeshUIBindings.bindDeferredViews()` at line 122 (literal read):
```kotlin
nodeInfoText = view.findViewById(R.id.nodeInfoText)
```

**Verified fact:** `nodeInfoText` is updated in `updateUI()` in `EnhancedMeshFragment.kt` at lines 1068–1071:
```kotlin
MeshUIBindings.nodeInfoText.text = if (networkInfo != null) {
    "IP: ${networkInfo.ipAddress}"
} else {
    "Mesh not initialized"
}
```

**Verified fact:** `MeshrabiyaWifiStateDto` (DtoModels.kt lines ~205–212) has:
- `wifiRole: String`
- `wifiDirectState: WifiDirectStateDto` → has `hotspotStatus: String`
- `wifiStationState: WifiStationStateDto` → has `status: String`
- `localOnlyHotspotState: LocalOnlyHotspotStateDto` → has `status: String`

**How to determine STA/AP in the fragment:**
```kotlin
val wifiStateDto = meshrabiyaApi.getLocalNodeState().wifiState
val isActingAsSta = wifiStateDto.wifiStationState.status == "AVAILABLE"
val isActingAsAp = wifiStateDto.wifiDirectState.hotspotStatus == "STARTED"
             || wifiStateDto.localOnlyHotspotState.status == "STARTED"
```
No new API methods needed — `getLocalNodeState()` already exists (MeshrabiyaApiImpl.kt line 329).

**Layout change strategy:** Replace the single `nodeInfoText` TextView with a horizontal `LinearLayout` containing a TextView for the IP text and a `ChipGroup` for the chips. `nodeInfoText` will be renamed to `meshIpAddressText` in the layout. `MeshUIBindings` will bind the new views.

---

### 2.5 Goal 4 Analysis — Internet WiFi Row

**Verified fact:** `NonMeshWifiConnectionStateDto` (DtoModels.kt lines 731–735, literal read):
```kotlin
data class NonMeshWifiConnectionStateDto(
    val status: NonMeshWifiStatusDto,
    val connectedSsid: String? = null,
    val errorMessage: String? = null,
)
```
Missing: `hasInternetAccess: Boolean = false` and `internetConnectionIpAddress: String? = null`.

**Verified fact:** `getNonMeshWifiStateFlow()` already exists in `MeshrabiyaApi.kt` (line 419) and is implemented in `MeshrabiyaApiImpl.kt` (line 2019):
```kotlin
return _nonMeshWifiState.asStateFlow()
```

**Verified fact:** There is no existing observer for `getNonMeshWifiStateFlow()` in `EnhancedMeshFragment.kt` (grep returned 0 matches for `getNonMeshWifiStateFlow`). A new observer must be added.

---

## 3. Solution Design — All Changes

### Change Order (dependency-safe, bottom-to-top within each LARGE file)

| # | File | Type | Description |
|---|------|------|-------------|
| C1 | `DtoModels.kt` | NOT large | Add `hasInternetAccess` + `internetConnectionIpAddress` to `NonMeshWifiConnectionStateDto` |
| C2 | `MeshrabiyaWifiManagerAndroid.kt` | ⚠️ LARGE | Add `InternetWifiNetworkState` data class (above companion object); add `_internetWifiNetworkState` MutableStateFlow; add `internetWifiNetworkStateFlow` val; update `connectToInternetWifi()` callback to override `onCapabilitiesChanged()`; clear state in `disconnectFromInternetWifi()`; add 2 imports |
| C3 | `MeshrabiyaApiImpl.kt` | ⚠️ LARGE | In `connectToNonMeshWifi()` — after success, launch coroutine to observe `internetWifiNetworkStateFlow` and update `_nonMeshWifiState` with `hasInternetAccess` and `internetConnectionIpAddress` |
| C4 | `fragment_mesh_enhanced_deferred.xml` | NOT large | Restructure Network Information Card: replace `nodeInfoText` with `meshIpRow` LinearLayout; add `meshIpAddressText`, `meshChipGroup` with three chips; add `internetWifiRow` LinearLayout (initially GONE) with `internetWifiIpText`, `internetWifiChipGroup` with two chips |
| C5 | `MeshUIBindings.kt` | NOT large | Add new view bindings: `meshIpAddressText`, `meshChipMesh`, `meshChipSta`, `meshChipAp`, `internetWifiRow`, `internetWifiIpText`, `internetWifiChipSta`, `internetWifiChipWeb`; remove `nodeInfoText` binding OR add typedef for backward compat |
| C6 | `EnhancedMeshFragment.kt` | ⚠️ LARGE | (a) Update `updateUI()` for new chip row; (b) Add `setupNonMeshWifiObserver()` method; (c) Call `setupNonMeshWifiObserver()` from `onViewCreated()` after deferred views are initialized |

---

## 4. Detailed Change Specifications

### C1 — `DtoModels.kt` — Extend `NonMeshWifiConnectionStateDto`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt`  
**Lines:** 700 — NOT large  
**Location:** Lines 731–735 (verified by literal read)

**BEFORE (lines 729–736, verbatim from disk):**
```kotlin
 * Observed via MeshrabiyaApi.getNonMeshWifiStateFlow().
 */
data class NonMeshWifiConnectionStateDto(
    val status: NonMeshWifiStatusDto,
    val connectedSsid: String? = null,
    val errorMessage: String? = null,
)
```

**AFTER:**
```kotlin
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

---

### C2 — `MeshrabiyaWifiManagerAndroid.kt` — Add State Flow + onCapabilitiesChanged

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Lines:** 1087 — ⚠️ LARGE — **present for manual edit only, do not use replace_string_in_file**

#### C2a — Two new imports (at the top, after existing imports)

**Location:** After line 62 (`import kotlin.coroutines.resume`), before the class KDoc `/** */`

**BEFORE (lines 60–68):**
```kotlin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 *
 */
class MeshrabiyaWifiManagerAndroid(
```

**AFTER:**
```kotlin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.net.LinkProperties
import java.net.Inet4Address

/**
 *
 */
class MeshrabiyaWifiManagerAndroid(
```

#### C2b — Add `InternetWifiNetworkState` data class and StateFlow fields

**Location:** After the `internetWifiNetworkCallback` field (line 240), before `private val closed = AtomicBoolean(false)`.

**BEFORE (lines 238–245, verbatim from disk):**
```kotlin
    /** NetworkCallback registered for the internet WiFi connection. Cleared on disconnect. */
    @Volatile
    private var internetWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val closed = AtomicBoolean(false)
```

**AFTER:**
```kotlin
    /** NetworkCallback registered for the internet WiFi connection. Cleared on disconnect. */
    @Volatile
    private var internetWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * State representing the current internet WiFi network connection and its capabilities.
     * Updated by connectToInternetWifi() NetworkCallback events.
     * Cleared by disconnectFromInternetWifi().
     * Observed by MeshrabiyaApiImpl to propagate to NonMeshWifiConnectionStateDto.
     */
    data class InternetWifiNetworkState(
        val network: Network? = null,
        val hasInternetAccess: Boolean = false,
        /** IPv4 address assigned on the internet WiFi interface, or null if unavailable. */
        val ipAddress: String? = null,
    )

    private val _internetWifiNetworkState = MutableStateFlow(InternetWifiNetworkState())

    /** Observe changes to the internet WiFi connection including connectivity validation. */
    val internetWifiNetworkStateFlow: kotlinx.coroutines.flow.StateFlow<InternetWifiNetworkState> =
        _internetWifiNetworkState.asStateFlow()

    private val closed = AtomicBoolean(false)
```

#### C2c — Add `onCapabilitiesChanged` to the callback in `connectToInternetWifi()`

**Location:** Inside `connectToInternetWifi()` — the callback `object : ConnectivityManager.NetworkCallback()` currently has `onAvailable()`, `onLost()`, `onUnavailable()`. We add `onCapabilitiesChanged()` and also update `onAvailable()` to set the initial state, and update `onLost()` to clear state.

**BEFORE (lines 632–662, verbatim from disk):**
```kotlin
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logger(Log.INFO, "$logPrefix connectToInternetWifi: onAvailable: SSID=$ssid network=$network")
                    internetWifiNetwork = network
                    if (continuation.isActive) {
                        continuation.resume(Result.success(network))
                    }
                }

                override fun onLost(network: Network) {
                    logger(Log.WARN, "$logPrefix connectToInternetWifi: onLost: network=$network")
                    if (internetWifiNetwork == network) {
                        internetWifiNetwork = null
                    }
                }

                override fun onUnavailable() {
                    logger(Log.WARN, "$logPrefix connectToInternetWifi: onUnavailable for SSID=$ssid")
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(
                            "Internet WiFi network unavailable for SSID=$ssid"
                        )))
                    }
                }
            }
```

**AFTER:**
```kotlin
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logger(Log.INFO, "$logPrefix connectToInternetWifi: onAvailable: SSID=$ssid network=$network")
                    internetWifiNetwork = network
                    // Extract IPv4 address from link properties (may not be available yet;
                    // onCapabilitiesChanged / onLinkPropertiesChanged fires later with final IP).
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
                    // Re-extract IP in case it was not available in onAvailable.
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
                            "Internet WiFi network unavailable for SSID=$ssid"
                        )))
                    }
                }
            }
```

#### C2d — Clear `_internetWifiNetworkState` in `disconnectFromInternetWifi()`

**Location:** Lines 674–681 of `disconnectFromInternetWifi()`.

**BEFORE (lines 674–682, verbatim from disk):**
```kotlin
    fun disconnectFromInternetWifi() {
        val callback = internetWifiNetworkCallback
        if (callback != null) {
            connectivityManager.unregisterNetworkCallback(callback)
            internetWifiNetworkCallback = null
        }
        internetWifiNetwork = null
        logger(Log.INFO, "$logPrefix disconnectFromInternetWifi: cleared internet WiFi network and callback")
    }
```

**AFTER:**
```kotlin
    fun disconnectFromInternetWifi() {
        val callback = internetWifiNetworkCallback
        if (callback != null) {
            connectivityManager.unregisterNetworkCallback(callback)
            internetWifiNetworkCallback = null
        }
        internetWifiNetwork = null
        _internetWifiNetworkState.value = InternetWifiNetworkState()
        logger(Log.INFO, "$logPrefix disconnectFromInternetWifi: cleared internet WiFi network and callback")
    }
```

---

### C3 — `MeshrabiyaApiImpl.kt` — Observe `internetWifiNetworkStateFlow` and propagate to `_nonMeshWifiState`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Lines:** 2062 — ⚠️ LARGE — **present for manual edit only**

#### C3a — Update `connectToNonMeshWifi()` to observe the new state flow

**Location:** Lines 1984–2010.

**BEFORE (lines 1984–2011, verbatim from disk):**
```kotlin
    override suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto {
        val node = myNode ?: return NonMeshWifiConnectionStateDto(
            status = NonMeshWifiStatusDto.FAILED,
            errorMessage = "Mesh not initialized",
        )
        _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.CONNECTING)
        val result = node.meshrabiyaWifiManager.connectToInternetWifi(ssid, passphrase)
        return result.fold(
            onSuccess = {
                val connected = NonMeshWifiConnectionStateDto(
                    status = NonMeshWifiStatusDto.CONNECTED,
                    connectedSsid = ssid,
                )
                _nonMeshWifiState.value = connected
                connected
            },
            onFailure = { error ->
                val failed = NonMeshWifiConnectionStateDto(
                    status = NonMeshWifiStatusDto.FAILED,
                    errorMessage = error.message,
                )
                _nonMeshWifiState.value = failed
                failed
            }
        )
    }
```

**AFTER:**
```kotlin
    override suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto {
        val node = myNode ?: return NonMeshWifiConnectionStateDto(
            status = NonMeshWifiStatusDto.FAILED,
            errorMessage = "Mesh not initialized",
        )
        _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.CONNECTING)
        val result = node.meshrabiyaWifiManager.connectToInternetWifi(ssid, passphrase)
        return result.fold(
            onSuccess = {
                val connected = NonMeshWifiConnectionStateDto(
                    status = NonMeshWifiStatusDto.CONNECTED,
                    connectedSsid = ssid,
                )
                _nonMeshWifiState.value = connected
                // Observe internet network state flow so subsequent capability changes
                // (NET_CAPABILITY_VALIDATED, IP address finalization) propagate to the UI.
                // The coroutine is cancelled when the scope is cancelled (app shutdown) or
                // when a disconnection resets _internetWifiNetworkState to InternetWifiNetworkState().
                apiScope.launch {
                    node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.collect { netState ->
                        val currentStatus = _nonMeshWifiState.value.status
                        if (currentStatus == NonMeshWifiStatusDto.CONNECTED ||
                            currentStatus == NonMeshWifiStatusDto.CONNECTING) {
                            _nonMeshWifiState.update { prev ->
                                prev.copy(
                                    hasInternetAccess = netState.hasInternetAccess,
                                    internetConnectionIpAddress = netState.ipAddress,
                                )
                            }
                        }
                    }
                }
                connected
            },
            onFailure = { error ->
                val failed = NonMeshWifiConnectionStateDto(
                    status = NonMeshWifiStatusDto.FAILED,
                    errorMessage = error.message,
                )
                _nonMeshWifiState.value = failed
                failed
            }
        )
    }
```

**Note:** `apiScope` must be verified to exist in `MeshrabiyaApiImpl`. If it does not, use `CoroutineScope(Dispatchers.IO + Job())` stored as a field. Verify before applying.

**Prerequisite verification needed before applying C3a:**
- Grep for `apiScope` or `scope` or `coroutineScope` in MeshrabiyaApiImpl.kt to find the existing scope name.

---

### C4 — `fragment_mesh_enhanced_deferred.xml` — Restructure Network Information Card

**File:** `app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml`  
**Lines:** 546 — NOT large  
**Location:** Lines 9–52 (the Network Information Card content).

**BEFORE (lines 9–52, verbatim from disk):**
```xml
    <!-- 4. Detailed Information Card -->
    <com.google.android.material.card.MaterialCardView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:cardCornerRadius="16dp"
        app:cardElevation="4dp"
        android:layout_marginBottom="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="20dp">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Network Information"
                android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                android:textStyle="bold"
                android:layout_marginBottom="12dp" />

            <TextView
                android:id="@+id/nodeInfoText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Node: Not initialized"
                android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                android:fontFamily="monospace"
                android:layout_marginBottom="8dp" />

            <TextView
                android:id="@+id/networkStatsText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Network interface: Not active"
                android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                android:fontFamily="monospace" />

        </LinearLayout>

    </com.google.android.material.card.MaterialCardView>
```

**AFTER:**
```xml
    <!-- 4. Detailed Information Card -->
    <com.google.android.material.card.MaterialCardView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        app:cardCornerRadius="16dp"
        app:cardElevation="4dp"
        android:layout_marginBottom="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="20dp">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Network Information"
                android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
                android:textStyle="bold"
                android:layout_marginBottom="12dp" />

            <!-- Mesh connection row: IP address + role chips -->
            <LinearLayout
                android:id="@+id/meshIpRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginBottom="8dp">

                <TextView
                    android:id="@+id/meshIpAddressText"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Not initialized"
                    android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                    android:fontFamily="monospace" />

                <com.google.android.material.chip.ChipGroup
                    android:id="@+id/meshChipGroup"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    app:singleLine="true"
                    app:chipSpacingHorizontal="4dp">

                    <!-- Always visible for mesh connection -->
                    <com.google.android.material.chip.Chip
                        android:id="@+id/meshChipMesh"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Mesh"
                        android:textSize="10sp"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                    <!-- Visible when device joined mesh as station (client) -->
                    <com.google.android.material.chip.Chip
                        android:id="@+id/meshChipSta"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="STA"
                        android:textSize="10sp"
                        android:visibility="gone"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                    <!-- Visible when device is running a mesh hotspot (AP mode) -->
                    <com.google.android.material.chip.Chip
                        android:id="@+id/meshChipAp"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="AP"
                        android:textSize="10sp"
                        android:visibility="gone"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                </com.google.android.material.chip.ChipGroup>

            </LinearLayout>

            <!-- Internet WiFi connection row: shown only when WiFi button is connected -->
            <LinearLayout
                android:id="@+id/internetWifiRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginBottom="8dp"
                android:visibility="gone">

                <TextView
                    android:id="@+id/internetWifiIpText"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="--"
                    android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                    android:fontFamily="monospace" />

                <com.google.android.material.chip.ChipGroup
                    android:id="@+id/internetWifiChipGroup"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    app:singleLine="true"
                    app:chipSpacingHorizontal="4dp">

                    <!-- Always visible for internet WiFi connection (device is STA to internet AP) -->
                    <com.google.android.material.chip.Chip
                        android:id="@+id/internetWifiChipSta"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="STA"
                        android:textSize="10sp"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                    <!-- Visible when NET_CAPABILITY_VALIDATED confirms internet access -->
                    <com.google.android.material.chip.Chip
                        android:id="@+id/internetWifiChipWeb"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Web"
                        android:textSize="10sp"
                        android:visibility="gone"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                </com.google.android.material.chip.ChipGroup>

            </LinearLayout>

            <TextView
                android:id="@+id/networkStatsText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Network interface: Not active"
                android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                android:fontFamily="monospace" />

        </LinearLayout>

    </com.google.android.material.card.MaterialCardView>
```

**Note:** `nodeInfoText` id is removed and replaced with `meshIpAddressText`. All reads of `MeshUIBindings.nodeInfoText` in `EnhancedMeshFragment.kt` must be updated to `MeshUIBindings.meshIpAddressText` in Change C6.

---

### C5 — `MeshUIBindings.kt` — Add new view bindings

**File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt`  
**Lines:** 165 — NOT large

#### C5a — Add new lateinit fields (after `nodeInfoText` in the field declarations)

**Location:** After `lateinit var nodeInfoText: TextView` (currently line 20 area).

**Verified: `nodeInfoText` declaration is at line approx 20 (from literal read bindings section).**

**BEFORE (lines 19–21, verbatim from disk):**
```kotlin
    lateinit var meshStatusText: TextView
    lateinit var nodeInfoText: TextView
    lateinit var meshRolesText: TextView
```

**AFTER:**
```kotlin
    lateinit var meshStatusText: TextView
    lateinit var nodeInfoText: TextView  // kept for backward compat; not updated post-C4
    lateinit var meshIpAddressText: TextView
    lateinit var meshChipMesh: com.google.android.material.chip.Chip
    lateinit var meshChipSta: com.google.android.material.chip.Chip
    lateinit var meshChipAp: com.google.android.material.chip.Chip
    lateinit var internetWifiRow: android.widget.LinearLayout
    lateinit var internetWifiIpText: TextView
    lateinit var internetWifiChipSta: com.google.android.material.chip.Chip
    lateinit var internetWifiChipWeb: com.google.android.material.chip.Chip
    lateinit var meshRolesText: TextView
```

#### C5b — Bind new views in `bindDeferredViews()`

**Location:** In `bindDeferredViews()`, after `nodeInfoText = view.findViewById(R.id.nodeInfoText)`.

**Verified: `nodeInfoText` is bound at line 122 in `bindDeferredViews()`.**

**BEFORE (lines 122–124, verbatim from disk):**
```kotlin
        nodeInfoText = view.findViewById(R.id.nodeInfoText)
        networkStatsText = view.findViewById(R.id.networkStatsText)
```

**AFTER:**
```kotlin
        // nodeInfoText id removed from XML in C4; keep field for compile compat but do not bind
        // nodeInfoText = view.findViewById(R.id.nodeInfoText)
        meshIpAddressText = view.findViewById(R.id.meshIpAddressText)
        meshChipMesh = view.findViewById(R.id.meshChipMesh)
        meshChipSta = view.findViewById(R.id.meshChipSta)
        meshChipAp = view.findViewById(R.id.meshChipAp)
        internetWifiRow = view.findViewById(R.id.internetWifiRow)
        internetWifiIpText = view.findViewById(R.id.internetWifiIpText)
        internetWifiChipSta = view.findViewById(R.id.internetWifiChipSta)
        internetWifiChipWeb = view.findViewById(R.id.internetWifiChipWeb)
        networkStatsText = view.findViewById(R.id.networkStatsText)
```

**Note on `nodeInfoText` backward compat:** After C4 removes the `nodeInfoText` id from the XML, `view.findViewById(R.id.nodeInfoText)` will crash at runtime. The binding call must be commented out. The `lateinit var nodeInfoText` field is kept to prevent compile errors on any remaining references; however, all two usages in `updateUI()` and `setupNetworkInfoObserver()` must be replaced in C6.

---

### C6 — `EnhancedMeshFragment.kt` — UI updates for chip row + internet WiFi observer

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 2419 — ⚠️ LARGE — **present for manual edit only**

#### C6a — Update `updateUI()` mesh IP section

**Location:** Lines 1068–1071 (literal read):
```kotlin
MeshUIBindings.nodeInfoText.text = if (networkInfo != null) {
    "IP: ${networkInfo.ipAddress}"
} else {
    "Mesh not initialized"
}
```

**BEFORE (lines 1066–1083, verbatim from disk):**
```kotlin
			// Network Status - show local node IP address (deferred view)
				val networkInfo = meshrabiyaApi.getNetworkInfo()
				MeshUIBindings.nodeInfoText.text = if (networkInfo != null) {
					"IP: ${networkInfo.ipAddress}"
				} else {
					"Mesh not initialized"
				}

				// Network Information - show detailed network stats (deferred view)
				MeshUIBindings.networkStatsText.text = if (networkInfo != null) {
					"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
				} else {
					"No network data"
				}
```

**AFTER:**
```kotlin
			// Network Status - show local node IP address with role chips (deferred view)
				val networkInfo = meshrabiyaApi.getNetworkInfo()
				if (networkInfo != null) {
					MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
					// Determine STA/AP role from wifiState DTO
					val wifiStateDto = try {
						meshrabiyaApi.getLocalNodeState().wifiState
					} catch (e: Exception) {
						null
					}
					val isActingAsSta = wifiStateDto?.wifiStationState?.status == "AVAILABLE"
					val isActingAsAp = wifiStateDto?.wifiDirectState?.hotspotStatus == "STARTED"
						|| wifiStateDto?.localOnlyHotspotState?.status == "STARTED"
					MeshUIBindings.meshChipMesh.visibility = View.VISIBLE
					MeshUIBindings.meshChipSta.visibility = if (isActingAsSta) View.VISIBLE else View.GONE
					MeshUIBindings.meshChipAp.visibility = if (isActingAsAp) View.VISIBLE else View.GONE
				} else {
					MeshUIBindings.meshIpAddressText.text = "Mesh not initialized"
					MeshUIBindings.meshChipMesh.visibility = View.GONE
					MeshUIBindings.meshChipSta.visibility = View.GONE
					MeshUIBindings.meshChipAp.visibility = View.GONE
				}

				// Network Information - show detailed network stats (deferred view)
				MeshUIBindings.networkStatsText.text = if (networkInfo != null) {
					"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
				} else {
					"No network data"
				}
```

#### C6b — Update `setupNetworkInfoObserver()` to replace `nodeInfoText` reference

**Location:** Lines 742–743 (literal read):
```kotlin
MeshUIBindings.nodeInfoText.text = "IP: ${networkInfo.ipAddress}"
```

**BEFORE (lines 740–748, verbatim from disk):**
```kotlin
				if (deferredViewsInitialized && networkInfo != null) {
					activity?.runOnUiThread {
						android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Updating UI with connectedPeers=${networkInfo.connectedPeers}")
						MeshUIBindings.nodeInfoText.text = "IP: ${networkInfo.ipAddress}"
						MeshUIBindings.networkStatsText.text = 
							"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
					}
				}
```

**AFTER:**
```kotlin
				if (deferredViewsInitialized && networkInfo != null) {
					activity?.runOnUiThread {
						android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] Updating UI with connectedPeers=${networkInfo.connectedPeers}")
						MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
						MeshUIBindings.networkStatsText.text = 
							"Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
					}
				}
```

#### C6c — Add `setupNonMeshWifiObserver()` method

**Location:** Add as a new private method after `setupNetworkInfoObserver()`. `setupNetworkInfoObserver()` ends at approximately line 755 (grep match at line 731 for the start). The exact location must be verified before inserting — insert just after the closing `}` of `setupNetworkInfoObserver()`.

**BEFORE (approximately lines 754–756, the end of `setupNetworkInfoObserver()` and start of `setupListeners()`):**
```kotlin
	}

	private fun setupListeners() {
```

**AFTER:**
```kotlin
	}

	/**
	 * Observes the non-mesh WiFi connection state flow and updates the internet WiFi row
	 * in the Network Information Card. Must be called after deferred views are initialized.
	 */
	private fun setupNonMeshWifiObserver() {
		viewLifecycleOwner.lifecycleScope.launch {
			meshrabiyaApi.getNonMeshWifiStateFlow().collect { wifiState ->
				if (!deferredViewsInitialized) return@collect
				activity?.runOnUiThread {
					val isConnected = wifiState.status.name == "CONNECTED"
					MeshUIBindings.internetWifiRow.visibility =
						if (isConnected) View.VISIBLE else View.GONE
					if (isConnected) {
						val ipText = wifiState.internetConnectionIpAddress ?: "--"
						MeshUIBindings.internetWifiIpText.text = ipText
						MeshUIBindings.internetWifiChipSta.visibility = View.VISIBLE
						MeshUIBindings.internetWifiChipWeb.visibility =
							if (wifiState.hasInternetAccess) View.VISIBLE else View.GONE
					}
				}
			}
		}
	}

	private fun setupListeners() {
```

#### C6d — Call `setupNonMeshWifiObserver()` from `onViewCreated()`

**Location:** After `setupNetworkInfoObserver()` call (line 392 area of `onViewCreated()`).

**BEFORE (lines 391–395, verbatim from disk):**
```kotlin
		setupNetworkInfoObserver()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")
		
		// Initial UI update to show current mesh state
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Calling updateUI()...")
```

**AFTER:**
```kotlin
		setupNetworkInfoObserver()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")

		// Setup observer for non-mesh WiFi state — internet WiFi row in Network Information Card
		setupNonMeshWifiObserver()
		
		// Initial UI update to show current mesh state
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Calling updateUI()...")
```

---

## 5. Open Questions Requiring Code Verification Before Applying C3a

The following must be verified by literal read BEFORE applying C3a:

1. **`apiScope` in `MeshrabiyaApiImpl`** — grep for `apiScope`, `coroutineScope`, `CoroutineScope` inside `MeshrabiyaApiImpl.kt` to find the existing coroutine scope field used for background work. If not found, a new `private val apiScope = CoroutineScope(Dispatchers.IO + Job())` must be declared as a class field.

2. **Line precision of all ⚠️ LARGE file BEFORE blocks** — The line numbers above are derived from the literal reads performed in this session. Before applying any LARGE file change, re-read the exact range to confirm lines haven't drifted since this session's reads.

3. **`Chip` import in `EnhancedMeshFragment.kt`** — Verify whether `import com.google.android.material.chip.Chip` is already present. If not, it must be added.

4. **`NonMeshWifiStatusDto.name` vs enum comparison in C6c** — In `EnhancedMeshFragment.kt`, the comment at line 2410 says "Avoid importing NonMeshWifiStatus enum — use DTO field checks instead." This pattern is already established. `wifiState.status.name == "CONNECTED"` is the correct approach since the fragment deliberately avoids importing the enum. Confirmed consistent.

---

## 6. Order of Work for Implementation

Apply changes in this exact sequence to avoid compile breaks:

1. **C1** — `DtoModels.kt` (NOT large, tool edit OK)
2. **C2a, C2b, C2c, C2d** — `MeshrabiyaWifiManagerAndroid.kt` (LARGE, manual BEFORE/AFTER only, bottom-to-top within file: C2d first, then C2c, then C2b, then C2a)
3. **Verify `apiScope` in `MeshrabiyaApiImpl.kt`** before C3a
4. **C3a** — `MeshrabiyaApiImpl.kt` (LARGE, manual BEFORE/AFTER only)
5. **C4** — `fragment_mesh_enhanced_deferred.xml` (NOT large, tool edit OK)
6. **C5a, C5b** — `MeshUIBindings.kt` (NOT large, tool edit OK, C5b before C5a in file top-to-bottom but C5a removes the binding so C5b must be applied first to avoid leaving a broken binding)
7. **C6d, C6c, C6b, C6a** — `EnhancedMeshFragment.kt` (LARGE, manual BEFORE/AFTER only, applied bottom-to-top: C6d is in onViewCreated ~line 393, C6c is new method ~line 756, C6b is in setupNetworkInfoObserver ~line 743, C6a is in updateUI ~line 1068 — so order: C6a first (highest line), then C6b, C6c, C6d)

**Build/verify after each file is complete** (not after each sub-change).

---

## 7. Large File Rule Summary

| File | Lines | Edit Method |
|------|-------|-------------|
| `MeshrabiyaWifiManagerAndroid.kt` | 1087 | ⚠️ Manual BEFORE/AFTER only |
| `MeshrabiyaApiImpl.kt` | 2062 | ⚠️ Manual BEFORE/AFTER only |
| `EnhancedMeshFragment.kt` | 2419 | ⚠️ Manual BEFORE/AFTER only |
| All others | ≤700 | Tool edits allowed |

---

## 8. New Scope (G5–G9) — Goals

The following goals were added after the original C1–C6 scope was defined. They extend the network UI with MESH_ROUTER AP extension functionality and several UX improvements.

### G5 — MESH_ROUTER AP Button (Mesh Extender Hotspot)

After a node has joined a mesh (i.e., MESH_ROUTER role is active), show an AP/antenna button in the mesh action bar. Clicking this button starts a local-only hotspot using the **passphrase scanned from the QR code during `joinMesh()`** — allowing nearby devices to join this node's AP and reach the mesh. This is "extending the mesh" by acting as an AP while also being a station.

- Button is GONE by default; becomes VISIBLE when MESH_ROUTER role is detected in `setupRoleObserver()`
- Button is DISABLED (not clickable) when Non-Mesh WiFi is connected (see G6)
- When the AP is ACTIVE, a new row appears in the Network Information Card showing the AP IP + "AP" chip + "Mesh" chip
- The passphrase used is the one scanned from the QR code during `joinMesh()`, stored as `lastJoinedMeshPassphrase` in `MeshrabiyaApiImpl`
- On API < 33, the OS assigns a random passphrase; this is a known limitation documented in G5 Open Questions

### G6 — WiFi Button Stop Indicator

When Non-Mesh WiFi is connected (i.e., `nonMeshWifiState.status == CONNECTED`), the WiFi button text gains a red stop square suffix (◼). Clicking the button while in this state calls `disconnectFromNonMeshWifi()` instead of opening the connect dialog. Button text reverts automatically when the StateFlow observer detects status = DISCONNECTED.

### G7/G8 — Role Observer Timing (CONFIRMED ALREADY CORRECT — no code change)

**Confirmed by literal read:** `setupRoleObserver()` is called at `onViewCreated()` line 366, BEFORE any Start/Join/Merge mesh action. `startWifiStateMonitoring()` fires in `OrbotApp.onCreate()`. The `wifiApConnectionButton` visibility gate (`isInternetWifiFeatureAvailable()` inside `setupRoleObserver()`) correctly responds ~2 seconds after app launch when `concurrentApStationSupported` is detected. No structural change is needed.

### G9 — Collapse Scan Pane on Mesh Status Drop

In the `meshStatusFlow` observer (`onViewCreated()` lines 376–389), when status transitions to `DISCONNECTED`, `ERROR`, `UNKNOWN`, or `INITIALIZING`, and the scan/join pane is currently expanded (`meshExpandableContent.visibility == View.VISIBLE`), automatically call `collapsePane()` to dismiss the pane.

---

## 9. Verified Current State — G5–G9 Additions

### 9.1 — Passphrase Storage Gap (G5)

- **`MeshrabiyaApiImpl.kt` line 649:** `val password = qrJson.getString("password")` — parsed from QR JSON during `joinMesh()` but **stored nowhere**. Only passed to `WifiConnectConfig.passphrase` for the outbound station connection.
- **`LocalOnlyHotspotManager.kt` line ~143:** `UnhiddenSoftApConfigurationBuilder` uses **hardcoded** `.setPassphrase("meshtest12", SECURITY_TYPE_WPA2_PSK)`. No parameter exists for a custom passphrase.
- **Required fix:** Add `passphrase: String? = null` to `startLocalOnlyHotspot()` signature; use `passphrase ?: "meshtest12"` at the `setPassphrase()` call. Thread custom passphrase from new `startMeshExtenderHotspot()` API → `requestHotspot()` → `startLocalOnlyHotspot()`.

### 9.2 — `disconnectFromNonMeshWifi()` Verified (G6)

- **`MeshrabiyaApi.kt` line 420:** `fun disconnectFromNonMeshWifi(): Boolean` — exists and is callable from fragment.
- **`ENhancedMeshFragment.kt` line 897:** Current click handler calls `showInternetWifiConnectionDialog()` unconditionally — no stop branch.

### 9.3 — Role Observer Timing Confirmed (G7/G8)

- **`EnhancedMeshFragment.kt` line 366:** `setupRoleObserver()` called unconditionally from `onViewCreated()` — before any mesh action. Confirmed by literal read. No change needed.

### 9.4 — Scan Pane Collapse (G9)

- **`EnhancedMeshFragment.kt` lines 371–392 (current on-disk, 2026-03-04):** G9 was applied. `meshStatusFlow` observer triggers `collapsePane()` when status is `DISCONNECTED || ERROR || UNKNOWN || INITIALIZING`. ⚠️ **NEEDS CORRECTION** — trigger must be `DISCONNECTED` only; `collapsePane()` must not be called (it clears mode flags). See Section 16.
- **`collapsePane()` at line 1349 (literal read 2026-03-04):** Stops camera (`stopQRScanning()` if `isCameraActive`), sets `meshExpandableContent.visibility = View.GONE`, sets `expandCollapseIndicator.rotation = 0f`, sets `isJoinMeshMode = false`, sets `isMergeMeshMode = false`.
- Pane-is-expanded check: `MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE`

### 9.5 — `LocalHotspotRequest` Passphrase Field (G5b — PENDING VERIFICATION)

**⚠️ MUST VERIFY BEFORE IMPLEMENTING G5b:** The structure of `LocalHotspotRequest` (used by `requestHotspot()` in `MeshrabiyaWifiManagerAndroid`) is not yet confirmed by literal read. Two possible passphrase-threading paths:

- **Path A:** Add `preferredPassphrase: String? = null` field to `LocalHotspotRequest`. Pass from `MeshrabiyaApiImpl.startMeshExtenderHotspot()` → `requestHotspot(LocalHotspotRequest(..., preferredPassphrase = lastJoinedMeshPassphrase))` → `startLocalOnlyHotspot(band, request.preferredPassphrase)`
- **Path B:** `MeshrabiyaApiImpl` calls a NEW overload `localOnlyHotspotManager.startLocalOnlyHotspot(band, passphrase)` directly via the internal manager reference, bypassing `requestHotspot()`.

**Verification required:** `grep_search` for `LocalHotspotRequest` and `requestHotspot` in `MeshrabiyaWifiManagerAndroid.kt` and related files before implementing G5b.

---

## 10. G5–G9 Solution Design Table

| ID | File | Large? | Change Description |
|----|------|--------|--------------------|
| G5-LHR | `LocalHotspotRequest.kt` | NOT large (<20 lines) | **NEW** Add `preferredPassphrase: String? = null` field to data class |
| G5a | `LocalOnlyHotspotManager.kt` | NOT large | Add `passphrase: String? = null` param to `startLocalOnlyHotspot()`; use `passphrase ?: "meshtest12"` at `.setPassphrase()` call (API 33+ path only) |
| G5b | `MeshrabiyaWifiManagerAndroid.kt` | ⚠️ LARGE | At line 400 change `localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)` to `localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)` |
| G5-VN | `VirtualNode.kt` | ⚠️ LARGE (1492 lines) | **NEW** Add `preferredPassphrase: String? = null` to `setWifiHotspotEnabled()` (lines 1280\u20131302); pass into `LocalHotspotRequest(...)` |
| G5c | `MeshrabiyaApiImpl.kt` | ⚠️ LARGE | Add `lastJoinedMeshPassphrase` field + `_meshExtenderHotspotState` StateFlow (after line 126); store passphrase in `joinMesh()`; add `startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)` / `stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)` methods using `eventMonitoringScope` |
| G5d | `MeshrabiyaApi.kt` | NOT large | Add `startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)`, `stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)`, `meshExtenderHotspotStateFlow` to interface. **No `SimpleCallback` \u2014 does not exist.** |
| G5e | `DtoModels.kt` | NOT large (751 lines) | Add `MeshExtenderHotspotStateDto` enum: INACTIVE, STARTING, ACTIVE, STOPPING |
| G5f | `fragment_mesh_enhanced_deferred.xml` | NOT large | Add `meshExtenderApButton` (`@drawable/ic_wifi`, NOT `ic_wifi_tethering` \u2014 does not exist); add `meshExtenderApRow` LinearLayout with IP textview + ChipGroup |
| G5g | `MeshUIBindings.kt` | NOT large | Add `lateinit var` bindings for `meshExtenderApButton`, `meshExtenderApRow`, `meshExtenderApIpText`, `meshExtenderApChipAp`, `meshExtenderApChipMesh`; bind in `bindDeferredViews()` |
| G5h | `EnhancedMeshFragment.kt` | ⚠️ LARGE | AP button visibility in `setupRoleObserver()` (uses `MeshRoleDto.MESH_ROUTER` \u2014 confirmed valid); click handler using `(Result<Unit>) -> Unit` lambda; `setupMeshExtenderObserver()` method; call from `onViewCreated()` |
| G6a | `EnhancedMeshFragment.kt` | ⚠️ LARGE | In `setupNonMeshWifiObserver()` (C6c): update WiFi button text to add \u201c\u25fc\u201d suffix when CONNECTED; disable AP extender button when CONNECTED |
| G6b | `EnhancedMeshFragment.kt` | ⚠️ LARGE | In `setupListeners()` (~line 897): branch WiFi button click on disconnection state using `(Result<Unit>) -> Unit` lambda |
| G9 | `EnhancedMeshFragment.kt` | ⚠️ LARGE | In `meshStatusFlow` observer (lines ~376\u2013389): call `collapsePane()` when status drops to DISCONNECTED/ERROR/UNKNOWN/INITIALIZING |

---

## 11. G5–G9 Detailed Change Specifications

### G5a — `LocalOnlyHotspotManager.kt` — Add passphrase parameter

**File:** `app/src/main/java/org/torproject/android/meshrabiya/LocalOnlyHotspotManager.kt`  
**Lines:** ~291 — NOT large — tool edit allowed

#### G5a-1 — Add `passphrase` param to function signature

**⚠️ MUST verify exact current signature by literal read before edit. The following is from the prior session's reads.**

**BEFORE (lines ~119–124):**
```kotlin
suspend fun startLocalOnlyHotspot(
    preferredBand: ConnectBand,
) {
    logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: band=$preferredBand")
    if(Build.VERSION.SDK_INT >= 33) {
```

**AFTER:**
```kotlin
suspend fun startLocalOnlyHotspot(
    preferredBand: ConnectBand,
    passphrase: String? = null,
) {
    logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: band=$preferredBand passphrase=${if (passphrase != null) "***provided***" else "default(meshtest12)"}")
    if(Build.VERSION.SDK_INT >= 33) {
```

#### G5a-2 — Use provided passphrase at `setPassphrase()` call

**⚠️ MUST verify exact current line by literal read before edit.**

**BEFORE (lines ~142–145):**
```kotlin
                .setSsid("meshr-${localNodeAddr.encodeAsHex()}")
                .setPassphrase("meshtest12", SECURITY_TYPE_WPA2_PSK)
                .setBssid(macAddr)
```

**AFTER:**
```kotlin
                .setSsid("meshr-${localNodeAddr.encodeAsHex()}")
                .setPassphrase(passphrase ?: "meshtest12", SECURITY_TYPE_WPA2_PSK)
                .setBssid(macAddr)
```

**⚠️ API < 33 limitation:** At API < 33 the OS-assigned hotspot ignores the passphrase parameter entirely (no `UnhiddenSoftApConfigurationBuilder` path). The stored `lastJoinedMeshPassphrase` will NOT be applied on older devices. Joining devices cannot use the original mesh passphrase on API < 33.

---

### G5b — `MeshrabiyaWifiManagerAndroid.kt` — Pass `preferredPassphrase` through `requestHotspot()`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Lines:** 1087 — ⚠️ LARGE — **manual BEFORE/AFTER only**

**Purpose:** The `LOCALONLY_HOTSPOT` branch of `requestHotspot()` calls `localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)` but does not forward any passphrase. After adding `preferredPassphrase` to `LocalHotspotRequest` (G5-LocalHotspotRequest) and `startLocalOnlyHotspot()` (G5a), this single call-site in `requestHotspot()` must be updated.

**Verified current code (literal read, line 400):**
```kotlin
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)
```

**Context (literal read, lines 393–405 of `requestHotspot()` body):**
```kotlin
                HotspotType.LOCALONLY_HOTSPOT -> {
                    wifiDirectManager.stopWifiDirectGroup()
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)
                }
                else -> {
                    //Do nothing
                }
            }
```

**BEFORE (lines 393–405, verbatim from disk; verify before applying):**
```kotlin
                HotspotType.LOCALONLY_HOTSPOT -> {
                    wifiDirectManager.stopWifiDirectGroup()
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)
                }
                else -> {
                    //Do nothing
                }
            }
```

**AFTER:**
```kotlin
                HotspotType.LOCALONLY_HOTSPOT -> {
                    wifiDirectManager.stopWifiDirectGroup()
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)
                }
                else -> {
                    //Do nothing
                }
            }
```

**Note:** No other call-sites of `startLocalOnlyHotspot()` exist in `MeshrabiyaWifiManagerAndroid.kt` per the literal read grep (only 1 match at line 400). No other files call `startLocalOnlyHotspot()` — it is package-private to the wifi manager layer.

---

### G5-VN — `VirtualNode.kt` — Add `preferredPassphrase` to `setWifiHotspotEnabled()`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 1492 — ⚠️ LARGE — **manual BEFORE/AFTER only**  
**New file in scope (added in Round 2 verification).**

**Purpose:** `setWifiHotspotEnabled()` constructs `LocalHotspotRequest`. After G5b adds `preferredPassphrase` to `LocalHotspotRequest`, this method must accept and forward the passphrase.

**Verified current code (literal read, lines 1278–1302):**
```kotlin
    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
    ): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                )
            )
        }else {
            meshrabiyaWifiManager.deactivateHotspot()
            LocalHotspotResponse(
                responseToMessageId = 0,
                config = null,
                errorCode = 0,
                redirectAddr = 0,
            )
        }
    }
```

**BEFORE (lines 1280–1302, verbatim from disk):**
```kotlin
    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
    ): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                )
            )
        }else {
            meshrabiyaWifiManager.deactivateHotspot()
            LocalHotspotResponse(
                responseToMessageId = 0,
                config = null,
                errorCode = 0,
                redirectAddr = 0,
            )
        }
    }
```

**AFTER:**
```kotlin
    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
        preferredPassphrase: String? = null,
    ): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                    preferredPassphrase = preferredPassphrase,
                )
            )
        }else {
            meshrabiyaWifiManager.deactivateHotspot()
            LocalHotspotResponse(
                responseToMessageId = 0,
                config = null,
                errorCode = 0,
                redirectAddr = 0,
            )
        }
    }
```

**Also required — `LocalHotspotRequest.kt` (NOT large, tool edit OK):**

**BEFORE (entire file, verbatim from disk):**
```kotlin
package com.ustadmobile.meshrabiya.vnet.wifi

data class LocalHotspotRequest(
    val preferredBand: ConnectBand,
    val preferredType: HotspotType,
) {
}
```

**AFTER:**
```kotlin
package com.ustadmobile.meshrabiya.vnet.wifi

data class LocalHotspotRequest(
    val preferredBand: ConnectBand,
    val preferredType: HotspotType,
    val preferredPassphrase: String? = null,
) {
}
```

---

### G5c — `MeshrabiyaApiImpl.kt` — Passphrase storage + extender API

**File:** `app/src/main/java/org/torproject/android/meshrabiya/MeshrabiyaApiImpl.kt`  
**Lines:** 2062 — ⚠️ LARGE — **manual BEFORE/AFTER only**

#### G5c-1 — Add `lastJoinedMeshPassphrase` volatile field

**Location (verified by literal read):** After `_nonMeshWifiState` at line 126 in `MeshrabiyaApiImpl.kt`. The `_nonMeshWifiState` declaration is the last StateFlow field before the `metricsMonitorJob`, `distributedStorageManager`, and `broadcastHandler` fields. The new `_meshExtenderHotspotState` and `lastJoinedMeshPassphrase` fields must be inserted immediately after line 126.

**BEFORE (lines 126–129, verbatim from disk):**
```kotlin
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE))

    private var metricsMonitorJob: Job? = null
    private var distributedStorageManager: DistributedStorageManager? = null
```

**AFTER:**
```kotlin
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE))

    // Mesh extender hotspot state (G5)
    private val _meshExtenderHotspotState = MutableStateFlow(MeshExtenderHotspotStateDto.INACTIVE)
    override val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto> = _meshExtenderHotspotState.asStateFlow()

    /**
     * Password from the most recently scanned mesh QR code. Stored during joinMesh() for
     * use by startMeshExtenderHotspot() to create a same-credential AP extension of the mesh.
     * Cleared when stopMesh() or stopMeshExtenderHotspot() is called.
     */
    @Volatile
    private var lastJoinedMeshPassphrase: String? = null

    private var metricsMonitorJob: Job? = null
    private var distributedStorageManager: DistributedStorageManager? = null
```

#### G5c-2 — Store passphrase in `joinMesh()` at password parse location

**Location (verified by literal read):** `joinMesh()` coroutine starts at line 636 (`eventMonitoringScope.launch {`). The QR parse block is within `lines 640–644`: `val password = qrJson.getString("password")` appears after `// Parse QR code JSON data` comment and after `val qrJson = org.json.JSONObject(jsonQrData)`. Exact anchor for insertion is immediately after the `val password` line.

**BEFORE (verbatim from disk, lines 641–647 approx):**
```kotlin
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")  // Default to "meshr-"
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
```

**AFTER:**
```kotlin
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                lastJoinedMeshPassphrase = password
                Log.d(TAG, "[JOIN] Stored mesh passphrase for AP extension (${password.length} chars)")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")  // Default to "meshr-"
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
```

#### G5c-3 — Add `_meshExtenderHotspotState` StateFlow field

**Location:** In the StateFlow declarations block (grep for `MutableStateFlow` near top of class). Add after existing StateFlow fields.

**BEFORE (approximate; must verify exact anchor):**
```kotlin
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiStateDto())
```

**AFTER:**
```kotlin
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiStateDto())
    private val _meshExtenderHotspotState = MutableStateFlow(MeshExtenderHotspotStateDto.INACTIVE)
    override val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto> = _meshExtenderHotspotState.asStateFlow()
```

#### G5c-4 — Add `startMeshExtenderHotspot()` implementation

**Location:** Add as new method after `startMesh()` method body. Verify exact anchor by literal read before applying (LARGE file — manual BEFORE/AFTER).  
**Scope correction from Round 2:** Use `(Result<Unit>) -> Unit` callback (NOT `SimpleCallback` — does not exist). Use `eventMonitoringScope.launch` (NOT `CoroutineScope(Dispatchers.IO).launch` — `eventMonitoringScope` at line 151 is the established scope). Pass `preferredPassphrase` via `setWifiHotspotEnabled()` new param (see G5-VN below for VirtualNode change required first).

**New method:**
```kotlin
    override fun startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit) {
        val pw = lastJoinedMeshPassphrase
        if (pw == null) {
            Log.w(TAG, "[EXTENDER] Cannot start mesh extender hotspot: no passphrase stored from joinMesh()")
            callback(Result.failure(IllegalStateException("No passphrase available — scan a mesh QR code first")))
            return
        }
        _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.STARTING
        eventMonitoringScope.launch {
            try {
                // preferredPassphrase param added to setWifiHotspotEnabled() in G5-VN (VirtualNode.kt)
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO,
                    preferredPassphrase = pw
                )
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.ACTIVE
                Log.i(TAG, "[EXTENDER] Mesh extender hotspot started with stored passphrase")
                callback(Result.success(Unit))
            } catch (e: Exception) {
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.INACTIVE
                Log.e(TAG, "[EXTENDER] Failed to start mesh extender hotspot", e)
                callback(Result.failure(e))
            }
        }
    }
```

#### G5c-5 — Add `stopMeshExtenderHotspot()` implementation

**Scope correction from Round 2:** Use `(Result<Unit>) -> Unit` callback. Use `eventMonitoringScope.launch`.

**New method:**
```kotlin
    override fun stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit) {
        _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.STOPPING
        eventMonitoringScope.launch {
            try {
                myNode?.setWifiHotspotEnabled(
                    enabled = false,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.INACTIVE
                Log.i(TAG, "[EXTENDER] Mesh extender hotspot stopped")
                callback(Result.success(Unit))
            } catch (e: Exception) {
                _meshExtenderHotspotState.value = MeshExtenderHotspotStateDto.INACTIVE
                Log.e(TAG, "[EXTENDER] Error stopping mesh extender hotspot", e)
                callback(Result.failure(e))
            }
        }
    }
```

---

### G5d — `MeshrabiyaApi.kt` — Add extender API to interface

**File:** `app/src/main/java/org/torproject/android/meshrabiya/MeshrabiyaApi.kt`  
**Lines:** ~440 — NOT large — tool edit allowed

**Scope correction from Round 2:** `SimpleCallback` does NOT exist and must NOT be introduced. The existing callback pattern is `(Result<Unit>) -> Unit` (verified in `MeshrabiyaApi.kt` at lines 63–64: `fun startMesh(callback: (Result<Unit>) -> Unit)`, `fun stopMesh(callback: (Result<Unit>) -> Unit)`). Use this pattern for the new methods.

**Add the following after `disconnectFromNonMeshWifi()` (the last method before `getNonMeshWifiStateFlow()`):**

```kotlin
    /**
     * Starts a local-only hotspot using the passphrase stored from the most recent joinMesh() QR scan.
     * This allows nearby devices to join this node's AP and reach the mesh (AP extension mode).
     * Only works reliably on API 33+; on older devices the OS assigns a random passphrase.
     */
    fun startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)

    /**
     * Stops the mesh extender hotspot started via [startMeshExtenderHotspot].
     */
    fun stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)

    /**
     * StateFlow emitting the current state of the mesh extender hotspot.
     */
    val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto>
```

---

### G5e — `DtoModels.kt` — Add `MeshExtenderHotspotStateDto`

**File:** `app/src/main/java/org/torproject/android/meshrabiya/DtoModels.kt`  
**Lines:** ~700 — NOT large — tool edit allowed

**Add after `NonMeshWifiStatusDto` enum (or at end of file before closing brace):**

```kotlin
/**
 * Represents the current state of the mesh extender (AP extension) hotspot.
 * INACTIVE: Not started.
 * STARTING: Hotspot start in progress.
 * ACTIVE: Hotspot is running and accessible.
 * STOPPING: Hotspot stop in progress.
 */
enum class MeshExtenderHotspotStateDto {
    INACTIVE,
    STARTING,
    ACTIVE,
    STOPPING
}
```

---

### G5f — `fragment_mesh_enhanced_deferred.xml` — Add extender AP button and row

**File:** `app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml`  
**Lines:** 546 — NOT large — tool edit allowed

#### G5f-1 — Add `meshExtenderApButton` to mesh action button row

**Location:** After `wifiApConnectionButton` in the action button bar. Verify exact anchor by literal read.  
**Scope correction from Round 2:** `ic_wifi_tethering` does NOT exist. Use `@drawable/ic_wifi` (verified present in `app/src/main/res/drawable/ic_wifi.xml`).

```xml
    <!-- AP Extender button: shown when device has MESH_ROUTER role after joinMesh().
         Starts a hotspot using the QR-scanned passphrase to extend mesh coverage. -->
    <ImageButton
        android:id="@+id/meshExtenderApButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:src="@drawable/ic_wifi"
        android:contentDescription="Start AP Extension"
        android:visibility="gone"
        android:layout_marginStart="8dp" />
```

#### G5f-2 — Add `meshExtenderApRow` to Network Information Card

**Location:** After `internetWifiRow` LinearLayout in the Network Information Card (added in C4). Add as a new sibling row.

```xml
            <!-- Mesh extender AP row: shown when meshExtenderHotspotState == ACTIVE.
                 Displays the AP interface IP with AP and Mesh chips. -->
            <LinearLayout
                android:id="@+id/meshExtenderApRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginBottom="8dp"
                android:visibility="gone">

                <TextView
                    android:id="@+id/meshExtenderApIpText"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="--"
                    android:textAppearance="@style/TextAppearance.Material3.BodySmall"
                    android:fontFamily="monospace" />

                <com.google.android.material.chip.ChipGroup
                    android:id="@+id/meshExtenderApChipGroup"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    app:singleLine="true"
                    app:chipSpacingHorizontal="4dp">

                    <com.google.android.material.chip.Chip
                        android:id="@+id/meshExtenderApChipAp"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="AP"
                        android:textSize="10sp"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                    <com.google.android.material.chip.Chip
                        android:id="@+id/meshExtenderApChipMesh"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Mesh"
                        android:textSize="10sp"
                        app:chipMinHeight="24dp"
                        style="@style/Widget.Material3.Chip.Assist" />

                </com.google.android.material.chip.ChipGroup>

            </LinearLayout>
```

---

### G5g — `MeshUIBindings.kt` — Add extender AP view bindings

**File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt`  
**Lines:** 165 — NOT large — tool edit allowed

#### G5g-1 — Add `lateinit var` fields (extend the additions from C5a)

**Add after `internetWifiChipWeb` field (already added in C5a):**

```kotlin
    lateinit var meshExtenderApButton: android.widget.ImageButton
    lateinit var meshExtenderApRow: android.widget.LinearLayout
    lateinit var meshExtenderApIpText: TextView
    lateinit var meshExtenderApChipAp: com.google.android.material.chip.Chip
    lateinit var meshExtenderApChipMesh: com.google.android.material.chip.Chip
```

#### G5g-2 — Bind in `bindDeferredViews()` (extend additions from C5b)

**Add after the `internetWifiChipWeb` binding:**

```kotlin
        meshExtenderApButton = view.findViewById(R.id.meshExtenderApButton)
        meshExtenderApRow = view.findViewById(R.id.meshExtenderApRow)
        meshExtenderApIpText = view.findViewById(R.id.meshExtenderApIpText)
        meshExtenderApChipAp = view.findViewById(R.id.meshExtenderApChipAp)
        meshExtenderApChipMesh = view.findViewById(R.id.meshExtenderApChipMesh)
```

---

### G5h + G6a + G6b + G9 — `EnhancedMeshFragment.kt` — All new/updated UI logic

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 2419 — ⚠️ LARGE — **manual BEFORE/AFTER only**

#### G9 — Close scan pane on DISCONNECTED only (CORRECTED 2026-03-04)

**Location:** `meshStatusFlow` observer, `EnhancedMeshFragment.kt` lines 371–392.

> ⚠️ **G9 was applied previously with an incorrect multi-state trigger (`DISCONNECTED || ERROR || UNKNOWN || INITIALIZING`) and called `collapsePane()` which clears mode flags. This is the corrected spec. See Section 16 for full analysis evidence.**

**BEFORE (current on-disk — lines 371–392, verbatim from literal read 2026-03-04):**
```kotlin
		viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.meshStatusFlow.collect { status ->
                activity?.runOnUiThread {
                    MeshUIBindings.meshStatusText.text = status.toString()
                    updateButtonStates(status)
                    if (status == MeshStateDto.DISCONNECTED ||
                        status == MeshStateDto.ERROR ||
                        status == MeshStateDto.UNKNOWN ||
                        status == MeshStateDto.INITIALIZING) {
                        if (deferredViewsInitialized &&
                            MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE) {
                            collapsePane()
                        }
                    }
                }

                if (status == MeshStateDto.CONNECTED) {
                    Log.d("EnhancedMeshFragment", "[MESH_STATUS] Connected - role updates now automatic")
                    // Role updates happen automatically via EmergentRoleManager.startWifiStateMonitoring()
                }
            }
        }
```

**AFTER (corrected):**
```kotlin
		viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.meshStatusFlow.collect { status ->
                activity?.runOnUiThread {
                    MeshUIBindings.meshStatusText.text = status.toString()
                    updateButtonStates(status)
                    if (status == MeshStateDto.DISCONNECTED) {
                        if (deferredViewsInitialized &&
                            MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE) {
                            if (isCameraActive) stopQRScanning()
                            MeshUIBindings.meshExpandableContent.visibility = View.GONE
                            MeshUIBindings.expandCollapseIndicator.rotation = 0f
                        }
                    }
                }

                if (status == MeshStateDto.CONNECTED) {
                    Log.d("EnhancedMeshFragment", "[MESH_STATUS] Connected - role updates now automatic")
                    // Role updates happen automatically via EmergentRoleManager.startWifiStateMonitoring()
                }
            }
        }
```

**Why not call `collapsePane()`:** `collapsePane()` (line 1349) always executes `isJoinMeshMode = false` and `isMergeMeshMode = false`. These mode flags control which API (`joinMesh` vs `mergeMesh`) is called on QR scan success (read at lines 2119–2120: `val wasJoinMode = isJoinMeshMode` / `val wasMergeMode = isMergeMeshMode`, confirmed by literal reads 2026-03-04). Resetting them on a background `DISCONNECTED` event destroys the user's scan intent. The corrected implementation inlines only the pane-hide subset: camera stop (an active resource, not a setting), `meshExpandableContent.visibility = View.GONE`, `expandCollapseIndicator.rotation = 0f`.

---

#### G6b — WiFi button click: branch on connection state

**Location:** `setupListeners()` at line 897. Verified current click handler calls `showInternetWifiConnectionDialog()` unconditionally.

**BEFORE (line ~895–900; must verify exact anchor):**
```kotlin
		MeshUIBindings.wifiApConnectionButton.setOnClickListener {
			showInternetWifiConnectionDialog()
		}
```

**AFTER:**
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
```

---

#### G6a — WiFi button stop indicator text in `setupNonMeshWifiObserver()`

**Location:** Inside `setupNonMeshWifiObserver()` added in C6c (new private method after `setupNetworkInfoObserver()`). Extend the observer body to update WiFi button text.

**Updated full body of `setupNonMeshWifiObserver()` (replaces C6c AFTER block):**
```kotlin
	private fun setupNonMeshWifiObserver() {
		viewLifecycleOwner.lifecycleScope.launch {
			meshrabiyaApi.getNonMeshWifiStateFlow().collect { wifiState ->
				if (!deferredViewsInitialized) return@collect
				activity?.runOnUiThread {
					val isConnected = wifiState.status.name == "CONNECTED"
					// Internet WiFi row in Network Information Card
					MeshUIBindings.internetWifiRow.visibility =
						if (isConnected) View.VISIBLE else View.GONE
					if (isConnected) {
						val ipText = wifiState.internetConnectionIpAddress ?: "--"
						MeshUIBindings.internetWifiIpText.text = ipText
						MeshUIBindings.internetWifiChipSta.visibility = View.VISIBLE
						MeshUIBindings.internetWifiChipWeb.visibility =
							if (wifiState.hasInternetAccess) View.VISIBLE else View.GONE
					}
					// G6: Add stop indicator to WiFi button when Non-Mesh WiFi is connected
					// "◼" is the Unicode black medium square (U+25FC) used as a stop symbol
					val wifiButtonText = if (isConnected) "WiFi ◼" else "WiFi"
					MeshUIBindings.wifiApConnectionButton.text = wifiButtonText
					// G5h: Disable AP extender button when Non-Mesh WiFi is connected
					if (deferredViewsInitialized) {
						MeshUIBindings.meshExtenderApButton.isEnabled = !isConnected
					}
				}
			}
		}
	}
```

---

#### G5h — AP extender button visibility and `setupMeshExtenderObserver()`

**Location 1 (visibility in `setupRoleObserver()`):** Inside the role observer block where `wifiApConnectionButton` visibility is already managed (lines 714–718). Add AP button visibility logic for MESH_ROUTER role.

**⚠️ MUST verify exact anchor by literal read before applying. LARGE file — manual edit.**

**BEFORE (lines ~714–720, approximate; must verify):**
```kotlin
					val showInternetWifiButton = isInternetWifiFeatureAvailable()
					MeshUIBindings.wifiApConnectionButton.visibility =
						if (showInternetWifiButton) View.VISIBLE else View.GONE
```

**AFTER:**
```kotlin
					val showInternetWifiButton = isInternetWifiFeatureAvailable()
					MeshUIBindings.wifiApConnectionButton.visibility =
						if (showInternetWifiButton) View.VISIBLE else View.GONE
					// G5h: AP extender button appears when MESH_ROUTER role is present.
					// MESH_ROUTER means this device joined a mesh as a station and can
					// optionally run a hotspot to extend the mesh to nearby devices.
					val isMeshRouter = MeshRoleDto.MESH_ROUTER in roles
					MeshUIBindings.meshExtenderApButton.visibility =
						if (isMeshRouter) View.VISIBLE else View.GONE
```

**Location 2 — Add `setupMeshExtenderObserver()` method:** Add as new private method after `setupNonMeshWifiObserver()`.

```kotlin
	/**
	 * Observes the mesh extender hotspot state and updates the AP row in
	 * the Network Information Card. Must be called after deferred views are initialized.
	 */
	private fun setupMeshExtenderObserver() {
		viewLifecycleOwner.lifecycleScope.launch {
			meshrabiyaApi.meshExtenderHotspotStateFlow.collect { state ->
				if (!deferredViewsInitialized) return@collect
				activity?.runOnUiThread {
					val isActive = state == MeshExtenderHotspotStateDto.ACTIVE
					MeshUIBindings.meshExtenderApRow.visibility =
						if (isActive) View.VISIBLE else View.GONE
					if (isActive) {
						// Attempt to get the AP interface IP from hotspot info
						val hotspotInfo = try {
							meshrabiyaApi.getHotspotInfo()
						} catch (e: Exception) {
							null
						}
						val apIp = hotspotInfo?.nodeAddress ?: "--"
						MeshUIBindings.meshExtenderApIpText.text = apIp
					}
					// Update extender button appearance to reflect running state
					val buttonText = when (state) {
						MeshExtenderHotspotStateDto.ACTIVE -> "AP ◼"
						MeshExtenderHotspotStateDto.STARTING -> "AP…"
						MeshExtenderHotspotStateDto.STOPPING -> "AP…"
						else -> null  // button visibility managed by role observer
					}
					if (buttonText != null && MeshUIBindings.meshExtenderApButton.visibility == View.VISIBLE) {
						// If button has a text label (ImageButton may not — verify in G5f)
						// MeshUIBindings.meshExtenderApButton.contentDescription = buttonText
					}
				}
			}
		}
	}
```

**⚠️ NOTE on `getHotspotInfo()` call inside observer:** `getHotspotInfo()` is a suspend function per prior read. The call inside `runOnUiThread` would be incorrect. Extract to before `runOnUiThread` or call inside the `launch` block before the `runOnUiThread` block. Adjust accordingly when implementing.

**Location 3 — AP button click handler:** Add inside `setupListeners()` after the WiFi button click handler.

```kotlin
		// G5h: AP extender button — start or stop mesh extender hotspot
		// G5h: AP extender button — start or stop mesh extender hotspot
		// Scope correction from Round 2: use (Result<Unit>) -> Unit lambda, not SimpleCallback
		MeshUIBindings.meshExtenderApButton.setOnClickListener {
			val currentState = meshrabiyaApi.meshExtenderHotspotStateFlow.value
			if (currentState == MeshExtenderHotspotStateDto.ACTIVE) {
				meshrabiyaApi.stopMeshExtenderHotspot { result ->
					if (result.isSuccess) {
						Log.i("EnhancedMeshFragment", "[EXTENDER] Mesh extender hotspot stopped")
					} else {
						Log.e("EnhancedMeshFragment", "[EXTENDER] Failed to stop: ${result.exceptionOrNull()?.message}")
					}
				}
			} else if (currentState == MeshExtenderHotspotStateDto.INACTIVE) {
				meshrabiyaApi.startMeshExtenderHotspot { result ->
					if (result.isSuccess) {
						Log.i("EnhancedMeshFragment", "[EXTENDER] Mesh extender hotspot started")
					} else {
						Log.e("EnhancedMeshFragment", "[EXTENDER] Failed to start: ${result.exceptionOrNull()?.message}")
						// TODO: show snackbar with reason
					}
				}
			}
		}
```

**Location 4 — Call `setupMeshExtenderObserver()` from `onViewCreated()`:**

Extend the C6d AFTER block to include `setupMeshExtenderObserver()`:

**BEFORE (extends C6d AFTER, lines ~393–397):**
```kotlin
		setupNetworkInfoObserver()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")

		// Setup observer for non-mesh WiFi state — internet WiFi row in Network Information Card
		setupNonMeshWifiObserver()
```

**AFTER:**
```kotlin
		setupNetworkInfoObserver()
		android.util.Log.e("EnhancedMeshFragment", "[LIFECYCLE] Network info observer setup complete")

		// Setup observer for non-mesh WiFi state — internet WiFi row in Network Information Card
		setupNonMeshWifiObserver()
		// Setup observer for mesh extender (AP extension) hotspot state — AP row in Network Information Card
		setupMeshExtenderObserver()
```

---

## 12. G5–G9 Open Questions — STATUS AFTER ROUND 2 VERIFICATION (2026-03-04)

All questions below were resolved by literal file reads. See Section 15 for complete evidence.

1. **`LocalHotspotRequest` structure** — ✅ **RESOLVED.** File: `LocalHotspotRequest.kt`. Current fields: `preferredBand: ConnectBand` and `preferredType: HotspotType` ONLY. No passphrase field exists. **Path A confirmed:** must add `preferredPassphrase: String? = null` as a third field. Also confirmed: `MeshrabiyaWifiManagerAndroid.requestHotspot()` at line 400 calls `localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)` — passphrase must be threaded through `request.preferredPassphrase`.

2. **`setWifiHotspotEnabled()` signature** — ✅ **RESOLVED.** `VirtualNode.kt` lines 1280–1302: `open suspend fun setWifiHotspotEnabled(enabled: Boolean, preferredBand: ConnectBand = ConnectBand.BAND_2GHZ, hotspotType: HotspotType = HotspotType.AUTO): LocalHotspotResponse?`. No passphrase param. Must add `preferredPassphrase: String? = null` and pass it into the `LocalHotspotRequest(...)` construction. **VirtualNode.kt is 1492 lines — LARGE file rule applies.** This is a new file in the modification list.

3. **`SimpleCallback` interface** — ✅ **RESOLVED.** `SimpleCallback` does NOT exist anywhere in the project. The existing callback pattern throughout `MeshrabiyaApi.kt` is `(Result<Unit>) -> Unit` lambda (e.g., `fun startMesh(callback: (Result<Unit>) -> Unit)`, `fun stopMesh(callback: (Result<Unit>) -> Unit)`). **All G5d, G5c-4, G5c-5 specs must use `(Result<Unit>) -> Unit` as the callback type.** The `SimpleCallback` interface references in Sections 11 and G5h click handler are incorrect and updated in Section 15.

4. **`ic_wifi_tethering` drawable** — ✅ **RESOLVED.** `ic_wifi_tethering` does NOT exist in `app/src/main/res/drawable/`. `ic_wifi.xml` IS present. **Use `@drawable/ic_wifi` for the AP extender button icon.** Other available candidates: `ic_mesh_network.xml`, `ic_broadcast.xml`.

5. **`MeshRoleDto.MESH_ROUTER`** — ✅ **RESOLVED.** `DtoModels.kt` line 546: `enum class MeshRoleDto { MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, MESH_HUB, TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY }`. `MESH_ROUTER` is valid. Already used in `EnhancedMeshFragment.kt` at lines 664 and 667 (`MeshRoleDto.MESH_ROUTER in rolesDto`).

6. **`getHotspotInfo()` call inside coroutine** — ✅ **CONFIRMED / design note.** `getHotspotInfo()` is a regular (non-suspend) function per `MeshrabiyaApi.kt` signature `fun getHotspotInfo(): HotspotInfoDto?`. It can be called inside `runOnUiThread {}` with no coroutine issue. However, the API docs note it returns null if mesh is not CONNECTED, so the call should guard for null. Implementation: call `meshrabiyaApi.getHotspotInfo()` inside the `runOnUiThread {}` block and null-check the result.

7. **`meshExtenderApButton` type** — ✅ **RESOLVED (design decision).** Use `ImageButton` with `@drawable/ic_wifi` icon. Icon is updated to show AP state via `contentDescription` changes. If a textual stop indicator is needed for the button (like the WiFi button's "WiFi ◼"), prefer changing to a plain `Button` — but for this MVP, `ImageButton` with an icon is sufficient and consistent with the existing action button row style.

---

## 13. Updated Order of Work (C1–C6 + G5–G9)

Apply changes in this exact sequence:

**Phase 1 — Data/DTO layer (no compile dependencies):**
1. ✅ **C1** — `DtoModels.kt`: Added `hasInternetAccess: Boolean = false` and `internetConnectionIpAddress: String? = null` to `NonMeshWifiConnectionStateDto` — **DONE 2026-03-04**
2. ✅ **G5e** — `DtoModels.kt`: Added `MeshExtenderHotspotStateDto` enum (INACTIVE, STARTING, ACTIVE, STOPPING) — **DONE 2026-03-04**

**Phase 2 — API interface (defines contracts):**
3. ✅ **G5d** — `MeshrabiyaApi.kt`: Added `startMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)`, `stopMeshExtenderHotspot(callback: (Result<Unit>) -> Unit)`, `val meshExtenderHotspotStateFlow: StateFlow<MeshExtenderHotspotStateDto>` — **DONE 2026-03-04**

**Phase 3 — Infrastructure (hotspot machinery, bottom-up through call chain):**
4. ✅ **G5a** — `LocalOnlyHotspotManager.kt`: Added `passphrase: String? = null` param; uses `passphrase ?: "meshtest12"` at `.setPassphrase()` call — **DONE 2026-03-04**
5. ✅ **G5b** — `MeshrabiyaWifiManagerAndroid.kt` (**LARGE**, manual): Line 413 on disk: `localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)` — **DONE 2026-03-04** (verified by literal grep)  
   ✅ **G5-LHR** — `LocalHotspotRequest.kt`: Added `val preferredPassphrase: String? = null` third field — **DONE 2026-03-04**
6. ✅ **G5-VN** — `VirtualNode.kt` (**LARGE**, 1492 lines, manual): `preferredPassphrase: String? = null` param at line 1284; passed into `LocalHotspotRequest` at line 1292 — **DONE 2026-03-04** (verified by literal grep)

**Phase 4 — API implementation:**
7. ✅ **C2a, C2b, C2c, C2d** — `MeshrabiyaWifiManagerAndroid.kt` (**LARGE**, manual): `InternetWifiNetworkState` data class at line 244; `_internetWifiNetworkState` MutableStateFlow at line 250; `internetWifiNetworkStateFlow` at line 252; callback overrides + `disconnectFromInternetWifi` reset — **DONE 2026-03-04** (verified by literal grep)
8. ✅ **C3a** — `MeshrabiyaApiImpl.kt` (**LARGE**, manual): `connectToNonMeshWifi()` collects `internetWifiNetworkStateFlow` on success — **DONE 2026-03-04** (verified in prior session)
9. ✅ **G5c-1 thru G5c-5** — `MeshrabiyaApiImpl.kt` (**LARGE**, manual): `lastJoinedMeshPassphrase` at line 132; stored in `joinMesh()` at line 697; `_meshExtenderHotspotState` + `meshExtenderHotspotStateFlow` present; `startMeshExtenderHotspot()` at line 434 — **DONE 2026-03-04** (verified by literal grep)

**Phase 5 — XML layout:**
10. ✅ **C4** — `fragment_mesh_enhanced_deferred.xml`: Network Information Card rebuilt with meshIpRow/chips, internetWifiRow/chips, meshExtenderApRow/chips, networkStatsText — **DONE 2026-03-04**
11. ✅ **G5f** — `fragment_mesh_enhanced_deferred.xml`: Added `meshExtenderApButton` ImageButton (using `@drawable/ic_wifi`) before card 4; `meshExtenderApRow` included in C4 card — **DONE 2026-03-04**

**Phase 6 — View bindings:**
12. ✅ **C5a, C5b** — `MeshUIBindings.kt`: Added new view bindings for mesh IP row and internet WiFi row; commented out `nodeInfoText` binding — **DONE 2026-03-04**
13. ✅ **G5g** — `MeshUIBindings.kt`: Added `meshExtenderApButton`, `meshExtenderApRow`, `meshExtenderApIpText`, `meshExtenderApChipAp`, `meshExtenderApChipMesh` fields + bindings — **DONE 2026-03-04**

**Phase 7 — Fragment UI logic (all LARGE file manual edits, bottom-to-top within file):**
14. ✅ **C6a** — `EnhancedMeshFragment.kt`: `updateUI()` mesh IP section updated at lines 1153–1183 on disk — **DONE 2026-03-04** (verified by literal read)
15. ✅ **C6b** — `EnhancedMeshFragment.kt`: `setupNetworkInfoObserver()` uses `meshIpAddressText` at line 756 on disk — **DONE 2026-03-04** (verified by literal grep)
16. ✅ **C6c / G6a** — `EnhancedMeshFragment.kt`: `setupNonMeshWifiObserver()` defined at line 765 on disk — **DONE 2026-03-04** (verified by literal grep)
17. ✅ **G5h (method)** — `EnhancedMeshFragment.kt`: `setupMeshExtenderObserver()` defined at line 790 on disk — **DONE 2026-03-04** (verified by literal grep)
18. ✅ **G6b** — `EnhancedMeshFragment.kt`: WiFi button click handler calls `disconnectFromNonMeshWifi()` at line 958 on disk — **DONE 2026-03-04** (verified by literal grep)
19. ✅ **G5h (click)** — `EnhancedMeshFragment.kt`: `meshExtenderApButton.setOnClickListener` at line 968 on disk — **DONE 2026-03-04** (verified by literal grep)
20. ✅ **G5h (role)** — `EnhancedMeshFragment.kt`: `meshExtenderApButton.visibility` controlled by `isMeshRouter` at line 731 on disk — **DONE 2026-03-04** (verified by literal grep)
21. ⬜ **G9 (CORRECTION)** — `EnhancedMeshFragment.kt`: Correct `meshStatusFlow` observer (lines 371–392 on disk): narrow trigger from `DISCONNECTED || ERROR || UNKNOWN || INITIALIZING` to `DISCONNECTED` only; replace `collapsePane()` call with inline pane-hide (camera stop + `meshExpandableContent.visibility = View.GONE` + `expandCollapseIndicator.rotation = 0f`) — see Section 16 + G9 spec in Section 11 for full corrected BEFORE/AFTER — **⚠️ LARGE FILE — MANUAL EDIT REQUIRED**
22. ✅ **C6d + G5h(call)** — `EnhancedMeshFragment.kt`: `setupNonMeshWifiObserver()` at line 401 + `setupMeshExtenderObserver()` at line 402 called in `onViewCreated()` — **DONE 2026-03-04** (verified by literal grep)

**Build/verify after each Phase is complete** before starting the next.

---

## 14. Updated Large File Rule Summary

| File | Lines | Edit Method | Notes |
|------|-------|-------------|-------|
| `MeshrabiyaWifiManagerAndroid.kt` | 1087 | ⚠️ Manual BEFORE/AFTER only | |
| `MeshrabiyaApiImpl.kt` | 2062 | ⚠️ Manual BEFORE/AFTER only | |
| `EnhancedMeshFragment.kt` | 2419 | ⚠️ Manual BEFORE/AFTER only | |
| `VirtualNode.kt` | 1492 | ⚠️ Manual BEFORE/AFTER only | **NEW** — added in G5-VN |
| `DtoModels.kt` | 751 | Tool edits allowed | |
| `MeshrabiyaApi.kt` | ~440 | Tool edits allowed | |
| `MeshUIBindings.kt` | 165 | Tool edits allowed | |
| `fragment_mesh_enhanced_deferred.xml` | 546 | Tool edits allowed | |
| `LocalOnlyHotspotManager.kt` | 291 | Tool edits allowed | |
| `LocalHotspotRequest.kt` | <20 | Tool edits allowed | **NEW** — add `preferredPassphrase` field |

---

## 15. Round 2 Verification Evidence (2026-03-04)

All statements in this section are backed by literal file reads. File paths and line numbers are as read from disk.

---

### 15.1 — `LocalHotspotRequest` Structure

**File read:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalHotspotRequest.kt` (entire file, 8 lines)

**Verbatim content from disk:**
```kotlin
package com.ustadmobile.meshrabiya.vnet.wifi

data class LocalHotspotRequest(
    val preferredBand: ConnectBand,
    val preferredType: HotspotType,
) {
}
```

**Conclusion:** `LocalHotspotRequest` has exactly two fields: `preferredBand` and `preferredType`. There is no `passphrase`, `preferredPassphrase`, or any credential field. Path A is the only viable approach: add `val preferredPassphrase: String? = null` as a third field.

---

### 15.2 — `setWifiHotspotEnabled()` Signature in `VirtualNode.kt`

**File read:** `VirtualNode.kt` lines 1278–1320

**Verbatim signature from disk (lines 1280–1286):**
```kotlin
    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
    ): LocalHotspotResponse? {
```

**Verbatim `LocalHotspotRequest` construction (lines 1287–1293):**
```kotlin
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                )
            )
```

**Conclusion:** No passphrase parameter. No passphrase forwarding. `VirtualNode.kt` is 1492 lines (LARGE). The new `preferredPassphrase: String? = null` parameter must be added and threaded into `LocalHotspotRequest(...)`. Existing callers (`startMesh()` in `MeshrabiyaApiImpl.kt` lines 352–356 and `stopMesh()` at lines 403–408) use only `enabled`, `preferredBand`, `hotspotType` — the `= null` default on the new param means they compile without modification.

---

### 15.3 — `SimpleCallback` / Callback Pattern in `MeshrabiyaApi.kt`

**File read:** `MeshrabiyaApi.kt` lines 1–440 (full file)

**Grep result:** Searching `SimpleCallback|interface.*Callback` across all `.kt` files — 0 matches in `MeshrabiyaApi.kt`.

**Verified callback pattern from disk (lines 63–64 of `MeshrabiyaApi.kt`):**
```kotlin
    fun startMesh(callback: (Result<Unit>) -> Unit)
    fun stopMesh(callback: (Result<Unit>) -> Unit)
```

**Conclusion:** `SimpleCallback` does not exist anywhere in the project. The established callback type is `(Result<Unit>) -> Unit` lambda. All new API methods must use this type. All `callback.onSuccess()` and `callback.onFailure()` references in earlier plan drafts are incorrect and replaced with `callback(Result.success(Unit))` and `callback(Result.failure(e))` respectively.

---

### 15.4 — Available Drawables for AP Button Icon

**Directory listed:** `app/src/main/res/drawable/`

**`ic_wifi_tethering` found:** NO — not present.

**Available relevant drawables present on disk:**
- `ic_wifi.xml` — ✅ present
- `ic_mesh_network.xml` — ✅ present
- `ic_broadcast.xml` — ✅ present
- `ic_share_24.xml` — ✅ present

**Conclusion:** Use `@drawable/ic_wifi` for the AP extender button. This is the most semantically appropriate available icon for a WiFi AP action.

---

### 15.5 — `MeshRoleDto` Enum Constants

**File read:** `DtoModels.kt` lines 543–551

**Verbatim from disk:**
```kotlin
// --- MeshRole DTO ---
enum class MeshRoleDto {
    MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, MESH_HUB, TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
}
```

**Usage in `EnhancedMeshFragment.kt` (grep results, lines 664 and 667):**
```kotlin
                    if (MeshRoleDto.MESH_ROUTER in rolesDto && MeshRoleDto.MESH_ROUTER !in previousRolesDto) {
                    if (MeshRoleDto.MESH_ROUTER !in rolesDto && MeshRoleDto.MESH_ROUTER in previousRolesDto) {
```

**Conclusion:** `MeshRoleDto.MESH_ROUTER` is a confirmed valid enum constant, already in active use in `EnhancedMeshFragment.kt`. The variable holding the roles in `setupRoleObserver()` is named `rolesDto` (type `Set<MeshRoleDto>`).

---

### 15.6 — `getHotspotInfo()` Return Type (suspend vs. regular)

**File read:** `MeshrabiyaApi.kt` lines 67–80 (the `getHotspotInfo` declaration)

**Verbatim from disk:**
```kotlin
    fun getHotspotInfo(): HotspotInfoDto?
```

**Conclusion:** `getHotspotInfo()` is a **regular (non-suspend)** function. It can be called directly inside `runOnUiThread {}` without any coroutine wrapper. The prior plan's concern that it was `suspend` was incorrect. Guard for null return since it returns null if mesh is not CONNECTED.

---

### 15.7 — `eventMonitoringScope` in `MeshrabiyaApiImpl.kt`

**File read:** `MeshrabiyaApiImpl.kt` lines 75–165

**Verbatim from disk (line 151):**
```kotlin
    private val eventMonitoringScope = CoroutineScope(Dispatchers.Default)
```

**Conclusion:** `eventMonitoringScope` exists and is `CoroutineScope(Dispatchers.Default)`. All new coroutine launches in `startMeshExtenderHotspot()` and `stopMeshExtenderHotspot()` must use `eventMonitoringScope.launch { }`. The earlier plan draft used `CoroutineScope(Dispatchers.IO).launch { }` which is incorrect and replaced in Section 11.

---

### 15.8 — `_nonMeshWifiState` Location and Adjacent Code in `MeshrabiyaApiImpl.kt`

**File read:** `MeshrabiyaApiImpl.kt` lines 115–165

**Verbatim from disk (lines 115–130):**
```kotlin
    private val _networkInfoFlow = MutableStateFlow<NetworkInfoDto?>(null)
    val networkInfoFlow: StateFlow<NetworkInfoDto?> = _networkInfoFlow.asStateFlow()
    // StateFlow for mesh status
    private val _meshStatusFlow = MutableStateFlow(getMeshStatus())
    override val meshStatusFlow: StateFlow<MeshStateDto> get() = _meshStatusFlow

    // --- Network Overview Metrics StateFlow ---
    private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto(0L, 0L, 0))
    override val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()

    // Non-mesh WiFi connection state Flow — updated by connectToNonMeshWifi/disconnectFromNonMeshWifi
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE))

    private var metricsMonitorJob: Job? = null
    private var distributedStorageManager: DistributedStorageManager? = null
```

**Conclusion:** `_nonMeshWifiState` is declared at line 126. The new `_meshExtenderHotspotState` and `lastJoinedMeshPassphrase` fields must be inserted between line 126 and the `metricsMonitorJob` declaration at line 128. The BEFORE/AFTER in G5c-1 (Section 11) uses this exact anchor.

---

### 15.9 — `joinMesh()` Passphrase Parse Location

**File read:** `MeshrabiyaApiImpl.kt` lines 620–680

**Verbatim from disk (the relevant block):**
```kotlin
        // Launch connection in event monitoring scope (survives beyond this call)
        eventMonitoringScope.launch {
            try {
                // TODO PT8: If mesh is CONNECTED, broadcast MeshMergeAnnouncementMessage
                // and wait 5 seconds for propagation before connecting
                
                // Parse QR code JSON data
                val qrJson = org.json.JSONObject(jsonQrData)
                val password = qrJson.getString("password")
                val ssidPattern = qrJson.optString("ssidPattern", "meshr-")  // Default to "meshr-"
                val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
```

**Conclusion:** `val password = qrJson.getString("password")` is within the coroutine launched at approximately line 636. The insert point for `lastJoinedMeshPassphrase = password` is immediately after this line. The G5c-2 BEFORE/AFTER in Section 11 reflects this exact code.

---

### 15.10 — `requestHotspot()` in `MeshrabiyaWifiManagerAndroid.kt` – Full LOCALONLY_HOTSPOT Branch

**File read:** `MeshrabiyaWifiManagerAndroid.kt` lines 340–430

**Verbatim from disk (the `LOCALONLY_HOTSPOT` branch, lines ~393–405):**
```kotlin
                HotspotType.LOCALONLY_HOTSPOT -> {
                    wifiDirectManager.stopWifiDirectGroup()
                    localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand)
                }
                else -> {
                    //Do nothing
                }
            }
```

**Conclusion:** Confirmed single call at this branch. Only `request.preferredBand` is passed. After G5b, it becomes `localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)`.

---

### 15.11 — `LocalOnlyHotspotManager.startLocalOnlyHotspot()` — Full Current Code

**File read:** `LocalOnlyHotspotManager.kt` lines 110–165

**Verbatim current function signature and API 33+ passphrase line from disk:**
```kotlin
    suspend fun startLocalOnlyHotspot(
        preferredBand: ConnectBand,
    ) {
        logger(Log.INFO, "$logPrefix startLocalOnlyHotspot: band=$preferredBand")
        if(Build.VERSION.SDK_INT >= 33) {
            ...
                .setSsid("meshr-${localNodeAddr.encodeAsHex()}")
                .setPassphrase("meshtest12", SECURITY_TYPE_WPA2_PSK)
                .setBssid(macAddr)
```

**Conclusion:** Function signature is `suspend fun startLocalOnlyHotspot(preferredBand: ConnectBand)`. The hardcoded passphrase `"meshtest12"` is at the `.setPassphrase(...)` call in the API 33+ `UnhiddenSoftApConfigurationBuilder` chain. The G5a BEFORE/AFTER in Section 11 correctly reflects this code.

---

### 15.12 — `MeshrabiyaApiImpl.kt` Call to `setWifiHotspotEnabled()` in `startMesh()`

**File read:** `MeshrabiyaApiImpl.kt` lines 340–420

**Verbatim from disk (lines 352–356):**
```kotlin
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
```

**Conclusion:** `startMesh()` calls `setWifiHotspotEnabled(enabled, preferredBand, hotspotType)` — three named params. After G5-VN adds `preferredPassphrase: String? = null` as a 4th param with a default, this call compiles unchanged. The new `startMeshExtenderHotspot()` method calls the same function with all four params: `setWifiHotspotEnabled(enabled = true, preferredBand = ConnectBand.BAND_5GHZ, hotspotType = HotspotType.AUTO, preferredPassphrase = pw)`.

---

### 15.13 — Complete Passphrase Threading Chain (verified end-to-end)

The full path from stored passphrase to `setPassphrase()` call, all nodes verified by literal read:

```
MeshrabiyaApiImpl.startMeshExtenderHotspot()
  → reads: lastJoinedMeshPassphrase (stored in joinMesh() from QR parse)
  → calls: myNode?.setWifiHotspotEnabled(enabled=true, preferredBand, hotspotType, preferredPassphrase=pw)
             [VirtualNode.kt line 1280 — NEW param preferredPassphrase: String? = null]
  → calls: meshrabiyaWifiManager.requestHotspot(requestMessageId, LocalHotspotRequest(preferredBand, preferredType, preferredPassphrase))
             [LocalHotspotRequest.kt — NEW field preferredPassphrase: String? = null]
  → calls: localOnlyHotspotManager.startLocalOnlyHotspot(request.preferredBand, request.preferredPassphrase)
             [MeshrabiyaWifiManagerAndroid.kt line 400 — single call-site updated]
  → calls: UnhiddenSoftApConfigurationBuilder().setPassphrase(passphrase ?: "meshtest12", SECURITY_TYPE_WPA2_PSK)
             [LocalOnlyHotspotManager.kt — NEW passphrase: String? = null param]
```

Files modified in this chain (in order of change application):
1. `LocalHotspotRequest.kt` — add field (G5-LHR, NOT large)
2. `LocalOnlyHotspotManager.kt` — add param, use in setPassphrase (G5a, NOT large)
3. `MeshrabiyaWifiManagerAndroid.kt` — forward at call-site (G5b, LARGE)
4. `VirtualNode.kt` — add param, pass to LocalHotspotRequest (G5-VN, LARGE)
5. `MeshrabiyaApiImpl.kt` — add field, store in joinMesh(), implement methods (G5c, LARGE)

---

## 16. G9 Correction Analysis (2026-03-04)

All statements backed by literal file reads from disk. No assumptions made.

### 16.1 — Current G9 On-Disk State

**File read:** `EnhancedMeshFragment.kt` lines 371–392, verbatim.

```kotlin
		viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.meshStatusFlow.collect { status ->
                activity?.runOnUiThread {
                    MeshUIBindings.meshStatusText.text = status.toString()
                    updateButtonStates(status)
                    if (status == MeshStateDto.DISCONNECTED ||
                        status == MeshStateDto.ERROR ||
                        status == MeshStateDto.UNKNOWN ||
                        status == MeshStateDto.INITIALIZING) {
                        if (deferredViewsInitialized &&
                            MeshUIBindings.meshExpandableContent.visibility == View.VISIBLE) {
                            collapsePane()
                        }
                    }
                }

                if (status == MeshStateDto.CONNECTED) {
                    Log.d("EnhancedMeshFragment", "[MESH_STATUS] Connected - role updates now automatic")
                    // Role updates happen automatically via EmergentRoleManager.startWifiStateMonitoring()
                }
            }
        }
```

**Problem 1 — Incorrect trigger set:** trigger fires on `DISCONNECTED || ERROR || UNKNOWN || INITIALIZING`. Required: fire on `DISCONNECTED` only.

**Problem 2 — `collapsePane()` clears mode flags:** see §16.3.

---

### 16.2 — `MeshStateDto` Enum Values

**File read:** `DtoModels.kt` line 34, verbatim:

```kotlin
enum class MeshStateDto {
    INITIALIZING, CONNECTING, CONNECTED, DISCONNECTED, ERROR, UNKNOWN;
}
```

**Conclusion:** Six values total. Corrected trigger must match exclusively on `DISCONNECTED`.

---

### 16.3 — `collapsePane()` Implementation

**File read:** `EnhancedMeshFragment.kt` lines 1349–1368, verbatim:

```kotlin
	private fun collapsePane() {
		android.util.Log.d("EnhancedMeshFragment", "collapsePane()")
		
		// Stop camera if active
		if (isCameraActive) {
			stopQRScanning()
		}
		
		// Hide expandable content
		MeshUIBindings.meshExpandableContent.visibility = View.GONE
		MeshUIBindings.expandCollapseIndicator.rotation = 0f  // Point down
		
		// Reset mode flags
		isJoinMeshMode = false
		isMergeMeshMode = false
	}
```

**Settings cleared by `collapsePane()`:**
- `isJoinMeshMode = false` (line 1362)
- `isMergeMeshMode = false` (line 1363)

These must NOT be cleared on the DISCONNECTED trigger.

---

### 16.4 — `isJoinMeshMode` and `isMergeMeshMode` Usage

**File reads:** `EnhancedMeshFragment.kt` lines 134–135 (declarations), lines 2119–2120 (usage in QR scan success handler), verbatim:

```kotlin
// Line 134:
	private var isJoinMeshMode = false     // True when Join Mesh button clicked
// Line 135:
	private var isMergeMeshMode = false    // True when Merge Mesh button clicked
```

```kotlin
// Lines 2119–2120 (QR scan success, inside processQRCodeResult()):
		// **CRITICAL**: Save mode flags BEFORE collapsePane resets them
		val wasJoinMode = isJoinMeshMode
		val wasMergeMode = isMergeMeshMode
```

`wasJoinMode` is then used at line 2148 to branch between `joinMesh()` and `mergeMesh()` API calls. Resetting `isJoinMeshMode`/`isMergeMeshMode` on a background DISCONNECTED event (triggered before a QR scan completes, e.g., during a brief network drop) would cause `wasJoinMode == false` and `wasMergeMode == false`, resulting in neither API being called on scan success.

---

### 16.5 — `stopQRScanning()` — Is It a "Settings Clear"?

**File read:** `EnhancedMeshFragment.kt` lines 2040–2076, verbatim key assignments:

```kotlin
	private fun stopQRScanning() {
		// ...
		isCameraActive = false
		currentCamera = null
		MeshUIBindings.scanningStatusText.text = ""
	}
```

**Conclusion:** `stopQRScanning()` clears camera hardware state (`isCameraActive`, `currentCamera`, barcode scanner, flashlight) and clears `scanningStatusText`. None of these are "settings" per the requirement. Stopping the camera when the pane is forcibly hidden is correct behaviour — camera hardware should not keep running when the pane is closed.

---

### 16.6 — `expandCollapseIndicator` and `meshExpandableContent` Binding Location

**File read:** `MeshUIBindings.kt` lines 113–117 (inside `bindViews()`, non-deferred):

```kotlin
        expandCollapseIndicator = view.findViewById(R.id.expandCollapseIndicator)
        
        // Expandable content
        meshExpandableContent = view.findViewById(R.id.meshExpandableContent)
```

**Conclusion:** Both `expandCollapseIndicator` and `meshExpandableContent` are bound in `bindViews()` (non-deferred — available from fragment creation, before ViewStub inflation). The `deferredViewsInitialized` guard in the G9 condition is not required for these specific views, but is retained as a defensive check consistent with the existing implementation.

---

### 16.7 — Corrected G9 Design

**Required behaviour:** When `meshStatusFlow` emits `DISCONNECTED` and the scan/join pane is currently visible, close the pane. Do not clear `isJoinMeshMode` or `isMergeMeshMode`.

**Approach:** Do NOT call `collapsePane()`. Inline only the non-settings-clearing subset:
1. `if (isCameraActive) stopQRScanning()` — stop camera hardware (not a setting)
2. `MeshUIBindings.meshExpandableContent.visibility = View.GONE` — hide pane
3. `MeshUIBindings.expandCollapseIndicator.rotation = 0f` — reset chevron

`isJoinMeshMode` and `isMergeMeshMode` remain unchanged.

**No new method needed.** The inline approach is sufficient and avoids adding a new overload of `collapsePane()` that could be confused with the existing one.

**File to change:** `EnhancedMeshFragment.kt` — LARGE (2521 lines on disk 2026-03-04). Manual BEFORE/AFTER edit required. Full BEFORE/AFTER spec is in Section 11 (G9 corrected spec).
