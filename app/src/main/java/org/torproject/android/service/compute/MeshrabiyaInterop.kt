package org.torproject.android.service.compute

// Conversion helpers between Meshrabiya model types and app compute support types.
// These provide a guarded, best-effort mapping so the app and Meshrabiya can interoperate
// without changing either module's public models. Assumptions are documented here.

import com.ustadmobile.meshrabiya.model.ResourceRequirements as UMRR
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement as UMServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ExecutionProfile as UMExecutionProfile
import org.torproject.android.service.compute.ServicePackageManager as SPM
import com.ustadmobile.meshrabiya.storage.DistributedFileInfo as UMDistributedFileInfo
import com.ustadmobile.meshrabiya.storage.LocalFileReference as UMLocalFileReference
import com.ustadmobile.meshrabiya.storage.ReplicationLevel as UMReplicationLevel
import com.ustadmobile.meshrabiya.storage.SyncPriority as UMSyncPriority
import org.torproject.android.service.compute.IntelligentDistributedComputeService
import java.io.File

/**
 * Map Meshrabiya ResourceRequirements -> app ResourceRequirements.
 * Assumptions:
 * - preferredRAMMB is approximated as double the minMemoryMB when not available.
 * - CPU intensity is inferred heuristically from minCpuCores.
 * - requiresStorage true when minStorageMB > 0 and minStorageGB computed accordingly.
 */
fun UMRR.toAppResourceRequirements(): ResourceRequirements {
    val minRam = this.minMemoryMB
    val preferredRam = maxOf(128, minRam * 2)
    val cpuIntensity = when {
        this.minCpuCores <= 1 -> CPUIntensity.LIGHT
        this.minCpuCores <= 4 -> CPUIntensity.MODERATE
        else -> CPUIntensity.HEAVY
    }

    return ResourceRequirements(
        minRAMMB = minRam,
        preferredRAMMB = preferredRam,
        cpuIntensity = cpuIntensity,
        requiresGPU = this.minGpu,
        requiresNPU = false,
        requiresStorage = (this.minStorageMB > 0),
        minStorageGB = this.minStorageMB / 1024f,
        thermalConstraints = setOf(ThermalState.COLD, ThermalState.WARM, ThermalState.HOT, ThermalState.CRITICAL),
        maxNetworkLatencyMs = 1000,
        minBatteryLevel = 10
    )
}

/**
 * Map app ResourceRequirements -> Meshrabiya ResourceRequirements.
 * Uses conservative downcasting where necessary.
 */
fun ResourceRequirements.toMeshrabiya(): UMRR {
    val minStorageMB = (this.minStorageGB * 1024f).toInt()
    val minCpuCores = when (this.cpuIntensity) {
        CPUIntensity.LIGHT -> 1
        CPUIntensity.MODERATE -> 2
        CPUIntensity.HEAVY -> 4
        CPUIntensity.BURST -> 6
    }

    return UMRR(
        minMemoryMB = this.minRAMMB,
        minStorageMB = minStorageMB,
        minCpuCores = minCpuCores,
        minGpu = this.requiresGPU
    )
}

/**
 * Map Meshrabiya DistributedFileInfo -> app FileMetadata / SharedFileMetadata
 */
fun UMDistributedFileInfo.toAppFileMetadata(): DistributedStorageAgent.FileMetadata {
    val localRef = this.localReference
    val replicationFactor = when (this.replicationLevel) {
        UMReplicationLevel.MINIMAL -> 1
        UMReplicationLevel.STANDARD -> 3
        UMReplicationLevel.HIGH -> 5
        UMReplicationLevel.CRITICAL -> 7
        else -> 1
    }

    return DistributedStorageAgent.FileMetadata(
        fileId = localRef.id,
        originalName = this.path.substringAfterLast('/'),
        sizeBytes = 0L, // size not provided by DistributedFileInfo; unknown
        checksumMD5 = localRef.checksum,
        storedTimestamp = this.createdAt,
        accessCount = 0L,
        lastAccessTimestamp = this.lastAccessed,
        replicationFactor = replicationFactor,
        tags = this.meshReferences.toSet()
    )
}

/**
 * Map app StorageRequest -> Meshrabiya DistributedFileInfo (transport-friendly)
 * This creates a lightweight DistributedFileInfo that can be sent to other nodes
 */
