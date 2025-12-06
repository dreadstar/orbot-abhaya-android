# Tor Integration Plan V3 - Part 4A: Unit Testing Strategy
**Version:** 3.0  
**Date:** January 2025  
**Dependencies:** Parts 1-3 complete

---

## 4A.1 TESTING OVERVIEW

### Test Levels

1. **Unit Tests** (Part 4A): Individual component testing
2. **Integration Tests** (Part 4B): Multi-component interaction
3. **End-to-End Tests** (Part 4C): Full gateway routing flow

### Test Framework

- **Framework:** JUnit 5
- **Mocking:** MockK
- **Coroutines:** kotlinx-coroutines-test
- **DataStore:** In-memory DataStore for testing

---

## 4A.2 PACKET HEADER TESTS

**File:** `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/VirtualPacketHeaderTest.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VirtualPacketHeaderTest {

    @Test
    fun `header size is 21 bytes`() {
        assertEquals(21, VirtualPacketHeader.HEADER_SIZE)
    }

    @Test
    fun `serialize and deserialize with GATEWAY_TYPE_NONE`() {
        val header = VirtualPacketHeader(
            toAddr = 0x0A000001,
            toPort = 8080,
            fromAddr = 0x0A000002,
            fromPort = 12345,
            lastHopAddr = 0x0A000003,
            hopCount = 1,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
            payloadSize = 1024
        )

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE)
        header.toBytes(bytes, 0)
        val deserialized = VirtualPacketHeader.fromBytes(bytes, 0)

        assertEquals(header, deserialized)
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_NONE, deserialized.gatewayType)
    }

    @Test
    fun `serialize and deserialize with GATEWAY_TYPE_TOR`() {
        val header = createTestHeader(gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR)

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE)
        header.toBytes(bytes, 0)
        val deserialized = VirtualPacketHeader.fromBytes(bytes, 0)

        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, deserialized.gatewayType)
    }

    @Test
    fun `serialize and deserialize with GATEWAY_TYPE_CLEARNET`() {
        val header = createTestHeader(gatewayType = VirtualPacketHeader.GATEWAY_TYPE_CLEARNET)

        val bytes = ByteArray(VirtualPacketHeader.HEADER_SIZE)
        header.toBytes(bytes, 0)
        val deserialized = VirtualPacketHeader.fromBytes(bytes, 0)

        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, deserialized.gatewayType)
    }

    @Test
    fun `gateway type constants have correct values`() {
        assertEquals(0.toByte(), VirtualPacketHeader.GATEWAY_TYPE_NONE)
        assertEquals(1.toByte(), VirtualPacketHeader.GATEWAY_TYPE_TOR)
        assertEquals(2.toByte(), VirtualPacketHeader.GATEWAY_TYPE_CLEARNET)
    }

    private fun createTestHeader(gatewayType: Byte) = VirtualPacketHeader(
        toAddr = 0x0A000001,
        toPort = 443,
        fromAddr = 0x0A000002,
        fromPort = 54321,
        lastHopAddr = 0x0A000003,
        hopCount = 2,
        maxHops = 10,
        gatewayType = gatewayType,
        payloadSize = 512
    )
}
```

---

## 4A.3 GATEWAY PREFERENCE TESTS

**File:** `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/api/GatewayPreferenceTest.kt`

```kotlin
package com.ustadmobile.meshrabiya.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GatewayPreferenceTest {

    @Test
    fun `default preference is TOR_ONLY`() {
        assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.DEFAULT)
    }

    @Test
    fun `enum values have expected names`() {
        assertEquals("TOR_ONLY", GatewayPreference.TOR_ONLY.name)
        assertEquals("CLEARNET_ONLY", GatewayPreference.CLEARNET_ONLY.name)
        assertEquals("EITHER", GatewayPreference.EITHER.name)
    }

    @Test
    fun `valueOf parses from string`() {
        assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.valueOf("TOR_ONLY"))
        assertEquals(GatewayPreference.CLEARNET_ONLY, GatewayPreference.valueOf("CLEARNET_ONLY"))
        assertEquals(GatewayPreference.EITHER, GatewayPreference.valueOf("EITHER"))
    }

    @Test
    fun `all enum values are unique`() {
        val values = GatewayPreference.values()
        assertEquals(3, values.size)
        assertEquals(3, values.distinct().size)
    }
}
```

---

## 4A.4 VPN RULES PRECEDENCE TESTS

**File:** `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/VpnRulesPrecedenceTest.kt`

