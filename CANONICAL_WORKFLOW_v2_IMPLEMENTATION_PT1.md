# CANONICAL WORKFLOWS v2 IMPLEMENTATION PLAN - PART 1

**Date**: December 3, 2025  
**Scope**: Phases 5-7 Implementation - Foundation & Refactoring  
**Status**: IMPLEMENTATION READY

---

## EXECUTIVE SUMMARY

This document details Part 1 of the implementation plan for CANONICAL_WORKFLOWS_v2.md Phases 5-7 (Add Task, Task Manager Receives Access Update, Completed Task workflows). Part 1 covers foundation work: new data structures, interfaces, message types, executor refactoring, and DistributedComputeClient implementation.

**Part 2** (separate document) will cover TaskManager, DistributedComputeServer, and integration work.

---

## DESIGN DECISIONS INCORPORATED

1. **Task Sandbox**: Use ProcessBuilder with filesystem isolation (no PID tracking due to Java 8/Android constraints)
2. **ServiceLibraryEntry**: Add `hasInputFiles` and `hasOutputFiles` boolean fields
3. **Task Result Retention**: Never expire (owner revokes only)
4. **Node Ranking Weights**: latency 30%, currentLoad 25%, capability 20%, fitness 15%, RAM 5%, storage 5%
5. **Acceptance Timing**: Send TaskAcceptanceMessage immediately; if prep fails, send TaskCompletedMessage with success=false

---

## PART 1 SCOPE

### Files to Create (6)
1. `Task.kt` - Core task data structure
2. `TaskExecutor.kt` - Executor interface
3. `ExecutionResult.kt` - Execution outcome data class
4. `DistributedComputeClient.kt` - Client-side task management
5. `TaskAcceptanceMessage.kt` - Task acceptance message type
6. `TaskCompletionAckMessage.kt` - Completion acknowledgment message type

### Files to Refactor (6)
1. `MeshrabiyaConstants.kt` - Add task-related constants
2. `ServiceLibraryEntry.kt` - Add hasInputFiles/hasOutputFiles fields
3. `MeshEcosystemMessage.kt` - Add new message types
4. `JVMExecutor.kt` - Implement TaskExecutor interface
5. `JSExecutor.kt` - Implement TaskExecutor interface
6. `MLNativeExecutor.kt` - Implement TaskExecutor interface

---

## 1. CONSTANTS ADDITIONS

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/MeshrabiyaConstants.kt`

**Action**: Add task completion retry constants

```kotlin
// Add to MeshrabiyaConstants object

/**
 * Interval between retry attempts for task completion notifications (30 seconds)
 */
fun getTaskCompletionRetryIntervalMs(): Long = 30_000L

/**
 * Total retry period for task completion notifications (5 minutes)
 */
fun getTaskCompletionRetryPeriodMs(): Long = 300_000L
```

**Location**: Add after existing timeout/retry constants (around line 50-80)

---

## 2. CORE DATA STRUCTURES

### 2.1 Task.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/Task.kt` (NEW)

**Full Implementation**:

```kotlin
package net.ballmerlabs.meshrabiya.compute

import java.util.UUID

/**
 * Represents a compute task being executed on the mesh network.
 * Manages task lifecycle, sandbox isolation, and execution context.
 *
 * @property taskId Unique identifier for the task
 * @property serviceId Service identifier from ServiceLibrary
 * @property inputParams Input parameters for the task
 * @property requesterNodeId Public key of the node requesting the task
 * @property recipients List of recipients with access to task results
 * @property state Current execution state of the task
 * @property publicKey Public key for encrypting data shared with this task
 * @property privateKey Private key for decrypting input files (stored securely)
 * @property filesDir Sandbox directory for task file I/O
 * @property executable Code bundle to execute
 * @property createdAt Timestamp when task was created
 * @property startedAt Timestamp when execution started (null if not started)
 * @property completedAt Timestamp when execution completed (null if not completed)
 */
data class Task(
    val taskId: String,
    val serviceId: String,
    val inputParams: Map<String, Any>,
    val requesterNodeId: String,
    val recipients: List<RecipientEntry>,
    var state: TaskState,
    val publicKey: String,
    val privateKey: ByteArray,
    val filesDir: String,
    val executable: Executable,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Task

        if (taskId != other.taskId) return false
        if (serviceId != other.serviceId) return false
        if (inputParams != other.inputParams) return false
        if (requesterNodeId != other.requesterNodeId) return false
        if (recipients != other.recipients) return false
        if (state != other.state) return false
        if (publicKey != other.publicKey) return false
        if (!privateKey.contentEquals(other.privateKey)) return false
        if (filesDir != other.filesDir) return false
        if (executable != other.executable) return false
        if (createdAt != other.createdAt) return false
        if (startedAt != other.startedAt) return false
        if (completedAt != other.completedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = taskId.hashCode()
        result = 31 * result + serviceId.hashCode()
        result = 31 * result + inputParams.hashCode()
        result = 31 * result + requesterNodeId.hashCode()
        result = 31 * result + recipients.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + filesDir.hashCode()
        result = 31 * result + executable.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (startedAt?.hashCode() ?: 0)
        result = 31 * result + (completedAt?.hashCode() ?: 0)
        return result
    }
}

/**
 * Task execution state machine
 */
enum class TaskState {
    /** Task accepted, preparing sandbox and dependencies */
    PREPARING,
    
    /** Task is actively executing */
    EXECUTING,
    
    /** Task completed successfully */
    COMPLETED,
    
    /** Task failed during execution */
    FAILED
}

/**
 * Represents executable code bundle for a task
 *
 * @property runtime Runtime environment for execution
 * @property codeBundle Binary code to execute
 * @property entryPoint Entry point for execution (function name, class name, etc.)
 */
data class Executable(
    val runtime: RuntimeType,
    val codeBundle: ByteArray,
    val entryPoint: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Executable

        if (runtime != other.runtime) return false
        if (!codeBundle.contentEquals(other.codeBundle)) return false
        if (entryPoint != other.entryPoint) return false

        return true
    }

    override fun hashCode(): Int {
        var result = runtime.hashCode()
        result = 31 * result + codeBundle.contentHashCode()
        result = 31 * result + entryPoint.hashCode()
        return result
    }
}

/**
 * Supported runtime types for task execution
 */
enum class RuntimeType {
    /** JVM bytecode execution */
    JVM,
    
    /** JavaScript execution via J2V8 */
    JAVASCRIPT,
    
    /** TensorFlow Lite ML model execution */
    ML_NATIVE
}
```

**Dependencies**: 
- Import `RecipientEntry` from `net.ballmerlabs.meshrabiya.storage`

---

### 2.2 TaskExecutor.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/TaskExecutor.kt` (NEW)

**Full Implementation**:

```kotlin
package net.ballmerlabs.meshrabiya.compute

/**
 * Interface for task execution runtimes.
 * Implementations handle execution for specific runtime types (JVM, JS, ML).
 */
interface TaskExecutor {
    /**
     * Execute a task and return the result.
     *
     * @param task Task to execute with all context
     * @return ExecutionResult with output or error details
     */
    suspend fun execute(task: Task): ExecutionResult

    /**
     * Validate that the code bundle is well-formed for this runtime.
     *
     * @param codeBundle Binary code to validate
     * @return true if valid, false otherwise
     */
    fun validateCodeBundle(codeBundle: ByteArray): Boolean

    /**
     * Get the runtime type this executor supports.
     *
     * @return RuntimeType enum value
     */
    fun getSupportedRuntime(): RuntimeType
}
```

---

### 2.3 ExecutionResult.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/ExecutionResult.kt` (NEW)

**Full Implementation**:

```kotlin
package net.ballmerlabs.meshrabiya.compute

/**
 * Result of task execution.
 *
 * @property success Whether execution completed successfully
 * @property resultMessage Optional result message (JSON/XML/text)
 * @property resultMessageType Type of result message (json/xml/text)
 * @property outputFiles List of output file paths relative to task filesDir
 * @property executionTimeMs Time taken to execute in milliseconds
 * @property errorType Type of error if execution failed
 * @property errorMessage Detailed error message if execution failed
 */
data class ExecutionResult(
    val success: Boolean,
    val resultMessage: String? = null,
    val resultMessageType: String? = null,
    val outputFiles: List<String> = emptyList(),
    val executionTimeMs: Long,
    val errorType: ExecutionErrorType? = null,
    val errorMessage: String? = null
)

/**
 * Types of execution errors
 */
enum class ExecutionErrorType {
    /** Code bundle validation failed */
    INVALID_CODE_BUNDLE,
    
    /** Runtime execution error (exception, crash, etc.) */
    RUNTIME_ERROR,
    
    /** Timeout exceeded */
    TIMEOUT,
    
    /** Insufficient resources (memory, storage, etc.) */
    RESOURCE_EXHAUSTED,
    
    /** Sandbox security violation */
    SECURITY_VIOLATION,
    
    /** Unknown or unclassified error */
    UNKNOWN
}
```

---

## 3. SERVICE LIBRARY UPDATES

### 3.1 ServiceLibraryEntry.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/ServiceLibraryEntry.kt`

**Current Definition** (lines 39-52):

```kotlin
data class ServiceLibraryEntry(
    val serviceId: String,
    val serviceName: String,
    val serviceHash: String,
    val runtime: String,
    val codeBundle: ByteArray,
    val entryPoint: String,
    val description: String? = null,
    val resourceRequirements: ResourceRequirements? = null
)
```

**Action**: Add `hasInputFiles` and `hasOutputFiles` fields

**New Definition**:

```kotlin
data class ServiceLibraryEntry(
    val serviceId: String,
    val serviceName: String,
    val serviceHash: String,
    val runtime: String,
    val codeBundle: ByteArray,
    val entryPoint: String,
    val description: String? = null,
    val resourceRequirements: ResourceRequirements? = null,
    val hasInputFiles: Boolean = false,
    val hasOutputFiles: Boolean = false
)
```

**Explanation**: These fields indicate whether a service expects input files or produces output files, used by TaskManager to determine if sandbox file I/O is needed.

---

## 4. MESSAGE TYPE ADDITIONS

### 4.1 MeshEcosystemMessage.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/messages/MeshEcosystemMessage.kt`

**Action**: Add two new message types

**Location**: Add after existing message definitions (around line 1000-1100)

```kotlin
/**
 * Message sent by compute node to client when task is accepted for execution.
 *
 * @property taskId Task identifier
 * @property publicKey Public key for encrypting data shared with this task
 * @property computeNodeAddress Mesh network address of compute node executing the task
 */
data class TaskAcceptanceMessage(
    val taskId: String,
    val publicKey: String,
    val computeNodeAddress: String
) : MeshEcosystemMessage() {
    override fun toBytes(): ByteArray {
        // Serialization implementation
        return serializeToBytes(
            "taskId" to taskId,
            "publicKey" to publicKey,
            "computeNodeAddress" to computeNodeAddress
        )
    }

    companion object {
        fun fromBytes(bytes: ByteArray): TaskAcceptanceMessage {
            val map = deserializeFromBytes(bytes)
            return TaskAcceptanceMessage(
                taskId = map["taskId"] as String,
                publicKey = map["publicKey"] as String,
                computeNodeAddress = map["computeNodeAddress"] as String
            )
        }
    }
}

/**
 * Message sent by client to compute node acknowledging task completion.
 *
 * @property taskId Task identifier
 * @property receivedAt Timestamp when client received completion notification
 */
data class TaskCompletionAckMessage(
    val taskId: String,
    val receivedAt: Long = System.currentTimeMillis()
) : MeshEcosystemMessage() {
    override fun toBytes(): ByteArray {
        return serializeToBytes(
            "taskId" to taskId,
            "receivedAt" to receivedAt
        )
    }

    companion object {
        fun fromBytes(bytes: ByteArray): TaskCompletionAckMessage {
            val map = deserializeFromBytes(bytes)
            return TaskCompletionAckMessage(
                taskId = map["taskId"] as String,
                receivedAt = map["receivedAt"] as Long
            )
        }
    }
}
```

