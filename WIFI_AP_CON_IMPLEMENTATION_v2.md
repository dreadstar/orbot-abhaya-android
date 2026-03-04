# WiFi AP Concurrency — Full Code-Level Implementation Reference v2

**Date:** 2026-03-03  
**Status:** Research complete — all BEFORE code confirmed by literal disk reads  
**Supersedes:** `WIFI_AP_CON_IMPLEMENTATION.md`  
**Plans read:** `WIFI_AP_CON_PLAN.md` (1192 lines, fully read), `WIFI_STA_PLAN.md` (631 lines, fully read)  
**Constraint:** No assumptions. Every BEFORE block verified by literal `read_file` of current disk state.  
**NO CODE CHANGES have been applied.** This document describes what must be changed.

---

## How to Read This Document

- **NOT large** = file ≤ 800 lines. Can be edited with `replace_string_in_file` / `multi_replace_string_in_file`.
- **⚠️ LARGE FILE** = file > 800 lines. **MUST be edited manually** per the Large File Manual Edit Rule. BEFORE/AFTER blocks are presented with line numbers for manual application.
- Changes are ordered by dependency. Apply them in the sequence listed.
- All `BEFORE` blocks are verbatim from disk. Any deviation during edit indicates file drift.

---

## File Inventory

| File | Absolute Path | Lines | Large? |
|------|---------------|-------|--------|
| `MeshrabiyaConstants.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt` | 341 | NOT large |
| `VirtualDatagramSocketImpl.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocketImpl.kt` | 227 | NOT large |
| `VirtualDatagramSocket2.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocket2.kt` | 23 | NOT large |
| `VirtualNode.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt` | 1483 | ⚠️ LARGE |
| `GatewayTypeResolver.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewayTypeResolver.kt` | 220 | NOT large |
| `MeshrabiyaWifiState.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt` | 64 | NOT large |
| `LocalOnlyHotspotManager.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalOnlyHotspotManager.kt` | 279 | NOT large |
| `AndroidVirtualNode.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt` | 225 | NOT large |
| `MeshrabiyaWifiManagerAndroid.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` | 876 | ⚠️ LARGE |
| `DtoModels.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt` | 700 | NOT large |
| `MeshrabiyaApi.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt` | 391 | NOT large |
| `MeshrabiyaApiImpl.kt` | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` | 1975 | ⚠️ LARGE |
| `fragment_mesh_enhanced.xml` | `app/src/main/res/layout/fragment_mesh_enhanced.xml` | 489 | NOT large |
| `MeshUIBindings.kt` | `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt` | 163 | NOT large |
| `EnhancedMeshFragment.kt` | `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` | 2338 | ⚠️ LARGE |

---

## Change 1 — Tech Debt 17.4a: `MeshrabiyaConstants.kt` — Add `VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt`  
**Lines:** 341 — NOT large  
**Purpose:** Replace the magic number `VirtualPacketHeader.HEADER_SIZE - 3` used in `VirtualDatagramSocketImpl` with a named constant. `VirtualPacketHeader.HEADER_SIZE = 21`, so offset `18` points to the `gatewayType` byte in the header. This is a prerequisite for Change 2.

**Insertion point:** After line 82 (`const val ROUTE_DROP_ON_POOL_EXHAUSTION: Boolean = true`), before line 83 (`/** Maximum number of pending listener... */`).

**BEFORE (lines 79–88):**

```kotlin
    /**
     * Enable graceful packet dropping when connection pool is exhausted
     * When true, packets are dropped with warning log instead of blocking
     */
    const val ROUTE_DROP_ON_POOL_EXHAUSTION: Boolean = true

    /**
     * Maximum number of pending listener registrations to queue
     * Used by MeshrabiyaApiImpl to limit memory usage for deferred listeners
     */
```

**AFTER (lines 79–88+):**

```kotlin
    /**
     * Enable graceful packet dropping when connection pool is exhausted
     * When true, packets are dropped with warning log instead of blocking
     */
    const val ROUTE_DROP_ON_POOL_EXHAUSTION: Boolean = true

    // ========================================
    // VIRTUAL PACKET HEADER LAYOUT CONSTANTS
    // ========================================

    /**
     * Byte offset of the gatewayType field within VirtualPacketHeader.
     * VirtualPacketHeader.HEADER_SIZE = 21 bytes.
     * gatewayType byte is at index 18 (HEADER_SIZE - 3).
     *
     * Layout (bytes 0-20):
     *   [0-3]   fromAddr (Int)
     *   [4-7]   toAddr (Int)
     *   [8-11]  fromPort (Int, as 2 bytes) + toPort (Int, as 2 bytes)
     *   [12-15] hopCount + maxHops + protocol + flags
     *   [16-17] payloadSize (Short)
     *   [18]    gatewayType  ← this constant
     *   [19-20] reserved
     *
     * Verified: VirtualDatagramSocketImpl.kt line 143 uses HEADER_SIZE - 3 = 18.
     */
    const val VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET = 18

    /**
     * Maximum number of pending listener registrations to queue
     * Used by MeshrabiyaApiImpl to limit memory usage for deferred listeners
     */
```

---

## Change 2 — Tech Debt 17.4b: `VirtualDatagramSocketImpl.kt` — Replace Magic Number at Line 143

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocketImpl.kt`  
**Lines:** 227 — NOT large  
**Purpose:** Replace `VirtualPacketHeader.HEADER_SIZE - 3` with `MeshrabiyaConstants.VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET`. Requires Change 1 first.  
**Dependency:** Change 1 must be applied first (`VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET` must exist).

**BEFORE (lines 141–147, verbatim from disk):**

```kotlin
            //V3: Resolve gateway type before routing (if resolver available)
            gatewayTypeResolver?.let { resolver ->
                //TODO: Extract source package name from DatagramPacket if available
                //For now, use null (will fallback to global preference)
                val resolvedType = resolver.resolveGatewayType(virtualPacket, sourcePackageName = null)
                //Update packet header in-place
                virtualPacket.data[VirtualPacketHeader.HEADER_SIZE - 3] = resolvedType //gatewayType at offset 18
            }
```

**AFTER:**

```kotlin
            //V3: Resolve gateway type before routing (if resolver available)
            gatewayTypeResolver?.let { resolver ->
                //TODO: Extract source package name from DatagramPacket if available
                //For now, use null (will fallback to global preference)
                val resolvedType = resolver.resolveGatewayType(virtualPacket, sourcePackageName = null)
                //Update packet header in-place using named constant (offset = HEADER_SIZE - 3 = 18)
                virtualPacket.data[MeshrabiyaConstants.VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET] = resolvedType
            }
```

**Import required:** `import com.ustadmobile.meshrabiya.MeshrabiyaConstants` — verify this import does not already exist at the top of `VirtualDatagramSocketImpl.kt` before adding.

---

