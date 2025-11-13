# Task KeyPair Enhancement Implementation Plan - Part 1

**Date**: November 13, 2025  
**Purpose**: Per-task encryption keypairs for task data isolation and dynamic file sharing  
**Related Documents**: 
- TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md (Part 1)
- TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md
- TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md
- TASK_KEYPAIR_ENHANCEMENT_QUESTIONS.md (answers)

---

## Executive Summary

This enhancement adds per-task PGP keypairs to isolate task data from compute node operators and enable dynamic file sharing with running tasks. Key features:

1. **Per-Task Keypair Generation**: Compute node generates unique RSA keypair when receiving TASK_ASSIGNMENT
2. **Task Identity**: Each task has its own cryptographic identity (keypair) separate from node identity
3. **Data Isolation**: Files are encrypted for task pub key only, compute node operator cannot read task data
4. **Dynamic File Sharing**: Users can add files to running tasks via drop folders, encrypted with task pub key
5. **Transparent Decryption**: Sandbox container automatically decrypts files using environment variable keypair
6. **Multi-Tenant Security**: Multiple tasks on same compute node cannot access each other's data

### Security Goals (All Addressed)
- ✅ Isolate task data from compute node owner
- ✅ Allow dynamic permission grants (add files without re-submitting)
- ✅ Provide task-level access control (separate from node-level)
- ✅ Support multi-tenant compute nodes (task isolation)

### Lifecycle Correction
The original plan (Part 3 Section 8) incorrectly described the task lifecycle. The **corrected lifecycle** is:

```
1. Client broadcasts TASK_REQUEST
2. Only compute nodes with runtime + adequate capabilities respond
3. Client sends TASK_ASSIGNMENT to selected node
4. Compute node generates per-task keypair
5. Compute node sends TASK_SCHEDULED (with task pub key) or TASK_REJECTED
6. Client re-encrypts input files to add task pub key access
7. Compute node executes task
8. Compute node sends TASK_COMPLETED
```

---

## Section 1: Corrected Task Lifecycle & Message Flow

### 1.1 Complete Task Lifecycle (Corrected)

The original implementation plan had an incomplete understanding of the task request lifecycle. This section provides the **complete and correct** flow:

#### Phase 1: Task Discovery (Broadcast)
```kotlin
// Client Node (IntelligentDistributedComputeService)
fun requestComputeTask(task: TaskRequest) {
    // Broadcast to mesh to find capable nodes
    val discoveryMessage = TaskDiscoveryMessage(
        taskId = task.taskId,
        taskType = task.taskType,
        jobType = task.jobType,
        resourceRequirements = task.resourceLimits,
        estimatedDuration = task.estimatedDurationMs
    )
    
    meshNetwork.broadcast(
        messageType = MessageType.TASK_DISCOVERY,
        data = discoveryMessage
    )
    
    // Wait for responses (timeout: 30 seconds)
    startResponseTimer(task.taskId, timeoutMs = 30000)
}

// Compute Node (MeshrabiyaVirtualNode)
fun handleTaskDiscoveryMessage(message: TaskDiscoveryMessage) {
    // Check if this node can handle the task
    if (!canHandleTask(message)) {
        return  // Don't respond if incapable
    }
    
    // Verify runtime availability
    if (!RuntimeRegistry.isRuntimeAvailable(message.taskType)) {
        return
    }
    
    // Calculate current fitness/capacity
    val currentLoad = TaskManager.getTotalLoad()
    val availableCapacity = estimateAvailableCapacity()
    
    if (!hasAdequateCapacity(message.resourceRequirements, availableCapacity)) {
        return
    }
    
    // Respond with capabilities
    val responseMessage = TaskCapabilityResponse(
        taskId = message.taskId,
        nodeId = getNodeId(),
        nodePubKey = getNodePubKey(),
        availableCapacity = availableCapacity,
        estimatedStartTime = calculateEstimatedStartTime(),
        reputation = getNodeReputation()
    )
    
    sendMessage(
        targetAddress = message.requesterAddress,
        messageType = MessageType.TASK_CAPABILITY_RESPONSE,
        data = responseMessage
    )
}
```

#### Phase 2: Node Selection
```kotlin
// Client Node (IntelligentDistributedComputeService)
private val taskDiscoveryResponses = mutableMapOf<String, MutableList<TaskCapabilityResponse>>()

fun handleTaskCapabilityResponse(response: TaskCapabilityResponse) {
    taskDiscoveryResponses
        .getOrPut(response.taskId) { mutableListOf() }
        .add(response)
}

private fun selectBestNode(taskId: String): TaskCapabilityResponse? {
    val responses = taskDiscoveryResponses[taskId] ?: return null
    
    if (responses.isEmpty()) return null
    
    // Selection criteria (can be enhanced later)
    return responses
        .filter { it.availableCapacity.ramBytes >= task.resourceLimits.maxMemoryBytes }
        .sortedByDescending { it.reputation }
        .sortedBy { it.estimatedStartTime }
        .firstOrNull()
}

private fun onTaskDiscoveryTimeout(taskId: String) {
    val selectedNode = selectBestNode(taskId)
    
    if (selectedNode == null) {
        failTask(taskId, "No capable compute nodes responded")
        return
    }
    
    // Proceed to Phase 3: Task Assignment
    assignTaskToNode(taskId, selectedNode)
}
```

