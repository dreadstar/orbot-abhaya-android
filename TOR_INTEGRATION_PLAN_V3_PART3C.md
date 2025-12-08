# Tor Integration Plan V3 - Part 3C: OriginatingMessageManager Updates
**Version:** 3.0  
**Date:** January 2025  
**Dependencies:** Part 3A complete

---

## 3C.1 ORIGINATING MESSAGE TRACKING

### Purpose

Track which packets originated from this node and were sent via gateways.

Enables:
- Return path routing
- Gateway usage statistics
- Debugging gateway routing

---

## 3C.2 GATEWAY TYPE TRACKING

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`

**Current Structure** (estimated):
```kotlin
data class OriginatingMessage(
    val fromAddr: Int,
    val fromPort: Int,
    val toAddr: Int,
    val toPort: Int,
    val timestamp: Long,
)
```

**V3 Extension**:
```kotlin
data class OriginatingMessage(
    val fromAddr: Int,
    val fromPort: Int,
    val toAddr: Int,
    val toPort: Int,
    val timestamp: Long,
    val gatewayType: Byte,      // V3: Gateway type used (0, 1, 2)
    val gatewayAddr: Int?,      // V3: Gateway node address (if routed via gateway)
)
```

---

## 3C.3 TRACKING GATEWAY PACKETS

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**Update in routeViaGateway()**:
```kotlin
private fun routeViaGateway(
    packet: VirtualPacket,
    datagramPacket: DatagramPacket?
) {
    // ... existing gateway selection code ...
    
    val selectedGateway = selectBestGateway(gateways, packet)
    
    if (selectedGateway != null) {
        // V3: Track originating message with gateway info
        originatingMessageManager.trackGatewayMessage(
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            toAddr = packet.header.toAddr,
            toPort = packet.header.toPort,
            gatewayType = packet.header.gatewayType,
            gatewayAddr = selectedGateway.address
        )
        
        forwardToGateway(packet, selectedGateway)
    }
}
```

---

## 3C.4 ORIGINATINGMESSAGEMANAGER UPDATES

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`

**Add method**:
```kotlin
/**
 * Tracks a message sent via gateway for return path routing.
 *
 * @param fromAddr Source virtual address
 * @param fromPort Source port
 * @param toAddr Destination address (internet)
 * @param toPort Destination port
 * @param gatewayType Gateway type (TOR or CLEARNET)
 * @param gatewayAddr Gateway node address
 */
fun trackGatewayMessage(
    fromAddr: Int,
    fromPort: Int,
    toAddr: Int,
    toPort: Int,
    gatewayType: Byte,
    gatewayAddr: Int
) {
    val message = OriginatingMessage(
        fromAddr = fromAddr,
        fromPort = fromPort,
        toAddr = toAddr,
        toPort = toPort,
        timestamp = System.currentTimeMillis(),
        gatewayType = gatewayType,
        gatewayAddr = gatewayAddr
    )
    
    originatingMessages[createKey(fromAddr, fromPort)] = message
    
    logger.debug {
        "Tracked gateway message: $fromAddr:$fromPort → gateway $gatewayAddr (type=$gatewayType)"
    }
}

/**
 * Gets gateway address for return traffic.
 *
 * @param toAddr Destination address (local node)
 * @param toPort Destination port
 * @return Gateway address, or null if not routed via gateway
 */
fun getGatewayForReturnTraffic(toAddr: Int, toPort: Int): Int? {
    val message = originatingMessages[createKey(toAddr, toPort)]
    return message?.gatewayAddr
}
```

---

## 3C.5 GATEWAY USAGE STATISTICS

**Optional enhancement**:

```kotlin
/**
 * Returns statistics on gateway usage.
 *
 * @return Map of gateway type to usage count
 */
fun getGatewayUsageStats(): Map<Byte, Int> {
    val stats = mutableMapOf<Byte, Int>()
    
    originatingMessages.values.forEach { msg ->
        if (msg.gatewayAddr != null) {
            val count = stats.getOrDefault(msg.gatewayType, 0)
            stats[msg.gatewayType] = count + 1
        }
    }
    
    return stats
}
```

---

## 3C.6 IMPLEMENTATION CHECKLIST

### OriginatingMessage.kt

- [ ] Add `gatewayType: Byte` field
- [ ] Add `gatewayAddr: Int?` field
- [ ] Update constructor/data class

### OriginatingMessageManager.kt

- [ ] Add `trackGatewayMessage()` method
- [ ] Add `getGatewayForReturnTraffic()` method
- [ ] Optional: Add `getGatewayUsageStats()` method
- [ ] Update logging

### VirtualNode.kt

- [ ] Call `trackGatewayMessage()` in `routeViaGateway()`
- [ ] Pass gateway type and gateway address

### Testing

- [ ] Unit test: Track gateway message
- [ ] Unit test: Get gateway for return traffic
- [ ] Unit test: Gateway usage statistics
- [ ] Integration test: End-to-end gateway tracking

---

**END OF PART 3C**

**Next:** Part 4A - Testing Strategy (Unit Tests)
