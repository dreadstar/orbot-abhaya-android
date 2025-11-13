# Task Execution Layer Implementation Plan - Part 2

**Date**: November 13, 2025  
**Status**: COMPREHENSIVE IMPLEMENTATION PLAN (PART 2 OF 2)  
**Continuation of**: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md

---

## Section 4: Resource Monitoring Implementation

### 4.1 Overview

TaskManager must track resource usage across all executing tasks to:
1. Provide total load for node capability calculations (used in compute node response decisions)
2. Track per-task metrics for enforcement and billing
3. Detect resource limit violations (trigger task termination)
4. Generate execution statistics for completion notifications

### 4.2 Add Resource Monitoring Loop

**Add to TaskManager object**:

```kotlin
/**
 * ENSURE RESOURCE MONITORING ACTIVE
 * 
 * Start resource monitoring loop if not already running
 */
private fun ensureResourceMonitoringActive() {
    if (resourceMonitoringJob != null && resourceMonitoringJob?.isActive == true) {
        return // Already running
    }
    
    resourceMonitoringJob = GlobalScope.launch {
        while (isActive && activeExecutions.isNotEmpty()) {
            try {
                updateResourceMetrics()
                delay(MeshrabiyaConstants.RESOURCE_MONITORING_INTERVAL_MS)
            } catch (e: Exception) {
                betaLogger.log(
                    LogLevel.ERROR,
                    "TaskManager",
                    "Resource monitoring error: ${e.message}"
                )
            }
        }
    }
}

/**
 * UPDATE RESOURCE METRICS
 * 
 * Poll all active containers for current resource usage
 */
private suspend fun updateResourceMetrics() {
    var totalRamActual = 0L
    var totalRamAverage = 0L
    var totalRamPeak = 0L
    var totalCpuTime = 0L
    var totalCpuPercent = 0f
    var totalDiskIo = 0L
    var totalDiskStorage = 0L
    
    activeExecutions.values.forEach { execution ->
        try {
            // Poll container for current metrics
            val containerMetrics = strangersSafeComputeEngine.getContainerMetrics(
                execution.containerId
            )
            
            // Update per-task metrics
            execution.currentMetrics = containerMetrics
            execution.metricsHistory.add(containerMetrics)
            
            // Update peak metrics
            execution.peakMetrics = ResourceMetrics(
                ramActualBytes = maxOf(execution.peakMetrics.ramActualBytes, containerMetrics.ramActualBytes),
                ramAverageBytes = execution.metricsHistory.map { it.ramActualBytes }.average().toLong(),
                ramPeakBytes = execution.metricsHistory.maxOf { it.ramActualBytes },
                cpuTimeUsedMs = containerMetrics.cpuTimeUsedMs,
                cpuPercentage = containerMetrics.cpuPercentage,
                diskIoOperations = containerMetrics.diskIoOperations,
                diskStorageUsedBytes = containerMetrics.diskStorageUsedBytes
            )
            
            // Accumulate totals
            totalRamActual += containerMetrics.ramActualBytes
            totalRamAverage += execution.peakMetrics.ramAverageBytes
            totalRamPeak += execution.peakMetrics.ramPeakBytes
            totalCpuTime += containerMetrics.cpuTimeUsedMs
            totalCpuPercent += containerMetrics.cpuPercentage
            totalDiskIo += containerMetrics.diskIoOperations
            totalDiskStorage += containerMetrics.diskStorageUsedBytes
            
            // Check for resource limit violations
            checkResourceLimitViolations(execution)
            
            // Update task status with current metrics
            val taskId = containerToTask[execution.containerId]
            if (taskId != null) {
                val status = taskStatuses[taskId]
                if (status != null) {
                    taskStatuses[taskId] = status.copy(
                        resourceUsage = containerMetrics
                    )
                }
            }
            
        } catch (e: Exception) {
            betaLogger.log(
                LogLevel.ERROR,
                "TaskManager",
                "Failed to get metrics for container ${execution.containerId}: ${e.message}"
            )
        }
    }
    
    // Update total load
    totalLoad = ResourceMetrics(
        ramActualBytes = totalRamActual,
        ramAverageBytes = totalRamAverage,
        ramPeakBytes = totalRamPeak,
        cpuTimeUsedMs = totalCpuTime,
        cpuPercentage = totalCpuPercent,
        diskIoOperations = totalDiskIo,
        diskStorageUsedBytes = totalDiskStorage
    )
}

/**
 * CHECK RESOURCE LIMIT VIOLATIONS
 * 
 * Terminate task if it exceeds resource limits
 */
private suspend fun checkResourceLimitViolations(execution: ExecutionState) {
    val limits = execution.context.resourceLimits
    val metrics = execution.currentMetrics
    
    when {
        metrics.ramActualBytes > limits.maxMemoryBytes -> {
            betaLogger.log(
                LogLevel.WARN,
                "TaskManager",
                "Task ${execution.context.taskId} exceeded memory limit: " +
                "${metrics.ramActualBytes} > ${limits.maxMemoryBytes}"
            )
            terminateTask(execution.context.taskId, ExecutionErrorType.OUT_OF_MEMORY)
        }
        
        metrics.cpuTimeUsedMs > limits.maxCpuTimeMs -> {
            betaLogger.log(
                LogLevel.WARN,
                "TaskManager",
                "Task ${execution.context.taskId} exceeded CPU time limit: " +
                "${metrics.cpuTimeUsedMs} > ${limits.maxCpuTimeMs}"
            )
            terminateTask(execution.context.taskId, ExecutionErrorType.TIMEOUT)
        }
        
        metrics.diskStorageUsedBytes > limits.maxDiskBytes -> {
            betaLogger.log(
                LogLevel.WARN,
                "TaskManager",
                "Task ${execution.context.taskId} exceeded disk limit: " +
                "${metrics.diskStorageUsedBytes} > ${limits.maxDiskBytes}"
            )
            terminateTask(execution.context.taskId, ExecutionErrorType.DISK_QUOTA_EXCEEDED)
        }
        
        (System.currentTimeMillis() - execution.startedAt) > limits.maxExecutionTimeMs -> {
            betaLogger.log(
                LogLevel.WARN,
                "TaskManager",
                "Task ${execution.context.taskId} exceeded execution time limit"
            )
            terminateTask(execution.context.taskId, ExecutionErrorType.TIMEOUT)
        }
    }
}

/**
 * TERMINATE TASK
 * 
 * Forcefully stop task execution due to violation
 */
private suspend fun terminateTask(taskId: String, errorType: ExecutionErrorType) {
    val execution = activeExecutions[taskId] ?: return
    
    // Kill container
    strangersSafeComputeEngine.killContainer(execution.containerId)
    
    // Create error result
    val errorResult = ExecutionResult(
        taskId = taskId,
        success = false,
        outputManifest = emptyList(),
        resourcesUsed = execution.currentMetrics,
        executionTimeMs = System.currentTimeMillis() - execution.startedAt,
        errorMessage = "Task terminated: ${errorType.name}",
        errorType = errorType
    )
    
    // Send completion notification with error
    sendCompletionNotification(
        taskId = taskId,
        requesterNodeId = execution.context.requesterNodeId,
        callbackAddress = execution.context.callbackAddress,
        result = errorResult
    )
    
    // Update status
    failTask(taskId, "Task terminated: ${errorType.name}")
    
    // Cleanup
    cleanupExecution(taskId)
}

/**
 * GET TOTAL LOAD
 * 
 * Public API for node capability calculations
 */
fun getTotalLoad(): ResourceMetrics {
    return totalLoad
}

/**
 * GET TASK METRICS
 * 
 * Get current resource usage for specific task
 */
fun getTaskMetrics(taskId: String): ResourceMetrics? {
    return activeExecutions[taskId]?.currentMetrics
}

/**
 * GET PEAK METRICS
 * 
 * Get peak resource usage for specific task
 */
fun getPeakMetrics(taskId: String): ResourceMetrics? {
    return activeExecutions[taskId]?.peakMetrics
}
```

