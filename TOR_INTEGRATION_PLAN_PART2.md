# Meshrabiya Tor Integration Plan - PART 2 of 4
## Tor Status Query & Gateway Failover (First Half)

**Document Version**: 1.0  
**Created**: 2025-12-05  
**Status**: Implementation Ready  
**Estimated Implementation Time**: 6-8 hours (Part 2 only)

---

## PART 2 OVERVIEW

### Purpose
Part 2 implements real-time Tor network status tracking and begins gateway failover logic by:
1. Registering a BroadcastReceiver for Orbot ACTION_STATUS broadcasts
2. Creating torNetworkActive StateFlow to track Tor availability
3. Querying initial Tor status on initMesh() startup
4. Integrating Tor status into role selection logic
5. Beginning gateway failover algorithm (suitability scoring)

### Scope
- **Section 2**: Tor Status Query on initMesh (BroadcastReceiver, StateFlow, initial query)
- **Section 3 (First Half)**: Gateway Failover Logic - Suitability Scoring Algorithm

### Dependencies on Part 1
- ✅ `GatewayPreference` enum exists (Part 1, Section 1.1)
- ✅ `gatewayPreference` StateFlow available (Part 1, Section 1.2)
- ✅ DataStore persistence ready (Part 1, Section 1.4)

### Key Changes (Part 2)
| Component | Change Type | Lines Added | Risk Level |
|-----------|-------------|-------------|------------|
| MeshrabiyaApiImpl.kt | BroadcastReceiver | ~50 lines | MEDIUM |
| MeshrabiyaApiImpl.kt | StateFlow addition | ~8 lines | LOW |
| MeshrabiyaApiImpl.kt | initMesh() modification | ~12 lines | LOW |
| EmergentRoleManager.kt | Role selection update | ~15 lines | LOW |
| GatewayRouter.kt | Suitability algorithm | ~60 lines | MEDIUM |

**Total**: ~145 lines added

---

## SECTION 2: TOR STATUS QUERY ON INITMESH

### 2.1 BroadcastReceiver Definition & Registration

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add after line 48 (after getInstance method), register in initMesh() after line 91  
**Lines Added**: ~50

**Context**: Current initMesh() method (lines 75-91):
```kotlin
override fun initMesh(context: Context) {
    val dataStore = context.dataStore
    
    myNode = AndroidVirtualNode(
        appContext = context.applicationContext,
        dataStore = dataStore
    )
    
    emergentRoleManager = myNode?.emergentRoleManager
    distributedStorageManager = myNode?.distributedStorageManager
}
```

**BroadcastReceiver Implementation**:

Add after line 48 (after getInstance):

```kotlin
/**
 * BroadcastReceiver for Orbot status broadcasts.
 * Listens for ACTION_STATUS from Orbot to track Tor network availability.
 * 
 * Orbot broadcasts status changes with:
 * - ACTION: org.torproject.android.intent.action.STATUS
 * - EXTRA: EXTRA_STATUS with values "ON", "OFF", "STARTING", "STOPPING"
 * 
 * This receiver updates torNetworkActive StateFlow for mesh-wide Tor status.
 */
private var orbotStatusReceiver: BroadcastReceiver? = null

/**
 * StateFlow tracking Tor network availability.
 * Updated by Orbot STATUS broadcasts and REQUEST_STATUS queries.
 * 
 * true = Tor network is active and usable
 * false = Tor network is inactive or starting/stopping
 */
private val _torNetworkActive = MutableStateFlow(false)
val torNetworkActive: StateFlow<Boolean> = _torNetworkActive.asStateFlow()

/**
 * Create BroadcastReceiver for Orbot status tracking.
 * Called during initMesh() to set up Tor status monitoring.
 * 
 * @param context Application context for receiver registration
 * @return Configured BroadcastReceiver ready for registration
 */
private fun createOrbotStatusReceiver(context: Context): BroadcastReceiver {
    return object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == ORBOT_ACTION_STATUS) {
                val status = intent.getStringExtra(ORBOT_EXTRA_STATUS)
                val isActive = status == "ON"
                
                val previousStatus = _torNetworkActive.value
                _torNetworkActive.value = isActive
                
                // Log status changes for debugging
                if (previousStatus != isActive) {
                    logger?.log(
                        LogLevel.INFO,
                        "MeshrabiyaApiImpl",
                        "Tor network status changed: $previousStatus → $isActive (Orbot status: $status)"
                    )
                } else {
                    logger?.log(
                        LogLevel.DEBUG,
                        "MeshrabiyaApiImpl",
                        "Tor network status update: $isActive (Orbot status: $status)"
                    )
                }
                
                // Trigger role re-evaluation if status changed
                if (previousStatus != isActive) {
                    emergentRoleManager?.updateRoles()
                }
            }
        }
    }
}

companion object {
    // Orbot intent action constants
    private const val ORBOT_ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
    private const val ORBOT_ACTION_REQUEST_STATUS = "org.torproject.android.intent.action.REQUEST_STATUS"
    private const val ORBOT_EXTRA_STATUS = "EXTRA_STATUS"
    
    // ... existing companion object code ...
}
```

