# KNOWLEDGE - November 17, 2025

## Session Overview

**Date**: November 17, 2025  
**Focus**: Gateway Routing Implementation - Phase 3 Complete, Phase 4 Planning & Start  
**Status**: 🚧 In Progress - Documentation Complete, Implementation Starting

---

## Major Accomplishments

### 1. Gateway Routing Implementation Plan Created ✅

**Objective**: Design comprehensive distributed gateway routing using MeshRole information

**Plan Deliverables**:
- ✅ 600+ line implementation plan document created
- ✅ Architecture diagrams (current state → target state)
- ✅ 4 new component designs with full code examples
- ✅ 5-phase implementation sequence (10 days)
- ✅ 6 open questions identified for user input
- ✅ Success metrics defined

**File Created**:
- `GATEWAY_ROUTING_IMPLEMENTATION_PLAN.md`

---

### 2. Key Design Clarifications Integrated ✅

**User Feedback Incorporated**:
1. ✅ **Role Categorization Clarified**:
   - Gateway Roles: TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY (used for routing)
   - Intelligence Roles: STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER (stored for future optimization)
   - ALL 7 roles broadcast in MmcpOriginatorMessage, but only 3 used for gateway routing

2. ✅ **Topology Integration Strategy**:
   - Enhance EXISTING `topologyMap` in OriginatingMessageManager (don't create new)
   - Change type: `Map<Int, Set<Int>>` → `Map<Int, NodeTopologyInfo>`
   - Store ALL roles (gateway + intelligence) for future use

3. ✅ **VirtualNode Integration Split**:
   - CLIENT NODE behavior: Select gateway from topology → route packet TO gateway node
   - GATEWAY NODE behavior: Receive packet → route THROUGH configured proxy
   - Integrate with existing `routeViaProxy()` method (line ~832)

---

## Architectural Design

### Phase 3 Status: Role Broadcasting ✅ COMPLETE

**Already Implemented**:
```kotlin
MmcpOriginatorMessage ✅
  ├─> meshRoles: Set<MeshRole> (ALL 7 types serialized)
  ├─> centralityScore: Float
  ├─> fitnessScore: Float
  └─> neighbors: List<Int>

OriginatingMessageManager ✅
  ├─> makeOriginatingMessage() - includes meshRoles
  ├─> onReceiveOriginatingMessage() - receives roles
  └─> Callbacks: getCentralityScore(), getMeshRoles(), getFitnessScore()

EmergentRoleManager ✅
  └─> currentMeshRoles: StateFlow<Set<MeshRole>>
```

### Phase 4 Design: Gateway Routing (Starting Today)

**New Components to Implement**:

1. **NodeTopologyInfo.kt** (NEW)
   - Stores: neighbors, meshRoles (ALL 7), fitness, centrality, pingTime
   - Methods: `hasRole()`, `isGatewayNode()`, `calculateGatewaySuitability()`
   - Purpose: Enhanced topology data structure

2. **GatewaySelectionResult.kt** (NEW)
   - Sealed class: SingleGateway, MultipleGateways, NoGatewayAvailable, GatewayDisabledByUser
   - Data class: GatewayNode (address, suitability, hopCount, weight)
   - Enum: DistributionStrategy (ROUND_ROBIN, WEIGHTED, LATENCY_AWARE, FAILOVER)

3. **GatewaySelector.kt** (NEW)
   - `selectGateway(type)`: Single best gateway selection
   - `selectMultipleGateways(type, count)`: Multi-gateway selection for multiplexing
   - Validation: Only accepts TOR/CLEARNET/I2P gateway roles
   - Ranking: 0.3*centrality + 0.4*fitness + 0.3*latency

4. **GatewayRouter.kt** (NEW)
   - `routeToGateway(packet, type)`: Main routing method
   - CLIENT behavior: Route packet TO selected gateway node
   - GATEWAY behavior: Route packet THROUGH proxy (Tor/I2P/etc)
   - Multiplexing: Round-robin across N gateways (30s cache)

**Enhanced Components**:

1. **OriginatingMessageManager.kt** (ENHANCE EXISTING)
   - Change `topologyMap` type: `Map<Int, Set<Int>>` → `Map<Int, NodeTopologyInfo>`
   - Add `getNodesWithRole(role)` method
   - Add `getGatewayNodes()` method
   - Update `onReceiveOriginatingMessage()` to populate NodeTopologyInfo

2. **VirtualNode.kt** (ENHANCE EXISTING)
   - Add `gatewaySelector` field (lazy init)
   - Add `gatewayRouter` field (lazy init)
   - Add `isGatewayNode(type)` method
   - Add `routeThroughGateway(packet)` method
   - Add `determineGatewayType(packet)` method
   - Enhance `routeViaProxy(packet)` to return Boolean

---

## Implementation Sequence

### Phase 1: Data Structures & Topology Integration (Days 1-2)
**Status**: Starting after documentation update

1. Create `NodeTopologyInfo.kt`
2. Create `GatewaySelectionResult.kt`
3. Update `OriginatingMessageManager.kt` topology map
4. Update `onReceiveOriginatingMessage()` to store roles

### Phase 2: Gateway Selection (Days 3-4)
**Status**: Pending Phase 1 completion

1. Create `GatewaySelector.kt`
2. Implement gateway validation (TOR/CLEARNET/I2P only)
3. Implement suitability ranking
4. Integrate with EmergentRoleManager preferences

### Phase 3: CLIENT Node Routing (Days 5-6)
**Status**: Pending Phase 2 completion

1. Create `GatewayRouter.kt`
2. Implement `routeToGateway()` CLIENT behavior
3. Implement multiplexing (round-robin)
4. Add gateway pool caching

### Phase 4: GATEWAY Node Routing (Days 7-8)
**Status**: Pending Phase 3 completion

1. Implement GATEWAY behavior in `routeToGateway()`
2. Enhance `routeViaProxy()` method
3. End-to-end flow: CLIENT → GATEWAY → PROXY

### Phase 5: Traffic Classification & Testing (Days 9-10)
**Status**: Pending Phase 4 completion

1. Implement `determineGatewayType()` logic
2. E2E tests (10-node mesh)
3. Performance testing
4. Role isolation tests

---

## Open Questions (Requiring User Input)

### 1. Topology Map Type Change Impact ⚠️ CRITICAL
**Question**: What components depend on `getTopologyMap()` returning `Map<Int, Set<Int>>`?

**Action Required**:
- Grep for all usages of `getTopologyMap()` and `topologyMap`
- Verify EmergentRoleManager centrality calculation still works
- Update any dependent tests

### 2. Official Repository Alignment
**Question**: Does official UstadMobile/Meshrabiya repo have gateway routing?

**Action Required**:
- Review official VirtualNode.kt routing logic
- Review official OriginatingMessageManager.kt topology usage
- Align approach with upstream design

### 3. Traffic Classification Strategy (Phase 5)
**Options**:
- A. Destination-based (.onion → TOR, .i2p → I2P, else CLEARNET)
- B. Port-based (443 → CLEARNET, 9150 → TOR, 7657 → I2P)
- C. Explicit tagging (application layer specifies)

**Recommendation**: Start with Option C (explicit), add A+B in Phase 5

### 4. Multiplexing Algorithm
**Options**: Round-robin (MVP), Weighted, Latency-aware, Failover

**Recommendation**: Implement round-robin first, configurable later

### 5. Gateway Pool Refresh Rate
**Options**: 10s (fast), 30s (recommended), 60s (slow)

**Current**: 30s in plan

### 6. Proxy Configuration
**Question**: How are `proxyHost` and `proxyPort` configured on gateway nodes?

**Clarification Needed**: Auto-detect or user-configured?

---

## TODOs (Prioritized)

### Immediate (Today - 11/17/2025)

1. ✅ **Update Documentation**
   - ✅ Update INTERIM_COMMIT_LOG.md with progress
   - ✅ Create KNOWLEDGE-11172025.md
   - 🚧 Update REFACTORING_PLAN_COMPREHENSIVE_v2.md status

2. 🚧 **Complete Refactoring Plan Part 4**
   - Implement test cases from REFACTORING_PLAN_COMPREHENSIVE_v2.md Part 4
   - Do NOT compile yet (as per user request)

3. 🚧 **Implement Gateway Routing Phase 1**
   - Create NodeTopologyInfo.kt
   - Create GatewaySelectionResult.kt
   - Update OriginatingMessageManager topology map
   - Update onReceiveOriginatingMessage()
   - Do NOT compile until all Phase 1 complete

### Next Session

4. **Pre-Implementation Verification**
   - Grep for `getTopologyMap()` usages
   - Verify EmergentRoleManager compatibility
   - Check for test dependencies

5. **Phase 1 Compilation & Testing**
   - Compile Phase 1 changes
   - Run unit tests for NodeTopologyInfo
   - Verify topology map storage

6. **Phase 2 Implementation**
   - Create GatewaySelector.kt
   - Implement gateway validation
   - Write unit tests

---

## Key Design Rules

### Rule 1: Role Isolation
- ✅ Gateway roles (TOR/CLEARNET/I2P): Used for routing
- ✅ Intelligence roles (STORAGE/COMPUTE/MESH_ROUTER): Stored, NOT used for routing
- ✅ All 7 roles: Broadcast and stored for future use

### Rule 2: Topology Integration
- ✅ Enhance EXISTING topologyMap (don't create new)
- ✅ Store ALL roles in NodeTopologyInfo
- ⚠️ Verify no breaking changes to dependent components

### Rule 3: CLIENT vs GATEWAY Behavior
- ✅ CLIENT: Select gateway → route packet TO gateway node
- ✅ GATEWAY: Receive packet → route packet THROUGH proxy
- ✅ Use `isGatewayNode(type)` to determine behavior

### Rule 4: Incremental Implementation
- ✅ Implement all code for each phase BEFORE compiling
- ✅ Test each phase independently
- ✅ No partial implementations

---

## Compilation Status

**Current Baseline**: 784 errors (all pre-existing)
- Refactored files (Phase 3): 0 errors ✅
- Service layer files: 784 errors (IntelligentDistributedComputeService, TaskManager, etc.)

**Phase 4 Target**: Keep 0 errors in gateway routing files

---

## File Locations

### New Files (To Be Created)
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/NodeTopologyInfo.kt`
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewaySelectionResult.kt`
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewaySelector.kt`
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/GatewayRouter.kt`

### Files to Enhance
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

### Reference Files
- `GATEWAY_ROUTING_IMPLEMENTATION_PLAN.md` (master plan)
- `REFACTORING_PLAN_COMPREHENSIVE_v2.md` (Phase 3 refactoring)
- `KNOWLEDGE-11162025.md` (previous session)

---

**END OF KNOWLEDGE-11172025.md**
