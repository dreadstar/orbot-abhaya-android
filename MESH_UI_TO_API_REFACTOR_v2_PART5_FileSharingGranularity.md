# MESH_UI_TO_API_REFACTOR_v2_PART5_FileSharingGranularity
**NOTES: 
- Persist these notes section from version to verion of this plan
- Wherever i say verified, i mean verfied by literal eaminiation of the code on disk. 
- there should be no guessed or estimated code in the plan. All code must be based on the actual context and the actual implemenation of any objects and function used

**Files:**
- app/src/main/java/org/torproject/android/mesh/MeshFileSharingManager.kt
- app/src/main/java/org/torproject/android/mesh/FolderContentsAdapter.kt

## Objective
Refactor file sharing logic to support per-file and per-folder sharing controls via MeshrabiyaApi. Remove legacy sharing logic and update UI to reflect new granularity.

## Verified API Touchpoints
- MeshrabiyaApi.shareFile(fileId: String, recipient: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.shareFolder(folderId: String, recipient: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.getFileShareStatus(fileId: String, callback: (Result<FileShareStatus>) -> Unit)
- MeshrabiyaApi.getFolderShareStatus(folderId: String, callback: (Result<FolderShareStatus>) -> Unit)

## Implementation Steps
1. **Remove legacy sharing logic:**
   - Delete all code referencing legacy sharing managers and direct file/folder sharing
2. **Refactor sharing controls:**
   - Use MeshrabiyaApi for all sharing actions
   - Update UI to allow per-file and per-folder sharing
3. **Update status checks:**
   - Use MeshrabiyaApi for all share status queries
4. **Update imports:**
   - Use short names for all types
   - Remove unused legacy imports

## Code Context Example
```kotlin
// Before:
legacySharingManager.shareFileDirect(file, recipient)

// After:
meshrabiyaApi.shareFile(fileId, recipient, callback)
```

## Checklist
- [ ] Legacy sharing logic removed
- [ ] Sharing controls use MeshrabiyaApi
- [ ] UI updated for per-file/folder sharing
- [ ] Status checks use MeshrabiyaApi
- [ ] Imports updated

## Outstanding Questions
- Are there any sharing scenarios not covered by MeshrabiyaApi? (List for user review)
**Answer: no there should not be now. if you find one bring it to my attention.
## Status
Ready for implementation. All signatures and touchpoints verified.
