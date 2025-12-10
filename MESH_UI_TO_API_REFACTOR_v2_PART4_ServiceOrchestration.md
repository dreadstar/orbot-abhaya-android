# MESH_UI_TO_API_REFACTOR_v2_PART4_ServiceOrchestration
**NOTES: 
- Persist these notes section from version to verion of this plan
- Wherever i say verified, i mean verfied by literal eaminiation of the code on disk. 
- there should be no guessed or estimated code in the plan. All code must be based on the actual context and the actual implemenation of any objets and function used

**Files:**
- app/src/main/java/org/torproject/android/mesh/MeshServiceOrchestrator.kt
- app/src/main/java/org/torproject/android/mesh/ServiceAnnouncementManager.kt

## Objective
Refactor service orchestration to use MeshrabiyaApi for all mesh service lifecycle events. Remove legacy service announcement logic and update all control flows to API-based orchestration.

## Verified API Touchpoints
- MeshrabiyaApi.startMeshService(callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.stopMeshService(callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.getMeshServiceStatus(callback: (Result<MeshServiceStatus>) -> Unit)

## Implementation Steps
1. **Remove legacy service announcement logic:**
   - Delete all code referencing ServiceAnnouncementManager and related broadcast logic
2. **Refactor service lifecycle control:**
   - Replace direct service start/stop calls with MeshrabiyaApi methods
3. **Update status checks:**
   - Use MeshrabiyaApi.getMeshServiceStatus for all status queries
4. **Update imports:**
   - Use short names for all types
   - Remove unused legacy imports

## Code Context Example
```kotlin
// Before:
serviceAnnouncementManager.announceServiceStart()
startMeshServiceInternal()

// After:
meshrabiyaApi.startMeshService(callback)
```

## Checklist
- [ ] Legacy service announcement logic removed
- [ ] Service lifecycle control uses MeshrabiyaApi
- [ ] Status checks use MeshrabiyaApi
- [ ] Imports updated

## Outstanding Questions
- Are there any mesh service events not covered by MeshrabiyaApi? (List for user review)
**Answer: Have all of these lists ready for my review in the next version . and moving forward if you want me to evaluate a list of things, have the list present  for me to review at the time you pose the question.
## Status
Ready for implementation. All signatures and touchpoints verified.
