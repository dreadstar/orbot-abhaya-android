# MESHRABIYA CANONICAL WORKFLOWS REFACTORING PLAN

**Date**: November 18, 2025  
**Purpose**: Comprehensive plan to refactor Meshrabiya to implement CANONICAL_WORKFLOWS.md  
**Status**: PLANNING DOCUMENT

---

## EXECUTIVE SUMMARY

This plan refactors the Meshrabiya distributed storage and compute system to align with the canonical workflows defined in CANONICAL_WORKFLOWS.md. The current implementation has partial workflow support but requires significant architectural changes to achieve full compliance.

### Critical Gaps Identified:
1. **Service keypair not created** at DistributedStorageManager startup (Step 0)
2. **Encryption timing wrong** - happens before storage node selection instead of after
3. **Multiple node selection** instead of single node with daisy-chain replication
4. **Missing handleIncomingChunkStorage()** function for storage node-side chunk handling
5. **No replicaCount propagation** in ChunkTransferMessage
6. **Local caching exists** but should be removed per canonical design
7. **Client-side re-encryption** instead of storage-side re-encryption
8. **No daisy-chain replication** - current code replicates from client to multiple nodes
9. **No chunkIndexes retry parameter** for partial retrieval/update failures
10. **AccessScope still in use** - should be deprecated in favor of explicit owner/recipients

---

## PHASE 1: DATA STRUCTURE UPDATES

### 1.1 Update MeshChunk Structure
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshStorageDataDefinitions.kt`

**Current State**:
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
    val recipientKeyIds: List<Long> = emptyList(),
    val sessionKeys: Map<Long, ByteArray> = emptyMap()
)
```

