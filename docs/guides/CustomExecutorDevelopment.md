# Developer Guide: Custom Executor Development

**Version**: 1.0.0  
**Last Updated**: November 14, 2025  
**Difficulty**: Advanced  
**Estimated Time**: 2-4 hours

---

## Overview

This guide walks you through creating a custom executor for the Meshrabiya distributed compute layer. Custom executors enable support for new programming languages, specialized runtimes, or custom execution environments.

### What You'll Build

By the end of this guide, you'll have:
- A working custom executor for a new runtime (Rust in this example)
- Integration with the sandbox filesystem
- Resource limit enforcement
- Task keypair support for encrypted file access

### Prerequisites

- Kotlin/Android development experience
- Understanding of process management and sandboxing
- Familiarity with the Task Keypair Enhancement architecture
- Review of [TaskManager API Documentation](../api/TaskManager_API.md)

---

## Table of Contents

1. [Executor Architecture](#executor-architecture)
2. [Step 1: Define Executor Interface](#step-1-define-executor-interface)
3. [Step 2: Implement Executor](#step-2-implement-executor)
4. [Step 3: Sandbox Integration](#step-3-sandbox-integration)
5. [Step 4: Resource Limits](#step-4-resource-limits)
6. [Step 5: Keypair Integration](#step-5-keypair-integration)
7. [Step 6: Error Handling](#step-6-error-handling)
8. [Step 7: Registration](#step-7-registration)
9. [Testing](#testing)
10. [Best Practices](#best-practices)

---

## Executor Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────┐
│                   TaskManager                            │
│  ┌─────────────────────────────────────────────────┐   │
│  │         Executor Registry                        │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐     │   │
│  │  │  Python  │  │   Java   │  │   Rust   │ ←── │   │
│  │  │ Executor │  │ Executor │  │ Executor │     │   │
│  │  └──────────┘  └──────────┘  └──────────┘     │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│            StrangersSafeComputeEngine                    │
│  ┌─────────────────────────────────────────────────┐   │
│  │            Sandbox Environment                   │   │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  │   │
│  │  │ /sandbox/ │  │ /sandbox/ │  │ /sandbox/ │  │   │
│  │  │  input/   │  │  output/  │  │   tmp/    │  │   │
│  │  └───────────┘  └───────────┘  └───────────┘  │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Executor Responsibilities

1. **Execution**: Run task code in the target runtime
2. **Isolation**: Enforce sandbox boundaries (filesystem, network)
3. **Resource Management**: Enforce CPU, memory, and time limits
4. **File Access**: Handle encrypted input files via keypairs
5. **Error Handling**: Capture and report execution errors

---

## Step 1: Define Executor Interface

First, let's define the `TaskExecutor` interface that all executors must implement.

### File: `TaskExecutor.kt`

```kotlin
package org.torproject.meshrabiya.compute.executors

import org.torproject.meshrabiya.compute.Task
import org.torproject.meshrabiya.compute.TaskKeypair
import org.torproject.meshrabiya.compute.ExecutionResult

/**
 * Interface for task executors
 * 
 * Custom executors must implement this interface to support
 * a new runtime or execution environment.
 */
interface TaskExecutor {
    
    /**
     * Runtime type supported by this executor
     */
    val runtimeType: RuntimeType
    
    /**
     * Execute a task in the sandbox environment
     * 
     * @param task Task to execute
     * @param sandboxDir Sandbox root directory
     * @param keypair Task keypair for encrypted file access (optional)
     * @return Execution result containing output files and logs
     * @throws ExecutionException if execution fails
     */
    suspend fun execute(
        task: Task,
        sandboxDir: File,
        keypair: TaskKeypair?
    ): ExecutionResult
    
    /**
     * Check if this executor is available on this device
     * 
     * @return True if executor can run, false otherwise
     */
    fun isAvailable(): Boolean
    
    /**
     * Get executor version information
     * 
     * @return Version string (e.g., "python-3.9.5")
     */
    fun getVersion(): String
}

/**
 * Execution result
 * 
 * @property outputFiles List of output file paths (relative to sandbox/output/)
 * @property logs Execution logs (stdout + stderr)
 * @property exitCode Process exit code
 * @property executionTimeMs Total execution time in milliseconds
 * @property resourceUsage Actual resource consumption
 */
data class ExecutionResult(
    val outputFiles: List<String>,
    val logs: String,
    val exitCode: Int,
    val executionTimeMs: Long,
    val resourceUsage: ResourceUsage
)

/**
 * Resource usage statistics
 * 
 * @property peakMemoryMB Peak memory usage in MB
 * @property cpuTimeMs Total CPU time in milliseconds
 * @property ioReadBytes Total bytes read from disk
 * @property ioWriteBytes Total bytes written to disk
 */
data class ResourceUsage(
    val peakMemoryMB: Int,
    val cpuTimeMs: Long,
    val ioReadBytes: Long,
    val ioWriteBytes: Long
)

/**
 * Runtime types
 */
enum class RuntimeType {
    PYTHON,
    JAVA,
    JVM,
    JAVASCRIPT,
    ML_NATIVE,
    WORKFLOW,
    RUST  // Our new custom runtime
}
```

---

## Step 2: Implement Executor

Now let's implement a custom executor for Rust tasks.

### File: `RustExecutor.kt`

```kotlin
package org.torproject.meshrabiya.compute.executors

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.torproject.meshrabiya.compute.Task
import org.torproject.meshrabiya.compute.TaskKeypair
import org.torproject.meshrabiya.logging.Logger

/**
 * Executor for Rust tasks
 * 
 * Compiles and runs Rust code in a sandboxed environment with
 * resource limits and encrypted file access support.
 */
class RustExecutor : TaskExecutor {
    
    override val runtimeType = RuntimeType.RUST
    
    private val logger = Logger.get(this::class)
    
    /**
     * Execute Rust task
     * 
     * Steps:
     * 1. Write Rust source code to sandbox
     * 2. Compile using rustc
     * 3. Execute binary with resource limits
     * 4. Collect output files and logs
     */
    override suspend fun execute(
        task: Task,
        sandboxDir: File,
        keypair: TaskKeypair?
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Create sandbox directories
        val inputDir = File(sandboxDir, "input")
        val outputDir = File(sandboxDir, "output")
        val tmpDir = File(sandboxDir, "tmp")
        
        inputDir.mkdirs()
        outputDir.mkdirs()
        tmpDir.mkdirs()
        
        // Write Rust source code
        val sourceFile = File(tmpDir, "main.rs")
        sourceFile.writeText(task.executable.code)
        
        // Compile Rust code
        val binaryFile = File(tmpDir, "task_binary")
        val compileResult = compileRustCode(sourceFile, binaryFile)
        
        if (compileResult.exitCode != 0) {
            throw ExecutionException("Rust compilation failed: ${compileResult.logs}")
        }
        
        logger.info("Rust code compiled successfully")
        
        // Execute binary with resource limits
        val executionResult = executeBinaryWithLimits(
            binary = binaryFile,
            sandboxDir = sandboxDir,
            resourceLimits = task.resourceLimits,
            timeoutSeconds = task.resourceLimits.timeoutSeconds
        )
        
        // Collect output files
        val outputFiles = outputDir.listFiles()?.map { it.name } ?: emptyList()
        
        val executionTime = System.currentTimeMillis() - startTime
        
        logger.info("Rust task completed in ${executionTime}ms")
        
        return@withContext ExecutionResult(
            outputFiles = outputFiles,
            logs = compileResult.logs + "\n" + executionResult.logs,
            exitCode = executionResult.exitCode,
            executionTimeMs = executionTime,
            resourceUsage = executionResult.resourceUsage
        )
    }
    
    /**
     * Compile Rust source code
     */
    private suspend fun compileRustCode(
        sourceFile: File,
        outputFile: File
    ): CompilationResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "rustc",
            sourceFile.absolutePath,
            "-o", outputFile.absolutePath,
            "-C", "opt-level=2"  // Optimize for speed
        ).redirectErrorStream(true).start()
        
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        
        CompilationResult(
            exitCode = exitCode,
            logs = output
        )
    }
    
    /**
     * Execute binary with resource limits
     */
    private suspend fun executeBinaryWithLimits(
        binary: File,
        sandboxDir: File,
        resourceLimits: ResourceLimits,
        timeoutSeconds: Long
    ): ExecutionResultInternal = withContext(Dispatchers.IO) {
        // Make binary executable
        binary.setExecutable(true)
        
        // Build process with resource limits
        val processBuilder = ProcessBuilder(binary.absolutePath)
            .directory(sandboxDir)
            .redirectErrorStream(true)
        
        // Set environment variables
        processBuilder.environment().apply {
            put("SANDBOX_ROOT", sandboxDir.absolutePath)
            put("MAX_MEMORY_MB", resourceLimits.maxMemoryMB.toString())
        }
        
        val process = processBuilder.start()
        
        // Monitor resource usage
        val resourceMonitor = ResourceMonitor(process, resourceLimits)
        val monitorJob = launch { resourceMonitor.start() }
        
        // Wait for completion with timeout
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        
        if (!completed) {
            // Timeout - kill process
            logger.warn("Task timed out after ${timeoutSeconds}s, killing process")
            process.destroyForcibly()
            monitorJob.cancel()
            throw ExecutionException("Task execution timed out")
        }
        
        // Collect output
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.exitValue()
        
        // Get resource usage
        monitorJob.cancel()
        val resourceUsage = resourceMonitor.getUsage()
        
        ExecutionResultInternal(
            exitCode = exitCode,
            logs = output,
            resourceUsage = resourceUsage
        )
    }
    
    override fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("rustc", "--version").start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logger.warn("Rust not available: ${e.message}")
            false
        }
    }
    
    override fun getVersion(): String {
        return try {
            val process = ProcessBuilder("rustc", "--version").start()
            val output = process.inputStream.bufferedReader().readText()
            output.trim()
        } catch (e: Exception) {
            "unknown"
        }
    }
}

/**
 * Compilation result (internal)
 */
private data class CompilationResult(
    val exitCode: Int,
    val logs: String
)

/**
 * Execution result (internal)
 */
private data class ExecutionResultInternal(
    val exitCode: Int,
    val logs: String,
    val resourceUsage: ResourceUsage
)

/**
 * Execution exception
 */
class ExecutionException(message: String) : Exception(message)
```

---

## Step 3: Sandbox Integration

The executor must properly integrate with the sandbox filesystem to provide isolated file access.

### Sandbox Directory Structure

```
/sandbox/
├── input/          # Decrypted input files (read-only)
│   ├── file-1.txt
│   └── file-2.dat
├── output/         # Task output files (read-write)
│   └── result.txt
└── tmp/            # Temporary files (read-write)
    ├── main.rs
    └── task_binary
```

### File Access Helper

```kotlin
/**
 * Helper for sandbox file access
 */
class SandboxFileHelper(
    private val sandboxDir: File,
    private val storageManager: DistributedStorageManager,
    private val keypair: TaskKeypair?
) {
    
    /**
     * Prepare input files in sandbox
     * 
     * Decrypts input files from distributed storage and places them
     * in /sandbox/input/ directory.
     */
    suspend fun prepareInputFiles(fileIds: List<String>, taskId: String) {
        val inputDir = File(sandboxDir, "input")
        inputDir.mkdirs()
        
        for (fileId in fileIds) {
            // Retrieve encrypted file
            val encryptedData = storageManager.retrieveFile(
                fileId = fileId,
                requesterId = "task-$taskId"  // Use task as requester
            )
            
            // Decrypt using task keypair (if available)
            val decryptedData = if (keypair != null) {
                PGPEncryptionService.decrypt(encryptedData, keypair.privateKey)
            } else {
                encryptedData  // Legacy mode (no encryption)
            }
            
            // Write to sandbox input directory
            val inputFile = File(inputDir, fileId)
            inputFile.writeBytes(decryptedData)
        }
    }
    
    /**
     * Collect output files from sandbox
     * 
     * Encrypts output files and stores them in distributed storage.
     * 
     * @return List of output file IDs
     */
    suspend fun collectOutputFiles(ownerId: String): List<String> {
        val outputDir = File(sandboxDir, "output")
        val outputFiles = outputDir.listFiles() ?: return emptyList()
        
        return outputFiles.map { outputFile ->
            val fileData = outputFile.readBytes()
            
            // Encrypt for owner (if keypair available)
            val encryptedData = if (keypair != null) {
                val ownerPublicKey = getNodePublicKey(ownerId)
                PGPEncryptionService.encrypt(fileData, ownerPublicKey)
            } else {
                fileData  // Legacy mode (no encryption)
            }
            
            // Store in distributed storage
            storageManager.storeFile(
                data = encryptedData,
                filename = outputFile.name,
                owner = ownerId,
                recipients = listOf(ownerId)
            )
        }
    }
    
    /**
     * Cleanup sandbox directory
     */
    fun cleanup() {
        sandboxDir.deleteRecursively()
    }
}
```

---

## Step 4: Resource Limits

Implement resource monitoring and enforcement to prevent tasks from exceeding limits.

### Resource Monitor

```kotlin
/**
 * Monitors process resource usage
 */
class ResourceMonitor(
    private val process: Process,
    private val limits: ResourceLimits
) {
    private var peakMemoryMB = 0
    private var cpuTimeMs = 0L
    private var ioReadBytes = 0L
    private var ioWriteBytes = 0L
    
    /**
     * Start monitoring (blocking)
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        while (process.isAlive) {
            // Get current resource usage
            val pid = getPid(process)
            val memoryUsage = getMemoryUsageMB(pid)
            val cpuUsage = getCpuTimeMs(pid)
            val ioUsage = getIOUsage(pid)
            
            // Update peak values
            peakMemoryMB = maxOf(peakMemoryMB, memoryUsage)
            cpuTimeMs = cpuUsage
            ioReadBytes = ioUsage.readBytes
            ioWriteBytes = ioUsage.writeBytes
            
            // Check limits
            if (memoryUsage > limits.maxMemoryMB) {
                Logger.warn("Memory limit exceeded: ${memoryUsage}MB > ${limits.maxMemoryMB}MB")
                process.destroyForcibly()
                throw ResourceLimitException("Memory limit exceeded")
            }
            
            // Check every 100ms
            delay(100)
        }
    }
    
    /**
     * Get resource usage statistics
     */
    fun getUsage(): ResourceUsage {
        return ResourceUsage(
            peakMemoryMB = peakMemoryMB,
            cpuTimeMs = cpuTimeMs,
            ioReadBytes = ioReadBytes,
            ioWriteBytes = ioWriteBytes
        )
    }
    
    /**
     * Get process PID
     */
    private fun getPid(process: Process): Long {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(process)
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Get memory usage in MB
     */
    private fun getMemoryUsageMB(pid: Long): Int {
        return try {
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val rssKB = output.toIntOrNull() ?: 0
            rssKB / 1024  // Convert KB to MB
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Get CPU time in milliseconds
     */
    private fun getCpuTimeMs(pid: Long): Long {
        return try {
            val process = ProcessBuilder("ps", "-o", "cputime=", "-p", pid.toString()).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            parseCpuTime(output)
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Parse CPU time string (format: "MM:SS.mm" or "HH:MM:SS")
     */
    private fun parseCpuTime(timeStr: String): Long {
        val parts = timeStr.split(":")
        return when (parts.size) {
            2 -> {
                // MM:SS.mm
                val minutes = parts[0].toLongOrNull() ?: 0
                val seconds = parts[1].toDoubleOrNull() ?: 0.0
                (minutes * 60 * 1000) + (seconds * 1000).toLong()
            }
            3 -> {
                // HH:MM:SS
                val hours = parts[0].toLongOrNull() ?: 0
                val minutes = parts[1].toLongOrNull() ?: 0
                val seconds = parts[2].toLongOrNull() ?: 0
                (hours * 3600 * 1000) + (minutes * 60 * 1000) + (seconds * 1000)
            }
            else -> 0
        }
    }
    
    /**
     * Get I/O usage
     */
    private fun getIOUsage(pid: Long): IOUsage {
        return try {
            val file = File("/proc/$pid/io")
            if (!file.exists()) return IOUsage(0, 0)
            
            val lines = file.readLines()
            var readBytes = 0L
            var writeBytes = 0L
            
            for (line in lines) {
                when {
                    line.startsWith("read_bytes:") -> {
                        readBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0
                    }
                    line.startsWith("write_bytes:") -> {
                        writeBytes = line.substringAfter(":").trim().toLongOrNull() ?: 0
                    }
                }
            }
            
            IOUsage(readBytes, writeBytes)
        } catch (e: Exception) {
            IOUsage(0, 0)
        }
    }
}

/**
 * I/O usage statistics
 */
private data class IOUsage(
    val readBytes: Long,
    val writeBytes: Long
)

/**
 * Resource limit exception
 */
class ResourceLimitException(message: String) : Exception(message)
```

---

## Step 5: Keypair Integration

Integrate task keypairs for encrypted file access.

### Keypair-Aware Executor Wrapper

```kotlin
/**
 * Wraps executor with keypair support
 */
class KeypairAwareExecutor(
    private val baseExecutor: TaskExecutor,
    private val storageManager: DistributedStorageManager,
    private val taskManager: TaskManager
) : TaskExecutor by baseExecutor {
    
    override suspend fun execute(
        task: Task,
        sandboxDir: File,
        keypair: TaskKeypair?
    ): ExecutionResult {
        val fileHelper = SandboxFileHelper(sandboxDir, storageManager, keypair)
        
        try {
            // Prepare input files (decrypt into sandbox)
            fileHelper.prepareInputFiles(task.inputFiles, task.taskId)
            
            // Execute task
            val result = baseExecutor.execute(task, sandboxDir, keypair)
            
            // Collect output files (encrypt from sandbox)
            val outputFileIds = fileHelper.collectOutputFiles(task.owner)
            
            // Update result with output file IDs
            return result.copy(
                outputFiles = outputFileIds
            )
            
        } finally {
            // Cleanup sandbox
            fileHelper.cleanup()
        }
    }
}
```

---

## Step 6: Error Handling

Implement comprehensive error handling for executor operations.

### Error Handling Pattern

```kotlin
/**
 * Execute task with error handling
 */
suspend fun executeTaskSafely(
    executor: TaskExecutor,
    task: Task,
    sandboxDir: File,
    keypair: TaskKeypair?
): ExecutionResult {
    return try {
        executor.execute(task, sandboxDir, keypair)
        
    } catch (e: ExecutionException) {
        // Task execution failed (expected)
        logger.error("Task execution failed: ${e.message}")
        ExecutionResult(
            outputFiles = emptyList(),
            logs = "Execution error: ${e.message}",
            exitCode = -1,
            executionTimeMs = 0,
            resourceUsage = ResourceUsage(0, 0, 0, 0)
        )
        
    } catch (e: ResourceLimitException) {
        // Resource limit exceeded (expected)
        logger.warn("Resource limit exceeded: ${e.message}")
        ExecutionResult(
            outputFiles = emptyList(),
            logs = "Resource limit exceeded: ${e.message}",
            exitCode = -2,
            executionTimeMs = 0,
            resourceUsage = ResourceUsage(0, 0, 0, 0)
        )
        
    } catch (e: TimeoutException) {
        // Execution timeout (expected)
        logger.warn("Task timed out: ${e.message}")
        ExecutionResult(
            outputFiles = emptyList(),
            logs = "Task timed out",
            exitCode = -3,
            executionTimeMs = 0,
            resourceUsage = ResourceUsage(0, 0, 0, 0)
        )
        
    } catch (e: Exception) {
        // Unexpected error (critical)
        logger.error("Unexpected executor error", e)
        throw ExecutorCriticalException("Executor failed unexpectedly", e)
    }
}
```

---

## Step 7: Registration

Register your custom executor with the `TaskManager`.

### Executor Registry

```kotlin
/**
 * Registry for task executors
 */
class ExecutorRegistry {
    private val executors = mutableMapOf<RuntimeType, TaskExecutor>()
    
    /**
     * Register an executor
     */
    fun register(executor: TaskExecutor) {
        if (executor.isAvailable()) {
            executors[executor.runtimeType] = executor
            logger.info("Registered executor: ${executor.runtimeType} (${executor.getVersion()})")
        } else {
            logger.warn("Executor not available: ${executor.runtimeType}")
        }
    }
    
    /**
     * Get executor for runtime type
     */
    fun getExecutor(runtimeType: RuntimeType): TaskExecutor {
        return executors[runtimeType]
            ?: throw ExecutorNotFoundException("No executor for runtime: $runtimeType")
    }
    
    /**
     * List all available executors
     */
    fun listExecutors(): List<RuntimeType> {
        return executors.keys.toList()
    }
}
```

### Registration Example

```kotlin
// Initialize executor registry
val executorRegistry = ExecutorRegistry()

// Register built-in executors
executorRegistry.register(PythonExecutor())
executorRegistry.register(JavaExecutor())
executorRegistry.register(JavaScriptExecutor())

// Register custom executor
executorRegistry.register(RustExecutor())

// Initialize TaskManager with registry
val taskManager = TaskManager(
    scheduler = scheduler,
    storageManager = storageManager,
    computeEngine = computeEngine,
    executorRegistry = executorRegistry
)
```

---

## Testing

### Unit Tests

```kotlin
class RustExecutorTest {
    
    private lateinit var executor: RustExecutor
    private lateinit var sandboxDir: File
    
    @Before
    fun setup() {
        executor = RustExecutor()
        sandboxDir = Files.createTempDirectory("test-sandbox").toFile()
    }
    
    @After
    fun cleanup() {
        sandboxDir.deleteRecursively()
    }
    
    @Test
    fun `test simple Rust task execution`() = runBlocking {
        val task = Task(
            taskId = "test-1",
            executable = Executable(
                runtime = RuntimeType.RUST,
                code = """
                    fn main() {
                        println!("Hello from Rust!");
                    }
                """.trimIndent(),
                entryPoint = "main"
            ),
            inputFiles = emptyList(),
            resourceLimits = ResourceLimits(
                maxMemoryMB = 64,
                maxCpuCores = 1,
                timeoutSeconds = 10
            ),
            owner = "test-owner"
        )
        
        val result = executor.execute(task, sandboxDir, keypair = null)
        
        assertEquals(0, result.exitCode)
        assertTrue(result.logs.contains("Hello from Rust!"))
    }
    
    @Test
    fun `test Rust task with file I-O`() = runBlocking {
        // Prepare input file
        val inputDir = File(sandboxDir, "input")
        inputDir.mkdirs()
        File(inputDir, "input.txt").writeText("Test input")
        
        val task = Task(
            taskId = "test-2",
            executable = Executable(
                runtime = RuntimeType.RUST,
                code = """
                    use std::fs;
                    
                    fn main() {
                        let input = fs::read_to_string("/sandbox/input/input.txt").unwrap();
                        let output = input.to_uppercase();
                        fs::write("/sandbox/output/output.txt", output).unwrap();
                    }
                """.trimIndent(),
                entryPoint = "main"
            ),
            inputFiles = listOf("input.txt"),
            resourceLimits = ResourceLimits(maxMemoryMB = 64, timeoutSeconds = 10),
            owner = "test-owner"
        )
        
        val result = executor.execute(task, sandboxDir, keypair = null)
        
        assertEquals(0, result.exitCode)
        
        val outputFile = File(sandboxDir, "output/output.txt")
        assertTrue(outputFile.exists())
        assertEquals("TEST INPUT", outputFile.readText())
    }
}
```

---

## Best Practices

### 1. Executor Availability Check

Always check if the executor is available before registration:

```kotlin
// ✅ GOOD
if (executor.isAvailable()) {
    executorRegistry.register(executor)
} else {
    logger.warn("Rust executor not available, skipping registration")
}

// ❌ BAD
executorRegistry.register(RustExecutor())  // May fail if Rust not installed
```

### 2. Resource Limit Enforcement

Always enforce resource limits to prevent runaway tasks:

```kotlin
// ✅ GOOD
val resourceMonitor = ResourceMonitor(process, limits)
launch { resourceMonitor.start() }

// ❌ BAD
// No resource monitoring - task could consume all system resources
```

### 3. Sandbox Cleanup

Always cleanup sandbox directories, even on errors:

```kotlin
// ✅ GOOD
try {
    executor.execute(task, sandboxDir, keypair)
} finally {
    sandboxDir.deleteRecursively()
}

// ❌ BAD
executor.execute(task, sandboxDir, keypair)
// Sandbox never cleaned up on error
```

### 4. Error Context

Provide detailed error context in exceptions:

```kotlin
// ✅ GOOD
throw ExecutionException("Rust compilation failed at line 42: expected ';'")

// ❌ BAD
throw ExecutionException("Compilation failed")  // No context
```

---

## See Also

- [TaskManager API Documentation](../api/TaskManager_API.md)
- [DistributedStorageManager API Documentation](../api/DistributedStorageManager_API.md)
- [Task Keypair Enhancement Guide](TaskKeypairEnhancement.md)

---

**Document Version**: 1.0.0  
**Last Updated**: November 14, 2025  
**Maintainer**: Meshrabiya Core Team
