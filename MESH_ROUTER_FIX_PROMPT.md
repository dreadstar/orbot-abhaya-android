# MESH_HUB Role Implementation - Agent Task Prompt

## CRITICAL: AGENTS.md COMPLIANCE

**This task MUST follow these AGENTS.md protocols:**

1. **IMPLEMENTATION VERIFICATION BEFORE CODE GENERATION PROTOCOL (2025-12-06)**
   - Read ACTUAL current implementation on disk before ANY code generation
   - Verify EVERY class, method, property signature with grep_search + read_file
   - Document discrepancies between assumptions and reality
   - Complete enforcement checklist before writing ANY implementation code

2. **DETAILED PLAN SPECIFICATION RULE (2026-01-25)**
   - Perform exhaustive, codebase-driven research
   - Enumerate every file, field, method with exact paths and signatures
   - Describe all wiring/propagation steps with concrete code-level details
   - Cite verification steps for every referenced symbol
   - Never omit or generalize steps

3. **LARGE FILE MANUAL EDIT RULE (2026-02-02)**
   - VirtualNode.kt (1378 lines) - PRESENT changes as BEFORE/AFTER snippets with line numbers
   - EmergentRoleManager.kt (1354 lines) - PRESENT changes as BEFORE/AFTER snippets with line numbers
   - DO NOT attempt direct edits with replace_string_in_file
   - Include minimum 5 lines context before/after each change

4. **NEVER ASSUME USER ERROR - CRITICAL RULE (2026-01-23)**
   - Trust all user observations about mesh behavior
   - Analyze deeper when logs don't match expectations
   - Never question testing methodology

---

## TASK OVERVIEW

**Objective:** Analyze the complete mesh networking system and implement MESH_HUB role to fix broadcast forwarding for non-concurrent hotspot nodes.

**Problem Statement:**
- Phone 1 acts as WiFi hotspot (MESH_HUB) with Phone 2 connected as station
- Phone 1 has roles: [MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE] - no MESH_ROUTER
- Broadcast messages sent from Phone 1 are discarded with log: "not MESH_ROUTER, not forwarding"
- Phone 2 never receives broadcasts despite mesh showing CONNECTED with 1 neighbor
- Root cause: VirtualNode.route() requires MESH_ROUTER role to forward broadcasts
- Architecture flaw: MESH_ROUTER designed for AP concurrency, but basic hotspot relay needs forwarding capability

**Role Clarifications:**

- **MESH_ROUTER:** Device WITH AP concurrency (hotspot + station simultaneously) - can forward between segments
- **MESH_HUB (NEW):** Device WITHOUT AP concurrency acting as hotspot - central relay point
  - Should be assigned when node starts mesh or promoted to hotspot WITHOUT AP concurrency
  - MUST forward broadcasts to all connected nodes and packets to all connected target nodes
  - Connected nodes MUST be able communicate through hub relay (hub shoould forward packets as needed)
  - Connected nodes MUST be able to communicate with hub itself
  - must be able to detect if any connected nodes are MESH_ROUTERs and be able to route packets to the MESH_ROUTER when packet targets are located on the other side of the MESH_ROUTER  from topology perspective

**Broadcast Architecture (Dual-Path Design):**

The finalized architecture separates broadcast origination from forwarding:

**Path 1: Broadcast Origination**
- ANY node (station, hub, or router) can originate broadcasts
- `sendBroadcast()` sends directly to ALL neighbors (no `route()` call)
- Sender does NOT see notification (no loopback)
- Sender's IP included in packet's `fromAddr` field

**Path 2: Broadcast Forwarding**
- `route()` only handles broadcasts received from network
- ALWAYS delivers locally (receiver sees notification)
- Forwards if node has MESH_HUB or MESH_ROUTER role
- Uses deduplication to prevent broadcast storms
- Excludes sender from forwarding list

**Multi-Tier Topology Example:**

```
                    Phone 1 (MESH_HUB)
                    169.254.1.242
                    WiFi: Non-concurrent hotspot
                           |
          +----------------+----------------+
          |                                 |
    Phone 2 (Station)              Phone 3 (MESH_ROUTER)
    169.254.10.156                 169.254.15.203
    WiFi: Station                  WiFi: Concurrent AP+Station
                                           |
                                   +-------+-------+
                                   |               |
                             Phone 4           Phone 5
                             169.254.20.100    169.254.20.101
                             Station           Station
```