**Required Changes**:
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
    val recipientKeyIds: List<Long> = emptyList(),
    val sessionKeys: Map<Long, ByteArray> = emptyMap(),
    var replicaCount: Int = 0  // ADD THIS FIELD - CRITICAL for daisy-chain replication
)
```

**Rationale**: Replica count must be tracked per chunk and propagated through transfer messages to enable natural termination of daisy-chain replication.

### 1.2 Add Service Keypair Storage
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**Add to class properties** (after line ~120):
```kotlin
class DistributedStorageManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val meshGossipService: MeshGossipService,
    private val storageConfig: StorageConfiguration,
    private val connectionPool: MeshConnectionPool
) {
    // ADD THESE PROPERTIES:
    // Service keypair for chunk encryption (in-memory only, per instance)
    private val serviceKeypair: Pair<ByteArray, ByteArray> by lazy {
        // Generate keypair at first access (lazy initialization at startup)
        encryptionManager.generateServiceKeypair()
    }
    private val servicePublicKey: ByteArray
        get() = serviceKeypair.first
    private val servicePrivateKey: ByteArray
        get() = serviceKeypair.second
```

**Rationale**: Step 0 of canonical workflow requires service keypair creation at startup for encrypting chunks with storage node access.

### 1.3 Update StorageNodeRequest Structure
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshStorageDataDefinitions.kt`

**Add fields**:
```kotlin
data class StorageNodeRequest(
    val requestId: String,
    val chunkId: String,
    val chunkIndex: Int,  // ADD THIS
    val fileId: String,
    val desiredReplicas: Int? = null,  // ADD THIS (optional)
    val chunkSizeBytes: Long,
    val replicaCount: Int = 0  // ADD THIS - for daisy-chain propagation
)
```

**Rationale**: chunkIndex needed for retry logic, desiredReplicas for custom replication levels, replicaCount for daisy-chain tracking.

### 1.4 Update StorageNodeResponse Structure
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshStorageDataDefinitions.kt`

**Add field**:
```kotlin
data class StorageNodeResponse(
    val nodeId: String,
    val availableSpace: Long,
    val totalStorageAllocated: Long,  // ADD THIS
    val systemState: String,
    val meshUrl: String,
    val latencyEstimate: Long,
    val fitnessScore: Double,
    val fileId: String,
    val servicePublicKey: ByteArray  // ADD THIS - from Step 0 keypair
)
```

**Rationale**: totalStorageAllocated needed for secondary ranking, servicePublicKey needed for encryption in Step 1.5.

### 1.5 Update ChunkTransferMessage Structure
**File**: Search for ChunkTransferMessage definition

**Add field**:
```kotlin
data class ChunkTransferMessage(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val fileName: String,
    val relativePath: String,
    val chunkBytes: ByteArray,
    val hash: String,
    val replicaCount: Int = 0,  // ADD THIS - propagates through daisy-chain
    val recipientKeyIds: List<Long> = emptyList(),  // ADD IF MISSING
    val sessionKeys: Map<Long, ByteArray> = emptyMap()  // ADD IF MISSING
)
```

**Rationale**: replicaCount must be included in transfer message to enable increment-before-store pattern.

### 1.6 Add ChunkRetrievalQuery chunkIndexes Parameter
**File**: Search for ChunkRetrievalQuery definition

**Update**:
```kotlin
data class ChunkRetrievalQuery(
    val fileId: String,
    val chunkIndexes: List<Int>? = null  // ADD THIS - for retry of missing chunks
)
```

**Rationale**: Enables retry logic to request only missing chunks instead of all chunks.

### 1.7 Update FilePermissionUpdateMessage
**File**: Search for FilePermissionUpdateMessage definition

**Update**:
```kotlin
data class FilePermissionUpdateMessage(
    val fileId: String,
    val addedRecipients: List<RecipientEntry>,
    val removedRecipients: List<RecipientEntry>,
    val chunkIndexes: List<Int>? = null  // ADD THIS - for retry targeting
)
```

**Rationale**: Enables targeted retry for permission updates on specific chunks.

---

## PHASE 2: STORAGE WORKFLOW REFACTORING

### 2.1 Refactor storeFile() - Remove Early Encryption
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**Current Issue** (lines ~450-470):
Encryption happens BEFORE storage node selection, but canonical workflow requires encryption AFTER receiving storage node's service public key.

**Required Changes**:

**Step 1**: Remove encryption from file preparation section (lines ~450-470)
```kotlin
// REMOVE THIS SECTION:
// val encryptedData = encryptionManager.encryptWithRecipients(
//     data = data,
//     owner = effectiveOwner,
//     recipients = effectiveRecipients.map { it.publicKey }
// )
// FileOutputStream(file).use { it.write(encryptedData) }

// REPLACE WITH:
// Chunks created unencrypted at this stage
val file = File(path)
file.parentFile?.mkdirs()
FileOutputStream(file).use { it.write(data) }  // Store unencrypted temporarily
```

**Step 2**: Move encryption to chunk transfer loop (after node selection, before sending)
```kotlin
// IN THE CHUNK TRANSFER SECTION (after findBestStorageNodesForChunk):
chunks.forEach { chunk ->
    val candidateNode = findBestStorageNodeForChunk(chunk, fileId, desiredReplicas)  // SINGLE node, not multiple
    
    if (candidateNode != null) {
        // READ: Encryption happens HERE, after we have storage node's service public key
        val chunkBytes = readChunkBytes(file, chunk)
        val encryptedChunkBytes = encryptionManager.encryptChunkWithRecipients(
            data = chunkBytes,
            ownerPublicKey = effectiveOwner,
            storageNodeServicePublicKey = candidateNode.servicePublicKey,  // From response
            recipientPublicKeys = effectiveRecipients.map { it.publicKey }
        )
        
        val chunkMsg = ChunkTransferMessage(
            chunkId = chunk.chunkId,
            fileId = chunk.fileId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks,
            fileName = chunk.fileName,
            relativePath = chunk.relativePath,
            chunkBytes = encryptedChunkBytes,  // Now encrypted
            hash = chunk.hash,
            replicaCount = 0  // Client always starts at 0
        )
        
        // Send to selected node
        virtualNode.sendChunkToNode(candidateNode.nodeId, chunk, chunkMsg.toBytes())
    }
}
```

**Rationale**: Canonical workflow Step 1.5 requires encryption AFTER storage node selection to include storage node's service public key.

### 2.2 Change Multiple Node Selection to Single Node
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**Current Issue** (lines ~485-520):
Code selects multiple nodes and sends chunk to all of them. Canonical workflow requires selecting SINGLE best node.

**Required Changes**:

**Replace** `findBestStorageNodesForChunk()` with `findBestStorageNodeForChunk()`:
```kotlin
// REPLACE THIS:
// val candidateNodes = findBestStorageNodesForChunk(chunk, fileId, desiredReplicas)
// candidateNodes.forEach { nodeId -> ... }

// WITH THIS:
private suspend fun findBestStorageNodeForChunk(
    chunk: MeshChunk,
    fileId: String,
    desiredReplicas: Int
): StorageNodeResponse? {
    val request = StorageNodeRequest(
        requestId = UUID.randomUUID().toString(),
        chunkId = chunk.chunkId,
        chunkIndex = chunk.chunkIndex,
        fileId = fileId,
        desiredReplicas = desiredReplicas,
        chunkSizeBytes = chunk.chunkSize,
        replicaCount = 0  // Client always sends 0
    )
    
    // Broadcast via CoreGossipBroadcastService
    virtualNode.coreGossipBroadcastService.sendStorageNodeRequest(request)
    
    // Wait for responses (timeout from MeshrabiyaConstants)
    delay(MeshrabiyaConstants.getTimeoutMs())
    
    // Get collected responses from pending requests
    val pending = pendingStorageNodeRequests.find { it.request.requestId == request.requestId }
    val responses = pending?.responses ?: emptyList()
    
    if (responses.isEmpty()) {
        return null
    }
    
    // Rank and select SINGLE best node
    return responses
        .filter { it.systemState == "HEALTHY" }
        .sortedWith(
            compareByDescending<StorageNodeResponse> { it.availableSpace }
                .thenBy { it.latencyEstimate }
                .thenByDescending { it.fitnessScore }
                .thenByDescending { it.totalStorageAllocated }
        )
        .firstOrNull()  // SINGLE node only
}
```

**Rationale**: Canonical workflow Step 1.4 requires selecting single best node. Replication happens via daisy-chain, not parallel broadcast.

### 2.3 Implement handleIncomingChunkStorage()
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**Current Issue**:
Existing `handleIncomingChunkTransfer()` at line ~300 does NOT implement canonical workflow Step 1.6.

**Required Changes**:

**REPLACE** existing function with canonical implementation:
```kotlin
/**
 * Storage Node-Side: Receive Chunk & Store
 * Implements CANONICAL_WORKFLOWS.md Section 1.6
 * 
 * This is THE STORAGE NODE HANDLER for incoming chunks.
 * Called by MeshEcosystemListener when ChunkTransferMessage arrives.
 */
fun handleIncomingChunkStorage(senderId: Int, chunkMsg: ChunkTransferMessage) {
    scope.launch {
        betaLogger.log(LogLevel.INFO, TAG, 
            "Storage node received chunk ${chunkMsg.chunkId} from node $senderId " +
            "with replicaCount=${chunkMsg.replicaCount}")
        
        // Step 1: Validate chunk
        val computedHash = sha256(chunkMsg.chunkBytes)
        if (computedHash != chunkMsg.hash) {
            betaLogger.log(LogLevel.ERROR, TAG, 
                "Chunk ${chunkMsg.chunkId} hash mismatch: expected ${chunkMsg.hash}, got $computedHash")
            return@launch
        }
        
        // Step 2: Write chunk to local filesystem
        val chunkDir = File(context.filesDir, "distributed_storage/${chunkMsg.fileId}")
        chunkDir.mkdirs()
        val chunkFile = File(chunkDir, "${chunkMsg.chunkId}.chunk")
        FileOutputStream(chunkFile).use { it.write(chunkMsg.chunkBytes) }
        
        // Step 3: Increment replica count BEFORE storing metadata
        val storedReplicaCount = chunkMsg.replicaCount + 1
        
        betaLogger.log(LogLevel.INFO, TAG,
            "Storing chunk ${chunkMsg.chunkId} as replica #$storedReplicaCount")
        
        // Step 4: Index chunk metadata in StorageDataStore
        val meshChunk = MeshChunk(
            chunkId = chunkMsg.chunkId,
            fileId = chunkMsg.fileId,
            chunkIndex = chunkMsg.chunkIndex,
            totalChunks = chunkMsg.totalChunks,
            chunkSize = chunkMsg.chunkBytes.size.toLong(),
            fileName = chunkMsg.fileName,
            relativePath = chunkMsg.relativePath,
            hash = chunkMsg.hash,
            storedAt = System.currentTimeMillis(),
            recipientKeyIds = chunkMsg.recipientKeyIds,
            sessionKeys = chunkMsg.sessionKeys,
            replicaCount = storedReplicaCount  // Incremented count
        )
        
        storageDataStore.addChunk(meshChunk)
        
        // Step 5: Send completion notification to client node
        val completionMsg = ChunkStorageCompletionMessage(
            chunkId = chunkMsg.chunkId,
            fileId = chunkMsg.fileId,
            success = true
        )
        virtualNode.sendDirectMessage(senderId, completionMsg.toBytes())
        
        // Step 6: Initiate replication workflow if needed
        val targetReplicas = chunkMsg.desiredReplicas ?: MeshrabiyaConstants.getReplicaCount()
        if (storedReplicaCount < targetReplicas) {
            betaLogger.log(LogLevel.INFO, TAG,
                "Initiating replication for chunk ${chunkMsg.chunkId}: " +
                "current=$storedReplicaCount, target=$targetReplicas")
            initiateChunkReplication(meshChunk, targetReplicas)
        } else {
            betaLogger.log(LogLevel.INFO, TAG,
                "Replication complete for chunk ${chunkMsg.chunkId}: " +
                "count=$storedReplicaCount >= target=$targetReplicas")
        }
    }
}
```

**Rationale**: This implements the complete storage node-side workflow from Section 1.6, including critical replica count increment-before-store.

### 2.4 Implement Daisy-Chain Replication
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**NEW FUNCTION** (implements Section 4):
```kotlin
/**
 * File Replication Workflow
 * Implements CANONICAL_WORKFLOWS.md Section 4
 * 
 * Storage node acts as CLIENT to replicate chunk to next node in chain.
 */
private suspend fun initiateChunkReplication(chunk: MeshChunk, targetReplicas: Int) {
    // Section 4.1: Check replica count (already done before calling this)
    
    // Section 4.2: Storage Node Acts as Client - Broadcast Storage Request
    val request = StorageNodeRequest(
        requestId = UUID.randomUUID().toString(),
        chunkId = chunk.chunkId,
        chunkIndex = chunk.chunkIndex,
        fileId = chunk.fileId,
        desiredReplicas = targetReplicas,
        chunkSizeBytes = chunk.chunkSize,
        replicaCount = chunk.replicaCount  // Send current stored count
    )
    
    betaLogger.log(LogLevel.INFO, TAG,
        "Storage node broadcasting replication request for chunk ${chunk.chunkId} " +
        "with replicaCount=${chunk.replicaCount}")
    
    // Broadcast via CoreGossipBroadcastService (storage node acts as client)
    virtualNode.coreGossipBroadcastService.sendStorageNodeRequest(request)
    
    // Section 4.3: Wait for responses
    delay(MeshrabiyaConstants.getTimeoutMs())
    
    // Section 4.4: Select Replica Target (single best node)
    val pending = pendingStorageNodeRequests.find { it.request.requestId == request.requestId }
    val responses = pending?.responses ?: emptyList()
    
    val selectedNode = responses
        .filter { it.systemState == "HEALTHY" }
        .sortedWith(
            compareByDescending<StorageNodeResponse> { it.availableSpace }
                .thenBy { it.latencyEstimate }
                .thenByDescending { it.fitnessScore }
        )
        .firstOrNull()
    
    if (selectedNode == null) {
        betaLogger.log(LogLevel.WARN, TAG,
            "No available nodes for replication of chunk ${chunk.chunkId}")
        return
    }
    
    // Section 4.5: Transfer Chunk to Replica
    val connection = connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
    try {
        // Read chunk from local storage
        val chunkDir = File(context.filesDir, "distributed_storage/${chunk.fileId}")
        val chunkFile = File(chunkDir, "${chunk.chunkId}.chunk")
        val chunkBytes = chunkFile.readBytes()
        
        // Create transfer message with CURRENT stored count
        val transferMsg = ChunkTransferMessage(
            chunkId = chunk.chunkId,
            fileId = chunk.fileId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks,
            fileName = chunk.fileName,
            relativePath = chunk.relativePath,
            chunkBytes = chunkBytes,
            hash = chunk.hash,
            replicaCount = chunk.replicaCount,  // Send same count as stored
            recipientKeyIds = chunk.recipientKeyIds,
            sessionKeys = chunk.sessionKeys
        )
        
        betaLogger.log(LogLevel.INFO, TAG,
            "Sending chunk ${chunk.chunkId} to replica node ${selectedNode.nodeId} " +
            "with replicaCount=${chunk.replicaCount}")
        
        connection.virtualNode.sendChunkToNode(selectedNode.nodeId, chunk, transferMsg.toBytes())
    } finally {
        connectionPool.releaseConnection(connection)
    }
    
    // Section 4.6: Receiving node will handle storage and continue chain
    // Section 4.7: Natural termination when stored count >= target
}
```

**Rationale**: Implements complete daisy-chain replication per Section 4. Each storage node acts as client for next replica.

### 2.5 Remove AccessScope Dependency
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**Current Issue**:
storeFile() signature includes `accessScope: AccessScope` parameter (line ~424).

**Required Changes**:

**Update function signature** (line ~417):
```kotlin
// REMOVE accessScope parameter:
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
    owner: String? = null,
    recipients: List<RecipientEntry>? = null  // AccessScope removed
): FileReference?
```

**Update FileMetadata storage** (remove accessScope logic around lines ~440-455):
```kotlin
// REMOVE:
// val effectiveRecipients = when {
//     recipients != null -> recipients
//     accessScope == AccessScope.TASK_ISOLATED -> listOf(...)
//     accessScope == AccessScope.SERVICE_SHARED -> emptyList()
//     accessScope == AccessScope.MESH_GLOBAL -> emptyList()
//     else -> emptyList()
// }

