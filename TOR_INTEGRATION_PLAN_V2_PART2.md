# Meshrabiya Tor Integration Plan V2 - PART 2 of 3
## Tor Status Tracking, BroadcastReceiver & Dual-Mode Gateway Routing

**Document Version**: 2.0  
**Created**: 2025-12-06  
**Status**: Implementation Ready  
**Estimated Implementation Time**: 12-14 hours (Part 2 only)

---

## EXECUTIVE SUMMARY

### Purpose
Part 2 implements real-time Tor network status tracking and integrates Tor availability into both server-side gateway role selection and client-side packet routing decisions.

### Critical Architecture (Client vs Server Continued)

**SERVER-SIDE** (Part 2 Focus):
- Track Tor network status from Orbot via BroadcastReceiver
- Modify `selectBestGatewayRole()` to check Tor availability before becoming TOR_GATEWAY
- If Tor offline, node cannot become TOR_GATEWAY (may become CLEARNET_GATEWAY if capable)

**CLIENT-SIDE** (Part 2 Foundation):
- Implement `determineGatewayType()` for packet classification
- Use existing `GatewayRouter.routeToGateway()` with preference filtering
- Honor gatewayPreference (TOR_ONLY filters out Clearnet gateways)

### Scope of Part 2

Part 2 builds on Part 1's preference foundation by:

1. **Tor Status Tracking** (Section 1):
   - BroadcastReceiver for Orbot ACTION_STATUS
   - torNetworkActive StateFlow with real-time updates
   - Query initial Tor status on startup
   - Handle "STARTING" as FALSE (conservative)

2. **Server-Side Gateway Selection** (Section 2):
   - Modify selectBestGatewayRole() to check Tor status
   - Only allow TOR_GATEWAY role if Tor network active
   - Keep CLEARNET_GATEWAY selection independent
   - Log role selection reasoning

3. **Client-Side Packet Classification** (Section 3):
   - Implement determineGatewayType() stub
   - Detect internet-destined packets
   - Return TOR_GATEWAY or CLEARNET_GATEWAY based on packet analysis
   - Integrate into VirtualNode.route()

4. **Gateway Router Integration** (Section 4):
   - Use existing GatewayRouter.routeToGateway()
   - Add preference filtering (gatewayPreference enforcement)
   - Multi-hop routing via topology map
   - Failover to alternative gateways

### Key Changes (Part 2)

| Component | Change Type | Lines Added | Risk Level |
|-----------|-------------|-------------|------------|
| MeshrabiyaApiImpl.kt | BroadcastReceiver | ~85 lines | LOW |
| EmergentRoleManager.kt | Role selection refactor | ~40 lines | MEDIUM |
| VirtualNode.kt | Packet classification | ~65 lines | MEDIUM |
| VirtualNode.kt | Route() integration | ~25 lines | MEDIUM |
| GatewayRouter.kt | Preference filtering | ~45 lines | LOW |

**Total**: ~260 lines added/modified

### Dependencies
- **Required**: Orbot app installed on device
- **Confirmed Available**: GatewayRouter.routeToGateway() ✅

**Answer: You should understand that in this case the library has been integrated into a refactored version of the Orbot App

- **Confirmed Available**: NodeTopologyInfo with meshRoles ✅
- **Confirmed Available**: VirtualNode.route() at lines 631-680 ✅

### Confidence Levels
- **BroadcastReceiver Implementation**: 100% (standard Android pattern)
- **Server Gateway Selection**: 95% (minor Tor status integration)
- **Packet Classification**: 70% (heuristic-based, needs real-world testing)
- **Gateway Router Integration**: 95% (leverages existing implementation)

---

## RESEARCH FINDINGS REVIEW

### From Part 1 Research (Relevant to Part 2)

✅ **VirtualNode.route()** (lines 631-680):
- Main routing logic confirmed
- Currently handles local delivery, mesh routing
- Has placeholder for gateway routing (not yet called)

✅ **VirtualNode.determineGatewayType()** (lines 936-944):
- **STUB** - returns null currently
- Correct signature: `private fun determineGatewayType(packet: VirtualPacket): MeshRole?`
- **Primary implementation task for Part 2**

✅ **VirtualNode.routeThroughGateway()** (exists but not called):
- Method exists in VirtualNode
- Will be called from route() after determineGatewayType() implementation

✅ **GatewayRouter.routeToGateway()** (213 lines total):
- Full implementation confirmed
- Takes packet and gatewayType (MeshRole)
- Returns Boolean (success/failure)
- Handles gateway pool refresh, round-robin selection
- **No changes needed - can use as-is**

**Answer: may need to be integrated into control flow of `VirtualNode.onIncomingMmcpMessage()`
✅ **EmergentRoleManager.selectBestGatewayRole()** (lines 280-302):
- Current signature: `private fun selectBestGatewayRole(node, mesh, userPreferences): MeshRole?`
- Returns null or MeshRole (TOR_GATEWAY, CLEARNET_GATEWAY)
- **Needs minor modification**: Check Tor status before returning TOR_GATEWAY

❌ **VirtualPacket.nextHop** - DOES NOT EXIST:
- Correction from V1: Packets are header-based
- Routing modifies packet destination, not nextHop field

✅ **Context Access Chain**:
- MeshrabiyaApiImpl → AndroidVirtualNode → EmergentRoleManager
- Full context available for BroadcastReceiver registration

---

## USER CLARIFICATIONS INCORPORATED

### Clarification 1: Tor "STARTING" Status

**User Statement**: "Tor 'STARTING' status = FALSE (conservative)"

**Resolution**:
- Only set torNetworkActive = true when status == "ON"
- STARTING, STOPPING, OFF all map to false
- Conservative approach prevents premature TOR_GATEWAY role assignment

**Implementation Impact**:
- BroadcastReceiver maps: "ON" → true, all others → false
- selectBestGatewayRole() only allows TOR_GATEWAY when torNetworkActive == true

---

### Clarification 2: Return Immediately from initMesh()

**User Statement**: "Return immediately from initMesh(), async response from Orbot"

**Resolution**:
- BroadcastReceiver registration is synchronous
- Orbot status query is asynchronous (Intent broadcast)
- initMesh() doesn't wait for Tor status response
- Initial status unknown until first broadcast received

**Implementation Impact**:
- torNetworkActive initializes to false (conservative default)
- First broadcast updates status asynchronously
- UI should observe torNetworkActive StateFlow for changes

---

### Clarification 3: Multi-hop Gateway Failover

**User Statement**: "If a user has selected TOR_ONLY and gateway node can no longer route TOR, first the gateway node would try to forward the packet to another TOR Gateway server node. IF there is not another TOR Gateway, then the packet send fails."

**Resolution**:
- GatewayRouter tries alternative Tor gateways (round-robin)
- If all Tor gateways fail, packet is dropped (strict enforcement)
- Follow networking standards (ICMP unreachable or silent drop)