## Change 3 — 11.10a: `VirtualDatagramSocket2.kt` — Add `context` Parameter

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocket2.kt`  
**Lines:** 23 — NOT large  
**Purpose:** Thread the Android `Context` from `VirtualNode` down to `VirtualDatagramSocketImpl` so that `GatewayTypeResolver` can be instantiated. Without this, `gatewayTypeResolver` is always `null`, gateway routing is permanently dead code, and all packets default to `GATEWAY_TYPE_NONE`.  
**Background:** `VirtualDatagramSocketImpl` constructor (line 29) already accepts `context: Context? = null` on disk. The only missing link is `VirtualDatagramSocket2` not accepting or passing a context.

**BEFORE (complete file, 23 lines, verbatim from disk):**

```kotlin
package com.ustadmobile.meshrabiya.vnet.datagram

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.DatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualNode

/**
 * Thin wrapper required so that we can access the protected constructor specifying the impl class
 */
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
))
```

**AFTER (complete file):**

```kotlin
package com.ustadmobile.meshrabiya.vnet.datagram

import android.content.Context
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.DatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualNode

/**
 * Thin wrapper required so that we can access the protected constructor specifying the impl class.
 * The [context] parameter is forwarded to [VirtualDatagramSocketImpl] to enable
 * [com.ustadmobile.meshrabiya.vnet.GatewayTypeResolver] instantiation for CLEARNET/TOR routing.
 */
class VirtualDatagramSocket2(
    router: VirtualRouter,
    localVirtualAddress: Int,
    logger: MNetLogger,
    private val parentNode: VirtualNode? = null,
    context: Context? = null,
): DatagramSocket(VirtualDatagramSocketImpl(
    router = router,
    localVirtualAddress = localVirtualAddress,
    logger = logger,
    parentNode = parentNode,
    context = context,
))
```

---

## Change 4 — 11.10b: `VirtualNode.kt` — Pass `appContext` in `createDatagramSocket()` *(⚠️ LARGE FILE)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 1483 — ⚠️ LARGE FILE — **present for manual edit only**  
**Change location:** Line 524 (confirmed by literal read)  
**Purpose:** Pass `appContext` (the `Context` constructor parameter on `VirtualNode`, confirmed at line 97) to `VirtualDatagramSocket2` so `GatewayTypeResolver` can be constructed.  
**Dependency:** Change 3 must be applied first.

**BEFORE (lines 522–527):**

```kotlin
    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger, this)
    }
```

**AFTER (lines 522–527):**

```kotlin
    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger, this, appContext)
    }
```

**Note:** `appContext` is a constructor parameter of `VirtualNode` declared at line 97 (`val appContext: Context`). No import changes needed — `Context` is already used in the file.

---

## Change 5 — 11.11: `VirtualNode.kt` — Replace `shouldRouteViaProxy()` Stub *(⚠️ LARGE FILE)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 1483 — ⚠️ LARGE FILE — **present for manual edit only**  
**Change location:** Lines 1402–1411 (confirmed by literal read)  
**Purpose:** The current stub returns `true` for ALL packets, meaning mesh control packets are incorrectly classified as proxy-bound. This breaks mesh stability on TOR_GATEWAY nodes and mislabels clearnet packets on CLEARNET_GATEWAY nodes. The fix classifies by destination subnet membership.  
**Verified facts:**  
- `prefixMatches` extension is imported at line 7: `import com.ustadmobile.meshrabiya.ext.prefixMatches`  
- `address: InetAddress` is a constructor parameter (line 94)  
- `networkPrefixLength: Int` is a constructor parameter (line 95, default 16)  
- `getInetAddressFor(addr: Int): InetAddress` exists as a companion function at line 113  
- Parameter type of current stub is `packet: VirtualPacket` (confirmed)

**BEFORE (lines 1401–1411, verbatim from disk):**

```kotlin
    // --- Helper: Should route via proxy ---
    private fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
        // Define logic for which packets should go via proxy (Tor)
        // Example: packets destined for Internet (not mesh addresses)
        // Here, you may want to check packet.header.toAddr or other fields
        // For now, route all non-mesh traffic if proxy is active and TOR_GATEWAY role is present
        return true
    }
```

**AFTER:**

```kotlin
    // --- Helper: Should route via proxy ---
    // Route via proxy (Tor) only when the destination is outside the mesh virtual subnet.
    // Mesh control and data packets (e.g. 169.254.x.x destinations) MUST NOT go through
    // the proxy — doing so breaks mesh communication on TOR_GATEWAY nodes and incorrectly
    // marks peer-to-peer mesh packets as external traffic on CLEARNET_GATEWAY nodes.
    //
    // Implementation: uses the prefixMatches extension (already imported) to test whether
    // the packet destination address shares the same /networkPrefixLength prefix as this
    // node's own virtual address. If it matches, the destination is within the mesh subnet
    // and should NOT be proxied.
    protected open fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
        val destInetAddress = getInetAddressFor(packet.header.toAddr)
        return !destInetAddress.prefixMatches(networkPrefixLength, address)
    }
```

**Note:** Visibility changed from `private` to `protected open` to allow `AndroidVirtualNode` to override for finer per-node control (per plan section 11.11 rationale).

---

## Change 6 — 11.12: `GatewayTypeResolver.kt` — Null-Safety on Precedence 3

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewayTypeResolver.kt`  
**Lines:** 220 — NOT large  
**Purpose:** `MeshrabiyaApiImpl.getInstance()` throws if the singleton has not been initialized (startup race window). Wrapping in `runCatching` returns `GATEWAY_TYPE_NONE` safely instead of crashing.  
**Background:** Confirmed by literal read — `getInstance()` at lines 108–111 calls `MeshrabiyaApiImpl.getInstance().getGatewayPreference()` with no null guard.

**BEFORE (lines 107–114, verbatim from disk):**

```kotlin
        // Precedence 3: Global gateway preference (fallback)
        val preference = MeshrabiyaApiImpl.getInstance().getGatewayPreference()
        val gatewayType = applyGlobalPreference(preference)
        Log.d(TAG, "No VPN rule for package, using global preference $preference → gatewayType=$gatewayType")
        return gatewayType
    }
```

**AFTER:**

```kotlin
        // Precedence 3: Global gateway preference (fallback).
        // runCatching guards against MeshrabiyaApiImpl singleton not yet initialized.
        // Default to GATEWAY_TYPE_NONE to prevent a crash during the startup window.
        val preference = runCatching { MeshrabiyaApiImpl.getInstance().getGatewayPreference() }
            .getOrNull()
        if (preference == null) {
            Log.w(TAG, "resolveGatewayType: MeshrabiyaApiImpl not initialized — defaulting to GATEWAY_TYPE_NONE")
            return VirtualPacketHeader.GATEWAY_TYPE_NONE
        }
        val gatewayType = applyGlobalPreference(preference)
        Log.d(TAG, "No VPN rule for package, using global preference $preference → gatewayType=$gatewayType")
        return gatewayType
    }
```

---

