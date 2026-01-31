# MESH JOIN PLAN - PART 2: HOTSPOT ROLE SWITCHING & RECOVERY

**⚠️ PHASE 2 FEATURE - NOT YET IMPLEMENTED**

This document describes automatic hotspot recovery features that are **proposed but not yet implemented**. The basic QR join functionality (Phase 1) does NOT require these features.

**⚠️ IMPORTANT: Separation of Concerns**

**Hotspot Recovery** (this document) is SEPARATE from **Mesh Merging** (PT8):

- **Hotspot Recovery** = Automatic failsafe when no localhotspot is running
  - Triggered by: No hotspot detected in local network
  - Action: Start localhotspot with stored or new config
  - Decision: Based on neighbors, role, network conditions
  - See this document (PT2) for implementation

- **Mesh Merge** = Gossip-based convergence of multiple mesh groups
  - Triggered by: QR scan OR receiving MeshMergeAnnouncement
  - Action: Attempt WiFi connection to target mesh
  - Decision: Join if target config differs from stored config
  - See PT8 for implementation

**Do NOT confuse these two functionalities!**

## User Question: Automatic Hotspot Mode Switching

**Q5: "Can logic be applied that if (after connecting to the mesh) there isn't a hotspot available (device no longer in range), then the node becomes a hotspot?"**

**A: YES - Technically feasible, especially for concurrent AP+STA devices**

### Current State: No Automatic Switching

**Finding:** Searched codebase for hotspot switching logic:
- `becomeHotspot`, `switchMode`, `fallback`, `role.*switch` → **0 matches**
- Hotspot control is **manual only** via `setWifiHotspotEnabled()`
- No recovery mechanisms for lost hotspot connections

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

**Lines 175-189:** Current hotspot control (manual only)
```kotlin
open suspend fun setWifiHotspotEnabled(
    enabled: Boolean,
    preferredBand: ConnectBand = ConnectBand.BAND_UNKNOWN,
    hotspotType: HotspotType = HotspotType.AUTO,
): LocalHotspotResponse? {
    return if(enabled) {
        // Start hotspot
        meshrabiyaWifiManager.startHotspot(preferredBand, hotspotType)
    } else {
        // Stop hotspot
        meshrabiyaWifiManager.stopHotspot()
        null
    }
}
```

---

## Hotspot Recovery Implementation Strategy

### Phase 1: Connection Loss Detection (ALREADY PARTIALLY IMPLEMENTED)

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/ext/OriginatingMessageManager.kt`

**Lines 217-240:** Existing station persistence logic
```kotlin
// Get current station state
val stationState = getWifiState().wifiStationState
val stationNeighborInetAddr = stationState.config?.linkLocalAddr

// If connected as station but haven't heard from hotspot recently
if(stationNeighborInetAddr != null &&
    !neighbors.any { it.value.lastHopRealInetAddr == stationNeighborInetAddr }) {
    
    logger(Log.VERBOSE, 
        "$logPrefix sending originating to parent station at $stationNeighborInetAddr"
    )
    
    // Retry sending to parent hotspot
    stationState.stationBoundDatagramSocket.send(
        nextHopAddress = stationNeighborInetAddr,
        nextHopPort = stationDatagramPort,
        virtualPacket = packet,
    )
}
```

**What This Does:**
- Monitors whether originating messages are received from parent hotspot
- If not received recently, retries sending packets to hotspot
- Helps maintain connection during brief signal loss

**Limitation:** Only retries sending - doesn't detect complete hotspot loss or trigger recovery

---

### Phase 2: Enhanced Connection Monitoring (NEW IMPLEMENTATION NEEDED)

**Add to:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

**New Method: Monitor Station Connection Health**

```kotlin
/**
 * Monitor WiFi station connection health and trigger recovery if connection is lost
 */
private fun startConnectionMonitoring() {
    nodeScope.launch {
        meshrabiyaWifiManager.state
            .map { it.wifiStationState }
            .distinctUntilChanged()
            .collect { stationState ->
                when (stationState.status) {
                    WifiStationState.Status.CONNECTED -> {
                        logger(Log.INFO, "$logPrefix Station connected")
                        // Reset retry counter
                        stationRetryCount = 0
                    }
                    
                    WifiStationState.Status.LOST -> {
                        logger(Log.WARN, "$logPrefix Station connection lost, attempting recovery")
                        handleConnectionLoss()
                    }
                    
                    WifiStationState.Status.UNAVAILABLE -> {
                        logger(Log.ERROR, "$logPrefix Station unavailable, searching for alternate hotspot")
                        handleHotspotUnavailable()
                    }
                    
                    else -> {
                        // CONNECTING, DISCONNECTED - no action
                    }
                }
            }
    }
}

