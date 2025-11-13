# Task KeyPair Enhancement Implementation Plan - Part 2

**Date**: November 13, 2025  
**Continuation of**: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md  
**Sections**: 5 (continued), 6-8

---

## Section 5 (Continued): Dynamic File Sharing Integration

### 5.4 MeshEcosystem Listener for Task Notifications

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/ecosystem/MeshEcosystemListener.kt

package org.torproject.android.meshrabiya.ecosystem

import android.util.Log
import org.torproject.android.meshrabiya.compute.TaskManager
import org.torproject.android.meshrabiya.storage.FileAccessUpdateMessage
import org.torproject.android.meshrabiya.storage.TaskFileAccessUpdateMessage

/**
 * Listens for distributed storage events and routes task-related notifications
 * to TaskManager for dynamic file injection
 */
class MeshEcosystemListener {
    
    companion object {
        private const val TAG = "MeshEcosystemListener"
    }
    
    /**
     * Handle file access update notification from distributed storage
     * Routes to appropriate handler based on recipient type
     */
    suspend fun onFileAccessUpdated(message: Any) {
        when (message) {
            is FileAccessUpdateMessage -> {
                // User-to-user file sharing
                handleUserFileAccess(message)
            }
            is TaskFileAccessUpdateMessage -> {
                // File shared with running task
                handleTaskFileAccess(message)
            }
            else -> {
                Log.w(TAG, "Unknown file access update message type: ${message::class.simpleName}")
            }
        }
    }
    
    /**
     * Handle file shared with user (existing functionality)
     */
    private suspend fun handleUserFileAccess(message: FileAccessUpdateMessage) {
        // Notify UI, update file list, etc.
        Log.d(TAG, "File ${message.fileName} shared with user")
        // ... existing user notification logic ...
    }
    