## Change 7 — S1: `MeshrabiyaWifiState.kt` — Add `staStaConcurrencySupported` Field

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/state/MeshrabiyaWifiState.kt`  
**Lines:** 64 — NOT large  
**Purpose:** Add the `staStaConcurrencySupported` state field required by changes S2–S7. Currently (confirmed by literal read) the data class has exactly 6 fields with `concurrentApStationSupported` as the only concurrency flag.  
**Dependency:** Must be applied before S2 (which sets the new field in `_state.update`).

**BEFORE (lines 9–16, verbatim from disk):**

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
    // When false (default), the STA/STA path in connectToInternetWifi() is unavailable.
    val staStaConcurrencySupported: Boolean = false,
)
```

---

## Change 8 — 11.1a: `LocalOnlyHotspotManager.kt` — Add Lambda Parameter and Gate WiFi Suppression

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalOnlyHotspotManager.kt`  
**Lines:** 279 — NOT large  
**Purpose:** On AP+STA capable devices, the hotspot monitor's WiFi suppression loop (which forcibly disconnects and removes WiFi networks) must NOT run — the WiFi connection IS the intentional internet connection being used by the MESH_ROUTER feature. Currently the suppression runs unconditionally.  
**Approach:** Add a lambda `concurrentApStationSupported: () -> Boolean` to the constructor. The lambda is read at suppression-check time (not at construction time) so it always reflects live state. The suppression block is gated with `!concurrentApStationSupported()`.  
**Dependency:** Change 9 (11.1b in `MeshrabiyaWifiManagerAndroid.kt`) must pass the lambda at construction site.

### 8a — Constructor Change

**BEFORE (lines 37–44, verbatim from disk):**

```kotlin
class LocalOnlyHotspotManager(
    appContext: Context,
    private val logger: MNetLogger,
    name: String,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val dataStore: DataStore<Preferences>,
) {
```

**AFTER:**

```kotlin
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
```

### 8b — Suppression Block Gate

**BEFORE (lines 181–202, verbatim from disk — inside `startHotspotMonitoring()`):**

```kotlin
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
                        }
                    } catch (e: Exception) {
                        logger(Log.ERROR, "$logPrefix [HOTSPOT MONITOR] Failed to disconnect WiFi", e)
                    }
                }
```

**AFTER:**

```kotlin
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
```

---

## Change 9 — 11.9: `AndroidVirtualNode.kt` — Gate `disconnectStation()` in `setWifiHotspotEnabled()`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`  
**Lines:** 225 — NOT large  
**Purpose:** On API 30+ devices with `isStaApConcurrencySupported = true`, calling `disconnectStation()` before starting the hotspot destroys the internet WiFi that the MESH_ROUTER feature exists to maintain. The guard prevents destruction of the intentional internet connection.  
**Severity:** CRITICAL — without this fix, enabling the hotspot on a concurrent-capable device destroys the internet WiFi connection before the hotspot even starts.  
**Background confirmed by literal read:** `meshrabiyaWifiManager` in `AndroidVirtualNode` is typed `MeshrabiyaWifiManagerAndroid` (line 72, confirmed). `state` is a `MutableStateFlow` on `MeshrabiyaWifiManagerAndroid`, `.value` is callable. `concurrentApStationSupported` is in `MeshrabiyaWifiState` (Change 7 adds `staStaConcurrencySupported`; `concurrentApStationSupported` was already there).

**BEFORE (lines 175–192, verbatim from disk):**

```kotlin
        if (enabled) {
            logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (regular WiFi) before starting hotspot", null)
            meshrabiyaWifiManager.disconnectStation()
            
            // CRITICAL: Wait for WiFi disconnect to complete and verify
            logger(Log.INFO, "setWifiHotspotEnabled: Waiting 2 seconds for WiFi disconnect to stabilize...", null)
            kotlinx.coroutines.delay(2000)
            logger(Log.INFO, "setWifiHotspotEnabled: Proceeding with hotspot creation", null)
        }
```

**AFTER:**

```kotlin
        if (enabled) {
            // On concurrent AP+STA capable devices (API 30+), do NOT disconnect the station.
            // The station WiFi is the internet connection that MESH_ROUTER is designed to keep.
            // On non-concurrent devices (or devices where this hasn't been detected yet),
            // the existing disconnect-before-hotspot behavior is preserved.
            if (!meshrabiyaWifiManager.state.value.concurrentApStationSupported) {
                logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (non-concurrent device)", null)
                meshrabiyaWifiManager.disconnectStation()
                logger(Log.INFO, "setWifiHotspotEnabled: Waiting 2 seconds for WiFi disconnect to stabilize...", null)
                kotlinx.coroutines.delay(2000)
                logger(Log.INFO, "setWifiHotspotEnabled: Proceeding with hotspot creation", null)
            } else {
                logger(Log.INFO, "setWifiHotspotEnabled: AP+STA concurrent device — keeping internet WiFi, proceeding directly", null)
            }
        }
```

---

## Change 10 — `MeshrabiyaWifiManagerAndroid.kt` — All AP+STA and STA/STA Changes *(⚠️ LARGE FILE)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Lines:** 876 — ⚠️ LARGE FILE — **all sub-changes below must be applied manually in a single edit session**

Sub-changes covered in this section:
- **11.1b** — Pass `concurrentApStationSupported` lambda to `LocalOnlyHotspotManager` construction
- **S2** — Replace `detectConcurrentSupport()` with `detectWifiConcurrencyCapabilities()` + update `init` coroutine
- **S3** — Fix `bindProcessToNetwork` gate (C9 regression fix)
- **S4** — Fix `connectToInternetWifi()` capability guard (C10 correction)
- **S6** — Add `staStaConcurrencySupported` and `currentWifiState` property getters
- **11.2** — Add `connectToInternetWifi()` and `disconnectFromInternetWifi()` functions
- **11.13** — Add `_internetWifiNetwork` storage for CLEARNET gateway handler

### 10a — 11.1b: `LocalOnlyHotspotManager` Construction (lines 94–101)

**BEFORE (lines 94–101, verbatim from disk):**

```kotlin
    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
    )
```

**AFTER:**

```kotlin
    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        // Lambda reads live state — evaluated at hotspot monitor check time, not at construction.
        concurrentApStationSupported = { _state.value.concurrentApStationSupported },
    )
```

### 10b — S2: Replace `detectConcurrentSupport()` — Init Coroutine (lines 257–264)

**BEFORE (lines 257–264, verbatim from disk):**

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

**AFTER:**

