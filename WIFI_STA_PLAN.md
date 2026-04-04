# WiFi STA/STA Concurrency — Analysis and Plan
**Date:** 2026-03-03  
**Status:** Analysis complete — NO code changes applied  
**Depends on:** WIFI_AP_CON_IMPLEMENTATION.md (all C1–C18 changes are assumed as a baseline)  
**Constraint:** No code changes. No assumptions. Every statement backed by literal file reads.

---

## 1. The STA/STA Scenario

The user's question is:

> "Will the MESH_ROUTER node be able to Join Mesh AND still use the new feature to connect to a Non-Mesh WiFi Network?"

"Join Mesh" means the device is in **station mode** — it connected to another node's mesh hotspot via `WifiNetworkSpecifier` + `requestNetwork()` inside `connectToHotspotInternal()`. To ALSO hold a connection to a non-mesh internet WiFi while in this state, the device needs to maintain **two simultaneous WiFi station (STA) connections**. This is:

- **AP+STA**: one WiFi Access Point (hotspot) + one Station. What `isStaApConcurrencySupported()` detects. Requires API 30. Currently detected and stored.
- **STA/STA**: two simultaneous Station connections to two different networks. Entirely separate capability. What `isStaStaConcurrencySupported()` detects. Requires API **31**. **Currently not detected anywhere in the codebase.**

These two capabilities are independent. A device can have one, both, or neither. The current MESH_ROUTER feature design handles only AP+STA. This document specifies what is needed to also support STA/STA.

---

## 2. The Two Operating Modes for "MESH_ROUTER + Internet WiFi"

| Mode | Device WiFi Roles | Concurrency Required | API Required |
|------|-------------------|---------------------|--------------|
| **AP+STA mode** (Start Mesh) | Hotspot (AP) + internet WiFi station (STA) | `isStaApConcurrencySupported` = true | API 30+ |
| **STA/STA mode** (Join Mesh) | Mesh station (STA) + internet WiFi station (STA) | `isStaStaConcurrencySupported` = true | API 31+ |

The C1–C18 changes in `WIFI_AP_CON_IMPLEMENTATION.md` address **AP+STA mode only**.

---

## 3. What Exists Today — Evidence

### 3.1 AP+STA Detection

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Lines 272–283 (literal read):**

```kotlin
private suspend fun detectConcurrentSupport(): Boolean {
    return if (Build.VERSION.SDK_INT >= 30) {
        delay(200)
        val supported = wifiManager.isStaApConcurrencySupported
        logger(Log.INFO, "$logPrefix isStaApConcurrencySupported = $supported (SDK ${Build.VERSION.SDK_INT})")
        supported
    } else {
        logger(Log.INFO, "$logPrefix Concurrent AP+Station not supported (SDK < 30, actual: ${Build.VERSION.SDK_INT})")
        false
    }
}
```

Called from the `init` block (lines 257–264):

```kotlin
// Detect concurrent AP+Station support after WiFi system initialization
nodeScope.launch {
    val supported = detectConcurrentSupport()
    _state.update { prev ->
        prev.copy(concurrentApStationSupported = supported)
    }
    logger(Log.INFO, "$logPrefix Concurrent AP+Station support detected: $supported")
}
```

### 3.2 The Only Concurrency Flag in State

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt`  
**Lines 9–15 (literal read):**

```kotlin
data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val wifiStationState: WifiStationState = WifiStationState(),
    val localOnlyHotspotState: LocalOnlyHotspotState = LocalOnlyHotspotState(),
    val errorCode: Int = 0,
    val concurrentApStationSupported: Boolean = false,
)
```

There is no `staStaConcurrencySupported` field. The state model cannot represent STA/STA capability.

### 3.3 MESH_ROUTER Assigned on Hardware Capability Alone — Not on Operating Mode

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`  
**Lines 427–434 (literal read):**

```kotlin
// MESH_ROUTER: assign whenever AP+Station concurrency support is true
android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] MESH_ROUTER check: concurrency=$concurrentApStationSupported")
if (concurrentApStationSupported) {
    roles.add(MeshRole.MESH_ROUTER)
    android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding MESH_ROUTER (hardware concurrency detected)")
    safeLog(LogLevel.INFO, "[ROLE_CALC] Assigned MESH_ROUTER – concurrency support present")
} else {
    android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✗ MESH_ROUTER NOT assigned (no concurrency)")
```

MESH_ROUTER is assigned whenever `concurrentApStationSupported = true` — regardless of whether the hotspot is running. Contrast with MESH_HUB (lines 443–449):

```kotlin
if (!concurrentApStationSupported && wifiState.hotspotIsStarted) {
    roles.add(MeshRole.MESH_HUB)
```

MESH_HUB requires `hotspotIsStarted`. MESH_ROUTER does not. A device in Join Mesh mode (pure STA) that has concurrent capability **already receives MESH_ROUTER role** after the C1–C18 changes go in, so the WiFi button (C16–C18) becomes visible in Join Mesh — but the underlying machinery will fail because it assumes AP mode.