### 4.3 Add Resource Monitoring Constants

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/util/MeshrabiyaConstants.kt`

```kotlin
object MeshrabiyaConstants {
    // ... existing constants ...
    
    /**
     * Interval for polling container resource metrics
     */
    const val RESOURCE_MONITORING_INTERVAL_MS = 2000L // 2 seconds
}
```

### 4.4 Implement StrangersSafeComputeEngine.getContainerMetrics()

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/StrangersSafeComputeEngine.kt`

**Current state**: `monitorContainerExecution()` returns placeholder data (line ~300)

**Add new method**:

```kotlin
/**
 * GET CONTAINER METRICS
 * 
 * Poll container for current resource usage
 * Called by TaskManager on interval
 */
suspend fun getContainerMetrics(containerId: String): ResourceMetrics {
    val container = activeContainers[containerId]
        ?: throw IllegalStateException("Container not found: $containerId")
    
    // Read from /proc for actual resource usage
    val memoryBytes = readContainerMemoryUsage(containerId)
    val cpuStats = readContainerCpuUsage(containerId)
    val diskStats = readContainerDiskUsage(containerId)
    
    return ResourceMetrics(
        ramActualBytes = memoryBytes.current,
        ramAverageBytes = memoryBytes.average,
        ramPeakBytes = memoryBytes.peak,
        cpuTimeUsedMs = cpuStats.timeUsedMs,
        cpuPercentage = cpuStats.percentage,
        diskIoOperations = diskStats.ioOperations,
        diskStorageUsedBytes = diskStats.storageUsed
    )
}

/**
 * READ CONTAINER MEMORY USAGE
 * 
 * Read from /proc/<pid>/status and /proc/<pid>/statm
 */
private suspend fun readContainerMemoryUsage(containerId: String): MemoryUsage {
    val container = activeContainers[containerId] ?: return MemoryUsage(0, 0, 0)
    val pid = container.processId
    
    return withContext(Dispatchers.IO) {
        try {
            // Read VmRSS (actual memory usage) from /proc/<pid>/status
            val statusFile = File("/proc/$pid/status")
            val vmRss = statusFile.readLines()
                .find { it.startsWith("VmRSS:") }
                ?.split("\\s+".toRegex())
                ?.get(1)
                ?.toLongOrNull() ?: 0L
            
            val currentBytes = vmRss * 1024 // Convert KB to bytes
            
            // Track history for average and peak
            container.memoryHistory.add(currentBytes)
            if (container.memoryHistory.size > 30) { // Keep last 30 samples
                container.memoryHistory.removeAt(0)
            }
            
            val averageBytes = if (container.memoryHistory.isNotEmpty()) {
                container.memoryHistory.average().toLong()
            } else {
                currentBytes
            }
            
            val peakBytes = container.memoryHistory.maxOrNull() ?: currentBytes
            
            MemoryUsage(currentBytes, averageBytes, peakBytes)
            
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "StrangersSafeComputeEngine", 
                "Failed to read memory for container $containerId: ${e.message}")
            MemoryUsage(0, 0, 0)
        }
    }
}

private data class MemoryUsage(
    val current: Long,
    val average: Long,
    val peak: Long
)

/**
 * READ CONTAINER CPU USAGE
 * 
 * Read from /proc/<pid>/stat
 */
private suspend fun readContainerCpuUsage(containerId: String): CpuUsage {
    val container = activeContainers[containerId] ?: return CpuUsage(0, 0f)
    val pid = container.processId
    
    return withContext(Dispatchers.IO) {
        try {
            // Read from /proc/<pid>/stat
            val statFile = File("/proc/$pid/stat")
            val statFields = statFile.readText().split(" ")
            
            // Fields 14 and 15 are utime and stime (in clock ticks)
            val utime = statFields.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = statFields.getOrNull(14)?.toLongOrNull() ?: 0L
            
            // Convert clock ticks to milliseconds (assumes 100 ticks/second)
            val clockTicksPerSecond = 100L
            val totalTimeMs = ((utime + stime) * 1000) / clockTicksPerSecond
            
            // Calculate CPU percentage (time used / elapsed time)
            val elapsedMs = System.currentTimeMillis() - container.startTime
            val cpuPercent = if (elapsedMs > 0) {
                (totalTimeMs.toFloat() / elapsedMs.toFloat()) * 100f
            } else {
                0f
            }
            
            CpuUsage(totalTimeMs, cpuPercent)
            
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "StrangersSafeComputeEngine",
                "Failed to read CPU for container $containerId: ${e.message}")
            CpuUsage(0, 0f)
        }
    }
}

private data class CpuUsage(
    val timeUsedMs: Long,
    val percentage: Float
)

/**
 * READ CONTAINER DISK USAGE
 * 
 * Read from /proc/<pid>/io
 */
private suspend fun readContainerDiskUsage(containerId: String): DiskUsage {
    val container = activeContainers[containerId] ?: return DiskUsage(0, 0)
    val pid = container.processId
    
    return withContext(Dispatchers.IO) {
        try {
            // Read from /proc/<pid>/io
            val ioFile = File("/proc/$pid/io")
            val ioLines = ioFile.readLines()
            
            // Get read and write operations
            val readOps = ioLines.find { it.startsWith("syscr:") }
                ?.split(":")?.get(1)?.trim()?.toLongOrNull() ?: 0L
            val writeOps = ioLines.find { it.startsWith("syscw:") }
                ?.split(":")?.get(1)?.trim()?.toLongOrNull() ?: 0L
            
            val totalIoOps = readOps + writeOps
            
            // Get storage used (from container's working directory)
            val containerDir = File("/data/containers/$containerId")
            val storageUsed = if (containerDir.exists()) {
                calculateDirectorySize(containerDir)
            } else {
                0L
            }
            
            DiskUsage(totalIoOps, storageUsed)
            
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "StrangersSafeComputeEngine",
                "Failed to read disk usage for container $containerId: ${e.message}")
            DiskUsage(0, 0)
        }
    }
}

private data class DiskUsage(
    val ioOperations: Long,
    val storageUsed: Long
)

/**
 * CALCULATE DIRECTORY SIZE
 * 
 * Recursively calculate total size of directory
 */
private fun calculateDirectorySize(directory: File): Long {
    var size = 0L
    directory.listFiles()?.forEach { file ->
        size += if (file.isDirectory) {
            calculateDirectorySize(file)
        } else {
            file.length()
        }
    }
    return size
}

/**
 * KILL CONTAINER
 * 
 * Forcefully terminate container process
 */
suspend fun killContainer(containerId: String) {
    val container = activeContainers[containerId] ?: return
    
    withContext(Dispatchers.IO) {
        try {
            // Send SIGKILL to process
            Runtime.getRuntime().exec("kill -9 ${container.processId}")
            
            betaLogger.log(
                LogLevel.INFO,
                "StrangersSafeComputeEngine",
                "Killed container $containerId (PID ${container.processId})"
            )
            
        } catch (e: Exception) {
            betaLogger.log(
                LogLevel.ERROR,
                "StrangersSafeComputeEngine",
                "Failed to kill container $containerId: ${e.message}"
            )
        }
    }
}
```

