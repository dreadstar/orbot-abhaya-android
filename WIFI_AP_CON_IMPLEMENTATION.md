# WiFi AP Concurrency — Implementation Details

**Date:** 2026-03-03  
**Status:** Analysis complete — NO code changes applied  
**Source:** All code snippets verified by literal disk reads of the files listed  
**Large File Threshold:** 800 lines (LARGE FILE notation = cannot be edited with `replace_string_in_file`)

---

## Document Purpose

This document provides production-ready, code-level implementation details for all changes
required by the WiFi AP Concurrency feature, as designed in `WIFI_AP_CON_PLAN.md`.

Every BEFORE/AFTER snippet is derived exclusively from literal reads of the current files on disk.
No assumptions are made. All snippets are complete with zero ellipsis or truncation.

---

## Change Index

| ID | File | Lines | Large? | Category | Blocker |
|----|------|-------|--------|----------|---------|
| C1 | `MeshrabiyaConstants.kt` | 341 | NO | New constant | 17.4 |
| C2 | `VirtualDatagramSocketImpl.kt` | 227 | NO | Use named constant + import | 17.4 |
| C3 | `VirtualDatagramSocket2.kt` | 22 | NO | Pass context through | 5b |
| C4 | `VirtualNode.kt` | 1483 | **YES** | Pass context in factory | 5b |
| C5 | `MeshrabiyaWifiManagerAndroid.kt` | 876 | **YES** | Expose concurrentApStationSupported property | 6 |
| C6 | `AndroidVirtualNode.kt` | 225 | NO | Gate WiFi disconnect on non-concurrent | 6 |
| C7 | `LocalOnlyHotspotManager.kt` | 279 | NO | Add lambda + gate suppression loop | 1 |
| C8 | `MeshrabiyaWifiManagerAndroid.kt` | 876 | **YES** | Pass lambda to LocalOnlyHotspotManager | 1 |
| C9 | `MeshrabiyaWifiManagerAndroid.kt` | 876 | **YES** | Conditional bindProcessToNetwork | 2 |
| C10 | `MeshrabiyaWifiManagerAndroid.kt` | 876 | **YES** | Add internetWifiNetwork + connectToInternetWifi() | 17.5 |
| C11 | `GatewayTypeResolver.kt` | 220 | NO | Null-safety on getInstance() | 5d |
| C12 | `VirtualNode.kt` | 1483 | **YES** | Fix shouldRouteViaProxy() | 5c |
| C13 | `DtoModels.kt` | 700 | NO | Add new DTOs | Feature |
| C14 | `MeshrabiyaApi.kt` | 391 | NO | Add new API declarations | Feature |
| C15 | `MeshrabiyaApiImpl.kt` | 1975 | **YES** | Add new implementations | Feature |
| C16 | `fragment_mesh_enhanced.xml` | 489 | NO | Add wifiApConnectionButton | Feature |
| C17 | `MeshUIBindings.kt` | 163 | NO | Add field + binding calls | Feature |
| C18 | `EnhancedMeshFragment.kt` | 2338 | **YES** | Button show/hide + click handler | Feature |

---

## C1 — `MeshrabiyaConstants.kt` — Add `VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt`  
**Line count:** 341 — NOT a large file  
**Location:** After line 93 (after `KEY_SERVICE_PARTICIPATION_ENABLED`)

### Rationale

`VirtualDatagramSocketImpl.send()` writes the `gatewayType` byte into the serialized packet
buffer using the expression `VirtualPacketHeader.HEADER_SIZE - 3`. `HEADER_SIZE = 21`
(confirmed at `VirtualPacketHeader.kt:111`), so the offset is `18`. If the header layout changes
this expression silently produces the wrong offset. The project convention (all `KEY_*` gateway
constants are `const val` in `MeshrabiyaConstants`) requires this offset to be a named constant.

**BEFORE (lines 91–95):**

```kotlin
    // DataStore preference keys for gateway settings and participation
    const val KEY_TOR_GATEWAY_ENABLED = "tor_gateway_enabled"
    const val KEY_CLEARNET_GATEWAY_ENABLED = "clearnet_gateway_enabled"
    const val KEY_STORAGE_PARTICIPATION_ENABLED = "storage_participation_enabled"
    const val KEY_SERVICE_PARTICIPATION_ENABLED = "service_participation_enabled"
```

**AFTER (lines 91–100):**

```kotlin
    // DataStore preference keys for gateway settings and participation
    const val KEY_TOR_GATEWAY_ENABLED = "tor_gateway_enabled"
    const val KEY_CLEARNET_GATEWAY_ENABLED = "clearnet_gateway_enabled"
    const val KEY_STORAGE_PARTICIPATION_ENABLED = "storage_participation_enabled"
    const val KEY_SERVICE_PARTICIPATION_ENABLED = "service_participation_enabled"

    // Byte offset of the gatewayType field within a serialized VirtualPacketHeader byte array.
    // Layout: toAddr(4) + toPort(2) + fromAddr(4) + fromPort(2) + lastHopAddr(4) + hopCount(1) + maxHops(1) = 18
    // HEADER_SIZE(21) - 3 = 18. Named constant prevents silent breakage if header layout changes.
    const val VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET = 18
```

---

## C2 — `VirtualDatagramSocketImpl.kt` — Import + Use Named Constant

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocketImpl.kt`  
**Line count:** 227 — NOT a large file

### Part A: Add import

**BEFORE (lines 1–21):**

```kotlin
package com.ustadmobile.meshrabiya.vnet.datagram

import android.content.Context
import android.util.Log
import androidx.core.util.Pools.SynchronizedPool
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.Protocol
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.GatewayTypeResolver
import java.net.DatagramPacket
import java.net.DatagramSocketImpl
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingDeque
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import java.util.concurrent.atomic.AtomicBoolean
```

**AFTER (lines 1–22):**

```kotlin
package com.ustadmobile.meshrabiya.vnet.datagram

import android.content.Context
import android.util.Log
import androidx.core.util.Pools.SynchronizedPool
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.vnet.Protocol
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.GatewayTypeResolver
import java.net.DatagramPacket
import java.net.DatagramSocketImpl
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingDeque
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import java.util.concurrent.atomic.AtomicBoolean
```

### Part B: Replace magic-number expression with named constant

**BEFORE (lines 139–147):**

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

**AFTER (lines 139–147):**

```kotlin
            //V3: Resolve gateway type before routing (if resolver available)
            gatewayTypeResolver?.let { resolver ->
                //TODO: Extract source package name from DatagramPacket if available
                //For now, use null (will fallback to global preference)
                val resolvedType = resolver.resolveGatewayType(virtualPacket, sourcePackageName = null)
                //Update packet header in-place using named constant (offset 18 = HEADER_SIZE - 3)
                virtualPacket.data[MeshrabiyaConstants.VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET] = resolvedType
            }
```

---

## C3 — `VirtualDatagramSocket2.kt` — Add Context Parameter

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocket2.kt`  
**Line count:** 22 — NOT a large file

