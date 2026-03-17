## Gateway Routing Debug – Part 2: Mesh Internet Availability Probe & Mesh IP Green Dot

**Date:** 2026-03-17  
**Depends on:** `GATEWAY_ROUTING_DEBUG_PT1.md`, `GATEWAY_ROUTING_REFACTOR_PT3.md`, `NETWORK_UI_AP_STA_PLAN.md`

---

### 1. Clarification: Does the Issue 6 Solution in PT1 Use a Real Mesh-Side Internet Test?

**Answer:** **No.**  
The current Issue 6 solution in `GATEWAY_ROUTING_DEBUG_PT1.md` relies purely on **topology + local nonMesh state**, not on an actual internet reachability test performed via a mesh gateway that is **not** the testing node.

#### 1.1 Evidence – Where Mesh Gateway Availability Comes From

- **File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
- **Location (verified on disk):** Network info combine block and `_meshInternetGatewayAvailableFlow` update.

```300:335:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
        eventMonitoringScope.launch {
            val node = myNode ?: return@launch
            combine(
                node.state,
                node.originatingMessageManager.state,
                _nonMeshWifiState,
                node.meshrabiyaWifiManager.internetWifiNetworkStateFlow,
                combine(_nonMeshInternetConfirmed, _currentMeshRolesFlow) { confirmed, roles -> Pair(confirmed, roles) }
            ) { localState, topology, nonMeshWifi, internetWifiState, confirmedAndRoles ->
                val internetConfirmed = confirmedAndRoles.first
                val localRoles = confirmedAndRoles.second
                val neighborCount = localState.originatorMessages.count { it.value.hopCount == 1.toByte() }
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
                NetworkInfoDto(
                    bssid = "",
                    ssid = "",
                    ipAddress = localState.address.addressToDotNotation(),
                    isConnected = true,
                    connectedPeers = neighborCount,
                    torGateways = torGateways,
                    clearnetGateways = clearnetGateways,
                    nonMeshSsid = nonMeshSsid,
                    nonMeshIpAddress = internetWifiState.ipAddress,
                    nonMeshHasInternet = nonMeshHasInternet
                )
            }
            .distinctUntilChanged()
            .collect { dto ->
                _networkInfoFlow.value = dto
                _meshInternetGatewayAvailableFlow.value =
                    dto.nonMeshHasInternet != true && dto.clearnetGateways > 0
            }
        }
```

- The **mesh gateway availability** flag is:

  - **Input 1:** `dto.clearnetGateways > 0` → there is at least one reachable `CLEARNET_GATEWAY` in the topology (may include self).
  - **Input 2:** `dto.nonMeshHasInternet != true` → this node does **not** currently have confirmed direct nonMesh internet (either `false` or `null`).

- There is **no probe** performed via a remote gateway here; `_meshInternetGatewayAvailableFlow` is derived entirely from:
  - Topology metadata (`NetworkInfoDto.clearnetGateways`), and
  - Local nonMesh WiFi state (`nonMeshHasInternet`).

#### 1.2 Evidence – How NonMesh Internet Is Probed and Confirmed

- **File:** `MeshrabiyaApiImpl.kt` – nonMesh internet confirmation and periodic probe.

```400:452:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
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
                            _nonMeshInternetConfirmed.value = confirmed
                        }
                    }
                } else {
                    _nonMeshInternetConfirmed.value = false
                }
            }
        }
```

```452:494:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
    private suspend fun checkNonMeshInternetAccess(node: AndroidVirtualNode): Boolean =
        withContext(Dispatchers.IO) {
            val network = node.meshrabiyaWifiManager.internetWifiNetwork ?: return@withContext false
            try {
                val url = URL("http://connectivitycheck.gstatic.com/generate_204")
                val conn = network.openConnection(url) as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.requestMethod = "HEAD"
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code == 204 || code == 200
            } catch (e: Exception) {
                Log.d(TAG, "[NONMESH] internet probe failed (${e.javaClass.simpleName}), trying VALIDATED")
                val ctx = appContext ?: return@withContext false
                val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@withContext false
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        }
```

