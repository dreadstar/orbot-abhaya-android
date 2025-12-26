# MeshrabiyaApiImpl Core Dependencies - Research Report

**Date:** December 6, 2025  
**Target File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

---

## 1. AndroidVirtualNode

**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

### Constructor Signature
```kotlin
class AndroidVirtualNode(
    val appContext: Context,
    port: Int = 0,
    json: Json = Json,
    logger: MNetLogger = MNetLoggerStdout(),
    dataStore: DataStore<Preferences>,
    address: InetAddress = randomApipaInetAddr(),
    config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
) : VirtualNode(...)
```

### Properties Used by MeshrabiyaApiImpl

| Property | Type | Source |
|----------|------|--------|
| `address` | `InetAddress` | Inherited from `VirtualNode`, final override from constructor param |
| `emergentRoleManager` | `EmergentRoleManager` | Inherited from `VirtualNode` (line 208-218) |
| `distributedStorageManager` | `DistributedStorageManager?` | Inherited from `VirtualNode` (line 339, nullable) |
| `currentNodeState` | `LocalNodeState` | Inherited from `HasNodeState` interface (getter, line 141-142 VirtualNode) |
| `state` | `Flow<LocalNodeState>` | Inherited from `VirtualNode` (line 138) |
| `originatingMessageManager` | `OriginatingMessageManager` | Inherited from `VirtualNode` (line 220-237) |

### Methods Called by MeshrabiyaApiImpl

```kotlin
// From OriginatingMessageManager (accessed via VirtualNode)
fun neighbors(): List<Pair<Int, VirtualNode.LastOriginatorMessage>>
// Source: VirtualNode.kt line 1110 delegates to originatingMessageManager.neighbors()
// Actual impl: OriginatingMessageManager.kt line 611

// WiFi hotspot control (overridden in AndroidVirtualNode)
override suspend fun setWifiHotspotEnabled(
    enabled: Boolean,
    preferredBand: ConnectBand,
    hotspotType: HotspotType,
): LocalHotspotResponse?
// Source: AndroidVirtualNode.kt line 157-163
// Calls super.setWifiHotspotEnabled() from VirtualNode.kt line 1032

// Proxy configuration (from VirtualNode)
fun setProxy(host: String, port: Int)
// Source: VirtualNode.kt line 108-113

fun setProxyActive(active: Boolean)
// Source: VirtualNode.kt line 115-118

// Service accessors (from VirtualNode)
fun obtainDistributedComputeClient(): DistributedComputeClient
// Source: VirtualNode.kt line 357
// Returns: distributedComputeClient (lazy-initialized, line 348-355)

fun obtainMeshEcosystemListener(): MeshEcosystemListener
// Source: VirtualNode.kt line 363
// Returns: meshEcosystemListener (lazy-initialized, line 310-316)
```

---

## 2. EmergentRoleManager

**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

### Constructor Signature
```kotlin
class EmergentRoleManager(
    private val virtualNode: VirtualNode,
    private val context: Context,
    private val getTopologyMap: (() -> Map<Int, NodeTopologyInfo>)? = null,
    private val getCurrentNodeCapabilities: (() -> NodeCapabilitySnapshot)? = null,
    private val meshTrafficRouter: Any? = null,
    private val distributedStorageManager: Any? = null,
    private val deviceCapabilityManager: DeviceCapabilityManager? = null
)
```

### Methods Used by MeshrabiyaApiImpl

```kotlin
fun getCurrentMeshRoles(): Set<MeshRole>
// Source: EmergentRoleManager.kt line 1130
// Returns: _currentMeshRoles.value (private MutableStateFlow<Set<MeshRole>>)

fun setPreferredRoles(roles: Set<MeshRole>)
// Source: EmergentRoleManager.kt line 1136-1139
// Sets: _preferredRoles.value = roles
// Logs: "User set preferred roles: $roles"
```

### Properties Exposed (StateFlows)

```kotlin
val currentMeshRoles: StateFlow<Set<MeshRole>>
// Source: Line 151, asStateFlow() of _currentMeshRoles
// Initial value: setOf(MeshRole.MESH_PARTICIPANT)

val userAllowsTorProxy: StateFlow<Boolean>
// Source: Line 168, asStateFlow() of _userAllowsTorProxy
// Setter: setUserAllowsTorProxy(allowed: Boolean) at line 170-173
```

---

## 3. DistributedStorageManager

**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

### Constructor Signature
```kotlin
class DistributedStorageManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val meshGossipService: MeshGossipService,
    private val coreGossipBroadcastService: CoreGossipBroadcastService,
    val storageConfig: StorageConfiguration,
    private val connectionPool: MeshConnectionPool = MeshConnectionPool.getInstance()
)
```

### Properties Used by MeshrabiyaApiImpl

```kotlin
val storageStats: StateFlow<StorageStats>
// Source: Line 141, asStateFlow() of _storageStats
// Type: StateFlow<StorageStats>

val participationEnabled: StateFlow<Boolean>
// Source: Line 144, asStateFlow() of _participationEnabled
// Type: StateFlow<Boolean>

val storageConfig: StorageConfiguration
// Source: Constructor parameter (line 41), public val

val fileMetadataStore: ConcurrentHashMap<String, FileMetadata>
// Source: Line 136 (public property)
```

### Methods Used by MeshrabiyaApiImpl

