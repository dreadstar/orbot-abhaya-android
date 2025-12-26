# FILE STORAGE ANALYSIS

## 1. Literal Verification of Code on Disk

### `chunkFile` Signature (DistributedStorageManager.kt)
- **Current signature:**
  ```kotlin
  fun chunkFile(
      chunkId: String,
      fileId: String,
      chunkIndex: Int,
      totalChunks: Int,
      fileName: String,
      relativePath: String,
      chunkBytes: ByteArray,
      hash: String,
      replicaCount: Int = 0,
      recipients: List<RecipientEntry> = emptyList(),
      sessionKeys: Map<String, ByteArray> = emptyMap(),
      owner: RecipientEntry
  ): List<MeshChunk>
  ```
- **Actual implementation on disk:**
  The above signature is present, but the function body references a `file` variable that is not a parameter, indicating a code/plan mismatch.
- **Client usage:**
  In `DistributedStorageClient.kt`, the call is:
  ```kotlin
  val chunks = manager.chunkFile(file, fileId, chunkSize, owner=ownerEntry)
  ```
  This does not match the actual function signature.

## 2. Server Node Replication and Chunk Handling

### Questions and Answers
| Question | Verified Answer |
|---|---|
| Does the server node try to chunk the chunk it received? | **No.** The server stores the chunk as received, does not re-chunk. |
| Does the server try to reassemble the file from chunks before replicating? | **No.** Replication is performed at the chunk level. |
| Are the chunks being reassembled to store on disk? | **No.** Chunks are stored individually, not reassembled. |

### Evidence
- In `DistributedStorageServer.handleIncomingChunkTransfer`, the chunk is written directly to disk as a file named `${chunkTransfer.chunkId}.chunk`.
- Replication (`initiateChunkReplication`) reads the chunk file and forwards it, does not re-chunk or reassemble.
- No code path on the server attempts to reassemble the file from chunks for storage or replication.

## 3. Storage Location and Reference Handling
- **Chunks are stored in**: `shared_storage/<fileId>/<relativePath>/<chunkId>.chunk` on server nodes.
- **MeshChunk** (as stored in `StorageDataStore`) includes: `relativePath`, `fileId`, `chunkId`, and a reference to the owner and recipients.
- **FileReference** is used on the client/owner node to track the file root and metadata.
- **Recipient nodes**: Use the file name from `FileReference` and store in a shared subfolder, appending a datetime stamp on conflict.
- **Output files from tasks**: Should be written to the distributed shared folder on both owner and recipient nodes (not fully implemented, but implied by comments and conventions).

## 4. Gap Analysis
| Requirement | Current Implementation | Gap/Issue |
|---|---|---|
| Server should not chunk received chunk | Server stores chunk as-is | **Correct** |
| Server should not reassemble file for replication | Replication is chunk-level | **Correct** |
| Chunks should be stored with relative path and file reference | MeshChunk includes relativePath, fileId, owner | **Correct** |
| chunkFile signature should match usage | Signature and usage mismatch | **Needs refactor** |
| Output files from tasks to shared folder | Not fully implemented | **Needs implementation** |
| Recipient file naming conflict resolution | Not fully implemented | **Needs implementation** |

## 5. Refactor Plan (Production-Ready)

### a. Fix `chunkFile` Signature and Usage
- **Update `chunkFile` in DistributedStorageManager.kt** to accept a `File` parameter and match all usages.
- **Update all calls** to `chunkFile` to use the new signature.

### b. Ensure Server Stores and Replicates Chunks Only
- **No change needed**; current logic is correct.

### c. Implement Output File Handling for Tasks
- **Add logic** to write output files to the distributed shared folder on both owner and recipient nodes.

### d. Implement Recipient File Naming Conflict Resolution
- **Add logic** to append a datetime stamp to the file name prefix in case of conflict in the shared folder.

## 6. Corrected Function Table (Key Storage Functions)
| File | Object | Function | Purpose |
|---|---|---|---|
| DistributedStorageManager.kt | DistributedStorageManager | chunkFile | Chunk a file into MeshChunk objects (should take File param) |
| DistributedStorageClient.kt | DistributedStorageClient | storeFile | Client-side file storage, chunking, encryption, node selection |
| DistributedStorageServer.kt | DistributedStorageServer | handleIncomingChunkTransfer | Store received chunk as file, index in store |
| DistributedStorageServer.kt | DistributedStorageServer | initiateChunkReplication | Replicate chunk to next node, chunk-level only |
| StorageDataStore.kt | StorageDataStore | addMeshChunk | Index/store chunk metadata in DB |

## 7. Deliverables
- Literal verification of all code and signatures on disk
- Answers to all server/replication/chunking questions
- Gap analysis table
- Refactor plan with actionable, production-ready steps
- Table of corrected key storage functions

---

**This document is based on literal file reads and verified codebase state as of December 20, 2025.**

## 8. Additional Clarifications and Best Practices

- **ChunkReference (MeshChunk) should include a literal property referencing the FileReference for the parent file.** This enables direct access to owner, recipient, filename, and other metadata needed for chunk-level operations and recipient-side file reconstruction.
- **Replica count must NOT be stored on FileReference.** Replication is a property of individual chunks, not the file as a whole. FileReference should only track file-level metadata (fileId, path, owner, recipients, etc.).
- **Recipient filename and destination logic:**
  - Owner nodes: Use the local file path and name for FileReference.
  - Recipient nodes: Use the filename from FileReference, store in the shared subfolder of the selected dropfolder. On name conflict, append a datetime stamp to the file name prefix.
- **Output files from tasks:**
  - Must be written to the distributed shared folder on both owner and recipient client nodes, using the FileReference and chunking logic above.
- **MeshChunk/ChunkReference structure:**
  - Should include: chunkId, fileId, relativePath, owner, recipients, hash, replicaCount, and a direct reference to the FileReference.
  - This enables robust tracking, retrieval, and reconstruction of files from distributed chunks.

## 9. Comprehensive Refactor Plan (Including All Gaps and Clarifications)

### a. MeshChunk/ChunkReference Structure
- **Add a property to MeshChunk/ChunkReference:**
  - `val fileReference: FileReference` (or nullable if not always available)
  - Ensure this is set when chunking and storing chunks.
- **Update all chunk creation and storage logic** to include the FileReference.

### b. FileReference Structure
- **Remove any replicaCount property from FileReference.**
- **Ensure FileReference only contains file-level metadata.**

### c. Chunk Storage and Indexing
- **Ensure all chunks are stored with their relative path, fileId, and a reference to FileReference.**
- **Update StorageDataStore and all indexing logic** to support the new MeshChunk structure.

### d. Recipient Filename and Destination Logic
- **Owner nodes:** Use local file path and name for FileReference.
- **Recipient nodes:** Use filename from FileReference, store in shared subfolder. On conflict, append datetime stamp to filename prefix.
- **Update all file retrieval and reconstruction logic** to use this convention.

### e. Output File Handling for Tasks
- **Implement logic to write output files to the distributed shared folder** on both owner and recipient nodes, using FileReference and chunking.

### f. API and Data Model Updates
- **Update all APIs and data models** to reflect the new MeshChunk/ChunkReference and FileReference structures.
- **Update serialization/deserialization logic** for MeshChunk and FileReference as needed.

### g. Documentation and Comments
- **Document all changes** in code and project documentation, including rationale for chunk-level replication and FileReference linkage.

---

**These clarifications and refactor steps are based on literal codebase research and best practices for distributed file storage as of December 20, 2025.**
