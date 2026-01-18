# MESH JOIN PLAN - PART 3: MESH TOPOLOGY & ROLE SYSTEM

## Mesh Architecture Overview

### Current Architecture: Flat Peer-to-Peer with Role-Based Routing

The Meshrabiya mesh network is **NOT hierarchical** - there is no parent/child relationship between nodes. Instead:

✅ **Flat topology** - All nodes are peers  
✅ **Role-based routing** - Nodes advertise capabilities (ROUTER, GATEWAY, STORAGE, COMPUTE)  
✅ **Intelligent discovery** - Broadcast originating messages every 3 seconds  
✅ **Metric-driven selection** - Clients choose best gateway based on centrality, fitness, hop count  

---

## Topology Discovery System

### Originating Message Manager

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/ext/OriginatingMessageManager.kt`

**Purpose:** Every node broadcasts its presence, roles, and metrics to neighbors every 3 seconds.

**Lines 159-183: Message Creation**
```kotlin
private fun makeOriginatingMessage(): OriginatingMessage {
    val originatorAddr = localNodeAddress
    val originatorSeq = _originatingSequence++
    
    return OriginatingMessage(
        originatorAddr = originatorAddr,
        sequenceNum = originatorSeq,
        roles = localNodeCapabilities()?.roles?.toList() ?: emptyList(),  // ← Includes MESH_ROUTER
        messageId = nextOriginatingMessageId++,
        sentTime = timestampProvider(),
        neighborCount = neighbors.size,
        batteryLevel = localNodeCapabilities()?.batteryLevel ?: 0,
        isCharging = localNodeCapabilities()?.isCharging ?: false,
        networkType = localNodeCapabilities()?.networkType ?: NetworkType.NONE,
        centralityScore = localNodeCapabilities()?.centralityScore ?: 0f,
        fitness = localNodeCapabilities()?.normalizedFitness ?: 0f,
    )
}
```

**Lines 185-215: Broadcasting**
```kotlin
private val sendOriginatingMessageRunnable = Runnable {
    val originatingMessage = makeOriginatingMessage()
    
    logger(Log.VERBOSE, "$logPrefix sending originating message " +
           "messageId=${originatingMessage.messageId} " +
           "sentTime=${originatingMessage.sentTime} " +
           "neighbors=${neighbors.size}")
    
    val packet = originatingMessage.toVirtualPacket(
        toAddr = ADDR_BROADCAST,        // Broadcast to all
        fromAddr = localNodeAddress,
        lastHopAddr = localNodeAddress,
        hopCount = 1,
    )
    
    // Send to all direct neighbors
    neighbors.forEach {
        try {
            lastOriginatorMessage.receivedFromSocket.send(
                nextHopAddress = lastOriginatorMessage.lastHopRealInetAddr,
                nextHopPort = lastOriginatorMessage.lastHopRealPort,
                virtualPacket = packet,
            )
        } catch (e: IOException) {
            logger(Log.ERROR, "$logPrefix error sending originating message", e)
        }
    }
}
```

**Scheduling (Line 195):**
```kotlin
val originatingMessageFuture = scheduledExecutor.scheduleAtFixedRate(
    sendOriginatingMessageRunnable,
    3000,        // Initial delay: 3 seconds
    3000,        // Interval: 3 seconds
    TimeUnit.MILLISECONDS
)
```

**Key Points:**
- Every node broadcasts every 3 seconds
- Messages include full node state (roles, battery, centrality, fitness)
- Neighbors forward messages to their neighbors (flooding algorithm)
- TTL prevents infinite loops (hopCount limit)

### Topology Map Storage

**Lines 62-89: Enhanced Topology Tracking**
```kotlin
// Enhanced to store complete NodeTopologyInfo (roles, metrics) instead of just neighbors
private val _topologyMapInfo: MutableMap<Int, NodeTopologyInfo> = mutableMapOf()

// Expose as Flow for observers (e.g., GatewaySelector, RoleManager)
private val _topologyMapFlow = MutableStateFlow<Map<Int, NodeTopologyInfo>>(emptyMap())
val topologyMapFlow: StateFlow<Map<Int, NodeTopologyInfo>> = _topologyMapFlow.asStateFlow()

