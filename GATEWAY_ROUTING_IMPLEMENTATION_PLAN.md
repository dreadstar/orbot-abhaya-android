# Gateway Routing Implementation Plan
**Date:** November 17, 2025  
**Project:** Orbot-Abhaya-Android / Meshrabiya  
**Scope:** Gateway Role Routing with Originator Messages

---

## 📋 EXECUTIVE SUMMARY

This plan details the implementation of **distributed gateway routing** using MeshRole information propagated via `MmcpOriginatorMessage`. The design enables nodes to:
1. Broadcast their roles (all 7 types: MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY)
2. Discover available **gateway nodes** (TOR, CLEARNET, I2P only) in the topology
3. Select optimal gateways based on fitness, centrality, and user preferences
4. **Multiplex traffic across multiple gateways** of the same type for load balancing

**KEY CLARIFICATIONS**:
- ✅ **Role Broadcasting**: ALL roles (including STORAGE_NODE, COMPUTE_NODE) are already being sent in `MmcpOriginatorMessage.meshRoles`
- ✅ **Gateway Roles**: Only TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY are gateway roles for routing
- ✅ **STORAGE/COMPUTE Roles**: Used for topology intelligence and future mesh optimization (NOT for gateway routing)
- ✅ **Topology Integration**: Existing `topologyMap` in OriginatingMessageManager must be enhanced to store roles
- ✅ **VirtualNode Integration**: Route() method needs gateway selection for client nodes; gateway nodes need proxy routing

---

## 🎯 OBJECTIVES

### Primary Goals
1. **Role Propagation**: ✅ **COMPLETE** - All roles already sent in `MmcpOriginatorMessage.meshRoles` (Phase 3)
2. **Topology Integration**: Store ALL role information (including STORAGE/COMPUTE for intelligence) in existing `topologyMap`
3. **Gateway Selection**: Implement intelligent gateway selection for TOR/CLEARNET/I2P roles only
4. **Multiplexed Routing**: Load-balance across multiple gateways of same type
5. **User Control**: Respect user-enabled roles and privacy preferences

### Success Criteria
- ✅ Nodes broadcast ALL 7 role types every originator interval (already done)
- ✅ Receiving nodes store role information in existing `topologyMap` structure
- ✅ Gateway selection considers only TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY roles
- ✅ Client nodes select gateways based on fitness, centrality, and latency
- ✅ Traffic multiplexes across 2+ gateways when available
- ✅ Gateway nodes route traffic through their configured proxy (Tor/etc)
- ✅ Zero gateway traffic when user disables gateway roles

---

## 🏗️ ARCHITECTURE OVERVIEW

### Current State (Phase 3 Complete)
```
VirtualNode
  ├─> OriginatingMessageManager
  │   ├─> makeOriginatingMessage() ✅ (creates MmcpOriginatorMessage)
  │   │   └─> Returns meshRoles from EmergentRoleManager (ALL 7 types)
  │   │
  │   ├─> onReceiveOriginatingMessage() ✅ (receives messages)
  │   │   └─> topologyMap[fromAddr] = mmcpMessage.neighbors.toSet()
  │   │       ⚠️ NEEDS: Also store meshRoles, fitness, centrality
  │   │
  │   └─> Callbacks: getCentralityScore(), getMeshRoles(), getFitnessScore()
  │
  ├─> EmergentRoleManager
  │   ├─> currentMeshRoles: StateFlow<Set<MeshRole>> ✅
  │   ├─> calculateCentralityScore() ✅
  │   └─> calculateNormalizedFitness() ✅
  │
  └─> route(packet) ⚠️ NEEDS: Gateway selection logic for client nodes

MmcpOriginatorMessage ✅
  ├─> meshRoles: Set<MeshRole> (already serialized - ALL 7 types)
  ├─> centralityScore: Float
  ├─> fitnessScore: Float
  └─> neighbors: List<Int>

Existing topologyMap ⚠️ NEEDS ENHANCEMENT:
  Map<Int, Set<Int>>  // Currently: nodeAddress -> neighbors only
  └─> Need: Map<Int, NodeTopologyInfo>  // Enhanced with roles + metrics
```

### Target State (This Plan)
```
VirtualNode
  ├─> OriginatingMessageManager
  │   ├─> topologyMap: Map<Int, NodeTopologyInfo> 🆕 ENHANCED
  │   │   └─> NodeTopologyInfo { 
  │   │         neighbors,
  │   │         meshRoles (ALL 7 types for intelligence),
  │   │         fitness, centrality, pingTime
  │   │       }
  │   │
  │   ├─> onReceiveOriginatingMessage() 🆕 ENHANCED
  │   │   └─> topologyMap[fromAddr] = NodeTopologyInfo(...) // Store roles + metrics
  │   │
  │   └─> getNodesWithRole(role): List<NodeTopologyInfo> 🆕 NEW METHOD
  │       └─> Filter topologyMap by gateway roles (TOR/CLEARNET/I2P)
  │
  ├─> GatewaySelector 🆕 NEW COMPONENT
  │   ├─> selectGateway(gatewayType): GatewayNode
  │   │   └─> Only considers: TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
  │   │
  │   ├─> selectMultipleGateways(gatewayType, count): List<GatewayNode>
  │   │   └─> Rank by: 0.3*centrality + 0.4*fitness + 0.3*latency
  │   │
  │   └─> Uses: EmergentRoleManager for user preferences
  │
  ├─> GatewayRouter 🆕 NEW COMPONENT
  │   ├─> routeToGateway(packet, gatewayType)
  │   │   ├─> CLIENT NODE: Select gateway, route packet to gateway node
  │   │   └─> GATEWAY NODE: Route packet through proxy (Tor/etc)
  │   │
  │   └─> Multiplexing: Round-robin or weighted distribution across N gateways
  │
  └─> route(packet) 🆕 ENHANCED
      ├─> If packet needs gateway → routeToGateway()
      └─> Else → existing direct routing
```

**Role Categorization**:
```kotlin
// GATEWAY ROLES (used for routing)
TOR_GATEWAY         // Route traffic through Tor proxy
CLEARNET_GATEWAY    // Route traffic through Internet
I2P_GATEWAY         // Route traffic through I2P proxy

// INTELLIGENCE ROLES (topology optimization only)
STORAGE_NODE        // Future: distributed storage intelligence
COMPUTE_NODE        // Future: distributed compute intelligence
MESH_ROUTER         // Future: routing optimization
MESH_PARTICIPANT    // Base role (always active)
```

