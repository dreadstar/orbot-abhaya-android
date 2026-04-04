# GATEWAY ROUTING REFACTOR PLAN
**Date:** 2026  
**Status:** Verified against codebase on disk  
**Scope:** Complete gateway routing implementation — EmergentRoleManager internet-awareness, ClearnetGatewayForwarder implementation, TorGatewayForwarder (new), Orbot SOCKS5 integration, gateway failure/GATEWAY-DOWN, gateway selection improvements, Mesh Proxy Apps feature

---

## 1. Architecture Overview — Current State vs Target

### Current State (~70% complete per code archaeology)

| Component | File | Status | Notes |
|-----------|------|--------|-------|
| EmergentRoleManager role assignment | `vnet/EmergentRoleManager.kt` | ✅ Working | BUT: no internet-access check (critical gap) |
| Gateway type constants | `vnet/VirtualPacketHeader.kt` | ✅ Present | `GATEWAY_TYPE_TOR`, `GATEWAY_TYPE_CLEARNET`, `GATEWAY_TYPE_NONE` |
| `routeViaGateway()` | `vnet/VirtualNode.kt:994` | ✅ Implemented | Gateway selection + topology forwarding works |
| `selectBestGateway()` | `vnet/VirtualNode.kt:1074` | ✅ Implemented | Uses `calculateGatewaySuitability()` — 30% centrality + 40% fitness + 30% latency |
| `handleNoGatewayAvailable()` | `vnet/VirtualNode.kt:1184` | ✅ Implemented | EITHER preference: tries alternate gateway type |
| `filterViaGateway()` return path | `vnet/OriginatingMessageManager.kt:827` | ✅ Implemented | `trackGatewayMessage()` infrastructure in place |
| `onClearnetGatewayPacket()` | `vnet/VirtualNode.kt:1419` | ⚠️ Stub | Returns `false` — no dispatch to forwarder |
| `ClearnetGatewayForwarder.forward()` | `vnet/ClearnetGatewayForwarder.kt` | ❌ TODO stub | All logic is TODO comments |
| `TorGatewayForwarder` | (does not exist) | ❌ Missing | File does not exist anywhere in codebase |
| Gateway announcement MMCP | `vnet/EmergentRoleManager.kt:1119` | ✅ Present | `MmcpGatewayAnnouncement` broadcast on role assignment |
| Topology role propagation | `vnet/OriginatingMessageManager.kt` | ✅ Working | `meshRoles` in `NodeTopologyInfo` updates via originator messages |
| Signal strength capture | `vnet/wifi/MeshrabiyaWifiManagerAndroid.kt:197-198` | ✅ Captured in logs | `WifiInfo.linkSpeed` (Mbps) + `WifiInfo.rssi` (dBm) in log only |
| Gateway selection suitability score | `vnet/NodeTopologyInfo.kt:59` | ✅ Implemented | 30% centrality + 40% fitness + 30% latency normalization |
| Per-app routing (Mesh Proxy Apps) | Fragment, API | ❌ Not implemented | No per-app routing preference in current codebase |
| GATEWAY-DOWN explicit message | Anywhere | ❌ Not implemented | Staleness timeout only (30s implicit) |

### Critical Gaps (Priority Order)

1. **EmergentRoleManager assigns gateway roles without internet access check** — a node with no non-mesh WiFi can be assigned `CLEARNET_GATEWAY` or `TOR_GATEWAY` (fixes gateway role correctness)
2. **`ClearnetGatewayForwarder.forward()` is a complete stub** — packets reaching a clearnet gateway node are silently dropped
3. **`TorGatewayForwarder` doesn't exist** — Tor-routed packets reaching a gateway node dispatch to `routeViaProxy()` which uses `proxyHost:proxyPort` set by Orbot VPN but the actual SOCKS5 binding to Orbot's local port needs to be wired
4. **`onClearnetGatewayPacket()` in `AndroidVirtualNode` is never overridden** — must override to dispatch to `ClearnetGatewayForwarder`
5. **GATEWAY-DOWN explicit message** — 30s timeout is too slow for failover; need an explicit MMCP message for immediate detection
6. **Signal/bitrate not in gateway selection** — currently only logged, not fed into `calculateGatewaySuitability()`
7. **Mesh Proxy Apps** — per-app gateway routing preference UI does not exist

---

## 2. EmergentRoleManager Internet-Awareness Fix

### Problem

`calculateTargetRoles()` at `EmergentRoleManager.kt:377`:
```kotlin
if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
    // assigns TOR_GATEWAY + CLEARNET_GATEWAY based on user preference
}
```

`NodeCapabilitySnapshot.hasStableConnection()` at line 66:
```kotlin
fun hasStableConnection(): Boolean = networkQuality > 0.7f && stability > 0.6f
```

This checks mesh connectivity quality only — it does NOT verify that the device has non-mesh WiFi internet access. A device connected to the mesh with good mesh quality but no internet can be erroneously assigned both gateway roles.

### Why This Causes Real Failures

When gateway role is assigned without internet access:
1. The node broadcasts `MmcpGatewayAnnouncement` advertising `TOR_GATEWAY`/`CLEARNET_GATEWAY`
2. Other mesh nodes route internet-bound packets to this node via `routeViaGateway()`
3. `ClearnetGatewayForwarder.forward()` is called but has no `internetWifiNetwork` → packets drop silently
4. `TorGatewayForwarder` (once implemented) would fail to open SOCKS5 socket → packets drop

