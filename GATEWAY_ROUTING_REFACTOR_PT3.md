# GATEWAY ROUTING REFACTOR PT3 — Mesh Proxy Apps
## Complete Codebase-Verified Implementation Plan
**Date:** 2026-03-16  
**Status:** Ready for implementation  
**Builds on:** GATEWAY_ROUTING_REFACTOR_PT2.md (all L-10 signal wiring + Gap B complete and building)

---

## 1. Objective

When a given node has no direct internet access but can reach a CLEARNET_GATEWAY peer via the mesh, the apps selected in a new "Mesh Proxy Apps" screen should have their internet traffic transparently routed through the mesh to that gateway node, which then forwards it to the real internet.

This requires:
1. A UI activity (`MeshProxyAppManagerActivity`) to let users select apps — mirrors `AppManagerActivity` but persists its own distinct selection set via `MeshrabiyaApi` (DataStore), separate from Tor's "Choose Apps" (SharedPreferences).
2. A "Mesh Proxy Apps" button on the "More" tab grid alongside "Choose Apps".
3. A local SOCKS5 proxy server (`MeshLocalSocksProxy`) inside lib-meshrabiya that reroutes app traffic through the mesh via `VirtualNode.socketFactory` (ChainSocket TCP).
4. A TCP relay server (`MeshInternetRelayServer`) inside lib-meshrabiya running on CLEARNET_GATEWAY nodes that receives ChainSocket connections and forwards them to the real internet.
5. An Orbot-layer controller (`MeshProxyController`) in `OrbotMeshService` that observes mesh state flows and triggers `OrbotVpnManager` to rebuild the VPN in mesh-proxy mode when conditions are met.

---

## 2. Separation of Concerns Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     ORBOT APPLICATION LAYER                      │
│                                                                  │
│  app module:                                                     │
│    MeshProxyAppManagerActivity   — UI only; calls MeshrabiyaApi │
│    MoreFragment.kt               — adds "Mesh Proxy Apps" button │
│                                                                  │
│  orbotservice module:                                            │
│    MeshProxyController           — observes MeshrabiyaApi flows  │
│      ├─ getFreshMeshInternetGatewayAvailableFlow()               │
│      ├─ calls meshrabiyaApi.startMeshProxyServer()               │
│      └─ sends LOCAL_ACTION_MESH_PROXY_CHANGED LocalBroadcast     │
│                                                                  │
│    OrbotService.java             — receives LOCAL_ACTION_MESH_   │
│      └─ calls mVpnManager.handleIntent(builder, meshIntent)      │
│                                                                  │
│    OrbotVpnManager.java          — new mesh proxy mode:          │
│      ├─ setMeshProxyMode(active, socksPort, packages)            │
│      ├─ doAppBasedRouting() honours mMeshProxyActive             │
│      └─ setupTun2Socks() routes to mMeshProxySocks when active   │
└──────────────────────────────────────────────────────────┬───────┘
         ▲  ONLY via MeshrabiyaApi interface (clean boundary)
┌────────┴─────────────────────────────────────────────────────────┐
│                   lib-meshrabiya LAYER                           │
│                                                                  │
│  MeshrabiyaApi (interface):                                      │
│    EXISTING: setMeshProxyApps(Set<String>)                       │
│    EXISTING: getMeshProxyApps(): Set<String>                     │
│    EXISTING: getMeshProxyActiveFlow(): StateFlow<Boolean>        │
│    NEW:      getMeshInternetGatewayAvailableFlow()               │
│    NEW:      getMeshProxySocksPort(): Int                        │
│    NEW:      startMeshProxyServer()                              │
│    NEW:      stopMeshProxyServer()                               │
│                                                                  │
│  MeshrabiyaApiImpl:                                              │
│    NEW: _meshInternetGatewayAvailableFlow (StateFlow)            │
│         derived = !nonMeshHasInternet && clearnetGateways > 0    │
│    NEW: meshLocalSocksProxy: MeshLocalSocksProxy instance        │
│                                                                  │
│  NEW: MeshLocalSocksProxy.kt                                     │
│    - loopback dynamic TCP port                                   │
│    - full SOCKS5 server (IPv4 + domain name CONNECT)             │
│    - for each CONNECT: uses VirtualNode.socketFactory to open    │
│      a ChainSocket TCP connection to the best CLEARNET_GATEWAY,  │
│      then sends a 6-byte relay header + relays bytes             │
│                                                                  │
│  NEW: MeshInternetRelayServer.kt                                 │
│    - TCP server on 0.0.0.0:MESH_INTERNET_RELAY_PORT (9080)      │
│    - activated by EmergentRoleManager on CLEARNET_GATEWAY role   │
│    - for each connection: reads 6-byte header (destIP + port),   │
│      opens real socket via internetNetwork.bindSocket(),         │
│      relays bytes bidirectionally                                │
│                                                                  │
│  MODIFIED: VirtualNode — no changes needed (socketFactory        │
│            already exposed as public val SocketFactory)          │
│  MODIFIED: EmergentRoleManager — start/stop relay server        │
└──────────────────────────────────────────────────────────────────┘
```

**Interface contract**: `OrbotVpnManager` has NO dependency on `MeshrabiyaApi`. It receives all mesh-proxy parameters via `Intent` extras. `OrbotMeshService` is the only Orbot-layer class that references `MeshrabiyaApi`.

---

## 3. Codebase Verification — All Touchpoints Confirmed

| Symbol | File | Line | Verified |
|--------|------|------|---------|
| `MeshrabiyaApi` interface | `api/MeshrabiyaApi.kt` | L34 | ✅ |
| `setMeshProxyApps(Set<String>)` | `api/MeshrabiyaApi.kt` | L498 | ✅ |
| `getMeshProxyApps(): Set<String>` | `api/MeshrabiyaApi.kt` | L506 | ✅ |
| `getMeshProxyActiveFlow()` | `api/MeshrabiyaApi.kt` | L514 | ✅ |
| `_meshProxyActiveFlow` (MutableStateFlow) | `api/MeshrabiyaApiImpl.kt` | L1250 | ✅ |
| `setMeshProxyApps()` impl | `api/MeshrabiyaApiImpl.kt` | L1254-L1260 | ✅ |
| `getMeshProxyApps()` impl | `api/MeshrabiyaApiImpl.kt` | L1262-L1267 | ✅ |
| `KEY_MESH_PROXY_APP_PACKAGES` | `vnet/MeshrabiyaConstants.kt` | L123 | ✅ |
| `VirtualNode.socketFactory` | `vnet/VirtualNode.kt` | L341-L342 | ✅ (public val ChainSocketFactory) |
| `getAvailableClearnetGateways()` | `vnet/VirtualNode.kt` | L1079 | ✅ (private — not exposed; MeshLocalSocksProxy will be co-located in lib-meshrabiya so has access via internal API) |
| `ChainSocketFactoryImpl.createSocket(InetAddress, Int)` | `vnet/socket/ChainSocketFactoryImpl.kt` | L74 | ✅ (creates TCP socket through mesh) |
| `OrbotVpnManager` constructor | `vpn/OrbotVpnManager.java` | L78 | ✅ `OrbotVpnManager(OrbotService)` |
| `mVpnManager.handleIntent(builder, intent)` in OrbotService | `OrbotService.java` | L712 | ✅ |
| `OrbotVpnManager.doAppBasedRouting()` | `vpn/OrbotVpnManager.java` | L285-L314 | ✅ uses `TorifiedApp.getApps()` + `addAllowedApplication()` |
| `OrbotVpnManager.setupTun2Socks()` | `vpn/OrbotVpnManager.java` | L163+ | ✅ calls `IPtProxy.startSocks(pFlow, "127.0.0.1", mTorSocks)` |
| `LOCAL_ACTION_PORTS` constant | `OrbotConstants.kt` | L121 | ✅ |
| `AppManagerActivity` pattern | `ui/AppManagerActivity.kt` | L1-373 | ✅ full template read |
| `MoreFragment` "Choose Apps" anchor | `ui/more/MoreFragment.kt` | L95-L98 | ✅ |
| `AndroidManifest AppManagerActivity` | `AndroidManifest.xml` | L87-L90 | ✅ |
| `btn_choose_apps` string | `res/values/strings.xml` | L163 | ✅ |
| `nonMeshHasInternet` derivation | `api/MeshrabiyaApiImpl.kt` | L312 | ✅ used in NetworkInfoDto construction |
| `clearnetGateways` count in NetworkInfoDto | `api/DtoModels.kt` | L84 | ✅ |
| `MeshrabiyaApiImpl.getInstance()` pattern | `EnhancedMeshFragment.kt` | L352 | ✅ (singleton access pattern) |
| `EmergentRoleManager.activateGatewayRouting(CLEARNET_GATEWAY)` | `vnet/EmergentRoleManager.kt` | L1016-L1022 | ✅ |
| `internetWifiNetworkStateFlow` access on CLEARNET_GATEWAY | `vnet/EmergentRoleManager.kt` | L693 | ✅ `(virtualNode.meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)?.internetWifiNetworkStateFlow?.value` |
| `OrbotMeshService.registerTorPortReceiver()` pattern | `OrbotMeshService.kt` | L71-L90 | ✅ (LocalBroadcastManager receiver registration template) |

---

## 4. File Change Register

### 4A — New Files to Create

| # | File Path | Size | Notes |
|---|-----------|------|-------|
| 1 | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshLocalSocksProxy.kt` | NEW | SOCKS5 proxy + ChainSocket client |
| 2 | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/gateway/MeshInternetRelayServer.kt` | NEW | TCP relay server on gateway |
| 3 | `app/src/main/java/org/torproject/android/ui/MeshProxyAppManagerActivity.kt` | NEW | Mirror of AppManagerActivity |
| 4 | `orbotservice/src/main/java/org/torproject/android/service/MeshProxyController.kt` | NEW | Flow observer + VPN trigger |

### 4B — Small Files to Edit Directly (< 800 lines)

| # | File Path | Lines | Change |
|---|-----------|-------|--------|
| 5 | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt` | ~130 | Add `MESH_INTERNET_RELAY_PORT` |
| 6 | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt` | ~520 | Add 4 new methods before closing `}` |
| 7 | `app/src/main/java/org/torproject/android/ui/more/MoreFragment.kt` | ~155 | Add "Mesh Proxy Apps" OrbotMenuAction |
| 8 | `app/src/main/AndroidManifest.xml` | ~290 | Add `<activity>` for MeshProxyAppManagerActivity |
| 9 | `app/src/main/res/values/strings.xml` | ~309 | Add 2 new strings |
| 10 | `orbotservice/src/main/java/org/torproject/android/service/OrbotConstants.kt` | ~200 | Add `LOCAL_ACTION_MESH_PROXY_CHANGED` + extras |
| 11 | `orbotservice/src/main/java/org/torproject/android/service/OrbotMeshService.kt` | ~120 | Add MeshProxyController field + start/stop |

### 4C — Large Files (> 800 lines) — BEFORE/AFTER Manual Apply

| # | File Path | Change |
|---|-----------|--------|
| 12 | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt` | Add `_meshInternetGatewayAvailableFlow` + 4 method impls |
| 13 | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt` | Add MeshInternetRelayServer start/stop in CLEARNET_GATEWAY activation |
| 14 | `orbotservice/src/main/java/org/torproject/android/service/vpn/OrbotVpnManager.java` | Add mesh proxy mode fields + `handleIntent` case + `doAppBasedRouting` + `setupTun2Socks` guard |
| 15 | `orbotservice/src/main/java/org/torproject/android/service/OrbotService.java` | Register `LocalBroadcastReceiver` for `LOCAL_ACTION_MESH_PROXY_CHANGED` |

---

## 5. Detailed Implementation Specifications

---

### FILE 1 (NEW): `MeshLocalSocksProxy.kt`

**Path:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshLocalSocksProxy.kt`