// REPLACE WITH:
val effectiveRecipients = recipients ?: listOf(
    RecipientEntry(
        publicKey = effectiveOwner,
        recipientType = RecipientType.USER
    )
)
```

**Update FileMetadata structure** (around line ~75):
```kotlin
data class FileMetadata(
    val fileId: String,
    val path: String,
    val sizeBytes: Long,
    val owner: String,
    val recipients: List<RecipientEntry>,
    // val accessScope: AccessScope,  // REMOVE THIS
    val createdAt: Long,
    val lastAccessedBy: String? = null,
    val encryptionKeyId: String? = null
)
```

**Rationale**: AccessScope deprecated per canonical workflow. Access control via explicit owner + recipients only.

---

## PHASE 3: RETRIEVE WORKFLOW REFACTORING

### 3.1 Remove Local Caching
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**Current Issue** (lines ~577-610):
retrieveFile() checks local file first, but canonical workflow Section 2 says NO local storage copy.

**Required Changes**:

**REMOVE local file check** (lines ~580-608):
```kotlin
// REMOVE THIS ENTIRE SECTION:
// val file = File(fileRef.path)
// val localData = if (file.exists()) file.readBytes() else null
// if (localData != null) {
//     ... local decryption and return ...
// }

// REPLACE WITH direct mesh network query:
suspend fun retrieveFile(fileRef: FileReference): ByteArray? = coroutineScope {
    betaLogger.log(LogLevel.DEBUG, "Storage", "Retrieving file from mesh network: ${fileRef.id}")
    
    // Section 2.1: Query Mesh Network directly (no local check)
    val retrievedChunks = mutableListOf<MeshChunk>()  // Persists across retries
    var missingChunkIndexes: List<Int>? = null
    var retryCount = 0
    
    while (retryCount < MAX_RETRIES) {
        // Section 2.1: Broadcast ChunkRetrievalQuery
        virtualNode.coreGossipBroadcastService.sendChunkRetrievalQuery(
            fileId = fileRef.id,
            chunkIndexes = missingChunkIndexes  // null first time, specific indexes on retry
        )
        
        // Section 2.2: Wait for responses
        delay(MeshrabiyaConstants.getTimeoutMs())
        
        // Section 2.3: Download chunks
        val responses = pendingChunkRetrievals[fileRef.id] ?: emptyList()
        
        for (response in responses) {
            for (chunkIndex in response.chunkIndexes) {
                // Skip if already retrieved
                if (retrievedChunks.any { it.chunkIndex == chunkIndex }) {
                    continue
                }
                
                // Request chunk from best node
                val chunkNode = selectBestNodeForChunk(responses, chunkIndex)
                if (chunkNode != null) {
                    val chunk = downloadChunk(chunkNode.nodeId, fileRef.id, chunkIndex)
                    if (chunk != null) {
                        retrievedChunks.add(chunk)
                    }
                }
            }
        }
        
        // Section 2.3: Verify all chunks present
        val expectedChunks = retrievedChunks.maxOfOrNull { it.totalChunks } ?: 0
        if (retrievedChunks.size == expectedChunks) {
            // All chunks retrieved - reassemble file
            break
        }
        
        // Calculate missing chunks for retry
        val retrievedIndexes = retrievedChunks.map { it.chunkIndex }.toSet()
        missingChunkIndexes = (0 until expectedChunks).filter { it !in retrievedIndexes }
        
        betaLogger.log(LogLevel.WARN, TAG,
            "Missing ${missingChunkIndexes.size} chunks for file ${fileRef.id}, retrying...")
        
        retryCount++
    }
    
    if (retrievedChunks.isEmpty()) {
        betaLogger.log(LogLevel.ERROR, TAG, "Failed to retrieve file ${fileRef.id}")
        return@coroutineScope null
    }
    
    // Reassemble file from chunks
    val sortedChunks = retrievedChunks.sortedBy { it.chunkIndex }
    val fileBytes = sortedChunks.flatMap { it.chunkBytes.toList() }.toByteArray()
    
    // Decrypt file
    val decryptedBytes = encryptionManager.decrypt(fileBytes)
    
    betaLogger.log(LogLevel.DEBUG, "Storage", "File retrieved from mesh: ${fileRef.id}")
    onFileRetrieved?.invoke(fileRef.id, File(fileRef.path))
    
    return@coroutineScope decryptedBytes
}
```

**Rationale**: Canonical workflow Section 2 explicitly states NO local storage copy beyond drop folder original.

### 3.2 Add chunkIndexes Retry Support
**Already implemented in 3.1 above** - uses `missingChunkIndexes` parameter for targeted retry.

---

## PHASE 4: UPDATE FILE ACCESS WORKFLOW REFACTORING

### 4.1 Implement Storage-Side Re-encryption
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/StorageEncryptionManager.kt`