### Fix Plan

#### 2A. Add `hasNonMeshInternetAccess: Boolean` to `NodeCapabilitySnapshot`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

**Line:** ~64 (`data class NodeCapabilitySnapshot`)

**BEFORE:**
```kotlin
data class NodeCapabilitySnapshot(
    val nodeId: String,
    val resources: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float,
    val stability: Float,
    val timestamp: Long = System.currentTimeMillis()
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
    val networkQuality: Float,
    val stability: Float,
    val timestamp: Long = System.currentTimeMillis(),
    /** True if device is connected to non-mesh WiFi with validated internet access. */
    val hasNonMeshInternetAccess: Boolean = false,
) {
    fun hasStableConnection(): Boolean = networkQuality > 0.7f && stability > 0.6f
```

#### 2B. Update gateway role assignment to require internet access

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

**Line:** ~377 (inside `calculateTargetRoles()`)

**BEFORE:**
```kotlin
        if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
            // ... assigns TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
```

**AFTER:**
```kotlin
        if (node.hasStableConnection() && node.hasNonMeshInternetAccess && fitness > 0.8 && mesh.needsMoreGateways) {
            // ... assigns TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
```

**Rationale:** `hasNonMeshInternetAccess` is the canary — if false, assigning any internet gateway role is wrong.

#### 2C. Populate `hasNonMeshInternetAccess` when building the snapshot

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

Find where `NodeCapabilitySnapshot(...)` is constructed (search for `NodeCapabilitySnapshot(`). Add the `hasNonMeshInternetAccess` parameter sourced from `meshrabiyaWifiManager.internetWifiNetworkStateFlow.value.hasInternetAccess`.

**Code to add at snapshot construction site:**
```kotlin
hasNonMeshInternetAccess = node.meshrabiyaWifiManager
    .internetWifiNetworkStateFlow.value.hasInternetAccess,
```

**Note:** `node` in `EmergentRoleManager` is the `VirtualNode`/`AndroidVirtualNode` reference, which exposes `meshrabiyaWifiManager: MeshrabiyaWifiManagerAndroid` (visible in `AndroidVirtualNode.kt` line 73).

---

## 3. ClearnetGatewayForwarder — Full Implementation

### Current State

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/ClearnetGatewayForwarder.kt`

`forward()` contains only TODO comments. The class signature is:
```kotlin
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
)
```

#### 3A. Constructor Update — Pass `VirtualNode` or Context for Packet Injection

The forwarder needs two additional dependencies:
1. `internetWifiNetwork: Network` — the Android `Network` object for the non-mesh WiFi, used to bind sockets outside the VPN
2. A callback to inject return packets back into the mesh virtual network

**BEFORE (full class):**
```kotlin
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun forward(packet: VirtualPacket, internetWifiNetwork: Network) {
        scope.launch {
            try {
                // TODO: Parse VirtualPacket IP payload...
                logger(Log.DEBUG, "$logPrefix ClearnetGatewayForwarder: forward() — implementation pending")
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix ClearnetGatewayForwarder: forward error: ${e.message}")
            }
        }
    }

    fun close() {
        logger(Log.INFO, "$logPrefix ClearnetGatewayForwarder: closed")
    }
}
```

**AFTER (full implementation):**
```kotlin
package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Forwards CLEARNET-routed VirtualPackets to the internet via the provided Network object.
 *
 * Lifecycle: create when internet WiFi connects; call close() when it disconnects.
 *
 * Packet format (VirtualPacket payload is a raw UDP or TCP IP payload):
 *   - toAddr in VirtualPacketHeader = destination IP (inet4, big-endian int)
 *   - toPort in VirtualPacketHeader = destination port
 *   - fromAddr in VirtualPacketHeader = original source IP (mesh address)
 *   - fromPort in VirtualPacketHeader = source port for return routing
 *   - payload = raw UDP application data (DNS, HTTP, etc.)
 *
 * Return path: response is wrapped in a VirtualPacket routed back to fromAddr:fromPort.
 */
class ClearnetGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
    /** Called when a response packet must be injected back into the mesh. */
    private val onResponsePacket: (VirtualPacket) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /**
     * Forward a CLEARNET-tagged VirtualPacket via the provided internet WiFi network.
     *
     * Only UDP is implemented (DNS, NTP, lightweight HTTP). TCP requires a connection-tracked
     * proxy (see TorGatewayForwarder for SOCKS5 pattern to adapt for TCP clearnet).
     */
    fun forward(packet: VirtualPacket, internetWifiNetwork: Network) {
        scope.launch {
            try {
                val header = packet.header

                // --- Destination from VirtualPacket header ---
                val destIpBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(header.toAddr)
                    .array()
                val destInetAddr = InetAddress.getByAddress(destIpBytes)
                val destPort = header.toPort.toInt() and 0xFFFF

                // --- Application payload (raw bytes after VirtualPacket header) ---
                val payloadSize = header.payloadSize
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset,
                    packet.payloadOffset + payloadSize
                )

                logger(Log.DEBUG,
                    "$logPrefix ClearnetGatewayForwarder.forward: " +
                    "dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                // --- Send UDP via internet WiFi network (bypasses VPN by using bound network) ---
                val socket = DatagramSocket()
                internetWifiNetwork.bindSocket(socket)
                socket.soTimeout = 5_000

                val sendPacket = DatagramPacket(payload, payload.size,
                    InetSocketAddress(destInetAddr, destPort))
                socket.send(sendPacket)

                // --- Receive response (for request-response protocols like DNS) ---
                val responseBuffer = ByteArray(65_535)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                try {
                    socket.receive(responsePacket)
                    val responseData = responsePacket.data.copyOf(responsePacket.length)

                    // --- Wrap response in VirtualPacket routed back to original sender ---
                    val returnHeader = VirtualPacketHeader(
                        toAddr = header.fromAddr,
                        toPort = header.fromPort,
                        fromAddr = header.toAddr,   // response "from" the internet destination
                        fromPort = destPort.toShort(),
                        lastHopAddr = 0,
                        hopCount = 0,
                        maxHops = header.maxHops,
                        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                        payloadSize = responseData.size,
                    )
                    val returnPacket = VirtualPacket.fromHeaderAndPayloadData(
                        header = returnHeader,
                        data = responseData,
                        payloadOffset = 0,
                    )
                    onResponsePacket(returnPacket)
                    logger(Log.DEBUG,
                        "$logPrefix ClearnetGatewayForwarder: response ${responsePacket.length} bytes " +
                        "→ mesh addr ${header.fromAddr}")
                } catch (e: java.net.SocketTimeoutException) {
                    logger(Log.WARN, "$logPrefix ClearnetGatewayForwarder: response timeout for $destInetAddr:$destPort")
                } finally {
                    socket.close()
                }
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix ClearnetGatewayForwarder: forward error: ${e.message}")
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix ClearnetGatewayForwarder: unexpected error: ${e.message}", e)
            }
        }
    }

    fun close() {
        job.cancel()
        logger(Log.INFO, "$logPrefix ClearnetGatewayForwarder: closed")
    }
}
```

**Key design decisions:**
- `internetWifiNetwork.bindSocket(socket)` routes the UDP datagram through the non-mesh WiFi interface, completely bypassing the Orbot VPN tunnel
- Only UDP is supported in the initial implementation (covers DNS, NTP, UDP-based QUIC/HTTP3)
- TCP requires a full connection-tracked proxy (see Section 4 for the SOCKS5 pattern used for Tor that can be adapted)
- Response is wrapped in a new `VirtualPacket` with `gatewayType = GATEWAY_TYPE_NONE` (it IS the response, not a new gateway request)
- 5-second socket timeout prevents blocking the IO dispatcher coroutine indefinitely

#### 3B. Update `AndroidVirtualNode` constructor to wire `onResponsePacket`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

**Line:** ~89 (`clearnetGatewayForwarder` instantiation)

**BEFORE:**
```kotlin
    private val clearnetGatewayForwarder: ClearnetGatewayForwarder = ClearnetGatewayForwarder(
        logger = logger,
        logPrefix = "ClearnetGateway",
    )
```

**AFTER:**
```kotlin
    private val clearnetGatewayForwarder: ClearnetGatewayForwarder = ClearnetGatewayForwarder(
        logger = logger,
        logPrefix = "ClearnetGateway",
        onResponsePacket = { packet -> route(packet, null, null) },
    )
```

Note: `route()` (or `processRoutePacket()`) is the ingress function in `VirtualNode` that handles incoming packets. Verify the exact public-facing signature in `VirtualNode.kt` — use `onIncomingPacket()`, `route()`, or the equivalent that accepts a `VirtualPacket` and routes it to the correct local socket.

#### 3C. Override `onClearnetGatewayPacket()` in `AndroidVirtualNode`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

Add after the `clearnetGatewayForwarder` property declaration:

```kotlin
    override fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean {
        val network = meshrabiyaWifiManager.internetWifiNetwork ?: run {
            logger(Log.WARN, "ClearnetGateway: received clearnet packet but no internet WiFi network bound")
            return false
        }
        clearnetGatewayForwarder.forward(packet, network)
        return true
    }
