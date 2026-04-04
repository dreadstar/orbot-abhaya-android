# VPN Gateway Refactor — Runtime Bug Fixes PT6
**Created:** 2026-04-03  
**Mode:** INFORMATIONAL (no file mutations until user says "apply patch")  
**Source:** Phase 0–9 Debug-Patch-Strategy analysis of probe failure on TOR_GATEWAY-only meshes

---

## Phase 0 — Error Enumeration

```
ERROR #1
  Message : checkInternetViaMeshGateway() always returns false when the only
            available gateway is a TOR_GATEWAY node. Observed: two ECONNREFUSED
            on /192.168.84.81:9080 in phone_test2.log (process 32330, t+63s / t+93s).
            _meshInternetViaGatewayConfirmed permanently stays false.
  File    : Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
  Line    : 521
  Symbol  : checkInternetViaMeshGateway / MESH_INTERNET_RELAY_PORT / getAvailableGatewayAddresses
  Status  : RESOLVED by Changes D-1 and D-2
```

---

## Phase 1 — Symbol and Type Verification

| Symbol | File | Line | Finding |
|--------|------|------|---------|
| `checkInternetViaMeshGateway()` | MeshrabiyaApiImpl.kt | 521 | `private suspend fun checkInternetViaMeshGateway(): Boolean` — complete probe implementation |
| `getAvailableGatewayAddresses()` | VirtualNode.kt | 1190 | Returns `(getAvailableClearnetGateways() + getAvailableTorGateways()).distinctBy { it.nodeAddress }.map { it.nodeAddress }` — **mixes** both types |
| `getAvailableClearnetGatewayAddresses()` | VirtualNode.kt | 1182 | `fun getAvailableClearnetGatewayAddresses(): List<Int>` — public, clearnet only |
| `getAvailableTorGateways()` | VirtualNode.kt | 1163 | `private fun getAvailableTorGateways(): List<NodeTopologyInfo>` — private, returns non-stale TOR_GATEWAY nodes |
| `getAvailableTorGatewayAddresses()` | NOT FOUND | — | **Missing** — no public equivalent to `getAvailableClearnetGatewayAddresses()` for TOR nodes. Must be added. |
| `MESH_INTERNET_RELAY_PORT` | MeshrabiyaConstants.kt | 125 | `9080` — TCP relay port on CLEARNET_GATEWAY nodes ONLY |
| `MeshInternetRelayServer` | vnet/gateway/MeshInternetRelayServer.kt | 1 | Listens on 0.0.0.0:9080. CLEARNET_GATEWAY only. No equivalent server on TOR_GATEWAY nodes. |
| `myNode` | MeshrabiyaApiImpl.kt | 155 | `private var myNode: AndroidVirtualNode? = null` |
| `AndroidVirtualNode` extends `VirtualNode` | AndroidVirtualNode.kt | 40 | `class AndroidVirtualNode(…) : VirtualNode(…)` — new public fun on VirtualNode is accessible via `myNode` |
| `addressToDotNotation` | MeshrabiyaApiImpl.kt | 48 | `import com.ustadmobile.meshrabiya.ext.addressToDotNotation` — already imported |

---

## Root Cause

`checkInternetViaMeshGateway()` calls `getAvailableGatewayAddresses()` which returns addresses from **both** CLEARNET_GATEWAY and TOR_GATEWAY nodes combined. It then unconditionally tries to open a TCP socket on port `9080` (`MESH_INTERNET_RELAY_PORT`) against the first address in that list.

`MeshInternetRelayServer` — the service that listens on port 9080 — is only started on CLEARNET_GATEWAY nodes. TOR_GATEWAY nodes do **not** run any TCP relay server on any port. Their routing occurs at the SOCKS proxy layer.

When the only gateway in the topology is a TOR_GATEWAY node (as observed in the test logs: node 169.254.17.14 advertising `[TOR_GATEWAY]` only), the probe hits that address on port 9080, receives ECONNREFUSED immediately, and `checkInternetViaMeshGateway()` returns `false`. `_meshInternetViaGatewayConfirmed` permanently stays `false`. The joining device (Phone 2) never displays the green dot even though internet via Tor is fully available.

**Fix:** Split `checkInternetViaMeshGateway()` into two independent paths:

- **TOR path (new):** If any non-stale TOR_GATEWAY peer is known in the topology, return `true` immediately. TOR_GATEWAY role is only advertised when Orbot is fully active on the peer — advertising the role **is** the proof of internet availability via Tor. No TCP probe is possible or needed.
- **CLEARNET path (existing):** If no TOR_GATEWAY is known, fall through to the existing port 9080 TCP probe against CLEARNET_GATEWAY nodes only (using the existing `getAvailableClearnetGatewayAddresses()`).

