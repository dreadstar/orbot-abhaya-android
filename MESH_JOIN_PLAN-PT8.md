# MESH JOIN PLAN - PART 8: ORGANIC MESH MERGE & MULTI-HOP FORWARDING

**Status:** ✅ Architecture Validated | ⚠️ Implementation Required  
**Dependencies:** PT1-PT7 (QR Join Infrastructure)  
**Priority:** P0 - Critical for mesh merging functionality

---

## Executive Summary

This part implements **organic autonomous mesh merging** - enabling multiple isolated mesh groups to autonomously converge into a single mesh network through gossip propagation. Unlike coordinator-based approaches, this design has no central control, no quorums, and no synchronized execution.

### API Architecture: Join vs. Merge

**Two Separate API Functions** (see PT6 for full documentation):

1. **`joinMesh(jsonQrData, callback)`**
   - **Use Case:** Join a mesh from DISCONNECTED state
   - **Behavior:** Does NOT broadcast merge announcement
   - **UI:** "Join Mesh" button (enabled when DISCONNECTED)
   - **State Check:** Works from DISCONNECTED or CONNECTED

2. **`mergeMesh(jsonQrData, callback)`**
   - **Use Case:** Merge current mesh with another mesh
   - **Behavior:** ALWAYS broadcasts merge announcement first
   - **UI:** "Merge Mesh" button (enabled when CONNECTED)
   - **State Check:** REQUIRES CONNECTED (fails if DISCONNECTED)

**Why Separate Functions?**
- Clearer user intent (Join vs. Merge)
- Prevents accidental merge announcements when just joining
- Simpler state management (button enable/disable)
- Explicit error handling (mergeMesh fails if DISCONNECTED)

### Key Architectural Decisions

**Separation of Concerns:**
- **Mesh Join** = User-initiated (QR scan) OR announcement-triggered (gossip)
- **Hotspot Recovery** = SEPARATE automatic failsafe (see PT2)
- **Mesh Merge** = Gossip-based convergence (this document)

**Why Organic > Coordinator:**
- ✅ No single point of failure
- ✅ Simpler implementation (~70% less code)
- ✅ Graceful degradation (both meshes remain functional on partial failure)
- ✅ Matches industrial solutions (Cisco, Aruba, Meraki)
- ✅ Eventual consistency (devices converge organically)

---

## Critical Discovery: Multi-Hop Forwarding

### Current State: Commented Out

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 702-722

Multi-hop MESH_ROUTER forwarding logic **ALREADY EXISTS** but is **COMMENTED OUT**. This prevents gossip messages from propagating beyond direct neighbors (1-hop), making mesh-wide announcements impossible.

**Impact:**
```
Current (1-hop only):
Device A → broadcasts → Neighbors (B, C) → STOP

Needed for Merge (multi-hop):
Device A → broadcasts → B → forwards → C → forwards → D → ...
```

**Without multi-hop forwarding, mesh merging CANNOT work across large meshes.**

---

## Implementation Checklist

### P0 - Critical (Week 1)

**These changes enable basic organic mesh merging:**

- [ ] **1. Uncomment Multi-Hop Forwarding** (VirtualNode.kt Lines 702-722)
  - Add TTL check to prevent infinite forwarding
  - Verify EmergentRoleManager and MeshRole imports
  - Test multi-hop propagation with 3+ hop network
  
- [ ] **2. Create MeshMergeAnnouncementMessage** (new file)
  - Message type for merge announcements
  - TTL, hopCount, timestamp fields
  - Extends MeshEcosystemMessage
  
- [ ] **3. Add Merge Constants** (MeshrabiyaConstants.kt)
  - TTL defaults, jitter ranges, delays
  - Replace hardcoded timeouts in PT6
  
- [ ] **4. Implement MeshConfigStorage** (new file)
  - SharedPreferences for SSID/password/timestamp
  - Idempotent config checking
  
- [ ] **5. Add Merge Logic** (MeshEcosystemListener.kt)
  - onMergeAnnouncementReceived handler
  - Gossip rebroadcast with deduplication
  - Idempotent join decision

