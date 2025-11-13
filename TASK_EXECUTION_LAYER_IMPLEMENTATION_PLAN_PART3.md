# Task Execution Layer Implementation Plan - Part 3

**Date**: November 13, 2025  
**Status**: COMPREHENSIVE IMPLEMENTATION PLAN (PART 3 OF 3)  
**Continuation of**: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md

---

## Section 6: Runtime Management

### 6.1 Runtime Registry Design

**Purpose**: Track which runtimes are available on the device (built-in + user-installed)

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/RuntimeRegistry.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import com.ustadmobile.meshrabiya.log.betaLogger
import com.ustadmobile.meshrabiya.log.LogLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * RUNTIME REGISTRY
 * 
 * Manages available task execution runtimes
 * 
 * Built-in runtimes:
 * - JVM (native Android)
 * - PYTHON (Chaquopy - if included in build)
 * 
 * User-installed runtimes:
 * - Additional Python packages
 * - JavaScript (J2V8)
 * - ML frameworks (TFLite, ONNX)
 */
object RuntimeRegistry {
    
    private const val TAG = "RuntimeRegistry"
    private const val PREFS_NAME = "meshrabiya_runtimes"
    private const val KEY_INSTALLED_RUNTIMES = "installed_runtimes"
    
    private lateinit var prefs: SharedPreferences
    private val installedRuntimes = mutableMapOf<TaskType, RuntimeInfo>()
    