```

**Verified:** `VirtualNode.kt:1419` declares `protected open fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean = false`. This override is the correct dispatch point.

---

## 4. TorGatewayForwarder — New Implementation

### Overview

A `TorGatewayForwarder` connects to Orbot's local SOCKS5 proxy (default port 9050) to route TCP/UDP packets through the Tor network. When a mesh node has `TOR_GATEWAY` role, it must:
1. Accept CLEARNET-tagged packets from the mesh (via `routeViaProxy()` in `VirtualNode` or via a new `onTorGatewayPacket()` method)
2. Open a SOCKS5 connection to Orbot's local SOCKS5 proxy at `127.0.0.1:9050`
3. Complete SOCKS5 handshake with the packet's destination IP + port
4. Write packet payload, read response
5. Inject response back into the mesh

**Note on Orbot SOCKS5 port:** Orbot's SOCKS5 proxy is on `127.0.0.1:9050`. This is a local loopback connection — it does NOT go through the VPN interface. When the Orbot VPN is active, ALL traffic from the device is already Tor-routed so the SOCKS5 proxy is a second-hop. To avoid double-Tor-routing on the gateway node, the TorGatewayForwarder must use the `internetWifiNetwork.socketFactory` to open sockets, or use a dedicated loopback socket that bypasses the VPN.

**Critical insight:** On Android, connecting to `127.0.0.1:9050` via a normal socket goes to the loopback interface, not through the VPN (`tun0`). So a TorGatewayForwarder using a standard TCP socket to `127.0.0.1:9050` WILL work even when the VPN is active.

### New File: `TorGatewayForwarder.kt`

**File path:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/TorGatewayForwarder.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Forwards TOR-routed VirtualPackets to the Tor network via Orbot's SOCKS5 proxy.
 *
 * Orbot exposes a SOCKS5 proxy at 127.0.0.1:9050 (configurable via [orbotSocks5Port]).
 * Connections to 127.0.0.1 bypass the VPN interface on Android.
 *
 * Each forwarded packet creates a new TCP connection through SOCKS5. This is correct for
 * DNS-over-Tor and short-lived HTTP/HTTPS requests. For persistent connections, a
 * connection-pooling layer should be added in a future iteration.
 *
 * Lifecycle: instantiate when TOR_GATEWAY role is assigned; call close() when role is removed.
 */
class TorGatewayForwarder(
    private val logger: MNetLogger,
    private val logPrefix: String,
    /** Port of Orbot's SOCKS5 proxy. Default: 9050. */
    private val orbotSocks5Port: Int = 9050,
    /** Called when a response packet must be injected back into the mesh. */
    private val onResponsePacket: (VirtualPacket) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /**
     * Forward a TOR-tagged VirtualPacket via Orbot's SOCKS5 proxy to the destination.
     */
    fun forward(packet: VirtualPacket) {
        scope.launch {
            try {
                val header = packet.header

                // --- Destination from VirtualPacket header ---
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

                logger(Log.DEBUG,
                    "$logPrefix TorGatewayForwarder.forward: " +
                    "dst=${destInetAddr.hostAddress}:$destPort payloadSize=$payloadSize")

                // --- Open TCP socket to Orbot SOCKS5 proxy ---
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", orbotSocks5Port), 5_000)
                socket.soTimeout = 10_000

                val input: InputStream = socket.getInputStream()
                val output: OutputStream = socket.getOutputStream()

                // --- SOCKS5 handshake: no authentication ---
                // Step 1: Client greeting  [VER=5, NMETHODS=1, METHOD=0 (NO_AUTH)]
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                // Step 2: Server selection [VER=5, METHOD=0]
                val serverGreet = ByteArray(2)
                input.read(serverGreet)
                if (serverGreet[0] != 0x05.toByte() || serverGreet[1] != 0x00.toByte()) {
                    logger(Log.WARN, "$logPrefix SOCKS5 handshake failed: ${serverGreet.toHex()}")
                    socket.close()
                    return@launch
                }

                // --- SOCKS5 connect request: CONNECT to destination IP4 + port ---
                // [VER=5, CMD=1(CONNECT), RSV=0, ATYP=1(IPv4), ADDR(4b), PORT(2b)]
                val connectRequest = ByteBuffer.allocate(10)
                    .put(0x05)          // VER
                    .put(0x01)          // CMD = CONNECT
                    .put(0x00)          // RSV
                    .put(0x01)          // ATYP = IPv4
                    .put(destIpBytes)   // DST.ADDR (4 bytes, big-endian)
                    .putShort(destPort.toShort())  // DST.PORT
                    .array()
                output.write(connectRequest)

                // --- Read SOCKS5 connect response ---
                val connectReply = ByteArray(10)
                input.read(connectReply)
                if (connectReply[1] != 0x00.toByte()) {
                    val rep = connectReply[1]
                    logger(Log.WARN, "$logPrefix SOCKS5 connect failed: REP=$rep for ${destInetAddr.hostAddress}:$destPort")
                    socket.close()
                    return@launch
                }

                // --- Send application payload ---
                output.write(payload)
                output.flush()

                // --- Read response (DNS/HTTP replies) ---
                val responseBuffer = ByteArray(65_535)
                val bytesRead = input.read(responseBuffer)
                socket.close()

                if (bytesRead > 0) {
                    val responseData = responseBuffer.copyOf(bytesRead)
                    val returnHeader = VirtualPacketHeader(
                        toAddr = header.fromAddr,
                        toPort = header.fromPort,
                        fromAddr = header.toAddr,
                        fromPort = destPort.toShort(),
                        lastHopAddr = 0,
                        hopCount = 0,
                        maxHops = header.maxHops,
                        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                        payloadSize = responseData.size,
                    )
                    val returnPacket = VirtualPacket.fromHeaderAndPayloadData(
                        header = returnHeader,
                        data = responseData,
                        payloadOffset = 0,
                    )
                    onResponsePacket(returnPacket)
                    logger(Log.DEBUG,
                        "$logPrefix TorGatewayForwarder: response $bytesRead bytes " +
                        "→ mesh addr ${header.fromAddr}")
                }
            } catch (e: IOException) {
                logger(Log.WARN, "$logPrefix TorGatewayForwarder: forward error: ${e.message}")
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix TorGatewayForwarder: unexpected error: ${e.message}", e)
            }
        }
    }

    fun close() {
        job.cancel()
        logger(Log.INFO, "$logPrefix TorGatewayForwarder: closed")
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
```

### Wire `TorGatewayForwarder` into `AndroidVirtualNode.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

After the `clearnetGatewayForwarder` property, add:

```kotlin
    private val torGatewayForwarder: TorGatewayForwarder = TorGatewayForwarder(
        logger = logger,
        logPrefix = "TorGateway",
        onResponsePacket = { packet -> route(packet, null, null) },
    )
```

Then override the Tor gateway dispatch method. First, add a new protected open function to `VirtualNode.kt`:

```kotlin
    protected open fun onTorGatewayPacket(packet: VirtualPacket): Boolean = false
```

**Location in VirtualNode.kt (`processRoutePacket`):** After the CLEARNET dispatch block at line ~884, add:

```kotlin
        // --- TOR GATEWAY DISPATCH ---
        if (currentRoles.contains(MeshRole.TOR_GATEWAY) && packet.header.gatewayType == VirtualPacketHeader.GATEWAY_TYPE_TOR) {
            if (onTorGatewayPacket(packet)) return
        }
```

Then override in `AndroidVirtualNode.kt`:

```kotlin
    override fun onTorGatewayPacket(packet: VirtualPacket): Boolean {
        torGatewayForwarder.forward(packet)
        return true
    }
```

### Orbot SOCKS5 Port Discovery

The Orbot SOCKS5 port is configurable. The static default is 9050. The actual port can be read from Orbot's shared preferences or intent extras. In the Orbot Android codebase:
- Key: `"proxyPort"` or `"PrefOrbotProxy"` in `OrbotService`'s broadcast intents
- Broadcast action: `"org.torproject.android.intent.action.STATUS"`
- Extra: `"EXTRA_SOCKS_PROXY_PORT"` (int)

**Recommendation:** Add a `TorStatusMonitor` callback in `MeshrabiyaApiImpl` that captures the SOCKS5 port from Orbot's STATUS broadcast and passes it to `TorGatewayForwarder` (or stores it in a `MeshrabiyaConstants` preference). The existing `TorStatusMonitor.kt` already registers for Orbot STATUS broadcasts — add the port extraction there.

---

## 5. Gateway Failure Detection — GATEWAY-DOWN MMCP Message

### Current State

Gateway failure is detected only by 30-second stale timeout (`NodeTopologyInfo.isStale(30_000)`). This means:
- If a gateway node loses internet connectivity, other nodes continue routing to it for up to 30 seconds
- If the gateway node's app is killed, same 30-second delay before topology staleness is detected
- No proactive notification to re-route

### Fix Plan

#### 5A. Add `MMCP_GATEWAY_DOWN` Message Type

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/` (find the MMCP message type constants)

Search for `MMCP_TYPE_` or `MessageType` enum in the mmcp package. Add:
```kotlin
const val MMCP_TYPE_GATEWAY_DOWN: Byte = 0x08  // (use next available value)
```

#### 5B. Send `GATEWAY_DOWN` when internet is lost on a gateway node

In `MeshrabiyaApiImpl.kt`, the `_nonMeshWifiState` is set to `IDLE` in `disconnectFromNonMeshWifi()`. When this happens AND the node had a gateway role, broadcast a `GATEWAY_DOWN` MMCP message.

**Location:** `MeshrabiyaApiImpl.disconnectFromNonMeshWifi()` (~line 2182) — after clearing state, add:
```kotlin
        // Notify mesh: this node is no longer a gateway
        val node = myNode
        val roles = emergentRoleManager?.getCurrentMeshRoles() ?: emptySet()
        if (node != null && roles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY) }) {
            node.broadcastGatewayDown()
        }
```

#### 5C. `VirtualNode.broadcastGatewayDown()`

Adds a broadcast packet to all neighbors:
```kotlin
    fun broadcastGatewayDown() {
        val mmcpPayload = byteArrayOf(MMCP_TYPE_GATEWAY_DOWN)
        val header = VirtualPacketHeader(
            toAddr = ADDR_BROADCAST,
            toPort = 0,
            fromAddr = addressAsInt,
            fromPort = 0,
            lastHopAddr = addressAsInt,
            hopCount = 0,
            maxHops = DEFAULT_MAX_HOPS,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
            payloadSize = mmcpPayload.size,
        )
        val packet = VirtualPacket.fromHeaderAndPayloadData(header, mmcpPayload, 0)
        originatingMessageManager.neighbors().forEach { (_, neighbor) ->
            neighbor.receivedFromSocket.send(
                nextHopAddress = neighbor.lastHopRealInetAddr,
                nextHopPort = neighbor.lastHopRealPort,
                virtualPacket = packet,
            )
        }
        logger(Log.INFO, "$logPrefix broadcastGatewayDown: notified ${originatingMessageManager.neighbors().size} neighbors")
    }
```

#### 5D. Handle `GATEWAY_DOWN` in `VirtualNode.onIncomingMmcpMessage()`

When a `GATEWAY_DOWN` is received:
1. Remove the sender node from the topology map gateway role list immediately (don't wait for staleness)
2. Re-trigger `updateRoles()` in `EmergentRoleManager` to re-evaluate if local node should become a gateway

```kotlin
    // In onIncomingMmcpMessage():
    MMCP_TYPE_GATEWAY_DOWN -> {
        val senderAddr = packet.header.fromAddr
        logger(Log.INFO, "$logPrefix GATEWAY_DOWN from ${senderAddr.addressToDotNotation()}: removing gateway roles")
        originatingMessageManager.markNodeGatewayDown(senderAddr)
        emergentRoleManager.updateRoles()   // trigger immediate re-evaluation
        true
    }
