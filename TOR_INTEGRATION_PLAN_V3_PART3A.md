# Tor Integration Plan V3 - Part 3A: Gateway Routing Core Logic
**Version:** 3.0  
**Date:** January 2025  
**Dependencies:** Parts 1 & 2 complete

---

## 3A.1 ROUTING INTEGRATION POINT

### VirtualNode.route() Method

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**Current Flow** (line 627):
```kotlin
override fun route(
    packet: VirtualPacket,
    datagramPacket: DatagramPacket?,
    virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
) {
    // 1. Check hop count limit
    if(packet.header.hopCount >= config.maxHops) {
        return
    }
    
    // 2. Check if MMCP message
    if(packet.header.toPort == 0 && packet.header.fromAddr != addressAsInt){
        if(!onIncomingMmcpMessage(packet, datagramPacket, virtualNodeDatagramSocket)){
            return
        }
    }
    
    // 3. Check if packet for local node
    if (packet.header.toAddr == addressAsInt) {
        deliverToLocalNode(packet)
        return
    }
    
    // 4. Check for direct neighbor routing
    // ... existing logic
    
    // 5. Multi-hop routing via topology
    // ... existing logic
}
```

**V3 Integration** (add after step 5):
```kotlin
override fun route(
    packet: VirtualPacket,
    datagramPacket: DatagramPacket?,
    virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
) {
    // ... steps 1-5 (existing code)
    
    // 6. V3: Gateway routing for internet-bound packets
    if (!isDestinationOnMesh(packet.header.toAddr)) {
        routeViaGateway(packet, datagramPacket)
        return
    }
    
    // 7. Packet destination unknown - drop
    logger.warn { "Unknown destination: ${packet.header.toAddr}, dropping packet" }
}
```

---

## 3A.2 GATEWAY ROUTING METHOD

```kotlin
/**
 * Routes packet to internet via mesh gateway.
 * 
 * Uses gateway type from packet header to select appropriate gateway.
 * Implements multi-hop failover if primary gateway unavailable.
 *
 * @param packet Virtual packet with internet-bound destination
 * @param datagramPacket Original datagram (for metadata)
 */
private fun routeViaGateway(
    packet: VirtualPacket,
    datagramPacket: DatagramPacket?
) {
    val gatewayType = packet.header.gatewayType
    
    logger.debug { 
        "Routing internet-bound packet via gateway (type=$gatewayType)" 
    }
    
    // Get available gateways of requested type
    val gateways = when (gatewayType) {
        VirtualPacketHeader.GATEWAY_TYPE_TOR -> {
            getAvailableTorGateways()
        }
        VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> {
            getAvailableClearnetGateways()
        }
        else -> {
            logger.error { "Invalid gateway type: $gatewayType" }
            return
        }
    }
    
    if (gateways.isEmpty()) {
        handleNoGatewayAvailable(packet, gatewayType)
        return
    }
    
    // Select best gateway (closest, lowest load, etc.)
    val selectedGateway = selectBestGateway(gateways, packet)
    
    if (selectedGateway == null) {
        logger.warn { "No suitable gateway found for type=$gatewayType" }
        return
    }
    
    // Forward packet to gateway
    forwardToGateway(packet, selectedGateway)
}
```

---

## 3A.3 GATEWAY DISCOVERY

```kotlin
/**
 * Gets list of available Tor gateways from mesh topology.
 * 
 * @return List of nodes advertising TOR_GATEWAY role
 */
private fun getAvailableTorGateways(): List<VirtualNode> {
    return topology.nodes
        .filter { node ->
            node.roles.contains(NodeRole.TOR_GATEWAY) &&
            !isGatewayStale(node)
        }
        .toList()
}

/**
 * Gets list of available clearnet gateways.
 */
private fun getAvailableClearnetGateways(): List<VirtualNode> {
    return topology.nodes
        .filter { node ->
            node.roles.contains(NodeRole.CLEARNET_GATEWAY) &&
            !isGatewayStale(node)
        }
        .toList()
}

/**
 * Checks if gateway is stale (no recent heartbeat).
 * 
 * @param node Gateway node to check
 * @return true if stale (older than 30 seconds)
 */
private fun isGatewayStale(node: VirtualNode): Boolean {
    val lastSeen = node.lastHeartbeat ?: return true
    val ageMs = System.currentTimeMillis() - lastSeen
    return ageMs > GATEWAY_STALE_TIMEOUT_MS
}

companion object {
    const val GATEWAY_STALE_TIMEOUT_MS = 30_000L  // 30 seconds
}
```

---

## 3A.4 GATEWAY SELECTION

