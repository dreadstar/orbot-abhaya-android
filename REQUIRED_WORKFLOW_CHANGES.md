# REQUIRED WORKFLOW CHANGES DOCUMENT

**Date**: November 18, 2025  
**Purpose**: Document specific changes needed to align current implementation with canonical workflows  
**Status**: COMPREHENSIVE CHANGE SPECIFICATION  
**Reference**: CANONICAL_WORKFLOWS.md

---

## TABLE OF CONTENTS

1. [Executive Summary](#executive-summary)
2. [Issue 1: processTaskRequest Broadcast Pattern](#issue-1-processtaskrequest-broadcast-pattern)
3. [Issue 2: storeFile Mixed Client/Server Logic](#issue-2-storefile-mixed-clientserver-logic)
4. [Issue 3: Missing Storage Node Handler](#issue-3-missing-storage-node-handler)
5. [Issue 4: Replica Count Tracking](#issue-4-replica-count-tracking)
6. [Issue 5: Replication Workflow](#issue-5-replication-workflow)
7. [Implementation Checklist](#implementation-checklist)

---

## EXECUTIVE SUMMARY

### Problems Identified

1. **processTaskRequest()** is calling `broadcastComputeTaskRequestSync()` directly instead of using CoreGossipBroadcastService
2. **storeFile()** mixes client-side request logic with storage node logic
3. **No separate storage node handler** for receiving and storing chunks
4. **Replica count field missing** in MeshChunk data structure
5. **Replication workflow** needs storage nodes to act as clients

### Impact

- Inconsistent broadcast patterns between storage and compute
- Difficult to maintain separate client/server concerns
- Replication loop prevention not implemented
- Cannot track when to stop replicating chunks

### Solution Approach

1. Refactor processTaskRequest to use CoreGossipBroadcastService
2. Split storeFile into client-side and storage-node-side functions
3. Create new handleIncomingChunkStorage() function
4. Add replicaCount field to MeshChunk
5. Implement storage node replication workflow

---

## ISSUE 1: processTaskRequest Broadcast Pattern

### Current Implementation

**Location**: `IntelligentDistributedComputeService.kt` lines 119-133

```kotlin
fun processTaskRequest(localRequest: LocalComputeTaskRequest) {
    val taskId = localRequest.mmcpRequest.taskId
    activeRequests[taskId] = TrackedRequest(localRequest)
    
    scope.launch {
        betaLogger?.log(LogLevel.INFO, "ComputeService", 
            "Broadcasting compute task request $taskId (timeout=${MeshrabiyaConstants.getTimeoutMs()}ms)")
        
        // PROBLEM: Direct call to MeshGossipService
        val responses = virtualNode.getMeshGossipService().broadcastComputeTaskRequestSync(
            localRequest.mmcpRequest,
            MeshrabiyaConstants.getTimeoutMs()
        )
        
        handleComputeNodeResponses(localRequest, responses)
    }
}
```

### Problems

1. **Inconsistent pattern**: Storage uses `CoreGossipBroadcastService.sendStorageNodeRequest()`, but compute calls MeshGossipService directly
2. **Bypasses broadcast service**: Skips message serialization, deduplication, and proper layering
3. **Tight coupling**: Directly coupled to MeshGossipService implementation details

### Required Changes

**File**: `IntelligentDistributedComputeService.kt`

#### Change 1.1: Access CoreGossipBroadcastService from VirtualNode

```kotlin
class IntelligentDistributedComputeService(
    private val virtualNode: VirtualNode,
    private val pythonExecutor: PythonExecutor,
    private val emergentRoleManager: com.ustadmobile.meshrabiya.vnet.EmergentRoleManager,
    private val betaLogger: BetaTestLogger? = null
) {
    // Access via VirtualNode (no constructor parameter needed)
    private val coreGossipBroadcastService = virtualNode.coreGossipBroadcastService
    
    // ... existing code ...
}
```

#### Change 1.2: Refactor processTaskRequest

```kotlin
fun processTaskRequest(localRequest: LocalComputeTaskRequest) {
    val taskId = localRequest.mmcpRequest.taskId
    activeRequests[taskId] = TrackedRequest(localRequest)
    
    scope.launch {
        betaLogger?.log(LogLevel.INFO, "ComputeService", 
            "Broadcasting compute task request $taskId (timeout=${MeshrabiyaConstants.getTimeoutMs()}ms)")
        
        // FIXED: Use CoreGossipBroadcastService pattern
        coreGossipBroadcastService.sendComputeTaskRequest(
            taskId = localRequest.mmcpRequest.taskId,
            serviceId = localRequest.mmcpRequest.serviceId,
            inputParams = localRequest.mmcpRequest.inputParams,
            metadata = localRequest.mmcpRequest.metadata
        )
        
        // Wait for responses via MeshEcosystemListener callbacks
        // Responses will arrive via handleComputeNodeResponse()
        delay(MeshrabiyaConstants.getTimeoutMs())
        
        // After timeout, evaluate collected responses
        val tracked = activeRequests[taskId] ?: return@launch
        handleComputeNodeResponses(localRequest, tracked.responses)
    }
}
```

#### Change 1.3: Update Response Collection

**Current**: Responses returned synchronously from broadcast call  
**New**: Responses collected asynchronously via MeshEcosystemListener

```kotlin
/**
 * Handle single compute node response (called by MeshEcosystemListener).
 * Routes individual response to the appropriate tracked request.
 */
fun handleComputeNodeResponse(
    requestId: String,  // Maps to taskId
    senderId: Int,
    response: ComputeNodeResponse
) {
    val tracked = activeRequests[requestId] ?: return
    
    // Add response to tracked request
    synchronized(tracked.responses) {
        tracked.responses.add(response)
    }
    
    tracked.status = RequestStatus.COLLECTING
    tracked.lastUpdated = System.currentTimeMillis()
    
    betaLogger?.log(LogLevel.DEBUG, "ComputeService",
        "Collected response from node $senderId for task $requestId " +
        "(${tracked.responses.size} total)")
}
```

### Testing

1. **Unit test**: Verify CoreGossipBroadcastService.sendComputeTaskRequest() called with correct parameters
2. **Integration test**: Verify compute nodes receive ComputeTaskRequestMessage
3. **Integration test**: Verify responses collected via handleComputeNodeResponse()

---

## ISSUE 2: storeFile Mixed Client/Server Logic

### Current Implementation

**Location**: `DistributedStorageManager.kt` lines 417-570

```kotlin
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
    accessScope: AccessScope = AccessScope.TASK_ISOLATED,
    owner: String? = null,
    recipients: List<RecipientEntry>? = null
): FileReference? = coroutineScope {
    // CLIENT-SIDE LOGIC:
    // - Quota check
    // - Encryption
    // - Local file write
    // - Chunking
    // - Broadcast storage node request
    // - Collect responses
    // - Select storage nodes
    // - Transfer chunks
    // - Update metadata
    // - Return FileReference
    
    // PROBLEM: No storage node logic for receiving/storing chunks
}
```

### Problems

1. **Missing storage node handler**: No function to handle incoming chunk transfers
2. **Client-only perspective**: storeFile() only implements client-side workflow
3. **Cannot receive chunks**: Storage nodes have no way to receive and store chunks from clients
4. **Replication impossible**: Storage nodes cannot initiate replication without client logic

### Required Changes

#### Change 2.1: Keep storeFile() Client-Side Only

**File**: `DistributedStorageManager.kt`

**Current signature** (lines 417-424):
```kotlin
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
    accessScope: AccessScope = AccessScope.TASK_ISOLATED,
    owner: String? = null,
    recipients: List<RecipientEntry>? = null
): FileReference?
```

**Keep this signature unchanged** - it's correct for client-side API.

**Implementation changes**:

1. **Remove storage node logic** (if any)
2. **Only contain**:
   - Quota validation
   - Permission preparation
   - Encryption
   - Local file write
   - Chunking
   - Broadcast storage request via CoreGossipBroadcastService
   - Response collection
   - Node selection
   - Chunk transfer
   - Metadata storage
   - Return FileReference

#### Change 2.2: Create Storage Node Handler

**File**: `DistributedStorageManager.kt`

**Add new function**:

```kotlin
/**
 * Handle incoming chunk storage request from client or replication source.
 * 
 * This is the STORAGE NODE side of the workflow.
 * Called when this node receives a chunk to store.
 * 
 * Workflow:
 * 1. Receive chunk transfer message
 * 2. Validate chunk (hash, encryption)
 * 3. Write chunk to local filesystem
 * 4. Index chunk metadata in StorageDataStore
 * 5. Send completion notification to sender
 * 6. Check replica count and initiate replication if needed
 * 
 * @param senderId Node that sent the chunk
 * @param chunkTransferMessage The chunk data and metadata
 */
suspend fun handleIncomingChunkStorage(
    senderId: Int,
    chunkTransferMessage: MeshEcosystemMessage.ChunkTransferMessage
) = withContext(Dispatchers.IO) {
    val chunk = chunkTransferMessage
    
    betaLogger.log(
        LogLevel.INFO, 
        TAG, 
        "Receiving chunk ${chunk.chunkId} for file ${chunk.fileId} from node $senderId"
    )
    
    // 1. Validate chunk hash
    val calculatedHash = sha256(chunk.chunkBytes)
    if (calculatedHash != chunk.hash) {
        betaLogger.log(
            LogLevel.ERROR, 
            TAG, 
            "Chunk ${chunk.chunkId} hash mismatch: expected ${chunk.hash}, got $calculatedHash"
        )
        return@withContext
    }
    
    // 2. Check storage quota
    if (!storageQuotaManager.canStoreFile(chunk.chunkBytes.size.toLong())) {
        betaLogger.log(
            LogLevel.WARN, 
            TAG, 
            "Storage quota exceeded, cannot store chunk ${chunk.chunkId}"
        )
        // Send rejection notification to sender
        sendChunkStorageRejection(senderId, chunk.chunkId, "Quota exceeded")
        return@withContext
    }
    
    // 3. Write chunk to filesystem
    val storageDir = File(context.filesDir, "mesh_storage/${chunk.fileId}")
    storageDir.mkdirs()
    val chunkFile = File(storageDir, chunk.chunkId)
    
    try {
        FileOutputStream(chunkFile).use { it.write(chunk.chunkBytes) }
        betaLogger.log(
            LogLevel.DEBUG, 
            TAG, 
            "Chunk ${chunk.chunkId} written to ${chunkFile.absolutePath}"
        )
    } catch (e: Exception) {
        betaLogger.log(
            LogLevel.ERROR, 
            TAG, 
            "Failed to write chunk ${chunk.chunkId}: ${e.message}"
        )
        return@withContext
    }
    
    // 4. Create MeshChunk metadata with replicaCount = 0
    val meshChunk = MeshChunk(
        chunkId = chunk.chunkId,
        fileId = chunk.fileId,
        chunkIndex = chunk.chunkIndex,
        totalChunks = chunk.totalChunks,
        chunkSize = chunk.chunkBytes.size.toLong(),
        fileName = chunk.fileName,
        relativePath = chunk.relativePath,
        hash = chunk.hash,
        storedAt = System.currentTimeMillis(),
        recipientKeyIds = emptyList(),  // TODO: Extract from chunk encryption metadata
        sessionKeys = emptyMap(),       // TODO: Extract from chunk encryption metadata
        replicaCount = 0                // CRITICAL: Start at 0
    )
    
    // 5. Index chunk in StorageDataStore
    storageDataStore.addMeshChunk(meshChunk)
    
    betaLogger.log(
        LogLevel.INFO, 
        TAG, 
        "Chunk ${chunk.chunkId} stored successfully (replicaCount=0)"
    )
    
    // 6. Send completion notification to sender
    sendChunkStorageConfirmation(senderId, chunk.chunkId, chunk.fileId)
    
    // 7. Update storage stats
    updateStorageStats()
    
    // 8. Initiate replication workflow if needed
    initiateChunkReplication(meshChunk)
}
```

#### Change 2.3: Add Chunk Storage Notification Functions

**File**: `DistributedStorageManager.kt`

```kotlin
/**
 * Send chunk storage confirmation to sender.
 */
private suspend fun sendChunkStorageConfirmation(
    recipientNodeId: Int,
    chunkId: String,
    fileId: String
) {
    val confirmationMessage = MeshEcosystemMessage.ChunkStorageConfirmationMessage(
        chunkId = chunkId,
        fileId = fileId,
        success = true,
        nodeId = virtualNode.addressAsInt.toString(),
        timestamp = System.currentTimeMillis()
    )
    
    virtualNode.sendDirectMessage(recipientNodeId, confirmationMessage.toBytes())
    
    betaLogger.log(
        LogLevel.DEBUG, 
        TAG, 
        "Sent storage confirmation for chunk $chunkId to node $recipientNodeId"
    )
}

/**
 * Send chunk storage rejection to sender.
 */
private suspend fun sendChunkStorageRejection(
    recipientNodeId: Int,
    chunkId: String,
    reason: String
) {
    val rejectionMessage = MeshEcosystemMessage.ChunkStorageRejectionMessage(
        chunkId = chunkId,
        reason = reason,
        nodeId = virtualNode.addressAsInt.toString(),
        timestamp = System.currentTimeMillis()
    )
    
    virtualNode.sendDirectMessage(recipientNodeId, rejectionMessage.toBytes())
    
    betaLogger.log(
        LogLevel.WARN, 
        TAG, 
        "Sent storage rejection for chunk $chunkId to node $recipientNodeId: $reason"
    )
}
```

### Testing

1. **Unit test**: Verify storeFile() only contains client logic
2. **Unit test**: Verify handleIncomingChunkStorage() validates hash
3. **Integration test**: Client sends chunk → Storage node receives and stores
4. **Integration test**: Storage node sends confirmation → Client receives

---

## ISSUE 3: Missing Storage Node Handler

### Current State

**Problem**: No entry point for storage nodes to receive chunk transfer messages.

**Missing integration**: MeshEcosystemListener needs to route ChunkTransferMessage to DistributedStorageManager.

### Required Changes

#### Change 3.1: Register Handler in MeshEcosystemListener

**File**: `MeshEcosystemListener.kt`

**Add handler registration**:

```kotlin
class MeshEcosystemListener(
    private val meshGossipService: MeshGossipService,
    // ... other dependencies ...
) {
    private var distributedStorageManager: DistributedStorageManager? = null
    
    fun registerStorageManager(manager: DistributedStorageManager) {
        distributedStorageManager = manager
    }
    
    // ... existing code ...
    
    /**
     * Handle incoming ChunkTransferMessage.
     * Routes to DistributedStorageManager for storage.
     */
    private suspend fun handleChunkTransfer(
        senderId: Int,
        message: MeshEcosystemMessage.ChunkTransferMessage
    ) {
        distributedStorageManager?.handleIncomingChunkStorage(senderId, message)
            ?: run {
                betaLogger.log(
                    LogLevel.WARN,
                    "MeshEcosystemListener",
                    "Received ChunkTransferMessage but DistributedStorageManager not registered"
                )
            }
    }
    
    // Add to message dispatch logic:
    private suspend fun dispatchMessage(senderId: Int, message: MeshEcosystemMessage) {
        when (message) {
            is MeshEcosystemMessage.ChunkTransferMessage -> {
                handleChunkTransfer(senderId, message)
            }
            // ... other message types ...
        }
    }
}
```

#### Change 3.2: Update DistributedStorageManager Initialization

**File**: `DistributedStorageManager.kt`

**Existing** (lines 208-220):
```kotlin
fun registerWithEcosystemListener(listener: MeshEcosystemListener) {
    meshEcosystemListener = listener
    listener.registerStorageManager(this)
}
```

**This is already correct** - no changes needed.

### Testing

1. **Integration test**: ChunkTransferMessage received → Routed to handleIncomingChunkStorage()
2. **Integration test**: Unregistered manager → Warning logged, no crash

---

## ISSUE 4: Replica Count Tracking

### Current State

**File**: `MeshStorageDataDefinitions.kt` lines 7-23

```kotlin
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long = System.currentTimeMillis(),
    // Permission-related fields
    val recipientKeyIds: List<Long> = emptyList(),
    val sessionKeys: Map<Long, ByteArray> = emptyMap()
    // MISSING: replicaCount field
)
```

### Problems

1. **No replica count field**: Cannot track how many replicas exist
2. **Cannot stop replication**: No way to check if target replica count reached
3. **Potential infinite loops**: Replication could continue indefinitely

### Required Changes

#### Change 4.1: Add replicaCount Field

**File**: `MeshStorageDataDefinitions.kt`

```kotlin
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long = System.currentTimeMillis(),
    // Permission-related fields
    val recipientKeyIds: List<Long> = emptyList(),
    val sessionKeys: Map<Long, ByteArray> = emptyMap(),
    // Replication tracking
    var replicaCount: Int = 0  // ADD THIS - starts at 0, incremented by each storage node
)
```

#### Change 4.2: Initialize replicaCount in Client

**File**: `DistributedStorageManager.kt` in storeFile()

**Current** (line ~475):
```kotlin
val chunks = chunkFile(file, fileId, chunkSize)
```

**Update** (in chunkFile() function or after):
```kotlin
val chunks = chunkFile(file, fileId, chunkSize).map { chunk ->
    chunk.copy(replicaCount = 0)  // Ensure starts at 0
}
```

#### Change 4.3: Increment replicaCount in Storage Node

**File**: `DistributedStorageManager.kt` in handleIncomingChunkStorage()

**Already shown in Change 2.2** (line with comment "CRITICAL: Start at 0"):
```kotlin
// When receiving chunk from client (initial storage):
val meshChunk = MeshChunk(
    // ... other fields ...
    replicaCount = chunkTransferMessage.replicaCount  // Preserve count from sender (0 for initial)
)

// When initiating replication (this node is source):
val replicationRequest = StorageNodeRequest(
    // ... other fields ...
    currentReplicaCount = meshChunk.replicaCount + 1  // Increment for next hop
)
```

**CANONICAL INTERPRETATION** (per architectural decision):

**replicaCount = Number of storage nodes that have replicated this chunk AFTER the current node**

- Client creates chunk with `replicaCount = 0`
- First storage node receives with count=0, stores it locally
- When first storage node replicates, it sends with `replicaCount = 1` (1 node beyond original)
- Second storage node receives with count=1, stores, replicates with count=2
- Continue until count >= target

**Example for target=3**:
```
Client → Storage A (count=0) → stores → replicates (count=1)
       ↓
    Storage B (count=1) → stores → replicates (count=2)
       ↓
    Storage C (count=2) → stores → replicates (count=3)
       ↓
    Storage D (count=3) → stores → STOP (count >= 3)
```

This ensures exactly `target` replicas are created beyond the original.

#### Change 4.4: Update replicaCount During Replication

**File**: `DistributedStorageManager.kt` in replication workflow

```kotlin
/**
 * Update replica count after successful replication.
 * Called when a chunk is successfully replicated to another node.
 */
private suspend fun incrementReplicaCount(chunkId: String) {
    val chunk = storageDataStore.getMeshChunk(chunkId) ?: return
    
    val updatedChunk = chunk.copy(replicaCount = chunk.replicaCount + 1)
    storageDataStore.updateMeshChunk(updatedChunk)
    
    betaLogger.log(
        LogLevel.DEBUG,
        TAG,
        "Incremented replica count for chunk $chunkId: ${updatedChunk.replicaCount}"
    )
}
```

### Testing

1. **Unit test**: New chunks start with replicaCount = 0 (or 1)
2. **Unit test**: incrementReplicaCount() properly updates count
3. **Integration test**: Replica count increments after each successful replication
4. **Integration test**: Replication stops when count >= target

---

## ISSUE 5: Replication Workflow

### Current State

**Problem**: No replication workflow implemented. Storage nodes do not initiate replication after storing chunks.

### Required Changes

#### Change 5.1: Add Replication Initiation

**File**: `DistributedStorageManager.kt`

**Add to end of handleIncomingChunkStorage()**:

```kotlin
/**
 * Initiate chunk replication workflow.
 * 
 * Called after a chunk is successfully stored.
 * Checks if more replicas are needed and broadcasts storage request if so.
 * 
 * Storage node acts as CLIENT for replication:
 * 1. Check current replica count
 * 2. If count < target, broadcast storage node request
 * 3. Collect responses
 * 4. Select replica targets
 * 5. Transfer chunk to replicas
 * 6. Increment replica count
 * 7. Repeat until count >= target
 * 
 * @param chunk The chunk to replicate
 */
private suspend fun initiateChunkReplication(chunk: MeshChunk) = coroutineScope {
    // 1. Check if replication needed
    val targetReplicaCount = MeshrabiyaConstants.getReplicaCount()
    
    if (chunk.replicaCount >= targetReplicaCount) {
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Chunk ${chunk.chunkId} already has ${chunk.replicaCount} replicas (target: $targetReplicaCount) - skipping replication"
        )
        return@coroutineScope
    }
    
    val remainingReplicas = targetReplicaCount - chunk.replicaCount
    
    // 2. Create storage node request (STORAGE NODE ACTS AS CLIENT)
    val requestId = java.util.UUID.randomUUID().toString()
    val request = StorageNodeRequest(
        requestId = requestId,
        chunkId = chunk.chunkId,
        fileId = chunk.fileId,
        chunkSize = chunk.chunkSize,
        desiredReplicas = remainingReplicas,
        currentReplicaCount = chunk.replicaCount + 1,  // CRITICAL: Increment for next hop
        // Pass original owner and recipients for permission consistency
        owner = getFileMetadata(chunk.fileId)?.owner ?: virtualNode.addressAsInt.toString(),
        recipients = getFileMetadata(chunk.fileId)?.recipients?.map { it.publicKey } ?: emptyList()
    )   chunkId = chunk.chunkId,
        fileId = chunk.fileId,
        chunkSize = chunk.chunkSize,
        desiredReplicas = remainingReplicas,
        // Pass original owner and recipients for permission consistency
        owner = getFileMetadata(chunk.fileId)?.owner ?: virtualNode.addressAsInt.toString(),
        recipients = getFileMetadata(chunk.fileId)?.recipients?.map { it.publicKey } ?: emptyList()
    )
    
    // 3. Broadcast storage node request via CoreGossipBroadcastService
    val gossipService = CoreGossipBroadcastService(meshGossipService)
    gossipService.sendStorageNodeRequest(request)
    
    betaLogger.log(
        LogLevel.DEBUG,
        TAG,
        "Broadcast replication request for chunk ${chunk.chunkId} (requestId: $requestId)"
    )
    
    // 4. Wait for responses (with timeout)
    delay(MeshrabiyaConstants.getTimeoutMs())
    
    // 5. Collect responses (via handleStorageNodeResponse callback)
    val pending = pendingStorageNodeRequests.find { it.request.requestId == requestId }
    if (pending == null) {
        betaLogger.log(
            LogLevel.WARN,
            TAG,
            "No pending request found for replication $requestId - responses may have been lost"
        )
        return@coroutineScope
    }
    
    val responses = pending.responses.toList()
    
    betaLogger.log(
        LogLevel.INFO,
        TAG,
        "Received ${responses.size} responses for replication of chunk ${chunk.chunkId}"
    )
    
    if (responses.isEmpty()) {
        betaLogger.log(
            LogLevel.WARN,
            TAG,
            "No storage nodes available for replication of chunk ${chunk.chunkId} - will retry later"
        )
        return@coroutineScope
    }
    
    // 6. Rank and select replica targets
    val rankedNodes = responses
        .filter { it.availableSpace >= chunk.chunkSize }
        .sortedWith(
            compareBy<StorageNodeResponse> { it.systemState.batteryLevel }
                .thenBy { it.systemState.thermalState }
                .thenByDescending { it.availableSpace }
        )
        .take(remainingReplicas)
    
    if (rankedNodes.isEmpty()) {
        betaLogger.log(
            LogLevel.WARN,
            TAG,
            "No suitable storage nodes for replication of chunk ${chunk.chunkId}"
        )
        return@coroutineScope
    }
    
    betaLogger.log(
        LogLevel.INFO,
        TAG,
        "Selected ${rankedNodes.size} nodes for replication of chunk ${chunk.chunkId}"
    )
    
    // 7. Read chunk from local storage
    val storageDir = File(context.filesDir, "mesh_storage/${chunk.fileId}")
    val chunkFile = File(storageDir, chunk.chunkId)
    
    if (!chunkFile.exists()) {
        betaLogger.log(
            LogLevel.ERROR,
            TAG,
            "Chunk file not found for replication: ${chunkFile.absolutePath}"
        )
        return@coroutineScope
    }
    
    val chunkBytes = chunkFile.readBytes()
    
    // 8. Transfer chunk to each replica target
    val replicationJobs = rankedNodes.map { response ->
        async {
            val nodeId = response.nodeId
            
            betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Transferring chunk ${chunk.chunkId} to replica node $nodeId"
            )
            
            val connection = try {
                connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
            } catch (e: Exception) {
                betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Failed to acquire connection for replication to node $nodeId: ${e.message}"
                )
                null
            }
            
            if (connection != null) {
                try {
                    val chunkMsg = MeshEcosystemMessage.ChunkTransferMessage(
                        chunkId = chunk.chunkId,
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        fileName = chunk.fileName,
                        relativePath = chunk.relativePath,
                        chunkBytes = chunkBytes,
                        hash = chunk.hash,
                        replicaCount = chunk.replicaCount + 1  // CRITICAL: Increment count in transfer
                    )
                    
                    connection.virtualNode.sendChunkToNode(nodeId, chunk, chunkMsg.toBytes())
                    
                    betaLogger.log(
                        LogLevel.INFO,
                        TAG,
                        "Successfully transferred chunk ${chunk.chunkId} to replica node $nodeId"
                    )
                    
                    // Increment replica count after successful transfer
                    incrementReplicaCount(chunk.chunkId)
                    
                } catch (e: Exception) {
                    betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Failed to transfer chunk ${chunk.chunkId} to replica node $nodeId: ${e.message}"
                    )
                } finally {
                    connectionPool.releaseConnection(connection)
                }
            }
        }
    }
    
    // Wait for all replication transfers to complete
    replicationJobs.forEach { it.await() }
    
    // 9. Get updated replica count
    val updatedChunk = storageDataStore.getMeshChunk(chunk.chunkId)
    
    betaLogger.log(
        LogLevel.INFO,
        TAG,
        "Replication complete for chunk ${chunk.chunkId}: " +
        "replicaCount=${updatedChunk?.replicaCount ?: 0}, target=$targetReplicaCount"
    )
}
```

#### Change 5.2: Add Replica Count Query (Optional Enhancement)

**File**: `DistributedStorageManager.kt`

**Purpose**: Query mesh network for current replica count of a chunk (for verification).

```kotlin
/**
 * Query mesh network for replica count of a chunk.
 * 
 * Broadcasts ReplicaQuery and collects responses from all nodes storing the chunk.
 * 
 * @param chunkId Chunk ID to query
 * @return Number of nodes storing this chunk
 */
suspend fun queryReplicaCount(chunkId: String): Int = coroutineScope {
    val gossipService = CoreGossipBroadcastService(meshGossipService)
    gossipService.sendReplicaQuery(chunkId)
    
    // Wait for responses
    delay(MeshrabiyaConstants.getTimeoutMs())
    
    // Count responses (each response = 1 replica)
    val responses = pendingReplicaResponses[chunkId] ?: emptyList()
    pendingReplicaResponses.remove(chunkId)
    
    val count = responses.size
    
    betaLogger.log(
        LogLevel.INFO,
        TAG,
        "Replica count for chunk $chunkId: $count"
    )
    
    count
}
```

### Testing

1. **Unit test**: initiateChunkReplication() checks replica count before broadcasting
2. **Unit test**: Replication stops when count >= target
3. **Integration test**: Storage node stores chunk → Initiates replication
4. **Integration test**: Replica nodes receive and store chunks
5. **Integration test**: Replica count increments correctly
6. **Integration test**: Replication stops at target count

---

## IMPLEMENTATION CHECKLIST

### Phase 1: Broadcast Pattern Consistency

- [ ] Add `coreGossipBroadcastService` property to VirtualNode constructor
- [ ] Initialize CoreGossipBroadcastService in VirtualNode with MeshGossipService
- [ ] Update IntelligentDistributedComputeService to access via `virtualNode.coreGossipBroadcastService`
- [ ] Update DistributedStorageManager to access via `virtualNode.coreGossipBroadcastService`
- [ ] Refactor `processTaskRequest()` to use `coreGossipBroadcastService.sendComputeTaskRequest()`
- [ ] Update response collection to use asynchronous callbacks via `handleComputeNodeResponse()`
- [ ] Remove direct calls to `virtualNode.getMeshGossipService().broadcastComputeTaskRequestSync()`
- [ ] Test: Verify compute task requests broadcast correctly
- [ ] Test: Verify compute nodes receive requests and respond

### Phase 2: Storage Client/Server Separation

- [ ] Review `storeFile()` implementation - ensure it only contains client-side logic
- [ ] Remove any storage node logic from `storeFile()` (if present)
- [ ] Create `handleIncomingChunkStorage()` function in DistributedStorageManager
- [ ] Implement chunk validation (hash verification)
- [ ] Implement chunk filesystem storage
- [ ] Implement chunk metadata indexing
- [ ] Implement chunk storage confirmation/rejection messages
- [ ] Register chunk transfer handler in MeshEcosystemListener
- [ ] Test: Client sends chunk → Storage node receives and stores
- [ ] Test: Storage node sends confirmation → Client receives

### Phase 3: Replica Count Tracking

- [ ] Add `var replicaCount: Int = 0` field to MeshChunk data class
- [ ] Add `replicaCount` field to ChunkTransferMessage
- [ ] Add `currentReplicaCount` field to StorageNodeRequest
- [ ] Update `chunkFile()` in client to initialize replicaCount = 0
- [ ] Update `handleIncomingChunkStorage()` to preserve replicaCount from transfer message
- [ ] Update `initiateChunkReplication()` to increment count: `currentReplicaCount = chunk.replicaCount + 1`
- [ ] Update `ChunkTransferMessage` creation to include incremented count
- [ ] Add `updateMeshChunk()` function to StorageDataStore (if missing)
### Phase 4: Replication Workflow

- [ ] Create `initiateChunkReplication()` function in DistributedStorageManager
- [ ] Implement replica count check (stop if count >= target)
- [ ] Implement retry logic matching storage node request pattern (same timeout/retry values)
- [ ] Implement storage node request broadcast with incremented count (storage node acts as client)
- [ ] Implement response collection and node selection (same ranking as initial storage)
- [ ] Implement chunk transfer to replica nodes with incremented replicaCount
- [ ] Remove `incrementReplicaCount()` function (count incremented during transfer, not after)
- [ ] Call `initiateChunkReplication()` from `handleIncomingChunkStorage()`
- [ ] Test: Storage node initiates replication after storing chunk
- [ ] Test: Replica nodes receive chunks with correct incremented count
- [ ] Test: Replication stops when received count >= target
- [ ] Test: Retry logic matches storage node request retry behavior`handleIncomingChunkStorage()`
- [ ] Test: Storage node initiates replication after storing chunk
- [ ] Test: Replica nodes receive and store chunks
- [ ] Test: Replica count increments correctly
- [ ] Test: Replication stops at target count

### Phase 5: Integration Testing

- [ ] Test: End-to-end storeFile workflow (client → storage node → replication)
- [ ] Test: Replica count tracked correctly throughout workflow
- [ ] Test: Replication stops when target reached
- [ ] Test: Multiple clients storing simultaneously
- [ ] Test: Storage nodes with insufficient space reject chunks
- [ ] Test: Storage nodes with low battery reject replication
- [ ] Test: Network interruptions during transfer (retry logic)

### Phase 6: Documentation

- [ ] Update CANONICAL_WORKFLOWS.md with any clarifications
- [ ] Update STORAGE_LIFECYCLE.md with replication details
- [ ] Document MeshChunk replicaCount field in code comments
- [ ] Document handleIncomingChunkStorage() workflow in code comments
- [ ] Update KNOWLEDGE doc with implementation notes

---

## ARCHITECTURAL DECISIONS (FINALIZED)

### Decision 1: replicaCount Interpretation ✅

**DECISION**: Replica count starts at **0** in the initial client request to storage node.

**Implementation**:
- Client node creates chunks with `replicaCount = 0`
- First storage node receives chunk with `replicaCount = 0`, stores it
- When first storage node initiates replication, it increments to `replicaCount = 1`
- Each subsequent storage node increments the count: 1 → 2 → 3, etc.
- Replication stops when `replicaCount >= MeshrabiyaConstants.getReplicaCount()`

**Example flow for target=3**:
1. Client → Storage Node A: chunk with replicaCount=0
2. Storage Node A stores, then replicates with replicaCount=1
3. Storage Node B receives (count=1), stores, replicates with replicaCount=2
4. Storage Node C receives (count=2), stores, replicates with replicaCount=3
5. Storage Node D receives (count=3), stores, **stops** (count >= target)

### Decision 2: Replica Count Synchronization ✅

**DECISION**: No synchronization between nodes. Incremental propagation only.

**Implementation**:
- Each storage node independently increments replicaCount when it initiates replication
- No query-based verification
- No gossip-based updates
- Trust propagation: Storage Node A tells B "count is now N+1", B accepts and continues

**Rationale**:
- Simpler implementation
- Avoids synchronization overhead
- Natural termination through incremental counting
- Over-replication prevented by count >= target check

### Decision 3: Replication Retry Strategy ✅

**DECISION**: Mirror storage node request retry strategy.

**Implementation**:
- Use same timeout values: `MeshrabiyaConstants.getTimeoutMs()`
- Use same retry count: `MeshrabiyaConstants.getMaxRetries()`
- Use same retry delay: `RETRY_DELAY_MS = 10000L` (10 seconds)
- Same failure handling: Log and move on after max retries

**Code pattern**:
```kotlin
var retries = 0
while (retries < MeshrabiyaConstants.getMaxRetries()) {
    // Attempt replication
    if (success) break
    
    retries++
    delay(RETRY_DELAY_MS)
}
```

### Decision 4: CoreGossipBroadcastService Instantiation ✅

**DECISION**: Hybrid pattern - Constructor in VirtualNode, Singleton elsewhere.

**Implementation**:

**In VirtualNode** (primary instantiation):
```kotlin
class VirtualNode(
    // ... other params ...
) {
    private val meshGossipService: MeshGossipService = // ...
    
    // Create as instance property
    val coreGossipBroadcastService = CoreGossipBroadcastService(meshGossipService)
    
    // ... rest of VirtualNode ...
}
```

**Everywhere else** (singleton access):
```kotlin
object CoreGossipBroadcastServiceSingleton {
    @Volatile
    private var instance: CoreGossipBroadcastService? = null
    
    fun initialize(meshGossipService: MeshGossipService) {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    instance = CoreGossipBroadcastService(meshGossipService)
                }
            }
        }
    }
    
    fun getInstance(): CoreGossipBroadcastService {
        return instance ?: throw IllegalStateException(
            "CoreGossipBroadcastService not initialized. Call initialize() first."
        )
    }
}
```

**Usage in IntelligentDistributedComputeService**:
```kotlin
class IntelligentDistributedComputeService(
    private val virtualNode: VirtualNode,
    // ... other params ...
) {
    // Access via VirtualNode
    private val coreGossipBroadcastService = virtualNode.coreGossipBroadcastService
    
    // ... rest of implementation ...
}
```

**Usage in DistributedStorageManager**:
```kotlin
class DistributedStorageManager(
    private val virtualNode: VirtualNode,
    // ... other params ...
) {
    // Access via VirtualNode
    private val coreGossipBroadcastService = virtualNode.coreGossipBroadcastService
    
    // ... rest of implementation ...
}
```

**Rationale**:
- VirtualNode is the root dependency container
- All services get VirtualNode reference
- Natural access pattern: `virtualNode.coreGossipBroadcastService`
- No need for separate singleton pattern
- Proper lifecycle management (tied to VirtualNode)

---

## PRIORITY RANKING

### Critical (Must Fix)

1. **Issue 2**: storeFile mixed logic - **BLOCKS** storage node functionality
2. **Issue 3**: Missing storage node handler - **BLOCKS** chunk reception
3. **Issue 4**: Replica count tracking - **BLOCKS** replication logic

### High (Should Fix)

4. **Issue 1**: processTaskRequest broadcast pattern - **INCONSISTENT** with storage pattern
5. **Issue 5**: Replication workflow - **INCOMPLETE** distributed storage

### Medium (Nice to Have)

6. Replica count synchronization - **OPTIONAL** verification
7. Replication retry strategy - **OPTIONAL** robustness
8. Query-based replica count - **OPTIONAL** debugging

---

## TIMELINE ESTIMATE

**Assuming single developer working full-time**:

- Phase 1 (Broadcast Pattern): 4 hours
- Phase 2 (Client/Server Separation): 8 hours
- Phase 3 (Replica Count Tracking): 4 hours
- Phase 4 (Replication Workflow): 12 hours
- Phase 5 (Integration Testing): 8 hours
- Phase 6 (Documentation): 4 hours

**Total**: 40 hours (1 week)

---

## CONCLUSION

This document provides a comprehensive specification for aligning the current implementation with canonical workflows. The changes are necessary to:

1. Establish consistent broadcast patterns between storage and compute
2. Separate client-side and storage-node-side concerns
3. Implement proper replication tracking and control
4. Enable distributed storage with configurable replica counts

All changes are backward-compatible and follow the architectural patterns established in CANONICAL_WORKFLOWS.md and STORAGE_LIFECYCLE.md.

**Next steps**: Review questions, make decisions, proceed with implementation checklist.