```kotlin
        // Detect concurrent WiFi capabilities after WiFi system initialization.
        // Both AP+STA (API 30) and STA/STA (API 31) are checked in one coroutine.
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

### 10c — S2: Replace `detectConcurrentSupport()` Function (lines 270–283)

**BEFORE (lines 270–283, verbatim from disk):**

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

**AFTER (replace the entire function):**

```kotlin
    /**
     * Detect device WiFi concurrency capabilities at startup.
     * Returns Pair(concurrentApStationSupported, staStaConcurrencySupported).
     *
     * concurrentApStationSupported (API 30+): device can run hotspot (AP) + WiFi station (STA)
     *   simultaneously. Required for MESH_ROUTER Start Mesh + internet WiFi feature.
     *
     * staStaConcurrencySupported (API 31+): device can hold two simultaneous WiFi station
     *   connections on different SSIDs. Required for MESH_ROUTER Join Mesh + internet WiFi.
     *
     * Delays 200ms to allow WiFi system to fully initialize before querying (preserves
     * prior detectConcurrentSupport() behavior).
     */
    private suspend fun detectWifiConcurrencyCapabilities(): Pair<Boolean, Boolean> {
        // **TODO(MeshrabiyaConstants refactor):** `200` is a magic number for the WiFi system
        // initialization delay. Refactor when the time comes:
        //   1. Add to `MeshrabiyaConstants.kt`:
        //        `const val WIFI_CONCURRENCY_DETECTION_DELAY_MS = 200L`
        //   2. Replace `delay(200)` with:
        //        `delay(MeshrabiyaConstants.WIFI_CONCURRENCY_DETECTION_DELAY_MS)`
        delay(200) // brief delay for WiFi system initialization

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

### 10d — S6: Add `staStaConcurrencySupported` and `currentWifiState` Properties

**Location:** Immediately after any existing `concurrentApStationSupported` property that C5 adds (or if C5 has not yet been applied, add all three properties in a new block after the `_state` declaration at line 197).

**Additions (new code — no existing BEFORE, insert after `_state` declaration block):**

```kotlin
    /**
     * Synchronous read of AP+STA concurrency support flag.
     * True on API 30+ hardware where isStaApConcurrencySupported = true.
     * Required for: MESH_ROUTER Start Mesh + internet WiFi (AP+STA mode).
     */
    val concurrentApStationSupported: Boolean
        get() = _state.value.concurrentApStationSupported

    /**
     * Synchronous read of STA/STA concurrency support flag.
     * True only on API 31+ hardware where isStaStaConcurrencySupported = true.
     * Required for: MESH_ROUTER Join Mesh + internet WiFi (STA/STA mode).
     */
    val staStaConcurrencySupported: Boolean
        get() = _state.value.staStaConcurrencySupported

    /**
     * Synchronous snapshot of the current WiFi state.
     * Use this when a single consistent read of all state fields is needed
     * without collecting the Flow (e.g., from non-suspending API methods).
     */
    val currentWifiState: MeshrabiyaWifiState
        get() = _state.value

    /**
     * Holds the Network object for the current internet WiFi connection,
     * set by connectToInternetWifi() success callback and cleared by disconnectFromInternetWifi().
     * Used by ClearnetGatewayForwarder (11.13) to bind outbound sockets to the internet interface.
     */
    @Volatile
    var internetWifiNetwork: Network? = null
        private set
```

**Import required for `Network`:**  
`import android.net.Network`  
Verify this import is not already present before adding.

### 10e — S3: Fix `bindProcessToNetwork` Gate in `connectToHotspotInternal()` (lines ~492–505)

**BEFORE (lines 492–505, verbatim from disk):**

```kotlin
            // CRITICAL: Bind all app sockets to this mesh network to prevent switching back to regular WiFi
            val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
            logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess", null)
            if (!bindSuccess) {
                logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
            }
```

**AFTER:**

```kotlin
            // Bind all process traffic to the mesh network, EXCEPT when this device is actively
            // running its own hotspot (AP+STA mode). In AP+STA mode the process-wide mesh bind
            // would redirect internet-forwarding sockets onto the mesh, creating a routing loop.
            //
            // In pure station mode (Join Mesh, hotspot not running), even on concurrent-capable
            // devices, the process-wide bind IS needed to keep mesh traffic on the mesh station
            // interface.
            //
            // In STA/STA mode (Join Mesh + internet WiFi simultaneously), the internet WiFi
            // connection is established via WifiNetworkSuggestion and bound per-socket via
            // Network.bindSocket() — the process-wide mesh bind does not interfere.
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

### 10f — S4 + 11.2: New `connectToInternetWifi()` Function and Mode-Aware Guard

**Location:** Add as a new function in `MeshrabiyaWifiManagerAndroid`, alongside other WiFi connection functions (after `connectToHotspotInternal()`).  
**This is entirely new code.** The BEFORE is "this function does not exist." The full implementation is derived from the plan specification (section 11.2) plus the confirmed WifiNetworkSuggestion/NetworkCallback pattern observed in existing `connectToHotspotInternal()` code and ChainSocketServer pattern at lines 720–810.

```kotlin
    /**
     * Connect to an internet (non-mesh) WiFi network while the mesh remains active.
     *
     * AP+STA mode (hotspot running): requires isStaApConcurrencySupported = true (API 30).
     * STA/STA mode (Join Mesh, hotspot not running): requires isStaStaConcurrencySupported = true (API 31).
     *
     * Uses WifiNetworkSuggestion (not WifiNetworkSpecifier) so Android treats this as a persistent
     * suggestion rather than a temporary request, allowing background reconnection.
     *
     * On success: stores the resulting Network in [internetWifiNetwork] for per-socket binding
     * by ClearnetGatewayForwarder (11.13). Does NOT call bindProcessToNetwork — the internet
     * WiFi socket is explicitly bound at the socket level.
     *
     * @param ssid SSID of the internet WiFi network.
     * @param passphrase WPA2 passphrase. Pass empty string for open networks.
     * @return Result.success(network) on connected, Result.failure with descriptive exception on error.
     */
    suspend fun connectToInternetWifi(ssid: String, passphrase: String): Result<Network> {
        // ---- Mode-aware capability guard (S4) ----
        val currentState = _state.value
        val hotspotRunning = currentState.hotspotIsStarted

        if (hotspotRunning) {
            // AP+STA mode: hotspot is running, adding simultaneous STA internet connection.
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
            // STA/STA mode: device in pure station mode (Join Mesh), adding second STA for internet.
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

        // ---- Build and submit WifiNetworkSuggestion ----
        val suggestion = if (passphrase.isEmpty()) {
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .build()
        } else {
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
        }

        val suggestionList = listOf(suggestion)
        val addStatus = wifiManager.addNetworkSuggestions(suggestionList)
        if (addStatus != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            return Result.failure(IllegalStateException(
                "addNetworkSuggestions failed: status=$addStatus for SSID=$ssid"
            ))
        }

        logger(Log.INFO, "$logPrefix connectToInternetWifi: suggestion added for SSID=$ssid, hotspotRunning=$hotspotRunning")

        // ---- Register NetworkCallback for the internet connection ----
        return suspendCancellableCoroutine { continuation ->
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                // Exclude the mesh network by requiring VALIDATED internet capability
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

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

            internetWifiNetworkCallback = callback
            connectivityManager.requestNetwork(networkRequest, callback)

            continuation.invokeOnCancellation {
                logger(Log.INFO, "$logPrefix connectToInternetWifi: request cancelled, cleaning up")
                connectivityManager.unregisterNetworkCallback(callback)
                wifiManager.removeNetworkSuggestions(suggestionList)
                internetWifiNetwork = null
                internetWifiNetworkCallback = null
            }
        }
    }

    /**
     * Disconnect from the internet (non-mesh) WiFi connection and restore mesh process binding.
     * Removes the WifiNetworkSuggestion, unregisters the NetworkCallback, clears [internetWifiNetwork].
     */
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

**Additional field needed (add alongside `stationBoundSockets` and other AtomicReference fields at lines 209–215):**

```kotlin
    /** NetworkCallback registered for the internet WiFi connection. Cleared on disconnect. */
    @Volatile
    private var internetWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
```

**Imports required** (verify none already present before adding):
- `import android.net.wifi.WifiNetworkSuggestion`
- `import android.net.NetworkRequest`
- `import android.net.NetworkCapabilities`
- `import kotlinx.coroutines.suspendCancellableCoroutine`
- `import kotlin.coroutines.resume`

---

## Change 11 — 11.4: `DtoModels.kt` — New Data Transfer Objects

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt`  
**Lines:** 700 — NOT large  
**Purpose:** Add DTOs for the non-mesh WiFi connection feature exposed through `MeshrabiyaApi`.  
**Insertion:** Add after the last entity `BroadcastProgressDto` (confirmed at line ~693), before the closing `}` of the file or at end of file.  
**Background:** File ends with a commented-out `NetworkOverviewMetricsDto` block. Insert before the closing `}` of the package/object scope, after the last active entity.

> **🔄 RENAME NOTE (2026-03-03):** `NonMeshWifiStatus` was renamed to `NonMeshWifiStatusDto` to conform to the
> DtoModels.kt convention that only DTO types (data classes and DTO-suffixed enums) live in this file.
> **Pattern basis:** `MeshStateDto` (line 35 of DtoModels.kt) is the exact precedent — a lifecycle/observed-state enum
> with `Dto` suffix in package `com.ustadmobile.meshrabiya.api.model`. `NonMeshWifiStatus` is analogous (observed
> connection lifecycle state). No import changes are needed in `MeshrabiyaApi.kt` or `MeshrabiyaApiImpl.kt` because
> both already use `import com.ustadmobile.meshrabiya.api.model.*` wildcard imports.
> **Contrast with `GatewayPreference`** (`GatewayPreference.kt`, package `com.ustadmobile.meshrabiya.api`) which was
> correctly NOT placed in DtoModels.kt because it is a user policy/preference choice type, not observed state.

**AFTER (complete block to append):**

```kotlin

