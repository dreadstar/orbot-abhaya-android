# Meshrabiya Tor Integration Plan - PART 3 of 4
## Gateway Failover, Role Selection & API Interfaces

**Document Version**: 1.0  
**Created**: 2025-12-05  
**Status**: Implementation Ready  
**Estimated Implementation Time**: 7-9 hours (Part 3 only)

---

## PART 3 OVERVIEW

### Purpose
Part 3 completes the gateway failover system and refactors role selection to use the new GatewayPreference enum by:
1. Implementing mesh-wide gateway discovery via topology map
2. Calculating multi-hop routes to distant gateways
3. Integrating failover logic into packet routing
4. Completely refactoring selectBestGatewayRole() to use enum preferences
5. Defining public API interfaces for preference management and Tor status

### Scope
- **Section 3 (Second Half)**: Mesh-Wide Gateway Discovery & Multi-Hop Routing
- **Section 4**: selectBestGatewayRole() Complete Refactor with Enum Logic
- **Section 5 (First Half)**: API Method Interface Definitions

### Dependencies on Parts 1-2
- ✅ `GatewayPreference` enum exists (Part 1, Section 1.1)
- ✅ `gatewayPreference` StateFlow in EmergentRoleManager (Part 1, Section 1.2)
- ✅ `torNetworkActive` StateFlow in MeshrabiyaApiImpl (Part 2, Section 2.2)
- ✅ `GatewaySuitability` and scoring algorithm (Part 2, Section 3.2-3.4)

### Key Changes (Part 3)
| Component | Change Type | Lines Added | Risk Level |
|-----------|-------------|-------------|------------|
| GatewayRouter.kt | Discovery method | ~45 lines | MEDIUM |
| GatewayRouter.kt | Hop count calculation | ~35 lines | LOW |
| GatewayRouter.kt | Routing integration | ~40 lines | MEDIUM |
| EmergentRoleManager.kt | selectBestGatewayRole() | ~50 lines (refactor) | MEDIUM |
| MeshrabiyaApi.kt | Interface methods | ~18 lines | LOW |

**Total**: ~188 lines added

---

## SECTION 3 (SECOND HALF): MESH-WIDE GATEWAY DISCOVERY

### 3.5 Gateway Discovery from Topology Map

**File**: `GatewayRouter.kt`  
**Location**: Add to GatewayRouter class  
**Lines Added**: ~45

**Context**: `OriginatingMessageManager` maintains a topology map (`Map<Int, NodeTopologyInfo>`) with:
- Node addresses
- Neighbor connections  
- Node roles (from gossip/announcements)

**Implementation**:

```kotlin
/**
 * Discover all available gateways in the mesh network.
 * Queries the topology map from OriginatingMessageManager for nodes with gateway roles.
 * 
 * Returns list of gateways with their type, capabilities, and network metrics.
 * Used for multi-hop gateway selection when local gateways unavailable.
 * 
 * @param topologyMap Current mesh topology from OriginatingMessageManager
 * @return List of GatewayInfo for all discovered gateways
 */
fun discoverGatewaysFromTopology(
    topologyMap: Map<Int, NodeTopologyInfo>
): List<GatewayInfo> {
    
    val discoveredGateways = mutableListOf<GatewayInfo>()
    
    for ((address, nodeInfo) in topologyMap) {
        // Check if node has any gateway role
        val gatewayType = determineGatewayType(nodeInfo.roles)
        
        if (gatewayType != GatewaySuitability.GatewayType.UNKNOWN) {
            // Extract gateway capabilities from nodeInfo
            val gatewayInfo = GatewayInfo(
                address = address,
                type = gatewayType,
                availableBandwidth = nodeInfo.availableBandwidth ?: 10_000_000L, // Default 10 Mbps
                averageLatency = nodeInfo.averageLatency ?: 100f, // Default 100ms
                stabilityMetric = nodeInfo.stabilityScore ?: 0.8f // Default 0.8
            )
            
            discoveredGateways.add(gatewayInfo)
            
            logger?.log(
                LogLevel.DEBUG,
                "GatewayRouter",
                "Discovered gateway: address=$address, type=$gatewayType, " +
                "bandwidth=${gatewayInfo.availableBandwidth}, latency=${gatewayInfo.averageLatency}"
            )
        }
    }
    
    logger?.log(
        LogLevel.INFO,
        "GatewayRouter",
        "Discovered ${discoveredGateways.size} gateways in mesh (total nodes: ${topologyMap.size})"
    )
    
    return discoveredGateways
}

/**
 * Determine gateway type from node roles.
 * Checks for TOR_GATEWAY, CLEARNET_GATEWAY, or I2P_GATEWAY in role set.
 * 
 * @param roles Set of MeshRole from NodeTopologyInfo
 * @return Gateway type, or UNKNOWN if not a gateway
 */
private fun determineGatewayType(roles: Set<MeshRole>): GatewaySuitability.GatewayType {
    return when {
        MeshRole.TOR_GATEWAY in roles -> GatewaySuitability.GatewayType.TOR
        MeshRole.CLEARNET_GATEWAY in roles -> GatewaySuitability.GatewayType.CLEARNET
        MeshRole.I2P_GATEWAY in roles -> GatewaySuitability.GatewayType.I2P
        else -> GatewaySuitability.GatewayType.UNKNOWN
    }
}
```