### 3.4 `hotspotIsStarted` Is Computed From State

**File:** `MeshrabiyaWifiState.kt`, **lines 28–30 (literal read):**

```kotlin
val hotspotIsStarted: Boolean
    get() = wifiDirectState.hotspotStatus == HotspotStatus.STARTED
            || localOnlyHotspotState.status == HotspotStatus.STARTED
```

This computed property is available on any `MeshrabiyaWifiState` instance. It is the correct discriminator between AP mode and STA mode for the gating logic needed.

### 3.5 C9 Regression Under STA/STA (Regression in the Current Plan)

The C9 design in `WIFI_AP_CON_IMPLEMENTATION.md` changes `connectToHotspotInternal()` so that when `concurrentApStationSupported = true`, `bindProcessToNetwork` is skipped entirely. The intent is to protect MESH_ROUTER devices from having their internet WiFi sockets accidentally bound to the mesh.

But `connectToHotspotInternal()` is called for **every mesh station connection**, including a plain Join Mesh operation on a device that has AP+STA capability but whose hotspot is **not** running. On such a device, C9 would skip the mesh network binding, eliminating mesh routing through the station connection.

**Evidence — `connectToHotspotInternal()` lines 492–502 (literal read, current code):**

```kotlin
if (resultState.network != null) {
    logger(Log.INFO, "$logPrefix connectToHotspot: ${config.ssid} - success status=${resultState.status}")
    
    // CRITICAL: Bind all app sockets to this mesh network to prevent switching back to regular WiFi
    val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
    logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess", null)
    if (!bindSuccess) {
        logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
    }
    
    return resultState.network
```

The C9 AFTER condition `if (!_state.value.concurrentApStationSupported)` is too broad — it incorrectly skips binding for concurrent devices in STA-only mode. This is a regression that must be corrected whether or not STA/STA is supported.

---

## 4. API Level Capability Matrix

| Android Version | API Level | `isStaApConcurrencySupported` | `isStaStaConcurrencySupported` | Feature Available? | Mode |
|----------------|-----------|-------------------------------|--------------------------------|-------------------|------|
| ≤ 8.1 | < 29 | N/A | N/A | **NO** | — |
| 9 | 29 | N/A (API 30 req.) | N/A | **NO** | — |
| 10 | 30 | ✅ | N/A (API 31 req.) | **YES** — AP+STA only | Start Mesh only |
| 11 | 30\* | ✅ | N/A (API 31 req.) | **YES** — AP+STA only | Start Mesh only |
| 12 | 31 | ✅ | ✅ (if hardware supports) | **YES** — both modes | Start Mesh + Join Mesh |
| 13+ | 33+ | ✅ | ✅ (if hardware supports) | **YES** — both modes, full feature |  Start Mesh + Join Mesh |

\* Android 11 is API 30.  API 31 = Android 12.

**Graceful degradation model:**  
- `API < 30`: feature hidden entirely (no MESH_ROUTER role assigned, button never shown)  
- `API 30–30`: feature available in AP+STA mode only (Start Mesh path); Join Mesh path silently skipped per capability check  
- `API 31+` + `isStaStaConcurrencySupported = true`: full feature — both Start Mesh and Join Mesh paths available

---

## 5. Required Additional Changes (Beyond WIFI_AP_CON_IMPLEMENTATION.md C1–C18)

These are numbered S1–S7 ("STA plan") to distinguish from C1–C18 in the AP plan.

---

### S1 — `MeshrabiyaWifiState.kt` — Add `staStaConcurrencySupported` field ✅ COMPLETE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt`  
**Line count:** 64 — NOT a large file

**Rationale:** The state model currently has no field for STA/STA capability (confirmed: only `concurrentApStationSupported` at line 15). Every component that makes routing or UI decisions must be able to read this from the single source of truth (`_state`) without calling the WiFi manager API themselves.

**BEFORE (lines 9–15):**

```kotlin
data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val wifiStationState: WifiStationState = WifiStationState(),
    val localOnlyHotspotState: LocalOnlyHotspotState = LocalOnlyHotspotState(),
    val errorCode: Int = 0,
    val concurrentApStationSupported: Boolean = false,
)
```

**AFTER:**

```kotlin
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
    val staStaConcurrencySupported: Boolean = false,
)
```

---

### S2 — `MeshrabiyaWifiManagerAndroid.kt` — Detect STA/STA Capability, Update State ✅ COMPLETE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — ⚠️ LARGE FILE (manual edit required)

**Rationale:** `detectConcurrentSupport()` at lines 272–283 calls `wifiManager.isStaApConcurrencySupported` (API 30). A parallel check for `wifiManager.isStaStaConcurrencySupported` (API 31) must be added in the same `init` coroutine so both capabilities are resolved during initialization. Both results must be stored in `_state`.

**Evidence — `init` block coroutine (lines 257–264):**

```kotlin
// Detect concurrent AP+Station support after WiFi system initialization
nodeScope.launch {
    val supported = detectConcurrentSupport()
    _state.update { prev ->
        prev.copy(concurrentApStationSupported = supported)
    }
    logger(Log.INFO, "$logPrefix Concurrent AP+Station support detected: $supported")
}
```