- This probe:
  - Runs **only on the nonMesh WiFi interface** (bound `internetWifiNetwork`).
  - Uses HTTP HEAD to `connectivitycheck.gstatic.com` + fallback to `NET_CAPABILITY_VALIDATED`.
  - Updates `_nonMeshInternetConfirmed`, which then feeds `nonMeshHasInternet` and finally `_meshInternetGatewayAvailableFlow`.

- **There is no analogous probe that runs via a mesh gateway.**  
  No code in `MeshrabiyaApiImpl`, `VirtualNode`, `MeshLocalSocksProxy`, or gateway components runs `generate_204` (or similar) via a CLEARNET_GATEWAY to validate mesh-routed internet.

#### 1.3 Evidence – Current UI Use of Mesh Gateway Availability

- **MeshProxyController** consumes `_meshInternetGatewayAvailableFlow` for VPN routing decisions (no UI toast):

```24:51:orbotservice/src/main/java/org/torproject/android/service/MeshProxyController.kt
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
```

- **EnhancedMeshFragment** now also observes this flow (Issue 6 patch in PT1) and shows a **toast** when it flips from false→true (code not repeated here; PT1 already specifies).

- In all usages, the flow is treated as **“mesh internet via gateway is possible”**, but in reality it only encodes:
  - “No direct nonMesh internet here” **and**
  - “At least one clearnet gateway reachable in topology.”

**Conclusion:** The Issue 6 solution **does not** yet perform a mesh-side internet test using a remote gateway; it approximates availability from topology + nonMesh state.

---

### 2. Network Information Card – Current NonMesh Green Dot vs Mesh IP Row

#### 2.1 Internet WiFi Row and Green Dot (Implemented)

- **File:** `app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml`

```40:90:app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml
            <!-- Mesh connection row: IP address + role chips (C4) -->
            <LinearLayout
                android:id="@+id/meshIpRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginBottom="2dp">

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
                    ...
                </com.google.android.material.chip.Chip.ChipGroup>

            </LinearLayout>

            <!-- Internet WiFi connection row: shown only when WiFi button is connected (C4) -->
            <LinearLayout
                android:id="@+id/internetWifiRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginBottom="2dp"
                android:visibility="gone">
                ...
                <!-- Green dot: shown directly left of chips when NET_CAPABILITY_VALIDATED confirms internet access -->
                <View
                    android:id="@+id/internetWifiGreenDot"
                    android:layout_width="8dp"
                    android:layout_height="8dp"
                    android:layout_marginStart="8dp"
                    android:layout_marginEnd="6dp"
                    android:background="@android:color/holo_green_light"
                    android:visibility="gone" />

                <com.google.android.material.chip.ChipGroup
                    android:id="@+id/internetWifiChipGroup"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="0dp"
                    app:singleLine="true"
                    app:chipSpacingHorizontal="4dp">
                    ...
                </com.google.android.material.chip.ChipGroup>
            </LinearLayout>
```

- **File:** `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt`

```1:40:app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt
object MeshUIBindings {
    ...
    lateinit var meshIpAddressText: TextView
    lateinit var meshChipMesh: com.google.android.material.chip.Chip
    lateinit var meshChipSta: com.google.android.material.chip.Chip
    lateinit var meshChipAp: com.google.android.material.chip.Chip
    lateinit var internetWifiRow: android.widget.LinearLayout
    lateinit var internetWifiIpText: TextView
    lateinit var internetWifiChipSta: com.google.android.material.chip.Chip
    lateinit var internetWifiGreenDot: View
    lateinit var internetWifiChipWifi: com.google.android.material.chip.Chip
    ...
}
```

- **File:** `EnhancedMeshFragment.kt` – where the nonMesh row is updated and the green dot is toggled:

```780:809:app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
                        MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
                        MeshUIBindings.networkStatsText.text =
                            "Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
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

- The nonMesh row:
  - Shows a **green dot** immediately left of its chips when `networkInfo.nonMeshHasInternet == true`.
  - Has periodic validation via `checkNonMeshInternetAccess()` and OS VALIDATED; this is the behavior we want to mirror conceptually for the mesh IP row, but via a **mesh gateway test**.

#### 2.2 Mesh IP Row – No Green Dot Yet

- As shown in the layout snippet above, `meshIpRow` contains **only**:
  - `meshIpAddressText`
  - `meshChipGroup` (chips: Mesh, STA, AP)

- There is currently **no** view for a mesh-level green dot, and **no binding or update logic** in `MeshUIBindings.kt` / `EnhancedMeshFragment.kt` to drive such a dot.

---

### 3. Design Goals for This Enhancement

1. **Mesh Internet Availability Test via Remote Gateway**
   - The node must **actively test** whether internet is reachable **through the mesh**, using a clearnet gateway that is **not** the testing node.
   - The test should:
     - Run **only** when topology indicates there is at least one other `CLEARNET_GATEWAY`.
     - Preferentially use the **same real-world endpoint** as the nonMesh probe (`generate_204`) to keep semantics consistent.
     - Be strictly **mesh-routed** (i.e., traffic goes to CLEARNET_GATEWAY over mesh, not via local nonMesh / Tor).

2. **Periodic Retest While Gateways Present**
   - As long as topology reports at least one **other** clearnet gateway, the mesh-side internet test should run periodically (e.g., same 30s cadence as the nonMesh probe).
   - If the testing node itself is the **only** gateway, we:
     - **Do not** run the mesh-side test (there is no “other node” to test through).
     - Continue to rely on the **local nonMesh probe** (already implemented).

3. **Mesh IP Row Green Dot**
   - Add a small green dot **to the immediate left of the chips** in the Mesh IP row, mirroring the nonMesh row.
   - The dot should represent **“this node currently has internet via the mesh topology”**, i.e.:
     - either:
       - the node has direct nonMesh internet (`nonMeshHasInternet == true`), **or**
       - the mesh-side gateway test is currently passing (internet reachable via at least one clearnet gateway).
   - The dot must not conflate:
     - “At least one gateway exists in topology” (metadata) vs
     - “End-to-end connectivity actually tested and working” (probe).

---

### 4. Proposed Implementation – Mesh-Side Internet Probe

This section gives an **implementation-ready** plan, grounded in the current code.

#### 4.1 New State in MeshrabiyaApiImpl – Mesh Internet Probe

**File:** `MeshrabiyaApiImpl.kt`  
**Section:** Same region as `_nonMeshInternetConfirmed` and `nonMeshInternetCheckJob`.

**New fields (conceptual):**
- `_meshInternetViaGatewayConfirmed: MutableStateFlow<Boolean>` – **has this node recently succeeded in reaching the internet via a remote mesh gateway?**
- `meshInternetCheckJob: Job?` – periodic mesh-probe job (like `nonMeshInternetCheckJob`).

**Behavior:**
- `_meshInternetViaGatewayConfirmed`:
  - Set to `true` when a mesh-side probe succeeds.
  - Reset to `false` when either:
    - No more **other** clearnet gateways are available in topology.
    - Or a probe fails and an expiry window elapses (optional: keep last success for some seconds).

#### 4.2 Determining “Other Gateways”

We already compute:

```300:322:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
                val remoteClearnetGateways = topology.values.count { nodeInfo ->
                    nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) && !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val torGateways = remoteTorGateways + (if (MeshRoleDto.TOR_GATEWAY in localRoles) 1 else 0)
                val clearnetGateways = remoteClearnetGateways + (if (MeshRoleDto.CLEARNET_GATEWAY in localRoles) 1 else 0)