**Rationale**:
- **Lazy Initialization**: Receiver created only when initMesh() called
- **Status Change Detection**: Only triggers role re-evaluation on actual changes
- **Logging Levels**: INFO for changes, DEBUG for updates
- **Thread Safety**: StateFlow updates are thread-safe

**Orbot Status Values**:
- **"ON"**: Tor network is active → `torNetworkActive = true`
- **"OFF"**: Tor network is inactive → `torNetworkActive = false`
- **"STARTING"**: Tor is starting up → `torNetworkActive = false` (not ready yet)
- **"STOPPING"**: Tor is shutting down → `torNetworkActive = false` (no longer usable)

**DECISION POINT**: Should "STARTING" status be treated as true (optimistic) or false (conservative)?

**Recommendation**: FALSE (conservative) - Only consider Tor active when Orbot reports "ON". This prevents routing failures during startup.

**Answer: set to FALSE

---

### 2.2 Receiver Registration in initMesh()

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Modify initMesh() method (lines 75-91)  
**Lines Modified**: ~12

**Updated initMesh() Method**:

```kotlin
override fun initMesh(context: Context) {
    val dataStore = context.dataStore
    
    myNode = AndroidVirtualNode(
        appContext = context.applicationContext,
        dataStore = dataStore
    )
    
    emergentRoleManager = myNode?.emergentRoleManager
    distributedStorageManager = myNode?.distributedStorageManager
    
    // Register Orbot status receiver for Tor network tracking
    try {
        orbotStatusReceiver = createOrbotStatusReceiver(context.applicationContext)
        val intentFilter = IntentFilter(ORBOT_ACTION_STATUS)
        context.applicationContext.registerReceiver(orbotStatusReceiver, intentFilter)
        
        logger?.log(
            LogLevel.INFO,
            "MeshrabiyaApiImpl",
            "Orbot status receiver registered"
        )
        
        // Query initial Tor status immediately
        queryInitialTorStatus(context.applicationContext)
        
    } catch (e: Exception) {
        logger?.log(
            LogLevel.ERROR,
            "MeshrabiyaApiImpl",
            "Failed to register Orbot status receiver: ${e.message}",
            e
        )
        // Non-fatal: mesh can operate without Tor status (assumes Tor unavailable)
        _torNetworkActive.value = false
    }
}
```

**Rationale**:
- **Application Context**: Prevents memory leaks (receiver tied to app, not activity)
- **Try-Catch**: Graceful degradation if Orbot not installed or receiver registration fails
- **Immediate Query**: Ensures torNetworkActive has correct value before mesh starts
- **Non-Fatal Failure**: Mesh operates with Tor assumed unavailable if registration fails

**Error Scenarios**:
1. **Orbot Not Installed**: Registration succeeds, but no broadcasts received → torNetworkActive stays false
2. **Permission Denied**: Registration throws SecurityException → caught, logged, torNetworkActive = false
3. **Receiver Already Registered**: Registration throws IllegalArgumentException → caught, logged, use existing receiver

---

### 2.3 Initial Tor Status Query

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add after createOrbotStatusReceiver() method  
**Lines Added**: ~18

**Implementation**:

```kotlin
/**
 * Query Orbot for current Tor network status.
 * Sends REQUEST_STATUS broadcast to Orbot, which responds with STATUS broadcast.
 * 
 * Called during initMesh() to get initial Tor status before mesh operations begin.
 * Response received via orbotStatusReceiver's onReceive() method.
 * 
 * @param context Application context for sending broadcast
 */
private fun queryInitialTorStatus(context: Context) {
    try {
        val requestIntent = Intent(ORBOT_ACTION_REQUEST_STATUS)
        context.sendBroadcast(requestIntent)
        
        logger?.log(
            LogLevel.DEBUG,
            "MeshrabiyaApiImpl",
            "Sent initial Tor status query to Orbot"
        )
        
    } catch (e: Exception) {
        logger?.log(
            LogLevel.WARN,
            "MeshrabiyaApiImpl",
            "Failed to query initial Tor status: ${e.message}",
            e
        )
        // Non-fatal: torNetworkActive remains false until first broadcast received
    }
}
```

**Timing**:
1. `initMesh()` called by application
2. Receiver registered (line ~103)
3. Initial query sent (line ~106)
4. Orbot receives query (if installed)
5. Orbot responds with STATUS broadcast (~100-500ms delay)
6. Receiver's onReceive() updates torNetworkActive
7. Role evaluation triggered if status changed

**DECISION POINT**: Should we wait for initial query response before returning from initMesh()?

**Recommendation**: NO - Return immediately, allow async response. Reasons:
- initMesh() should be fast (UI thread may call it)
- 500ms delay unacceptable for startup
- Mesh can operate with torNetworkActive=false initially
- First role evaluation will use correct status once received

**Answer: NO,Return immediately, allow async response. 

**Fallback Behavior**: If no response within 5 seconds, torNetworkActive remains false (conservative default).

---

### 2.4 Receiver Lifecycle Management

