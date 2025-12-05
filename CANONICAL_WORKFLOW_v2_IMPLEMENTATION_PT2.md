# CANONICAL WORKFLOWS v2 IMPLEMENTATION PLAN - PART 2

**Date**: December 3, 2025  
**Updated**: December 4, 2025  
**Scope**: Phases 5-7 Implementation - TaskManager, DistributedComputeServer, Integration  
**Status**: PART 1 COMPLETE - PART 2 IN PROGRESS

---

## CURRENT PROGRESS STATUS

### ✅ COMPLETED (Part 1)
- Task.kt created with executionContext property and assignedExecutor field
- TaskState enum with WAITING_FOR_INPUT state added
- TaskExecutor.kt interface created and updated with inputFiles/containerId parameters
- ExecutionResult.kt created
- DistributedComputeClient.kt created and fully functional
- TaskAcceptanceMessage and TaskCompletionAckMessage added to MeshEcosystemMessage.kt
- ServiceLibraryEntry.kt updated with hasInputFiles/hasOutputFiles
- VirtualNode.kt IntelligentDistributedComputeService references commented out
- ComputeTask.kt UUID serialization fixed with @Contextual
- **All Part 1 files compile with ZERO errors**

### 🔄 IN PROGRESS (Part 2 - Current Phase)
- **NEXT**: Create TaskManager.kt (~400 lines) - **REFACTORING PLANNED**
- **NEXT**: Create DistributedComputeServer.kt (~500 lines)

### ⏳ PENDING (Part 2 - Remaining)
- Update MeshEcosystemListener.kt routing (7 changes)
- Update VirtualNode.kt service instantiation (3 new services + registration)
- Add EmergentRoleManager.getLocalMLCapabilitiesForResponse()
- Rename 5 files to .md (IntelligentDistributedComputeService, executors, SandboxStorageProxy)
- Recreate 4 files from scratch (JVMExecutor, JSExecutor, MLNativeExecutor, SandboxStorageProxy)
- Build validation and testing

### 📋 REFACTORING DECISION
**TaskManager.kt** (~400 lines) will be refactored into modular components for easier editing and maintenance:
- See Section 9: TaskManager Refactoring Strategy (below)

---

## EXECUTIVE SUMMARY

This document details Part 2 of the implementation plan for CANONICAL_WORKFLOWS_v2.md Phases 5-7 (Add Task, Task Manager Receives Access Update, Completed Task workflows). Part 2 covers TaskManager creation, DistributedComputeServer implementation, and integration work including splitting IntelligentDistributedComputeService.

**Part 1** (completed) covered foundation work: new data structures, interfaces, message types, executor refactoring, and DistributedComputeClient implementation.

---

## DESIGN DECISIONS INCORPORATED

All 5 design decisions from Part 1 plus 3 additional clarifications:

1. **Task Sandbox**: Use ProcessBuilder with filesystem isolation (no PID tracking due to Java 8/Android constraints)
2. **ServiceLibraryEntry**: Add `hasInputFiles` and `hasOutputFiles` boolean fields
3. **Task Result Retention**: Never expire (owner revokes only)
4. **Node Ranking Weights**: latency 30%, currentLoad 25%, capability 20%, fitness 15%, RAM 5%, storage 5%
5. **Acceptance Timing**: Send TaskAcceptanceMessage immediately; if prep fails, send TaskCompletedMessage with success=false
6. **Task.kt Strategy**: Create Task.kt with TaskExecutionContext as a property
7. **MicroContainer**: Implement ProcessBuilder-based isolation directly in TaskManager (simpler architecture)
8. **Backward Compatibility**: Rename existing files to .md, recreate from scratch with complete implementations

---

## PART 2 SCOPE

### Files to Create (2)
1. `Task.kt` - Core task data structure with TaskExecutionContext property
2. `TaskManager.kt` - Task lifecycle orchestration
3. `DistributedComputeServer.kt` - Server-side task management

### Files to Rename & Recreate (5)
1. `IntelligentDistributedComputeService.kt` → `.md` (preserve as documentation)
2. `JSExecutor.kt` → `.md` (recreate with TaskExecutor interface)
3. `JVMExecutor.kt` → `.md` (recreate with TaskExecutor interface)
4. `MLNativeExecutor.kt` → `.md` (recreate with TaskExecutor interface)
5. `SandboxStorageProxy.kt` → `.md` (recreate with simplified API)

### Files to Refactor (3)
1. `MeshEcosystemListener.kt` - Update routing for Client/Server split
2. `VirtualNode.kt` - Update service instantiation
3. `EmergentRoleManager.kt` - Add ML capabilities method

---

## IMPLEMENTATION SECTIONS

## 1. TASK DATA STRUCTURE

### 1.1 Task.kt (NEW)

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/Task.kt`

**Full Implementation**:

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import java.util.UUID

/**
 * Represents a compute task being executed on the mesh network.
 * 
 * Combines task metadata, execution context, and lifecycle management.
 * Used by TaskManager to track tasks from acceptance through completion.
 *
 * @property taskId Unique identifier for the task
 * @property executionContext Complete task execution context (from existing model)
 * @property state Current execution state
 * @property publicKey Public key for encrypting data shared with this task
 * @property privateKey Private key for decrypting input files (stored securely in memory)
 * @property sandboxDir Sandbox directory for isolated execution
 * @property createdAt Timestamp when task was created
 * @property startedAt Timestamp when execution started (null if not started)
 * @property completedAt Timestamp when execution completed (null if not completed)
 * @property assignedExecutor Which executor is handling this task (JVM/JS/ML)
 */
data class Task(
    val taskId: String,
    val executionContext: TaskExecutionContext,
    var state: TaskState,
    val publicKey: String,
    val privateKey: ByteArray,
    val sandboxDir: String,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var assignedExecutor: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Task

        if (taskId != other.taskId) return false
        if (executionContext != other.executionContext) return false
        if (state != other.state) return false
        if (publicKey != other.publicKey) return false
        if (!privateKey.contentEquals(other.privateKey)) return false
        if (sandboxDir != other.sandboxDir) return false
        if (createdAt != other.createdAt) return false
        if (startedAt != other.startedAt) return false
        if (completedAt != other.completedAt) return false
        if (assignedExecutor != other.assignedExecutor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = taskId.hashCode()
        result = 31 * result + executionContext.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + sandboxDir.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (startedAt?.hashCode() ?: 0)
        result = 31 * result + (completedAt?.hashCode() ?: 0)
        result = 31 * result + (assignedExecutor?.hashCode() ?: 0)
        return result
    }
}

/**
 * Task execution state machine
 */
enum class TaskState {
    /** Task accepted, preparing sandbox and dependencies */
    PREPARING,
    
    /** Waiting for input files via FileAccessUpdateConfirmation */
    WAITING_FOR_INPUT,
    
    /** Task is actively executing */
    EXECUTING,
    
    /** Task completed successfully */
    COMPLETED,
    
    /** Task failed during preparation or execution */
    FAILED
}
```

**Dependencies**:
- Import `TaskExecutionContext` from `com.ustadmobile.meshrabiya.service.compute.model`

