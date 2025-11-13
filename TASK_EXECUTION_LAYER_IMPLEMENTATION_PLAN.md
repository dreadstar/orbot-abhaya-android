# Task Execution Layer Implementation Plan

**Date**: November 12, 2025  
**Status**: COMPREHENSIVE IMPLEMENTATION PLAN  
**Scope**: Single comprehensive fully functional complete update  
**Target**: Implement missing task execution layer using scheduler design patterns as reference

---

## Executive Summary

This plan implements a complete task execution layer for the Meshrabiya distributed compute system. The current implementation has client-side task request/response but **no execution on compute nodes**. This plan addresses all gaps identified in the scheduler analysis and incorporates all architectural decisions from TASK_EXECUTION_LAYER_QUESTIONS.md.

### Key Components

1. **TaskManager Extension**: Add execution orchestration, resource monitoring, load tracking
2. **Executor Implementations**: PythonExecutor, JVMExecutor, JSExecutor, MLNativeExecutor, WorkflowExecutor
3. **Sandboxing Integration**: Container lifecycle, metrics polling, runtime-specific configs
4. **Result Storage Coordination**: Multi-file manifests, AccessScope permissions, encryption integration
5. **Completion Notification**: Direct node-to-node TASK_COMPLETED messages with retry
6. **Runtime Management**: Automatic installation/uninstallation, registry persistence
7. **Storage API Refactoring**: Add AccessScope/Owner/recipients parameters (CRITICAL GAP IDENTIFIED)
8. **Data Structure Enhancements**: Task types, job types, execution context, resource metrics

### Implementation Approach

- **Single Phase**: Complete implementation (not incremental)
- **Built-in Services**: Define for each task_type/job_type combination
- **User Services**: Integration with ServicePackageManager for future extensibility
- **Testing Strategy**: Unit tests per executor, integration tests for full execution flow

---

## CRITICAL GAP: Storage API Missing Permission Parameters

### Current State Analysis

**DistributedStorageManager.storeFile()** (line 317):
```kotlin
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD
): FileReference?
```

**MISSING PARAMETERS**:
- ❌ `accessScope: AccessScope` - Defines permission model (TASK_ISOLATED, SERVICE_SHARED, MESH_GLOBAL)
- ❌ `owner: String` - Task requester node public key (for permission verification)
- ❌ `recipients: List<String>` - Authorized nodes that can access the file

**Current AccessScope** exists in `SandboxStorageProxy.kt` but is **not connected** to storage operations:
```kotlin
enum class AccessScope {
    TASK_ISOLATED,  // Can only access files created by this task
    SERVICE_SHARED, // Can access files from other tasks of same service
    MESH_GLOBAL     // Can access any files (dangerous, usually not allowed)
}
```

**Impact**: Task results cannot enforce proper permissions without these parameters. Per STORAGE_ENCRYPTION+PLAN.md, encryption keys must be distributed to authorized recipients.

### Required Refactoring

This plan includes comprehensive storage API refactoring to add permission parameters and integrate with the hybrid encryption design documented in STORAGE_ENCRYPTION+PLAN.md.

---

## Section 1: Data Structure Refactoring

### 1.1 Create MeshComputeDataDefinitions.kt

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/model/MeshComputeDataDefinitions.kt`

**Purpose**: Centralized compute-related data structures

**Contents**:

```kotlin
package com.ustadmobile.meshrabiya.service.compute.model

import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.AccessScope

/**
 * TASK EXECUTION CONTEXT
 * 
 * Bundles all information needed to execute a task on a compute node
 */
@Serializable
data class TaskExecutionContext(
    val taskId: String,
    val taskType: TaskType,
    val jobType: JobType,
    val codeBundle: ByteArray,           // Language-agnostic archive
    val inputManifest: List<FileReference>, // References to input files in DistributedStorage
    val resourceLimits: ResourceLimits,
    val deadlineMs: Long,
    val requesterNodeId: String,         // Task owner (for result permissions)
    val callbackAddress: String,         // For completion notification
    val accessScope: AccessScope = AccessScope.TASK_ISOLATED
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskExecutionContext) return false
        return taskId == other.taskId
    }

    override fun hashCode(): Int = taskId.hashCode()
}

/**
 * FILE REFERENCE
 * 
 * Reference to a file in DistributedStorage (not the actual file data)
 */
@Serializable
data class FileReference(
    val fileId: String,        // SHA-256 hash of file
    val fileName: String,      // Original filename
    val sizeBytes: Long,
    val mimeType: String? = null
)

