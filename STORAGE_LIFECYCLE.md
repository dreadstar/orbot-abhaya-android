# STORAGE_LIFECYCLE.md

## Purpose
This document defines the complete lifecycle for distributed file and chunk storage, replication, and retrieval in the orbot-abhaya-android project. It is intended for developers and AI agents to guide implementation, testing, and validation of all related features. All test cases and agent protocols must reference this document for correctness.

---

## 1. File Addition & Drop Folder Monitoring

- **User selects a drop folder** in the Mesh UI ([MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)).
- **Drop folder watcher** ([MeshStorageManager.kt](app/src/main/java/org/torproject/android/mesh/MeshStorageManager.kt)) is started only after selection.
- When a **new file is added** to the drop folder, the watcher triggers the storage lifecycle.

---

## 2. File Chunking (NEW)

- When a file is added, it is **split into chunks** of size defined by `MeshSettings.getChunkSizeKb()` (default: 256KB).
- Each chunk is assigned:
  - `chunkId` (e.g., SHA-256 hash or UUID)
  - `fileId` (unique for the whole file)
  - `chunkIndex` (order in file)
  - `totalChunks`
  - `chunkSize`
  - `fileName`
  - `relativePath` (for subfolders)
  - `hash` (for integrity)
- **Chunk metadata** is stored in DataStore ([DataStore.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DataStore.kt)).

---

## 3. Storage Node Discovery (Broadcast Request)

- The client node **broadcasts a Storage Node Request** to the mesh network using gossip protocol ([MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)).
- The request includes:
  - Chunk metadata (see above)
  - Any relevant metadata for fitness evaluation

---

## 4. Candidate Node Reception & Response

- **All nodes** on the mesh receive the broadcast.
- Each candidate node:
  - Checks if it is a **storage node** ([EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt)).
  - Evaluates **system state** (thermal, battery, etc.) and **available disk space**.
  - If eligible, **responds** with:
    - Available space
    - System state
    - Node ID
    - Mesh URL for storage
    - Latency estimate
    - Fitness score

---

## 5. Chunk Transfer (ENHANCED)

- The client node **initiates chunk transfer** to selected storage nodes using mesh protocols ([MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)).
- Each chunk is sent to a selected storage node, possibly distributing chunks across multiple nodes for redundancy and load balancing.
- Transfer is reliable and confirmed by completion notification for each chunk.

---

## 6. Storage Node Chunk Reception & Completion Notification (ENHANCED)

- The storage node **receives the chunk** and stores it in its shared storage area ([OrbotService.kt](orbotservice/src/main/java/org/torproject/android/service/OrbotService.kt)).
- The storage node **indexes chunk metadata** in DataStore ([DataStore.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DataStore.kt)), including fileId, chunkId, chunkIndex, totalChunks, fileName, relativePath.
- The storage node **sends a completion notification** to the client node, including the chunkId and fileId.

---

## 7. Client Node UI Update

- Upon receiving completion notifications for all chunks, the client node:
  - Updates the UI ([MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)) to indicate successful storage and display the file identifier.
  - Marks the file as replicated to distributed storage.

---

## 8. Replication Process (ENHANCED)

- The storage node **initiates replication** of each chunk to other storage nodes ([ReplicationManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/ReplicationManager.kt)).
- Replication uses a **broadcast request** similar to the initial storage process.
- Each candidate node:
  - Checks if it already stores the chunk (using chunkId).
  - Evaluates eligibility as in the initial storage process.
- Replication continues until the **desired replica count (X)** is reached for each chunk.
  - X is configurable ([AppSettings.kt](app/src/main/java/org/torproject/android/AppSettings.kt)).
  - Before replicating, each node queries the mesh for the current replica count for each chunk.
  - Replication stops when X or more replicas are confirmed.

---

## 9. Replica Count Query (ENHANCED)

- Any node can **query the mesh** for the number of replicas of a given chunkId or fileId.
- The query is broadcast, and all nodes respond if they store the chunk.
- The client or storage node uses this to determine if further replication is needed.

---

## 10. Error Handling & Retries

- All steps include robust error handling:
  - Timeouts and retries for broadcasts and transfers, using values from MeshSettings ([MeshSettings.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/settings/MeshSettings.kt)).
  - UI alerts for user awareness.
  - Logging for diagnostics.
- All failures are surfaced to the user and retried according to protocol.

---

## 11. Security & Privacy

- File and chunk identifiers are anonymized and do not reveal user identity.
- Transfers and replication use encrypted mesh channels.
- Only eligible nodes participate in storage and replication.
- Chunk hashes are used for integrity verification.
- Encryption metadata is included in chunk headers if encryption is enabled.

---

## 12. Test Case Guidance (ENHANCED)

- Test cases must cover:
  - Drop folder selection and watcher activation/deactivation.
  - File chunking and chunk metadata creation.
  - Storage node discovery and candidate selection logic for each chunk.
  - Chunk transfer success and failure scenarios.
  - Completion notification and UI update for each chunk.
  - Replication initiation, progress, and completion for chunks.
  - Replica count query and enforcement for chunks.
  - Error handling, retries, and user alerts.
  - Security and privacy compliance.
  - File recomposition from chunks (see retrieval lifecycle).

---

## 13. References