**Full content:**

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
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

/**
 * Local SOCKS5 proxy server (client-side mesh proxy).
 *
 * Listens on a loopback TCP port (dynamically assigned). When OrbotVpnManager routes
 * mesh-proxy-app traffic here via go-tun2socks, this proxy:
 *  1. Completes the SOCKS5 handshake with the app's TCP connection.
 *  2. Resolves the best available CLEARNET_GATEWAY virtual address from the mesh topology.
 *  3. Opens a ChainSocket TCP connection to that gateway's MeshInternetRelayServer
 *     at [MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT] using [meshSocketFactory].
 *  4. Sends a 6-byte relay header [4-byte IPv4 dest addr][2-byte dest port big-endian]
 *     and then relays bytes bidirectionally.
 *
 * If no CLEARNET_GATEWAY is reachable, replies with SOCKS5 "host unreachable" (0x04).
 *
 * @param logger           Mesh logger.
 * @param logPrefix        Log tag prefix.
 * @param meshSocketFactory  [VirtualNode.socketFactory] — routes TCP through mesh via ChainSocket.
 * @param getGatewayAddress  Lambda returning the virtual [InetAddress] of the best
 *                           CLEARNET_GATEWAY node, or null if none is available.
 */
class MeshLocalSocksProxy(
    private val logger: MNetLogger,
    private val logPrefix: String,
    private val meshSocketFactory: SocketFactory,
    private val getGatewayAddress: () -> InetAddress?,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var serverSocket: ServerSocket? = null

    /** The loopback TCP port this proxy is listening on. 0 if not started. */
    val localPort: Int get() = serverSocket?.localPort ?: 0

    /** Start accepting connections. Idempotent — safe to call multiple times. */
    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        serverSocket = ss
        logger(Log.INFO, "$logPrefix MeshLocalSocksProxy started on port ${ss.localPort}")
        scope.launch {
            try {
                while (true) {
                    val client = ss.accept()
                    launch { handleSocks5(client) }
                }
            } catch (e: IOException) {
                logger(Log.INFO, "$logPrefix MeshLocalSocksProxy stopped: ${e.message}")
            }
        }
    }

    /** Stop accepting new connections. Idempotent. */
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleSocks5(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            val output: OutputStream = client.getOutputStream()

            // --- SOCKS5 greeting ---
            val verBuf = ByteArray(2)
            input.readFully(verBuf)
            if (verBuf[0] != 0x05.toByte()) { client.close(); return }
            val nMethods = verBuf[1].toInt() and 0xFF
            if (nMethods > 0) input.readFully(ByteArray(nMethods))
            output.write(byteArrayOf(0x05, 0x00)) // server selects NO_AUTH
            output.flush()

            // --- SOCKS5 CONNECT request ---
            val reqBase = ByteArray(4)
            input.readFully(reqBase)
            if (reqBase[0] != 0x05.toByte() || reqBase[1] != 0x01.toByte()) {
                // Only CONNECT (0x01) supported
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }
            val atyp = reqBase[3].toInt() and 0xFF
            val destAddr: InetAddress = when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4); input.readFully(ip); InetAddress.getByAddress(ip)
                }
                0x03 -> { // Domain name
                    val len = input.read()
                    val domain = ByteArray(len); input.readFully(domain)
                    InetAddress.getByName(String(domain))
                }
                0x04 -> { // IPv6
                    val ip = ByteArray(16); input.readFully(ip); InetAddress.getByAddress(ip)
                }
                else -> { client.close(); return }
            }
            val portBytes = ByteArray(2); input.readFully(portBytes)
            val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // --- Resolve gateway ---
            val gatewayAddr = getGatewayAddress()
            if (gatewayAddr == null) {
                logger(Log.WARN, "$logPrefix No CLEARNET_GATEWAY available for ${destAddr.hostAddress}:$destPort")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // host unreachable
                client.close(); return
            }

            logger(Log.DEBUG, "$logPrefix → ${destAddr.hostAddress}:$destPort via gateway ${gatewayAddr.hostAddress}")

            // --- Open ChainSocket to gateway's relay server ---
            val meshSocket: Socket = meshSocketFactory.createSocket(
                gatewayAddr, MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT
            )

            // Send 6-byte relay header: [4-byte dest IPv4][2-byte dest port]
            val destIpBytes = destAddr.address // always 4 bytes for IPv4 (resolved above)
            val relayHeader = byteArrayOf(
                destIpBytes[0], destIpBytes[1], destIpBytes[2], destIpBytes[3],
                ((destPort shr 8) and 0xFF).toByte(),
                (destPort and 0xFF).toByte()
            )
            meshSocket.getOutputStream().write(relayHeader)
            meshSocket.getOutputStream().flush()

            // Read 1-byte ACK from relay server (0x00 = success)
            val ack = meshSocket.getInputStream().read()
            if (ack != 0x00) {
                logger(Log.WARN, "$logPrefix Relay server rejected ${destAddr.hostAddress}:$destPort ack=$ack")
                output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                meshSocket.close(); client.close(); return
            }

            // --- Send SOCKS5 success reply to client ---
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            // --- Bidirectional relay ---
            val upThread = Thread {
                try { input.copyTo(meshSocket.outputStream) } catch (_: Exception) {}
                finally { try { meshSocket.close() } catch (_: Exception) {} }
            }
            upThread.isDaemon = true
            upThread.start()
            try { meshSocket.inputStream.copyTo(output) } catch (_: Exception) {}
            finally {
                try { client.close() } catch (_: Exception) {}
                try { meshSocket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix SOCKS5 handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        stop()
        job.cancel()
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Unexpected EOF (read ${offset}/${buf.size} bytes)")
            offset += n
        }
    }
}
```

---

### FILE 2 (NEW): `MeshInternetRelayServer.kt`

**Path:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/gateway/MeshInternetRelayServer.kt`