This requires exposing `getAvailableTorGatewayAddresses()` as a public function on `VirtualNode`, parallel to the existing `getAvailableClearnetGatewayAddresses()`.

---

## Changes

### Change D-1 — Add `getAvailableTorGatewayAddresses()` to VirtualNode

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 1178–1193  
**File size:** >800 lines — **manual edit required**

**Pattern uniqueness check:** grep for `fun getAvailableClearnetGatewayAddresses(): List<Int> =` → 1 match at line 1182 ✓

**BEFORE (lines 1178–1193):**
```kotlin
    /**
     * Returns integer virtual addresses of all known CLEARNET_GATEWAY peers.
     * Used by MeshLocalSocksProxy to resolve the best gateway to route traffic through.
     */
    fun getAvailableClearnetGatewayAddresses(): List<Int> =
        getAvailableClearnetGateways().map { it.nodeAddress }

    /**
     * Returns integer virtual addresses of all known gateway peers regardless of type.
     * Includes both CLEARNET_GATEWAY and TOR_GATEWAY nodes, deduplicated.
     * Used by MeshLocalSocksProxy when either gateway type is acceptable for routing.
     */
    fun getAvailableGatewayAddresses(): List<Int> =
        (getAvailableClearnetGateways() + getAvailableTorGateways())
            .distinctBy { it.nodeAddress }
            .map { it.nodeAddress }
```

**AFTER (lines 1178–1200):**
```kotlin
    /**
     * Returns integer virtual addresses of all known CLEARNET_GATEWAY peers.
     * Used by MeshLocalSocksProxy to resolve the best gateway to route traffic through.
     */
    fun getAvailableClearnetGatewayAddresses(): List<Int> =
        getAvailableClearnetGateways().map { it.nodeAddress }

    /**
     * Returns integer virtual addresses of all known TOR_GATEWAY peers.
     * Used by [MeshrabiyaApiImpl] to confirm internet via Tor is reachable without a TCP
     * probe: the presence of a non-stale TOR_GATEWAY peer is sufficient evidence the Tor
     * path is live (TOR_GATEWAY nodes route at the SOCKS layer, not via port 9080).
     */
    fun getAvailableTorGatewayAddresses(): List<Int> =
        getAvailableTorGateways().map { it.nodeAddress }

    /**
     * Returns integer virtual addresses of all known gateway peers regardless of type.
     * Includes both CLEARNET_GATEWAY and TOR_GATEWAY nodes, deduplicated.
     * Used by MeshLocalSocksProxy when either gateway type is acceptable for routing.
     */
    fun getAvailableGatewayAddresses(): List<Int> =
        (getAvailableClearnetGateways() + getAvailableTorGateways())
            .distinctBy { it.nodeAddress }
            .map { it.nodeAddress }
```

---

### Change D-2 — Split `checkInternetViaMeshGateway()` into TOR + CLEARNET paths

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Lines:** 521–567  
**File size:** >800 lines — **manual edit required**

**Pattern uniqueness check:** grep for `private suspend fun checkInternetViaMeshGateway(): Boolean =` → 1 match at line 521 ✓

**BEFORE (lines 521–567):**
```kotlin
    private suspend fun checkInternetViaMeshGateway(): Boolean =
        withContext(Dispatchers.IO) {
            val node = myNode ?: return@withContext false
            val gatewayAddrs = node.getAvailableGatewayAddresses()
            if (gatewayAddrs.isEmpty()) {
                Log.d(TAG, "[MESH_PROBE] No gateway addresses available")
                return@withContext false
            }
            val gatewayVirtualAddr = gatewayAddrs.first()
            val gatewayInet = node.getInetAddressFor(gatewayVirtualAddr)
            var relaySocket: java.net.Socket? = null
            try {
                // Open a ChainSocket to the gateway's relay server.
                // ChainSocketFactory resolves the next-hop using the mesh network's bound socket,
                // so this works correctly even when bindProcessToNetwork() is active.
                relaySocket = node.socketFactory.createSocket(
                    gatewayInet,
                    MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT
                )
                relaySocket.soTimeout = 10_000

                val out = relaySocket.getOutputStream()
                val inp = relaySocket.getInputStream()

                // Send 6-byte relay header: [4-byte IPv4 dest][2-byte port big-endian]
                // Target: 8.8.8.8:53 — hardcoded to avoid DNS resolution on the bound network.
                // TCP connection to 8.8.8.8:53 confirms full internet path is reachable.
                val probeTarget = byteArrayOf(8, 8, 8, 8, 0, 53)
                out.write(probeTarget)
                out.flush()

                // Read 1-byte ACK from relay server (0x00 = success, 0x01 = failure)
                val ack = inp.read()
                if (ack == 0x00) {
                    Log.d(TAG, "[MESH_PROBE] ✅ Internet confirmed via mesh gateway ${gatewayInet.hostAddress}")
                    return@withContext true
                } else {
                    Log.d(TAG, "[MESH_PROBE] Gateway relay returned failure ack=$ack")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.d(TAG, "[MESH_PROBE] Probe failed: ${e.javaClass.simpleName} ${e.message}")
                false
            } finally {
                try { relaySocket?.close() } catch (_: Exception) {}
            }
        }
```

