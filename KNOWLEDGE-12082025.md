# KNOWLEDGE-12082025.md
## Orbot-Abhaya-Android Project Knowledge Update
**Date:** December 8, 2025

---

## API Boundary, UI Mapping, and Implementation Clarification (per user instructions)

### Strict API Boundary Protocol (Summary)
- All mesh logic (file ops, mesh service participation, distributed storage, mesh status, gateway controls) must use MeshrabiyaApi only.
- Non-mesh features (local file/folder creation, direct UI logic) retain direct implementation unless explicitly mapped to API.
- No direct access to mesh roles from UI; role assignment is not exposed and not to be implemented.
- Deprecated/JobType-based status logic is to be left as-is unless a MeshrabiyaApi function is available.

### UI Field to API/Direct Mapping Log (Canonical Table)
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

### Outstanding TODOs
- [ ] Ensure all mesh logic is routed through MeshrabiyaApi as mapped above.
- [ ] Retain direct logic for non-mesh features as specified.
- [ ] For selectFolderButton, ensure MeshrabiyaApi.selectDropFolder is called after folder selection.
- [ ] Do not implement or expose role assignment/capability change in UI.
- [ ] Leave JobType-based/deprecated status code untouched unless a new API function is provided.
- [ ] Update all v3_PART plan documents to reflect this mapping and clarify implementation boundaries for future agents.

### Uncertainties/Resolved Questions
- Service status fields: Only toggle text based on available MeshrabiyaApi functions; leave direct logic for Python/ML status as-is.
- Drop folder: Only call MeshrabiyaApi.selectDropFolder on selection; all other folder logic remains direct.
- Role assignment: Not present in UI, not to be implemented.

### Rule Reminder
- Always update the most recent KNOWLEDGE-*.md file for new rules/clarifications (see AGENTS.md).
- All plan and mapping changes must be reflected in both KNOWLEDGE-12082025.md and the v3_PART plan documents.

---

## Status
- Mapping, rules, and user clarifications incorporated as of Dec 8, 2025.
- Ready for plan document updates and implementation.