**File**: `MeshrabiyaApiImpl.kt`  
**Location**: Add cleanup method after initMesh()  
**Lines Added**: ~20

**Cleanup Method**:

```kotlin
/**
 * Unregister Orbot status receiver.
 * Should be called when MeshrabiyaApi is no longer needed.
 * 
 * NOTE: Android automatically unregisters receivers when the application
 * process is killed, so this is primarily for graceful shutdown scenarios.
 * 
 * @param context Application context used during registration
 */
fun cleanup(context: Context) {
    try {
        orbotStatusReceiver?.let { receiver ->
            context.applicationContext.unregisterReceiver(receiver)
            orbotStatusReceiver = null
            
            logger?.log(
                LogLevel.INFO,
                "MeshrabiyaApiImpl",
                "Orbot status receiver unregistered"
            )
        }
    } catch (e: Exception) {
        logger?.log(
            LogLevel.WARN,
            "MeshrabiyaApiImpl",
            "Failed to unregister Orbot status receiver: ${e.message}",
            e
        )
        // Non-fatal: Android will clean up on process death anyway
    }
}
```

**When to Call**:
- Application's `onDestroy()` (graceful shutdown)
- Unit tests (cleanup between test runs)
- **NOT** in `Application.onTerminate()` (never called in production)

**Rationale**:
- **Optional Cleanup**: Android auto-cleanup makes this non-critical
- **Null Check**: Safe to call multiple times
- **Error Tolerance**: Unregistration failures are non-fatal

**Per User Clarification 1**: No `Application.onTerminate()` cleanup needed. Android handles receiver cleanup automatically on process death.

---

### 2.5 EmergentRoleManager Integration

**File**: `EmergentRoleManager.kt`  
**Location**: Modify selectBestGatewayRole() method (lines 278-299)  
**Lines Modified**: ~15

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

**Updated Code with Tor Status Integration**:

```kotlin
private fun selectBestGatewayRole(
    node: NodeCapabilitySnapshot, 
    mesh: MeshIntelligence,
    userPreferences: Set<MeshRole>
): MeshRole {
    val gatewayRoles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
    val preferredGateways = userPreferences.intersect(gatewayRoles)
    
    // Get current Tor network status from MeshrabiyaApi
    val torAvailable = try {
        // Access torNetworkActive from parent API instance
        (virtualNode as? AndroidVirtualNode)?.let { androidNode ->
            // Reflection to access MeshrabiyaApiImpl.torNetworkActive
            val apiImplClass = Class.forName("com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl")
            val instanceMethod = apiImplClass.getMethod("getInstance", Context::class.java)
            val apiInstance = instanceMethod.invoke(null, context)
            val torActiveField = apiImplClass.getDeclaredField("torNetworkActive")
            torActiveField.isAccessible = true
            val stateFlow = torActiveField.get(apiInstance) as StateFlow<Boolean>
            stateFlow.value
        } ?: false
    } catch (e: Exception) {
        safeLog(LogLevel.WARN, "Failed to get Tor network status, assuming unavailable: ${e.message}")
        false // Conservative default
    }
    
    // If user has gateway preferences, honor them first
    if (preferredGateways.isNotEmpty()) {
        return preferredGateways.first()
    }
    
    // Use gatewayPreference enum (from Part 1) with Tor availability check
    return when (gatewayPreference.value) {
        GatewayPreference.TOR_ONLY -> {
            // Only select Tor gateway if Tor network is actually available
            if (torAvailable) {
                MeshRole.TOR_GATEWAY
            } else {
                safeLog(LogLevel.WARN, "TOR_ONLY preference but Tor unavailable, declining gateway role")
                // Return CLEARNET_GATEWAY with warning, or throw exception to decline role
                // DECISION POINT: Decline role entirely or fallback to Clearnet?
                MeshRole.CLEARNET_GATEWAY // Temporary fallback (see decision below)
            }
        }
        GatewayPreference.CLEARNET_ONLY -> MeshRole.CLEARNET_GATEWAY
        GatewayPreference.EITHER -> {
            // Capability-based selection, prefer Tor if available
            when {
                torAvailable && node.resources.availableBandwidth > 5_000_000L -> MeshRole.TOR_GATEWAY
                node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
                torAvailable -> MeshRole.TOR_GATEWAY // Lower bandwidth, but Tor available
                else -> MeshRole.CLEARNET_GATEWAY
            }
        }
    }
}
```

**DECISION POINT**: When user preference is TOR_ONLY but Tor is unavailable, should we:
1. **Return CLEARNET_GATEWAY** (current code) - Violates user preference
2. **Throw exception** - Prevents node from becoming any gateway
3. **Return null/special value** - Requires changing return type

**Recommendation**: Option 2 (Throw exception) - Change return type to `MeshRole?` and return null when cannot honor preference. This prevents violating user's privacy/security preference.

**Alternative (Simpler)**: Keep current return type, but don't add TOR_GATEWAY to roles in calculateTargetRoles() if Tor unavailable. Node simply doesn't become a gateway.

**Chosen Approach**: Alternative (simpler) - Modify calculateTargetRoles() to check Tor availability before adding gateway role. No signature change needed.