```

#### 5E. `OriginatingMessageManager.markNodeGatewayDown(addr: Int)`

```kotlin
    fun markNodeGatewayDown(addr: Int) {
        topologyMap.compute(addr) { _, existing ->
            existing?.copy(
                meshRoles = existing.meshRoles - MeshRole.TOR_GATEWAY - MeshRole.CLEARNET_GATEWAY
            )
        }
        logger(Log.INFO, "markNodeGatewayDown: cleared gateway roles for ${addr.addressToDotNotation()}")
    }
```

---

## 6. Gateway Selection Enhancement — Signal/Bitrate Integration

### Current State

`NodeTopologyInfo.calculateGatewaySuitability()` (line 59–80) uses:
- 30% centrality score
- 40% fitness score (hardware)
- 30% latency normalization (`pingTime`)

Signal strength (RSSI in dBm) and bitrate (Mbps) are captured in logs (`MeshrabiyaWifiManagerAndroid.kt:197-198`) but are NOT stored in `NodeTopologyInfo` or propagated via originator messages.

### Why Signal/Bitrate Matters for Gateway Selection

A gateway node with strong WiFi signal to the internet AP will have more stable and higher-throughput internet access than a node with weak signal. Including signal quality improves gateway selection accuracy.

### Fix Plan

#### 6A. Add `internetSignalStrengthDbm` and `internetLinkSpeedMbps` to `NodeTopologyInfo`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/NodeTopologyInfo.kt`

**BEFORE:**
```kotlin
data class NodeTopologyInfo(
    val nodeAddress: Int,
    val neighbors: Set<Int>,
    val meshRoles: Set<MeshRole>,
    val centralityScore: Float,
    val fitnessScore: Float,
    val lastSeen: Long,
    val pingTime: Short = 0
)
```

**AFTER:**
```kotlin
data class NodeTopologyInfo(
    val nodeAddress: Int,
    val neighbors: Set<Int>,
    val meshRoles: Set<MeshRole>,
    val centralityScore: Float,
    val fitnessScore: Float,
    val lastSeen: Long,
    val pingTime: Short = 0,
    /** RSSI of this node's non-mesh WiFi connection to the internet AP, in dBm. 0 = unknown. */
    val internetSignalStrengthDbm: Int = 0,
    /** Link speed of this node's non-mesh WiFi connection, in Mbps. 0 = unknown. */
    val internetLinkSpeedMbps: Int = 0,
)
```

#### 6B. Propagate signal/bitrate in originator messages

The originator message that carries `NodeTopologyInfo` fields (in `OriginatingMessageManager`) needs to include `internetSignalStrengthDbm` and `internetLinkSpeedMbps`. This requires:

1. Add these fields to whatever MMCP originator message struct carries fitness/centrality/roles
2. Populate them from `meshrabiyaWifiManager.getInternetWifiSignalInfo()` — a new helper function that reads `WifiInfo.rssi` and `WifiInfo.linkSpeed` from the currently connected internet WiFi network

**New helper in `MeshrabiyaWifiManagerAndroid.kt`:**
```kotlin
    data class InternetWifiSignalInfo(
        val rssiDbm: Int = 0,
        val linkSpeedMbps: Int = 0,
    )

    fun getInternetWifiSignalInfo(): InternetWifiSignalInfo {
        if (internetWifiNetwork == null) return InternetWifiSignalInfo()
        val info = wifiManager.connectionInfo ?: return InternetWifiSignalInfo()
        return InternetWifiSignalInfo(
            rssiDbm = info.rssi,
            linkSpeedMbps = info.linkSpeed,
        )
    }
```

#### 6C. Update `calculateGatewaySuitability()` to include signal quality

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/NodeTopologyInfo.kt`

Add signal quality to the suitability score. RSSI range: -90 dBm (very weak) to -30 dBm (excellent). Normalize to 0.0–1.0 by: `signalQuality = (rssi + 90) / 60f` clamped to [0, 1].

**BEFORE (suitability calculation):**
```kotlin
    fun calculateGatewaySuitability(role: MeshRole): Float {
        if (!hasRole(role)) return 0f
        val latencyScore = (1.0f - (pingTime.toFloat() / 1000f)).coerceIn(0f, 1f)
        return centralityScore * 0.3f + fitnessScore * 0.4f + latencyScore * 0.3f
    }
```

**AFTER:**
```kotlin
    fun calculateGatewaySuitability(role: MeshRole): Float {
        if (!hasRole(role)) return 0f
        val latencyScore = (1.0f - (pingTime.toFloat() / 1000f)).coerceIn(0f, 1f)
        // Signal quality: normalize RSSI from [-90,-30] dBm → [0.0, 1.0]
        // 0 (unknown) → treated as 0.5 (neutral, don't penalize nodes that don't report it)
        val signalQuality = if (internetSignalStrengthDbm != 0) {
            ((internetSignalStrengthDbm.toFloat() + 90f) / 60f).coerceIn(0f, 1f)
        } else 0.5f
        // Weights: 25% centrality + 35% fitness + 25% latency + 15% signal
        return centralityScore * 0.25f + fitnessScore * 0.35f + latencyScore * 0.25f + signalQuality * 0.15f
    }