---

## ⚠️ KEY CLARIFICATIONS FROM USER FEEDBACK

### 1. Role Categorization
- ✅ **ALL 7 roles** (MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY) are sent in `MmcpOriginatorMessage.meshRoles`
- ✅ **Gateway roles** = ONLY TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY (used for routing)
- ✅ **Intelligence roles** = STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER (stored for future optimization, NOT used for gateway routing)

### 2. Topology Integration
- ✅ **Existing `topologyMap`** in OriginatingMessageManager (line 107) must be **enhanced**, not replaced
- ✅ Change type from `Map<Int, Set<Int>>` → `Map<Int, NodeTopologyInfo>`
- ✅ Update `onReceiveOriginatingMessage()` (line ~359) to populate NodeTopologyInfo with ALL roles
- ⚠️ **CRITICAL**: Verify no other components depend on old `Map<Int, Set<Int>>` format before type change

### 3. Virtual Node Integration
- ✅ **CLIENT NODE** behavior: Select gateway from topology → route packet TO gateway node
- ✅ **GATEWAY NODE** behavior: Receive packet → route THROUGH configured proxy (Tor/I2P/etc)
- ✅ Integrate with existing `routeViaProxy()` method (line ~832)
- ✅ Add `isGatewayNode(type)` check to determine CLIENT vs GATEWAY behavior

### 4. Implementation Focus
- **Phase 1**: Enhance topology storage (integrate with existing topologyMap)
- **Phase 2**: Gateway selection (TOR/CLEARNET/I2P only, ignore STORAGE/COMPUTE)
- **Phase 3**: CLIENT node routing (route TO gateway)
- **Phase 4**: GATEWAY node routing (route THROUGH proxy)
- **Phase 5**: Traffic classification + E2E testing

---

## 📦 DETAILED COMPONENT DESIGN

### 1. Data Structures

#### 1.1 NodeTopologyInfo (NEW)
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/NodeTopologyInfo.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

/**
 * Topology information for a single mesh node, extracted from MmcpOriginatorMessage.
 * Used for gateway selection and mesh intelligence.
 * 
 * Stores ALL role types:
 * - Gateway roles (TOR/CLEARNET/I2P) for routing decisions
 * - Intelligence roles (STORAGE/COMPUTE) for future optimization
 */
data class NodeTopologyInfo(
    val nodeAddress: Int,
    val neighbors: Set<Int>,
    val meshRoles: Set<MeshRole>,  // ALL 7 types stored
    val centralityScore: Float,
    val fitnessScore: Float,
    val lastSeen: Long = System.currentTimeMillis(),
    val pingTime: Short = 0  // Latency to reach this node
) {
    /**
     * Check if node offers a specific role (gateway or intelligence)
     */
    fun hasRole(role: MeshRole): Boolean {
        return meshRoles.contains(role)
    }
    
    /**
     * Check if node is a gateway (TOR, CLEARNET, or I2P)
     */
    fun isGatewayNode(): Boolean {
        return meshRoles.any { it in GATEWAY_ROLES }
    }
    
    /**
     * Calculate gateway suitability score (0.0-1.0)
     * Higher is better for routing decisions.
     * Only meaningful for nodes with gateway roles.
     */
    fun calculateGatewaySuitability(gatewayType: MeshRole): Float {
        if (!hasRole(gatewayType)) return 0f
        
        // Weighted combination of factors
        val centralityWeight = 0.3f
        val fitnessWeight = 0.4f
        val latencyWeight = 0.3f
        
        val normalizedLatency = 1f - (pingTime / 1000f).coerceIn(0f, 1f)
        
        return (centralityScore * centralityWeight) +
               (fitnessScore * fitnessWeight) +
               (normalizedLatency * latencyWeight)
    }
    
    companion object {
        val GATEWAY_ROLES = setOf(
            MeshRole.TOR_GATEWAY,
            MeshRole.CLEARNET_GATEWAY,
            MeshRole.I2P_GATEWAY
        )
    }
}
```

#### 1.2 GatewaySelectionResult (NEW)
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewaySelectionResult.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

/**
 * Result of gateway selection operation
 */
sealed class GatewaySelectionResult {
    data class SingleGateway(
        val nodeAddress: Int,
        val suitability: Float,
        val hopCount: Int
    ) : GatewaySelectionResult()
    
    data class MultipleGateways(
        val gateways: List<GatewayNode>,
        val distributionStrategy: DistributionStrategy
    ) : GatewaySelectionResult()
    
    object NoGatewayAvailable : GatewaySelectionResult()
    
    object GatewayDisabledByUser : GatewaySelectionResult()
}

data class GatewayNode(
    val nodeAddress: Int,
    val suitability: Float,
    val hopCount: Int,
    val weight: Float = 1f  // For weighted distribution
)

enum class DistributionStrategy {
    ROUND_ROBIN,       // Equal distribution
    WEIGHTED,          // Based on suitability scores
    LATENCY_AWARE,     // Prefer lower latency
    FAILOVER           // Primary + backup
}
```

---

### 2. OriginatingMessageManager Updates

#### 2.1 Enhance Existing Topology Map Storage
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`

**Current State** (Line 107):
```kotlin
private val topologyMap: MutableMap<Int, Set<Int>> = mutableMapOf()
fun getTopologyMap(): Map<Int, Set<Int>> = topologyMap
```

**Changes Required**:
```kotlin
// === CHANGE 1: Update topology map to store NodeTopologyInfo ===
private val topologyMap: MutableMap<Int, NodeTopologyInfo> = mutableMapOf()

private val _topologyMapFlow = MutableStateFlow<Map<Int, NodeTopologyInfo>>(emptyMap())
val topologyMapFlow: StateFlow<Map<Int, NodeTopologyInfo>> = _topologyMapFlow.asStateFlow()

/**
 * Get immutable copy of topology map for gateway selection
 */
fun getTopologyMap(): Map<Int, NodeTopologyInfo> {
    return topologyMap.toMap()
}

/**
 * Get all nodes offering a specific role (gateway or intelligence)
 * @param role Can be gateway role (TOR/CLEARNET/I2P) or intelligence role (STORAGE/COMPUTE)
 */
