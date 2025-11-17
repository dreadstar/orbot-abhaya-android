# Comprehensive Refactoring Plan: OriginatingMessageManager + EmergentRoleManager Integration

**Date:** November 16, 2025  
**Status:** FINAL REVISION - Using MmcpOriginatorMessage (Official Design)  
**Priority:** CRITICAL  
**Reference:** `OriginatingMessageManager_official.md` + Current `OriginatingMessageManager.kt`

---

## Executive Summary

This plan properly defines **MmcpOriginatorMessage** (from official design) with topology/centrality enhancements and integrates with **canonical EmergentRoleManager**:

1. ✅ **Define MmcpOriginatorMessage class** with official + enhanced fields
2. ✅ **Break circular dependency** using callback pattern
3. ✅ **Build topology map** from neighbor lists in messages
4. ✅ **Enable BFS centrality** calculation for emergent role assignment
5. ✅ **Maintain architectural separation** (EmergentRoleManager as pure consumer)

---

## Part 1: Analysis - Official vs Current Implementation

### Official OriginatingMessageManager Design

From `OriginatingMessageManager_official.md`, the canonical message creation is:

```kotlin
private fun makeOriginatingMessage(): MmcpOriginatorMessage {
    return MmcpOriginatorMessage(
        messageId = nextMmcpMessageId(),
        pingTimeSum = 0,
        connectConfig = getWifiState().connectConfig,
        sentTime = System.currentTimeMillis()
    )
}
```

**Key Official Fields:**
- `messageId`: Unique message identifier
- `sentTime`: Timestamp for freshness comparison
- `pingTimeSum`: Cumulative ping time across hops (updated as message propagates)
- `connectConfig`: WiFi connection configuration for neighbor discovery

**Official Behavior:**
- Broadcasts every 3 seconds to all direct neighbors
- Maintains routing table (`originatorMessages`) with best routes (most recent, lowest hop count)
- Lost node detection (10s timeout)
- Ping/pong mechanism for neighbor latency measurement

### Current Implementation Issues

Looking at `#file:OriginatingMessageManager.kt`:

1. **Line 111-120**: Tries to call `EmergentRoleManager.getInstance().calculateCentralityScore()` → **CIRCULAR DEPENDENCY**
2. **Line 119**: Calls `MmcpMessageFactory.createNodeAnnouncement()` → **WRONG - MmcpNodeAnnouncement is DEPRECATED**
3. **Line 268**: Same circular dependency in `makeOriginatingMessage()`
4. **Line 299**: References `MmcpNodeAnnouncement` type → **WRONG - DEPRECATED TYPE**
5. **Line 105**: Has `topologyMap` but never populated: `// topologyMap[...] = mmcpMessage.neighbors.toSet()`
6. **No MmcpOriginatorMessage class definition** - only used inline in official code

---

## Part 2: Proposed Solution


### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         VirtualNode                              │
│                         (Mediator)                               │
└───────────┬─────────────────────────────────────┬───────────────┘
            │                                     │
            ▼                                     ▼
┌───────────────────────────┐      ┌──────────────────────────────┐
│ OriginatingMessageManager │      │   EmergentRoleManager        │
│   (Enhanced Protocol)     │      │      (Consumer)              │
├───────────────────────────┤      ├──────────────────────────────┤
│ SENDS:                    │      │ READS:                       │
│ • MmcpOriginatorMessage   │      │ • topologyMap (via callback) │
│   [OFFICIAL FIELDS]       │      │                              │
│   - messageId             │      │ CALCULATES:                  │
│   - sentTime              │      │ • BFS centrality score       │
│   - pingTimeSum           │      │ • Network position metrics   │
│   - connectConfig         │      │                              │
│   [ENHANCED FIELDS]       │      │ PROVIDES:                    │
│   - neighbors: List<Int>  │      │ • Centrality score (callback)│
│   - centralityScore       │      │ • Role recommendations       │
│   - fitnessScore          │      │                              │
│   - meshRoles             │      │ DEPENDS ON:                  │
│                           │      │ • getTopologyMap callback    │
│ RECEIVES:                 │      │ • getCurrentNodeCapabilities │
│ • Builds topologyMap from │      │                              │
│   neighbor lists          │      └──────────────────────────────┘
│                           │                     ▲
│ PROVIDES:                 │                     │
│ • getTopologyMap()        │◄────────────────────┘
│ • State flow updates      │
│                           │
│ USES (via callbacks):     │
│ • getCentralityScore()    │◄────────────────────┐
│ • getMeshRoles()          │                     │
│ • getFitnessScore()       │                     │
└───────────────────────────┘                     │
                                                  │
                                          (from VirtualNode)