/**
 * Get complete topology map with node information
 */
fun getTopologyMapInfo(): Map<Int, NodeTopologyInfo> = _topologyMapInfo

/**
 * Find nodes with specific role
 */
fun getNodesWithRole(role: MeshRole): List<NodeTopologyInfo> {
    return _topologyMapInfo.filter { it.value.hasRole(role) }.values.toList()
}

/**
 * Find gateway nodes (TOR or CLEARNET)
 */
fun getGatewayNodes(): List<NodeTopologyInfo> {
    return _topologyMapInfo.filter { it.value.isGatewayNode() }.values.toList()
}

/**
 * Check if node is stale (hasn't been heard from recently)
 */
fun NodeTopologyInfo.isStale(timeoutMs: Long): Boolean {
    return System.currentTimeMillis() - lastSeenTime > timeoutMs
}
```

**NodeTopologyInfo Data Structure:**

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/ext/NodeTopologyInfo.kt`

```kotlin
data class NodeTopologyInfo(
    val nodeAddress: Int,              // Virtual address
    val roles: Set<MeshRole>,          // Advertised roles
    val lastSeenTime: Long,            // Timestamp of last message
    val hopCount: Int,                 // Hops from this node
    val batteryLevel: Int,             // Battery percentage
    val isCharging: Boolean,           // Charging state
    val networkType: NetworkType,      // WIFI, CELLULAR, ETHERNET, etc.
    val centralityScore: Float,        // BFS centrality measure
    val fitness: Float,                // Normalized fitness (0.0-1.0)
    val sequenceNum: Int,              // Message sequence number
) {
    fun hasRole(role: MeshRole): Boolean = role in roles
    
    fun isGatewayNode(): Boolean = 
        hasRole(MeshRole.TOR_GATEWAY) || 
        hasRole(MeshRole.CLEARNET_GATEWAY) ||
        hasRole(MeshRole.I2P_GATEWAY)
}
```

---

## Role System Deep Dive

### Complete Role Enumeration

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt`

```kotlin
enum class MeshRole {
    /**
     * Base role for all mesh participants
     */
    MESH_PARTICIPANT,
    
    /**
     * Node offering distributed storage capacity
     * Requirements: >1MB storage available, thermal state OK, fitness >0.4
     */
    STORAGE_NODE,
    
    /**
     * Node offering compute resources for distributed tasks
     * Requirements: >30% CPU available, thermal state OK, battery >30% or charging
     */
    COMPUTE_NODE,
    
    /**
     * Node routing mesh traffic between networks
     * Requirements: Centrality >3.0, fitness >0.6, concurrent AP+STA support
     * ← THIS IS THE KEY ROLE FOR HOTSPOT DEVICES
     */
    MESH_ROUTER,
    
    /**
     * Node sharing Tor gateway access
     * Requirements: Stable connection, fitness >0.8, Tor service running
     */
    TOR_GATEWAY,
    
    /**
     * Node sharing clearnet Internet gateway access
     * Requirements: Stable connection, fitness >0.8, clearnet available
     */
    CLEARNET_GATEWAY,
    