**Add to MicroContainer data class**:

```kotlin
data class MicroContainer(
    val containerId: String,
    val processId: Int,
    val limits: ResourceLimits,
    val startTime: Long,
    val memoryHistory: MutableList<Long> = mutableListOf(),
    val inputPipe: FileDescriptor,
    val outputPipe: FileDescriptor,
    val errorPipe: FileDescriptor
)
```

---

## Section 5: Executor Implementations

### 5.1 TaskExecutor Interface

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executors/TaskExecutor.kt`

**Define common interface**:

```kotlin
package com.ustadmobile.meshrabiya.service.compute.executors

import com.ustadmobile.meshrabiya.service.compute.model.*

/**
 * TASK EXECUTOR INTERFACE
 * 
 * Common interface for all task type executors
 */
interface TaskExecutor {
    
    /**
     * Execute task in sandbox container
     * 
     * @param context Task execution context
     * @param inputFiles Map of filename to file data
     * @param containerId Sandbox container ID
     * @return Execution result with outputs and metrics
     */
    suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult
    
    /**
     * Validate code bundle format
     * 
     * @param codeBundle Code bundle bytes
     * @return True if bundle is valid for this executor
     */
    fun validateCodeBundle(codeBundle: ByteArray): Boolean
    
    /**
     * Get supported task type
     */
    fun getSupportedTaskType(): TaskType
}
```

### 5.2 PythonExecutor Implementation

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executors/PythonExecutor.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute.executors

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.StrangersSafeComputeEngine
import com.ustadmobile.meshrabiya.log.betaLogger
import com.ustadmobile.meshrabiya.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * PYTHON EXECUTOR
 * 
 * Executes Python scripts using Chaquopy in sandboxed container
 * 
 * Code bundle format:
 * - Single .py file, OR
 * - ZIP archive with main.py entry point + dependencies
 */
class PythonExecutor(
    private val sandboxEngine: StrangersSafeComputeEngine
) : TaskExecutor {
    
    companion object {
        private const val TAG = "PythonExecutor"
        private const val MAIN_SCRIPT_NAME = "main.py"
    }
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. Prepare container workspace
            val workspaceDir = File("/data/containers/$containerId/workspace")
            workspaceDir.mkdirs()
            
            // 2. Extract code bundle
            val scriptFile = extractCodeBundle(context.codeBundle, workspaceDir)
            
            // 3. Write input files to workspace
            inputFiles.forEach { (filename, data) ->
                File(workspaceDir, filename).writeBytes(data)
            }
            
            // 4. Initialize Python runtime in container
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            val py = Python.getInstance()
            
            // 5. Execute script
            betaLogger.log(LogLevel.INFO, TAG, "Executing Python script: ${scriptFile.name}")
            
            val result = try {
                val module = py.getModule(scriptFile.nameWithoutExtension)
                val output = module.callAttr("main", inputFiles.keys.toTypedArray())
                output.toString()
            } catch (e: Exception) {
                betaLogger.log(LogLevel.ERROR, TAG, "Python execution error: ${e.message}")
                throw e
            }
            
            // 6. Collect output files
            val outputDir = File(workspaceDir, "outputs")
            val outputManifest = if (outputDir.exists()) {
                collectOutputFiles(outputDir, context.taskId)
            } else {
                emptyList()
            }
            
            // 7. Get final metrics
            val finalMetrics = sandboxEngine.getContainerMetrics(containerId)
            
            ExecutionResult(
                taskId = context.taskId,
                success = true,
                outputManifest = outputManifest,
                resultMessage = result,
                resourcesUsed = finalMetrics,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, TAG, "Python execution failed: ${e.message}")
            
            ExecutionResult(
                taskId = context.taskId,
                success = false,
                outputManifest = emptyList(),
                resourcesUsed = sandboxEngine.getContainerMetrics(containerId),
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: "Unknown error",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        }
    }
    
    /**
     * EXTRACT CODE BUNDLE
     * 
     * Extract Python code from bundle (single file or ZIP)
     */
    private fun extractCodeBundle(bundle: ByteArray, workspaceDir: File): File {
        // Check if bundle is a ZIP archive
        return if (bundle.size > 4 && 
                   bundle[0] == 0x50.toByte() && 
                   bundle[1] == 0x4B.toByte()) {
            // ZIP archive - extract all files
            ZipInputStream(bundle.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var mainScript: File? = null
                
                while (entry != null) {
                    val file = File(workspaceDir, entry.name)
                    
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                        
                        if (entry.name.endsWith(MAIN_SCRIPT_NAME)) {
                            mainScript = file
                        }
                    }
                    
                    entry = zip.nextEntry
                }
                
                mainScript ?: throw IllegalArgumentException("No main.py found in code bundle")
            }
        } else {
            // Single Python file
            val scriptFile = File(workspaceDir, MAIN_SCRIPT_NAME)
            scriptFile.writeBytes(bundle)
            scriptFile
        }
    }
    
    /**
     * COLLECT OUTPUT FILES
     * 
     * Scan output directory and create manifest
     */
    private fun collectOutputFiles(outputDir: File, taskId: String): List<FileReference> {
        val manifest = mutableListOf<FileReference>()
        
        outputDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                manifest.add(
                    FileReference(
                        fileId = sha256File(file),
                        fileName = file.name,
                        sizeBytes = file.length(),
                        mimeType = getMimeType(file.name)
                    )
                )
            }
        }
        
        return manifest
    }
    
    private fun sha256File(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun getMimeType(filename: String): String? {
        return when (filename.substringAfterLast('.', "")) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> null
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        // Check if it's a ZIP or starts with Python shebang/import
        return if (codeBundle.size > 4 && 
                   codeBundle[0] == 0x50.toByte() && 
                   codeBundle[1] == 0x4B.toByte()) {
            true // Valid ZIP
        } else {
            // Check for Python syntax markers
            val header = String(codeBundle.take(100).toByteArray())
            header.contains("#!/usr/bin/python") || 
            header.contains("import ") || 
            header.contains("def ")
        }
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.PYTHON
}
```