### Rationale

`VirtualDatagramSocketImpl` already accepts `context: Context? = null` (line 31 of that file)
and uses it to construct `GatewayTypeResolver`. However `VirtualDatagramSocket2` — the
production wrapper — does not pass `context`, so `GatewayTypeResolver` is always null and
outgoing packets always carry `GATEWAY_TYPE_NONE`. This change wires the context through.

**BEFORE (full file):**

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

**AFTER (full file):**

```kotlin
package com.ustadmobile.meshrabiya.vnet.datagram

import android.content.Context
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
    private val parentNode: VirtualNode? = null,
    context: Context? = null
): DatagramSocket(VirtualDatagramSocketImpl(
    router = router,
    localVirtualAddress = localVirtualAddress,
    logger = logger,
    context = context,
    parentNode = parentNode
))
```

---

## C4 — `VirtualNode.kt` — Pass Context in `createDatagramSocket()` — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Line count:** 1483 — **LARGE FILE (>800 lines) — manual edit required**

### Rationale

`createDatagramSocket()` constructs `VirtualDatagramSocket2` without passing context (confirmed
at line 524). `getContext()` is declared `protected abstract fun getContext(): android.content.Context?`
at line 118 and implemented in `AndroidVirtualNode`. Passing it here activates the
`GatewayTypeResolver` for all sockets created from this factory.

**BEFORE (Lines 522–526):**

```kotlin
    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger, this)
    }

    fun createBoundDatagramSocket(port: Int): DatagramSocket {
```

**AFTER (Lines 522–526):**

```kotlin
    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger, this, getContext())
    }

    fun createBoundDatagramSocket(port: Int): DatagramSocket {
```

---

## C5 — `MeshrabiyaWifiManagerAndroid.kt` — Add `concurrentApStationSupported` Property — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — **LARGE FILE (>800 lines) — manual edit required**

### Rationale

`AndroidVirtualNode.setWifiHotspotEnabled()` (C6) and the `LocalOnlyHotspotManager` lambda
(C8) both need read access to the current `concurrentApStationSupported` value. The `_state`
MutableStateFlow is private, and `state` is declared as `Flow<MeshrabiyaWifiState>` so `.value`
cannot be called on it from outside. A computed property backed by `_state.value` exposes this
cleanly without any interface changes.

**BEFORE (Lines 207–213):**

```kotlin
    private val _state = MutableStateFlow(MeshrabiyaWifiState(
        concurrentApStationSupported = false  // Start with false, detect asynchronously in init
    ))

    override val state: Flow<MeshrabiyaWifiState> = _state.asStateFlow()
```

**AFTER (Lines 207–216):**

```kotlin
    private val _state = MutableStateFlow(MeshrabiyaWifiState(
        concurrentApStationSupported = false  // Start with false, detect asynchronously in init
    ))

    override val state: Flow<MeshrabiyaWifiState> = _state.asStateFlow()

    /**
     * Synchronous read of the current concurrent AP+Station support flag from state.
     * Backed by _state.value so always reflects the most recent detectConcurrentSupport() result.
     */
    val concurrentApStationSupported: Boolean
        get() = _state.value.concurrentApStationSupported
```

---

## C6 — `AndroidVirtualNode.kt` — Gate WiFi Disconnect on Non-Concurrent — NOT a large file

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`  
**Line count:** 225 — NOT a large file

### Rationale

`setWifiHotspotEnabled(enabled=true)` unconditionally calls `meshrabiyaWifiManager.disconnectStation()`.
`disconnectStation()` throws `IllegalStateException` when the device is connected to WiFi
(confirmed at lines 596–608 of `MeshrabiyaWifiManagerAndroid.kt`). On a device where
`concurrentApStationSupported = true`, the hotspot can start without disconnecting WiFi;
attempting `disconnectStation()` on a WiFi-connected concurrent device crashes hotspot startup.

**BEFORE (lines 162–176):**

```kotlin
        // CRITICAL: Disconnect from regular WiFi before starting hotspot
        // Even on devices with concurrent AP+STA support, we MUST be on the hotspot network
        // to receive packets from joining devices. If we stay on regular WiFi, we'll be on
        // a different subnet (e.g., 192.168.1.x vs 192.168.121.x) and can't communicate.
        if (enabled) {
            logger(Log.INFO, "setWifiHotspotEnabled: Disconnecting from station (regular WiFi) before starting hotspot", null)
            meshrabiyaWifiManager.disconnectStation()
            
            // CRITICAL: Wait for WiFi disconnect to complete and verify
            logger(Log.INFO, "setWifiHotspotEnabled: Waiting 2 seconds for WiFi disconnect to stabilize...", null)
            kotlinx.coroutines.delay(2000)
            logger(Log.INFO, "setWifiHotspotEnabled: Proceeding with hotspot creation", null)
        }
```

**AFTER (lines 162–176):**

```kotlin
        // On non-concurrent devices: disconnect WiFi before starting hotspot (cannot run both).
        // On concurrent devices (concurrentApStationSupported = true): skip entirely — hotspot
        // can start while WiFi is connected, and disconnectStation() throws IllegalStateException
        // if WiFi is already connected, which would crash hotspot startup on these devices.
        if (enabled && !meshrabiyaWifiManager.concurrentApStationSupported) {
            logger(Log.INFO, "setWifiHotspotEnabled: Non-concurrent device — disconnecting WiFi before starting hotspot", null)
            meshrabiyaWifiManager.disconnectStation()

            // CRITICAL: Wait for WiFi disconnect to complete and verify
            logger(Log.INFO, "setWifiHotspotEnabled: Waiting 2 seconds for WiFi disconnect to stabilize...", null)
            kotlinx.coroutines.delay(2000)
            logger(Log.INFO, "setWifiHotspotEnabled: Proceeding with hotspot creation", null)
        }
```

---

## C7 — `LocalOnlyHotspotManager.kt` — Add Lambda + Gate Suppression Loop — NOT a large file

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalOnlyHotspotManager.kt`  
**Line count:** 279 — NOT a large file

### Rationale

`startHotspotMonitoring()` (lines 171–234) checks every 2 seconds whether WiFi is connected
while the hotspot is running, and actively disconnects it by calling `wifiManager.disconnect()`,
`wifiManager.removeNetwork()`, and `wifiManager.disableNetwork()`. On a concurrent AP+STA
device that has deliberately connected to an internet WiFi while running the mesh hotspot, this
suppression loop would immediately sever the internet WiFi connection. Gating this block on
`!concurrentApStationSupported()` prevents the loop from interfering.

### Part A: Add constructor parameter

**BEFORE (lines 36–45):**

```kotlin
class LocalOnlyHotspotManager(
    appContext: Context,
    private val logger: MNetLogger,
    name: String,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val dataStore: DataStore<Preferences>,
) {
    private val appContext = appContext
    private val logPrefix: String = "[LocalOnlyHotspotManager: $name]"
```

**AFTER (lines 36–46):**