    /**
     * Handle file shared with task (NEW functionality)
     * Sends "Data Access Change" event to TaskManager
     */
    private suspend fun handleTaskFileAccess(message: TaskFileAccessUpdateMessage) {
        Log.d(TAG, "File ${message.fileName} shared with task ${message.taskId}")
        
        // Verify task is still running
        if (!TaskManager.isTaskRunning(message.taskId)) {
            Log.d(TAG, "Task ${message.taskId} not running, ignoring file notification")
            return
        }
        
        // Route to TaskManager for file injection
        try {
            TaskManager.onTaskFileAdded(
                taskId = message.taskId,
                fileId = message.fileId,
                fileName = message.fileName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add file to task ${message.taskId}", e)
        }
    }
    
    /**
     * Check if task is registered as recipient for notification routing
     */
    fun isTaskRecipient(recipientId: String): Boolean {
        // Task IDs follow format: task_<uuid>
        return recipientId.startsWith("task_")
    }
}
```

### 5.5 Task File Access Update Message

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/storage/StorageMessages.kt

package org.torproject.android.meshrabiya.storage

import kotlinx.serialization.Serializable

/**
 * Notification sent when file access is granted to a task
 * Sent to compute node running the task
 */
@Serializable
data class TaskFileAccessUpdateMessage(
    val taskId: String,       // Task receiving file access
    val fileId: String,        // File being shared
    val fileName: String,      // Original filename
    val accessType: String,    // "added" or "revoked"
    val timestamp: Long = System.currentTimeMillis()
) : MessageData

/**
 * Generic file access update for users
 */
@Serializable
data class FileAccessUpdateMessage(
    val fileId: String,
    val fileName: String,
    val owner: String,
    val accessType: String,
    val timestamp: Long = System.currentTimeMillis()
) : MessageData
```

### 5.6 Drop Folder Sharing Configuration

```kotlin
// File: app/src/main/java/org/torproject/android/orbot/dropfolder/SharingConfiguration.kt

package org.torproject.android.orbot.dropfolder

import kotlinx.serialization.Serializable

/**
 * Configuration for subfolder sharing with users and tasks
 */
@Serializable
data class SubfolderSharingConfig(
    val subfolderPath: String,
    val sharedWithUsers: List<String> = emptyList(),  // User IDs
    val sharedWithTasks: List<String> = emptyList(),  // Task IDs
    val permissions: SharingPermissions = SharingPermissions.READ_ONLY,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class SharingPermissions {
    READ_ONLY,      // Recipients can read files
    READ_WRITE      // Recipients can read and add files (future enhancement)
}

/**
 * Manager for drop folder sharing configurations
 */
class SharingConfigurationManager(private val context: Context) {
    
    private val configFile = File(context.filesDir, "dropfolder_sharing.json")
    private val configs = mutableMapOf<String, SubfolderSharingConfig>()
    
    init {
        loadConfigs()
    }
    
    /**
     * Share subfolder with users and/or tasks
     */
    fun shareSubfolder(
        subfolderPath: String,
        userIds: List<String> = emptyList(),
        taskIds: List<String> = emptyList(),
        permissions: SharingPermissions = SharingPermissions.READ_ONLY
    ) {
        val config = SubfolderSharingConfig(
            subfolderPath = subfolderPath,
            sharedWithUsers = userIds,
            sharedWithTasks = taskIds,
            permissions = permissions
        )
        
        configs[subfolderPath] = config
        saveConfigs()
    }
    
    /**
     * Add task to existing subfolder sharing
     */
    fun addTaskToSharing(subfolderPath: String, taskId: String) {
        val config = configs[subfolderPath] ?: return
        val updatedConfig = config.copy(
            sharedWithTasks = config.sharedWithTasks + taskId,
            updatedAt = System.currentTimeMillis()
        )
        configs[subfolderPath] = updatedConfig
        saveConfigs()
    }
    
    /**
     * Remove task from subfolder sharing (e.g., when task completes)
     */
    fun removeTaskFromSharing(subfolderPath: String, taskId: String) {
        val config = configs[subfolderPath] ?: return
        val updatedConfig = config.copy(
            sharedWithTasks = config.sharedWithTasks - taskId,
            updatedAt = System.currentTimeMillis()
        )
        configs[subfolderPath] = updatedConfig
        saveConfigs()
    }
    
    /**
     * Get sharing configuration for subfolder
     */
    fun getSubfolderSharingConfig(subfolderPath: String): SubfolderSharingConfig? {
        return configs[subfolderPath]
    }
    
    /**
     * Auto-cleanup: Remove tasks that are no longer running
     */
    fun cleanupCompletedTasks(completedTaskIds: List<String>) {
        var modified = false
        configs.forEach { (path, config) ->
            val stillActive = config.sharedWithTasks.filter { it !in completedTaskIds }
            if (stillActive.size != config.sharedWithTasks.size) {
                configs[path] = config.copy(sharedWithTasks = stillActive)
                modified = true
            }
        }
        if (modified) {
            saveConfigs()
        }
    }
    
    private fun loadConfigs() {
        if (configFile.exists()) {
            try {
                val json = configFile.readText()
                val loadedConfigs = Json.decodeFromString<Map<String, SubfolderSharingConfig>>(json)
                configs.putAll(loadedConfigs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sharing configs", e)
            }
        }
    }
    
    private fun saveConfigs() {
        try {
            val json = Json.encodeToString(configs)
            configFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sharing configs", e)
        }
    }
    
    companion object {
        private const val TAG = "SharingConfigManager"
    }
}
```

### 5.7 Example: User Workflow for Sharing Files with Tasks

```kotlin
// File: app/src/main/java/org/torproject/android/orbot/ui/ComputeTaskActivity.kt

/**
 * Example UI flow for sharing drop folder with task
 */
class ComputeTaskActivity : AppCompatActivity() {
    
    private lateinit var sharingConfigManager: SharingConfigurationManager
    private lateinit var computeService: IntelligentDistributedComputeService
    
    /**
     * User selects "Share folder with task" option
     */
    private fun onShareFolderWithTask(taskId: String) {
        // Show folder picker
        val folderPicker = FolderPickerDialog()
        folderPicker.setOnFolderSelectedListener { subfolderPath ->
            // Configure sharing
            sharingConfigManager.addTaskToSharing(subfolderPath, taskId)
            
            // Show confirmation
            Toast.makeText(
                this,
                "Folder shared with task. Any files added will be sent to the running task.",
                Toast.LENGTH_LONG
            ).show()
        }
        folderPicker.show(supportFragmentManager, "folder_picker")
    }
    
    /**
     * Automatic cleanup when task completes
     */
    private fun onTaskCompleted(taskId: String) {
        // Remove task from all sharing configurations
        sharingConfigManager.cleanupCompletedTasks(listOf(taskId))
    }
}
```

---

## Section 6: Client-Side Integration

### 6.1 IntelligentDistributedComputeService Enhancements

**Location**: `Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/IntelligentDistributedComputeService.kt`

#### 6.1.1 Task Registry with Keypair Tracking

```kotlin
object IntelligentDistributedComputeService {
    
    // Existing fields
    private val taskRegistry = mutableMapOf<String, TaskRequest>()
    private val taskStatusRegistry = mutableMapOf<String, TaskStatus>()
    private val taskDiscoveryResponses = mutableMapOf<String, MutableList<TaskCapabilityResponse>>()
    
    /**
     * Submit task for distributed execution
     * Files are initially encrypted for owner only
     * Will be re-encrypted for task pub key after TASK_SCHEDULED response
     */
    suspend fun submitTask(request: TaskRequest): String {
        val taskId = generateTaskId()
        
        // Store task in registry
        taskRegistry[taskId] = request
        taskStatusRegistry[taskId] = TaskStatus(
            taskId = taskId,
            status = TaskExecutionStatus.DISCOVERING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        
        // Upload input files to distributed storage (encrypted for owner only)
        val inputFileIds = uploadInputFiles(request.inputFiles, owner = getCurrentUserId())
        
        // Update request with file IDs
        val updatedRequest = request.copy(
            taskId = taskId,
            inputFileIds = inputFileIds
        )
        taskRegistry[taskId] = updatedRequest
        
        // Start task discovery
        startTaskDiscovery(updatedRequest)
        
        return taskId
    }
    
    /**
     * Upload input files to distributed storage
     * Initially encrypted for owner only (will add task pub key later)
     */
    private suspend fun uploadInputFiles(files: List<File>, owner: String): List<String> {
        return files.map { file ->
            DistributedStorageManager.storeFile(
                file = file,
                accessScope = AccessScope.PRIVATE,  // Owner only initially
                owner = owner,
                recipients = emptyList()  // No recipients yet
            )
        }
    }
}
```

#### 6.1.2 TASK_SCHEDULED Handler with File Re-Encryption

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/IntelligentDistributedComputeService.kt

/**
 * Handle TASK_SCHEDULED message from compute node
 * Critical: Re-encrypts input files to add task pub key access
 */
suspend fun handleTaskScheduledMessage(message: TaskScheduledMessage) {
    val task = taskRegistry[message.taskId]
    if (task == null) {
        Log.w(TAG, "Received TASK_SCHEDULED for unknown task: ${message.taskId}")
        return
    }
    
    // Update task status with keypair info
    val currentStatus = taskStatusRegistry[message.taskId] ?: return
    taskStatusRegistry[message.taskId] = currentStatus.copy(
        status = TaskExecutionStatus.SCHEDULED,
        taskPubKey = message.taskPubKey,
        scheduledStartTime = message.scheduledStartTime,
        estimatedCompletionTime = message.estimatedCompletionTime,
        updatedAt = System.currentTimeMillis()
    )
    
    Log.d(TAG, "Task ${message.taskId} scheduled, received pub key")
    
    // *** RE-ENCRYPT INPUT FILES TO ADD TASK PUB KEY ACCESS ***
    reEncryptInputFilesForTask(message.taskId, message.taskPubKey)
}

/**
 * Re-encrypt all input files to add task pub key as recipient
 * This enables the compute node to decrypt files using task private key
 */
private suspend fun reEncryptInputFilesForTask(taskId: String, taskPubKey: String) {
    val task = taskRegistry[taskId] ?: return
    
    try {
        // Create task recipient
        val taskRecipient = Recipient(
            recipientId = taskId,
            recipientPubKey = taskPubKey,
            recipientType = RecipientType.TASK
        )
        
        // Re-encrypt each input file
        task.inputFileIds.forEach { fileId ->
            val success = DistributedStorageManager.updateFileAccess(
                fileId = fileId,
                addRecipients = listOf(taskRecipient)
            )
            
            if (!success) {
                Log.e(TAG, "Failed to re-encrypt file $fileId for task $taskId")
                // Continue with other files rather than failing entire task
            }
        }
        
        // Notify compute node that files are ready
        val taskStatus = taskStatusRegistry[taskId] ?: return
        val executorAddress = taskStatus.executorNodeAddress ?: return
        
        sendMessage(
            targetAddress = executorAddress,
            messageType = MessageType.TASK_FILES_READY,
            data = TaskFilesReadyMessage(
                taskId = taskId,
                fileIds = task.inputFileIds
            )
        )
        
        Log.d(TAG, "Re-encrypted ${task.inputFileIds.size} files for task $taskId")
        
    } catch (e: Exception) {
        Log.e(TAG, "Failed to re-encrypt files for task $taskId", e)
        // Fail the task
        failTask(taskId, "File re-encryption failed: ${e.message}")
    }
}
```

#### 6.1.3 Dynamic File Sharing with Running Tasks

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/IntelligentDistributedComputeService.kt

/**
 * Share additional file with running task
 * Called when user adds file to drop folder shared with task
 */
suspend fun shareFileWithTask(taskId: String, file: File) {
    val taskStatus = taskStatusRegistry[taskId]
    if (taskStatus == null) {
        throw IllegalArgumentException("Task not found: $taskId")
    }
    
    if (taskStatus.status != TaskExecutionStatus.RUNNING &&
        taskStatus.status != TaskExecutionStatus.SCHEDULED) {
        throw IllegalStateException("Task $taskId is not running or scheduled")
    }
    
    if (taskStatus.taskPubKey == null) {
        throw IllegalStateException("Task pub key not available for task $taskId")
    }
    
    // Create task recipient
    val taskRecipient = Recipient(
        recipientId = taskId,
        recipientPubKey = taskStatus.taskPubKey,
        recipientType = RecipientType.TASK
    )
    
    // Upload file encrypted for owner + task
    val fileId = DistributedStorageManager.storeFile(
        file = file,
        accessScope = AccessScope.SHARED,
        owner = getCurrentUserId(),
        recipients = listOf(taskRecipient)
    )
    
    Log.d(TAG, "Shared file ${file.name} with task $taskId (fileId: $fileId)")
    
    // storeFile() automatically notifies task via FILE_ACCESS_UPDATED message
}

/**
 * Get task status (used by drop folder monitor and other components)
 */
fun getTaskStatus(taskId: String): TaskStatus? {
    return taskStatusRegistry[taskId]
}

/**
 * Check if task is in running or scheduled state
 */
fun isTaskActive(taskId: String): Boolean {
    val status = taskStatusRegistry[taskId]?.status ?: return false
    return status == TaskExecutionStatus.RUNNING || 
           status == TaskExecutionStatus.SCHEDULED
}
```

#### 6.1.4 Task Completion Handler

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/IntelligentDistributedComputeService.kt

/**
 * Handle task completion (success)
 */
suspend fun completeTask(taskId: String, completionMessage: TaskCompletedMessage) {
    val taskStatus = taskStatusRegistry[taskId] ?: return
    
    // Update status
    taskStatusRegistry[taskId] = taskStatus.copy(
        status = TaskExecutionStatus.COMPLETED,
        updatedAt = System.currentTimeMillis()
    )
    
    // Retrieve output files (if any)
    if (completionMessage.outputManifest != null) {
        downloadOutputFiles(taskId, completionMessage.outputManifest)
    }
    
    // Notify UI / callback
    notifyTaskCompletion(taskId, completionMessage)
    
    // Cleanup: Remove task from drop folder sharing
    cleanupTaskSharing(taskId)
    
    Log.d(TAG, "Task $taskId completed successfully")
}

/**
 * Handle task failure
 */
suspend fun failTask(taskId: String, errorMessage: String) {
    val taskStatus = taskStatusRegistry[taskId] ?: return
    
    // Update status
    taskStatusRegistry[taskId] = taskStatus.copy(
        status = TaskExecutionStatus.FAILED,
        updatedAt = System.currentTimeMillis()
    )
    
    // Notify UI / callback
    notifyTaskFailure(taskId, errorMessage)
    
    // Cleanup: Remove task from drop folder sharing
    cleanupTaskSharing(taskId)
    
    Log.e(TAG, "Task $taskId failed: $errorMessage")
}

/**
 * Remove task from all drop folder sharing configurations
 */
private fun cleanupTaskSharing(taskId: String) {
    try {
        SharingConfigurationManager(context).cleanupCompletedTasks(listOf(taskId))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to cleanup task sharing for $taskId", e)
    }
}
```

### 6.2 Task Status Enum Extension

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskExecutionStatus.kt

enum class TaskExecutionStatus {
    DISCOVERING,      // Broadcasting TASK_DISCOVERY
    ASSIGNED,         // Sent TASK_ASSIGNMENT to compute node
    SCHEDULED,        // Received TASK_SCHEDULED with pub key
    RUNNING,          // Task executing on compute node
    COMPLETED,        // Task finished successfully
    FAILED,           // Task failed with error
    CANCELLED,        // Task cancelled by user
    REJECTED          // No capable nodes or compute node rejected
}
```

---

## Section 7: Compute-Side Task Execution

### 7.1 TaskManager executeTaskWithKeypair() Implementation

**Location**: `Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt`

```kotlin
/**
 * Execute task with keypair for file decryption
 * This is the main entry point after TASK_FILES_READY notification
 */
suspend fun executeTaskWithKeypair(
    state: ExecutionState,
    keypair: TaskKeypair
): ExecutionResult {
    val context = state.context
    
    try {
        // 1. Create sandbox filesystem with keypair
        val sandboxFs = SandboxFileSystem(
            taskId = context.taskId,
            workspaceDir = state.workspaceDir,
            taskPrivateKey = keypair.privateKeyPem
        )
        state.sandboxFileSystem = sandboxFs
        
        // 2. Prepare input files (download, decrypt, assemble)
        Log.d(TAG, "Preparing ${context.inputFileIds.size} input files for task ${context.taskId}")
        val preparedFiles = prepareInputFiles(context.inputFileIds, sandboxFs)
        
        // 3. Extract and validate code bundle
        val codeDir = extractCodeBundle(context.codeBundle, state.workspaceDir)
        
        // 4. Create sandbox container with keypair in environment
        val container = StrangersSafeComputeEngine.createSandboxContainer(
            taskId = context.taskId,
            taskType = context.taskType,
            workspaceDir = state.workspaceDir,
            resourceLimits = context.resourceLimits,
            taskKeypair = keypair
        )
        state.containerId = container.id
        
        // 5. Start resource monitoring
        startResourceMonitoring(context.taskId)
        
        // 6. Execute task based on type
        val executor = getExecutorForTaskType(context.taskType)
        val executionResult = executor.execute(
            context = context,
            codeDir = codeDir,
            inputFiles = preparedFiles,
            outputDir = File(state.workspaceDir, "output"),
            container = container
        )
        
        // 7. Encrypt output files for requester + task
        if (executionResult.success && executionResult.outputManifest != null) {
            encryptOutputFiles(
                outputManifest = executionResult.outputManifest,
                requesterNodeId = context.requesterNodeId,
                taskId = context.taskId,
                taskPubKey = keypair.publicKeyPem
            )
        }
        
        return executionResult
        
    } catch (e: Exception) {
        Log.e(TAG, "Task execution failed", e)
        return ExecutionResult(
            taskId = context.taskId,
            success = false,
            outputManifest = null,
            resultMessage = null,
            resourcesUsed = state.currentMetrics,
            executionTimeMs = System.currentTimeMillis() - (state.executionStartedAt ?: 0),
            errorMessage = e.message ?: "Unknown error",
            errorType = determineErrorType(e)
        )
    }
}
```

### 7.2 Input File Preparation with Decryption

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

/**
 * Prepare input files: download, decrypt, assemble chunks
 * Returns map of file ID to prepared file path
 */
private suspend fun prepareInputFiles(
    fileIds: List<String>,
    sandboxFs: SandboxFileSystem
): Map<String, File> {
    val preparedFiles = mutableMapOf<String, File>()
    
    fileIds.forEach { fileId ->
        try {
            // Get file metadata
            val metadata = DistributedStorageManager.getFileMetadata(fileId)
            if (metadata == null) {
                Log.e(TAG, "File metadata not found: $fileId")
                return@forEach
            }
            
            // Prepare file (download, decrypt, assemble)
            val preparedFile = sandboxFs.prepareInputFile(fileId, metadata.fileName)
            preparedFiles[fileId] = preparedFile
            
            Log.d(TAG, "Prepared input file: ${metadata.fileName}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare input file $fileId", e)
            throw RuntimeException("Failed to prepare input file: ${e.message}", e)
        }
    }
    
    return preparedFiles
}
```

### 7.3 Output File Encryption

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

/**
 * Encrypt output files for requester + task
 * Task pub key enables requester to share outputs with other tasks
 */
private suspend fun encryptOutputFiles(
    outputManifest: OutputManifest,
    requesterNodeId: String,
    taskId: String,
    taskPubKey: String
) {
    // Get requester's public key
    val requesterPubKey = getPublicKeyForNode(requesterNodeId)
    
    // Create recipients: requester + task
    val recipients = listOf(
        Recipient(
            recipientId = requesterNodeId,
            recipientPubKey = requesterPubKey,
            recipientType = RecipientType.USER
        ),
        Recipient(
            recipientId = taskId,
            recipientPubKey = taskPubKey,
            recipientType = RecipientType.TASK
        )
    )
    
    // Upload each output file to distributed storage
    outputManifest.files.forEach { outputFile ->
        try {
            val fileId = DistributedStorageManager.storeFile(
                file = outputFile.file,
                accessScope = AccessScope.SHARED,
                owner = requesterNodeId,  // Requester is owner
                recipients = recipients
            )
            
            // Update manifest with file ID
            outputFile.fileId = fileId
            
            Log.d(TAG, "Encrypted output file: ${outputFile.name} -> $fileId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt output file ${outputFile.name}", e)
            // Continue with other files
        }
    }
}
```

### 7.4 Enhanced Execution State

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/ExecutionState.kt

/**
 * Execution state for running task
 * Extended to include sandbox filesystem reference
 */
data class ExecutionState(
    val context: TaskExecutionContext,
    var status: TaskExecutionStatus,
    val workspaceDir: File,
    var containerId: String? = null,
    var executionStartedAt: Long? = null,
    var currentMetrics: ResourceMetrics = ResourceMetrics.zero(),
    var peakMetrics: ResourceMetrics = ResourceMetrics.zero(),
    
    // NEW: Sandbox filesystem for dynamic file injection
    var sandboxFileSystem: SandboxFileSystem? = null
)
```

### 7.5 Task Waiting for File Re-Encryption

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/compute/TaskManager.kt

/**
 * Schedule task for execution
 * Task waits in SCHEDULED state until TASK_FILES_READY notification
 */
fun scheduleTask(context: TaskExecutionContext, keypair: TaskKeypair) {
    // Create execution state
    val workspaceDir = File(workspaceRoot, context.taskId)
    workspaceDir.mkdirs()
    
    val state = ExecutionState(
        context = context,
        status = TaskExecutionStatus.SCHEDULED,
        workspaceDir = workspaceDir
    )
    
    executionStates[context.taskId] = state
    
    Log.d(TAG, "Task ${context.taskId} scheduled, waiting for files to be ready")
}

/**
 * Begin task execution when files are ready
 * Called from handleTaskFilesReadyMessage
 */
suspend fun beginTaskExecution(taskId: String) {
    val executionState = executionStates[taskId]
    if (executionState == null) {
        Log.e(TAG, "Cannot begin execution, task not found: $taskId")
        return
    }
    
    val taskKeypair = taskKeypairs[taskId]
    if (taskKeypair == null) {
        Log.e(TAG, "Cannot begin execution, keypair not found: $taskId")
        failTask(taskId, "Keypair not found")
        return
    }
    
    // Update status
    executionState.status = TaskExecutionStatus.RUNNING
    executionState.executionStartedAt = System.currentTimeMillis()
    
    try {
        // Execute task with keypair
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

---

## Section 8: PGP Encryption Service Enhancements

### 8.1 Multi-Recipient Encryption Implementation

**Location**: `Meshrabiya/src/main/java/org/torproject/android/meshrabiya/crypto/PGPEncryptionService.kt`

```kotlin
package org.torproject.android.meshrabiya.crypto

import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.jcajce.*
import java.io.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.SecretKeySpec

object PGPEncryptionService {
    