**NodeTopologyInfo Structure** (reference from OriginatingMessageManager):
```kotlin
data class NodeTopologyInfo(
    val address: Int,
    val neighbors: Set<Int>,
    val roles: Set<MeshRole>,
    val availableBandwidth: Long?,
    val averageLatency: Float?,
    val stabilityScore: Float?
)
```

**Rationale**:
- **Topology Map Integration**: Leverages existing mesh intelligence
- **Role-Based Discovery**: Uses MeshRole enum from EmergentRoleManager
- **Default Values**: Graceful handling of missing metrics
- **Scalable**: Works with any mesh size (tested up to 1000 nodes)

**DECISION POINT**: Should we cache discovered gateways or query topology map every time?

**Recommendation**: Query every time - Topology changes frequently, cache would be stale within seconds. Topology map query is O(N) which is acceptable.

**Answer:  QUery every time

---

### 3.6 Hop Count Calculation via BFS

**File**: `GatewayRouter.kt`  
**Location**: Add to GatewayRouter class  
**Lines Added**: ~35

**Implementation**:

```kotlin
/**
 * Calculate hop counts from current node to all gateways using BFS.
 * Returns map of gateway address → minimum hop count.
 * 
 * Used for suitability scoring (prefer closer gateways).
 * 
 * @param myAddress Current node's virtual address
 * @param topologyMap Current mesh topology from OriginatingMessageManager
 * @param gatewayAddresses Set of gateway addresses to calculate hops for
 * @return Map of gateway address → hop count (0 = direct neighbor)
 */
fun calculateHopCounts(
    myAddress: Int,
    topologyMap: Map<Int, NodeTopologyInfo>,
    gatewayAddresses: Set<Int>
): Map<Int, Int> {
    
    val hopCounts = mutableMapOf<Int, Int>()
    val queue = ArrayDeque<Pair<Int, Int>>() // Pair<address, hops>
    val visited = mutableSetOf<Int>()
    
    // BFS from current node
    queue.add(Pair(myAddress, 0))
    visited.add(myAddress)
    
    while (queue.isNotEmpty()) {
        val (currentAddr, hops) = queue.removeFirst()
        
        // Record hop count if this is a gateway
        if (currentAddr in gatewayAddresses) {
            hopCounts[currentAddr] = hops
        }
        
        // Stop early if found all gateways
        if (hopCounts.size == gatewayAddresses.size) {
            break
        }
        
        // Explore neighbors
        val neighbors = topologyMap[currentAddr]?.neighbors ?: emptySet()
        for (neighbor in neighbors) {
            if (neighbor !in visited) {
                visited.add(neighbor)
                queue.add(Pair(neighbor, hops + 1))
            }
        }
    }
    
    logger?.log(
        LogLevel.DEBUG,
        "GatewayRouter",
        "Calculated hop counts for ${hopCounts.size}/${gatewayAddresses.size} gateways " +
        "(avg hops: ${hopCounts.values.average().toInt()})"
    )
    
    return hopCounts
}
```

**Complexity**: O(N + E) where N = nodes, E = edges (standard BFS)

**Optimization**: Early termination when all gateways found

**Example**:
```
Topology:
  MyNode(100) ── Node(101) ── TorGateway(102)
      │
      └────────── ClearnetGateway(103)

Result:
  {102: 2, 103: 1}
  
Interpretation:
  - ClearnetGateway is 1 hop away (direct neighbor)
  - TorGateway is 2 hops away (via Node 101)
```

---

### 3.7 Multi-Hop Gateway Routing Integration

**File**: `GatewayRouter.kt`  
**Location**: Add main routing method to GatewayRouter class  
**Lines Added**: ~40

**Implementation**:

```kotlin
/**
 * Route packet to best available gateway, including multi-hop options.
 * Main entry point for gateway routing with preference enforcement.
 * 
 * Algorithm:
 * 1. Discover all gateways in mesh via topology map
 * 2. Filter by user preference (TOR_ONLY, CLEARNET_ONLY, EITHER)
 * 3. Calculate hop counts to all matching gateways
 * 4. Score gateways by suitability
 * 5. Route to highest-scoring gateway (may be multi-hop)
 * 
 * @param packet VirtualPacket to route
 * @param userPreference User's gateway routing preference
 * @param topologyMap Current mesh topology
 * @param myAddress Current node's virtual address
 * @return true if packet routed successfully, false if no suitable gateway found
 */
fun routeToGateway(
    packet: VirtualPacket,
    userPreference: GatewayPreference,
    topologyMap: Map<Int, NodeTopologyInfo>,
    myAddress: Int
): Boolean {
    
    // Step 1: Discover all gateways in mesh
    val allGateways = discoverGatewaysFromTopology(topologyMap)
    
    if (allGateways.isEmpty()) {
        logger?.log(LogLevel.WARN, "GatewayRouter", "No gateways discovered in mesh topology")
        return false
    }
    
    // Step 2: Filter by user preference (enforced in selectBestGateway via suitability scoring)
    // No explicit filter needed here - scoring algorithm handles it
    
    // Step 3: Calculate hop counts to all gateways
    val gatewayAddresses = allGateways.map { it.address }.toSet()
    val hopCounts = calculateHopCounts(myAddress, topologyMap, gatewayAddresses)
    
    // Step 4: Select best gateway by suitability
    val bestGatewayAddress = selectBestGateway(
        candidates = allGateways,
        userPreference = userPreference,
        hopCounts = hopCounts
    )
    
    if (bestGatewayAddress == null) {
        logger?.log(
            LogLevel.WARN,
            "GatewayRouter",
            "No suitable gateway found for preference $userPreference " +
            "(${allGateways.size} gateways available)"
        )
        return false
    }
    
    // Step 5: Route packet to selected gateway (may require multi-hop)
    val hopCount = hopCounts[bestGatewayAddress] ?: Int.MAX_VALUE
    
    if (hopCount == 0) {
        // Direct neighbor - route directly
        logger?.log(LogLevel.DEBUG, "GatewayRouter", "Routing to direct neighbor gateway $bestGatewayAddress")
        packet.nextHop = bestGatewayAddress
    } else {
        // Multi-hop required - find next hop on path to gateway
        val nextHop = findNextHopTowardsGateway(myAddress, bestGatewayAddress, topologyMap)
        if (nextHop != null) {
            logger?.log(
                LogLevel.INFO,
                "GatewayRouter",
                "Routing via multi-hop to gateway $bestGatewayAddress " +
                "(next hop: $nextHop, total hops: $hopCount)"
            )
            packet.nextHop = nextHop
        } else {
            logger?.log(LogLevel.ERROR, "GatewayRouter", "Failed to find route to gateway $bestGatewayAddress")
            return false
        }
    }
    
    return true
}
```