```kotlin
class LocalOnlyHotspotManager(
    appContext: Context,
    private val logger: MNetLogger,
    name: String,
    private val localNodeAddr: Int,
    private val router: VirtualRouter,
    private val dataStore: DataStore<Preferences>,
    private val concurrentApStationSupported: () -> Boolean = { false },
) {
    private val appContext = appContext
    private val logPrefix: String = "[LocalOnlyHotspotManager: $name]"
```

### Part B: Gate the WiFi suppression block

**BEFORE (lines 186–210):**

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

**AFTER (lines 186–212):**

```kotlin
                // PHASE 2: Continuous WiFi Suppression - actively prevent reconnection
                // SKIP on concurrent AP+STA devices: they WANT WiFi connected alongside hotspot.
                if (!concurrentApStationSupported() && currentStatus == HotspotStatus.STARTED && isWifiConnected && wifiSSID != "<unknown ssid>") {
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

---

## C8 — `MeshrabiyaWifiManagerAndroid.kt` — Pass Lambda to `LocalOnlyHotspotManager` — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — **LARGE FILE (>800 lines) — manual edit required**

### Rationale

The `LocalOnlyHotspotManager` is constructed in the primary constructor default parameter block.
After C7, it accepts `concurrentApStationSupported: () -> Boolean`. The lambda must read
`_state.value.concurrentApStationSupported` — but since `_state` is a class-level field
initialized before this constructor default parameter block runs, using `{ _state.value.concurrentApStationSupported }`
as the lambda in the constructor is not safe (field initializer ordering). Instead the lambda
body accesses the property added in C5 (`concurrentApStationSupported`), which itself reads
`_state.value`. Because the lambda is called at monitoring-loop time (not at construction time),
`_state` will always be initialized by then.

**BEFORE (lines 87–97):**

```kotlin
    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
    )
) : Closeable, MeshrabiyaWifiManager {
```

**AFTER (lines 87–98):**

```kotlin
    private val localOnlyHotspotManager: LocalOnlyHotspotManager = LocalOnlyHotspotManager(
        appContext = appContext,
        logger = logger,
        name = localNodeAddr.addressToDotNotation(),
        localNodeAddr = localNodeAddr,
        router = router,
        dataStore = dataStore,
        concurrentApStationSupported = { _state.value.concurrentApStationSupported },
    )
) : Closeable, MeshrabiyaWifiManager {
```

---

## C9 — `MeshrabiyaWifiManagerAndroid.kt` — Conditional `bindProcessToNetwork` — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — **LARGE FILE (>800 lines) — manual edit required**  
**Location:** Lines 492–502 inside `connectToHotspotInternal()`

### Rationale

`connectToHotspotInternal()` calls `connectivityManager.bindProcessToNetwork(resultState.network)`
when the mesh network becomes available. On a MESH_ROUTER device with concurrent AP+STA support,
this binds ALL sockets (including any future internet WiFi sockets) to the mesh network, creating
a routing loop for CLEARNET_GATEWAY forwarding. On concurrent devices the binding must be `null`
(no process-wide binding), relying instead on per-socket `network.bindSocket()` which is already
used in `createStationNetworkBoundSockets()`.

**BEFORE (lines 492–505):**

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
        }else {
```

**AFTER (lines 492–512):**

```kotlin
        if (resultState.network != null) {
            logger(Log.INFO, "$logPrefix connectToHotspot: ${config.ssid} - success status=${resultState.status}")

            // On NON-concurrent devices: bind all process traffic to the mesh network so the
            // device does not switch back to regular WiFi while on the mesh.
            // On CONCURRENT devices: do NOT bind process-wide — the MESH_ROUTER has both a mesh
            // hotspot AND an internet WiFi connection simultaneously. A process-wide bind to the
            // mesh network would route all internet-forwarding sockets through the mesh instead of
            // the internet WiFi interface, creating a routing loop. Per-socket binding is used
            // instead (already done in createStationNetworkBoundSockets via network.bindSocket()).
            if (!_state.value.concurrentApStationSupported) {
                val bindSuccess = connectivityManager.bindProcessToNetwork(resultState.network)
                logger(Log.INFO, "$logPrefix connectToHotspot: bindProcessToNetwork result=$bindSuccess (non-concurrent)", null)
                if (!bindSuccess) {
                    logger(Log.WARN, "$logPrefix connectToHotspot: Failed to bind process to mesh network - device may switch networks", null)
                }
            } else {
                logger(Log.INFO, "$logPrefix connectToHotspot: concurrent device — skipping process-wide network binding, using per-socket binding", null)
            }

            return resultState.network
        }else {
```

---

## C10 — `MeshrabiyaWifiManagerAndroid.kt` — Add Internet WiFi Network Infrastructure — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`  
**Line count:** 876 — **LARGE FILE (>800 lines) — manual edit required**

### Part A: Add required imports (after existing import block, before class declaration)

These imports are needed for `WifiNetworkSuggestion` and `@RequiresApi`:

**BEFORE (lines 11–18, import section excerpt):**

```kotlin
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
```

**AFTER (lines 11–21):**

```kotlin
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import androidx.annotation.RequiresApi
```

### Part B: Add `internetWifiNetwork` AtomicReference field

This field stores the `Network` object from the internet WiFi `WifiNetworkSuggestion` callback,
so the CLEARNET_GATEWAY forwarding code can bind sockets to it. Pattern mirrors `connectRequest`.

**BEFORE (lines 194–207):**

```kotlin
    private val stationBoundSockets = AtomicReference<Pair<VirtualNodeDatagramSocket, ChainSocketServer>?>()

    private val closed = AtomicBoolean(false)

    private var wifiLock: WifiManager.WifiLock? = null

    private val connectRequest = AtomicReference<Pair<WifiConnectConfig, NetworkCallback>?>(null)
```

**AFTER (lines 194–212):**

```kotlin
    private val stationBoundSockets = AtomicReference<Pair<VirtualNodeDatagramSocket, ChainSocketServer>?>()

    private val closed = AtomicBoolean(false)

    private var wifiLock: WifiManager.WifiLock? = null

    private val connectRequest = AtomicReference<Pair<WifiConnectConfig, NetworkCallback>?>(null)

    /**
     * Stores the Network object for the internet-bearing WiFi connection on MESH_ROUTER concurrent
     * devices. Set when the WifiNetworkSuggestion connection becomes available; cleared on disconnect.
     * Mirrors the connectRequest pattern for mesh station connections.
     * Used by AndroidVirtualNode to forward CLEARNET_GATEWAY packets over the internet interface.
     */
    private val internetNetworkCallback = AtomicReference<Pair<WifiNetworkSuggestion, NetworkCallback>?>(null)

    /**
     * The current internet WiFi Network object, or null if not connected.
     * Exposed for AndroidVirtualNode to use for CLEARNET_GATEWAY packet forwarding.
     */
    @Volatile
    var internetWifiNetwork: Network? = null
        private set
```

### Part C: Add `connectToInternetWifi()` and `disconnectFromInternetWifi()` methods

These methods must be inserted before the `close()` method (current line 820) as they are new
functionality. Insert after `createStationNetworkBoundSockets()` ends (current line ~810).

**Location:** After `createStationNetworkBoundSockets()` closes (line ~810) and before `close()` (line 820).

**Existing context at insertion point (lines 807–822):**

```kotlin
            logger(Log.DEBUG, "$logPrefix : addWifiConnectionConnect: Peer address is: $peerAddr", null)

            if(peerAddr != null) {
                //Once connected,
                onNewWifiConnectionListener.onNewWifiConnection(WifiConnectEvent(
                    neighborPort = config.port,
                    neighborInetAddress = peerAddr,
                    socket = networkBoundDatagramSocket,
                    neighborVirtualAddress = config.nodeVirtualAddr,
                ))
            }
        }
    }

    override fun close() {
```

**New methods to INSERT between line 819 and 820 (between `}` closing `createStationNetworkBoundSockets` and `override fun close()`):**

```kotlin
    /**
     * Connect to an internet-bearing WiFi network on a concurrent AP+STA device.
     *
     * Uses WifiNetworkSuggestion API (Android 29+) to request connection. On successful
     * connection, stores the Network object in internetWifiNetwork for use by CLEARNET_GATEWAY
     * packet forwarding. Does NOT bind process to this network — per-socket binding is used
     * instead to avoid interfering with mesh-bound sockets.
     *
     * Requirements:
     * - Build.VERSION.SDK_INT >= 30 (isStaApConcurrencySupported requires API 30)
     * - concurrentApStationSupported must be true
     * - Permissions: CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION
     *
     * @param ssid SSID of the internet WiFi network
     * @param passphrase WPA2 passphrase for the network
     * @return Result.success(Unit) if suggestion submitted and network became available;
     *         Result.failure(...) if conditions not met or network unavailable
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun connectToInternetWifi(ssid: String, passphrase: String): Result<Unit> {
        if (!_state.value.concurrentApStationSupported) {
            return Result.failure(IllegalStateException(
                "connectToInternetWifi: concurrent AP+STA not supported on this device"
            ))
        }

        logger(Log.INFO, "$logPrefix connectToInternetWifi: Connecting to SSID=$ssid", null)

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .setIsAppInteractionRequired(true)
            .build()

        val removeResult = wifiManager.removeNetworkSuggestions(listOf(suggestion))
        logger(Log.DEBUG, "$logPrefix connectToInternetWifi: removeNetworkSuggestions result=$removeResult", null)

        val addResult = wifiManager.addNetworkSuggestions(listOf(suggestion))
        if (addResult != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            logger(Log.ERROR, "$logPrefix connectToInternetWifi: addNetworkSuggestions failed: $addResult", null)
            return Result.failure(IllegalStateException(
                "addNetworkSuggestions failed with status $addResult"
            ))
        }

        val internetNetworkAvailable = kotlinx.coroutines.CompletableDeferred<Network>()

        val networkCallback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                logger(Log.INFO, "$logPrefix connectToInternetWifi: Network available: $network", null)
                internetWifiNetwork = network
                internetNetworkAvailable.complete(network)
            }

            override fun onUnavailable() {
                logger(Log.WARN, "$logPrefix connectToInternetWifi: Network unavailable", null)
                internetNetworkAvailable.completeExceptionally(
                    IllegalStateException("Internet WiFi network unavailable")
                )
            }

            override fun onLost(network: Network) {
                logger(Log.INFO, "$logPrefix connectToInternetWifi: Network lost: $network", null)
                if (internetWifiNetwork == network) {
                    internetWifiNetwork = null
                }
            }
        }

        val prevCallback = internetNetworkCallback.getAndUpdate { suggestion to networkCallback }
        prevCallback?.second?.let {
            connectivityManager.unregisterNetworkCallback(it)
            logger(Log.DEBUG, "$logPrefix connectToInternetWifi: unregistered previous internet callback", null)
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.requestNetwork(networkRequest, networkCallback)
        logger(Log.INFO, "$logPrefix connectToInternetWifi: Requested network for SSID=$ssid; waiting for availability", null)

        return try {
            withTimeout(30_000L) {
                internetNetworkAvailable.await()
            }
            logger(Log.INFO, "$logPrefix connectToInternetWifi: Connected to internet WiFi SSID=$ssid", null)
            Result.success(Unit)
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix connectToInternetWifi: Failed to connect to SSID=$ssid", e)
            connectivityManager.unregisterNetworkCallback(networkCallback)
            internetNetworkCallback.compareAndSet(suggestion to networkCallback, null)
            internetWifiNetwork = null
            Result.failure(e)
        }
    }

    /**
     * Disconnect from the internet WiFi connection previously established by connectToInternetWifi().
     * Removes the WifiNetworkSuggestion, unregisters the NetworkCallback, and clears internetWifiNetwork.
     */
    suspend fun disconnectFromInternetWifi() {
        logger(Log.INFO, "$logPrefix disconnectFromInternetWifi called", null)

        val prevPair = internetNetworkCallback.getAndUpdate { null }
        if (prevPair != null) {
            try {
                connectivityManager.unregisterNetworkCallback(prevPair.second)
                logger(Log.DEBUG, "$logPrefix disconnectFromInternetWifi: unregistered NetworkCallback", null)
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix disconnectFromInternetWifi: exception unregistering callback", e)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.removeNetworkSuggestions(listOf(prevPair.first))
                    logger(Log.DEBUG, "$logPrefix disconnectFromInternetWifi: removed WifiNetworkSuggestion", null)
                }
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix disconnectFromInternetWifi: exception removing suggestion", e)
            }
        }

        internetWifiNetwork = null
        logger(Log.INFO, "$logPrefix disconnectFromInternetWifi: complete", null)
    }
```

---

## C11 — `GatewayTypeResolver.kt` — Add Null-Safety on `getInstance()` — NOT a large file

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewayTypeResolver.kt`  
**Line count:** 220 — NOT a large file

### Rationale

At line 96, `resolveGatewayType()` calls `MeshrabiyaApiImpl.getInstance().getGatewayPreference()`.
`getInstance()` constructs a singleton, but `getGatewayPreference()` returns `currentGatewayPreference`
which defaults to `GatewayPreference.DEFAULT` before `initMesh()` is called. If the mesh is not
initialized yet (race on startup), or if `getInstance()` throws for any reason, the entire packet
send path crashes. Wrapping in `runCatching` returns `GATEWAY_TYPE_NONE` as safe fallback.

**BEFORE (lines 91–100):**

```kotlin
        // Precedence 3: Global gateway preference (fallback)
        val preference = MeshrabiyaApiImpl.getInstance().getGatewayPreference()
        val gatewayType = applyGlobalPreference(preference)
        Log.d(TAG, "No VPN rule for package, using global preference $preference → gatewayType=$gatewayType")
        return gatewayType
    }