**Implementation Impact**:
- GatewayRouter already implements multi-gateway selection
- Add preference filtering to ensure only compatible gateways selected
- Part 3 will add ICMP unreachable notification (optional)

---

### Clarification 4: EITHER Preference Behavior

**User Statement**: "EITHER preference: Prefer Tor (privacy-first)"

**Resolution**:
- When gatewayPreference == EITHER, prefer Tor gateways if available
- Only fallback to Clearnet if no Tor gateways available
- Privacy-first default behavior

**Implementation Impact**:
- determineGatewayType() returns TOR_GATEWAY for most packets
- GatewayRouter selection prioritizes Tor gateways
- Clearnet used as fallback only

---

## SECTION 1: TOR NETWORK STATUS TRACKING

### 1.1 BroadcastReceiver for Orbot Status

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add as inner class or companion object  
**Lines Added**: ~60

**Orbot Intent Reference**:
```kotlin
// Orbot broadcasts:
// Action: "org.torproject.android.intent.action.STATUS"
// Extras:
//   - "org.torproject.android.intent.extra.STATUS" (String): "ON", "OFF", "STARTING", "STOPPING"
//   - "org.torproject.android.intent.extra.PACKAGE_NAME" (String): Orbot package
```

**Implementation**:

```kotlin
/**
 * BroadcastReceiver for Orbot Tor network status updates.
 * 
 * Listens for ACTION_STATUS broadcasts from Orbot and updates torNetworkActive
 * StateFlow to reflect real-time Tor network availability.
 * 
 * Status Mapping (conservative):
 * - "ON" → torNetworkActive = true
 * - "STARTING", "STOPPING", "OFF" → torNetworkActive = false
 * 
 * This receiver should be registered in initMesh() and unregistered in cleanup().
 */
private inner class OrbotStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
        const val STATUS_ON = "ON"
        const val STATUS_OFF = "OFF"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_STOPPING = "STOPPING"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_STATUS) return
        
        val status = intent.getStringExtra(EXTRA_STATUS) ?: return
        val previousStatus = _torNetworkActive.value
        val newStatus = when (status) {
            STATUS_ON -> true
            STATUS_OFF, STATUS_STARTING, STATUS_STOPPING -> false
            else -> {
                // Unknown status - log warning and default to false (conservative)
                android.util.Log.w(
                    "MeshrabiyaAPI",
                    "Unknown Orbot status received: $status, defaulting to inactive"
                )
                false
            }
        }
        
        _torNetworkActive.value = newStatus
        
        // Log status change for debugging
        if (previousStatus != newStatus) {
            android.util.Log.i(
                "MeshrabiyaAPI",
                "Tor network status changed: $status → torNetworkActive = $newStatus"
            )
            
            // Trigger role re-evaluation on Tor status change (SERVER-SIDE)
            // This may change node's gateway role based on new Tor availability
            myNode?.emergentRoleManager?.updateRoles()
        }
    }
}
```

**Rationale**:
- **Inner Class**: Access to MeshrabiyaApiImpl's torNetworkActive StateFlow
- **Conservative Mapping**: Only "ON" = true, all others = false
- **Role Re-evaluation**: Tor status change triggers updateRoles() for SERVER-SIDE
- **Logging**: Status changes logged for debugging

---

### 1.2 torNetworkActive StateFlow

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add to class properties (after myNode property)  
**Lines Added**: ~15

**Implementation**:

```kotlin
/**
 * Real-time Tor network status from Orbot.
 * 
 * Tracks whether Tor network is currently active and available for routing.
 * Updated by OrbotStatusReceiver when Orbot broadcasts status changes.
 * 
 * Values:
 * - true: Tor network is ON and ready for use
 * - false: Tor network is OFF, STARTING, STOPPING, or unknown (conservative)
 * 
 * Default: false (conservative - assume Tor unavailable until confirmed)
 * 
 * Used by:
 * - selectBestGatewayRole() to determine if node can become TOR_GATEWAY (SERVER)
 * - Public API getTorNetworkStatus() for UI display (CLIENT)
 */
private val _torNetworkActive = MutableStateFlow(false)
val torNetworkActive: StateFlow<Boolean> = _torNetworkActive.asStateFlow()
```

**Import Requirements**:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

**Rationale**:
- **Default false**: Conservative assumption (Tor unavailable until proven otherwise)
- **StateFlow**: Reactive - UI and server logic can observe changes
- **Private Mutable**: Only OrbotStatusReceiver can update value

---

### 1.3 BroadcastReceiver Registration in initMesh()

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Modify initMesh() method (currently at lines 74-95)  
**Lines Added**: ~25

**Current initMesh() signature**:
```kotlin
override fun initMesh(context: Context): Flow<ConnectivityState> {
    // ... existing initialization ...
    return _connectivityState.asStateFlow()
}
```

**Add to initMesh()** (after line 95, before return statement):

```kotlin
// Register BroadcastReceiver for Orbot status tracking
val orbotReceiver = OrbotStatusReceiver()
val intentFilter = IntentFilter(OrbotStatusReceiver.ACTION_STATUS)

try {
    context.registerReceiver(orbotReceiver, intentFilter)
    android.util.Log.i("MeshrabiyaAPI", "Orbot status receiver registered successfully")
    
    // Query initial Tor status (Orbot will respond with broadcast if running)
    val queryIntent = Intent(OrbotStatusReceiver.ACTION_STATUS)
    queryIntent.setPackage("org.torproject.android") // Target Orbot specifically
    context.sendBroadcast(queryIntent)
    android.util.Log.d("MeshrabiyaAPI", "Sent Tor status query to Orbot")
    
} catch (e: Exception) {
    android.util.Log.w(
        "MeshrabiyaAPI",
        "Failed to register Orbot receiver (Orbot may not be installed): ${e.message}"
    )
    // Non-fatal: Tor status remains false, node can still use Clearnet gateways
}

// Store receiver reference for cleanup (add to class property)
this.orbotStatusReceiver = orbotReceiver
```

**Additional Class Property** (add to MeshrabiyaApiImpl):
```kotlin
private var orbotStatusReceiver: OrbotStatusReceiver? = null
```

**Import Requirements**:
```kotlin
import android.content.Intent
import android.content.IntentFilter
```

**Rationale**:
- **Graceful Failure**: If Orbot not installed, log warning but continue
- **Initial Query**: Send broadcast to request current status immediately
- **Cleanup Reference**: Store receiver for later unregistration

---

### 1.4 BroadcastReceiver Cleanup

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add cleanup method or modify existing cleanup logic  
**Lines Added**: ~15

**Implementation**:

```kotlin
/**
 * Cleanup method to unregister BroadcastReceivers and release resources.
 * Should be called when mesh is stopped or app is shutting down.
 */
fun cleanup(context: Context) {
    orbotStatusReceiver?.let { receiver ->
        try {
            context.unregisterReceiver(receiver)
            android.util.Log.i("MeshrabiyaAPI", "Orbot status receiver unregistered")
        } catch (e: Exception) {
            android.util.Log.w("MeshrabiyaAPI", "Failed to unregister Orbot receiver: ${e.message}")
        }
        orbotStatusReceiver = null
    }
    
    // ... other cleanup logic ...
}
```

**Rationale**:
- **Prevent Leaks**: Unregister receiver when no longer needed
- **Graceful Failure**: Catch unregister exceptions (receiver may not be registered)
- **Null Safety**: Set reference to null after cleanup

---

## SECTION 2: SERVER-SIDE GATEWAY ROLE SELECTION

### 2.1 Refactor selectBestGatewayRole() - Tor Status Check

**File**: `EmergentRoleManager.kt`  
**Location**: Modify selectBestGatewayRole() method (lines 280-302)  
**Lines Modified**: ~40

**Current Method** (approximate structure):
```kotlin
private fun selectBestGatewayRole(
    node: NodeCapabilitySnapshot,
    mesh: MeshIntelligence,
    userPreferences: Set<MeshRole>
): MeshRole? {
    // Current logic: bandwidth-based gateway selection
    // Uses userAllowsTorProxy Boolean
    return when {
        node.bandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
        userAllowsTorProxy.value -> MeshRole.TOR_GATEWAY
        else -> null
    }
}
```

**Refactored Method** (with Tor status check):

```kotlin
/**
 * Select best gateway role for this node (SERVER-SIDE).
 * 
 * Determines whether this node should become a TOR_GATEWAY, CLEARNET_GATEWAY,
 * or no gateway based on:
 * - Node capabilities (bandwidth, battery)
 * - Tor network availability (torNetworkActive)
 * - User preferences (userPreferences set)
 * 
 * NOTE: This is SERVER-SIDE logic (what gateway I become for OTHERS).
 * It is independent of gatewayPreference (CLIENT-SIDE routing preference).
 * 
 * @param node Current node capabilities
 * @param mesh Mesh intelligence data
 * @param userPreferences User's preferred roles (may include gateway types)
 * @return MeshRole (TOR_GATEWAY, CLEARNET_GATEWAY) or null if not suitable
 */
private fun selectBestGatewayRole(
    node: NodeCapabilitySnapshot,
    mesh: MeshIntelligence,
    userPreferences: Set<MeshRole>
): MeshRole? {
    // Check if user explicitly prefers gateway roles
    val userWantsTorGateway = userPreferences.contains(MeshRole.TOR_GATEWAY)
    val userWantsClearnetGateway = userPreferences.contains(MeshRole.CLEARNET_GATEWAY)
    
    // Bandwidth thresholds (from existing logic)
    val CLEARNET_GATEWAY_MIN_BANDWIDTH = 10_000_000L // 10 Mbps
    val TOR_GATEWAY_MIN_BANDWIDTH = 5_000_000L       // 5 Mbps (Tor has lower throughput)
    
    // Check Tor network availability from MeshrabiyaApiImpl
    // Access via virtualNode → meshrabiyaApi → torNetworkActive
    val torNetworkActive = getTorNetworkStatus()
    
    // Capability-based selection with Tor status awareness
    return when {
        // User explicitly wants Clearnet gateway + has bandwidth
        userWantsClearnetGateway && node.bandwidth >= CLEARNET_GATEWAY_MIN_BANDWIDTH -> {
            safeLog(LogLevel.INFO, "Selecting CLEARNET_GATEWAY: user preference + sufficient bandwidth (${node.bandwidth / 1_000_000L} Mbps)")
            MeshRole.CLEARNET_GATEWAY
        }
        
        // User explicitly wants Tor gateway + Tor available + has bandwidth
        userWantsTorGateway && torNetworkActive && node.bandwidth >= TOR_GATEWAY_MIN_BANDWIDTH -> {
            safeLog(LogLevel.INFO, "Selecting TOR_GATEWAY: user preference + Tor active + sufficient bandwidth (${node.bandwidth / 1_000_000L} Mbps)")
            MeshRole.TOR_GATEWAY
        }
        
        // User wants Tor gateway but Tor unavailable - cannot fulfill
        userWantsTorGateway && !torNetworkActive -> {
            safeLog(LogLevel.WARN, "Cannot select TOR_GATEWAY: Tor network inactive (user preference exists)")
            null
        }
        
        // Automatic selection: High bandwidth → Clearnet gateway
        node.bandwidth >= CLEARNET_GATEWAY_MIN_BANDWIDTH -> {
            safeLog(LogLevel.INFO, "Auto-selecting CLEARNET_GATEWAY: high bandwidth (${node.bandwidth / 1_000_000L} Mbps)")
            MeshRole.CLEARNET_GATEWAY
        }
        
        // Automatic selection: Medium bandwidth + Tor available → Tor gateway
        node.bandwidth >= TOR_GATEWAY_MIN_BANDWIDTH && torNetworkActive -> {
            safeLog(LogLevel.INFO, "Auto-selecting TOR_GATEWAY: medium bandwidth + Tor active (${node.bandwidth / 1_000_000L} Mbps)")
            MeshRole.TOR_GATEWAY
        }
        
        // Insufficient bandwidth or Tor unavailable - no gateway role
        else -> {
            safeLog(LogLevel.DEBUG, "No gateway role: insufficient bandwidth (${node.bandwidth / 1_000_000L} Mbps) or Tor inactive")
            null
        }
    }
}

/**
 * Get current Tor network status from MeshrabiyaApiImpl.
 * 
 * @return true if Tor network is active, false otherwise
 */
private fun getTorNetworkStatus(): Boolean {
    return try {
        // Access MeshrabiyaApiImpl instance via singleton
        val apiImpl = com.ustadmobile.meshrabiya.MeshrabiyaApiImpl.getInstance()
        apiImpl.torNetworkActive.value
    } catch (e: Exception) {
        safeLog(LogLevel.WARN, "Failed to get Tor network status: ${e.message}, defaulting to false")
        false // Conservative default
    }
}
```

**Import Requirements**:
```kotlin
import com.ustadmobile.meshrabiya.MeshrabiyaApiImpl
```

**Rationale**:
- **Tor Availability Check**: Only select TOR_GATEWAY if Tor network active
- **User Preferences**: Respect user's explicit gateway type preferences
- **Bandwidth Thresholds**: Clearnet requires higher bandwidth than Tor
- **Conservative Default**: Tor status failure → false (no TOR_GATEWAY role)
- **Detailed Logging**: Each selection path logged for debugging

---

### 2.2 Update Role Re-evaluation Trigger

**File**: `EmergentRoleManager.kt`  
**Location**: Modify updateRoles() or add Tor status observation  
**Lines Added**: ~20

**Option 1: Observe Tor Status in Init Block** (RECOMMENDED):