**Rationale**:
- Reuses existing `TaskExecutionContext` structure (discovered in research)
- Adds lifecycle tracking fields (state, timestamps, sandbox)
- Includes task keypair for file encryption
- Simple, focused on what TaskManager needs to track

---

## 2. TASK MANAGER (REFACTORED - 4 COMPONENTS)

### Overview

TaskManager has been refactored into 4 focused components for improved testability, maintainability, and future extensibility:

1. **TaskSandboxManager** - Filesystem isolation and sandbox lifecycle
2. **TaskInputFileManager** - Input file retrieval and tracking
3. **TaskExecutionCoordinator** - Executor selection and task execution
4. **TaskManager** - Orchestration and public API

**Total Lines**: ~465 (vs ~400 monolithic)  
**Benefits**: Independent testing, clearer boundaries, parallel development, future growth without file size limits

---

### 2.1 TaskSandboxManager.kt (NEW)

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskSandboxManager.kt`

**Responsibility**: Filesystem isolation, sandbox creation/cleanup, path management

**Full Implementation** (~110 lines):

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TaskSandboxManager
 * 
 * Manages filesystem isolation for compute tasks.
 * Each task gets isolated sandbox with inputs/ and outputs/ directories.
 * 
 * Features:
 * - Path caching for performance
 * - Automatic directory creation
 * - Recursive cleanup on task removal
 */
class TaskSandboxManager(
    private val context: Context
) {
    private val pathsCache = ConcurrentHashMap<String, SandboxPaths>()
    
    companion object {
        private const val SANDBOX_BASE_DIR = "task_sandboxes"
    }
    
    /**
     * Create sandbox directories for task.
     * Returns cached paths if already created.
     */
    fun createSandbox(taskId: String): SandboxPaths {
        return pathsCache.getOrPut(taskId) {
            val baseDir = File(context.filesDir, "$SANDBOX_BASE_DIR/$taskId")
            val inputsDir = File(baseDir, "inputs")
            val outputsDir = File(baseDir, "outputs")
            
            baseDir.mkdirs()
            inputsDir.mkdirs()
            outputsDir.mkdirs()
            
            SandboxPaths(
                base = baseDir.absolutePath,
                inputs = inputsDir.absolutePath,
                outputs = outputsDir.absolutePath
            )
        }
    }
    
    /**
     * Get sandbox paths (cached or computed).
     */
    fun getSandboxPaths(taskId: String): SandboxPaths {
        return pathsCache.getOrPut(taskId) {
            val baseDir = File(context.filesDir, "$SANDBOX_BASE_DIR/$taskId")
            SandboxPaths(
                base = baseDir.absolutePath,
                inputs = File(baseDir, "inputs").absolutePath,
                outputs = File(baseDir, "outputs").absolutePath
            )
        }
    }
    
    /**
     * Write input file to sandbox inputs directory.
     */
    fun writeInputFile(taskId: String, fileName: String, data: ByteArray) {
        val paths = getSandboxPaths(taskId)
        val inputFile = File(paths.inputs, fileName)
        inputFile.writeBytes(data)
    }
    
    /**
     * Read all output files from sandbox outputs directory.
     */
    fun readOutputFiles(taskId: String): Map<String, ByteArray> {
        val paths = getSandboxPaths(taskId)
        val outputsDir = File(paths.outputs)
        
        if (!outputsDir.exists()) return emptyMap()
        
        val outputFiles = mutableMapOf<String, ByteArray>()
        outputsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                outputFiles[file.name] = file.readBytes()
            }
        }
        
        return outputFiles
    }
    
    /**
     * Cleanup sandbox directory and remove from cache.
     */
    fun cleanupSandbox(taskId: String) {
        pathsCache.remove(taskId)
        
        val sandboxDir = File(context.filesDir, "$SANDBOX_BASE_DIR/$taskId")
        if (sandboxDir.exists()) {
            sandboxDir.deleteRecursively()
        }
    }
}

/**
 * Sandbox paths data class.
 */
data class SandboxPaths(
    val base: String,
    val inputs: String,
    val outputs: String
)
```

---

### 2.2 TaskInputFileManager.kt (NEW)

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskInputFileManager.kt`

**Responsibility**: Input file retrieval, FileReference tracking, execution readiness

**Full Implementation** (~95 lines):

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.service.compute.model.FileReference
import com.ustadmobile.meshrabiya.service.compute.model.ServiceLibraryEntry
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * TaskInputFileManager
 * 
 * Manages input file retrieval and tracking for tasks.
 * 
 * Features:
 * - FileReference tracking per task
 * - Determines execution readiness
 * - Integrates with DistributedStorageClient and TaskSandboxManager
 */
class TaskInputFileManager(
    private val context: Context,
    private val distributedStorageClient: DistributedStorageClient,
    private val sandboxManager: TaskSandboxManager,
    private val betaLogger: BetaTestLogger? = null
) {
    private val expectedFiles = ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedFiles = ConcurrentHashMap<String, MutableSet<String>>()
    
    companion object {
        private const val TAG = "TaskInputFileManager"
    }
    
    /**
     * Track expected input files for task.
     */
    fun trackExpectedFiles(taskId: String, inputManifest: List<FileReference>) {
        val fileIds = inputManifest.map { it.fileId }.toMutableSet()
        expectedFiles[taskId] = fileIds
        receivedFiles.getOrPut(taskId) { mutableSetOf() }
        
        betaLogger?.log(TAG, "Tracking ${fileIds.size} expected files for task $taskId")
    }
    
    /**
     * Handle file access update notification.
     * Retrieves file from storage and writes to sandbox.
     */
    suspend fun handleFileAccessUpdate(
        task: Task,
        fileId: String
    ) = withContext(Dispatchers.IO) {
        try {
            betaLogger?.log(TAG, "Retrieving file $fileId for task ${task.taskId}")
            
            // Retrieve file from distributed storage
            val fileReference = FileReference(
                fileId = fileId,
                fileName = fileId,
                sizeBytes = 0L
            )
            
            val fileBytes = distributedStorageClient.retrieveFile(fileReference)
            
            if (fileBytes != null) {
                // Write to sandbox inputs directory
                sandboxManager.writeInputFile(task.taskId, fileReference.fileName, fileBytes)
                
                // Track received file
                receivedFiles.getOrPut(task.taskId) { mutableSetOf() }.add(fileId)
                
                betaLogger?.log(TAG, "File $fileId added to task ${task.taskId} sandbox")
            } else {
                betaLogger?.log(TAG, "Failed to retrieve file: $fileId")
                throw Exception("File retrieval failed: $fileId")
            }
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "File access update failed for task ${task.taskId}: ${e.message}")
            task.state = TaskState.FAILED
            throw e
        }
    }
    
    /**
     * Check if all expected input files have been received.
     */
    fun shouldProceedToExecution(task: Task, serviceLibraryEntry: ServiceLibraryEntry): Boolean {
        // If task doesn't expect input files, proceed immediately
        if (!serviceLibraryEntry.hasInputFiles) {
            return true
        }
        
        val expected = expectedFiles[task.taskId] ?: emptySet()
        val received = receivedFiles[task.taskId] ?: emptySet()
        
        val ready = expected == received
        
        betaLogger?.log(TAG, "Task ${task.taskId} ready check: ${received.size}/${expected.size} files received")
        
        return ready
    }
    
    /**
     * Cleanup tracking data for task.
     */
    fun cleanupTask(taskId: String) {
        expectedFiles.remove(taskId)
        receivedFiles.remove(taskId)
    }
}
```

