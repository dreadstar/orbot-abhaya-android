# MESH_UI_TO_API_REFACTOR_PART4_ServiceOrchestration

## Scope
Service orchestration controls in UI and managers.

## Goals
- Refactor all service enable/disable controls to use MeshrabiyaApi.setServiceParticipationEnabled.
- Operate by runtime type and serviceId.
- Remove legacy orchestration logic.

## Verified API Touchpoints
- MeshrabiyaApi.setServiceParticipationEnabled(serviceId, enabled, callback)

## Status
Ready for code-level refactor.