**Full content:**

```kotlin
package com.ustadmobile.meshrabiya.vnet.gateway

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
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
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP relay server running on CLEARNET_GATEWAY nodes.
 *
 * Activated by [EmergentRoleManager] when the CLEARNET_GATEWAY role is assigned.
 * Accepts inbound ChainSocket TCP connections (from [MeshLocalSocksProxy] on client nodes).
 *
 * Protocol for each accepted connection:
 *  1. Read a 6-byte relay header: [4 bytes IPv4 dest addr][2 bytes dest port, big-endian]
 *  2. Open a real internet TCP socket to that address, bound to [internetNetwork] so that
 *     it uses the internet WiFi NIC directly and bypasses Orbot's VPN tunnel.
 *  3. Write a 1-byte ACK (0x00 = success, 0x01 = failure).
 *  4. Relay bytes bidirectionally between the client socket and the internet socket.
 *
 * Listens on all interfaces at [MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT] (9080).
 * The port is in the virtual-address space and is reachable by peers via ChainSocket.
 */
class MeshInternetRelayServer(
    private val logger: MNetLogger,
    private val logPrefix: String,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var internetNetwork: Network? = null

    /** Start the relay server. Idempotent. Call with the current internet-capable Network. */
    fun start(network: Network?) {
        if (serverSocket != null) return
        internetNetwork = network
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT))
        serverSocket = ss
        logger(Log.INFO, "$logPrefix MeshInternetRelayServer started on port ${MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT}")
        scope.launch {
            try {
                while (true) {
                    val client = ss.accept()
                    launch { handleClient(client) }
                }
            } catch (e: IOException) {
                logger(Log.INFO, "$logPrefix MeshInternetRelayServer stopped: ${e.message}")
            }
        }
    }

    /** Stop accepting connections. Idempotent. */
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        logger(Log.INFO, "$logPrefix MeshInternetRelayServer stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            val input: InputStream = client.getInputStream()
            val output: OutputStream = client.getOutputStream()

            // Read 6-byte relay header
            val header = ByteArray(6)
            var offset = 0
            while (offset < 6) {
                val n = input.read(header, offset, 6 - offset)
                if (n < 0) { client.close(); return }
                offset += n
            }
            val destAddr = InetAddress.getByAddress(header.copyOfRange(0, 4))
            val destPort = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)

            logger(Log.DEBUG, "$logPrefix relay CONNECT → ${destAddr.hostAddress}:$destPort")

            val internetSocket = Socket()
            try {
                val network = internetNetwork
                if (network != null) {
                    network.bindSocket(internetSocket)
                }
                internetSocket.connect(InetSocketAddress(destAddr, destPort), 10_000)
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix relay failed to connect ${destAddr.hostAddress}:$destPort — ${e.message}")
                output.write(0x01) // failure ACK
                output.flush()
                internetSocket.close(); client.close(); return
            }

            // Send success ACK
            output.write(0x00)
            output.flush()

            // Bidirectional relay
            val upThread = Thread {
                try { input.copyTo(internetSocket.outputStream) } catch (_: Exception) {}
                finally { try { internetSocket.close() } catch (_: Exception) {} }
            }
            upThread.isDaemon = true
            upThread.start()
            try { internetSocket.inputStream.copyTo(output) } catch (_: Exception) {}
            finally {
                try { client.close() } catch (_: Exception) {}
                try { internetSocket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix relay handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        stop()
        job.cancel()
    }
}
```

---

### FILE 3 (NEW): `MeshProxyAppManagerActivity.kt`

**Path:** `app/src/main/java/org/torproject/android/ui/MeshProxyAppManagerActivity.kt`

**Design:** mirrors `AppManagerActivity` (373 lines, fully read at lines 1–373). Uses `MeshrabiyaApiImpl.getInstance()` for DataStore persistence. Reuses same layout files `R.layout.activity_app_manager` and `R.layout.layout_apps_item`. Tracks selection as `isMeshProxied` flag on `TorifiedApp`.

**Full content:**