```

- **remote** clearnet gateways = `remoteClearnetGateways` (topology nodes with `CLEARNET_GATEWAY` role, excluding this node).
- **local** clearnet gateway flag = `MeshRoleDto.CLEARNET_GATEWAY in localRoles`.

We can derive:
- `hasRemoteClearnetGateway = remoteClearnetGateways > 0`
- `isLocalClearnetGateway = MeshRoleDto.CLEARNET_GATEWAY in localRoles`

Our requirement:
- Run a mesh-side test **only** when `hasRemoteClearnetGateway == true`.
- If `isLocalClearnetGateway == true` but `hasRemoteClearnetGateway == false`, then this node is the **only** gateway → **do not** run a mesh-side test; rely on local nonMesh probe only.

#### 4.3 Where to Trigger the Mesh Probe Job

We already have an event-monitoring scope and background jobs:

```160:205:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
    private val eventMonitoringScope = CoroutineScope(Dispatchers.Default)
    private var stateMonitorJob: Job? = null
    private var peerMonitorJob: Job? = null
    // Confirmed internet access on non-mesh WiFi; persists across transient VALIDATED dropouts
    private val _nonMeshInternetConfirmed = MutableStateFlow(false)
    private var nonMeshInternetCheckJob: Job? = null
```

**Plan:**
- Add `_meshInternetViaGatewayConfirmed: MutableStateFlow(false)` and `meshInternetCheckJob: Job?` alongside `_nonMeshInternetConfirmed` / `nonMeshInternetCheckJob`.
- Drive `meshInternetCheckJob` from a **new collector** that watches:
  - Topology + local roles (e.g., from `node.originatingMessageManager.state` and `_currentMeshRolesFlow`).
  - Option: piggyback on the same `combine` used for `NetworkInfoDto`, but with a lighter `map` to extract just `remoteClearnetGateways` + `localRoles`.

High-level pseudo:

```kotlin
eventMonitoringScope.launch {
    combine(
        node.originatingMessageManager.state,
        _currentMeshRolesFlow
    ) { topoState, localRoles ->
        val remoteClearnetGateways = topoState.topology.values.count { ... }
        val hasRemote = remoteClearnetGateways > 0
        val isLocalGateway = MeshRoleDto.CLEARNET_GATEWAY in localRoles
        Pair(hasRemote, isLocalGateway)
    }.distinctUntilChanged().collect { (hasRemote, isLocalGateway) ->
        meshInternetCheckJob?.cancel()
        meshInternetCheckJob = null
        if (hasRemote) {
            meshInternetCheckJob = eventMonitoringScope.launch {
                while (true) {
                    // run mesh-side probe via remote gateway
                }
            }
        } else {
            _meshInternetViaGatewayConfirmed.value = false
        }
    }
}
```

#### 4.4 How to Implement the Mesh-Side Probe

We need to send a test request that:
- **Leaves this node via the mesh**.
- **Reaches a remote clearnet gateway**, which then accesses the internet.
- Returns success/failure to this node.

The existing architecture already includes:

- **Mesh internet relay server** on clearnet gateways:

```1:40:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshLocalSocksProxy.kt
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
 */
```

- A `MeshLocalSocksProxy` that already:
  - Selects an available `CLEARNET_GATEWAY` (`getGatewayAddress` lambda).
  - Connects via `ChainSocket` to that gateway’s `MeshInternetRelayServer`.
  - Forwards TCP to arbitrary internet hosts.

**Design choice:**  
Instead of inventing a new test protocol, we can **reuse** the mesh proxy path:

1. Ensure `MeshLocalSocksProxy` is available (mesh proxy server).
2. Open a SOCKS5 connection to `127.0.0.1:<meshProxyPort>`.
3. Perform a minimal SOCKS5 “CONNECT to `connectivitycheck.gstatic.com:80`” and send an HTTP HEAD `/generate_204`.
4. Consider the mesh-side test **successful** if we get a `204` or `200` within a timeout.

**Where to get the port:**
- `MeshrabiyaApiImpl` already has:

```1280:1288:Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
    override fun getMeshProxySocksPort(): Int = meshLocalSocksProxy?.localPort ?: 0
    override fun startMeshProxyServer() {
        if (meshLocalSocksProxy == null) {
            meshLocalSocksProxy = MeshLocalSocksProxy(
                logger = ::logger,
                logPrefix = "[MeshLocalSocksProxy]",
                meshSocketFactory = myNode?.socketFactory ?: throw IllegalStateException("Mesh not initialized"),
                getGatewayAddress = { myNode?.getClearnetGatewayAddress() }
            )
        }
        meshLocalSocksProxy?.start()
    }
