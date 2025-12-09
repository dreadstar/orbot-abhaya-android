# MESH_UI_TO_API_REFACTOR_PART1_EnhancedMeshFragment

## Scope
EnhancedMeshFragment: UI logic, mesh file operations, drop folder selection, service orchestration controls.

## Goals
- Route all mesh file operations (store, retrieve, delete, share) through MeshrabiyaApi.
- Remove direct calls to legacy managers for mesh file operations.
- Refactor drop folder selection to use MeshrabiyaApi.selectDropFolder.
- Refactor service orchestration controls to use MeshrabiyaApi stubs.
- Preserve client-side UI logic and callbacks.

## Verified API Touchpoints
- MeshrabiyaApi.storeFile(file, callback)
- MeshrabiyaApi.retrieveFile(fileId, callback)
- MeshrabiyaApi.deleteFile(fileId, callback)
- MeshrabiyaApi.selectDropFolder(path, callback)
- MeshrabiyaApi.setServiceParticipationEnabled(serviceId, enabled, callback)

## Status
Ready for code-level refactor.
