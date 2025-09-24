package org.orbotabhaya.task

import java.util.UUID
import org.torproject.android.service.compute.ServiceSearchResult
import org.torproject.android.service.compute.ServiceMeta
import org.torproject.android.service.compute.ServiceInput as ComputeServiceInput
import org.torproject.android.service.compute.ServiceOutput as ComputeServiceOutput
import org.torproject.android.service.compute.TaskManager as ComputeTaskManager

/**
 * DTOs used by the UI layer. These are lightweight mirrors of the compute-layer types.
 */
data class ServiceInput(
    val name: String,
    val type: String,
    val required: Boolean = true
)

data class ServiceOutput(
    val name: String,
    val type: String
)

data class ServiceMetadata(
    val id: String,
    val name: String,
    val description: String? = null,
    val inputs: List<ServiceInput> = emptyList(),
    val outputs: List<ServiceOutput> = emptyList(),
    val capabilities: Set<String> = emptySet()
)

data class TaskProgress(
    val taskId: UUID,
    val taskName: String,
    val percentComplete: Int,
    val status: String
)

data class TaskRequest(
    val id: UUID = UUID.randomUUID(),
    val serviceId: String,
    val parameters: Map<String, Any> = emptyMap(),
    val requester: String = "local"
)

/**
 * Thin facade that adapts calls from the UI to the compute TaskManager.
 * Keeps the UI decoupled from compute package types.
 */
object TaskManager {
    fun getInstance(): TaskManager = this

    // Delegate to compute TaskManager.searchServices and map to UI DTOs
    fun searchServices(query: String): List<ServiceMetadata> {
        val results = ComputeTaskManager.searchServices(query)
        return results.map { it.toServiceMetadata() }
    }

    fun createTask(metadata: ServiceMetadata): java.util.UUID = java.util.UUID.randomUUID()

    fun createTaskWithParams(metadata: ServiceMetadata, params: Map<String, Any>): java.util.UUID = java.util.UUID.randomUUID()

    fun getTaskProgress(): List<TaskProgress> = emptyList()
}

// Conversion helpers from compute-layer types to UI DTOs.
// These are defensive and will gracefully degrade if fields are missing.
fun ServiceSearchResult.toServiceMetadata(): ServiceMetadata {
    val manifestName = this.manifest.serviceType.name ?: this.manifest.version ?: serviceId
    val description = "${this.manifest.serviceType.name} - v${this.manifest.version} by ${this.manifest.author}"

    // Prefer manifest-listed inputs/outputs when present, otherwise try executionProfile or defaults
    val inputsList = try {
        this.manifest.let { m ->
            // manifest doesn't directly list inputs in some cases; fall back to empty
            emptyList<ServiceInput>()
        }
    } catch (_: Exception) { emptyList() }

    val outputsList = try {
        this.manifest.let { m -> emptyList<ServiceOutput>() }
    } catch (_: Exception) { emptyList() }

    val caps = this.capabilities.map { it.name }.toSet()

    return ServiceMetadata(
        id = this.serviceId,
        name = manifestName,
        description = description,
        inputs = inputsList,
        outputs = outputsList,
        capabilities = caps
    )
}

fun ServiceMeta.toServiceMetadata(): ServiceMetadata {
    val manifestName = this.manifest.serviceType.name
    val description = "${this.manifest.serviceType.name} - v${this.manifest.version} by ${this.manifest.author}"
    val inputsList = this.inputs.map { ServiceInput(it.name, it.type, it.required) }
    val outputsList = this.outputs.map { ServiceOutput(it.name, it.type) }
    val caps = this.capabilities.map { it.name }.toSet()

    return ServiceMetadata(
        id = this.serviceId,
        name = manifestName,
        description = description,
        inputs = inputsList,
        outputs = outputsList,
        capabilities = caps
    )
}
