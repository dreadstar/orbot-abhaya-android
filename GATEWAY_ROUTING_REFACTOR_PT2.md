# GATEWAY ROUTING REFACTOR — PART 2
## Remaining Work: L-10 Signal Wiring | Gap B Orbot VPN Exclusion | Gap C Mesh Proxy Apps

**Date:** 2026-03-15  
**Status:** Plan — all BEFORE/AFTER verified from live disk reads  
**Prerequisite:** [`GATEWAY_ROUTING_REFACTOR_PLAN.md`](GATEWAY_ROUTING_REFACTOR_PLAN.md) — Part 1 (L-1..L-9) must be applied first

---

## HOW TO USE THIS DOCUMENT

- **SMALL FILE** (≤800 lines): Changes can be applied directly with `replace_string_in_file` — full BEFORE/AFTER provided.
- **LARGE FILE** (>800 lines): Changes MUST be applied **manually** by the developer. BEFORE/AFTER are shown for exact anchoring.
- All BEFORE/AFTER text is verified from live on-disk reads as of 2026-03-15.

---

## SECTION 1 — L-1 THROUGH L-9 STATUS

These were presented as BEFORE/AFTER snippets in the prior session (see `GATEWAY_ROUTING_REFACTOR_PLAN.md`).
They are **pending manual application** by the developer.

| ID  | File | Class / Location | Change Summary | Size |
|-----|------|-----------------|----------------|------|
| L-1 | `EmergentRoleManager.kt` | `NodeCapabilitySnapshot` | Add `val hasNonMeshInternetAccess: Boolean` field | LARGE |
| L-2 | `EmergentRoleManager.kt` | `calculateGatewayEligibility()` | Gate TOR/CLEARNET gateway roles on `hasNonMeshInternetAccess` | LARGE |
| L-3 | `EmergentRoleManager.kt` | `getNodeCapabilities()` | Populate `hasNonMeshInternetAccess` from AndroidVirtualNode field | LARGE |
| L-4 | `VirtualNode.kt` | `processRoutePacket()` | Dispatch packets to `onTorGatewayPacket()` when dest is TOR gateway | LARGE |
| L-5 | `VirtualNode.kt` | _(new stubs)_ | Add `onTorGatewayPacket()` + `broadcastGatewayDown()` bodies | LARGE |
| L-6 | `VirtualNode.kt` | raw byte intercept | Handle `WHAT_GATEWAY_DOWN` before `fromVirtualPacket()` | LARGE |
| L-7 | `OriginatingMessageManager.kt` | _(new fun)_ | Add `markNodeGatewayDown()` — updates `_topologyMapInfo` + emits flow | LARGE |
| L-8 | `MeshrabiyaApiImpl.kt` | `disconnectFromNonMeshWifi()` | Trigger `GATEWAY_DOWN` broadcast on disconnect | LARGE |
| L-9 | `MeshrabiyaWifiManagerAndroid.kt` | _(new fun + data class)_ | Add `InternetWifiSignalInfo` data class + `getInternetWifiSignalInfo()` | LARGE |

> **All L-1..L-9 BEFORE/AFTER snippets are in `GATEWAY_ROUTING_REFACTOR_PLAN.md`.**

---

## SECTION 2 — L-10: GAP A — INTERNET SIGNAL / BITRATE WIRING

### Problem

`NodeTopologyInfo` has `internetSignalStrengthDbm` and `internetLinkSpeedMbps` fields (added in S-2 / original plan), but they are **always zero** because no node ever populates or transmits them.

The full data path is broken at three points:
1. `MmcpOriginatorMessage` has no signal fields — never serialized/sent
2. `OriginatingMessageManager` has no callback to get local signal info
3. `VirtualNode` never wires `meshrabiyaWifiManager.getInternetWifiSignalInfo()` as a callback

### Fix Overview (5 sub-changes across 3 files)