fun getNodesWithRole(role: MeshRole): List<NodeTopologyInfo> {
    return topologyMap.values.filter { it.hasRole(role) }
}

/**
 * Get all gateway nodes (TOR, CLEARNET, I2P only)
 */
fun getGatewayNodes(): Map<MeshRole, List<NodeTopologyInfo>> {
    val gatewayRoles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
    return gatewayRoles.associateWith { role ->
        topologyMap.values.filter { it.hasRole(role) }
    }
}
```

#### 2.2 Update onReceiveOriginatingMessage()
**Location**: Line ~359 in OriginatingMessageManager.kt

**Current Implementation** (Phase 3):
```kotlin
// === NEW: BUILD TOPOLOGY MAP ===
if (mmcpMessage.neighbors.isNotEmpty()) {
    topologyMap[virtualPacket.header.fromAddr] = mmcpMessage.neighbors.toSet()
    
    logger(
        Log.VERBOSE,
        message = { "$logPrefix updated topology: node ${virtualPacket.header.fromAddr.addressToDotNotation()} " +
            "has ${mmcpMessage.neighbors.size} neighbors" }
    )
}
```

**NEW Implementation**:
```kotlin
// === ENHANCED: BUILD TOPOLOGY MAP WITH ROLES ===
val nodeInfo = NodeTopologyInfo(
    nodeAddress = virtualPacket.header.fromAddr,
    neighbors = mmcpMessage.neighbors.toSet(),
    meshRoles = mmcpMessage.meshRoles,  // Store ALL roles (gateway + intelligence)
    centralityScore = mmcpMessage.centralityScore,
    fitnessScore = mmcpMessage.fitnessScore,
    lastSeen = System.currentTimeMillis(),
    pingTime = mmcpMessage.pingTimeSum
)

topologyMap[virtualPacket.header.fromAddr] = nodeInfo
_topologyMapFlow.value = topologyMap.toMap()  // Emit update for observers

// Log gateway role changes (TOR/CLEARNET/I2P only)
val gatewayRoles = nodeInfo.meshRoles.filter { 
    it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
}
if (gatewayRoles.isNotEmpty()) {
    logger(
        Log.INFO, 
        message = { "$logPrefix Node ${virtualPacket.header.fromAddr.addressToDotNotation()} offers gateways: $gatewayRoles " +
            "(fitness=${nodeInfo.fitnessScore}, centrality=${nodeInfo.centralityScore})" }
    )
}

// Log intelligence roles (STORAGE/COMPUTE) at DEBUG level
val intelligenceRoles = nodeInfo.meshRoles.filter {
    it in setOf(MeshRole.STORAGE_NODE, MeshRole.COMPUTE_NODE)
}
if (intelligenceRoles.isNotEmpty()) {
    logger(
        Log.DEBUG,
        message = { "$logPrefix Node ${virtualPacket.header.fromAddr.addressToDotNotation()} offers intelligence: $intelligenceRoles " +
            "(fitness=${nodeInfo.fitnessScore}, centrality=${nodeInfo.centralityScore})" }
    )
}

logger(
    Log.VERBOSE,
    message = { "$logPrefix updated topology: node ${virtualPacket.header.fromAddr.addressToDotNotation()} " +
        "has ${mmcpMessage.neighbors.size} neighbors, ${nodeInfo.meshRoles.size} roles" }
)
```

---

### 3. GatewaySelector Component (NEW)

#### 3.1 GatewaySelector Class
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewaySelector.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import android.util.Log
import kotlin.math.exp

/**
 * Intelligent gateway selector that chooses optimal gateways based on:
 * - Fitness score (hardware capability)
 * - Centrality score (topology position)
 * - Latency (ping time)
 * - User preferences (enabled roles)
 * 
 * ONLY selects gateway roles: TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
 * (STORAGE/COMPUTE roles ignored for routing purposes)
 */
class GatewaySelector(
    private val originatingMessageManager: OriginatingMessageManager,
    private val emergentRoleManager: EmergentRoleManager,
    private val logger: MNetLogger,
    private val localNodeAddress: Int
) {
    
    private val logPrefix = "[GatewaySelector ${localNodeAddress.addressToDotNotation()}]"
    
    companion object {
        val GATEWAY_ROLES = setOf(
            MeshRole.TOR_GATEWAY,
            MeshRole.CLEARNET_GATEWAY,
            MeshRole.I2P_GATEWAY
        )
    }
    
    /**
     * Select single best gateway for given type
     * @param gatewayType Must be TOR_GATEWAY, CLEARNET_GATEWAY, or I2P_GATEWAY
     */
    fun selectGateway(gatewayType: MeshRole): GatewaySelectionResult {
        // Validate gateway type
        if (gatewayType !in GATEWAY_ROLES) {
            logger(Log.ERROR, "$logPrefix Invalid gateway type: $gatewayType (not a gateway role)")
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Check if user has enabled this gateway type
        val currentRoles = emergentRoleManager.currentMeshRoles.value
        if (gatewayType !in currentRoles) {
            logger(Log.INFO, "$logPrefix Gateway type $gatewayType disabled by user")
            return GatewaySelectionResult.GatewayDisabledByUser
        }
        
        // Get all nodes offering this gateway (from topology map)
        val availableGateways = originatingMessageManager.getNodesWithRole(gatewayType)
        if (availableGateways.isEmpty()) {
            logger(Log.WARN, "$logPrefix No gateways available for $gatewayType")
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Calculate suitability and select best
        val rankedGateways = availableGateways
            .map { node ->
                GatewayNode(
                    nodeAddress = node.nodeAddress,
                    suitability = node.calculateGatewaySuitability(gatewayType),
                    hopCount = getHopCount(node.nodeAddress)
                )
            }
            .sortedByDescending { it.suitability }
        
        val best = rankedGateways.first()
        logger(Log.INFO, 
            "$logPrefix Selected gateway ${best.nodeAddress.addressToDotNotation()} " +
            "for $gatewayType (suitability=${best.suitability})"
        )
        
        return GatewaySelectionResult.SingleGateway(
            nodeAddress = best.nodeAddress,
            suitability = best.suitability,
            hopCount = best.hopCount
        )
    }
    
    /**
     * Select multiple gateways for multiplexed routing
     */
    fun selectMultipleGateways(
        gatewayType: MeshRole,
        maxCount: Int = 3,
        strategy: DistributionStrategy = DistributionStrategy.WEIGHTED
    ): GatewaySelectionResult {
        // Check user preferences
        val currentRoles = emergentRoleManager.currentMeshRoles.value
        if (gatewayType !in currentRoles) {
            return GatewaySelectionResult.GatewayDisabledByUser
        }
        
        // Get available gateways
        val availableGateways = originatingMessageManager.getNodesWithRole(gatewayType)
        if (availableGateways.isEmpty()) {
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Rank and select top N
        val rankedGateways = availableGateways
            .map { node ->
                val suitability = node.calculateGatewaySuitability(gatewayType)
                GatewayNode(
                    nodeAddress = node.nodeAddress,
                    suitability = suitability,
                    hopCount = getHopCount(node.nodeAddress),
                    weight = calculateWeight(suitability, strategy)
                )
            }
            .sortedByDescending { it.suitability }
            .take(maxCount)
        
        logger(Log.INFO, 
            "$logPrefix Selected ${rankedGateways.size} gateways for $gatewayType: " +
            rankedGateways.joinToString { "${it.nodeAddress.addressToDotNotation()}(${it.suitability})" }
        )
        
        return GatewaySelectionResult.MultipleGateways(
            gateways = rankedGateways,
            distributionStrategy = strategy
        )
    }
    
    /**
     * Calculate distribution weight based on strategy
     */
    private fun calculateWeight(suitability: Float, strategy: DistributionStrategy): Float {
        return when (strategy) {
            DistributionStrategy.ROUND_ROBIN -> 1f
            DistributionStrategy.WEIGHTED -> suitability
            DistributionStrategy.LATENCY_AWARE -> suitability  // Already includes latency
            DistributionStrategy.FAILOVER -> if (suitability > 0.8f) 1f else 0.1f
        }
    }
    
    /**
     * Get hop count to reach a node
     */
    private fun getHopCount(nodeAddress: Int): Int {
        return originatingMessageManager.getOriginatorMessages()[nodeAddress]?.hopCount ?: Int.MAX_VALUE
    }
}
```