#### Phase 3: Task Assignment (Generates Keypair)
```kotlin
// Client Node (IntelligentDistributedComputeService)
fun assignTaskToNode(taskId: String, selectedNode: TaskCapabilityResponse) {
    val task = getTaskRequest(taskId) ?: return
    
    // NOTE: Input files are encrypted for OWNER only at this point
    // They will be re-encrypted for task pub key after TASK_SCHEDULED response
    
    val assignmentMessage = TaskAssignmentMessage(
        taskId = task.taskId,
        taskType = task.taskType,
        jobType = task.jobType,
        codeBundle = task.codeBundle,  // Base64 encoded
        inputFileIds = task.inputFileIds,  // Encrypted for owner only
        resourceLimits = task.resourceLimits,
        requesterNodeId = getNodeId(),
        requesterAddress = getNodeAddress(),
        assignedAt = System.currentTimeMillis()
    )
    
    sendMessage(
        targetAddress = selectedNode.nodeId,
        messageType = MessageType.TASK_ASSIGNMENT,
        data = assignmentMessage
    )
    
    // Update task status
    updateTaskStatus(taskId) {
        it.copy(
            status = TaskExecutionStatus.ASSIGNED,
            executorNodeAddress = selectedNode.nodeId,
            assignedAt = System.currentTimeMillis()
        )
    }
}

// Compute Node (MeshrabiyaVirtualNode)
fun handleTaskAssignmentMessage(message: TaskAssignmentMessage) {
    // Re-verify runtime (could have changed since discovery)
    if (!RuntimeRegistry.isRuntimeAvailable(message.taskType)) {
        sendTaskRejection(
            taskId = message.taskId,
            reason = "Runtime no longer available",
            requesterAddress = message.requesterAddress
        )
        return
    }
    
    // Re-verify capacity (could have changed since discovery)
    val currentLoad = TaskManager.getTotalLoad()
    if (!hasAdequateCapacity(message.resourceLimits, currentLoad)) {
        sendTaskRejection(
            taskId = message.taskId,
            reason = "Capacity dropped below required levels",
            requesterAddress = message.requesterAddress
        )
        return
    }
    
    // *** GENERATE PER-TASK KEYPAIR ***
    val taskKeypair = generateTaskKeypair(message.taskId)
    
    // Store keypair in TaskManager (in-memory only)
    TaskManager.registerTaskKeypair(message.taskId, taskKeypair)
    
    // Send TASK_SCHEDULED with public key
    val scheduledMessage = TaskScheduledMessage(
        taskId = message.taskId,
        taskPubKey = taskKeypair.publicKeyPem,  // PGP public key PEM format
        scheduledStartTime = System.currentTimeMillis() + 5000,  // Start in 5 seconds
        estimatedCompletionTime = calculateEstimatedCompletion(message)
    )
    
    sendMessage(
        targetAddress = message.requesterAddress,
        messageType = MessageType.TASK_SCHEDULED,
        data = scheduledMessage
    )
    
    // Create execution context (without decrypted input files yet)
    val executionContext = TaskExecutionContext(
        taskId = message.taskId,
        taskType = message.taskType,
        jobType = message.jobType,
        codeBundle = message.codeBundle,
        inputFileIds = message.inputFileIds,  // Still encrypted for owner
        resourceLimits = message.resourceLimits,
        requesterNodeId = message.requesterNodeId,
        callbackAddress = message.requesterAddress
    )
    
    // Schedule task for execution (will wait for re-encrypted files)
    TaskManager.scheduleTask(executionContext, taskKeypair)
}

private fun generateTaskKeypair(taskId: String): TaskKeypair {
    // Generate RSA 2048-bit keypair (100-500ms overhead acceptable)
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair()
    
    // Convert to PGP format (PEM encoding)
    val publicKeyPem = convertToPGPPublicKey(keyPair.public)
    val privateKeyPem = convertToPGPPrivateKey(keyPair.private)
    
    return TaskKeypair(
        taskId = taskId,
        publicKeyPem = publicKeyPem,
        privateKeyPem = privateKeyPem,
        createdAt = System.currentTimeMillis()
    )
}
```

#### Phase 4: File Re-Encryption (Client Side)
```kotlin
// Client Node (IntelligentDistributedComputeService)
fun handleTaskScheduledMessage(message: TaskScheduledMessage) {
    val task = getTaskRequest(message.taskId) ?: return
    
    // Update task status with keypair info
    updateTaskStatus(message.taskId) {
        it.copy(
            status = TaskExecutionStatus.SCHEDULED,
            taskPubKey = message.taskPubKey,
            scheduledStartTime = message.scheduledStartTime
        )
    }
    
    // *** RE-ENCRYPT INPUT FILES TO ADD TASK PUB KEY ACCESS ***
    // Note: This uses the DistributedStorageManager enhancement described in Section 2
    coroutineScope.launch {
        task.inputFileIds.forEach { fileId ->
            DistributedStorageManager.updateFileAccess(
                fileId = fileId,
                addRecipients = listOf(
                    Recipient(
                        recipientId = message.taskId,  // Task as recipient
                        recipientPubKey = message.taskPubKey,
                        recipientType = RecipientType.TASK
                    )
                )
            )
        }
        
        // Notify compute node that files are ready
        sendMessage(
            targetAddress = task.executorNodeAddress!!,
            messageType = MessageType.TASK_FILES_READY,
            data = TaskFilesReadyMessage(
                taskId = message.taskId,
                fileIds = task.inputFileIds
            )
        )
    }
}

// Compute Node (MeshrabiyaVirtualNode)
fun handleTaskFilesReadyMessage(message: TaskFilesReadyMessage) {
    // Signal TaskManager to begin execution
    TaskManager.beginTaskExecution(message.taskId)
}
```