/**
 * RESOURCE LIMITS
 * 
 * Per-task resource constraints for sandboxing
 */
@Serializable
data class ResourceLimits(
    val maxMemoryBytes: Long,
    val maxCpuTimeMs: Long,
    val maxDiskBytes: Long,
    val maxExecutionTimeMs: Long,
    val allowNetworkAccess: Boolean = false // Always false for untrusted code
)

/**
 * RESOURCE METRICS
 * 
 * Snapshot of resource usage at a point in time
 */
@Serializable
data class ResourceMetrics(
    val timestamp: Long = System.currentTimeMillis(),
    val ramActualBytes: Long,
    val ramAverageBytes: Long,
    val ramPeakBytes: Long,
    val cpuTimeUsedMs: Long,
    val cpuPercentage: Float,
    val diskIoOperations: Long,
    val diskStorageUsedBytes: Long
) {
    companion object {
        fun zero() = ResourceMetrics(
            ramActualBytes = 0,
            ramAverageBytes = 0,
            ramPeakBytes = 0,
            cpuTimeUsedMs = 0,
            cpuPercentage = 0f,
            diskIoOperations = 0,
            diskStorageUsedBytes = 0
        )
    }
}

/**
 * EXECUTION RESULT
 * 
 * Complete result of task execution including outputs and metrics
 */
@Serializable
data class ExecutionResult(
    val taskId: String,
    val success: Boolean,
    val outputManifest: List<FileReference>, // Zero or more output files
    val resultMessage: String? = null,       // Optional task-defined message
    val resourcesUsed: ResourceMetrics,
    val executionTimeMs: Long,
    val errorMessage: String? = null,
    val errorType: ExecutionErrorType? = null
)

/**
 * EXECUTION ERROR TYPES
 */
enum class ExecutionErrorType {
    TIMEOUT,                 // Exceeded maxExecutionTimeMs
    OUT_OF_MEMORY,          // Exceeded maxMemoryBytes
    DISK_QUOTA_EXCEEDED,    // Exceeded maxDiskBytes
    SANDBOX_VIOLATION,      // Attempted prohibited syscall
    RUNTIME_ERROR,          // Code threw exception
    INPUT_NOT_FOUND,        // Input file not available
    INVALID_CODE_BUNDLE,    // Code bundle malformed or unsupported
    UNKNOWN
}

/**
 * OUTPUT MANIFEST
 * 
 * Describes files created by a task (sent in completion notification)
 */
@Serializable
data class OutputManifest(
    val taskId: String,
    val files: List<FileReference>,
    val totalSizeBytes: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)
```

### 1.2 Refactor Task Type Enums

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/model/TaskType.kt`

**Purpose**: Define execution engines (separate from job types)

**Contents**:

```kotlin
package com.ustadmobile.meshrabiya.service.compute.model

import kotlinx.serialization.Serializable

/**
 * TASK TYPES (Execution Engines)
 * 
 * Defines which runtime is required to execute the task.
 * Each type requires different executor implementation and sandbox configuration.
 */
@Serializable
enum class TaskType {
    /**
     * Python scripts (requires Chaquopy runtime)
     * - Executor: PythonExecutor
     * - Sandbox: Python-specific syscall whitelist
     * - Bundle format: .py files or Python wheel
     */
    PYTHON,

    /**
     * Compiled Java bytecode (requires JVM)
     * - Executor: JVMExecutor
     * - Sandbox: JVM SecurityManager
     * - Bundle format: .class files or JAR
     */
    JAVA,

    /**
     * JVM languages (Kotlin, Scala, Groovy)
     * - Executor: JVMExecutor (same as JAVA)
     * - Sandbox: JVM SecurityManager
     * - Bundle format: .class files or JAR
     */
    JVM,

    /**
     * JavaScript code (requires Node.js or J2V8)
     * - Executor: JSExecutor
     * - Sandbox: JS-specific syscall whitelist
     * - Bundle format: .js files or npm package
     */
    JAVASCRIPT,

    /**
     * Native ML models (TFLite, ML Kit)
     * - Executor: MLNativeExecutor
     * - Sandbox: Native code restrictions
     * - Bundle format: .tflite, .tflite.json, ML Kit model files
     */
    ML_NATIVE,

    /**
     * Multi-step workflow orchestration
     * - Executor: WorkflowExecutor
     * - Sandbox: Workflow-specific (orchestrates other tasks)
     * - Bundle format: JSON/YAML workflow definition
     */
    WORKFLOW;

    /**
     * Get required runtime for this task type
     */
    fun getRequiredRuntime(): RuntimeType {
        return when (this) {
            PYTHON -> RuntimeType.PYTHON
            JAVA, JVM -> RuntimeType.JVM
            JAVASCRIPT -> RuntimeType.NODEJS
            ML_NATIVE -> RuntimeType.NATIVE
            WORKFLOW -> RuntimeType.JVM // Workflow orchestrator is Kotlin
        }
    }
}

/**
 * RUNTIME TYPES
 * 
 * Available runtimes on compute nodes
 */
@Serializable
enum class RuntimeType {
    JVM,        // Always available (app is JVM-based)
    NATIVE,     // NDK-compiled native code
    PYTHON,     // Chaquopy (user-installed)
    NODEJS,     // J2V8 (user-installed)
    RUST,       // Cross-compiled to native (future)
    GO,         // Cross-compiled to native (future)
    WASM        // WebAssembly runtime (future)
}
```

