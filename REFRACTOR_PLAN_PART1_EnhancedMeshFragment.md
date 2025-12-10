# Refactor Plan Part 1: EnhancedMeshFragment and Underlying Code

## Objective
Total refactor of EnhancedMeshFragment and all underlying fragments, adapters, managers, and control logic to use MeshrabiyaApi for all mesh file operations, service orchestration, and sharing controls.

## Scope
- EnhancedMeshFragment
- DropFolderFragment
- StorageParticipationFragment
- FolderContentsAdapter
- DropFolderAdapter
- MeshStorageManager
- DropFileManager
- All mesh file operations, service orchestration, and sharing controls

## Goals
1. Route all mesh file operations (store, retrieve, delete, share) through MeshrabiyaApi.
2. Remove direct calls to legacy managers (DistributedStorageManager, DropFileManager, MeshDropFolderService) for mesh file operations.
3. Ensure drop folder selection and status are set and retrieved via MeshrabiyaApi.
4. Refactor service orchestration controls to use MeshrabiyaApi stubs for enable/disable by runtime type and serviceId.
5. Expose file sharing granularity via MeshrabiyaApi permission update workflows.
6. Preserve client-side logic for drop folder selection, UI callbacks, and local-only folder operations.
7. Remove all deprecated service announcement logic.
8. Document all changes and verify all API touchpoints, method signatures, and data structures before implementation.

## Steps
- Enumerate all mesh file operations and refactor to use MeshrabiyaApi.
- Update all adapters and fragments to call MeshStorageManager/MeshrabiyaApi for mesh file operations.
- Refactor drop folder selection logic to use MeshrabiyaApi.selectDropFolder.
- Refactor service orchestration UI to use MeshrabiyaApi.setServiceParticipationEnabled and related stubs.
- Expose file sharing granularity via MeshrabiyaApi permission update methods.
- Remove all legacy and deprecated code paths.
- Validate all method signatures and data structures before code changes.

## Verification
- All mesh file operations are routed through MeshrabiyaApi.
- No direct calls to legacy managers for mesh file operations remain.
- Drop folder selection and status are handled via MeshrabiyaApi.
- Service orchestration and sharing controls use MeshrabiyaApi stubs.
- File sharing granularity is exposed via API.
- All deprecated logic is removed.
- All API touchpoints, method signatures, and data structures are verified.

## Status
Ready for code changes. This plan part is pre-verified and complete.