fun DistributedStorageAgent.StorageRequest.toDistributedFileInfo(): UMDistributedFileInfo {
    val localRef = UMLocalFileReference(
        id = this.fileId,
        localPath = "",
        checksum = ""
    )

    val replLevel = when (this.replicationFactor) {
        1 -> UMReplicationLevel.MINIMAL
        3 -> UMReplicationLevel.STANDARD
        5 -> UMReplicationLevel.HIGH
        7 -> UMReplicationLevel.CRITICAL
        else -> UMReplicationLevel.STANDARD
    }

    val priority = when (this.priority) {
        DistributedStorageAgent.StoragePriority.LOW -> UMSyncPriority.LOW
        DistributedStorageAgent.StoragePriority.NORMAL -> UMSyncPriority.NORMAL
        DistributedStorageAgent.StoragePriority.HIGH -> UMSyncPriority.HIGH
        DistributedStorageAgent.StoragePriority.CRITICAL -> UMSyncPriority.CRITICAL
    }

    return UMDistributedFileInfo(
        path = this.fileName,
        localReference = localRef,
        replicationLevel = replLevel,
        priority = priority,
        createdAt = System.currentTimeMillis(),
        lastAccessed = 0L,
        meshReferences = this.tags.toList()
    )
}

/**
 * Convert a TaskExecutionRequest into a Meshrabiya DistributedFileInfo for transport.
 * The caller should provide the localPath where the request payload has been written.
 */
fun IntelligentDistributedComputeService.TaskExecutionRequest.toDistributedFileInfo(localPath: String): UMDistributedFileInfo {
    val localRef = UMLocalFileReference(
        id = this.taskId,
        localPath = localPath,
        checksum = ""
    )

    return UMDistributedFileInfo(
        path = "compute/requests/${this.taskId}",
        localReference = localRef,
        replicationLevel = UMReplicationLevel.STANDARD,
        priority = UMSyncPriority.NORMAL,
        createdAt = System.currentTimeMillis(),
        lastAccessed = 0L,
        meshReferences = emptyList()
    )
}

/**
 * Convert the app-level lightweight ServiceManifest (used by the compute subsystem)
 * into a Meshrabiya ServiceAnnouncement for announcing packages to the mesh.
 * This is intentionally conservative and does not attempt to fill every field.
 */
/**
 * Convert a ServicePackageManager.ServiceManifest -> Meshrabiya ServiceAnnouncement.
 * Uses conservative defaults and best-effort mappings from ResourceSpec.
 */
fun SPM.ServiceManifest.toMeshrabiyaAnnouncement(sizeKB: Int = 0): UMServiceAnnouncement {
    // Map ResourceSpec -> UMRR
    fun SPM.ResourceSpec.toUMRR(): UMRR {
        val minCpu = when {
            this.estimatedCpuUsage <= 0.15f -> 1
            this.estimatedCpuUsage <= 0.5f -> 2
            this.estimatedCpuUsage <= 0.85f -> 4
            else -> 6
        }
        return UMRR(
            minMemoryMB = this.minMemoryMB,
            minStorageMB = this.storageRequiredMB,
            minCpuCores = minCpu,
            minGpu = this.requiresGPU
        )
    }

    val umExec = UMExecutionProfile(
        profileName = "mesh-distributed",
        cpuCores = this.resourceRequirements.estimatedCpuUsage.let { usage ->
            when {
                usage <= 0.15f -> 1
                usage <= 0.5f -> 2
                usage <= 0.85f -> 4
                else -> 6
            }
        },
        gpuEnabled = this.resourceRequirements.requiresGPU,
        memoryMB = this.resourceRequirements.maxMemoryMB,
        storageMB = this.resourceRequirements.storageRequiredMB
    )

    val capabilitiesList = (this.tags ?: emptyList()).toMutableList()
    capabilitiesList.addAll(this.requiredPermissions ?: emptyList())

    val svcType = try {
        UMServiceAnnouncement.ServiceType.valueOf(this.serviceType.uppercase())
    } catch (e: Exception) {
        UMServiceAnnouncement.ServiceType.WORKFLOW
    }

    return UMServiceAnnouncement(
        serviceId = this.packageId,
        serviceType = svcType,
        version = this.packageVersion,
        sizeKB = sizeKB,
        capabilities = capabilitiesList,
        resourceRequirements = this.resourceRequirements.toUMRR(),
        executionProfile = umExec
    )
}
