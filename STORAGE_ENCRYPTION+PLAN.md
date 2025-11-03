# STORAGE_ENCRYPTION+PLAN.md

## Distributed Storage Encryption & Permission Update Plan

### Architectural Overview
- Hybrid encryption: Each chunk is encrypted with a symmetric key; the symmetric key is encrypted for each authorized PGP public key.
- Permission change: When permissions change, all affected chunks must have their session keys re-encrypted for the new set of authorized keys.
- Remote update: Storage nodes holding chunks receive a broadcast message instructing them to update the encrypted session keys for the affected chunks.
- Separation of concerns:
  - Encryption logic: `StorageEncryptionManager`
  - Permission management: new `FolderPermissionManager`
  - Remote update protocol: new `ChunkPermissionUpdateService`
  - Gossip/broadcast: extend `MeshGossipService`
  - Chunk metadata: update `MeshChunk` and `StorageDataStore`

### Changes to Existing Files
- `StorageEncryptionManager.kt`: Add multi-recipient encryption, updateRecipients method.
- `MeshChunk.kt`: Add metadata for recipient key IDs and session keys.
- `StorageDataStore.kt`: Add methods to update chunk permissions.
- `DistributedStorageManager.kt`: Add API for permission change, local and remote update logic, confirmation handling, broadcast task update.
- `MeshGossipService.kt`: Add broadcast message type for permission update, confirmation, and task data access update.

### New Files
- `FolderPermissionManager.kt`: Track folder-level permissions, trigger chunk update operations.
- `ChunkPermissionUpdateService.kt`: Handle incoming permission update requests, re-encrypt session keys, validate sender.
- `FilePermissionUpdateHandler.kt`: On storage node, handle permission update, send confirmation.
- `UserNotificationManager.kt`: Notify user on completion/failure/timeout.
- `TaskDataAccessUpdateHandler.kt`: On compute_node, handle task data access update broadcast.

### Workflow
1. User changes permissions in UI.
2. User node broadcasts a `FilePermissionUpdateMessage` to all storage nodes holding chunks for the file.
3. Each storage node validates request, updates session keys, sends confirmation.
4. User node listens for confirmations, logs, updates progress UI, notifies user, broadcasts `TaskDataAccessUpdateMessage` to all compute_nodes.
5. Each compute_node receives broadcast, checks if any local tasks (by UUID) are affected, updates config, triggers data access change handler.

### Security & Reliability
- Only owner can update permissions.
- Confirmation messages sent only to requesting user node.
- Progress UI updated based on confirmations.
- User notified on completion/failure/timeout.
- Task data access updates broadcast to all compute_nodes.
- Each compute_node updates only local tasks matching UUIDs.
- All changes atomic and logged.

### Summary Table of Changes
| File                        | Change/Addition                                      |
|-----------------------------|-----------------------------------------------------|
| MeshFragment.kt             | UI for per-file permission change, trigger API      |
| DistributedStorageManager.kt| Permission update API, confirmation handling, broadcast task update |
| MeshGossipService.kt        | Permission update/confirmation messages, broadcast, task data access update |
| StorageEncryptionManager.kt | Update session keys for new recipients              |
| StorageDataStore.kt         | Update chunk recipients/session keys                |
| MeshChunk.kt                | Store recipient key IDs/session keys                |
| FilePermissionUpdateHandler.kt | New: handle permission update on storage node    |
| UserNotificationManager.kt  | New: notify user of permission update result        |
| TaskManager.kt              | Track running tasks, update accessible files, trigger handler |
| TaskDataAccessUpdateHandler.kt | New: handle task data access update on compute_node |

---

## Task Identity: Public Key vs UUID

### Direct Use of Public Key as Task ID
- **Pros:**
  - Unifies identity and access for tasks and users.
  - Public key can be used for cryptographic operations and access control.
- **Cons:**
  - Multiple tasks may run on a single compute_node; searching for a task by public key can be slow if not indexed.
  - Generating key pairs for every task can be expensive and unnecessary for short-lived or ephemeral tasks.
  - No guarantee of uniqueness if tasks are not globally coordinated.

### Alternative: UUID with Public Key Mapping
- **Assign each task a UUID (fast, unique, no collision risk).**
- **Optionally, associate a public key with the task for cryptographic operations.**
- **Maintain a local mapping (UUID -> public key) on each compute_node.**
- **Broadcasts and access control use UUID for fast lookup; cryptographic operations use the mapped public key.**
- **Tasks register themselves on startup, so lookup is O(1) in a local map.**

### Advantages of UUID Approach
- **Fast lookup and management, even with many tasks.**
- **No need to search or scan for public keys.**
- **Can support ephemeral tasks and long-running tasks equally well.**
- **Still allows cryptographic access control if needed.**
- **No global coordination required; each node manages its own UUIDs.**

### Recommended Strategy
- **Use UUID as the primary task identifier for broadcast, lookup, and access control.**
- **Associate public key with task only if cryptographic operations are required.**
- **Maintain local UUID->task mapping for fast access.**
- **Broadcasts use UUID; compute_node checks if task is running locally and updates config accordingly.**

### Summary
- **Direct use of public key as task ID is not practical for fast lookup or management.**
- **UUID is the best choice for task identity, with optional public key association for cryptographic needs.**
- **This approach is scalable, efficient, and compatible with distributed broadcast notification.**
