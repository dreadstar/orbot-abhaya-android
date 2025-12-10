# MESH_UI_TO_API_REFACTOR_PART3_Managers

## Scope
MeshStorageManager, DropFileManager, and related managers.

## Goals
- Remove drop folder monitoring logic from managers (handled in Meshrabiya).
- Refactor drop folder selection to use MeshrabiyaApi.selectDropFolder.
- Route all mesh file operations through MeshrabiyaApi.
- Remove all deprecated service announcement logic.

## Verified API Touchpoints
- MeshrabiyaApi.storeFile(file, callback)
- MeshrabiyaApi.retrieveFile(fileId, callback)
- MeshrabiyaApi.deleteFile(fileId, callback)
- MeshrabiyaApi.selectDropFolder(path, callback)

## Status
Ready for code-level refactor.
