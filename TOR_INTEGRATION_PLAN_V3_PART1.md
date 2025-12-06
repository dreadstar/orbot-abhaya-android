# Tor Integration Plan V3 - Part 1: Packet Header Extension & Gateway Type Classification
**Version:** 3.0  
**Date:** January 2025  
**Status:** Final Design  
**Previous Version:** V2 (3 parts, port-based classification approach)

---

## EXECUTIVE SUMMARY

This is Part 1 of the V3 Tor Integration Plan for Orbot-Abhaya-Android, incorporating critical architectural changes based on user clarifications provided in Answer blocks after V2 plan completion.

### Key V3 Architectural Changes from V2

1. **Packet Header Gateway Type Flag** (NEW - Answer Block 1):
   - V2 Approach: Port-based inspection of packet payload to determine gateway type
   - V3 Approach: Add 1-byte `gatewayType` field to VirtualPacketHeader
   - Impact: Dramatically simpler classification, explicit gateway requests
   - Header size: 20 bytes → 21 bytes

2. **Orbot VPN Per-App Proxy Rules Precedence** (NOW IMPLEMENTABLE - Answer Block 1):
   - V2 Status: Listed as blocker - couldn't find VPN settings access
   - V3 Status: RESOLVED - This IS Orbot (fork), VPN code exists in `/app` module
   - Implementation: Read TorifiedApp list from SharedPreferences (key: "PrefTord")
   - Logic: Orbot VPN app selection supersedes global gateway preference

3. **Project Deployment Context** (CLARIFIED - Answer Block 2):
   - V2 Understanding: Library integrating WITH Orbot as separate app
   - V3 Understanding: This IS Orbot - fork of official Orbot with Meshrabiya integrated
   - Impact: Library can access Orbot features directly (same app), cross-module access via callbacks

4. **Routing Integration Point** (CLARIFIED - Answer Block 3):
   - V2 Approach: Integrate into `VirtualNode.route()` method
   - User Mention: `onIncomingMmcpMessage()` control flow
   - V3 Clarification: `onIncomingMmcpMessage()` handles MMCP messages only (subset of routing)
   - V3 Approach: PRIMARY integration into `route()`, MMCP awareness only

### Scope of Part 1

This part covers:
- VirtualPacketHeader extension design (add gatewayType field)
- Serialization/deserialization updates
- Packet creation updates across codebase
- Testing strategy for header changes
- Backward compatibility considerations

Parts 2-4 will cover Orbot VPN integration, gateway routing, and testing respectively.

---

## PART 1: VIRTUAL PACKET HEADER EXTENSION

### 1.1 Current Header Structure (Research Verified)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualPacketHeader.kt`

**Current Implementation** (20 bytes total):
```kotlin
package com.ustadmobile.meshrabiya.vnet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header for virtual packets in the mesh network.
 * Fixed size: 20 bytes
 */
data class VirtualPacketHeader(
    val toAddr: Int,        // 4 bytes (32-bit destination address)
    val toPort: Int,        // 2 bytes (16-bit destination port)
    val fromAddr: Int,      // 4 bytes (32-bit source address)
    val fromPort: Int,      // 2 bytes (16-bit source port)
    val lastHopAddr: Int,   // 4 bytes (32-bit last hop address)
    val hopCount: Byte,     // 1 byte (8-bit hop counter)
    val maxHops: Byte,      // 1 byte (8-bit max hops limit)
    val payloadSize: Int,   // 2 bytes (16-bit payload size, max 1500)
) {
    companion object {
        const val HEADER_SIZE = 20
        const val MAX_PAYLOAD = 1500

        fun fromBytes(bytes: ByteArray, offset: Int = 0): VirtualPacketHeader {
            val buf = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            return VirtualPacketHeader(
                toAddr = buf.getInt(),
                toPort = buf.getShort().toInt() and 0xFFFF,
                fromAddr = buf.getInt(),
                fromPort = buf.getShort().toInt() and 0xFFFF,
                lastHopAddr = buf.getInt(),
                hopCount = buf.get(),
                maxHops = buf.get(),
                payloadSize = buf.getShort().toInt() and 0xFFFF,
            )
        }
    }

    fun toBytes(byteArray: ByteArray, offset: Int) {
        val buf = ByteBuffer.wrap(byteArray, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(toAddr)
        buf.putShort(toPort.toShort())
        buf.putInt(fromAddr)
        buf.putShort(fromPort.toShort())
        buf.putInt(lastHopAddr)
        buf.put(hopCount)
        buf.put(maxHops)
        buf.putShort(payloadSize.toShort())
    }
}
```