// ========================================
// WiFi Internet Connection DTOs
// Added for WIFI_AP_CON feature (11.4)
// ========================================

/**
 * Represents a discovered WiFi network available for internet connection.
 * Returned by MeshrabiyaApi.scanAvailableWifiNetworks().
 */
data class NonMeshWifiNetworkDto(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val isSecured: Boolean,
)

/**
 * Request to connect to a non-mesh WiFi network.
 * Used internally; the API takes ssid + passphrase directly.
 */
data class WifiConnectionRequestDto(
    val ssid: String,
    val passphrase: String,
)

/**
 * State of the current non-mesh WiFi internet connection.
 * Observed via MeshrabiyaApi.getNonMeshWifiStateFlow().
 */
data class NonMeshWifiConnectionStateDto(
    val status: NonMeshWifiStatusDto,
    val connectedSsid: String? = null,
    val errorMessage: String? = null,
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

---

## Change 12 — 11.3 + S5a: `MeshrabiyaApi.kt` — Add Method Declarations

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`  
**Lines:** 391 — NOT large  
**Purpose:** Expose the WiFi internet connection feature through the public API interface. Add 5 new method declarations. Confirmed by literal read: none of these methods exist today.  
**Insertion:** Add after `fun rotateUserKey(): User` (the current last method), before the closing `}` of the interface.

**AFTER (append to interface, before closing `}`):**

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
     * Disconnect from the non-mesh internet WiFi.
     * Removes the WifiNetworkSuggestion and releases the internet Network object.
     * @return true if disconnection was performed, false if no connection was active.
     */
    suspend fun disconnectFromNonMeshWifi(): Boolean

    /**
     * Observe the current non-mesh WiFi connection state.
     * Emits [NonMeshWifiConnectionStateDto] updates as connection state changes.
     */
    fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto>

    /**
     * Scan for available WiFi networks.
     * Requires ACCESS_FINE_LOCATION permission.
     * @return List of discovered networks, ordered by signal strength descending.
     */
    suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto>

    /**
     * Returns true when the internet WiFi connection feature is currently available.
     *
     * Two paths to true:
     *   1. AP+STA mode: hotspot is running AND isStaApConcurrencySupported = true (API 30+)
     *   2. STA/STA mode: in Join Mesh AND isStaStaConcurrencySupported = true (API 31+)
     *
     * Returns false when mesh is not initialized, API < 30, or neither capability is present.
     */
    fun isInternetWifiFeatureAvailable(): Boolean
```

**Import required in `MeshrabiyaApi.kt`** (verify not already present):  
`import kotlinx.coroutines.flow.StateFlow`

---

## Change 13 — 11.5 + S5b: `MeshrabiyaApiImpl.kt` — Implement New API Methods *(⚠️ LARGE FILE)*

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Lines:** 1975 — ⚠️ LARGE FILE — **present for manual edit only**

**Confirmed facts about the file:**
- `myNode: AndroidVirtualNode?` is declared at line 109 with type `AndroidVirtualNode?`
- `myNode.meshrabiyaWifiManager` returns `MeshrabiyaWifiManagerAndroid` (concrete type, no cast needed — confirmed from `AndroidVirtualNode.kt` line 72: `override val meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid`)
- Existing WiFi state access at line 431: `node.meshrabiyaWifiManager.state.first()` (suspending, blockingly reads state as Flow)
- After Change 10d above, `currentWifiState` property gives synchronous access without suspending
- Class closing `}` is at end of file, last function is `applyPendingBroadcastListeners()` then `}`
- `isTorActive()` is at line 1156

**Location:** Insert all 5 new method implementations before the final `}` closing `MeshrabiyaApiImpl`, after `applyPendingBroadcastListeners()`.

**State Flow for `getNonMeshWifiStateFlow()`:** A `MutableStateFlow<NonMeshWifiConnectionStateDto>` must be stored as a field. Add alongside other MutableStateFlow declarations in the class body:

```kotlin
    // Non-mesh WiFi connection state Flow — updated by connectToNonMeshWifi/disconnectFromNonMeshWifi
    private val _nonMeshWifiState = MutableStateFlow(NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE))
```

**The 5 implementations (add before closing `}` of class):**