---

### 4. GatewayRouter Component (NEW)

#### 4.1 GatewayRouter Class
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewayRouter.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes packets through selected gateways with multiplexing support.
 * 
 * Two main behaviors:
 * 1. CLIENT NODE: Select gateway from topology, route packet to gateway node
 * 2. GATEWAY NODE: Route packet through configured proxy (Tor/etc)
 */
class GatewayRouter(
    private val gatewaySelector: GatewaySelector,
    private val virtualNode: VirtualNode,
    private val logger: MNetLogger,
    private val localNodeAddress: Int
) {
    
    private val logPrefix = "[GatewayRouter ${localNodeAddress.addressToDotNotation()}]"
    
    // Round-robin counter for multiplexing
    private val roundRobinCounter = AtomicInteger(0)
    
    // Cache of active gateway pools (per gateway type)
    private val gatewayPools: MutableMap<MeshRole, List<GatewayNode>> = mutableMapOf()
    
    /**
     * Route packet through appropriate gateway based on destination.
     * CLIENT NODE behavior: Select gateway, route to gateway node
     */
    fun routeToGateway(
        packet: VirtualPacket,
        gatewayType: MeshRole
    ): Boolean {
        // Check if THIS node is a gateway (should route through proxy instead)
        if (virtualNode.isGatewayNode(gatewayType)) {
            return routeThroughProxyAsGateway(packet, gatewayType)
        }
        
        // CLIENT NODE: Select gateways (use cached pool or refresh)
        val gateways = getOrRefreshGatewayPool(gatewayType)
        
        when {
            gateways.isEmpty() -> {
                logger(Log.WARN, "$logPrefix No gateways available for $gatewayType, falling back to direct routing")
                return virtualNode.route(packet)
            }
            
            gateways.size == 1 -> {
                // Single gateway - simple routing
                return routeViaGatewayNode(packet, gateways.first())
            }
            
            else -> {
                // Multiple gateways - use multiplexing
                return routeViaMultiplexedGateways(packet, gateways)
            }
        }
    }
    
    /**
     * GATEWAY NODE behavior: Route packet through configured proxy
     * Integrates with existing VirtualNode.routeViaProxy() method
     */
    private fun routeThroughProxyAsGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
        logger(Log.INFO, "$logPrefix This node is a $gatewayType, routing through proxy")
        
        // Use existing proxy routing logic from VirtualNode (line ~832)
        return try {
            virtualNode.routeViaProxy(packet)
            true
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix Failed to route via proxy: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get gateway pool, refresh if stale
     */
    private fun getOrRefreshGatewayPool(gatewayType: MeshRole): List<GatewayNode> {
        // Check if pool exists and is fresh (< 30 seconds old)
        val cached = gatewayPools[gatewayType]
        if (cached != null && isPoolFresh(cached)) {
            return cached
        }
        
        // Refresh gateway pool
        val result = gatewaySelector.selectMultipleGateways(
            gatewayType = gatewayType,
            maxCount = 3,  // Use up to 3 gateways
            strategy = DistributionStrategy.WEIGHTED
        )
        
        val newPool = when (result) {
            is GatewaySelectionResult.MultipleGateways -> result.gateways
            is GatewaySelectionResult.SingleGateway -> 
                listOf(GatewayNode(result.nodeAddress, result.suitability, result.hopCount))
            else -> emptyList()
        }
        
        gatewayPools[gatewayType] = newPool
        return newPool
    }
    
    /**
     * Check if gateway pool is still fresh
     */
    private fun isPoolFresh(pool: List<GatewayNode>): Boolean {
        // Pool is fresh if all gateways were seen recently
        val topology = gatewaySelector.originatingMessageManager.getTopologyMap()
        return pool.all { gateway ->
            val nodeInfo = topology[gateway.nodeAddress]
            nodeInfo != null && (System.currentTimeMillis() - nodeInfo.lastSeen) < 30_000
        }
    }
    
    /**
     * CLIENT NODE: Route via single gateway node
     */
    private fun routeViaGatewayNode(packet: VirtualPacket, gateway: GatewayNode): Boolean {
        logger(Log.DEBUG, "$logPrefix CLIENT: Routing via gateway ${gateway.nodeAddress.addressToDotNotation()}")
        
        // Modify packet to route through gateway node
        val gatewayPacket = packet.copy(
            header = packet.header.copy(
                toAddr = gateway.nodeAddress  // Set gateway as next hop
            )
        )
        
        return virtualNode.route(gatewayPacket)
    }
    
    /**
     * CLIENT NODE: Route via multiple gateways (multiplexing)
     */
    private fun routeViaMultiplexedGateways(packet: VirtualPacket, gateways: List<GatewayNode>): Boolean {
        // Select gateway using round-robin (can be enhanced with weighted selection)
        val index = roundRobinCounter.getAndIncrement() % gateways.size
        val selectedGateway = gateways[index]
        
        logger(Log.DEBUG, 
            "$logPrefix Multiplexing: selected gateway ${selectedGateway.nodeAddress.addressToDotNotation()} " +
            "(${index + 1}/${gateways.size})"
        )
        
        return routeViaGatewayNode(packet, selectedGateway)
    }
    
    /**
     * Clear gateway pool cache (call when topology changes significantly)
     */
    fun clearGatewayPools() {
        gatewayPools.clear()
        logger(Log.INFO, "$logPrefix Cleared gateway pool cache")
    }
}
```

---

### 5. VirtualNode Integration

#### 5.1 Add GatewayRouter to VirtualNode
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**Add after EmergentRoleManager initialization** (around line 200):
```kotlin
// === Gateway selector and router ===
protected val gatewaySelector: GatewaySelector by lazy {
    GatewaySelector(
        originatingMessageManager = originatingMessageManager,
        emergentRoleManager = emergentRoleManager,
        logger = logger,
        localNodeAddress = addressAsInt
    )
}

protected val gatewayRouter: GatewayRouter by lazy {
    GatewayRouter(
        gatewaySelector = gatewaySelector,
        virtualNode = this,
        logger = logger,
        localNodeAddress = addressAsInt
    )
}
```

#### 5.2 Add Gateway Routing Methods
**Add to VirtualNode class**:
```kotlin
/**
 * Check if this node is acting as a gateway of given type
 */
fun isGatewayNode(gatewayType: MeshRole): Boolean {
    return emergentRoleManager.currentMeshRoles.value.contains(gatewayType)
}

/**
 * Route packet through gateway based on destination analysis
 * CLIENT NODE: Select gateway from topology, route to gateway
 * GATEWAY NODE: Route through proxy
 */
fun routeThroughGateway(packet: VirtualPacket): Boolean {
    // Determine gateway type needed based on destination
    val gatewayType = determineGatewayType(packet)
    
    return if (gatewayType != null) {
        gatewayRouter.routeToGateway(packet, gatewayType)
    } else {
        // No gateway needed, route directly
        route(packet)
    }
}

/**
 * Determine which gateway type is needed for this packet
 * @return Gateway type (TOR/CLEARNET/I2P) or null for direct routing
 */
private fun determineGatewayType(packet: VirtualPacket): MeshRole? {
    // TODO: Implement packet inspection logic
    // Phase 1: Explicit tagging (application layer specifies gateway)
    // Phase 2: Destination-based (.onion → TOR, .i2p → I2P, else CLEARNET)
    // Phase 3: Port-based (443 → CLEARNET, 9150 → TOR, 7657 → I2P)
    
    // For now, return null (no gateway routing until classification implemented)
    return null
}

/**
 * Route packet through configured proxy (Tor/etc)
 * GATEWAY NODE behavior - called by GatewayRouter
 * Enhances existing routeViaProxy() method (line ~832)
 */
fun routeViaProxy(packet: VirtualPacket): Boolean {
    val host = proxyHost ?: return false
    val port = proxyPort ?: return false
    
    try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        val socket = Socket(proxy)
        socket.getOutputStream().write(packet.data)
        socket.close()
        return true
    } catch (e: Exception) {
        logger(Log.ERROR, "$logPrefix Failed to route via proxy: ${e.message}", e)
        return false
    }
}
```

---

## 🔄 IMPLEMENTATION SEQUENCE

### Phase 1: Data Structures & Topology Integration (Days 1-2)
**Priority**: HIGH  
**Risk**: LOW

1. **Create `NodeTopologyInfo.kt`**
   - Data class storing neighbors, ALL meshRoles, fitness, centrality, pingTime
   - Methods: `hasRole()`, `isGatewayNode()`, `calculateGatewaySuitability()`
   - Companion object with GATEWAY_ROLES constant
   - Unit tests for suitability calculation
   
2. **Create `GatewaySelectionResult.kt`**
   - Sealed class with variants (SingleGateway, MultipleGateways, NoGatewayAvailable, GatewayDisabledByUser)
   - Unit tests for all variants

3. **Update `OriginatingMessageManager.kt`** ⚠️ **CRITICAL - INTEGRATE WITH EXISTING**
   - **CHANGE**: Modify existing `topologyMap` type from `Map<Int, Set<Int>>` to `Map<Int, NodeTopologyInfo>`
   - Add `_topologyMapFlow` and `topologyMapFlow` for observers
   - Update `getTopologyMap()` return type
   - Add `getNodesWithRole(role: MeshRole)` method
   - Add `getGatewayNodes()` method
   - **ENHANCE**: Update `onReceiveOriginatingMessage()` (line ~359) to populate `NodeTopologyInfo` instead of just neighbors
   - Store ALL roles (gateway + intelligence) for future use
   - Log gateway roles (TOR/CLEARNET/I2P) at INFO level
   - Log intelligence roles (STORAGE/COMPUTE) at DEBUG level

**Testing**: 
- Unit tests for NodeTopologyInfo methods
- Integration test: Send MmcpOriginatorMessage with roles, verify topology map storage
- Verify STORAGE/COMPUTE roles stored but NOT used for routing

---

### Phase 2: Gateway Selection (Days 3-4)
**Priority**: HIGH  
**Risk**: MEDIUM

1. **Create `GatewaySelector.kt`**
   - Implement `selectGateway()` - only accepts TOR/CLEARNET/I2P roles
   - Implement `selectMultipleGateways()` - rank by suitability (0.3*centrality + 0.4*fitness + 0.3*latency)
   - Integration with `EmergentRoleManager` for user preferences
   - Validation: Reject non-gateway roles (STORAGE/COMPUTE/MESH_ROUTER/MESH_PARTICIPANT)
   - Add comprehensive logging

2. **Unit Tests**
   - Mock topology with 5 nodes (2 TOR, 1 CLEARNET, 1 I2P, 1 STORAGE)
   - Verify STORAGE node NOT selected as gateway
   - Verify suitability ranking
   - Verify user preference filtering

**Testing**: 
- Unit tests with mock topology
- Integration tests with 3-5 nodes
- Negative test: Verify STORAGE_NODE not selected for gateway routing

---

### Phase 3: Gateway Routing - CLIENT NODE (Days 5-6)
**Priority**: HIGH  
**Risk**: HIGH

1. **Create `GatewayRouter.kt`**
   - Implement `routeToGateway()` - CLIENT behavior
   - Implement `routeViaGatewayNode()` - modify packet toAddr to gateway
   - Implement `routeViaMultiplexedGateways()` - round-robin selection
   - Implement `getOrRefreshGatewayPool()` - 30s cache
   - Add `routeThroughProxyAsGateway()` - GATEWAY behavior (calls VirtualNode.routeViaProxy)

2. **VirtualNode Integration - CLIENT SIDE**
   - Add `gatewaySelector` and `gatewayRouter` fields (after line 226)
   - Add `isGatewayNode(type)` method
   - Add `routeThroughGateway(packet)` method
   - Implement `determineGatewayType(packet)` - Phase 1: return null (no classification yet)

**Testing**:
- Routing tests with simulated gateways (3 nodes: client + 2 gateways)
- Multiplexing verification (round-robin distribution across 2 TOR gateways)
- Verify CLIENT node routes TO gateway, not through proxy

---

### Phase 4: Gateway Routing - GATEWAY NODE (Days 7-8)
**Priority**: HIGH  
**Risk**: MEDIUM

**⚠️ BREAKING CHANGE NOTE**: 
Before implementing Phase 4, the following components must be updated to use the new topology format:
- **EmergentRoleManager**: Update centrality calculation to use `NodeTopologyInfo.neighbors` instead of `Set<Int>`
- **Any tests**: Update topology map assertions from `Map<Int, Set<Int>>` to `Map<Int, NodeTopologyInfo>`
- **Migration commands documented in KNOWLEDGE-11172025.md**

**Current TODOs (from INTERIM_COMMIT_LOG.md)**:
1. ✅ Phase 1 Complete: NodeTopologyInfo, GatewaySelectionResult, GatewaySelector, GatewayRouter created
2. ✅ OriginatingMessageManager enhanced with NodeTopologyInfo storage
3. ⏳ **NEXT**: Update EmergentRoleManager to use `getTopologyMapInfo()` instead of deprecated `getTopologyMap()`
4. ⏳ **CURRENT**: Implement VirtualNode integration (Phase 4)
5. ⏳ Compile all changes and fix any type errors
6. ⏳ Create Part 4 tests from REFACTORING_PLAN_COMPREHENSIVE_v2.md

1. **VirtualNode Integration - GATEWAY SIDE**
   - Enhance existing `routeViaProxy()` method (line ~832) to return Boolean
   - Integrate with `GatewayRouter.routeThroughProxyAsGateway()`
   - Ensure gateway nodes route packets through configured proxy

2. **End-to-End Flow**
   - CLIENT NODE: Packet → routeThroughGateway() → selectGateway() → route(to gateway)
   - GATEWAY NODE: Receive packet → routeViaProxy() → proxy (Tor/etc)

**Testing**:
- E2E test: Client → Gateway selection → Route to gateway → Gateway routes to proxy
- Verify proxy configuration (proxyHost, proxyPort)
- Test with Tor proxy (port 9150)

---

### Phase 5: Traffic Classification & Integration Testing (Days 9-10)
**Priority**: MEDIUM  
**Risk**: HIGH

1. **Implement Traffic Classification**
   - Phase 5a: Explicit tagging (application specifies gateway type in packet metadata)
   - Phase 5b (future): Destination-based (.onion → TOR, .i2p → I2P, else CLEARNET)
   - Phase 5c (future): Port-based (443 → CLEARNET, 9150 → TOR, 7657 → I2P)

2. **End-to-End Tests**
   - 10-node mesh: 3 TOR gateways, 2 CLEARNET gateways, 1 I2P gateway, 4 clients
   - Verify role propagation (ALL 7 types in topology)
   - Verify gateway selection (only TOR/CLEARNET/I2P used for routing)
   - Verify traffic multiplexing (round-robin across multiple TOR gateways)
   - Verify STORAGE/COMPUTE roles stored but NOT used for routing

3. **Performance Testing**
   - Measure gateway selection latency (< 50ms target)
   - Verify no performance regression in direct routing
   - Test with 50+ nodes

4. **Edge Cases**
   - All gateways offline → fallback to direct routing
   - Gateway role changes dynamically → pool refresh
   - User disables gateway mid-session → stop gateway routing
   - Node with STORAGE+TOR roles → use TOR for routing, ignore STORAGE

**Testing**:
- Full integration test suite
- Performance benchmarks
- Stress tests (100+ nodes)
- Role isolation tests (verify STORAGE/COMPUTE ignored)

---

## ❓ OPEN QUESTIONS & UNCERTAINTIES

### 1. Official Meshrabiya Repository Alignment
**Question**: Does the official UstadMobile/Meshrabiya repo have gateway routing already?

**Action Required**: 
- Need access to official repo to check:
  - `VirtualNode.kt` routing logic
  - Existing `topologyMap` usage in `OriginatingMessageManager.kt`
  - Gateway selection algorithms
  - Any existing multiplexing support

**Resolution**: User to provide official files or confirm approach

---

### 2. Topology Map Type Change Impact
**Question**: Changing `topologyMap` from `Map<Int, Set<Int>>` to `Map<Int, NodeTopologyInfo>` - what breaks?

**Concerns**:
- Are there other components reading `topologyMap.getTopologyMap()`?
- Does EmergentRoleManager use topology data for centrality calculation?
- Any tests dependent on the old Set<Int> format?

**Action Required**: 
- Grep for all usages of `getTopologyMap()` and `topologyMap`
- Verify EmergentRoleManager centrality calculation still works
- Update any dependent tests

**Resolution**: User to confirm no other dependencies, or we add migration compatibility

---

### 3. Traffic Classification Strategy
**Question**: How should we determine which gateway type to use for a given packet?

**Options**:
A. **Destination-based** (IP address ranges)
   - .onion domains → TOR_GATEWAY
   - .i2p domains → I2P_GATEWAY  
   - Everything else → CLEARNET_GATEWAY
   
B. **Port-based** (Protocol inspection)
   - Port 443 (HTTPS) → CLEARNET_GATEWAY
   - Port 9150 (Tor SOCKS) → TOR_GATEWAY
   - Port 7657 (I2P SAM) → I2P_GATEWAY
   
C. **Explicit tagging** (Application layer)
   - VPN apps tag packets with desired gateway
   - MeshTrafficRouter specifies gateway type

**Recommendation**: Start with **Option C** (explicit tagging) for MVP, add Options A+B in Phase 5

**Action Required**: User feedback on classification strategy for Phase 5

---

### 4. Multiplexing Algorithm
**Question**: Which multiplexing strategy should be the default?

**Options**:
- **Round-robin**: Simple, equal distribution (RECOMMENDED for MVP)
- **Weighted**: Based on suitability scores (better but more complex)
- **Latency-aware**: Prefer faster gateways (needs RTT tracking)
- **Failover**: Primary gateway + backups (simplest, least efficient)

**Recommendation**: Implement round-robin first (Phase 3), make strategy configurable

**Action Required**: User confirmation of default strategy

---

### 5. Gateway Pool Refresh Rate
**Question**: How often should we refresh the gateway pool?

**Trade-offs**:
- **Fast refresh (10s)**: Responds quickly to topology changes, higher overhead
- **Slow refresh (60s)**: Lower overhead, slower to adapt
- **Event-driven**: Refresh on topology change events (optimal but complex)

**Current Implementation**: 30-second cache in `getOrRefreshGatewayPool()`

**Recommendation**: Keep 30-second refresh with event-driven invalidation in Phase 5

**Action Required**: User input on refresh policy

---

### 6. Proxy Configuration for Gateway Nodes
**Question**: How are proxy settings (proxyHost, proxyPort) configured on gateway nodes?

**Current State**: VirtualNode has `proxyHost` and `proxyPort` fields (line ~832)

**Clarification Needed**:
- Are these set by EmergentRoleManager when TOR_GATEWAY role activated?
- Is there a UI for users to configure proxy settings?
- Should proxy settings auto-detect (e.g., Tor running on localhost:9150)?

**Action Required**: Confirm proxy configuration flow

---

## 📄 FILES TO REVIEW FROM OFFICIAL REPO

Please provide these files from `https://github.com/UstadMobile/Meshrabiya`:

1. **VirtualNode.kt** - Check for existing routing logic
2. **OriginatingMessageManager.kt** - Verify official topology building
3. **EmergentRoleManager.kt** - Confirm role management approach
4. **Any gateway-related files** - See if gateway selection exists

**Format**: Copy file contents into a response document with inline answers/notes

---

## 📊 SUCCESS METRICS

### Functional Metrics
- ✅ 100% of ALL roles (7 types) propagated in originator messages (already done in Phase 3)
- ✅ 100% of received roles stored in topology map (Phase 1)
- ✅ Gateway selection ONLY considers TOR/CLEARNET/I2P roles (Phase 2)
- ✅ STORAGE/COMPUTE roles stored but NOT used for routing (Phase 1-2)
- ✅ Gateway selection completes in < 50ms (99th percentile) (Phase 2)
- ✅ CLIENT nodes route packets TO gateway nodes (Phase 3)
- ✅ GATEWAY nodes route packets THROUGH proxy (Phase 4)
- ✅ Traffic multiplexed across 2+ gateways when available (Phase 3)
- ✅ Zero gateway traffic when user disables all gateway roles (Phase 2)

### Performance Metrics
- ✅ < 5% CPU overhead for gateway selection
- ✅ < 10MB memory for topology map (100 nodes with all 7 roles)
- ✅ < 100ms latency added by gateway routing
- ✅ No packet loss due to gateway routing logic
- ✅ Gateway pool cache reduces lookup overhead (30s refresh)