```kotlin
package org.torproject.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListAdapter
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.torproject.android.BuildConfig
import org.torproject.android.R
import org.torproject.android.service.OrbotConstants
import org.torproject.android.service.vpn.TorifiedApp
import org.torproject.android.ui.core.BaseActivity

/**
 * "Mesh Proxy Apps" activity — a replica of [AppManagerActivity] that persists app selection
 * via [MeshrabiyaApiImpl] (DataStore) rather than SharedPreferences, using a storage key
 * entirely separate from the "Choose Apps" Tor selection.
 *
 * Apps checked here will have their traffic routed through the mesh to a CLEARNET_GATEWAY
 * when the local device does not have direct internet access.
 */
class MeshProxyAppManagerActivity : BaseActivity(), View.OnClickListener {

    inner class AppWrapper(
        var header: String? = null,
        var subheader: String? = null,
        var app: TorifiedApp? = null,
        var isMeshProxied: Boolean = false,
    )

    private var pMgr: PackageManager? = null
    private var listAppsAll: GridView? = null
    private var adapterAppsAll: ListAdapter? = null
    private var progressBar: ProgressBar? = null

    private val meshrabiyaApi by lazy { MeshrabiyaApiImpl.getInstance() }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var selectedPackages: Set<String> = emptySet()
    var uiList: MutableList<AppWrapper> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pMgr = packageManager
        setContentView(R.layout.activity_app_manager)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_mesh_proxy_apps)
        listAppsAll = findViewById(R.id.applistview)
        progressBar = findViewById(R.id.progressBar)
        lockActivityOrientation()
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
            selectedPackages = withContext(Dispatchers.IO) { meshrabiyaApi.getMeshProxyApps() }
            reloadApps()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.app_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_save_apps -> { saveAppSettings(); finish(); true }
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reloadApps() {
        scope.launch {
            progressBar?.visibility = View.VISIBLE
            withContext(Dispatchers.IO) { loadApps() }
            listAppsAll?.adapter = adapterAppsAll
            progressBar?.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun loadApps() {
        val allApps = loadMeshProxyApps(this@MeshProxyAppManagerActivity, selectedPackages)
        val inflater = layoutInflater
        uiList.clear()
        uiList.addAll(allApps.map { AppWrapper(app = it, isMeshProxied = it.isTorified) })

        adapterAppsAll = object : ArrayAdapter<AppWrapper?>(
            this,
            R.layout.layout_apps_item,
            R.id.itemtext,
            uiList as List<AppWrapper?>
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                var cv = convertView
                var entry: ListEntry? = null
                if (cv == null) {
                    cv = inflater.inflate(R.layout.layout_apps_item, parent, false)
                } else {
                    entry = cv.tag as? ListEntry
                }
                if (entry == null) {
                    entry = ListEntry()
                    entry.container = cv?.findViewById(R.id.appContainer)
                    entry.icon = cv?.findViewById(R.id.itemicon)
                    entry.box = cv?.findViewById(R.id.itemcheck)
                    entry.text = cv?.findViewById(R.id.itemtext)
                    entry.header = cv?.findViewById(R.id.tvHeader)
                    entry.subheader = cv?.findViewById(R.id.tvSubheader)
                    cv?.tag = entry
                }
                val aw = uiList[position]
                if (aw.header != null) {
                    entry.header?.text = aw.header
                    entry.header?.visibility = View.VISIBLE
                    entry.subheader?.visibility = View.GONE
                    entry.container?.visibility = View.GONE
                } else {
                    val app = aw.app
                    entry.header?.visibility = View.GONE
                    entry.subheader?.visibility = View.GONE
                    entry.container?.visibility = View.VISIBLE
                    val packageName = app?.packageName
                    if (entry.icon != null && packageName != null) {
                        try {
                            entry.icon?.setImageDrawable(pMgr?.getApplicationIcon(packageName))
                            entry.icon?.tag = entry.box
                            entry.icon?.setOnClickListener(this@MeshProxyAppManagerActivity)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    entry.text?.text = app?.name
                    entry.text?.tag = entry.box
                    entry.text?.setOnClickListener(this@MeshProxyAppManagerActivity)
                    entry.box?.isChecked = aw.isMeshProxied
                    entry.box?.tag = aw
                    entry.box?.setOnClickListener(this@MeshProxyAppManagerActivity)
                }
                cv?.onFocusChangeListener = OnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(
                        ContextCompat.getColor(
                            context,
                            if (hasFocus) R.color.dark_purple else android.R.color.transparent
                        )
                    )
                }
                return cv ?: View(context)
            }
        }
    }

    private fun saveAppSettings() {
        val proxied = uiList
            .filter { it.isMeshProxied && it.app != null }
            .mapNotNull { it.app?.packageName }
            .toSet()
        scope.launch {
            withContext(Dispatchers.IO) { meshrabiyaApi.setMeshProxyApps(proxied) }
        }
    }

    override fun onClick(v: View) {
        var cbox: CheckBox? = null
        if (v is CheckBox) cbox = v
        else if (v.tag is CheckBox) cbox = v.tag as CheckBox
        if (cbox != null) {
            val aw = cbox.tag as? AppWrapper ?: return
            aw.isMeshProxied = !aw.isMeshProxied
            cbox.isChecked = aw.isMeshProxied
        }
    }

    private class ListEntry {
        var box: CheckBox? = null
        var text: TextView? = null
        var icon: ImageView? = null
        var container: View? = null
        var header: TextView? = null
        var subheader: TextView? = null
    }

    companion object {
        private fun includeAppInUi(applicationInfo: ApplicationInfo): Boolean {
            if (!applicationInfo.enabled) return false
            return if (OrbotConstants.BYPASS_VPN_PACKAGES.contains(applicationInfo.packageName)) false
            else BuildConfig.APPLICATION_ID != applicationInfo.packageName
        }

        fun loadMeshProxyApps(context: Context, selectedPackages: Set<String>): List<TorifiedApp> {
            val pMgr = context.packageManager
            val lAppInfo = pMgr.getInstalledApplications(0)
            val apps = ArrayList<TorifiedApp>()
            for (aInfo in lAppInfo) {
                if (!includeAppInUi(aInfo)) continue
                val app = TorifiedApp()
                try {
                    val pInfo = pMgr.getPackageInfo(aInfo.packageName, PackageManager.GET_PERMISSIONS)
                    for (permInfo in pInfo.requestedPermissions ?: emptyArray()) {
                        if (permInfo == Manifest.permission.INTERNET) { app.usesInternet = true }
                    }
                } catch (_: Exception) {}
                if (!app.usesInternet) continue
                try {
                    app.name = pMgr.getApplicationLabel(aInfo).toString()
                } catch (_: Exception) { continue }
                app.isEnabled = aInfo.enabled
                app.uid = aInfo.uid
                app.username = pMgr.getNameForUid(app.uid)
                app.procname = aInfo.processName
                app.packageName = aInfo.packageName
                app.isTorified = selectedPackages.contains(app.packageName)
                apps.add(app)
            }
            apps.sort()
            return apps
        }
    }
}
```

---

### FILE 4 (NEW): `MeshProxyController.kt`

**Path:** `orbotservice/src/main/java/org/torproject/android/service/MeshProxyController.kt`

**Full content:**

```kotlin
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
 * when the local device has no direct internet access but an INTERNET_GATEWAY is reachable via mesh.
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
```

---

## 6. Small-File BEFORE/AFTER Edits

### EDIT 5: `MeshrabiyaConstants.kt` — Add `MESH_INTERNET_RELAY_PORT`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt`  
**Pattern uniqueness:** `KEY_MESH_PROXY_APP_PACKAGES` appears exactly 1 time as an anchor (verified: L123).

**BEFORE (line 122–125):**
```kotlin
    const val KEY_MESH_PROXY_APP_PACKAGES = "mesh_proxy_app_packages"  // Set<String> of package names
```

**AFTER:**
```kotlin
    const val KEY_MESH_PROXY_APP_PACKAGES = "mesh_proxy_app_packages"  // Set<String> of package names
    const val MESH_INTERNET_RELAY_PORT = 9080  // TCP relay port on CLEARNET_GATEWAY nodes
```

---