#### Phase 5: Task Execution (Using Keypair)
```kotlin
// Compute Node (TaskManager)
suspend fun beginTaskExecution(taskId: String) {
    val executionState = executionStates[taskId] ?: return
    val taskKeypair = taskKeypairs[taskId] ?: return
    
    // Update status
    executionState.status = TaskExecutionStatus.RUNNING
    executionState.executionStartedAt = System.currentTimeMillis()
    
    try {
        // Execute task (see Section 3 for full implementation)
        val result = executeTaskWithKeypair(executionState, taskKeypair)
        
        // Clean up keypair immediately after completion
        taskKeypairs.remove(taskId)
        
        // Send completion notification
        sendCompletionNotification(executionState, result)
        
    } catch (e: Exception) {
        // Clean up keypair on failure
        taskKeypairs.remove(taskId)
        
        handleTaskFailure(executionState, e)
    } finally {
        cleanupExecution(taskId)
    }
}
```

#### Phase 6: Task Completion
```kotlin
// Compute Node (TaskManager)
private suspend fun sendCompletionNotification(
    state: ExecutionState,
    result: ExecutionResult
) {
    val completionMessage = TaskCompletedMessage(
        taskId = state.context.taskId,
        success = result.success,
        outputManifest = result.outputManifest,  // Encrypted for requester + task
        resultMessage = result.resultMessage,
        executionStats = ExecutionStats(
            startTime = state.executionStartedAt!!,
            endTime = System.currentTimeMillis(),
            peakMemory = state.peakMetrics.ramPeakBytes,
            totalCpuTime = state.peakMetrics.cpuTimeUsedMs
        ),
        errorMessage = result.errorMessage,
        errorType = result.errorType
    )
    
    sendMessage(
        targetAddress = state.context.callbackAddress,
        messageType = MessageType.TASK_COMPLETED,
        data = completionMessage
    )
}

// Client Node (MeshrabiyaVirtualNode)
fun handleTaskCompletionMessage(message: TaskCompletedMessage) {
    if (message.success) {
        IntelligentDistributedComputeService.completeTask(message.taskId, message)
    } else {
        IntelligentDistributedComputeService.failTask(
            message.taskId,
            message.errorMessage ?: "Unknown error"
        )
    }
    
    // Send acknowledgment
    sendTaskCompletionAck(message.taskId, message.requesterAddress)
}
```

### 1.2 New Message Types

Add to `MessageType.kt` enum:

```kotlin
enum class MessageType(val id: Int) {
    // ... existing types ...
    
    // Task Discovery & Assignment (corrected lifecycle)
    TASK_DISCOVERY(24),              // Broadcast to find capable nodes
    TASK_CAPABILITY_RESPONSE(25),    // Compute node responds with capabilities
    TASK_ASSIGNMENT(26),             // Client assigns task to selected node
    TASK_REJECTED(27),               // Compute node rejects (capacity/runtime unavailable)
    TASK_SCHEDULED(28),              // Compute node accepts + sends task pub key
    TASK_FILES_READY(29),            // Client signals files re-encrypted
    TASK_COMPLETED(30),              // Compute node sends result
    TASK_COMPLETION_ACK(31),         // Client acknowledges receipt
    
    // Dynamic File Sharing
    FILE_ACCESS_UPDATED(32);         // Notification of new file access (existing system)
}
```

### 1.3 New Message Data Classes

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskMessages.kt

package org.torproject.android.meshrabiya.compute

import kotlinx.serialization.Serializable

/**
 * Phase 1: Task Discovery (Broadcast)
 */
@Serializable
data class TaskDiscoveryMessage(
    val taskId: String,
    val taskType: TaskType,
    val jobType: JobType,
    val resourceRequirements: ResourceLimits,
    val estimatedDurationMs: Long?,
    val requesterAddress: String
) : MessageData

/**
 * Phase 1: Compute Node Response
 */
@Serializable
data class TaskCapabilityResponse(
    val taskId: String,
    val nodeId: String,
    val nodePubKey: String,
    val availableCapacity: ResourceMetrics,
    val estimatedStartTime: Long,
    val reputation: Double = 1.0  // Future: node reputation system
) : MessageData

/**
 * Phase 3: Task Assignment
 */
@Serializable
data class TaskAssignmentMessage(
    val taskId: String,
    val taskType: TaskType,
    val jobType: JobType,
    val codeBundle: String,  // Base64 encoded ZIP/JAR/PY
    val inputFileIds: List<String>,  // Initially encrypted for owner only
    val resourceLimits: ResourceLimits,
    val requesterNodeId: String,
    val requesterAddress: String,
    val assignedAt: Long
) : MessageData

/**
 * Phase 3: Task Rejection (capacity/runtime unavailable)
 */
@Serializable
data class TaskRejectionMessage(
    val taskId: String,
    val reason: String,
    val rejectedAt: Long = System.currentTimeMillis()
) : MessageData

/**
 * Phase 3: Task Scheduled (with pub key)
 * Sent after keypair generation
 */
@Serializable
data class TaskScheduledMessage(
    val taskId: String,
    val taskPubKey: String,  // PGP public key PEM format
    val scheduledStartTime: Long,
    val estimatedCompletionTime: Long?
) : MessageData

/**
 * Phase 4: Files Re-Encrypted Notification
 */