### 5.3 JVMExecutor Implementation

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executors/JVMExecutor.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute.executors

import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.StrangersSafeComputeEngine
import com.ustadmobile.meshrabiya.log.betaLogger
import com.ustadmobile.meshrabiya.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLClassLoader
import java.security.Permissions
import java.security.Policy
import java.security.ProtectionDomain
import java.util.jar.JarFile

/**
 * JVM EXECUTOR
 * 
 * Executes Java/Kotlin/JVM bytecode in sandboxed container
 * 
 * Code bundle format:
 * - JAR file with Main-Class manifest entry, OR
 * - ZIP archive with .class files and MANIFEST.txt specifying entry point
 * 
 * Security: Uses Java SecurityManager to restrict operations
 */
class JVMExecutor(
    private val sandboxEngine: StrangersSafeComputeEngine
) : TaskExecutor {
    
    companion object {
        private const val TAG = "JVMExecutor"
        private const val ENTRY_POINT_KEY = "Main-Class"
    }
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. Prepare container workspace
            val workspaceDir = File("/data/containers/$containerId/workspace")
            workspaceDir.mkdirs()
            
            // 2. Save code bundle as JAR
            val jarFile = File(workspaceDir, "code.jar")
            jarFile.writeBytes(context.codeBundle)
            
            // 3. Write input files
            val inputDir = File(workspaceDir, "inputs")
            inputDir.mkdirs()
            inputFiles.forEach { (filename, data) ->
                File(inputDir, filename).writeBytes(data)
            }
            
            // 4. Create output directory
            val outputDir = File(workspaceDir, "outputs")
            outputDir.mkdirs()
            
            // 5. Set up restricted security policy
            val originalSecurityManager = System.getSecurityManager()
            val restrictedPolicy = createRestrictedPolicy(workspaceDir)
            Policy.setPolicy(restrictedPolicy)
            System.setSecurityManager(SecurityManager())
            
            try {
                // 6. Load JAR and find entry point
                val jar = JarFile(jarFile)
                val manifest = jar.manifest
                val mainClass = manifest?.mainAttributes?.getValue(ENTRY_POINT_KEY)
                    ?: throw IllegalArgumentException("No Main-Class in JAR manifest")
                
                // 7. Create isolated ClassLoader
                val classLoader = URLClassLoader(
                    arrayOf(jarFile.toURI().toURL()),
                    null // No parent = isolated from app classes
                )
                
                // 8. Load and execute main class
                betaLogger.log(LogLevel.INFO, TAG, "Executing JVM class: $mainClass")
                
                val clazz = classLoader.loadClass(mainClass)
                val mainMethod = clazz.getMethod("main", Array<String>::class.java)
                
                // Pass input/output directories as arguments
                val args = arrayOf(inputDir.absolutePath, outputDir.absolutePath)
                mainMethod.invoke(null, args)
                
                // 9. Collect output files
                val outputManifest = collectOutputFiles(outputDir, context.taskId)
                
                // 10. Get final metrics
                val finalMetrics = sandboxEngine.getContainerMetrics(containerId)
                
                ExecutionResult(
                    taskId = context.taskId,
                    success = true,
                    outputManifest = outputManifest,
                    resourcesUsed = finalMetrics,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
                
            } finally {
                // Restore original security manager
                System.setSecurityManager(originalSecurityManager)
            }
            
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, TAG, "JVM execution failed: ${e.message}")
            
            ExecutionResult(
                taskId = context.taskId,
                success = false,
                outputManifest = emptyList(),
                resourcesUsed = sandboxEngine.getContainerMetrics(containerId),
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: "Unknown error",
                errorType = when (e) {
                    is SecurityException -> ExecutionErrorType.SANDBOX_VIOLATION
                    else -> ExecutionErrorType.RUNTIME_ERROR
                }
            )
        }
    }
    
    /**
     * CREATE RESTRICTED POLICY
     * 
     * Java Security Policy that restricts file and network access
     */
    private fun createRestrictedPolicy(workspaceDir: File): Policy {
        return object : Policy() {
            override fun getPermissions(domain: ProtectionDomain): Permissions {
                val permissions = Permissions()
                
                // Allow read/write ONLY to workspace directory
                permissions.add(java.io.FilePermission(
                    workspaceDir.absolutePath + "/-",
                    "read,write"
                ))
                
                // Allow basic runtime permissions
                permissions.add(RuntimePermission("accessDeclaredMembers"))
                permissions.add(RuntimePermission("createClassLoader"))
                
                // NO network permissions
                // NO system property access
                // NO native library loading
                
                return permissions
            }
        }
    }
    
    private fun collectOutputFiles(outputDir: File, taskId: String): List<FileReference> {
        val manifest = mutableListOf<FileReference>()
        
        outputDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                manifest.add(
                    FileReference(
                        fileId = sha256File(file),
                        fileName = file.name,
                        sizeBytes = file.length()
                    )
                )
            }
        }
        
        return manifest
    }
    
    private fun sha256File(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        // Check for JAR/ZIP magic bytes (PK)
        return codeBundle.size > 4 && 
               codeBundle[0] == 0x50.toByte() && 
               codeBundle[1] == 0x4B.toByte()
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.JVM
}
```

### 5.4 JSExecutor Implementation (Stub)

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executors/JSExecutor.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute.executors

import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.StrangersSafeComputeEngine

/**
 * JAVASCRIPT EXECUTOR
 * 
 * Executes JavaScript code using J2V8 or Node.js runtime
 * 
 * TODO: Implement J2V8 integration
 * Requires: com.eclipsesource.j2v8:j2v8 dependency
 */
class JSExecutor(
    private val sandboxEngine: StrangersSafeComputeEngine
) : TaskExecutor {
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        TODO("JavaScript executor not yet implemented - requires J2V8 integration")
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        val header = String(codeBundle.take(100).toByteArray())
        return header.contains("function ") || 
               header.contains("const ") || 
               header.contains("var ") ||
               header.contains("=>")
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.JAVASCRIPT
}
```