```

**Plan for probe implementation:**

- Add a **private suspend function** to `MeshrabiyaApiImpl`:
  - `private suspend fun checkInternetViaMeshGateway(): Boolean`
  - Steps:
    1. Ensure proxy is running:
       - Call `startMeshProxyServer()` if not already started.
       - Retrieve port from `getMeshProxySocksPort()`.
       - If port is 0, return `false`.
    2. Open a TCP `Socket` to `127.0.0.1:port`.
    3. Implement minimal SOCKS5 handshake (no-auth, CONNECT).
    4. Send HTTP HEAD request to `connectivitycheck.gstatic.com/generate_204` through the CONNECT tunnel.
    5. Parse status; time out quickly (e.g. 5s).
    6. Return `true` on 204/200, else `false`.
  - Ensure:
    - This function is called on `Dispatchers.IO`.
    - Errors are logged with `[MESH_PROBE]` prefix; they **do not** crash the app.

- Integrate into `meshInternetCheckJob`:
  - When `hasRemoteClearnetGateway == true`, `meshInternetCheckJob` loops every e.g. `MESH_INTERNET_CHECK_INTERVAL_MS = 30_000L`:
    - Calls `checkInternetViaMeshGateway()`.
    - Updates `_meshInternetViaGatewayConfirmed.value`.

#### 4.5 How to Combine Direct and Mesh Probes for Availability

We now have:
- `nonMeshHasInternet` from:
  - `internetWifiState.hasInternetAccess` OR `_nonMeshInternetConfirmed`.
- `_meshInternetViaGatewayConfirmed` from:
  - `checkInternetViaMeshGateway()` periodic job.

**We can define a higher-level “this node currently has internet” signal:**

```kotlin
val hasAnyInternet = (nonMeshHasInternet == true) || _meshInternetViaGatewayConfirmed.value
```

**For `_meshInternetGatewayAvailableFlow`:**

- **Today:** `dto.nonMeshHasInternet != true && dto.clearnetGateways > 0`
- **After change, to satisfy your requirement:**
  - The flow should indicate **“internet is available to this node via mesh topology”**, which must be **backed by an actual test** when using a remote gateway.
  - We can keep the same boolean but change its semantics:
    - For non-gateway nodes:
      - `meshInternetGatewayAvailable = (_meshInternetViaGatewayConfirmed.value == true)`
    - For nodes that are themselves a gateway:
      - `meshInternetGatewayAvailable = (nonMeshHasInternet == true)` (local probe).

Concretely in the combine block:

- Compute:
  - `hasRemoteClearnetGateway`
  - `isLocalClearnetGateway`
  - `meshViaRemote = _meshInternetViaGatewayConfirmed.value`
  - `localHasInternet = (nonMeshHasInternet == true)`

- Then:

```kotlin
val meshInternetGatewayAvailable = when {
    isLocalClearnetGateway && !hasRemoteClearnetGateway ->
        // Only local gateway: rely on local nonMesh probe
        localHasInternet
    hasRemoteClearnetGateway ->
        // At least one remote gateway exists: require remote test to pass
        meshViaRemote
    else ->
        false
}
_meshInternetGatewayAvailableFlow.value = meshInternetGatewayAvailable
```

This logic satisfies:
- **If this node is the only gateway:** we never try to test via a remote gateway; we only rely on direct-probe success.
- **If there is at least one other gateway:** we only consider internet “available via mesh” if the mesh-side probe is succeeding.

---

### 5. Mesh IP Row Green Dot – Detailed Plan

#### 5.1 Layout Changes – Add `meshInternetGreenDot`

**File:** `app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml`  
**Location:** Inside `meshIpRow`, mirroring `internetWifiGreenDot` placement.

**BEFORE (excerpt of `meshIpRow`):**

```40:66:app/src/main/res/layout/fragment_mesh_enhanced_deferred.xml
            <LinearLayout
                android:id="@+id/meshIpRow"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:layout_marginBottom="2dp">

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
                    ...
                </com.google.android.material.chip.ChipGroup>
            </LinearLayout>