**Broadcast Flow: Phone 2 → Phone 4/5 (Cross-Segment)**

1. **Phone 2 Originates:** sendBroadcast() → direct send to Phone 1 (neighbor)
2. **Phone 1 Receives:** route() → deliver locally → forward to Phone 3 (MESH_HUB role)
3. **Phone 3 Receives:** route() → deliver locally → forward to Phone 4 & 5 (MESH_ROUTER role)
4. **Phone 4/5 Receive:** route() → deliver locally → do NOT forward (leaf nodes)

**Key Behaviors:**
- Sender (Phone 2) does NOT see notification
- All recipients (Phones 1, 3, 4, 5) DO see notification
- Deduplication prevents loops if multiple paths exist
- 3-4 hops from edge-to-edge in multi-tier topology

---

## PHASE 1: COMPREHENSIVE CODEBASE ANALYSIS

### Deliverable: CANONICAL_MESH_NETWORK_WORKFLOWS_v1.md

**MANDATORY SCOPE:**

This analysis MUST match the level of detail and structure found in existing CANONICAL_WORKFLOW documents (CANONICAL_WORKFLOW_v2_*.md). Review these files to understand the required depth.

#### 1.1 Mesh Initialization and Start

**Required Research (verify EVERY method):**

1. **MeshrabiyaApiImpl.initMesh()**
   - [ ] Perform `grep_search` for "fun initMesh" to find location
   - [ ] Read ACTUAL signature with parameters and return type
   - [ ] Document what services/managers are initialized
   - [ ] List all dependency injections and their initialization order
   - [ ] Verify callback registrations (topology, role updates, etc.)

2. **MeshrabiyaApiImpl.startMesh()**
   - [ ] Perform `grep_search` for "fun startMesh" to find location
   - [ ] Read ACTUAL signature and implementation
   - [ ] Document complete workflow from start to hotspot enabled
   - [ ] Identify WiFi manager interaction (setWifiHotspotEnabled)
   - [ ] Document HotspotType selection logic (AUTO vs LOCALONLY vs TETHERED)
   - [ ] Trace LocalHotspotResponse handling

3. **VirtualNode.setWifiHotspotEnabled()**
   - [ ] Location: Verify in VirtualNode.kt with exact line numbers
   - [ ] Read ACTUAL implementation (lines 1171-1192 per summary)
   - [ ] Document MeshrabiyaWifiManager.setLocalOnlyHotspotEnabled() call
   - [ ] Document MeshrabiyaWifiManager.setTetheringHotspotEnabled() call
   - [ ] Trace LocalHotspotResponse return path

4. **AP Concurrency Detection**
   - [ ] Search for "concurrentApStationSupported" in EmergentRoleManager.kt
   - [ ] Read ACTUAL lazy initialization (lines 128-136 per summary)
   - [ ] Document how WiFi hardware capability is queried
   - [ ] Verify this value never changes at runtime (hardware capability)

**Required Documentation:**

- Sequence diagram: User clicks "Start Mesh" → Hotspot enabled → Roles assigned
- Call graph: initMesh() → startMesh() → setWifiHotspotEnabled() → role assignment
- State transitions: DISCONNECTED → STARTING → CONNECTED
- Data flow: UI → API → VirtualNode → WiFi Manager → Android WiFi APIs
- **Code snippets:** Include ACTUAL method signatures and key logic blocks

#### 1.2 Mesh Join Workflow (Station Connection)

**Required Research (verify EVERY method):**

1. **MeshrabiyaApiImpl.joinMesh()**
   - [ ] Perform `grep_search` for "fun joinMesh" to find location
   - [ ] Read ACTUAL signature (takes connectLink parameter?)
   - [ ] Document MeshrabiyaConnectLink parsing
   - [ ] Trace WiFi station connection initiation

2. **Station Connection Establishment**
   - [ ] Search for WiFi station connection methods in MeshrabiyaWifiManager
   - [ ] Document APIPA address assignment (169.254.x.x range)
   - [ ] Verify UDP socket binding to local APIPA address
   - [ ] Document real transport address vs virtual address mapping

