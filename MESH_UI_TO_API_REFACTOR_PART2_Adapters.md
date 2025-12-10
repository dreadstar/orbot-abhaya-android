# MESH_UI_TO_API_REFACTOR_PART2_Adapters

## Scope
FolderContentsAdapter, DropFolderAdapter, and related adapters.

## Goals
- Refactor all mesh file operations in adapters to use MeshStorageManager/MeshrabiyaApi.
- Remove legacy logic for chunk/replica management.
- Ensure all imports use short names.

## Verified API Touchpoints
- MeshStorageManager.storeFile(file, callback)
- MeshStorageManager.retrieveFile(fileId, callback)
- MeshStorageManager.deleteFile(fileId, callback)

## Status
Ready for code-level refactor.
