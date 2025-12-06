# Tor Integration Plan V3 - Part 4B: Integration & E2E Testing
**Version:** 3.0  
**Date:** January 2025  
**Dependencies:** Part 4A complete

---

## 4B.1 INTEGRATION TEST SCENARIOS

### Scenario 1: Per-App VPN Override

**Test:** Chrome with Tor VPN enabled, global preference = CLEARNET_ONLY

```kotlin
@Test
fun `Chrome packet uses Tor gateway despite CLEARNET_ONLY preference`() = runBlocking {
    // Setup
    val prefs = mockk<SharedPreferences>()
    every { prefs.getString("PrefTord", "") } returns "com.android.chrome"
    
    val api = MeshrabiyaApiImpl(context, ...)
    api.setGatewayPreference(GatewayPreference.CLEARNET_ONLY)
    
    // Create packet from Chrome
    val packet = createTestPacket(
        sourceUid = CHROME_UID,
        destinationAddr = INTERNET_ADDR
    )
    
    // Act
    val gatewayType = api.determineGatewayType(packet)
    
    // Assert: VPN rule supersedes preference
    assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
}
```

### Scenario 2: Gateway Failover

**Test:** TOR_ONLY preference, no Tor gateway available, EITHER allows fallback

```kotlin
@Test
fun `EITHER preference falls back to clearnet when no Tor gateway`() = runBlocking {
    // Setup
    val api = MeshrabiyaApiImpl(context, ...)
    api.setGatewayPreference(GatewayPreference.EITHER)
    
    // Mock topology: only clearnet gateways available
    val topology = mockk<VirtualTopology>()
    every { topology.nodes } returns listOf(
        createMockNode(address = 10, roles = setOf(NodeRole.CLEARNET_GATEWAY))
    )
    
    val packet = createInternetBoundPacket(
        gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR  // Request Tor
    )
    
    // Act
    virtualNode.routeViaGateway(packet, null)
    
    // Assert: Fell back to clearnet gateway
    verify { 
        virtualNode.forwardToGateway(
            packet = withArg { 
                assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, it.header.gatewayType)
            },
            gateway = withArg { assertEquals(10, it.address) }
        )
    }
}
```

### Scenario 3: Gateway Selection

**Test:** Multiple gateways, select closest

```kotlin
@Test
fun `selectBestGateway chooses closest gateway`() {
    // Setup: 3 gateways at different distances
    val gateway1 = createMockNode(address = 10)  // 1 hop away
    val gateway2 = createMockNode(address = 20)  // 2 hops away
    val gateway3 = createMockNode(address = 30)  // 3 hops away
    
    val topology = mockk<VirtualTopology>()
    every { topology.getHopDistance(NODE_ADDR, 10) } returns 1
    every { topology.getHopDistance(NODE_ADDR, 20) } returns 2
    every { topology.getHopDistance(NODE_ADDR, 30) } returns 3
    
    val gateways = listOf(gateway1, gateway2, gateway3)
    val packet = createTestPacket()
    
    // Act
    val selected = virtualNode.selectBestGateway(gateways, packet)
    
    // Assert: Closest gateway selected
    assertEquals(10, selected?.address)
}
```

---

## 4B.2 END-TO-END TEST

**File:** `Meshrabiya/lib-meshrabiya/src/androidTest/java/com/ustadmobile/meshrabiya/GatewayRoutingE2ETest.kt`

```kotlin
package com.ustadmobile.meshrabiya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class GatewayRoutingE2ETest {

    private lateinit var context: Context
    private lateinit var clientApi: MeshrabiyaApi
    private lateinit var gatewayApi: MeshrabiyaApi

    @Before
    fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize client node
        clientApi = MeshrabiyaApiImpl(context, ...)
        clientApi.initMesh(
            config = MeshConfig(
                nodeId = "client",
                address = 0x0A000001
            )
        )
        
        // Initialize gateway node
        gatewayApi = MeshrabiyaApiImpl(context, ...)
        gatewayApi.initMesh(
            config = MeshConfig(
                nodeId = "gateway",
                address = 0x0A000002
            )
        )
        
        // Advertise gateway as Tor gateway
        gatewayApi.advertiseAsGateway(GatewayType.TOR)
        
        // Wait for topology convergence
        delay(2000)
    }

    @Test
    fun `client routes packet to internet via Tor gateway`() = runBlocking {
        // Setup client preference
        clientApi.setGatewayPreference(GatewayPreference.TOR_ONLY)
        
        // Create internet-bound packet
        val packet = createInternetPacket(
            destination = "8.8.8.8",
            port = 53
        )
        
        // Send packet
        clientApi.sendPacket(packet)
        
        // Wait for routing
        delay(1000)
        
        // Assert: Gateway received packet
        val gatewayStats = gatewayApi.getGatewayStats()
        assertTrue(gatewayStats.packetsReceived > 0)
        
        // Assert: Packet routed via Tor
        assertEquals(1, gatewayStats.torPacketsRouted)
        assertEquals(0, gatewayStats.clearnetPacketsRouted)
    }

    @Test
    fun `per-app VPN rule supersedes global preference`() = runBlocking {
        // Setup: Global preference = CLEARNET_ONLY
        clientApi.setGatewayPreference(GatewayPreference.CLEARNET_ONLY)
        
        // Setup: Chrome torified in VPN settings
        val prefs = Prefs.getSharedPrefs(context)
        prefs.edit().putString("PrefTord", "com.android.chrome").apply()
        
        // Create packet from Chrome
        val packet = createPacketFromApp(
            packageName = "com.android.chrome",
            destination = "1.1.1.1",
            port = 443
        )
        
        // Send packet
        clientApi.sendPacket(packet)
        delay(1000)
        
        // Assert: Used Tor gateway (VPN rule superseded CLEARNET preference)
        val stats = gatewayApi.getGatewayStats()
        assertEquals(1, stats.torPacketsRouted)
        assertEquals(0, stats.clearnetPacketsRouted)
    }
}
```

