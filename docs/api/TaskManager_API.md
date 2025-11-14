# TaskManager API Documentation

**Package**: `org.torproject.meshrabiya.compute`  
**Version**: 1.0.0  
**Status**: Stable  
**Last Updated**: November 14, 2025

---

## Overview

The `TaskManager` coordinates distributed task execution across the Meshrabiya mesh network. It handles task submission, scheduling, keypair management, execution lifecycle, and result collection with full support for per-task encryption keypairs.

### Key Features

- **Task Lifecycle Management**: Submit, schedule, execute, monitor, and cleanup
- **Per-Task Keypairs**: Isolated encryption for each task (feature-flagged)
- **Intelligent Scheduling**: Task decomposition and optimal node assignment
- **Resource Management**: Memory limits, CPU quotas, network restrictions
- **Fault Tolerance**: Automatic retry and failover on node failures

---

## Table of Contents

1. [Core API](#core-api)
2. [Task Operations](#task-operations)
3. [Keypair Management](#keypair-management)
4. [Task Scheduling](#task-scheduling)
5. [Task Monitoring](#task-monitoring)
6. [Error Handling](#error-handling)
7. [Usage Examples](#usage-examples)
8. [Best Practices](#best-practices)
9. [Integration Patterns](#integration-patterns)

---

## Core API

### Class: TaskManager

```kotlin
/**
 * Manages distributed task execution and keypair lifecycle
 * 
 * This class provides the primary interface for submitting tasks,
 * generating per-task keypairs, monitoring execution, and collecting results.
 *
 * Thread Safety: All methods are thread-safe and can be called concurrently
 * 
 * @property scheduler Task scheduler for node assignment
 * @property storageManager Distributed storage for input/output files
 * @property computeEngine Strangers-safe compute execution engine
 */
class TaskManager(
    private val scheduler: IntelligentTaskScheduler,
    private val storageManager: DistributedStorageManager,
    private val computeEngine: StrangersSafeComputeEngine
) {
    // API methods documented below
}
```

### Initialization

```kotlin
/**
 * Initialize the task manager
 * 
 * Sets up task registry, starts background monitoring, and initializes
 * keypair cleanup scheduler.
 *
 * @throws TaskManagerException if initialization fails
 */
suspend fun initialize()

/**
 * Shutdown the task manager gracefully
 * 
 * Cancels all running tasks, flushes pending operations, and cleans up
 * expired keypairs.
 */
suspend fun shutdown()
```

**Example**:
```kotlin
val taskManager = TaskManager(
    scheduler = IntelligentTaskScheduler(meshNetwork),
    storageManager = storageManager,
    computeEngine = computeEngine
)

try {
    taskManager.initialize()
    // ... use task manager
} finally {
    taskManager.shutdown()
}
```

---

## Task Operations

### Submit Task

```kotlin
/**
 * Submit a task for distributed execution
 * 
 * The task is registered, scheduled to an appropriate node, and queued
 * for execution. If task keypair feature is enabled, a keypair will be
 * generated automatically.
 *
 * @param task Task specification including executable, inputs, and resources
 * @return Unique task ID for monitoring and result retrieval
 * @throws TaskSubmissionException if submission fails
 * @throws InvalidTaskException if task specification is invalid
 * @throws InsufficientResourcesException if no nodes available
 *
 * Performance: O(1) submission + O(log N) scheduling
 * Feature Flag: Per-task keypairs controlled by FeatureFlags.isTaskKeypairEnabled()
 */
suspend fun submitTask(task: Task): String

/**
 * Task specification
 *
 * @property taskId Unique identifier (auto-generated if not provided)
 * @property executable Executable specification (runtime, code, entry point)
 * @property inputFiles List of input file IDs from distributed storage
 * @property resourceLimits Resource constraints (memory, CPU, timeout)
 * @property owner Node ID of task owner
 * @property priority Task priority (0-10, higher = more priority)
 */
data class Task(
    val taskId: String = UUID.randomUUID().toString(),
    val executable: Executable,
    val inputFiles: List<String> = emptyList(),
    val resourceLimits: ResourceLimits,
    val owner: String,
    val priority: Int = 5
)
```

**Example**:
```kotlin
val task = Task(
    executable = Executable(
        runtime = RuntimeType.PYTHON,
        code = """
            with open('/sandbox/input/input.txt', 'r') as f:
                data = f.read()
            with open('/sandbox/output/output.txt', 'w') as f:
                f.write(data.upper())
        """.trimIndent(),
        entryPoint = "main"
    ),
    inputFiles = listOf("550e8400-e29b-41d4-a716-446655440000"),
    resourceLimits = ResourceLimits(
        maxMemoryMB = 128,
        maxCpuCores = 1,
        timeoutSeconds = 30
    ),
    owner = "node-alice",
    priority = 7
)

val taskId = taskManager.submitTask(task)
println("Task submitted: $taskId")
```

### Get Task Status

```kotlin
/**
 * Get current status of a task
 *
 * @param taskId Unique task identifier
 * @return TaskStatus containing current state and execution details
 * @throws TaskNotFoundException if task doesn't exist
 */
suspend fun getTaskStatus(taskId: String): TaskStatus

/**
 * Task execution status
 *
 * @property taskId The task identifier
 * @property state Current execution state
 * @property assignedNode Node ID where task is/was executed
 * @property progress Execution progress (0.0-1.0)
 * @property startTime Execution start timestamp (ISO-8601)
 * @property endTime Execution end timestamp (null if running)
 * @property error Error message if task failed
 */
data class TaskStatus(
    val taskId: String,
    val state: TaskState,
    val assignedNode: String?,
    val progress: Double,
    val startTime: String?,
    val endTime: String?,
    val error: String?
)

enum class TaskState {
    SUBMITTED,      // Task registered, waiting for scheduling
    SCHEDULED,      // Assigned to node, waiting for execution
    RUNNING,        // Currently executing
    COMPLETED,      // Execution completed successfully
    FAILED,         // Execution failed
    CANCELLED       // Task was cancelled
}
```

**Example**:
```kotlin
val status = taskManager.getTaskStatus(taskId)

println("Task: $taskId")
println("State: ${status.state}")
println("Progress: ${(status.progress * 100).toInt()}%")
println("Assigned Node: ${status.assignedNode ?: "Not assigned"}")

when (status.state) {
    TaskState.COMPLETED -> {
        println("Task completed successfully")
        val result = taskManager.getTaskResult(taskId)
        // ... process result
    }
    TaskState.FAILED -> {
        println("Task failed: ${status.error}")
    }
    else -> {
        println("Task still in progress...")
    }
}
```

### Get Task Result

```kotlin
/**
 * Get task execution result
 * 
 * Retrieves output files and execution metadata. Only accessible by
 * task owner.
 *
 * @param taskId Unique task identifier
 * @param requesterId Node ID requesting result (must be owner)
 * @return TaskResult containing output files and execution stats
 * @throws TaskNotFoundException if task doesn't exist
 * @throws UnauthorizedException if requester is not owner
 * @throws TaskNotCompleteException if task is not in COMPLETED state
 */
suspend fun getTaskResult(
    taskId: String,
    requesterId: String
): TaskResult

/**
 * Task execution result
 *
 * @property taskId The task identifier
 * @property outputFiles List of output file IDs in distributed storage
 * @property executionTime Total execution time in milliseconds
 * @property resourceUsage Actual resource consumption during execution
 * @property logs Execution logs (stdout/stderr)
 */
data class TaskResult(
    val taskId: String,
    val outputFiles: List<String>,
    val executionTime: Long,
    val resourceUsage: ResourceUsage,
    val logs: String
)
```

**Example**:
```kotlin
try {
    val result = taskManager.getTaskResult(
        taskId = taskId,
        requesterId = "node-alice"
    )
    
    println("Execution time: ${result.executionTime}ms")
    println("Output files: ${result.outputFiles.size}")
    println("Memory used: ${result.resourceUsage.peakMemoryMB}MB")
    
    // Retrieve output files
    for (fileId in result.outputFiles) {
        val fileData = storageManager.retrieveFile(fileId, "node-alice")
        println("Output: ${String(fileData)}")
    }
    
} catch (e: TaskNotCompleteException) {
    println("Task not yet complete, wait and retry")
}
```

### Cancel Task

```kotlin
/**
 * Cancel a running or scheduled task
 * 
 * Stops task execution, cleans up resources, and removes task keypair.
 *
 * @param taskId Unique task identifier
 * @param requesterId Node ID requesting cancellation (must be owner)
 * @throws TaskNotFoundException if task doesn't exist
 * @throws UnauthorizedException if requester is not owner
 * @throws TaskNotCancellableException if task already completed
 */
suspend fun cancelTask(
    taskId: String,
    requesterId: String
)
```

**Example**:
```kotlin
try {
    taskManager.cancelTask(
        taskId = taskId,
        requesterId = "node-alice"
    )
    println("Task cancelled successfully")
} catch (e: TaskNotCancellableException) {
    println("Task already completed, cannot cancel")
}
```

---

## Keypair Management

### Generate Task Keypair

```kotlin
/**
 * Generate a keypair for a task (if feature flag enabled)
 * 
 * Creates a unique PGP keypair for the task to enable isolated file access.
 * The keypair has a TTL and will be automatically cleaned up after task
 * completion.
 *
 * @param taskId Unique task identifier
 * @param ttlSeconds Time-to-live for keypair (default: 3600s = 1 hour)
 * @return TaskKeypair containing public/private key pair
 * @throws TaskNotFoundException if task doesn't exist
 * @throws UnsupportedOperationException if feature flag is disabled
 * @throws KeypairGenerationException if keypair generation fails
 *
 * Performance: ~450ms on mobile devices, ~200ms on servers
 * Feature Flag: Requires FeatureFlags.isTaskKeypairEnabled() = true
 */
suspend fun generateTaskKeypair(
    taskId: String,
    ttlSeconds: Long = 3600
): TaskKeypair

/**
 * Task keypair information
 *
 * @property taskId The task identifier
 * @property publicKey PGP public key (armored format)
 * @property privateKey PGP private key (armored format)
 * @property fingerprint Key fingerprint (hex string)
 * @property createdAt Creation timestamp
 * @property expiresAt Expiration timestamp
 */
data class TaskKeypair(
    val taskId: String,
    val publicKey: String,
    val privateKey: String,
    val fingerprint: String,
    val createdAt: String,
    val expiresAt: String
)
```

**Example**:
```kotlin
try {
    val keypair = taskManager.generateTaskKeypair(
        taskId = taskId,
        ttlSeconds = 3600  // 1 hour TTL
    )
    
    println("Keypair generated for task: $taskId")
    println("Fingerprint: ${keypair.fingerprint}")
    println("Expires at: ${keypair.expiresAt}")
    
    // Use keypair to grant file access
    val taskRecipient = Recipient(
        nodeId = "task-$taskId",
        publicKey = keypair.publicKey
    )
    
} catch (e: UnsupportedOperationException) {
    println("Task keypair feature not enabled")
    // Fall back to legacy mode (no keypair)
}
```

### Get Task Public Key

```kotlin
/**
 * Get the public key for a task (if keypair exists)
 *
 * @param taskId Unique task identifier
 * @return Public key (armored format) or null if no keypair
 * @throws TaskNotFoundException if task doesn't exist
 */
suspend fun getTaskPublicKey(taskId: String): String?
```

**Example**:
```kotlin
val publicKey = taskManager.getTaskPublicKey(taskId)

if (publicKey != null) {
    println("Task has keypair, using enhanced mode")
    // ... grant file access using publicKey
} else {
    println("Task has no keypair, using legacy mode")
    // ... execute without keypair isolation
}
```

### Cleanup Expired Keypairs

```kotlin
/**
 * Remove expired task keypairs from registry
 * 
 * Automatically called by background scheduler, but can be triggered
 * manually for immediate cleanup.
 *
 * @return Number of keypairs cleaned up
 */
suspend fun cleanupExpiredKeypairs(): Int
```

**Example**:
```kotlin
// Manual cleanup after task completion
taskManager.cancelTask(taskId, requesterId)
val cleanedUp = taskManager.cleanupExpiredKeypairs()
println("Cleaned up $cleanedUp expired keypairs")
```

### Get Active Keypairs

```kotlin
/**
 * Get all active (non-expired) task keypairs
 * 
 * Useful for monitoring and debugging keypair lifecycle.
 *
 * @return List of task IDs with active keypairs
 */
suspend fun getActiveKeypairs(): List<String>
```

**Example**:
```kotlin
val activeKeypairs = taskManager.getActiveKeypairs()
println("Active keypairs: ${activeKeypairs.size}")

for (taskId in activeKeypairs) {
    val publicKey = taskManager.getTaskPublicKey(taskId)
    println("- Task: $taskId (key: ${publicKey?.take(20)}...)")
}
```

---

## Task Scheduling

### Task Decomposition

```kotlin
/**
 * Decompose a task into sub-tasks for parallel execution
 * 
 * Useful for map-reduce patterns and large data processing.
 *
 * @param task Parent task to decompose
 * @param decompositionStrategy Strategy for splitting task
 * @return List of sub-task IDs
 * @throws TaskDecompositionException if decomposition fails
 */
suspend fun decomposeTask(
    task: Task,
    decompositionStrategy: DecompositionStrategy
): List<String>

/**
 * Decomposition strategy for task splitting
 */
sealed class DecompositionStrategy {
    /**
     * Split by number of sub-tasks
     */
    data class SplitN(val numSubTasks: Int) : DecompositionStrategy()
    
    /**
     * Split by data size (MB per sub-task)
     */
    data class SplitBySize(val sizeMBPerSubTask: Int) : DecompositionStrategy()
    
    /**
     * Map-reduce pattern
     */
    data class MapReduce(
        val mapTasks: Int,
        val reduceTask: Boolean = true
    ) : DecompositionStrategy()
}
```

**Example - Map-Reduce**:
```kotlin
// Submit parent task
val parentTask = Task(
    executable = Executable(
        runtime = RuntimeType.PYTHON,
        code = mapReduceCode,
        entryPoint = "map"
    ),
    inputFiles = listOf(largeDataFileId),
    resourceLimits = ResourceLimits(maxMemoryMB = 512, timeoutSeconds = 300),
    owner = "node-alice"
)

// Decompose into map tasks
val subTaskIds = taskManager.decomposeTask(
    task = parentTask,
    decompositionStrategy = DecompositionStrategy.MapReduce(
        mapTasks = 5,
        reduceTask = true
    )
)

println("Parent task decomposed into ${subTaskIds.size} sub-tasks")

// Monitor sub-tasks
for (subTaskId in subTaskIds) {
    val status = taskManager.getTaskStatus(subTaskId)
    println("Sub-task $subTaskId: ${status.state}")
}
```

### Node Assignment

```kotlin
/**
 * Get the assigned node for a task
 *
 * @param taskId Unique task identifier
 * @return Node ID where task is assigned, or null if not scheduled
 * @throws TaskNotFoundException if task doesn't exist
 */
suspend fun getAssignedNode(taskId: String): String?
```

**Example**:
```kotlin
val assignedNode = taskManager.getAssignedNode(taskId)

if (assignedNode != null) {
    println("Task assigned to: $assignedNode")
    
    // Check node health
    val nodeStatus = meshNetwork.getNodeStatus(assignedNode)
    println("Node health: ${nodeStatus.health}")
} else {
    println("Task not yet assigned")
}
```

---

## Task Monitoring

### List Tasks

```kotlin
/**
 * List all tasks owned by a node
 *
 * @param owner Node ID of the owner
 * @param state Filter by task state (optional)
 * @return List of task IDs
 */
suspend fun listTasks(
    owner: String,
    state: TaskState? = null
): List<String>
```

**Example**:
```kotlin
// List all tasks
val allTasks = taskManager.listTasks(owner = "node-alice")
println("Total tasks: ${allTasks.size}")

// List running tasks
val runningTasks = taskManager.listTasks(
    owner = "node-alice",
    state = TaskState.RUNNING
)
println("Running tasks: ${runningTasks.size}")

// Print task details
for (taskId in runningTasks) {
    val status = taskManager.getTaskStatus(taskId)
    println("- $taskId: ${(status.progress * 100).toInt()}% complete")
}
```

### Wait for Task Completion

```kotlin
/**
 * Wait for a task to complete (blocking)
 * 
 * Polls task status until it reaches a terminal state (COMPLETED, FAILED, CANCELLED).
 *
 * @param taskId Unique task identifier
 * @param timeoutMs Maximum wait time in milliseconds (default: no timeout)
 * @return Final TaskStatus
 * @throws TaskNotFoundException if task doesn't exist
 * @throws TimeoutException if timeout exceeded
 */
suspend fun waitForCompletion(
    taskId: String,
    timeoutMs: Long = Long.MAX_VALUE
): TaskStatus
```

**Example**:
```kotlin
val taskId = taskManager.submitTask(task)
println("Task submitted: $taskId")

// Wait for completion with 5-minute timeout
try {
    val finalStatus = taskManager.waitForCompletion(
        taskId = taskId,
        timeoutMs = 5 * 60 * 1000  // 5 minutes
    )
    
    when (finalStatus.state) {
        TaskState.COMPLETED -> {
            println("Task completed successfully")
            val result = taskManager.getTaskResult(taskId, "node-alice")
            // ... process result
        }
        TaskState.FAILED -> {
            println("Task failed: ${finalStatus.error}")
        }
        TaskState.CANCELLED -> {
            println("Task was cancelled")
        }
        else -> {
            // Should not happen
        }
    }
} catch (e: TimeoutException) {
    println("Task timed out after 5 minutes")
    taskManager.cancelTask(taskId, "node-alice")
}
```

### Monitor Task Progress

```kotlin
/**
 * Monitor task progress with callback
 * 
 * Invokes callback function whenever task progress updates.
 *
 * @param taskId Unique task identifier
 * @param callback Function called on progress updates
 * @return Job for monitoring cancellation
 */
suspend fun monitorProgress(
    taskId: String,
    callback: (TaskStatus) -> Unit
): Job
```

**Example**:
```kotlin
val monitorJob = taskManager.monitorProgress(taskId) { status ->
    println("Task ${status.taskId}: ${(status.progress * 100).toInt()}% - ${status.state}")
    
    if (status.state == TaskState.COMPLETED) {
        println("Task completed in ${status.endTime}")
    }
}

// ... do other work

// Cancel monitoring when done
monitorJob.cancel()
```

---

## Error Handling

### Exception Hierarchy

```kotlin
/**
 * Base exception for task operations
 */
sealed class TaskException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Task not found
 */
class TaskNotFoundException(taskId: String) :
    TaskException("Task not found: $taskId")

/**
 * Task submission failed
 */
class TaskSubmissionException(message: String, cause: Throwable? = null) :
    TaskException(message, cause)

/**
 * Invalid task specification
 */
class InvalidTaskException(message: String) :
    TaskException(message)

/**
 * Insufficient resources for task execution
 */
class InsufficientResourcesException(message: String) :
    TaskException(message)

/**
 * Task not in completable state
 */
class TaskNotCompleteException(taskId: String, currentState: TaskState) :
    TaskException("Task $taskId not complete (current state: $currentState)")

/**
 * Task cannot be cancelled
 */
class TaskNotCancellableException(taskId: String, reason: String) :
    TaskException("Task $taskId cannot be cancelled: $reason")

/**
 * Keypair generation failed
 */
class KeypairGenerationException(taskId: String, cause: Throwable) :
    TaskException("Failed to generate keypair for task $taskId", cause)

/**
 * Task decomposition failed
 */
class TaskDecompositionException(message: String, cause: Throwable? = null) :
    TaskException(message, cause)
```

### Error Handling Best Practices

```kotlin
// Handle task submission errors
try {
    val taskId = taskManager.submitTask(task)
    println("Task submitted: $taskId")
} catch (e: InvalidTaskException) {
    // Task specification invalid - user error
    logger.error("Invalid task: ${e.message}")
    // ... show user error message
} catch (e: InsufficientResourcesException) {
    // No nodes available - retry later
    logger.warn("No resources available, retrying in 30s...")
    delay(30_000)
    // ... retry submission
} catch (e: TaskSubmissionException) {
    // Generic submission error
    logger.error("Task submission failed", e)
}

// Handle keypair generation errors
try {
    val keypair = taskManager.generateTaskKeypair(taskId)
    // ... use keypair
} catch (e: UnsupportedOperationException) {
    // Feature flag disabled - fall back to legacy mode
    logger.info("Keypair feature disabled, using legacy mode")
    // ... execute without keypair
} catch (e: KeypairGenerationException) {
    // Keypair generation failed - critical error
    logger.error("Keypair generation failed", e)
    taskManager.cancelTask(taskId, requesterId)
}

// Handle result retrieval errors
try {
    val result = taskManager.getTaskResult(taskId, requesterId)
    // ... process result
} catch (e: TaskNotCompleteException) {
    // Task not done yet - wait and retry
    taskManager.waitForCompletion(taskId)
    val result = taskManager.getTaskResult(taskId, requesterId)
} catch (e: UnauthorizedException) {
    // Access denied - user error
    logger.error("Unauthorized access to task result")
}
```

---

## Usage Examples

### Example 1: Simple Task Execution

```kotlin
suspend fun runSimpleTask() {
    // Create task
    val task = Task(
        executable = Executable(
            runtime = RuntimeType.PYTHON,
            code = """
                print("Hello from distributed compute!")
            """.trimIndent(),
            entryPoint = "main"
        ),
        inputFiles = emptyList(),
        resourceLimits = ResourceLimits(
            maxMemoryMB = 64,
            maxCpuCores = 1,
            timeoutSeconds = 10
        ),
        owner = "node-alice"
    )
    
    // Submit and wait
    val taskId = taskManager.submitTask(task)
    val finalStatus = taskManager.waitForCompletion(taskId)
    
    if (finalStatus.state == TaskState.COMPLETED) {
        val result = taskManager.getTaskResult(taskId, "node-alice")
        println("Task output: ${result.logs}")
    }
}
```

### Example 2: Task with Encrypted Files (Keypair Enhancement)

```kotlin
suspend fun runTaskWithEncryptedFiles() {
    // Store input file
    val inputData = "Sensitive data to process".toByteArray()
    val inputFileId = storageManager.storeFile(
        data = inputData,
        filename = "input.txt",
        owner = "node-alice",
        recipients = listOf("node-alice")  // Only owner initially
    )
    
    // Create task
    val task = Task(
        executable = Executable(
            runtime = RuntimeType.PYTHON,
            code = """
                with open('/sandbox/input/${inputFileId}', 'r') as f:
                    data = f.read()
                with open('/sandbox/output/result.txt', 'w') as f:
                    f.write(data.upper())
            """.trimIndent(),
            entryPoint = "main"
        ),
        inputFiles = listOf(inputFileId),
        resourceLimits = ResourceLimits(maxMemoryMB = 128, timeoutSeconds = 30),
        owner = "node-alice"
    )
    
    // Submit task
    val taskId = taskManager.submitTask(task)
    
    // Generate task keypair
    val keypair = taskManager.generateTaskKeypair(taskId)
    
    // Grant task access to input file
    val taskRecipient = "task-$taskId"
    storageManager.updateFileAccess(
        fileId = inputFileId,
        addRecipients = listOf(taskRecipient)
    )
    
    // Wait for completion
    val finalStatus = taskManager.waitForCompletion(taskId)
    
    if (finalStatus.state == TaskState.COMPLETED) {
        val result = taskManager.getTaskResult(taskId, "node-alice")
        println("Task completed: ${result.outputFiles.size} output files")
        
        // Cleanup: Remove task access
        storageManager.updateFileAccess(
            fileId = inputFileId,
            removeRecipients = listOf(taskRecipient)
        )
        
        // Cleanup: Delete keypair
        taskManager.cleanupExpiredKeypairs()
    }
}
```

### Example 3: Map-Reduce Task

```kotlin
suspend fun runMapReduceTask() {
    // Parent task
    val parentTask = Task(
        executable = Executable(
            runtime = RuntimeType.PYTHON,
            code = """
                # Map function
                def map_function(data):
                    return [word.upper() for word in data.split()]
                
                # Reduce function
                def reduce_function(results):
                    return ' '.join(results)
            """.trimIndent(),
            entryPoint = "map_function"
        ),
        inputFiles = listOf(largeTextFileId),
        resourceLimits = ResourceLimits(maxMemoryMB = 512, timeoutSeconds = 300),
        owner = "node-alice"
    )
    
    // Decompose into map tasks
    val subTaskIds = taskManager.decomposeTask(
        task = parentTask,
        decompositionStrategy = DecompositionStrategy.MapReduce(
            mapTasks = 5,
            reduceTask = true
        )
    )
    
    println("Created ${subTaskIds.size} sub-tasks")
    
    // Wait for all sub-tasks
    val mapTaskIds = subTaskIds.dropLast(1)  // All except reduce task
    val reduceTaskId = subTaskIds.last()
    
    // Monitor map tasks
    for (mapTaskId in mapTaskIds) {
        taskManager.waitForCompletion(mapTaskId)
    }
    println("All map tasks completed")
    
    // Wait for reduce task
    taskManager.waitForCompletion(reduceTaskId)
    println("Reduce task completed")
    
    // Get final result
    val result = taskManager.getTaskResult(reduceTaskId, "node-alice")
    println("Final result: ${result.outputFiles}")
}
```

---

## Best Practices

### 1. Resource Limits

```kotlin
// ✅ GOOD: Appropriate resource limits
val task = Task(
    executable = pythonExecutable,
    resourceLimits = ResourceLimits(
        maxMemoryMB = 128,      // Sufficient for task
        maxCpuCores = 1,        // Single-threaded task
        timeoutSeconds = 60,    // Reasonable timeout
        networkAccess = false   // No network needed
    ),
    owner = "node-alice"
)

// ❌ BAD: Excessive resource limits
val task = Task(
    executable = pythonExecutable,
    resourceLimits = ResourceLimits(
        maxMemoryMB = 4096,     // Way too much!
        maxCpuCores = 8,        // Unnecessary
        timeoutSeconds = 3600,  // 1 hour timeout for 10s task
        networkAccess = true    // Security risk if not needed
    ),
    owner = "node-alice"
)
```

### 2. Keypair Lifecycle

```kotlin
// ✅ GOOD: Proper keypair cleanup
val taskId = taskManager.submitTask(task)
val keypair = taskManager.generateTaskKeypair(taskId, ttlSeconds = 3600)

try {
    // ... grant file access, execute task
    val result = taskManager.waitForCompletion(taskId)
} finally {
    // Always cleanup, even on errors
    taskManager.cleanupExpiredKeypairs()
}

// ❌ BAD: No keypair cleanup
val keypair = taskManager.generateTaskKeypair(taskId)
// ... use keypair
// (keypair never cleaned up - memory leak)
```

### 3. Error Handling

```kotlin
// ✅ GOOD: Specific exception handling with retry
suspend fun submitTaskWithRetry(task: Task, maxRetries: Int = 3): String {
    repeat(maxRetries) { attempt ->
        try {
            return taskManager.submitTask(task)
        } catch (e: InsufficientResourcesException) {
            if (attempt == maxRetries - 1) throw e
            logger.warn("No resources, retrying (${attempt + 1}/$maxRetries)...")
            delay(10_000)
        } catch (e: InvalidTaskException) {
            // Don't retry for invalid tasks
            throw e
        }
    }
    throw TaskSubmissionException("Failed after $maxRetries retries")
}

// ❌ BAD: Generic exception handling
try {
    taskManager.submitTask(task)
} catch (e: Exception) {
    // Too broad, hides specific errors
}
```

### 4. Task Monitoring

```kotlin
// ✅ GOOD: Monitor with timeout
val taskId = taskManager.submitTask(task)

try {
    val result = taskManager.waitForCompletion(
        taskId = taskId,
        timeoutMs = 5 * 60 * 1000  // 5 minute timeout
    )
    // ... process result
} catch (e: TimeoutException) {
    logger.error("Task timed out, cancelling")
    taskManager.cancelTask(taskId, requesterId)
}

// ❌ BAD: Infinite wait
val result = taskManager.waitForCompletion(taskId)
// (may hang forever if task stalls)
```

---

## Integration Patterns

### Pattern 1: Task Pipeline

```kotlin
class TaskPipeline(private val taskManager: TaskManager) {
    
    suspend fun runPipeline(stages: List<PipelineStage>): String {
        var inputFileId = stages.first().inputFileId
        
        for ((index, stage) in stages.withIndex()) {
            val task = Task(
                executable = stage.executable,
                inputFiles = listOf(inputFileId),
                resourceLimits = stage.resourceLimits,
                owner = stage.owner
            )
            
            val taskId = taskManager.submitTask(task)
            val status = taskManager.waitForCompletion(taskId)
            
            if (status.state != TaskState.COMPLETED) {
                throw Exception("Stage $index failed: ${status.error}")
            }
            
            val result = taskManager.getTaskResult(taskId, stage.owner)
            inputFileId = result.outputFiles.first()  // Output becomes next input
        }
        
        return inputFileId  // Final output
    }
}
```

### Pattern 2: Batch Task Execution

```kotlin
class BatchTaskExecutor(private val taskManager: TaskManager) {
    
    suspend fun executeBatch(tasks: List<Task>): List<TaskResult> {
        // Submit all tasks
        val taskIds = tasks.map { task ->
            taskManager.submitTask(task)
        }
        
        // Wait for all tasks concurrently
        return coroutineScope {
            taskIds.map { taskId ->
                async {
                    taskManager.waitForCompletion(taskId)
                    taskManager.getTaskResult(taskId, tasks.first().owner)
                }
            }.awaitAll()
        }
    }
}
```

---

## Performance Considerations

### Latency Characteristics

| Operation | Typical Latency | Notes |
|-----------|-----------------|-------|
| `submitTask()` | 50-100ms | Task registration + scheduling |
| `generateTaskKeypair()` | 200-500ms | PGP keypair generation (mobile) |
| `getTaskStatus()` | 5-10ms | Local registry lookup |
| `getTaskResult()` | 100-300ms | Depends on output file size |
| `cancelTask()` | 50-100ms | Async cancellation |
| `cleanupExpiredKeypairs()` | 10-50ms | Per-keypair cleanup |

---

## See Also

- [DistributedStorageManager API Documentation](DistributedStorageManager_API.md)
- [StrangersSafeComputeEngine API Documentation](StrangersSafeComputeEngine_API.md)
- [Task Keypair Enhancement Guide](../guides/TaskKeypairEnhancement.md)

---

**Document Version**: 1.0.0  
**Last Updated**: November 14, 2025  
**Maintainer**: Meshrabiya Core Team