### 1.3 Refactor Job Type Enums

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/model/JobType.kt`

**Purpose**: Define job categories (independent of execution engine)

**Contents**:

```kotlin
package com.ustadmobile.meshrabiya.service.compute.model

import kotlinx.serialization.Serializable

/**
 * JOB TYPES
 * 
 * Defines the category of work being performed.
 * Independent of TaskType - e.g., IMAGE_PROCESSING can be Python, Java, or ML_NATIVE.
 * 
 * Used for:
 * - Service discovery (clients search by job type)
 * - Resource estimation (different jobs have different profiles)
 * - Priority scheduling (some jobs more time-sensitive)
 */
@Serializable
enum class JobType(
    val estimatedMemoryMb: Int,
    val estimatedCpuMs: Int,
    val typicalPriority: Priority
) {
    /**
     * Image processing (filters, transformations, OCR)
     * - Typical inputs: JPEG, PNG images
     * - Typical outputs: Processed images, extracted text
     */
    IMAGE_PROCESSING(
        estimatedMemoryMb = 256,
        estimatedCpuMs = 5000,
        typicalPriority = Priority.NORMAL
    ),

    /**
     * Video processing (encoding, transcoding, analysis)
     * - Typical inputs: MP4, AVI, MOV videos
     * - Typical outputs: Transcoded videos, extracted frames, metadata
     */
    VIDEO_PROCESSING(
        estimatedMemoryMb = 512,
        estimatedCpuMs = 30000,
        typicalPriority = Priority.LOW
    ),

    /**
     * Data analysis (statistics, aggregations, transformations)
     * - Typical inputs: CSV, JSON, Parquet datasets
     * - Typical outputs: Analysis results, visualizations, summaries
     */
    DATA_ANALYSIS(
        estimatedMemoryMb = 128,
        estimatedCpuMs = 10000,
        typicalPriority = Priority.NORMAL
    ),

    /**
     * ML inference (run trained model on input data)
     * - Typical inputs: Images, text, sensor data + model file
     * - Typical outputs: Predictions, classifications, embeddings
     */
    ML_INFERENCE(
        estimatedMemoryMb = 384,
        estimatedCpuMs = 8000,
        typicalPriority = Priority.HIGH
    ),

    /**
     * Cryptographic operations (encryption, signing, verification)
     * - Typical inputs: Data to encrypt/sign, keys
     * - Typical outputs: Encrypted data, signatures, verification results
     */
    CRYPTOGRAPHIC(
        estimatedMemoryMb = 64,
        estimatedCpuMs = 3000,
        typicalPriority = Priority.HIGH
    ),

    /**
     * MapReduce-style distributed computation
     * - Typical inputs: Large dataset split into chunks
     * - Typical outputs: Reduced results per chunk
     */
    MAP_REDUCE(
        estimatedMemoryMb = 256,
        estimatedCpuMs = 15000,
        typicalPriority = Priority.NORMAL
    ),

    /**
     * ML pipeline (multi-stage ML workflow)
     * - Typical inputs: Raw data, pipeline definition
     * - Typical outputs: Processed data, model outputs, metrics
     */
    ML_PIPELINE(
        estimatedMemoryMb = 512,
        estimatedCpuMs = 20000,
        typicalPriority = Priority.NORMAL
    ),

    /**
     * Graph processing (social network analysis, pathfinding)
     * - Typical inputs: Graph structure (nodes, edges), query
     * - Typical outputs: Analysis results, subgraphs, paths
     */
    GRAPH_PROCESSING(
        estimatedMemoryMb = 256,
        estimatedCpuMs = 12000,
        typicalPriority = Priority.NORMAL
    );

    enum class Priority {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }
}
```

**IMPORTANT**: Remove `DISTRIBUTED_STORAGE` from job types - storage replication is handled by `DistributedStorageManager.kt`, not compute tasks.

### 1.4 Extend TaskStatus Data Class

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskManager.kt`