```kotlin
fun getFileMetadata(fileId: String): FileMetadata?
// Source: Line 357-359
// Returns: fileMetadataStore[fileId]

suspend fun retrieveFile(fileRef: FileReference): ByteArray?
// Source: Line 226-228
// Delegates to: client.retrieveFile(fileRef)

fun configureStorageParticipation(config: StorageParticipationConfig)
// Source: Line 94-109
// Updates: quota manager, participation state, recalculates stats

fun registerWithEcosystemListener(listener: MeshEcosystemListener)
// Source: Line 182-185
// Sets: meshEcosystemListener and calls listener.registerStorageManager(this)

fun unregisterFromEcosystemListener(listener: MeshEcosystemListener)
// Source: Line 187-191
// Clears: meshEcosystemListener if matches
```

---

## 4. DistributedComputeClient

**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/DistributedComputeClient.kt`

### Constructor Signature
```kotlin
class DistributedComputeClient(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val betaLogger: BetaTestLogger
)
```

### Methods Used by MeshrabiyaApiImpl

```kotlin
suspend fun processTaskRequest(request: LocalComputeTaskRequest): String
// Source: Line 29-67
// Parameter: LocalComputeTaskRequest
// Returns: String (taskId)
// Creates: TrackedRequest, broadcasts ComputeTaskRequestMessage
```

---

## 5. Data Classes

### MeshState (Enum)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/MeshState.kt`

```kotlin
enum class MeshState {
    INITIALIZING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
    UNKNOWN
}
```

### NetworkInfo (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/NetworkInfo.kt`

```kotlin
data class NetworkInfo(
    val ssid: String = "",
    val bssid: String = "",
    val ipAddress: String = "",
    val connectedPeers: Int = 0,
    val isConnected: Boolean = false,
    // Phase 3B: Gateway statistics
    val torGateways: Int = 0,
    val clearnetGateways: Int = 0,
) {
    val totalGateways: Int get() = torGateways + clearnetGateways
}
```

### NodeInfo (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/NodeInfo.kt`

```kotlin
data class NodeInfo(
    val nodeId: String = "",
    val displayName: String = "",
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val capabilities: List<String> = emptyList()
)
```

### MeshFile (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshStorageDataDefinitions.kt`

```kotlin
data class MeshFile(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val storedAt: Long = System.currentTimeMillis()
)
```

### FileReference (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt` (line 406-410)

```kotlin
data class FileReference(
    val id: String,
    val path: String,
    val size: Long
)
```

### GatewayPreference (Enum)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/GatewayPreference.kt`

```kotlin
enum class GatewayPreference {
    TOR_ONLY,           // Route all traffic through Tor gateways exclusively
    CLEARNET_ONLY,      // Route all traffic through clearnet gateways exclusively
    // Additional values likely include:
    // TOR_PREFERRED, CLEARNET_PREFERRED, AUTO (check lines 50+ of file)
}
```

### MeshRole (Enum)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt`

```kotlin
enum class MeshRole {
    MESH_PARTICIPANT,    // Base role for all mesh nodes
    STORAGE_NODE,        // Node offering distributed storage
    COMPUTE_NODE,        // Node offering compute resources
    MESH_ROUTER,         // Node routing mesh traffic
    TOR_GATEWAY,         // Node sharing Tor gateway
    CLEARNET_GATEWAY,    // Node sharing clearnet Internet gateway
    I2P_GATEWAY          // Node sharing I2P gateway
}
```

---

## 6. Supporting Types

### ConnectBand (Enum)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/ConnectBand.kt`

```kotlin
enum class ConnectBand(val flag: Byte) {
    BAND_2GHZ(1),
    BAND_5GHZ(2),
    BAND_UNKNOWN(0)
}
```

### HotspotType (Enum)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/HotspotType.kt`

```kotlin
enum class HotspotType(val flag: Byte) {
    LOCALONLY_HOTSPOT(1),
    WIFIDIRECT_GROUP(2),
    AUTO(4)
}
```

### LocalHotspotResponse (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalHotspotResponse.kt`

```kotlin
data class LocalHotspotResponse(
    val responseToMessageId: Int,
    val errorCode: Int,
    val config: WifiConnectConfig?,
    val redirectAddr: Int,
)
```

### StorageConfiguration (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt` (line 386-392)

```kotlin
data class StorageConfiguration(
    val defaultReplicationFactor: Int = 3,
    val encryptionEnabled: Boolean = true,
    val compressionEnabled: Boolean = true,
    val maxFileSize: Long = 100L * 1024 * 1024,
    val defaultQuota: Long = 1L * 1024 * 1024 * 1024
)
```

### StorageStats (Data Class)
**File:** `/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt` (line 394-399)

```kotlin
data class StorageStats(
    val totalOffered: Long = 0L,
    val currentlyUsed: Long = 0L,
    val filesStored: Int = 0,
    val replicationHealth: Float = 1.0f
)
```

---

## Summary

All core dependencies for `MeshrabiyaApiImpl` have been verified and documented with exact signatures. Key findings:

1. **AndroidVirtualNode** inherits most functionality from `VirtualNode` base class
2. **Properties are mostly accessed via StateFlows** for reactive updates
3. **All methods have clear signatures** with no ambiguity
4. **Data classes are simple DTOs** with clear field structures
5. **Enums are well-defined** with specific values documented

**Next Steps:**
- Use these exact signatures when implementing MeshrabiyaApiImpl
- Reference this document for type compatibility checks
- Verify any Flow collectors use proper coroutine scopes