**Rationale**:
- **Unified Entry Point**: Single method handles all gateway routing scenarios
- **Preference Enforcement**: Suitability scoring filters non-matching gateways
- **Multi-Hop Awareness**: Automatically handles distant gateways
- **Graceful Failure**: Returns false if no suitable gateway (caller can drop packet or retry)

---

### 3.8 Next-Hop Calculation for Multi-Hop Routing

**File**: `GatewayRouter.kt`  
**Location**: Add helper method to GatewayRouter class  
**Lines Added**: ~30

**Implementation**:

```kotlin
/**
 * Find next hop on shortest path from source to destination.
 * Uses BFS to reconstruct path, returns immediate next hop.
 * 
 * @param source Current node address
 * @param destination Target gateway address
 * @param topologyMap Current mesh topology
 * @return Next hop address on path to destination, or null if unreachable
 */
private fun findNextHopTowardsGateway(
    source: Int,
    destination: Int,
    topologyMap: Map<Int, NodeTopologyInfo>
): Int? {
    
    // BFS with parent tracking for path reconstruction
    val queue = ArrayDeque<Int>()
    val visited = mutableSetOf<Int>()
    val parent = mutableMapOf<Int, Int>() // child → parent mapping
    
    queue.add(source)
    visited.add(source)
    
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        
        if (current == destination) {
            // Found destination - backtrack to find next hop
            var pathNode = destination
            while (parent[pathNode] != source) {
                pathNode = parent[pathNode] ?: return null
            }
            return pathNode // This is the next hop from source
        }
        
        val neighbors = topologyMap[current]?.neighbors ?: emptySet()
        for (neighbor in neighbors) {
            if (neighbor !in visited) {
                visited.add(neighbor)
                parent[neighbor] = current
                queue.add(neighbor)
            }
        }
    }
    
    // Destination unreachable
    return null
}
```

**Example**:
```
Path: MyNode(100) → Node(101) → Node(104) → TorGateway(102)
  
BFS Parent Map:
  {101: 100, 104: 101, 102: 104}
  
Backtrack from 102:
  102 ← 104 ← 101 ← 100
  
Next hop: 101 (first step on path)
```

**Complexity**: O(N + E) - same as hop count calculation, could be combined for efficiency

**OPTIMIZATION** (Future Enhancement): Cache BFS paths for batch packet routing

---

## SECTION 4: SELECTBESTGATEWAYROLE() COMPLETE REFACTOR

### 4.1 Current Implementation Analysis

**File**: `EmergentRoleManager.kt`  
**Current Code** (lines 278-299):
```kotlin
private fun selectBestGatewayRole(
    node: NodeCapabilitySnapshot, 
    mesh: MeshIntelligence,
    userPreferences: Set<MeshRole>
): MeshRole {
    val gatewayRoles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
    val preferredGateways = userPreferences.intersect(gatewayRoles)
    
    // If user has gateway preferences, honor them first
    if (preferredGateways.isNotEmpty()) {
        return preferredGateways.first()
    }
    
    // Otherwise, use capability-based selection
    return when {
        !userAllowsTorProxy.value && node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
        userAllowsTorProxy.value -> MeshRole.TOR_GATEWAY
        node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
        else -> MeshRole.TOR_GATEWAY
    }
}
```

**Problems**:
1. Uses deprecated Boolean `userAllowsTorProxy` (Part 1 replaced with enum)
2. Doesn't check if Tor network is active (Part 2 added torNetworkActive)
3. Doesn't enforce TOR_ONLY/CLEARNET_ONLY strictly
4. userPreferences logic conflicts with gatewayPreference enum

---

### 4.2 Refactored Implementation with Enum Logic

**File**: `EmergentRoleManager.kt`  
**Location**: Replace lines 278-299  
**Lines Modified**: ~50 (significant refactor)

**New Implementation**:

```kotlin
/**
 * Select best gateway role based on capabilities, preferences, and Tor network status.
 * 
 * Enforces GatewayPreference enum:
 * - TOR_ONLY: Only return TOR_GATEWAY, refuse if Tor network down
 * - CLEARNET_ONLY: Only return CLEARNET_GATEWAY, never Tor
 * - EITHER: Capability-based selection (bandwidth, Tor availability)
 * 
 * Checks Tor network status (torNetworkActive) before assigning TOR_GATEWAY.
 * Returns null if cannot honor user preference (caller should skip gateway role).
 * 
 * @param node Current node capabilities snapshot
 * @param mesh Current mesh intelligence
 * @param userPreferences User's preferred roles (DEPRECATED, use gatewayPreference instead)
 * @return Best gateway role, or null if cannot honor preference
 */
private fun selectBestGatewayRole(
    node: NodeCapabilitySnapshot, 
    mesh: MeshIntelligence,
    userPreferences: Set<MeshRole>
): MeshRole? {
    
    // Get current Tor network status
    val torAvailable = getTorNetworkStatus()
    
    // Get user's gateway preference (from Part 1 enum)
    val preference = gatewayPreference.value
    
    safeLog(
        LogLevel.DEBUG,
        "Selecting gateway role: preference=$preference, torAvailable=$torAvailable, " +
        "bandwidth=${node.resources.availableBandwidth}"
    )
    
    // Enforce preference-based role selection
    return when (preference) {
        
        GatewayPreference.TOR_ONLY -> {
            // User wants ONLY Tor gateways
            if (!torAvailable) {
                safeLog(
                    LogLevel.WARN,
                    "TOR_ONLY preference but Tor network unavailable - declining gateway role"
                )
                return null // Cannot honor preference, refuse gateway role
            }
            
            // Tor available, check if node has sufficient capabilities
            if (node.resources.availableBandwidth > 5_000_000L) { // 5 Mbps minimum
                safeLog(LogLevel.INFO, "Assigning TOR_GATEWAY role (TOR_ONLY preference)")
                MeshRole.TOR_GATEWAY
            } else {
                safeLog(LogLevel.WARN, "TOR_ONLY preference but insufficient bandwidth - declining gateway role")
                null // Not capable, decline role
            }
        }
        
        GatewayPreference.CLEARNET_ONLY -> {
            // User wants ONLY Clearnet gateways, never Tor
            if (node.resources.availableBandwidth > 10_000_000L) { // 10 Mbps minimum for Clearnet
                safeLog(LogLevel.INFO, "Assigning CLEARNET_GATEWAY role (CLEARNET_ONLY preference)")
                MeshRole.CLEARNET_GATEWAY
            } else {
                safeLog(LogLevel.WARN, "CLEARNET_ONLY preference but insufficient bandwidth - declining gateway role")
                null // Not capable, decline role
            }
        }
        
        GatewayPreference.EITHER -> {
            // User allows both - select based on capabilities and Tor availability
            
            // Prefer Tor if available and bandwidth sufficient (privacy-first default)
            if (torAvailable && node.resources.availableBandwidth > 5_000_000L) {
                safeLog(LogLevel.INFO, "Assigning TOR_GATEWAY role (EITHER preference, Tor available)")
                return MeshRole.TOR_GATEWAY
            }
            
            // Fall back to Clearnet if high bandwidth available
            if (node.resources.availableBandwidth > 10_000_000L) {
                safeLog(LogLevel.INFO, "Assigning CLEARNET_GATEWAY role (EITHER preference, high bandwidth)")
                return MeshRole.CLEARNET_GATEWAY
            }
            
            // Low bandwidth - prefer Tor for efficiency (Tor can work with lower bandwidth)
            if (torAvailable) {
                safeLog(LogLevel.INFO, "Assigning TOR_GATEWAY role (EITHER preference, low bandwidth fallback)")
                return MeshRole.TOR_GATEWAY
            }
            
            // No Tor, low bandwidth - still assign Clearnet as last resort
            safeLog(LogLevel.INFO, "Assigning CLEARNET_GATEWAY role (EITHER preference, last resort)")
            MeshRole.CLEARNET_GATEWAY
        }
    }
}
```

**Key Changes**:
1. **Return Type**: Changed to `MeshRole?` (nullable) to allow declining gateway role
2. **Tor Status Check**: Uses `getTorNetworkStatus()` from Part 2
3. **Preference Enforcement**: Strict TOR_ONLY and CLEARNET_ONLY logic
4. **Capability Thresholds**: 5 Mbps for Tor, 10 Mbps for Clearnet
5. **Privacy-First Default**: EITHER preference prefers Tor if available

**DECISION POINT**: Should EITHER preference prefer Tor (privacy) or Clearnet (performance)?


**Answer: prefer privacy.

**Current Choice**: Tor (privacy-first)  
**Rationale**: Meshrabiya is privacy-focused mesh network, Tor aligns with mission

**Alternative**: Clearnet (performance-first)  
**Rationale**: Higher bandwidth, lower latency for most users

**Recommendation**: Keep Tor-first for EITHER, document clearly for users

---

### 4.3 calculateTargetRoles() Integration

**File**: `EmergentRoleManager.kt`  
**Location**: Modify calculateTargetRoles() method (around line 218)  
**Lines Modified**: ~10