| ID | File | Lines | Size | Change |
|----|------|-------|------|--------|
| L-10a | `mmcp/MmcpOriginatorMessage.kt` | 141 | ✅ SMALL | Add 2 fields to constructor + `toBytes()` + `fromBytes()` + `copyWithPingTimeIncrement()` |
| L-10b | `vnet/OriginatingMessageManager.kt` | ~810 | ⚠️ LARGE | Add `getInternetSignalInfo` callback param to constructor |
| L-10c | `vnet/OriginatingMessageManager.kt` | ~810 | ⚠️ LARGE | Pass signal fields in `makeOriginatingMessage()` return value |
| L-10d | `vnet/OriginatingMessageManager.kt` | ~810 | ⚠️ LARGE | Pass signal fields when constructing `NodeTopologyInfo` on receive |
| L-10e | `vnet/VirtualNode.kt` | ~1500 | ⚠️ LARGE | Wire `getInternetSignalInfo` lambda in `OriginatingMessageManager(...)` construction |

---

### L-10a — `MmcpOriginatorMessage.kt` (SMALL — direct edit)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/MmcpOriginatorMessage.kt`  
**Lines:** 141 total

#### Change 1: Constructor — add two new fields after `meshRoles`

**BEFORE (lines 37–43):**
```kotlin
    // === ENHANCED FIELDS (for topology/centrality) ===
    val neighbors: List<Int> = emptyList(),  // Direct neighbor virtual addresses
    val centralityScore: Float = 0f,         // BFS centrality score
    val fitnessScore: Float = 0f,            // Node fitness (0.0-1.0)
    val meshRoles: Set<MeshRole> = emptySet(), // Current mesh roles
    
) : MmcpMessage(WHAT_ORIGINATOR, messageId) {
```

**AFTER (lines 37–45):**
```kotlin
    // === ENHANCED FIELDS (for topology/centrality) ===
    val neighbors: List<Int> = emptyList(),  // Direct neighbor virtual addresses
    val centralityScore: Float = 0f,         // BFS centrality score
    val fitnessScore: Float = 0f,            // Node fitness (0.0-1.0)
    val meshRoles: Set<MeshRole> = emptySet(), // Current mesh roles
    val internetSignalStrengthDbm: Int = 0,  // RSSI of internet WiFi network (0 = unknown)
    val internetLinkSpeedMbps: Int = 0,      // Link speed of internet WiFi network (0 = unknown)

) : MmcpMessage(WHAT_ORIGINATOR, messageId) {
```

---

#### Change 2: `copyWithPingTimeIncrement()` — copy the two new fields

**BEFORE (lines 49–65):**
```kotlin
    fun copyWithPingTimeIncrement(connectionPingTime: Long): MmcpOriginatorMessage {
        val newPingTimeSum = (pingTimeSum + connectionPingTime.toInt())
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        
        return MmcpOriginatorMessage(
            messageId = messageId,
            sentTime = sentTime,
            pingTimeSum = newPingTimeSum,
            connectConfig = connectConfig,
            neighbors = neighbors,
            centralityScore = centralityScore,
            fitnessScore = fitnessScore,
            meshRoles = meshRoles
        )
    }
```

**AFTER:**
```kotlin
    fun copyWithPingTimeIncrement(connectionPingTime: Long): MmcpOriginatorMessage {
        val newPingTimeSum = (pingTimeSum + connectionPingTime.toInt())
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        
        return MmcpOriginatorMessage(
            messageId = messageId,
            sentTime = sentTime,
            pingTimeSum = newPingTimeSum,
            connectConfig = connectConfig,
            neighbors = neighbors,
            centralityScore = centralityScore,
            fitnessScore = fitnessScore,
            meshRoles = meshRoles,
            internetSignalStrengthDbm = internetSignalStrengthDbm,
            internetLinkSpeedMbps = internetLinkSpeedMbps,
        )
    }
```

---

#### Change 3: `toBytes()` — serialize the two new fields at the end of the payload

**BEFORE (lines ~90–96):**
```kotlin
        dos.writeInt(meshRoles.size)
        meshRoles.forEach { dos.writeByte(it.ordinal) }
        
        val payload = baos.toByteArray()
        return headerAndPayloadToBytes(header, payload)
    }
```

**AFTER:**
```kotlin
        dos.writeInt(meshRoles.size)
        meshRoles.forEach { dos.writeByte(it.ordinal) }

        dos.writeInt(internetSignalStrengthDbm)
        dos.writeInt(internetLinkSpeedMbps)
        
        val payload = baos.toByteArray()
        return headerAndPayloadToBytes(header, payload)
    }
```

---

#### Change 4: `fromBytes()` — deserialize the two new fields + include in return