**Asnswer:  TOR_ONLY is a MESH CLIENT setting regarding routing out of Tor across the Mesh. Presumption is client node does not have Tor Running locally. SO that setting has NOTHING TO DO WITH the selection to enable Tor or Clearnet GATEWAY which are SERVER settings.  DO not confuse the logic for the client with the logic for the server.  so to anser the question directly: if a user has selected TOR_ONLY (on the client) and sends data on the mesh to be routed to the TOR network via a particular Gateway Server node  and node is no longer able to route TOR, first the gateway node would try to forward the packet to another TOR Gateway server node. IF there is not another TOR Gateway, then the packet send fails.  I dont know if there is a failure or acknowldement sent back to the client node.. follow networking standards on a packet delivery failure 


---

### 2.6 calculateTargetRoles() Tor Availability Check

**File**: `EmergentRoleManager.kt`  
**Location**: Modify calculateTargetRoles() method (around line 218)  
**Lines Modified**: ~8

**Current Code** (partial):
```kotlin
// Gateway roles (exclusive - pick one based on capabilities and preferences)
if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
    val gatewayRole = selectBestGatewayRole(node, mesh, userPreferences)
    roles.add(gatewayRole)
    safeLog(LogLevel.INFO, "Assigned gateway role: $gatewayRole")
}
```

**Updated Code with Tor Availability Guard**:

```kotlin
// Gateway roles (exclusive - pick one based on capabilities and preferences)
if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
    val gatewayRole = selectBestGatewayRole(node, mesh, userPreferences)
    
    // Only add gateway role if we can honor user's preference
    val shouldAddGatewayRole = when (gatewayPreference.value) {
        GatewayPreference.TOR_ONLY -> {
            // Only become gateway if Tor is available
            val torAvailable = getTorNetworkStatus() // Helper method, see below
            if (!torAvailable) {
                safeLog(LogLevel.WARN, "TOR_ONLY preference but Tor unavailable, declining gateway role")
            }
            torAvailable
        }
        GatewayPreference.CLEARNET_ONLY -> true // Clearnet always available (no external dependency)
        GatewayPreference.EITHER -> true // Can fall back to available option
    }
    
    if (shouldAddGatewayRole) {
        roles.add(gatewayRole)
        safeLog(LogLevel.INFO, "Assigned gateway role: $gatewayRole")
    } else {
        safeLog(LogLevel.INFO, "Declined gateway role due to preference constraints")
    }
}
```

**Helper Method** (add to EmergentRoleManager):

```kotlin
/**
 * Get current Tor network status from MeshrabiyaApi.
 * Used to determine if TOR_GATEWAY role can be assigned.
 * 
 * @return true if Tor network is active, false otherwise
 */
private fun getTorNetworkStatus(): Boolean {
    return try {
        // Access torNetworkActive from MeshrabiyaApiImpl singleton
        val apiImplClass = Class.forName("com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl")
        val instanceMethod = apiImplClass.getMethod("getInstance", Context::class.java)
        val apiInstance = instanceMethod.invoke(null, context)
        val torActiveField = apiImplClass.getDeclaredField("_torNetworkActive")
        torActiveField.isAccessible = true
        val stateFlow = torActiveField.get(apiInstance) as StateFlow<Boolean>
        stateFlow.value
    } catch (e: Exception) {
        safeLog(LogLevel.WARN, "Failed to get Tor network status: ${e.message}")
        false // Conservative default: assume Tor unavailable
    }
}
```

**Rationale**:
- **Preference Enforcement**: TOR_ONLY nodes refuse gateway role if Tor down
- **Graceful Degradation**: EITHER preference allows fallback to Clearnet
- **No Signature Change**: selectBestGatewayRole() keeps same return type
- **Clear Logging**: User can see why gateway role declined

**CLEANER APPROACH** (for Part 4): Pass torNetworkActive as parameter to EmergentRoleManager constructor, avoiding reflection.

**Answer: this all needs to be redone with the clarification from above regarding TOR_ONLY (User Gatewy route preference) vs allowing a node to ACT as a Gateway of TOr or clearnet.

---

### 2.7 Import Requirements for Section 2