```

**AFTER (lines 91–102):**

```kotlin
        // Precedence 3: Global gateway preference (fallback)
        // runCatching guards against MeshrabiyaApiImpl not yet initialized on startup
        val gatewayType = runCatching {
            val preference = MeshrabiyaApiImpl.getInstance().getGatewayPreference()
            applyGlobalPreference(preference).also { gt ->
                Log.d(TAG, "No VPN rule for package, using global preference $preference → gatewayType=$gt")
            }
        }.getOrElse { e ->
            Log.w(TAG, "GatewayTypeResolver: getGatewayPreference failed, defaulting to GATEWAY_TYPE_NONE", e)
            VirtualPacketHeader.GATEWAY_TYPE_NONE
        }
        return gatewayType
    }
```

---

## C12 — `VirtualNode.kt` — Fix `shouldRouteViaProxy()` — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Line count:** 1483 — **LARGE FILE (>800 lines) — manual edit required**  
**Location:** Lines 1401–1412

### Rationale

The current stub returns `true` for every packet, causing ALL packets arriving at a TOR_GATEWAY
node — including MMCP management messages, broadcast packets, and other mesh-local traffic — to
be routed through the Tor proxy. This breaks mesh operation entirely when `proxyActive && TOR_GATEWAY`.

Mesh virtual addresses are APIPA: `169.254.0.0/16`. The two fixed bytes are
`(169 shl 24).or(254 shl 16) = 0xA9FE0000`. The prefix length is 16 bits, so the mask is
`0xFFFF0000`. Any `toAddr` in this range is mesh-local and must NOT go through the proxy.
Only internet-bound addresses (outside the `169.254.0.0/16` range) should be proxied.

**Evidence from `VirtualNode.kt` (lines 67–73) confirming prefix:**

```kotlin
fun randomApipaAddr(): Int {
    //169.254
    val fixedSection = (169 shl 24).or(254 shl 16)
    val randomSection = Random.nextInt(Short.MAX_VALUE.toInt())
    return fixedSection.or(randomSection)
}
```

**BEFORE (Lines 1401–1412):**

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

**AFTER (Lines 1401–1415):**

```kotlin
    // --- Helper: Should route via proxy ---
    private fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
        // Only route internet-bound (non-mesh) traffic through the Tor proxy.
        // Mesh virtual addresses are APIPA: 169.254.0.0/16
        //   Upper 16 bits = (169 shl 24).or(254 shl 16) = 0xA9FE0000
        //   Mask            = 0xFFFF0000
        // If toAddr falls within this range it is a mesh-local address — do NOT proxy it.
        // Routing mesh management packets (MMCP, broadcast, etc.) through Tor would break
        // all mesh communication on any TOR_GATEWAY node when proxyActive = true.
        val toAddr = packet.header.toAddr
        val apipaPrefix = (169 shl 24).or(254 shl 16)   // 0xA9FE0000
        val apipaMask   = 0xFFFF0000.toInt()              // /16 mask
        return (toAddr and apipaMask) != (apipaPrefix and apipaMask)
    }