**Note**: Update message type registry and routing logic in `MeshEcosystemListener` to handle these new message types (Part 2).

---

## 5. EXECUTOR REFACTORING

All three executors need to implement the `TaskExecutor` interface and return `ExecutionResult`.

### 5.1 JVMExecutor.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/JVMExecutor.kt`

**Current Signature** (line 15):
```kotlin
class JVMExecutor(private val context: Context)
```

**Changes Required**:

1. **Implement TaskExecutor interface**
2. **Add execute() method** returning ExecutionResult
3. **Add validateCodeBundle() method**
4. **Add getSupportedRuntime() method**

**New Implementation**:

```kotlin
import net.ballmerlabs.meshrabiya.compute.TaskExecutor
import net.ballmerlabs.meshrabiya.compute.Task
import net.ballmerlabs.meshrabiya.compute.ExecutionResult
import net.ballmerlabs.meshrabiya.compute.RuntimeType
import net.ballmerlabs.meshrabiya.compute.ExecutionErrorType

class JVMExecutor(private val context: Context) : TaskExecutor {
    
    override suspend fun execute(task: Task): ExecutionResult {
        val startTime = System.currentTimeMillis()
        
        try {
            // Validate code bundle
            if (!validateCodeBundle(task.executable.codeBundle)) {
                return ExecutionResult(
                    success = false,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    errorType = ExecutionErrorType.INVALID_CODE_BUNDLE,
                    errorMessage = "Invalid JVM bytecode"
                )
            }

            // Load class from bytecode
            val classLoader = ByteArrayClassLoader(task.executable.codeBundle)
            val clazz = classLoader.loadClass(task.executable.entryPoint)
            
            // Create instance
            val instance = clazz.getDeclaredConstructor().newInstance()
            
            // Find and invoke execute method
            val method = clazz.getMethod("execute", Map::class.java, String::class.java)
            val result = method.invoke(instance, task.inputParams, task.filesDir)
            
            // Parse result (assuming it returns Map<String, Any>)
            val resultMap = result as? Map<String, Any> ?: emptyMap()
            val outputFiles = resultMap["outputFiles"] as? List<String> ?: emptyList()
            val resultMessage = resultMap["resultMessage"] as? String
            val resultMessageType = resultMap["resultMessageType"] as? String
            
            return ExecutionResult(
                success = true,
                resultMessage = resultMessage,
                resultMessageType = resultMessageType,
                outputFiles = outputFiles,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            
        } catch (e: Exception) {
            return ExecutionResult(
                success = false,
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorType = ExecutionErrorType.RUNTIME_ERROR,
                errorMessage = "JVM execution failed: ${e.message}"
            )
        }
    }

    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        // Basic validation: check for Java class file magic number
        if (codeBundle.size < 4) return false
        return codeBundle[0] == 0xCA.toByte() && 
               codeBundle[1] == 0xFE.toByte() && 
               codeBundle[2] == 0xBA.toByte() && 
               codeBundle[3] == 0xBE.toByte()
    }

    override fun getSupportedRuntime(): RuntimeType = RuntimeType.JVM
    
    // Helper class for loading bytecode
    private class ByteArrayClassLoader(private val classBytes: ByteArray) : ClassLoader() {
        override fun loadClass(name: String): Class<*> {
            return defineClass(name, classBytes, 0, classBytes.size)
        }
    }
}
```

---

### 5.2 JSExecutor.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/JSExecutor.kt`

**Current Signature** (line 18):
```kotlin
class JSExecutor(private val context: Context)
```