```

### Key Architectural Principles

1. **Proper MmcpOriginatorMessage Definition**: Define as a proper `MmcpMessage` subclass (not inline)
2. **Preserve Official Core**: All canonical fields (`messageId`, `sentTime`, `pingTimeSum`, `connectConfig`)
3. **Topology Enhancement**: Add `neighbors: List<Int>` to enable distributed topology building
4. **Centrality Distribution**: Add `centralityScore: Float` to propagate mesh intelligence
5. **Callback Pattern**: Break circular dependency via constructor injection
6. **Single Direction**: EmergentRoleManager reads from OriginatingMessageManager only
7. **Lazy Evaluation**: Centrality calculated on-demand when creating messages

---

## Part 3: Implementation Plan

### Phase 1: Define MmcpOriginatorMessage Class

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/MmcpOriginatorMessage.kt`

```kotlin
package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.MeshRole
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP Originating Message - broadcasts node presence and routing information.
 * 
 * This is the canonical message type from the official design, enhanced with
 * topology and centrality fields for distributed mesh intelligence.
 * 
 * Official fields (from OriginatingMessageManager_official.md):
 * - messageId: Unique identifier
 * - sentTime: Timestamp for message freshness
 * - pingTimeSum: Cumulative latency across hops
 * - connectConfig: WiFi connection configuration
 * 
 * Enhanced fields (for EmergentRoleManager integration):
 * - neighbors: Direct neighbor addresses for topology building
 * - centralityScore: BFS centrality from EmergentRoleManager
 * - fitnessScore: Node capability assessment (0.0-1.0)
 * - meshRoles: Currently assigned mesh roles
 */
class MmcpOriginatorMessage(
    messageId: Int,
    
    // === OFFICIAL FIELDS (from canonical design) ===
    val sentTime: Long,
    val pingTimeSum: Short = 0,
    val connectConfig: Any? = null,  // WiFi ConnectConfig (platform-specific)
    
    // === ENHANCED FIELDS (for topology/centrality) ===
    val neighbors: List<Int> = emptyList(),  // Direct neighbor virtual addresses
    val centralityScore: Float = 0f,         // BFS centrality score
    val fitnessScore: Float = 0f,            // Node fitness (0.0-1.0)
    val meshRoles: Set<MeshRole> = emptySet(), // Current mesh roles
    
) : MmcpMessage(WHAT_ORIGINATOR, messageId) {

    /**
     * Create updated message with incremented ping time (called at each hop).
     * This preserves the official behavior where pingTimeSum accumulates.
     */
    fun copyWithPingTimeIncrement(connectionPingTime: Long): MmcpOriginatorMessage {
        val newPingTimeSum = (pingTimeSum + connectionPingTime.toShort())
            .coerceIn(Short.MIN_VALUE, Short.MAX_VALUE)
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

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write official fields
        dos.writeLong(sentTime)
        dos.writeShort(pingTimeSum.toInt())
        
        // Write connectConfig (simplified - null for now)
        dos.writeBoolean(connectConfig != null)
        // TODO: Serialize connectConfig if present
        
        // Write enhanced fields
        dos.writeInt(neighbors.size)
        neighbors.forEach { dos.writeInt(it) }
        
        dos.writeFloat(centralityScore)
        dos.writeFloat(fitnessScore)
        
        dos.writeInt(meshRoles.size)
        meshRoles.forEach { dos.writeByte(it.ordinal) }
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpOriginatorMessage {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip 'what' byte
            
            val messageId = buffer.int
            val sentTime = buffer.long
            val pingTimeSum = buffer.short
            
            // Read connectConfig
            val hasConnectConfig = buffer.get() != 0.toByte()
            val connectConfig = if (hasConnectConfig) {
                // TODO: Deserialize connectConfig
                null
            } else null
            
            // Read enhanced fields
            val neighborCount = buffer.int
            val neighbors = List(neighborCount) { buffer.int }
            
            val centralityScore = buffer.float
            val fitnessScore = buffer.float
            
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

---

### Phase 2: Update MmcpMessage to Support WHAT_ORIGINATOR

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/MmcpMessage.kt`