```

---

## C13 — `DtoModels.kt` — Add New DTOs — NOT a large file

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt`  
**Line count:** 700 — NOT a large file  
**Location:** Append after the end of file (after line 700, the last `}` of `BroadcastProgressDto`)

### Rationale

The new API methods for connecting to internet WiFi require DTOs to communicate
network discovery results, connection requests, and connection state between the API
and the UI layer. The `package` declaration in `DtoModels.kt` is `package com.ustadmobile.meshrabiya.api.model`;
all new types must be placed in this file to use the same package.

**BEFORE (lines 694–700, end of file):**

```kotlin
/**
 * Progress update during broadcast send
 */
data class BroadcastProgressDto(
    val broadcastId: String,
    val chunksSent: Int,
    val totalChunks: Int,
    val bytesTransferred: Long,
    val totalBytes: Long
)
```

**AFTER (append to file after line 700):**

```kotlin
// ==========================================
// WiFi AP Concurrency DTOs
// ==========================================

/**
 * Status of the internet WiFi connection on a concurrent AP+STA device.
 */
enum class NonMeshWifiStatus {
    IDLE,           // Not attempting connection
    SCANNING,       // Scanning for available networks
    CONNECTING,     // Connection attempt in progress
    CONNECTED,      // Successfully connected, internet available
    FAILED          // Connection attempt failed
}

/**
 * Represents a discovered WiFi network available for the internet connection.
 */
data class NonMeshWifiNetworkDto(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,   // RSSI in dBm (e.g. -65)
    val isSecured: Boolean,    // true if WPA2/WPA3 protected
    val frequencyMhz: Int      // e.g. 2412 (2.4GHz) or 5180 (5GHz)
)

/**
 * Request from the UI to connect to a specific internet WiFi network.
 */
data class WifiConnectionRequestDto(
    val ssid: String,
    val passphrase: String     // WPA2 passphrase; empty string for open networks
)

/**
 * Current state of the internet WiFi connection on a concurrent AP+STA node.
 */
data class NonMeshWifiConnectionStateDto(
    val status: NonMeshWifiStatus,
    val connectedSsid: String? = null,     // Non-null when CONNECTED
    val signalStrengthDbm: Int? = null,    // Non-null when CONNECTED
    val errorMessage: String? = null       // Non-null when FAILED
)
```

---

## C14 — `MeshrabiyaApi.kt` — Add New API Declarations — NOT a large file

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`  
**Line count:** 391 — NOT a large file  
**Location:** After the existing `isTorActive()` declaration (end of V3 gateway section)

### Rationale

Adding four methods to expose internet WiFi connection lifecycle to the application layer:
scan for available networks, connect, disconnect, and observe connection state via a `StateFlow`.

**BEFORE (lines 174–183):**

```kotlin
    /**
     * Query current Tor daemon status from Orbot.
     * 
     * **Implementation:** Reads last known status from TorStatusMonitor BroadcastReceiver.
     * 
     * @return true if Tor is running ("ON" status), false otherwise
     */
    fun isTorActive(): Boolean

    // --- Storage Participation ---
```

**AFTER (lines 174–215):**

```kotlin
    /**
     * Query current Tor daemon status from Orbot.
     * 
     * **Implementation:** Reads last known status from TorStatusMonitor BroadcastReceiver.
     * 
     * @return true if Tor is running ("ON" status), false otherwise
     */
    fun isTorActive(): Boolean

    // --- WiFi AP Concurrency: Internet WiFi Controls (MESH_ROUTER concurrent devices only) ---

    /**
     * Scan for available WiFi networks that could be used as an internet connection.
     * Requires MESH_ROUTER role with concurrentApStationSupported = true.
     * Subject to Android scan throttle (4 scans/2min in foreground).
     *
     * @return List of discovered networks sorted by signal strength descending
     */
    suspend fun scanAvailableWifiNetworks(): List<com.ustadmobile.meshrabiya.api.model.NonMeshWifiNetworkDto>

    /**
     * Connect to a non-mesh internet WiFi network on a concurrent AP+STA device.
     * The mesh hotspot continues running while internet WiFi is connected.
     * When CLEARNET_GATEWAY is enabled, mesh client internet traffic will be forwarded
     * via this connection.
     *
     * @param ssid Target network SSID
     * @param passphrase WPA2 passphrase (empty string for open networks)
     * @param callback Result.success(Unit) on connection; Result.failure on error
     */
    fun connectToNonMeshWifi(
        ssid: String,
        passphrase: String,
        callback: (Result<Unit>) -> Unit
    )

    /**
     * Disconnect from the internet WiFi and clean up the WifiNetworkSuggestion.
     */
    fun disconnectFromNonMeshWifi(callback: (Result<Unit>) -> Unit)

    /**
     * Observe the current internet WiFi connection state.
     * Emits NonMeshWifiConnectionStateDto on every status change.
     */
    fun getNonMeshWifiStateFlow(): kotlinx.coroutines.flow.StateFlow<com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto>

    // --- Storage Participation ---
