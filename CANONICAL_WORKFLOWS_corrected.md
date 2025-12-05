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

#### 0 DistributedStorageManage start
   - At startup, DistributedSotorage service creates a service keypair, which will be used for encrypting chunks with access for the node storing the file, seprate from the access given to the user of the app.  it should persist till restart.

#### 1.1 Client-Side: File Preparation
**Location**: `DistributedStorageManager.storeFile()`

1. **Prepare permission metadata**:
   - `owner`: Task requester node public key (or local node)
   - `recipients`: List of `RecipientEntry` objects (type: USER or TASK)
   - `accessScope`: TASK_ISOLATED, SERVICE_SHARED, or MESH_GLOBAL
2. **Chunk file**: Split into chunks (size from `MeshrabiyaConstants.getChunkSizeKb()`)
   - Each chunk has: `chunkId`, `fileId`, `chunkIndex`, `totalChunks`, `hash`, `recipientKeyIds`, `sessionKeys`


#### 1.2 Client-Side: Storage Node Discovery
**Location**: `DistributedStorageManager.storeFile()` → `CoreGossipBroadcastService.sendStorageNodeRequest()`

1. **Create StorageNodeRequest** for each chunk:
   - requestId (UUID)
   - chunkId
   - fileId
   - desiredReplicas (based on ReplicationLevel).  Optional field. Desired Relication level for all files should default to the MeshtrabiyaConstants.getReplicaCount()
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
   - Is node a STORAGE_NODE? (EmergentRoleManager) This should be handled in MeshEcosystemListener or even better just ignored by the rouuter if not a STORAGE_NODE.
   -  **Validate quota**: Check if chunk size is within available storage
   - System state healthy? (battery, thermal)
3. **If eligible, send StorageNodeResponse**:
   - nodeId
   - availableSpace
   - totalStorageAllocated
   - systemState
   - meshUrl
   - latencyEstimate
   - fitnessScore
   - DistributedStorageManager  service pubbkey frmo Step 0

#### 1.4 Client-Side: Select Storage Nodes
**Location**: `DistributedStorageManager.storeFile()`

1. **Collect responses** until timeout
2. **Rank candidates** by:
   - System state (healthy preferred)
   - Available space (more preferred)
   - Latency (lower preferred)
   - Fitness score (higher preferred)
3. **Select best  node** 

#### 1.5 Client-Side: Chunk Transfer
**Location**: `DistributedStorageManager.storeFile()` → Connection pool