---

## 4B.3 TOR STATUS MONITORING TEST

**File:** `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/TorStatusIntegrationTest.kt`

```kotlin
package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TorStatusIntegrationTest {

    @Test
    fun `Tor status broadcast updates StateFlow`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val api = MeshrabiyaApiImpl(context, ...)
        
        // Initial state: Tor OFF
        assertEquals(false, api.torStatus.first())
        
        // Simulate Tor ON broadcast
        val intent = Intent("org.torproject.android.intent.action.STATUS")
        intent.putExtra("org.torproject.android.intent.extra.STATUS", "ON")
        
        api.torStatusReceiver.onReceive(context, intent)
        
        // Assert: Status updated
        assertEquals(true, api.torStatus.first())
    }

    @Test
    fun `Tor STARTING status remains false`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val api = MeshrabiyaApiImpl(context, ...)
        
        // Simulate STARTING broadcast
        val intent = Intent("org.torproject.android.intent.action.STATUS")
        intent.putExtra("org.torproject.android.intent.extra.STATUS", "STARTING")
        
        api.torStatusReceiver.onReceive(context, intent)
        
        // Assert: Conservative mapping (STARTING = false)
        assertEquals(false, api.torStatus.first())
    }
}
```

---

## 4B.4 EDGE CASE TESTS

### No Gateway Available

```kotlin
@Test
fun `TOR_ONLY preference drops packet when no Tor gateway`() = runBlocking {
    val api = MeshrabiyaApiImpl(context, ...)
    api.setGatewayPreference(GatewayPreference.TOR_ONLY)
    
    // No gateways in topology
    val topology = mockk<VirtualTopology>()
    every { topology.nodes } returns emptyList()
    
    val packet = createInternetBoundPacket()
    
    // Act
    virtualNode.routeViaGateway(packet, null)
    
    // Assert: Packet dropped (logged warning)
    verify(exactly = 1) { 
        logger.warn(match { it.contains("Dropping packet") })
    }
}
```

### Stale Gateway Filtering

```kotlin
@Test
fun `stale gateways are not selected`() {
    val now = System.currentTimeMillis()
    
    // Fresh gateway (10 seconds old)
    val freshGateway = createMockNode(
        address = 10,
        roles = setOf(NodeRole.TOR_GATEWAY),
        lastHeartbeat = now - 10_000
    )
    
    // Stale gateway (60 seconds old)
    val staleGateway = createMockNode(
        address = 20,
        roles = setOf(NodeRole.TOR_GATEWAY),
        lastHeartbeat = now - 60_000
    )
    
    val allGateways = listOf(freshGateway, staleGateway)
    val availableGateways = allGateways.filter { !isGatewayStale(it) }
    
    assertEquals(1, availableGateways.size)
    assertEquals(10, availableGateways.first().address)
}
```

---

## 4B.5 IMPLEMENTATION CHECKLIST

### Integration Tests

- [ ] Per-app VPN override test
- [ ] Gateway failover test (EITHER preference)
- [ ] Gateway selection test (closest gateway)
- [ ] No gateway available test (packet drop)
- [ ] Stale gateway filtering test

### E2E Tests

- [ ] Client → Gateway routing test
- [ ] Per-app VPN rule E2E test
- [ ] Tor status monitoring integration test
- [ ] Multi-gateway selection E2E test

### Test Execution

```bash
# Run integration tests
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :Meshrabiya:lib-meshrabiya:connectedAndroidTest

# Run all tests (unit + integration)
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :Meshrabiya:lib-meshrabiya:test \
  :Meshrabiya:lib-meshrabiya:connectedAndroidTest
```

---

**END OF PART 4B**

**Next:** Part 4C - Manual Testing & Deployment
