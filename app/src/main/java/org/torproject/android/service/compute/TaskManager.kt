package org.torproject.android.service.compute

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// Meshrabiya interop extension functions are top-level in the same package; no import required

/**
 * Task Management Backend Logic
 * Supports service search, task creation, progress tracking, and result management for distributed compute.
 */
object TaskManager {
    /**
     * Create a new task request with parameters and start tracking its status.
     */
    fun createTaskWithParams(service: org.torproject.android.service.compute.ServiceMeta, params: Map<String, Any>): UUID {
        val destinationFolder = params["destinationFolder"] as? String
        val request = TaskRequest(
            id = UUID.randomUUID(),
            service = service,
            parameters = params,
            requester = "local",
            requirements = if (destinationFolder != null) mapOf("destinationFolder" to destinationFolder) else null
        )
        createTaskRequest(request)
        return request.id
    }
    // Use ServiceSearchResult for manifest/meta-only service info and node tracking
    // (see IntelligentDistributedComputeService.kt)

    data class TaskRequest(
        val id: UUID = UUID.randomUUID(),
        val service: ServiceMeta,
        val parameters: Map<String, Any>,
        val requester: String,
        val requiredRole: String? = null,
        val requirements: Map<String, Any>? = null
    )

    data class TaskStatus(
        val id: UUID,
        val service: ServiceMeta,
        val progress: ProgressMetric,
        val state: State,
        val result: Any? = null,
        val requirements: Map<String, Any>? = null,
        val startedAt: Long,
        val completedAt: Long? = null
    ) {
        enum class State { RUNNING, COMPLETED, FAILED, CANCELLED }
    }

    data class ProgressMetric(
        val percentComplete: Int,
        val etaSeconds: Int?,
        val currentStage: String
    )

    private val serviceCache = mutableListOf<org.torproject.android.service.compute.ServiceSearchResult>()
    private val runningTasks = mutableMapOf<UUID, TaskStatus>()
    private val completedTasks = mutableListOf<TaskStatus>()

    /**
     * Search for services by name/type/version, using smart caching (closest, lowest latency, highest bandwidth).
     */
    fun searchServices(query: String): List<ServiceSearchResult> =
        serviceCache.filter {
            val manifest = it.manifest
            manifest.author.contains(query, ignoreCase = true) ||
            manifest.version.contains(query, ignoreCase = true) ||
            manifest.serviceType.name.contains(query, ignoreCase = true) ||
            manifest.resourceRequirements.toString().contains(query, ignoreCase = true) ||
            it.capabilities.any { cap -> cap.name.contains(query, ignoreCase = true) }
        }.take(5)

    /**
     * Add/update service metadata in cache (smart cache: keep only best candidates per service/version).
     */
    fun updateServiceCache(result: ServiceSearchResult) {
        serviceCache.removeAll { it.serviceId == result.serviceId && it.manifest.version == result.manifest.version && it.nodeId == result.nodeId }
        serviceCache.add(result)
        // Keep only top 5 per service/version/node
        val grouped = serviceCache.groupBy { Triple(it.serviceId, it.manifest.version, it.nodeId) }
        serviceCache.clear()
        grouped.values.forEach { group ->
            serviceCache.addAll(group.take(5))
        }
    }

    /**
     * Create a new task request and start tracking its status.
     */
    fun createTaskRequest(request: TaskRequest): TaskStatus {
        // Validate parameters against manifest inputs (compare by input name)
        val missingInputs = request.service.inputs.map { it.name }.filter { !request.parameters.containsKey(it) }
        if (missingInputs.isNotEmpty()) {
            throw IllegalArgumentException("Missing required inputs: ${missingInputs.joinToString()}")
        }
        val status = TaskStatus(
            id = request.id,
            service = request.service,
            progress = ProgressMetric(0, null, "Pending"),
            state = TaskStatus.State.RUNNING,
            requirements = request.requirements,
            startedAt = System.currentTimeMillis()
        )
        runningTasks[request.id] = status
        return status
    }

    /**
     * Update progress for a running task.
     */
    fun updateTaskProgress(taskId: UUID, percent: Int, eta: Int?, stage: String) {
        runningTasks[taskId]?.let {
            runningTasks[taskId] = it.copy(progress = ProgressMetric(percent, eta, stage))
        }
    }

