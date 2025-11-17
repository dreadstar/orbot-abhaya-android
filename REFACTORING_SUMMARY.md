# Refactoring Summary: MmcpOriginatorMessage + Topology Integration

**Date:** November 16, 2025  
**Status:** Ready for Implementation  
**Full Plan:** See `REFACTORING_PLAN_COMPREHENSIVE_v2.md`

---

## What We're Doing

**Properly define `MmcpOriginatorMessage`** (currently only used inline) with topology/centrality enhancements to enable EmergentRoleManager BFS centrality calculations.

---

## Key Changes

### 1. Create MmcpOriginatorMessage Class

**New File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/MmcpOriginatorMessage.kt`

```kotlin
class MmcpOriginatorMessage(
    messageId: Int,
    
    // OFFICIAL FIELDS (from canonical design)
    val sentTime: Long,
    val pingTimeSum: Short = 0,
    val connectConfig: Any? = null,
    
    // ENHANCED FIELDS (for topology/centrality)
    val neighbors: List<Int> = emptyList(),     // For topology building
    val centralityScore: Float = 0f,            // From EmergentRoleManager
    val fitnessScore: Float = 0f,               // Node capability (0.0-1.0)
    val meshRoles: Set<MeshRole> = emptySet(),  // Current roles
) : MmcpMessage(WHAT_ORIGINATOR, messageId)
```

### 2. Break Circular Dependency (Callback Pattern)

**Current Problem:**
```
OriginatingMessageManager → EmergentRoleManager.getInstance() ❌ CIRCULAR
```

**Solution:**
```
VirtualNode (mediator)
  ├─> OriginatingMessageManager(getCentralityScore callback)
  └─> EmergentRoleManager(getTopologyMap callback)
```

### 3. Update OriginatingMessageManager Constructor

```kotlin
class OriginatingMessageManager(
    // ... existing params ...
    
    // NEW: Callbacks to break circular dependency
    private val getCentralityScore: (() -> Float)? = null,
    private val getMeshRoles: (() -> Set<MeshRole>)? = null,
    private val getFitnessScore: (() -> Float)? = null,
)
```

### 4. Build Topology Map from Received Messages

```kotlin
// In onReceiveOriginatingMessage():
if (mmcpMessage.neighbors.isNotEmpty()) {
    topologyMap[virtualPacket.header.fromAddr] = mmcpMessage.neighbors.toSet()
}
```

### 5. Wire Callbacks in VirtualNode

```kotlin
// Create EmergentRoleManager with topology callback
protected val emergentRoleManager: EmergentRoleManager by lazy {
    EmergentRoleManager(
        virtualNode = this,
        getTopologyMap = { originatingMessageManager.getTopologyMap() },
        // ...
    )
}

// Create OriginatingMessageManager with EmergentRoleManager callbacks
protected val originatingMessageManager = OriginatingMessageManager(
    // ... existing params ...
    getCentralityScore = { emergentRoleManager.calculateCentralityScore() },
    getMeshRoles = { emergentRoleManager.currentMeshRoles.value },
    getFitnessScore = { emergentRoleManager.calculateNormalizedFitness(...) },
)
```

---

## Files to Modify

| File | Change Type | Lines |
|------|-------------|-------|
| `MmcpOriginatorMessage.kt` | **CREATE NEW** | ~120 |
| `OriginatingMessageManager.kt` | Modify constructor, methods | ~80 |
| `VirtualNode.kt` | Wire callbacks | ~20 |
| `EmergentRoleManager.kt` | Update constructor | ~15 |
| `MmcpMessage.kt` | Add fromBytes case | ~1 |

**Total Impact:** ~235 lines (120 new, 115 modified)

---

## Implementation Order

1. ✅ Create `MmcpOriginatorMessage.kt` class
2. ✅ Update `MmcpMessage.kt` to handle `WHAT_ORIGINATOR`
3. ✅ Add callbacks to `OriginatingMessageManager` constructor
4. ✅ Replace `makeOriginatingMessage()` - use callbacks, add neighbor list
5. ✅ Update `sendOriginatingMessageRunnable` - remove direct EmergentRoleManager calls
6. ✅ Update `onReceiveOriginatingMessage()` - build topology map, change type from `MmcpNodeAnnouncement` to `MmcpOriginatorMessage`
7. ✅ Update `VirtualNode.LastOriginatorMessage` type
8. ✅ Update `OriginatingMessageState` type
9. ✅ Wire callbacks in `VirtualNode.kt`
10. ✅ Update `EmergentRoleManager` constructor to use `getTopologyMap` callback

---

## Why This Approach?

1. **Uses Official Design**: `MmcpOriginatorMessage` is from canonical implementation, NOT deprecated `MmcpNodeAnnouncement`
2. **Breaks Circular Dependency**: Callbacks ensure EmergentRoleManager is pure consumer
3. **Enables Topology Building**: `neighbors` field allows distributed topology map construction
4. **Preserves Core Functionality**: All official OriginatingMessageManager behavior intact
5. **Clean Architecture**: Single responsibility, proper separation of concerns

---

## Testing Checklist

- [ ] Unit test: `MmcpOriginatorMessage` serialization/deserialization
- [ ] Unit test: `makeOriginatingMessage()` uses callbacks correctly
- [ ] Unit test: Topology map populated from received `neighbors` lists
- [ ] Unit test: EmergentRoleManager uses `getTopologyMap()` callback
- [ ] Integration test: 3-node mesh builds topology maps correctly
- [ ] Integration test: Centrality scores propagate across mesh
- [ ] Compile test: Zero circular dependency errors

---

## Success Criteria

✅ `OriginatingMessageManager.kt` compiles with zero errors  
✅ `EmergentRoleManager.kt` compiles with zero errors  
✅ No circular dependency warnings  
✅ Topology map populates from received messages  
✅ Centrality scores calculated using topology  
✅ All official OriginatingMessageManager functionality preserved  

---

**Next Step:** Implement Phase 1 - Create `MmcpOriginatorMessage.kt`