3. **Neighbor Discovery on Join**
   - [ ] Trace first originating message send after WiFi connected
   - [ ] Document how joining node discovers hub/hotspot
   - [ ] Verify neighbor list update in OriginatingMessageManager

**Required Documentation:**

- Sequence diagram: User scans QR → WiFi connects → Neighbor discovered → Mesh operational
- Network topology diagram: Station (169.254.10.156) ←→ Hotspot (169.254.1.242)
- Data flow: QR scan → connectLink → WiFi join → neighbor discovery → topology update
- **Code snippets:** Include ACTUAL methods for station connection and neighbor discovery

#### 1.3 Originating Message Protocol

**Required Research (verify EVERY method):**

1. **OriginatingMessageManager.kt - Complete Analysis**
   - [ ] Perform `grep_search` for "class OriginatingMessageManager"
   - [ ] Read ENTIRE class definition (constructor parameters)
   - [ ] Document callback structure:
     - onTopologyUpdateCallback
     - onRoleUpdateCallback
     - onTopologyMapCallback
   - [ ] Verify periodic broadcast interval (3 seconds observed in logs)

2. **Originating Message Lifecycle**
   - [ ] Search for originating message creation/serialization
   - [ ] Document message content: nodeId, roles, capabilities, timestamp
   - [ ] Trace broadcast mechanism (send to ADDR_BROADCAST?)
   - [ ] Verify reception and processing in route() method

3. **Neighbor List Management**
   - [ ] Read `OriginatingMessageManager.neighbors()` signature
   - [ ] Document return type (List<NodeTopologyInfo>?)
   - [ ] Verify how neighbors are added/removed from list
   - [ ] Document timeout/staleness logic for neighbor entries

4. **Topology Map Building**
   - [ ] Search for topology map data structure (Map<Int, NodeTopologyInfo>?)
   - [ ] Document when topology is updated (on every originating message?)
   - [ ] Verify getTopologyMap callback to EmergentRoleManager
   - [ ] Document NodeTopologyInfo structure (address, roles, capabilities, lastSeen)

**Required Documentation:**

- Timing diagram: Originating messages sent every 3 seconds, reception, neighbor update
- Data structure: NodeTopologyInfo fields, topology map structure
- Protocol specification: Message format, broadcast vs unicast, timeout values
- **Code snippets:** Include ACTUAL originating message creation, neighbors() implementation

#### 1.4 Packet Routing Logic (VirtualNode.route())

**CRITICAL: This is the primary failure point for broadcasts**

**Required Research (verify EVERY line of logic):**

1. **VirtualNode.route() Complete Analysis**
   - [ ] Location: Lines 723-868 in VirtualNode.kt (LARGE FILE - use read_file)
   - [ ] Read ENTIRE method implementation
   - [ ] Document ALL code paths:
     - Destination == self (loopback to local sockets)
     - Destination == ADDR_BROADCAST (broadcast handling)
     - Destination == neighbor (direct forwarding)
     - Destination == remote (multi-hop routing)
     - Gateway routing (TOR_GATEWAY, CLEARNET_GATEWAY)

2. **Broadcast Deduplication**
   - [ ] Lines 716-720: computeBroadcastId() implementation
   - [ ] Document seenBroadcasts cache structure (ConcurrentHashMap<String, Long>)
   - [ ] Verify TTL (60 seconds observed)

3. **MESH_ROUTER Role Check**
   - [ ] Lines 795-808: Read EXACT if/else logic for role check
   - [ ] Document CURRENT forwarding logic:
     ```kotlin
     if (meshRoles.contains(MeshRole.MESH_ROUTER)) {
         // Forward to neighbors
     } else {
         logger(Log.VERBOSE, "not MESH_ROUTER, not forwarding")
     }
     ```
   - [ ] Identify originatingMessageManager.neighbors() call for forwarding
   - [ ] Verify filtering logic for neighbor selection

4. **Packet Transmission to Neighbors**
   - [ ] Search for actual packet send mechanism in route()
   - [ ] Document UDP datagram creation for neighbor forwarding
   - [ ] Verify real transport address lookup for neighbors

**Required Documentation:**

- Flowchart: route() decision tree (ALL paths, not just broadcast)
- Architecture diagram: Packet flow from sender → route() → neighbor(s) → recipient
- **Code snippets:** Include ENTIRE route() method with line numbers (868-723=145 lines)
- Issue analysis: Why MESH_ROUTER gate exists, why it fails for MESH_HUB scenario