### P1 - High (Week 2)

**These improve production readiness:**

- [ ] **6. Update mergeMesh() API** (MeshrabiyaApiImpl.kt - see PT6)
  - Verify CONNECTED state (fail if DISCONNECTED)
  - Broadcast merge announcement to current mesh
  - Wait 5 seconds for multi-hop propagation
  - WiFi scanning for target mesh hotspots
  - Best hotspot selection logic
  - Idempotent check (no-op if already on target mesh)
  
- [ ] **7. Error Handling & Retry**
  - WiFi connection failure recovery
  - Progress tracking/logging
  - User feedback on merge status

### P2 - Medium (Week 3)

**These add robustness:**

- [ ] **8. Hotspot Recovery Integration**
  - Separate concern from merge (see PT2)
  - Neighbor-based backoff
  - Auto-reconnect on majority switch

---

## Code Changes Required

### Change 1: Uncomment Multi-Hop Forwarding (CRITICAL - P0)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 702-722  
**Action:** Uncomment entire block + add TTL check

**Before (COMMENTED OUT):**
```kotlin
if(toAddr == ADDR_BROADCAST) {
    // val broadcastId = computeBroadcastId(packet)
    // val now = System.currentTimeMillis()
    // val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
    // if (prev == null) {
    //     val meshRoles = emergentRoleManager.getCurrentMeshRoles()
    //     if (meshRoles.contains(MeshRole.MESH_ROUTER)) {
    //         logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=MESH_ROUTER)")
    //         originatingMessageManager.neighbors().filter {
    //             it.first != fromLastHop && it.first != packet.header.fromAddr
    //         }.forEach {
    //             logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
    //             it.second.receivedFromSocket.send(...)
    //         }
    //     }
    // }
}
```

**After (UNCOMMENTED + TTL CHECK):**
```kotlin
if(toAddr == ADDR_BROADCAST) {
    val broadcastId = computeBroadcastId(packet)
    val now = System.currentTimeMillis()
    val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
    if (prev == null) {
        // NEW: Check TTL before forwarding (prevent infinite loops)
        if (packet.header.maxHops > 0) {
            val meshRoles = emergentRoleManager.getCurrentMeshRoles()
            if (meshRoles.contains(MeshRole.MESH_ROUTER)) {
                logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=MESH_ROUTER, hops remaining: ${packet.header.maxHops})")
                originatingMessageManager.neighbors().filter {
                    it.first != fromLastHop && it.first != packet.header.fromAddr
                }.forEach {
                    logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
                    it.second.receivedFromSocket.send(
                        nextHopAddress = it.second.lastHopRealInetAddr,
                        nextHopPort = it.second.lastHopRealPort,
                        virtualPacket = packet,
                    )
                }
            } else {
                logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER, not forwarding")
            }
        } else {
            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId TTL exhausted (maxHops=0), not forwarding")
        }
    }
}
```

**Verification Checklist:**
- [x] `emergentRoleManager` property exists (Line 210 of VirtualNode.kt)
- [x] `getCurrentMeshRoles()` returns `Set<MeshRole>`
- [x] `MeshRole.MESH_ROUTER` enum exists (package: `com.ustadmobile.meshrabiya.vnet`)
- [x] `originatingMessageManager.neighbors()` returns `List<Pair<Int, OriginatingMessage>>`
- [ ] **NEW:** TTL check added (`packet.header.maxHops > 0`)
- [x] `packet.updateLastHopAddrAndIncrementHopCountInData()` decrements maxHops (Line 700)

**Testing Requirements:**
- Verify broadcast reaches all nodes in 3+ hop network
- Confirm MESH_ROUTER nodes forward, non-MESH_ROUTER nodes drop
- Verify deduplication prevents loops (same broadcastId not reforwarded)
- Confirm TTL prevents infinite propagation

---

### Change 2: Add MeshMergeAnnouncementMessage (P0)

**File:** Create `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshMergeAnnouncementMessage.kt`

```kotlin
package com.ustadmobile.meshrabiya.service

import kotlinx.serialization.Serializable

/**
 * Message broadcast across mesh to announce merge to new mesh configuration.
 * 
 * Unlike coordinator-based approaches, this message does NOT require:
 * - Acknowledgment from all devices
 * - Quorum checking
 * - Synchronized execution timestamps
 * 
 * Each device independently decides whether to join based on idempotent config comparison.
 * 
 * @param targetSsid SSID pattern for target mesh (e.g., "meshr-*" for mesh-wide discovery)
 * @param targetPassword Shared password for target mesh (e.g., "meshtest12")
 * @param targetPort UDP port for mesh communication
 * @param announcerId Virtual address of device that initiated merge (for tracking only)
 * @param messageId UUID for deduplication
 * @param ttl Time-to-live (hops remaining before message dies) - decremented by each forwarder
 * @param hopCount Number of hops message has traveled (incremented by each forwarder)
 * @param timestamp Milliseconds since epoch when announcement was created
 */
@Serializable
data class MeshMergeAnnouncementMessage(
    val targetSsid: String,
    val targetPassword: String,
    val targetPort: Int,
    val announcerId: Int,
    val messageId: String,
    val ttl: Int = MeshrabiyaConstants.MERGE_MESSAGE_TTL_DEFAULT,  // Default: 5 hops
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) : MeshEcosystemMessage("MeshMergeAnnouncement")
```

**Integration Point:**
- Sent by `MeshrabiyaApi.joinMesh()` BEFORE device attempts WiFi connection
- Received by `MeshEcosystemListener.onMergeAnnouncementReceived()`
- Rebroadcast by MESH_ROUTER nodes (via multi-hop forwarding)

---

### Change 3: Add Constants to MeshrabiyaConstants.kt (P0)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt`

**Add after existing constants (~line 120):**

```kotlin
// ========================================
// MESH MERGE CONSTANTS
// ========================================

/**
 * Default TTL for merge announcement messages (max hops before message dies).
 * 5 hops should cover most small-to-medium mesh networks (<50 nodes).
 */
const val MERGE_MESSAGE_TTL_DEFAULT = 5

/**
 * Minimum jitter delay before rebroadcasting merge announcement (milliseconds).
 * Prevents broadcast storms by randomizing rebroadcast timing.
 */
const val MERGE_REBROADCAST_JITTER_MIN_MS = 100L

/**
 * Maximum jitter delay before rebroadcasting merge announcement (milliseconds).
 * Rebroadcast delay = Random(100ms, 500ms)
 */
const val MERGE_REBROADCAST_JITTER_MAX_MS = 500L

/**
 * Base delay for hotspot recovery (milliseconds).
 * Actual delay = BASE_DELAY / max(1, neighborCount)
 * 
 * Example:
 * - 0 neighbors: 30s delay (30000 / 1 = 30000)
 * - 1 neighbor: 30s delay (30000 / 1 = 30000)
 * - 2 neighbors: 15s delay (30000 / 2 = 15000)
 * - 5 neighbors: 6s delay (30000 / 5 = 6000)
 * 
 * This prevents multiple devices from starting hotspots simultaneously.
 */
const val HOTSPOT_RECOVERY_BASE_DELAY_MS = 30_000L

/**
 * Timeout for WiFi network scanning (milliseconds).
 * Used when searching for available mesh hotspots during join.
 */
const val WIFI_SCAN_TIMEOUT_MS = 10_000L
```

**Update PT6 References:**
- Replace hardcoded `30000L` with `MeshrabiyaConstants.HOTSPOT_RECOVERY_BASE_DELAY_MS`
- Replace hardcoded timeout values with appropriate constants

---

### Change 4: Add Mesh Config Storage (P0)