**BEFORE (lines ~124–141):**
```kotlin
            val meshRolesCount = buffer.int
            val meshRoles = (0 until meshRolesCount).map {
                MeshRole.values()[buffer.get().toInt()]
            }.toSet()
            
            return MmcpOriginatorMessage(
                messageId = messageId,
                sentTime = sentTime,
                pingTimeSum = pingTimeSum,
                connectConfig = connectConfig,
                neighbors = neighbors,
                centralityScore = centralityScore,
                fitnessScore = fitnessScore,
                meshRoles = meshRoles
            )
        }
    }
}
```

**AFTER:**
```kotlin
            val meshRolesCount = buffer.int
            val meshRoles = (0 until meshRolesCount).map {
                MeshRole.values()[buffer.get().toInt()]
            }.toSet()

            val internetSignalStrengthDbm = if (buffer.hasRemaining()) buffer.int else 0
            val internetLinkSpeedMbps = if (buffer.hasRemaining()) buffer.int else 0

            return MmcpOriginatorMessage(
                messageId = messageId,
                sentTime = sentTime,
                pingTimeSum = pingTimeSum,
                connectConfig = connectConfig,
                neighbors = neighbors,
                centralityScore = centralityScore,
                fitnessScore = fitnessScore,
                meshRoles = meshRoles,
                internetSignalStrengthDbm = internetSignalStrengthDbm,
                internetLinkSpeedMbps = internetLinkSpeedMbps,
            )
        }
    }
}
```

> **Note on `buffer.hasRemaining()` guard:** This allows nodes running the old protocol (no signal fields) to still be deserialized safely. Old messages simply default both fields to `0`.

---

### L-10b — `OriginatingMessageManager.kt` constructor (LARGE — manual apply)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`  
**Location:** Lines ~56–67

**BEFORE:**
```kotlin
    // === NEW: Callbacks to break circular dependency ===
    private val getCentralityScore: (() -> Float)? = null,
    private val getMeshRoles: (() -> Set<MeshRole>)? = null,
    private val getFitnessScore: (() -> Float)? = null,  // Changed from () -> Int
    
    // === EXISTING PARAMS ===
    private val pingTimeout: Int = 15_000,
```

**AFTER:**
```kotlin
    // === NEW: Callbacks to break circular dependency ===
    private val getCentralityScore: (() -> Float)? = null,
    private val getMeshRoles: (() -> Set<MeshRole>)? = null,
    private val getFitnessScore: (() -> Float)? = null,  // Changed from () -> Int
    private val getInternetSignalInfo: (() -> Pair<Int, Int>)? = null,

    // === EXISTING PARAMS ===
    private val pingTimeout: Int = 15_000,
```

**Purpose:** Adds the signal callback following the same nullable callback pattern used for `getCentralityScore`, `getMeshRoles`, and `getFitnessScore`. Returns `Pair(rssiDbm, linkSpeedMbps)`.

---

### L-10c — `OriginatingMessageManager.kt` `makeOriginatingMessage()` (LARGE — manual apply)

**File:** Same as above  
**Location:** Lines ~341–349

**BEFORE:**
```kotlin
        return MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            sentTime = System.currentTimeMillis(),
            pingTimeSum = 0,  // Will be incremented as message propagates
            connectConfig = getWifiState().connectConfig,
            neighbors = neighborAddrs,  // NEW: For topology building
            centralityScore = centralityScore,  // NEW: From callback
            fitnessScore = fitnessScore,  // NEW: From callback
            meshRoles = meshRoles,  // NEW: From callback
        )
    }
```

**AFTER:**
```kotlin
        return MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            sentTime = System.currentTimeMillis(),
            pingTimeSum = 0,  // Will be incremented as message propagates
            connectConfig = getWifiState().connectConfig,
            neighbors = neighborAddrs,  // NEW: For topology building
            centralityScore = centralityScore,  // NEW: From callback
            fitnessScore = fitnessScore,  // NEW: From callback
            meshRoles = meshRoles,  // NEW: From callback
            internetSignalStrengthDbm = getInternetSignalInfo?.invoke()?.first ?: 0,
            internetLinkSpeedMbps = getInternetSignalInfo?.invoke()?.second ?: 0,
        )
    }
```

**Purpose:** Populates signal fields in every outgoing originator message so remote nodes see this node's internet WiFi signal quality.

---