#### 1.5 Broadcast System

**Required Research (verify EVERY method):**

1. **BroadcastMessageHandler.sendBroadcast()**
   - [ ] Location: Lines 64-200 in BroadcastMessageHandler.kt
   - [ ] Read ACTUAL implementation
   - [ ] Document chunking logic (5335 chunks for 5MB file observed)
   - [ ] Verify BroadcastPacketSerializer usage
   - [ ] **CRITICAL:** Document why it calls virtualNode.route() instead of direct neighbor send
   - [ ] Identify loopback architecture: send to self, rely on route() to forward

2. **Broadcast Reception**
   - [ ] Search for broadcast chunk reception handling
   - [ ] Document reassembly logic
   - [ ] Verify file save mechanism
   - [ ] Document UI notification on completion

**Required Documentation:**

- Architecture diagram: sendBroadcast() → route(self) → MESH_ROUTER check → forward to neighbors
- Data flow: File → chunks → VirtualPacket → route() → UDP → neighbor nodes
- **Code snippets:** Include sendBroadcast() implementation with line numbers
- Design flaw analysis: Why loopback + role gate architecture is problematic

#### 1.6 Role Assignment Logic

**Required Research (verify EVERY method):**

1. **EmergentRoleManager.calculateTargetRoles()**
   - [ ] Location: Lines 229-356 in EmergentRoleManager.kt (LARGE FILE - use read_file)
   - [ ] Read ENTIRE method implementation
   - [ ] Document ALL role assignment conditions:
     - MESH_PARTICIPANT (always assigned)
     - TOR_GATEWAY (conditions?)
     - CLEARNET_GATEWAY (conditions?)
     - STORAGE_NODE (storage availability, fitness > threshold)
     - COMPUTE_NODE (CPU availability, fitness > threshold)
     - MESH_ROUTER (AP concurrency + fitness > threshold)
     - COORDINATOR (centrality score logic)

2. **Fitness Score Calculation**
   - [ ] Read calculateNormalizedFitness() implementation
   - [ ] Document factors: battery, thermal, CPU, network quality, stability
   - [ ] Verify normalization (0.0-1.0 range)

3. **MESH_ROUTER Assignment Current Logic**
   - [ ] Search for MESH_ROUTER in calculateTargetRoles()
   - [ ] Document EXACT conditions for MESH_ROUTER assignment:
     - Requires concurrentApStationSupported == true
     - Requires fitness > threshold (what threshold?)
     - Any other conditions?
   - [ ] **CRITICAL:** Document why this logic excludes non-concurrent hotspots

4. **Role Update Triggering**
   - [ ] Search for updateRoles() method
   - [ ] Document when role recalculation is triggered
   - [ ] Verify callback chain: calculateTargetRoles() → transitions → applyTransitionPlan()

**Required Documentation:**

- Decision tree: calculateTargetRoles() with ALL conditions for each role
- Data flow: NodeCapabilitySnapshot → fitness score → role assignments → state update
- **Code snippets:** Include calculateTargetRoles() implementation with line numbers
- Gap analysis: Where MESH_HUB role should be assigned (currently missing)

#### 1.7 Hotspot Promotion (If Exists)

**Required Research:**

1. **Search for Hotspot Promotion Logic**
   - [ ] Perform `grep_search` for "promote.*hotspot|hotspot.*promot"
   - [ ] Search for mesh healing/recovery mechanisms
   - [ ] Verify if nodes can become hotspot after initial join

2. **Document Findings:**
   - If promotion exists: Document workflow completely
   - If promotion doesn't exist: State clearly that it's not implemented

---

## PHASE 2: REFACTOR PLAN DEVELOPMENT

### Deliverable: MESH_HUB_REFACTOR_PLAN_v1.md

**MANDATORY STRUCTURE:**

#### 2.1 Solution Architecture

**Describe TWO implementation options:**

**Option A: Modify Broadcast Forwarding Logic**
- Add MESH_HUB to role check in VirtualNode.route() line ~795
- Change: `if (meshRoles.contains(MeshRole.MESH_ROUTER))`
- To: `if (meshRoles.contains(MeshRole.MESH_ROUTER) || meshRoles.contains(MeshRole.MESH_HUB))`
- Pros/Cons: Minimal change, preserves loopback architecture
- Risk assessment: Does this work for all packet types? Just broadcasts?