@Serializable
data class TaskFilesReadyMessage(
    val taskId: String,
    val fileIds: List<String>
) : MessageData

/**
 * Phase 6: Task Completion (existing, no changes)
 */
@Serializable
data class TaskCompletedMessage(
    val taskId: String,
    val success: Boolean,
    val outputManifest: OutputManifest?,
    val resultMessage: String?,
    val executionStats: ExecutionStats,
    val errorMessage: String?,
    val errorType: ExecutionErrorType?
) : MessageData

/**
 * Phase 6: Completion Acknowledgment
 */
@Serializable
data class TaskCompletionAckMessage(
    val taskId: String,
    val receivedAt: Long = System.currentTimeMillis()
) : MessageData
```

---

## Section 2: Storage System Enhancements

### 2.1 File Access Model Enhancement

The current storage model encrypts files for `owner + recipients`. This enhancement adds support for **tasks as recipients**.

#### Recipient Types

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/RecipientTypes.kt

package org.torproject.android.meshrabiya.storage

import kotlinx.serialization.Serializable

enum class RecipientType {
    USER,           // Regular mesh user (identified by node pub key)
    TASK,           // Compute task (identified by task ID + task pub key)
    STORAGE_NODE    // Storage node (identified by node pub key)
}

@Serializable
data class Recipient(
    val recipientId: String,        // User ID, Task ID, or Node ID
    val recipientPubKey: String,    // PGP public key PEM format
    val recipientType: RecipientType,
    val addedAt: Long = System.currentTimeMillis()
)
```

#### Storage API Extensions

**Location**: `Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/DistributedStorageManager.kt` (line ~317)

**Current signature** (from Part 1 Section 2):
```kotlin
suspend fun storeFile(
    file: File,
    accessScope: AccessScope,
    owner: String,
    recipients: List<String>
): String
```

**Enhanced signature**:
```kotlin
suspend fun storeFile(
    file: File,
    accessScope: AccessScope,
    owner: String,
    recipients: List<Recipient>  // Changed from List<String> to List<Recipient>
): String {
    // Implementation in Section 2.2
}
```

**New method**: Update file access for existing files
```kotlin
suspend fun updateFileAccess(
    fileId: String,
    addRecipients: List<Recipient> = emptyList(),
    removeRecipients: List<String> = emptyList()  // Recipient IDs to remove
): Boolean {
    // Implementation in Section 2.3
}
```

### 2.2 Enhanced storeFile() Implementation

This is the **critical enhancement** that enables task keypair support in the storage layer.

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/DistributedStorageManager.kt