**File:** Create `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshConfigStorage.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores mesh configuration (SSID, password, port) in SharedPreferences for:
 * - Idempotent merge decision (only join if config differs)
 * - Automatic reconnection after app restart
 * - Hotspot recovery with correct credentials
 * 
 * This is the "source of truth" for what mesh the device believes it's part of.
 */
data class MeshConfig(
    val ssid: String,
    val password: String,
    val port: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if this config differs from another (for idempotent join decisions).
     * Ignores timestamp - only compares SSID, password, port.
     */
    fun differFrom(other: MeshConfig?): Boolean {
        if (other == null) return true
        return ssid != other.ssid || password != other.password || port != other.port
    }
}

/**
 * Manages persistent storage of mesh configuration.
 * 
 * Thread-safe: All operations use SharedPreferences which handles synchronization.
 */
class MeshConfigStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "mesh_config", 
        Context.MODE_PRIVATE
    )
    
    /**
     * Retrieve stored mesh configuration.
     * 
     * @return MeshConfig if valid config exists, null otherwise
     */
    fun getStoredMeshConfig(): MeshConfig? {
        val ssid = prefs.getString("ssid", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        val port = prefs.getInt("port", 0)
        val timestamp = prefs.getLong("timestamp", 0L)
        
        // Validate port (must be non-zero)
        if (port <= 0) return null
        
        return MeshConfig(ssid, password, port, timestamp)
    }
    
    /**
     * Store new mesh configuration.
     * Overwrites any existing configuration.
     * 
     * @param config New mesh configuration to store
     */
    fun storeNewMeshConfig(config: MeshConfig) {
        prefs.edit()
            .putString("ssid", config.ssid)
            .putString("password", config.password)
            .putInt("port", config.port)
            .putLong("timestamp", config.timestamp)
            .apply()  // Async write
    }
    
    /**
     * Clear stored mesh configuration.
     * Used when leaving mesh or performing factory reset.
     */
    fun clearMeshConfig() {
        prefs.edit()
            .remove("ssid")
            .remove("password")
            .remove("port")
            .remove("timestamp")
            .apply()
    }
}
```

**Integration Points:**
- Instantiate in `AndroidVirtualNode` constructor
- Use in `joinMesh()` to store new config
- Use in `MeshEcosystemListener.onMergeAnnouncementReceived()` for idempotent checking
- Use in hotspot recovery to start hotspot with correct credentials

---

### Change 5: Add Merge Logic to MeshEcosystemListener (P0)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshEcosystemListener.kt`

**Add after existing message handlers (~line 150):**