```

---

## 7. Orbot VPN Routing Analysis

### VPN Architecture

Orbot creates a `VpnService`-based TUN interface (`tun0`). When active:
- **All device traffic** is routed through `tun0` → Tor network (in full VPN mode)
- **Per-app exclusions** are configured via `builder.addAllowedApplication()` — apps NOT in the list bypass Tor

### Impact on Mesh Routing

| Scenario | Impact | Solution |
|----------|--------|----------|
| Orbot VPN active + mesh socket | Mesh UDP traffic goes via VPN unless excluded | `VpnService.Builder.addDisallowedApplication(meshPackageName)` OR use protected sockets |
| Gateway node forwarding clearnet | `internetWifiNetwork.bindSocket()` bypasses VPN ✓ | Already handled — use bound socket pattern in ClearnetGatewayForwarder |
| Gateway node forwarding Tor | Loopback socket to 127.0.0.1:9050 bypasses VPN (loopback is not VPN-tunneled) ✓ | Already correct |
| `NET_CAPABILITY_VALIDATED` lost on VPN activation | `_internetWifiNetworkState.hasInternetAccess` flips false | Fixed by green dot periodic check (see companion PR) |

### Recommendation: Exclude Mesh Traffic from Orbot VPN

In `OrbotService.kt` (or the VPN builder), the Meshrabiya library package (`com.ustadmobile.meshrabiya`) should be added to the `addDisallowedApplication()` list:

```kotlin
    // In OrbotVpnManager.kt when building VPN:
    builder.addDisallowedApplication("com.ustadmobile.meshrabiya")
```

**Why:** Mesh traffic (UDP to/from mesh IP ranges) should NOT go via Tor. The mesh itself is the privacy layer (encrypted P2P). Double-tunneling mesh traffic through Tor would add ~500ms latency to every mesh packet.

### Per-App Gateway Preference Analysis

Orbot already has per-app Tor routing via `builder.addAllowedApplication()` (user configures this in the "Choose Apps" screen). This is separate from the "Mesh Proxy Apps" feature below, which routes app traffic through the **mesh** to reach a **mesh gateway node** that then exits to Tor.

---

## 8. "Mesh Proxy Apps" Feature — Per-App Gateway Routing

### Feature Definition

"Mesh Proxy Apps" (or "Torify via Mesh") allows users to configure which apps on their device use the mesh to route their internet traffic to a gateway node, rather than routing directly from their device.

Use cases:
1. Device has NO internet WiFi (joined mesh as participant) — apps can still access internet via a mesh gateway node
2. Device has internet WiFi but prefers to route through a certain gateway (e.g., the gateway node with Tor active)

### Architecture Overview

```
[User App] → [Android VPN (Meshrabiya VPN service)] → [Mesh routing]
    → [MESH] → [TOR_GATEWAY node] → [Orbot SOCKS5] → [Tor] → [Internet]
```

For this to work, the local device must run a lightweight VPN service that intercepts per-app traffic and injects it into the virtual mesh network as `GATEWAY_TYPE_TOR` or `GATEWAY_TYPE_CLEARNET` packets.

### Required Components

#### 8A. New: `MeshProxyVpnService.kt`

A new `VpnService` subclass in the `app/` module that:

1. Intercepts selected apps' traffic via TUN interface
2. Reads IP packets from TUN fd (`FileInputStream` on `/dev/tun0`)
3. Parses IP/TCP/UDP headers to extract destination IP + port
4. Creates `VirtualPacket` with `gatewayType = GATEWAY_TYPE_TOR` or `GATEWAY_TYPE_CLEARNET` based on user preference
5. Sets `toAddr` = destination IP, `toPort` = destination port, `fromAddr` = local mesh address
6. Injects packet into `AndroidVirtualNode` via `meshrabiyaApi.routePacketViaGateway(packet)`
7. Listens for return packets (injected back by the gateway forwarder) and writes them back to the TUN fd

**Key implementation notes:**
- Only intercept UDP in Phase 1 (DNS, NTP, QUIC/HTTP3)
- TCP requires a full connection-tracking proxy layer (Phase 2)
- Must NOT intercept Orbot's own traffic (to avoid loops)
- Must NOT intercept Meshrabiya's own traffic

```kotlin
class MeshProxyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = Builder()
            .addAddress("10.0.0.1", 32)
            .addRoute("0.0.0.0", 0)              // capture all traffic
            .addDnsServer("8.8.8.8")
            .setSession("MeshProxy")
            // Only apply to selected apps from UI preference
            .apply {
                selectedAppPackages.forEach { addAllowedApplication(it) }
            }
            // Exclude Orbot + Meshrabiya from double-routing
            .addDisallowedApplication("org.torproject.android")
            .addDisallowedApplication(packageName)

        vpnInterface = builder.establish()
        startPacketForwarding()
        return START_STICKY
    }

    private fun startPacketForwarding() {
        val fd = vpnInterface?.fileDescriptor ?: return
        // Launch coroutine reading from TUN + injecting into mesh
    }
}
```

#### 8B. Per-App Selection UI in `EnhancedMeshFragment`

Add a "Proxy Apps" chip or button that launches an `AppSelectionActivity`:
- Shows list of installed apps (similar to Orbot's "Choose Apps" screen)
- User toggles apps to route via mesh gateway
- Selection persisted in DataStore as `Set<String>` of package names
- Key: `PREF_MESH_PROXY_APP_PACKAGES`

New API function in `MeshrabiyaApi.kt`:
```kotlin
    suspend fun setMeshProxyApps(packageNames: Set<String>)
    suspend fun getMeshProxyApps(): Set<String>
    fun getMeshProxyActiveFlow(): StateFlow<Boolean>  // true when VPN is active