**File**: `MeshrabiyaApiImpl.kt`  
**Required Imports**:
```kotlin
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

**File**: `EmergentRoleManager.kt`  
**Additional Imports**:
```kotlin
import kotlinx.coroutines.flow.StateFlow
```

---

## SECTION 3 (FIRST HALF): GATEWAY FAILOVER LOGIC - SUITABILITY SCORING

### 3.1 Gateway Failover Architecture Overview

**Problem**: User prefers TOR_ONLY, but no local Tor gateways available. Should packet be:
1. Dropped (strict preference enforcement)
2. Routed via multi-hop to distant Tor gateway
3. Fallback to Clearnet (violates preference)

**Solution**: Multi-hop gateway routing with suitability scoring.

**Components**:
1. **Suitability Scorer** (this section): Rank available gateways by fitness
2. **Mesh-Wide Discovery** (Part 3): Query topology map for all gateways
3. **Multi-Hop Router** (Part 3): Calculate routes to distant gateways
4. **Preference Filter** (Part 3): Only consider gateways matching user preference

**This Section**: Implement suitability scoring algorithm in GatewayRouter.

---

### 3.2 GatewaySuitability Data Class

**File**: `GatewayRouter.kt` (NEW FILE if doesn't exist, or add to existing)  
**Location**: Top of file, before class definition  
**Lines Added**: ~25

**Implementation**:

```kotlin
/**
 * Suitability score for a gateway candidate.
 * Used to rank available gateways for packet routing.
 * 
 * Higher totalScore = better gateway for this packet/preference.
 * 
 * @property gatewayAddress Virtual address of the gateway node
 * @property gatewayType Type of gateway (TOR, CLEARNET, I2P)
 * @property hopCount Number of hops to reach this gateway (0 = direct neighbor)
 * @property bandwidthScore Score 0.0-1.0 based on available bandwidth
 * @property latencyScore Score 0.0-1.0 based on network latency (lower latency = higher score)
 * @property stabilityScore Score 0.0-1.0 based on gateway uptime/reliability
 * @property preferenceScore Score 0.0-1.0 based on user preference match (1.0 = exact match)
 * @property totalScore Weighted sum of all scores (0.0-1.0)
 */
data class GatewaySuitability(
    val gatewayAddress: Int,
    val gatewayType: GatewayType,
    val hopCount: Int,
    val bandwidthScore: Float,
    val latencyScore: Float,
    val stabilityScore: Float,
    val preferenceScore: Float,
    val totalScore: Float
) {
    /**
     * Gateway type enum for filtering and preference matching.
     */
    enum class GatewayType {
        TOR,
        CLEARNET,
        I2P,
        UNKNOWN
    }
}
```

**Rationale**:
- **Hop Count**: Prefer closer gateways (lower latency, fewer failure points)
- **Bandwidth Score**: Prefer high-bandwidth gateways for large transfers
- **Latency Score**: Prefer low-latency gateways for real-time traffic
- **Stability Score**: Prefer gateways with high uptime (avoid flaky nodes)
- **Preference Score**: Heavily weight user's preference (1.0 for exact match)

---

### 3.3 Suitability Scoring Algorithm

**File**: `GatewayRouter.kt`  
**Location**: Add to GatewayRouter class  
**Lines Added**: ~60

**Implementation**:

```kotlin
/**
 * Calculate suitability score for a gateway candidate.
 * Combines multiple metrics into single score for ranking.
 * 
 * Weights (tunable):
 * - Preference: 40% (most important - honor user choice)
 * - Hop Count: 20% (minimize latency and failure points)
 * - Bandwidth: 15% (important for throughput)
 * - Latency: 15% (important for responsiveness)
 * - Stability: 10% (important for long-lived connections)
 * 
 * @param gatewayInfo Gateway information from topology map
 * @param userPreference User's gateway routing preference
 * @param hopCount Number of hops to reach this gateway
 * @return GatewaySuitability with calculated scores
 */
fun calculateGatewaySuitability(
    gatewayInfo: GatewayInfo, // Data from topology map
    userPreference: GatewayPreference,
    hopCount: Int
): GatewaySuitability {
    
    // 1. Preference Score (0.0 or 1.0)
    val preferenceScore = when (userPreference) {
        GatewayPreference.TOR_ONLY -> if (gatewayInfo.type == GatewaySuitability.GatewayType.TOR) 1.0f else 0.0f
        GatewayPreference.CLEARNET_ONLY -> if (gatewayInfo.type == GatewaySuitability.GatewayType.CLEARNET) 1.0f else 0.0f
        GatewayPreference.EITHER -> 1.0f // All gateways acceptable
    }
    
    // 2. Hop Count Score (0.0-1.0, exponential decay)
    // 0 hops = 1.0, 1 hop = 0.8, 2 hops = 0.6, 3 hops = 0.4, 4+ hops = 0.2
    val hopCountScore = when (hopCount) {
        0 -> 1.0f
        1 -> 0.8f
        2 -> 0.6f
        3 -> 0.4f
        else -> 0.2f
    }.coerceIn(0.0f, 1.0f)
    
    // 3. Bandwidth Score (0.0-1.0)
    // Normalize available bandwidth to 0-100 Mbps range
    val bandwidthMbps = gatewayInfo.availableBandwidth / 1_000_000f // bytes/sec to Mbps
    val bandwidthScore = (bandwidthMbps / 100f).coerceIn(0.0f, 1.0f)
    
    // 4. Latency Score (0.0-1.0, inverted - lower latency = higher score)
    // Normalize latency to 0-500ms range, invert
    val latencyMs = gatewayInfo.averageLatency
    val latencyScore = (1.0f - (latencyMs / 500f)).coerceIn(0.0f, 1.0f)
    
    // 5. Stability Score (0.0-1.0)
    // Based on uptime ratio and recent disconnections
    val stabilityScore = gatewayInfo.stabilityMetric // Pre-calculated in GatewayInfo
    
    // Weighted total score
    val weights = mapOf(
        "preference" to 0.40f,
        "hopCount" to 0.20f,
        "bandwidth" to 0.15f,
        "latency" to 0.15f,
        "stability" to 0.10f
    )
    
    val totalScore = (
        preferenceScore * weights["preference"]!! +
        hopCountScore * weights["hopCount"]!! +
        bandwidthScore * weights["bandwidth"]!! +
        latencyScore * weights["latency"]!! +
        stabilityScore * weights["stability"]!!
    ).coerceIn(0.0f, 1.0f)
    
    return GatewaySuitability(
        gatewayAddress = gatewayInfo.address,
        gatewayType = gatewayInfo.type,
        hopCount = hopCount,
        bandwidthScore = bandwidthScore,
        latencyScore = latencyScore,
        stabilityScore = stabilityScore,
        preferenceScore = preferenceScore,
        totalScore = totalScore
    )
}
```

**GatewayInfo Data Class** (supporting structure):

```kotlin
/**
 * Information about a gateway from the topology map.
 * Extracted from OriginatingMessageManager topology data.
 * 
 * @property address Virtual address of the gateway
 * @property type Type of gateway (TOR, CLEARNET, I2P)
 * @property availableBandwidth Bandwidth in bytes/sec
 * @property averageLatency Average latency in milliseconds
 * @property stabilityMetric Stability score 0.0-1.0 (pre-calculated)
 */