```kotlin
// ========================================
// MESH MERGE ANNOUNCEMENT HANDLING
// ========================================

/**
 * Tracks seen merge message IDs to prevent rebroadcast loops.
 * Thread-safe concurrent set.
 */
private val seenMergeMessages = ConcurrentHashMap.newKeySet<String>()

/**
 * Handle received merge announcement from another device.
 * 
 * This implements the core organic merge logic:
 * 1. Check if config is different (idempotent)
 * 2. If different, update stored config and attempt join
 * 3. Rebroadcast to neighbors (with deduplication)
 * 
 * NO coordination, NO quorum, NO synchronized execution.
 * Each device makes independent decision.
 * 
 * @param message Merge announcement from another device
 */
suspend fun onMergeAnnouncementReceived(message: MeshMergeAnnouncementMessage) {
    logger(Log.INFO, "$logPrefix Merge announcement received from ${message.announcerId}, messageId=${message.messageId}, ttl=${message.ttl}, hopCount=${message.hopCount}")
    
    // ========================================
    // STEP 1: IDEMPOTENT CHECK
    // ========================================
    val storedConfig = meshConfigStorage.getStoredMeshConfig()
    val newConfig = MeshConfig(
        ssid = message.targetSsid, 
        password = message.targetPassword, 
        port = message.targetPort,
        timestamp = message.timestamp
    )
    
    // Only join if config is DIFFERENT from current
    if (newConfig.differFrom(storedConfig)) {
        logger(Log.INFO, "$logPrefix Config differs - joining new mesh: ${message.targetSsid}")
        
        // Store new config
        meshConfigStorage.storeNewMeshConfig(newConfig)
        
        // ========================================
        // STEP 2: ATTEMPT JOIN (AUTONOMOUS)
        // ========================================
        // This is an independent decision - no coordination with other devices
        try {
            tryJoinMesh(newConfig)
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix Failed to join mesh: ${e.message}", e)
            // Don't rethrow - we still want to rebroadcast announcement
        }
    } else {
        logger(Log.DEBUG, "$logPrefix Merge announcement ignored - already on target mesh")
    }
    
    // ========================================
    // STEP 3: REBROADCAST (ALWAYS, even if we didn't join)
    // ========================================
    rebroadcastAnnouncement(message)
}

/**
 * Rebroadcast merge announcement to neighbors (gossip propagation).
 * 
 * Uses:
 * - Deduplication (seen message tracking)
 * - TTL checking (prevent infinite propagation)
 * - Random jitter (prevent broadcast storms)
 * 
 * MESH_ROUTER nodes will forward this via multi-hop logic in VirtualNode.kt.
 * 
 * @param message Original merge announcement
 */
private suspend fun rebroadcastAnnouncement(message: MeshMergeAnnouncementMessage) {
    // Check deduplication
    if (seenMergeMessages.contains(message.messageId)) {
        logger(Log.VERBOSE, "$logPrefix Merge announcement ${message.messageId} already seen, not rebroadcasting")
        return
    }
    
    // Check TTL
    if (message.ttl <= 0) {
        logger(Log.VERBOSE, "$logPrefix Merge announcement ${message.messageId} TTL exhausted, not rebroadcasting")
        return
    }
    
    // Mark as seen
    seenMergeMessages.add(message.messageId)
    
    // Random jitter prevents broadcast storms
    delay(Random.nextLong(
        MeshrabiyaConstants.MERGE_REBROADCAST_JITTER_MIN_MS,
        MeshrabiyaConstants.MERGE_REBROADCAST_JITTER_MAX_MS
    ))
    
    // Create rebroadcast message (decremented TTL, incremented hopCount)
    val rebroadcast = message.copy(
        ttl = message.ttl - 1,
        hopCount = message.hopCount + 1
    )
    
    logger(Log.INFO, "$logPrefix Rebroadcasting merge announcement ${message.messageId}, new ttl=${rebroadcast.ttl}, hopCount=${rebroadcast.hopCount}")
    
    // Broadcast via MeshGossipService
    // MESH_ROUTER nodes will forward this to their neighbors (via uncommenting Change 1)
    meshGossipService.broadcastMessage(rebroadcast.toBytes())
}

/**
 * Attempt to join mesh with given configuration.
 * 
 * Uses mesh-wide discovery (scans for any "meshr-*" hotspot).
 * 
 * @param config Target mesh configuration
 */
private suspend fun tryJoinMesh(config: MeshConfig) {
    logger(Log.INFO, "$logPrefix Attempting to join mesh: ${config.ssid}")
    
    // ========================================
    // STEP 1: SCAN FOR MESH HOTSPOTS
    // ========================================
    val scanResults = meshrabiyaWifiManager.scanForHotspots(
        ssidPattern = "meshr-*",
        timeout = MeshrabiyaConstants.WIFI_SCAN_TIMEOUT_MS
    )
    
    if (scanResults.isEmpty()) {
        logger(Log.WARN, "$logPrefix No mesh hotspots found - will retry or use hotspot recovery")
        // Don't throw - hotspot recovery (PT2) will handle this
        return
    }
    
    // ========================================
    // STEP 2: SELECT BEST HOTSPOT
    // ========================================
    // Sort by signal strength (strongest first)
    val bestHotspot = scanResults.maxByOrNull { it.signalStrength }
    
    if (bestHotspot == null) {
        logger(Log.WARN, "$logPrefix No suitable hotspot found")
        return
    }
    
    logger(Log.INFO, "$logPrefix Connecting to mesh hotspot: ${bestHotspot.ssid} (signal: ${bestHotspot.signalStrength})")
    
    // ========================================
    // STEP 3: CONNECT AS STATION
    // ========================================
    virtualNode.connectAsStation(WifiConnectConfig(
        nodeVirtualAddr = virtualNode.addressAsInt,
        ssid = bestHotspot.ssid,
        passphrase = config.password,
        port = config.port,
        bssid = bestHotspot.bssid,
        band = bestHotspot.band
    ))
    
    logger(Log.INFO, "$logPrefix Successfully connected to mesh: ${bestHotspot.ssid}")
}
```