### EDIT 6: `MeshrabiyaApi.kt` — Add 4 new methods

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`  
**Pattern uniqueness:** the closing sequence `getMeshProxyActiveFlow(): StateFlow<Boolean>\n}` is unique (verified: only 1 occurrence of `getMeshProxyActiveFlow` in file).

**BEFORE (lines 511–519):**
```kotlin
    /**
     * Observe whether mesh proxy is currently active (i.e. the proxy VPN service is running
     * and at least one package is configured). Emits false when not active.
     */
    fun getMeshProxyActiveFlow(): StateFlow<Boolean>
}
```

**AFTER:**
```kotlin
    /**
     * Observe whether mesh proxy is currently active (i.e. the proxy VPN service is running
     * and at least one package is configured). Emits false when not active.
     */
    fun getMeshProxyActiveFlow(): StateFlow<Boolean>

    /**
     * Emits true when BOTH conditions hold simultaneously:
     *   1. The local device does NOT have direct internet (nonMeshHasInternet == false), AND
     *   2. At least one CLEARNET_GATEWAY node is reachable in the mesh topology.
     * Used by [MeshProxyController] to decide when to activate mesh-proxy VPN mode.
     */
    fun getMeshInternetGatewayAvailableFlow(): StateFlow<Boolean>

    /**
     * The loopback TCP port on which [MeshLocalSocksProxy] is currently listening.
     * Returns 0 if the proxy server has not been started via [startMeshProxyServer].
     */
    fun getMeshProxySocksPort(): Int

    /** Start the local SOCKS5 mesh-proxy server. Idempotent. */
    fun startMeshProxyServer()

    /** Stop the local SOCKS5 mesh-proxy server. Idempotent. */
    fun stopMeshProxyServer()
}
```

---

### EDIT 7: `MoreFragment.kt` — Add "Mesh Proxy Apps" button + import

**File:** `app/src/main/java/org/torproject/android/ui/more/MoreFragment.kt`  
**Pattern uniqueness for import:** `import org.torproject.android.ui.AppManagerActivity` appears exactly once (line 22, verified).  
**Pattern uniqueness for action:** `appManagerLauncher.launch(Intent(requireActivity(), AppManagerActivity::class.java))` appears exactly once (line 98, verified).

**BEFORE (lines 22–23):**
```kotlin
import org.torproject.android.ui.AppManagerActivity
import org.torproject.android.ui.OrbotMenuAction
```

**AFTER:**
```kotlin
import org.torproject.android.ui.AppManagerActivity
import org.torproject.android.ui.MeshProxyAppManagerActivity
import org.torproject.android.ui.OrbotMenuAction
```

**BEFORE (lines 95–101):**
```kotlin
            OrbotMenuAction(R.string.btn_choose_apps, R.drawable.ic_choose_apps) {
                appManagerLauncher.launch(Intent(requireActivity(), AppManagerActivity::class.java))
            },
            OrbotMenuAction(R.string.menu_log, R.drawable.ic_log) { showLog() },
```

**AFTER:**
```kotlin
            OrbotMenuAction(R.string.btn_choose_apps, R.drawable.ic_choose_apps) {
                appManagerLauncher.launch(Intent(requireActivity(), AppManagerActivity::class.java))
            },
            OrbotMenuAction(R.string.btn_mesh_proxy_apps, R.drawable.ic_choose_apps) {
                startActivity(Intent(requireActivity(), MeshProxyAppManagerActivity::class.java))
            },
            OrbotMenuAction(R.string.menu_log, R.drawable.ic_log) { showLog() },
```

---

### EDIT 8: `AndroidManifest.xml` — Register MeshProxyAppManagerActivity

**File:** `app/src/main/AndroidManifest.xml`  
**Pattern uniqueness:** `android:name=".ui.AppManagerActivity"` appears exactly once (line 87, verified).

**BEFORE (lines 86–92):**
```xml
        <activity
            android:name=".ui.AppManagerActivity"
            android:label="@string/title_choose_apps"
            android:theme="@style/OrbotActivityTheme" />

        <activity
            android:name=".ui.v3onionservice.OnionServiceActivity"
```

**AFTER:**
```xml
        <activity
            android:name=".ui.AppManagerActivity"
            android:label="@string/title_choose_apps"
            android:theme="@style/OrbotActivityTheme" />

        <activity
            android:name=".ui.MeshProxyAppManagerActivity"
            android:label="@string/title_mesh_proxy_apps"
            android:theme="@style/OrbotActivityTheme" />

        <activity
            android:name=".ui.v3onionservice.OnionServiceActivity"
```

---

### EDIT 9: `strings.xml` — Add 2 strings

**File:** `app/src/main/res/values/strings.xml`  
**Pattern uniqueness:** `<string name="btn_choose_apps">Choose apps</string>` appears exactly once (line 163, verified).

**BEFORE (lines 163–164):**
```xml
    <string name="btn_choose_apps">Choose apps</string>
    <string name="btn_change_exit">Change exit</string>
```

**AFTER:**
```xml
    <string name="btn_choose_apps">Choose apps</string>
    <string name="btn_mesh_proxy_apps">Mesh Proxy Apps</string>
    <string name="title_mesh_proxy_apps">Mesh Proxy Apps</string>
    <string name="btn_change_exit">Change exit</string>
```

---

### EDIT 10: `OrbotConstants.kt` — Add new broadcast constants

**File:** `orbotservice/src/main/java/org/torproject/android/service/OrbotConstants.kt`  
**Pattern uniqueness:** `LOCAL_ACTION_PORTS = "ports"` appears exactly once (line 121, verified).

**BEFORE (lines 121–122):**
```kotlin
    const val LOCAL_ACTION_PORTS = "ports"
    const val LOCAL_ACTION_V3_NAMES_UPDATED = "V3_NAMES_UPDATED"
```

**AFTER:**
```kotlin
    const val LOCAL_ACTION_PORTS = "ports"
    const val LOCAL_ACTION_V3_NAMES_UPDATED = "V3_NAMES_UPDATED"

    // Mesh proxy mode broadcast (sent by OrbotMeshService → received by OrbotService)
    const val LOCAL_ACTION_MESH_PROXY_CHANGED = "mesh_proxy_changed"
    const val EXTRA_MESH_PROXY_ACTIVE = "mesh_proxy_active"
    const val EXTRA_MESH_PROXY_SOCKS_PORT = "mesh_proxy_socks_port"
    const val EXTRA_MESH_PROXY_PACKAGES = "mesh_proxy_packages"
```

---

### EDIT 11: `OrbotMeshService.kt` — Add MeshProxyController

**File:** `orbotservice/src/main/java/org/torproject/android/service/OrbotMeshService.kt`  
**Pattern uniqueness for field:** `private lateinit var meshrabiyaApi: MeshrabiyaApiImpl` appears once (line ~35).  
**Pattern uniqueness for onCreate init:** `meshrabiyaApi.initMesh(applicationContext)` appears once (line ~62).  
**Pattern uniqueness for onDestroy:** `Log.i(TAG, "OrbotMeshService destroyed")` appears once.

**BEFORE (lines ~34–36):**
```kotlin
    // Reference to MeshrabiyaApi for mesh operations
    private lateinit var meshrabiyaApi: MeshrabiyaApiImpl
    
    // Section 8: Binder interface for client access
    private val binder = MeshBinder()
```

**AFTER:**
```kotlin
    // Reference to MeshrabiyaApi for mesh operations
    private lateinit var meshrabiyaApi: MeshrabiyaApiImpl

    // Mesh proxy controller — observes mesh state and triggers VPN rebuild
    private var meshProxyController: MeshProxyController? = null

    // Section 8: Binder interface for client access
    private val binder = MeshBinder()
```

**BEFORE (lines ~61–65):**
```kotlin
        // Get MeshrabiyaApi singleton and initialize if needed
        meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
        meshrabiyaApi.initMesh(applicationContext)
        
        // Section 8.3: Register Tor port broadcast receiver
        registerTorPortReceiver()
```

**AFTER:**
```kotlin
        // Get MeshrabiyaApi singleton and initialize if needed
        meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
        meshrabiyaApi.initMesh(applicationContext)

        // Start mesh proxy controller
        meshProxyController = MeshProxyController(applicationContext, meshrabiyaApi)
        meshProxyController?.start()

        // Section 8.3: Register Tor port broadcast receiver
        registerTorPortReceiver()
```

**BEFORE (lines ~107–111):**
```kotlin
        Log.i(TAG, "OrbotMeshService destroyed")
        super.onDestroy()
    }
```

**AFTER:**
```kotlin
        meshProxyController?.stop()
        meshProxyController = null
        Log.i(TAG, "OrbotMeshService destroyed")
        super.onDestroy()
    }