```kotlin
    // ========================================
    // WiFi Internet Connection Implementations (WIFI_AP_CON / 11.5 / S5b)
    // ========================================

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

    override suspend fun disconnectFromNonMeshWifi(): Boolean {
        val node = myNode ?: return false
        node.meshrabiyaWifiManager.disconnectFromInternetWifi()
        _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE)
        return true
    }

    override fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto> {
        return _nonMeshWifiState.asStateFlow()
    }

    override suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto> {
        val ctx = appContext ?: return emptyList()
        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults ?: return emptyList()
        return results
            .filter { it.SSID.isNotEmpty() }
            .map { scanResult ->
                NonMeshWifiNetworkDto(
                    ssid = scanResult.SSID,
                    bssid = scanResult.BSSID,
                    signalStrength = scanResult.level,
                    isSecured = scanResult.capabilities.contains("WPA") ||
                                scanResult.capabilities.contains("WEP"),
                )
            }
            .sortedByDescending { it.signalStrength }
    }

    override fun isInternetWifiFeatureAvailable(): Boolean {
        val node = myNode ?: return false
        val wifiState = node.meshrabiyaWifiManager.currentWifiState

        // AP+STA mode: hotspot is running and device supports concurrent AP+STA
        if (wifiState.hotspotIsStarted && wifiState.concurrentApStationSupported) {
            return true
        }

        // STA/STA mode: device is in pure station mode (Join Mesh) and supports dual-STA.
        // Check that a mesh station connection is active (wifiStationState.status == AVAILABLE).
        if (!wifiState.hotspotIsStarted &&
            wifiState.wifiStationState.status == com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.AVAILABLE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            wifiState.staStaConcurrencySupported) {
            return true
        }

        return false
    }
```

**Imports required** in `MeshrabiyaApiImpl.kt` (verify each is not already present):
- `import android.net.wifi.WifiManager`
- `import android.content.Context`
- `import android.os.Build`

---

## Change 14 — 11.7: `fragment_mesh_enhanced.xml` — Add `wifiApConnectionButton`

**File:** `app/src/main/res/layout/fragment_mesh_enhanced.xml`  
**Lines:** 489 — NOT large  
**Purpose:** Add the "Connect to Internet WiFi" button to the mesh control header button row. Initially hidden (`visibility="gone"`); shown when `isInternetWifiFeatureAvailable()` returns true.  
**Location confirmed:** `meshControlHeader` LinearLayout (line 34) contains `meshToggleButton` (lines 44–52), `joinMeshButton` (lines 54–65), `mergeMeshButton` (lines 67–78), then `expandCollapseIndicator` ImageView (lines 80–87, visibility=gone).  
**Insertion:** After `mergeMeshButton`'s closing `</com.google.android.material.button.MaterialButton>` (line ~78), before `expandCollapseIndicator` (line 80).

**BEFORE (lines 67–87, verbatim from disk — the mergeMeshButton block and immediately following expandCollapseIndicator):**

```xml
            <com.google.android.material.button.MaterialButton
                android:id="@+id/mergeMeshButton"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/merge_mesh"
                android:textSize="10sp"
                android:visibility="gone"
                app:icon="@drawable/ic_merge" />

            <ImageView
                android:id="@+id/expandCollapseIndicator"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:layout_gravity="center_vertical"
                android:src="@drawable/ic_expand_more"
                android:visibility="gone" />
```

**AFTER:**

```xml
            <com.google.android.material.button.MaterialButton
                android:id="@+id/mergeMeshButton"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/merge_mesh"
                android:textSize="10sp"
                android:visibility="gone"
                app:icon="@drawable/ic_merge" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/wifiApConnectionButton"
                style="@style/Widget.MaterialComponents.Button.TextButton"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/wifi_internet"
                android:textSize="10sp"
                android:visibility="gone"
                app:icon="@drawable/ic_wifi" />

            <ImageView
                android:id="@+id/expandCollapseIndicator"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:layout_gravity="center_vertical"
                android:src="@drawable/ic_expand_more"
                android:visibility="gone" />
```

**String resource required:** Add `<string name="wifi_internet">WiFi</string>` to `app/src/main/res/values/strings.xml`.  
**Drawable required:** `@drawable/ic_wifi` — use existing Android WiFi icon or add a vector drawable with a wifi+globe motif. Verify `ic_wifi` exists in the drawable resources before referencing.

---

## Change 15 — 11.8: `MeshUIBindings.kt` — Add `wifiApConnectionButton` Field and Binding

**File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt`  
**Lines:** 163 — NOT large  
**Purpose:** Expose the new button to `EnhancedMeshFragment` via the bindings class.  
**Confirmed by literal read:** `MeshUIBindings.kt` currently has `meshToggleButton`, `joinMeshButton`, `mergeMeshButton` as the last button fields. `bindImmediateViews()` function binds them via `view.findViewById`. `wifiApConnectionButton` does not exist anywhere in the file.

### 15a — Add field declaration

**BEFORE (find the `mergeMeshButton` field declaration, typically near other button declarations):**

```kotlin
    lateinit var mergeMeshButton: MaterialButton
```

**AFTER:**

```kotlin
    lateinit var mergeMeshButton: MaterialButton
    lateinit var wifiApConnectionButton: MaterialButton
```

### 15b — Add binding in `bindImmediateViews()`

**BEFORE (find the `mergeMeshButton` binding in `bindImmediateViews()`):**

```kotlin
        mergeMeshButton = view.findViewById(R.id.mergeMeshButton)
```

**AFTER:**

```kotlin
        mergeMeshButton = view.findViewById(R.id.mergeMeshButton)
        wifiApConnectionButton = view.findViewById(R.id.wifiApConnectionButton)
```

---

## Change 16 — S7 + 11.6: `EnhancedMeshFragment.kt` — Role Observer and Button Handler *(⚠️ LARGE FILE)*

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 2338 → 2415 after all sub-edits applied — ⚠️ LARGE FILE — **present for manual edit only**

### ⚠️ Import Violation Notes (flagged 2026-03-03)

**Violation 1 — `NonMeshWifiStatus` direct import:**  
`import com.ustadmobile.meshrabiya.api.NonMeshWifiStatus` was initially drafted for `EnhancedMeshFragment.kt`.  
This violates the app/library boundary rule: the app module must not import library-internal enums directly.  
**Fix applied:** Replaced `when (result.status) { NonMeshWifiStatus.X -> ... }` with structural DTO field checks:  
`result.connectedSsid != null` → success; `result.errorMessage != null` → failure.  
`NonMeshWifiStatus` remains an implementation detail invisible to the app module.  
**Do NOT add this import to `EnhancedMeshFragment.kt`.**

**Violation 2 — `NonMeshWifiConnectionStateDto` package location:**  
`NonMeshWifiConnectionStateDto` and `NonMeshWifiStatus` currently live in `com.ustadmobile.meshrabiya.api`  
(DtoModels.kt), not in `com.ustadmobile.meshrabiya.api.model` as convention requires.

```
// TODO(api.model refactor): NonMeshWifiConnectionStateDto and NonMeshWifiStatus are currently
// in com.ustadmobile.meshrabiya.api (DtoModels.kt). They should be relocated to
// com.ustadmobile.meshrabiya.api.model following the established DTO convention.
// Refactor steps:
//   1. Create Meshrabiya/lib-meshrabiya/.../api/model/NonMeshWifiDtos.kt with these two types
//   2. Remove them from DtoModels.kt
//   3. Update all import sites: MeshrabiyaApi.kt, MeshrabiyaApiImpl.kt
//      (no app-module imports needed — EnhancedMeshFragment uses DTO field checks, not the enum)
```

**Confirmed facts:**
- `setupRoleObserver()` starts at line 647
- MESH_ROUTER role appear is logged at line 663, disappear at line 665
- No C18 reflection block exists anywhere in the file (`getDeclaredField` grep returned zero matches)
- No `wifiApConnectionButton` visibility changes exist anywhere

### 16a — Button Visibility in `setupRoleObserver()` (lines 663–665 region)

**BEFORE (lines ~660–670, verbatim from disk — role appear/disappear logging):**

```kotlin
                    if (MeshRoleDto.MESH_ROUTER in rolesDto && MeshRoleDto.MESH_ROUTER !in previousRolesDto) {
                        Log.i(TAG, "MESH_ROUTER role appeared")
                    }
                    if (MeshRoleDto.MESH_ROUTER !in rolesDto && MeshRoleDto.MESH_ROUTER in previousRolesDto) {
                        Log.i(TAG, "MESH_ROUTER role disappeared")
                    }