```kotlin
package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.api.GatewayPreference
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VpnRulesPrecedenceTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @BeforeEach
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
    }

    @Test
    fun `VPN rule for Tor supersedes CLEARNET_ONLY preference`() {
        // Chrome is torified
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val isTorified = isPackageTorified("com.android.chrome", mockPrefs)
        
        assertTrue(isTorified)
    }

    @Test
    fun `VPN rule for clearnet supersedes TOR_ONLY preference`() {
        // WhatsApp NOT torified
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val isTorified = isPackageTorified("com.whatsapp", mockPrefs)
        
        assertFalse(isTorified)
    }

    @Test
    fun `empty VPN settings returns false`() {
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        val isTorified = isPackageTorified("com.android.chrome", mockPrefs)
        
        assertFalse(isTorified)
    }

    @Test
    fun `multiple torified apps parsed correctly`() {
        every { mockPrefs.getString("PrefTord", "") } returns 
            "com.android.chrome|org.mozilla.firefox|com.whatsapp"
        
        assertTrue(isPackageTorified("com.android.chrome", mockPrefs))
        assertTrue(isPackageTorified("org.mozilla.firefox", mockPrefs))
        assertTrue(isPackageTorified("com.whatsapp", mockPrefs))
        assertFalse(isPackageTorified("org.telegram.messenger", mockPrefs))
    }

    private fun isPackageTorified(packageName: String, prefs: SharedPreferences): Boolean {
        val torifiedAppsString = prefs.getString("PrefTord", "") ?: ""
        if (torifiedAppsString.isEmpty()) return false
        
        val torifiedPackages = torifiedAppsString.split("|").filter { it.isNotBlank() }
        return torifiedPackages.contains(packageName)
    }
}
```

---

## 4A.5 GATEWAY DISCOVERY TESTS

**File:** `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/GatewayDiscoveryTest.kt`

```kotlin
package com.ustadmobile.meshrabiya.vnet

import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GatewayDiscoveryTest {

    private lateinit var mockTopology: VirtualTopology
    private lateinit var testNodes: List<VirtualNode>

    @BeforeEach
    fun setup() {
        mockTopology = mockk(relaxed = true)
        
        // Create test nodes
        testNodes = listOf(
            createMockNode(address = 1, roles = setOf(NodeRole.TOR_GATEWAY)),
            createMockNode(address = 2, roles = setOf(NodeRole.CLEARNET_GATEWAY)),
            createMockNode(address = 3, roles = setOf(NodeRole.TOR_GATEWAY, NodeRole.CLEARNET_GATEWAY)),
            createMockNode(address = 4, roles = emptySet()),  // No gateway role
        )
        
        every { mockTopology.nodes } returns testNodes
    }

    @Test
    fun `getAvailableTorGateways returns only Tor gateways`() {
        val torGateways = testNodes.filter { 
            it.roles.contains(NodeRole.TOR_GATEWAY) 
        }
        
        assertEquals(2, torGateways.size)
        assertTrue(torGateways.any { it.address == 1 })
        assertTrue(torGateways.any { it.address == 3 })
    }

    @Test
    fun `getAvailableClearnetGateways returns only clearnet gateways`() {
        val clearnetGateways = testNodes.filter { 
            it.roles.contains(NodeRole.CLEARNET_GATEWAY) 
        }
        
        assertEquals(2, clearnetGateways.size)
        assertTrue(clearnetGateways.any { it.address == 2 })
        assertTrue(clearnetGateways.any { it.address == 3 })
    }

    @Test
    fun `stale gateways are filtered out`() {
        val now = System.currentTimeMillis()
        
        val freshNode = createMockNode(
            address = 1,
            roles = setOf(NodeRole.TOR_GATEWAY),
            lastHeartbeat = now - 10_000  // 10 seconds ago (fresh)
        )
        
        val staleNode = createMockNode(
            address = 2,
            roles = setOf(NodeRole.TOR_GATEWAY),
            lastHeartbeat = now - 60_000  // 60 seconds ago (stale)
        )
        
        assertFalse(isGatewayStale(freshNode, now))
        assertTrue(isGatewayStale(staleNode, now))
    }

    private fun createMockNode(
        address: Int,
        roles: Set<NodeRole>,
        lastHeartbeat: Long? = System.currentTimeMillis()
    ): VirtualNode {
        return mockk<VirtualNode>().apply {
            every { this@apply.address } returns address
            every { this@apply.roles } returns roles
            every { this@apply.lastHeartbeat } returns lastHeartbeat
        }
    }

    private fun isGatewayStale(node: VirtualNode, now: Long = System.currentTimeMillis()): Boolean {
        val lastSeen = node.lastHeartbeat ?: return true
        return (now - lastSeen) > 30_000L
    }
}
```

---

## 4A.6 IMPLEMENTATION CHECKLIST

### Test Files to Create

- [ ] `VirtualPacketHeaderTest.kt` (header serialization)
- [ ] `GatewayPreferenceTest.kt` (enum values)
- [ ] `VpnRulesPrecedenceTest.kt` (proxy rules logic)
- [ ] `GatewayDiscoveryTest.kt` (gateway finding)
- [ ] `GatewaySelectionTest.kt` (best gateway selection)
- [ ] `NetworkInfoTest.kt` (gateway counting)

### Test Coverage Goals

- [ ] VirtualPacketHeader: 100% (all gateway types)
- [ ] GatewayPreference: 100% (all enum values)
- [ ] VPN precedence logic: 90%+
- [ ] Gateway discovery: 90%+
- [ ] Gateway selection: 80%+

### Build Commands

```bash
# Run unit tests
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :Meshrabiya:lib-meshrabiya:test --console=plain

# Run with coverage
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :Meshrabiya:lib-meshrabiya:testDebugUnitTest \
  :Meshrabiya:lib-meshrabiya:jacocoTestReport
```

---

**END OF PART 4A**

**Next:** Part 4B - Integration Testing