---

### 2.3 TaskExecutionCoordinator.kt (NEW)

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskExecutionCoordinator.kt`

**Responsibility**: Executor selection, task execution, state transitions

**Full Implementation** (~110 lines):

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutor
import com.ustadmobile.meshrabiya.service.compute.executor.JVMExecutor
import com.ustadmobile.meshrabiya.service.compute.executor.JSExecutor
import com.ustadmobile.meshrabiya.service.compute.executor.MLNativeExecutor
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TaskExecutionCoordinator
 * 
 * Coordinates task execution via appropriate runtime executor.
 * 
 * Features:
 * - Lazy executor initialization (only when needed)
 * - Reads inputs from sandbox
 * - Routes to JVM/JS/ML executor based on TaskType
 * - Updates task state (EXECUTING → COMPLETED/FAILED)
 */
class TaskExecutionCoordinator(
    private val context: Context,
    private val betaLogger: BetaTestLogger? = null
) {
    companion object {
        private const val TAG = "TaskExecutionCoordinator"
    }
    
    /**
     * Lazy executor initialization - only created on first use per type.
     */
    private val executors: Map<TaskType, TaskExecutor> by lazy {
        mapOf(
            TaskType.JVM to JVMExecutor(),
            TaskType.JAVASCRIPT to JSExecutor(),
            TaskType.ML_NATIVE to MLNativeExecutor()
        )
    }
    
    /**
     * Execute task using appropriate executor.
     * Updates task state and timestamps.
     */
    suspend fun executeTask(
        task: Task,
        sandboxPaths: SandboxPaths,
        serviceLibraryEntry: ServiceLibraryEntry
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            // Transition to EXECUTING state
            task.state = TaskState.EXECUTING
            task.startedAt = System.currentTimeMillis()
            
            betaLogger?.log(TAG, "Executing task ${task.taskId} with executor ${task.executionContext.taskType}")
            
            // Get appropriate executor
            val executor = executors[task.executionContext.taskType] ?: run {
                betaLogger?.log(TAG, "No executor found for task type: ${task.executionContext.taskType}")
                task.state = TaskState.FAILED
                throw Exception("Executor not found for ${task.executionContext.taskType}")
            }
            
            // Read input files from sandbox inputs directory
            val inputsDir = File(sandboxPaths.inputs)
            val inputFiles = mutableMapOf<String, ByteArray>()
            
            if (inputsDir.exists()) {
                inputsDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        inputFiles[file.name] = file.readBytes()
                    }
                }
            }
            
            // Execute task
            val containerId = "container_${task.taskId}"
            val result = executor.execute(task, inputFiles, containerId)
            
            // Update task state based on result
            if (result.success) {
                task.state = TaskState.COMPLETED
                task.completedAt = System.currentTimeMillis()
                betaLogger?.log(TAG, "Task ${task.taskId} completed successfully")
            } else {
                task.state = TaskState.FAILED
                task.completedAt = System.currentTimeMillis()
                betaLogger?.log(TAG, "Task ${task.taskId} failed: ${result.errorMessage}")
            }
            
            return@withContext result
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Task execution failed: ${task.taskId} - ${e.message}")
            task.state = TaskState.FAILED
            task.completedAt = System.currentTimeMillis()
            throw e
        }
    }
}
```

---

### 2.4 TaskManager.kt (NEW - REFACTORED ORCHESTRATOR)

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskManager.kt`

**Responsibility**: Orchestration, task registry, keypair generation, public API

**Full Implementation** (~150 lines):

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.VirtualNode
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import kotlinx.coroutines.*
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64

/**
 * TaskManager
 * 
 * Orchestrates compute task lifecycle on a compute node.
 * 
 * Architecture:
 * - Delegates sandbox management to TaskSandboxManager
 * - Delegates file I/O to TaskInputFileManager
 * - Delegates execution to TaskExecutionCoordinator
 * - Owns task registry and generates keypairs
 * 
 * Public API:
 * - addTask() - Create new task with keypair
 * - prepareTask() - Prepare sandbox and handle input files
 * - handleTaskDataAccessUpdate() - Process incoming files
 * - getTask() / getActiveTasks() - Query tasks
 * - removeTask() - Cleanup task resources
 * - shutdown() - Cleanup all tasks
 */
class TaskManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val distributedStorageClient: DistributedStorageClient,
    private val betaLogger: BetaTestLogger? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTasks = ConcurrentHashMap<String, Task>()
    
    // Component delegation
    private val sandboxManager = TaskSandboxManager(context)
    private val fileManager = TaskInputFileManager(context, distributedStorageClient, sandboxManager, betaLogger)
    private val executionCoordinator = TaskExecutionCoordinator(context, betaLogger)
    
    companion object {
        private const val TAG = "TaskManager"
    }
    
    /**
     * Add new task for execution.
     * Generates RSA keypair and creates sandbox.
     */
    suspend fun addTask(
        executionContext: TaskExecutionContext,
        serviceLibraryEntry: ServiceLibraryEntry
    ): Task = withContext(Dispatchers.IO) {
        betaLogger?.log(TAG, "Adding task: ${executionContext.taskId}")
        
        try {
            // Generate RSA keypair for task
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()
            
            val publicKeyEncoded = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            val privateKeyBytes = keyPair.private.encoded
            
            // Create sandbox
            val sandboxPaths = sandboxManager.createSandbox(executionContext.taskId)
            
            // Create task
            val task = Task(
                taskId = executionContext.taskId,
                executionContext = executionContext,
                state = TaskState.PREPARING,
                publicKey = publicKeyEncoded,
                privateKey = privateKeyBytes,
                sandboxDir = sandboxPaths.base,
                assignedExecutor = executionContext.taskType.name
            )
            
            // Track task
            activeTasks[task.taskId] = task
            
            // Track expected input files if needed
            if (serviceLibraryEntry.hasInputFiles) {
                fileManager.trackExpectedFiles(task.taskId, executionContext.inputManifest)
            }
            
            betaLogger?.log(TAG, "Task added: ${task.taskId}")
            
            return@withContext task
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Failed to add task: ${executionContext.taskId} - ${e.message}")
            throw e
        }
    }
    
    /**
     * Prepare task for execution.
     * Checks for input files and proceeds to execution when ready.
     */
    suspend fun prepareTask(
        taskId: String,
        serviceLibraryEntry: ServiceLibraryEntry
    ) = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: run {
            betaLogger?.log(TAG, "Task not found for preparation: $taskId")
            return@withContext
        }
        
        try {
            betaLogger?.log(TAG, "Preparing task: $taskId")
            
            // Check if ready to execute
            if (fileManager.shouldProceedToExecution(task, serviceLibraryEntry)) {
                // Proceed to execution
                val sandboxPaths = sandboxManager.getSandboxPaths(taskId)
                executionCoordinator.executeTask(task, sandboxPaths, serviceLibraryEntry)
            } else {
                // Wait for input files
                task.state = TaskState.WAITING_FOR_INPUT
                betaLogger?.log(TAG, "Task $taskId waiting for input files")
            }
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Task preparation failed: $taskId - ${e.message}")
            task.state = TaskState.FAILED
            throw e
        }
    }
    
    /**
     * Handle file access update notification.
     * Retrieves file and checks execution readiness.
     */
    suspend fun handleTaskDataAccessUpdate(
        taskId: String,
        fileId: String
    ) = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: run {
            betaLogger?.log(TAG, "Task not found for access update: $taskId")
            return@withContext
        }
        
        try {
            // Retrieve and store file
            fileManager.handleFileAccessUpdate(task, fileId)
            
            // Check if ready to proceed
            // Note: Would need serviceLibraryEntry here - simplified for now
            if (task.state == TaskState.WAITING_FOR_INPUT) {
                // Resume preparation (would call prepareTask with serviceLibraryEntry)
                betaLogger?.log(TAG, "File received for task $taskId, checking readiness")
            }
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Access update failed for task $taskId: ${e.message}")
        }
    }
    
    /**
     * Get task by ID.
     */
    fun getTask(taskId: String): Task? = activeTasks[taskId]
    
    /**
     * Get all active tasks.
     */
    fun getActiveTasks(): List<Task> = activeTasks.values.toList()
    
    /**
     * Remove task and cleanup all resources.
     */
    suspend fun removeTask(taskId: String) = withContext(Dispatchers.IO) {
        val task = activeTasks.remove(taskId) ?: return@withContext
        
        betaLogger?.log(TAG, "Removing task: $taskId")
        
        // Cleanup components
        sandboxManager.cleanupSandbox(taskId)
        fileManager.cleanupTask(taskId)
        
        betaLogger?.log(TAG, "Task removed: $taskId")
    }
    
    /**
     * Shutdown manager and cleanup all tasks.
     */
    suspend fun shutdown() {
        betaLogger?.log(TAG, "Shutting down TaskManager")
        
        activeTasks.keys.forEach { taskId ->
            removeTask(taskId)
        }
        
        scope.cancel()
    }
}
```