private var stationRetryCount = 0
private var lastConnectedConfig: WifiConnectConfig? = null

/**
 * Handle temporary connection loss - retry with exponential backoff
 */
private suspend fun handleConnectionLoss() {
    val config = lastConnectedConfig ?: run {
        logger(Log.WARN, "$logPrefix No previous connection config, searching for hotspot")
        handleHotspotUnavailable()
        return
    }
    
    // Retry up to 3 times with exponential backoff
    repeat(3) { attempt ->
        stationRetryCount++
        val backoffDelay = 5000L * (stationRetryCount)  // 5s, 10s, 15s
        
        logger(Log.INFO, "$logPrefix Retry attempt $attempt after ${backoffDelay}ms")
        delay(backoffDelay)
        
        try {
            connectAsStation(config)
            logger(Log.INFO, "$logPrefix Successfully reconnected to ${config.ssid}")
            stationRetryCount = 0
            return
        } catch (e: WifiConnectException) {
            logger(Log.WARN, "$logPrefix Retry $attempt failed: ${e.message}")
        }
    }
    
    // All retries failed - hotspot is truly unavailable
    logger(Log.ERROR, "$logPrefix All reconnection attempts failed")
    handleHotspotUnavailable()
}

/**
 * Hotspot is unavailable - search for alternates or become hotspot
 */
private suspend fun handleHotspotUnavailable() {
    logger(Log.INFO, "$logPrefix Searching for alternate hotspots in mesh")
    
    // Query topology for nodes with MESH_ROUTER role (they're running hotspots)
    val availableHotspots = originatingMessageManager
        .getNodesWithRole(MeshRole.MESH_ROUTER)
        .filterNot { it.isStale(30_000L) }  // Not stale within last 30 seconds
        .sortedByDescending { it.centralityScore }  // Best-connected first
    
    logger(Log.INFO, "$logPrefix Found ${availableHotspots.size} available hotspots")
    
    // Try to connect to each available hotspot
    for (hotspotNode in availableHotspots) {
        try {
            logger(Log.INFO, "$logPrefix Requesting connection info from node ${hotspotNode.nodeAddress}")
            
            // Request hotspot config from the node
            // (Requires new mesh protocol message type - see Phase 3)
            val config = requestHotspotConfig(hotspotNode.nodeAddress)
            
            logger(Log.INFO, "$logPrefix Attempting connection to ${config.ssid}")
            connectAsStation(config)
            
            logger(Log.INFO, "$logPrefix Successfully connected to alternate hotspot")
            lastConnectedConfig = config
            return
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix Failed to connect to hotspot ${hotspotNode.nodeAddress}: ${e.message}")
        }
    }
    
    // No hotspots available - should this device become one?
    logger(Log.WARN, "$logPrefix No hotspots available in mesh")
    considerBecomingHotspot()
}
```

---

### Phase 3: Coordinated Hotspot Promotion (ANSWERS USER'S KEY QUESTION)

**Q: "How can rules be extended to avoid all devices changing mode at the same time when the hotspot they're using isn't reachable?"**

**A: Use neighbor-based backoff algorithm (no quorums needed)**

**Add to:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

```kotlin
/**
 * Decide whether this device should become a hotspot based on mesh state
 * and neighbor count (inverse relationship to prevent stampede)
 */
