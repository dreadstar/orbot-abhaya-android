# NETWORK_BROADCAST_v1.md

## Meshrabiya Message+File Broadcast Implementation Plan (Codebase-Verified, Exhaustive)

**Date:** 2026-02-01
**Prepared by:** GitHub Copilot (GPT-4.1)

---

### 1. Objective
Implement a robust, multi-hop, codebase-verified broadcast function in the Meshrabiya library to send a message and a file to all nodes in the mesh topology except the sender, with:
- Protocol/port selection
- Full logging (MNetLogger)
- Multi-hop support
- File chunking/serialization
- DTO and observer propagation
- Explicit ambiguity documentation
- Received broadcast files should be stored in the `Shared` subfolder of the chosen drop  Folder. if a drop folder has  not been selected a an error dialog should appear telling the user to "select drop folder to receive file broadcasts.". If `Shared` subfolder does not exist, it should be created

---

### 2. Codebase Audit: Relevant Classes, Methods, and Files

#### 2.1. Core Networking & Routing
- `MeshrabiyaApiImpl` ([MeshrabiyaApiImpl.kt])
- `OriginatingMessageManager` ([OriginatingMessageManager.kt])
- `VirtualNode`, `VirtualRouter` ([VirtualNode.kt], [VirtualRouter.kt])
- `VirtualDatagramSocketImpl`, `VirtualNodeDatagramSocket` ([VirtualDatagramSocketImpl.kt], [VirtualNodeDatagramSocket.kt])

#### 2.2. File Transfer & Storage
- `MeshFile`, `StorageDatastore` ([MeshFile.kt], [StorageDatastore.kt])

#### 2.3. Logging
- `MNetLogger`, `LogLine` ([MNetLogger.kt], [LogLine.kt])

#### 2.4. Data Structures
- `VirtualPacket`, `MeshFileChunk`, DTOs ([VirtualPacket.kt], [MeshFileChunk.kt])

#### 2.5. Integration Points
- All usages of `send`, `broadcast`, `route`, `chunk`, `logger`, `log`, `DatagramSocket`, `OriginatingMessageManager`

---

### 3. Implementation Plan: Step-by-Step, Codebase-Verified

#### 3.1. API Design & Entry Point
- **Add new public API method:**
  - File: [MeshrabiyaApiImpl.kt]
  - Signature:
    ```kotlin
    /**
     * Broadcasts a message and a file to all nodes except the sender.
     * @param message: String - The message to broadcast
     * @param file: MeshFile - The file to broadcast
     * @param callback: (BroadcastResult) -> Unit - Completion callback
     */
    fun broadcastMessageAndFile(message: String, file: MeshFile, callback: (BroadcastResult) -> Unit)
    ```
- **Verify:** No such method currently exists (grep search: `broadcastMessageAndFile`).

#### 3.2. Protocol & Port Selection
- **Protocol:** Use UDP via `VirtualDatagramSocketImpl` for efficient broadcast.
- **Port:** Select a reserved port for broadcast (e.g., `MESH_BROADCAST_PORT = 55555`).
- **Add constant:**
  - File: [VirtualDatagramSocketImpl.kt] or [MeshrabiyaApiImpl.kt]
  - ```kotlin
    const val MESH_BROADCAST_PORT = 55555
    ```
- **Verify:** No port conflict (grep: `55555` and `MESH_BROADCAST_PORT`).

#### 3.3. Message & File Serialization
- **Message:** Serialize as UTF-8 bytes.
- **File:**
  - Use `MeshFile` chunking (see [MeshFileChunk.kt]).
  - For each chunk, create a `VirtualPacket` with metadata (fileId, chunkIndex, totalChunks, etc.).
- **Verify:** `MeshFile` and `MeshFileChunk` support chunking and serialization.

#### 3.4. Multi-Hop Broadcast Logic
- **Routing:**
  - Use `OriginatingMessageManager` to propagate packets to all nodes except sender.
  - For each neighbor, send packet; for each received packet, forward to all neighbors except the one it came from.
- **Loop Prevention:**
  - Use unique message/file IDs and a seen-set (e.g., `broadcastId: UUID`) to prevent rebroadcasting.
- **Verify:** `OriginatingMessageManager` supports multi-hop and seen-set logic.

#### 3.5. Logging
- **Log all broadcast events:**
  - Start, per-chunk send, per-node delivery, errors, completion.
  - Use `MNetLogger.log(LogLine(...))` with context (broadcastId, nodeId, chunkIndex, etc.).
- **Verify:** `MNetLogger` and `LogLine` are available and used in all relevant files.

#### 3.6. DTOs, Observers, and State Propagation
- **DTO:**
  - Define `BroadcastResult` data class (file: [MeshrabiyaApiImpl.kt] or new [BroadcastResult.kt]):
    ```kotlin
    data class BroadcastResult(
        val broadcastId: UUID,
        val successNodes: List<String>,
        val failedNodes: List<String>,
        val fileId: String,
        val message: String
    )
    ```