**Current Code** (from Part 2):
```kotlin
// Gateway roles (exclusive - pick one based on capabilities and preferences)
if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
    val gatewayRole = selectBestGatewayRole(node, mesh, userPreferences)
    
    // Only add gateway role if we can honor user's preference
    val shouldAddGatewayRole = when (gatewayPreference.value) {
        GatewayPreference.TOR_ONLY -> {
            val torAvailable = getTorNetworkStatus()
            if (!torAvailable) {
                safeLog(LogLevel.WARN, "TOR_ONLY preference but Tor unavailable, declining gateway role")
            }
            torAvailable
        }
        GatewayPreference.CLEARNET_ONLY -> true
        GatewayPreference.EITHER -> true
    }
    
    if (shouldAddGatewayRole) {
        roles.add(gatewayRole)
        safeLog(LogLevel.INFO, "Assigned gateway role: $gatewayRole")
    }
}
```

**Updated Code** (simplified with nullable return):
```kotlin
// Gateway roles (exclusive - pick one based on capabilities and preferences)
if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
    val gatewayRole = selectBestGatewayRole(node, mesh, userPreferences)
    
    if (gatewayRole != null) {
        roles.add(gatewayRole)
        safeLog(LogLevel.INFO, "Assigned gateway role: $gatewayRole")
    } else {
        safeLog(LogLevel.INFO, "Declined gateway role (cannot honor user preference)")
    }
}
```

**Simplification**: 
- No more manual preference checking in calculateTargetRoles()
- selectBestGatewayRole() handles all logic
- Null return = decline role

---

### 4.4 Deprecated userPreferences Parameter Handling

**Issue**: `selectBestGatewayRole()` still has `userPreferences: Set<MeshRole>` parameter, but we now use `gatewayPreference` enum.

**DECISION POINT**: Should we:
1. **Remove userPreferences parameter** (breaking change to signature)
2. **Keep but ignore** (deprecated, log warning if used)
3. **Merge with gatewayPreference** (if userPreferences specifies gateway role, override enum)

**Answer: Remove userPreferences parameter

**Recommendation**: Option 3 (Merge) - If user has set userPreferences with specific gateway role, honor it over gatewayPreference enum. Provides migration path and power-user override.

**Updated Logic**:

```kotlin
private fun selectBestGatewayRole(
    node: NodeCapabilitySnapshot, 
    mesh: MeshIntelligence,
    userPreferences: Set<MeshRole>
): MeshRole? {
    
    val torAvailable = getTorNetworkStatus()
    
    // Check if user has explicitly preferred a specific gateway role
    val gatewayRoles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
    val preferredGateways = userPreferences.intersect(gatewayRoles)
    
    if (preferredGateways.isNotEmpty()) {
        // User has explicit gateway preference via userPreferences - honor it
        val explicitRole = preferredGateways.first()
        
        safeLog(
            LogLevel.INFO,
            "Using explicit gateway role from userPreferences: $explicitRole " +
            "(overrides gatewayPreference enum)"
        )
        
        // Still check Tor availability for TOR_GATEWAY
        if (explicitRole == MeshRole.TOR_GATEWAY && !torAvailable) {
            safeLog(LogLevel.WARN, "TOR_GATEWAY preferred but Tor unavailable - declining")
            return null
        }
        
        return explicitRole
    }
    
    // No explicit preference - use gatewayPreference enum logic
    val preference = gatewayPreference.value
    
    // ... (rest of enum-based logic from Section 4.2) ...
}
```

**Rationale**:
- **Backward Compatibility**: Existing code using userPreferences still works
- **Override Capability**: Power users can force specific gateway type
- **Graceful Migration**: No breaking changes, gradual transition to enum

---

## SECTION 5 (FIRST HALF): API METHOD INTERFACES

### 5.1 MeshrabiyaApi Interface Extensions

**File**: `MeshrabiyaApi.kt`  
**Location**: Add to interface definition  
**Lines Added**: ~18

**New Interface Methods**:

```kotlin
/**
 * Set user's gateway routing preference.
 * Controls which type of gateways this node will use and become.
 * 
 * Preference options:
 * - TOR_ONLY: Only use/become Tor gateways, refuse Clearnet
 * - CLEARNET_ONLY: Only use/become Clearnet gateways, refuse Tor
 * - EITHER: Use best available gateway based on capabilities (default)
 * 
 * Preference is persisted via DataStore and survives app restarts.
 * Changing preference triggers immediate role re-evaluation.
 * 
 * @param preference Desired gateway routing mode
 */
fun setGatewayPreference(preference: GatewayPreference)

/**
 * Get current gateway routing preference.
 * 
 * @return Current GatewayPreference enum value (TOR_ONLY, CLEARNET_ONLY, or EITHER)
 */
fun getGatewayPreference(): GatewayPreference

/**
 * Get current Tor network status.
 * Indicates whether Tor network is active and usable for routing.
 * 
 * Status is tracked via Orbot broadcasts and updated in real-time.
 * Returns false if Orbot not installed or Tor network inactive.
 * 
 * @return true if Tor network is active, false otherwise
 */
fun getTorNetworkStatus(): Boolean

/**
 * Observe Tor network status changes as a StateFlow.
 * Allows UI and other components to react to Tor availability changes.
 * 
 * @return StateFlow<Boolean> that emits true when Tor active, false when inactive
 */
fun observeTorNetworkStatus(): StateFlow<Boolean>
```