**BEFORE (`detectConcurrentSupport()`, lines 272–283):**

```kotlin
private suspend fun detectConcurrentSupport(): Boolean {
    return if (Build.VERSION.SDK_INT >= 30) {
        // Brief delay to allow WiFi system to fully initialize
        delay(200)
        val supported = wifiManager.isStaApConcurrencySupported
        logger(Log.INFO, "$logPrefix isStaApConcurrencySupported = $supported (SDK ${Build.VERSION.SDK_INT})")
        supported
    } else {
        logger(Log.INFO, "$logPrefix Concurrent AP+Station not supported (SDK < 30, actual: ${Build.VERSION.SDK_INT})")
        false
    }
}
```

**AFTER — replace `detectConcurrentSupport()` with a combined capability detection, and update the init coroutine:**

Update `init` coroutine:

```kotlin
// Detect concurrent WiFi capabilities after WiFi system initialization
nodeScope.launch {
    val (apStaSupported, staStaSupported) = detectWifiConcurrencyCapabilities()
    _state.update { prev ->
        prev.copy(
            concurrentApStationSupported = apStaSupported,
            staStaConcurrencySupported = staStaSupported,
        )
    }
    logger(Log.INFO, "$logPrefix WiFi concurrency: AP+STA=$apStaSupported, STA+STA=$staStaSupported")
}
```

Replace `detectConcurrentSupport()` with:

```kotlin
/**
 * Detect device WiFi concurrency capabilities.
 * Returns Pair(concurrentApStationSupported, staStaConcurrencySupported).
 *
 * concurrentApStationSupported: device can run both a WiFi hotspot (AP) and a WiFi station (STA)
 *   simultaneously. Required for MESH_ROUTER Start Mesh + internet WiFi feature.
 *   API 30+: WifiManager.isStaApConcurrencySupported.
 *
 * staStaConcurrencySupported: device can hold two simultaneous WiFi station connections
 *   on different SSIDs. Required for MESH_ROUTER Join Mesh + internet WiFi (STA/STA).
 *   API 31+: WifiManager.isStaStaConcurrencySupported.
 * 
 * Delays briefly to allow the WiFi system to fully initialize before querying.
 */
private suspend fun detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean> {
    delay(200) // brief delay for WiFi system initialization (matches prior detectConcurrentSupport behavior)

    val apStaSupported = if (Build.VERSION.SDK_INT >= 30) {
        val result = wifiManager.isStaApConcurrencySupported
        logger(Log.INFO, "$logPrefix isStaApConcurrencySupported = $result (SDK ${Build.VERSION.SDK_INT})")
        result
    } else {
        logger(Log.INFO, "$logPrefix AP+STA not supported: SDK ${Build.VERSION.SDK_INT} < 30")
        false
    }

    val staStaSupported = if (Build.VERSION.SDK_INT >= 31) {
        val result = wifiManager.isStaStaConcurrencySupported
        logger(Log.INFO, "$logPrefix isStaStaConcurrencySupported = $result (SDK ${Build.VERSION.SDK_INT})")
        result
    } else {
        logger(Log.INFO, "$logPrefix STA/STA not supported: SDK ${Build.VERSION.SDK_INT} < 31")
        false
    }

    return apStaSupported to staStaSupported
}
```

---

### S3 — `MeshrabiyaWifiManagerAndroid.kt` — Fix C9 `bindProcessToNetwork` Gating (Regression Fix) ✅ COMPLETE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — ⚠️ LARGE FILE (manual edit required)

**Rationale:** C9 in `WIFI_AP_CON_IMPLEMENTATION.md` changes `connectToHotspotInternal()` to skip `bindProcessToNetwork` whenever `concurrentApStationSupported = true`. This is too broad: a device in **pure station mode (Join Mesh, hotspot not running)** with AP+STA capability needs the process-wide network bind to prevent Android from routing mesh packets off the mesh interface. Skipping it in this case silently breaks mesh routing.

The correct discriminator is `hotspotIsStarted` (computed property on `MeshrabiyaWifiState`, confirmed at lines 28–30 of `MeshrabiyaWifiState.kt`). Process-wide binding must be skipped only when the device is actively running its own hotspot alongside a STA internet connection — i.e., the AP+STA scenario.

**C9 AFTER (as written in WIFI_AP_CON_IMPLEMENTATION.md):**

```kotlin
if (!_state.value.concurrentApStationSupported) {
    val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
    logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess (non-concurrent)", null)
    if (!bindSuccess) {
        logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
    }
} else {
    logger(Log.INFO, "$logPrefix connectToHotspot: concurrent device — skipping process-wide network binding, using per-socket binding", null)
}
```

**S3 CORRECTED AFTER (replaces C9 AFTER):**