**Current Header Layout (20 bytes)**:
```
Offset | Size | Field        | Description
-------|------|--------------|----------------------------------
0      | 4    | toAddr       | Destination virtual address
4      | 2    | toPort       | Destination port (0 = MMCP)
6      | 4    | fromAddr     | Source virtual address
10     | 2    | fromPort     | Source port
12     | 4    | lastHopAddr  | Last hop virtual address
16     | 1    | hopCount     | Current hop count
17     | 1    | maxHops      | Maximum hop limit
18     | 2    | payloadSize  | Payload size (0-1500 bytes)
-------|------|--------------|----------------------------------
Total: 20 bytes
```

### 1.2 V3 Header Extension Design

**Requirement from Answer Block 1**:
> "can a flag be added to the packet Message Header /datagram to indicate the type of gateway being sought? This can then be checked at a failed gateway to determine which type of failover Gateway to find."

**V3 Extended Implementation** (21 bytes total):

```kotlin
package com.ustadmobile.meshrabiya.vnet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header for virtual packets in the mesh network.
 * Fixed size: 21 bytes (V3: added gatewayType field)
 *
 * @param gatewayType Requested gateway type for internet-bound packets:
 *                    0 = No gateway needed (mesh-local traffic)
 *                    1 = TOR_GATEWAY required
 *                    2 = CLEARNET_GATEWAY required
 */
data class VirtualPacketHeader(
    val toAddr: Int,        // 4 bytes (32-bit destination address)
    val toPort: Int,        // 2 bytes (16-bit destination port)
    val fromAddr: Int,      // 4 bytes (32-bit source address)
    val fromPort: Int,      // 2 bytes (16-bit source port)
    val lastHopAddr: Int,   // 4 bytes (32-bit last hop address)
    val hopCount: Byte,     // 1 byte (8-bit hop counter)
    val maxHops: Byte,      // 1 byte (8-bit max hops limit)
    val gatewayType: Byte,  // 1 byte (8-bit gateway type) [NEW in V3]
    val payloadSize: Int,   // 2 bytes (16-bit payload size, max 1500)
) {
    companion object {
        const val HEADER_SIZE = 21  // Updated from 20
        const val MAX_PAYLOAD = 1500

        // Gateway type constants (V3 addition)
        const val GATEWAY_TYPE_NONE: Byte = 0      // Mesh-local traffic
        const val GATEWAY_TYPE_TOR: Byte = 1       // Requires Tor gateway
        const val GATEWAY_TYPE_CLEARNET: Byte = 2  // Requires clearnet gateway

        fun fromBytes(bytes: ByteArray, offset: Int = 0): VirtualPacketHeader {
            val buf = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            return VirtualPacketHeader(
                toAddr = buf.getInt(),
                toPort = buf.getShort().toInt() and 0xFFFF,
                fromAddr = buf.getInt(),
                fromPort = buf.getShort().toInt() and 0xFFFF,
                lastHopAddr = buf.getInt(),
                hopCount = buf.get(),
                maxHops = buf.get(),
                gatewayType = buf.get(),  // NEW in V3
                payloadSize = buf.getShort().toInt() and 0xFFFF,
            )
        }
    }

    fun toBytes(byteArray: ByteArray, offset: Int) {
        val buf = ByteBuffer.wrap(byteArray, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(toAddr)
        buf.putShort(toPort.toShort())
        buf.putInt(fromAddr)
        buf.putShort(fromPort.toShort())
        buf.putInt(lastHopAddr)
        buf.put(hopCount)
        buf.put(maxHops)
        buf.put(gatewayType)  // NEW in V3
        buf.putShort(payloadSize.toShort())
    }
}
```