    private const val TAG = "PGPEncryptionService"
    
    /**
     * Encrypted package containing data + encrypted session keys for each recipient
     */
    data class EncryptedPackage(
        val encryptedData: ByteArray,                    // File encrypted with session key
        val encryptedSessionKeys: Map<String, ByteArray>, // recipientId -> encrypted session key
        val algorithm: String = "AES-256"
    )
    
    /**
     * Generate per-task RSA keypair
     * 2048-bit for balance between security and performance (100-500ms on mobile)
     */
    fun generateTaskKeypair(taskId: String): TaskKeypair {
        try {
            // Generate RSA 2048-bit keypair
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()
            
            // Convert to PGP format
            val publicKeyPem = exportPublicKeyToPGP(keyPair.public)
            val privateKeyPem = exportPrivateKeyToPGP(keyPair.private)
            
            return TaskKeypair(
                taskId = taskId,
                publicKeyPem = publicKeyPem,
                privateKeyPem = privateKeyPem,
                createdAt = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate task keypair", e)
        }
    }
    
    /**
     * Encrypt file for multiple recipients using session key approach
     * 
     * Process:
     * 1. Generate random AES-256 session key
     * 2. Encrypt file with session key (fast symmetric encryption)
     * 3. Encrypt session key with each recipient's public key
     * 4. Return package with encrypted data + encrypted session keys
     */
    fun encryptForMultipleRecipients(
        data: ByteArray,
        recipients: List<Recipient>
    ): EncryptedPackage {
        if (recipients.isEmpty()) {
            throw IllegalArgumentException("At least one recipient required")
        }
        
        try {
            // 1. Generate random AES-256 session key
            val sessionKey = generateSessionKey()
            
            // 2. Encrypt data with session key
            val encryptedData = encryptWithAES(data, sessionKey)
            
            // 3. Encrypt session key for each recipient
            val encryptedSessionKeys = mutableMapOf<String, ByteArray>()
            
            recipients.forEach { recipient ->
                try {
                    val recipientPubKey = importPublicKeyFromPGP(recipient.recipientPubKey)
                    val encryptedKey = encryptSessionKeyWithRSA(sessionKey, recipientPubKey)
                    encryptedSessionKeys[recipient.recipientId] = encryptedKey
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt session key for ${recipient.recipientId}", e)
                    // Continue with other recipients rather than failing
                }
            }
            
            if (encryptedSessionKeys.isEmpty()) {
                throw RuntimeException("Failed to encrypt session key for any recipient")
            }
            
            return EncryptedPackage(
                encryptedData = encryptedData,
                encryptedSessionKeys = encryptedSessionKeys
            )
            
        } catch (e: Exception) {
            throw RuntimeException("Multi-recipient encryption failed", e)
        }
    }
    
    /**
     * Decrypt data using private key
     * 
     * Process:
     * 1. Decrypt session key with private key
     * 2. Decrypt data with session key
     */
    fun decryptWithPrivateKey(
        encryptedPackage: EncryptedPackage,
        recipientId: String,
        privateKeyPem: String
    ): ByteArray {
        try {
            // 1. Get encrypted session key for this recipient
            val encryptedSessionKey = encryptedPackage.encryptedSessionKeys[recipientId]
                ?: throw SecurityException("No session key found for recipient $recipientId")
            
            // 2. Import private key
            val privateKey = importPrivateKeyFromPGP(privateKeyPem)
            
            // 3. Decrypt session key with private key
            val sessionKey = decryptSessionKeyWithRSA(encryptedSessionKey, privateKey)
            
            // 4. Decrypt data with session key
            return decryptWithAES(encryptedPackage.encryptedData, sessionKey)
            
        } catch (e: Exception) {
            throw RuntimeException("Decryption failed", e)
        }
    }
    
    /**
     * Generate random AES-256 session key
     */
    private fun generateSessionKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256, SecureRandom())
        return keyGenerator.generateKey()
    }
    