**Dependencies**:
- All 4 files import from `com.ustadmobile.meshrabiya.service.compute.model.*`
- TaskManager imports Task, TaskState from local package
- Components are self-contained with focused dependencies

**Key Features**:
1. **Modular Architecture**: 4 focused components instead of monolithic 400-line file
2. **Lazy Executor Init**: Executors only created when first task of that type arrives
3. **FileReference Tracking**: Precise tracking of expected vs received input files
4. **Sandbox Path Caching**: Performance optimization for repeated path access
5. **Distributed Error Handling**: Components update shared Task state, throw to orchestrator for logging
6. **Preserved Public API**: All DistributedComputeServer calls remain unchanged

---

## 3. DISTRIBUTED COMPUTE SERVER

### 3.1 DistributedComputeServer.kt (NEW)

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/DistributedComputeServer.kt`

**Full Implementation**:

```kotlin
package net.ballmerlabs.meshrabiya.compute

import android.content.Context
import com.ustadmobile.meshrabiya.VirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.messages.*
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.storage.SyncPriority
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * DistributedComputeServer
 * 
 * Server-side distributed compute service.
 * Handles incoming task requests, task execution, and result delivery.
 * 
 * Extracted from IntelligentDistributedComputeService (server-side functions).
 */
class DistributedComputeServer(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val emergentRoleManager: EmergentRoleManager,
    private val taskManager: TaskManager,
    private val distributedStorageClient: DistributedStorageClient,
    private val betaLogger: BetaTestLogger? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobCount = AtomicInteger(0)
    private val completionRetryJobs = ConcurrentHashMap<String, Job>()
    
    companion object {
        private const val TAG = "DistributedComputeServer"
    }
    
    /**
     * Handle incoming compute task request broadcast.
     * Evaluate capabilities and send response if eligible.
     */
    suspend fun handleIncomingComputeTaskRequest(
        requestId: String,
        requesterNodeAddress: Int,
        request: ComputeTaskRequestMessage
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(TAG, "Received compute task request: ${request.taskId}")
        
        try {
            // Check if we have the service in our library
            val serviceEntry = findServiceLibraryEntry(request.serviceId)
            if (serviceEntry == null) {
                betaLogger?.log(TAG, "Service not found: ${request.serviceId}")
                return@withContext
            }
            
            // Verify service hash matches
            if (serviceEntry.serviceBundleHash != request.metadata["serviceHash"]) {
                betaLogger?.log(TAG, "Service hash mismatch for: ${request.serviceId}")
                return@withContext
            }
            
            // Check if we have required capabilities
            val resourceReqs = serviceEntry.resourceRequirements
            if (!hasRequiredCapabilities(resourceReqs)) {
                betaLogger?.log(TAG, "Insufficient capabilities for task: ${request.taskId}")
                return@withContext
            }
            
            // Check fitness level
            val fitnessScore = emergentRoleManager.calculateFitnessScore()
            if (fitnessScore < 0.3f) { // Minimum fitness threshold
                betaLogger?.log(TAG, "Fitness too low for task: $fitnessScore")
                return@withContext
            }
            
            // Get ML capabilities if needed
            val (mlKitFeatures, mlKitCustomSupport) = emergentRoleManager.getLocalMLCapabilitiesForResponse()
            
            // Calculate current load
            val currentLoad = activeJobCount.get().toDouble() / MeshrabiyaConstants.getMaxConcurrentTasks()
            
            // Estimate latency
            val estimatedLatencyMs = estimateLatency()
            
            // Send response
            val response = ComputeNodeResponse(
                taskId = request.taskId,
                nodeAddress = virtualNode.getLocalAddress(),
                available = true,
                currentLoad = currentLoad,
                estimatedLatencyMs = estimatedLatencyMs,
                mlKitFeatures = mlKitFeatures,
                mlKitCustomSupport = mlKitCustomSupport,
                fitnessMetric = fitnessScore,
                capabilityMetric = calculateCapabilityMetric(resourceReqs),
                availableRamMb = getAvailableRamMb(),
                availableStorageMb = getAvailableStorageMb()
            )
            
            val responseMessage = ComputeNodeResponseMessage(
                requestId = requestId,
                response = response
            )
            
            virtualNode.sendDirectMessage(requesterNodeAddress, responseMessage.toBytes())
            
            betaLogger?.log(TAG, "Sent compute node response for task: ${request.taskId}")
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Error handling compute request: ${e.message}")
        }
    }
    
    /**
     * Handle task assignment message from client.
     * Accepts task, creates via TaskManager, and starts preparation.
     */
    suspend fun handleTaskAssignmentMessage(
        senderAddress: Int,
        assignment: TaskAssignmentMessage
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(TAG, "Received task assignment: ${assignment.taskId}")
        
        try {
            // Get service library entry
            val serviceEntry = findServiceLibraryEntry(assignment.metadata?.get("serviceId") as? String ?: "")
            if (serviceEntry == null) {
                sendTaskRejection(senderAddress, assignment, "Service not found")
                return@withContext
            }
            
            // Validate resources still available
            if (!hasRequiredCapabilities(serviceEntry.resourceRequirements)) {
                sendTaskRejection(senderAddress, assignment, "Insufficient resources")
                return@withContext
            }
            
            // Create execution context from assignment
            val executionContext = TaskExecutionContext(
                taskId = assignment.taskId,
                taskType = TaskType.valueOf(assignment.taskType),
                jobType = JobType.valueOf(assignment.jobType),
                codeBundle = serviceEntry.codeBundle,
                inputManifest = assignment.inputFiles.map { parseFileReference(it) },
                requesterNodeId = assignment.requesterNodeId,
                callbackAddress = senderAddress.toString(),
                accessScope = AccessScope.TASK_ISOLATED
            )
            
            // Add task via TaskManager
            val task = taskManager.addTask(executionContext, serviceEntry)
            
            // Increment active job count
            activeJobCount.incrementAndGet()
            
            // Send acceptance message
            sendTaskAcceptance(senderAddress, task)
            
            // Start preparation phase
            scope.launch {
                try {
                    taskManager.prepareTask(task.taskId, serviceEntry)
                    
                    // After execution completes, send completion message
                    val completedTask = taskManager.getTask(task.taskId)
                    if (completedTask != null) {
                        sendTaskCompletion(senderAddress, completedTask, serviceEntry)
                    }
                    
                } catch (e: Exception) {
                    betaLogger?.log(TAG, "Task preparation/execution failed: ${e.message}")
                    
                    // Send failure completion message
                    val failedTask = taskManager.getTask(task.taskId)
                    if (failedTask != null) {
                        failedTask.state = TaskState.FAILED
                        sendTaskCompletion(senderAddress, failedTask, serviceEntry)
                    }
                } finally {
                    activeJobCount.decrementAndGet()
                }
            }
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Error handling task assignment: ${e.message}")
            sendTaskRejection(senderAddress, assignment, "Internal error: ${e.message}")
        }
    }
    
    /**
     * Send task acceptance message to client.
     */
    private suspend fun sendTaskAcceptance(
        requesterAddress: Int,
        task: Task
    ) {
        val acceptanceMessage = TaskAcceptanceMessage(
            taskId = task.taskId,
            publicKey = task.publicKey,
            computeNodeAddress = virtualNode.getLocalAddress().toString()
        )
        
        virtualNode.sendDirectMessage(requesterAddress, acceptanceMessage.toBytes())
        
        betaLogger?.log(TAG, "Sent task acceptance: ${task.taskId}")
    }
    
    /**
     * Send task rejection message to client.
     */
    private suspend fun sendTaskRejection(
        requesterAddress: Int,
        assignment: TaskAssignmentMessage,
        reason: String
    ) {
        // Create rejection message (assuming it exists)
        betaLogger?.log(TAG, "Sending task rejection: ${assignment.taskId} - $reason")
        // TODO: Implement TaskRejectionMessage if needed
    }
    
    /**
     * Send task completion message to client with retry logic.
     */
    private suspend fun sendTaskCompletion(
        requesterAddress: Int,
        task: Task,
        serviceEntry: ServiceLibraryEntry
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(TAG, "Sending task completion: ${task.taskId}")
        
        try {
            // Collect output files if task has output
            val outputManifest = if (serviceEntry.hasOutputFiles && task.state == TaskState.COMPLETED) {
                collectAndStoreOutputFiles(task)
            } else {
                emptyList()
            }
            
            // Create completion message
            val completionMessage = TaskCompletedMessage(
                taskId = task.taskId,
                executorNodeId = virtualNode.getLocalAddress().toString(),
                status = if (task.state == TaskState.COMPLETED) "SUCCESS" else "FAILED",
                executionStats = ExecutionStats(
                    executionTimeMs = (task.completedAt ?: System.currentTimeMillis()) - (task.startedAt ?: task.createdAt),
                    cpuTimeMs = 0L, // TODO: Track actual CPU time
                    memoryPeakBytes = 0L, // TODO: Track actual memory
                    diskReadBytes = 0L,
                    diskWriteBytes = 0L
                ),
                executionError = if (task.state == TaskState.FAILED) {
                    ExecutionError(
                        errorType = "EXECUTION_FAILED",
                        errorMessage = "Task execution failed",
                        errorCode = 1
                    )
                } else null,
                resultStorageRefs = outputManifest.map { it.fileId }
            )
            
            // Send completion message
            virtualNode.sendDirectMessage(requesterAddress, completionMessage.toBytes())
            
            // Start retry loop
            startCompletionRetryLoop(task.taskId, requesterAddress, completionMessage)
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Error sending task completion: ${e.message}")
        }
    }
    
    /**
     * Collect output files and store to distributed storage.
     * Returns list of FileReferences for output manifest.
     */
    private suspend fun collectAndStoreOutputFiles(task: Task): List<FileReference> {
        val outputFiles = mutableListOf<FileReference>()
        
        try {
            val outputsDir = File(task.sandboxDir, "outputs")
            if (!outputsDir.exists()) return emptyList()
            
            outputsDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    // Store file to distributed storage with task permissions
                    val fileRef = distributedStorageClient.storeFile(
                        path = file.name,
                        data = file.readBytes(),
                        priority = SyncPriority.HIGH,
                        owner = task.executionContext.requesterNodeId,
                        recipients = listOf(
                            RecipientEntry(
                                publicKey = task.executionContext.requesterNodeId,
                                recipientType = RecipientType.USER
                            ),
                            RecipientEntry(
                                publicKey = task.publicKey,
                                recipientType = RecipientType.TASK,
                                taskId = task.taskId
                            )
                        )
                    )
                    
                    if (fileRef != null) {
                        outputFiles.add(fileRef)
                        betaLogger?.log(TAG, "Stored output file: ${file.name} -> ${fileRef.fileId}")
                    }
                }
            }
            
        } catch (e: Exception) {
            betaLogger?.log(TAG, "Error collecting output files: ${e.message}")
        }
        
        return outputFiles
    }
    
    /**
     * Start retry loop for task completion notification.
     */
    private fun startCompletionRetryLoop(
        taskId: String,
        requesterAddress: Int,
        completionMessage: TaskCompletedMessage
    ) {
        val retryJob = scope.launch {
            val retryInterval = MeshrabiyaConstants.getTaskCompletionRetryIntervalMs()
            val retryPeriod = MeshrabiyaConstants.getTaskCompletionRetryPeriodMs()
            val maxRetries = (retryPeriod / retryInterval).toInt()
            
            var retryCount = 0
            while (retryCount < maxRetries && isActive) {
                delay(retryInterval)
                
                // Resend completion message
                virtualNode.sendDirectMessage(requesterAddress, completionMessage.toBytes())
                betaLogger?.log(TAG, "Retry $retryCount: Sent completion for task $taskId")
                
                retryCount++
            }
            
            if (retryCount >= maxRetries) {
                betaLogger?.log(TAG, "Task $taskId completion notification failed after $retryCount retries")
                cleanupTask(taskId)
            }
        }
        
        completionRetryJobs[taskId] = retryJob
    }
    
    /**
     * Handle task completion acknowledgment from client.
     * Stops retry loop and cleans up resources.
     */
    suspend fun handleTaskCompletionAckMessage(
        senderAddress: Int,
        ack: TaskCompletionAckMessage
    ) {
        betaLogger?.log(TAG, "Received completion ACK for task: ${ack.taskId}")
        
        // Stop retry loop
        completionRetryJobs[ack.taskId]?.cancel()
        completionRetryJobs.remove(ack.taskId)
        
        // Cleanup task
        cleanupTask(ack.taskId)
    }
    
    /**
     * Handle file access update notification.
     * Routes to TaskManager for processing.
     */
    suspend fun handleTaskDataAccessUpdate(
        fileAccessUpdate: FileAccessUpdateConfirmation
    ) {
        betaLogger?.log(TAG, "Received file access update for file: ${fileAccessUpdate.fileId}")
        
        // Check if any active task needs this file
        taskManager.getActiveTasks().forEach { task ->
            if (task.state == TaskState.WAITING_FOR_INPUT) {
                // Pass to TaskManager to handle
                taskManager.handleTaskDataAccessUpdate(task.taskId, fileAccessUpdate.fileId)
            }
        }
    }
    
    /**
     * Cleanup task resources.
     */
    private suspend fun cleanupTask(taskId: String) {
        betaLogger?.log(TAG, "Cleaning up task: $taskId")
        taskManager.removeTask(taskId)
    }
    
    // === Helper Methods ===
    
    private fun findServiceLibraryEntry(serviceId: String): ServiceLibraryEntry? {
        // TODO: Implement service library lookup
        // For now, return null (would integrate with ServiceLibraryManager)
        return null
    }
    
    private fun hasRequiredCapabilities(requirements: ResourceRequirements): Boolean {
        // TODO: Check actual system resources against requirements
        return true
    }
    
    private fun calculateCapabilityMetric(requirements: ResourceRequirements): Double {
        // TODO: Calculate how well this node matches required capabilities
        return 1.0
    }
    
    private fun getAvailableRamMb(): Long {
        // TODO: Get actual available RAM
        return 1024L
    }
    
    private fun getAvailableStorageMb(): Long {
        // TODO: Get actual available storage
        return 10240L
    }
    
    private fun estimateLatency(): Long {
        // TODO: Estimate execution latency based on current load
        return 1000L
    }
    
    private fun parseFileReference(fileMap: Map<String, String>): FileReference {
        return FileReference(
            fileId = fileMap["fileId"] ?: "",
            fileName = fileMap["fileName"] ?: "",
            sizeBytes = fileMap["sizeBytes"]?.toLongOrNull() ?: 0L
        )
    }
    
    /**
     * Shutdown server and cleanup all tasks.
     */
    suspend fun shutdown() {
        betaLogger?.log(TAG, "Shutting down DistributedComputeServer")
        
        // Cancel all retry jobs
        completionRetryJobs.values.forEach { it.cancel() }
        completionRetryJobs.clear()
        
        scope.cancel()
    }
}
```

**Dependencies**:
- Import all message types from messages package
- Import TaskManager from local package
- Import storage client and models

**Key Features**:
1. **Request Handling**: Evaluates eligibility and sends ComputeNodeResponse
2. **Task Assignment**: Creates tasks via TaskManager, sends acceptance
3. **Execution Orchestration**: Coordinates with TaskManager for execution
4. **Output Storage**: Stores result files with TASK permissions
5. **Completion Retry**: Implements retry loop with timeout (5 min)
6. **ACK Handling**: Stops retry and cleans up on acknowledgment

---

## 4. INTEGRATION UPDATES

### 4.1 MeshEcosystemListener.kt Updates

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/service/MeshEcosystemListener.kt`