**Dependencies:**
- Requires `meshConfigStorage` property in MeshEcosystemListener
- Requires `meshrabiyaWifiManager` for WiFi scanning
- Requires `virtualNode` for `connectAsStation()`
- Requires uncommenting Change 1 for multi-hop rebroadcast

---

### Change 6: Update joinMesh() API to Broadcast Announcement (P1)

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt`

**Modify existing `joinMesh()` implementation to:**

**Before joining target mesh, broadcast announcement to current mesh:**

```kotlin
override fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
    android.util.Log.d("MeshrabiyaApi", "joinMesh called with QR data: $jsonQrData")
    
    nodeScope.launch {
        try {
            // ========================================
            // STEP 1: PARSE QR CODE DATA
            // ========================================
            val qrData = Json.decodeFromString<MeshJoinQrData>(jsonQrData)
            
            if (qrData.type != "mesh_join") {
                throw IllegalArgumentException("Invalid QR code type: ${qrData.type}")
            }
            
            // ========================================
            // STEP 2: BROADCAST MERGE ANNOUNCEMENT
            //         (if currently on a mesh)
            // ========================================
            val currentStatus = getMeshStatus()
            if (currentStatus == MeshStateDto.CONNECTED || currentStatus == MeshStateDto.CONNECTING) {
                android.util.Log.d("MeshrabiyaApi", "Broadcasting merge announcement to current mesh before joining")
                
                val announcement = MeshMergeAnnouncementMessage(
                    targetSsid = qrData.ssidPattern,  // "meshr-*"
                    targetPassword = qrData.password,
                    targetPort = virtualNode.port,
                    announcerId = virtualNode.addressAsInt,
                    messageId = UUID.randomUUID().toString(),
                    ttl = MeshrabiyaConstants.MERGE_MESSAGE_TTL_DEFAULT,
                    hopCount = 0,
                    timestamp = System.currentTimeMillis()
                )
                
                // Broadcast to current mesh
                meshGossipService.broadcastMessage(announcement.toBytes())
                
                // Give gossip time to propagate (3-5 seconds for multi-hop)
                delay(5000)
                
                android.util.Log.d("MeshrabiyaApi", "Merge announcement broadcast complete")
            }
            
            // ========================================
            // STEP 3: PROCEED WITH JOIN
            //         (existing logic from PT6)
            // ========================================
            // ... existing joinMesh() implementation ...
            
            callback(Result.success(Unit))
            
        } catch (e: Exception) {
            android.util.Log.e("MeshrabiyaApi", "Failed to join mesh", e)
            callback(Result.failure(e))
        }
    }
}
```

**Key Changes:**
1. **Broadcasts merge announcement** to current mesh BEFORE joining target mesh
2. **Waits 5 seconds** for gossip propagation (allows multi-hop rebroadcast)
3. **Then proceeds** with existing join logic (WiFi scan + connect)

**Result:**
- All devices in current mesh receive announcement
- MESH_ROUTER nodes forward announcement to their neighbors
- Each device independently decides whether to join (idempotent check)
- Entire mesh converges to target mesh organically

---

## Organic Merge Flow Diagram

```
┌──────────────────────────────────────────────────────────┐
│ Device A: Scans QR code from Mesh B                     │
│ QR Data: {"password":"meshtest12", "ssidPattern":"meshr-*"} │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────┐
│ Device A: Calls joinMesh(qrData)                        │
│ - Checks current mesh status                            │
│ - IF CONNECTED: Broadcasts MeshMergeAnnouncement        │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────┐
│ MeshGossipService: Broadcasts to all direct neighbors   │
│ - maxHops = 5 (TTL)                                     │
│ - Message contains target SSID, password, port          │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────┐
│ MESH_ROUTER Nodes: Receive & Forward (Multi-Hop)        │
│ - VirtualNode.kt Lines 702-722 (UNCOMMENTED)            │
│ - Check TTL > 0                                         │
│ - Filter out sender and last hop                        │
│ - Forward to all other neighbors                        │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────┐
│ All Devices: Receive MeshMergeAnnouncement              │
│ - MeshEcosystemListener.onMergeAnnouncementReceived()  │
└───────────────────┬──────────────────────────────────────┘
                    │
        ┌───────────┴──────────┐
        │                      │
        ▼                      ▼