private suspend fun considerBecomingHotspot() {
    // Only consider if device supports concurrent AP+STA or isn't currently connected
    val canBecomeHotspot = meshrabiyaWifiManager.state.first().concurrentApStationSupported ||
                           meshrabiyaWifiManager.state.first().wifiStationState.status == WifiStationState.Status.DISCONNECTED
    
    if (!canBecomeHotspot) {
        logger(Log.WARN, "$logPrefix Cannot become hotspot (no concurrent support and currently connected)")
        return
    }
    
    // Check if any neighbor already started a hotspot
    val neighborsWithHotspot = originatingMessageManager
        .getNodesWithRole(MeshRole.MESH_ROUTER)
        .filterNot { it.isStale(15_000L) }  // Active within last 15 seconds
    
    if (neighborsWithHotspot.isNotEmpty()) {
        logger(Log.INFO, "$logPrefix ${neighborsWithHotspot.size} neighbors already running hotspots, aborting promotion")
        // Try connecting to one of them
        handleHotspotUnavailable()
        return
    }
    
    // Calculate backoff time inversely proportional to number of neighbors
    // More neighbors = shorter wait (more urgent need for hotspot)
    // Fewer neighbors = longer wait (give others time to promote)
    val neighborCount = originatingMessageManager.getTopologyMapInfo().size
    
    val baseBackoffMs = 30_000L  // 30 seconds base
    val backoffMs = if (neighborCount > 0) {
        baseBackoffMs / neighborCount  // Inverse relationship
    } else {
        baseBackoffMs  // Solo device waits full time
    }
    
    logger(Log.INFO, "$logPrefix Waiting ${backoffMs}ms before becoming hotspot (neighborCount=$neighborCount)")
    delay(backoffMs)
    
    // Re-check if any neighbor became hotspot during wait
    val neighborsNow = originatingMessageManager
        .getNodesWithRole(MeshRole.MESH_ROUTER)
        .filterNot { it.isStale(10_000L) }
    
    if (neighborsNow.isNotEmpty()) {
        logger(Log.INFO, "$logPrefix Neighbor promoted to hotspot during wait, aborting")
        handleHotspotUnavailable()
        return
    }
    
    // No hotspots appeared - become one!
    logger(Log.INFO, "$logPrefix Promoting self to hotspot (no alternatives found)")
    promoteToHotspot()
}

/**
 * Promote this device to hotspot and add MESH_ROUTER role
 */
private suspend fun promoteToHotspot() {
    try {
        // Start hotspot
        val hotspotResponse = setWifiHotspotEnabled(
            enabled = true,
            preferredBand = ConnectBand.BAND_5GHZ,  // Prefer 5GHz for better performance
            hotspotType = HotspotType.LOCALONLY_HOTSPOT
        )
        
        if (hotspotResponse != null) {
            logger(Log.INFO, "$logPrefix Successfully promoted to hotspot: ${hotspotResponse.config.ssid}")
            
            // Add MESH_ROUTER role
            // EmergentRoleManager automatically assigns MESH_ROUTER when:
            // - fitness > 0.6 && centrality > 3.0 && concurrentApStationSupported
            // No manual role addition needed - roles are recalculated automatically
            notifyRoleChange(addRole = MeshRole.MESH_ROUTER)
            
            // Announce new hotspot to mesh via originating message
            // (Automatic - originating messages include roles)
        } else {
            logger(Log.ERROR, "$logPrefix Failed to promote to hotspot")
        }
    } catch (e: Exception) {
        logger(Log.ERROR, "$logPrefix Exception promoting to hotspot", e)
    }
}

/**
 * Notify role manager of role change
 */
private fun notifyRoleChange(addRole: MeshRole? = null, removeRole: MeshRole? = null) {
    // Post event to role manager
    // EmergentRoleManager recalculates roles automatically based on state changes
    nodeScope.launch {
        // This will be implemented in EmergentRoleManager integration
        logger(Log.INFO, "$logPrefix Role change requested: add=$addRole remove=$removeRole")
    }
}
```

**Backoff Algorithm Explanation:**

| Scenario | Neighbor Count | Backoff Time | Reasoning |
|----------|---------------|--------------|-----------|
| Solo device | 0 | 30 seconds | No urgency, might be network error |
| Small mesh | 3 neighbors | 10 seconds | Some coordination needed |
| Medium mesh | 6 neighbors | 5 seconds | Higher demand, quicker promotion |
| Large mesh | 10+ neighbors | 3 seconds | Critical need, immediate promotion |

**Formula:** `backoffMs = 30000 / max(neighborCount, 1)`

**Why This Works:**
1. **Prevents stampede:** Devices wait different times based on local view
2. **Self-healing:** Eventually someone will promote after sufficient wait
3. **Distributed decision:** No central coordinator needed
4. **Adaptive:** Scales with mesh size automatically

---

### Phase 4: Multi-Hotspot Collision Handling

**Q: "Do we need additional rules to handle when 2 hotspots come within range?"**

**A: YES - Implement hotspot consolidation to avoid waste**

**Add to:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

```kotlin
/**
 * Monitor for hotspot collisions and consolidate if needed
 */
