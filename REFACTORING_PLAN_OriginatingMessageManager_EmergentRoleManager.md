# Refactoring Plan: Break Circular Dependency Between EmergentRoleManager and OriginatingMessageManager

**Date**: November 16, 2025  
**Issue**: Circular dependency where OriginatingMessageManager tries to call EmergentRoleManager.getInstance().calculateCentralityScore(), while EmergentRoleManager needs topology data from OriginatingMessageManager.

---

## Current Problematic Architecture

```
OriginatingMessageManager
    ↓ (calls)
EmergentRoleManager.getInstance().calculateCentralityScore()
    ↓ (accesses)
virtualNode.getOriginatingMessageManager().getTopologyMap()
    ↑ (creates circular dependency)
```

**Problems**:
1. OriginatingMessageManager imports EmergentRoleManager just to call `calculateCentralityScore()`
2. EmergentRoleManager accesses OriginatingMessageManager to get topology data
3. This creates a compile-time circular dependency
4. Violates the principle that EmergentRoleManager should be a **consumer** of OriginatingMessageManager, not the other way around

---

## Proposed Solution: Inversion of Control via Callback Pattern

### Core Principle
**OriginatingMessageManager should be a data provider, not a consumer of EmergentRoleManager.**

### Refactored Architecture

```
OriginatingMessageManager
    ↓ (provides topology data)
VirtualNode (mediator/coordinator)
    ↓ (passes topology to)
EmergentRoleManager
    ↓ (calculates centrality internally)
    ↓ (returns score)
VirtualNode
    ↓ (passes score back to)
OriginatingMessageManager (uses score in messages)
```

---

## Step-by-Step Refactoring Plan

### Phase 1: Remove Direct Dependency (IMMEDIATE)

**File**: `OriginatingMessageManager.kt`

**Current Code (Lines 109-116)**:
```kotlin
val centralityScore = try {
    EmergentRoleManager.getInstance().calculateCentralityScore()
} catch (e: Exception) {
    0f
}
```

**Refactor To**:
```kotlin
// Use callback provided via constructor to get centrality score
val centralityScore = getCentralityScore?.invoke() ?: 0f
```

**Constructor Changes**:
```kotlin
class OriginatingMessageManager(
    private val localNodeInetAddr: InetAddress,
    private val logger: MNetLogger,
    // ... existing parameters ...
    private val getCentralityScore: (() -> Float)? = null,  // NEW: Callback to get centrality
    private val betaLogger: BetaTestLogger? = null
) {
```

**Occurrences to Update**:
1. Line 111 in `sendOriginatingMessageRunnable`
2. Line 266 in `makeOriginatingMessage()`
3. Line 374 in `onReceiveOriginatingMessage()`

---

### Phase 2: Update VirtualNode to Wire Dependencies (COORDINATION)

**File**: `VirtualNode.kt` (or `AndroidVirtualNode.kt`)

**Current Creation** (approximate):
```kotlin
originatingMessageManager = OriginatingMessageManager(
    localNodeInetAddr = address,
    logger = logger,
    // ... other params ...
)
```

**Refactor To**:
```kotlin
// Create EmergentRoleManager first
emergentRoleManager = EmergentRoleManager(
    context = context,
    virtualNode = this,
    // ... other params ...
)

// Create OriginatingMessageManager with callback
originatingMessageManager = OriginatingMessageManager(
    localNodeInetAddr = address,
    logger = logger,
    // ... other params ...
    getCentralityScore = { emergentRoleManager.calculateCentralityScore() },  // Callback
    betaLogger = betaLogger
)
```

**Key Point**: VirtualNode becomes the mediator that:
1. Creates both managers
2. Provides EmergentRoleManager's calculation method as a callback to OriginatingMessageManager
3. Maintains proper dependency flow

---

### Phase 3: Clean Up EmergentRoleManager Access Pattern (CLEANUP)

**File**: `EmergentRoleManager.kt`

**Current Pattern** (Lines 511-514):
```kotlin
val topologyMap: Map<Int, Set<Int>> = (virtualNode as VirtualNode)
    .getOriginatingMessageManager()
    .getTopologyMap()
```

**Keep As-Is**: This is correct! EmergentRoleManager is a **consumer** of OriginatingMessageManager data. This direction is fine.

**Ensure**:
- EmergentRoleManager never calls back into OriginatingMessageManager except to read data
- No circular method calls

---

### Phase 4: Alternative Pattern - Event-Based (OPTIONAL FUTURE ENHANCEMENT)

If callback pattern becomes complex, consider event-based:

```kotlin
// In OriginatingMessageManager
interface TopologyUpdateListener {
    fun onTopologyChanged(topologyMap: Map<Int, Set<Int>>)
}

private var topologyListener: TopologyUpdateListener? = null

fun setTopologyUpdateListener(listener: TopologyUpdateListener) {
    topologyListener = listener
}

// Notify when topology changes
private fun notifyTopologyUpdate() {
    topologyListener?.onTopologyChanged(topologyMap)
}
```

**Usage**:
```kotlin
// In VirtualNode
originatingMessageManager.setTopologyUpdateListener(emergentRoleManager)
```

---

## Implementation Priority

### CRITICAL (Do First)
1. ✅ Comment out `MmcpNodeAnnouncement` and `MmcpMessageFactory` imports (DONE)
2. ✅ Fix `MeshRole` import to use `vnet` package (DONE)
3. **Add `getCentralityScore` callback parameter to OriginatingMessageManager constructor**
4. **Replace all 3 `EmergentRoleManager.getInstance()` calls with callback invocations**

### HIGH (Do Next)
5. **Update VirtualNode/AndroidVirtualNode to wire the callback**
6. **Test compilation**
7. **Verify EmergentRoleManager can still access topology via `getOriginatingMessageManager()`**

### MEDIUM (Future Cleanup)
8. Remove `EmergentRoleManager.getInstance()` singleton pattern if no longer needed
9. Consider converting to event-based pattern if callback becomes unwieldy
10. Add unit tests to verify no circular initialization issues

---

## Benefits of This Refactoring

1. **Breaks Circular Dependency**: OriginatingMessageManager no longer imports EmergentRoleManager
2. **Clear Data Flow**: OriginatingMessageManager → VirtualNode → EmergentRoleManager → (callback) → OriginatingMessageManager
3. **Proper Separation**: EmergentRoleManager remains a consumer of topology data
4. **Testable**: Callbacks can be mocked for unit testing
5. **Flexible**: Can swap out centrality calculation logic without modifying OriginatingMessageManager

---

## Testing Strategy

### Unit Tests Needed:
```kotlin
@Test
fun testOriginatingMessageManager_withCentralityCallback() {
    var callbackInvoked = false
    val manager = OriginatingMessageManager(
        // ... params ...
        getCentralityScore = { 
            callbackInvoked = true
            0.75f 
        }
    )
    // Trigger message sending
    // Assert callback was invoked
    assertTrue(callbackInvoked)
}

@Test
fun testOriginatingMessageManager_withoutCentralityCallback() {
    val manager = OriginatingMessageManager(
        // ... params ...
        getCentralityScore = null  // No callback
    )
    // Trigger message sending
    // Should default to 0f without error
}
```

### Integration Tests Needed:
- Verify EmergentRoleManager can calculate centrality from OriginatingMessageManager's topology
- Verify OriginatingMessageManager receives centrality score via callback
- Verify no circular initialization issues during VirtualNode creation

---

## Migration Path (for existing code)

**Current instantiation sites to update**:
1. Search for `OriginatingMessageManager(` constructor calls
2. Add `getCentralityScore = { emergentRoleManager.calculateCentralityScore() }` parameter
3. Ensure `emergentRoleManager` is initialized before `originatingMessageManager`

**Backward Compatibility**:
- Make `getCentralityScore` optional (nullable with default null)
- Default behavior returns 0f if callback not provided
- Allows gradual migration

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Null callback during message sending | Medium | Low | Default to 0f, log warning |
| Initialization order issues | Medium | High | Document required order in VirtualNode |
| Callback performance overhead | Low | Low | Callback is only invoked during message creation (infrequent) |
| Breaking existing tests | High | Medium | Update all test fixtures |

---

## Success Criteria

- ✅ OriginatingMessageManager compiles without importing EmergentRoleManager
- ✅ EmergentRoleManager can access topology data from OriginatingMessageManager
- ✅ Centrality scores are calculated and included in messages
- ✅ No circular dependencies detected by dependency analysis tools
- ✅ All unit tests pass
- ✅ Integration tests verify end-to-end flow

---

## Code Review Checklist

- [ ] OriginatingMessageManager constructor has `getCentralityScore` callback parameter
- [ ] All 3 instances of `EmergentRoleManager.getInstance()` replaced with callback
- [ ] VirtualNode initialization order: EmergentRoleManager → OriginatingMessageManager
- [ ] Callback wired correctly in VirtualNode
- [ ] Null callback handling tested
- [ ] Documentation updated to reflect new architecture
- [ ] No new circular dependencies introduced

---

## Next Steps

1. **Review this plan** with team
2. **Implement Phase 1** (add callback parameter, replace getInstance() calls)
3. **Implement Phase 2** (update VirtualNode wiring)
4. **Test compilation** and fix any cascading errors
5. **Run integration tests** to verify behavior
6. **Document** the new pattern in architecture docs
7. **Update KNOWLEDGE docs** with this refactoring decision

---

**End of Refactoring Plan**