**Current TaskStatus** (line ~40):
```kotlin
data class TaskStatus(
    val taskId: String,
    val phase: TaskPhase,
    val progress: Float,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val taskHash: String? = null
)
```

**Add Execution Fields**:

```kotlin
data class TaskStatus(
    val taskId: String,
    val phase: TaskPhase,
    val progress: Float,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val taskHash: String? = null,
    
    // === EXECUTION FIELDS (NEW) ===
    val executionStartedAt: Long? = null,
    val executorNodeAddress: String? = null,
    val containerId: String? = null,
    val resourceUsage: ResourceMetrics? = null,
    val executionContext: TaskExecutionContext? = null
)
```

**Add Execution Phases**:

```kotlin
enum class TaskPhase {
    CREATED,
    BROADCASTING,
    AWAITING_RESPONSES,
    SELECTING_NODE,
    ASSIGNING,
    ACCEPTED,           // NEW: Compute node accepted task
    PREPARING,          // NEW: Loading inputs, creating container
    EXECUTING,          // NEW: Task running in sandbox
    FINALIZING,         // NEW: Storing results, cleaning up
    COMPLETED,
    FAILED,
    CANCELLED
}
```

### 1.5 Add TASK_COMPLETED Message Type

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/core/message/MeshEcosystemMessage.kt`

**Add New Message Type**:

```kotlin
@Serializable
data class TaskCompletedMessage(
    val taskId: String,
    val success: Boolean,
    val resultManifest: List<FileReference>,    // Zero or more output files
    val resultMessage: String? = null,          // Optional task-defined message
    val executionStats: ExecutionStats,
    val error: ExecutionError? = null,
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : MeshEcosystemMessage() {
    
    @Serializable
    data class ExecutionStats(
        val executionTimeMs: Long,
        val resourcesUsed: ResourceMetrics,
        val containerId: String
    )
    
    @Serializable
    data class ExecutionError(
        val errorType: ExecutionErrorType,
        val errorMessage: String,
        val stackTrace: String? = null
    )
}
```

### 1.6 Add Task Completion Retry Constant

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/util/MeshrabiyaConstants.kt`

**Add Constant**:

```kotlin
object MeshrabiyaConstants {
    // ... existing constants ...
    
    /**
     * Duration to retry sending task completion notification if client is offline
     * After this period, stop retrying and log failure
     */
    const val TASK_COMPLETION_RETRY_PERIOD_MS = 5 * 60 * 1000L // 5 minutes
    
    /**
     * Interval between retry attempts for task completion notification
     */
    const val TASK_COMPLETION_RETRY_INTERVAL_MS = 30 * 1000L // 30 seconds
}
```

---

## Section 2: Storage API Refactoring (CRITICAL)

### 2.1 Problem Statement

**Current Gap**: `DistributedStorageManager.storeFile()` lacks permission parameters needed for task result storage. Per STORAGE_ENCRYPTION+PLAN.md, task results must:
1. Be encrypted with hybrid encryption (chunk key + per-recipient key encryption)
2. Have explicit Owner (task requester)
3. Have explicit recipients list (authorized nodes)
4. Use AccessScope to define permission model

### 2.2 Refactor DistributedStorageManager.storeFile()

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/DistributedStorageManager.kt` (line 317)

**Current Signature**:
```kotlin
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD
): FileReference?
```

**New Signature**:
```kotlin
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
    accessScope: AccessScope = AccessScope.TASK_ISOLATED,
    owner: String? = null,              // Task requester node public key
    recipients: List<String>? = null    // Authorized nodes (null = owner only)
): FileReference?
```

**Implementation Changes**:

1. **Import AccessScope** (add at top of file):
```kotlin
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.AccessScope
```

2. **Update storeFile() Implementation**:

```kotlin
suspend fun storeFile(
    path: String,
    data: ByteArray,
    priority: SyncPriority = SyncPriority.NORMAL,
    replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
    accessScope: AccessScope = AccessScope.TASK_ISOLATED,
    owner: String? = null,
    recipients: List<String>? = null
): FileReference? = coroutineScope {
    betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation started: $path (${data.size} bytes)")

    if (!storageQuotaManager.canStoreFile(data.size.toLong())) {
        betaLogger.log(LogLevel.WARN, "Storage", "Storage quota exceeded for file: $path (${data.size} bytes)")
        throw StorageQuotaExceededException("Insufficient storage quota")
    }

    // === NEW: Prepare encryption with recipients ===
    val effectiveOwner = owner ?: meshNodeId // Default to local node if no owner specified
    val effectiveRecipients = when (accessScope) {
        AccessScope.TASK_ISOLATED -> recipients ?: listOf(effectiveOwner)
        AccessScope.SERVICE_SHARED -> recipients ?: emptyList() // Service members loaded separately
        AccessScope.MESH_GLOBAL -> emptyList() // Public access (no specific recipients)
    }
    
    betaLogger.log(
        LogLevel.DEBUG,
        "DistributedStorage",
        "Starting encryption for file: $path, size=${data.size}B, owner=$effectiveOwner, " +
        "accessScope=$accessScope, recipients=${effectiveRecipients.size}"
    )
    
    val encryptStartTime = System.currentTimeMillis()
    
    // === NEW: Encrypt with hybrid encryption (chunk key + per-recipient key encryption) ===
    val encryptedData = encryptionManager.encryptWithRecipients(
        data = data,
        owner = effectiveOwner,
        recipients = effectiveRecipients
    )
    
    val encryptDuration = System.currentTimeMillis() - encryptStartTime
    betaLogger.log(
        LogLevel.DEBUG,
        "DistributedStorage",
        "Encryption complete for $path: ${data.size}B -> ${encryptedData.size}B in ${encryptDuration}ms"
    )
    
    // Store encrypted data to local file
    val file = File(path)
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { it.write(encryptedData) }
    
    val fileId = sha256File(file)
    val chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
    val chunks = chunkFile(file, fileId, chunkSize)
    
    // === NEW: Store metadata with permissions ===
    val fileMetadata = FileMetadata(
        fileId = fileId,
        path = path,
        sizeBytes = data.size.toLong(),
        owner = effectiveOwner,
        recipients = effectiveRecipients,
        accessScope = accessScope,
        createdAt = System.currentTimeMillis()
    )
    metadataStore.storeMetadata(fileId, fileMetadata)
    
    // ... rest of existing implementation (chunk replication) ...
}
```

3. **Add FileMetadata Data Class** (if not exists):

```kotlin
@Serializable
data class FileMetadata(
    val fileId: String,
    val path: String,
    val sizeBytes: Long,
    val owner: String,
    val recipients: List<String>,
    val accessScope: AccessScope,
    val createdAt: Long,
    val encryptionKeyId: String? = null
)
```

4. **Add EncryptionManager.encryptWithRecipients()** (new method):

```kotlin
/**
 * Hybrid encryption with per-recipient key encryption
 * Per STORAGE_ENCRYPTION+PLAN.md design
 */