**Changes Required**:

1. **Add Client/Server properties** (after line 79):

```kotlin
private var computeClient: DistributedComputeClient? = null
private var computeServer: DistributedComputeServer? = null

fun registerComputeClient(client: DistributedComputeClient) {
    computeClient = client
}

fun registerComputeServer(server: DistributedComputeServer) {
    computeServer = server
}
```

2. **Update ComputeNodeResponseMessage routing** (lines 162-167):

```kotlin
is ComputeNodeResponseMessage -> {
    // Route to CLIENT (client receives responses from potential compute nodes)
    message.requestId?.let { requestId ->
        computeClient?.handleComputeNodeResponse(requestId, senderId, message.response)
    }
}
```

3. **Update ComputeTaskRequestMessage routing** (lines 171-177):

```kotlin
is ComputeTaskRequestMessage -> {
    // Route to SERVER (server handles incoming task requests)
    if (currentRoles.contains(MeshRole.COMPUTE_NODE)) {
        val requestId = message.taskId
        computeServer?.handleIncomingComputeTaskRequest(requestId, senderId, message)
    }
}
```

4. **Add TaskAcceptanceMessage routing** (new):

```kotlin
is TaskAcceptanceMessage -> {
    // Route to CLIENT (client receives acceptance from compute node)
    computeClient?.handleTaskAcceptanceMessage(message)
}
```