```kotlin
// Bind all process traffic to the mesh network, EXCEPT when this device is actively running
// its own hotspot (AP+STA mode). In AP+STA mode the MESH_ROUTER holds both a hotspot and
// an internet WiFi connection simultaneously; process-wide mesh binding would redirect
// internet-forwarding sockets onto the mesh, creating a routing loop.
//
// In pure station mode (Join Mesh, hotspot not running), even on concurrent-capable devices,
// the process-wide bind IS needed to keep mesh traffic on the mesh station interface.
//
// In STA/STA mode (Join Mesh + internet WiFi simultaneously), the internet WiFi connection
// is via WifiNetworkSuggestion which gets its own Network object. That socket is bound
// per-socket via Network.bindSocket() — the process-wide mesh bind does not interfere
// with it because WifiNetworkSuggestion sockets are bound at socket creation, overriding
// the process default.
val hotspotRunning = _state.value.hotspotIsStarted
val skipProcessBinding = hotspotRunning && _state.value.concurrentApStationSupported
if (!skipProcessBinding) {
    val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
    logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess (hotspotRunning=$hotspotRunning)", null)
    if (!bindSuccess) {
        logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
    }
} else {
    logger(Log.INFO, "$logPrefix connectToHotspot: AP+STA mode with hotspot running — skipping process-wide binding, using per-socket binding for mesh", null)
}
```

**Impact:** This fixes the regression for concurrent-capable Join Mesh devices while preserving the correct skip behavior for AP+STA hotspot-and-internet-WiFi mode.

---

### S4 — `MeshrabiyaWifiManagerAndroid.kt` — Gate `connectToInternetWifi()` on Correct Capability per Mode ✅ COMPLETE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — ⚠️ LARGE FILE (manual edit required)

**Rationale:** C10 in `WIFI_AP_CON_IMPLEMENTATION.md` guards `connectToInternetWifi()` with:

```kotlin
if (!_state.value.concurrentApStationSupported) {
    return Result.failure(IllegalStateException(
        "connectToInternetWifi requires concurrentApStationSupported == true"
    ))
}
```

This allows AP+STA devices to call the function, but rejects all other devices — even ones that have STA/STA for Join Mesh mode. The guard must be replaced with a mode-aware check:

- If hotspot is running (`hotspotIsStarted = true`) → check `concurrentApStationSupported` (API 30+)
- If hotspot is NOT running (pure station mode) → check `staStaConcurrencySupported` (API 31+)

**C10 guard BEFORE (from WIFI_AP_CON_IMPLEMENTATION.md):**

```kotlin
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    throw IllegalStateException("connectToInternetWifi requires API 30+ (concurrent AP+STA)")
}
if (!_state.value.concurrentApStationSupported) {
    throw IllegalStateException("connectToInternetWifi requires concurrentApStationSupported == true")
}
```

**S4 CORRECTED guard (replaces C10 guard):**

```kotlin
val currentState = _state.value
val hotspotRunning = currentState.hotspotIsStarted

if (hotspotRunning) {
    // AP+STA mode: hotspot is running, we want to add a simultaneous STA internet connection.
    // Requires isStaApConcurrencySupported (API 30).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return Result.failure(IllegalStateException(
            "Internet WiFi while hotspot running requires API 30 (AP+STA)"
        ))
    }
    if (!currentState.concurrentApStationSupported) {
        return Result.failure(IllegalStateException(
            "This device does not support concurrent AP+STA mode (isStaApConcurrencySupported = false)"
        ))
    }
} else {
    // STA/STA mode: device is in pure station mode (Join Mesh), wants to add a second STA
    // connection to internet WiFi. Requires isStaStaConcurrencySupported (API 31).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return Result.failure(IllegalStateException(
            "Internet WiFi while in Join Mesh mode requires API 31 (STA/STA)"
        ))
    }
    if (!currentState.staStaConcurrencySupported) {
        return Result.failure(IllegalStateException(
            "This device does not support simultaneous dual-STA mode (isStaStaConcurrencySupported = false)"
        ))
    }
}
```

---

### S5 — `MeshrabiyaApi.kt` + `MeshrabiyaApiImpl.kt` — Add `isInternetWifiFeatureAvailable()` ✅ COMPLETE

**Files:**  
`Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt` (391 lines, NOT large)  
`Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` (1975 lines, ⚠️ LARGE)

**Rationale:** The UI (C18 in `WIFI_AP_CON_IMPLEMENTATION.md`) currently determines button visibility by combining `MESH_ROUTER in roles` with a reflection-based access into `myNode`. Neither approach captures the operating-mode-aware logic in S4. A dedicated query method encapsulates the full decision — mode detection, capability check, API level check — in one place, testable and accessible to any UI component without reflection.

**Declaration to add in `MeshrabiyaApi.kt` (after `isTorActive()`):**

```kotlin
/**
 * Returns true when the internet WiFi connection feature is currently available —
 * meaning this device can both participate in the mesh AND hold a simultaneous
 * internet WiFi connection.
 *
 * Two paths to true:
 *   1. AP+STA mode: hotspot is running AND isStaApConcurrencySupported = true (API 30+)
 *   2. STA/STA mode: in Join Mesh (station mode) AND isStaStaConcurrencySupported = true (API 31+)
 *
 * Returns false when:
 *   - Mesh is not initialized
 *   - API < 30
 *   - Device has neither AP+STA nor STA/STA capability
 *   - In Join Mesh but device does not support STA/STA (API < 31 or isStaStaConcurrencySupported = false)
 */
fun isInternetWifiFeatureAvailable(): Boolean
```