private fun startHotspotCollisionMonitoring() {
    nodeScope.launch {
        // Monitor topology for nearby hotspots
        originatingMessageManager.topologyMapFlow
            .map { topology ->
                topology.values.filter { node ->
                    node.hasRole(MeshRole.MESH_ROUTER) && 
                    !node.isStale(20_000L) &&
                    node.hopCount <= 2  // Within 2 hops (likely same physical area)
                }
            }
            .distinctUntilChanged()
            .collect { nearbyHotspots ->
                if (nearbyHotspots.size > 1 && isRunningHotspot()) {
                    logger(Log.INFO, "$logPrefix Detected ${nearbyHotspots.size} nearby hotspots")
                    considerHotspotConsolidation(nearbyHotspots)
                }
            }
    }
}

/**
 * Decide if this hotspot should shut down to consolidate with others
 */
private suspend fun considerHotspotConsolidation(nearbyHotspots: List<NodeTopologyInfo>) {
    // Don't consolidate if we have clients connected
    val clientCount = getConnectedClientCount()
    if (clientCount > 0) {
        logger(Log.INFO, "$logPrefix Keeping hotspot active (has $clientCount clients)")
        return
    }
    
    // Consolidation rules:
    // 1. If another hotspot has higher centrality, defer to it
    // 2. If another hotspot has more clients, defer to it
    // 3. If tie, use virtual address as tiebreaker (deterministic)
    
    val myCentrality = originatingMessageManager.getTopologyMapInfo()[addressAsInt]?.centralityScore ?: 0.0f
    val myAddress = addressAsInt
    
    val betterHotspots = nearbyHotspots.filter { hotspot ->
        // Higher centrality wins
        if (hotspot.centralityScore > myCentrality) return@filter true
        
        // Same centrality: higher address wins (deterministic tiebreaker)
        if (hotspot.centralityScore == myCentrality && hotspot.nodeAddress > myAddress) return@filter true
        
        false
    }
    
    if (betterHotspots.isNotEmpty()) {
        logger(Log.INFO, "$logPrefix Found better hotspot, shutting down and connecting as station")
        
        // Announce shutdown to give clients time to migrate
        announceHotspotShutdown(timeUntilShutdown = 10_000L)
        delay(10_000L)
        
        // Stop hotspot
        setWifiHotspotEnabled(false)
        
        // Remove MESH_ROUTER role
        notifyRoleChange(removeRole = MeshRole.MESH_ROUTER)
        
        // Connect to the better hotspot
        try {
            val betterHotspot = betterHotspots.first()
            val config = requestHotspotConfig(betterHotspot.nodeAddress)
            connectAsStation(config)
            logger(Log.INFO, "$logPrefix Successfully consolidated to better hotspot")
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix Failed to connect to better hotspot", e)
        }
    }
}

/**
 * Get count of clients connected to this hotspot
 */
private fun getConnectedClientCount(): Int {
    // Count neighbors whose last hop address matches our hotspot interface
    return originatingMessageManager.getTopologyMapInfo().values.count { neighbor ->
        neighbor.hopCount == 1  // Direct connection = client
    }
}

/**
 * Announce impending hotspot shutdown to allow client migration
 */
private suspend fun announceHotspotShutdown(timeUntilShutdown: Long) {
    // Create shutdown announcement message
    // (Requires new mesh protocol message type)
    logger(Log.INFO, "$logPrefix Announcing hotspot shutdown in ${timeUntilShutdown}ms")
    
    // Broadcast to all clients
    // This will be part of the mesh protocol enhancement
}
```

**Consolidation Algorithm Summary:**

1. **Detect collision:** Multiple hotspots within 2 hops
2. **Check clients:** Never shut down if clients are connected
3. **Compare metrics:**
   - Centrality score (higher = more important to mesh)
   - Virtual address (deterministic tiebreaker)
4. **Graceful shutdown:** 10-second warning for client migration
5. **Reconnect:** Join the better hotspot as a station

**Result:** Mesh self-organizes to optimal number of hotspots based on coverage and load.

---

## Technical Feasibility Assessment

### LocalOnlyHotspot Switching (Android 13+)

**Fully Supported - No Breaking Changes**

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/LocalOnlyHotspotManager.kt`