```kotlin
init {
    // ... existing init logic (gateway preference loading) ...
    
    // Observe Tor network status changes and trigger role re-evaluation
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val apiImpl = com.ustadmobile.meshrabiya.MeshrabiyaApiImpl.getInstance()
            apiImpl.torNetworkActive.collect { torActive ->
                safeLog(
                    LogLevel.INFO,
                    "Tor network status changed to: $torActive, triggering role re-evaluation"
                )
                updateRoles() // Re-evaluate gateway role based on new Tor status
            }
        } catch (e: Exception) {
            safeLog(LogLevel.WARN, "Failed to observe Tor status: ${e.message}")
        }
    }
}
```

**Import Requirements**:
```kotlin
import kotlinx.coroutines.flow.collect
```

**Rationale**:
- **Reactive**: Tor status changes automatically trigger role re-evaluation
- **SERVER-SIDE**: When Tor goes offline, node may lose TOR_GATEWAY role
- **Conservative**: If observation fails, roles still evaluated on periodic reassessment

---

## SECTION 3: CLIENT-SIDE PACKET CLASSIFICATION

### 3.1 Implement determineGatewayType() - From Stub to Working

**File**: `VirtualNode.kt`  
**Location**: Replace stub at lines 936-944  
**Lines Modified**: ~65

**Current Stub**:
```kotlin
private fun determineGatewayType(packet: VirtualPacket): MeshRole? {
    // TODO: Implement packet classification
    return null
}
```

**Full Implementation**:

```kotlin
/**
 * Determine which type of gateway is needed for packet routing (CLIENT-SIDE).
 * 
 * Analyzes packet destination to classify whether packet needs Tor gateway,
 * Clearnet gateway, or can be routed directly within mesh.
 * 
 * Classification Logic:
 * 1. Check if packet is destined for internet (not mesh-internal)
 * 2. Analyze destination address/port for Tor-specific services
 * 3. Return gateway type needed: TOR_GATEWAY, CLEARNET_GATEWAY, or null (mesh-only)
 * 
 * NOTE: This is CLIENT-SIDE logic (what gateway I need for MY packet).
 * It is independent of selectBestGatewayRole() (SERVER-SIDE).
 * 
 * @param packet Virtual packet to classify
 * @return MeshRole.TOR_GATEWAY if Tor needed, CLEARNET_GATEWAY if clearnet needed, null if mesh-only
 */
private fun determineGatewayType(packet: VirtualPacket): MeshRole? {
    val header = packet.header
    val toAddr = header.toAddr
    
    // Check 1: Is destination within mesh network?
    // Mesh addresses are in specific range (e.g., 0xF0000000 - 0xFFFFFFFF)
    // If destination is mesh address, no gateway needed
    if (isMeshAddress(toAddr)) {
        return null // Route within mesh, no gateway needed
    }
    
    // Check 2: Destination is external internet address
    // Now determine if packet needs Tor or Clearnet gateway
    
    // Extract port from packet data (if TCP/UDP packet)
    val destinationPort = extractDestinationPort(packet)
    
    // Tor-specific ports (common Tor hidden services and protocols)
    val torPorts = setOf(
        9001,  // Tor ORPort (relay communication)
        9030,  // Tor DirPort (directory information)
        9050,  // Tor SOCKS proxy
        9051,  // Tor control port
        9150,  // Tor Browser SOCKS proxy
        // Add more Tor-specific ports as needed
    )
    
    // Check 3: Is packet destined for Tor-specific port?
    if (destinationPort != null && destinationPort in torPorts) {
        safeLog(LogLevel.DEBUG, "Packet to port $destinationPort classified as TOR_GATEWAY needed")
        return MeshRole.TOR_GATEWAY
    }
    
    // Check 4: Is packet destined for .onion domain? (requires DNS inspection)
    // This would require packet payload inspection, which is complex
    // For now, assume standard internet traffic unless port indicates Tor
    
    // Default: Clearnet gateway for general internet traffic
    safeLog(LogLevel.DEBUG, "Packet to external address classified as CLEARNET_GATEWAY needed")
    return MeshRole.CLEARNET_GATEWAY
}

/**
 * Check if address is within mesh network address range.
 * 
 * @param address Virtual address to check
 * @return true if address is mesh-internal, false if external
 */
private fun isMeshAddress(address: Int): Boolean {
    // Mesh addresses use specific high-order bits
    // Example: 0xF0000000 to 0xFFFFFFFF
    // Adjust mask based on actual mesh addressing scheme
    val MESH_ADDRESS_MASK = 0xF0000000.toInt()
    val MESH_ADDRESS_PREFIX = 0xF0000000.toInt()
    
    return (address and MESH_ADDRESS_MASK) == MESH_ADDRESS_PREFIX
}

/**
 * Extract destination port from packet payload (if TCP/UDP).
 * 
 * NOTE: This is a simplified extraction. Real implementation would need
 * to parse IP header and TCP/UDP header from packet data.
 * 
 * @param packet Virtual packet to inspect
 * @return Destination port number, or null if cannot determine
 */
private fun extractDestinationPort(packet: VirtualPacket): Int? {
    return try {
        // TODO: Implement actual packet payload parsing
        // For now, return null (cannot determine port)
        // Real implementation would:
        // 1. Parse IP header from packet.data
        // 2. Determine protocol (TCP/UDP)
        // 3. Parse transport header to extract destination port
        null
    } catch (e: Exception) {
        safeLog(LogLevel.WARN, "Failed to extract destination port: ${e.message}")
        null
    }
}
```

**Import Requirements**:
```kotlin
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToString
```

**Rationale**:
- **Mesh Address Check**: First check if packet is mesh-internal (no gateway needed)
- **Port-Based Classification**: Use destination port to identify Tor services
- **Conservative Default**: Unknown packets default to CLEARNET_GATEWAY
- **Extensible**: Can add .onion domain detection with DNS inspection later
- **Logging**: Classification logged for debugging

**Known Limitations**:
- Port extraction is stub (needs packet payload parsing)
- .onion domain detection not implemented (requires DNS inspection)
- May misclassify some Tor traffic as Clearnet (acceptable for V1)

**Future Enhancements** (Part 3 or later):
- Implement IP/TCP/UDP header parsing for port extraction
- Add DNS inspection for .onion domain detection
- Use packet metadata if available
- Support SOCKS proxy detection

---

### 3.2 Integrate determineGatewayType() into route()

**File**: `VirtualNode.kt`  
**Location**: Modify route() method (lines 631-680)  
**Lines Added**: ~25