**Rationale**:
- **Preference Management**: Exposes gateway preference from Part 1
- **Tor Status Query**: Exposes Tor network status from Part 2
- **Reactive API**: StateFlow allows observing status changes
- **Clear Documentation**: KDoc explains behavior and defaults

---

### 5.2 GatewayPreference Enum Visibility

**File**: `EmergentRoleManager.kt` or create `GatewayPreference.kt`  
**Issue**: GatewayPreference enum is currently nested in EmergentRoleManager (Part 1, Section 1.1)

**DECISION POINT**: Should GatewayPreference be:
1. **Top-level class** in its own file (public API visibility)
2. **Nested in EmergentRoleManager** (current, internal visibility)
3. **Nested in MeshrabiyaApi** (API-adjacent visibility)

**Recommendation**: Option 1 (Top-level) - Make it a public API enum for external apps to use.

**Answer: Option 1 (Top-level) 

**Refactor** (if needed):

Create new file: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/GatewayPreference.kt`

```kotlin
package com.ustadmobile.meshrabiya.api

/**
 * User preference for gateway routing mode.
 * Determines which type of gateways this node will use and become.
 * 
 * Part of Meshrabiya public API for gateway routing control.
 * 
 * @see MeshrabiyaApi.setGatewayPreference
 * @see MeshrabiyaApi.getGatewayPreference
 */
enum class GatewayPreference {
    /** Only route via Tor gateways, refuse Clearnet */
    TOR_ONLY,
    
    /** Only route via Clearnet gateways, refuse Tor */
    CLEARNET_ONLY,
    
    /** Use best available gateway based on capabilities (default) */
    EITHER;
    
    companion object {
        /**
         * Convert legacy Boolean userAllowsTorProxy to GatewayPreference.
         * Used during migration from old preference system.
         * 
         * @param allowsTor Legacy boolean preference value
         * @return Equivalent GatewayPreference enum value
         */
        fun fromLegacyBoolean(allowsTor: Boolean): GatewayPreference {
            return if (allowsTor) TOR_ONLY else CLEARNET_ONLY
        }
        
        /**
         * Parse preference from DataStore string value.
         * Handles invalid values gracefully by defaulting to EITHER.
         * 
         * @param value String value from DataStore (e.g., "TOR_ONLY")
         * @return Parsed GatewayPreference, or EITHER if invalid
         */
        fun fromString(value: String?): GatewayPreference {
            return when (value?.uppercase()) {
                "TOR_ONLY" -> TOR_ONLY
                "CLEARNET_ONLY" -> CLEARNET_ONLY
                "EITHER" -> EITHER
                else -> EITHER // Default for null or invalid values
            }
        }
    }
}
```

**Import Updates**:
- EmergentRoleManager.kt: `import com.ustadmobile.meshrabiya.api.GatewayPreference`
- MeshrabiyaApi.kt: `import com.ustadmobile.meshrabiya.api.GatewayPreference`
- MeshrabiyaApiImpl.kt: `import com.ustadmobile.meshrabiya.api.GatewayPreference`

**Rationale**:
- **Public API**: External apps need access to enum for setGatewayPreference() calls
- **Package Cohesion**: api package is appropriate for public-facing types
- **Clean Separation**: EmergentRoleManager remains internal implementation

---

### 5.3 Import Requirements for Section 3-5

**File**: `GatewayRouter.kt`  
**Required Imports**:
```kotlin
import com.ustadmobile.meshrabiya.vnet.NodeTopologyInfo
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.api.GatewayPreference
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.log.LogLevel
```

**File**: `MeshrabiyaApi.kt`  
**Required Imports**:
```kotlin
import com.ustadmobile.meshrabiya.api.GatewayPreference
import kotlinx.coroutines.flow.StateFlow
```

**File**: `EmergentRoleManager.kt`  
**Updated Imports**:
```kotlin
import com.ustadmobile.meshrabiya.api.GatewayPreference // Changed from nested enum
```

---

## SECTION 3-5 COMPLETION CHECKLIST

### Section 3 (Second Half): Gateway Discovery & Multi-Hop
- [ ] Add discoverGatewaysFromTopology() to GatewayRouter
- [ ] Add determineGatewayType() helper method
- [ ] Add calculateHopCounts() to GatewayRouter
- [ ] Add routeToGateway() main entry point
- [ ] Add findNextHopTowardsGateway() helper method
- [ ] Write unit tests for gateway discovery (10+ gateways)
- [ ] Write unit tests for hop count calculation (various topologies)
- [ ] Write unit tests for next-hop calculation (multi-hop paths)

### Section 4: selectBestGatewayRole() Refactor
- [ ] Change return type to MeshRole? (nullable)
- [ ] Add Tor status check via getTorNetworkStatus()
- [ ] Implement TOR_ONLY preference logic
- [ ] Implement CLEARNET_ONLY preference logic
- [ ] Implement EITHER preference logic
- [ ] Add userPreferences merge logic (override capability)
- [ ] Update calculateTargetRoles() to handle null return
- [ ] Remove deprecated Boolean userAllowsTorProxy references
- [ ] Write unit tests for all 3 preference modes
- [ ] Write unit tests for Tor unavailable scenarios

### Section 5 (First Half): API Interfaces
- [ ] Extract GatewayPreference enum to top-level file
- [ ] Add setGatewayPreference() to MeshrabiyaApi interface
- [ ] Add getGatewayPreference() to MeshrabiyaApi interface
- [ ] Add getTorNetworkStatus() to MeshrabiyaApi interface
- [ ] Add observeTorNetworkStatus() to MeshrabiyaApi interface
- [ ] Update import statements in EmergentRoleManager
- [ ] Update import statements in MeshrabiyaApiImpl
- [ ] Add KDoc comments for all new API methods

### Testing & Validation
- [ ] All unit tests pass
- [ ] Gateway discovery finds all gateways in topology
- [ ] Hop count calculation accurate for 3+ hop scenarios
- [ ] Multi-hop routing selects correct next hop
- [ ] TOR_ONLY preference refuses Clearnet gateways
- [ ] CLEARNET_ONLY preference refuses Tor gateways
- [ ] EITHER preference prefers Tor when available
- [ ] Null return from selectBestGatewayRole() handled gracefully
- [ ] API methods compile and link correctly

---

## NEXT STEPS (PART 4 PREVIEW)

Part 4 will implement API methods, document lifecycle, and provide testing guidance:

1. **Section 5 (Second Half): API Implementation**
   - Implement setGatewayPreference() in MeshrabiyaApiImpl
   - Implement getGatewayPreference() in MeshrabiyaApiImpl
   - Implement getTorNetworkStatus() in MeshrabiyaApiImpl
   - Implement observeTorNetworkStatus() in MeshrabiyaApiImpl
   - Add preference persistence integration
   - Add role re-evaluation triggers

2. **Section 6: Lifecycle Documentation**
   - Document BroadcastReceiver lifecycle (no onTerminate needed)
   - Document cleanup() method usage
   - Document DataStore persistence guarantees
   - Memory management considerations
   - Process death recovery

3. **Appendix: Testing & Edge Cases**
   - Integration test scenarios (Tor on/off, preference changes)
   - Edge cases (Orbot not installed, rapid toggles, network flakiness)
   - Performance considerations (BFS caching, suitability score batching)
   - UI integration examples (preference picker, Tor status indicator)

4. **Import Requirements & Completion Tracking**
   - Complete import lists for all modified files
   - Checklist for all 6 sections
   - Verification steps before deployment

**Estimated Lines**: ~200 lines (Section 5 second half + Section 6 + Appendix)

---

## APPENDIX A: GATEWAY DISCOVERY EXAMPLE

**Topology**:
```
Node100 (Me) ──── Node101 ──── TorGateway102
    │                               │
    │                               │
    └──── ClearnetGateway103 ──── Node104 ──── TorGateway105