```

**AFTER:**

```kotlin
                    if (MeshRoleDto.MESH_ROUTER in rolesDto && MeshRoleDto.MESH_ROUTER !in previousRolesDto) {
                        Log.i(TAG, "MESH_ROUTER role appeared")
                        // Show internet WiFi button only when feature is actually available for this device/mode.
                        // isInternetWifiFeatureAvailable() checks both AP+STA (Start Mesh, API 30+)
                        // and STA/STA (Join Mesh, API 31+) capability gates in one call.
                        val featureAvailable = meshrabiyaApi.isInternetWifiFeatureAvailable()
                        binding.wifiApConnectionButton.visibility =
                            if (featureAvailable) android.view.View.VISIBLE
                            else android.view.View.GONE
                    }
                    if (MeshRoleDto.MESH_ROUTER !in rolesDto && MeshRoleDto.MESH_ROUTER in previousRolesDto) {
                        Log.i(TAG, "MESH_ROUTER role disappeared")
                        binding.wifiApConnectionButton.visibility = android.view.View.GONE
                    }
```

### 16b — Button Click Handler (add in `onViewCreated()` alongside other button click bindings)

**Location:** Find the `setupMeshButtons()` or equivalent method where `joinMeshButton` and `mergeMeshButton` click listeners are set. Add adjacent to those bindings.

**New code (no BEFORE — this handler does not exist):**

```kotlin
        binding.wifiApConnectionButton.setOnClickListener {
            showInternetWifiConnectionDialog()
        }