```kotlin
/**
 * Selects best gateway from available list.
 * 
 * Selection criteria:
 * 1. Closest hop distance
 * 2. Lowest load (if load metrics available)
 * 3. Round-robin for equal candidates
 *
 * @param gateways List of available gateways
 * @param packet Packet being routed
 * @return Selected gateway, or null if none suitable
 */
private fun selectBestGateway(
    gateways: List<VirtualNode>,
    packet: VirtualPacket
): VirtualNode? {
    if (gateways.isEmpty()) return null
    
    // Calculate hop distance to each gateway
    val gatewaysWithDistance = gateways.mapNotNull { gateway ->
        val distance = topology.getHopDistance(addressAsInt, gateway.address)
        if (distance != null && distance <= config.maxHops) {
            Pair(gateway, distance)
        } else {
            null
        }
    }
    
    if (gatewaysWithDistance.isEmpty()) return null
    
    // Find minimum distance
    val minDistance = gatewaysWithDistance.minOf { it.second }
    
    // Get all gateways at minimum distance
    val closestGateways = gatewaysWithDistance
        .filter { it.second == minDistance }
        .map { it.first }
    
    // Round-robin selection among closest
    return closestGateways[
        packet.header.fromAddr % closestGateways.size
    ]
}
```

---

## 3A.5 PACKET FORWARDING

```kotlin
/**
 * Forwards packet to selected gateway.
 * 
 * Updates packet header (hopCount, lastHopAddr) and sends to gateway.
 *
 * @param packet Packet to forward
 * @param gateway Target gateway node
 */
private fun forwardToGateway(
    packet: VirtualPacket,
    gateway: VirtualNode
) {
    // Create forwarded packet with updated header
    val forwardedHeader = packet.header.copy(
        toAddr = gateway.address,  // Route to gateway
        hopCount = (packet.header.hopCount + 1).toByte(),
        lastHopAddr = addressAsInt
    )
    
    val forwardedPacket = VirtualPacket(forwardedHeader, packet.payload)
    
    logger.debug { 
        "Forwarding packet to gateway ${gateway.address} (hop ${forwardedHeader.hopCount})" 
    }
    
    // Send to next hop toward gateway
    val nextHop = topology.getNextHop(addressAsInt, gateway.address)
    if (nextHop != null) {
        sendToNextHop(forwardedPacket, nextHop)
    } else {
        logger.error { "No route to gateway ${gateway.address}" }
    }
}
```

---

## 3A.6 NO GATEWAY HANDLING

```kotlin
/**
 * Handles case where no gateway is available.
 * 
 * Behavior based on gateway preference:
 * - TOR_ONLY: Drop packet (no fallback)
 * - CLEARNET_ONLY: Drop packet (no fallback)
 * - EITHER: Try alternate gateway type
 *
 * @param packet Packet that couldn't be routed
 * @param requestedType Gateway type that was requested
 */
private fun handleNoGatewayAvailable(
    packet: VirtualPacket,
    requestedType: Byte
) {
    logger.warn { "No gateway available for type=$requestedType" }
    
    // Check if preference allows fallback
    val preference = getGatewayPreference()
    
    if (preference == GatewayPreference.EITHER) {
        // Try alternate gateway type
        val alternateType = if (requestedType == VirtualPacketHeader.GATEWAY_TYPE_TOR) {
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
        } else {
            VirtualPacketHeader.GATEWAY_TYPE_TOR
        }
        
        logger.info { "Attempting fallback to gateway type=$alternateType" }
        
        // Update packet header with alternate type
        val fallbackHeader = packet.header.copy(gatewayType = alternateType)
        val fallbackPacket = VirtualPacket(fallbackHeader, packet.payload)
        
        routeViaGateway(fallbackPacket, null)
    } else {
        // No fallback allowed - drop packet
        logger.warn { 
            "Dropping packet: no $requestedType gateway (preference=$preference)" 
        }
    }
}
```

---

## 3A.7 IMPLEMENTATION CHECKLIST

### VirtualNode.kt

- [ ] Add `routeViaGateway()` method after `route()`
- [ ] Add gateway discovery methods:
  - [ ] `getAvailableTorGateways()`
  - [ ] `getAvailableClearnetGateways()`
  - [ ] `isGatewayStale()`
- [ ] Add `selectBestGateway()` method
- [ ] Add `forwardToGateway()` method
- [ ] Add `handleNoGatewayAvailable()` method
- [ ] Add `GATEWAY_STALE_TIMEOUT_MS` constant
- [ ] Integrate gateway routing into `route()` method (step 6)
- [ ] Add logging for gateway routing decisions

### Testing

- [ ] Unit test: Gateway discovery (Tor and clearnet)
- [ ] Unit test: Gateway selection (closest, load balancing)
- [ ] Unit test: Packet forwarding (header updates)
- [ ] Unit test: No gateway handling (fallback logic)
- [ ] Integration test: End-to-end gateway routing

---

**END OF PART 3A**

**Next:** Part 3B - NetworkInfo Updates & Gateway Statistics