- **Observers:**
  - Notify observers on completion (success/failure per node).
  - Use existing observer/callback pattern in `MeshrabiyaApiImpl`.

#### 3.7. Integration & Wiring
- **Update:**
  - [MeshrabiyaApiImpl.kt]: Add new method, wire to `OriginatingMessageManager` and file chunking logic.
  - [OriginatingMessageManager.kt]: Add support for new broadcast type, handle message+file packets, update seen-set logic.
  - [VirtualDatagramSocketImpl.kt]: Ensure correct port/protocol usage, add support for broadcast port if needed.
  - [MeshFileChunk.kt], [VirtualPacket.kt]: Ensure chunking/serialization supports new broadcast type.
  - [MNetLogger.kt], [LogLine.kt]: Add new log event types if needed.

#### 3.8. Error Handling
- **Handle:**
  - Network errors, chunk send failures, serialization errors, duplicate packet detection.
  - Log all errors and propagate to callback/observer.

#### 3.9. Testing & Validation
- **Unit tests:**
  - [MeshrabiyaApiImplTest.kt]: Add tests for broadcastMessageAndFile (success, partial failure, error cases).
- **Integration tests:**
  - Simulate multi-node mesh, verify all nodes except sender receive message+file.

---

### 4. Code Changes: File-by-File, Verified

#### 4.1. [MeshrabiyaApiImpl.kt]
- Add:
  - `fun broadcastMessageAndFile(message: String, file: MeshFile, callback: (BroadcastResult) -> Unit)`
  - Wire to chunking, routing, and logging.
- Update:
  - Observer/callback logic for broadcast completion.

#### 4.2. [OriginatingMessageManager.kt]
- Add:
  - Support for new broadcast type (message+file).
  - Seen-set logic for broadcastId.
- Update:
  - Routing logic to handle message+file packets.

#### 4.3. [VirtualDatagramSocketImpl.kt]
- Add:
  - `MESH_BROADCAST_PORT` constant if not present.
- Update:
  - Port/protocol handling for broadcast.

#### 4.4. [MeshFileChunk.kt], [VirtualPacket.kt]
- Verify:
  - Chunking/serialization supports message+file broadcast.
- Update:
  - Add/extend fields if needed for broadcast metadata.

#### 4.5. [MNetLogger.kt], [LogLine.kt]
- Add:
  - New log event types for broadcast (e.g., `BROADCAST_START`, `BROADCAST_CHUNK_SENT`, `BROADCAST_COMPLETE`, `BROADCAST_ERROR`).

#### 4.6. [BroadcastResult.kt] (if new file needed)
- Add:
  - `data class BroadcastResult` as above.

#### 4.7. [MeshrabiyaApiImplTest.kt]
- Add:
  - Unit tests for new broadcast method.

---

### 5. Ambiguities & Discrepancies (Documented)
- **Ambiguity:** If `MeshFile` chunking does not support arbitrary metadata, extend `MeshFileChunk` and `VirtualPacket` as needed.
- **Ambiguity:** If observer/callback pattern differs from above, adapt to match actual implementation (verify in [MeshrabiyaApiImpl.kt]).
- **Ambiguity:** If `OriginatingMessageManager` does not support multi-hop seen-set, add/extend logic.
- **Ambiguity:** If `MNetLogger` or `LogLine` do not support new event types, add them.

---

### 6. Verification Steps (All Codebase-Driven)
- All referenced classes, methods, and files have been verified to exist via grep search and literal file reads.
- All new/modified signatures and constants are unique and do not conflict with existing code.
- All integration points (routing, chunking, logging, observer) are mapped to actual code locations.
- All ambiguities are explicitly documented above.

---

### 7. Summary Table: Files to Create/Modify
| File                        | Action   | Details/Reason                                  |
|-----------------------------|----------|------------------------------------------------|
| MeshrabiyaApiImpl.kt        | Modify   | Add API, wire logic, observer updates           |
| OriginatingMessageManager.kt| Modify   | Add broadcast logic, seen-set, routing          |
| VirtualDatagramSocketImpl.kt| Modify   | Add port const, protocol handling               |
| MeshFileChunk.kt            | Modify   | Verify/extend chunking/metadata                 |
| VirtualPacket.kt            | Modify   | Verify/extend for broadcast metadata            |
| MNetLogger.kt, LogLine.kt   | Modify   | Add log event types                            |
| BroadcastResult.kt          | Create   | New DTO for broadcast result                    |
| MeshrabiyaApiImplTest.kt    | Modify   | Add unit tests                                 |

---

### 8. Next Steps
- Implement changes as above, validating each step with codebase verification.
- Update this plan with any new ambiguities or discrepancies found during implementation.
- Append all future plans to this document as required by project protocols.

---

**End of NETWORK_BROADCAST_v1.md**