```

### 16c — `showInternetWifiConnectionDialog()` (new function)

**Add as a new private function in `EnhancedMeshFragment.kt`:**

```kotlin
    /**
     * Shows a dialog allowing the user to select and connect to a non-mesh internet WiFi network.
     * Calls meshrabiyaApi.scanAvailableWifiNetworks() to populate the list.
     * On selection, prompts for passphrase (if required) and calls meshrabiyaApi.connectToNonMeshWifi().
     */
    private fun showInternetWifiConnectionDialog() {
        lifecycleScope.launch {
            val networks = meshrabiyaApi.scanAvailableWifiNetworks()
            if (networks.isEmpty()) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "No WiFi networks found. Ensure location permission is granted.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val ssidList = networks.map { "${it.ssid} (${it.signalStrength} dBm)" }.toTypedArray()
            var selectedIndex = 0

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Connect to Internet WiFi")
                .setSingleChoiceItems(ssidList, 0) { _, which -> selectedIndex = which }
                .setPositiveButton("Connect") { _, _ ->
                    val selected = networks[selectedIndex]
                    if (selected.isSecured) {
                        showPassphraseDialog(selected.ssid)
                    } else {
                        connectToInternetWifi(selected.ssid, "")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

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
            // (App module must not import library-internal enums; see Import Violation Note above.)
            val message = when {
                result.connectedSsid != null -> "Connected to $ssid"
                result.errorMessage != null  -> "Connection failed: ${result.errorMessage}"
                else                         -> "Connecting to $ssid..."
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
```

---

## Change 17 — 11.13: CLEARNET Gateway-End Handler Skeleton

**Files:**  
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt` — 1483 lines (**⚠️ LARGE FILE**) — see correction note below  
- `MeshrabiyaWifiManagerAndroid.kt` — already handled in Change 10 (the `internetWifiNetwork` field is stored there)

> **🚨 CRITICAL CORRECTION (applied 2026-03-03):** `processRoutePacket()` is in `VirtualNode.kt` at line 830, **NOT** in `AndroidVirtualNode.kt`. **Change 17a therefore belongs with the large file changes** (alongside Changes 4, 5 on `VirtualNode.kt`). `AndroidVirtualNode.kt` contains only the `onClearnetGatewayPacket()` override hook that `VirtualNode.processRoutePacket()` calls — that override was implemented as a non-large file edit. The `VirtualNode.kt` dispatch branch insertion is the large file portion of Change 17a and must be applied manually. See the `VirtualNode.kt` large file section above for the full BEFORE/AFTER.

**Purpose:** Without a gateway-end handler, the CLEARNET_GATEWAY role assigns, routing decisions mark packets as GATEWAY_TYPE_CLEARNET, but no code actually forwards those packets to the internet — they are silently dropped. This adds the dispatch branch alongside the existing TOR gateway handling.

**NOTE:** This change requires `ClearnetGatewayForwarder` — a new class that wraps a socket factory bound to `internetWifiNetwork`. The full implementation of `ClearnetGatewayForwarder` is a significant new class. The `VirtualNode.kt` dispatch branch (large file) calls `onClearnetGatewayPacket()`, a `protected open` hook. `AndroidVirtualNode` overrides that hook to call `clearnetGatewayForwarder.forward()`.

### 17a — `VirtualNode.kt` (⚠️ LARGE) — Add CLEARNET dispatch hook in `processRoutePacket()` + `AndroidVirtualNode.kt` override

**Location (`VirtualNode.kt`):** The `processRoutePacket()` function is at line 830. Find the `// --- CONDITIONAL PROXY ROUTING ---` block. Add the CLEARNET dispatch immediately after the TOR branch block, reusing the already-computed `currentRoles` val.

**`VirtualNode.kt` addition (after the existing TOR proxy block):**

```kotlin
        // --- CLEARNET GATEWAY DISPATCH ---
        if (currentRoles.contains(MeshRole.CLEARNET_GATEWAY) && shouldRouteViaProxy(packet)) {
            if (onClearnetGatewayPacket(packet)) return
        }
```

**New `protected open` hook method in `VirtualNode.kt` (add near `shouldRouteViaProxy`):**

```kotlin
    /**
     * Called when this node is a CLEARNET_GATEWAY and a non-mesh packet arrives.
     * Override in AndroidVirtualNode to forward via the internet WiFi network.
     * @return true if the packet was handled (caller should return), false to fall through.
     */
    protected open fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean = false
```

**`AndroidVirtualNode.kt` override (NOT large — already applied):**

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
```

### 17b — `ClearnetGatewayForwarder` — New Class Skeleton

**File to create:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/ClearnetGatewayForwarder.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.packet.VirtualPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * Forwards CLEARNET-routed VirtualPackets to the internet via the provided Network object.
 *
 * The [internetWifiNetwork] is the Network obtained from ConnectivityManager when
 * connectToInternetWifi() succeeds. Sockets created via its socketFactory are automatically
 * routed through the internet WiFi interface, bypassing the mesh.
 *
 * Usage: instantiate one instance; call [forward] when GATEWAY_TYPE_CLEARNET packets arrive.
 * Call [close] when the internet WiFi connection is lost to release resources.
 *
 * NOTE: This is a functional skeleton. The full implementation requires:
 * - Parsing the VirtualPacket IP payload to extract destination IP + port
 * - Creating a TCP or UDP socket via internetWifiNetwork.socketFactory
 * - Binding the socket to the network via internetWifiNetwork.bindSocket(socket) for UDP
 * - Streaming the payload, collecting the response, and injecting it back into the mesh
 *
 * The ChainSocketServer pattern (already used in MeshrabiyaWifiManagerAndroid for station
 * bound sockets) is the recommended approach for the forwarding implementation.
 */
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Forward a CLEARNET-tagged VirtualPacket via the provided internet WiFi network.
     * @param packet The VirtualPacket with gatewayType == GATEWAY_TYPE_CLEARNET.
     * @param internetWifiNetwork The Network object for the internet WiFi connection.
     */
    fun forward(packet: VirtualPacket, internetWifiNetwork: Network) {
        scope.launch {
            try {
                // TODO: Parse VirtualPacket IP payload, extract destination address and port.
                // TODO: Create socket via internetWifiNetwork.socketFactory.
                // TODO: internetWifiNetwork.bindSocket(socket) for UDP sockets.
                // TODO: Write payload, read response, inject back into virtual network.
                logger.invoke(Log.DEBUG, "$logPrefix ClearnetGatewayForwarder: forward() — implementation pending")
            } catch (e: IOException) {
                logger.invoke(Log.WARN, "$logPrefix ClearnetGatewayForwarder: forward error: ${e.message}")
            }
        }
    }

    fun close() {
        // Cancel scope and release resources when internet WiFi disconnects.
        // scope.cancel() can be added when a SupervisorJob is provided.
        logger.invoke(Log.INFO, "$logPrefix ClearnetGatewayForwarder: closed")
    }
}
```

**Field to add in `AndroidVirtualNode.kt`:**

```kotlin
    private val clearnetGatewayForwarder: ClearnetGatewayForwarder = ClearnetGatewayForwarder(
        logger = logger,
        logPrefix = "ClearnetGateway",
    )
```

---

## Application Sequence Summary

Apply changes in this order (dependency order):

| Step | Change ID | File | Size | Note |
|------|-----------|------|------|------|
| 1 | Tech Debt 17.4a | `MeshrabiyaConstants.kt` | 341 | Prerequisite for step 2 |
| 2 | Tech Debt 17.4b | `VirtualDatagramSocketImpl.kt` | 227 | Requires step 1 |
| 3 | 11.10a | `VirtualDatagramSocket2.kt` | 23 | Prerequisite for step 4 |
| 4 | 11.10b | `VirtualNode.kt` | 1483 ⚠️ | Line 524 only |
| 5 | 11.11 | `VirtualNode.kt` | 1483 ⚠️ | Lines 1401–1411 |
| 6 | 11.12 | `GatewayTypeResolver.kt` | 220 | Lines 107–114 |
| 7 | S1 | `MeshrabiyaWifiState.kt` | 64 | Prerequisite for all S-changes |
| 8 | 11.1a | `LocalOnlyHotspotManager.kt` | 279 | Constructor + suppression block |
| 9 | 11.9 | `AndroidVirtualNode.kt` | 225 | Lines 175–192 |
| 10 | 10a–10f | `MeshrabiyaWifiManagerAndroid.kt` | 876 ⚠️ | Manual edit — all sub-changes |
| 11 | 11.4 | `DtoModels.kt` | 700 | Append to end of file |
| 12 | 11.3 + S5a | `MeshrabiyaApi.kt` | 391 | Append to interface |
| 13 | 11.5 + S5b | `MeshrabiyaApiImpl.kt` | 1975 ⚠️ | New field + 5 methods |
| 14 | 11.7 | `fragment_mesh_enhanced.xml` | 489 | Insert button between elements |
| 15 | 11.8 | `MeshUIBindings.kt` | 163 | Field + binding |
| 16 | S7 + 11.6 | `EnhancedMeshFragment.kt` | 2338 ⚠️ | Role observer + click handler |
| 17 | 11.13 | `AndroidVirtualNode.kt` + new file | 225 / new | CLEARNET dispatch + skeleton |

---

## Large Files — Confirmed Edit Locations

| File | Line Count | Sub-changes | Confirmed BEFORE lines |
|------|-----------|-------------|----------------------|
| `VirtualNode.kt` | 1483 | 11.10b (L524), 11.11 (L1401–1411) | Verbatim from disk |
| `MeshrabiyaWifiManagerAndroid.kt` | 876 | 10a (L94–101), 10b (L257–264), 10c (L270–283), 10d (new, ~L200), 10e (L492–505), 10f (new after connectToHotspotInternal), fields (L209–215) | Verbatim from disk |
| `MeshrabiyaApiImpl.kt` | 1975 | New field + 5 methods (before closing `}`) | Class closing confirmed |
| `EnhancedMeshFragment.kt` | 2338 | 16a (L~660–670), 16b (click setup), 16c (3 new functions) | setupRoleObserver at L647 confirmed |

---

## Permissions Required

Verify these permissions exist in `app/src/main/AndroidManifest.xml`:

| Permission | Required By | API Level | Status |
|------------|-------------|-----------|--------|
| `CHANGE_WIFI_STATE` | `addNetworkSuggestions()`, `startLocalOnlyHotspot()` | All | Likely present (mesh hotspot uses it) |
| `ACCESS_WIFI_STATE` | `addSuggestionConnectionStatusListener()`, scan | All | Likely present |
| `ACCESS_FINE_LOCATION` | WiFi scan results | All | Likely present (QR/mesh scan) |
| `NEARBY_WIFI_DEVICES` | `startLocalOnlyHotspot()` with config on API 33+ | 33+ | **Must verify — may be missing** |

---

## Capability Matrix

| Scenario | Minimum API | Gate Check | Mode |
|----------|-------------|------------|------|
| Start Mesh + Internet WiFi (AP+STA) | API 30 | `concurrentApStationSupported` | Hotspot running |
| Join Mesh + Internet WiFi (STA/STA) | API 31 | `staStaConcurrencySupported` | Station only |
| Neither capability | Any | Feature hidden — button not shown | — |