┌─────────────────────┐  ┌───────────────────────┐
│ Config DIFFERS      │  │ Config SAME           │
│ (new mesh)          │  │ (already on target)   │
└──────┬──────────────┘  └───────┬───────────────┘
       │                          │
       ▼                          ▼
┌─────────────────────┐  ┌───────────────────────┐
│ 1. Store new config │  │ Log: Already on mesh  │
│ 2. Scan for hotspots│  └───────┬───────────────┘
│ 3. Connect to best  │          │
└──────┬──────────────┘          │
       │                          │
       └──────────┬───────────────┘
                  │
                  ▼
┌──────────────────────────────────────────────────────────┐
│ All Devices: Rebroadcast announcement (if TTL > 0)       │
│ - Add to seenMessages set (deduplication)               │
│ - Random jitter (100-500ms)                             │
│ - Decrement TTL, increment hopCount                     │
│ - Broadcast via MeshGossipService                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────┐
│ RESULT: Entire mesh converges to target mesh            │
│ - No coordinator needed                                  │
│ - No quorum needed                                       │
│ - Graceful degradation on partial failure                │
│ - EmergentRoleManager optimizes topology over time      │
└──────────────────────────────────────────────────────────┘
```

---

## Testing Strategy

### Unit Tests

**Test 1: Multi-Hop Forwarding**
- Network topology: A → B → C → D (4 hops)
- Send broadcast from A
- Verify all nodes receive message
- Verify MESH_ROUTER nodes forward, non-MESH_ROUTER nodes drop
- Verify TTL prevents infinite loops

**Test 2: Deduplication**
- Network topology: A ↔ B ↔ C (bidirectional)
- Send broadcast from A
- Verify each node receives message ONLY ONCE (despite multiple paths)
- Verify seenMessages set prevents rebroadcast loops

**Test 3: Idempotent Join**
- Device receives MeshMergeAnnouncement
- Config matches stored config → no join attempted
- Config differs → join attempted
- Second announcement with same config → no second join

**Test 4: Gossip Propagation**
- 6-node mesh: A → B → C, D → E → F (two groups)
- Device C scans QR from Group 2
- Verify all 6 devices receive announcement within 10 seconds
- Verify all converge to same mesh

### Integration Tests

**Test 5: Merge Two Meshes (E2E)**
- Setup: Mesh A (3 devices), Mesh B (3 devices)
- Action: Device from Mesh A scans QR from Mesh B
- Expected:
  - All Mesh A devices receive announcement within 10s
  - All Mesh A devices connect to Mesh B hotspots
  - Single merged mesh with 6 devices
  - Topology stabilizes within 20s

**Test 6: Partial Failure Resilience**
- Setup: 5-device mesh, 2 devices have dead batteries during merge
- Action: Merge initiated
- Expected:
  - 3 active devices successfully merge
  - 2 dead devices remain on old mesh (graceful degradation)
  - No crash, no deadlock
  - When dead devices reactivate, they can rejoin via stored config

**Test 7: Concurrent Merge Attempts**
- Setup: 2 devices scan different QR codes simultaneously
- Action: Both broadcast merge announcements
- Expected:
  - Both announcements propagate (no collision)
  - Devices converge to whichever config they receive first
  - Idempotent logic prevents thrashing
  - Final state: Single mesh (whichever announcement "won")

---

## Integration with Existing Plan Parts

### PT1 Updates Required

**Add cross-reference to PT8 in Executive Summary:**

```markdown
### See Also