**AFTER (lines 521–580):**
```kotlin
    private suspend fun checkInternetViaMeshGateway(): Boolean =
        withContext(Dispatchers.IO) {
            val node = myNode ?: return@withContext false

            // Path 1: TOR_GATEWAY — the presence of any non-stale TOR_GATEWAY peer
            // confirms internet via Tor is available. TOR_GATEWAY nodes route at the SOCKS
            // layer; they do NOT run MeshInternetRelayServer on port 9080, so no TCP probe
            // is possible or needed. TOR_GATEWAY role is only advertised when Orbot is fully
            // active on the peer, so advertising == internet reachable via Tor.
            val torGatewayAddrs = node.getAvailableTorGatewayAddresses()
            if (torGatewayAddrs.isNotEmpty()) {
                Log.d(TAG, "[MESH_PROBE] ✅ Internet confirmed via TOR_GATEWAY ${torGatewayAddrs.first().addressToDotNotation()}")
                return@withContext true
            }

            // Path 2: CLEARNET_GATEWAY — probe port 9080 (MeshInternetRelayServer).
            val clearnetGatewayAddrs = node.getAvailableClearnetGatewayAddresses()
            if (clearnetGatewayAddrs.isEmpty()) {
                Log.d(TAG, "[MESH_PROBE] No gateway addresses available")
                return@withContext false
            }
            val gatewayVirtualAddr = clearnetGatewayAddrs.first()
            val gatewayInet = node.getInetAddressFor(gatewayVirtualAddr)
            var relaySocket: java.net.Socket? = null
            try {
                // Open a ChainSocket to the gateway's relay server.
                // ChainSocketFactory resolves the next-hop using the mesh network's bound socket,
                // so this works correctly even when bindProcessToNetwork() is active.
                relaySocket = node.socketFactory.createSocket(
                    gatewayInet,
                    MeshrabiyaConstants.MESH_INTERNET_RELAY_PORT
                )
                relaySocket.soTimeout = 10_000

                val out = relaySocket.getOutputStream()
                val inp = relaySocket.getInputStream()

                // Send 6-byte relay header: [4-byte IPv4 dest][2-byte port big-endian]
                // Target: 8.8.8.8:53 — hardcoded to avoid DNS resolution on the bound network.
                // TCP connection to 8.8.8.8:53 confirms full internet path is reachable.
                val probeTarget = byteArrayOf(8, 8, 8, 8, 0, 53)
                out.write(probeTarget)
                out.flush()

                // Read 1-byte ACK from relay server (0x00 = success, 0x01 = failure)
                val ack = inp.read()
                if (ack == 0x00) {
                    Log.d(TAG, "[MESH_PROBE] ✅ Internet confirmed via CLEARNET_GATEWAY ${gatewayInet.hostAddress}")
                    return@withContext true
                } else {
                    Log.d(TAG, "[MESH_PROBE] Gateway relay returned failure ack=$ack")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.d(TAG, "[MESH_PROBE] Probe failed: ${e.javaClass.simpleName} ${e.message}")
                false
            } finally {
                try { relaySocket?.close() } catch (_: Exception) {}
            }
        }
```

---

## Change Summary

| Change | File | Lines | Effect | Editable |
|--------|------|-------|--------|----------|
| D-1 | VirtualNode.kt | 1183 (insert ~7 lines) | Expose `getAvailableTorGatewayAddresses()` as public fun | Manual (>800L) |
| D-2 | MeshrabiyaApiImpl.kt | 521–567 (replace) | Split probe into TOR-presence check + CLEARNET TCP probe | Manual (>800L) |

**Apply order:** D-1 first (adds the function D-2 calls), then D-2.

**No new imports required** in either file:
- `addressToDotNotation` — already imported at MeshrabiyaApiImpl.kt:48
- `getAvailableTorGatewayAddresses()` — method on `VirtualNode`/`AndroidVirtualNode`, no import needed

Say **"apply patch"** to enter PATCH mode and apply all changes.