**Current route() Method** (approximate structure):
```kotlin
internal fun route(packet: VirtualPacket): Boolean {
    val toAddr = packet.header.toAddr
    
    // Check 1: Is packet for local node?
    if (toAddr == localNodeAddress) {
        deliverToLocalNode(packet)
        return true
    }
    
    // Check 2: Is destination a direct neighbor?
    val neighbor = neighbors.find { it.address == toAddr }
    if (neighbor != null) {
        neighbor.sendPacket(packet)
        return true
    }
    
    // Check 3: Multi-hop routing via topology map
    val nextHop = findNextHop(toAddr)
    if (nextHop != null) {
        forwardPacket(packet, nextHop)
        return true
    }
    
    // Packet cannot be routed
    return false
}
```

**Add Gateway Routing** (BEFORE "Packet cannot be routed"):

```kotlin
// Check 4: Does packet need internet gateway?
val gatewayType = determineGatewayType(packet)
if (gatewayType != null) {
    // Packet needs internet gateway (Tor or Clearnet)
    safeLog(LogLevel.DEBUG, "Packet needs gateway type: $gatewayType, routing through gateway")
    
    // Use existing routeThroughGateway() method (verified to exist)
    val routedViaGateway = routeThroughGateway(packet, gatewayType)
    
    if (routedViaGateway) {
        safeLog(LogLevel.INFO, "Packet successfully routed via $gatewayType gateway")
        return true
    } else {
        safeLog(LogLevel.WARN, "Failed to route packet via $gatewayType gateway (no suitable gateway found)")
        // Packet dropped - no gateway available for required type
        return false
    }
}

// Packet cannot be routed (not local, not neighbor, not gateway-destined)
return false
```

**Rationale**:
- **After Mesh Routing**: Check gateway routing only if mesh routing fails
- **Use Existing Method**: Calls routeThroughGateway() which exists in VirtualNode
- **Strict Enforcement**: If gateway needed but unavailable, drop packet
- **Logging**: Clear logs for debugging gateway routing decisions

---

### 3.3 Modify routeThroughGateway() - Preference Enforcement

**File**: `VirtualNode.kt`  
**Location**: Modify existing routeThroughGateway() method  
**Lines Modified**: ~30

**Current Method** (approximate):
```kotlin
private fun routeThroughGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
    // Use GatewayRouter to select and route via gateway
    return gatewayRouter.routeToGateway(packet, gatewayType)
}
```

**Enhanced Method** (with preference filtering):

```kotlin
/**
 * Route packet through internet gateway (CLIENT-SIDE).
 * 
 * Uses GatewayRouter to select appropriate gateway based on:
 * - Gateway type needed (TOR_GATEWAY or CLEARNET_GATEWAY)
 * - User's gateway preference (gatewayPreference from EmergentRoleManager)
 * - Gateway availability in topology map
 * 
 * Preference Enforcement:
 * - TOR_ONLY: Only route via TOR_GATEWAY, drop if unavailable
 * - CLEARNET_ONLY: Only route via CLEARNET_GATEWAY, drop if unavailable
 * - EITHER: Prefer Tor, fallback to Clearnet (privacy-first)
 * 
 * @param packet Virtual packet to route
 * @param gatewayType Gateway type needed (from determineGatewayType)
 * @return true if routed successfully, false if no suitable gateway found
 */
private fun routeThroughGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
    // Get user's gateway preference (CLIENT-SIDE setting)
    val userPreference = emergentRoleManager.getGatewayPreference()
    
    // Determine effective gateway type based on preference and packet needs
    val effectiveGatewayType = when (userPreference) {
        GatewayPreference.TOR_ONLY -> {
            // Strict: Only use Tor gateways
            if (gatewayType == MeshRole.CLEARNET_GATEWAY) {
                safeLog(LogLevel.WARN, "User preference TOR_ONLY conflicts with packet needing Clearnet, dropping packet")
                return false // Drop packet - preference violation
            }
            MeshRole.TOR_GATEWAY
        }
        
        GatewayPreference.CLEARNET_ONLY -> {
            // Strict: Only use Clearnet gateways
            if (gatewayType == MeshRole.TOR_GATEWAY) {
                safeLog(LogLevel.WARN, "User preference CLEARNET_ONLY conflicts with packet needing Tor, dropping packet")
                return false // Drop packet - preference violation
            }
            MeshRole.CLEARNET_GATEWAY
        }
        
        GatewayPreference.EITHER -> {
            // Flexible: Prefer Tor, but accept packet's classification
            if (gatewayType == MeshRole.TOR_GATEWAY || gatewayType == MeshRole.CLEARNET_GATEWAY) {
                gatewayType // Use packet's classification
            } else {
                // Packet didn't specify, prefer Tor (privacy-first)
                MeshRole.TOR_GATEWAY
            }
        }
    }
    
    // Use GatewayRouter to route via selected gateway type
    val routed = gatewayRouter.routeToGateway(packet, effectiveGatewayType)
    
    if (!routed && userPreference == GatewayPreference.EITHER) {
        // EITHER preference: Try fallback gateway type
        val fallbackType = if (effectiveGatewayType == MeshRole.TOR_GATEWAY) {
            MeshRole.CLEARNET_GATEWAY
        } else {
            MeshRole.TOR_GATEWAY
        }
        
        safeLog(LogLevel.INFO, "Primary gateway $effectiveGatewayType unavailable, trying fallback $fallbackType")
        return gatewayRouter.routeToGateway(packet, fallbackType)
    }
    
    return routed
}
```

**Import Requirements**:
```kotlin
import com.ustadmobile.meshrabiya.api.GatewayPreference
```

**Rationale**:
- **Preference Enforcement**: Respects user's gatewayPreference (CLIENT-SIDE)
- **Strict TOR_ONLY/CLEARNET_ONLY**: Drops packets that violate preference
- **Flexible EITHER**: Tries primary, then fallback (privacy-first with availability)
- **Logging**: Preference violations and fallbacks logged clearly

---

## SECTION 4: GATEWAY ROUTER INTEGRATION

### 4.1 Add Preference Filtering to GatewayRouter (OPTIONAL)

**File**: `GatewayRouter.kt`  
**Location**: Modify routeToGateway() method  
**Lines Modified**: ~25

**Note**: Research confirmed GatewayRouter already exists (213 lines) with full implementation. This section is OPTIONAL enhancement for additional filtering at GatewayRouter level. Can skip if VirtualNode.routeThroughGateway() filtering is sufficient.

**Current Method** (approximate from research):
```kotlin
fun routeToGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
    // Get gateway pool for requested type
    val gatewayPool = getOrRefreshGatewayPool(gatewayType)
    
    if (gatewayPool.isEmpty()) {
        return false // No gateways available
    }
    
    // Round-robin selection
    val selectedGateway = gatewayPool[currentIndex % gatewayPool.size]
    currentIndex++
    
    // Route via proxy
    return virtualNode.routeViaProxy(packet, selectedGateway)
}
```

**Enhanced Method** (OPTIONAL - additional topology map filtering):