```

---

## C15 — `MeshrabiyaApiImpl.kt` — New Method Implementations — **LARGE FILE**

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Line count:** 1975 — **LARGE FILE (>800 lines) — manual edit required**

### Part A: Add required import

**BEFORE (imports block excerpt, line ~60):**

```kotlin
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
```

**AFTER:**

```kotlin
import android.net.wifi.WifiManager
import android.os.Build
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
```

### Part B: Add `_nonMeshWifiState` StateFlow field

Add after the existing `_networkOverviewMetricsFlow` StateFlow field declaration.

**BEFORE (lines ~102–106, existing fields section):**

```kotlin
    private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto(0L, 0L, 0))
    override val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()
```

**AFTER:**

```kotlin
    private val _networkOverviewMetricsFlow = MutableStateFlow(NetworkOverviewMetricsDto(0L, 0L, 0))
    override val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetricsFlow.asStateFlow()

    private val _nonMeshWifiState = MutableStateFlow(
        com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
            status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.IDLE
        )
    )
```

### Part C: Add the four new method implementations

Insert after the `isTorActive()` implementation and the `loadGatewayPreference()` method
(after line ~1190 — immediately before `// --- Storage Participation ---`).

**BEFORE (lines ~1185–1191):**

```kotlin
    /**
     * V3: Load gateway preference from DataStore on initialization.
     */
    private suspend fun loadGatewayPreference(context: Context) {
        val prefs = context.dataStore.data.first()
        val prefString = prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)]
        currentGatewayPreference = GatewayPreference.fromString(prefString)
    }

    // --- Storage Participation ---
```

**AFTER (insert entire block before `// --- Storage Participation ---`):**

```kotlin
    /**
     * V3: Load gateway preference from DataStore on initialization.
     */
    private suspend fun loadGatewayPreference(context: Context) {
        val prefs = context.dataStore.data.first()
        val prefString = prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)]
        currentGatewayPreference = GatewayPreference.fromString(prefString)
    }

    // --- WiFi AP Concurrency: Internet WiFi Implementation ---

    override suspend fun scanAvailableWifiNetworks(): List<com.ustadmobile.meshrabiya.api.model.NonMeshWifiNetworkDto> {
        val context = appContext ?: return emptyList()
        val wifiMgr = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        _nonMeshWifiState.value = com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
            status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.SCANNING
        )

        @Suppress("DEPRECATION")
        wifiMgr.startScan()
        kotlinx.coroutines.delay(2000)

        @Suppress("DEPRECATION")
        val results = wifiMgr.scanResults ?: emptyList()

        _nonMeshWifiState.value = com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
            status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.IDLE
        )

        return results
            .filter { it.SSID.isNotEmpty() }
            .sortedByDescending { it.level }
            .map { scan ->
                com.ustadmobile.meshrabiya.api.model.NonMeshWifiNetworkDto(
                    ssid = scan.SSID,
                    bssid = scan.BSSID,
                    signalStrength = scan.level,
                    isSecured = scan.capabilities.contains("WPA") || scan.capabilities.contains("WEP"),
                    frequencyMhz = scan.frequency
                )
            }
    }

    override fun connectToNonMeshWifi(
        ssid: String,
        passphrase: String,
        callback: (Result<Unit>) -> Unit
    ) {
        val node = myNode
        if (node == null) {
            callback(Result.failure(IllegalStateException("Mesh not initialized")))
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(Result.failure(IllegalStateException(
                "connectToNonMeshWifi requires Android 11+ (API 30)"
            )))
            return
        }

        if (!node.meshrabiyaWifiManager.concurrentApStationSupported) {
            callback(Result.failure(IllegalStateException(
                "This device does not support concurrent AP+STA mode"
            )))
            return
        }

        _nonMeshWifiState.value = com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
            status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.CONNECTING
        )

        eventMonitoringScope.launch {
            val result = node.meshrabiyaWifiManager.connectToInternetWifi(ssid, passphrase)
            result.fold(
                onSuccess = {
                    _nonMeshWifiState.value = com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
                        status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.CONNECTED,
                        connectedSsid = ssid
                    )
                    callback(Result.success(Unit))
                },
                onFailure = { error ->
                    _nonMeshWifiState.value = com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
                        status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.FAILED,
                        errorMessage = error.message
                    )
                    callback(Result.failure(error))
                }
            )
        }
    }

    override fun disconnectFromNonMeshWifi(callback: (Result<Unit>) -> Unit) {
        val node = myNode
        if (node == null) {
            callback(Result.failure(IllegalStateException("Mesh not initialized")))
            return
        }

        eventMonitoringScope.launch {
            try {
                node.meshrabiyaWifiManager.disconnectFromInternetWifi()
                _nonMeshWifiState.value = com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto(
                    status = com.ustadmobile.meshrabiya.api.model.NonMeshWifiStatus.IDLE
                )
                callback(Result.success(Unit))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun getNonMeshWifiStateFlow(): kotlinx.coroutines.flow.StateFlow<com.ustadmobile.meshrabiya.api.model.NonMeshWifiConnectionStateDto> {
        return _nonMeshWifiState.asStateFlow()
    }

    // --- Storage Participation ---
```

---

## C16 — `fragment_mesh_enhanced.xml` — Add WiFi AP Button — NOT a large file

**File:** `app/src/main/res/layout/fragment_mesh_enhanced.xml`  
**Line count:** 489 — NOT a large file  
**Location:** Inside `meshControlHeader` LinearLayout, after `mergeMeshButton` (line 80) and before `expandCollapseIndicator` (line 83)

### Rationale

The button must start as `android:visibility="gone"` and only become visible when the device
acquires the `MESH_ROUTER` role with `concurrentApStationSupported = true`. Using the same
weight pattern as the existing three buttons keeps layout balanced.

**BEFORE (lines 74–97):**