### Quality Metrics
- ✅ 100% unit test coverage for new components
- ✅ Integration tests for CLIENT → GATEWAY → PROXY flow
- ✅ Integration tests for 3, 5, 10, 50 node scenarios
- ✅ Role isolation tests (verify STORAGE/COMPUTE not used for routing)
- ✅ Zero regressions in existing mesh functionality
- ✅ Code review approval from 2+ team members

---

## 🚀 NEXT STEPS

1. **User Review & Clarifications** (TODAY)
   - Review updated plan with clarifications integrated
   - Answer 6 open questions (topology impact, traffic classification, etc.)
   - Provide official repo files if available (OriginatingMessageManager.kt, VirtualNode.kt)
   - Approve or request changes

2. **Pre-Implementation Verification** (Day 0)
   - Grep for all usages of `getTopologyMap()` to assess type change impact
   - Verify EmergentRoleManager doesn't depend on old topology format
   - Check for any tests using `Map<Int, Set<Int>>` format
   - Document breaking changes if any

3. **Phase 1 Implementation** (Days 1-2)
   - Create data structures (NodeTopologyInfo, GatewaySelectionResult)
   - **CRITICAL**: Enhance existing `topologyMap` in OriginatingMessageManager
   - Update `onReceiveOriginatingMessage()` to store ALL roles
   - Add `getNodesWithRole()` and `getGatewayNodes()` methods
   - Write unit tests for topology storage (verify STORAGE/COMPUTE stored but not used for routing)