**NEW FUNCTION** (implements Section 3.3):
```kotlin
/**
 * Re-encrypt chunk keys on storage node side.
 * Implements CANONICAL_WORKFLOWS.md Section 3.3
 * 
 * @param fileId File ID to re-encrypt chunks for
 * @param addedRecipients New recipients to add
 * @param removedRecipients Recipients to remove
 * @param servicePrivateKey Storage node's service private key
 * @return List of chunk indexes successfully re-encrypted
 */
suspend fun reEncryptChunkKeys(
    fileId: String,
    addedRecipients: List<RecipientEntry>,
    removedRecipients: List<RecipientEntry>,
    servicePrivateKey: ByteArray
): List<Int> {
    val reEncryptedChunkIndexes = mutableListOf<Int>()
    
    // Get all chunks for this file
    val chunks = storageDataStore.getChunksForFile(fileId)
    
    for (chunk in chunks) {
        // Decrypt chunk key using storage node's service private key
        val chunkKey = decryptChunkKeyWithServiceKey(chunk, servicePrivateKey)
        
        // Create updated sessionKeys map
        val updatedSessionKeys = chunk.sessionKeys.toMutableMap()
        val updatedRecipientKeyIds = chunk.recipientKeyIds.toMutableList()
        
        // Add new recipients
        for (recipient in addedRecipients) {
            val recipientPublicKey = loadPublicKey(recipient.publicKey)
            val encryptedChunkKey = encryptChunkKeyForRecipient(chunkKey, recipientPublicKey)
            updatedSessionKeys[recipientPublicKey.keyID] = encryptedChunkKey
            if (recipientPublicKey.keyID !in updatedRecipientKeyIds) {
                updatedRecipientKeyIds.add(recipientPublicKey.keyID)
            }
        }
        
        // Remove revoked recipients
        for (recipient in removedRecipients) {
            val recipientPublicKey = loadPublicKey(recipient.publicKey)
            updatedSessionKeys.remove(recipientPublicKey.keyID)
            updatedRecipientKeyIds.remove(recipientPublicKey.keyID)
        }
        
        // Update chunk metadata in StorageDataStore
        val updatedChunk = chunk.copy(
            recipientKeyIds = updatedRecipientKeyIds,
            sessionKeys = updatedSessionKeys
        )
        storageDataStore.updateChunk(updatedChunk)
        
        reEncryptedChunkIndexes.add(chunk.chunkIndex)
    }
    
    return reEncryptedChunkIndexes
}
```