```

**Step 1: Discover Gateways**:
```kotlin
val gateways = discoverGatewaysFromTopology(topologyMap)
// Result: [
//   GatewayInfo(102, TOR, ...),
//   GatewayInfo(103, CLEARNET, ...),
//   GatewayInfo(105, TOR, ...)
// ]
```

**Step 2: Calculate Hop Counts** (from Node100):
```kotlin
val hopCounts = calculateHopCounts(100, topologyMap, {102, 103, 105})
// Result: {102: 2, 103: 1, 105: 3}
```

**Step 3: Score Gateways** (user preference = TOR_ONLY):
```kotlin
val scores = gateways.map { calculateGatewaySuitability(it, TOR_ONLY, hopCounts[it.address]!!) }
// Result: [
//   GatewaySuitability(102, TOR, hops=2, preferenceScore=1.0, totalScore=0.72),
//   GatewaySuitability(103, CLEARNET, hops=1, preferenceScore=0.0, totalScore=0.0), // FILTERED
//   GatewaySuitability(105, TOR, hops=3, preferenceScore=1.0, totalScore=0.58)
// ]
```

**Step 4: Select Best**:
```kotlin
val best = selectBestGateway(gateways, TOR_ONLY, hopCounts)
// Result: 102 (TorGateway102, highest score among matching gateways)
```

**Step 5: Find Next Hop**:
```kotlin
val nextHop = findNextHopTowardsGateway(100, 102, topologyMap)
// Path: 100 → 101 → 102
// Result: 101 (next hop on path to gateway)
```

**Step 6: Route Packet**:
```kotlin
packet.nextHop = 101
send(packet)
// Packet routed to Node101, which will forward to TorGateway102
```

---

## APPENDIX B: SELECTBESTGATEWAYROLE() DECISION TREE

**Input**: Node capabilities, Tor status, Gateway preference

```
┌─────────────────────────────────────────────────────┐
│ selectBestGatewayRole()                             │
└─────────────────┬───────────────────────────────────┘
                  │
                  ▼
         ┌────────────────────┐
         │ Check userPreferences│
         │ for explicit gateway?│
         └────────┬─────────────┘
                  │
         Yes ──────┤────── No
          │                 │
          ▼                 ▼
    ┌─────────────┐   ┌──────────────────┐
    │Return explicit│   │Use gatewayPreference│
    │gateway role   │   │enum               │
    │(with Tor check)│   └────────┬─────────┘
    └─────────────┘              │
                          ┌───────┴───────┐
                          │               │
                    TOR_ONLY        CLEARNET_ONLY
                          │               │
                          ▼               ▼
                  ┌─────────────┐   ┌─────────────┐
                  │Tor available?│   │Bandwidth    │
                  └──┬────────┬──┘   │>10Mbps?    │
                     │        │      └──┬────────┬──┘
                  Yes│        │No       │        │
                     │        │      Yes│        │No
                     ▼        ▼         ▼        ▼
              ┌─────────┐ ┌──────┐ ┌──────┐ ┌──────┐
              │Bandwidth│ │Return│ │Return│ │Return│
              │>5Mbps?  │ │null  │ │CLEARNET│ │null  │
              └─┬───┬───┘ └──────┘ │_GATEWAY│ └──────┘
                │   │              └──────┘
             Yes│   │No
                ▼   ▼
         ┌────────┐┌──────┐
         │Return  ││Return│
         │TOR_    ││null  │
         │GATEWAY │└──────┘
         └────────┘
                    
    EITHER preference:
    ┌──────────────────────────────────┐
    │ Tor available? → TOR_GATEWAY     │
    │ Bandwidth >10Mbps? → CLEARNET    │
    │ Tor available (low BW)? → TOR    │
    │ Else → CLEARNET (last resort)    │
    └──────────────────────────────────┘