**Implementation to add in `MeshrabiyaApiImpl.kt` (alongside other new implementations from C15):**

```kotlin
override fun isInternetWifiFeatureAvailable(): Boolean {
    val node = myNode ?: return false
    val wifiState = node.meshrabiyaWifiManager.state.value  // direct value access via _state backing property

    // AP+STA mode: hotspot is running and device supports concurrent AP+STA
    if (wifiState.hotspotIsStarted && wifiState.concurrentApStationSupported) {
        return true
    }

    // STA/STA mode: device is in pure station mode and supports simultaneous dual-STA
    if (!wifiState.hotspotIsStarted &&
        wifiState.wifiStationState.status == WifiStationState.Status.AVAILABLE &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        wifiState.staStaConcurrencySupported) {
        return true
    }

    return false
}
```

Note: `MeshrabiyaWifiManager.state` is a `Flow<MeshrabiyaWifiState>` (not a `StateFlow`). To call `.value` the field being accessed is `_state` (a `MutableStateFlow`) on `MeshrabiyaWifiManagerAndroid`. C5 in `WIFI_AP_CON_IMPLEMENTATION.md` already adds `val concurrentApStationSupported: Boolean get() = _state.value.concurrentApStationSupported`. A matching property for `staStaConcurrencySupported` should be added in the same file (see S6).

---

### S6 — `MeshrabiyaWifiManagerAndroid.kt` — Add `staStaConcurrencySupported` Property and Expose Full State ✅ COMPLETE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — ⚠️ LARGE FILE (manual edit required)

**Rationale:** C5 in `WIFI_AP_CON_IMPLEMENTATION.md` adds:

```kotlin
val concurrentApStationSupported: Boolean
    get() = _state.value.concurrentApStationSupported
```

A parallel property is needed for `staStaConcurrencySupported`, and the `state` property must be promoted to `StateFlow` (or a `currentState` property added) to allow `MeshrabiyaApiImpl` to call `.value` without going through the backing field.

**Addition alongside C5 (same location in the file, after the existing state declarations):**

```kotlin
/**
 * Synchronous read of STA/STA concurrency support flag.
 * True only on API 31+ hardware that supports two simultaneous WiFi station connections.
 */
val staStaConcurrencySupported: Boolean
    get() = _state.value.staStaConcurrencySupported

/**
 * Synchronous snapshot of the current WiFi state.
 * Use this when a single consistent read of all state fields is needed without collecting the Flow.
 */
val currentWifiState: MeshrabiyaWifiState
    get() = _state.value
```

`S5`'s `MeshrabiyaApiImpl.isInternetWifiFeatureAvailable()` then accesses state via
`node.meshrabiyaWifiManager.currentWifiState` instead of the `state` Flow.

---

### S7 — `EnhancedMeshFragment.kt` — Refine Button Visibility to Use `isInternetWifiFeatureAvailable()` ✅ COMPLETE

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Line count:** 2338 — ⚠️ LARGE FILE (manual edit required)

**Rationale:** C18 in `WIFI_AP_CON_IMPLEMENTATION.md` shows the button based on `MESH_ROUTER in rolesDto && hasConcurrentSupport`. MESH_ROUTER is assigned purely on hardware capability (confirmed: `EmergentRoleManager.kt` lines 427–430), so a Join Mesh device with AP+STA hardware falsely satisfies the condition but has no hotspot running, and `hasConcurrentSupport` also passes — making the button visible in a state where the old C10 guard would then fail. With S4 and S5 in place, `isInternetWifiFeatureAvailable()` is the single correct check.

**C18 Part A BEFORE (button visibility, inside `setupRoleObserver()` `runOnUiThread` block):**

```kotlin
val hasConcurrentSupport = (meshrabiyaApi as? com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl)
    ?.let {
        val node = it.javaClass.getDeclaredField("myNode")
            .also { f -> f.isAccessible = true }
            .get(it) as? com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
        node?.meshrabiyaWifiManager?.concurrentApStationSupported
    } ?: false
val isMeshRouter = MeshRoleDto.MESH_ROUTER in rolesDto
MeshUIBindings.wifiApConnectionButton.visibility =
    if (isMeshRouter && hasConcurrentSupport) android.view.View.VISIBLE
    else android.view.View.GONE
```

**S7 AFTER (replaces C18 Part A):**

```kotlin
// isInternetWifiFeatureAvailable() encapsulates AP+STA mode (hotspot running + API 30+)
// and STA/STA mode (Join Mesh + API 31+ dual-STA) into a single checked query.
// No MESH_ROUTER role check needed - that role is hardware-capability-only and is
// already guaranteed to be present when this method returns true.
val featureAvailable = meshrabiyaApi.isInternetWifiFeatureAvailable()
MeshUIBindings.wifiApConnectionButton.visibility =
    if (featureAvailable) android.view.View.VISIBLE
    else android.view.View.GONE
```

