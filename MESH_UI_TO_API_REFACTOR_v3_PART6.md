# MESH_UI_TO_API_REFACTOR_v3_PART6

---
## AGENT ONBOARDING & IMPLEMENTATION INSTRUCTIONS (2025-12-07)

### Project Overview
This document is part of a six-part refactor plan for Orbot’s mesh UI/control layers. The objective is to route all mesh file operations and control logic through the MeshrabiyaApi interface, enforcing a strict API boundary and removing all legacy or direct library calls. All code must be production-ready, error-free, and use only the verified MeshrabiyaApi entry points.

### Strict API Boundary Protocol
- All UI, control, adapter, manager, service, and sharing logic must use MeshrabiyaApi only.
- No direct library calls or legacy logic allowed.
- All function calls must match the verified MeshrabiyaApi signatures.

### MeshrabiyaApi Function Mappings (Signature-Verified)
- storeFile(path: String, data: ByteArray, metadata: FileMetadata): ResultType
- retrieveFile(fileId: String): MeshFile?
- deleteFile(fileId: String): Boolean
- getAllMeshFiles(): List<MeshFile>

### Implementation Steps
1. Enumerate all file operation calls in target classes/fragments.
2. Refactor all such calls to use MeshrabiyaApi only.
3. Remove any legacy or direct library calls.
4. Use dependency injection for MeshrabiyaApi.
5. Support coroutines for suspend functions.
6. Implement robust error handling for all API calls.
7. Use short name imports only (never fully qualified names).
8. Validate code structure and run brace_paren_check.sh for well-formedness.
9. Track each implementation step in INTERIM_COMMIT_LOG.md.
10. Reference AGENTS.md and AI_RULES.md for operational rules and protocols.


### Canonical UI Field to API/Direct Mapping (2025-12-08)

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

### Outstanding TODOs (2025-12-08)
- [ ] Ensure all mesh logic is routed through MeshrabiyaApi as mapped above.
- [ ] Retain direct logic for non-mesh features as specified.
- [ ] For selectFolderButton, ensure MeshrabiyaApi.selectDropFolder is called after folder selection.
- [ ] Do not implement or expose role assignment/capability change in UI.
- [ ] Leave JobType-based/deprecated status code untouched unless a new API function is provided.
- [ ] Update all v3_PART plan documents to reflect this mapping and clarify implementation boundaries for future agents.

### Uncertainties/Resolved Questions (2025-12-08)
- Service status fields: Only toggle text based on available MeshrabiyaApi functions; leave direct logic for Python/ML status as-is.
- Drop folder: Only call MeshrabiyaApi.selectDropFolder on selection; all other folder logic remains direct.
- Role assignment: Not present in UI, not to be implemented.

---

## STRICT API BOUNDARY PROTOCOL
All verification must ensure that only functions defined in MeshrabiyaApi are used for all UI, adapter, manager, service orchestration, and sharing logic. No direct calls to library classes, objects, or methods are allowed. If a required function does not exist in MeshrabiyaApi, implementation must pause and user guidance is required before proceeding. All code must be production-ready and use only verified MeshrabiyaApi functions. No mock, placeholder, or NotImplemented code is allowed.

### Verified MeshrabiyaApi Functions for Verification
- provideAppContext(context: Context)
- getAppContext(): Context?
- initMesh(context: Context)
- getNodeRole(): Byte
- getFitnessScore(): Float
- getConnectionUri(): String
- getLocalNodeState(): LocalNodeState
- getNeighbors(): List<Int>
- getHopCountToNode(nodeId: Int): Int?
- getConnectLink(): String?
- getConnectLinkFlow(): Flow<String?>
- startMesh(callback: (Result<Unit>) -> Unit)
- stopMesh(callback: (Result<Unit>) -> Unit)
- getMeshStatus(): MeshState
- getPeerCount(): Int
- getNetworkInfo(): NetworkInfo
- getNodeInfo(nodeId: String): NodeInfo
- setProxy(host: String, port: Int)
- setProxyActive(active: Boolean)
- setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
- getTorGatewayStatus(): Boolean
- setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
- getInternetGatewayStatus(): Boolean
- getGatewayStatus(): Boolean
- setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit)
- getGatewayPreference(): GatewayPreference
- isTorActive(): Boolean
- setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
- getStorageParticipationStatus(): Boolean
- getAvailableStorageDevices(): List<StorageDevice>
- setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit)
- getStorageAllocations(): List<StorageAllocation>
- enableDistributedStorage()
- disableDistributedStorage()
- isComputeLayerParticipating(): Boolean
- selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
- getDropFolder(): File?
- getDropFolderFiles(): List<File>
- storeFile(file: File, callback: (Result<String>) -> Unit)
- retrieveFile(fileId: String, callback: (Result<File>) -> Unit)
- streamFile(fileId: String, callback: (Result<Unit>) -> Unit)
- deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
- getAllMeshFiles(): List<MeshFile>
- setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)
- getAvailableServices(): List<String>
- getServiceParticipationStatus(serviceId: String): Boolean
- addTask(requestParams: Map<String, Any>): ApiResult
- startTask(taskId: String, callback: (Result<Unit>) -> Unit)
- cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)
- setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit)
- setOnFileStored(handler: (fileId: String, file: File) -> Unit)
- setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit)
- setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit)
- setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)
- setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)
- getSettings(): Map<String, Any>
- setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit)
- setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean)
- getMeshTrafficRouterStatus(): String
- setOnMeshStateChanged(handler: (newState: MeshState) -> Unit)
- setOnPeerCountChanged(handler: (newCount: Int) -> Unit)
- setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit)
- setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit)

## Objective
Comprehensive checklist for verifying the v3 implementation, incorporating user answers and implementation rules.

## Checklist
### General
- [ ] All legacy logic and deprecated code paths removed
- [ ] All mesh file operations routed through MeshrabiyaApi
- [ ] All service orchestration and sharing controls use MeshrabiyaApi
- [ ] Imports use short names only
- [ ] All API touchpoints verified for existence, signature, and usage
- [ ] All user answers reviewed and incorporated
- [ ] All implementation rules followed

### Component-Specific
#### EnhancedMeshFragment
- [ ] All file operations use MeshrabiyaApi
- [ ] UI reflects API-driven state
#### Adapters (FolderContentsAdapter, DropFolderAdapter)
- [ ] All data sources use MeshrabiyaApi
- [ ] Legacy adapter logic removed
#### Managers (MeshStorageManager, DropFileManager)
- [ ] Drop folder monitoring logic removed
- [ ] All manager operations use MeshrabiyaApi
#### Service Orchestration
- [ ] Legacy service announcement logic removed
- [ ] Service lifecycle control uses MeshrabiyaApi
#### File Sharing Granularity
- [ ] Legacy sharing logic removed
- [ ] UI supports per-file and per-folder sharing via MeshrabiyaApi

### Validation
- [ ] All code changes pass structural validation (brace_paren_check.sh)
- [ ] All code changes pass lint and build
- [ ] All new logic covered by tests
- [ ] INTERIM_COMMIT_LOG.md updated with implementation details

## Notes
- All outstanding questions answered and incorporated
- All implementation rules enforced

## Status
Ready for implementation and verification. Checklist matches user requirements and project standards.