1. **For  selected storage node**:
   - Acquire connection from MeshConnectionPool
   
   - Using the Chunk created in 1.1, Create ChunkTransferMessage with encrypted chunk data **Encrypt file data**: Use hybrid encryption with per-recipient key encryption
      - Chunk key encrypts data
      - The owner's public key, the selected storage node's DistributedStorageManager service public key and Each recipient's public key encrypts the chunk key
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
   - **replicaCount = value from ChunkTransferMessage** +1
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
    accessScope: AccessScope = AccessScope.TASK_ISOLATED,
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
// replicaCount = Number of nodes that will replicate this chunk AFTER the current node
// 
// Example flow for target=3:
// Client → Storage A (count=0) → stores → replicates with count=1
// Storage A → Storage B (count=1) → stores → replicates with count=2
// Storage B → Storage C (count=2) → stores → replicates with count=3
// Storage C → Storage D (count=3) → stores → STOPS (count >= 3)
```

---

## 2. RETRIEVE FILE WORKFLOW

### Overview
Client node retrieves a file from the distributed mesh network.

### Workflow Steps

#### 2.1 Client-Side: Check Local Storage
**Location**: `DistributedStorageManager.retrieveFile()`



#### 2.2 Client-Side: Query Mesh Network
**Location**: `DistributedStorageManager.retrieveFile()` → `CoreGossipBroadcastService`

1. **Broadcast ChunkRetrievalQuery** for fileId:
   ```kotlin
   coreGossipBroadcastService.sendChunkRetrievalQuery(fileId)
   ```
2. **Wait for responses** within timeout

#### 2.3 Storage Node-Side: Respond to Query
**Location**: Storage node chunk query handler

1. **Check if node has chunks** for fileId and if provided matching and of of the Indexes in the optionally provided list of ChunkIndexes
2. **If found, send ChunkRetrievalResponse**:
   - nodeId
   - fileId
   - List of chunkIds, chunkIndexes
   - systemState
   - latencyEstimate

#### 2.4 Client-Side: Download Chunks
**Location**: `DistributedStorageManager.retrieveFile()`

1. **Build chunk map** from responses
2. **For each chunk**:
   - Select best storage node
   - Request chunk via  connection pool
   - Verify chunk hash
   - Store in retrievedChunks array, which shuld persist and be appended to by retries  until the retrieval succeeds or fails
3. **Verify All Chunks present** 
   - if yes, **Reassemble file** from chunks in order
   - if no, retry Query of Mesh Network for file including missing chunkIndexes as optional paramter
4. **Verify file integrity**
5. **Return file bytes**

#### 2.5 Client-Side: Update UI
**Location**: `DistributedStorageManager.onFileRetrieved` callback

1. **Trigger UI callback**: `onFileRetrieved(fileId, file)` via API
2. fileAccess api function should call DistributedStorageManger  function to get list of those with access to the file.  this list should exclude the DistribtedStorageManager service accounts.

---

## 3. UPDATE FILE ACCESS WORKFLOW

### Overview
Update file permissions without re-encrypting entire file (only re-encrypt chunk keys).

### Workflow Steps


#### 3.3 Client-Side: Broadcast Permission Update
**Location**: `DistributedStorageManager.updateFileAccess()`  → `CoreGossipBroadcastService.sendFilePermissionUpdate()`

1. **Create FilePermissionUpdateMessage**:  recipientes can include tasks
   - fileId
   - addedRecipients
   - removedRecipients
2. **Broadcast to mesh network**:
   ```kotlin
   coreGossipBroadcastService.sendFilePermissionUpdate(message)
   ```

#### 3.4 Storage Node-Side: Update Local Metadata
**Location**: Storage node permission update handler

1. **Receive FilePermissionUpdateMessage**

### 3.5 Storage-Side: Re-encrypt Chunk Keys
**Location**: `StorageEncryptionManager.reEncryptChunkKeys()` (TODO)

1. **For each chunk**:
   - Decrypt chunk key using master key or owner private key
   - For each NEW recipient:
     - Encrypt chunk key with recipient's public key
     - Create session key packet
   - **Update chunk metadata** in StorageDataStore with new sessionKeys:
      - Add new recipientKeyIds and sessionKeys
      - Remove revoked recipientKeyIds
2. **Send confirmation (FileAccessUpdate message )** to client node and all recipients. if recipeient is a task, the message is sent to the comput_node running the task
**Location**: DistributedStorageManger  →  MeshEcosystemListener

#### 3.6 Client-Side: Handle confirmations
**Location**: `MeshEcoSystemListener`  → `DistributedStorageManger` (FUTURE)
Listen for confirmations. If all confirmation for all chunkIndexes not revieved by timeout, retry broadcast (use an option parameter for list of chunkIndexes).  if Retries fail to get remaining confirmations for the outstanding chunks, intitiate storeFIle workflow for the missing chunks, as calculated by the chunksizing, chunkIndex and localFile.
## 4 Storage Node: Check Replica Count
**Location**: Storage node replication logic (AFTER storing chunk)

1. **Get current replica count** current Count = replicaCount from received ChunkTransferMessage +1 (CRITICAL: Increment for next hop)
2. **Check if replication needed**:
   ```kotlin
   // after incrementing replicaCount from message
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
   - **currentReplicaCount = chunk.replicaCount** 
   
2. **Broadcast via CoreGossipBroadcastService**:
   ```kotlin
   coreGossipBroadcastService.sendStorageNodeRequest(request)
   ```
3. **Wait for responses**

#### 4.3 Other Storage Nodes: Respond
**Location**: Standard storage node response logic

1. **Receive StorageNodeRequest**
2. **Check if already storing this chunk** (skip if yes)
3. **Check eligibility** (same as original storage)
4. **Send StorageNodeResponse, IF eligible**

#### 4.4 Storage Node: Select Replica Target
**Location**: Storage node replication logic

1. **Collect responses**
2. **Rank candidates** (same criteria as client-side)
3. **Select best  node** (only one replica is chosen at a time)

#### 4.5 Storage Node: Transfer Chunk to Replicas
**Location**: Storage node replication logic

1. **For each selected replica node**:
   - Acquire connection via connection pool
   - Read chunk from local copy of the chunk
   - Create ChunkTransferMessage with **replicaCount = chunk.replicaCount** (CRITICAL)
   - Send chunk via `virtualNode.sendChunkToNode()`
   - Release connection

#### 4.6 Replica Storage Node: Store Chunk
**Location**: `DistributedStorageManager.handleIncomingChunkStorage()`

1. **Receive chunk** with replicaCount (same as Section 1.6)
2. **Store chunk** to filesystem
3. **Index metadata** incrementing the **replicaCount from transfer message**
4. **Send completion notification**
5. **Check if more replication needed**: if replicaCount < target, goto 4.2 (recursive)

#### 4.7 Natural Termination
**Location**: Any storage node in replication chain

**No explicit update needed** - Count is propagated through transfers:

1. **Storage Node A** receives chunk with replicaCount=x
2. **Stores locally** with count=X+1
3. **Checks**: if count >= target, STOP
4. **Else**: Replicates with count=current count value=X+1
5. **Storage Node B** receives with count=N+1 (repeat from step 1)

**No incrementReplicaCount() function needed** - count increments naturally through the chain. 

### Key Points