### L-10d — `OriginatingMessageManager.kt` `NodeTopologyInfo(...)` construction (LARGE — manual apply)

**File:** Same as above  
**Location:** Lines ~424–437

**BEFORE:**
```kotlin
            val nodeInfo = NodeTopologyInfo(
                nodeAddress = virtualPacket.header.fromAddr,
                neighbors = mmcpMessage.neighbors.toSet(),
                meshRoles = mmcpMessage.meshRoles,  // Store ALL roles (gateway + intelligence)
                centralityScore = mmcpMessage.centralityScore,
                fitnessScore = mmcpMessage.fitnessScore,
                lastSeen = System.currentTimeMillis(),
                pingTime = mmcpMessage.pingTimeSum
            )
```

**AFTER:**
```kotlin
            val nodeInfo = NodeTopologyInfo(
                nodeAddress = virtualPacket.header.fromAddr,
                neighbors = mmcpMessage.neighbors.toSet(),
                meshRoles = mmcpMessage.meshRoles,  // Store ALL roles (gateway + intelligence)
                centralityScore = mmcpMessage.centralityScore,
                fitnessScore = mmcpMessage.fitnessScore,
                lastSeen = System.currentTimeMillis(),
                pingTime = mmcpMessage.pingTimeSum,
                internetSignalStrengthDbm = mmcpMessage.internetSignalStrengthDbm,
                internetLinkSpeedMbps = mmcpMessage.internetLinkSpeedMbps,
            )
```

**Purpose:** Propagates the received signal values into the topology map so `calculateGatewaySuitability()` can use real signal data.

---

### L-10e — `VirtualNode.kt` `OriginatingMessageManager(...)` construction (LARGE — manual apply)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Location:** Lines ~280–300

**BEFORE:**
```kotlin
    open val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutor,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentNodeState.wifiState },
        
        // === NEW: Callbacks to EmergentRoleManager ===
        getCentralityScore = { emergentRoleManager.calculateCentralityScore() },
        getMeshRoles = { emergentRoleManager.currentMeshRoles.value },
        getFitnessScore = { 
            emergentRoleManager.calculateNormalizedFitness(getCurrentNodeCapabilities()) 
        },
        
        // === EXISTING PARAMS ===
        pingTimeout = 15_000,
        originatingMessageNodeLostThreshold = 10_000,
        lostNodeCheckInterval = 1_000
    )
```

**AFTER:**
```kotlin
    open val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutor,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentNodeState.wifiState },
        
        // === NEW: Callbacks to EmergentRoleManager ===
        getCentralityScore = { emergentRoleManager.calculateCentralityScore() },
        getMeshRoles = { emergentRoleManager.currentMeshRoles.value },
        getFitnessScore = { 
            emergentRoleManager.calculateNormalizedFitness(getCurrentNodeCapabilities()) 
        },
        getInternetSignalInfo = {
            (meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
                ?.getInternetWifiSignalInfo()
                ?.let { Pair(it.rssiDbm, it.linkSpeedMbps) }
                ?: Pair(0, 0)
        },

        // === EXISTING PARAMS ===
        pingTimeout = 15_000,
        originatingMessageNodeLostThreshold = 10_000,
        lostNodeCheckInterval = 1_000
    )
```

**Import note:** `VirtualNode.kt` already has `import com.ustadmobile.meshrabiya.vnet.wifi.*` (line 21) — no new import needed for `MeshrabiyaWifiManagerAndroid`.