    /**
     * Node sharing I2P gateway access
     * Requirements: Stable connection, fitness >0.8, I2P service running
     */
    I2P_GATEWAY
}
```

### Role Assignment Algorithm

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

**Lines 234-340: Core Assignment Logic** (VERIFIED)
```kotlin
private fun calculateTargetRoles(
    node: NodeCapabilitySnapshot,
    mesh: MeshIntelligence
): Set<MeshRole> {
    val roles = mutableSetOf<MeshRole>()
    val userPreferences = _preferredRoles.value
    
    // Everyone gets base participation role
    roles.add(MeshRole.MESH_PARTICIPANT)
    
    // Calculate normalized fitness score (0.0-1.0)
    val fitness = calculateNormalizedFitness(node)
    
    // ==============================
    // GATEWAY ROLES
    // ==============================
    // Requirements:
    // - User has enabled the role
    // - Device has stable connection
    // - High fitness (>0.8)
    // - Mesh needs more gateways
    if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
        if (MeshRole.TOR_GATEWAY in userPreferences) {
            roles.add(MeshRole.TOR_GATEWAY)
        }
        if (MeshRole.CLEARNET_GATEWAY in userPreferences) {
            roles.add(MeshRole.CLEARNET_GATEWAY)
        }
        if (MeshRole.I2P_GATEWAY in userPreferences) {
            roles.add(MeshRole.I2P_GATEWAY)
        }
    }
    
    // ==============================
    // STORAGE NODE ROLE
    // ==============================
    // Requirements:
    // - User has enabled storage sharing
    // - At least 1MB storage available
    // - Medium fitness (>0.4)
    // - Mesh needs more storage
    // - Thermal state is OK (not throttling)
    if (MeshRole.STORAGE_NODE in userPreferences &&
        node.storageOffered > 1_000_000L &&
        fitness > 0.4 && 
        mesh.needsMoreStorage &&
        node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL)) {
        roles.add(MeshRole.STORAGE_NODE)
    }
    
    // ==============================
    // COMPUTE NODE ROLE
    // ==============================
    // Requirements:
    // - User has enabled compute sharing
    // - At least 30% CPU available
    // - Thermal state is OK
    // - Battery >30% or charging
    // - Mesh needs more compute
    if (MeshRole.COMPUTE_NODE in userPreferences &&
        node.availableCPU > 0.3f && 
        node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL) && 
        (node.isCharging || node.batteryLevel > 30) &&
        mesh.needsMoreCompute) {
        roles.add(MeshRole.COMPUTE_NODE)
    }
    
    // ==============================
    // MESH ROUTER ROLE
    // ==============================
    // THIS IS THE CRITICAL ROLE FOR HOTSPOT DEVICES
    // Requirements:
    // - High centrality (>3.0) - well-connected in mesh topology
    // - Good fitness (>0.6) - device is capable
    // - Concurrent AP+STA support - can be hotspot AND station simultaneously
    //
    // Note: This role is NOT user-configurable - it's automatically
    // assigned based on device capability and network position
    val centralityResult = calculateBFSCentrality()
    if (fitness > 0.6 && 
        centralityResult.centralityScore > 3.0f && 
        concurrentApStationSupported) {  // ← CRITICAL CHECK
        roles.add(MeshRole.MESH_ROUTER)
    }
    
    return roles
}
```

**Key Insight:** MESH_ROUTER role is **automatically assigned** based on:
1. Device hardware capability (concurrent AP+STA support)
2. Network position (centrality score >3.0)
3. Device health (fitness >0.6)

It is **NOT user-configurable** like gateway roles.

### Fitness Score Calculation

**Lines 122-141:**
```kotlin
internal fun calculateNormalizedFitness(node: NodeCapabilitySnapshot): Float {
    // Battery component (30% weight)
    val batteryScore = when {
        node.isCharging -> 1.0f           // Charging = best
        node.batteryLevel > 70 -> 0.9f    // >70% = excellent
        node.batteryLevel > 30 -> 0.6f    // >30% = acceptable
        else -> 0.3f                       // <30% = poor
    }
    
    // Thermal component (20% weight)
    val thermalScore = when (node.thermalState) {
        ThermalState.COOL -> 1.0f
        ThermalState.WARM -> 0.8f
        ThermalState.HOT -> 0.5f
        ThermalState.THROTTLING -> 0.2f   // CPU throttled
        ThermalState.CRITICAL -> 0.1f     // Overheating
    }
    
    // Weighted combination
    return (batteryScore * 0.3f +           // 30% battery
            thermalScore * 0.2f +           // 20% thermal
            node.networkQuality * 0.3f +    // 30% network quality
            node.stability * 0.2f)          // 20% stability
        .coerceIn(0.0f, 1.0f)
}
```

### Centrality Calculation (BFS-Based)

**Lines 143-180:**
```kotlin
/**
 * Calculate BFS (Breadth-First Search) centrality of this node in mesh topology
 * Returns: CentralityResult with score and reachable node count
 */