**Changes Required**: Same as JVMExecutor

**New Implementation**:

```kotlin
import net.ballmerlabs.meshrabiya.compute.TaskExecutor
import net.ballmerlabs.meshrabiya.compute.Task
import net.ballmerlabs.meshrabiya.compute.ExecutionResult
import net.ballmerlabs.meshrabiya.compute.RuntimeType
import net.ballmerlabs.meshrabiya.compute.ExecutionErrorType
import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Object
import org.json.JSONObject

class JSExecutor(private val context: Context) : TaskExecutor {
    
    override suspend fun execute(task: Task): ExecutionResult {
        val startTime = System.currentTimeMillis()
        var v8: V8? = null
        
        try {
            // Validate code bundle
            if (!validateCodeBundle(task.executable.codeBundle)) {
                return ExecutionResult(
                    success = false,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    errorType = ExecutionErrorType.INVALID_CODE_BUNDLE,
                    errorMessage = "Invalid JavaScript code"
                )
            }

            // Initialize V8 runtime
            v8 = V8.createV8Runtime()
            
            // Inject task context
            val taskContext = V8Object(v8)
            taskContext.add("filesDir", task.filesDir)
            taskContext.add("inputParams", JSONObject(task.inputParams).toString())
            v8.add("taskContext", taskContext)
            taskContext.release()
            
            // Execute code
            val code = String(task.executable.codeBundle, Charsets.UTF_8)
            v8.executeScript(code)
            
            // Call entry point
            val resultJson = v8.executeStringScript("${task.executable.entryPoint}(taskContext)")
            val result = JSONObject(resultJson)
            
            // Parse result
            val outputFiles = mutableListOf<String>()
            if (result.has("outputFiles")) {
                val filesArray = result.getJSONArray("outputFiles")
                for (i in 0 until filesArray.length()) {
                    outputFiles.add(filesArray.getString(i))
                }
            }
            
            return ExecutionResult(
                success = true,
                resultMessage = result.optString("resultMessage", null),
                resultMessageType = result.optString("resultMessageType", null),
                outputFiles = outputFiles,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            
        } catch (e: Exception) {
            return ExecutionResult(
                success = false,
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorType = ExecutionErrorType.RUNTIME_ERROR,
                errorMessage = "JavaScript execution failed: ${e.message}"
            )
        } finally {
            v8?.release()
        }
    }

    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        try {
            // Basic validation: ensure it's valid UTF-8 and contains function keyword
            val code = String(codeBundle, Charsets.UTF_8)
            return code.contains("function") || code.contains("=>")
        } catch (e: Exception) {
            return false
        }
    }

    override fun getSupportedRuntime(): RuntimeType = RuntimeType.JAVASCRIPT
}
```

---

### 5.3 MLNativeExecutor.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/MLNativeExecutor.kt`

**Current Signature** (line 20):
```kotlin
class MLNativeExecutor(private val context: Context)
```

**Changes Required**: Same as JVMExecutor

**New Implementation**:

```kotlin
import net.ballmerlabs.meshrabiya.compute.TaskExecutor
import net.ballmerlabs.meshrabiya.compute.Task
import net.ballmerlabs.meshrabiya.compute.ExecutionResult
import net.ballmerlabs.meshrabiya.compute.RuntimeType
import net.ballmerlabs.meshrabiya.compute.ExecutionErrorType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer

class MLNativeExecutor(private val context: Context) : TaskExecutor {
    
    override suspend fun execute(task: Task): ExecutionResult {
        val startTime = System.currentTimeMillis()
        var interpreter: Interpreter? = null
        
        try {
            // Validate code bundle
            if (!validateCodeBundle(task.executable.codeBundle)) {
                return ExecutionResult(
                    success = false,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    errorType = ExecutionErrorType.INVALID_CODE_BUNDLE,
                    errorMessage = "Invalid TensorFlow Lite model"
                )
            }

            // Write model to temp file (TFLite requires file path)
            val modelFile = File(task.filesDir, "model.tflite")
            modelFile.writeBytes(task.executable.codeBundle)
            
            // Load interpreter
            interpreter = Interpreter(modelFile)
            
            // Parse input from inputParams
            val inputBuffer = parseInputBuffer(task.inputParams)
            
            // Prepare output buffer
            val outputBuffer = ByteBuffer.allocateDirect(getOutputBufferSize(interpreter))
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Write output to file
            val outputFile = File(task.filesDir, "output.bin")
            outputFile.writeBytes(outputBuffer.array())
            
            return ExecutionResult(
                success = true,
                resultMessage = "Inference complete",
                resultMessageType = "text",
                outputFiles = listOf("output.bin"),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            
        } catch (e: Exception) {
            return ExecutionResult(
                success = false,
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorType = ExecutionErrorType.RUNTIME_ERROR,
                errorMessage = "ML execution failed: ${e.message}"
            )
        } finally {
            interpreter?.close()
        }
    }

    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        // Basic validation: check for TFLite magic number
        if (codeBundle.size < 4) return false
        return codeBundle[0] == 0x54.toByte() && // 'T'
               codeBundle[1] == 0x46.toByte() && // 'F'
               codeBundle[2] == 0x4C.toByte() && // 'L'
               codeBundle[3] == 0x33.toByte()    // '3'
    }

    override fun getSupportedRuntime(): RuntimeType = RuntimeType.ML_NATIVE
    
    private fun parseInputBuffer(inputParams: Map<String, Any>): ByteBuffer {
        // Parse input parameters into ByteBuffer
        // This is model-specific - implement based on your model's input format
        val buffer = ByteBuffer.allocateDirect(1024) // Example size
        // TODO: Implement actual parsing logic
        return buffer
    }
    
    private fun getOutputBufferSize(interpreter: Interpreter): Int {
        // Get output tensor size from interpreter
        val outputTensor = interpreter.getOutputTensor(0)
        return outputTensor.numBytes()
    }
}
```

---

## 6. DISTRIBUTED COMPUTE CLIENT

### 6.1 DistributedComputeClient.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/kotlin/net/ballmerlabs/meshrabiya/compute/DistributedComputeClient.kt` (NEW)

**Full Implementation**:

```kotlin
package net.ballmerlabs.meshrabiya.compute

import android.content.Context
import kotlinx.coroutines.*
import net.ballmerlabs.meshrabiya.MeshrabiyaConstants
import net.ballmerlabs.meshrabiya.VirtualNode
import net.ballmerlabs.meshrabiya.messages.*
import net.ballmerlabs.meshrabiya.util.BetaLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side distributed compute service.
 * Handles task submission, node selection, and result retrieval.
 */
class DistributedComputeClient(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val betaLogger: BetaLogger
) {
    private val activeRequests = ConcurrentHashMap<String, TrackedRequest>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Process a task request from the API.
     *
     * @param request LocalComputeTaskRequest from user
     * @return taskId for tracking
     */
    suspend fun processTaskRequest(request: LocalComputeTaskRequest): String {
        betaLogger.log("Processing task request: taskId=${request.taskId}")
        
        // Track request
        val trackedRequest = TrackedRequest(
            request = request,
            status = TaskRequestStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        activeRequests[request.taskId] = trackedRequest
        
        // Broadcast compute task request
        val message = ComputeTaskRequestMessage(
            taskId = request.taskId,
            serviceId = request.serviceId,
            serviceHash = request.serviceHash
        )
        
        virtualNode.coreGossipBroadcastService.sendComputeTaskRequest(message)
        
        betaLogger.log("Broadcast compute task request: taskId=${request.taskId}")
        
        return request.taskId
    }

    /**
     * Handle compute node response (called by MeshEcosystemListener).
     *
     * @param response ComputeNodeResponse from potential compute node
     */
    fun handleComputeNodeResponse(response: ComputeNodeResponse) {
        val tracked = activeRequests[response.taskId] ?: run {
            betaLogger.log("Received response for unknown task: ${response.taskId}")
            return
        }
        
        // Add response to candidates
        tracked.candidateNodes.add(response)
        
        betaLogger.log("Received compute node response: taskId=${response.taskId}, node=${response.nodeAddress}")
    }

    /**
     * Select best compute node and assign task.
     * Called after response collection timeout.
     *
     * @param taskId Task identifier
     */
    suspend fun selectAndAssignNode(taskId: String) {
        val tracked = activeRequests[taskId] ?: run {
            betaLogger.log("Cannot assign task - not found: $taskId")
            return
        }
        
        if (tracked.candidateNodes.isEmpty()) {
            betaLogger.log("No capable nodes for task: $taskId - will retry")
            // TODO: Implement retry with backoff
            return
        }
        
        // Rank candidates by design decision weights
        val ranked = tracked.candidateNodes.sortedWith(
            compareBy<ComputeNodeResponse> { it.estimatedLatencyMs } // 30% - ascending
                .thenBy { it.currentLoad } // 25% - ascending
                .thenByDescending { it.capabilityMetric } // 20% - descending
                .thenByDescending { it.fitnessMetric } // 15% - descending
                .thenByDescending { it.availableRamMb } // 5% - descending
                .thenByDescending { it.availableStorageMb } // 5% - descending
        )
        
        val selected = ranked.first()
        betaLogger.log("Selected compute node: taskId=$taskId, node=${selected.nodeAddress}")
        
        assignTaskToNode(taskId, selected)
    }

    /**
     * Assign task to selected compute node.
     *
     * @param taskId Task identifier
     * @param node Selected compute node
     */
    private suspend fun assignTaskToNode(taskId: String, node: ComputeNodeResponse) {
        val tracked = activeRequests[taskId] ?: return
        
        // Update status
        tracked.status = TaskRequestStatus.ASSIGNED
        tracked.selectedNodeAddress = node.nodeAddress
        
        // Create assignment message
        val assignmentMessage = TaskAssignmentMessage(
            taskId = taskId,
            serviceId = tracked.request.serviceId,
            inputParams = tracked.request.inputParams,
            requesterNodeId = tracked.request.requesterNodeId,
            recipients = tracked.request.recipients
        )
        
        // Send direct message to selected node
        virtualNode.sendDirectMessage(node.nodeAddress, assignmentMessage.toBytes())
        
        betaLogger.log("Sent task assignment: taskId=$taskId, node=${node.nodeAddress}")
    }

    /**
     * Handle task acceptance message from compute node.
     *
     * @param message TaskAcceptanceMessage from compute node
     */
    fun handleTaskAcceptanceMessage(message: TaskAcceptanceMessage) {
        val tracked = activeRequests[message.taskId] ?: run {
            betaLogger.log("Received acceptance for unknown task: ${message.taskId}")
            return
        }
        
        // Update status
        tracked.status = TaskRequestStatus.ACCEPTED
        tracked.taskPublicKey = message.publicKey
        
        betaLogger.log("Task accepted: taskId=${message.taskId}, node=${message.computeNodeAddress}")
    }

    /**
     * Handle task completion message from compute node.
     *
     * @param message TaskCompletedMessage from compute node
     */
    suspend fun handleTaskCompletionMessage(message: TaskCompletedMessage) {
        val tracked = activeRequests[message.taskId] ?: run {
            betaLogger.log("Received completion for unknown task: ${message.taskId}")
            return
        }
        
        // Update status
        tracked.status = if (message.success) {
            TaskRequestStatus.COMPLETED
        } else {
            TaskRequestStatus.FAILED
        }
        tracked.completionMessage = message
        
        betaLogger.log("Task completed: taskId=${message.taskId}, success=${message.success}")
        
        // Download result files if any
        if (message.success && message.resultManifest.isNotEmpty()) {
            for (fileRef in message.resultManifest) {
                // TODO: Call DistributedStorageClient.retrieveFile(fileRef)
                betaLogger.log("Downloading result file: ${fileRef.fileId}")
            }
        }
        
        // Send acknowledgment
        val ackMessage = TaskCompletionAckMessage(taskId = message.taskId)
        virtualNode.sendDirectMessage(
            tracked.selectedNodeAddress ?: return,
            ackMessage.toBytes()
        )
        
        // Notify UI via API
        // TODO: Call MeshrabiyaAPI callback with completion details
        
        betaLogger.log("Sent task completion acknowledgment: taskId=${message.taskId}")
    }

    /**
     * Tracked request with response collection
     */
    private data class TrackedRequest(
        val request: LocalComputeTaskRequest,
        var status: TaskRequestStatus,
        val createdAt: Long,
        val candidateNodes: MutableList<ComputeNodeResponse> = mutableListOf(),
        var selectedNodeAddress: String? = null,
        var taskPublicKey: String? = null,
        var completionMessage: TaskCompletedMessage? = null
    )

    /**
     * Task request status
     */
    private enum class TaskRequestStatus {
        PENDING,
        ASSIGNED,
        ACCEPTED,
        COMPLETED,
        FAILED
    }
}
```