**Rationale**: Implements storage-side re-encryption per Section 3.3, avoiding chunk retransmission.

### 4.2 Add Permission Update Handler
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**NEW FUNCTION**:
```kotlin
/**
 * Storage Node-Side: Handle Permission Update
 * Implements CANONICAL_WORKFLOWS.md Section 3.2-3.3
 */
fun handleFilePermissionUpdate(
    senderId: Int,
    updateMsg: MeshEcosystemMessage.FilePermissionUpdateMessage
) {
    scope.launch {
        betaLogger.log(LogLevel.INFO, TAG,
            "Received permission update for file ${updateMsg.fileId} from node $senderId")
        
        // Section 3.2: Check if node stores chunks for this fileId
        val chunks = storageDataStore.getChunksForFile(updateMsg.fileId)
        if (chunks.isEmpty()) {
            betaLogger.log(LogLevel.DEBUG, TAG,
                "No chunks for file ${updateMsg.fileId}, ignoring permission update")
            return@launch
        }
        
        // Section 3.3: Re-encrypt chunk keys
        val reEncryptedIndexes = encryptionManager.reEncryptChunkKeys(
            fileId = updateMsg.fileId,
            addedRecipients = updateMsg.addedRecipients,
            removedRecipients = updateMsg.removedRecipients,
            servicePrivateKey = servicePrivateKey
        )
        
        betaLogger.log(LogLevel.INFO, TAG,
            "Re-encrypted ${reEncryptedIndexes.size} chunks for file ${updateMsg.fileId}")
        
        // Section 3.3: Send confirmation to client and all recipients
        val confirmation = FileAccessUpdateConfirmation(
            nodeId = virtualNode.addressAsInt.toString(),
            fileId = updateMsg.fileId,
            chunkIndexes = reEncryptedIndexes
        )
        
        // Send to client (sender)
        virtualNode.sendDirectMessage(senderId, confirmation.toBytes())
        
        // Send to all recipients (including tasks)
        for (recipient in updateMsg.addedRecipients) {
            if (recipient.recipientType == RecipientType.TASK) {
                // Task recipient - send to compute node running the task
                val taskNodeAddress = findComputeNodeForTask(recipient.taskId)
                if (taskNodeAddress != null) {
                    virtualNode.sendDirectMessage(taskNodeAddress, confirmation.toBytes())
                }
            } else {
                // User recipient - send to user's node
                val userNodeAddress = resolveNodeAddress(recipient.publicKey)
                if (userNodeAddress != null) {
                    virtualNode.sendDirectMessage(userNodeAddress, confirmation.toBytes())
                }
            }
        }
    }
}
```