private fun calculateBFSCentrality(): CentralityResult {
    val topology = virtualNode.originatingMessageManager.getTopologyMapInfo()
    val myAddress = virtualNode.addressAsInt
    
    if (topology.isEmpty()) {
        return CentralityResult(centralityScore = 0.0f, reachableNodes = 0)
    }
    
    // Build adjacency map (who's connected to whom)
    val adjacencyMap = mutableMapOf<Int, MutableSet<Int>>()
    topology.forEach { (nodeAddr, nodeInfo) ->
        adjacencyMap.getOrPut(nodeAddr) { mutableSetOf() }
        
        // Add edges based on hop count
        // If a node is 1 hop away, it's a direct neighbor
        if (nodeInfo.hopCount == 1) {
            adjacencyMap.getOrPut(myAddress) { mutableSetOf() }.add(nodeAddr)
            adjacencyMap.getOrPut(nodeAddr) { mutableSetOf() }.add(myAddress)
        }
    }
    
    // BFS from this node
    val visited = mutableSetOf<Int>()
    val queue = ArrayDeque<Pair<Int, Int>>()  // (nodeAddr, distance)
    queue.add(myAddress to 0)
    visited.add(myAddress)
    
    var totalDistance = 0
    var reachableNodes = 0
    
    while (queue.isNotEmpty()) {
        val (currentNode, distance) = queue.removeFirst()
        reachableNodes++
        totalDistance += distance
        
        // Visit neighbors
        adjacencyMap[currentNode]?.forEach { neighbor ->
            if (neighbor !in visited) {
                visited.add(neighbor)
                queue.add(neighbor to distance + 1)
            }
        }
    }
    
    // Centrality = 1 / average distance
    // Higher centrality = shorter average path to all nodes = more central position
    val avgDistance = if (reachableNodes > 1) {
        totalDistance.toFloat() / (reachableNodes - 1)
    } else {
        Float.MAX_VALUE
    }
    
    val centralityScore = if (avgDistance > 0) {
        1.0f / avgDistance * 10.0f  // Scale up for readability
    } else {
        0.0f
    }
    
    return CentralityResult(
        centralityScore = centralityScore,
        reachableNodes = reachableNodes
    )
}

data class CentralityResult(
    val centralityScore: Float,    // Higher = more central
    val reachableNodes: Int        // Number of nodes reachable via BFS
)
```

**Centrality Examples:**

| Position | Avg Distance | Centrality Score | Interpretation |
|----------|--------------|------------------|----------------|
| Hub (star center) | 1.0 | 10.0 | Ideal router |
| Well-connected | 1.5 | 6.67 | Good router |
| Peripheral | 3.0 | 3.33 | Marginal router |
| Edge node | 5.0 | 2.0 | Not suitable for routing |
| Isolated | ∞ | 0.0 | No routing capability |

**Threshold:** MESH_ROUTER role requires centrality >3.0, which means average distance <3.33 hops to all nodes.

---

## Graceful Role Transitions

### Transition Planning

**Lines 182-217:**
```kotlin
/**
 * Plan graceful transitions between role sets
 * Prevents service disruption by checking mesh state before removing roles
 */
private fun planGracefulTransitions(
    currentRoles: Set<MeshRole>, 
    targetRoles: Set<MeshRole>,
    userInitiated: Boolean = false
): RoleTransition {
    val toAdd = targetRoles - currentRoles
    val toRemove = currentRoles - targetRoles
    
    // If user explicitly toggled a role, respect their choice immediately
    val safeToRemove = if (userInitiated) {
        toRemove  // User wants it removed - do it now
    } else {
        // System-initiated transition - apply safety checks
        toRemove.filter { role ->
            when (role) {
                MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY -> {
                    // Only remove gateway role if other gateways exist
                    // Prevents leaving mesh without any gateway
                    val meshIntel = meshIntelligence.value
                    meshIntel.activeGateways > 1
                }
                
                MeshRole.MESH_ROUTER -> {
                    // Only remove router role if other routers exist nearby
                    val nearbyRouters = virtualNode.originatingMessageManager
                        .getNodesWithRole(MeshRole.MESH_ROUTER)
                        .count { !it.isStale(30_000L) && it.hopCount <= 2 }
                    nearbyRouters > 1
                }
                
                MeshRole.STORAGE_NODE, MeshRole.COMPUTE_NODE -> {
                    // Storage and compute can be removed freely
                    // Other nodes will pick up the slack
                    true
                }
                
                MeshRole.MESH_PARTICIPANT -> {
                    // Never remove base participation role
                    false
                }
            }
        }.toSet()
    }
    
    return RoleTransition(
        toAdd = toAdd,
        toRemove = safeToRemove
    )
}