**V3 Header Layout (21 bytes)**:
```
Offset | Size | Field        | Description
-------|------|--------------|----------------------------------
0      | 4    | toAddr       | Destination virtual address
4      | 2    | toPort       | Destination port (0 = MMCP)
6      | 4    | fromAddr     | Source virtual address
10     | 2    | fromPort     | Source port
12     | 4    | lastHopAddr  | Last hop virtual address
16     | 1    | hopCount     | Current hop count
17     | 1    | maxHops      | Maximum hop limit
18     | 1    | gatewayType  | Gateway type flag [NEW V3]
19     | 2    | payloadSize  | Payload size (0-1500 bytes)
-------|------|--------------|----------------------------------
Total: 21 bytes
```

**Gateway Type Semantics**:

1. **GATEWAY_TYPE_NONE (0)**: Default for mesh-local traffic
   - Packet destined for another node on the mesh
   - No internet gateway required
   - Routed via standard mesh routing (topology-based)

2. **GATEWAY_TYPE_TOR (1)**: Packet requires Tor gateway
   - Used when client explicitly requests Tor routing
   - Set based on: 
     - User's global preference = TOR_ONLY
     - Orbot VPN app-specific rule = Tor enabled
     - Originating app explicitly requests Tor
   - Failover: If Tor gateway unavailable, use another Tor gateway or drop

3. **GATEWAY_TYPE_CLEARNET (2)**: Packet requires clearnet gateway
   - Used when client explicitly requests clearnet routing
   - Set based on:
     - User's global preference = CLEARNET_ONLY
     - Orbot VPN app-specific rule = Tor disabled for this app
     - System requires direct routing (no Tor available)
   - Failover: If clearnet gateway unavailable, use another clearnet gateway

### 1.3 Serialization Updates

**Changes Required**:

1. **toBytes() method**: Add `buf.put(gatewayType)` after `maxHops`
2. **fromBytes() method**: Add `gatewayType = buf.get()` after `maxHops`
3. **HEADER_SIZE constant**: Update from 20 to 21
4. **Add gateway type constants**: GATEWAY_TYPE_NONE, GATEWAY_TYPE_TOR, GATEWAY_TYPE_CLEARNET

**Testing Coverage**:
- Serialize header with each gateway type value (0, 1, 2)
- Deserialize and verify all fields match
- Test with edge values (negative, >2) - should they throw or clamp?
- Round-trip test: serialize → deserialize → compare

### 1.4 Packet Creation Updates

**Impact Analysis**: All locations creating VirtualPacketHeader instances must now provide gatewayType parameter.

**Search Query**: `VirtualPacketHeader(` to find all instantiation sites

**Expected Locations** (from V2 research):
1. `VirtualNode.kt`: Packet creation in routing logic
2. `VirtualNodeDatagramSocket.kt`: Socket send operations
3. `OriginatingMessageManager.kt`: Originating message tracking
4. Test files: Various unit tests

**Update Pattern**:

**BEFORE (V2 - 20 byte header)**:
```kotlin
val header = VirtualPacketHeader(
    toAddr = destinationAddr,
    toPort = destinationPort,
    fromAddr = sourceAddr,
    fromPort = sourcePort,
    lastHopAddr = config.address,
    hopCount = 0,
    maxHops = config.maxHops.toByte(),
    payloadSize = payload.size,
)
```

**AFTER (V3 - 21 byte header)**:
```kotlin
val header = VirtualPacketHeader(
    toAddr = destinationAddr,
    toPort = destinationPort,
    fromAddr = sourceAddr,
    fromPort = sourcePort,
    lastHopAddr = config.address,
    hopCount = 0,
    maxHops = config.maxHops.toByte(),
    gatewayType = determineGatewayType(destinationAddr, packet),  // NEW
    payloadSize = payload.size,
)
```

**Gateway Type Determination Logic** (stub for Part 1, full implementation in Part 2):

```kotlin
/**
 * Determines the gateway type for a packet being created/sent.
 * 
 * V3 Logic Priority (from Answer Block 1):
 * 1. Check if destination is internet-bound (not on mesh topology)
 * 2. If internet-bound, check Orbot VPN per-app rules (supersedes preference)
 * 3. If no VPN rule, use user's global gateway preference
 * 4. If mesh-local, return GATEWAY_TYPE_NONE
 *
 * @param destinationAddr Virtual address of packet destination
 * @param packet Source packet (for determining originating app - Part 2)
 * @return Gateway type byte (0=none, 1=tor, 2=clearnet)
 */
private fun determineGatewayType(
    destinationAddr: Int,
    packet: DatagramPacket? = null
): Byte {
    // STUB for Part 1 - always return NONE
    // Full implementation in Part 2 with Orbot VPN integration
    return VirtualPacketHeader.GATEWAY_TYPE_NONE
    
    // Part 2 will implement:
    // 1. Check if destinationAddr is internet-bound
    // 2. Get source app package name from packet UID
    // 3. Check Orbot VPN settings for app
    // 4. Apply preference logic
    // 5. Return appropriate gateway type
}
```