4. **Phase 2 Implementation** (Days 3-4)
   - Create GatewaySelector component
   - Validate only TOR/CLEARNET/I2P roles accepted
   - Implement suitability ranking
   - Integrate with EmergentRoleManager for user preferences
   - Write unit tests with mixed roles (verify STORAGE ignored)

5. **Incremental Deployment**
   - Deploy Phase 1 → verify role propagation and topology storage
   - Deploy Phase 2 → verify gateway selection (only TOR/CLEARNET/I2P)
   - Deploy Phase 3 → verify CLIENT node routing
   - Deploy Phase 4 → verify GATEWAY node proxy routing
   - Full deployment after all phases validated

---

## 📝 APPENDIX

### A. MeshRole Categorization
```kotlin
// From MeshRole.kt - ALL 7 roles broadcast in MmcpOriginatorMessage
enum class MeshRole {
    MESH_PARTICIPANT,    // Base role (always active)
    
    // === GATEWAY ROLES (used for routing) ===
    TOR_GATEWAY,         // ⭐ Tor network gateway - route traffic through Tor proxy
    CLEARNET_GATEWAY,    // ⭐ Internet gateway - route traffic through clearnet
    I2P_GATEWAY,         // ⭐ I2P network gateway - route traffic through I2P proxy
    
    // === INTELLIGENCE ROLES (topology optimization only) ===
    STORAGE_NODE,        // 🔍 Distributed storage capability (future intelligence)
    COMPUTE_NODE,        // 🔍 Distributed compute capability (future intelligence)
    MESH_ROUTER          // 🔍 Mesh traffic routing capability (future intelligence)
}
```

