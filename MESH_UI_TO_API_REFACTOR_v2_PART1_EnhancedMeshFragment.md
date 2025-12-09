# MESH_UI_TO_API_REFACTOR_v2_PART1_EnhancedMeshFragment

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

## Objective
Refactor EnhancedMeshFragment to use MeshrabiyaApi for all mesh file operations, drop folder selection, and service orchestration. Remove all legacy manager calls and deprecated logic.

**NOTES: 
- Persist these notes section from version to verion of this plan
- Wherever i say verified, i mean verfied by literal eaminiation of the code on disk. 
- there should be no guessed or estimated code in the plan. All code must be based on the actual context and the actual implemenation of any objets and function used


## Verified API Touchpoints
- MeshrabiyaApi.storeFile(file: File, callback: (Result<String>) -> Unit)
- MeshrabiyaApi.retrieveFile(fileId: String, callback: (Result<File>) -> Unit)
- MeshrabiyaApi.deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.onTaskStatusUpdate: ((taskId: String, status: String) -> Unit)?

## Implementation Steps
1. **Remove all direct calls to DistributedStorageManager, DropFileManager, MeshDropFolderService for mesh file operations.**
   - Example: Replace `distributedStorageManager.storeFile(...)` with `meshrabiyaApi.storeFile(...)`
2. **Refactor drop folder selection logic:**
   - Replace any local drop folder path changes with `meshrabiyaApi.selectDropFolder(path, callback)`
3. **Wire service orchestration controls:**
   - Replace legacy enable/disable logic with `meshrabiyaApi.setServiceParticipationEnabled(serviceId, enabled, callback)`
4. **Wire task status callback:**
   - Set `meshrabiyaApi.onTaskStatusUpdate` to update UI on task status changes
5. **Remove all deprecated service announcement logic.**
6. **Update imports:**
   - Use short names for all MeshrabiyaApi types
   - Remove unused legacy imports

## Code Context Example
```kotlin
// Before:
distributedStorageManager.storeFile(file.absolutePath, file.readBytes(), ...)

// After:
meshrabiyaApi.storeFile(file, callback)
```

## Checklist
- [ ] All mesh file operations use MeshrabiyaApi
- [ ] Drop folder selection uses MeshrabiyaApi
- [ ] Service orchestration uses MeshrabiyaApi
- [ ] Task status callback wired
- [ ] Deprecated logic removed
- [ ] Imports updated

## Outstanding Questions
- Are there any UI-specific file operations that must remain local? (List for user review)
- Are all service IDs and runtime types available via MeshrabiyaApi?

## Status
Ready for implementation. All signatures and touchpoints verified.
