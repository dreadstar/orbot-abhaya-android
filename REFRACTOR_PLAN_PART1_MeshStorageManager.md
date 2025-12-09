# Refactor Plan Part 1: MeshStorageManager

## Goals
- Remove drop folder monitoring logic from MeshStorageManager (handled in Meshrabiya library).
- Ensure drop folder selection sets the folder in MeshrabiyaApi on change.
- Retain MeshStorageManager for client-side logic and UI callback integration.
- Validate all mesh file operations (store, retrieve, delete) use MeshrabiyaApi.

## Steps
1. Remove all code related to drop folder monitoring (watchDropFolder, dropFolderWatcherJob, related coroutine logic).
2. Refactor setDropFolder to call MeshrabiyaApi.selectDropFolder when the folder changes.
3. Confirm all mesh file operations are routed through MeshrabiyaApi.
4. Retain UI callback logic and drop folder selection.

## Verification
- All monitoring logic is removed from MeshStorageManager.
- Drop folder selection is set in MeshrabiyaApi.
- No mesh file operations bypass MeshrabiyaApi.
- UI callback logic remains intact.

## Status
Ready for code changes. This plan part is pre-verified and complete.