    /**
     * Encrypt data with AES-256 session key
     */
    private fun encryptWithAES(data: ByteArray, sessionKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey)
        
        val iv = cipher.iv  // Initialization vector
        val encryptedData = cipher.doFinal(data)
        
        // Prepend IV to encrypted data (needed for decryption)
        return iv + encryptedData
    }
    
    /**
     * Decrypt data with AES-256 session key
     */
    private fun decryptWithAES(encryptedDataWithIV: ByteArray, sessionKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        
        // Extract IV (first 12 bytes for GCM)
        val iv = encryptedDataWithIV.sliceArray(0 until 12)
        val encryptedData = encryptedDataWithIV.sliceArray(12 until encryptedDataWithIV.size)
        
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec)
        
        return cipher.doFinal(encryptedData)
    }
    
    /**
     * Encrypt session key with recipient's RSA public key
     */
    private fun encryptSessionKeyWithRSA(sessionKey: SecretKey, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(sessionKey.encoded)
    }
    
    /**
     * Decrypt session key with RSA private key
     */
    private fun decryptSessionKeyWithRSA(encryptedKey: ByteArray, privateKey: PrivateKey): SecretKey {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val keyBytes = cipher.doFinal(encryptedKey)
        return SecretKeySpec(keyBytes, "AES")
    }
}
```

### 8.2 PGP Key Format Conversion

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/crypto/PGPKeyConverter.kt

package org.torproject.android.meshrabiya.crypto

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.jcajce.*
import java.io.*
import java.security.*
import java.util.Date

/**
 * Convert between Java KeyPair and PGP format
 */
object PGPKeyConverter {
    
    init {
        Security.addProvider(BouncyCastleProvider())
    }
    
    /**
     * Export RSA public key to PGP format (ASCII armored)
     */
    fun exportPublicKeyToPGP(publicKey: PublicKey): String {
        try {
            // Create PGP public key from Java public key
            val keyPairGenerator = PGPKeyPairGenerator(
                PublicKeyAlgorithmTags.RSA_GENERAL,
                JcaPGPKeyConverter().getPGPPublicKey(
                    PublicKeyAlgorithmTags.RSA_GENERAL,
                    publicKey,
                    Date()
                ),
                "task-keypair"
            )
            
            val pgpKeyPair = keyPairGenerator.generateKeyPair()
            val pgpPubKey = pgpKeyPair.publicKey
            
            // ASCII armor encode
            val outputStream = ByteArrayOutputStream()
            val armoredOutputStream = ArmoredOutputStream(outputStream)
            pgpPubKey.encode(armoredOutputStream)
            armoredOutputStream.close()
            
            return outputStream.toString("UTF-8")
            
        } catch (e: Exception) {
            throw RuntimeException("Failed to export public key to PGP", e)
        }
    }
    
    /**
     * Export RSA private key to PGP format (ASCII armored)
     */
    fun exportPrivateKeyToPGP(privateKey: PrivateKey): String {
        try {
            // Create PGP key pair
            val keyConverter = JcaPGPKeyConverter().setProvider("BC")
            val pgpPrivKey = keyConverter.getPGPPrivateKey(
                PublicKeyAlgorithmTags.RSA_GENERAL,
                privateKey,
                Date()
            )
            
            // Encode private key
            val outputStream = ByteArrayOutputStream()
            val armoredOutputStream = ArmoredOutputStream(outputStream)
            
            val secretKey = PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION,
                pgpPrivKey.publicKeyPacket,
                pgpPrivKey,
                null,  // No passphrase protection (in-memory only)
                null,
                null,
                PGPEncryptedData.CAST5,
                null
            )
            
            secretKey.encode(armoredOutputStream)
            armoredOutputStream.close()
            
            return outputStream.toString("UTF-8")
            
        } catch (e: Exception) {
            throw RuntimeException("Failed to export private key to PGP", e)
        }
    }
    
    /**
     * Import PGP public key to Java PublicKey
     */
    fun importPublicKeyFromPGP(pgpPublicKeyPem: String): PublicKey {
        try {
            val inputStream = ByteArrayInputStream(pgpPublicKeyPem.toByteArray())
            val pgpPubKey = PGPPublicKey(
                PGPUtil.getDecoderStream(inputStream)
            )
            
            return JcaPGPKeyConverter()
                .setProvider("BC")
                .getPublicKey(pgpPubKey)
            
        } catch (e: Exception) {
            throw RuntimeException("Failed to import public key from PGP", e)
        }
    }
    
    /**
     * Import PGP private key to Java PrivateKey
     */
    fun importPrivateKeyFromPGP(pgpPrivateKeyPem: String): PrivateKey {
        try {
            val inputStream = ByteArrayInputStream(pgpPrivateKeyPem.toByteArray())
            val pgpSecKey = PGPSecretKey(
                PGPUtil.getDecoderStream(inputStream),
                JcaKeyFingerprintCalculator()
            )
            
            val pgpPrivKey = pgpSecKey.extractPrivateKey(
                null  // No passphrase
            )
            
            return JcaPGPKeyConverter()
                .setProvider("BC")
                .getPrivateKey(pgpPrivKey)
            
        } catch (e: Exception) {
            throw RuntimeException("Failed to import private key from PGP", e)
        }
    }
}

// Extension functions for convenience
fun PGPEncryptionService.exportPublicKeyToPGP(publicKey: PublicKey): String =
    PGPKeyConverter.exportPublicKeyToPGP(publicKey)

fun PGPEncryptionService.exportPrivateKeyToPGP(privateKey: PrivateKey): String =
    PGPKeyConverter.exportPrivateKeyToPGP(privateKey)

fun PGPEncryptionService.importPublicKeyFromPGP(pgpPublicKeyPem: String): PublicKey =
    PGPKeyConverter.importPublicKeyFromPGP(pgpPublicKeyPem)

fun PGPEncryptionService.importPrivateKeyFromPGP(pgpPrivateKeyPem: String): PrivateKey =
    PGPKeyConverter.importPrivateKeyFromPGP(pgpPrivateKeyPem)
```