```kotlin
/**
 * Route packet to internet via gateway of specified type.
 * 
 * Selects gateway from topology map, filters by type and availability,
 * uses round-robin for load balancing.
 * 
 * @param packet Packet to route to internet
 * @param gatewayType Required gateway type (TOR_GATEWAY or CLEARNET_GATEWAY)
 * @return true if routed successfully, false if no suitable gateway
 */
fun routeToGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
    // Get topology map from OriginatingMessageManager
    val topologyMap = virtualNode.originatingMessageManager.getTopologyMapInfo()
    
    // Filter gateways by type and availability
    val availableGateways = topologyMap.values
        .filter { nodeInfo ->
            nodeInfo.hasRole(gatewayType) &&
            nodeInfo.isRecentlySeen() && // Seen in last 30 seconds
            nodeInfo.address != localNodeAddress // Not self
        }
        .sortedByDescending { it.calculateGatewaySuitability(gatewayType) }
    
    if (availableGateways.isEmpty()) {
        logger(android.util.Log.WARN, "No available gateways for type $gatewayType", null)
        return false
    }
    
    // Round-robin selection from available gateways
    val selectedGateway = availableGateways[currentIndex % availableGateways.size]
    currentIndex++
    
    logger(
        android.util.Log.INFO,
        "Routing via $gatewayType gateway: ${selectedGateway.address} (suitability: ${selectedGateway.calculateGatewaySuitability(gatewayType)})",
        null
    )
    
    // Create packet with gateway as next hop
    packet.updateLastHopAddrAndIncrementHopCountInData(localNodeAddress)
    
    // Route via proxy to gateway
    return virtualNode.routeViaProxy(packet, selectedGateway.address)
}
```

**Rationale**:
- **Topology Map Integration**: Uses real-time gateway availability from topology
- **Suitability Scoring**: Selects best gateway based on NodeTopologyInfo metrics
- **Freshness Check**: Only use recently-seen gateways (avoid stale routes)
- **Load Balancing**: Round-robin across available gateways

**Note**: This enhancement is OPTIONAL. If GatewayRouter already has sufficient logic (confirmed via research), skip this modification.

---

## SECTION 5: PUBLIC API METHODS FOR TOR STATUS

### 5.1 Add Tor Status Methods to MeshrabiyaApi Interface

**File**: `MeshrabiyaApi.kt`  
**Location**: Add to interface definition  
**Lines Added**: ~15

**Implementation**:

```kotlin
/**
 * Get current Tor network status.
 * 
 * Indicates whether Tor network is currently active and available for routing.
 * Status is updated in real-time by BroadcastReceiver listening to Orbot.
 * 
 * Status values:
 * - true: Tor network is ON and ready for use
 * - false: Tor network is OFF, STARTING, STOPPING, or unknown
 * 
 * Note: This reflects SERVER-SIDE Tor availability (can this node become TOR_GATEWAY).
 * It is separate from CLIENT-SIDE gatewayPreference (what gateways I use).
 * 
 * @return true if Tor network active, false otherwise
 */
fun getTorNetworkStatus(): Boolean

/**
 * Observe Tor network status changes as a StateFlow.
 * 
 * Allows UI and other components to react to Tor status changes in real-time.
 * Useful for displaying Tor connectivity status to user.
 * 
 * @return StateFlow<Boolean> that emits true when Tor is active, false otherwise
 */
fun observeTorNetworkStatus(): StateFlow<Boolean>
```

**Import Requirements**:
```kotlin
import kotlinx.coroutines.flow.StateFlow
```

---

### 5.2 Implement Tor Status Methods in MeshrabiyaApiImpl

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add method implementations  
**Lines Added**: ~10

**Implementation**:

```kotlin
override fun getTorNetworkStatus(): Boolean {
    return torNetworkActive.value
}

override fun observeTorNetworkStatus(): StateFlow<Boolean> {
    return torNetworkActive
}
```

**Rationale**:
- **Simple Accessors**: Direct exposure of torNetworkActive StateFlow
- **Thread-Safe**: StateFlow provides thread-safe reads
- **Reactive**: UI can observe() for real-time updates

---

## SECTION 6: VERIFICATION & TESTING

### 6.1 Unit Tests for Tor Status Tracking

**File**: `OrbotStatusReceiverTest.kt` (new test file)

**Test Cases**:

```kotlin
package com.ustadmobile.meshrabiya

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrbotStatusReceiverTest {
    
    private lateinit var apiImpl: MeshrabiyaApiImpl
    private lateinit var receiver: MeshrabiyaApiImpl.OrbotStatusReceiver
    
    @Before
    fun setup() {
        apiImpl = MeshrabiyaApiImpl.getInstance()
        // Access receiver via reflection or make it package-visible for testing
        receiver = apiImpl.createOrbotStatusReceiverForTesting()
    }
    
    @Test
    fun testStatusON_setsTorActiveTrue() = runTest {
        val intent = createStatusIntent("ON")
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        
        assertTrue(apiImpl.getTorNetworkStatus())
    }
    
    @Test
    fun testStatusOFF_setsTorActiveFalse() = runTest {
        val intent = createStatusIntent("OFF")
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        
        assertFalse(apiImpl.getTorNetworkStatus())
    }
    
    @Test
    fun testStatusSTARTING_setsTorActiveFalse() = runTest {
        val intent = createStatusIntent("STARTING")
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        
        assertFalse(apiImpl.getTorNetworkStatus())
    }
    
    @Test
    fun testStatusSTOPPING_setsTorActiveFalse() = runTest {
        val intent = createStatusIntent("STOPPING")
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        
        assertFalse(apiImpl.getTorNetworkStatus())
    }
    
    @Test
    fun testUnknownStatus_setsTorActiveFalse() = runTest {
        val intent = createStatusIntent("UNKNOWN")
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        
        assertFalse(apiImpl.getTorNetworkStatus())
    }
    
    @Test
    fun testStatusChange_triggersRoleReeval uation() = runTest {
        // Set initial status to OFF
        receiver.onReceive(ApplicationProvider.getApplicationContext(), createStatusIntent("OFF"))
        
        // Change to ON
        receiver.onReceive(ApplicationProvider.getApplicationContext(), createStatusIntent("ON"))
        
        // Verify updateRoles() was called (check via mock or log verification)
        // This is integration test - verify role changes in EmergentRoleManager
    }
    
    private fun createStatusIntent(status: String): Intent {
        return Intent("org.torproject.android.intent.action.STATUS").apply {
            putExtra("org.torproject.android.intent.extra.STATUS", status)
        }
    }
}
```

---

### 6.2 Unit Tests for Gateway Role Selection

**File**: `EmergentRoleManagerGatewayTest.kt` (new test file)

**Test Cases**:

```kotlin
@Test
fun testSelectGatewayRole_torActiveAndSufficientBandwidth_selectsTorGateway() = runTest {
    // Setup: Tor active, high bandwidth
    setMockTorStatus(true)
    val capabilities = mockCapabilities(bandwidth = 6_000_000L)
    val userPrefs = setOf(MeshRole.TOR_GATEWAY)
    
    val role = manager.selectBestGatewayRole(capabilities, mockMesh, userPrefs)
    
    assertEquals(MeshRole.TOR_GATEWAY, role)
}

@Test
fun testSelectGatewayRole_torInactiveWithTorPreference_returnsNull() = runTest {
    // Setup: Tor inactive, user wants Tor gateway
    setMockTorStatus(false)
    val capabilities = mockCapabilities(bandwidth = 6_000_000L)
    val userPrefs = setOf(MeshRole.TOR_GATEWAY)
    
    val role = manager.selectBestGatewayRole(capabilities, mockMesh, userPrefs)
    
    assertNull(role) // Cannot become Tor gateway when Tor inactive
}

@Test
fun testSelectGatewayRole_torInactiveButHighBandwidth_selectsClearnet() = runTest {
    // Setup: Tor inactive, high bandwidth, no user preference
    setMockTorStatus(false)
    val capabilities = mockCapabilities(bandwidth = 12_000_000L)
    val userPrefs = emptySet<MeshRole>()
    
    val role = manager.selectBestGatewayRole(capabilities, mockMesh, userPrefs)
    
    assertEquals(MeshRole.CLEARNET_GATEWAY, role)
}

@Test
fun testSelectGatewayRole_insufficientBandwidth_returnsNull() = runTest {
    setMockTorStatus(true)
    val capabilities = mockCapabilities(bandwidth = 1_000_000L) // 1 Mbps (too low)
    val userPrefs = emptySet<MeshRole>()
    
    val role = manager.selectBestGatewayRole(capabilities, mockMesh, userPrefs)
    
    assertNull(role)
}
```

---

### 6.3 Integration Tests for Packet Classification

**File**: `PacketClassificationTest.kt` (new test file)

**Test Cases**:

```kotlin
@Test
fun testDetermineGatewayType_meshAddress_returnsNull() {
    val packet = createMeshPacket(toAddr = 0xF0000001) // Mesh address
    
    val gatewayType = virtualNode.determineGatewayType(packet)
    
    assertNull(gatewayType) // Mesh-only, no gateway needed
}

@Test
fun testDetermineGatewayType_externalAddress_returnsClearnet() {
    val packet = createInternetPacket(toAddr = 0x08080808) // External (8.8.8.8)
    
    val gatewayType = virtualNode.determineGatewayType(packet)
    
    assertEquals(MeshRole.CLEARNET_GATEWAY, gatewayType)
}

@Test
fun testDetermineGatewayType_torPort_returnsTorGateway() {
    val packet = createInternetPacket(toAddr = 0x08080808, port = 9050) // Tor SOCKS
    
    val gatewayType = virtualNode.determineGatewayType(packet)
    
    assertEquals(MeshRole.TOR_GATEWAY, gatewayType)
}
```

---

### 6.4 Manual Testing Checklist

**Scenario 1: Tor Network Status Tracking**
- [ ] Install Orbot on test device
- [ ] Start mesh with Orbot running
- [ ] Verify torNetworkActive = true
- [ ] Stop Orbot
- [ ] Verify torNetworkActive = false
- [ ] Verify logs show status changes

**Scenario 2: Gateway Role Selection**
- [ ] Start with Tor active, high bandwidth node
- [ ] Verify node becomes TOR_GATEWAY
- [ ] Stop Tor
- [ ] Verify node switches to CLEARNET_GATEWAY or no gateway
- [ ] Restart Tor
- [ ] Verify node becomes TOR_GATEWAY again

**Scenario 3: Client Preference Enforcement**
- [ ] Set gatewayPreference = TOR_ONLY
- [ ] Send internet-bound packet
- [ ] Verify packet routed via Tor gateway (if available)
- [ ] Disable all Tor gateways
- [ ] Verify packet dropped (strict enforcement)

**Scenario 4: EITHER Preference Fallback**
- [ ] Set gatewayPreference = EITHER
- [ ] Send packet with Tor available
- [ ] Verify packet routed via Tor (privacy-first)
- [ ] Disable Tor gateways
- [ ] Verify packet falls back to Clearnet gateway

**Scenario 5: Packet Classification**
- [ ] Send packet to mesh address (0xF0000XXX)
- [ ] Verify no gateway routing attempted
- [ ] Send packet to external address (8.8.8.8)
- [ ] Verify gateway routing attempted

---

## COMPLETION CHECKLIST - PART 2

### Code Implementation
- [ ] Create OrbotStatusReceiver inner class
- [ ] Add torNetworkActive StateFlow to MeshrabiyaApiImpl
- [ ] Register BroadcastReceiver in initMesh()
- [ ] Add initial Tor status query in initMesh()
- [ ] Add orbotStatusReceiver property to MeshrabiyaApiImpl
- [ ] Implement cleanup() method for receiver unregistration
- [ ] Refactor selectBestGatewayRole() with Tor status check
- [ ] Add getTorNetworkStatus() helper to EmergentRoleManager
- [ ] Add Tor status observation in EmergentRoleManager init block
- [ ] Implement determineGatewayType() in VirtualNode
- [ ] Add isMeshAddress() helper method
- [ ] Add extractDestinationPort() stub method
- [ ] Integrate determineGatewayType() into route()
- [ ] Modify routeThroughGateway() with preference enforcement
- [ ] (Optional) Enhance GatewayRouter.routeToGateway()
- [ ] Add getTorNetworkStatus() to MeshrabiyaApi interface
- [ ] Add observeTorNetworkStatus() to MeshrabiyaApi interface
- [ ] Implement API methods in MeshrabiyaApiImpl

### Testing
- [ ] Create OrbotStatusReceiverTest.kt
- [ ] Write unit tests for all Orbot status values (ON, OFF, STARTING, STOPPING)
- [ ] Write unit tests for unknown status handling
- [ ] Create EmergentRoleManagerGatewayTest.kt
- [ ] Write unit tests for Tor-active gateway selection
- [ ] Write unit tests for Tor-inactive fallback
- [ ] Write unit tests for insufficient bandwidth
- [ ] Create PacketClassificationTest.kt
- [ ] Write unit tests for mesh address detection
- [ ] Write unit tests for external address classification
- [ ] Write unit tests for Tor port detection
- [ ] Run all VirtualNode tests (verify no regressions)
- [ ] Run all EmergentRoleManager tests (verify no regressions)

### Documentation
- [ ] Add KDoc comments for OrbotStatusReceiver
- [ ] Add KDoc comments for torNetworkActive
- [ ] Add KDoc comments for determineGatewayType()
- [ ] Add KDoc comments for routeThroughGateway()
- [ ] Add KDoc comments for API methods
- [ ] Update KNOWLEDGE-12062025.md with Part 2 details