suspend fun encryptWithRecipients(
    data: ByteArray,
    owner: String,
    recipients: List<String>
): ByteArray {
    // 1. Generate random symmetric key for this chunk
    val chunkKey = generateSymmetricKey()
    
    // 2. Encrypt data with symmetric key
    val encryptedData = encryptSymmetric(data, chunkKey)
    
    // 3. Encrypt chunk key for each recipient (PGP public key encryption)
    val encryptedKeys = recipients.map { recipientPubKey ->
        EncryptedKeyForRecipient(
            recipientId = recipientPubKey,
            encryptedKey = encryptAsymmetric(chunkKey, recipientPubKey)
        )
    }
    
    // 4. Bundle: [encrypted data] + [encrypted keys for each recipient]
    return serializeHybridEncryptedBundle(encryptedData, encryptedKeys)
}
```

### 2.3 Update TaskManager Result Storage Calls

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskManager.kt`

**Current completeTask()** (lines 234-294):
```kotlin
fun completeTask(
    taskId: String,
    outputs: Map<String, ByteArray>,
    message: String = "Task completed successfully"
) {
    // ... existing code ...
    
    // Publish outputs via hooks with TASK_ISOLATED scope
    publishOutputHooks[taskId]?.forEach { hook ->
        hook(taskId, outputs, AccessScope.TASK_ISOLATED)
    }
}
```

**Update to Pass Owner and Recipients**:

```kotlin
fun completeTask(
    taskId: String,
    outputs: Map<String, ByteArray>,
    owner: String,              // NEW: Task requester node ID
    recipients: List<String>,   // NEW: Authorized nodes
    accessScope: AccessScope = AccessScope.TASK_ISOLATED,
    message: String = "Task completed successfully"
) {
    val status = taskStatuses[taskId] ?: return
    
    // ... existing status update code ...
    
    // Publish outputs via hooks with proper permissions
    publishOutputHooks[taskId]?.forEach { hook ->
        hook(taskId, outputs, owner, recipients, accessScope)
    }
}
```

**Update Hook Signature**:
```kotlin
// OLD
typealias PublishOutputHook = (taskId: String, outputs: Map<String, ByteArray>, accessScope: AccessScope) -> Unit

// NEW
typealias PublishOutputHook = (
    taskId: String,
    outputs: Map<String, ByteArray>,
    owner: String,
    recipients: List<String>,
    accessScope: AccessScope
) -> Unit
```

---

## Section 3: TaskManager Extension

### 3.1 Overview

TaskManager currently tracks task lifecycle but **does not execute tasks**. This section adds:
- Execution orchestration methods
- Resource monitoring (poll container metrics)
- Load tracking (total + per-task)
- Result storage coordination
- Completion notification sending

### 3.2 Add Execution State Tracking

**Add to TaskManager object**:

```kotlin
object TaskManager {
    // ... existing fields ...
    
    // === EXECUTION STATE (NEW) ===
    
    /**
     * Active executions: taskId -> ExecutionState
     */
    private val activeExecutions = ConcurrentHashMap<String, ExecutionState>()
    
    /**
     * Container ID to Task ID mapping
     */
    private val containerToTask = ConcurrentHashMap<String, String>()
    
    /**
     * Resource monitoring job
     */
    private var resourceMonitoringJob: Job? = null
    
    /**
     * Total load across all executing tasks
     */
    @Volatile
    private var totalLoad = ResourceMetrics.zero()
    
    /**
     * Execution state for a task
     */
    private data class ExecutionState(
        val context: TaskExecutionContext,
        val containerId: String,
        val startedAt: Long,
        var currentMetrics: ResourceMetrics,
        var peakMetrics: ResourceMetrics,
        val metricsHistory: MutableList<ResourceMetrics> = mutableListOf()
    )
}
```

### 3.3 Add Execution Methods

**Add to TaskManager object**:

```kotlin
/**
 * EXECUTE TASK
 * 
 * Main entry point for task execution on compute node.
 * Called after compute node receives task assignment message.
 * 
 * Flow:
 * 1. Create execution context
 * 2. Retrieve input files from DistributedStorage
 * 3. Create sandbox container
 * 4. Load executor for task type
 * 5. Execute task in sandbox
 * 6. Store result files
 * 7. Send completion notification
 * 8. Cleanup container
 */
suspend fun executeTask(
    taskId: String,
    taskType: TaskType,
    jobType: JobType,
    codeBundle: ByteArray,
    inputManifest: List<FileReference>,
    resourceLimits: ResourceLimits,
    deadlineMs: Long,
    requesterNodeId: String,
    callbackAddress: String,
    accessScope: AccessScope = AccessScope.TASK_ISOLATED
): ExecutionResult = coroutineScope {
    
    updateTaskStatus(
        taskId,
        TaskPhase.ACCEPTED,
        0f,
        "Task accepted, preparing for execution"
    )
    
    try {
        // 1. Create execution context
        val context = TaskExecutionContext(
            taskId = taskId,
            taskType = taskType,
            jobType = jobType,
            codeBundle = codeBundle,
            inputManifest = inputManifest,
            resourceLimits = resourceLimits,
            deadlineMs = deadlineMs,
            requesterNodeId = requesterNodeId,
            callbackAddress = callbackAddress,
            accessScope = accessScope
        )
        
        // 2. Retrieve input files
        updateTaskStatus(taskId, TaskPhase.PREPARING, 0.1f, "Retrieving input files")
        val inputFiles = retrieveInputFiles(inputManifest)
        
        // 3. Create sandbox container
        updateTaskStatus(taskId, TaskPhase.PREPARING, 0.3f, "Creating sandbox container")
        val containerId = createSandboxContainer(context)
        
        // 4. Register execution
        val executionState = ExecutionState(
            context = context,
            containerId = containerId,
            startedAt = System.currentTimeMillis(),
            currentMetrics = ResourceMetrics.zero(),
            peakMetrics = ResourceMetrics.zero()
        )
        activeExecutions[taskId] = executionState
        containerToTask[containerId] = taskId
        
        // 5. Start resource monitoring if not already running
        ensureResourceMonitoringActive()
        
        // 6. Load executor
        updateTaskStatus(taskId, TaskPhase.PREPARING, 0.5f, "Loading executor")
        val executor = loadExecutor(taskType)
        
        // 7. Execute task
        updateTaskStatus(taskId, TaskPhase.EXECUTING, 0.6f, "Executing task")
        val result = executor.execute(context, inputFiles, containerId)
        
        // 8. Store result files
        if (result.success && result.outputManifest.isNotEmpty()) {
            updateTaskStatus(taskId, TaskPhase.FINALIZING, 0.8f, "Storing result files")
            storeResultFiles(
                taskId = taskId,
                outputManifest = result.outputManifest,
                owner = requesterNodeId,
                recipients = listOf(requesterNodeId), // Task requester can access results
                accessScope = accessScope
            )
        }
        
        // 9. Send completion notification
        updateTaskStatus(taskId, TaskPhase.FINALIZING, 0.9f, "Sending completion notification")
        sendCompletionNotification(
            taskId = taskId,
            requesterNodeId = requesterNodeId,
            callbackAddress = callbackAddress,
            result = result
        )
        
        // 10. Update status
        if (result.success) {
            completeTask(
                taskId = taskId,
                outputs = emptyMap(), // Already stored via storeResultFiles
                owner = requesterNodeId,
                recipients = listOf(requesterNodeId),
                accessScope = accessScope,
                message = result.resultMessage ?: "Task completed successfully"
            )
        } else {
            failTask(taskId, result.errorMessage ?: "Task execution failed")
        }
        
        result
        
    } catch (e: Exception) {
        val errorResult = ExecutionResult(
            taskId = taskId,
            success = false,
            outputManifest = emptyList(),
            resourcesUsed = activeExecutions[taskId]?.currentMetrics ?: ResourceMetrics.zero(),
            executionTimeMs = System.currentTimeMillis() - (activeExecutions[taskId]?.startedAt ?: 0),
            errorMessage = e.message ?: "Unknown error",
            errorType = ExecutionErrorType.UNKNOWN
        )
        
        failTask(taskId, e.message ?: "Execution failed")
        errorResult
        
    } finally {
        // 11. Cleanup
        cleanupExecution(taskId)
    }
}
```