**Why safe cast instead of interface method?**  
`InternetWifiSignalInfo` is a nested class inside `MeshrabiyaWifiManagerAndroid` — putting it on the `MeshrabiyaWifiManager` interface would create a circular dependency (interface referencing its concrete implementation's nested class). The safe cast `as? MeshrabiyaWifiManagerAndroid` returns `null` on test doubles/mocks, at which point the lambda returns `Pair(0, 0)`.

---

## SECTION 3 — GAP B: ORBOT VPN EXCLUSION

### Problem

When Orbot VPN is in **all-traffic mode** (no individual apps selected), `OrbotVpnManager.java` calls `builder.addDisallowedApplication(packageName)` for every package in `BYPASS_VPN_PACKAGES`. Meshrabiya's package is NOT in this list, so all mesh UDP traffic gets tunneled through Tor — adding ~500ms latency per hop and breaking time-sensitive mesh protocols.

### Fix

**File:** `/Users/dreadstar/workspace/orbot-android/orbotservice/src/main/java/org/torproject/android/service/OrbotConstants.kt`  
**Lines:** 197 total — ✅ SMALL — direct edit

**BEFORE (lines 162–170):**
```kotlin
    val BYPASS_VPN_PACKAGES = mutableListOf(
        "org.torproject.torbrowser_alpha",
        "org.torproject.torbrowser",
        "org.onionshare.android",
        "org.onionshare.android.fdroid",
        "org.briarproject.briar.android",
        "im.cwtch.flwtch",
    )
```

**AFTER:**
```kotlin
    val BYPASS_VPN_PACKAGES = mutableListOf(
        "org.torproject.torbrowser_alpha",
        "org.torproject.torbrowser",
        "org.onionshare.android",
        "org.onionshare.android.fdroid",
        "org.briarproject.briar.android",
        "im.cwtch.flwtch",
        "com.ustadmobile.meshrabiya",  // Prevent mesh UDP from being Tor-tunneled in all-traffic VPN mode
    )
```

**Purpose:** Adding meshrabiya to `BYPASS_VPN_PACKAGES` ensures the VPN builder calls `addDisallowedApplication("com.ustadmobile.meshrabiya")` when in all-traffic mode, bypassing the Tor SOCKS proxy for mesh traffic. This only affects all-traffic mode — when specific apps are selected, meshrabiya is not in the selected set anyway.

**Verification:** `doAppBasedRouting()` in `OrbotVpnManager.java` at line 284 iterates `BYPASS_VPN_PACKAGES` only when `mVpnPrefs.getStringSet(OrbotConstants.PREFS_KEY_APPS, null) == null` (no per-app selection). This is the correct scope.

---

## SECTION 4 — GAP C: MESH PROXY APPS (Phase 2)

### Overview

Mesh Proxy Apps allows users to select which apps on **other mesh nodes** should have their traffic proxied through this node's internet connection. It is the outbound complement to Gap B: Gap B ensures mesh is not accidentally torified; Gap C gives users explicit control over what leaves the mesh through a given gateway node.

**Status:** 0% implemented anywhere in the codebase.

### Component Map

| Component | File | Size | Action |
|-----------|------|------|--------|
| C-1 | `MeshrabiyaConstants.kt` | ✅ SMALL | Add `KEY_MESH_PROXY_APP_PACKAGES` constant |
| C-2 | `MeshrabiyaApi.kt` | ✅ SMALL | Add 3 interface method signatures |
| C-3 | `api/DtoModels.kt` | ✅ SMALL | Add `meshProxyActive: Boolean` to `NetworkInfoDto` + update `toDto()` |
| C-4 | `MeshrabiyaApiImpl.kt` | ⚠️ LARGE | Implement the 3 new API methods |
| C-5 | _(new file)_ `MeshProxyAppManagerActivity.kt` | 🆕 NEW | App chooser UI — mirrors `AppManagerActivity.kt` |
| C-6 | _(new file)_ `MeshProxyVpnService.kt` | 🆕 NEW STUB | Phase 2 VPN service — stub only in this plan |
| C-7 | Fragment / "More" tab | ⚠️ LARGE | Add "Proxy Apps" entry point |

---

### C-1 — `MeshrabiyaConstants.kt` (SMALL — direct edit)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt`  
**Location:** Lines 118–123 (KEY_* block)

**BEFORE:**
```kotlin
    // DataStore preference keys for gateway settings and participation
    const val KEY_TOR_GATEWAY_ENABLED = "tor_gateway_enabled"
    const val KEY_CLEARNET_GATEWAY_ENABLED = "clearnet_gateway_enabled"
    const val KEY_STORAGE_PARTICIPATION_ENABLED = "storage_participation_enabled"
    const val KEY_SERVICE_PARTICIPATION_ENABLED = "service_participation_enabled"
```

**AFTER:**
```kotlin
    // DataStore preference keys for gateway settings and participation
    const val KEY_TOR_GATEWAY_ENABLED = "tor_gateway_enabled"
    const val KEY_CLEARNET_GATEWAY_ENABLED = "clearnet_gateway_enabled"
    const val KEY_STORAGE_PARTICIPATION_ENABLED = "storage_participation_enabled"
    const val KEY_SERVICE_PARTICIPATION_ENABLED = "service_participation_enabled"
    const val KEY_MESH_PROXY_APP_PACKAGES = "mesh_proxy_app_packages"  // Set<String> of package names
```

---

### C-2 — `MeshrabiyaApi.kt` (SMALL — direct edit)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`  
**Location:** Before the closing `}` of the interface (currently line 496)

**BEFORE:**
```kotlin
    /**
     * Returns true if the Android WiFi radio is currently enabled (WifiManager.isWifiEnabled).
     * Works on all SDK versions — the getter is not deprecated.
     * Used as a pre-flight gate before the Join Mesh flow.
     * Returns false if the mesh node is not yet initialized.
     */
    fun isWifiEnabled(): Boolean
}
```

**AFTER:**
```kotlin
    /**
     * Returns true if the Android WiFi radio is currently enabled (WifiManager.isWifiEnabled).
     * Works on all SDK versions — the getter is not deprecated.
     * Used as a pre-flight gate before the Join Mesh flow.
     * Returns false if the mesh node is not yet initialized.
     */
    fun isWifiEnabled(): Boolean

    // === MESH PROXY APPS (Phase 2) ===

    /**
     * Persist the set of package names whose traffic this node will proxy through its
     * internet connection on behalf of remote mesh peers.
     * Stored via DataStore using [MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES].
     */
    suspend fun setMeshProxyApps(packageNames: Set<String>)

    /**
     * Return the currently persisted set of package names for mesh proxy.
     * Returns empty set if none configured.
     */
    suspend fun getMeshProxyApps(): Set<String>

    /**
     * Observe whether mesh proxy is currently active (i.e. the proxy VPN service is running
     * and at least one package is configured). Emits false when not active.
     */
    fun getMeshProxyActiveFlow(): StateFlow<Boolean>
}
```

**Import to add at top of `MeshrabiyaApi.kt`** (after package line, with existing imports):
```kotlin
import kotlinx.coroutines.flow.StateFlow
```
> Verify first: `grep_search` for `import kotlinx.coroutines.flow.StateFlow` in `MeshrabiyaApi.kt` — if already present, skip.

---

### C-3 — `DtoModels.kt` (SMALL — direct edit)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt`  
**Location:** Lines 78–117

#### Change 1: Add field to `NetworkInfoDto`

**BEFORE (lines 77–92):**
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
    val clearnetGateways: Int
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
    val nonMeshSsid: String? = null,
    val nonMeshIpAddress: String? = null,
    val nonMeshHasInternet: Boolean? = null,
    val torGateways: Int,
    val clearnetGateways: Int,
    val meshProxyActive: Boolean = false,
)
```

#### Change 2: Update `toDto()` extension

**BEFORE (lines 94–107):**
```kotlin
fun NetworkInfo.toDto(
    nonMeshSsid: String? = null,
    nonMeshIpAddress: String? = null,
    nonMeshHasInternet: Boolean? = null
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
    clearnetGateways
)
```

**AFTER:**
```kotlin
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
```

---

### C-4 — `MeshrabiyaApiImpl.kt` (LARGE — manual apply)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Pattern:** Follows `setTorGatewayEnabled()` at line 1247 exactly — use it as a template.

Add the following three methods as a block, after `setTorGatewayEnabled()`'s closing `}` (approximately line 1297).

**Required imports** (add with existing imports if not present):
```kotlin
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