data class GatewayInfo(
    val address: Int,
    val type: GatewaySuitability.GatewayType,
    val availableBandwidth: Long,
    val averageLatency: Float,
    val stabilityMetric: Float
)
```

**Rationale**:
- **40% Preference Weight**: Ensures user preference heavily influences selection
- **Exponential Hop Decay**: Strongly prefers direct neighbors
- **Normalized Scores**: All metrics 0.0-1.0 for fair weighting
- **Tunable Weights**: Can adjust based on performance testing

**DECISION POINT**: Should preference score be 0.0 (filter out) or lower value (deprioritize but allow)?

**Answer: if the client node has selected ONLY one type of gateway ONLY that type of gateway should be selected. and favor given to closer gateways of the type corresponding to the user selection

**Current Choice**: 0.0 (filter out) - Strict preference enforcement. TOR_ONLY users never route via Clearnet.

**Alternative**: 0.1 (deprioritize) - Allow fallback to non-preferred gateway with very low score. Only used if no preferred gateways available.

**Recommendation**: Keep 0.0 (strict) for TOR_ONLY/CLEARNET_ONLY, but implement timeout fallback (Part 3) where after 5s of no preferred gateway, expand to 0.1 scoring.

---

### 3.4 Gateway Selection with Suitability Ranking

**File**: `GatewayRouter.kt`  
**Location**: Add to GatewayRouter class  
**Lines Added**: ~40

**Implementation**:

```kotlin
/**
 * Select best gateway from a list of candidates.
 * Ranks all candidates by suitability score, returns highest-scoring gateway.
 * 
 * Filters out gateways with preferenceScore = 0.0 (strict preference enforcement).
 * Returns null if no suitable gateways found.
 * 
 * @param candidates List of available gateways with topology info
 * @param userPreference User's gateway routing preference
 * @param hopCounts Map of gateway address → hop count
 * @return Address of best gateway, or null if none suitable
 */
fun selectBestGateway(
    candidates: List<GatewayInfo>,
    userPreference: GatewayPreference,
    hopCounts: Map<Int, Int>
): Int? {
    
    if (candidates.isEmpty()) {
        logger?.log(LogLevel.DEBUG, "GatewayRouter", "No gateway candidates available")
        return null
    }
    
    // Calculate suitability for all candidates
    val suitabilities = candidates.map { gateway ->
        val hopCount = hopCounts[gateway.address] ?: Int.MAX_VALUE
        calculateGatewaySuitability(gateway, userPreference, hopCount)
    }
    
    // Filter out unsuitable gateways (preferenceScore = 0.0)
    val suitableCandidates = suitabilities.filter { it.preferenceScore > 0.0f }
    
    if (suitableCandidates.isEmpty()) {
        logger?.log(
            LogLevel.WARN,
            "GatewayRouter",
            "No gateways match user preference: $userPreference (${candidates.size} candidates filtered out)"
        )
        return null
    }
    
    // Select gateway with highest total score
    val bestGateway = suitableCandidates.maxByOrNull { it.totalScore }!!
    
    logger?.log(
        LogLevel.INFO,
        "GatewayRouter",
        "Selected gateway ${bestGateway.gatewayAddress} (${bestGateway.gatewayType}) " +
        "with score ${bestGateway.totalScore} (hops=${bestGateway.hopCount}, " +
        "bandwidth=${bestGateway.bandwidthScore}, latency=${bestGateway.latencyScore})"
    )
    
    return bestGateway.gatewayAddress
}
```

**Usage Pattern** (Part 3 will implement full integration):

```kotlin
// Get all gateways from topology map
val allGateways = discoverGatewaysFromTopology() // Part 3, Section 3.5

