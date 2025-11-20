# CANONICAL WORKFLOWS DOCUMENTATION

**Date**: November 18, 2025  
**Purpose**: Define the canonical workflows for distributed storage and compute operations in the Meshrabiya mesh network  
**Status**: COMPREHENSIVE REFERENCE

---

## TABLE OF CONTENTS

1. [Store File Workflow](#1-store-file-workflow)
2. [Retrieve File Workflow](#2-retrieve-file-workflow)
3. [Update File Access Workflow](#3-update-file-access-workflow)
4. [File Replication Workflow](#4-file-replication-workflow)
5. [Add Task (Compute Request) Workflow](#5-add-task-compute-request-workflow)
6. [Task Manager Receives Access Update Notice](#6-task-manager-receives-access-update-notice)
7. [Completed Task Workflow](#7-completed-task-workflow)

---

## 1. STORE FILE WORKFLOW

### Overview
Client node stores a file to the distributed mesh network with encryption and replication.

### Actors
- **Client Node**: Node requesting file storage
- **Storage Nodes**: Nodes providing storage capacity
- **CoreGossipBroadcastService**: Handles UDP broadcast serialization
- **MeshGossipService**: Manages UDP transport layer
- **DistributedStorageManager**: Orchestrates storage operations

### Workflow Steps

#### 0. DistributedStorageManager Startup
**Location**: `DistributedStorageManager` initialization

- At startup, DistributedStorageManager service creates a service keypair, which will be used for encrypting chunks with access for the node storing the file, separate from the access given to the user of the app
- This keypair is stored in memory only (does not need to persist across restarts)
- One keypair per DistributedStorageManager instance, used for ALL chunks that require it to sign/encrypt

#### 1.1 Client-Side: File Preparation
**Location**: `DistributedStorageManager.storeFile()`

1. **Prepare permission metadata**:
   - `owner`: Task requester node public key (or local node)
   - `recipients`: List of `RecipientEntry` objects (type: USER or TASK)
2. **Chunk file**: Split into chunks (size from `MeshrabiyaConstants.getChunkSizeKb()`)
   - Each chunk has: `chunkId`, `fileId`, `chunkIndex`, `totalChunks`, `hash`, `recipientKeyIds`, `sessionKeys`
   - Chunks are NOT encrypted yet (encryption happens in step 1.5 after storage node selection)

#### 1.2 Client-Side: Storage Node Discovery
**Location**: `DistributedStorageManager.storeFile()` → `CoreGossipBroadcastService.sendStorageNodeRequest()`

1. **Create StorageNodeRequest** for each chunk:
   - requestId (UUID)
   - chunkId
   - chunkIndex
   - fileId
   - desiredReplicas (based on ReplicationLevel) - **Optional field**. Desired replication level for all files should default to `MeshrabiyaConstants.getReplicaCount()`
   - chunkSizeBytes
2. **Broadcast request via CoreGossipBroadcastService**:
   ```kotlin
   coreGossipBroadcastService.sendStorageNodeRequest(request)
   ```
3. **Wait for responses** within timeout window (`MeshrabiyaConstants.getTimeoutMs()`)

#### 1.3 Storage Node-Side: Receive Broadcast & Respond
**Location**: `MeshEcosystemListener` → Storage node logic in `DistributedStorageManager`

1. **Receive StorageNodeRequest broadcast** via UDP
2. **Check eligibility**:
   - Is node a STORAGE_NODE? (EmergentRoleManager) - This should be handled in MeshEcosystemListener or even better just ignored by the router if not a STORAGE_NODE
   - **Validate quota**: Check if chunk size is within available storage
   - System state healthy? (battery, thermal)
3. **If eligible, send StorageNodeResponse**:
   - nodeId
   - availableSpace
   - totalStorageAllocated (secondary/tertiary ranking factor)
   - systemState
   - meshUrl
   - latencyEstimate
   - fitnessScore
   - DistributedStorageManager service public key from Step 0

#### 1.4 Client-Side: Select Storage Node
**Location**: `DistributedStorageManager.storeFile()`

1. **Collect responses** until timeout
2. **Rank candidates** by:
   - System state (healthy preferred)
   - Available space (more preferred)
   - Latency (lower preferred)
   - Fitness score (higher preferred)
   - Total storage allocated (secondary/tertiary factor)
3. **Select best node** (singular - only ONE storage node per chunk, replication handled via daisy-chaining)

#### 1.5 Client-Side: Chunk Transfer
**Location**: `DistributedStorageManager.storeFile()` → Connection pool

1. **For selected storage node**:
   - Acquire connection from MeshConnectionPool
   - Using the Chunk created in 1.1, Create ChunkTransferMessage with encrypted chunk data
   - **Encrypt file data**: Use hybrid encryption with per-recipient key encryption
     - Chunk key encrypts data
     - The owner's public key, the selected storage node's DistributedStorageManager service public key, and each recipient's public key encrypts the chunk key
   - Send chunk via `virtualNode.sendChunkToNode(nodeId, chunk, bytes)`
   - Release connection

#### 1.6 Storage Node-Side: Receive Chunk & Store
**Location**: NEW FUNCTION NEEDED: `DistributedStorageManager.handleIncomingChunkStorage()`

**THIS IS THE MISSING STORAGE NODE HANDLER**

1. **Receive ChunkTransferMessage** via connection pool
2. **Validate chunk**:
   - Verify hash matches
   - Check encryption metadata
3. **Write chunk to local filesystem**:
   - Path: `<storage_area>/<fileId>/<chunkId>`
4. **Index chunk metadata** in StorageDataStore:
   - chunkId, fileId, chunkIndex, totalChunks
   - fileName, relativePath
   - recipientKeyIds, sessionKeys
   - **replicaCount = (value from ChunkTransferMessage) + 1** (CRITICAL: Increment before storing)
5. **Send completion notification** to client node
6. **Initiate replication workflow** (see Section 4) if replicaCount < target

#### 1.7 Client-Side: Update UI
**Location**: `DistributedStorageManager.onFileStored` callback

1. **Receive completion notifications** for all chunks
2. **Update StagedSyncManager** with chunk IDs and node IDs
3. **Store FileMetadata** with permissions
4. **Trigger UI callback**: via the API `onFileStored(fileId, file)`
5. **Update storage stats**

### Key Data Structures

```kotlin
// DistributedStorageManager.storeFile() signature
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
    owner: String? = null,
    recipients: List<RecipientEntry>? = null
): FileReference?

// MeshChunk structure
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long,
    val recipientKeyIds: List<Long>,
    val sessionKeys: Map<Long, ByteArray>,
    var replicaCount: Int = 0  // CRITICAL: Track replication progress
)

// Replica count interpretation (CANONICAL):
// replicaCount = The replica number stored on this node
// 
// Example flow for target=3:
// Client → Storage A (receives count=0, stores as count=1, sends count=1)
// Storage A → Storage B (receives count=1, stores as count=2, sends count=2)
// Storage B → Storage C (receives count=2, stores as count=3, sends count=3)
// Storage C checks: count=3 >= target=3, STOPS replication
```

---

## 2. RETRIEVE FILE WORKFLOW

### Overview
Client node retrieves a file from the distributed mesh network.

**IMPORTANT**: There is NO local storage copy beyond the original file which the user places initially in the drop folder.

### Workflow Steps

#### 2.1 Client-Side: Query Mesh Network
**Location**: `DistributedStorageManager.retrieveFile()` → `CoreGossipBroadcastService`

1. **Broadcast ChunkRetrievalQuery** for fileId:
   ```kotlin
   coreGossipBroadcastService.sendChunkRetrievalQuery(
       fileId = fileId,
       chunkIndexes = null  // Optional: specify missing chunks on retry
   )
   ```
2. **Wait for responses** within timeout

#### 2.2 Storage Node-Side: Respond to Query
**Location**: Storage node chunk query handler

1. **Check if node has chunks** for fileId
   - If `chunkIndexes` parameter provided: only respond if node has matching chunks from that list
   - If `chunkIndexes` is null: respond with all chunks for this fileId
2. **If found, send ChunkRetrievalResponse**:
   - nodeId
   - fileId
   - List of chunkIds, chunkIndexes
   - systemState
   - latencyEstimate

#### 2.3 Client-Side: Download Chunks
**Location**: `DistributedStorageManager.retrieveFile()`

1. **Build chunk map** from responses
2. **For each chunk**:
   - Select best storage node
   - Request chunk via connection pool
   - Verify chunk hash
   - Store in `retrievedChunks` array, which should persist and be appended to by retries until the retrieval succeeds or fails
3. **Verify All Chunks present**:
   - **If yes**: Reassemble file from chunks in order
   - **If no**: Retry Query of Mesh Network for file, including missing chunkIndexes as optional parameter
4. **Verify file integrity**
5. **Return file bytes**

#### 2.4 Client-Side: Update UI
**Location**: `DistributedStorageManager.onFileRetrieved` callback

1. **Trigger UI callback**: `onFileRetrieved(fileId, file)` via API
2. **fileAccess API function** should call DistributedStorageManager function to get list of those with access to the file
   - This list should **exclude the DistributedStorageManager service accounts** (only show user/task recipients)

---

## 3. UPDATE FILE ACCESS WORKFLOW

### Overview
Update file permissions without re-encrypting entire file (only re-encrypt chunk keys).

**CRITICAL ARCHITECTURAL CHANGE**: Re-encryption happens on storage nodes, not client-side. This avoids re-transferring chunks and avoids the need for the client to know where all chunks are stored.

### Workflow Steps

#### 3.1 Client-Side: Broadcast Permission Update
**Location**: `DistributedStorageManager.updateFileAccess()` → `CoreGossipBroadcastService.sendFilePermissionUpdate()`

1. **Create FilePermissionUpdateMessage**:
   - fileId
   - addedRecipients (can include tasks)
   - removedRecipients
   - Optional: chunkIndexes (used for retry of specific chunks)
2. **Broadcast to mesh network**:
   ```kotlin
   coreGossipBroadcastService.sendFilePermissionUpdate(message)
   ```

#### 3.2 Storage Node-Side: Receive Permission Update
**Location**: Storage node permission update handler in `DistributedStorageManager`

1. **Receive FilePermissionUpdateMessage**
2. **Check if node stores chunks** for this fileId
3. **If yes, proceed to re-encryption** (Section 3.3)

#### 3.3 Storage Node-Side: Re-encrypt Chunk Keys
**Location**: `StorageEncryptionManager.reEncryptChunkKeys()` (TODO)

1. **For each chunk** this node stores for the fileId:
   - Decrypt chunk key using the storage node's DistributedStorageManager service private key
   - For each NEW recipient:
     - Encrypt chunk key with recipient's public key
     - Create session key packet
   - For each REMOVED recipient:
     - Remove their session key entry
2. **Update chunk metadata** in StorageDataStore with new sessionKeys:
   - Add new recipientKeyIds and sessionKeys
   - Remove revoked recipientKeyIds
3. **Send confirmation** (FileAccessUpdateConfirmation message) to client node and all recipients
   - If recipient is a task, the message is sent to the compute node running the task
   - Include: nodeId, fileId, chunkIndexes confirmed

#### 3.4 Client-Side: Handle Confirmations
**Location**: `MeshEcosystemListener` → `DistributedStorageManager` (FUTURE)

1. **Listen for confirmations** (FileAccessUpdateConfirmation messages)
2. **Track confirmed chunks** by chunkIndex
3. **If all confirmations for all chunkIndexes NOT received by timeout**:
   - Retry broadcast with optional parameter containing list of missing chunkIndexes
4. **If retries fail** to get remaining confirmations for outstanding chunks:
   - Initiate storeFile workflow for the missing chunks, as calculated by the chunk sizing, chunkIndex and local file
   - This re-stores chunks to fresh nodes with updated permissions

---

## 4. FILE REPLICATION WORKFLOW

### Overview
Storage nodes automatically replicate chunks to achieve target redundancy via daisy-chaining.

### Workflow Steps

#### 4.1 Storage Node: Check Replica Count
**Location**: Storage node replication logic (AFTER storing chunk)

1. **Get current replica count**:
   - Current count = (replicaCount from received ChunkTransferMessage) + 1
   - This is the count that was stored in step 1.6
2. **Check if replication needed**:
   ```kotlin
   // After incrementing replicaCount from message
   if (chunk.replicaCount >= MeshrabiyaConstants.getReplicaCount()) {
       // Stop - target already reached
       betaLogger.log("Replication complete: count=${chunk.replicaCount} >= target")
   } else {
       // Proceed with replication
   }
   ```

#### 4.2 Storage Node Acts as Client: Broadcast Storage Request
**Location**: Storage node replication workflow

**CRITICAL**: Storage node now acts like a CLIENT node

1. **Create StorageNodeRequest** for the chunk:
   - Same owner and recipients as original
   - requestId (new UUID for replication)
   - chunkId, fileId
   - **currentReplicaCount = chunk.replicaCount** (send current count, receiving node will increment)
   - chunkSizeBytes
2. **Broadcast via CoreGossipBroadcastService**:
   ```kotlin
   coreGossipBroadcastService.sendStorageNodeRequest(request)
   ```
3. **Wait for responses**

#### 4.3 Other Storage Nodes: Respond
**Location**: Standard storage node response logic

1. **Receive StorageNodeRequest**
2. **Check if already storing this chunk** (skip if yes - prevents duplicate storage and infinite loops)
3. **Check eligibility** (same as original storage in Section 1.3)
4. **Send StorageNodeResponse, IF eligible**

#### 4.4 Storage Node: Select Replica Target
**Location**: Storage node replication logic

1. **Collect responses**
2. **Rank candidates** (same criteria as client-side)
3. **Select best node** (only ONE replica is chosen at a time - daisy-chain architecture)

#### 4.5 Storage Node: Transfer Chunk to Replica
**Location**: Storage node replication logic

1. **For selected replica node**:
   - Acquire connection via connection pool
   - Read chunk from local copy of the chunk
   - Create ChunkTransferMessage with **replicaCount = chunk.replicaCount** (CRITICAL: send current stored count)
   - Send chunk via `virtualNode.sendChunkToNode()`
   - Release connection

#### 4.6 Replica Storage Node: Store Chunk
**Location**: `DistributedStorageManager.handleIncomingChunkStorage()`

1. **Receive chunk** with replicaCount (same as Section 1.6)
2. **Store chunk** to filesystem
3. **Index metadata**, incrementing the **replicaCount from transfer message** (receives N, stores as N+1)
4. **Send completion notification**
5. **Check if more replication needed**: if replicaCount < target, goto 4.1 (recursive)

#### 4.7 Natural Termination
**Location**: Any storage node in replication chain

**No explicit update needed** - Count is propagated through transfers:

1. **Storage Node A** receives chunk with replicaCount=X
2. **Stores locally** with count=X+1
3. **Checks**: if count >= target, STOP
4. **Else**: Replicates with count=X+1 (sends current stored count)
5. **Storage Node B** receives with count=X+1 (repeat from step 1)

**No incrementReplicaCount() function needed** - count increments naturally through the chain.

### Key Points

- **Replica count starts at 0** when client creates chunk
- **Each storage node receives count N**, stores with count N+1, replicates with count N+1
- **Count incremented before storage**, stored value represents which replica this is (1st, 2nd, 3rd, etc.)
- **Count propagates through transfer messages**, not through post-storage updates
- **No synchronization needed** - each node increments independently during storage
- **Natural termination** when stored count >= target
- **Retry strategy mirrors storage node request** - same timeout, retry count, delay values
- **Infinite loop prevention**: Storage nodes that already have a chunk won't respond to replication requests

---

## 5. ADD TASK (COMPUTE REQUEST) WORKFLOW

### Overview
Client node requests compute task execution on mesh network.

### Workflow Steps

#### 5.1 Client-Side: Prepare Task Request
**Location**: Client application logic

1. **Create LocalComputeTaskRequest**:
   - taskId (UUID)
   - taskType (PYTHON, JAVA, ML_NATIVE, etc.)
   - jobType (IMAGE_PROCESSING, ML_INFERENCE, etc.)
   - inputParams
   - resourceLimits
   - requesterNodeId

#### 5.2 Client-Side: Process Task Request
**Location**: `IntelligentDistributedComputeService.processTaskRequest()`

1. **Track request** in activeRequests map
2. **Create tracked request** with status PENDING
3. **Broadcast via CoreGossipBroadcastService**:
   ```kotlin
   coreGossipBroadcastService.sendComputeTaskRequest(
       taskId = request.taskId,
       serviceId = request.serviceId,
       inputParams = request.inputParams,
       metadata = request.metadata
   )
   ```
4. **Wait for responses** asynchronously via `handleComputeNodeResponse()` callbacks

#### 5.3 Compute Node-Side: Receive Broadcast & Respond
**Location**: `IntelligentDistributedComputeService.handleIncomingComputeTaskRequest()`

1. **Receive ComputeTaskRequestMessage** via UDP
2. **Check eligibility**:
   - Is node a COMPUTE_NODE? (EmergentRoleManager) - This should be handled in MeshEcosystemListener or even better just ignored by the router if not a COMPUTE_NODE
   - ServiceId maps to a service in service library
   - Has required runtime? (Python, JVM, etc.) based on service profile in library
   - Has required ML capabilities? (ML Kit features) based on service profile in library
   - Queue depth acceptable?
   - System state healthy?
   - Is available
3. **If eligible, send ComputeNodeResponse**:
   - nodeAddress
   - currentLoad
   - processing speed
   - ram
   - available storage
   - mlKitFeatures
   - estimatedLatencyMs

#### 5.4 Client-Side: Select Compute Node
**Location**: `IntelligentDistributedComputeService.handleComputeNodeResponses()`

1. **Collect responses** until timeout
2. **Filter responses**:
   - Has required ML capabilities, if required by task
   - Sufficient memory
   - Sufficient disk
   - Fastest processing
   - Latency (lower importance)
3. **Rank candidates** by:
   - currentLoad (ascending - prefer less loaded)
   - processing speed (descending - prefer faster)
   - ram (descending - prefer more RAM)
   - available storage (descending - prefer more storage)
   - estimatedLatencyMs (ascending - prefer faster)
   - mlKitFeatures.size (descending - prefer more capable), if needed
4. **Select best node** (first in ranked list)
5. **If no capable nodes**: Retry with backoff

#### 5.5 Client-Side: Assign Task
**Location**: `IntelligentDistributedComputeService.assignTaskToNode()`

1. **Update tracked request**:
   - status = ASSIGNED
   - selectedNodeAddress = chosen node
2. **Create TaskAssignmentMessage**:
   - taskId
   - taskExecutionContext (code bundle, inputs, limits)
   - requesterNodeId
   - callbackAddress
3. **Send direct message** to selected compute node:
   ```kotlin
   virtualNode.sendDirectMessage(nodeAddress, assignmentMessage.toBytes())
   ```

#### 5.6 Compute Node-Side: Execute Task
**Location**: `IntelligentDistributedComputeService.handleTaskAssignmentMessage()`

1. **Receive TaskAssignmentMessage**
2. **Validate resources** still available
3. **Send TaskAcceptanceMessage** to client
4. **Execute task** (see Section 7)
5. **Send TaskCompletedMessage** when done

---

## 6. TASK MANAGER RECEIVES ACCESS UPDATE NOTICE

### Overview
Compute node receives notification that task data access permissions changed (FileAccessUpdateConfirmation).

### Workflow Steps

#### 6.1 Compute Node-Side: Receive File Access Update
**Location**: `MeshEcosystemListener` → `IntelligentDistributedComputeService.handleTaskDataAccessUpdate()`

1. **Receive FileAccessUpdateConfirmation message**
2. **Check if running affected task** (TaskManager):
   - Look up taskId in active executions
3. **If running, update task execution context** (TaskManager):
   - Retrieve new file (get chunks and reassemble) using Retrieve File workflow
     - Make a call to `DistributedStorageManager.retrieveFile(fileId)`
   - Decrypt file with the task key
   - Add the decrypted file to the task sandbox
   - Trigger newFile event in the sandbox
4. **Log update** for audit trail

---

## 7. COMPLETED TASK WORKFLOW

### Overview
Compute node completes task execution and notifies client with results.

### Workflow Steps

#### 7.1 Compute Node: Task Execution Finishes
**Location**: Task executor (PythonExecutor, JVMExecutor, etc.)

1. **Task completes** (success or failure)
2. **Collect execution metrics**:
   - executionTimeMs
   - resourcesUsed (RAM, CPU, disk)
   - containerId
3. **Collect output files** (if any):
   - Generate FileReference for each output
   - Files already stored via SandboxStorageProxy during execution

#### 7.2 Compute Node: Store Results to Distributed Storage
**Location**: Task executor → `DistributedStorageManager.storeFile()`

**CRITICAL**: Results must be stored with TASK permissions

1. **For each output file**:
   ```kotlin
   val fileRef = distributedStorageManager.storeFile(
       path = outputPath,
       data = outputBytes,
       priority = SyncPriority.HIGH,
       replicationLevel = ReplicationLevel.STANDARD,
       owner = taskContext.requesterNodeId,  // CRITICAL
       recipients = listOf(
           RecipientEntry(
               publicKey = taskContext.requesterNodeId,
               recipientType = RecipientType.USER
           ),
           RecipientEntry(
               publicKey = taskContext.taskId,
               recipientType = RecipientType.TASK,
               expiresAt = System.currentTimeMillis() + taskTimeout,
               taskId = taskContext.taskId
           )
       )
   )
   ```
2. **Build OutputManifest** with all FileReferences
3. **For each file in the Manifest**: send FilePermissionUpdateMessage with addedRecipients containing all the recipients including the owner

#### 7.3 Compute Node: Send Completion Notification
**Location**: `IntelligentDistributedComputeService.sendTaskCompletion()`

1. **Create TaskCompletedMessage**:
   - taskId
   - success (true/false)
   - resultManifest (list of FileReferences)
   - resultMessage (optional)
   - executionStats (time, resources, containerId)
   - error (if failure)
2. **Send direct message** to client node:
   ```kotlin
   virtualNode.sendDirectMessage(
       clientNodeAddress, 
       completionMessage.toBytes()
   )
   ```
3. **Start retry loop** (if client offline):
   - Retry every `TASK_COMPLETION_RETRY_INTERVAL_MS` (30 sec)
   - For up to `TASK_COMPLETION_RETRY_PERIOD_MS` (5 min)
   - Stop after timeout or successful ACK

#### 7.4 Client-Side: Receive Completion
**Location**: `IntelligentDistributedComputeService.handleTaskCompletionMessage()`

1. **Receive TaskCompletedMessage**
2. **Validate taskId** matches tracked request
3. **Update tracked request**:
   - status = COMPLETED or FAILED
   - result = completion message
4. **Download result files** (if any):
   - For each FileReference in resultManifest:
     - Call `DistributedStorageManager.retrieveFile(fileRef)`
5. **Send TaskCompletionAckMessage** to compute node
6. **Trigger completion callback**:
   ```kotlin
   onTaskCompleted?.invoke(taskId, result)
   ```

#### 7.5 Compute Node: Receive ACK
**Location**: `IntelligentDistributedComputeService.handleTaskCompletionAckMessage()`

1. **Receive TaskCompletionAckMessage**
2. **Stop retry loop** for this taskId
3. **Clean up task resources**:
   - Remove tracked execution
   - Clean up temporary files
   - Release sandbox resources

#### 7.6 Compute Node: Cleanup After Timeout
**Location**: Retry loop timeout handler

**If no ACK received within `TASK_COMPLETION_RETRY_PERIOD_MS`**:

1. **Log failure**: "Task $taskId completion notification failed after 5 minutes"
2. **Stop retrying**
3. **Clean up task resources** (same as 7.5)

---

## WORKFLOW SUMMARY TABLE

| Workflow | Client Entry Point | Broadcast Function | Storage Node Handler | Compute Node Handler |
|----------|-------------------|-------------------|---------------------|---------------------|
| **Store File** | `DistributedStorageManager.storeFile()` | `CoreGossipBroadcastService.sendStorageNodeRequest()` | `handleIncomingChunkStorage()` (NEW) | N/A |
| **Retrieve File** | `DistributedStorageManager.retrieveFile()` | `CoreGossipBroadcastService.sendChunkRetrievalQuery()` | Chunk query handler | N/A |
| **Update File Access** | `DistributedStorageManager.updateFileAccess()` | `CoreGossipBroadcastService.sendFilePermissionUpdate()` | Permission update handler + re-encryption | N/A |
| **Replicate File** | Storage node after store | `CoreGossipBroadcastService.sendStorageNodeRequest()` | `handleIncomingChunkStorage()` | N/A |
| **Add Task** | `IntelligentDistributedComputeService.processTaskRequest()` | `CoreGossipBroadcastService.sendComputeTaskRequest()` | N/A | `handleIncomingComputeTaskRequest()` |
| **Access Update Notice** | Storage node after re-encryption | Direct message (FileAccessUpdateConfirmation) | N/A | `handleTaskDataAccessUpdate()` |
| **Task Complete** | Compute node executor | Direct message (not broadcast) | N/A | `handleTaskCompletionMessage()` |

---

## KEY ARCHITECTURAL PRINCIPLES

1. **Client-side vs Server-side separation**:
   - Client functions prepare requests and handle responses
   - Storage/compute node functions handle incoming requests and execute operations
   - Sometimes nodes act as clients (storage nodes during replication, compute nodes during result file storage)
   - **DON'T MIX**: `storeFile()` should only contain client logic

2. **Broadcast via CoreGossipBroadcastService**:
   - All mesh-wide broadcast requests go through CoreGossipBroadcastService
   - Access via `virtualNode.coreGossipBroadcastService` property
   - Instantiated in VirtualNode constructor
   - This ensures proper serialization, deduplication, and UDP transport
   - **DON'T CALL** MeshGossipService.broadcast* methods directly

3. **Replica count propagation** (CANONICAL):
   - Client always creates chunks with replicaCount = 0
   - Each storage node **receives count N**, stores with count N+1, **replicates with count N+1**
   - Count incremented **before storage** (stored count represents which replica: 1st, 2nd, 3rd, etc.)
   - Example: Client sends(0) → StorageA receives(0), stores(1), sends(1) → StorageB receives(1), stores(2), sends(2) → StorageC receives(2), stores(3), sends(3) → STOP(3≥3)
   - No synchronization needed - trust propagation model
   - Replication stops when stored count >= target
   - **CRITICAL** for preventing infinite replication loops - also limited by the fact that only storage nodes that don't have a given chunk will respond

4. **Permission propagation**:
   - Owner and recipients set at initial storage
   - Passed unchanged during replication
   - Updated via updateFileAccess() for permission changes (re-encryption happens on storage nodes)
   - Task results use requester as owner, task as ephemeral recipient

5. **Direct messages vs broadcasts**:
   - **Discovery**: Broadcast with retry (storage nodes, compute nodes)
   - **Assignment**: Direct message with retry (task assignment)
   - **Completion**: Direct message with retry (task completion)
   - **Permission updates**: Broadcast (FilePermissionUpdate to all storage nodes)
   - **Permission confirmations**: Direct messages to recipients including tasks and owner (FileAccessUpdateConfirmation)

6. **Daisy-chain architecture**:
   - Storage: Client sends to ONE storage node, which replicates to ONE more node, which replicates to ONE more node, etc.
   - This distributes the load and avoids overwhelming the client or any single node
   - Each node only needs to know about the next node in the chain, not all replicas

7. **Storage-side re-encryption**:
   - Permission updates trigger re-encryption on storage nodes, not clients
   - Avoids re-transferring entire chunks across the network
   - Client doesn't need to know where all chunks are stored
   - Storage nodes confirm updates via direct messages

8. **No local caching**:
   - Beyond the original file in the drop folder, there is NO local storage copy
   - All retrievals go to the mesh network
   - This ensures consistency and avoids cache invalidation issues

---

**This document defines the canonical workflows for all distributed storage and compute operations in the Meshrabiya mesh network. All implementations must follow these workflows exactly.**