**Option B: Refactor Broadcast Architecture**
- Remove loopback from BroadcastMessageHandler.sendBroadcast()
- Implement direct neighbor broadcast in BroadcastMessageHandler
- Remove role gate from broadcast forwarding (all nodes forward broadcasts)
- Pros/Cons: Cleaner architecture, eliminates role dependency
- Risk assessment: Larger change surface, requires careful testing

**Recommendation:** State which option is preferred and why

#### 2.2 File-by-File Implementation Plan

**For EACH file requiring changes:**

##### 2.2.1 MeshRole.kt (Add MESH_HUB enum)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt`

**Current State Verification:**
- [ ] Perform `grep_search` for "enum class MeshRole" to find file
- [ ] Read current enum values
- [ ] Document exact location for new enum addition

**Changes Required:**

**Location:** (Insert after verifying actual line numbers)

**BEFORE:**
```kotlin
enum class MeshRole {
    MESH_PARTICIPANT,
    TOR_GATEWAY,
    CLEARNET_GATEWAY,
    STORAGE_NODE,
    COMPUTE_NODE,
    MESH_ROUTER,
    COORDINATOR
}
```

**AFTER:**
```kotlin
enum class MeshRole {
    MESH_PARTICIPANT,
    TOR_GATEWAY,
    CLEARNET_GATEWAY,
    STORAGE_NODE,
    COMPUTE_NODE,
    MESH_ROUTER,
    MESH_HUB,  // NEW: Central relay for non-concurrent hotspot nodes
    COORDINATOR
}
```

**Purpose:** Add MESH_HUB role to distinguish non-concurrent hotspots from MESH_ROUTER

##### 2.2.2 EmergentRoleManager.kt (Modify calculateTargetRoles)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

**⚠️ LARGE FILE WARNING: 1354 lines - DO NOT use replace_string_in_file**

**Current State Verification:**
- [ ] Read lines 229-356: calculateTargetRoles() implementation
- [ ] Find EXACT location where MESH_ROUTER is assigned
- [ ] Verify concurrentApStationSupported check
- [ ] Document ALL nearby context (5+ lines before/after)

**Changes Required:**

**Location:** Lines XXX-YYY (VERIFY with read_file before specifying)

**BEFORE:** (Include ACTUAL code with 5+ lines context)
```kotlin
// Example - VERIFY actual code:
if (concurrentApStationSupported && fitness > 0.6f) {
    roles.add(MeshRole.MESH_ROUTER)
}
```

**AFTER:** (Include ACTUAL code with 5+ lines context)
```kotlin
// If node has AP concurrency, assign MESH_ROUTER
if (concurrentApStationSupported && fitness > 0.6f) {
    roles.add(MeshRole.MESH_ROUTER)
}

// NEW: If node is acting as hotspot WITHOUT AP concurrency, assign MESH_HUB
// This occurs when:
// 1. Node started mesh (called setWifiHotspotEnabled)
// 2. Node was promoted to hotspot
// 3. Node does NOT have concurrent AP+Station hardware capability
// TODO: Need to detect "is currently hotspot" state from WiFi manager
if (!concurrentApStationSupported && isCurrentlyActingAsHotspot() && node.hasStableConnection()) {
    roles.add(MeshRole.MESH_HUB)
}
```

**Purpose:** Assign MESH_HUB role to non-concurrent hotspot nodes

**⚠️ UNCERTAINTY - REQUIRES RESEARCH:**

**How to detect "isCurrentlyActingAsHotspot()" state?**

Options to investigate:
1. Check VirtualNode.meshrabiyaWifiManager.getWifiState() for hotspot enabled?
2. Check LocalNodeState for hotspot flag?
3. Check MeshrabiyaWifiState for current mode?
4. Add callback from VirtualNode.setWifiHotspotEnabled() to EmergentRoleManager?

**ACTION REQUIRED:** Research all options, document findings, choose best approach

##### 2.2.3 VirtualNode.kt (Modify route() broadcast forwarding)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**⚠️ LARGE FILE WARNING: 1378 lines - DO NOT use replace_string_in_file**