### 5.5 MLNativeExecutor Implementation (Stub)

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executors/MLNativeExecutor.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute.executors

import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.StrangersSafeComputeEngine

/**
 * ML NATIVE EXECUTOR
 * 
 * Executes native ML models (TFLite, ML Kit)
 * 
 * Supported formats:
 * - TensorFlow Lite (.tflite)
 * - ML Kit models
 * - ONNX Runtime (future)
 * 
 * TODO: Implement TFLite interpreter integration
 */
class MLNativeExecutor(
    private val sandboxEngine: StrangersSafeComputeEngine
) : TaskExecutor {
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        TODO("ML Native executor not yet implemented - requires TFLite integration")
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        // Check for TFLite magic bytes
        return codeBundle.size > 8 && 
               codeBundle[0] == 0x54.toByte() && // 'T'
               codeBundle[1] == 0x46.toByte() && // 'F'
               codeBundle[2] == 0x4C.toByte()    // 'L'
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.ML_NATIVE
}
```

### 5.6 WorkflowExecutor Implementation (Stub)

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executors/WorkflowExecutor.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute.executors

import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.StrangersSafeComputeEngine
import com.ustadmobile.meshrabiya.service.compute.TaskManager

/**
 * WORKFLOW EXECUTOR
 * 
 * Executes multi-step workflows (orchestrates other task types)
 * 
 * Code bundle format: JSON/YAML workflow definition
 * 
 * Example workflow:
 * {
 *   "steps": [
 *     {"type": "PYTHON", "script": "preprocess.py", "input": "data.csv"},
 *     {"type": "ML_NATIVE", "model": "classifier.tflite", "input": "step1.output"},
 *     {"type": "PYTHON", "script": "postprocess.py", "input": "step2.output"}
 *   ]
 * }
 * 
 * TODO: Implement workflow definition parsing and step orchestration
 */
class WorkflowExecutor(
    private val sandboxEngine: StrangersSafeComputeEngine,
    private val taskManager: TaskManager
) : TaskExecutor {
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        TODO("Workflow executor not yet implemented - requires workflow DSL")
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        val header = String(codeBundle.take(100).toByteArray())
        return header.trim().startsWith("{") || header.trim().startsWith("---") // JSON or YAML
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.WORKFLOW
}
```

---

**END OF FIRST HALF OF PART 2**

This covers:
4. ✅ Resource Monitoring Implementation (complete polling loop, metric tracking, limit enforcement)
5. ✅ Executor Implementations (TaskExecutor interface, PythonExecutor complete, JVMExecutor complete, stubs for JS/ML/Workflow)

**Next section will cover**:
6. Runtime Management
7. Service Library Enhancements
8. Integration Points (task acceptance message, node selection salvage)
9. Testing Strategy
10. Implementation Checklist
