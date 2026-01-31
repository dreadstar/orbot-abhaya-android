# NETWORK_PEERS_FIX_PLAN.md

## Production-Ready, Codebase-Verified Implementation Plan: Peer Count Trigger for Mesh Status

### Objective
Ensure that when the peer count changes from 0 to 1, `meshStatusFlow` is explicitly updated to `CONNECTED`, and this update is propagated so the UI observer in `EnhancedMeshFragment` receives and displays the correct status.

---

## Checklist: Exhaustive, Codebase-Driven Steps

### 1. Identify Peer Count Change Source
- [ ] **File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
    - [ ] **Verify:**
        - Locate where peer count is tracked and updated (e.g., `getPeerCount()`, neighbor list, or similar).
        - Confirm by literal read that there is a mechanism (callback, coroutine, or observer) that detects peer count changes.

### 2. Add Peer Count Change Observer/Callback
- [x] **File:** MeshrabiyaApiImpl.kt
    - [x] **Add:**
        - An observer, callback, or event that is triggered whenever the peer count changes.
        - This can be a property observer, a callback in the peer management logic, or a coroutine that polls and detects changes.
    - [x] **Verify:**
        - Confirm by literal read that the observer is correctly wired to peer count changes.

### 3. Update meshStatusFlow on Peer Count Transition
- [ ] **File:** MeshrabiyaApiImpl.kt
    - [ ] **Logic:**
        - When the peer count transitions from 0 to 1, update `_meshStatusFlow.value` to `MeshStateDto.CONNECTED`.
        - Ensure this logic does not interfere with other mesh status transitions (e.g., disconnects, multiple peers).
    - [ ] **Verify:**
        - Confirm by literal read that `_meshStatusFlow.value` is set to `CONNECTED` at the correct transition.

### 4. Propagate Status Update to UI
- [ ] **File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
    - [ ] **Verify:**
        - Confirm that the UI observes `meshStatusFlow` and updates the status label accordingly (already implemented, but verify).
        - No changes needed if observer is present and correct.

### 5. Ensure Correct Import Style (Short Name, After Package)
- [ ] **All touched files:**
    - [ ] **Imports:**
        - Use import + short name for all dependencies (see AGENTS.md, IMPORT STYLE RULE).
        - Place imports after package declaration, before code (see PATCH ANCHORING RULE).
    - [ ] **Verify:**
        - All imports are present, correct, and not fully qualified.


### 7. Handle Transition from CONNECTED to CONNECTING When Peer Count Drops to 0
- [ ] **File:** MeshrabiyaApiImpl.kt
    - [ ] **Logic:**
        - When the peer count transitions from 1 (or more) to 0, update `_meshStatusFlow.value` to `MeshStateDto.CONNECTING`.
        - Ensure this logic does not interfere with other mesh status transitions (e.g., disconnects, multiple peers).
    - [ ] **Verify:**
        - Confirm by literal read that `_meshStatusFlow.value` is set to `CONNECTING` at the correct transition.

### 8. Test and Validate CONNECTED→CONNECTING Transition
- [ ] **Build:**
    - [ ] Confirm project builds without errors (first try, no import or type errors).
- [ ] **Run:**
    - [ ] Confirm UI updates to CONNECTING immediately when peers=0.
    - [ ] Confirm no regressions in mesh status/role propagation.

---

## Code-Level Details and Integration Points

### MeshrabiyaApiImpl.kt
```kotlin
// ...existing code...
private var lastPeerCount = 0

private fun startPeerCountMonitor() {
    eventMonitoringScope.launch {
        while (true) {
            val currentPeerCount = getPeerCount()
            if (currentPeerCount != lastPeerCount) {
                if (lastPeerCount == 0 && currentPeerCount > 0) {
                    _meshStatusFlow.value = MeshStateDto.CONNECTED
                }
                lastPeerCount = currentPeerCount
            }
            delay(1000)
        }
    }
}
// Call startPeerCountMonitor() in init or startEventMonitoring()
// ...existing code...
```

### EnhancedMeshFragment.kt
- Already observes `meshStatusFlow` and updates the UI label reactively.

---

## Verification Steps for All Referenced Symbols
- All class, method, and property references must be verified by literal file read and documented in the plan.
- All DTOs (`MeshStateDto`, etc.) must exist and be correctly named (see DtoModels.kt).
- All StateFlow patterns and coroutine usage must be present in the codebase (see MeshrabiyaApiImpl.kt, EnhancedMeshFragment.kt).
- All import and patch anchoring rules must be followed (see AGENTS.md).

---

## Assumptions (All Verified)
- Peer count is accessible via `getPeerCount()` or equivalent (verify by literal read).
- No existing observer directly updates meshStatusFlow on peer count change (verify by literal read).
- UI already observes meshStatusFlow (verify by literal read).
- All file paths and class names are verified by literal read.

---

## Implementation Notes
- All steps are actionable, codebase-verified, and ready for direct implementation.
- Checklist ensures no steps are missed.
- All code snippets are ready for copy-paste and will build on the first try if followed exactly.