**Current State Verification:**
- [ ] Read lines 723-868: Complete route() method
- [ ] Find EXACT location of MESH_ROUTER role check (lines 795-808)
- [ ] Read ALL surrounding context (10+ lines before/after)
- [ ] Verify originatingMessageManager.neighbors() call structure

**Changes Required (Option A):**

**Location:** Lines 795-808 (VERIFY exact lines with read_file)

**BEFORE:** (Include ACTUAL code with 10+ lines context)
```kotlin
// Example - VERIFY actual code with literal read_file:
val broadcastId = computeBroadcastId(packet)
if (seenBroadcasts.containsKey(broadcastId)) {
    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId already seen, not forwarding")
    return
}
seenBroadcasts[broadcastId] = System.currentTimeMillis()

if (meshRoles.contains(MeshRole.MESH_ROUTER)) {
    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors")
    originatingMessageManager.neighbors().filter { neighbor ->
        neighbor.address != packet.fromAddr
    }.forEach { neighbor ->
        // Forward packet to neighbor
    }
} else {
    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER, not forwarding")
}
```

**AFTER:** (Include ACTUAL code with 10+ lines context)
```kotlin
// Example - VERIFY actual code with literal read_file:
val broadcastId = computeBroadcastId(packet)
if (seenBroadcasts.containsKey(broadcastId)) {
    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId already seen, not forwarding")
    return
}
seenBroadcasts[broadcastId] = System.currentTimeMillis()

// UPDATED: Forward broadcasts if node is MESH_ROUTER or MESH_HUB
if (meshRoles.contains(MeshRole.MESH_ROUTER) || meshRoles.contains(MeshRole.MESH_HUB)) {
    val roleType = if (meshRoles.contains(MeshRole.MESH_ROUTER)) "MESH_ROUTER" else "MESH_HUB"
    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=$roleType)")
    originatingMessageManager.neighbors().filter { neighbor ->
        neighbor.address != packet.fromAddr
    }.forEach { neighbor ->
        // Forward packet to neighbor
    }
} else {
    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER or MESH_HUB, not forwarding")
}
```

**Purpose:** Allow MESH_HUB nodes to forward broadcasts to connected stations

**⚠️ VERIFICATION REQUIRED:**

After making this change, verify:
1. Does this affect non-broadcast packet forwarding? (Check other uses of MESH_ROUTER in route())
2. Should MESH_HUB forward ALL packet types or just broadcasts?
3. Are there other places in route() that check MESH_ROUTER role?

**ACTION REQUIRED:** Search entire route() method for all MESH_ROUTER checks, document each

##### 2.2.4 BroadcastMessageHandler.kt (Optional Refactor)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`

**Current State Verification:**
- [ ] Read lines 64-200: sendBroadcast() implementation
- [ ] Verify virtualNode.route() call at line ~153
- [ ] Document why loopback architecture exists

**Changes Required (Option B - if choosing to refactor):**

**Location:** Lines XXX-YYY (VERIFY with read_file)

**BEFORE:** (Include ACTUAL code)
```kotlin
// Example - VERIFY actual code:
chunks.forEach { chunk ->
    val packet = VirtualPacket(
        fromAddr = virtualNode.addressAsInt,
        toAddr = ADDR_BROADCAST,
        data = chunk
    )
    virtualNode.route(packet)  // Loopback architecture
}
```

**AFTER:** (Include ACTUAL code)
```kotlin
// REFACTORED: Direct neighbor broadcast, no loopback
chunks.forEach { chunk ->
    val packet = VirtualPacket(
        fromAddr = virtualNode.addressAsInt,
        toAddr = ADDR_BROADCAST,
        data = chunk
    )
    
    // Send directly to all neighbors, no role check required
    virtualNode.originatingMessageManager.neighbors().forEach { neighbor ->
        val datagram = DatagramPacket(
            packet.toByteArray(),
            packet.size,
            neighbor.realTransportAddress
        )
        virtualNode.datagramSocket.send(datagram)
    }
}
```

**Purpose:** Eliminate role dependency from broadcast forwarding

**⚠️ DECISION REQUIRED:**

- Option A (minimal change): Add MESH_HUB to role check in route()
- Option B (architectural refactor): Remove loopback, implement direct broadcast