Add to `fromBytes()` switch statement:

```kotlin
WHAT_ORIGINATOR -> MmcpOriginatorMessage.fromBytes(byteArray, offset, len)
```

**Verify constant exists:**
```kotlin
const val WHAT_ORIGINATOR = 7.toByte()  // Should already exist
```

---

### Phase 3: Update OriginatingMessageManager Constructor (Add Callbacks)

**File:** `OriginatingMessageManager.kt` (Line ~43)

**BEFORE:**
```kotlin
class OriginatingMessageManager(
    private val localNodeInetAddr: InetAddress,
    private val logger: MNetLogger,
    private val scheduledExecutor: ScheduledExecutorService,
    private val nextMmcpMessageId: () -> Int,
    private val getWifiState: () -> MeshrabiyaWifiState,
    private val getFitnessScore: () -> Int,
    private val getNodeRole: () -> Byte,
    private val pingTimeout: Int = 15_000,
    private val originatingMessageNodeLostThreshold: Int = 10000,
    lostNodeCheckInterval: Int = 1_000,
    private val betaLogger: BetaTestLogger? = null
)
```

**AFTER:**
```kotlin
class OriginatingMessageManager(
    private val localNodeInetAddr: InetAddress,
    private val logger: MNetLogger,
    private val scheduledExecutor: ScheduledExecutorService,
    private val nextMmcpMessageId: () -> Int,
    private val getWifiState: () -> MeshrabiyaWifiState,
    
    // === NEW: Callbacks to break circular dependency ===
    private val getCentralityScore: (() -> Float)? = null,
    private val getMeshRoles: (() -> Set<MeshRole>)? = null,
    private val getFitnessScore: (() -> Float)? = null,  // Changed from () -> Int
    
    // === EXISTING PARAMS ===
    private val pingTimeout: Int = 15_000,
    private val originatingMessageNodeLostThreshold: Int = 10000,
    lostNodeCheckInterval: Int = 1_000,
    private val betaLogger: BetaTestLogger? = null
)
```

---

### Phase 4: Replace makeOriginatingMessage() - Use Callbacks

**File:** `OriginatingMessageManager.kt` (Line ~264)

**BEFORE (WRONG - uses deprecated MmcpNodeAnnouncement):**
```kotlin
private fun makeOriginatingMessage(fitnessScore: Int, nodeRole: Byte): MmcpNodeAnnouncement {
    // Calculate centrality score using EmergentRoleManager
    val centralityScore = try {
        EmergentRoleManager.getInstance().calculateCentralityScore()
    } catch (e: Exception) {
        0f
    }
    
    // Get current mesh roles from EmergentRoleManager if available
    val meshRoles = (localNodeInetAddr as? AndroidVirtualNode)?.emergentRoleManager?.currentMeshRoles?.value 
        ?: setOf(com.ustadmobile.meshrabiya.vnet.MeshRole.MESH_PARTICIPANT)
    
    return MmcpMessageFactory.createNodeAnnouncement(
        messageId = nextMmcpMessageId(),
        nodeId = localNodeAddress.toString(),
        centralityScore = centralityScore,
        meshRoles = meshRoles
    )
}
```