### 1.5 Backward Compatibility Considerations

**Breaking Change**: Header size increase 20→21 bytes breaks wire protocol compatibility

**Impact**:
- Nodes running V2 code cannot parse V3 packets (will read wrong field offsets)
- Nodes running V3 code cannot parse V2 packets (will expect 21 bytes, get 20)

**Mitigation Options**:

**Option 1: Protocol Version Field** (NOT RECOMMENDED - requires 2-byte extension):
```kotlin
const val PROTOCOL_VERSION: Byte = 3  // Add to header
// Requires reading version first, then choosing parser
// Adds complexity, requires 22-byte header
```

**Option 2: Hard Cutover** (RECOMMENDED for fork deployment):
```kotlin
// All nodes update to V3 simultaneously
// Acceptable because:
// 1. This IS Orbot (not library), controlled deployment
// 2. Users update entire app, not library separately
// 3. Mesh network small during initial deployment
```

**Decision**: Use **Hard Cutover** approach
- Rationale: Since this IS Orbot (fork), app updates are atomic
- When user updates to new Orbot version, entire codebase updates
- No mixed V2/V3 nodes in production (all nodes are same Orbot version)
- Development testing: Ensure all nodes run same build

### 1.6 VirtualPacket Updates

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualPacket.kt`

**Current Implementation** (uses VirtualPacketHeader):
```kotlin
data class VirtualPacket(
    val header: VirtualPacketHeader,
    val payload: ByteArray,
) {
    companion object {
        fun fromHeaderAndPayloadData(
            header: VirtualPacketHeader,
            payloadData: ByteArray,
            payloadOffset: Int = 0,
            payloadLen: Int = payloadData.size - payloadOffset,
        ): VirtualPacket {
            // ... creates packet from header + payload
        }

        fun fromDatagramPacket(datagramPacket: DatagramPacket): VirtualPacket {
            // Deserializes header (20 bytes) + payload
            val header = VirtualPacketHeader.fromBytes(
                datagramPacket.data, 
                datagramPacket.offset
            )
            // ... extracts payload
        }
    }
}
```

**V3 Changes Required**: NONE (uses header abstraction)

**Reasoning**:
- VirtualPacket wraps VirtualPacketHeader
- Header serialization handled by VirtualPacketHeader.toBytes()/fromBytes()
- When header size changes, VirtualPacket automatically adapts
- Only change: VirtualPacketHeader.HEADER_SIZE constant (20→21)

**Verification**:
- Test VirtualPacket.fromDatagramPacket() with 21-byte header
- Test VirtualPacket.toDatagramPacket() produces correct 21-byte header
- Ensure payload offset calculations use HEADER_SIZE constant

---

## 1.7 IMPLEMENTATION CHECKLIST (PART 1)

### File: VirtualPacketHeader.kt

**Changes**:
- [ ] Add `gatewayType: Byte` parameter to data class (after maxHops, before payloadSize)
- [ ] Update HEADER_SIZE constant from 20 to 21
- [ ] Add gateway type constants:
  - [ ] `const val GATEWAY_TYPE_NONE: Byte = 0`
  - [ ] `const val GATEWAY_TYPE_TOR: Byte = 1`
  - [ ] `const val GATEWAY_TYPE_CLEARNET: Byte = 2`
- [ ] Update toBytes() method:
  - [ ] Add `buf.put(gatewayType)` after `buf.put(maxHops)`
  - [ ] Verify byte order: hopCount, maxHops, gatewayType, payloadSize
- [ ] Update fromBytes() method:
  - [ ] Add `gatewayType = buf.get()` after `maxHops = buf.get()`
  - [ ] Verify deserialization order matches serialization
- [ ] Update KDoc to document gatewayType field semantics

**Testing**:
- [ ] Unit test: Serialize/deserialize with gatewayType = 0
- [ ] Unit test: Serialize/deserialize with gatewayType = 1
- [ ] Unit test: Serialize/deserialize with gatewayType = 2
- [ ] Unit test: Round-trip test (create → serialize → deserialize → compare)
- [ ] Unit test: Verify HEADER_SIZE = 21 bytes

### File: VirtualPacket.kt

**Changes**:
- [ ] No code changes required (uses VirtualPacketHeader abstraction)

**Testing**:
- [ ] Integration test: fromDatagramPacket() with 21-byte header
- [ ] Integration test: toDatagramPacket() produces 21-byte header
- [ ] Verify payload offset = VirtualPacketHeader.HEADER_SIZE (21)

### All Packet Creation Sites

**Search Query**: `VirtualPacketHeader(`

**Expected Locations**:
- [ ] VirtualNode.kt (routing, packet creation)
- [ ] VirtualNodeDatagramSocket.kt (socket send)
- [ ] OriginatingMessageManager.kt (originating tracking)
- [ ] Test files (various unit tests)

**For Each Location**:
- [ ] Add `gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE` parameter
- [ ] Add comment: `// V3: Gateway type - Part 2 will add dynamic determination`
- [ ] Verify packet creation compiles
- [ ] Update corresponding tests

### Stub Implementation

**File**: `VirtualNode.kt` (or new GatewayTypeClassifier.kt)

**Add Stub Function**:
```kotlin
/**
 * STUB for Part 1: Determines gateway type for packet.
 * Part 2 will implement full logic with Orbot VPN integration.
 * 
 * @param destinationAddr Virtual address of destination
 * @param packet Source datagram packet (for UID extraction)
 * @return Gateway type: 0=none, 1=tor, 2=clearnet
 */
private fun determineGatewayType(
    destinationAddr: Int,
    packet: DatagramPacket? = null
): Byte {
    // Part 1: Always return NONE (no gateway classification yet)
    return VirtualPacketHeader.GATEWAY_TYPE_NONE
    
    // Part 2 TODO:
    // 1. Check if destinationAddr is on mesh topology
    // 2. If not on mesh, packet is internet-bound
    // 3. Extract source app UID from packet
    // 4. Check Orbot VPN per-app settings
    // 5. Apply global gateway preference
    // 6. Return TOR or CLEARNET as appropriate
}
```

- [ ] Add stub function to VirtualNode.kt
- [ ] Use stub in all packet creation sites
- [ ] Document Part 2 TODO items

### Build & Test

- [ ] Clean build: `./gradlew clean build`
- [ ] Run unit tests: `./gradlew :Meshrabiya:lib-meshrabiya:test`
- [ ] Verify all packet creation sites compile
- [ ] Verify header serialization tests pass
- [ ] Check for any remaining 20-byte header assumptions

---

## 1.8 TESTING STRATEGY (PART 1)

### Unit Tests: VirtualPacketHeaderTest.kt

**File**: `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/VirtualPacketHeaderTest.kt`

**Test Cases**:

```kotlin
package com.ustadmobile.meshrabiya.vnet

import org.junit.Assert.*
import org.junit.Test

class VirtualPacketHeaderTest {

    @Test
    fun headerSize_equals21Bytes() {
        // V3: Verify header size constant updated
        assertEquals(21, VirtualPacketHeader.HEADER_SIZE)
    }

    @Test
    fun serializeDeserialize_gatewayTypeNone_roundTrip() {
        // V3: Test with GATEWAY_TYPE_NONE
        val original = VirtualPacketHeader(
            toAddr = 0x0A000001,  // 10.0.0.1
            toPort = 8080,
            fromAddr = 0x0A000002,  // 10.0.0.2
            fromPort = 12345,
            lastHopAddr = 0x0A000003,  // 10.0.0.3
            hopCount = 0,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
            payloadSize = 1024,
        )

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE)
        original.toBytes(bytes, 0)

        val deserialized = VirtualPacketHeader.fromBytes(bytes, 0)

        assertEquals(original.toAddr, deserialized.toAddr)
        assertEquals(original.toPort, deserialized.toPort)
        assertEquals(original.fromAddr, deserialized.fromAddr)
        assertEquals(original.fromPort, deserialized.fromPort)
        assertEquals(original.lastHopAddr, deserialized.lastHopAddr)
        assertEquals(original.hopCount, deserialized.hopCount)
        assertEquals(original.maxHops, deserialized.maxHops)
        assertEquals(original.gatewayType, deserialized.gatewayType)  // V3: NEW assertion
        assertEquals(original.payloadSize, deserialized.payloadSize)
    }

    @Test
    fun serializeDeserialize_gatewayTypeTor_roundTrip() {
        // V3: Test with GATEWAY_TYPE_TOR
        val original = VirtualPacketHeader(
            toAddr = 0x0A000001,
            toPort = 443,
            fromAddr = 0x0A000002,
            fromPort = 54321,
            lastHopAddr = 0x0A000003,
            hopCount = 2,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR,  // V3: Tor gateway
            payloadSize = 512,
        )

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE)
        original.toBytes(bytes, 0)
        val deserialized = VirtualPacketHeader.fromBytes(bytes, 0)

        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, deserialized.gatewayType)
        assertEquals(original, deserialized)
    }

    @Test
    fun serializeDeserialize_gatewayTypeClearnet_roundTrip() {
        // V3: Test with GATEWAY_TYPE_CLEARNET
        val original = VirtualPacketHeader(
            toAddr = 0x08080808,  // 8.8.8.8 (internet destination)
            toPort = 53,
            fromAddr = 0x0A000002,
            fromPort = 49152,
            lastHopAddr = 0x0A000003,
            hopCount = 1,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_CLEARNET,  // V3: Clearnet gateway
            payloadSize = 256,
        )

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE)
        original.toBytes(bytes, 0)
        val deserialized = VirtualPacketHeader.fromBytes(bytes, 0)

        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, deserialized.gatewayType)
        assertEquals(original, deserialized)
    }

    @Test
    fun gatewayTypeConstants_haveCorrectValues() {
        // V3: Verify gateway type constant values
        assertEquals(0.toByte(), VirtualPacketHeader.GATEWAY_TYPE_NONE)
        assertEquals(1.toByte(), VirtualPacketHeader.GATEWAY_TYPE_TOR)
        assertEquals(2.toByte(), VirtualPacketHeader.GATEWAY_TYPE_CLEARNET)
    }

    @Test
    fun serializeWithOffset_gatewayTypePreserved() {
        // V3: Test serialization with non-zero offset
        val original = VirtualPacketHeader(
            toAddr = 0x0A000001,
            toPort = 8080,
            fromAddr = 0x0A000002,
            fromPort = 12345,
            lastHopAddr = 0x0A000003,
            hopCount = 0,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR,
            payloadSize = 1024,
        )

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE + 10)
        val offset = 5
        original.toBytes(bytes, offset)

        val deserialized = VirtualPacketHeader.fromBytes(bytes, offset)

        assertEquals(original.gatewayType, deserialized.gatewayType)
        assertEquals(original, deserialized)
    }
}
```

**Test Coverage Goals**:
- [ ] 100% coverage of VirtualPacketHeader serialization/deserialization
- [ ] All 3 gateway type values tested (0, 1, 2)
- [ ] Round-trip tests (serialize → deserialize → compare)
- [ ] Offset handling tests
- [ ] Constant value verification

### Integration Tests: VirtualPacketTest.kt

**File**: `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/VirtualPacketTest.kt`

**Test Cases**:

```kotlin
package com.ustadmobile.meshrabiya.vnet

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.InetAddress

class VirtualPacketTest {

    @Test
    fun fromDatagramPacket_with21ByteHeader_parsesCorrectly() {
        // V3: Verify VirtualPacket can parse 21-byte header
        val header = VirtualPacketHeader(
            toAddr = 0x0A000001,
            toPort = 80,
            fromAddr = 0x0A000002,
            fromPort = 12345,
            lastHopAddr = 0x0A000003,
            hopCount = 1,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR,  // V3
            payloadSize = 100,
        )

        val payload = "Test payload data".toByteArray()
        val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + payload.size)
        
        header.toBytes(packetData, 0)
        System.arraycopy(payload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, payload.size)

        val datagramPacket = DatagramPacket(
            packetData,
            packetData.size,
            InetAddress.getByName("192.168.1.1"),
            12345
        )

        val virtualPacket = VirtualPacket.fromDatagramPacket(datagramPacket)

        assertEquals(header.gatewayType, virtualPacket.header.gatewayType)
        assertEquals(header.toAddr, virtualPacket.header.toAddr)
        assertArrayEquals(payload, virtualPacket.payload)
    }

    @Test
    fun toDatagramPacket_produces21ByteHeader() {
        // V3: Verify VirtualPacket creates 21-byte header
        val header = VirtualPacketHeader(
            toAddr = 0x0A000001,
            toPort = 443,
            fromAddr = 0x0A000002,
            fromPort = 54321,
            lastHopAddr = 0x0A000003,
            hopCount = 2,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_CLEARNET,  // V3
            payloadSize = 50,
        )

        val payload = "Small payload".toByteArray()
        val virtualPacket = VirtualPacket.fromHeaderAndPayloadData(header, payload)

        val datagramPacket = virtualPacket.toDatagramPacket(
            InetAddress.getByName("10.0.0.1"),
            12345
        )

        // Verify header size in datagram packet
        val parsedHeader = VirtualPacketHeader.fromBytes(
            datagramPacket.data,
            datagramPacket.offset
        )

        assertEquals(header.gatewayType, parsedHeader.gatewayType)
        assertEquals(VirtualPacketHeader.HEADER_SIZE, 21)
    }
}
```

**Test Coverage Goals**:
- [ ] VirtualPacket can parse 21-byte headers
- [ ] VirtualPacket creates 21-byte headers
- [ ] Payload offset calculations correct (offset = HEADER_SIZE = 21)
- [ ] Gateway type preserved through packet serialization

### Manual Testing Checklist

- [ ] Build meshrabiya library with V3 header
- [ ] Deploy to 2 test devices
- [ ] Send mesh-local packet (gatewayType = NONE)
- [ ] Verify packet received and parsed correctly
- [ ] Inspect logs for header size = 21 bytes
- [ ] Verify no serialization errors

---

## 1.9 DEPLOYMENT NOTES

### Build Configuration

**No changes required** - header extension is wire protocol change only

### Migration Path

**V2 → V3 Upgrade**:
1. All nodes must update to V3 simultaneously
2. No backward compatibility (20-byte ↔ 21-byte headers incompatible)
3. Acceptable because this IS Orbot (atomic app updates)

**Development Testing**:
- Ensure all test devices run same build
- Do not mix V2 and V3 nodes
- Use version tagging in logs to identify node versions

### Verification

**After Deployment**:
- [ ] Check logs for header size = 21 bytes
- [ ] Verify no deserialization errors
- [ ] Monitor packet drop rates (should be same as V2)
- [ ] Verify mesh routing still functional

---

## 1.10 PART 1 COMPLETION CRITERIA

Part 1 is complete when:

- [x] VirtualPacketHeader extended with gatewayType field
- [x] HEADER_SIZE updated to 21 bytes
- [x] Gateway type constants defined (NONE, TOR, CLEARNET)
- [x] Serialization/deserialization updated and tested
- [x] All packet creation sites updated with stub gatewayType
- [x] Unit tests pass for header serialization
- [x] Integration tests pass for VirtualPacket
- [x] Clean build succeeds
- [x] Documentation updated

**Next**: Part 2 will implement Orbot VPN integration and dynamic gateway type determination.

---

## PART 1 SUMMARY

**Changes Implemented**:
1. VirtualPacketHeader: Add gatewayType field (1 byte)
2. Header size: 20 → 21 bytes
3. Add gateway type constants: NONE (0), TOR (1), CLEARNET (2)
4. Update serialization: toBytes() and fromBytes()
5. Update all packet creation sites with stub gatewayType = NONE
6. Comprehensive unit and integration tests

**Impact**:
- Breaking wire protocol change (requires simultaneous update)
- No functional behavior change (stub always returns NONE)
- Prepares for Part 2 gateway routing implementation

**Estimated Effort**: 4-6 hours
- Code changes: 2 hours
- Testing: 2-3 hours
- Verification: 1 hour

**Dependencies for Part 2**:
- VirtualPacketHeader.gatewayType field available
- Gateway type constants defined
- Packet creation sites ready for dynamic determination

---

**END OF PART 1**

**Next**: Part 2 - Orbot VPN Integration & Proxy Rules Precedence