```

**AFTER (conceptual – to be applied manually per Large File Rule):**
- Insert a `View` representing `meshInternetGreenDot` between `meshIpAddressText` and `meshChipGroup`, mirroring `internetWifiGreenDot`:

```xml
                <TextView
                    android:id="@+id/meshIpAddressText"
                    ... />

                <View
                    android:id="@+id/meshInternetGreenDot"
                    android:layout_width="8dp"
                    android:layout_height="8dp"
                    android:layout_marginStart="8dp"
                    android:layout_marginEnd="6dp"
                    android:background="@android:color/holo_green_light"
                    android:visibility="gone" />

                <com.google.android.material.chip.ChipGroup
                    android:id="@+id/meshChipGroup"
                    ... />
```

#### 5.2 Bindings – MeshUIBindings

**File:** `MeshUIBindings.kt`  
**Location:** Near other Network Information bindings.

**BEFORE (excerpt):**

```1:28:app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt
    lateinit var meshIpAddressText: TextView
    lateinit var meshChipMesh: com.google.android.material.chip.Chip
    lateinit var meshChipSta: com.google.android.material.chip.Chip
    lateinit var meshChipAp: com.google.android.material.chip.Chip
    lateinit var internetWifiRow: android.widget.LinearLayout
    lateinit var internetWifiIpText: TextView
    lateinit var internetWifiChipSta: com.google.android.material.chip.Chip
    lateinit var internetWifiGreenDot: View
    lateinit var internetWifiChipWifi: com.google.android.material.chip.Chip
```

**AFTER (add one line):**

```kotlin
    lateinit var meshIpAddressText: TextView
    lateinit var meshInternetGreenDot: View
    lateinit var meshChipMesh: com.google.android.material.chip.Chip
    ...
```

And in the view-binding initialization (not shown here; see `NETWORK_UI_AP_STA_PLAN.md` C5a/C5b for patterns), add:

```kotlin
    meshInternetGreenDot = view.findViewById(R.id.meshInternetGreenDot)
```

#### 5.3 UI Logic – Driving the Mesh IP Green Dot

We want the Mesh IP dot to track **effective internet availability for this node**, using both direct and mesh-probed signals.

**File:** `EnhancedMeshFragment.kt`  
**Location:** Where `networkInfoFlow` is observed to update Network Information Card.

**Current behavior (excerpt – already shows nonMesh green dot):**

```780:809:app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
                        MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
                        MeshUIBindings.networkStatsText.text =
                            "Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
                        if (!networkInfo.nonMeshSsid.isNullOrEmpty()) {
                            ...
                            MeshUIBindings.internetWifiGreenDot.visibility =
                                if (networkInfo.nonMeshHasInternet == true) View.VISIBLE else View.GONE
                        } else {
                            ...
                        }
```

**Planned extension:**

1. Add a collector for **effective internet availability**:
   - Source: a new API method or flow in `MeshrabiyaApi` (e.g. `getHasAnyInternetFlow(): StateFlow<Boolean>`), which internally combines `nonMeshHasInternet` and `_meshInternetViaGatewayConfirmed`.
   - Alternatively, reuse `getMeshInternetGatewayAvailableFlow()` **if** we redefine it to mean “this node currently has internet via mesh topology (direct or via remote gateway test)”.

2. In `EnhancedMeshFragment`, within the same Network Information observer (or a dedicated coroutine that runs after deferred views are initialized), set:

```kotlin
MeshUIBindings.meshInternetGreenDot.visibility =
    if (hasAnyInternet) View.VISIBLE else View.GONE