**Stop Hotspot (Lines 198-217):**
```kotlin
suspend fun stopLocalOnlyHotspot(waitForStop: Boolean = true) {
    val prevState = _state.getAndUpdate { prev ->
        if(prev.status == HotspotStatus.STARTED) {
            prev.copy(status = HotspotStatus.STOPPING)
        } else prev
    }
    
    if(prevState.status == HotspotStatus.STARTED) {
        val reservationVal = localOnlyHotspotReservation
        
        logger(Log.DEBUG, "$logPrefix Stopping local only hotspot")
        reservationVal?.close()  // Releases hotspot cleanly
        localOnlyHotspotReservation = null
        
        _state.value = LocalOnlyHotspotState(status = HotspotStatus.STOPPED)
    }
    
    if(waitForStop) {
        _state.filter { it.status == HotspotStatus.STOPPED }.first()
    }
}
```

**Start Hotspot:** Already shown in Part 1 (Lines 100-144)

**Key Points:**
- ✅ Clean shutdown via `reservation.close()`
- ✅ State tracking prevents double-start/stop
- ✅ Async with optional wait for completion
- ✅ **Maintains station connection** if concurrent AP+STA supported

### WiFi Direct Switching (Android 8-12)

**Partially Supported - Causes Station Disconnect**

**Limitations:**
- ❌ Starting WiFi Direct Group **tears down** active station connection
- ❌ No graceful migration - clients experience abrupt disconnect
- ❌ Must re-establish mesh connections after mode switch

**Recommendation:** 
- Enable automatic switching **only for Android 13+** (concurrent devices)
- Android 8-12 devices require **manual user confirmation** before switching
- Warn user: "Switching to hotspot mode will disconnect you from the current network"

---

## Integration with Role Management

### EmergentRoleManager Extension

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

**Note:** EmergentRoleManager automatically recalculates roles. No manual intervention needed for Phase 2.

**Add Flow for External Role Requests:**

```kotlin
// Add property to class
private val _roleChangeRequests = MutableSharedFlow<RoleChangeRequest>()

/**
 * Request a role be added or removed outside normal calculation cycle
 */
suspend fun requestRoleChange(request: RoleChangeRequest) {
    _roleChangeRequests.emit(request)
}

data class RoleChangeRequest(
    val addRole: MeshRole? = null,
    val removeRole: MeshRole? = null,
    val reason: String
)

// In start() method, collect role change requests
init {
    roleManagerScope.launch {
        _roleChangeRequests.collect { request ->
            logger(Log.INFO, "External role change request: $request")
            
            when {
                request.addRole != null -> {
                    _currentRoles.update { current ->
                        current + request.addRole
                    }
                }
                request.removeRole != null -> {
                    _currentRoles.update { current ->
                        current - request.removeRole
                    }
                }
            }
            
            // Recalculate immediately to reflect change
            triggerRoleRecalculation()
        }
    }
}
```

**Hook from AndroidVirtualNode:**

```kotlin
// In notifyRoleChange() implementation
private suspend fun notifyRoleChange(addRole: MeshRole? = null, removeRole: MeshRole? = null) {
    // Get role manager from MeshrabiyaApiImpl
    // (Requires passing reference during initialization)
    roleManager?.requestRoleChange(
        RoleChangeRequest(
            addRole = addRole,
            removeRole = removeRole,
            reason = "Hotspot promotion/demotion"
        )
    )
}
```

---

## Summary

### Automatic Hotspot Recovery: FULLY FEASIBLE

✅ **Connection monitoring** - Detect lost hotspot  
✅ **Exponential backoff retry** - 3 attempts before giving up  
✅ **Alternate hotspot search** - Query topology for MESH_ROUTER nodes  
✅ **Coordinated promotion** - Neighbor-based backoff prevents stampede  
✅ **Role integration** - Automatic MESH_ROUTER role assignment  
✅ **Collision handling** - Consolidate redundant hotspots  
✅ **Graceful migration** - 10-second warning for client moves  

### Platform Support

| Feature | Android 13+ | Android 8-12 |
|---------|-------------|--------------|
| Automatic recovery | ✅ Full support | ⚠️ Requires user confirmation |
| Concurrent AP+STA | ✅ Yes | ❌ No |
| Hotspot switching | ✅ Seamless | ❌ Breaks station connection |
| MESH_ROUTER role | ✅ Automatic | ❌ Not available |

### Next Steps

See MESH_JOIN_PLAN-PT3.md for mesh topology and role system details.