- [MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)
- [MainActivity.kt](app/src/main/java/org/torproject/android/MainActivity.kt)
- [GatewayCapabilitiesManager.kt](app/src/main/java/org/torproject/android/GatewayCapabilitiesManager.kt)
- [MeshStorageManager.kt](app/src/main/java/org/torproject/android/mesh/MeshStorageManager.kt)
- [EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt)
- [MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)
- [OrbotService.kt](orbotservice/src/main/java/org/torproject/android/service/OrbotService.kt)
- [ReplicationManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/ReplicationManager.kt)
- [AppSettings.kt](app/src/main/java/org/torproject/android/AppSettings.kt)
- [DataStore.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DataStore.kt)

---

## 14. File Retrieval Lifecycle (ENHANCED)

### Purpose

This section defines the complete lifecycle for retrieving files/chunks from the mesh network. The process is robust against mesh volatility and mirrors the storage lifecycle in terms of broadcast, candidate selection, transfer, error handling, and UI feedback.

---

### 1. Retrieval Request & Query Broadcast

- **User requests a file** via the Mesh UI ([MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)).
- The client node **broadcasts a Retrieval Query** for the desired fileId to the mesh network using gossip protocol ([MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)).
- The query includes:
  - fileId
  - Any relevant metadata for candidate evaluation

---

### 2. Candidate Node Reception & Response

- **All nodes** on the mesh receive the retrieval query.
- Each candidate node:
  - Checks if it **stores any chunk** of the requested fileId (using DataStore).
  - Evaluates **system state** and **availability for transfer**.
  - If eligible, **responds** with:
    - Node ID
    - Mesh URL for retrieval
    - List of chunkIds, chunkIndexes, chunkSizes, relativePath, fileName
    - System state
    - Latency estimate
    - Fitness score

---

### 3. Client Node Response Handling & Candidate Selection

- The client node **waits for responses** within a timeout window (see MeshSettings for defaults).
- If **no responses** are received:
  - UI alerts the user.
  - The client retries after a delay, up to the max retry count ([MeshSettings.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/settings/MeshSettings.kt)).
- If **responses are received**:
  - The client builds a map of chunkIndexes to available nodeIds.
  - For each chunk, the client selects the **best candidate** based on:
    - Healthy system state
    - Lowest latency
    - Highest fitness score
  - If **none are suitable** for a chunk, UI alerts the user and retries after a delay.

---

### 4. Chunk Download & Recomposition (NEW)

- For each chunk, the client node:
  - Sends a **retrieve chunk request** to the selected node.
  - Handles timeouts, retries, and alternate sources as per MeshSettings.
  - Downloads all chunks in parallel, constrained by node fitness and system limits.
- Once all chunks are retrieved:
  - **Verify chunk hashes** for integrity.
  - **Recompose the file** in the correct order.
  - Place the fully recomposed file in the drop folder or subfolder as indicated by relativePath.
  - Update DataStore with recomposed file metadata.

---

### 5. Source Node Completion Notification

- Upon successful transfer of each chunk, the source node:
  - Sends a **completion notification** to the requesting client node.
  - Includes the chunkId, fileId, and transfer status.

---

### 6. Client Node UI Update

- Upon receiving completion notifications for all chunks, the client node:
  - Updates the UI ([MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)) to indicate successful retrieval and display the file identifier.
  - Marks the file as retrieved from distributed storage.

---

### 7. Error Handling & Retries

- All steps include robust error handling:
  - Timeouts and retries for broadcasts and chunk transfers (using MeshSettings defaults).
  - UI alerts for user awareness.
  - Logging for diagnostics.
- All failures are surfaced to the user and retried according to protocol.
- If maximum retries are exceeded, the user is notified and the operation is aborted.

---

### 8. Security & Privacy

- Chunk and file identifiers are anonymized and do not reveal user identity.
- Transfers use encrypted mesh channels.
- Only eligible nodes participate in retrieval.
- Chunk hashes are verified before recomposition.

---

### 9. Test Case Guidance (ENHANCED)

- Test cases must cover:
  - Retrieval query broadcast and candidate response logic for chunks.
  - Candidate selection and chunk transfer initiation.
  - Chunk transfer success and failure scenarios.
  - Completion notification and UI update for each chunk.
  - Error handling, retries, and user alerts.
  - Security and privacy compliance.
  - File recomposition from chunks and placement in drop folder.

---

### 10. References

- [MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)
- [MainActivity.kt](app/src/main/java/org/torproject/android/MainActivity.kt)
- [MeshStorageManager.kt](app/src/main/java/org/torproject/android/mesh/MeshStorageManager.kt)
- [MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)
- [OrbotService.kt](orbotservice/src/main/java/org/torproject/android/service/OrbotService.kt)
- [MeshSettings.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/settings/MeshSettings.kt)
- [DataStore.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DataStore.kt)

---

## 15. FUTURE ENHANCEMENTS

- **UI Mesh File Browser:**  
  - Add a UI view for user files on the mesh, indicating which files are present in the drop folder/subfolders and which are only in distributed storage.
  - Allow user to download mesh files to drop folder or subfolder, preserving relative path.
- **Advanced Chunking:**  
  - Support variable chunk sizes, erasure coding, and deduplication.
- **Encryption Metadata:**  
  - Store and manage encryption keys and metadata for each chunk.
- **Partial File Retrieval:**  
  - Support retrieval of only selected chunks (for streaming or preview).
- **Mesh-wide Garbage Collection:**  
  - Periodically remove orphaned chunks and files from distributed storage.

---

**This document is the canonical reference for distributed storage, chunking, replication, and retrieval lifecycle in the orbot-abhaya-android project. All development, agent operations, and test cases must comply with these protocols.**