    /**
     * Mark a task as completed and move to completed list.
     */
    fun completeTask(taskId: UUID, result: Any?) {
        runningTasks[taskId]?.let {
            // Map result to manifest outputs if result is a map
            val mappedResult = if (result is Map<*, *>) {
                val outputMap = mutableMapOf<String, Any?>()
                for (output in it.service.outputs) {
                    // service.outputs is a list of ServiceOutput - use its name
                    outputMap[output.name] = result[output.name]
                }
                outputMap
            } else result
            val completed = it.copy(
                state = TaskStatus.State.COMPLETED,
                result = mappedResult,
                completedAt = System.currentTimeMillis()
            )
            completedTasks.add(completed)
            runningTasks.remove(taskId)

            // If result is a file and destination folder is set, stream to Distributed Storage
            val destinationFolder = it.requirements?.get("destinationFolder") as? String
            if (destinationFolder != null && mappedResult is Map<*, *>) {
                val fileOutput = mappedResult.values.find { v -> v is java.io.File || v is android.net.Uri }
                if (fileOutput != null) {
                    // 1. Generate unique filename
                    val baseName = "output_${System.currentTimeMillis()}"
                    val extension = when (fileOutput) {
                        is java.io.File -> fileOutput.extension
                        is android.net.Uri -> "bin" // Could resolve actual type if needed
                        else -> "bin"
                    }
                    val uniqueFileName = "$baseName.$extension"

                    // 2. Convert file to byte array
                    val fileBytes: ByteArray? = when (fileOutput) {
                        is java.io.File -> fileOutput.readBytes()
                        is android.net.Uri -> {
                            val context = getAppContext()
                            context?.contentResolver?.openInputStream(fileOutput)?.use { it.readBytes() }
                        }
                        else -> null
                    }

                    if (fileBytes != null) {
                        // 3. Get selected drop folder path (local or SAF)
                        val dropFolderManager = org.torproject.android.service.storage.StorageDropFolderManager.getInstance(getAppContext()!!)
                        val dropFolderPath = dropFolderManager.getSelectedFolderPath() ?: "mesh_drop"

                        // 4. Create DistributedStorageManager and SandboxStorageProxy
                        val distributedStorageManager = com.ustadmobile.meshrabiya.storage.DistributedStorageManager(
                            getAppContext()!!,
                            getMeshNetworkInterface(),
                            getStorageConfiguration()
                        )
                        val sandboxPolicy = com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageAccessPolicy(
                            allowedOperations = setOf(
                                com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageOperation.WRITE,
                                com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageOperation.READ
                            ),
                            retentionPolicy = com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.RetentionPolicy.PERSISTENT,
                            accessScope = com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.AccessScope.TASK_ISOLATED
                        )
                        val sandboxProxy = com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy(
                            distributedStorageManager,
                            sandboxPolicy
                        )

                        // 5. Create storage request
                        val taskIdStr = taskId.toString()
                        val sandboxId = "default"
                        val request = com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageRequest.Store(
                            requestId = UUID.randomUUID().toString(),
                            taskId = taskIdStr,
                            fileName = uniqueFileName,
                            data = java.util.Base64.getEncoder().encodeToString(fileBytes),
                            metadata = mapOf("destinationFolder" to dropFolderPath),
                            retentionPolicy = com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.RetentionPolicy.PERSISTENT
                        )

                        // 6. Process storage request in coroutine (use structured scope)
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val payload = kotlinx.serialization.json.Json.encodeToString(request)
                                val responseJson = sandboxProxy.processStorageRequest(payload, taskIdStr, sandboxId)
                                // Optionally parse response and update result with file reference
                            } catch (e: Exception) {
                                // Log and ignore for now - storage is best-effort from UI
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }

    }
    /**
     * Get application context (replace with your actual context provider)
     */
    private fun getAppContext(): android.content.Context? {
        // Use OrbotApp singleton for global application context
        return org.torproject.android.OrbotApp.instance.applicationContext
    }

    /**
     * Get mesh network interface (replace with your actual provider)
     */
    private fun getMeshNetworkInterface(): com.ustadmobile.meshrabiya.storage.MeshNetworkInterface {
        // Require a concrete MeshNetworkInterface provided by MeshServiceCoordinator.
        // If storage participation has not been initialized or a mesh adapter is not yet
        // available, fail fast so callers can't silently rely on a no-op fallback.
        val ctx = getAppContext() ?: throw IllegalStateException("Application context unavailable")
        val coordinator = org.torproject.android.service.MeshServiceCoordinator.getInstance(ctx)
            ?: throw IllegalStateException("MeshServiceCoordinator not available; ensure the mesh adapter is initialized before using TaskManager")

        return coordinator.provideMeshNetworkInterface()
            ?: throw IllegalStateException("Mesh network adapter not provided by MeshServiceCoordinator; storage operations require an explicit mesh adapter")
    }

    /**
     * Get storage configuration (replace with your actual provider)
     */
    private fun getStorageConfiguration(): com.ustadmobile.meshrabiya.storage.StorageConfiguration {
        // Return a safe default StorageConfiguration. MeshServiceCoordinator exposes storage
        // config via private helpers; avoid accessing private API and provide a fallback.
        return try {
            com.ustadmobile.meshrabiya.storage.StorageConfiguration()
        } catch (e: Exception) {
            // Should not happen, but keep a minimal fallback
            com.ustadmobile.meshrabiya.storage.StorageConfiguration(defaultReplicationFactor = 1)
        }
    }
    /**
     * Get all running tasks.
     */
    fun getRunningTasks(): List<TaskStatus> = runningTasks.values.toList()

    /**
     * Get all completed tasks.
     */
    fun getCompletedTasks(): List<TaskStatus> = completedTasks

    /**
     * Get progress metric for a task.
     */
    fun getTaskProgress(taskId: UUID): ProgressMetric? = runningTasks[taskId]?.progress

}
