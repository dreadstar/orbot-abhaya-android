# MESH_UI_TO_API_REFACTOR_v3_PART5_FileSharingGranularity

## STRICT API BOUNDARY PROTOCOL
All file sharing code must connect ONLY to functions defined in MeshrabiyaApi. No direct calls to library classes, objects, or methods are allowed. If a required function does not exist in MeshrabiyaApi, implementation must pause and user guidance is required before proceeding. All code must be production-ready and use only verified MeshrabiyaApi functions. No mock, placeholder, or NotImplemented code is allowed.

### Verified MeshrabiyaApi Functions for File Sharing Granularity
- setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)
- setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)

## Objective
Refactor file sharing logic to support per-file and per-folder sharing controls via MeshrabiyaApi. Incorporate user answers and implementation rules.


## Canonical UI Field to API/Direct Mapping (2025-12-08)

| UI Field / Feature                | Current Logic / Source                | New Mapping / API Function(s)                | Implementation Status / Notes |
|-----------------------------------|---------------------------------------|----------------------------------------------|------------------------------|
| storageParticipationToggle        | meshCoordinator.getUserSharingPreferences() | MeshrabiyaApi.setStorageParticipationEnabled, getStorageParticipationStatus | API only                      |
| storageAllocationSlider           | meshCoordinator.getUserSharingPreferences() | MeshrabiyaApi.setStorageAllocation, getStorageAllocations | API only                      |
| storageStatusText                 | meshCoordinator.getStorageParticipationStatus() | MeshrabiyaApi.getStorageParticipationStatus | API only                      |
| storageAllocationText             | meshCoordinator.getUserSharingPreferences() | MeshrabiyaApi.getStorageAllocations         | API only                      |
| selectFolderButton                | Direct file/folder selection           | Direct, but call MeshrabiyaApi.selectDropFolder on selection | Hybrid (Direct+API)           |
| createFolderButton                | Direct file/folder creation            | Direct only                                 | Direct only                  |
| serviceLayerParticipationSwitch   | serviceLayerCoordinator                | MeshrabiyaApi.setServiceParticipationEnabled, getServiceParticipationStatus | API only                      |
| serviceLayerStatusText            | serviceLayerCoordinator                | MeshrabiyaApi.getServiceParticipationStatus  | API only                      |
| pythonServiceStatus, mlInferenceServiceStatus | serviceLayerCoordinator        | Leave as-is (no API function)                | Direct only                  |
| distributedStorageServiceStatus   | serviceLayerCoordinator                | MeshrabiyaApi.getStorageParticipationStatus  | API only                      |
| taskSchedulerServiceStatus        | serviceLayerCoordinator                | MeshrabiyaApi (if available), else leave as-is | API or Direct                |
| torGatewayStatus, internetGatewayStatus | gatewayManager/meshCoordinator   | MeshrabiyaApi.setTorGatewayEnabled, getTorGatewayStatus, setInternetGatewayEnabled, getInternetGatewayStatus | API only |
| activeNodesText, networkLoadText, stabilityText | meshCoordinator/networkStats | MeshrabiyaApi.getNetworkInfo, getPeerCount, getMeshStatus | API only |
| JobType-based status fields       | Deprecated/legacy                      | Leave as-is (per user)                       | No change                    |
| Role assignment/capability change | Not present in UI                      | Not to be implemented                        | N/A                          |

## User Answers Incorporated (2025-12-08)
- All mesh logic must use MeshrabiyaApi only, as mapped above.
- Non-mesh features (local file/folder creation, direct UI logic) retain direct implementation unless explicitly mapped to API.
- No direct access to mesh roles from UI; role assignment is not exposed and not to be implemented.
- Deprecated/JobType-based status logic is to be left as-is unless a MeshrabiyaApi function is available.

## Outstanding TODOs (2025-12-08)
- [ ] Ensure all mesh logic is routed through MeshrabiyaApi as mapped above.
- [ ] Retain direct logic for non-mesh features as specified.
- [ ] For selectFolderButton, ensure MeshrabiyaApi.selectDropFolder is called after folder selection.
- [ ] Do not implement or expose role assignment/capability change in UI.
- [ ] Leave JobType-based/deprecated status code untouched unless a new API function is provided.
- [ ] Update all v3_PART plan documents to reflect this mapping and clarify implementation boundaries for future agents.

## Uncertainties/Resolved Questions (2025-12-08)
- Service status fields: Only toggle text based on available MeshrabiyaApi functions; leave direct logic for Python/ML status as-is.
- Drop folder: Only call MeshrabiyaApi.selectDropFolder on selection; all other folder logic remains direct.
- Role assignment: Not present in UI, not to be implemented.

## Implementation Steps
1. Remove all legacy sharing logic
2. Use MeshrabiyaApi for all sharing actions (verified signatures)
3. Update UI for per-file and per-folder sharing
4. Update status checks to use MeshrabiyaApi
5. Update imports to use short names only
6. Validate all code changes with brace_paren_check.sh

## Build/Test Verification
After completing this section:
- Run Gradle build and test as described in the overview
- Review logs for errors and update INTERIM_COMMIT_LOG.md

## Verified API Touchpoints
- MeshrabiyaApi.shareFile(fileId: String, recipient: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.shareFolder(folderId: String, recipient: String, callback: (Result<Unit>) -> Unit)
- MeshrabiyaApi.getFileShareStatus(fileId: String, callback: (Result<FileShareStatus>) -> Unit)
- MeshrabiyaApi.getFolderShareStatus(folderId: String, callback: (Result<FolderShareStatus>) -> Unit)

- Use dependency injection (DI) for MeshrabiyaApi instance
- Depend on MeshrabiyaApi interface for abstraction and testability
- Prefer suspend functions or Flow/LiveData for async API calls
- Use sealed classes or Result<T> for explicit error handling
- Imports must use short names
- All API signatures verified
- All changes must be structurally validated
+- For every code implementation, always verify:
	- The exact method/property signature
	- The enclosing object/class structure
	- The existence and correctness of all referenced symbols
- Use dependency injection (DI) for MeshrabiyaApi instance
- Depend on MeshrabiyaApi interface for abstraction and testability
- Prefer suspend functions or Flow/LiveData for async API calls
- Use sealed classes or Result<T> for explicit error handling
- Imports must use short names
- All API signatures verified
- All changes must be structurally validated

## Status
Ready for implementation. User answers and rules incorporated.