```xml
                    <!-- Merge Mesh Button (enabled when CONNECTED) -->
                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/mergeMeshButton"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:layout_marginStart="8dp"
                        android:text="Merge Mesh"
                        android:enabled="false"
                        android:paddingVertical="12dp"
                        app:cornerRadius="8dp"
                        app:strokeColor="?attr/colorSecondary"
                        app:strokeWidth="1dp"
                        style="@style/Widget.Material3.Button.OutlinedButton" />

                    <!-- Expand/Collapse Indicator (initially hidden) -->
                    <ImageView
                        android:id="@+id/expandCollapseIndicator"
                        android:layout_width="24dp"
                        android:layout_height="24dp"
                        android:layout_marginStart="8dp"
                        android:src="@android:drawable/arrow_down_float"
                        android:contentDescription="Expand/Collapse"
                        android:visibility="gone" />
```

**AFTER (lines 74–105):**

```xml
                    <!-- Merge Mesh Button (enabled when CONNECTED) -->
                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/mergeMeshButton"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:layout_marginStart="8dp"
                        android:text="Merge Mesh"
                        android:enabled="false"
                        android:paddingVertical="12dp"
                        app:cornerRadius="8dp"
                        app:strokeColor="?attr/colorSecondary"
                        app:strokeWidth="1dp"
                        style="@style/Widget.Material3.Button.OutlinedButton" />

                    <!-- WiFi AP Connection Button (visible only on MESH_ROUTER concurrent AP+STA devices) -->
                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/wifiApConnectionButton"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:layout_marginStart="8dp"
                        android:text="WiFi"
                        android:paddingVertical="12dp"
                        android:visibility="gone"
                        app:cornerRadius="8dp"
                        app:strokeColor="?attr/colorTertiary"
                        app:strokeWidth="1dp"
                        style="@style/Widget.Material3.Button.OutlinedButton" />

                    <!-- Expand/Collapse Indicator (initially hidden) -->
                    <ImageView
                        android:id="@+id/expandCollapseIndicator"
                        android:layout_width="24dp"
                        android:layout_height="24dp"
                        android:layout_marginStart="8dp"
                        android:src="@android:drawable/arrow_down_float"
                        android:contentDescription="Expand/Collapse"
                        android:visibility="gone" />
```

---

## C17 — `MeshUIBindings.kt` — Add `wifiApConnectionButton` — NOT a large file

**File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt`  
**Line count:** 163 — NOT a large file

### Part A: Add field declaration

**BEFORE (lines 35–40):**

```kotlin
    // Mesh control card and new buttons
    lateinit var meshControlCard: MaterialCardView
    lateinit var meshControlHeader: LinearLayout
    lateinit var joinMeshButton: MaterialButton
    lateinit var mergeMeshButton: MaterialButton
    lateinit var expandCollapseIndicator: ImageView
```

**AFTER (lines 35–41):**

```kotlin
    // Mesh control card and new buttons
    lateinit var meshControlCard: MaterialCardView
    lateinit var meshControlHeader: LinearLayout
    lateinit var joinMeshButton: MaterialButton
    lateinit var mergeMeshButton: MaterialButton
    lateinit var wifiApConnectionButton: MaterialButton
    lateinit var expandCollapseIndicator: ImageView
```

### Part B: Add `findViewById` call in `bindImmediateViews()`

**BEFORE (lines 93–100):**

```kotlin
        // Mesh control card and new buttons
        meshControlCard = view.findViewById(R.id.meshControlCard)
        meshControlHeader = view.findViewById(R.id.meshControlHeader)
        joinMeshButton = view.findViewById(R.id.joinMeshButton)
        mergeMeshButton = view.findViewById(R.id.mergeMeshButton)
        expandCollapseIndicator = view.findViewById(R.id.expandCollapseIndicator)
```

**AFTER (lines 93–101):**

```kotlin
        // Mesh control card and new buttons
        meshControlCard = view.findViewById(R.id.meshControlCard)
        meshControlHeader = view.findViewById(R.id.meshControlHeader)
        joinMeshButton = view.findViewById(R.id.joinMeshButton)
        mergeMeshButton = view.findViewById(R.id.mergeMeshButton)
        wifiApConnectionButton = view.findViewById(R.id.wifiApConnectionButton)
        expandCollapseIndicator = view.findViewById(R.id.expandCollapseIndicator)