**Recommendation:** Start with Option A for faster fix, consider Option B as future improvement

#### 2.3 Testing Strategy

**Required Test Cases:**

1. **MESH_HUB Role Assignment Test**
   - Start mesh on Phone 1 (non-concurrent device)
   - Verify logs show: "Current mesh roles AFTER updateRoles(): [MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_HUB]"
   - Verify MESH_HUB role assigned, NOT MESH_ROUTER

2. **Broadcast Forwarding Test**
   - Phone 1 (MESH_HUB) connected to Phone 2 (station)
   - Send broadcast from Phone 1 with file attachment
   - Verify Phone 1 logs: "forwarding to neighbors (role=MESH_HUB)"
   - Verify Phone 2 logs: "Received broadcast chunk 0/XXX"
   - Verify Phone 2 saves file and shows notification

3. **Station-to-Station Communication Test**
   - Phone 1 (MESH_HUB) with Phone 2 and Phone 3 as stations
   - Send broadcast from Phone 2
   - Verify Phone 2 → MESH_HUB → Phone 3 relay works
   - Verify Phone 3 receives broadcast

4. **MESH_ROUTER Not Affected Test**
   - Device WITH AP concurrency enabled
   - Verify still gets MESH_ROUTER role (not MESH_HUB)
   - Verify broadcast forwarding still works

**Test Verification:**
- Capture full logcat with `adb logcat -v time *:V`
- Search for role assignment logs
- Search for broadcast forwarding logs
- Verify file received and saved on recipient

#### 2.4 Uncertainties and Research Questions

**Document ALL unknowns that require additional investigation:**

1. **Hotspot State Detection**
   - Q: How to detect "isCurrentlyActingAsHotspot()" in EmergentRoleManager?
   - Options to research: [list all possibilities]
   - Required verification: [specific methods to check]

2. **MESH_HUB Role Removal**
   - Q: When should MESH_HUB role be removed?
   - If hotspot disabled?
   - If AP concurrency becomes available?
   - If promoted to MESH_ROUTER?

3. **Non-Broadcast Traffic**
   - Q: Should MESH_HUB forward ALL packet types or just broadcasts?
   - Current code checks MESH_ROUTER in multiple places
   - Need to verify each check individually

4. **Hotspot Promotion**
   - Q: Does hotspot promotion exist?
   - If yes: Does MESH_HUB need to be assigned during promotion?
   - If no: Document that MESH_HUB only assigned during initial startMesh()

5. **Multi-Role Scenarios**
   - Q: Can node have both MESH_HUB and other roles?
   - Test: MESH_HUB + STORAGE_NODE + COMPUTE_NODE (observed in logs)
   - Verify: No conflicts with role combinations

**For EACH uncertainty:**
- List specific files/methods to investigate
- List specific search patterns to use
- Document decision criteria
- State how to verify the answer

#### 2.5 Rollback Plan

**If implementation causes issues:**

1. Revert MeshRole.kt (remove MESH_HUB enum)
2. Revert EmergentRoleManager.kt (remove MESH_HUB assignment)
3. Revert VirtualNode.kt (restore MESH_ROUTER-only check)
4. Verify existing functionality preserved

**Monitoring After Deployment:**
- Monitor logs for "MESH_HUB" role assignment
- Monitor broadcast forwarding success rate
- Monitor for any new compilation errors
- Monitor for mesh connectivity issues

---

## EXECUTION REQUIREMENTS

### Pre-Implementation Checklist

**Before writing ANY code, complete this checklist:**

- [ ] Read ENTIRE VirtualNode.route() method (lines 723-868)
- [ ] Read ENTIRE EmergentRoleManager.calculateTargetRoles() method (lines 229-356)
- [ ] Verified signature of originatingMessageManager.neighbors()
- [ ] Verified signature of virtualNode.meshrabiyaWifiManager methods
- [ ] Found MeshRole enum definition and exact location for new value
- [ ] Documented ALL places where MESH_ROUTER role is checked
- [ ] Searched for "isCurrentlyActingAsHotspot" or equivalent state check
- [ ] Verified NodeTopologyInfo structure and fields
- [ ] Verified LocalNodeState structure and fields
- [ ] Documented discrepancies between this prompt and actual code