**Rationale**: Implements storage-side permission update handling per Sections 3.2-3.3.

### 4.3 Add Client-Side Confirmation Handler
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**NEW FUNCTION**:
```kotlin
/**
 * Client-Side: Handle Confirmations
 * Implements CANONICAL_WORKFLOWS.md Section 3.4
 */
private suspend fun handleFilePermissionUpdateConfirmations(
    fileId: String,
    expectedChunkIndexes: Set<Int>,
    timeout: Long = MeshrabiyaConstants.getTimeoutMs()
): Boolean {
    val confirmedChunks = mutableSetOf<Int>()
    val startTime = System.currentTimeMillis()
    var retryCount = 0
    
    while (retryCount < MAX_RETRIES) {
        // Wait for confirmations
        delay(timeout)
        
        // Collect confirmations received
        val confirmations = pendingPermissionConfirmations[fileId] ?: emptyList()
        for (confirmation in confirmations) {
            confirmedChunks.addAll(confirmation.chunkIndexes)
        }
        
        // Check if all chunks confirmed
        if (confirmedChunks.containsAll(expectedChunkIndexes)) {
            betaLogger.log(LogLevel.INFO, TAG,
                "All chunks confirmed for file $fileId permission update")
            return true
        }
        
        // Calculate missing chunks
        val missingIndexes = expectedChunkIndexes - confirmedChunks
        
        betaLogger.log(LogLevel.WARN, TAG,
            "Missing confirmations for ${missingIndexes.size} chunks, retrying...")
        
        // Retry with missing chunk indexes
        virtualNode.coreGossipBroadcastService.sendFilePermissionUpdate(
            FilePermissionUpdateMessage(
                fileId = fileId,
                addedRecipients = addedRecipients,  // From outer scope
                removedRecipients = removedRecipients,  // From outer scope
                chunkIndexes = missingIndexes.toList()
            )
        )
        
        retryCount++
    }
    
    // If retries failed, initiate storeFile for missing chunks
    val missingIndexes = expectedChunkIndexes - confirmedChunks
    if (missingIndexes.isNotEmpty()) {
        betaLogger.log(LogLevel.ERROR, TAG,
            "Failed to get confirmations for ${missingIndexes.size} chunks, re-storing...")
        
        // Re-store missing chunks with updated permissions
        // Implementation depends on having local file access
    }
    
    return false
}
```

**Rationale**: Implements client-side confirmation tracking and retry logic per Section 3.4.

---

## PHASE 5: BROADCAST SERVICE INTEGRATION

### 5.1 Update CoreGossipBroadcastService
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/CoreGossipBroadcastService.kt`

**Current State**: Already has most required methods (sendStorageNodeRequest, sendChunkRetrievalQuery, etc.)

**Required Changes**:

**Update sendChunkRetrievalQuery** (line ~105):
```kotlin
// ADD chunkIndexes parameter:
fun sendChunkRetrievalQuery(fileId: String, chunkIndexes: List<Int>? = null) {
    val query = ChunkRetrievalQuery(
        fileId = fileId,
        chunkIndexes = chunkIndexes  // Add optional parameter
    )
    val message = MeshEcosystemMessage.ChunkRetrievalQueryMessage(query)
    sendBroadcast(message)
}
```

**Rationale**: Enables retry logic with targeted chunk requests.

---

## PHASE 6: MESSAGE ROUTING & ECOSYSTEM LISTENER

### 6.1 Update MeshEcosystemListener Routing
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshEcosystemListener.kt`

**Required Changes**:

**Add routing for StorageNodeRequest** (storage nodes need to respond):
```kotlin
// IN routeMessage() method, ADD:
is MeshEcosystemMessage.StorageNodeRequestMessage -> {
    if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
        // Storage node evaluates request and sends response
        storageManager?.handleStorageNodeRequest(senderId, message.request)
    }
}
```

**Add routing for ChunkRetrievalQuery**:
```kotlin
is MeshEcosystemMessage.ChunkRetrievalQueryMessage -> {
    if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
        storageManager?.handleChunkRetrievalQuery(senderId, message.query)
    }
}
```

**Add routing for FilePermissionUpdate**:
```kotlin
is MeshEcosystemMessage.FilePermissionUpdateMessage -> {
    if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
        storageManager?.handleFilePermissionUpdate(senderId, message)
    }
}
```

**Rationale**: Ensures storage nodes receive and process broadcast requests.

### 6.2 Add Storage Node Request Handler
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt`

**NEW FUNCTION**:
```kotlin
/**
 * Storage Node-Side: Receive Broadcast & Respond
 * Implements CANONICAL_WORKFLOWS.md Section 1.3
 */