- **PT8: Organic Mesh Merge** - Enables merging multiple isolated mesh groups
  - Multi-hop forwarding (CRITICAL - must be enabled first)
  - Gossip-based merge announcements
  - Idempotent config management
```

### PT2 Updates Required

**Clarify separation of concerns:**

```markdown
**Note:** Hotspot recovery (PT2) is SEPARATE from mesh merging (PT8).
- **Hotspot Recovery** = Automatic failsafe when no localhotspot running
- **Mesh Merge** = Gossip-based convergence via user-initiated or announcement-triggered join
```

### PT6 Updates Required

**Update `joinMesh()` API documentation:**

```markdown
This method:
1. **Broadcasts merge announcement** to current mesh (if connected)
2. **Waits for gossip propagation** (5 seconds for multi-hop)
3. **Scans for target mesh hotspots** (mesh-wide discovery)
4. **Connects to strongest hotspot** (signal strength based)

See PT8 for merge announcement logic and multi-hop forwarding requirements.
```

---

## Success Criteria

**Week 1 (P0 Complete):**
- [ ] Multi-hop forwarding uncommented and tested (3+ hop network)
- [ ] MeshMergeAnnouncementMessage created and serializing correctly
- [ ] Constants added to MeshrabiyaConstants.kt
- [ ] MeshConfigStorage storing/retrieving configs correctly
- [ ] MeshEcosystemListener handling merge announcements
- [ ] **Test:** 2-mesh merge completes within 20 seconds

**Week 2 (P1 Complete):**
- [ ] joinMesh() broadcasting announcements before join
- [ ] WiFi scanning finding target mesh hotspots
- [ ] Best hotspot selection working (signal strength)
- [ ] Error handling and retry logic tested
- [ ] **Test:** 3-mesh merge with one failed device (graceful degradation)

**Week 3 (P2 Complete):**
- [ ] Hotspot recovery integrated (separate concern)
- [ ] Neighbor-based backoff preventing simultaneous hotspots
- [ ] Auto-reconnect on majority switch working
- [ ] Progress tracking/logging complete
- [ ] **Test:** 10-device mesh with dynamic topology changes

---

## Known Limitations & Future Work

**Current Limitations:**

1. **No rollback** - Once merge announcement sent, can't abort
   - Mitigation: Idempotent logic prevents incorrect joins
   - Future: Add "cancel merge" announcement

2. **WiFi disconnection gap** - 2-8 seconds of lost mesh connectivity during join
   - Mitigation: Concurrent AP+STA devices maintain connection (Android 13+)
   - Future: Bridge device support (PT2, P3)

3. **No merge progress UI** - User doesn't see merge status
   - Mitigation: Logging tracks merge progress
   - Future: Toast notifications or progress dialog

**Future Enhancements (P3):**

- Bridge device support for Android 13+ (maintain dual connections)
- Partition detection and auto-healing
- Merge analytics (success rate, convergence time)
- Multi-group merge (>2 meshes merging simultaneously)

---

## References

**Related Documents:**
- **Research Validation:** See `MESH_GROUP_MERGING_RESEARCH_FINDINGS.md` for:
  - Industry standards analysis (IEEE 802.11s, gossip protocols)
  - Alternative strategies and why they were rejected
  - Risk assessment and success rate estimates
  
- **Architecture Decisions:** See PT1 for mesh-wide discovery rationale

**Code Locations:**
- Multi-hop forwarding: `VirtualNode.kt` Lines 702-722
- EmergentRoleManager: `EmergentRoleManager.kt` (MESH_ROUTER assignment)
- MeshGossipService: `MeshGossipService.kt` Line 190-241
- OriginatingMessageManager: `OriginatingMessageManager.kt` (topology discovery)

---

**End of PT8**