---

### S8 — `MeshrabiyaWifiManagerAndroid.kt` — Replace Naked `200`, `500`, and `10` Literals with Named Constants

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 1049 — ⚠️ LARGE FILE (manual edit required)

**Rationale:** Five numeric literals appear across four functions with no semantic name attached:

- `200` at line 311 (`detectWifiConcurrencyCapabilities()`) — a settle delay inserted before querying Android WiFi concurrency APIs
- `200` at line 834 (`createBoundSocket()`) — a socket-bind retry cadence waiting for WiFi Direct interface readiness
- `500` at line 361 (`requestHotspot()`) — a settle delay after `wifiManager.disconnect()`, waiting for the WiFi client association to drop
- `500` at line 798 (`disconnectStation()`) — a settle delay after `wifiManager.isWifiEnabled = false`, waiting for the WiFi subsystem to fully disable
- `10` at line 892 (`createStationNetworkBoundSockets()`) — the maximum number of socket bind attempts on a WiFi Direct interface

The two `200` literals are coincidentally equal but represent different timing concerns; the two `500` literals are also coincidentally equal but represent different hardware-level wait conditions (client association drop vs. subsystem shutdown). Shared constants would merge distinct tuning knobs and obscure the independent nature of each settle period. Five separate named constants resolve all ambiguities. The `_ANDROID` infix in each name signals that these are Android-platform-specific values scoped to this class's companion object, not entries in the global `MeshrabiyaConstants` mesh-protocol object.

**Evidence — companion object on disk (lines 1039–1048):**

```kotlin
    companion object {

        const val PREFIX_SSID = "ssid_"

        const val HOTSPOT_TIMEOUT = 10000L

        const val WIFI_DIRECT_SERVICE_TYPE = "_meshr._tcp"

    }
```

**Evidence — all five literal sites (bottom-to-top order matching application sequence):**

| Line | Function | Literal | Role | Proposed constant |
|------|----------|---------|------|-------------------|
| 892 | `createStationNetworkBoundSockets()` | `10` | max socket-bind attempts on Android WiFi Direct interface | `WIFI_DIRECT_SOCKET_BIND_MAX_ATTEMPTS_ANDROID` |
| 834 | `createBoundSocket()` | `200` | default `interval` parameter — socket bind retry cadence on Android WiFi Direct interface | `SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS` |
| 798 | `disconnectStation()` | `500` | settle delay after `wifiManager.isWifiEnabled = false` — Android WiFi subsystem shutdown | `WIFI_SUBSYSTEM_DISABLE_SETTLE_DELAY_ANDROID_MS` |
| 361 | `requestHotspot()` | `500` | settle delay after `wifiManager.disconnect()` — Android WiFi client association drop | `WIFI_CLIENT_DISCONNECT_SETTLE_DELAY_ANDROID_MS` |
| 311 | `detectWifiConcurrencyCapabilities()` | `200` | settle delay before querying Android WiFi concurrency APIs | `WIFI_CONCURRENCY_DETECT_INIT_DELAY_ANDROID_MS` |

---

#### S8-A — companion object: add five constants (lines 1039–1048)

**BEFORE (lines 1039–1048):**

```kotlin
    companion object {

        const val PREFIX_SSID = "ssid_"

        const val HOTSPOT_TIMEOUT = 10000L

        const val WIFI_DIRECT_SERVICE_TYPE = "_meshr._tcp"

    }
```

**AFTER:**

```kotlin
    companion object {

        const val PREFIX_SSID = "ssid_"

        const val HOTSPOT_TIMEOUT = 10000L

        const val WIFI_DIRECT_SERVICE_TYPE = "_meshr._tcp"

        /**
         * Settle delay (ms) before querying Android WiFi concurrency APIs in
         * detectWifiConcurrencyCapabilities(). Gives WifiManager time to fully initialize
         * before isStaApConcurrencySupported / isStaStaConcurrencySupported are called.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val WIFI_CONCURRENCY_DETECT_INIT_DELAY_ANDROID_MS = 200L

        /**
         * Settle delay (ms) after `wifiManager.disconnect()` in requestHotspot().
         * Allows the Android WiFi client association to fully drop before the hotspot starts.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val WIFI_CLIENT_DISCONNECT_SETTLE_DELAY_ANDROID_MS = 500L

        /**
         * Settle delay (ms) after `wifiManager.isWifiEnabled = false` in disconnectStation().
         * Allows the Android WiFi subsystem to fully shut down before continuing.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val WIFI_SUBSYSTEM_DISABLE_SETTLE_DELAY_ANDROID_MS = 500L

        /**
         * Default retry interval (ms) between socket bind attempts in createBoundSocket().
         * On Android, link-local IPv6 addresses on the WiFi Direct station interface may not
         * be immediately available after network bring-up; short retries cover the window.
         * _ANDROID suffix: Android-platform-specific timing, not a mesh protocol value.
         */
        const val SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS = 200L

        /**
         * Maximum socket bind attempts in createBoundSocket() for WiFi Direct connections.
         * Android 13+ may delay link-local IPv6 address assignment on the station interface;
         * retrying at SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS intervals covers the window.
         * _ANDROID suffix: Android-platform-specific count, not a mesh protocol value.
         */
        const val WIFI_DIRECT_SOCKET_BIND_MAX_ATTEMPTS_ANDROID = 10

    }
```