**Code to insert:**
```kotlin
    // === MESH PROXY APPS (Phase 2) ===

    private val _meshProxyActiveFlow = MutableStateFlow(false)

    override fun getMeshProxyActiveFlow(): StateFlow<Boolean> = _meshProxyActiveFlow

    override suspend fun setMeshProxyApps(packageNames: Set<String>) {
        val context = appContext ?: throw IllegalStateException("App context not provided")
        context.dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)] = packageNames
        }
        Log.i(TAG, "[MESH_PROXY] Saved ${packageNames.size} proxy app packages")
    }

    override suspend fun getMeshProxyApps(): Set<String> {
        val context = appContext ?: return emptySet()
        val prefs = context.dataStore.data.first()
        return prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)]
            ?: emptySet()
    }
```

**Anchor for insertion** — BEFORE:
```kotlin
    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
```

**AFTER (insert the block above immediately before this line):**
```kotlin
    // === MESH PROXY APPS (Phase 2) ===

    private val _meshProxyActiveFlow = MutableStateFlow(false)

    override fun getMeshProxyActiveFlow(): StateFlow<Boolean> = _meshProxyActiveFlow

    override suspend fun setMeshProxyApps(packageNames: Set<String>) {
        val context = appContext ?: throw IllegalStateException("App context not provided")
        context.dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)] = packageNames
        }
        Log.i(TAG, "[MESH_PROXY] Saved ${packageNames.size} proxy app packages")
    }

    override suspend fun getMeshProxyApps(): Set<String> {
        val context = appContext ?: return emptySet()
        val prefs = context.dataStore.data.first()
        return prefs[stringSetPreferencesKey(MeshrabiyaConstants.KEY_MESH_PROXY_APP_PACKAGES)]
            ?: emptySet()
    }

    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
```