**AFTER (CORRECT - uses canonical MmcpOriginatorMessage):**
```kotlin
private fun makeOriginatingMessage(): MmcpOriginatorMessage {
    // Get current direct neighbor addresses for topology building
    val neighborAddrs = originatorMessages
        .filter { it.value.hopCount == 1.toByte() }
        .keys
        .toList()
    
    // Use callbacks instead of direct EmergentRoleManager access
    val centralityScore = getCentralityScore?.invoke() ?: 0f
    val meshRoles = getMeshRoles?.invoke() ?: setOf(MeshRole.MESH_PARTICIPANT)
    val fitnessScore = getFitnessScore?.invoke() ?: 0f
    
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

---

### Phase 5: Update sendOriginatingMessageRunnable - Remove Direct Calls

**File:** `OriginatingMessageManager.kt` (Line ~108)

**BEFORE:**
```kotlin
private val sendOriginatingMessageRunnable = Runnable {
    try {
        val neighborAddrs = originatorMessages.keys.toList()
        // Calculate centrality score using EmergentRoleManager
        val centralityScore = try {
            EmergentRoleManager.getInstance().calculateCentralityScore()
        } catch (e: Exception) {
            0f
        }
        val originatingMessage = MmcpMessageFactory.createNodeAnnouncement(
            messageId = nextMmcpMessageId(),
            nodeId = localNodeAddress.toString(),
            centralityScore = centralityScore
        )
        logBeta(LogLevel.DEBUG, "Sending originating message: $originatingMessage")

        logger(
            priority = Log.VERBOSE,
            message = { "$logPrefix sending originating message messageId=${originatingMessage.messageId} timestamp=${originatingMessage.timestamp}" }
        )
```

**AFTER:**
```kotlin
private val sendOriginatingMessageRunnable = Runnable {
    try {
        val originatingMessage = makeOriginatingMessage()  // Now includes callbacks
        
        logBeta(LogLevel.DEBUG, "Sending originating message: " +
            "messageId=${originatingMessage.messageId}, " +
            "neighbors=${originatingMessage.neighbors.size}, " +
            "centrality=${originatingMessage.centralityScore}")

        logger(
            priority = Log.VERBOSE,
            message = { "$logPrefix sending originating message messageId=${originatingMessage.messageId} " +
                "sentTime=${originatingMessage.sentTime} neighbors=${originatingMessage.neighbors.size}" }
        )
```

---

### Phase 6: Update onReceiveOriginatingMessage() - Build Topology Map

**File:** `OriginatingMessageManager.kt` (Line ~296)

**BEFORE (WRONG - signature uses deprecated MmcpNodeAnnouncement):**
```kotlin
fun onReceiveOriginatingMessage(
    mmcpMessage: MmcpNodeAnnouncement,
    datagramPacket: DatagramPacket,
    datagramSocket: VirtualNodeDatagramSocket,
    virtualPacket: VirtualPacket,
): Boolean {
```

**AFTER (CORRECT - signature + topology building):**
```kotlin
fun onReceiveOriginatingMessage(
    mmcpMessage: MmcpOriginatorMessage,  // Changed type
    datagramPacket: DatagramPacket,
    datagramSocket: VirtualNodeDatagramSocket,
    virtualPacket: VirtualPacket,
): Boolean {
    assertNotClosed()
    
    logBeta(LogLevel.DEBUG, "Received originating message from " +
        "${virtualPacket.header.fromAddr.addressToDotNotation()}: " +
        "neighbors=${mmcpMessage.neighbors.size}, " +
        "centrality=${mmcpMessage.centralityScore}")

    // === OFFICIAL FRESHNESS CHECK (preserved from canonical design) ===
    val currentOriginatorMessage = originatorMessages[virtualPacket.header.fromAddr]
    val currentlyKnownSentTime = (currentOriginatorMessage?.originatorMessage?.sentTime ?: 0)
    val currentlyKnownHopCount = (currentOriginatorMessage?.hopCount ?: Byte.MAX_VALUE)
    
    val connectionPingTime = neighborPingTimes[virtualPacket.header.lastHopAddr]?.pingTime ?: 0
    
    val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
            || mmcpMessage.sentTime == currentlyKnownSentTime && virtualPacket.header.hopCount < currentlyKnownHopCount
    
    val isNewNeighbor = virtualPacket.header.hopCount == 1.toByte() &&
            !originatorMessages.containsKey(virtualPacket.header.fromAddr)

    // === UPDATE ROUTING TABLE (official logic) ===
    if(currentOriginatorMessage == null || isMoreRecentOrBetter) {
        originatorMessages[virtualPacket.header.fromAddr] = VirtualNode.LastOriginatorMessage(
            originatorMessage = mmcpMessage.copyWithPingTimeIncrement(connectionPingTime.toLong()),
            timeReceived = System.currentTimeMillis(),
            lastHopAddr = virtualPacket.header.lastHopAddr,
            hopCount = virtualPacket.header.hopCount,
            lastHopRealInetAddr = datagramPacket.address,
            receivedFromSocket = datagramSocket,
            lastHopRealPort = datagramPacket.port
        )
        
        // === NEW: BUILD TOPOLOGY MAP ===
        if (mmcpMessage.neighbors.isNotEmpty()) {
            topologyMap[virtualPacket.header.fromAddr] = mmcpMessage.neighbors.toSet()
            
            logger(
                Log.VERBOSE,
                message = { "$logPrefix updated topology: node ${virtualPacket.header.fromAddr.addressToDotNotation()} " +
                    "has ${mmcpMessage.neighbors.size} neighbors" }
            )
        }
        
        // === NEW: STORE NEIGHBOR METADATA ===
        if (virtualPacket.header.hopCount == 1.toByte()) {
            neighborFitnessInfo[virtualPacket.header.fromAddr] = Pair(
                (mmcpMessage.fitnessScore * 100).toInt(),
                0  // Reserved
            )
            neighborCentralityInfo[virtualPacket.header.fromAddr] = mmcpMessage.centralityScore
        }

        // === EMIT STATE UPDATE ===
        _state.value = OriginatingMessageState(
            pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
        )
    }

    // === TRIGGER IMMEDIATE REPLY FOR NEW NEIGHBORS (official behavior) ===
    if(isNewNeighbor) {
        scheduledExecutor.submit(sendOriginatingMessageRunnable)
    }

    return isMoreRecentOrBetter
}
```

---

### Phase 7: Update VirtualNode.LastOriginatorMessage Type

**File:** `VirtualNode.kt` (Line ~136)

**BEFORE (WRONG - uses deprecated MmcpNodeAnnouncement):**
```kotlin
data class LastOriginatorMessage(
    val originatorMessage: MmcpNodeAnnouncement,  // WRONG - deprecated type
    val timeReceived: Long,
    val lastHopAddr: Int,
    val hopCount: Byte,
    val lastHopRealInetAddr: InetAddress,
    val receivedFromSocket: VirtualNodeDatagramSocket,
    val lastHopRealPort: Int,
)
```

**AFTER (CORRECT - uses canonical MmcpOriginatorMessage):**
```kotlin
data class LastOriginatorMessage(
    val originatorMessage: MmcpOriginatorMessage,  // Correct type
    val timeReceived: Long,
    val lastHopAddr: Int,
    val hopCount: Byte,
    val lastHopRealInetAddr: InetAddress,
    val receivedFromSocket: VirtualNodeDatagramSocket,
    val lastHopRealPort: Int,
)
```

---

### Phase 8: Wire Callbacks in VirtualNode

**File:** `VirtualNode.kt` (Line ~152)

**BEFORE:**
```kotlin
protected open val originatingMessageManager = OriginatingMessageManager(
    localNodeInetAddr = address,
    logger = logger,
    scheduledExecutor = scheduledExecutor,
    nextMmcpMessageId = { nextMmcpMessageId() },
    getWifiState = { currentNodeState.wifiState },
    getFitnessScore = { getCurrentFitnessScore() },
    getNodeRole = { getCurrentNodeRole() }
)
```

**AFTER:**
```kotlin
// === STEP 1: Create EmergentRoleManager with topology callback ===
protected val emergentRoleManager: EmergentRoleManager by lazy {
    EmergentRoleManager(
        virtualNode = this,
        getTopologyMap = { originatingMessageManager.getTopologyMap() },
        getCurrentNodeCapabilities = { getCurrentNodeCapabilities() },
        logger = logger,
        betaLogger = betaLogger
    )
}

// === STEP 2: Create OriginatingMessageManager with EmergentRoleManager callbacks ===
protected open val originatingMessageManager = OriginatingMessageManager(
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
    lostNodeCheckInterval = 1_000,
    betaLogger = betaLogger
)
```

---

### Phase 9: Update EmergentRoleManager Constructor

**File:** `EmergentRoleManager.kt` (Line ~75)

**BEFORE:**
```kotlin
class EmergentRoleManager(
    private val virtualNode: VirtualNodeInterface,
    private val logger: MNetLogger,
    private val betaLogger: BetaTestLogger? = null
) {
    // Inside calculateBFSCentrality():
    val topologyMap = (virtualNode as VirtualNode).getOriginatingMessageManager().getTopologyMap()
```

**AFTER:**
```kotlin
class EmergentRoleManager(
    private val virtualNode: VirtualNodeInterface,
    private val getTopologyMap: () -> Map<Int, Set<Int>>,  // NEW: Callback
    private val getCurrentNodeCapabilities: () -> NodeCapabilitySnapshot,  // NEW: Callback
    private val logger: MNetLogger,
    private val betaLogger: BetaTestLogger? = null
) {
    // Inside calculateBFSCentrality():
    val topologyMap = getTopologyMap()  // Use callback
```

---

### Phase 10: Update OriginatingMessageState Type

**File:** `OriginatingMessageManager.kt` (Line ~595)

**BEFORE (WRONG - uses deprecated MmcpNodeAnnouncement):**
```kotlin
data class OriginatingMessageState(
    val pendingMessages: Map<Int, MmcpNodeAnnouncement> = emptyMap(),  // WRONG - deprecated type
)
```

**AFTER (CORRECT - uses canonical MmcpOriginatorMessage):**
```kotlin
data class OriginatingMessageState(
    val pendingMessages: Map<Int, MmcpOriginatorMessage> = emptyMap(),
)
```

---

## Part 4: Testing Strategy

### Unit Tests

#### Test 1: OriginatingMessageManager Callback Usage
```kotlin
@Test
fun testMakeOriginatingMessageUsesCallbacks() {
    var centralityCallbackInvoked = false
    var rolesCallbackInvoked = false
    var fitnessCallbackInvoked = false
    
    val manager = OriginatingMessageManager(
        // ... params ...
        getCentralityScore = { 
            centralityCallbackInvoked = true
            0.75f 
        },
        getMeshRoles = { 
            rolesCallbackInvoked = true
            setOf(MeshRole.MESH_ROUTER) 
        },
        getFitnessScore = { 
            fitnessCallbackInvoked = true
            0.85f 
        }
    )
    
    val message = manager.makeOriginatingMessage()
    
    assertTrue(centralityCallbackInvoked)
    assertTrue(rolesCallbackInvoked)
    assertTrue(fitnessCallbackInvoked)
    assertEquals(0.75f, message.centralityScore, 0.01f)
    assertEquals(setOf(MeshRole.MESH_ROUTER), message.meshRoles)
    assertEquals(0.85f, message.fitnessScore, 0.01f)
}
```

#### Test 2: Topology Map Building
```kotlin
@Test
fun testTopologyMapBuildingFromReceivedMessages() {
    val manager = OriginatingMessageManager(/* ... */)
    
    val originatorMessage = MmcpOriginatorMessage(
        messageId = 1,
        sentTime = System.currentTimeMillis(),
        pingTimeSum = 0,
        connectConfig = null,
        neighbors = listOf(192837465, 192837466, 192837467),  // 3 neighbors
        centralityScore = 0.5f,
        fitnessScore = 0.6f,
        meshRoles = setOf(MeshRole.MESH_PARTICIPANT)
    )
    
    val virtualPacket = VirtualPacket.createWithMmcpPayload(
        toAddr = ADDR_BROADCAST,
        fromAddr = 192837464,
        lastHopAddr = 192837463,
        hopCount = 2,
        mmcpMessage = originatorMessage
    )
    
    manager.onReceiveOriginatingMessage(
        mmcpMessage = originatorMessage,
        datagramPacket = /* mock */,
        datagramSocket = /* mock */,
        virtualPacket = virtualPacket
    )
    
    val topologyMap = manager.getTopologyMap()
    assertTrue(topologyMap.containsKey(192837464))
    assertEquals(setOf(192837465, 192837466, 192837467), topologyMap[192837464])
}
```

#### Test 3: EmergentRoleManager Centrality with Topology
```kotlin
@Test
fun testBFSCentralityWithTopologyCallback() {
    val mockTopology = mapOf(
        192837464 to setOf(192837465, 192837466),
        192837465 to setOf(192837464, 192837467),
        192837466 to setOf(192837464, 192837467),
        192837467 to setOf(192837465, 192837466)
    )
    
    val manager = EmergentRoleManager(
        virtualNode = mockVirtualNode,
        getTopologyMap = { mockTopology },
        getCurrentNodeCapabilities = { mockCapabilities },
        logger = mockLogger
    )
    
    val centralityResult = manager.calculateBFSCentrality()
    
    assertTrue(centralityResult.centralityScore > 0f)
    assertTrue(centralityResult.reachableNodes >= 3)
}
```

### Integration Tests

#### Test 4: End-to-End Topology Building
```kotlin
@Test
fun testEndToEndTopologyBuilding() {
    // Create 3 virtual nodes
    val node1 = AndroidVirtualNode(/* ... */)
    val node2 = AndroidVirtualNode(/* ... */)
    val node3 = AndroidVirtualNode(/* ... */)
    
    // Connect them in a triangle topology
    connectNodes(node1, node2)
    connectNodes(node2, node3)
    connectNodes(node3, node1)
    
    // Wait for originating messages to propagate
    delay(5000)
    
    // Verify topology maps are built
    val topology1 = node1.originatingMessageManager.getTopologyMap()
    val topology2 = node2.originatingMessageManager.getTopologyMap()
    val topology3 = node3.originatingMessageManager.getTopologyMap()
    
    // Each node should know about the other two nodes and their neighbors
    assertTrue(topology1.size >= 2)
    assertTrue(topology2.size >= 2)
    assertTrue(topology3.size >= 2)
    
    // Verify centrality scores are calculated
    val centrality1 = node1.emergentRoleManager.calculateCentralityScore()
    val centrality2 = node2.emergentRoleManager.calculateCentralityScore()
    val centrality3 = node3.emergentRoleManager.calculateCentralityScore()
    
    // All nodes should have similar centrality in triangle topology
    assertTrue(centrality1 > 0f)
    assertTrue(centrality2 > 0f)
    assertTrue(centrality3 > 0f)
}
```

---

## Part 6: Migration Path

### Step-by-Step Migration

1. ✅ **Create MmcpOriginatorMessage** (Phase 1)
2. ✅ **Add callbacks to OriginatingMessageManager constructor** (Phase 2)
3. ✅ **Update makeOriginatingMessage()** to use callbacks (Phase 3)
4. ✅ **Update sendOriginatingMessageRunnable** (Phase 4)
5. ✅ **Update onReceiveOriginatingMessage()** to build topology (Phase 5)
6. ✅ **Wire callbacks in VirtualNode** (Phase 6)
7. ✅ **Update EmergentRoleManager constructor** (Phase 7)
8. ⚠️ **Write unit tests** for each component (Phase 8)
9. ⚠️ **Write integration tests** for end-to-end flow (Phase 8)
10. ✅ **Remove singleton pattern** if desired (Optional)

### Backward Compatibility

**Optional Parameters:** All new callback parameters have default values (`null`), so existing code will compile but won't have topology/centrality features.

**Gradual Migration:** Can deploy with callbacks unset initially, then wire them up incrementally.

---

## Part 7: Success Criteria

### Compilation Success
- ✅ Zero compilation errors in `OriginatingMessageManager.kt`
- ✅ Zero compilation errors in `EmergentRoleManager.kt`
- ✅ No circular dependency warnings

### Functional Success
- ✅ Originating messages broadcast every 3s with neighbor lists
- ✅ Topology map populates from received node announcements
- ✅ Centrality scores calculated using topology map
- ✅ Centrality scores included in outgoing node announcements
- ✅ EmergentRoleManager assigns roles based on centrality thresholds

### Architectural Success
- ✅ EmergentRoleManager is pure consumer (no reverse dependency)
- ✅ VirtualNode mediates all dependencies via callbacks
- ✅ Official OriginatingMessageManager core functionality preserved
- ✅ Clean separation of concerns

---

## Part 8: Risk Assessment

### High Risk
- **Initialization Order:** EmergentRoleManager needs topology callback, but OriginatingMessageManager needs centrality callback
  - **Mitigation:** Use `lazy` initialization in VirtualNode

### Medium Risk
- **Message Size:** Adding neighbor lists increases message size
  - **Mitigation:** Limit neighbor list to direct neighbors only (hopCount == 1)
  
- **Topology Staleness:** Topology map may become stale if nodes leave
  - **Mitigation:** Existing lost node detection (10s timeout) handles this

### Low Risk
- **Callback Overhead:** Lambda invocations add minimal overhead
  - **Mitigation:** Callbacks invoked only every 3s (sendOriginatingMessageRunnable)

---

## Part 9: Future Enhancements

1. **Compression:** Compress neighbor lists for large meshes (>50 nodes)
2. **Incremental Updates:** Send only topology changes instead of full neighbor lists
3. **Multi-Hop Topology:** Extend topology map beyond direct neighbors (currently 1-hop)
4. **Gossip Protocol:** Use epidemic broadcast for topology dissemination in large meshes
5. **Centrality Caching:** Cache centrality scores with TTL to reduce recalculation

---

## Appendix A: Key File Locations

- **MmcpOriginatorMessage.kt**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/MmcpOriginatorMessage.kt`
- **OriginatingMessageManager.kt**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`
- **EmergentRoleManager.kt**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`
- **VirtualNode.kt**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`
- **MmcpMessage.kt**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/MmcpMessage.kt`

---

## Appendix B: Code Change Summary

| File | Lines Changed | Type |
|------|---------------|------|
| MmcpOriginatorMessage.kt | +230 | New file |
| MmcpMessage.kt | +1 | Modified (add fromBytes case) |
| OriginatingMessageManager.kt | ~80 | Modified (constructor, makeOriginatingMessage, onReceive) |
| EmergentRoleManager.kt | ~15 | Modified (constructor, topology access) |
| VirtualNode.kt | ~20 | Modified (wiring callbacks) |

**Total LOC Impact:** ~350 lines (230 new, 120 modified)

---

**END OF COMPREHENSIVE REFACTORING PLAN**
