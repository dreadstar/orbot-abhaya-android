# DistributedStorageManager API Documentation

**Package**: `org.torproject.meshrabiya.storage`  
**Version**: 1.0.0  
**Status**: Stable  
**Last Updated**: November 14, 2025

---

## Overview

The `DistributedStorageManager` provides a distributed, encrypted file storage layer for the Meshrabiya mesh network. It handles file storage, retrieval, replication, and access control with per-file encryption and dynamic recipient management.

### Key Features

- **Distributed Storage**: Files replicated across mesh nodes for resilience
- **End-to-End Encryption**: All files encrypted with per-file session keys
- **Dynamic Access Control**: Add/remove recipients without re-uploading files
- **Integrity Verification**: SHA-256 checksums for all stored files
- **Efficient Replication**: Intelligent replica placement and failover

---

## Table of Contents

1. [Core API](#core-api)
2. [File Operations](#file-operations)
3. [Access Control](#access-control)
4. [Replication Management](#replication-management)
5. [Metadata Operations](#metadata-operations)
6. [Error Handling](#error-handling)
7. [Usage Examples](#usage-examples)
8. [Best Practices](#best-practices)
9. [Integration Patterns](#integration-patterns)

---

## Core API

### Class: DistributedStorageManager

```kotlin
/**
 * Manages distributed storage across the mesh network
 * 
 * This class provides the primary interface for storing, retrieving, and managing
 * files in the distributed storage layer. All files are encrypted and replicated
 * across multiple nodes for durability.
 *
 * Thread Safety: All methods are thread-safe and can be called concurrently
 * 
 * @property meshNetwork The underlying mesh network for peer communication
 * @property replicationFactor Number of replicas per file (default: 3)
 */
class DistributedStorageManager(
    private val meshNetwork: MeshNetwork,
    private val replicationFactor: Int = 3
) {
    // API methods documented below
}
```

### Initialization

```kotlin
/**
 * Initialize the storage manager
 * 
 * Sets up the local storage directory, initializes metadata store,
 * and starts background replication monitoring.
 *
 * @param localStorageDir Directory for local file cache
 * @throws StorageException if initialization fails
 */
suspend fun initialize(localStorageDir: File)

/**
 * Shutdown the storage manager gracefully
 * 
 * Flushes pending operations, closes metadata store, and stops
 * background tasks.
 */
suspend fun shutdown()
```

**Example**:
```kotlin
val storageManager = DistributedStorageManager(
    meshNetwork = meshNetwork,
    replicationFactor = 3
)

try {
    storageManager.initialize(File("/data/meshrabiya/storage"))
    // ... use storage manager
} finally {
    storageManager.shutdown()
}
```

---

## File Operations

### Store File

```kotlin
/**
 * Store a file in the distributed storage layer
 * 
 * The file is encrypted with a generated session key, replicated across
 * nodes, and metadata is registered in the distributed hash table.
 *
 * @param data Raw file data (unencrypted)
 * @param filename Original filename for metadata
 * @param owner Node ID of the file owner
 * @param recipients List of node IDs authorized to access this file
 * @return Unique file ID (UUID) for future access
 * @throws StorageException if storage fails
 * @throws EncryptionException if encryption fails
 * @throws InsufficientNodesException if not enough nodes for replication
 *
 * Performance: O(replicationFactor) network operations
 * Encryption: AES-256-GCM with per-file session key
 */
suspend fun storeFile(
    data: ByteArray,
    filename: String,
    owner: String,
    recipients: List<String>
): String
```

**Example**:
```kotlin
val fileData = "Hello, Meshrabiya!".toByteArray()
val fileId = storageManager.storeFile(
    data = fileData,
    filename = "greeting.txt",
    owner = "node-alice",
    recipients = listOf("node-alice", "node-bob")
)

println("File stored with ID: $fileId")
// Output: File stored with ID: 550e8400-e29b-41d4-a716-446655440000
```

### Retrieve File

```kotlin
/**
 * Retrieve a file from distributed storage
 * 
 * Fetches the file from the nearest available replica, decrypts it,
 * and verifies integrity using stored checksum.
 *
 * @param fileId Unique file identifier
 * @param requesterId Node ID requesting the file
 * @return Decrypted file data
 * @throws FileNotFoundException if file doesn't exist
 * @throws UnauthorizedException if requester not in recipient list
 * @throws IntegrityException if checksum verification fails
 * @throws RetrievalException if all replicas are unavailable
 *
 * Performance: O(1) for local cache hit, O(replicationFactor) for cache miss
 * Caching: File cached locally after first retrieval
 */
suspend fun retrieveFile(
    fileId: String,
    requesterId: String
): ByteArray
```

**Example**:
```kotlin
try {
    val fileData = storageManager.retrieveFile(
        fileId = "550e8400-e29b-41d4-a716-446655440000",
        requesterId = "node-bob"
    )
    println("File contents: ${String(fileData)}")
    // Output: File contents: Hello, Meshrabiya!
} catch (e: UnauthorizedException) {
    println("Access denied: ${e.message}")
}
```

### Delete File

```kotlin
/**
 * Delete a file from distributed storage
 * 
 * Removes all replicas and metadata. This operation is eventually consistent -
 * some replicas may persist briefly after this method returns.
 *
 * @param fileId Unique file identifier
 * @param requesterId Node ID requesting deletion (must be owner)
 * @throws FileNotFoundException if file doesn't exist
 * @throws UnauthorizedException if requester is not owner
 *
 * Performance: O(replicationFactor) network operations
 * Note: Deletion is not reversible
 */
suspend fun deleteFile(
    fileId: String,
    requesterId: String
)
```

**Example**:
```kotlin
try {
    storageManager.deleteFile(
        fileId = "550e8400-e29b-41d4-a716-446655440000",
        requesterId = "node-alice"  // Must be owner
    )
    println("File deleted successfully")
} catch (e: UnauthorizedException) {
    println("Only the owner can delete this file")
}
```

---

## Access Control

### Update File Access

```kotlin
/**
 * Update access control for a file (add/remove recipients)
 * 
 * Re-encrypts the file's session key for new recipients without
 * re-uploading the entire file. This is the key operation for
 * per-task keypair enhancement.
 *
 * @param fileId Unique file identifier
 * @param addRecipients Node IDs to grant access (optional)
 * @param removeRecipients Node IDs to revoke access (optional)
 * @return True if update succeeded, false otherwise
 * @throws FileNotFoundException if file doesn't exist
 * @throws UnauthorizedException if requester is not owner
 *
 * Performance: O(|addRecipients| + |removeRecipients|) crypto operations
 * Encryption: Session key re-encrypted with each new recipient's public key
 */
suspend fun updateFileAccess(
    fileId: String,
    addRecipients: List<String> = emptyList(),
    removeRecipients: List<String> = emptyList()
): Boolean
```

**Example - Grant Access to Task**:
```kotlin
// Grant task access to file (for task keypair enhancement)
val success = storageManager.updateFileAccess(
    fileId = "550e8400-e29b-41d4-a716-446655440000",
    addRecipients = listOf("task-12345")
)

if (success) {
    println("Task can now access file")
} else {
    println("Failed to update access")
}
```

**Example - Revoke Access**:
```kotlin
// Revoke access from user
val success = storageManager.updateFileAccess(
    fileId = "550e8400-e29b-41d4-a716-446655440000",
    removeRecipients = listOf("node-bob")
)

if (success) {
    println("Access revoked for node-bob")
}
```

### Check File Access

```kotlin
/**
 * Check if a node has access to a file
 *
 * @param fileId Unique file identifier
 * @param nodeId Node ID to check
 * @return True if node has access, false otherwise
 * @throws FileNotFoundException if file doesn't exist
 */
suspend fun hasAccess(
    fileId: String,
    nodeId: String
): Boolean
```

**Example**:
```kotlin
val canAccess = storageManager.hasAccess(
    fileId = "550e8400-e29b-41d4-a716-446655440000",
    nodeId = "node-bob"
)

if (canAccess) {
    val fileData = storageManager.retrieveFile(fileId, "node-bob")
    // ... process file
}
```

---

## Replication Management

### Get Replica Status

```kotlin
/**
 * Get replication status for a file
 *
 * @param fileId Unique file identifier
 * @return ReplicationStatus containing replica count and locations
 * @throws FileNotFoundException if file doesn't exist
 */
suspend fun getReplicationStatus(fileId: String): ReplicationStatus

/**
 * Replication status information
 *
 * @property fileId The file identifier
 * @property targetReplicas Target number of replicas
 * @property currentReplicas Current number of healthy replicas
 * @property replicaLocations List of node IDs hosting replicas
 * @property isHealthy True if currentReplicas >= targetReplicas
 */
data class ReplicationStatus(
    val fileId: String,
    val targetReplicas: Int,
    val currentReplicas: Int,
    val replicaLocations: List<String>,
    val isHealthy: Boolean
)
```

**Example**:
```kotlin
val status = storageManager.getReplicationStatus(
    fileId = "550e8400-e29b-41d4-a716-446655440000"
)

println("Replicas: ${status.currentReplicas}/${status.targetReplicas}")
println("Locations: ${status.replicaLocations}")
println("Health: ${if (status.isHealthy) "✓" else "⚠"}")

// Output:
// Replicas: 3/3
// Locations: [node-1, node-3, node-5]
// Health: ✓
```

### Trigger Replication

```kotlin
/**
 * Manually trigger replication for under-replicated files
 * 
 * Useful for recovering from node failures or network partitions.
 *
 * @param fileId Unique file identifier (optional - if null, all files checked)
 * @return Number of files re-replicated
 */
suspend fun triggerReplication(fileId: String? = null): Int
```

**Example**:
```kotlin
// Replicate specific file
val count = storageManager.triggerReplication(
    fileId = "550e8400-e29b-41d4-a716-446655440000"
)
println("Re-replicated $count files")

// Check all files
val totalCount = storageManager.triggerReplication()
println("Re-replicated $totalCount under-replicated files")
```

---

## Metadata Operations

### Get File Metadata

```kotlin
/**
 * Get metadata for a file
 *
 * @param fileId Unique file identifier
 * @return FileMetadata containing file information
 * @throws FileNotFoundException if file doesn't exist
 */
suspend fun getFileMetadata(fileId: String): FileMetadata

/**
 * File metadata information
 *
 * @property fileId Unique identifier
 * @property filename Original filename
 * @property size File size in bytes
 * @property owner Owner node ID
 * @property recipients List of authorized node IDs
 * @property checksum SHA-256 checksum (hex string)
 * @property createdAt Creation timestamp (ISO-8601)
 * @property modifiedAt Last modification timestamp
 */
data class FileMetadata(
    val fileId: String,
    val filename: String,
    val size: Long,
    val owner: String,
    val recipients: List<String>,
    val checksum: String,
    val createdAt: String,
    val modifiedAt: String
)
```

**Example**:
```kotlin
val metadata = storageManager.getFileMetadata(
    fileId = "550e8400-e29b-41d4-a716-446655440000"
)

println("""
    File: ${metadata.filename}
    Size: ${metadata.size} bytes
    Owner: ${metadata.owner}
    Recipients: ${metadata.recipients.joinToString(", ")}
    Created: ${metadata.createdAt}
    Checksum: ${metadata.checksum}
""".trimIndent())
```

### List Files

```kotlin
/**
 * List all files owned by a node
 *
 * @param owner Node ID of the owner
 * @return List of file IDs owned by this node
 */
suspend fun listFiles(owner: String): List<String>

/**
 * List all files accessible by a node (owned or recipient)
 *
 * @param nodeId Node ID to check
 * @return List of file IDs accessible by this node
 */
suspend fun listAccessibleFiles(nodeId: String): List<String>
```

**Example**:
```kotlin
// List owned files
val ownedFiles = storageManager.listFiles(owner = "node-alice")
println("Owned files: ${ownedFiles.size}")

// List all accessible files (owned + shared)
val accessibleFiles = storageManager.listAccessibleFiles(nodeId = "node-alice")
println("Accessible files: ${accessibleFiles.size}")

// Print details
for (fileId in accessibleFiles) {
    val metadata = storageManager.getFileMetadata(fileId)
    println("- ${metadata.filename} (${metadata.size} bytes)")
}
```

---

## Error Handling

### Exception Hierarchy

```kotlin
/**
 * Base exception for storage operations
 */
sealed class StorageException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * File not found in storage
 */
class FileNotFoundException(fileId: String) :
    StorageException("File not found: $fileId")

/**
 * Unauthorized access attempt
 */
class UnauthorizedException(nodeId: String, fileId: String) :
    StorageException("Node $nodeId not authorized for file $fileId")

/**
 * Integrity check failed
 */
class IntegrityException(fileId: String, expectedChecksum: String, actualChecksum: String) :
    StorageException("Integrity check failed for $fileId: expected $expectedChecksum, got $actualChecksum")

/**
 * Not enough nodes available for replication
 */
class InsufficientNodesException(required: Int, available: Int) :
    StorageException("Insufficient nodes for replication: need $required, have $available")

/**
 * File retrieval failed from all replicas
 */
class RetrievalException(fileId: String, replicasFailed: Int) :
    StorageException("Failed to retrieve file $fileId: all $replicasFailed replicas unavailable")

/**
 * Encryption operation failed
 */
class EncryptionException(message: String, cause: Throwable? = null) :
    StorageException(message, cause)
```

### Error Handling Best Practices

```kotlin
// Handle specific exceptions
try {
    val fileData = storageManager.retrieveFile(fileId, requesterId)
    // ... process file
} catch (e: FileNotFoundException) {
    // File doesn't exist - maybe create it?
    logger.warn("File not found: $fileId")
} catch (e: UnauthorizedException) {
    // Access denied - user error
    logger.error("Unauthorized access attempt by $requesterId")
    throw SecurityException("Access denied")
} catch (e: IntegrityException) {
    // Data corruption - critical error
    logger.error("Integrity check failed: ${e.message}")
    alerting.sendAlert("DATA_CORRUPTION", e)
} catch (e: RetrievalException) {
    // Network issue - retry logic
    logger.warn("Retrieval failed, retrying...")
    delay(1000)
    // ... retry
} catch (e: StorageException) {
    // Generic storage error
    logger.error("Storage operation failed", e)
}
```

---

## Usage Examples

### Example 1: Basic File Storage

```kotlin
suspend fun storeAndRetrieveFile() {
    val storageManager = DistributedStorageManager(meshNetwork)
    storageManager.initialize(File("/data/storage"))
    
    try {
        // Store file
        val fileData = "Hello, World!".toByteArray()
        val fileId = storageManager.storeFile(
            data = fileData,
            filename = "hello.txt",
            owner = "node-alice",
            recipients = listOf("node-alice", "node-bob")
        )
        
        // Retrieve file
        val retrievedData = storageManager.retrieveFile(fileId, "node-bob")
        println(String(retrievedData))  // "Hello, World!"
        
    } finally {
        storageManager.shutdown()
    }
}
```

### Example 2: Task Keypair Enhancement Integration

```kotlin
suspend fun executeTaskWithEncryptedFiles(
    taskId: String,
    inputFileIds: List<String>
) {
    // 1. Generate task keypair
    val taskKeypair = TaskManager.generateTaskKeypair(taskId)
    val taskRecipient = "task-$taskId"
    
    // 2. Grant task access to input files
    for (fileId in inputFileIds) {
        val success = storageManager.updateFileAccess(
            fileId = fileId,
            addRecipients = listOf(taskRecipient)
        )
        
        if (!success) {
            throw Exception("Failed to grant task access to file $fileId")
        }
    }
    
    // 3. Execute task (task can now decrypt input files)
    val result = StrangersSafeComputeEngine.execute(
        taskId = taskId,
        inputFiles = inputFileIds,
        keypair = taskKeypair
    )
    
    // 4. Store output files (encrypted for task owner)
    val outputFileId = storageManager.storeFile(
        data = result.output,
        filename = "output-$taskId.txt",
        owner = result.ownerId,
        recipients = listOf(result.ownerId)
    )
    
    // 5. Cleanup: Remove task access to input files
    for (fileId in inputFileIds) {
        storageManager.updateFileAccess(
            fileId = fileId,
            removeRecipients = listOf(taskRecipient)
        )
    }
    
    // 6. Cleanup: Delete task keypair
    TaskManager.cleanupExpiredKeypairs()
}
```

### Example 3: Dynamic File Sharing

```kotlin
suspend fun shareFileWithMultipleUsers(
    fileId: String,
    newRecipients: List<String>
) {
    // Get current metadata
    val metadata = storageManager.getFileMetadata(fileId)
    println("Current recipients: ${metadata.recipients}")
    
    // Add new recipients
    val success = storageManager.updateFileAccess(
        fileId = fileId,
        addRecipients = newRecipients
    )
    
    if (success) {
        val updatedMetadata = storageManager.getFileMetadata(fileId)
        println("Updated recipients: ${updatedMetadata.recipients}")
        
        // Notify new recipients
        for (recipient in newRecipients) {
            notifyUser(recipient, "You now have access to ${metadata.filename}")
        }
    }
}
```

### Example 4: Replication Health Monitoring

```kotlin
suspend fun monitorReplicationHealth() {
    val allFiles = storageManager.listFiles(owner = "node-alice")
    
    for (fileId in allFiles) {
        val status = storageManager.getReplicationStatus(fileId)
        
        if (!status.isHealthy) {
            logger.warn("Under-replicated file: $fileId (${status.currentReplicas}/${status.targetReplicas})")
            
            // Trigger re-replication
            storageManager.triggerReplication(fileId)
            
            // Verify after replication
            delay(5000)  // Wait for replication
            val newStatus = storageManager.getReplicationStatus(fileId)
            
            if (newStatus.isHealthy) {
                logger.info("File $fileId successfully re-replicated")
            } else {
                alerting.sendAlert("REPLICATION_FAILURE", fileId)
            }
        }
    }
}
```

---

## Best Practices

### 1. File Lifecycle Management

```kotlin
// ✅ GOOD: Store file with appropriate recipients
val fileId = storageManager.storeFile(
    data = fileData,
    filename = "data.txt",
    owner = "node-alice",
    recipients = listOf("node-alice", "node-bob")  // Only necessary recipients
)

// ❌ BAD: Over-sharing with unnecessary recipients
val fileId = storageManager.storeFile(
    data = fileData,
    filename = "sensitive-data.txt",
    owner = "node-alice",
    recipients = listOf("node-alice", "node-bob", "node-charlie", "node-dave")  // Too many!
)
```

### 2. Error Handling

```kotlin
// ✅ GOOD: Handle specific exceptions with retry logic
suspend fun retrieveFileWithRetry(fileId: String, requesterId: String, maxRetries: Int = 3): ByteArray {
    repeat(maxRetries) { attempt ->
        try {
            return storageManager.retrieveFile(fileId, requesterId)
        } catch (e: RetrievalException) {
            if (attempt == maxRetries - 1) throw e
            logger.warn("Retrieval failed (attempt ${attempt + 1}/$maxRetries), retrying...")
            delay(1000L * (attempt + 1))  // Exponential backoff
        }
    }
    throw RetrievalException(fileId, maxRetries)
}

// ❌ BAD: Swallowing exceptions
try {
    val fileData = storageManager.retrieveFile(fileId, requesterId)
} catch (e: Exception) {
    // Silent failure - bad!
}
```

### 3. Access Control

```kotlin
// ✅ GOOD: Check access before attempting retrieval
if (storageManager.hasAccess(fileId, requesterId)) {
    val fileData = storageManager.retrieveFile(fileId, requesterId)
    // ... process file
} else {
    logger.warn("Access denied for $requesterId to $fileId")
}

// ❌ BAD: Relying on exception handling for access control
try {
    val fileData = storageManager.retrieveFile(fileId, requesterId)
} catch (e: UnauthorizedException) {
    // Using exceptions for flow control - inefficient
}
```

### 4. Replication Monitoring

```kotlin
// ✅ GOOD: Proactive replication monitoring
launch {
    while (isActive) {
        val underReplicatedCount = storageManager.triggerReplication()
        if (underReplicatedCount > 0) {
            logger.warn("Re-replicated $underReplicatedCount files")
        }
        delay(60_000)  // Check every minute
    }
}

// ❌ BAD: No replication monitoring
// (files become under-replicated over time due to node churn)
```

### 5. Resource Cleanup

```kotlin
// ✅ GOOD: Always shutdown storage manager
val storageManager = DistributedStorageManager(meshNetwork)
try {
    storageManager.initialize(storageDir)
    // ... use storage manager
} finally {
    storageManager.shutdown()  // Guaranteed cleanup
}

// ❌ BAD: No cleanup
val storageManager = DistributedStorageManager(meshNetwork)
storageManager.initialize(storageDir)
// ... use storage manager
// (storage manager never shut down - resource leak)
```

---

## Integration Patterns

### Pattern 1: Task Execution Integration

```kotlin
class TaskExecutor(
    private val storageManager: DistributedStorageManager,
    private val taskManager: TaskManager
) {
    suspend fun executeTask(task: Task): TaskResult {
        // Generate task keypair
        val keypair = taskManager.generateTaskKeypair(task.id)
        val taskRecipient = "task-${task.id}"
        
        try {
            // Grant task access to input files
            for (fileId in task.inputFiles) {
                storageManager.updateFileAccess(
                    fileId = fileId,
                    addRecipients = listOf(taskRecipient)
                )
            }
            
            // Execute task
            val result = executeTaskInternal(task, keypair)
            
            // Store output files
            val outputFileId = storageManager.storeFile(
                data = result.output,
                filename = "output-${task.id}.txt",
                owner = task.owner,
                recipients = listOf(task.owner)
            )
            
            return TaskResult(
                taskId = task.id,
                outputFileId = outputFileId,
                success = true
            )
            
        } finally {
            // Cleanup: Remove task access
            for (fileId in task.inputFiles) {
                storageManager.updateFileAccess(
                    fileId = fileId,
                    removeRecipients = listOf(taskRecipient)
                )
            }
            
            // Cleanup: Delete keypair
            taskManager.cleanupExpiredKeypairs()
        }
    }
}
```

### Pattern 2: File Sharing Workflow

```kotlin
class FileShareManager(private val storageManager: DistributedStorageManager) {
    
    suspend fun createSharedFolder(
        folderId: String,
        owner: String,
        members: List<String>
    ) {
        // Store folder metadata
        val folderMetadata = """
            {"folderId": "$folderId", "owner": "$owner", "members": ${members.toJson()}}
        """.trimIndent().toByteArray()
        
        val folderFileId = storageManager.storeFile(
            data = folderMetadata,
            filename = "folder-$folderId-metadata.json",
            owner = owner,
            recipients = listOf(owner) + members
        )
    }
    
    suspend fun addFileToFolder(
        folderId: String,
        fileData: ByteArray,
        filename: String,
        owner: String
    ): String {
        // Get folder members
        val folderMetadata = getFolderMetadata(folderId)
        val members = folderMetadata.members
        
        // Store file with folder members as recipients
        return storageManager.storeFile(
            data = fileData,
            filename = filename,
            owner = owner,
            recipients = listOf(owner) + members
        )
    }
    
    suspend fun addMemberToFolder(folderId: String, newMember: String) {
        // Get all files in folder
        val folderMetadata = getFolderMetadata(folderId)
        val fileIds = folderMetadata.fileIds
        
        // Grant new member access to all files
        for (fileId in fileIds) {
            storageManager.updateFileAccess(
                fileId = fileId,
                addRecipients = listOf(newMember)
            )
        }
        
        // Update folder metadata
        updateFolderMetadata(folderId, members = folderMetadata.members + newMember)
    }
}
```

### Pattern 3: Data Pipeline Integration

```kotlin
class DataPipeline(private val storageManager: DistributedStorageManager) {
    
    suspend fun runPipeline(
        inputFileId: String,
        stages: List<PipelineStage>
    ): String {
        var currentFileId = inputFileId
        
        for (stage in stages) {
            // Generate stage task
            val stageTaskId = "stage-${stage.name}-${UUID.randomUUID()}"
            
            // Grant stage access to input
            storageManager.updateFileAccess(
                fileId = currentFileId,
                addRecipients = listOf("task-$stageTaskId")
            )
            
            // Execute stage
            val stageOutput = stage.execute(currentFileId, stageTaskId)
            
            // Store stage output
            currentFileId = storageManager.storeFile(
                data = stageOutput,
                filename = "stage-${stage.name}-output.dat",
                owner = stage.owner,
                recipients = listOf(stage.owner)
            )
            
            // Cleanup: Remove stage access to input
            storageManager.updateFileAccess(
                fileId = currentFileId,
                removeRecipients = listOf("task-$stageTaskId")
            )
        }
        
        return currentFileId  // Final output file ID
    }
}
```

---

## Performance Considerations

### Latency Characteristics

| Operation | Typical Latency | Notes |
|-----------|-----------------|-------|
| `storeFile()` | 200-500ms | Depends on file size and network |
| `retrieveFile()` (cached) | 5-10ms | Local cache hit |
| `retrieveFile()` (uncached) | 100-300ms | Network fetch + decryption |
| `updateFileAccess()` | 50-100ms | Per-recipient crypto operation |
| `deleteFile()` | 100-200ms | Eventually consistent |
| `getFileMetadata()` | 10-20ms | Metadata cache |

### Optimization Tips

1. **Batch Access Updates**: Update access for multiple files at once
```kotlin
// Instead of:
for (fileId in fileIds) {
    storageManager.updateFileAccess(fileId, addRecipients = listOf(taskRecipient))
}

// Do:
val updates = fileIds.map { fileId ->
    async { storageManager.updateFileAccess(fileId, addRecipients = listOf(taskRecipient)) }
}
updates.awaitAll()
```

2. **Prefetch Files**: Retrieve files in parallel before execution
```kotlin
val files = inputFileIds.map { fileId ->
    async { storageManager.retrieveFile(fileId, requesterId) }
}.awaitAll()
```

3. **Monitor Replication**: Proactive replication prevents retrieval failures
```kotlin
launch {
    while (isActive) {
        storageManager.triggerReplication()
        delay(60_000)
    }
}
```

---

## See Also

- [TaskManager API Documentation](TaskManager_API.md)
- [PGP Encryption Service Documentation](PGPEncryptionService_API.md)
- [Task Keypair Enhancement Guide](../guides/TaskKeypairEnhancement.md)
- [Security Architecture](../security/EncryptionArchitecture.md)

---

**Document Version**: 1.0.0  
**Last Updated**: November 14, 2025  
**Maintainer**: Meshrabiya Core Team