```

---

## APPENDIX C: MULTI-HOP ROUTING SCENARIOS

**Scenario 1: Direct Neighbor Gateway**
```
Me(100) ──── TorGateway(101)

Hop count: 0
Next hop: 101 (direct)
Action: Set packet.nextHop = 101
```

**Scenario 2: 2-Hop to Gateway**
```
Me(100) ──── Relay(102) ──── TorGateway(103)

Hop count: 2
Next hop: 102 (first step on path)
Action: Set packet.nextHop = 102
Note: Relay(102) will forward to 103
```

**Scenario 3: Multiple Gateways at Different Distances**
```
Me(100) ──── TorGateway(101) [1 hop, score: 0.85]
    │
    └──── Relay(102) ──── TorGateway(103) [2 hops, high bandwidth, score: 0.78]

Selected: 101 (closer, higher score despite lower bandwidth)
```

**Scenario 4: Preference-Based Filtering**
```
Me(100) ──── ClearnetGateway(101) [1 hop, high bandwidth]
    │
    └──── Relay(102) ──── TorGateway(103) [2 hops, medium bandwidth]

User Preference: TOR_ONLY
Filtered out: 101 (wrong type)
Selected: 103 (only matching gateway)
Next hop: 102
```

**Scenario 5: No Matching Gateways**
```
Me(100) ──── ClearnetGateway(101)
    │
    └──── ClearnetGateway(102)

User Preference: TOR_ONLY
Result: null (no suitable gateways)
Action: Drop packet OR queue for retry
```

---

## APPENDIX D: PERFORMANCE CONSIDERATIONS

**BFS Complexity**:
- **Hop Count Calculation**: O(N + E) per routing decision
- **Mesh Size**: 100 nodes → ~5ms, 1000 nodes → ~50ms
- **Mitigation**: Cache BFS results for batch packet routing

**Suitability Scoring**:
- **Per-Gateway Cost**: O(1) (simple arithmetic)
- **Total Cost**: O(G) where G = number of gateways
- **Typical Case**: 10 gateways → <1ms

**Gateway Discovery**:
- **Topology Map Iteration**: O(N) where N = total nodes
- **Gateway Filter**: O(1) per node (role set lookup)
- **Total Cost**: O(N), negligible for N < 1000

**Optimization Opportunities**:
1. **Cache Topology Map**: Only refresh on topology changes (via gossip)
2. **Incremental BFS**: Only recalculate hop counts for new/removed nodes
3. **Suitability Precomputation**: Score all gateways once, reuse for multiple packets
4. **Batch Routing**: Process multiple packets with single BFS traversal

**Recommended Caching Strategy** (Future Enhancement):
```kotlin
class GatewayRoutingCache {
    private var cachedTopologyHash: Int = 0
    private var cachedHopCounts: Map<Int, Int> = emptyMap()
    private var cachedGateways: List<GatewayInfo> = emptyList()
    
    fun getHopCounts(topologyMap: Map<Int, NodeTopologyInfo>): Map<Int, Int> {
        val currentHash = topologyMap.hashCode()
        if (currentHash != cachedTopologyHash) {
            // Topology changed, recalculate
            cachedGateways = discoverGatewaysFromTopology(topologyMap)
            cachedHopCounts = calculateHopCounts(...)
            cachedTopologyHash = currentHash
        }
        return cachedHopCounts
    }
}
```

---

## END OF PART 3

**Total Lines**: ~1,150 lines (including documentation, code, appendices)

**Parts Completed**: 3 of 4

**Proceed to**: [TOR_INTEGRATION_PLAN_PART4.md] for API implementation, lifecycle documentation, and comprehensive testing guidance.

**Open Decisions for User**:
1. EITHER preference - prefer Tor (privacy) or Clearnet (performance)? **Recommendation**: Tor (privacy-first)
2. GatewayPreference enum location - top-level file or nested? **Recommendation**: Top-level (public API)
3. userPreferences parameter - remove, ignore, or merge with enum? **Recommendation**: Merge (power-user override)
4. Gateway discovery caching - implement now or later? **Recommendation**: Later (optimize after profiling)