> **Note:** `_meshProxyActiveFlow` will be wired to the actual `MeshProxyVpnService` running state in Phase 2. For now it defaults `false` and is the observable required by the API contract.

---

### C-5 — New File: `MeshProxyAppManagerActivity.kt`

**File to create:** `/Users/dreadstar/workspace/orbot-android/app/src/main/java/org/torproject/android/ui/MeshProxyAppManagerActivity.kt`

**Architecture:** Mirrors `AppManagerActivity.kt` (373 lines) but:
- Uses Meshrabiya's `DataStore` via `getMeshProxyApps()` / `setMeshProxyApps()` instead of Orbot's `SharedPreferences`
- Calls `meshrabiyaApi.setMeshProxyApps()` on save instead of `saveAppSettings()`
- Package list comes from `PackageManager.getInstalledApplications()` (same as `TorifiedApp.getApps()`)

**Full new file content:**
```kotlin
package org.torproject.android.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.torproject.android.R
import org.torproject.android.service.util.Prefs
import com.ustadmobile.meshrabiya.api.MeshrabiyaApi

/**
 * Activity for selecting which apps have their traffic proxied through this node's
 * internet connection on behalf of remote mesh peers.
 *
 * Phase 2 feature — mirrors AppManagerActivity pattern.
 */
class MeshProxyAppManagerActivity : AppCompatActivity() {

    private lateinit var meshrabiyaApi: MeshrabiyaApi
    private lateinit var listView: ListView
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_app_manager)  // Reuse existing layout

        supportActionBar?.apply {
            title = getString(R.string.mesh_proxy_apps_title)
            setDisplayHomeAsUpEnabled(true)
        }

        meshrabiyaApi = (applicationContext as? MeshrabiyaApiProvider)?.meshrabiyaApi
            ?: run { finish(); return }

        listView = findViewById(R.id.lvApps)

        lifecycleScope.launch {
            loadApps()
        }
    }

    private suspend fun loadApps() {
        // Load persisted selection
        val savedPackages = meshrabiyaApi.getMeshProxyApps()
        selectedPackages.clear()
        selectedPackages.addAll(savedPackages)

        // Load installed apps on IO thread
        val apps = withContext(Dispatchers.IO) {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != packageName }  // Exclude self
                .sortedBy { it.loadLabel(packageManager).toString() }
        }

        val adapter = MeshProxyAppAdapter(this@MeshProxyAppManagerActivity, apps, selectedPackages)
        listView.adapter = adapter
    }

    private fun saveAndFinish() {
        lifecycleScope.launch {
            meshrabiyaApi.setMeshProxyApps(selectedPackages.toSet())
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                saveAndFinish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        saveAndFinish()
    }
}
```

> **Note:** `MeshProxyAppAdapter` is a simple `ArrayAdapter<ApplicationInfo>` subclass — implement similar to Orbot's `TorifiedApp` list adapter. `MeshrabiyaApiProvider` is the interface/cast pattern used in the app module to access the API — verify the actual access pattern in use (check `OrbotActivity` or `MainActivity` for the pattern).

---

### C-6 — New File: `MeshProxyVpnService.kt` (STUB)

**File to create:** `/Users/dreadstar/workspace/orbot-android/app/src/main/java/org/torproject/android/service/MeshProxyVpnService.kt`