**Dependencies**:
- Import `LocalComputeTaskRequest` from existing location
- Import `ComputeTaskRequestMessage`, `ComputeNodeResponse`, `TaskAssignmentMessage`, `TaskCompletedMessage` from `MeshEcosystemMessage.kt`
- Import `FileReference` from storage package

---

## VALIDATION CHECKLIST

### Constants
- [ ] Add `getTaskCompletionRetryIntervalMs()` to MeshrabiyaConstants
- [ ] Add `getTaskCompletionRetryPeriodMs()` to MeshrabiyaConstants

### Data Structures
- [ ] Create `Task.kt` with TaskState, Executable, RuntimeType
- [ ] Create `TaskExecutor.kt` interface
- [ ] Create `ExecutionResult.kt` with ExecutionErrorType

### Service Library
- [ ] Add `hasInputFiles` field to ServiceLibraryEntry
- [ ] Add `hasOutputFiles` field to ServiceLibraryEntry

### Messages
- [ ] Add TaskAcceptanceMessage to MeshEcosystemMessage.kt
- [ ] Add TaskCompletionAckMessage to MeshEcosystemMessage.kt
- [ ] Verify serialization/deserialization methods

### Executors
- [ ] Update JVMExecutor to implement TaskExecutor
- [ ] Update JSExecutor to implement TaskExecutor
- [ ] Update MLNativeExecutor to implement TaskExecutor
- [ ] Verify all executors return ExecutionResult

### Client
- [ ] Create DistributedComputeClient.kt
- [ ] Implement processTaskRequest()
- [ ] Implement handleComputeNodeResponse()
- [ ] Implement selectAndAssignNode() with ranking weights
- [ ] Implement assignTaskToNode()
- [ ] Implement handleTaskAcceptanceMessage()
- [ ] Implement handleTaskCompletionMessage()

### Build
- [ ] Run `./tools/brace_paren_check.sh` on all modified files
- [ ] Compile Meshrabiya library module
- [ ] Fix any import errors
- [ ] Verify no syntax errors

---

## NEXT STEPS (PART 2)

Part 2 will cover:

1. **TaskManager.kt** - Complete implementation
   - addTask()
   - prepareTask()
   - executeTask()
   - handleTaskDataAccessUpdate()

2. **DistributedComputeServer.kt** - Server-side implementation
   - handleIncomingComputeTaskRequest()
   - handleTaskAssignmentMessage()
   - sendTaskAcceptance()
   - sendTaskCompletion()
   - handleTaskCompletionAckMessage()

3. **Integration Work**
   - Split IntelligentDistributedComputeService
   - Update MeshEcosystemListener routing
   - Update VirtualNode initialization
   - Update CoreGossipBroadcastService

4. **Testing**
   - Integration test checklist
   - Validation steps

---

**END OF PART 1**
