# Tor Integration Plan V3 - Part 3B: NetworkInfo & Gateway Statistics
**Version:** 3.0  
**Date:** January 2025  
**Dependencies:** Part 3A complete

---

## 3B.1 NETWORKINFO GATEWAY BREAKDOWN

### Requirement

Expose gateway statistics in NetworkInfo for UI display.

**Current NetworkInfo** (estimated):
```kotlin
data class NetworkInfo(
    val connectedNodes: Int,
    val totalNodes: Int,
    val gatewayNodes: Int,  // Total gateways (all types)
    // ... other fields
)
```

**V3 Extension**:
```kotlin
data class NetworkInfo(
    val connectedNodes: Int,
    val totalNodes: Int,
    val torGateways: Int,       // V3: Tor gateways count
    val clearnetGateways: Int,  // V3: Clearnet gateways count
    val totalGateways: Int,     // V3: Total (tor + clearnet)
    // ... other fields
)
```

---

## 3B.2 NETWORKINFO UPDATES

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/NetworkInfo.kt`

**Implementation**:
```kotlin
package com.ustadmobile.meshrabiya

data class NetworkInfo(
    val connectedNodes: Int = 0,
    val totalNodes: Int = 0,
    
    // V3: Gateway breakdown
    val torGateways: Int = 0,
    val clearnetGateways: Int = 0,
    
    // ... existing fields
) {
    /**
     * Total gateway nodes (Tor + clearnet).
     * Some nodes may advertise both roles.
     */
    val totalGateways: Int
        get() = torGateways + clearnetGateways
}
```

---

## 3B.3 GATEWAY COUNTING LOGIC

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt`

**Method:** `getNetworkInfo()`

```kotlin
override fun getNetworkInfo(): NetworkInfo {
    val topology = virtualNode?.topology
    
    if (topology == null) {
        return NetworkInfo()  // Mesh not initialized
    }
    
    val allNodes = topology.nodes
    val connectedNodes = allNodes.count { it.isConnected }
    
    // V3: Count gateways by type
    val torGateways = allNodes.count { node ->
        node.roles.contains(NodeRole.TOR_GATEWAY) &&
        !isGatewayStale(node)
    }
    
    val clearnetGateways = allNodes.count { node ->
        node.roles.contains(NodeRole.CLEARNET_GATEWAY) &&
        !isGatewayStale(node)
    }
    
    return NetworkInfo(
        connectedNodes = connectedNodes,
        totalNodes = allNodes.size,
        torGateways = torGateways,
        clearnetGateways = clearnetGateways,
    )
}

/**
 * Helper: Check if gateway is stale (no recent heartbeat).
 */
private fun isGatewayStale(node: VirtualNode): Boolean {
    val lastSeen = node.lastHeartbeat ?: return true
    val ageMs = System.currentTimeMillis() - lastSeen
    return ageMs > 30_000L  // 30 seconds
}
```

---

## 3B.4 UI DISPLAY

**Example UI usage**:
```kotlin
// In Activity/Fragment
lifecycleScope.launch {
    meshrabiyaApi.networkInfoFlow.collect { info ->
        binding.textTotalNodes.text = "Nodes: ${info.totalNodes}"
        binding.textTorGateways.text = "Tor Gateways: ${info.torGateways}"
        binding.textClearnetGateways.text = "Clearnet: ${info.clearnetGateways}"
        
        // Show warning if no gateways available
        if (info.totalGateways == 0) {
            binding.warningNoGateways.visibility = View.VISIBLE
        }
    }
}
```

---

## 3B.5 IMPLEMENTATION CHECKLIST

### NetworkInfo.kt

- [ ] Add `torGateways: Int` field
- [ ] Add `clearnetGateways: Int` field
- [ ] Add `totalGateways` computed property
- [ ] Update KDoc

### MeshrabiyaApiImpl.kt

- [ ] Update `getNetworkInfo()` to count Tor gateways
- [ ] Update `getNetworkInfo()` to count clearnet gateways
- [ ] Add `isGatewayStale()` helper method
- [ ] Test gateway counting logic

### Testing

- [ ] Unit test: Gateway counting (Tor, clearnet, mixed)
- [ ] Unit test: Stale gateway filtering
- [ ] Unit test: totalGateways computed property
- [ ] Integration test: NetworkInfo flow updates

---

**END OF PART 3B**

**Next:** Part 3C - OriginatingMessageManager Updates