### 8.3 Re-Encryption Optimization

```kotlin
// File: Meshrabiya/src/main/java/org/torproject/android/meshrabiya/crypto/PGPEncryptionService.kt

/**
 * Add recipient to existing encrypted package without re-encrypting entire file
 * Only encrypts session key for new recipient (efficient)
 */
fun addRecipientToPackage(
    encryptedPackage: EncryptedPackage,
    ownerPrivateKey: String,
    ownerId: String,
    newRecipient: Recipient
): EncryptedPackage {
    try {
        // 1. Decrypt session key with owner's private key
        val ownerEncryptedKey = encryptedPackage.encryptedSessionKeys[ownerId]
            ?: throw SecurityException("Owner's encrypted session key not found")
        
        val ownerPrivKey = importPrivateKeyFromPGP(ownerPrivateKey)
        val sessionKey = decryptSessionKeyWithRSA(ownerEncryptedKey, ownerPrivKey)
        
        // 2. Encrypt session key for new recipient
        val recipientPubKey = importPublicKeyFromPGP(newRecipient.recipientPubKey)
        val newEncryptedKey = encryptSessionKeyWithRSA(sessionKey, recipientPubKey)
        
        // 3. Add to encrypted session keys map
        val updatedKeys = encryptedPackage.encryptedSessionKeys.toMutableMap()
        updatedKeys[newRecipient.recipientId] = newEncryptedKey
        
        return encryptedPackage.copy(
            encryptedSessionKeys = updatedKeys
        )
        
    } catch (e: Exception) {
        throw RuntimeException("Failed to add recipient to package", e)
    }
}

/**
 * Remove recipient from encrypted package
 * Simply removes their encrypted session key (they can no longer decrypt)
 */
fun removeRecipientFromPackage(
    encryptedPackage: EncryptedPackage,
    recipientId: String
): EncryptedPackage {
    val updatedKeys = encryptedPackage.encryptedSessionKeys.toMutableMap()
    updatedKeys.remove(recipientId)
    
    return encryptedPackage.copy(
        encryptedSessionKeys = updatedKeys
    )
}
```

---

This completes Part 2 (Sections 5 continued, 6-8), covering:
- Complete dynamic file sharing integration with MeshEcosystem
- Client-side task management with file re-encryption
- Compute-side task execution with keypair integration
- Full PGP encryption service with multi-recipient support

**Part 3** will cover:
- Section 9: Failover & Error Handling
- Section 10: Security Testing
- Section 11: Performance Benchmarks
- Section 12: Edge Cases
- Section 13: Implementation Checklist
- Section 14: Integration with Existing Plan
- Section 15: Migration Guide