```

3. This ensures:
   - When direct nonMesh probe succeeds (even with no mesh gateways), both:
     - Internet WiFi row green dot, and
     - Mesh IP row green dot
     are visible (you _do_ have internet).
   - When there is no direct internet but the mesh-side probe via remote gateway succeeds, only the **Mesh IP** row dot is visible (no internet WiFi row), correctly indicating that internet is available **via mesh**.

---

### 6. Summary of Required Code Changes (for future implementation)

This section is a checklist of the concrete changes implied by the plan above.

1. **`MeshrabiyaApiImpl.kt`**
   - Add `_meshInternetViaGatewayConfirmed: MutableStateFlow<Boolean>` and `meshInternetCheckJob: Job?`.
   - Add a topology/roles-driven collector to start/stop `meshInternetCheckJob` based on `hasRemoteClearnetGateway` / `isLocalClearnetGateway`.
   - Implement `checkInternetViaMeshGateway()` using `startMeshProxyServer()`, `getMeshProxySocksPort()`, and a minimal SOCKS5 client + HTTP HEAD to `generate_204`.
   - Update the `_meshInternetGatewayAvailableFlow` derivation so that:
     - When this node is the **only** gateway, it uses `nonMeshHasInternet == true`.
     - When there is at least one **other** gateway, it uses `_meshInternetViaGatewayConfirmed == true`.

2. **`fragment_mesh_enhanced_deferred.xml`**
   - Add `meshInternetGreenDot` (`View`) to `meshIpRow`, placed directly left of `meshChipGroup`, mirroring `internetWifiGreenDot`.

3. **`MeshUIBindings.kt`**
   - Add a `lateinit var meshInternetGreenDot: View`.
   - Initialize it with `findViewById(R.id.meshInternetGreenDot)` in the binding setup.

4. **`EnhancedMeshFragment.kt`**
   - Add a collector that observes an internet-availability signal (from `MeshrabiyaApi`) and toggles `meshInternetGreenDot.visibility` accordingly.
   - Ensure this collector only runs after `deferredViewsInitialized == true`.
   - Keep the nonMesh green dot behavior unchanged.

5. **API Surface**
   - If not reusing `_meshInternetGatewayAvailableFlow` for the green dot, add a dedicated `getHasAnyInternetFlow(): StateFlow<Boolean>` or equivalent to `MeshrabiyaApi` and implement it in `MeshrabiyaApiImpl` using the existing combine logic.

---

### 7. How This Meets the Stated Requirements

- **“Does the Issue 6 solution rely on actual mesh-side testing?”**  
  - Currently: **No**, it relies on topology + nonMesh state only (verified above).  
  - After this plan: it **will** rely on a real mesh-side test for remote gateways (`checkInternetViaMeshGateway()`).

- **“The access should be retested periodically while topology contains a gateway (except when this node is the only gateway).”**  
  - Satisfied by `meshInternetCheckJob`, which:
    - Runs only when `hasRemoteClearnetGateway == true`.
    - Cancels and clears `_meshInternetViaGatewayConfirmed` when no remote gateways remain.
    - Defers to local nonMesh probes when this node is the only gateway.

- **“Mesh IP row should add a green dot to the immediate left of the chips (like nonMesh IP row).”**  
  - Satisfied by:
    - Adding `meshInternetGreenDot` to `meshIpRow` in `fragment_mesh_enhanced_deferred.xml`.
    - Binding it in `MeshUIBindings.kt`.
    - Driving visibility from an effective internet-availability signal in `EnhancedMeshFragment.kt`.