---

#### S8-B — `createStationNetworkBoundSockets()` (line 892): replace `10`

**BEFORE (lines 891–896):**

```kotlin
                try {
                    createBoundSocket(socketPort, netAddress, 10).also {
                        logger(Log.DEBUG, "$logPrefix : createStationNetworkBoundSockets : succeeded on retry")
                    }
                }catch(e: IOException) {
```

**AFTER:**

```kotlin
                try {
                    createBoundSocket(socketPort, netAddress, WIFI_DIRECT_SOCKET_BIND_MAX_ATTEMPTS_ANDROID).also {
                        logger(Log.DEBUG, "$logPrefix : createStationNetworkBoundSockets : succeeded on retry")
                    }
                }catch(e: IOException) {
```

---

#### S8-C — `createBoundSocket()` signature (line 834): replace `200` default parameter

**BEFORE (lines 830–836):**

```kotlin
    private suspend fun createBoundSocket(
        port: Int, bindAddress:
        InetAddress?,
        maxAttempts: Int,
        interval: Long = 200,
    ): DatagramSocket {
```

**AFTER:**

```kotlin
    private suspend fun createBoundSocket(
        port: Int, bindAddress:
        InetAddress?,
        maxAttempts: Int,
        interval: Long = SOCKET_BIND_RETRY_INTERVAL_ANDROID_MS,
    ): DatagramSocket {
```

---

#### S8-D — `disconnectStation()` (line 798): replace `500`

**BEFORE (lines 794–802):**

```kotlin
                if (wifiManager.isWifiEnabled) {
                    logger(Log.INFO, "$logPrefix disconnectStation: Disabling WiFi subsystem to prevent reconnection")
                    try {
                        wifiManager.isWifiEnabled = false
                        delay(500)
                        logger(Log.INFO, "$logPrefix disconnectStation: WiFi subsystem disabled successfully")
                    } catch (e: SecurityException) {
```

**AFTER:**

```kotlin
                if (wifiManager.isWifiEnabled) {
                    logger(Log.INFO, "$logPrefix disconnectStation: Disabling WiFi subsystem to prevent reconnection")
                    try {
                        wifiManager.isWifiEnabled = false
                        delay(WIFI_SUBSYSTEM_DISABLE_SETTLE_DELAY_ANDROID_MS)
                        logger(Log.INFO, "$logPrefix disconnectStation: WiFi subsystem disabled successfully")
                    } catch (e: SecurityException) {
```

---

#### S8-E — `requestHotspot()` (line 361): replace `500`

**BEFORE (lines 357–365):**

```kotlin
                try {
                    wifiManager.disconnect()
                    logger(Log.DEBUG, "$logPrefix WiFi disconnected successfully", null)
                    // Give it a moment to disconnect
                    delay(500)
                } catch (e: Exception) {
                    logger(Log.WARN, "$logPrefix Failed to disconnect WiFi: ${e.message}", e)
                }
```

**AFTER:**

```kotlin
                try {
                    wifiManager.disconnect()
                    logger(Log.DEBUG, "$logPrefix WiFi disconnected successfully", null)
                    // Give it a moment to disconnect
                    delay(WIFI_CLIENT_DISCONNECT_SETTLE_DELAY_ANDROID_MS)
                } catch (e: Exception) {
                    logger(Log.WARN, "$logPrefix Failed to disconnect WiFi: ${e.message}", e)
                }
```

---

#### S8-F — `detectWifiConcurrencyCapabilities()` (line 311): replace `200`

**BEFORE (lines 309–314):**

```kotlin
    private suspend fun detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean> {
        delay(200) // brief delay for WiFi system initialization

        val apStaSupported = if (Build.VERSION.SDK_INT >= 30) {
```

**AFTER:**

```kotlin
    private suspend fun detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean> {
        delay(WIFI_CONCURRENCY_DETECT_INIT_DELAY_ANDROID_MS)

        val apStaSupported = if (Build.VERSION.SDK_INT >= 30) {
```

---

## 6. STA/STA Mode — Additional Routing Consideration

When a device is in **Join Mesh + internet WiFi** (STA/STA), it is **not** acting as a hotspot. The routing implications differ from AP+STA:

- The device is a **mesh client** connected to another node's hotspot via `WifiNetworkSpecifier`.
- It simultaneously holds an internet WiFi connection via `WifiNetworkSuggestion`.
- It is NOT the CLEARNET_GATEWAY node for other devices (it has no hotspot to which client nodes can connect).
- However, **for its own traffic**, if CLEARNET_GATEWAY preference is set, the device can route its own internet traffic directly over the `WifiNetworkSuggestion` network.