5. **Add TaskCompletedMessage routing** (new):

```kotlin
is TaskCompletedMessage -> {
    // Route to CLIENT (client receives completion from compute node)
    computeClient?.handleTaskCompletionMessage(message)
}
```

6. **Add TaskCompletionAckMessage routing** (new):

```kotlin
is TaskCompletionAckMessage -> {
    // Route to SERVER (server receives ACK from client)
    computeServer?.handleTaskCompletionAckMessage(senderId, message)
}
```

7. **Add FileAccessUpdateConfirmation routing** (new):

```kotlin
is FileAccessUpdateConfirmation -> {
    // Route to SERVER for task data updates
    computeServer?.handleTaskDataAccessUpdate(message)
}
```

---

### 4.2 VirtualNode.kt Updates

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/vnet/VirtualNode.kt`

**Changes Required**:

1. **Comment out IntelligentDistributedComputeService** (lines 402-416):

```kotlin
// DEPRECATED: Split into DistributedComputeClient and DistributedComputeServer
// protected val intelligentDistributedComputeService: IntelligentDistributedComputeService by lazy {
//     IntelligentDistributedComputeService(
//         virtualNode = this,
//         emergentRoleManager = emergentRoleManager,
//         betaLogger = BetaTestLogger.getInstance(
//             getContext() ?: throw IllegalStateException("Context required")
//         )
//     )
// }
```

2. **Add TaskManager instantiation** (new):

```kotlin
protected val taskManager: TaskManager by lazy {
    TaskManager(
        context = getContext() ?: throw IllegalStateException("Context required"),
        virtualNode = this,
        distributedStorageClient = getDistributedStorageManager()?.getDistributedStorageClient()
            ?: throw IllegalStateException("DistributedStorageClient required"),
        betaLogger = BetaTestLogger.getInstance(
            getContext() ?: throw IllegalStateException("Context required")
        )
    )
}
```

3. **Add DistributedComputeClient instantiation** (new):

```kotlin
protected val distributedComputeClient: DistributedComputeClient by lazy {
    DistributedComputeClient(
        context = getContext() ?: throw IllegalStateException("Context required"),
        virtualNode = this,
        betaLogger = BetaTestLogger.getInstance(
            getContext() ?: throw IllegalStateException("Context required")
        )
    )
}
```

4. **Add DistributedComputeServer instantiation** (new):

```kotlin
protected val distributedComputeServer: DistributedComputeServer by lazy {
    DistributedComputeServer(
        context = getContext() ?: throw IllegalStateException("Context required"),
        virtualNode = this,
        emergentRoleManager = emergentRoleManager,
        taskManager = taskManager,
        distributedStorageClient = getDistributedStorageManager()?.getDistributedStorageClient()
            ?: throw IllegalStateException("DistributedStorageClient required"),
        betaLogger = BetaTestLogger.getInstance(
            getContext() ?: throw IllegalStateException("Context required")
        )
    )
}
```

5. **Update service registration** (in init or startup):

```kotlin
// Register compute client and server with ecosystem listener
meshEcosystemListener.registerComputeClient(distributedComputeClient)
meshEcosystemListener.registerComputeServer(distributedComputeServer)
```

6. **Update accessor methods** (lines 858-863):

```kotlin
// Comment out old accessor
// fun getIntelligentDistributedComputeService(): IntelligentDistributedComputeService = intelligentDistributedComputeService