suspend fun storeFile(
    file: File,
    accessScope: AccessScope,
    owner: String,
    recipients: List<Recipient>
): String {
    val fileId = generateFileId()
    
    // 1. Chunk file (existing logic)
    val chunks = chunkFile(file)
    
    // 2. Generate symmetric key for file
    val symmetricKey = generateSymmetricKey()
    
    // 3. Encrypt chunks with symmetric key
    val encryptedChunks = chunks.map { chunk ->
        encryptChunkWithSymmetricKey(chunk, symmetricKey)
    }
    
    // 4. Encrypt symmetric key for owner + all recipients
    val recipientKeys = mutableMapOf<String, String>()
    
    // Encrypt for owner (always included)
    val ownerPubKey = getPublicKeyForUser(owner)
    recipientKeys[owner] = encryptSymmetricKeyWithPGP(symmetricKey, ownerPubKey)
    
    // Encrypt for each recipient
    recipients.forEach { recipient ->
        recipientKeys[recipient.recipientId] = encryptSymmetricKeyWithPGP(
            symmetricKey,
            recipient.recipientPubKey
        )
    }
    
    // 5. Select storage node(s)
    val storageNodes = selectStorageNodes(fileId, accessScope)
    
    // 6. Re-encrypt to add storage node access (CRITICAL ENHANCEMENT)
    // Storage nodes need access to manage future access updates
    storageNodes.forEach { storageNode ->
        val storageNodePubKey = getPublicKeyForNode(storageNode.nodeId)
        recipientKeys[storageNode.nodeId] = encryptSymmetricKeyWithPGP(
            symmetricKey,
            storageNodePubKey
        )
    }
    
    // 7. Create file metadata
    val metadata = FileMetadata(
        fileId = fileId,
        fileName = file.name,
        fileSize = file.length(),
        mimeType = getMimeType(file),
        owner = owner,
        recipients = recipients,  // Now includes type information
        accessScope = accessScope,
        chunkIds = encryptedChunks.map { it.chunkId },
        encryptedKeys = recipientKeys,  // Map of recipientId -> encrypted symmetric key
        storageNodes = storageNodes.map { it.nodeId },
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
    
    // 8. Store chunks and metadata
    storeChunksOnNodes(encryptedChunks, storageNodes)
    storeMetadata(metadata)
    
    // 9. Notify recipients (CRITICAL FOR TASK FILE SHARING)
    notifyRecipients(metadata)
    
    return fileId
}

private suspend fun notifyRecipients(metadata: FileMetadata) {
    metadata.recipients.forEach { recipient ->
        when (recipient.recipientType) {
            RecipientType.USER -> {
                // Send to user's node address
                sendMessage(
                    targetAddress = recipient.recipientId,  // User's mesh address
                    messageType = MessageType.FILE_ACCESS_UPDATED,
                    data = FileAccessUpdateMessage(
                        fileId = metadata.fileId,
                        fileName = metadata.fileName,
                        owner = metadata.owner,
                        accessType = "added",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            RecipientType.TASK -> {
                // Send to compute node running the task
                val taskStatus = IntelligentDistributedComputeService.getTaskStatus(recipient.recipientId)
                if (taskStatus?.executorNodeAddress != null) {
                    sendMessage(
                        targetAddress = taskStatus.executorNodeAddress,  // Compute node address
                        messageType = MessageType.FILE_ACCESS_UPDATED,
                        data = TaskFileAccessUpdateMessage(
                            taskId = recipient.recipientId,  // Task ID
                            fileId = metadata.fileId,
                            fileName = metadata.fileName,
                            accessType = "added",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            RecipientType.STORAGE_NODE -> {
                // Storage nodes don't need notification (they already have the file)
            }
        }
    }
}
```

### 2.3 updateFileAccess() Implementation

This enables **dynamic file sharing** with running tasks.

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/DistributedStorageManager.kt

suspend fun updateFileAccess(
    fileId: String,
    addRecipients: List<Recipient> = emptyList(),
    removeRecipients: List<String> = emptyList()
): Boolean {
    // 1. Retrieve file metadata
    val metadata = getFileMetadata(fileId) ?: return false
    
    // 2. Verify caller is owner (permission check)
    val callerId = getCurrentUserId()
    if (metadata.owner != callerId) {
        throw SecurityException("Only file owner can update access")
    }
    
    // 3. Retrieve symmetric key (decrypt with owner's private key)
    val ownerPrivateKey = getPrivateKeyForCurrentUser()
    val encryptedSymmetricKey = metadata.encryptedKeys[callerId]
        ?: throw IllegalStateException("Owner's encrypted key not found")
    val symmetricKey = decryptSymmetricKeyWithPGP(encryptedSymmetricKey, ownerPrivateKey)
    
    // 4. Add new recipients
    val newRecipientKeys = mutableMapOf<String, String>()
    addRecipients.forEach { recipient ->
        newRecipientKeys[recipient.recipientId] = encryptSymmetricKeyWithPGP(
            symmetricKey,
            recipient.recipientPubKey
        )
    }
    
    // 5. Remove recipients
    val updatedKeys = metadata.encryptedKeys.toMutableMap()
    removeRecipients.forEach { recipientId ->
        updatedKeys.remove(recipientId)
    }
    updatedKeys.putAll(newRecipientKeys)
    
    // 6. Update metadata on storage nodes
    val updatedMetadata = metadata.copy(
        recipients = metadata.recipients + addRecipients,
        encryptedKeys = updatedKeys,
        updatedAt = System.currentTimeMillis()
    )
    
    updateMetadataOnStorageNodes(updatedMetadata)
    
    // 7. Notify new recipients
    addRecipients.forEach { recipient ->
        when (recipient.recipientType) {
            RecipientType.USER -> {
                sendMessage(
                    targetAddress = recipient.recipientId,
                    messageType = MessageType.FILE_ACCESS_UPDATED,
                    data = FileAccessUpdateMessage(
                        fileId = fileId,
                        fileName = metadata.fileName,
                        owner = metadata.owner,
                        accessType = "added",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            RecipientType.TASK -> {
                // Notify compute node running the task
                val taskStatus = IntelligentDistributedComputeService.getTaskStatus(recipient.recipientId)
                if (taskStatus?.executorNodeAddress != null) {
                    sendMessage(
                        targetAddress = taskStatus.executorNodeAddress,
                        messageType = MessageType.FILE_ACCESS_UPDATED,
                        data = TaskFileAccessUpdateMessage(
                            taskId = recipient.recipientId,
                            fileId = fileId,
                            fileName = metadata.fileName,
                            accessType = "added",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            RecipientType.STORAGE_NODE -> {
                // No notification needed
            }
        }
    }
    
    return true
}
```

### 2.4 FileMetadata Schema Update

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/FileMetadata.kt

@Serializable
data class FileMetadata(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val owner: String,
    val recipients: List<Recipient>,  // Changed from List<String>
    val accessScope: AccessScope,
    val chunkIds: List<String>,
    val encryptedKeys: Map<String, String>,  // recipientId -> encrypted symmetric key
    val storageNodes: List<String>,  // Node IDs storing this file
    val createdAt: Long,
    val updatedAt: Long
)
```

### 2.5 Storage Node Access Update Handler

**Critical**: Storage nodes need to handle access update requests from file owners.

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/StorageNodeService.kt

fun handleFileAccessUpdateRequest(request: FileAccessUpdateRequest) {
    // 1. Verify requester is file owner
    val metadata = getStoredFileMetadata(request.fileId)
    if (metadata?.owner != request.requesterId) {
        sendError(request.requesterId, "Only owner can update access")
        return
    }
    
    // 2. Update stored metadata
    val updatedMetadata = metadata.copy(
        recipients = request.newRecipients,
        encryptedKeys = request.newEncryptedKeys,
        updatedAt = System.currentTimeMillis()
    )
    
    storeMetadataLocally(updatedMetadata)
    
    // 3. Replicate to other storage nodes (existing replication logic)
    replicateMetadataUpdate(updatedMetadata)
    
    // 4. Acknowledge
    sendAcknowledgment(request.requesterId, request.fileId)
}
```

---

## Section 3: TaskManager Keypair Management

### 3.1 TaskKeypair Data Structure

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskKeypair.kt

package org.torproject.android.meshrabiya.compute

/**
 * Per-task PGP keypair for data isolation
 * 
 * Security Notes:
 * - Stored in-memory only (destroyed on process restart)
 * - Private key injected into sandbox via environment variable
 * - Destroyed immediately after task completion
 */
data class TaskKeypair(
    val taskId: String,
    val publicKeyPem: String,   // PGP public key (PEM format)
    val privateKeyPem: String,  // PGP private key (PEM format) - SENSITIVE
    val createdAt: Long
)
```

### 3.2 TaskManager Keypair Registry

**Location**: `Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt`

```kotlin
object TaskManager {
    // ... existing fields ...
    
    /**
     * In-memory registry of task keypairs
     * Destroyed on app restart (intentional - tasks don't persist across restarts)
     */
    private val taskKeypairs = mutableMapOf<String, TaskKeypair>()
    
    /**
     * Register a newly generated task keypair
     * Called from handleTaskAssignmentMessage after keypair generation
     */
    fun registerTaskKeypair(taskId: String, keypair: TaskKeypair) {
        taskKeypairs[taskId] = keypair
        Log.d(TAG, "Registered keypair for task $taskId")
    }
    
    /**
     * Retrieve task keypair for execution
     * Returns null if task not found or keypair already destroyed
     */
    fun getTaskKeypair(taskId: String): TaskKeypair? {
        return taskKeypairs[taskId]
    }
    
    /**
     * Destroy task keypair immediately after task completion
     * Called from cleanup methods
     */
    fun destroyTaskKeypair(taskId: String) {
        taskKeypairs.remove(taskId)?.let {
            Log.d(TAG, "Destroyed keypair for task $taskId")
        }
    }
    
    /**
     * Get task public key only (for sharing with client)
     * Used when sending TASK_SCHEDULED message
     */
    fun getTaskPublicKey(taskId: String): String? {
        return taskKeypairs[taskId]?.publicKeyPem
    }
}
```

### 3.3 TaskStatus Extension (Client Side)

**Location**: `Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskStatus.kt`

Add new fields to track task keypair and executor node:

```kotlin
@Serializable
data class TaskStatus(
    val taskId: String,
    val status: TaskExecutionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    
    // Existing fields from Part 1
    val executionStartedAt: Long? = null,
    val executorNodeAddress: String? = null,
    val containerId: String? = null,
    val resourceUsage: ResourceMetrics? = null,
    val executionContext: TaskExecutionContext? = null,
    
    // NEW FIELDS for keypair enhancement
    val taskPubKey: String? = null,          // Task's public key (from TASK_SCHEDULED)
    val scheduledStartTime: Long? = null,    // When task will start
    val estimatedCompletionTime: Long? = null
)
```

### 3.4 Keypair Lifecycle Management

#### Keypair Creation (on TASK_ASSIGNMENT)
```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/node/MeshrabiyaVirtualNode.kt

private fun generateTaskKeypair(taskId: String): TaskKeypair {
    try {
        // Generate RSA 2048-bit keypair
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Convert to PGP format (using BouncyCastle)
        val publicKeyPem = convertPublicKeyToPGPFormat(keyPair.public)
        val privateKeyPem = convertPrivateKeyToPGPFormat(keyPair.private)
        
        return TaskKeypair(
            taskId = taskId,
            publicKeyPem = publicKeyPem,
            privateKeyPem = privateKeyPem,
            createdAt = System.currentTimeMillis()
        )
        
    } catch (e: Exception) {
        Log.e(TAG, "Failed to generate keypair for task $taskId", e)
        throw RuntimeException("Keypair generation failed", e)
    }
}

private fun convertPublicKeyToPGPFormat(publicKey: PublicKey): String {
    // Use BouncyCastle PGP library to convert RSA public key to PGP format
    val pgpPubKey = PGPPublicKey(
        PublicKeyAlgorithmTags.RSA_GENERAL,
        publicKey as RSAPublicKey,
        Date()
    )
    
    val outputStream = ByteArrayOutputStream()
    val armoredOutputStream = ArmoredOutputStream(outputStream)
    pgpPubKey.encode(armoredOutputStream)
    armoredOutputStream.close()
    
    return outputStream.toString("UTF-8")
}

private fun convertPrivateKeyToPGPFormat(privateKey: PrivateKey): String {
    // Similar to public key, but with private key material
    // Implementation details depend on BouncyCastle library
    // Returns PEM-encoded private key
    val keyPair = KeyPair(null, privateKey as RSAPrivateKey)
    // ... BouncyCastle conversion logic ...
    return pemEncodedPrivateKey
}
```

#### Keypair Destruction (on task completion/failure)
```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

private suspend fun cleanupExecution(taskId: String) {
    // Remove execution state
    executionStates.remove(taskId)
    
    // *** DESTROY KEYPAIR IMMEDIATELY ***
    destroyTaskKeypair(taskId)
    
    // Remove resource metrics
    taskMetrics.remove(taskId)
    
    // Stop resource monitoring if no tasks running
    if (executionStates.isEmpty()) {
        resourceMonitoringJob?.cancel()
        resourceMonitoringJob = null
    }
    
    Log.d(TAG, "Cleaned up execution for task $taskId (including keypair)")
}

private suspend fun handleTaskFailure(state: ExecutionState, error: Exception) {
    Log.e(TAG, "Task ${state.context.taskId} failed", error)
    
    // *** DESTROY KEYPAIR ON FAILURE ***
    destroyTaskKeypair(state.context.taskId)
    
    val errorResult = ExecutionResult(
        taskId = state.context.taskId,
        success = false,
        outputManifest = null,
        resultMessage = null,
        resourcesUsed = state.currentMetrics,
        executionTimeMs = System.currentTimeMillis() - (state.executionStartedAt ?: 0),
        errorMessage = error.message ?: "Unknown error",
        errorType = determineErrorType(error)
    )
    
    sendCompletionNotification(state, errorResult)
    cleanupExecution(state.context.taskId)
}
```

---

## Section 4: Sandbox Keypair Integration

### 4.1 Environment Variable Injection

The task private key is injected into the sandbox container as an environment variable for transparent decryption.

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/StrangersSafeComputeEngine.kt

fun createSandboxContainer(
    taskId: String,
    taskType: TaskType,
    workspaceDir: File,
    resourceLimits: ResourceLimits,
    taskKeypair: TaskKeypair  // NEW PARAMETER
): MicroContainer {
    val containerId = "task_${taskId}_${System.currentTimeMillis()}"
    
    // Prepare environment variables
    val environment = mutableMapOf<String, String>()
    environment["TASK_ID"] = taskId
    environment["TASK_WORKSPACE"] = workspaceDir.absolutePath
    environment["TASK_OUTPUT_DIR"] = File(workspaceDir, "output").absolutePath
    environment["TASK_INPUT_DIR"] = File(workspaceDir, "input").absolutePath
    
    // *** INJECT TASK PRIVATE KEY ***
    environment["TASK_PRIVATE_KEY"] = taskKeypair.privateKeyPem
    environment["TASK_PUBLIC_KEY"] = taskKeypair.publicKeyPem
    
    // Resource limits
    environment["TASK_MAX_MEMORY_BYTES"] = resourceLimits.maxMemoryBytes.toString()
    environment["TASK_MAX_CPU_TIME_MS"] = resourceLimits.maxCpuTimeMs.toString()
    
    // Create sandbox configuration
    val sandboxConfig = getSandboxConfigForTaskType(taskType, resourceLimits)
    
    // Launch isolated process
    val process = launchIsolatedProcess(
        containerId = containerId,
        workspaceDir = workspaceDir,
        environment = environment,
        sandboxConfig = sandboxConfig
    )
    
    val container = MicroContainer(
        id = containerId,
        taskId = taskId,
        processId = getProcessId(process),
        workspaceDir = workspaceDir,
        createdAt = System.currentTimeMillis(),
        resourceLimits = resourceLimits,
        memoryHistory = mutableListOf()
    )
    
    containers[containerId] = container
    
    return container
}
```

### 4.2 Transparent File Decryption in Sandbox

The sandbox provides a transparent decryption layer so task code sees unencrypted, assembled files.

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/SandboxFileSystem.kt

/**
 * Sandbox filesystem layer that transparently decrypts files for task code
 * 
 * Task code sees:
 * - /task/input/file1.txt (plaintext, assembled if chunked)
 * - /task/input/file2.jpg (plaintext, assembled if chunked)
 * 
 * Actual storage:
 * - /workspace/encrypted/file1.txt.enc (encrypted chunks)
 * - /workspace/encrypted/file2.jpg.enc (encrypted chunks)
 */
class SandboxFileSystem(
    private val taskId: String,
    private val workspaceDir: File,
    private val taskPrivateKey: String  // From environment variable
) {
    private val encryptedDir = File(workspaceDir, "encrypted")
    private val inputDir = File(workspaceDir, "input")
    private val outputDir = File(workspaceDir, "output")
    
    init {
        encryptedDir.mkdirs()
        inputDir.mkdirs()
        outputDir.mkdirs()
    }
    
    /**
     * Prepare input file for task access
     * Downloads encrypted chunks, decrypts, assembles, places in input directory
     */
    suspend fun prepareInputFile(fileId: String, fileName: String): File {
        // 1. Download encrypted file from distributed storage
        val encryptedFile = File(encryptedDir, "$fileName.enc")
        DistributedStorageManager.downloadFile(fileId, encryptedFile)
        
        // 2. Retrieve symmetric key (encrypted for task)
        val metadata = DistributedStorageManager.getFileMetadata(fileId)
            ?: throw IllegalStateException("File metadata not found: $fileId")
        
        val encryptedSymmetricKey = metadata.encryptedKeys[taskId]
            ?: throw SecurityException("Task does not have access to file: $fileId")
        
        // 3. Decrypt symmetric key with task private key
        val symmetricKey = decryptSymmetricKeyWithPGP(encryptedSymmetricKey, taskPrivateKey)
        
        // 4. Decrypt file with symmetric key
        val decryptedFile = File(inputDir, fileName)
        decryptFileWithSymmetricKey(encryptedFile, decryptedFile, symmetricKey)
        
        // 5. Delete encrypted file (save space)
        encryptedFile.delete()
        
        return decryptedFile
    }
    
    /**
     * Handle dynamic file addition (called when FILE_ACCESS_UPDATED received)
     */
    suspend fun addFileToTask(fileId: String, fileName: String) {
        try {
            val inputFile = prepareInputFile(fileId, fileName)
            
            // Trigger task notification event (if task code has registered listener)
            triggerFileAddedEvent(inputFile)
            
            Log.d(TAG, "Added file $fileName to task $taskId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add file $fileName to task $taskId", e)
        }
    }
    
    /**
     * Trigger event in sandbox for task code to detect new file
     * Uses a simple file-based signaling mechanism
     */
    private fun triggerFileAddedEvent(newFile: File) {
        // Create signal file that task code can poll
        val eventFile = File(workspaceDir, ".new_files")
        eventFile.appendText("${newFile.name}\n")
    }
}
```

### 4.3 Task Code Event Handler (Example)

This is example code that task developers can use to detect new files.

```python
# Example Python task code
import os
import time

def watch_for_new_files():
    """Poll for new file notifications"""
    event_file = os.path.join(os.environ['TASK_WORKSPACE'], '.new_files')
    last_size = 0
    
    while True:
        if os.path.exists(event_file):
            current_size = os.path.getsize(event_file)
            if current_size > last_size:
                # New files added
                with open(event_file, 'r') as f:
                    new_files = f.read().splitlines()
                
                for filename in new_files:
                    handle_new_file(filename)
                
                last_size = current_size
        
        time.sleep(1)  # Poll every second

def handle_new_file(filename):
    """Process newly added file"""
    input_dir = os.environ['TASK_INPUT_DIR']
    file_path = os.path.join(input_dir, filename)
    
    print(f"New file added: {filename}")
    # ... process file ...

# Main task logic
def main():
    # Start background thread to watch for new files
    import threading
    watcher = threading.Thread(target=watch_for_new_files, daemon=True)
    watcher.start()
    
    # Main processing logic
    process_initial_files()
    
    # Keep running to receive dynamic files
    while not is_task_complete():
        time.sleep(1)

if __name__ == "__main__":
    main()
```

---

## Section 5: Dynamic File Sharing Integration

### 5.1 Drop Folder Integration

Users share files with tasks by adding them to a drop folder subfolder that is shared with the task.

```kotlin
// File: app/src/main/java/org/torproject/android/orbot/dropfolder/DropFolderMonitor.kt

class DropFolderMonitor(
    private val context: Context,
    private val distributedStorageManager: DistributedStorageManager,
    private val computeService: IntelligentDistributedComputeService
) {
    
    /**
     * Handle new file in drop folder
     * Determines recipients (users + tasks) and uploads with encryption
     */
    suspend fun onFileAdded(file: File, subfolderPath: String) {
        // 1. Determine sharing configuration for this subfolder
        val sharingConfig = getSubfolderSharingConfig(subfolderPath)
        
        // 2. Build recipient list (users + tasks)
        val recipients = mutableListOf<Recipient>()
        
        // Add user recipients
        sharingConfig.sharedWithUsers.forEach { userId ->
            val userPubKey = getUserPublicKey(userId)
            recipients.add(
                Recipient(
                    recipientId = userId,
                    recipientPubKey = userPubKey,
                    recipientType = RecipientType.USER
                )
            )
        }
        
        // Add task recipients
        sharingConfig.sharedWithTasks.forEach { taskId ->
            val taskStatus = computeService.getTaskStatus(taskId)
            if (taskStatus != null && taskStatus.taskPubKey != null) {
                recipients.add(
                    Recipient(
                        recipientId = taskId,
                        recipientPubKey = taskStatus.taskPubKey,
                        recipientType = RecipientType.TASK
                    )
                )
            } else {
                Log.w(TAG, "Task $taskId not found or no pub key available")
            }
        }
        
        // 3. Upload file with encryption for all recipients
        val fileId = distributedStorageManager.storeFile(
            file = file,
            accessScope = AccessScope.SHARED,
            owner = getCurrentUserId(),
            recipients = recipients
        )
        
        Log.d(TAG, "Uploaded file ${file.name} with ${recipients.size} recipients (including tasks)")
        
        // Note: storeFile() automatically notifies all recipients
        // Tasks will receive notification at compute node via FILE_ACCESS_UPDATED message
    }
}
```

### 5.2 FILE_ACCESS_UPDATED Handler (Compute Node)

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/node/MeshrabiyaVirtualNode.kt

fun handleFileAccessUpdateMessage(message: TaskFileAccessUpdateMessage) {
    // Notify TaskManager of new file for task
    TaskManager.onTaskFileAdded(
        taskId = message.taskId,
        fileId = message.fileId,
        fileName = message.fileName
    )
}
```

### 5.3 TaskManager File Addition Handler

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

suspend fun onTaskFileAdded(taskId: String, fileId: String, fileName: String) {
    // 1. Check if task is still running
    val executionState = executionStates[taskId]
    if (executionState == null) {
        Log.d(TAG, "Task $taskId not running, ignoring file addition")
        return
    }
    
    if (executionState.status != TaskExecutionStatus.RUNNING) {
        Log.d(TAG, "Task $taskId not in RUNNING state, ignoring file addition")
        return
    }
    
    // 2. Get sandbox filesystem for task
    val sandboxFs = executionState.sandboxFileSystem
        ?: throw IllegalStateException("No sandbox filesystem for task $taskId")
    
    // 3. Add file to sandbox (downloads, decrypts, places in input directory)
    sandboxFs.addFileToTask(fileId, fileName)
    
    Log.d(TAG, "Added file $fileName to running task $taskId")
}
```

---

This completes Part 1 of the Task KeyPair Enhancement Implementation Plan, covering:
- Corrected task lifecycle with discovery/assignment phases
- Storage system enhancements for task recipients
- TaskManager keypair management
- Sandbox keypair integration with transparent decryption
- Dynamic file sharing via drop folders

**Part 2** will cover:
- Executor implementations with keypair integration
- Testing strategy for keypair security
- Performance optimization
- Error handling and edge cases
- Complete implementation checklist