This means STA/STA mode primarily benefits **a single device's own traffic** — browser, app, etc. — when:
1. The device is in Join Mesh and wants VPN-free Clearnet access for its own connections  
2. The mesh already has other CLEARNET_GATEWAY nodes for other clients to use

For the CLEARNET_GATEWAY forwarding path (forwarding packets from mesh clients through to internet), the device still needs to be a hotspot (AP mode) so other nodes can connect to it. STA/STA alone does not enable a device to serve as a shared internet gateway for other mesh participants.

**Implication for feature scope:** 
- AP+STA (Start Mesh): enables both own traffic and shared gateway for mesh clients  
- STA/STA (Join Mesh): enables own traffic through internet WiFi only; no shared gateway capability

This distinction does not require additional code changes, but the UI dialog text and any documentation surfaced to the user should accurately reflect what STA/STA mode does and does not provide.

---

## 7. Summary of All Changes Required

### From `WIFI_AP_CON_IMPLEMENTATION.md` (C1–C18): baseline — apply first

### Additional STA/STA Changes (apply after C1–C18):

| ID | File | Lines | Large? | Status | Description |
|----|------|-------|--------|--------|-------------|
| S1 | `MeshrabiyaWifiState.kt` | 64 | NO | ✅ COMPLETE | Add `staStaConcurrencySupported: Boolean = false` field |
| S2 | `MeshrabiyaWifiManagerAndroid.kt` | 1048 | **YES** | ✅ COMPLETE | Replace `detectConcurrentSupport()` with `detectWifiConcurrencyCapabilities()`, update init coroutine |
| S3 | `MeshrabiyaWifiManagerAndroid.kt` | 1048 | **YES** | ✅ COMPLETE | Fix C9 `bindProcessToNetwork` gate to use `hotspotIsStarted && concurrentApStationSupported` instead of `concurrentApStationSupported` alone |
| S4 | `MeshrabiyaWifiManagerAndroid.kt` | 1048 | **YES** | ✅ COMPLETE | Fix C10 `connectToInternetWifi()` guard to branch on `hotspotIsStarted` and check correct capability per mode |
| S5 | `MeshrabiyaApi.kt` + `MeshrabiyaApiImpl.kt` | 391 / 2057 | NO / **YES** | ✅ COMPLETE | Add `isInternetWifiFeatureAvailable()` declaration and implementation |
| S6 | `MeshrabiyaWifiManagerAndroid.kt` | 1048 | **YES** | ✅ COMPLETE | Add `staStaConcurrencySupported` and `currentWifiState` properties alongside C5's `concurrentApStationSupported` |
| S7 | `EnhancedMeshFragment.kt` | 2417 | **YES** | ✅ COMPLETE | Remove extra `MeshRoleDto.MESH_ROUTER in rolesDto &&` gate — `isInternetWifiFeatureAvailable()` is the only correct check (STA/STA-only devices lack MESH_ROUTER role) |
| S8 | `MeshrabiyaWifiManagerAndroid.kt` | 1049 | **YES** | ⬜ PENDING | Replace 5 naked literals (`200`×2, `500`×2, `10`) with 5 separate named `const val` constants (`_ANDROID` scope suffix) added to existing companion object (S8-A through S8-F) |

### Application sequence (STA changes only — apply after all C changes):

1. **S1** — state model must gain the field before it can be set (needed by S2)
2. **S2** — sets the new field during init; also replaces `detectConcurrentSupport()` which is referenced in tests
3. **S6** — property addition in the same file as S2/S3/S4; do all three in one manual edit session
4. **S3** — corrects the C9 regression; same file, same manual edit session as S2/S4/S6
5. **S4** — corrects C10 guard; same file, same manual edit session
6. **S5** (declaration) — `MeshrabiyaApi.kt` must get declaration before `MeshrabiyaApiImpl.kt` compiles
7. **S5** (implementation) — `MeshrabiyaApiImpl.kt`; depends on S1 (state field), S6 (`currentWifiState`)
8. **S7** — `EnhancedMeshFragment.kt`; depends on S5's `isInternetWifiFeatureAvailable()` existing in the API
9. **S8** — `MeshrabiyaWifiManagerAndroid.kt` companion object + 5 call sites (S8-A through S8-F in order); no dependencies on S1–S7, can apply any time after the file compiles; apply S8-A first (defines constants), then S8-B through S8-F in any order

---

## 8. Minimum API Level Summary

| Scenario | Minimum API | `isStaApConcurrencySupported` | `isStaStaConcurrencySupported` |
|----------|-------------|-------------------------------|--------------------------------|
| Start Mesh + Internet WiFi (AP+STA) | **API 30** (Android 10) | required = true | not checked |
| Join Mesh + Internet WiFi (STA/STA) | **API 31** (Android 12) | not checked | required = true |

Both scenarios check hardware capability at runtime. The feature is hidden (button not shown) when neither capability is present. No crash or error is surfaced to the user — the button simply does not appear.