data class RoleTransition(
    val toAdd: Set<MeshRole>,
    val toRemove: Set<MeshRole>
)
```

**Safety Logic Summary:**
- **Gateway removal:** Only if mesh has >1 gateway (prevent island)
- **Router removal:** Only if other routers nearby (prevent partition)
- **Storage/Compute removal:** Always safe (elastic resources)
- **Participant removal:** Never allowed (base role)

### Transition Execution

**Lines 219-250:**
```kotlin
/**
 * Execute role transition with proper lifecycle management
 */
private suspend fun executeRoleTransition(transition: RoleTransition) {
    // Remove roles first (clean shutdown)
    transition.toRemove.forEach { role ->
        logger(Log.INFO, "$logPrefix Removing role: $role")
        
        when (role) {
            MeshRole.TOR_GATEWAY -> {
                // Stop accepting Tor gateway requests
                stopTorGatewayService()
            }
            
            MeshRole.CLEARNET_GATEWAY -> {
                // Stop accepting clearnet gateway requests
                stopClearnetGatewayService()
            }
            
            MeshRole.MESH_ROUTER -> {
                // Announce router shutdown (give clients time to migrate)
                announceRouterShutdown()
                delay(10_000L)  // 10 second grace period
            }
            
            MeshRole.STORAGE_NODE -> {
                // Migrate stored data to other storage nodes
                migrateStorageData()
            }
            
            MeshRole.COMPUTE_NODE -> {
                // Finish running tasks before shutting down
                finishComputeTasks()
            }
            
            else -> {
                // No special cleanup needed
            }
        }
    }
    
    // Add roles second (start services)
    transition.toAdd.forEach { role ->
        logger(Log.INFO, "$logPrefix Adding role: $role")
        
        when (role) {
            MeshRole.TOR_GATEWAY -> {
                startTorGatewayService()
            }
            
            MeshRole.CLEARNET_GATEWAY -> {
                startClearnetGatewayService()
            }
            
            MeshRole.MESH_ROUTER -> {
                // Router role is activated by hotspot start
                // No separate service needed
                logger(Log.INFO, "$logPrefix MESH_ROUTER role activated (hotspot running)")
            }
            
            MeshRole.STORAGE_NODE -> {
                startStorageService()
            }
            
            MeshRole.COMPUTE_NODE -> {
                startComputeService()
            }
            
            else -> {
                // No special startup needed
            }
        }
    }
    
    // Update current roles
    _currentRoles.value = (_currentRoles.value - transition.toRemove) + transition.toAdd
    
    // Broadcast role change in next originating message
    // (Automatic - originating messages include current roles)
}
```

---

## Routing Mechanism

### No Parent/Child Hierarchy

The mesh uses **role-based discovery** rather than hierarchical routing:

**Example: Tor Gateway Selection**

**File:** `orbotservice/src/main/java/org/torproject/android/service/intelligent/GatewaySelector.kt`

```kotlin
/**
 * Select best Tor gateway from mesh topology
 */
fun selectTorGateway(): NodeTopologyInfo? {
    val topology = originatingMessageManager.getTopologyMapInfo()
    
    // Get all nodes advertising TOR_GATEWAY role
    val torGateways = topology.values
        .filter { it.hasRole(MeshRole.TOR_GATEWAY) }
        .filterNot { it.isStale(30_000L) }  // Not stale
    
    if (torGateways.isEmpty()) return null
    
    // Rank gateways by composite score
    val ranked = torGateways.sortedByDescending { gateway ->
        calculateGatewayScore(gateway)
    }
    
    return ranked.firstOrNull()
}