// Add new accessors
fun getDistributedComputeClient(): DistributedComputeClient = distributedComputeClient
fun getDistributedComputeServer(): DistributedComputeServer = distributedComputeServer
fun getTaskManager(): TaskManager = taskManager
```

---

### 4.3 EmergentRoleManager.kt Updates

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/vnet/EmergentRoleManager.kt`

**Add Missing Method**:

```kotlin
/**
 * Get local ML capabilities for compute node response.
 * Returns tuple of (MLKit features, custom ML support).
 */
fun getLocalMLCapabilitiesForResponse(): Pair<List<String>, Boolean> {
    // Check device capability manager for ML features
    val mlKitFeatures = deviceCapabilityManager?.getMLKitFeatures() ?: emptyList()
    
    // Check if custom ML models are supported
    val customSupport = deviceCapabilityManager?.supportsCustomMLModels() ?: false
    
    return Pair(mlKitFeatures, customSupport)
}
```

**Note**: This assumes DeviceCapabilityManager has these methods. If not, return empty/false defaults.

---

## 5. FILE RENAMING & RECREATION

### 5.1 Bash Renaming Commands

Execute these commands from project root:

```bash
# Rename existing files to .md for preservation
cd /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya

# Rename IntelligentDistributedComputeService
mv compute/IntelligentDistributedComputeService.kt compute/IntelligentDistributedComputeService.md

# Rename executors
cd service/compute/executor
mv JVMExecutor.kt JVMExecutor.md
mv JSExecutor.kt JSExecutor.md
mv MLNativeExecutor.kt MLNativeExecutor.md

# Rename SandboxStorageProxy
cd ../../security
mv SandboxStorageProxy.kt SandboxStorageProxy.md
```

### 5.2 Recreated Files

All renamed files will be recreated from scratch with complete implementations based on Part 1 specifications. The .md files serve as documentation/reference of the old implementation.

**Files to Recreate**:
1. `JVMExecutor.kt` - Implement TaskExecutor interface, use Part 1 spec
2. `JSExecutor.kt` - Implement TaskExecutor interface, use Part 1 spec
3. `MLNativeExecutor.kt` - Implement TaskExecutor interface, use Part 1 spec
4. `SandboxStorageProxy.kt` - Simplified API matching TaskManager needs

**Note**: These will be created in separate implementation phase after renaming.

---

## 6. VALIDATION CHECKLIST

### Core Classes
- [ ] Create TaskSandboxManager.kt (~110 lines)
- [ ] Create TaskInputFileManager.kt (~95 lines)
- [ ] Create TaskExecutionCoordinator.kt (~110 lines)
- [ ] Create TaskManager.kt refactored orchestrator (~150 lines)
- [ ] Create DistributedComputeServer.kt with all handlers
- [ ] Verify all imports resolve correctly

### Integration
- [ ] Update MeshEcosystemListener with Client/Server routing
- [ ] Update VirtualNode instantiation and registration
- [ ] Add EmergentRoleManager.getLocalMLCapabilitiesForResponse()
- [ ] Verify message routing flows correctly

### File Management
- [ ] Rename IntelligentDistributedComputeService.kt to .md
- [ ] Rename executor files to .md
- [ ] Rename SandboxStorageProxy.kt to .md
- [ ] Recreate all 4 renamed files from scratch

### Build
- [ ] Run `./tools/brace_paren_check.sh` on all 4 TaskManager component files
- [ ] Run `./tools/brace_paren_check.sh` on DistributedComputeServer.kt
- [ ] Compile Meshrabiya library module
- [ ] Fix any import errors
- [ ] Verify no syntax errors

### Testing
- [ ] Test task submission flow (Client → Server)
- [ ] Test task execution (TaskManager → Executor)
- [ ] Test completion notification with retry
- [ ] Test file access update handling
- [ ] Verify sandbox isolation works
- [ ] Test FileReference tracking (expected vs received files)
- [ ] Test lazy executor initialization
- [ ] Test sandbox path caching

---

## 7. IMPLEMENTATION ORDER

### Phase 1: Core Components Creation (Day 1 Morning)
1. Create TaskSandboxManager.kt (~110 lines)
2. Create TaskInputFileManager.kt (~95 lines)
3. Create TaskExecutionCoordinator.kt (~110 lines)
4. Run structural validation on all 3 components

### Phase 2: Orchestrator Creation (Day 1 Afternoon)
1. Create TaskManager.kt refactored orchestrator (~150 lines)
2. Create DistributedComputeServer.kt (~500 lines)
3. Run structural validation on both files
4. Verify all imports resolve

### Phase 3: Integration (Day 1-2)
1. Update MeshEcosystemListener routing
2. Update VirtualNode instantiation
3. Add EmergentRoleManager method
4. Test message routing

### Phase 4: File Recreation (Day 2)
1. Rename old files to .md via bash
2. Recreate JVMExecutor.kt from Part 1 spec
3. Recreate JSExecutor.kt from Part 1 spec
4. Recreate MLNativeExecutor.kt from Part 1 spec
5. Recreate SandboxStorageProxy.kt

### Phase 5: Testing & Validation (Day 2-3)
1. Unit test TaskSandboxManager (path caching, cleanup)
2. Unit test TaskInputFileManager (FileReference tracking)
3. Unit test TaskExecutionCoordinator (lazy executors)
4. Integration test TaskManager lifecycle
5. Integration test full workflow
6. Test retry mechanisms
7. Validate sandbox isolation
8. Performance testing

---

## 9. TASKMANAGER REFACTORING STRATEGY

### 9.1 Rationale for Refactoring

The original TaskManager.kt design (~400 lines) combines multiple concerns:
1. **Task Lifecycle Management** - addTask(), getTask(), removeTask()
2. **Sandbox Management** - directory creation, cleanup, file I/O
3. **Executor Coordination** - routing to JVM/JS/ML executors
4. **Input File Handling** - retrieval, decryption, placement
5. **State Machine Logic** - PREPARING → WAITING_FOR_INPUT → EXECUTING → COMPLETED/FAILED