```

#### 8C. API Key in `MeshrabiyaConstants.kt`

```kotlin
    val KEY_MESH_PROXY_APP_PACKAGES = stringSetPreferencesKey("mesh_proxy_app_packages")
    val KEY_MESH_PROXY_GATEWAY_PREFERENCE = stringPreferencesKey("mesh_proxy_gateway_preference")
```

#### 8D. New `NetworkInfoDto` field

Add `meshProxyActive: Boolean` to `NetworkInfoDto` so the Fragment can show a persistent notification when Mesh Proxy is active:

```kotlin
data class NetworkInfoDto(
    ...
    val meshProxyActive: Boolean = false,
)
```

---

## 9. Implementation Sequence (Priority Order)

| # | Task | File(s) | Effort | Blocks |
|---|------|---------|--------|--------|
| 1 | EmergentRoleManager internet-awareness fix | `EmergentRoleManager.kt` | Small | Gateway role correctness |
| 2 | `ClearnetGatewayForwarder` full implementation | `ClearnetGatewayForwarder.kt` | Medium | Clearnet packet forwarding |
| 3 | `AndroidVirtualNode.onClearnetGatewayPacket()` override | `AndroidVirtualNode.kt` | Small | Clearnet dispatch |
| 4 | `TorGatewayForwarder` new file | New file | Medium | Tor packet forwarding |
| 5 | `VirtualNode.onTorGatewayPacket()` + `AndroidVirtualNode` override | `VirtualNode.kt`, `AndroidVirtualNode.kt` | Small | Tor dispatch |
| 6 | `GATEWAY_DOWN` MMCP message broadcast + handler | `VirtualNode.kt`, `OriginatingMessageManager.kt`, `MeshrabiyaApiImpl.kt` | Medium | Fast failover |
| 7 | Signal/bitrate in gateway selection | `NodeTopologyInfo.kt`, `MeshrabiyaWifiManagerAndroid.kt`, `OriginatingMessageManager.kt` | Medium | Better gateway selection |
| 8 | Orbot VPN exclusion for mesh traffic | `orbotservice/` VPN builder | Small | Correct VPN interaction |
| 9 | Mesh Proxy Apps VPN service + UI | New files in `app/` | Large | Per-app routing |

---

## 10. Integration Points Summary

### Complete Data Flow: Non-Gateway Node → Gateway Node → Internet → Return

```
1. App on Node A sends DNS query to 8.8.8.8:53
2. MeshProxyVpnService (if active) captures UDP packet from TUN fd
   OR: existing mesh socket routes to gateway based on GatewayPreference
3. VirtualPacket created: toAddr=8.8.8.8, toPort=53, gatewayType=GATEWAY_TYPE_TOR
4. VirtualNode.processRoutePacket() → no local route for 8.8.8.8 → routeViaGateway()
5. routeViaGateway() → getAvailableTorGateways() from topology
6. selectBestGateway() → picks Node B (TOR_GATEWAY, fitness=0.93, latency=20ms)
7. forwardToGateway() → packet sent to Node B via mesh hop(s)
8. Node B's VirtualNode.processRoutePacket():
   - currentRoles contains TOR_GATEWAY
   - packet.header.gatewayType == GATEWAY_TYPE_TOR
   - onTorGatewayPacket(packet) → returns true
9. AndroidVirtualNode.onTorGatewayPacket() → torGatewayForwarder.forward(packet)
10. TorGatewayForwarder: SOCKS5 connect to 127.0.0.1:9050
    → SOCKS5 CONNECT to 8.8.8.8:53
    → Send DNS payload
    → Receive DNS response
11. TorGatewayForwarder: onResponsePacket(returnPacket)
    → VirtualPacket(toAddr=Node A, toPort=srcPort, payload=DNS response)
12. Node B routes returnPacket → Node A via mesh
13. Node A's VirtualNode: toAddr==localAddr, deliver to listening socket / TUN fd
14. App receives DNS response → resolves hostname → proceeds with HTTP(S) request
```

### Key File Map

| File | Role | Status |
|------|------|--------|
| `vnet/EmergentRoleManager.kt` | Role assignment (add internet-access gate) | Needs change |
| `vnet/VirtualNode.kt` | Packet routing engine | Needs 2 additions (Tor dispatch + GATEWAY_DOWN handler) |
| `vnet/AndroidVirtualNode.kt` | Override gateway dispatch methods | Needs 2 overrides |
| `vnet/ClearnetGatewayForwarder.kt` | Clearnet UDP forwarding | Full rewrite |
| `vnet/TorGatewayForwarder.kt` | Tor SOCKS5 forwarding | New file |
| `vnet/NodeTopologyInfo.kt` | Gateway suitability scoring | Add signal/bitrate fields |
| `vnet/OriginatingMessageManager.kt` | Topology + gateway tracking | Add `markNodeGatewayDown()` |
| `vnet/wifi/MeshrabiyaWifiManagerAndroid.kt` | Internet WiFi signal info | Add `getInternetWifiSignalInfo()` |
| `api/MeshrabiyaApiImpl.kt` | NonMesh disconnect → GATEWAY_DOWN trigger | Needs GATEWAY_DOWN broadcast call |
| `api/DtoModels.kt` | NetworkInfoDto + NonMeshWifi DTOs | Add `meshProxyActive`, signal fields |
| `app/.../MeshProxyVpnService.kt` | Per-app VPN service | New file (Phase 2) |
| `app/.../EnhancedMeshFragment.kt` | Proxy Apps UI chip | New UI element (Phase 2) |