**Phase 2 stub only** — this is a `VpnService` subclass that will intercept outbound traffic from configured apps and forward it via the mesh-selected clearnet gateway. Full implementation is Phase 2 scope.

```kotlin
package org.torproject.android.service

import android.net.VpnService
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * STUB — Phase 2: Mesh Proxy VPN Service
 *
 * When running, intercepts outbound traffic from apps in getMeshProxyApps() and
 * forwards it through the best available clearnet gateway node in the mesh.
 *
 * Full implementation pending Phase 2 design.
 */
class MeshProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "MeshProxyVpnService"

        fun start(context: android.content.Context) {
            val intent = Intent(context, MeshProxyVpnService::class.java)
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, MeshProxyVpnService::class.java)
            context.stopService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MeshProxyVpnService started — Phase 2 stub, no traffic interception yet")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MeshProxyVpnService stopped")
    }
}
```

**AndroidManifest.xml addition required** (in `app/src/main/AndroidManifest.xml`):
```xml
<service
    android:name=".service.MeshProxyVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

---

### C-7 — UI Entry Point (LARGE — manual apply)

The "Proxy Apps" button needs to be added to the mesh settings UI. Identify the correct fragment/activity for the "More" or mesh settings tab, then add:

```kotlin
// In the relevant settings fragment's onViewCreated or onClick handler:
val proxyAppsButton = view.findViewById<Button>(R.id.btnMeshProxyApps)
proxyAppsButton.setOnClickListener {
    startActivity(Intent(requireContext(), MeshProxyAppManagerActivity::class.java))
}
```

And in the corresponding layout XML:
```xml
<Button
    android:id="@+id/btnMeshProxyApps"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/mesh_proxy_apps_title"
    style="@style/Widget.AppCompat.Button.Borderless" />
```

**String resource** to add to `app/src/main/res/values/strings.xml`:
```xml
<string name="mesh_proxy_apps_title">Mesh Proxy Apps</string>
```

> **Identify the correct fragment** by searching for the existing "Choose Apps" / `AppManagerActivity` launch point — the new button goes in the same section.

---

## SECTION 5 — IMPLEMENTATION ORDER

Apply changes in this order to minimize compile errors:

```
1.  L-10a:  MmcpOriginatorMessage.kt         (SMALL — 4 hunks, direct edit)
2.  L-10b:  OriginatingMessageManager.kt      (LARGE — manual, constructor)
3.  L-10c:  OriginatingMessageManager.kt      (LARGE — manual, makeOriginatingMessage)
4.  L-10d:  OriginatingMessageManager.kt      (LARGE — manual, NodeTopologyInfo construction)
5.  L-10e:  VirtualNode.kt                    (LARGE — manual, OriginatingMessageManager construction)
6.  Gap B:  OrbotConstants.kt                 (SMALL — direct edit)
7.  C-1:    MeshrabiyaConstants.kt            (SMALL — direct edit)
8.  C-2:    MeshrabiyaApi.kt                  (SMALL — direct edit)
9.  C-3:    DtoModels.kt                      (SMALL — 2 hunks, direct edit)
10. C-4:    MeshrabiyaApiImpl.kt              (LARGE — manual insert before setTorGatewayEnabled)
11. C-5:    MeshProxyAppManagerActivity.kt    (NEW FILE — create)
12. C-6:    MeshProxyVpnService.kt            (NEW FILE — create stub)
13. C-7:    UI entry point                    (LARGE — manual, after identifying fragment)

Build after step 5 to catch serialization issues.
Build after step 9 to catch API contract errors.
Final build after step 13.
```

---

## SECTION 6 — VERIFICATION CHECKLIST

After all changes are applied:

- [ ] `./gradlew :lib-meshrabiya:compileDebugKotlin` — no errors
- [ ] `./gradlew assembleDebug` — clean build
- [ ] Two-phone test: originator message from Phone 1 contains non-zero `internetSignalStrengthDbm` when Phone 1 has internet WiFi
- [ ] Verify `NodeTopologyInfo` for Phone 1 on Phone 2 shows correct signal values
- [ ] `calculateGatewaySuitability()` returns different scores for nodes with and without signal data
- [ ] Orbot all-traffic VPN mode: mesh UDP reachable (not routed through Tor) — verify with `adb logcat` mesh packet logs
- [ ] Gap C: `MeshProxyAppManagerActivity` launches from settings, saves/loads selections