**Benefits of Refactoring:**
- Easier to edit individual components (smaller files, clearer boundaries)
- Better separation of concerns (SRP compliance)
- Improved testability (can test sandbox logic separately from execution)
- Simpler future enhancements (e.g., adding new executor types)
- Reduced merge conflicts in collaborative development

### 9.2 Proposed Component Structure

Refactor TaskManager into 4 focused components:

#### **Component 1: TaskManager.kt (~150 lines)**
**Responsibility**: Core task lifecycle orchestration
- Task registry (ConcurrentHashMap)
- Public API: addTask(), prepareTask(), getTask(), getActiveTasks(), removeTask(), shutdown()
- Delegates to other components for actual work
- Minimal business logic

#### **Component 2: TaskSandboxManager.kt (~100 lines)**
**Responsibility**: Sandbox filesystem management
- createSandbox(taskId) - creates task_sandboxes/{taskId}/inputs and /outputs directories
- cleanupSandbox(taskId) - recursively deletes sandbox directory
- getSandboxPaths(taskId) - returns SandboxPaths(base, inputs, outputs)
- writeInputFile(taskId, fileName, data)
- readOutputFiles(taskId) - returns Map<String, ByteArray>

#### **Component 3: TaskExecutionCoordinator.kt (~100 lines)**
**Responsibility**: Executor selection and task execution
- Manages executor registry (TaskType → TaskExecutor)
- executeTask(task, serviceLibraryEntry) - routes to appropriate executor
- Handles execution state transitions (EXECUTING → COMPLETED/FAILED)
- Collects execution results

#### **Component 4: TaskInputFileManager.kt (~80 lines)**
**Responsibility**: Input file retrieval and processing
- handleFileAccessUpdate(task, fileId) - retrieves file from storage
- decryptInputFile(encryptedBytes, privateKey) - decrypts with task keypair
- trackExpectedFiles(task, inputManifest) - tracks which files still needed
- shouldProceedToExecution(task) - checks if all input files received

### 9.3 Component Interactions

```
TaskManager (Orchestrator)
    ├── TaskSandboxManager (Filesystem)
    │   └── Creates/cleans sandbox directories
    ├── TaskInputFileManager (File I/O)
    │   └── Retrieves files from storage → writes to sandbox
    └── TaskExecutionCoordinator (Execution)
        └── Routes to JVM/JS/ML executors → updates task state
```

**Flow Example (with input files):**
1. `TaskManager.addTask()` → creates Task object
2. `TaskManager.prepareTask()` →
   - `TaskSandboxManager.createSandbox()`
   - If hasInputFiles: set state to WAITING_FOR_INPUT
3. `TaskManager.handleTaskDataAccessUpdate()` →
   - `TaskInputFileManager.handleFileAccessUpdate()` → retrieves file
   - `TaskSandboxManager.writeInputFile()` → writes to inputs/
   - `TaskInputFileManager.shouldProceedToExecution()` → checks if ready
   - If ready: call `prepareTask()` again to continue
4. `TaskManager.prepareTask()` (resumed) →
   - Set state to EXECUTING
   - `TaskExecutionCoordinator.executeTask()` → runs executor
5. Completion: `TaskSandboxManager.cleanupSandbox()`

### 9.4 File Locations

All files in: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/`

**New Files:**
- `TaskManager.kt` - Core orchestrator (refactored)
- `TaskSandboxManager.kt` - Sandbox management (NEW)
- `TaskExecutionCoordinator.kt` - Executor coordination (NEW)
- `TaskInputFileManager.kt` - Input file handling (NEW)

### 9.5 Implementation Priority

**Phase A: Create Core Components (Day 1 Morning)**
1. Create `TaskSandboxManager.kt` (~100 lines)
2. Create `TaskInputFileManager.kt` (~80 lines)
3. Create `TaskExecutionCoordinator.kt` (~100 lines)
4. Run structural validation on all 3

**Phase B: Create Orchestrator (Day 1 Afternoon)**
1. Create `TaskManager.kt` (~150 lines) - delegates to Phase A components
2. Run structural validation
3. Verify all imports resolve

**Phase C: Integration Testing (Day 1 Evening)**
1. Test task lifecycle without input files
2. Test task lifecycle with input files
3. Test executor routing (JVM/JS/ML)
4. Test sandbox isolation

### 9.6 Updated Validation Checklist

**Core Classes (Updated):**
- [ ] Create TaskSandboxManager.kt
- [ ] Create TaskInputFileManager.kt
- [ ] Create TaskExecutionCoordinator.kt
- [ ] Create TaskManager.kt (refactored orchestrator)
- [ ] Create DistributedComputeServer.kt
- [ ] Verify all imports resolve correctly

**All other sections remain unchanged.**

---

## 8. KNOWN GAPS & FUTURE WORK

### ServiceLibraryManager

### ServiceLibraryManager
- Currently no centralized service library management
- findServiceLibraryEntry() returns null
- **TODO**: Create ServiceLibraryManager class or use built-in list

### FileAccessUpdateConfirmation Message
- Assumed to exist but not verified in research
- May need to create if doesn't exist
- **TODO**: Verify or create message type

### Resource Tracking
- ExecutionStats currently returns zero/stub values
- **TODO**: Implement actual CPU/memory tracking
- May require platform-specific code

### Sandbox Enhancement
- Current implementation uses basic filesystem isolation
- **TODO**: Enhance with resource limits (CPU, memory quotas)
- Consider Android-specific sandboxing features

### Service Discovery
- I2PServiceRegistry exists but not integrated
- **TODO**: Integrate service discovery into library management

---

## SUMMARY

Part 2 completes the distributed compute infrastructure by:

1. **Creating TaskManager (Refactored)** - Modular orchestrator split into 4 components
2. **Creating DistributedComputeServer** - Server-side request handling
3. **Splitting IntelligentDistributedComputeService** - Clean Client/Server separation
4. **Integrating Components** - Updated routing, instantiation, registration
5. **Recreating Executors** - Fresh implementations with TaskExecutor interface

**Refactoring Benefits:**
- TaskManager split into 4 focused files (~465 total lines vs ~400 monolithic)
- Easier editing and independent testing per component
- Clear separation of concerns (sandbox, execution, file I/O, orchestration)
- Better maintainability and future extensibility
- FileReference tracking for precise input file management
- Lazy executor initialization for memory efficiency
- Sandbox path caching for performance optimization

Combined with Part 1, this provides complete implementation of Phases 5-7 (Add Task, Task Manager Receives Access Update, Completed Task workflows).

**Total New Code**: ~2200 lines  
**Files Created**: 7 (4 TaskManager components + DistributedComputeServer + Task.kt + ExecutionResult.kt)  
**Files Refactored**: 3 (MeshEcosystemListener, VirtualNode, EmergentRoleManager)  
**Files Recreated**: 4 (JVMExecutor, JSExecutor, MLNativeExecutor, SandboxStorageProxy)

**Implementation Time Estimate**: 2-3 days with testing

---

**END OF PART 2**