```

---

## 7. Large-File BEFORE/AFTER Snippets (Manual Apply)

---

### LARGE EDIT 12: `MeshrabiyaApiImpl.kt`

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

#### 12A — Add `_meshInternetGatewayAvailableFlow` state + update logic

**Location:** Near line 147 where `_networkInfoFlow` is declared. Add a new StateFlow immediately after `_meshProxyActiveFlow`.

**BEFORE (lines 1248–1253):**
```kotlin
    // === MESH PROXY APPS (Phase 2) ===

    private val _meshProxyActiveFlow = MutableStateFlow(false)

    override fun getMeshProxyActiveFlow(): StateFlow<Boolean> = _meshProxyActiveFlow
```

**AFTER:**
```kotlin
    // === MESH PROXY APPS (Phase 2) ===

    private val _meshProxyActiveFlow = MutableStateFlow(false)

    override fun getMeshProxyActiveFlow(): StateFlow<Boolean> = _meshProxyActiveFlow

    private val _meshInternetGatewayAvailableFlow = MutableStateFlow(false)

    override fun getMeshInternetGatewayAvailableFlow(): StateFlow<Boolean> =
        _meshInternetGatewayAvailableFlow
```

**Purpose:** Declares the new flow that `MeshProxyController` collects.

---

#### 12B — Update `_meshInternetGatewayAvailableFlow` in the reactive NetworkInfoDto combine block

**Location:** Lines ~310–320 where `NetworkInfoDto` is constructed — the place that already derives `nonMeshHasInternet`.

**BEFORE (around lines 311–318):**
```kotlin
                val nonMeshHasInternet = (internetWifiState.hasInternetAccess || internetConfirmed)
```

**AFTER:**
```kotlin
                val nonMeshHasInternet = (internetWifiState.hasInternetAccess || internetConfirmed)
                // Update mesh-internet-gateway availability: true when local has no internet
                // but at least one clearnet gateway is reachable in the mesh
                _meshInternetGatewayAvailableFlow.value = !nonMeshHasInternet &&
                    (topology.values.any { it.roles.contains(com.ustadmobile.meshrabiya.vnet.MeshRole.CLEARNET_GATEWAY) })
```

**Purpose:** Reactively derives the trigger condition for mesh proxy mode from existing state.

---

#### 12C — Add `meshLocalSocksProxy` instance + implement 3 new API methods

**BEFORE (lines 1268–1275, immediately after `getMeshProxyApps` implementation):**
```kotlin
    override suspend fun getMeshProxyApps(): Set<String> {
        val context = appContext ?: return emptySet()
        val prefs = context.dataStore.data.first()
        return prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)]
            ?: emptySet()
    }

    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
```

**AFTER:**
```kotlin
    override suspend fun getMeshProxyApps(): Set<String> {
        val context = appContext ?: return emptySet()
        val prefs = context.dataStore.data.first()
        return prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)]
            ?: emptySet()
    }

    @Volatile private var meshLocalSocksProxy: com.ustadmobile.meshrabiya.vnet.MeshLocalSocksProxy? = null

    override fun getMeshProxySocksPort(): Int = meshLocalSocksProxy?.localPort ?: 0

    override fun startMeshProxyServer() {
        val node = myNode ?: return
        if (meshLocalSocksProxy != null) return  // idempotent
        val proxy = com.ustadmobile.meshrabiya.vnet.MeshLocalSocksProxy(
            logger = node.logger,
            logPrefix = "[MeshProxy]",
            meshSocketFactory = node.socketFactory,
            getGatewayAddress = {
                val gateways = node.getAvailableClearnetGatewayAddresses()
                gateways.firstOrNull()?.let { addr ->
                    java.net.InetAddress.getByAddress(addr.addressToByteArray())
                }
            }
        )
        proxy.start()
        meshLocalSocksProxy = proxy
        _meshProxyActiveFlow.value = true
        Log.i(TAG, "[MESH_PROXY] MeshLocalSocksProxy started on port ${proxy.localPort}")
    }

    override fun stopMeshProxyServer() {
        meshLocalSocksProxy?.close()
        meshLocalSocksProxy = null
        _meshProxyActiveFlow.value = false
        Log.i(TAG, "[MESH_PROXY] MeshLocalSocksProxy stopped")
    }

    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
```

**Purpose:** Implements all 3 new proxy lifecycle methods. Uses `node.getAvailableClearnetGatewayAddresses()` — a new 1-line method to add on `VirtualNode` (see EDIT 12D below).

---

#### 12D — Add `getAvailableClearnetGatewayAddresses()` to `VirtualNode.kt`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**This file is large. Present as BEFORE/AFTER.**

**Location:** Immediately after `getAvailableClearnetGateways()` at line 1079.

**BEFORE (lines 1077–1085):**
```kotlin
     * @return List of NodeTopologyInfo for nodes advertising CLEARNET_GATEWAY role
     */
    private fun getAvailableClearnetGateways(): List<NodeTopologyInfo> {
```

**AFTER:**
```kotlin
     * @return List of NodeTopologyInfo for nodes advertising CLEARNET_GATEWAY role
     */
    private fun getAvailableClearnetGateways(): List<NodeTopologyInfo> {
```
*(No change to the private function.)*

**Add NEW function immediately after the closing `}` of `getAvailableClearnetGateways()`. The exact position must be confirmed by reading lines 1079–1095 before applying. The new function:**

```kotlin
    /**
     * Returns integer virtual addresses of all known CLEARNET_GATEWAY peers.
     * Used by [MeshLocalSocksProxy] to resolve the best gateway to route traffic through.
     * Returns empty list if no clearnet gateway is currently reachable.
     */
    fun getAvailableClearnetGatewayAddresses(): List<Int> =
        getAvailableClearnetGateways().map { it.nodeAddress }
```

**Purpose:** Makes the private gateway list accessible to `MeshrabiyaApiImpl` without exposing `NodeTopologyInfo` outside the library.

**Note:** Also need to import `com.ustadmobile.meshrabiya.ext.addressToByteArray` in `MeshrabiyaApiImpl.kt` if not already present. Verify with `grep_search "addressToByteArray" MeshrabiyaApiImpl.kt` before applying.

---

### LARGE EDIT 13: `EmergentRoleManager.kt`

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

#### 13A — Add `meshInternetRelayServer` field to constructor

**Location:** Constructor parameters (line 138+). The constructor begins `class EmergentRoleManager(` at line 138.

**Current constructor (lines ~138–160, partial):**
```kotlin
class EmergentRoleManager(
    ...existing params...
    private val getTopologyMap: (() -> Map<Int, NodeTopologyInfo>)? = null,  // NEW: Callback
```

**After last existing constructor param (before closing `)`):**  
Add:
```kotlin
    private val meshInternetRelayServer: com.ustadmobile.meshrabiya.vnet.gateway.MeshInternetRelayServer? = null,
```

**Note:** Read lines 138–165 before applying to get exact last param anchor. The pattern must uniquely match within the constructor. Add the new param as the last one before `)`.

---

#### 13B — Start relay server in CLEARNET_GATEWAY activation

**BEFORE (lines 1016–1023):**
```kotlin
                MeshRole.CLEARNET_GATEWAY in addedRoles -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Activating clearnet gateway routing")
                    activateGatewayRouting(GatewayMode.CLEARNET_GATEWAY)
                }
```

**AFTER:**
```kotlin
                MeshRole.CLEARNET_GATEWAY in addedRoles -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Activating clearnet gateway routing")
                    activateGatewayRouting(GatewayMode.CLEARNET_GATEWAY)
                    val internetNetwork = (virtualNode.meshrabiyaWifiManager as?
                        com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManagerAndroid)
                        ?.internetWifiNetworkStateFlow?.value?.network
                    meshInternetRelayServer?.start(internetNetwork)
                    safeLog(LogLevel.INFO, "EmergentRole: MeshInternetRelayServer started")
                }
```

---

#### 13C — Stop relay server in deactivation path

**BEFORE (lines 1025–1033):**
```kotlin
                setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
                    .intersect(removedRoles).isNotEmpty() -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Deactivating gateway routing")
                    deactivateGatewayRouting()
                }