**If ANY checkbox is unchecked, DO NOT write implementation code yet.**

### Verification Process

**For EVERY method, property, class referenced in the plan:**

1. Search codebase: `grep_search` for definition
2. Read actual code: `read_file` with exact line numbers
3. Document signature: Parameters, return type, modifiers
4. Check for suspend: Is it a suspend function?
5. Verify existence: Confirm it's not commented out or deprecated
6. List call sites: Use `list_code_usages` if needed

### Documentation Standards

**CANONICAL_MESH_NETWORK_WORKFLOWS_v1.md Requirements:**

- Minimum 50 pages (equivalent) of detailed analysis
- ALL workflows documented with sequence diagrams
- ALL method signatures included with exact parameters
- ALL data structures documented with field types
- ALL code snippets include line numbers from actual files
- ALL assumptions stated and verified with evidence
- ALL uncertainties clearly marked and researched

**MESH_HUB_REFACTOR_PLAN_v1.md Requirements:**

- BEFORE/AFTER code snippets for ALL changes
- Line numbers for ALL edits (LARGE FILE RULE compliance)
- Exact file paths (absolute) for ALL modified files
- Complete verification checklist for each change
- Test cases with expected log output
- ALL uncertainties documented with research questions
- Decision criteria for architecture choices

### Success Criteria

**Analysis Phase Complete When:**

- [ ] All 7 workflow sections documented in CANONICAL_MESH_NETWORK_WORKFLOWS_v1.md
- [ ] All method signatures verified with grep_search + read_file
- [ ] All data structures documented with actual field names
- [ ] All code snippets include line numbers and file paths
- [ ] All assumptions backed by codebase evidence
- [ ] All discrepancies between prompt and reality documented

**Planning Phase Complete When:**

- [ ] Both Option A and Option B architectures fully described
- [ ] All file changes documented with BEFORE/AFTER snippets
- [ ] All uncertainties listed with research questions
- [ ] All test cases specified with expected behavior
- [ ] Rollback plan documented
- [ ] Pre-implementation checklist 100% complete

### Deliverable Format

**CANONICAL_MESH_NETWORK_WORKFLOWS_v1.md:**

```markdown
# Canonical Mesh Network Workflows v1

## Executive Summary
[Overview of mesh networking system]

## 1. Mesh Initialization and Start
### 1.1 MeshrabiyaApiImpl.initMesh()
**File:** `/path/to/MeshrabiyaApiImpl.kt`
**Lines:** XXX-YYY
**Signature:** 
```kotlin
suspend fun initMesh(...): Result<Unit>
```
[Complete implementation analysis]

[Continue for all sections...]
```

**MESH_HUB_REFACTOR_PLAN_v1.md:**

```markdown
# MESH_HUB Role Implementation - Refactor Plan v1

## Executive Summary
[Solution overview and recommendation]

## Architecture Decision: Option A vs Option B
[Detailed comparison]

## Implementation Changes

### Change 1: MeshRole.kt
**File:** `/Users/dreadstar/workspace/orbot-android/.../MeshRole.kt`
**Location:** Lines XXX-YYY

**BEFORE:**
```kotlin
[actual code with context]
```

**AFTER:**
```kotlin
[actual code with changes and context]
```

**Purpose:** [explanation]

[Continue for all changes...]

## Uncertainties
1. [Question with research steps]
2. [Question with research steps]

## Testing Strategy
[Detailed test cases]
```

---

## AGENT INSTRUCTIONS SUMMARY

1. **Start with research, not implementation**
2. **Use grep_search + read_file to verify EVERY method**
3. **Never assume - always verify with actual code**
4. **Follow LARGE FILE RULE for VirtualNode.kt and EmergentRoleManager.kt**
5. **Document discrepancies between this prompt and reality**
6. **Complete both deliverables before requesting code review**
7. **Mark ALL uncertainties clearly**
8. **Provide BEFORE/AFTER code snippets with line numbers**

**This task is complete when:**
- CANONICAL_MESH_NETWORK_WORKFLOWS_v1.md exists with complete analysis
- MESH_HUB_REFACTOR_PLAN_v1.md exists with complete implementation plan
- All verification checklists are 100% complete
- All uncertainties are documented with research questions
- User approves the plan before any code changes are made

---

**END OF TASK PROMPT**