private fun calculateGatewayScore(gateway: NodeTopologyInfo): Float {
    // Composite score based on multiple factors
    val hopPenalty = 1.0f / (gateway.hopCount + 1)  // Prefer closer gateways
    val centralityBonus = gateway.centralityScore / 10.0f  // Prefer well-connected
    val fitnessBonus = gateway.fitness  // Prefer healthy devices
    val batteryPenalty = if (gateway.batteryLevel < 30 && !gateway.isCharging) 0.5f else 1.0f
    
    return hopPenalty * 0.4f + 
           centralityBonus * 0.3f + 
           fitnessBonus * 0.2f * 
           batteryPenalty * 0.1f
}
```

**Client Flow:**
1. Request service (e.g., "Connect to Tor")
2. Query topology: `getNodesWithRole(MeshRole.TOR_GATEWAY)`
3. Rank candidates by score (hops, centrality, fitness)
4. Connect to best gateway
5. If gateway fails, select next best

**No Single Point of Failure:** Multiple gateways can exist, clients dynamically select best one.

---

## Integration with User Preferences

**File:** `orbotservice/src/main/java/org/torproject/android/service/intelligent/IntelligentRoleManager.kt`

**Lines 30-50:**
```kotlin
// User's preferred roles (configurable in UI)
private val _preferredRoles = MutableStateFlow<Set<MeshRole>>(setOf(
    MeshRole.MESH_PARTICIPANT  // Everyone starts as participant
))

/**
 * Update user's role preferences
 * NOTE: MESH_ROUTER is NOT user-configurable - it's automatic
 */
fun setPreferredRoles(roles: Set<MeshRole>) {
    logger(Log.INFO, "User set preferred roles: $roles")
    
    // Ensure MESH_PARTICIPANT is always included
    _preferredRoles.value = roles + MeshRole.MESH_PARTICIPANT
    
    // Trigger immediate recalculation
    triggerRoleRecalculation()
}

/**
 * Get user's current role preferences
 */
fun getPreferredRoles(): Set<MeshRole> = _preferredRoles.value
```

**UI Integration Example:**

```kotlin
// In settings UI
val roleCheckboxes = mapOf(
    MeshRole.TOR_GATEWAY to checkboxTorGateway,
    MeshRole.CLEARNET_GATEWAY to checkboxClearnetGateway,
    MeshRole.STORAGE_NODE to checkboxStorage,
    MeshRole.COMPUTE_NODE to checkboxCompute,
    // Note: MESH_ROUTER and I2P_GATEWAY not shown (automatic)
)

roleCheckboxes.forEach { (role, checkbox) ->
    checkbox.setOnCheckedChangeListener { _, isChecked ->
        val currentPrefs = roleManager.getPreferredRoles().toMutableSet()
        if (isChecked) {
            currentPrefs.add(role)
        } else {
            currentPrefs.remove(role)
        }
        roleManager.setPreferredRoles(currentPrefs)
    }
}
```

---

## Summary

### Mesh Topology Characteristics

✅ **Flat peer-to-peer** (no parent/child hierarchy)  
✅ **Role-based routing** (gateway, router, storage, compute)  
✅ **Broadcast discovery** (originating messages every 3 seconds)  
✅ **Metric-driven selection** (centrality, fitness, hop count)  
✅ **Self-organizing** (roles assigned automatically based on capability)  
✅ **Fault-tolerant** (multiple gateways, graceful role transitions)  

### MESH_ROUTER Role

✅ **Automatically assigned** based on hardware + topology  
✅ **Requirements:** Centrality >3.0, fitness >0.6, concurrent AP+STA support  
✅ **Not user-configurable** (unlike gateway roles)  
✅ **Graceful removal** (only if other routers nearby)  
✅ **Activated by hotspot** (no separate service)  

### Role Transition Safety

✅ **Gateway removal:** Only if >1 gateway exists  
✅ **Router removal:** Only if other routers nearby  
✅ **10-second grace period** for client migration  
✅ **User overrides** respected immediately  

### Next Steps

See MESH_JOIN_PLAN-PT4.md for UI implementation details (layout, QR generation, camera scanning).