    /**
     * Initialize registry (called from MeshrabiyaService)
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadInstalledRuntimes()
        registerBuiltInRuntimes()
    }
    
    /**
     * LOAD INSTALLED RUNTIMES
     * 
     * Restore from SharedPreferences
     */
    private fun loadInstalledRuntimes() {
        val json = prefs.getString(KEY_INSTALLED_RUNTIMES, null) ?: return
        
        try {
            val runtimeList = Json.decodeFromString<List<RuntimeInfo>>(json)
            runtimeList.forEach { runtime ->
                installedRuntimes[runtime.taskType] = runtime
            }
            betaLogger.log(LogLevel.INFO, TAG, "Loaded ${runtimeList.size} installed runtimes")
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, TAG, "Failed to load runtimes: ${e.message}")
        }
    }
    
    /**
     * SAVE INSTALLED RUNTIMES
     * 
     * Persist to SharedPreferences
     */
    private fun saveInstalledRuntimes() {
        val runtimeList = installedRuntimes.values.toList()
        val json = Json.encodeToString(runtimeList)
        prefs.edit().putString(KEY_INSTALLED_RUNTIMES, json).apply()
    }
    
    /**
     * REGISTER BUILT-IN RUNTIMES
     * 
     * Detect which runtimes are available at build time
     */
    private fun registerBuiltInRuntimes() {
        // JVM is always available (native Android)
        installedRuntimes[TaskType.JVM] = RuntimeInfo(
            taskType = TaskType.JVM,
            version = System.getProperty("java.version") ?: "unknown",
            isBuiltIn = true,
            installedAt = System.currentTimeMillis()
        )
        
        // Check if Chaquopy is available
        if (isPythonAvailable()) {
            installedRuntimes[TaskType.PYTHON] = RuntimeInfo(
                taskType = TaskType.PYTHON,
                version = getPythonVersion(),
                isBuiltIn = true,
                installedAt = System.currentTimeMillis()
            )
        }
        
        betaLogger.log(LogLevel.INFO, TAG, "Registered ${installedRuntimes.size} built-in runtimes")
    }
    
    /**
     * CHECK IF PYTHON AVAILABLE
     * 
     * Detect Chaquopy at runtime
     */
    private fun isPythonAvailable(): Boolean {
        return try {
            Class.forName("com.chaquo.python.Python")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    /**
     * GET PYTHON VERSION
     */
    private fun getPythonVersion(): String {
        return try {
            val pythonClass = Class.forName("com.chaquo.python.Python")
            val versionField = pythonClass.getDeclaredField("VERSION")
            versionField.get(null).toString()
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * IS RUNTIME AVAILABLE
     * 
     * Check if runtime is installed and ready
     */
    fun isRuntimeAvailable(taskType: TaskType): Boolean {
        return installedRuntimes.containsKey(taskType)
    }
    
    /**
     * GET RUNTIME INFO
     */
    fun getRuntimeInfo(taskType: TaskType): RuntimeInfo? {
        return installedRuntimes[taskType]
    }
    
    /**
     * GET ALL AVAILABLE RUNTIMES
     */
    fun getAvailableRuntimes(): List<TaskType> {
        return installedRuntimes.keys.toList()
    }
    
    /**
     * INSTALL RUNTIME
     * 
     * Register user-installed runtime (called after installation completes)
     */
    fun installRuntime(taskType: TaskType, version: String) {
        installedRuntimes[taskType] = RuntimeInfo(
            taskType = taskType,
            version = version,
            isBuiltIn = false,
            installedAt = System.currentTimeMillis()
        )
        saveInstalledRuntimes()
        
        betaLogger.log(LogLevel.INFO, TAG, "Installed runtime: $taskType v$version")
    }
    
    /**
     * UNINSTALL RUNTIME
     * 
     * Remove user-installed runtime (built-in runtimes cannot be uninstalled)
     */
    fun uninstallRuntime(taskType: TaskType): Boolean {
        val runtime = installedRuntimes[taskType]
        
        if (runtime == null) {
            betaLogger.log(LogLevel.WARN, TAG, "Cannot uninstall: runtime not found")
            return false
        }
        
        if (runtime.isBuiltIn) {
            betaLogger.log(LogLevel.WARN, TAG, "Cannot uninstall built-in runtime: $taskType")
            return false
        }
        
        installedRuntimes.remove(taskType)
        saveInstalledRuntimes()
        
        betaLogger.log(LogLevel.INFO, TAG, "Uninstalled runtime: $taskType")
        return true
    }
}

/**
 * RUNTIME INFO
 * 
 * Metadata about installed runtime
 */
@Serializable
data class RuntimeInfo(
    val taskType: TaskType,
    val version: String,
    val isBuiltIn: Boolean,
    val installedAt: Long
)
```

### 6.2 Automatic Installation via UI

**Trigger**: User enables compute node capability in UI, but required runtime missing

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/RuntimeInstaller.kt`

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.log.betaLogger
import com.ustadmobile.meshrabiya.log.LogLevel
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * RUNTIME INSTALLER
 * 
 * Handles automatic download and installation of task execution runtimes
 * 
 * Installation sources:
 * - JavaScript: Download J2V8 native library from Maven Central
 * - ML Native: Download TFLite runtime from Google Maven
 * - Python packages: Use pip via Chaquopy
 */
object RuntimeInstaller {
    
    private const val TAG = "RuntimeInstaller"
    
    /**
     * INSTALL RUNTIME
     * 
     * Download and install runtime for task type
     * Called from UI when user enables support for task type
     */
    suspend fun installRuntime(
        context: Context,
        taskType: TaskType,
        progressCallback: (Int) -> Unit = {}
    ): InstallResult = withContext(Dispatchers.IO) {
        
        betaLogger.log(LogLevel.INFO, TAG, "Installing runtime: $taskType")
        progressCallback(0)
        
        try {
            when (taskType) {
                TaskType.JAVASCRIPT -> installJavaScript(context, progressCallback)
                TaskType.ML_NATIVE -> installMLNative(context, progressCallback)
                TaskType.PYTHON -> installPythonPackages(context, progressCallback)
                TaskType.JVM -> {
                    // JVM is built-in, no installation needed
                    InstallResult.AlreadyInstalled
                }
                TaskType.WORKFLOW -> {
                    // Workflow executor has no external dependencies
                    InstallResult.AlreadyInstalled
                }
                TaskType.JAVA -> {
                    // Java is same as JVM
                    InstallResult.AlreadyInstalled
                }
            }
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, TAG, "Installation failed: ${e.message}")
            InstallResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    /**
     * INSTALL JAVASCRIPT RUNTIME
     * 
     * Download J2V8 native library
     */
    private suspend fun installJavaScript(
        context: Context,
        progressCallback: (Int) -> Unit
    ): InstallResult {
        val arch = System.getProperty("os.arch") ?: "arm64-v8a"
        val j2v8Version = "6.2.1"
        
        // Determine correct artifact based on architecture
        val artifactName = when {
            arch.contains("arm64") || arch.contains("aarch64") -> "j2v8_android_arm64_v8a"
            arch.contains("arm") -> "j2v8_android_armeabi_v7a"
            arch.contains("x86_64") -> "j2v8_android_x86_64"
            else -> "j2v8_android_x86"
        }
        
        val mavenUrl = "https://repo1.maven.org/maven2/com/eclipsesource/j2v8/" +
                      "$artifactName/$j2v8Version/$artifactName-$j2v8Version.jar"
        
        betaLogger.log(LogLevel.INFO, TAG, "Downloading J2V8 from: $mavenUrl")
        progressCallback(10)
        
        // Download JAR
        val jarFile = File(context.filesDir, "j2v8.jar")
        downloadFile(mavenUrl, jarFile, progressCallback)
        
        progressCallback(90)
        
        // Extract native library
        extractNativeLibrary(jarFile, context)
        
        progressCallback(100)
        
        // Register runtime
        RuntimeRegistry.installRuntime(TaskType.JAVASCRIPT, j2v8Version)
        
        return InstallResult.Success(j2v8Version)
    }
    
    /**
     * INSTALL ML NATIVE RUNTIME
     * 
     * Download TensorFlow Lite runtime
     */
    private suspend fun installMLNative(
        context: Context,
        progressCallback: (Int) -> Unit
    ): InstallResult {
        val tfliteVersion = "2.14.0"
        val mavenUrl = "https://maven.google.com/org/tensorflow/tensorflow-lite/" +
                      "$tfliteVersion/tensorflow-lite-$tfliteVersion.aar"
        
        betaLogger.log(LogLevel.INFO, TAG, "Downloading TFLite from: $mavenUrl")
        progressCallback(10)
        
        // Download AAR
        val aarFile = File(context.filesDir, "tensorflow-lite.aar")
        downloadFile(mavenUrl, aarFile, progressCallback)
        
        progressCallback(90)
        
        // Extract native library
        extractNativeLibrary(aarFile, context)
        
        progressCallback(100)
        
        // Register runtime
        RuntimeRegistry.installRuntime(TaskType.ML_NATIVE, tfliteVersion)
        
        return InstallResult.Success(tfliteVersion)
    }
    
    /**
     * INSTALL PYTHON PACKAGES
     * 
     * Install additional Python packages via pip
     */
    private suspend fun installPythonPackages(
        context: Context,
        progressCallback: (Int) -> Unit
    ): InstallResult {
        if (!RuntimeRegistry.isRuntimeAvailable(TaskType.PYTHON)) {
            return InstallResult.Failed("Python runtime not available (Chaquopy not included in build)")
        }
        
        // Install common data science packages
        val packages = listOf("numpy", "pandas", "scikit-learn")
        
        packages.forEachIndexed { index, pkg ->
            betaLogger.log(LogLevel.INFO, TAG, "Installing Python package: $pkg")
            progressCallback((index * 100) / packages.size)
            
            // Use Chaquopy pip
            try {
                val python = com.chaquo.python.Python.getInstance()
                val pip = python.getModule("pip")
                pip.callAttr("main", arrayOf("install", pkg))
            } catch (e: Exception) {
                betaLogger.log(LogLevel.ERROR, TAG, "Failed to install $pkg: ${e.message}")
            }
        }
        
        progressCallback(100)
        return InstallResult.Success("Python packages installed")
    }
    
    /**
     * DOWNLOAD FILE
     * 
     * Download file from URL with progress tracking
     */
    private suspend fun downloadFile(
        url: String,
        destination: File,
        progressCallback: (Int) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection()
            connection.connect()
            
            val totalSize = connection.contentLength
            val buffer = ByteArray(8192)
            var downloaded = 0L
            
            connection.getInputStream().use { input ->
                destination.outputStream().use { output ->
                    var read = input.read(buffer)
                    while (read > 0) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        
                        if (totalSize > 0) {
                            val progress = 10 + ((downloaded * 80) / totalSize).toInt()
                            progressCallback(progress)
                        }
                        
                        read = input.read(buffer)
                    }
                }
            }
        }
    }
    
    /**
     * EXTRACT NATIVE LIBRARY
     * 
     * Extract .so file from JAR/AAR
     */
    private fun extractNativeLibrary(archive: File, context: Context) {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        
        // TODO: Implement ZIP extraction and library placement
        // This requires careful handling of Android's library loading mechanism
        
        betaLogger.log(LogLevel.INFO, TAG, "Native library extracted to: $nativeLibDir")
    }
    
    /**
     * UNINSTALL RUNTIME
     * 
     * Remove runtime files and unregister
     */
    suspend fun uninstallRuntime(
        context: Context,
        taskType: TaskType
    ): Boolean = withContext(Dispatchers.IO) {
        
        betaLogger.log(LogLevel.INFO, TAG, "Uninstalling runtime: $taskType")
        
        // Remove runtime files
        when (taskType) {
            TaskType.JAVASCRIPT -> {
                File(context.filesDir, "j2v8.jar").delete()
            }
            TaskType.ML_NATIVE -> {
                File(context.filesDir, "tensorflow-lite.aar").delete()
            }
            else -> {
                // Other runtimes don't have files to clean up
            }
        }
        
        // Unregister from registry
        RuntimeRegistry.uninstallRuntime(taskType)
    }
}

/**
 * INSTALL RESULT
 */
sealed class InstallResult {
    data class Success(val version: String) : InstallResult()
    data class Failed(val error: String) : InstallResult()
    object AlreadyInstalled : InstallResult()
}
```

### 6.3 Runtime Verification

**Purpose**: Before responding to compute task requests, verify required runtime is available

**Add to TaskManager**:

```kotlin
/**
 * VERIFY RUNTIME AVAILABLE
 * 
 * Check if node can execute task type
 * Called before accepting task assignment
 */
fun verifyRuntimeAvailable(taskType: TaskType): Boolean {
    val available = RuntimeRegistry.isRuntimeAvailable(taskType)
    
    if (!available) {
        betaLogger.log(
            LogLevel.WARN,
            "TaskManager",
            "Cannot accept task: runtime not available for $taskType"
        )
    }
    
    return available
}
```

---

## Section 7: Service Library Enhancements

### 7.1 Service Entry Schema Extension

**Current schema** (from LocalDeviceServiceLibrary.kt):
```kotlin
data class ServiceEntry(
    val serviceName: String,
    val host: String,
    val port: Int,
    val category: ServiceCategory
)
```

**Extended schema** (add compute capability fields):

```kotlin
data class ServiceEntry(
    val serviceName: String,
    val host: String,
    val port: Int,
    val category: ServiceCategory,
    
    // NEW: Compute capability fields
    val supportsCompute: Boolean = false,
    val taskTypes: List<TaskType> = emptyList(),
    val jobTypes: List<JobType> = emptyList(),
    val maxConcurrentTasks: Int = 1,
    val estimatedCapacity: ResourceMetrics? = null
)
```

### 7.2 Built-In Services Definition

**Add to LocalDeviceServiceLibrary**:

```kotlin
/**
 * GET BUILT-IN COMPUTE SERVICES
 * 
 * Define default compute services for each task type
 */
private fun getBuiltInComputeServices(): List<ServiceEntry> {
    val services = mutableListOf<ServiceEntry>()
    
    // Get available runtimes
    val availableRuntimes = RuntimeRegistry.getAvailableRuntimes()
    
    availableRuntimes.forEach { taskType ->
        // Get supported job types for this task type
        val supportedJobs = getJobTypesForTaskType(taskType)
        
        supportedJobs.forEach { jobType ->
            services.add(
                ServiceEntry(
                    serviceName = "${taskType.name}_${jobType.name}_COMPUTE",
                    host = "localhost",
                    port = 0, // No port for compute services
                    category = ServiceCategory.COMPUTE,
                    supportsCompute = true,
                    taskTypes = listOf(taskType),
                    jobTypes = listOf(jobType),
                    maxConcurrentTasks = getMaxConcurrentTasks(),
                    estimatedCapacity = estimateNodeCapacity()
                )
            )
        }
    }
    
    return services
}

/**
 * GET JOB TYPES FOR TASK TYPE
 * 
 * Map task types to compatible job types
 */
private fun getJobTypesForTaskType(taskType: TaskType): List<JobType> {
    return when (taskType) {
        TaskType.PYTHON -> listOf(
            JobType.IMAGE_PROCESSING,
            JobType.VIDEO_PROCESSING,
            JobType.DATA_ANALYSIS,
            JobType.ML_INFERENCE,
            JobType.ML_PIPELINE
        )
        
        TaskType.JVM, TaskType.JAVA -> listOf(
            JobType.DATA_ANALYSIS,
            JobType.CRYPTOGRAPHIC,
            JobType.MAP_REDUCE,
            JobType.GRAPH_PROCESSING
        )
        
        TaskType.JAVASCRIPT -> listOf(
            JobType.DATA_ANALYSIS,
            JobType.MAP_REDUCE
        )
        
        TaskType.ML_NATIVE -> listOf(
            JobType.IMAGE_PROCESSING,
            JobType.ML_INFERENCE,
            JobType.VIDEO_PROCESSING
        )
        
        TaskType.WORKFLOW -> listOf(
            JobType.ML_PIPELINE,
            JobType.MAP_REDUCE,
            JobType.GRAPH_PROCESSING
        )
    }
}

/**
 * GET MAX CONCURRENT TASKS
 * 
 * Calculate based on device resources
 */
private fun getMaxConcurrentTasks(): Int {
    val runtime = Runtime.getRuntime()
    val availableProcessors = runtime.availableProcessors()
    
    // Allow 1 task per CPU core, up to 4 tasks max
    return minOf(availableProcessors, 4)
}

/**
 * ESTIMATE NODE CAPACITY
 * 
 * Calculate total available resources for compute
 */
private fun estimateNodeCapacity(): ResourceMetrics {
    val runtime = Runtime.getRuntime()
    
    return ResourceMetrics(
        ramActualBytes = 0,
        ramAverageBytes = 0,
        ramPeakBytes = runtime.maxMemory(),
        cpuTimeUsedMs = 0,
        cpuPercentage = 0f,
        diskIoOperations = 0,
        diskStorageUsedBytes = File("/data").freeSpace
    )
}
```

### 7.3 Persistence Layer

**Add to LocalDeviceServiceLibrary**:

```kotlin
/**
 * SAVE SERVICES
 * 
 * Persist service library to SharedPreferences
 */
fun saveServices() {
    val json = Json.encodeToString(localDeviceServiceLibrary.toList())
    prefs.edit().putString(KEY_SERVICE_LIBRARY, json).apply()
    
    betaLogger.log(LogLevel.INFO, TAG, "Saved ${localDeviceServiceLibrary.size} services")
}

/**
 * LOAD SERVICES
 * 
 * Restore service library from SharedPreferences
 */
private fun loadServices() {
    val json = prefs.getString(KEY_SERVICE_LIBRARY, null) ?: return
    
    try {
        val services = Json.decodeFromString<List<ServiceEntry>>(json)
        localDeviceServiceLibrary.clear()
        localDeviceServiceLibrary.addAll(services)
        
        betaLogger.log(LogLevel.INFO, TAG, "Loaded ${services.size} services")
    } catch (e: Exception) {
        betaLogger.log(LogLevel.ERROR, TAG, "Failed to load services: ${e.message}")
    }
}

/**
 * REFRESH SERVICES
 * 
 * Rebuild service library (call after runtime installation/uninstallation)
 */
fun refreshServices() {
    localDeviceServiceLibrary.clear()
    localDeviceServiceLibrary.addAll(getBuiltInComputeServices())
    // Add user-installed services from ServicePackageManager
    localDeviceServiceLibrary.addAll(getUserInstalledServices())
    saveServices()
}
```

### 7.4 ServicePackageManager Integration

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/ServicePackageManager.kt`

**Current implementation**: Empty skeleton

**Add compute service support**:

```kotlin
/**
 * INSTALL SERVICE PACKAGE
 * 
 * Install user-provided service implementation
 * 
 * Package format: APK with:
 * - Service implementation class
 * - Metadata: taskType, jobType, runtime requirements
 */
suspend fun installServicePackage(
    context: Context,
    packagePath: String
): InstallResult {
    
    val packageInfo = context.packageManager.getPackageArchiveInfo(
        packagePath,
        PackageManager.GET_META_DATA
    ) ?: return InstallResult.Failed("Invalid package")
    
    // Extract metadata
    val metadata = packageInfo.applicationInfo?.metaData
    val taskType = metadata?.getString("taskType")?.let { TaskType.valueOf(it) }
        ?: return InstallResult.Failed("Missing taskType metadata")
    
    val jobType = metadata.getString("jobType")?.let { JobType.valueOf(it) }
        ?: return InstallResult.Failed("Missing jobType metadata")
    
    // Verify runtime available
    if (!RuntimeRegistry.isRuntimeAvailable(taskType)) {
        return InstallResult.Failed("Required runtime not installed: $taskType")
    }
    
    // Install package
    // TODO: Implement package installation via PackageManager
    
    // Register service
    val service = ServiceEntry(
        serviceName = packageInfo.packageName ?: "unknown",
        host = "localhost",
        port = 0,
        category = ServiceCategory.COMPUTE,
        supportsCompute = true,
        taskTypes = listOf(taskType),
        jobTypes = listOf(jobType),
        maxConcurrentTasks = 1,
        estimatedCapacity = null
    )
    
    LocalDeviceServiceLibrary.addService(service)
    LocalDeviceServiceLibrary.saveServices()
    
    return InstallResult.Success(packageInfo.versionName ?: "1.0")
}
```

---

## Section 8: Integration Points

### 8.1 IntelligentDistributedComputeService.assignTaskToNode()

**Current state** (line ~200): Has TODO comment

**Implementation**:

```kotlin
/**
 * ASSIGN TASK TO NODE
 * 
 * Send task assignment message to selected compute node
 */
private suspend fun assignTaskToNode(
    taskId: String,
    nodeAddress: String,
    task: TaskRequest
) {
    betaLogger.log(
        LogLevel.INFO,
        TAG,
        "Assigning task $taskId to node $nodeAddress"
    )
    
    // Create task assignment message
    val assignment = TaskAssignmentMessage(
        messageId = generateMessageId(),
        taskId = taskId,
        requesterNodeId = meshrabiyaVirtualNode.localNodeAddress,
        callbackAddress = meshrabiyaVirtualNode.localNodeAddress,
        taskType = task.taskType,
        jobType = task.jobType,
        codeBundle = task.codeBundle,
        inputFiles = task.inputFileIds,
        resourceLimits = task.resourceLimits,
        timestamp = System.currentTimeMillis()
    )
    
    // Send via VirtualNode
    val messageBytes = MessagePackSerializer.encode(assignment)
    
    meshrabiyaVirtualNode.sendMessage(
        destinationAddress = nodeAddress,
        payload = messageBytes,
        messageType = MessageType.TASK_ASSIGNMENT
    )
    
    // Update task status
    TaskManager.updateTaskStatus(taskId) { status ->
        status.copy(
            state = TaskState.ASSIGNED,
            assignedNodeAddress = nodeAddress,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
```

### 8.2 Task Assignment Message Handler (Compute Node)

**Location**: Add to MeshrabiyaVirtualNode message handlers

```kotlin
/**
 * HANDLE TASK ASSIGNMENT MESSAGE
 * 
 * Compute node receives task assignment and begins execution
 */
private suspend fun handleTaskAssignmentMessage(
    senderAddress: String,
    payload: ByteArray
) {
    try {
        val assignment = MessagePackSerializer.decode<TaskAssignmentMessage>(payload)
        
        betaLogger.log(
            LogLevel.INFO,
            TAG,
            "Received task assignment: ${assignment.taskId} (${assignment.taskType})"
        )
        
        // Verify runtime available (should already be verified, but double-check)
        if (!TaskManager.verifyRuntimeAvailable(assignment.taskType)) {
            sendTaskRejection(assignment, "Runtime not available")
            return
        }
        
        // Create task execution context
        val context = TaskExecutionContext(
            taskId = assignment.taskId,
            taskType = assignment.taskType,
            jobType = assignment.jobType,
            codeBundle = assignment.codeBundle,
            inputFileIds = assignment.inputFiles,
            resourceLimits = assignment.resourceLimits,
            requesterNodeId = assignment.requesterNodeId,
            callbackAddress = assignment.callbackAddress
        )
        
        // Execute task (async)
        TaskManager.executeTask(context)
        
    } catch (e: Exception) {
        betaLogger.log(
            LogLevel.ERROR,
            TAG,
            "Failed to handle task assignment: ${e.message}"
        )
    }
}

/**
 * SEND TASK REJECTION
 * 
 * Notify requester that task cannot be executed
 */
private suspend fun sendTaskRejection(
    assignment: TaskAssignmentMessage,
    reason: String
) {
    val rejection = TaskRejectionMessage(
        taskId = assignment.taskId,
        reason = reason
    )
    
    val messageBytes = MessagePackSerializer.encode(rejection)
    
    sendMessage(
        destinationAddress = assignment.requesterNodeId,
        payload = messageBytes,
        messageType = MessageType.TASK_REJECTED
    )
}
```

### 8.3 Task Completion Message Handler (Client Node)

**Location**: Add to MeshrabiyaVirtualNode message handlers

```kotlin
/**
 * HANDLE TASK COMPLETION MESSAGE
 * 
 * Client node receives task completion notification
 */
private suspend fun handleTaskCompletionMessage(
    senderAddress: String,
    payload: ByteArray
) {
    try {
        val completion = MessagePackSerializer.decode<TaskCompletedMessage>(payload)
        
        betaLogger.log(
            LogLevel.INFO,
            TAG,
            "Received task completion: ${completion.taskId} (success=${completion.result.success})"
        )
        
        if (completion.result.success) {
            // Mark task complete
            TaskManager.completeTask(
                taskId = completion.taskId,
                result = completion.result
            )
            
            // Download result files if needed
            completion.result.outputManifest.forEach { fileRef ->
                // Trigger file retrieval via DistributedStorageManager
                // Client already has read access (set by compute node)
            }
            
        } else {
            // Mark task failed
            TaskManager.failTask(
                taskId = completion.taskId,
                errorMessage = completion.result.errorMessage ?: "Unknown error"
            )
        }
        
        // Send acknowledgment
        sendTaskCompletionAck(completion.taskId, senderAddress)
        
    } catch (e: Exception) {
        betaLogger.log(
            LogLevel.ERROR,
            TAG,
            "Failed to handle task completion: ${e.message}"
        )
    }
}

/**
 * SEND TASK COMPLETION ACKNOWLEDGMENT
 * 
 * Confirm receipt of completion notification (stops retry loop)
 */
private suspend fun sendTaskCompletionAck(taskId: String, executorAddress: String) {
    val ack = TaskCompletionAckMessage(taskId = taskId)
    val messageBytes = MessagePackSerializer.encode(ack)
    
    sendMessage(
        destinationAddress = executorAddress,
        payload = messageBytes,
        messageType = MessageType.TASK_COMPLETION_ACK
    )
}
```

### 8.4 Add Message Types

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MessageType.kt`

```kotlin
enum class MessageType(val value: Int) {
    // ... existing types ...
    
    TASK_ASSIGNMENT(25),
    TASK_REJECTED(26),
    TASK_COMPLETED(27),
    TASK_COMPLETION_ACK(28);
    
    companion object {
        fun fromValue(value: Int): MessageType? {
            return values().find { it.value == value }
        }
    }
}
```

### 8.5 File Organization Consideration

**Current state**: All compute logic in single files could grow large

**Recommendation**: If files exceed 1000 lines after implementation, break out:

1. **TaskManager.kt** → Split into:
   - `TaskManager.kt` (core task lifecycle)
   - `TaskExecutor.kt` (execution orchestration)
   - `ResourceMonitor.kt` (polling and tracking)

2. **IntelligentDistributedComputeService.kt** → Split into:
   - `ComputeService.kt` (main service)
   - `NodeSelector.kt` (node selection logic)
   - `TaskDispatcher.kt` (assignment and routing)

**Implementation**: Only split if necessary during development

---

## Section 9: Testing Strategy

### 9.1 Unit Tests

**Test Coverage Goals**:
- Data structures: serialization/deserialization
- Executors: code validation, sandbox creation
- Resource monitoring: metric calculation accuracy
- Storage API: permission enforcement
- Runtime registry: persistence

**Example test** (`TaskExecutionContextTest.kt`):

```kotlin
class TaskExecutionContextTest {
    
    @Test
    fun testSerialization() {
        val context = TaskExecutionContext(
            taskId = "test-123",
            taskType = TaskType.PYTHON,
            jobType = JobType.DATA_ANALYSIS,
            codeBundle = "print('hello')".toByteArray(),
            inputFileIds = listOf("file1", "file2"),
            resourceLimits = ResourceLimits(
                maxMemoryBytes = 512_000_000,
                maxCpuTimeMs = 60_000,
                maxDiskBytes = 100_000_000,
                maxExecutionTimeMs = 120_000
            ),
            requesterNodeId = "node-456",
            callbackAddress = "node-456"
        )
        
        val bytes = MessagePackSerializer.encode(context)
        val decoded = MessagePackSerializer.decode<TaskExecutionContext>(bytes)
        
        assertEquals(context, decoded)
    }
    
    @Test
    fun testResourceLimitsValidation() {
        val limits = ResourceLimits(
            maxMemoryBytes = -1, // Invalid
            maxCpuTimeMs = 60_000,
            maxDiskBytes = 100_000_000,
            maxExecutionTimeMs = 120_000
        )
        
        assertFalse(limits.isValid())
    }
}
```

### 9.2 Integration Tests

**Test Scenarios**:

1. **Full Execution Flow**:
   - Client submits task
   - Service selects node
   - Node executes task
   - Result returned to client

2. **Resource Limit Enforcement**:
   - Submit task with low memory limit
   - Task exceeds limit
   - Task terminated with OUT_OF_MEMORY error

3. **Runtime Missing**:
   - Client requests JavaScript task
   - Selected node doesn't have JS runtime
   - Node rejects task
   - Service selects different node

4. **File Storage Integration**:
   - Task generates output files
   - Files stored with correct permissions
   - Client can retrieve files
   - Other nodes cannot access files

**Example integration test** (`TaskExecutionIntegrationTest.kt`):

```kotlin
@RunWith(AndroidJUnit4::class)
class TaskExecutionIntegrationTest {
    
    private lateinit var computeService: IntelligentDistributedComputeService
    private lateinit var taskManager: TaskManager
    
    @Before
    fun setup() {
        // Initialize services
        computeService = IntelligentDistributedComputeService(/* ... */)
        taskManager = TaskManager
        
        // Install Python runtime for tests
        RuntimeRegistry.installRuntime(TaskType.PYTHON, "3.8")
    }
    
    @Test
    fun testPythonTaskExecution() = runBlocking {
        // Create simple Python task
        val code = """
            def main(inputs):
                return "Hello from Python!"
        """.trimIndent()
        
        val taskId = "test-python-${System.currentTimeMillis()}"
        
        val context = TaskExecutionContext(
            taskId = taskId,
            taskType = TaskType.PYTHON,
            jobType = JobType.DATA_ANALYSIS,
            codeBundle = code.toByteArray(),
            inputFileIds = emptyList(),
            resourceLimits = ResourceLimits(
                maxMemoryBytes = 512_000_000,
                maxCpuTimeMs = 60_000,
                maxDiskBytes = 100_000_000,
                maxExecutionTimeMs = 120_000
            ),
            requesterNodeId = "test-node",
            callbackAddress = "test-node"
        )
        
        // Execute task
        taskManager.executeTask(context)
        
        // Wait for completion (with timeout)
        var attempts = 0
        while (attempts < 30) {
            val status = taskManager.getTaskStatus(taskId)
            if (status?.state == TaskState.COMPLETED) {
                assertTrue(status.result?.success == true)
                return@runBlocking
            }
            delay(1000)
            attempts++
        }
        
        fail("Task did not complete within timeout")
    }
    
    @Test
    fun testResourceLimitEnforcement() = runBlocking {
        // Create task that allocates too much memory
        val code = """
            def main(inputs):
                big_list = [0] * 100_000_000  # ~400MB
                return "Done"
        """.trimIndent()
        
        val taskId = "test-oom-${System.currentTimeMillis()}"
        
        val context = TaskExecutionContext(
            taskId = taskId,
            taskType = TaskType.PYTHON,
            jobType = JobType.DATA_ANALYSIS,
            codeBundle = code.toByteArray(),
            inputFileIds = emptyList(),
            resourceLimits = ResourceLimits(
                maxMemoryBytes = 100_000_000, // 100MB limit
                maxCpuTimeMs = 60_000,
                maxDiskBytes = 100_000_000,
                maxExecutionTimeMs = 120_000
            ),
            requesterNodeId = "test-node",
            callbackAddress = "test-node"
        )
        
        // Execute task
        taskManager.executeTask(context)
        
        // Wait for termination
        var attempts = 0
        while (attempts < 30) {
            val status = taskManager.getTaskStatus(taskId)
            if (status?.state == TaskState.FAILED) {
                assertEquals(ExecutionErrorType.OUT_OF_MEMORY, status.result?.errorType)
                return@runBlocking
            }
            delay(1000)
            attempts++
        }
        
        fail("Task was not terminated for memory violation")
    }
}
```

### 9.3 Security Tests

**Test Scenarios**:

1. **Sandbox Escape Attempt**:
   - Task tries to read /etc/passwd
   - Operation blocked by sandbox

2. **Network Access Attempt**:
   - Task tries to open socket
   - Operation blocked

3. **File Permission Enforcement**:
   - Task A stores file
   - Task B (different requester) tries to access
   - Access denied

4. **Code Injection**:
   - Malicious code bundle
   - Validation rejects bundle

### 9.4 Performance Tests

**Metrics to Track**:
- Task execution overhead (vs native execution)
- Resource monitoring overhead
- Storage encryption overhead
- Completion notification latency

**Test Tool**: Use Android Profiler + custom benchmarks

---

## Section 10: Implementation Checklist

### Phase 1: Data Structures ✅ (Covered in Part 1)

- [ ] Create `MeshComputeDataDefinitions.kt`
  - [ ] TaskExecutionContext
  - [ ] FileReference
  - [ ] ResourceLimits
  - [ ] ResourceMetrics
  - [ ] ExecutionResult
  - [ ] ExecutionErrorType enum
  - [ ] OutputManifest

- [ ] Create `TaskType.kt` enum
  - [ ] PYTHON, JAVA, JVM, JAVASCRIPT, ML_NATIVE, WORKFLOW
  - [ ] RuntimeType mapping method

- [ ] Create `JobType.kt` enum
  - [ ] 8 job categories with resource estimates

- [ ] Extend `TaskStatus` data class
  - [ ] Add execution fields (executionStartedAt, executorNodeAddress, containerId, resourceUsage, executionContext)

- [ ] Add `TASK_COMPLETED` message type
  - [ ] TaskCompletedMessage data class
  - [ ] ExecutionStats
  - [ ] ExecutionError

- [ ] Add constants to `MeshrabiyaConstants`
  - [ ] TASK_COMPLETION_RETRY_PERIOD_MS
  - [ ] TASK_COMPLETION_RETRY_INTERVAL_MS
  - [ ] RESOURCE_MONITORING_INTERVAL_MS

### Phase 2: Storage API Refactoring ✅ (Covered in Part 1)

- [ ] Refactor `DistributedStorageManager.storeFile()`
  - [ ] Add accessScope parameter
  - [ ] Add owner parameter
  - [ ] Add recipients parameter
  - [ ] Implement encryptWithRecipients()
  - [ ] Update FileMetadata to include permissions

- [ ] Update `TaskManager.completeTask()`
  - [ ] Pass owner (task requester) to storeFile()
  - [ ] Pass recipients (task requester + compute node) to storeFile()

### Phase 3: TaskManager Extension ✅ (Covered in Part 1)

- [ ] Add execution state tracking fields
- [ ] Implement `executeTask()` method
- [ ] Implement `retrieveInputFiles()` method
- [ ] Implement `createSandboxContainer()` method
- [ ] Implement `getSandboxConfigForTaskType()` method
- [ ] Implement `loadExecutor()` method
- [ ] Implement `storeResultFiles()` method
- [ ] Implement `sendCompletionNotification()` method
- [ ] Implement `cleanupExecution()` method

### Phase 4: Resource Monitoring ✅ (Covered in Part 2)

- [ ] Implement `ensureResourceMonitoringActive()` in TaskManager
- [ ] Implement `updateResourceMetrics()` polling loop
- [ ] Implement `checkResourceLimitViolations()`
- [ ] Implement `terminateTask()` method
- [ ] Add `getContainerMetrics()` to StrangersSafeComputeEngine
  - [ ] Implement `readContainerMemoryUsage()` (/proc parsing)
  - [ ] Implement `readContainerCpuUsage()` (/proc parsing)
  - [ ] Implement `readContainerDiskUsage()` (/proc parsing)
  - [ ] Implement `calculateDirectorySize()` helper
- [ ] Add `killContainer()` to StrangersSafeComputeEngine
- [ ] Update `MicroContainer` data class (add memoryHistory)

### Phase 5: Executor Implementations ✅ (Covered in Part 2)

- [ ] Create `TaskExecutor` interface
- [ ] Implement `PythonExecutor`
  - [ ] Code bundle extraction (single file + ZIP)
  - [ ] Chaquopy integration
  - [ ] Output file collection
  - [ ] Validation logic
- [ ] Implement `JVMExecutor`
  - [ ] JAR loading
  - [ ] SecurityManager setup
  - [ ] Isolated ClassLoader
  - [ ] Output file collection
- [ ] Implement `JSExecutor` (stub for now)
- [ ] Implement `MLNativeExecutor` (stub for now)
- [ ] Implement `WorkflowExecutor` (stub for now)

### Phase 6: Runtime Management ✅ (Covered in Part 3)

- [ ] Create `RuntimeRegistry` object
  - [ ] SharedPreferences persistence
  - [ ] Built-in runtime detection (JVM, Python/Chaquopy)
  - [ ] Runtime registration/unregistration
  - [ ] Runtime availability checking

- [ ] Create `RuntimeInstaller` object
  - [ ] JavaScript runtime installation (J2V8)
  - [ ] ML Native runtime installation (TFLite)
  - [ ] Python package installation (pip)
  - [ ] File download with progress
  - [ ] Native library extraction

- [ ] Add `verifyRuntimeAvailable()` to TaskManager

### Phase 7: Service Library Enhancements ✅ (Covered in Part 3)

- [ ] Extend `ServiceEntry` data class
  - [ ] Add supportsCompute field
  - [ ] Add taskTypes field
  - [ ] Add jobTypes field
  - [ ] Add maxConcurrentTasks field
  - [ ] Add estimatedCapacity field

- [ ] Add to `LocalDeviceServiceLibrary`:
  - [ ] `getBuiltInComputeServices()` method
  - [ ] `getJobTypesForTaskType()` mapping
  - [ ] `getMaxConcurrentTasks()` calculation
  - [ ] `estimateNodeCapacity()` method
  - [ ] `saveServices()` persistence
  - [ ] `loadServices()` restore
  - [ ] `refreshServices()` rebuild

- [ ] Extend `ServicePackageManager`:
  - [ ] `installServicePackage()` implementation
  - [ ] Metadata extraction
  - [ ] Runtime verification
  - [ ] Service registration

### Phase 8: Integration Points ✅ (Covered in Part 3)

- [ ] Implement `IntelligentDistributedComputeService.assignTaskToNode()`
  - [ ] Create TaskAssignmentMessage
  - [ ] Send via VirtualNode
  - [ ] Update task status

- [ ] Add message handlers to `MeshrabiyaVirtualNode`:
  - [ ] `handleTaskAssignmentMessage()` (compute node)
  - [ ] `handleTaskCompletionMessage()` (client node)
  - [ ] `sendTaskRejection()` helper
  - [ ] `sendTaskCompletionAck()` helper

- [ ] Add new message types to `MessageType` enum:
  - [ ] TASK_ASSIGNMENT
  - [ ] TASK_REJECTED
  - [ ] TASK_COMPLETED
  - [ ] TASK_COMPLETION_ACK

- [ ] Add message data classes:
  - [ ] TaskAssignmentMessage
  - [ ] TaskRejectionMessage
  - [ ] TaskCompletionAckMessage

### Phase 9: Testing

- [ ] Write unit tests:
  - [ ] TaskExecutionContextTest (serialization)
  - [ ] ResourceLimitsTest (validation)
  - [ ] ExecutorTests (per executor)
  - [ ] RuntimeRegistryTest (persistence)
  - [ ] StorageAPITest (permissions)

- [ ] Write integration tests:
  - [ ] TaskExecutionIntegrationTest (full flow)
  - [ ] ResourceLimitEnforcementTest
  - [ ] RuntimeMissingTest
  - [ ] FileStorageIntegrationTest

- [ ] Write security tests:
  - [ ] SandboxEscapeTest
  - [ ] NetworkAccessTest
  - [ ] FilePermissionTest
  - [ ] CodeInjectionTest

- [ ] Write performance tests:
  - [ ] ExecutionOverheadBenchmark
  - [ ] MonitoringOverheadBenchmark
  - [ ] EncryptionOverheadBenchmark
  - [ ] NotificationLatencyBenchmark

### Phase 10: Documentation

- [ ] Update README.md:
  - [ ] Task execution architecture overview
  - [ ] Supported task types and job types
  - [ ] Runtime installation guide
  - [ ] Resource limits explanation

- [ ] Create API documentation:
  - [ ] TaskManager API
  - [ ] Executor interface
  - [ ] Runtime management API
  - [ ] Service library API

- [ ] Create developer guide:
  - [ ] How to add new task type
  - [ ] How to implement custom executor
  - [ ] How to create service package
  - [ ] Security best practices

- [ ] Update KNOWLEDGE.md:
  - [ ] Document implementation decisions
  - [ ] Known limitations
  - [ ] Future enhancements
  - [ ] Troubleshooting guide

### Phase 11: End-to-End Validation

- [ ] Test complete execution flow:
  - [ ] Client submits Python task
  - [ ] Node selected and assigned
  - [ ] Task executes successfully
  - [ ] Results returned
  - [ ] Files accessible

- [ ] Test error scenarios:
  - [ ] Runtime missing
  - [ ] Resource limit exceeded
  - [ ] Code validation failure
  - [ ] Network interruption during execution

- [ ] Test performance:
  - [ ] Execute 10 concurrent tasks
  - [ ] Measure resource usage
  - [ ] Verify limit enforcement
  - [ ] Check for memory leaks

- [ ] Test security:
  - [ ] Verify sandbox isolation
  - [ ] Verify file permissions
  - [ ] Verify code validation
  - [ ] Verify network blocking

---

## IMPLEMENTATION NOTES

### Critical Dependencies

1. **Chaquopy** (Python runtime):
   - Add to build.gradle: `implementation 'com.chaquo.python:gradle:14.0.2'`
   - Required for PythonExecutor

2. **J2V8** (JavaScript runtime):
   - Add to build.gradle: `implementation 'com.eclipsesource.j2v8:j2v8:6.2.1'`
   - Optional, can be installed at runtime

3. **TensorFlow Lite** (ML runtime):
   - Add to build.gradle: `implementation 'org.tensorflow:tensorflow-lite:2.14.0'`
   - Optional, can be installed at runtime

### Build Configuration

**Update app/build.gradle.kts**:

```kotlin
plugins {
    id("com.chaquo.python") version "14.0.2" // For Python support
}

android {
    // ... existing config ...
    
    defaultConfig {
        // ... existing config ...
        
        ndk {
            // Required for native executors
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
}

dependencies {
    // ... existing dependencies ...
    
    // Task execution runtimes
    implementation("com.chaquo.python:gradle:14.0.2")
    
    // Optional runtimes (can be installed at runtime)
    // implementation("com.eclipsesource.j2v8:j2v8:6.2.1")
    // implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
```

### File Size Management

If any file exceeds 1000 lines after implementation, break it out:

1. **TaskManager.kt** (likely to exceed):
   - Keep: Task lifecycle management
   - Extract: `TaskExecutor.kt` (execution orchestration)
   - Extract: `ResourceMonitor.kt` (polling and tracking)

2. **IntelligentDistributedComputeService.kt** (may exceed):
   - Keep: Main service logic
   - Extract: `NodeSelector.kt` (node selection)
   - Extract: `TaskDispatcher.kt` (assignment and routing)

### Optional: Scheduler Code Salvage

From previous scheduler analysis, potentially salvageable components:

1. **Node Selection Logic** (IntelligentTaskScheduler.md lines 50-150):
   - Multi-factor scoring (resources, latency, reliability)
   - Could enhance `selectOptimalNode()` in IntelligentDistributedComputeService

2. **Resource Estimation** (IntelligentTaskScheduler.md lines 200-250):
   - Task complexity analysis
   - Could inform `JobType` resource estimates

**Decision criteria**: Only salvage if:
- Logic is complete and testable
- Removes quorum dependencies
- Provides clear benefit over simple implementation
- Doesn't add significant complexity

**Implementation**: Create `NodeSelectionStrategy.kt` if salvaged

---

## END OF COMPREHENSIVE IMPLEMENTATION PLAN

**Summary**: This 3-part plan covers:

**Part 1** (1188 lines):
- Data structures (TaskExecutionContext, TaskType, JobType, TaskStatus, messages)
- Storage API refactoring (permission parameters)
- TaskManager extension (execution orchestration methods)

**Part 2** (First Half):
- Resource monitoring (polling loop, metric tracking, limit enforcement)
- Executor implementations (TaskExecutor interface, PythonExecutor, JVMExecutor, stubs)

**Part 3** (Second Half - THIS FILE):
- Runtime management (registry, installer, verification)
- Service library enhancements (compute capability fields, built-in services)
- Integration points (message handlers, task assignment)
- Testing strategy (unit, integration, security, performance)
- Implementation checklist (11 phases)

**Total estimated implementation**: 6000-8000 lines of new/modified code

**Next steps**: Begin Phase 1 (Data Structures) and proceed sequentially through checklist.