**Key Distinction**:
- **Gateway Roles (TOR/CLEARNET/I2P)**: Used by GatewaySelector for packet routing
- **Intelligence Roles (STORAGE/COMPUTE/MESH_ROUTER)**: Stored in topology but NOT used for gateway selection
- **All roles**: Broadcast in MmcpOriginatorMessage, stored in NodeTopologyInfo

### B. MmcpOriginatorMessage Schema
```kotlin
// Already implemented in Phase 3 ✅
class MmcpOriginatorMessage(
    messageId: Int,
    val sentTime: Long,
    val pingTimeSum: Short,
    val connectConfig: Any?,
    val neighbors: List<Int>,           // ✅ Topology (currently stored as Set<Int>)
    val centralityScore: Float,         // ✅ Selection metric
    val fitnessScore: Float,            // ✅ Selection metric
    val meshRoles: Set<MeshRole>        // ⭐ ALL 7 ROLES BROADCAST
)
```

**Current Topology Storage** (needs enhancement):
```kotlin
// OriginatingMessageManager.kt line 359
topologyMap[fromAddr] = mmcpMessage.neighbors.toSet()  // Currently: Map<Int, Set<Int>>

// NEEDS TO BECOME:
topologyMap[fromAddr] = NodeTopologyInfo(
    nodeAddress = fromAddr,
    neighbors = mmcpMessage.neighbors.toSet(),
    meshRoles = mmcpMessage.meshRoles,  // Store ALL 7 types
    centralityScore = mmcpMessage.centralityScore,
    fitnessScore = mmcpMessage.fitnessScore,
    pingTime = mmcpMessage.pingTimeSum
)
```

### C. Architecture Diagram - CLIENT and GATEWAY Flows
```
┌──────────────────────────────────────────────────────────────────┐
│                         VirtualNode                              │
├──────────────────────────────────────────────────────────────────┤
│  OriginatingMessageManager                                       │
│  ├─ makeOriginatingMessage() ────> MmcpOriginatorMessage ✅     │
│  │                                  ├─ meshRoles (ALL 7 types) ⭐│
│  │                                  ├─ centralityScore           │
│  │                                  ├─ fitnessScore              │
│  │                                  └─ neighbors                 │
│  │                                                                │
│  └─ onReceiveOriginatingMessage()                                │
│      └─> topologyMap[addr] = NodeTopologyInfo ⭐ (ENHANCED)     │
│           ├─ meshRoles (ALL 7 types stored)                      │
│           ├─ centralityScore                                     │
│           ├─ fitnessScore                                        │
│           ├─ neighbors                                           │
│           └─ pingTime                                            │
├──────────────────────────────────────────────────────────────────┤
│  GatewaySelector ⭐ (NEW)                                        │
│  ├─ selectGateway(type) ──────────────────┐                     │
│  │   ├─ VALIDATE: Only TOR/CLEARNET/I2P   │                     │
│  │   ├─ Filter by role from topologyMap   │                     │
│  │   ├─ Rank by suitability               │                     │
│  │   └─ Return best gateway                │                     │
│  │                                          │                     │
│  └─ selectMultipleGateways(type, n) ──────┤                     │
│      ├─ VALIDATE: Only TOR/CLEARNET/I2P   │                     │
│      ├─ Filter (IGNORE STORAGE/COMPUTE)   │                     │
│      ├─ Rank by suitability               │                     │
│      └─ Return top N gateways              │                     │
│                                             ▼                     │
│  GatewayRouter ⭐ (NEW)                                          │
│  ├─ routeToGateway(packet, type)                                │
│  │   ├─ IF this node is GATEWAY → routeThroughProxyAsGateway() │
│  │   └─ ELSE → routeViaGatewayNode() (CLIENT)                  │
│  │                                                                │
│  ├─ CLIENT behavior: Select gateway, route packet TO gateway    │
│  └─ GATEWAY behavior: Route packet THROUGH proxy (Tor/etc)      │
└──────────────────────────────────────────────────────────────────┘

CLIENT NODE Flow:
1. App → routeThroughGateway(packet)
2. determineGatewayType(packet) → TOR_GATEWAY
3. GatewayRouter.routeToGateway(packet, TOR_GATEWAY)
4. Check: isGatewayNode(TOR_GATEWAY)? NO (this is CLIENT)
5. GatewaySelector → Query topologyMap for TOR_GATEWAY nodes
6. GatewaySelector → Rank by fitness/centrality/latency
7. GatewayRouter → Modify packet.toAddr = selectedGateway
8. VirtualNode.route() → Send packet TO gateway node
9. Gateway node receives packet...

GATEWAY NODE Flow:
1. Gateway receives packet from client
2. GatewayRouter.routeToGateway(packet, TOR_GATEWAY)
3. Check: isGatewayNode(TOR_GATEWAY)? YES (this is GATEWAY)
4. GatewayRouter.routeThroughProxyAsGateway(packet, TOR_GATEWAY)
5. VirtualNode.routeViaProxy(packet)
6. Proxy (Tor) → Forward to Tor network
7. Packet exits through Tor
```

**Role Usage**:
- ✅ **TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY**: Used for gateway selection and routing
- ❌ **STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER**: Stored in topology, NOT used for gateway routing
- 🔮 **Future**: STORAGE/COMPUTE roles used for distributed intelligence (job scheduling, data replication)

---

**END OF PLAN**

This plan is ready for user review and implementation. Please answer the open questions to proceed.