// Calculate hop counts via BFS
val hopCounts = calculateHopCounts(allGateways) // Part 3, Section 3.6

// Select best gateway
val bestGatewayAddress = selectBestGateway(
    candidates = allGateways,
    userPreference = emergentRoleManager.getGatewayPreference(),
    hopCounts = hopCounts
)

if (bestGatewayAddress != null) {
    // Route packet to selected gateway (Part 3, Section 3.7)
    routePacketToGateway(packet, bestGatewayAddress)
} else {
    // Drop packet or queue for retry
    logger?.log(LogLevel.WARN, "No suitable gateway found, dropping packet")
}
```

---

### 3.5 Testing Strategy for Suitability Scoring

**Unit Tests Required**:

1. **Preference Filtering Tests**:
   ```kotlin
   @Test
   fun testTorOnlyFiltersOutClearnet() {
       val torGateway = GatewayInfo(address = 1, type = TOR, ...)
       val clearnetGateway = GatewayInfo(address = 2, type = CLEARNET, ...)
       
       val suitability1 = calculateGatewaySuitability(torGateway, GatewayPreference.TOR_ONLY, 0)
       val suitability2 = calculateGatewaySuitability(clearnetGateway, GatewayPreference.TOR_ONLY, 0)
       
       assertEquals(1.0f, suitability1.preferenceScore)
       assertEquals(0.0f, suitability2.preferenceScore)
   }
   ```

2. **Hop Count Scoring Tests**:
   ```kotlin
   @Test
   fun testHopCountScoreDecay() {
       val gateway = GatewayInfo(address = 1, type = TOR, ...)
       
       val score0 = calculateGatewaySuitability(gateway, GatewayPreference.EITHER, 0)
       val score1 = calculateGatewaySuitability(gateway, GatewayPreference.EITHER, 1)
       val score2 = calculateGatewaySuitability(gateway, GatewayPreference.EITHER, 2)
       
       assertTrue(score0.hopCountScore > score1.hopCountScore)
       assertTrue(score1.hopCountScore > score2.hopCountScore)
   }
   ```

3. **Total Score Weighting Tests**:
   ```kotlin
   @Test
   fun testPreferenceWeightDominates() {
       val torGateway = GatewayInfo(address = 1, type = TOR, bandwidth = 1_000_000L, latency = 100f, stability = 0.5f)
       val clearnetGateway = GatewayInfo(address = 2, type = CLEARNET, bandwidth = 100_000_000L, latency = 10f, stability = 1.0f)
       
       val torScore = calculateGatewaySuitability(torGateway, GatewayPreference.TOR_ONLY, 0)
       val clearnetScore = calculateGatewaySuitability(clearnetGateway, GatewayPreference.TOR_ONLY, 0)
       
       // Tor gateway should score higher despite worse metrics (preference = 40% weight)
       assertTrue(torScore.totalScore > clearnetScore.totalScore)
   }
   ```

4. **Gateway Selection Tests**:
   ```kotlin
   @Test
   fun testSelectBestGatewayRanking() {
       val candidates = listOf(
           GatewayInfo(1, TOR, 10_000_000L, 50f, 0.8f),
           GatewayInfo(2, TOR, 50_000_000L, 30f, 0.9f),
           GatewayInfo(3, TOR, 5_000_000L, 100f, 0.5f)
       )
       val hopCounts = mapOf(1 to 0, 2 to 1, 3 to 0)
       
       val best = selectBestGateway(candidates, GatewayPreference.EITHER, hopCounts)
       
       assertEquals(2, best) // Gateway 2 should win (high bandwidth, low latency, acceptable hops)
   }
   ```

---

## SECTION 2-3 COMPLETION CHECKLIST

### Section 2: Tor Status Query
- [ ] Add orbotStatusReceiver property to MeshrabiyaApiImpl
- [ ] Add torNetworkActive StateFlow to MeshrabiyaApiImpl
- [ ] Implement createOrbotStatusReceiver() method
- [ ] Add Orbot intent constants to companion object
- [ ] Modify initMesh() to register receiver
- [ ] Implement queryInitialTorStatus() method
- [ ] Add cleanup() method for receiver unregistration
- [ ] Modify EmergentRoleManager.selectBestGatewayRole() to use Tor status
- [ ] Modify EmergentRoleManager.calculateTargetRoles() with Tor availability guard
- [ ] Add getTorNetworkStatus() helper method to EmergentRoleManager
- [ ] Add all required imports (BroadcastReceiver, IntentFilter, StateFlow)

### Section 3 (First Half): Suitability Scoring
- [ ] Add GatewaySuitability data class to GatewayRouter
- [ ] Add GatewayInfo data class to GatewayRouter
- [ ] Implement calculateGatewaySuitability() method
- [ ] Implement selectBestGateway() method
- [ ] Write unit tests for preference filtering
- [ ] Write unit tests for hop count scoring
- [ ] Write unit tests for total score weighting
- [ ] Write unit tests for gateway selection ranking

### Testing & Validation
- [ ] All unit tests pass
- [ ] BroadcastReceiver receives Orbot broadcasts correctly
- [ ] torNetworkActive updates on status changes
- [ ] Role re-evaluation triggers on Tor status change
- [ ] TOR_ONLY preference prevents gateway role when Tor down
- [ ] Suitability scoring ranks gateways correctly
- [ ] Preference weight (40%) dominates other factors

---

## NEXT STEPS (PART 3 PREVIEW)

Part 3 will complete gateway failover and refactor role selection:

1. **Section 3 (Second Half): Mesh-Wide Gateway Discovery**
   - Query OriginatingMessageManager topology map for all gateways
   - Extract gateway type from role announcements
   - Calculate hop counts via BFS traversal
   - Filter gateways by user preference

2. **Section 4: selectBestGatewayRole() Complete Refactor**
   - Replace Boolean logic with GatewayPreference enum logic (from Part 1)
   - Integrate Tor status checking (from Part 2)
   - Add TOR_ONLY preference logic (never select Clearnet)
   - Add CLEARNET_ONLY preference logic (never select Tor)
   - Add EITHER preference logic (capability-based, current behavior)

3. **Section 5 (First Half): API Method Interfaces**
   - Add setGatewayPreference() to MeshrabiyaApi interface
   - Add getGatewayPreference() to MeshrabiyaApi interface
   - Add getTorNetworkStatus() to MeshrabiyaApi interface

**Estimated Lines**: ~200 lines (Section 3 second half + Section 4 + Section 5 first half)

---

## APPENDIX A: ORBOT BROADCAST SPECIFICATION

**Intent Action**: `org.torproject.android.intent.action.STATUS`

**Extras**:
- **EXTRA_STATUS**: String with values:
  - `"ON"` - Tor network is active and ready
  - `"OFF"` - Tor network is inactive
  - `"STARTING"` - Tor is starting up (not yet ready)
  - `"STOPPING"` - Tor is shutting down

**Request Action**: `org.torproject.android.intent.action.REQUEST_STATUS`

**Response**: Orbot responds with STATUS broadcast (100-500ms delay)

**Permissions**: None required (implicit broadcast)

**Orbot Not Installed**: Broadcasts never received, torNetworkActive remains false

---

## APPENDIX B: SUITABILITY SCORING WEIGHTS (TUNABLE)

| Metric | Weight | Rationale |
|--------|--------|-----------|
| Preference | 40% | User choice is paramount (privacy/security) |
| Hop Count | 20% | Minimize latency and failure points |
| Bandwidth | 15% | Important for throughput, less critical than preference |
| Latency | 15% | Important for responsiveness, less critical than hops |
| Stability | 10% | Nice to have, but mesh is resilient to gateway failures |

**Adjustment Scenarios**:
- **Real-time traffic** (VoIP, gaming): Increase Latency to 25%, decrease Stability to 5%
- **Bulk transfer** (file download): Increase Bandwidth to 25%, decrease Latency to 5%
- **Privacy-critical** (whistleblower): Increase Preference to 60%, decrease others proportionally

**Future Enhancement**: Allow per-packet weight tuning based on traffic type (QoS integration).

---

## APPENDIX C: HOP COUNT CALCULATION (PREVIEW)

**Algorithm** (to be implemented in Part 3, Section 3.6):

```kotlin
fun calculateHopCounts(myAddress: Int, topologyMap: Map<Int, NodeTopologyInfo>): Map<Int, Int> {
    val hopCounts = mutableMapOf<Int, Int>()
    val queue = ArrayDeque<Pair<Int, Int>>() // Pair<address, hops>
    val visited = mutableSetOf<Int>()
    
    // BFS from current node
    queue.add(Pair(myAddress, 0))
    visited.add(myAddress)
    
    while (queue.isNotEmpty()) {
        val (currentAddr, hops) = queue.removeFirst()
        hopCounts[currentAddr] = hops
        
        val neighbors = topologyMap[currentAddr]?.neighbors ?: emptySet()
        for (neighbor in neighbors) {
            if (neighbor !in visited) {
                visited.add(neighbor)
                queue.add(Pair(neighbor, hops + 1))
            }
        }
    }
    
    return hopCounts
}
```

**Complexity**: O(N + E) where N = nodes, E = edges (standard BFS)

**Usage**: Called once per routing decision, cached for batch packet routing.

---

## END OF PART 2

**Total Lines**: ~1,105 lines (including documentation, code, appendices)

**Parts Completed**: 2 of 4

**Proceed to**: [TOR_INTEGRATION_PLAN_PART3.md] for mesh-wide discovery, role selection refactor, and API interface definitions.

**Open Questions for User**:
1. Tor status "STARTING" - treat as false (conservative) or true (optimistic)? **Recommendation**: FALSE
2. TOR_ONLY + Tor unavailable - decline gateway role (current) or throw exception? **Recommendation**: Decline role (simpler)
3. Preference score 0.0 (filter out) or 0.1 (deprioritize)? **Recommendation**: 0.0 with 5s timeout fallback to 0.1
4. Should cleanup() be added to MeshrabiyaApi interface for public API? **Recommendation**: Yes (Part 4)