fun handleStorageNodeRequest(senderId: Int, request: StorageNodeRequest) {
    scope.launch {
        // Section 1.3: Check eligibility
        
        // Check if already storing this chunk (prevent duplicates)
        if (storageDataStore.hasChunk(request.chunkId)) {
            betaLogger.log(LogLevel.DEBUG, TAG,
                "Already storing chunk ${request.chunkId}, skipping response")
            return@launch
        }
        
        // Validate quota
        if (!storageQuotaManager.canStoreFile(request.chunkSizeBytes)) {
            betaLogger.log(LogLevel.DEBUG, TAG,
                "Insufficient quota for chunk ${request.chunkId}")
            return@launch
        }
        
        // Check system state
        val systemState = checkSystemState()
        if (systemState != "HEALTHY") {
            betaLogger.log(LogLevel.DEBUG, TAG,
                "System state unhealthy: $systemState")
            return@launch
        }
        
        // Send StorageNodeResponse
        val response = StorageNodeResponse(
            nodeId = virtualNode.addressAsInt.toString(),
            availableSpace = storageQuotaManager.getAvailableSpace(),
            totalStorageAllocated = storageQuotaManager.getTotalAllocated(),
            systemState = systemState,
            meshUrl = virtualNode.getMeshUrl(),
            latencyEstimate = estimateLatency(senderId),
            fitnessScore = calculateFitnessScore(),
            fileId = request.fileId,
            servicePublicKey = servicePublicKey  // From Step 0 keypair
        )
        
        val responseMsg = StorageNodeResponseMessage(
            requestId = request.requestId,
            response = response
        )
        
        // Send response via direct message or broadcast (depends on architecture)
        virtualNode.sendDirectMessage(senderId, responseMsg.toBytes())
        
        betaLogger.log(LogLevel.DEBUG, TAG,
            "Sent storage node response for chunk ${request.chunkId}")
    }
}
```

**Rationale**: Implements storage node response logic per Section 1.3.

---

## PHASE 7: COMPUTE WORKFLOW INTEGRATION

### 7.1 Update Task Result Storage
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/IntelligentDistributedComputeService.kt`

**Required Changes**:

**Locate task completion section** and update to use canonical storeFile:
```kotlin
// In task completion handler, REPLACE AccessScope.TASK_ISOLATED with explicit recipients:
val fileRef = distributedStorageManager.storeFile(
    path = outputPath,
    data = outputBytes,
    priority = SyncPriority.HIGH,
    replicationLevel = ReplicationLevel.STANDARD,
    owner = taskContext.requesterNodeId,
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
    // accessScope parameter REMOVED
)
```

**Rationale**: Section 7.2 requires explicit recipient list instead of AccessScope.

### 7.2 Add FilePermissionUpdate Broadcast After Storage
**File**: Same as 7.1

**Add after storeFile** (Section 7.2 step 3):
```kotlin
// After building OutputManifest:
for (fileRef in outputManifest.fileReferences) {
    val updateMsg = FilePermissionUpdateMessage(
        fileId = fileRef.id,
        addedRecipients = listOf(
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
        ),
        removedRecipients = emptyList()
    )
    
    virtualNode.coreGossipBroadcastService.sendFilePermissionUpdate(updateMsg)
}
```

**Rationale**: Section 7.2 step 3 requires broadcasting permission updates for output files.

---

## PHASE 8: ENCRYPTION MANAGER UPDATES

### 8.1 Add Service Keypair Generation
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/StorageEncryptionManager.kt`

**NEW FUNCTION**:
```kotlin
/**
 * Generate service keypair for DistributedStorageManager.
 * Implements CANONICAL_WORKFLOWS.md Step 0
 * 
 * @return Pair of (publicKey, privateKey) as ByteArrays
 */
fun generateServiceKeypair(): Pair<ByteArray, ByteArray> {
    // Use existing PGP key generation infrastructure
    val keyPair = pgpKeyGenerator.generateKeyPair(
        keySize = 2048,
        identity = "DistributedStorageManager Service Key"
    )
    
    val publicKey = keyPair.publicKey.encoded
    val privateKey = keyPair.privateKey.privateKeyDataPacket.encoded
    
    betaLogger.log(LogLevel.INFO, "StorageEncryption",
        "Generated service keypair: publicKey size=${publicKey.size}B")
    
    return Pair(publicKey, privateKey)
}
```

**Rationale**: Step 0 requires service keypair generation at startup.

### 8.2 Add Chunk Encryption with Storage Node Key
**File**: Same as 8.1

**NEW FUNCTION**:
```kotlin
/**
 * Encrypt chunk with hybrid encryption including storage node service key.
 * Implements CANONICAL_WORKFLOWS.md Section 1.5
 * 
 * @param data Chunk data (unencrypted)
 * @param ownerPublicKey Owner's public key
 * @param storageNodeServicePublicKey Storage node's service public key from Step 0
 * @param recipientPublicKeys List of recipient public keys
 * @return Encrypted chunk bytes with session keys
 */
fun encryptChunkWithRecipients(
    data: ByteArray,
    ownerPublicKey: String,
    storageNodeServicePublicKey: ByteArray,
    recipientPublicKeys: List<String>
): Pair<ByteArray, Map<Long, ByteArray>> {
    // Generate random chunk key
    val chunkKey = generateRandomKey(32)
    
    // Encrypt data with chunk key
    val encryptedData = encryptWithSymmetricKey(data, chunkKey)
    
    // Encrypt chunk key for: owner + storage node + all recipients
    val sessionKeys = mutableMapOf<Long, ByteArray>()
    
    // Owner
    val ownerKey = loadPublicKey(ownerPublicKey)
    sessionKeys[ownerKey.keyID] = encryptKeyForRecipient(chunkKey, ownerKey)
    
    // Storage node service key
    val storageKey = loadPublicKeyFromBytes(storageNodeServicePublicKey)
    sessionKeys[storageKey.keyID] = encryptKeyForRecipient(chunkKey, storageKey)
    
    // All recipients
    for (recipientKeyStr in recipientPublicKeys) {
        val recipientKey = loadPublicKey(recipientKeyStr)
        sessionKeys[recipientKey.keyID] = encryptKeyForRecipient(chunkKey, recipientKey)
    }
    
    return Pair(encryptedData, sessionKeys)
}
```

**Rationale**: Section 1.5 requires chunk encryption with owner + storage node service key + all recipients.

---

## PHASE 9: TESTING & VALIDATION

### 9.1 Unit Tests Required

**File**: `Meshrabiya/src/test/java/com/ustadmobile/meshrabiya/storage/CanonicalWorkflowTests.kt` (NEW)

**Test Cases**:
```kotlin
class CanonicalWorkflowTests {
    @Test
    fun testServiceKeypairGeneration() {
        // Verify Step 0: Service keypair created at startup
    }
    