- **Replica count starts at 0** when client creates chunk
- **Each storage node receives count N**, stores with count N+1, replicates with count N+1
- **Count propagates through transfer messages**, not through post-storage updates
- **No synchronization needed** - each node increments independently during replication
- **Natural termination** when received count >= target
- **Retry strategy mirrors storage node request** - same timeout, retry count, delay values

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
   - accessScope

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
   - Is node a COMPUTE_NODE? (EmergentRoleManager) This should be handled in MeshEcosystemListener or even better just ignored by the rouuter if not a COMPUTE_NODE.
   - ServiceId maps to a service in service library
   - Has required runtime? (Python, JVM, etc.) based on service profile in library
   - Has required ML capabilities? (ML Kit features) based on service profile in library
   - Queue depth acceptable?
   - System state healthy?
   - is availabe 
3. **If eligible, send ComputeNodeResponse**:
   - nodeAddress
   - currentLoad
   - processing speed
   - ram
   - availabe storage
   - mlKitFeatures
   - estimatedLatencyMs

#### 5.4 Client-Side: Select Compute Node
**Location**: `IntelligentDistributedComputeService.handleComputeNodeResponses()`

1. **Collect responses** until timeout
2. **Filter responses**: (may require chagnesi n what is sent in reponse,  and captured device metrics on the preceding storage side)
   - has required ML capabilities, if required by task
   - Sufficinent Memeory 
   - Sufficient Disk
   - fastest processing
   - laensy  (lower importance)
3. **Rank candidates** by:
   - currentLoad (ascending - prefer less loaded)
   - processing speed
   - ram
   - availabe storage
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
Compute node receives notification that task data access permissions changed (FileAccessUpdate).

### Workflow Steps

#### 6.1 Compute-Side: Receives File Access Update (FileAccessUpdate)
**Location**:  MeshEcosystemListener  →  `IntelligentDistributedComputeService.handleTaskDataAccessUpdate()`

1. **Receive TaskDataAccessUpdateMessage**
2. **Check if running affected task**: (TaskManager)
   - Look up taskId in active executions
3. **if running, Update task execution context**: (TaskManager)
   - Update AccessScope (what is AccessScope for?)
   - Retrieve new file (get chunks and reasseble) to which the task with Retrieve File workflow (make a call to `DistributedStoraveManager.retrieveFile` )
   - decrypt file with the task key 
   - add the decrypted file to the task sandbox
   - trigger newFile event in the sandbox 
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
       accessScope = AccessScope.TASK_ISOLATED,
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
3. For each file in the Manifest send FileAccessUpdate with addedRecipients containing all the recipients including the owner

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
| **Update File Access** | `DistributedStorageManager.updateFileAccess()` | `CoreGossipBroadcastService.sendFilePermissionUpdate()` | Permission update handler | N/A |
| **Replicate File** | Storage node after store | `CoreGossipBroadcastService.sendStorageNodeRequest()` | `handleIncomingChunkStorage()` | N/A |
| **Add Task** | `IntelligentDistributedComputeService.processTaskRequest()` | `CoreGossipBroadcastService.sendComputeTaskRequest()` (FIX NEEDED) | N/A | `handleIncomingComputeTaskRequest()` |
| **Access Update** | Client/TaskManager | `CoreGossipBroadcastService.sendTaskDataAccessUpdate()` | N/A | `handleTaskDataAccessUpdate()` |
| **Task Complete** | Compute node executor | Direct message (not broadcast) | N/A | `handleTaskCompletionMessage()` |

---

## KEY ARCHITECTURAL PRINCIPLES

1. **Client-side vs Server-side separation**:
   - Client functions prepare requests and handle responses
   - Storage/compute node functions handle incoming requests and execute operations and sometimes act as a client as in the case of remplication and task completion output file writing
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
   - Count incremented **before storage**, 
   - Example: Client(0)→StorageA(1→1)→StorageB(2→2)→StorageC(3→3)→STOP(3≥3)
   - No synchronization needed - trust propagation model
   - Replication stops when received count >= target
   - **CRITICAL** for preventing infinite replication loops, also limited by the fact that only storage nodes that dont have a given chunk will respond

4. **Permission propagation**:
   - Owner and recipients set at initial storage
   - Passed unchanged during replication
   - Updated via updateFileAccess() for permission changes
   - Task results use requester as owner, task as ephemeral recipient

5. **Direct messages vs broadcasts**:
   - Discovery: Broadcast  with retry(storage nodes, compute nodes)
   - Assignment: Direct message with retry (task assignment)
   - Completion: Direct message with retry (task completion)
   - Permissions: Broadcast (file access updates)
   - Permissions Update Update Notification: DIrect Messages to Recipients (incuding tasks) and Owner (FileAccessUpdate)

---

**This document defines the canonical workflows for all distributed storage and compute operations in the Meshrabiya mesh network. All implementations must follow these workflows exactly.**