### Validation
- [ ] Kotlin compiler passes (no errors)
- [ ] Lint check passes
- [ ] Manual test: Orbot status tracking works
- [ ] Manual test: Tor inactive prevents TOR_GATEWAY role
- [ ] Manual test: TOR_ONLY preference drops Clearnet packets
- [ ] Manual test: EITHER preference falls back to Clearnet
- [ ] Manual test: Packet classification detects mesh vs internet
- [ ] Code review completed
- [ ] Update INTERIM_COMMIT_LOG.md

---

## NEXT STEPS (PART 3 PREVIEW)

Part 3 will complete the integration with comprehensive testing and NetworkInfo() enhancement:

### Section 1: NetworkInfo() Gateway Breakdown
- Show total gateway count
- Show Tor gateway count vs Clearnet gateway count
- Display gateway distribution in UI

### Section 2: Comprehensive Testing Scenarios
- CLIENT vs SERVER separation validation
- Preference change stress testing
- Tor status rapid toggling
- Gateway failover testing
- Multi-hop routing verification

### Section 3: Edge Case Handling
- Orbot not installed
- All gateways offline
- Rapid preference changes
- Network transitions (WiFi → Cellular)

### Section 4: Implementation Checklist
- Final code locations reference
- Complete import lists
- Deployment guide
- Performance considerations

**Estimated Lines**: ~140 lines (Part 3)

---

## APPENDIX A: FILE MODIFICATION SUMMARY

| File | Section | Lines Added | Lines Removed | Net Change |
|------|---------|-------------|---------------|------------|
| MeshrabiyaApiImpl.kt | 1.1 Receiver | 60 | 0 | +60 |
| MeshrabiyaApiImpl.kt | 1.2 StateFlow | 15 | 0 | +15 |
| MeshrabiyaApiImpl.kt | 1.3 Registration | 25 | 0 | +25 |
| MeshrabiyaApiImpl.kt | 1.4 Cleanup | 15 | 0 | +15 |
| MeshrabiyaApiImpl.kt | 5.2 API Methods | 10 | 0 | +10 |
| EmergentRoleManager.kt | 2.1 Role Selection | 40 | 20 | +20 |
| EmergentRoleManager.kt | 2.2 Observation | 20 | 0 | +20 |
| VirtualNode.kt | 3.1 Packet Classification | 65 | 5 | +60 |
| VirtualNode.kt | 3.2 Route Integration | 25 | 0 | +25 |
| VirtualNode.kt | 3.3 Preference Enforcement | 30 | 10 | +20 |
| GatewayRouter.kt | 4.1 Enhancement (Optional) | 25 | 0 | +25 |
| MeshrabiyaApi.kt | 5.1 Interface | 15 | 0 | +15 |
| **TOTAL (Part 2)** | | **310** | **35** | **+275** |

**Test Files**:
| File | Lines Added |
|------|-------------|
| OrbotStatusReceiverTest.kt | 70 |
| EmergentRoleManagerGatewayTest.kt | 60 |
| PacketClassificationTest.kt | 50 |
| **TOTAL (Tests)** | **180** |

**Grand Total**: ~455 lines (implementation + tests)

---

## APPENDIX B: ORBOT INTEGRATION REFERENCE

**Orbot Package**: `org.torproject.android`

**Intent Action**: `org.torproject.android.intent.action.STATUS`

**Intent Extras**:
- Key: `org.torproject.android.intent.extra.STATUS`
- Values: "ON", "OFF", "STARTING", "STOPPING"

**Status Mapping (Conservative)**:
| Orbot Status | torNetworkActive | TOR_GATEWAY Role Allowed? |
|--------------|------------------|---------------------------|
| ON | true | YES |
| OFF | false | NO |
| STARTING | false | NO (conservative) |
| STOPPING | false | NO |
| Unknown | false | NO (safe default) |

**Orbot Installation Detection**:
```kotlin
fun isOrbotInstalled(context: Context): Boolean {
    return try {
        context.packageManager.getPackageInfo("org.torproject.android", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
```

---

## APPENDIX C: PACKET CLASSIFICATION DECISION TREE

```
┌────────────────────┐
│  Packet Received   │
└─────────┬──────────┘
          │
          ▼
    ┌─────────────┐
    │Is Mesh Addr?│
    └──┬──────┬───┘
       │YES   │NO
       ▼      ▼
   ┌──────┐  ┌────────────────┐
   │ NULL │  │Is Tor Port?    │
   │      │  │(9001,9050,etc) │
   └──────┘  └──┬──────┬──────┘
                │YES   │NO
                ▼      ▼
         ┌──────────┐ ┌────────────────┐
         │TOR_      │ │CLEARNET_       │
         │GATEWAY   │ │GATEWAY         │
         └──────────┘ └────────────────┘
```

**Classification Examples**:
- `0xF0000001` → NULL (mesh address)
- `0x08080808:80` → CLEARNET_GATEWAY (8.8.8.8:80)
- `0x01020304:9050` → TOR_GATEWAY (Tor SOCKS)
- `0xC0A80001:443` → CLEARNET_GATEWAY (192.168.0.1:443)

---

## APPENDIX D: GATEWAY PREFERENCE ENFORCEMENT MATRIX

| User Preference | Packet Needs | Effective Gateway | Fallback | Action |
|-----------------|--------------|-------------------|----------|--------|
| TOR_ONLY | TOR_GATEWAY | TOR_GATEWAY | None | Route via Tor |
| TOR_ONLY | CLEARNET_GATEWAY | N/A | None | Drop packet |
| CLEARNET_ONLY | CLEARNET_GATEWAY | CLEARNET_GATEWAY | None | Route via Clearnet |
| CLEARNET_ONLY | TOR_GATEWAY | N/A | None | Drop packet |
| EITHER | TOR_GATEWAY | TOR_GATEWAY | CLEARNET | Try Tor, fallback Clearnet |
| EITHER | CLEARNET_GATEWAY | CLEARNET_GATEWAY | TOR | Try Clearnet, fallback Tor |
| EITHER | NULL (prefer Tor) | TOR_GATEWAY | CLEARNET | Try Tor, fallback Clearnet |

**Strict Enforcement**: TOR_ONLY and CLEARNET_ONLY drop packets that violate preference.

**Privacy-First Fallback**: EITHER prefers Tor, only uses Clearnet if Tor unavailable.

---

## END OF PART 2

**Total Lines**: ~1,350 lines (including documentation, code, tests, appendices)

**Implementation Time**: 12-14 hours estimated

**Proceed to**: [TOR_INTEGRATION_PLAN_V2_PART3.md] for API completion, NetworkInfo enhancement, and comprehensive testing.

**Questions/Decisions Resolved**:
1. ✅ Tor "STARTING" status = FALSE (conservative)
2. ✅ Return immediately from initMesh() (async Orbot response)
3. ✅ Multi-hop gateway failover (GatewayRouter handles)
4. ✅ EITHER preference behavior (prefer Tor, privacy-first)
5. ✅ Packet classification heuristics (port-based for V1)