**Add Helper Methods**:

```kotlin
/**
 * RETRIEVE INPUT FILES
 * 
 * Download input files from DistributedStorage using manifest references
 */
private suspend fun retrieveInputFiles(
    inputManifest: List<FileReference>
): Map<String, ByteArray> {
    return inputManifest.associate { fileRef ->
        val data = distributedStorageManager.retrieveFile(fileRef.fileId)
            ?: throw IllegalStateException("Input file not found: ${fileRef.fileName}")
        fileRef.fileName to data
    }
}

/**
 * CREATE SANDBOX CONTAINER
 * 
 * Create isolated container for task execution
 */
private suspend fun createSandboxContainer(context: TaskExecutionContext): String {
    val sandboxConfig = getSandboxConfigForTaskType(context.taskType)
    
    return strangersSafeComputeEngine.createContainer(
        limits = context.resourceLimits,
        syscallWhitelist = sandboxConfig.syscallWhitelist,
        networkAccess = false // Always false for untrusted code
    )
}

/**
 * GET SANDBOX CONFIG FOR TASK TYPE
 * 
 * Different task types require different syscall whitelists
 */
private fun getSandboxConfigForTaskType(taskType: TaskType): SandboxConfig {
    return when (taskType) {
        TaskType.PYTHON -> SandboxConfig(
            syscallWhitelist = listOf(
                "read", "write", "open", "close", "stat", "fstat",
                "mmap", "munmap", "brk", "exit_group",
                // Python needs more syscalls for interpreter
                "access", "getcwd", "getdents", "lseek"
            )
        )
        TaskType.JAVA, TaskType.JVM -> SandboxConfig(
            syscallWhitelist = listOf(
                "read", "write", "open", "close", "stat", "fstat",
                "mmap", "munmap", "brk", "exit_group",
                // JVM needs additional syscalls
                "futex", "clone", "set_robust_list"
            )
        )
        TaskType.JAVASCRIPT -> SandboxConfig(
            syscallWhitelist = listOf(
                "read", "write", "open", "close", "stat", "fstat",
                "mmap", "munmap", "brk", "exit_group"
            )
        )
        TaskType.ML_NATIVE -> SandboxConfig(
            syscallWhitelist = listOf(
                "read", "write", "open", "close", "stat", "fstat",
                "mmap", "munmap", "brk", "exit_group",
                // ML models may need GPU access
                "ioctl" // For GPU/NPU access
            )
        )
        TaskType.WORKFLOW -> SandboxConfig(
            syscallWhitelist = listOf(
                "read", "write", "open", "close", "stat", "fstat",
                "mmap", "munmap", "brk", "exit_group"
            )
        )
    }
}

private data class SandboxConfig(
    val syscallWhitelist: List<String>
)

/**
 * LOAD EXECUTOR
 * 
 * Get executor implementation for task type
 */
private fun loadExecutor(taskType: TaskType): TaskExecutor {
    return when (taskType) {
        TaskType.PYTHON -> PythonExecutor(strangersSafeComputeEngine)
        TaskType.JAVA, TaskType.JVM -> JVMExecutor(strangersSafeComputeEngine)
        TaskType.JAVASCRIPT -> JSExecutor(strangersSafeComputeEngine)
        TaskType.ML_NATIVE -> MLNativeExecutor(strangersSafeComputeEngine)
        TaskType.WORKFLOW -> WorkflowExecutor(strangersSafeComputeEngine, this)
    }
}

/**
 * STORE RESULT FILES
 * 
 * Store task output files to DistributedStorage with proper permissions
 */
private suspend fun storeResultFiles(
    taskId: String,
    outputManifest: List<FileReference>,
    owner: String,
    recipients: List<String>,
    accessScope: AccessScope
) {
    outputManifest.forEach { fileRef ->
        // Read file data from container's output directory
        val filePath = "/task_outputs/$taskId/${fileRef.fileName}"
        val fileData = File(filePath).readBytes()
        
        // Store with permissions
        distributedStorageManager.storeFile(
            path = filePath,
            data = fileData,
            priority = SyncPriority.HIGH, // Task results are high priority
            replicationLevel = ReplicationLevel.STANDARD,
            accessScope = accessScope,
            owner = owner,
            recipients = recipients
        )
    }
}

/**
 * SEND COMPLETION NOTIFICATION
 * 
 * Send direct node-to-node TASK_COMPLETED message to task requester
 */
private suspend fun sendCompletionNotification(
    taskId: String,
    requesterNodeId: String,
    callbackAddress: String,
    result: ExecutionResult
) {
    val message = MeshEcosystemMessage.TaskCompletedMessage(
        taskId = taskId,
        success = result.success,
        resultManifest = result.outputManifest,
        resultMessage = result.resultMessage,
        executionStats = MeshEcosystemMessage.TaskCompletedMessage.ExecutionStats(
            executionTimeMs = result.executionTimeMs,
            resourcesUsed = result.resourcesUsed,
            containerId = activeExecutions[taskId]?.containerId ?: ""
        ),
        error = result.errorMessage?.let {
            MeshEcosystemMessage.TaskCompletedMessage.ExecutionError(
                errorType = result.errorType ?: ExecutionErrorType.UNKNOWN,
                errorMessage = it
            )
        },
        senderId = meshNodeId
    )
    
    // Send with retry logic
    sendWithRetry(
        recipientId = requesterNodeId,
        message = message,
        retryPeriodMs = MeshrabiyaConstants.TASK_COMPLETION_RETRY_PERIOD_MS,
        retryIntervalMs = MeshrabiyaConstants.TASK_COMPLETION_RETRY_INTERVAL_MS
    )
}

/**
 * CLEANUP EXECUTION
 * 
 * Remove execution state and delete container
 */
private suspend fun cleanupExecution(taskId: String) {
    val execution = activeExecutions.remove(taskId) ?: return
    containerToTask.remove(execution.containerId)
    
    // Delete container
    strangersSafeComputeEngine.deleteContainer(execution.containerId)
    
    // Stop resource monitoring if no more active executions
    if (activeExecutions.isEmpty()) {
        resourceMonitoringJob?.cancel()
        resourceMonitoringJob = null
        totalLoad = ResourceMetrics.zero()
    }
}
```

---

**END OF FIRST HALF**

This completes the first half of the comprehensive implementation plan, covering:
1. ✅ Data structure refactoring (task types, job types, execution context, resource metrics)
2. ✅ CRITICAL: Storage API refactoring (add AccessScope/Owner/recipients parameters)
3. ✅ TaskManager execution methods (executeTask, helpers, cleanup)

**Second half will cover**:
4. Executor implementations (Python, JVM, JS, MLNative, Workflow)
5. Resource monitoring implementation
6. Runtime management
7. Service library enhancements
8. Integration points
9. Testing strategy
10. Implementation checklist