```

**AFTER:**
```kotlin
                setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
                    .intersect(removedRoles).isNotEmpty() -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Deactivating gateway routing")
                    deactivateGatewayRouting()
                    if (MeshRole.CLEARNET_GATEWAY in removedRoles) {
                        meshInternetRelayServer?.stop()
                        safeLog(LogLevel.INFO, "EmergentRole: MeshInternetRelayServer stopped")
                    }
                }
```

**Note:** Verify exact line numbers of these blocks by reading lines 1010–1040 before applying.

---

#### 13D — Wire `MeshInternetRelayServer` construction in `MeshrabiyaApiImpl.kt`

In `MeshrabiyaApiImpl`, when constructing `EmergentRoleManager` (find via `grep "EmergentRoleManager("` in `MeshrabiyaApiImpl.kt`), add the `meshInternetRelayServer` parameter:

```kotlin
meshInternetRelayServer = com.ustadmobile.meshrabiya.vnet.gateway.MeshInternetRelayServer(
    logger = node.logger,
    logPrefix = "[RelayServer]",
)
```

This also requires reading the exact `EmergentRoleManager(...)` construction call in `MeshrabiyaApiImpl` before applying. Locate it with: `grep_search "EmergentRoleManager(" MeshrabiyaApiImpl.kt`.

---

### LARGE EDIT 14: `OrbotVpnManager.java`

**File:** `/Users/dreadstar/workspace/orbot-android/orbotservice/src/main/java/org/torproject/android/service/vpn/OrbotVpnManager.java`

#### 14A — Add mesh proxy mode fields

**BEFORE (lines 61–67):**
```java
public class OrbotVpnManager implements Handler.Callback {
    private static final String TAG = "OrbotVpnManager";
    boolean isStarted = false;
    private ParcelFileDescriptor mInterface;
    private int mTorSocks = -1;
    private int mTorDns = -1;
    private final VpnService mService;
```

**AFTER:**
```java
public class OrbotVpnManager implements Handler.Callback {
    private static final String TAG = "OrbotVpnManager";
    boolean isStarted = false;
    private ParcelFileDescriptor mInterface;
    private int mTorSocks = -1;
    private int mTorDns = -1;
    // Mesh proxy mode state (set via LOCAL_ACTION_MESH_PROXY_CHANGED)
    private boolean mMeshProxyActive = false;
    private int mMeshProxySocks = 0;
    private java.util.Set<String> mMeshProxyPackages = new java.util.HashSet<>();
    private final VpnService mService;
```

---

#### 14B — Add case in `handleIntent()` for `LOCAL_ACTION_MESH_PROXY_CHANGED`

**Location:** Inside the switch statement in `handleIntent()`. Find the `LOCAL_ACTION_PORTS` case (verified at line ~103-113) and add the new case immediately after it.

**BEFORE (the block after LOCAL_ACTION_PORTS case, around line 114):**
```java
                    case OrbotConstants.LOCAL_ACTION_PORTS -> {
                        Log.d(TAG, "setting VPN ports");
                        int torSocks = intent.getIntExtra(OrbotConstants.EXTRA_SOCKS_PROXY_PORT, -1);
                        int torDns = intent.getIntExtra(OrbotConstants.EXTRA_DNS_PORT, -1);

                        //if running, we need to restart
                        if ((torSocks != -1 && torSocks != mTorSocks && torDns != -1 && torDns != mTorDns)) {

                            mTorSocks = torSocks;
                            mTorDns = torDns;

                            setupTun2Socks(builder);
                        }
                    }
                }
            }
        }
    }
```

**AFTER:**
```java
                    case OrbotConstants.LOCAL_ACTION_PORTS -> {
                        Log.d(TAG, "setting VPN ports");
                        int torSocks = intent.getIntExtra(OrbotConstants.EXTRA_SOCKS_PROXY_PORT, -1);
                        int torDns = intent.getIntExtra(OrbotConstants.EXTRA_DNS_PORT, -1);

                        //if running, we need to restart
                        if ((torSocks != -1 && torSocks != mTorSocks && torDns != -1 && torDns != mTorDns)) {

                            mTorSocks = torSocks;
                            mTorDns = torDns;

                            setupTun2Socks(builder);
                        }
                    }
                    case OrbotConstants.LOCAL_ACTION_MESH_PROXY_CHANGED -> {
                        Log.d(TAG, "mesh proxy state changed");
                        mMeshProxyActive = intent.getBooleanExtra(OrbotConstants.EXTRA_MESH_PROXY_ACTIVE, false);
                        mMeshProxySocks = intent.getIntExtra(OrbotConstants.EXTRA_MESH_PROXY_SOCKS_PORT, 0);
                        var packages = intent.getStringArrayListExtra(OrbotConstants.EXTRA_MESH_PROXY_PACKAGES);
                        mMeshProxyPackages = packages != null ? new java.util.HashSet<>(packages) : new java.util.HashSet<>();
                        if (mMeshProxyActive && mMeshProxySocks > 0) {
                            Log.d(TAG, "activating mesh proxy VPN with " + mMeshProxyPackages.size() + " apps via SOCKS port " + mMeshProxySocks);
                            setupTun2Socks(builder);
                        } else if (!mMeshProxyActive && isStarted) {
                            // Revert to normal state (will be restarted by Tor on next LOCAL_ACTION_PORTS)
                            stopVPN();
                        }
                    }
                }
            }
        }
    }
```

---

#### 14C — Modify `setupTun2Socks` to route via mesh proxy when active

**BEFORE (lines ~204–206 inside `setupTun2Socks`):**
```java
            mInterface = builder.establish();
            mDnsResolver = new DNSResolver(mTorDns);
```

**AFTER:**
```java
            mInterface = builder.establish();
            if (mMeshProxyActive && mMeshProxySocks > 0) {
                // Mesh proxy mode: DNS handled by go-tun2socks via mesh SOCKS5
                mDnsResolver = null;
            } else {
                mDnsResolver = new DNSResolver(mTorDns);
            }
```

---

#### 14D — Modify `startListeningToFD` to select correct SOCKS5 port

**BEFORE (lines ~228–229 in `startListeningToFD`):**
```java
            IPtProxy.startSocks(pFlow, "127.0.0.1", mTorSocks);
```

**AFTER:**
```java
            int activeSocksPort = (mMeshProxyActive && mMeshProxySocks > 0) ? mMeshProxySocks : mTorSocks;
            IPtProxy.startSocks(pFlow, "127.0.0.1", activeSocksPort);