    @Test
    fun testChunkCreationUnencrypted() {
        // Verify Step 1.1: Chunks created unencrypted
    }
    
    @Test
    fun testSingleNodeSelection() {
        // Verify Step 1.4: Only ONE node selected per chunk
    }
    
    @Test
    fun testEncryptionAfterNodeSelection() {
        // Verify Step 1.5: Encryption happens after node selection
    }
    
    @Test
    fun testReplicaCountIncrementBeforeStore() {
        // Verify Step 1.6: replicaCount = received + 1
    }
    
    @Test
    fun testDaisyChainReplication() {
        // Verify Section 4: Daisy-chain flow Client(0)→A(1)→B(2)→C(3)
    }
    
    @Test
    fun testNoLocalCaching() {
        // Verify Section 2: No local storage check
    }
    
    @Test
    fun testStorageSideReEncryption() {
        // Verify Section 3.3: Re-encryption on storage nodes
    }
    
    @Test
    fun testChunkIndexesRetry() {
        // Verify retry with optional chunkIndexes parameter
    }
    
    @Test
    fun testAccessScopeDeprecation() {
        // Verify storeFile works without AccessScope
    }
}
```

### 9.2 Integration Tests Required

**File**: `Meshrabiya/src/test/java/com/ustadmobile/meshrabiya/integration/CanonicalIntegrationTests.kt` (NEW)

**Test Scenarios**:
1. **End-to-End Storage**: Client stores file → Single node selected → Daisy-chain replication → Target replicas reached
2. **End-to-End Retrieval**: Client queries mesh → Chunks downloaded → File reassembled → No local cache used
3. **Permission Update**: Client broadcasts update → Storage nodes re-encrypt → Confirmations sent → Retry for missing
4. **Task Output Storage**: Compute node completes → Results stored with task permissions → Permission updates broadcast
5. **Replica Count Propagation**: Verify count increments naturally through chain without synchronization

---

## PHASE 10: DEPRECATION & CLEANUP

### 10.1 Deprecate Old Functions

**Mark as @Deprecated**:
- `findBestStorageNodesForChunk()` (replaced by singular version)
- `encryptWithRecipients()` without storage node key parameter
- Any AccessScope-related utility functions

### 10.2 Remove Unused Code

**Files to clean**:
- Remove AccessScope references from all storage-related classes
- Remove local file caching logic from retrieveFile()
- Remove parallel chunk sending logic (replaced by single node selection)

---

## IMPLEMENTATION TIMELINE

### Week 1-2: Foundation (Phases 1-2)
- Update data structures
- Refactor storeFile() for correct encryption timing
- Implement single node selection
- Implement handleIncomingChunkStorage()
- Implement daisy-chain replication

### Week 3: Retrieval & Updates (Phases 3-4)
- Remove local caching from retrieveFile()
- Implement storage-side re-encryption
- Add permission update handlers
- Add confirmation tracking

### Week 4: Integration (Phases 5-7)
- Update CoreGossipBroadcastService
- Update MeshEcosystemListener routing
- Integrate compute workflow changes
- Update task result storage

### Week 5: Encryption & Testing (Phases 8-9)
- Add service keypair generation
- Add chunk encryption with storage node key
- Write unit tests
- Write integration tests

### Week 6: Cleanup & Validation (Phase 10)
- Deprecate old functions
- Remove unused code
- Full end-to-end validation
- Performance benchmarking

---

## CRITICAL SUCCESS FACTORS

1. **Replica Count Propagation**: Ensure replicaCount field is added to ALL relevant messages and incremented correctly
2. **Single Node Selection**: Completely replace parallel broadcast with single node selection + daisy-chain
3. **Encryption Timing**: Ensure chunks are NOT encrypted until after storage node selection
4. **No Local Caching**: Remove ALL local file checks from retrieve workflow
5. **Storage-Side Re-encryption**: Move re-encryption from client to storage nodes
6. **Service Keypair**: Generate and use service keypair for all chunk encryption
7. **AccessScope Removal**: Complete removal of AccessScope from all workflows

---

## RISK MITIGATION

### Risk 1: Breaking Existing Storage
**Mitigation**: Implement migration strategy to re-encrypt existing chunks with new format

### Risk 2: Performance Impact
**Mitigation**: Benchmark daisy-chain vs parallel replication; optimize if needed

### Risk 3: Network Partition During Replication
**Mitigation**: Implement retry logic and timeout handling per canonical workflow

### Risk 4: Data Loss During Migration
**Mitigation**: Maintain backward compatibility until full migration complete

---

## VALIDATION CHECKLIST

- [ ] Service keypair generated at DistributedStorageManager startup
- [ ] Chunks created unencrypted in Step 1.1
- [ ] Encryption happens in Step 1.5 after node selection
- [ ] Only ONE node selected per chunk initially
- [ ] replicaCount incremented BEFORE storage (receive N, store N+1)
- [ ] Daisy-chain replication working (each node replicates to ONE more node)
- [ ] Natural termination when stored count >= target
- [ ] No local caching in retrieve workflow
- [ ] Storage-side re-encryption implemented
- [ ] Confirmations tracked and retried with chunkIndexes
- [ ] AccessScope removed from all workflows
- [ ] All broadcasts go through CoreGossipBroadcastService
- [ ] MeshEcosystemListener routes all message types correctly
- [ ] End-to-end tests pass for all 7 canonical workflows

---

**This refactoring plan provides complete alignment with CANONICAL_WORKFLOWS.md while maintaining backward compatibility where possible and providing clear migration paths where breaking changes are required.**