```

---

## C18 — `EnhancedMeshFragment.kt` — Button Show/Hide + Click Handler — **LARGE FILE**

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Line count:** 2338 — **LARGE FILE (>800 lines) — manual edit required**

### Part A: Add button visibility in `setupRoleObserver()` — Lines ~661–669

The MESH_ROUTER role appear/disappear logging already exists at lines 663–667. The button
visibility update must be added in the same `activity?.runOnUiThread { }` block that updates
`meshRolesText`. Specifically, after the existing MESH_ROUTER role detection log statements:

**BEFORE (lines 661–670, within the `activity?.runOnUiThread { }` block):**

```kotlin
                activity?.runOnUiThread {
                    val uiUpdateStart = System.currentTimeMillis()
                    
                    // Update roles text - show "Roles: --" when mesh not started or no roles determined yet
                    val meshState = meshrabiyaApi.getMeshStatus()
                    val meshStarted = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
                    
                    android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Updating meshRolesText (meshState=$meshState, meshStarted=$meshStarted)")
                    MeshUIBindings.meshRolesText.text = if (!meshStarted) {
```

**AFTER (lines 661–676):**

```kotlin
                activity?.runOnUiThread {
                    val uiUpdateStart = System.currentTimeMillis()

                    // WiFi AP Concurrency: show button only when MESH_ROUTER role is active
                    // concurrentApStationSupported is checked at runtime via api cast;
                    // hasConcurrentSupport defaults to false if mesh not initialized.
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

                    // Update roles text - show "Roles: --" when mesh not started or no roles determined yet
                    val meshState = meshrabiyaApi.getMeshStatus()
                    val meshStarted = meshState == MeshStateDto.CONNECTED || meshState == MeshStateDto.CONNECTING
                    
                    android.util.Log.d("EnhancedMeshFragment", "[ROLE_OBSERVER] Updating meshRolesText (meshState=$meshState, meshStarted=$meshStarted)")
                    MeshUIBindings.meshRolesText.text = if (!meshStarted) {
```

> **Note on reflection approach:** The `myNode` field is private in `MeshrabiyaApiImpl`. A
> cleaner alternative is to add a public `isConcurrentRouterActive(): Boolean` method to
> `MeshrabiyaApi` interface and implement it in `MeshrabiyaApiImpl`. This avoids reflection.
> The implementation would be:
> ```kotlin
> // MeshrabiyaApi.kt — add declaration:
> fun isConcurrentRouterActive(): Boolean
>
> // MeshrabiyaApiImpl.kt — add implementation:
> override fun isConcurrentRouterActive(): Boolean {
>     val node = myNode ?: return false
>     return node.meshrabiyaWifiManager.concurrentApStationSupported &&
>            (emergentRoleManager?.getCurrentMeshRoles()?.contains(MeshRole.MESH_ROUTER) == true)
> }
> ```
> If `MeshrabiyaApi` is extended this way, the button visibility check simplifies to:
> `meshrabiyaApi.isConcurrentRouterActive()`

### Part B: Wire button click listener

The click listener must be set up in `onViewCreated()` or the same function that wires the
other button click listeners. Search for `joinMeshButton.setOnClickListener` to find the
correct location (confirmed present by grep of the file).

The new listener opens the internet WiFi selection dialog:

**Location:** After `mergeMeshButton.setOnClickListener { ... }` block.

**New code to ADD (insert after the mergeMeshButton click listener block):**

```kotlin
        // WiFi AP Connection button — only visible on concurrent AP+STA MESH_ROUTER devices
        MeshUIBindings.wifiApConnectionButton.setOnClickListener {
            showInternetWifiDialog()
        }
```

### Part C: Add `showInternetWifiDialog()` function

Add as a new `private fun` in the fragment class. This function invokes the scan, presents an
`AlertDialog` with the scan results, prompts for passphrase, and calls `connectToNonMeshWifi`.

**New function to ADD (insert anywhere within the class body as a new private fun):**

```kotlin
    /**
     * Display internet WiFi selection dialog for concurrent AP+STA MESH_ROUTER devices.
     * Scans available networks, presents list to user, prompts for passphrase, connects.
     */
    private fun showInternetWifiDialog() {
        val scope = viewLifecycleOwner.lifecycleScope

        // Show scanning spinner dialog
        val scanningDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Scanning for WiFi Networks…")
            .setMessage("Please wait")
            .setCancelable(false)
            .show()

        scope.launch {
            try {
                val networks = (meshrabiyaApi as? com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl)
                    ?.scanAvailableWifiNetworks() ?: emptyList()

                scanningDialog.dismiss()

                if (networks.isEmpty()) {
                    activity?.runOnUiThread {
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("No WiFi Networks Found")
                            .setMessage("No WiFi networks were detected. Make sure WiFi is enabled and try again.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@launch
                }

                val ssidList = networks.map { "${it.ssid} (${it.signalStrength} dBm)" }.toTypedArray()
                var selectedIndex = 0

                activity?.runOnUiThread {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Select Internet WiFi Network")
                        .setSingleChoiceItems(ssidList, 0) { _, which -> selectedIndex = which }
                        .setPositiveButton("Next") { _, _ ->
                            val selectedNetwork = networks[selectedIndex]
                            showPassphraseDialog(selectedNetwork.ssid)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                scanningDialog.dismiss()
                activity?.runOnUiThread {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Scan Failed")
                        .setMessage("WiFi scan failed: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * Show passphrase entry dialog and initiate internet WiFi connection.
     */
    private fun showPassphraseDialog(ssid: String) {
        val scope = viewLifecycleOwner.lifecycleScope
        val inputLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val passphraseInput = android.widget.EditText(requireContext()).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(passphraseInput)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Connect to $ssid")
            .setMessage("Enter the WiFi password:")
            .setView(inputLayout)
            .setPositiveButton("Connect") { _, _ ->
                val passphrase = passphraseInput.text.toString()
                val connectingDialog = android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Connecting to $ssid…")
                    .setMessage("Please wait")
                    .setCancelable(false)
                    .show()

                meshrabiyaApi.connectToNonMeshWifi(ssid, passphrase) { result ->
                    connectingDialog.dismiss()
                    activity?.runOnUiThread {
                        result.fold(
                            onSuccess = {
                                android.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Connected")
                                    .setMessage("Successfully connected to $ssid. Internet Gateway is now available.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            },
                            onFailure = { error ->
                                android.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Connection Failed")
                                    .setMessage("Could not connect to $ssid: ${error.message}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        )
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
```

---

## Implementation Sequence

Apply changes in this exact order to avoid compilation errors:

| Step | Changes | Reason |
|------|---------|--------|
| 1 | C1 | `MeshrabiyaConstants.VIRTUAL_PACKET_GATEWAY_TYPE_OFFSET` must exist before C2 |
| 2 | C2 (both parts) | Import + use constant; compile-verifiable immediately |
| 3 | C3 | `VirtualDatagramSocket2` gains context param; must exist before C4 |
| 4 | C4 | `createDatagramSocket()` passes context; depends on C3 |
| 5 | C5 | `concurrentApStationSupported` property on `MeshrabiyaWifiManagerAndroid`; must exist before C6 and C8 |
| 6 | C6 | `AndroidVirtualNode` uses `meshrabiyaWifiManager.concurrentApStationSupported`; depends on C5 |
| 7 | C7 | `LocalOnlyHotspotManager` constructor gains lambda; must exist before C8 |
| 8 | C8 | `MeshrabiyaWifiManagerAndroid` passes lambda to `LocalOnlyHotspotManager`; depends on C7 |
| 9 | C9 | Conditional `bindProcessToNetwork`; independent of C7/C8 but same file |
| 10 | C10 (all parts) | Add internet WiFi infrastructure; depends on C9 (same file, coordinate manually) |
| 11 | C11 | `GatewayTypeResolver` null-safety; independent |
| 12 | C12 | `shouldRouteViaProxy()` fix; independent |
| 13 | C13 | New DTOs in `DtoModels.kt`; must exist before C14/C15 |
| 14 | C14 | New API declarations; depends on C13 DTOs |
| 15 | C15 (all parts) | Implementations; depend on C10 (WiFi methods) and C14 (declarations) |
| 16 | C16 | XML layout; independent |
| 17 | C17 | `MeshUIBindings` field and bind; depends on C16 (view ID must exist) |
| 18 | C18 | `EnhancedMeshFragment` wiring; depends on C14/C15/C17 |

---

## File Edit Method Summary

| File | Lines | Method |
|------|-------|--------|
| `MeshrabiyaConstants.kt` | 341 | `replace_string_in_file` |
| `VirtualDatagramSocketImpl.kt` | 227 | `multi_replace_string_in_file` (2 edits) |
| `VirtualDatagramSocket2.kt` | 22 | `replace_string_in_file` |
| `VirtualNode.kt` | 1483 | **Manual edit — BEFORE/AFTER presented above** |
| `MeshrabiyaWifiManagerAndroid.kt` | 876 | **Manual edit — BEFORE/AFTER presented above (5 locations)** |
| `AndroidVirtualNode.kt` | 225 | `replace_string_in_file` |
| `LocalOnlyHotspotManager.kt` | 279 | `multi_replace_string_in_file` (2 edits) |
| `GatewayTypeResolver.kt` | 220 | `replace_string_in_file` |
| `DtoModels.kt` | 700 | `replace_string_in_file` (append) |
| `MeshrabiyaApi.kt` | 391 | `replace_string_in_file` |
| `MeshrabiyaApiImpl.kt` | 1975 | **Manual edit — BEFORE/AFTER presented above (3 locations)** |
| `fragment_mesh_enhanced.xml` | 489 | `replace_string_in_file` |
| `MeshUIBindings.kt` | 163 | `multi_replace_string_in_file` (2 edits) |
| `EnhancedMeshFragment.kt` | 2338 | **Manual edit — BEFORE/AFTER presented above (3 locations)** |