```

---

#### 14E — Modify `doAppBasedRouting` to use mesh proxy packages when active

**BEFORE (lines 285–299):**
```java
    private void doAppBasedRouting(VpnService.Builder builder) throws NameNotFoundException {
        var apps = TorifiedApp.Companion.getApps(mService, prefs);
        var individualAppsWereSelected = false;
        var isLockdownMode = isVpnLockdown(mService);

        for (TorifiedApp app : apps) {
            if (app.isTorified() && (!app.getPackageName().equals(mService.getPackageName()))) {
                if (prefs.getBoolean(app.getPackageName() + OrbotConstants.APP_TOR_KEY, true)) {
                    builder.addAllowedApplication(app.getPackageName());
                }
                individualAppsWereSelected = true;
            }
        }
```

**AFTER:**
```java
    private void doAppBasedRouting(VpnService.Builder builder) throws NameNotFoundException {
        // Mesh proxy mode: allow ONLY the selected mesh-proxy packages through this VPN
        if (mMeshProxyActive && !mMeshProxyPackages.isEmpty()) {
            for (String pkg : mMeshProxyPackages) {
                builder.addAllowedApplication(pkg);
                Log.d(TAG, "mesh proxy: allowing " + pkg);
            }
            return;
        }

        var apps = TorifiedApp.Companion.getApps(mService, prefs);
        var individualAppsWereSelected = false;
        var isLockdownMode = isVpnLockdown(mService);

        for (TorifiedApp app : apps) {
            if (app.isTorified() && (!app.getPackageName().equals(mService.getPackageName()))) {
                if (prefs.getBoolean(app.getPackageName() + OrbotConstants.APP_TOR_KEY, true)) {
                    builder.addAllowedApplication(app.getPackageName());
                }
                individualAppsWereSelected = true;
            }
        }
```

**Note on DNS:** When `mMeshProxyActive == true`, the `isPacketDNS` check in `startListeningToFD` sends DNS to `mDnsResolver` which is `null` at that point. Add a null guard:

**BEFORE (in `startListeningToFD`, lines ~242–246):**
```java
                                if (isPacketDNS(ipPacket))
                                        mExec.execute(new RequestPacketHandler(ipPacket, pFlow, mDnsResolver));
```

**AFTER:**
```java
                                if (isPacketDNS(ipPacket) && mDnsResolver != null)
                                        mExec.execute(new RequestPacketHandler(ipPacket, pFlow, mDnsResolver));
```

---

### LARGE EDIT 15: `OrbotService.java`

**File:** `/Users/dreadstar/workspace/orbot-android/orbotservice/src/main/java/org/torproject/android/service/OrbotService.java`

#### 15A — Register LocalBroadcastReceiver for `LOCAL_ACTION_MESH_PROXY_CHANGED`

**Location:** Find where `mVpnManager = new OrbotVpnManager(this)` is constructed (line 381) and register the receiver nearby, OR find `onCreate()` / where other receivers are registered.

**Pattern to find (around line 379–384):**
```java
                mVpnManager = new OrbotVpnManager(this);
```

**Add immediately after that line:**
```java
                // Register mesh proxy state receiver
                LocalBroadcastManager.getInstance(OrbotService.this).registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (mVpnManager != null) {
                                mVpnManager.handleIntent(new Builder(), intent);
                            }
                        }
                    },
                    new IntentFilter(OrbotConstants.LOCAL_ACTION_MESH_PROXY_CHANGED)
                );
```

**Note:** Confirm exact surrounding code by reading lines 375–390 before applying. The `BroadcastReceiver` and `IntentFilter` imports already exist in `OrbotService.java` (it already uses `LocalBroadcastManager`). Add `import androidx.localbroadcastmanager.content.LocalBroadcastManager;` if not present.

---

## 8. Implementation Sequence

Implement in this order to avoid compilation failures:

```
Step 1:  Edit OrbotConstants.kt          (adds constants — no dependencies)
Step 2:  Edit MeshrabiyaConstants.kt     (adds MESH_INTERNET_RELAY_PORT)
Step 3:  Create MeshInternetRelayServer.kt  (uses only MeshrabiyaConstants)
Step 4:  Create MeshLocalSocksProxy.kt      (uses only MeshrabiyaConstants + SocketFactory)
Step 5:  Edit MeshrabiyaApi.kt           (new interface methods)
Step 6:  Edit VirtualNode.kt (LARGE)     (add getAvailableClearnetGatewayAddresses())
Step 7:  Edit EmergentRoleManager.kt (LARGE) (add relayServer field + start/stop)
Step 8:  Edit MeshrabiyaApiImpl.kt (LARGE)   (implement 4 new API methods + flow update)
Step 9:  Create MeshProxyAppManagerActivity.kt  (uses MeshrabiyaApiImpl.getInstance())
Step 10: Create MeshProxyController.kt          (uses MeshrabiyaApi interface)
Step 11: Edit OrbotMeshService.kt        (add MeshProxyController)
Step 12: Edit OrbotVpnManager.java (LARGE)  (add mesh proxy mode)
Step 13: Edit OrbotService.java (LARGE)     (register LocalBroadcastReceiver)
Step 14: Edit MoreFragment.kt            (add button + import)
Step 15: Edit AndroidManifest.xml        (register activity)
Step 16: Edit strings.xml                (add strings)
```

---

## 9. Verification Checklist

Before building:
- [ ] `MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT` defined
- [ ] `MeshInternetRelayServer` compiles with `Network`, `ServerSocket`, `MeshrabiyaConstants` imports
- [ ] `MeshLocalSocksProxy` compiles with `SocketFactory`, `MeshrabiyaConstants` imports
- [ ] `VirtualNode.getAvailableClearnetGatewayAddresses()` declared `fun` (not `private`)
- [ ] `MeshrabiyaApi` has all 4 new method signatures
- [ ] `MeshrabiyaApiImpl` implements all 4 new methods (no `abstract` compile errors)
- [ ] `_meshInternetGatewayAvailableFlow` updated in the reactive combine block
- [ ] `EmergentRoleManager` constructor has `meshInternetRelayServer` param with default `= null`
- [ ] `EmergentRoleManager` wired in `MeshrabiyaApiImpl` construction call
- [ ] `OrbotConstants` has `LOCAL_ACTION_MESH_PROXY_CHANGED` + 3 extras
- [ ] `MeshProxyAppManagerActivity` imports `MeshrabiyaApiImpl`, `TorifiedApp`
- [ ] `MeshProxyController` imports `MeshrabiyaApi`, `OrbotConstants`, `LocalBroadcastManager`
- [ ] `OrbotMeshService` starts/stops `meshProxyController` in `onCreate`/`onDestroy`
- [ ] `OrbotVpnManager` has `mMeshProxyActive`, `mMeshProxySocks`, `mMeshProxyPackages` fields
- [ ] `OrbotVpnManager.handleIntent()` has `LOCAL_ACTION_MESH_PROXY_CHANGED` case
- [ ] `OrbotVpnManager.doAppBasedRouting()` early-returns in mesh proxy mode
- [ ] `OrbotVpnManager.startListeningToFD()` null-guards `mDnsResolver` + selects correct SOCKS port
- [ ] `OrbotService.java` registers LocalBroadcastReceiver for mesh proxy changed
- [ ] `MoreFragment.kt` import `MeshProxyAppManagerActivity` added
- [ ] `MoreFragment.kt` new `OrbotMenuAction` inserted after "Choose Apps"
- [ ] `AndroidManifest.xml` has `<activity android:name=".ui.MeshProxyAppManagerActivity">`
- [ ] `strings.xml` has `btn_mesh_proxy_apps` and `title_mesh_proxy_apps`

---

## 10. Known Constraints and Future Work

| Constraint | Impact | Future Resolution |
|------------|--------|-------------------|
| `ClearnetGatewayForwarder` is UDP-only | Raw UDP in VirtualPacket routing remains UDP-only | MeshInternetRelayServer uses ChainSocket TCP bypassing this — not affected |
| `go-tun2socks` routes ALL VPN traffic to single SOCKS5 | Cannot mix Tor + mesh SOCKS5 simultaneously | Mesh proxy mode and Tor VPN mode are mutually exclusive (by design); when mesh proxy activates, Tor VPN is stopped |
| DNS in mesh proxy mode flows through MeshLocalSocksProxy | DNS latency may be higher | Acceptable for initial implementation; DNS caching at gateway is a future opt |
| `MeshLocalSocksProxy` resolves domain names locally before routing | DNS resolution uses device's default resolver even when no internet | Phase 2: route DNS ENTIRELY through mesh SOCKS5 by not resolving locally |
| Gateway-side `MeshInternetRelayServer` binds to `0.0.0.0:9080` | Port 9080 must not be in use on gateway nodes | Future: dynamic port assignment, advertised in originator messages |
| No backwards app version compatibility | Per `NO_APP_VERSION_Backwards_COMPATIBILITY` rule — N/A, current version baseline only | — |
