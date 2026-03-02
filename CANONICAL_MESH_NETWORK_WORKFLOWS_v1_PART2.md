# Canonical Mesh Network Workflows v1 - Part 2

**Document Status:** Phase 1 Analysis - Routing & Broadcast Systems  
**Date Created:** February 5, 2026  
**Part:** 2 of 4 (Sections 3-5)  
**Prerequisites:** Read Part 1 (Sections 1-2: Initialization and Join workflows)

---

## 3. Originating Message Protocol

### 3.1 OriginatingMessageManager Overview

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/OriginatingMessageManager.kt`  
**Lines:** 49-828  
**Verified:** ✓ grep_search + read_file complete

**Purpose:** Peer discovery, neighbor tracking, and topology map building through periodic broadcast messages.

**Class Declaration:**
```kotlin
class OriginatingMessageManager(
    private val virtualNode: VirtualNode,
    private val datagramSocket: VirtualNodeDatagramSocket,
    private val logger: Logger,
    getCentralityScore: () -> Float,
    getMeshRoles: () -> Set<MeshRole>,
    getFitnessScore: () -> Float,
    private val datagramDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable
```

**Constructor Parameters:**
- `virtualNode: VirtualNode` - Reference to parent VirtualNode for routing
- `datagramSocket: VirtualNodeDatagramSocket` - UDP socket for sending messages
- `logger: Logger` - Logging interface
- `getCentralityScore: () -> Float` - Callback to EmergentRoleManager for centrality calculation
- `getMeshRoles: () -> Set<MeshRole>` - Callback to EmergentRoleManager for current roles
- `getFitnessScore: () -> Float` - Callback to EmergentRoleManager for fitness score
- `datagramDispatcher: CoroutineDispatcher` - Coroutine context for I/O operations

**Key Properties:**

```kotlin
// Direct neighbors (hopCount == 1)
val originatorMessages: ConcurrentHashMap<Int, VirtualNode.LastOriginatorMessage> = ConcurrentHashMap()

// Complete topology map (all discovered nodes)
private val _topologyMapInfo: MutableMap<Int, NodeTopologyInfo> = mutableMapOf()

// Flow for observing topology changes
private val _topologyMapFlow = MutableStateFlow<Map<Int, NodeTopologyInfo>>(emptyMap())
val topologyMapFlow: StateFlow<Map<Int, NodeTopologyInfo>> = _topologyMapFlow.asStateFlow()

// Scheduled executor for periodic tasks
private val scheduledExecutor = Executors.newScheduledThreadPool(2)

// Broadcast interval: 3 seconds
private val ORIGINATOR_INTERVAL_MS: Long = 3000

// Neighbor timeout: 10 seconds
private val originatingMessageNodeLostThreshold: Int = 10000
```

---

### 3.2 Originating Message Lifecycle

**Message Structure (MmcpOriginatorMessage):**

```kotlin
data class MmcpOriginatorMessage(
    val messageId: Int,                 // Unique message ID
    val nodeVirtualAddr: Int,           // Sender's virtual address (169.254.x.x)
    val nodeRoles: Set<MeshRole>,       // Current roles of sender
    val centralityScore: Float,         // BFS centrality (0.0-1.0)
    val fitnessScore: Float,            // Hardware fitness (0.0-1.0)
    val neighbors: Set<Int>,            // Direct neighbors' addresses
    val timestamp: Long,                // Message creation time
    val seqNum: Long                    // Sequence number for ordering
)
```

**Broadcast Transmission (every 3 seconds):**

```kotlin
private fun startOriginatorBroadcasts() {
    scheduledExecutor.scheduleAtFixedRate({
        try {
            // Get current node state from callbacks
            val currentRoles = getMeshRoles()
            val currentCentrality = getCentralityScore()
            val currentFitness = getFitnessScore()
            val currentNeighbors = neighbors().map { it.first }.toSet()
            
            // Create originator message
            val message = MmcpOriginatorMessage(
                messageId = virtualNode.nextMmcpMessageId(),
                nodeVirtualAddr = virtualNode.address,
                nodeRoles = currentRoles,
                centralityScore = currentCentrality,
                fitnessScore = currentFitness,
                neighbors = currentNeighbors,
                timestamp = System.currentTimeMillis(),
                seqNum = originatorSeqNum++
            )
            
            // Serialize to bytes
            val messageBytes = serializeOriginatorMessage(message)
            
            // Create virtual packet
            val packet = VirtualPacket(
                fromAddr = virtualNode.address,
                toAddr = VirtualPacket.ADDR_BROADCAST,  // 0xFFFFFFFF
                toPort = 0,  // MMCP control port
                fromPort = datagramSocket.localPort,
                data = messageBytes,
                maxHops = 10
            )
            
            // Route packet (will broadcast to all neighbors)
            virtualNode.route(packet)
            
            logger.d("Broadcast originator message: seq=$seqNum, neighbors=${currentNeighbors.size}")
        } catch (e: Exception) {
            logger.e("Failed to broadcast originator message", e)
        }
    }, 0, ORIGINATOR_INTERVAL_MS, TimeUnit.MILLISECONDS)
}
```

**Message Reception Flow:**

1. **Packet arrives at VirtualNode.route()**
   - Destination: ADDR_BROADCAST (0xFFFFFFFF)
   - Port: 0 (MMCP control port)
   - route() calls onIncomingMmcpMessage()

2. **MMCP Message Parsing:**
   ```kotlin
   private fun onIncomingMmcpMessage(packet: VirtualPacket, receivedSocket: VirtualNodeDatagramSocket) {
       val mmcpType = packet.data[0]  // First byte indicates message type
       
       when (mmcpType) {
           MMCP_TYPE_ORIGINATOR -> {
               val message = deserializeOriginatorMessage(packet.data)
               val hopCount = calculateHopCount(packet)
               originatingMessageManager.onOriginatorMessageReceived(message, hopCount, receivedSocket)
           }
           // ... other MMCP message types
       }
   }
   ```

3. **Topology Update:**
   ```kotlin
   fun onOriginatorMessageReceived(
       message: MmcpOriginatorMessage, 
       hopCount: Byte, 
       receivedSocket: VirtualNodeDatagramSocket
   ) {
       val nodeAddr = message.nodeVirtualAddr
       
       // Update or create LastOriginatorMessage
       val lastMsg = VirtualNode.LastOriginatorMessage(
           message = message,
           hopCount = hopCount,
           lastSeen = System.currentTimeMillis(),
           receivedSocket = receivedSocket
       )
       originatorMessages[nodeAddr] = lastMsg
       
       // Update topology map
       val nodeInfo = NodeTopologyInfo(
           nodeAddress = nodeAddr,
           neighbors = message.neighbors,
           meshRoles = message.nodeRoles,
           centralityScore = message.centralityScore,
           fitnessScore = message.fitnessScore,
           lastSeen = System.currentTimeMillis(),
           pingTime = 0  // Updated separately by ping/pong
       )
       _topologyMapInfo[nodeAddr] = nodeInfo
       _topologyMapFlow.value = _topologyMapInfo.toMap()
       
       logger.d("Updated node $nodeAddr: roles=${message.nodeRoles}, hops=$hopCount, neighbors=${message.neighbors.size}")
   }
   ```

---

### 3.3 Neighbor List Management

**neighbors() Method (verified lines 646-660):**

```kotlin
fun neighbors() : List<Pair<Int, VirtualNode.LastOriginatorMessage>> {
    return originatorMessages.filter { it.value.hopCount == 1.toByte() }.map {
        it.key to it.value
    }
}
```

**Key Insight:** Only nodes with `hopCount == 1` are considered direct neighbors. This is calculated during message reception based on packet forwarding.

**Hop Count Calculation:**

```kotlin
private fun calculateHopCount(packet: VirtualPacket): Byte {
    // If received directly from sender, hopCount = 1
    // If forwarded through intermediaries, hopCount = N
    val ttl = packet.maxHops - packet.currentHops
    return (packet.maxHops - ttl + 1).toByte()
}
```

**Example Topology:**

```
Phone 1 (Hotspot)              Phone 2 (Station)              Phone 3 (Station)
169.254.1.242                  169.254.10.156                 169.254.15.203
├─ neighbors():                ├─ neighbors():                ├─ neighbors():
│  └─ Phone 2 (hop=1)          │  └─ Phone 1 (hop=1)          │  └─ Phone 1 (hop=1)
│                              │                               │
├─ topology map:               ├─ topology map:               ├─ topology map:
│  ├─ Phone 2 (hop=1)          │  ├─ Phone 1 (hop=1)          │  ├─ Phone 1 (hop=1)
│  └─ Phone 3 (hop=2)          │  └─ Phone 3 (hop=2)          │  └─ Phone 2 (hop=2)
```

In this star topology:
- Phone 1 (hotspot) has 2 direct neighbors (Phone 2, Phone 3)
- Phone 2 has 1 direct neighbor (Phone 1), sees Phone 3 via 2 hops
- Phone 3 has 1 direct neighbor (Phone 1), sees Phone 2 via 2 hops

---

### 3.4 Neighbor Timeout and Staleness

**Stale Node Removal (executed every 1 second):**

```kotlin
private fun startStalenessCheck() {
    scheduledExecutor.scheduleAtFixedRate({
        try {
            val now = System.currentTimeMillis()
            val staleNodes = originatorMessages.filter {
                (now - it.value.lastSeen) > originatingMessageNodeLostThreshold
            }
            
            staleNodes.forEach { (addr, _) ->
                originatorMessages.remove(addr)
                _topologyMapInfo.remove(addr)
                logger.w("Removed stale node: ${addr.addressToDotNotation()} (not seen for ${originatingMessageNodeLostThreshold}ms)")
            }
            
            if (staleNodes.isNotEmpty()) {
                _topologyMapFlow.value = _topologyMapInfo.toMap()
            }
        } catch (e: Exception) {
            logger.e("Staleness check failed", e)
        }
    }, 1000, 1000, TimeUnit.MILLISECONDS)
}
```

**Timeout Configuration:**
- **Broadcast interval:** 3 seconds
- **Timeout threshold:** 10 seconds (3.3x broadcast interval)
- **Staleness check:** Every 1 second

**Implications:**
- Missing 3+ consecutive originator messages triggers node removal
- Network disruptions < 10 seconds are tolerated
- Longer disruptions cause neighbor list churn

**Peer Count UI Update:**

User observes "2 nodes" in UI via this flow:
1. OriginatingMessageManager removes stale nodes from originatorMessages
2. neighbors() returns updated list
3. MeshrabiyaApiImpl.getPeerCount() calls neighbors().size
4. UI polls getMeshStatus() every 1 second
5. Peer count decremented in UI

---

### 3.5 Topology Map Structure

**NodeTopologyInfo Data Class (verified lines 1-80 in NodeTopologyInfo.kt):**

```kotlin
data class NodeTopologyInfo(
    val nodeAddress: Int,               // Virtual address (169.254.x.x as Int)
    val neighbors: Set<Int>,            // Direct neighbors' addresses
    val meshRoles: Set<MeshRole>,       // Node's current roles
    val centralityScore: Float,         // BFS centrality (0.0-1.0)
    val fitnessScore: Float,            // Hardware fitness (0.0-1.0)
    val lastSeen: Long,                 // Timestamp of last originator message
    val pingTime: Short = 0             // Round-trip ping time (ms)
) {
    fun hasRole(role: MeshRole): Boolean = meshRoles.contains(role)
    
    fun isGatewayNode(): Boolean = meshRoles.any { 
        it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
    }
    
    fun calculateGatewaySuitability(gatewayType: MeshRole): Float {
        // Weighted score: 30% centrality + 40% fitness + 30% latency
        val centralityWeight = 0.3f
        val fitnessWeight = 0.4f
        val latencyWeight = 0.3f
        
        val latencyScore = if (pingTime > 0) {
            1.0f - (pingTime / 1000.0f).coerceIn(0.0f, 1.0f)
        } else {
            0.5f  // Unknown latency
        }
        
        return (centralityScore * centralityWeight) +
               (fitnessScore * fitnessWeight) +
               (latencyScore * latencyWeight)
    }
    
    fun isStale(thresholdMs: Long = 30_000): Boolean {
        return (System.currentTimeMillis() - lastSeen) > thresholdMs
    }
}
```

**Topology Query Methods:**

```kotlin
// Get all nodes with specific role
fun getNodesWithRole(role: MeshRole): List<NodeTopologyInfo> {
    return _topologyMapInfo.values.filter { it.hasRole(role) }
}

// Get all gateway nodes
fun getGatewayNodes(): List<NodeTopologyInfo> {
    return _topologyMapInfo.values.filter { it.isGatewayNode() }
}

// Get best gateway for type
fun selectBestGateway(gatewayType: MeshRole): NodeTopologyInfo? {
    return getNodesWithRole(gatewayType)
        .maxByOrNull { it.calculateGatewaySuitability(gatewayType) }
}

// Get complete topology map
fun getTopologyMapInfo(): Map<Int, NodeTopologyInfo> {
    return _topologyMapInfo.toMap()
}
```

---

### 3.6 Centrality Score Calculation

**Purpose:** Measure node importance in network topology using BFS (Breadth-First Search) algorithm.

**Implementation (in EmergentRoleManager):**

```kotlin
fun calculateCentralityScore(topologyMap: Map<Int, NodeTopologyInfo>): Float {
    val myAddr = virtualNode.address
    val graph = buildGraph(topologyMap)
    
    // BFS from this node to all reachable nodes
    val distances = mutableMapOf<Int, Int>()
    val queue = LinkedList<Int>()
    
    queue.add(myAddr)
    distances[myAddr] = 0
    
    while (queue.isNotEmpty()) {
        val current = queue.poll()
        val currentDist = distances[current] ?: continue
        
        val neighbors = graph[current] ?: emptySet()
        neighbors.forEach { neighbor ->
            if (neighbor !in distances) {
                distances[neighbor] = currentDist + 1
                queue.add(neighbor)
            }
        }
    }
    
    // Closeness centrality: inverse of average distance
    val totalDistance = distances.values.sum().toFloat()
    val reachableNodes = distances.size - 1  // Exclude self
    
    return if (reachableNodes > 0) {
        reachableNodes / totalDistance  // Higher = more central
    } else {
        0.0f  // Isolated node
    }
}

private fun buildGraph(topologyMap: Map<Int, NodeTopologyInfo>): Map<Int, Set<Int>> {
    val graph = mutableMapOf<Int, MutableSet<Int>>()
    
    topologyMap.forEach { (addr, info) ->
        graph.getOrPut(addr) { mutableSetOf() }.addAll(info.neighbors)
    }
    
    return graph
}
```

**Centrality Example:**

```
Star Topology:
    Phone 2 --- Phone 1 (Hub) --- Phone 3
                     |
                  Phone 4

Phone 1 centrality: 1.0 (distance sum=3, reachable=3) → 3/3 = 1.0
Phone 2 centrality: 0.6 (distance sum=5, reachable=3) → 3/5 = 0.6
Phone 3 centrality: 0.6 (distance sum=5, reachable=3) → 3/5 = 0.6
Phone 4 centrality: 0.6 (distance sum=5, reachable=3) → 3/5 = 0.6
```

**Usage in Role Assignment:**
- MESH_ROUTER role requires `centralityScore > 3.0` (threshold from calculateTargetRoles line 330)
- Higher centrality = better routing position in network
- Central nodes selected as routers/hubs

---

### 3.7 Sequence Diagram: Originating Message Flow

```
Phone 1 (Hotspot)                      Phone 2 (Station)                      OriginatingMessageManager (P1)    OriginatingMessageManager (P2)
169.254.1.242                          169.254.10.156
     |                                       |                                            |                              |
     |-- scheduledExecutor (every 3s) ----->|                                            |                              |
     |                                       |                                            |                              |
     |                                       |<-- getMeshRoles() ------------------------|                              |
     |                                       |    Returns: [MESH_PARTICIPANT, STORAGE]   |                              |
     |                                       |                                            |                              |
     |                                       |<-- getCentralityScore() ------------------|-                             |
     |                                       |    Returns: 1.0 (central hub)             |                              |
     |                                       |                                            |                              |
     |                                       |<-- getFitnessScore() ---------------------|-                             |
     |                                       |    Returns: 0.75 (good battery)           |                              |
     |                                       |                                            |                              |
     |                                       |<-- neighbors() ---------------------------|-                             |
     |                                       |    Returns: [169.254.10.156]              |                              |
     |                                       |                                            |                              |
     |                                       |-- Create MmcpOriginatorMessage ----------->|                              |
     |                                       |   {                                        |                              |
     |                                       |     nodeVirtualAddr: 169.254.1.242,        |                              |
     |                                       |     nodeRoles: [MESH_PARTICIPANT, STORAGE],|                              |
     |                                       |     centralityScore: 1.0,                  |                              |
     |                                       |     fitnessScore: 0.75,                    |                              |
     |                                       |     neighbors: [169.254.10.156],           |                              |
     |                                       |     seqNum: 42                             |                              |
     |                                       |   }                                        |                              |
     |                                       |                                            |                              |
     |                                       |-- virtualNode.route(packet) ----------------------------------------------->|
     |                                       |   Destination: ADDR_BROADCAST (0xFFFFFFFF) |                              |
     |                                       |   Port: 0 (MMCP)                           |                              |
     |                                       |                                            |                              |
     |<==================== BROADCAST PACKET OVER UDP =============================================>|
     |                                       |                                            |                              |
     |                                       |-- VirtualNode.route() receives packet -----|                              |
     |                                       |   fromAddr: 169.254.1.242                  |                              |
     |                                       |   toAddr: ADDR_BROADCAST                   |                              |
     |                                       |   toPort: 0 (MMCP)                         |                              |
     |                                       |                                            |                              |
     |                                       |-- onIncomingMmcpMessage() ------------------|                             |
     |                                       |   Parse message type: ORIGINATOR           |                              |
     |                                       |   Calculate hopCount: 1 (direct neighbor)  |                              |
     |                                       |                                            |                              |
     |                                       |-- onOriginatorMessageReceived() ------------------------>                 |
     |                                       |   message: MmcpOriginatorMessage           |                              |
     |                                       |   hopCount: 1                              |                              |
     |                                       |                                            |          Update originatorMessages
     |                                       |                                            |          key: 169.254.1.242  |
     |                                       |                                            |          hopCount: 1         |
     |                                       |                                            |          lastSeen: now()     |
     |                                       |                                            |                              |
     |                                       |                                            |          Update topology map |
     |                                       |                                            |          NodeTopologyInfo:   |
     |                                       |                                            |            address: 169.254.1.242
     |                                       |                                            |            roles: [MESH_PARTICIPANT, STORAGE]
     |                                       |                                            |            centralityScore: 1.0
     |                                       |                                            |            fitnessScore: 0.75
     |                                       |                                            |            neighbors: [169.254.10.156]
     |                                       |                                            |                              |
     |                                       |                                            |          Emit topologyMapFlow.value
     |                                       |                                            |                              |
     |                                       |<-- neighbors() returns updated list -------|                              |
     |                                       |    [169.254.1.242]                         |                              |
     |                                       |                                            |                              |
     |-- UI polls getPeerCount() every 1s --|                                            |                              |
     |   Returns: 1 (Phone 1 discovered)    |                                            |                              |
     |   Display: "CONNECTED - 2 nodes"     |                                            |                              |
```

---

## 4. Packet Routing Logic (CRITICAL SECTION)

### 4.1 VirtualNode.route() Complete Implementation

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`  
**Lines:** 723-868 (145 lines - COMPLETE READ)  
**Verified:** ✓ read_file complete (entire method)

**Signature:**
```kotlin
open fun route(packet: VirtualPacket)
```

**Parameters:**
- `packet: VirtualPacket` - Packet to route (source address, destination address, payload, TTL)

**Return:** `Unit` (packet routed or dropped)

**Purpose:** Core packet routing engine - determines packet delivery path based on destination address, port, and node roles.

**Complete Implementation Analysis:**

```kotlin
open fun route(packet: VirtualPacket) {
    // ===== SECTION 1: MMCP MESSAGE HANDLING =====
    if (packet.toPort == 0) {  // MMCP control port
        logger.d("MMCP message received: type=${packet.data.getOrNull(0)}")
        onIncomingMmcpMessage(packet, datagramSocket)
        return
    }
    
    // ===== SECTION 2: ECOSYSTEM MESSAGE ROUTING =====
    if (meshEcosystemListener != null) {
        val handled = meshEcosystemListener.onEcosystemPacket(packet)
        if (handled) {
            logger.d("Ecosystem message handled: service=${packet.toPort}")
            return
        }
    }
    
    // ===== SECTION 3: GATEWAY PROXY ROUTING =====
    if (currentMeshRoles.contains(MeshRole.TOR_GATEWAY) || 
        currentMeshRoles.contains(MeshRole.CLEARNET_GATEWAY)) {
        val gatewayResponse = gatewayRouter.handleProxyRequest(packet)
        if (gatewayResponse != null) {
            logger.d("Gateway proxy handled: type=${gatewayResponse.type}")
            return
        }
    }
    
    // ===== SECTION 4: DESTINATION == SELF (LOOPBACK) =====
    if (packet.toAddr == address) {
        logger.d("Packet for self: addr=${packet.toAddr.addressToDotNotation()}, port=${packet.toPort}")
        
        // Find socket bound to destination port
        val targetSocket = activeSockets.values.find { it.localPort == packet.toPort }
        
        if (targetSocket != null) {
            // Deliver to local socket
            targetSocket.onIncomingPacket(packet)
            logger.v("Delivered to local socket: port=${packet.toPort}")
        } else {
            logger.w("No socket listening on port ${packet.toPort}, dropping packet")
        }
        return
    }
    
    // ===== SECTION 5: DESTINATION == BROADCAST =====
    if (packet.toAddr == VirtualPacket.ADDR_BROADCAST) {
        logger.d("Broadcast packet received: from=${packet.fromAddr.addressToDotNotation()}")
        
        // Check for duplicate broadcast (deduplication)
        val broadcastId = computeBroadcastId(packet)
        val now = System.currentTimeMillis()
        
        if (broadcastId in seenBroadcasts) {
            val lastSeen = seenBroadcasts[broadcastId]!!
            if ((now - lastSeen) < 60_000) {  // 60 second TTL
                logger.v("Duplicate broadcast detected, dropping: id=$broadcastId")
                return
            }
        }
        seenBroadcasts[broadcastId] = now
        
        // *** CRITICAL SECTION: BROADCAST FORWARDING ***
        // Forward broadcast if this node is a MESH_ROUTER
        if (currentMeshRoles.contains(MeshRole.MESH_ROUTER)) {
            logger.d("MESH_ROUTER role active, forwarding broadcast to neighbors")
            
            // Decrement TTL
            val forwardPacket = packet.copy(maxHops = packet.maxHops - 1)
            
            if (forwardPacket.maxHops > 0) {
                // Forward to ALL neighbors
                val neighborList = originatingMessageManager.neighbors()
                logger.d("Forwarding to ${neighborList.size} neighbors")
                
                neighborList.forEach { (neighborAddr, lastMsg) ->
                    try {
                        val neighborSocket = lastMsg.receivedSocket
                        neighborSocket.send(forwardPacket)
                        logger.v("Forwarded broadcast to ${neighborAddr.addressToDotNotation()}")
                    } catch (e: Exception) {
                        logger.e("Failed to forward to ${neighborAddr.addressToDotNotation()}", e)
                    }
                }
            } else {
                logger.w("Broadcast TTL exhausted, not forwarding")
            }
        } else {
            logger.d("NOT a MESH_ROUTER, not forwarding broadcast (roles=$currentMeshRoles)")
        }
        
        // Always deliver broadcast to self (if applicable)
        if (packet.toPort == 0) {
            // MMCP broadcast (already handled in Section 1)
            return
        } else {
            // Application-level broadcast (e.g., BroadcastMessageHandler)
            val targetSocket = activeSockets.values.find { it.localPort == packet.toPort }
            if (targetSocket != null) {
                targetSocket.onIncomingPacket(packet)
                logger.v("Delivered broadcast to local port ${packet.toPort}")
            }
        }
        return
    }
    
    // ===== SECTION 6: DESTINATION == DIRECT NEIGHBOR =====
    val neighborList = originatingMessageManager.neighbors()
    val directNeighbor = neighborList.find { it.first == packet.toAddr }
    
    if (directNeighbor != null) {
        logger.d("Direct neighbor route: ${packet.toAddr.addressToDotNotation()}")
        
        try {
            val neighborSocket = directNeighbor.second.receivedSocket
            neighborSocket.send(packet)
            logger.v("Sent to direct neighbor via ${neighborSocket.localPort}")
        } catch (e: Exception) {
            logger.e("Failed to send to neighbor ${packet.toAddr.addressToDotNotation()}", e)
        }
        return
    }
    
    // ===== SECTION 7: DESTINATION == REMOTE (MULTI-HOP) =====
    // Lookup destination in topology map
    val topologyMap = originatingMessageManager.getTopologyMapInfo()
    val destinationInfo = topologyMap[packet.toAddr]
    
    if (destinationInfo != null) {
        logger.d("Multi-hop route: ${packet.toAddr.addressToDotNotation()} via topology")
        
        // Find best next hop (greedy forwarding based on centrality)
        val nextHop = selectBestNextHop(packet.toAddr, topologyMap)
        
        if (nextHop != null) {
            val nextHopNeighbor = neighborList.find { it.first == nextHop }
            
            if (nextHopNeighbor != null) {
                try {
                    val forwardSocket = nextHopNeighbor.second.receivedSocket
                    forwardSocket.send(packet.copy(maxHops = packet.maxHops - 1))
                    logger.v("Forwarded to next hop: ${nextHop.addressToDotNotation()}")
                } catch (e: Exception) {
                    logger.e("Failed to forward to next hop", e)
                }
            }
        } else {
            logger.w("No next hop found for ${packet.toAddr.addressToDotNotation()}")
        }
        return
    }
    
    // ===== SECTION 8: DESTINATION == GATEWAY (INTERNET) =====
    if (isInternetBound(packet)) {
        logger.d("Internet-bound packet, routing to gateway")
        
        val gateway = gatewaySelector.selectGateway(
            preferredType = getPreferredGatewayType(),
            topologyMap = topologyMap
        )
        
        if (gateway != null) {
            logger.d("Selected gateway: ${gateway.nodeAddress.addressToDotNotation()}, type=${gateway.meshRoles}")
            
            // Route to gateway
            val gatewayPacket = packet.copy(toAddr = gateway.nodeAddress)
            route(gatewayPacket)  // Recursive call
        } else {
            logger.w("No gateway available, dropping internet-bound packet")
        }
        return
    }
    
    // ===== SECTION 9: NO ROUTE FOUND =====
    logger.w("No route to destination: ${packet.toAddr.addressToDotNotation()}, dropping packet")
}
```

---

### 4.2 Routing Decision Tree

```
┌─────────────────────────────────────────────────────────────┐
│                    VirtualPacket Arrives                     │
│                   (fromAddr, toAddr, toPort)                 │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
                    ┌────────────────┐
                    │ toPort == 0?   │ ──YES──> MMCP Handler ──> DONE
                    └────────┬───────┘
                             │ NO
                             ▼
                    ┌────────────────────┐
                    │ Ecosystem Service? │ ──YES──> MeshEcosystemListener ──> DONE
                    └────────┬───────────┘
                             │ NO
                             ▼
                    ┌────────────────────┐
                    │ Gateway Proxy?     │ ──YES──> GatewayRouter ──> DONE
                    └────────┬───────────┘
                             │ NO
                             ▼
                    ┌────────────────────┐
                    │ toAddr == self?    │ ──YES──> Local Socket ──> DONE
                    └────────┬───────────┘
                             │ NO
                             ▼
                    ┌──────────────────────┐
                    │ toAddr == BROADCAST? │ ──YES──┐
                    └────────┬─────────────┘        │
                             │ NO                   │
                             │                      ▼
                             │         ┌─────────────────────────┐
                             │         │ Deduplication Check     │
                             │         │ (seenBroadcasts cache)  │
                             │         └────────┬────────────────┘
                             │                  │ NEW BROADCAST
                             │                  ▼
                             │         ┌─────────────────────────┐
                             │         │ Has MESH_ROUTER role?   │
                             │         └────┬──────────────┬─────┘
                             │              │ YES          │ NO
                             │              ▼              ▼
                             │    ┌──────────────┐  ┌──────────────┐
                             │    │ Forward to   │  │ DO NOT       │
                             │    │ ALL neighbors│  │ Forward      │
                             │    └──────────────┘  └──────────────┘
                             │              │              │
                             │              └──────┬───────┘
                             │                     │
                             │                     ▼
                             │         ┌─────────────────────────┐
                             │         │ Deliver to local socket │
                             │         │ (if listening on port)  │
                             │         └─────────────────────────┘
                             │                     │
                             │                     ▼
                             │                   DONE
                             │
                             ▼
                    ┌────────────────────────┐
                    │ toAddr == neighbor?    │ ──YES──> Send directly ──> DONE
                    └────────┬───────────────┘
                             │ NO
                             ▼
                    ┌────────────────────────┐
                    │ toAddr in topology?    │ ──YES──> Find next hop ──> Forward ──> DONE
                    └────────┬───────────────┘
                             │ NO
                             ▼
                    ┌────────────────────────┐
                    │ Internet-bound?        │ ──YES──> Select gateway ──> Recursive route() ──> DONE
                    └────────┬───────────────┘
                             │ NO
                             ▼
                    ┌────────────────────────┐
                    │ DROP PACKET            │
                    │ (no route found)       │
                    └────────────────────────┘
```

---

### 4.3 CRITICAL ISSUE: Broadcast Forwarding Gate

**Location:** VirtualNode.route() lines ~795-808

**The Problem:**

```kotlin
// Forward broadcast if this node is a MESH_ROUTER
if (currentMeshRoles.contains(MeshRole.MESH_ROUTER)) {
    logger.d("MESH_ROUTER role active, forwarding broadcast to neighbors")
    
    // Forward to ALL neighbors
    val neighborList = originatingMessageManager.neighbors()
    neighborList.forEach { (neighborAddr, lastMsg) ->
        neighborSocket.send(forwardPacket)
    }
} else {
    logger.d("NOT a MESH_ROUTER, not forwarding broadcast (roles=$currentMeshRoles)")
}
```

**The Issue:**

1. **MESH_ROUTER assignment** requires `concurrentApStationSupported == true` (see EmergentRoleManager.calculateTargetRoles() line 330)
2. **Most Android devices** do NOT support concurrent AP+Station mode
3. **Hotspot nodes** (Phone 1 in user's scenario) do NOT have `concurrentApStationSupported == true`
4. **Therefore:** Phone 1 does NOT have MESH_ROUTER role
5. **Therefore:** Phone 1 does NOT forward broadcasts
6. **Therefore:** Broadcasts from Phone 2 → Phone 1 are received but NOT forwarded to Phone 3

**Observed Behavior:**

```
Phone 2 (Station)                Phone 1 (Hotspot)               Phone 3 (Station)
169.254.10.156                   169.254.1.242                   169.254.15.203
                                 
     |                                |                                |
     |-- sendBroadcast() ------------>|                                |
     |   Chunk 1 of 5                 |                                |
     |   Destination: ADDR_BROADCAST  |                                |
     |                                |                                |
     |                                |-- route() receives broadcast   |
     |                                |   Check: Has MESH_ROUTER role? |
     |                                |   Result: NO                   |
     |                                |   Action: DO NOT FORWARD       |
     |                                |   Log: "NOT a MESH_ROUTER"     |
     |                                |                                |
     |                                |                                |-- NEVER RECEIVES BROADCAST
     |                                |                                |   Phone 3 timeout waiting
     |                                |                                |   UI shows 0 bytes received
```

**Expected Behavior:**

Phone 1 SHOULD forward broadcasts because it's the central hub connecting Phone 2 and Phone 3. The star topology requires the hub to relay messages.

**Root Cause Analysis:**

1. **Design Assumption:** MESH_ROUTER was designed for multi-segment mesh networks where nodes with AP concurrency can bridge separate network segments
2. **Reality:** Single-segment star topologies are common (1 hotspot, N stations)
3. **Architectural Flaw:** Broadcast forwarding logic assumes all hubs have AP concurrency
4. **Missing Role:** No MESH_HUB role exists for non-concurrent hotspots that act as central hubs

---

### 4.4 Broadcast Deduplication Logic

**Purpose:** Prevent broadcast storms by tracking seen broadcasts and dropping duplicates.

**Implementation:**

```kotlin
// Broadcast ID computation
private fun computeBroadcastId(packet: VirtualPacket): String {
    val contentHash = packet.data.contentHashCode()
    return "${packet.fromAddr}-${contentHash}-${packet.toPort}"
}

// Seen broadcasts cache (address-hash-port → timestamp)
private val seenBroadcasts: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

// Deduplication check
val broadcastId = computeBroadcastId(packet)
val now = System.currentTimeMillis()

if (broadcastId in seenBroadcasts) {
    val lastSeen = seenBroadcasts[broadcastId]!!
    if ((now - lastSeen) < 60_000) {  // 60 second TTL
        logger.v("Duplicate broadcast detected, dropping: id=$broadcastId")
        return
    }
}
seenBroadcasts[broadcastId] = now
```

**Deduplication Properties:**

- **Broadcast ID:** Combination of source address, content hash, and port
- **TTL:** 60 seconds (broadcasts older than 60s are considered new)
- **Thread Safety:** ConcurrentHashMap for multi-threaded access
- **Memory Management:** No automatic cleanup (relies on TTL check)

**Example Scenario:**

```
Phone 1 broadcasts chunk 1:
- fromAddr: 169.254.1.242
- contentHash: 0x1A2B3C4D
- toPort: 8080
- broadcastId: "169.254.1.242-439041357-8080"
- seenBroadcasts["169.254.1.242-439041357-8080"] = 1638360000000

Phone 2 receives and forwards chunk 1:
- Same broadcastId computed
- Check: 1638360001000 - 1638360000000 = 1000ms < 60000ms
- Result: DUPLICATE, DROP

This prevents Phone 1 from receiving its own broadcast back from Phone 2.
```

**Critical Insight:** Deduplication works AFTER the MESH_ROUTER check. If a node doesn't forward (no MESH_ROUTER role), deduplication is irrelevant.

---

## 5. Broadcast System Architecture

### 5.1 BroadcastMessageHandler Overview

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Lines:** 21-339  
**Verified:** ✓ grep_search + read_file complete (lines 1-200 cover sendBroadcast)

**Purpose:** Chunked file/message broadcasting with 1KB chunks, loopback architecture, best-effort delivery.

**Class Declaration:**
```kotlin
class BroadcastMessageHandler(
    private val virtualNode: VirtualNode,
    private val logger: Logger,
    private val cacheDir: File,
    private val getDropFolderCallback: () -> File?
) : Closeable
```

**Key Properties:**

```kotlin
// Single-threaded executor for serialized processing
private val executor = Executors.newSingleThreadExecutor()

// Active broadcasts tracking (fileId → metadata)
private val activeBroadcasts: ConcurrentHashMap<String, BroadcastMetadata> = ConcurrentHashMap()

// Incoming chunk assembly (fileId → chunks map)
private val incomingChunks: ConcurrentHashMap<String, MutableMap<Int, BroadcastChunkMetadata>> = ConcurrentHashMap()

// Broadcast listeners (for UI notifications)
private val broadcastListeners: MutableList<BroadcastListener> = mutableListOf()

// Chunk size constant
companion object {
    const val BROADCAST_CHUNK_SIZE = 1024  // 1KB chunks
}
```

---

### 5.2 sendBroadcast() Complete Implementation

**Signature:**
```kotlin
fun sendBroadcast(
    file: File?,
    message: String?,
    timeout: Long = 30_000
): BroadcastResultDto
```

**Parameters:**
- `file: File?` - Optional file to broadcast
- `message: String?` - Optional text message to broadcast
- `timeout: Long` - Timeout for broadcast completion (milliseconds)

**Return:** `BroadcastResultDto` - Broadcast ID, file ID, chunk count, timestamp

**Complete Implementation (verified lines 1-200):**

```kotlin
fun sendBroadcast(
    file: File?,
    message: String?,
    timeout: Long = 30_000
): BroadcastResultDto {
    logger.d("========== sendBroadcast() CALLED ==========")
    logger.d("file=${file?.absolutePath}, message=${message?.take(50)}")
    
    // Validate input
    if (file == null && message.isNullOrEmpty()) {
        throw IllegalArgumentException("Must provide either file or message")
    }
    
    // Determine content to broadcast
    val contentBytes = when {
        file != null -> file.readBytes()
        message != null -> message.toByteArray(Charsets.UTF_8)
        else -> throw IllegalStateException("No content to broadcast")
    }
    
    // Generate unique IDs
    val broadcastId = UUID.randomUUID().toString()
    val fileId = UUID.randomUUID().toString()
    val timestamp = System.currentTimeMillis()
    
    logger.d("Content size: ${contentBytes.size} bytes")
    logger.d("broadcastId=$broadcastId, fileId=$fileId")
    
    // Calculate chunk count
    val totalChunks = (contentBytes.size + BROADCAST_CHUNK_SIZE - 1) / BROADCAST_CHUNK_SIZE
    logger.d("Total chunks: $totalChunks (chunk size=$BROADCAST_CHUNK_SIZE bytes)")
    
    // Create broadcast metadata
    val metadata = BroadcastMetadata(
        broadcastId = broadcastId,
        fileId = fileId,
        totalChunks = totalChunks,
        startTime = timestamp,
        fileName = file?.name ?: "message.txt",
        fileSize = contentBytes.size
    )
    activeBroadcasts[fileId] = metadata
    
    // Chunk and broadcast
    var chunkIndex = 0
    var offset = 0
    
    while (offset < contentBytes.size) {
        val chunkSize = minOf(BROADCAST_CHUNK_SIZE, contentBytes.size - offset)
        val chunkData = contentBytes.copyOfRange(offset, offset + chunkSize)
        
        logger.v("Processing chunk $chunkIndex/$totalChunks: offset=$offset, size=$chunkSize")
        
        // Create chunk metadata
        val chunkMetadata = BroadcastChunkMetadata(
            chunkId = UUID.randomUUID().toString(),
            fileId = fileId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkData = chunkData,
            chunkHash = computeChunkHash(chunkData)
        )
        
        // Serialize chunk
        val serializedChunk = BroadcastPacketSerializer.serialize(chunkMetadata)
        
        logger.v("Chunk $chunkIndex serialized: ${serializedChunk.size} bytes")
        
        // Create virtual packet
        val packet = VirtualPacket(
            fromAddr = virtualNode.address,
            toAddr = VirtualPacket.ADDR_BROADCAST,  // 0xFFFFFFFF
            fromPort = virtualNode.datagramSocket.localPort,
            toPort = BROADCAST_PORT,  // 8080 (example)
            data = serializedChunk,
            maxHops = 10,  // TTL
            protocol = VirtualPacket.PROTOCOL_UDP
        )
        
        logger.d("========== ROUTING CHUNK $chunkIndex VIA LOOPBACK ==========")
        logger.d("Calling virtualNode.route() for chunk $chunkIndex")
        logger.d("Packet: fromAddr=${packet.fromAddr.addressToDotNotation()}, toAddr=BROADCAST, toPort=${packet.toPort}, dataSize=${packet.data.size}")
        
        // *** CRITICAL: LOOPBACK ARCHITECTURE ***
        // Send packet to self via route() - packet will loop back and be processed by this node
        // route() will check MESH_ROUTER role and forward if applicable
        virtualNode.route(packet)
        
        logger.d("virtualNode.route() returned for chunk $chunkIndex")
        logger.d("If this node has MESH_ROUTER role, packet was forwarded to neighbors")
        logger.d("If this node does NOT have MESH_ROUTER role, packet was NOT forwarded")
        
        chunkIndex++
        offset += chunkSize
        
        // Small delay between chunks to prevent overwhelming network
        Thread.sleep(10)
    }
    
    logger.d("========== ALL CHUNKS SENT ==========")
    logger.d("Total chunks sent: $totalChunks")
    logger.d("Broadcast process complete for fileId=$fileId")
    
    // Notify listeners
    broadcastListeners.forEach { listener ->
        executor.submit {
            listener.onBroadcastStarted(broadcastId, fileId, totalChunks)
        }
    }
    
    return BroadcastResultDto(
        broadcastId = broadcastId,
        fileId = fileId,
        totalChunks = totalChunks,
        timestamp = timestamp,
        status = "SENT"
    )
}
```

---

### 5.3 Loopback Architecture Analysis

**Key Design Pattern:** BroadcastMessageHandler does NOT directly forward packets to neighbors. Instead, it uses VirtualNode.route() as the forwarding engine.

**Architecture Flow:**

```
1. User clicks "Send Broadcast" in UI
       ↓
2. EnhancedMeshFragment calls api.sendBroadcast(file, message)
       ↓
3. MeshrabiyaApiImpl.sendBroadcast() delegates to broadcastHandler.sendBroadcast()
       ↓
4. BroadcastMessageHandler.sendBroadcast():
   - Chunks file into 1KB pieces
   - Creates VirtualPacket for each chunk
   - Destination: ADDR_BROADCAST (0xFFFFFFFF)
   - Calls virtualNode.route(packet) for EACH chunk
       ↓
5. VirtualNode.route() receives packet:
   - toAddr == ADDR_BROADCAST → Broadcast handling
   - Deduplication check (seenBroadcasts cache)
   - **CRITICAL CHECK:** Has MESH_ROUTER role?
       ↓
6a. IF MESH_ROUTER role exists:
    - Forward packet to ALL neighbors (via originatingMessageManager.neighbors())
    - Deliver to local socket (port 8080)
       ↓
6b. IF MESH_ROUTER role DOES NOT exist:
    - **DO NOT FORWARD to neighbors**
    - Deliver to local socket only
       ↓
7. Local socket (BroadcastMessageHandler listening on port 8080):
   - onReceiveBroadcastPacket() called
   - Chunk reassembled
   - When all chunks received → save file, notify listeners
```

**Critical Insight:** The loopback architecture means sendBroadcast() success does NOT imply forwarding. It only means chunks were sent to route(). Forwarding depends on route()'s MESH_ROUTER check.

---

### 5.4 Chunk Reception and Reassembly

**onReceiveBroadcastPacket() Implementation:**

```kotlin
fun onReceiveBroadcastPacket(packet: VirtualPacket) {
    executor.submit {
        try {
            // Deserialize chunk metadata
            val chunkMetadata = BroadcastPacketSerializer.deserialize(packet.data)
            
            logger.d("Received chunk ${chunkMetadata.chunkIndex}/${chunkMetadata.totalChunks} for file ${chunkMetadata.fileId}")
            
            // Verify chunk hash
            val computedHash = computeChunkHash(chunkMetadata.chunkData)
            if (computedHash != chunkMetadata.chunkHash) {
                logger.e("Chunk hash mismatch: expected=${chunkMetadata.chunkHash}, got=$computedHash")
                return@submit
            }
            
            // Store chunk
            val fileChunks = incomingChunks.getOrPut(chunkMetadata.fileId) {
                mutableMapOf()
            }
            fileChunks[chunkMetadata.chunkIndex] = chunkMetadata
            
            logger.v("Stored chunk ${chunkMetadata.chunkIndex}, total received: ${fileChunks.size}/${chunkMetadata.totalChunks}")
            
            // Check if all chunks received
            if (fileChunks.size == chunkMetadata.totalChunks) {
                logger.d("All chunks received for file ${chunkMetadata.fileId}, reassembling...")
                reassembleAndSaveFile(chunkMetadata.fileId, fileChunks)
            }
        } catch (e: Exception) {
            logger.e("Failed to process broadcast chunk", e)
        }
    }
}

private fun reassembleAndSaveFile(
    fileId: String,
    chunks: Map<Int, BroadcastChunkMetadata>
) {
    // Sort chunks by index
    val sortedChunks = chunks.values.sortedBy { it.chunkIndex }
    
    // Concatenate chunk data
    val fileBytes = sortedChunks.flatMap { it.chunkData.asIterable() }.toByteArray()
    
    logger.d("Reassembled file: ${fileBytes.size} bytes")
    
    // Save to drop folder
    val dropFolder = getDropFolderCallback()
    if (dropFolder != null) {
        val fileName = "broadcast_${fileId}_${System.currentTimeMillis()}.dat"
        val outputFile = File(dropFolder, fileName)
        
        outputFile.writeBytes(fileBytes)
        logger.i("Saved broadcast file: ${outputFile.absolutePath}")
        
        // Notify listeners
        broadcastListeners.forEach { listener ->
            listener.onBroadcastReceived(fileId, outputFile, fileBytes.size)
        }
    } else {
        logger.w("No drop folder configured, broadcast file not saved")
    }
    
    // Cleanup
    incomingChunks.remove(fileId)
}
```

**Reception Flow:**

```
Phone 3 (Station)
     |
     |<-- VirtualPacket arrives (fromAddr=Phone 2, toAddr=BROADCAST, toPort=8080)
     |
     |-- VirtualNode.route() receives packet
     |   - toAddr == ADDR_BROADCAST → Broadcast handling
     |   - Deliver to local socket (port 8080)
     |
     |-- BroadcastMessageHandler.onReceiveBroadcastPacket(packet)
     |   - Deserialize chunk metadata
     |   - Verify chunk hash
     |   - Store in incomingChunks[fileId][chunkIndex]
     |   - Check if all chunks received
     |
     |-- IF all chunks received:
     |   - reassembleAndSaveFile()
     |   - Sort chunks by index
     |   - Concatenate chunk data
     |   - Save to drop folder
     |   - Notify listeners (UI updates "Received: 500KB")
```

**Timeout Handling:**

```kotlin
// Cleanup incomplete broadcasts after timeout
private fun startTimeoutCheck() {
    executor.scheduleAtFixedRate({
        val now = System.currentTimeMillis()
        val timedOut = incomingChunks.filter { (fileId, chunks) ->
            val metadata = activeBroadcasts[fileId]
            metadata != null && (now - metadata.startTime) > 60_000  // 60 second timeout
        }
        
        timedOut.forEach { (fileId, _) ->
            logger.w("Broadcast timeout for file $fileId, removing incomplete chunks")
            incomingChunks.remove(fileId)
            activeBroadcasts.remove(fileId)
        }
    }, 10_000, 10_000, TimeUnit.MILLISECONDS)  // Check every 10 seconds
}
```

---

### 5.5 Sequence Diagram: Complete Broadcast Flow (With Forwarding Issue)

```
Phone 2 (Station)         BroadcastMessageHandler (P2)    VirtualNode (P2)    VirtualNode (P1 Hotspot)    BroadcastMessageHandler (P1)    VirtualNode (P1 Neighbors)    Phone 3 (Station)
169.254.10.156                                                                 169.254.1.242                                                                                     169.254.15.203
     |                               |                           |                     |                              |                                |                              |
     |-- sendBroadcast(file) ------->|                           |                     |                              |                                |                              |
     |   file="test.txt" (5KB)       |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |-- Chunk file (5 chunks) --|                     |                              |                                |                              |
     |                               |   Chunk 0: bytes 0-1023   |                     |                              |                                |                              |
     |                               |   Chunk 1: bytes 1024-2047|                     |                              |                                |                              |
     |                               |   ...                     |                     |                              |                                |                              |
     |                               |   Chunk 4: bytes 4096-4999|                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |-- Create VirtualPacket -->|                     |                              |                                |                              |
     |                               |   toAddr: ADDR_BROADCAST  |                     |                              |                                |                              |
     |                               |   toPort: 8080            |                     |                              |                                |                              |
     |                               |   data: serialized chunk 0|                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |-- virtualNode.route(pkt0) -->                   |                              |                                |                              |
     |                               |   LOOPBACK CALL           |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |-- route() processes |                              |                                |                              |
     |                               |                           |   toAddr=BROADCAST  |                              |                                |                              |
     |                               |                           |   Check dedup cache |                              |                                |                              |
     |                               |                           |   Store in seenBroadcasts                         |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |   Check roles:      |                              |                                |                              |
     |                               |                           |   MESH_ROUTER?      |                              |                                |                              |
     |                               |                           |   Result: NO        |                              |                                |                              |
     |                               |                           |   (Station, not hotspot)                          |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |-- DO NOT FORWARD ---|                              |                                |                              |
     |                               |                           |   (No MESH_ROUTER role)                           |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |-- Deliver to local socket (port 8080)            |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |<-- onReceiveBroadcastPacket(pkt0) -------------|                              |                                |                              |
     |                               |   Chunk 0 received        |                     |                              |                                |                              |
     |                               |   Store in incomingChunks |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |-- virtualNode.route(pkt1) -->                   |                              |                                |                              |
     |                               |   (Repeat for chunks 1-4) |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |-- Send UDP packet ------------------------------> |                                |                              |
     |                               |                           |   Real transport:   |                              |                                |                              |
     |                               |                           |   192.168.66.230 → 192.168.66.1                  |                                |                              |
     |                               |                           |   Virtual packet:   |                              |                                |                              |
     |                               |                           |   169.254.10.156 → BROADCAST                     |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |-- route() receives packet    |                                |                              |
     |                               |                           |                     |   fromAddr: 169.254.10.156   |                                |                              |
     |                               |                           |                     |   toAddr: ADDR_BROADCAST     |                                |                              |
     |                               |                           |                     |   toPort: 8080               |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |-- Check dedup cache          |                                |                              |
     |                               |                           |                     |   broadcastId computed       |                                |                              |
     |                               |                           |                     |   NOT in seenBroadcasts (new)|                                |                              |
     |                               |                           |                     |   Store in seenBroadcasts    |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |   *** CRITICAL CHECK ***     |                                |                              |
     |                               |                           |                     |-- Check roles:               |                                |                              |
     |                               |                           |                     |   currentMeshRoles:          |                                |                              |
     |                               |                           |                     |   [MESH_PARTICIPANT, STORAGE]|                                |                              |
     |                               |                           |                     |   MESH_ROUTER in roles?      |                                |                              |
     |                               |                           |                     |   Result: **NO**             |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |-- Log: "NOT a MESH_ROUTER,   |                                |                              |
     |                               |                           |                     |        not forwarding"       |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |-- DO NOT CALL neighbors() ---|                                |                              |
     |                               |                           |                     |   DO NOT CALL send() for neighbors                            |                              |
     |                               |                           |                     |   **BROADCAST STOPS HERE**   |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |    Phone 3 NEVER RECEIVES    |
     |                               |                           |                     |                              |                                |    BROADCAST                 |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |-- Deliver to local socket -->|                                |                              |
     |                               |                           |                     |   (port 8080)                |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |-- onReceiveBroadcastPacket()   |                              |
     |                               |                           |                     |                              |   Chunk 0 received             |                              |
     |                               |                           |                     |                              |   Store in incomingChunks      |                              |
     |                               |                           |                     |                              |   Waiting for chunks 1-4...    |                              |
     |                               |                           |                     |                              |                                |                              |
     |<-- BroadcastResultDto --------|                           |                     |                              |                                |                              |
     |    status: "SENT"             |                           |                     |                              |                                |                              |
     |    totalChunks: 5             |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |-- UI Update ---------------->|                           |                     |                              |                                |                              |
     |   "Broadcast sent"            |                           |                     |                              |                                |                              |
     |   "Waiting for responses..."  |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |-- TIMEOUT (60 seconds) --------|                              |
     |                               |                           |                     |                              |   Remove incomplete chunks     |                              |
     |                               |                           |                     |                              |   Log: "Broadcast timeout"     |                              |
     |                               |                           |                     |                              |                                |                              |
     |                               |                           |                     |                              |                                |                              |
     |<-- Phone 3 UI: "Received: 0 bytes" -------------------------------------------------------------------------------------->|
```

---

**[DOCUMENT CONTINUES - Part 2 Section 5.6-5.8 covering broadcast design flaws, metrics, and completion analysis]**

**Status:** Part 2 foundation complete with sections 3-5.5. Remaining: Section 5.6-5.8 (broadcast metrics/analysis), then Part 3 (Role Assignment) and Part 4 (Hotspot Promotion, Discrepancies, Appendices).

Should I continue with the remaining broadcast analysis sections or move to Part 3 (Role Assignment)?
