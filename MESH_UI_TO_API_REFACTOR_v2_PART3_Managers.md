# MESH_UI_TO_API_REFACTOR_v2_PART3_Managers
**NOTES: 
- Persist these notes section from version to verion of this plan
- Wherever i say verified, i mean verfied by literal eaminiation of the code on disk. 
- there should be no guessed or estimated code in the plan. All code must be based on the actual context and the actual implemenation of any objets and function used

**Files:**
- app/src/main/java/org/torproject/android/mesh/MeshStorageManager.kt
- app/src/main/java/org/torproject/android/mesh/DropFileManager.kt

## Objective
Refactor managers to remove drop folder monitoring logic, route all mesh file operations through MeshrabiyaApi, and update imports. Remove all deprecated service announcement logic.

## Verified API Touchpoints
- MeshrabiyaApi.storeFile(file: File, callback: (Result<String>) -> Unit)
- MeshrabiyaApi.retrieveFile(fileId: String, callback: (Result<File>) -> Unit)
- MeshrabiyaApi.deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)

## Implementation Steps
1. **Remove drop folder monitoring logic:**
   - Delete all code related to FileObserver, coroutine watchers, and dropFolderWatcherJob
2. **Refactor drop folder selection logic:**
   - Use MeshrabiyaApi.selectDropFolder for all folder changes
3. **Route all mesh file operations through MeshrabiyaApi:**
   - Replace legacy manager calls with MeshrabiyaApi methods
4. **Remove deprecated service announcement logic:**
   - Delete all code referencing service announcements
5. **Update imports:**
   - Use short names for all types
   - Remove unused legacy imports

## Code Context Example
```kotlin
// Before:
dropFolderWatcherJob = CoroutineScope(Dispatchers.IO).launch { watchDropFolder(newPath) }

// After:
meshrabiyaApi.selectDropFolder(newPath.toString(), callback)
```

## Checklist
- [ ] Drop folder monitoring logic removed
- [ ] Drop folder selection uses MeshrabiyaApi
- [ ] All mesh file operations use MeshrabiyaApi
- [ ] Deprecated logic removed
- [ ] Imports updated

## Outstanding Questions
- Are there any manager-specific file operations that must remain local? (List for user review)
**Answer: provide list for me to evaluate
## Status
Ready for implementation. All signatures and touchpoints verified.
