# MeshrabiyaApiImpl Complete Implementation Plan
**Date:** 2025-12-05  
**Status:** PLANNED - NOT STARTED  
**Priority:** CRITICAL (Blocks OrbotMeshService and full app functionality)

## Executive Summary

This plan eliminates ALL stub code, TODOs, and NotImplementedErrors from `MeshrabiyaApiImpl.kt` (416 lines) per AGENTS.md rule. The file currently has 65% unimplemented code across 26+ methods. All implementations will use existing, functional Meshrabiya components from Canonical Workflows v2.

**Total Scope:** 8 major implementation sections, 26+ methods, 3 open research questions

---

## Open Questions & Research Needed

### ❓ QUESTION 1: Task Status/Cancellation Methods
**Status:** UNRESOLVED  
**Context:** TaskManager has NO `getTaskStatus()` or `cancelTask()` methods (grep confirmed 0 matches)  
**Impact:** Blocks implementation of:
- `MeshrabiyaApi.getTaskStatus(taskId: String)` (if interface requires it)
- `MeshrabiyaApi.cancelTask(taskId: String, callback)`

**Options:**
1. Access `TaskManager.activeTasks: ConcurrentHashMap<String, Task>` directly (protected property, may need accessor method)
2. Implement cancellation by setting `task.state = TaskState.CANCELLED` directly on Task object
3. Document limitation and implement no-op with error callback
4. Add missing methods to TaskManager (requires TaskManager.kt modification)

*Answer: remove these functions. this would be client side functionality so the task most likely is not running on the same node meaning `TaskManager.activeTasks` on the node wont have information.  there is a `DistributedComputeClient.activeRequests` list maintained on the client side.  However, we would really want to more generally want to register a callback to handle(which would update the UI control layer, updating task status in the UI representation and perhaps triggering a notication of some sort) when the DistributedComputeClient handles an task status update message, DistributedComputeClient would also update the status of the corresponding TaskRequest in the activeRequests list.  Currently there are 2 task status update messages:
   - TaskCompletedMessage: can pass success of failed

I think we should consider refactoring to use a single TaskStatusUpdate for the transition to each phase of task execution:  for entering Perparation , and  entering execution, at Success or Failure.  This new message would have a status field that can have the value of the TaskStatus Enum (verify) along with the other data already being sent. 

The client side would need a handler to update the associated TaskRequest status in the activeRequests list, matching by taskId  and  also triggering the UI callback if it has been configured in via the API.   You should perform at least 2 iterations of research agent. the first to validate approach and refine strategy. and a second to resolve signatures, verify function exsistence and resovle any uncertainties in the plan which are answerable with close examination of the code. 

If there are Outstanding undertainties related to this Question or my proposed solution, Iclude those questions in the doc for me to answer.



**Action Required:** Review MeshrabiyaApi interface for required signatures, then choose implementation approach

### ❓ QUESTION 2: Stream File Implementation
**Status:** UNRESOLVED  
**Context:** `streamFile()` method has no equivalent in canonical workflows (DistributedStorageClient only has `storeFile()` and `retrieveFile()`)  
**Impact:** Blocks complete file operations implementation

**Options:**
1. Implement progressive chunked retrieval using `FileReference.chunkIds` + `virtualNode.fileChunkStore`
2. Implement as `retrieveFile()` wrapper with progress callbacks (not true streaming)
3. Document as unsupported, return error in callback
4. Research FileChunkStore API for chunk-by-chunk retrieval

**Action Required:** Decide on approach based on MeshrabiyaApi contract expectations

*Answer: The goal of streamFile is to stream data from the client node to Distributed storage. Document this but comment out the function for now. and update MeshrabiyaAPI as well.

### ❓ QUESTION 3: Drop Folder Auto-Sync Implementation
**Status:** UNRESOLVED  
**Context:** Drop folder APIs exist but FileObserver integration for auto-sync is not documented in canonical workflows  
**Impact:** Blocks drop folder auto-upload feature

**Options:**
1. Implement Android FileObserver monitoring drop folder path, calling `storeFile()` on CREATE/MODIFY events
2. Implement manual sync only (`getDropFolderFiles()` returns list, user calls `storeFile()` explicitly)
3. Add dropFolderPath property to DistributedStorageManager (requires manager modification)
4. Use polling mechanism (inefficient but simpler)

**Action Required:** Verify DistributedStorageManager interface, decide on auto-sync vs manual approach

**Answer: We should use Android FileObserver monitoring. UI should pass the path to mnonitor via api. DistributedStorageManager monitor the path which is stored in the DistributedStorageManager.DropFolder object. DropFolder object stores:
   - folder path
   - list of Store File Triggers
      - id:Incrementer
      - sub path (should be unique)
      - recipients list (potentially containing users and task recipient) 
API would need to also add a setStoreFileTrigger function which would in trun call a new `DistributedStorageManager.setStoreFileTrigger(trigger:StoreFileTrigger,delete:Boolean? )`. `DistributedStorageManager.setStoreFileTrigger(rigger:StoreFileTrigger,delete:Boolean)` would create , update or delete StoreFileTrigger in `DistributedStorageManager.DropFolder.triggerList` using exact match of the sub folder path  to determine update and deletes

When the FileObserver monitor detects a new file in the DropFolder or its subfolders, DistributedStorageManager should have a function to see if the path of that file corresponds to one or more trigger paths.  A distinct list of recipients from the matching triggers should be used as the recipients list in a `DistributedStorageClient.storeFile()` call to place the new file in DistributedStorage

The handler for monitoring needs to ignore the trigger rules when the new file has been added via a retrieveFile action delivering a file to the DropFolder (or its subfolder). Recommend a way to achieve that building on research agent analysis of existing retrieve file workflow, API and DistributedStorageManager, DistributedStorageClient, DistributedStorageServer with goal of creating sub plan for this complete DropFolder solution.  Once you have the subplan formulated, conduct second round of research agent with goal of verifying exact functions and thier signatures, objects and any abiguities which can be resolved by literal examination of actual codebase. This should all be added to this document as a new section. This plan should emphasise and make it clear to the agent that the code must be fully implemented and nothing left undone or mocked.

If after the reasearch, you still have uncertainties or ambiguities, include questions in the new section.  The new section of the plan  should also include tracking structure 

---

## Implementation Sections (Priority Order)

### SECTION 1: Compute/Task API Implementation ⭐ HIGHEST PRIORITY
**Priority:** HIGHEST (User explicitly demanded this)  
**Status:** ❌ NOT STARTED  
**Lines:** 273-301  
**Methods:** 4

#### 1.1 addTask() - Submit Compute Task Request
**Current State:** `return ApiResult.Failure(NotImplementedError(...))`  
**Target Implementation:**
```kotlin
override fun addTask(requestParams: Map<String, Any>): ApiResult {
    return try {
        val taskId = requestParams["taskId"] as? String ?: java.util.UUID.randomUUID().toString()
        val serviceId = requestParams["serviceId"] as? String ?: return ApiResult.Failure(IllegalArgumentException("serviceId required"))
        val inputParams = requestParams["inputParams"] as? Map<String, Any> ?: emptyMap()
        
        // Create LocalComputeTaskRequest for canonical workflow
        val request = LocalComputeTaskRequest(
            taskId = taskId,
            serviceId = serviceId,
            inputParams = inputParams,
            metadata = requestParams
        )
        
        // Launch coroutine to submit task via DistributedComputeClient
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val returnedTaskId = myNode?.distributedComputeClient?.processTaskRequest(request)
                // Task broadcasted, returnedTaskId should match taskId
            } catch (e: Exception) {
                onOperationFailed?.invoke("addTask", e)
            }
        }
        
        ApiResult.Success // Return immediately with taskId
    } catch (e: Exception) {
        ApiResult.Failure(e)
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.service.compute.LocalComputeTaskRequest`
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- Access: `myNode.distributedComputeClient` (protected property)

**Verification:**
- [ ] Compiles without errors
- [ ] Creates LocalComputeTaskRequest with correct parameters
- [ ] Calls distributedComputeClient.processTaskRequest()
- [ ] Returns ApiResult.Success immediately
- [ ] Handles exceptions with ApiResult.Failure

---

#### 1.2 startTask() - Start Task Execution
**Current State:** `callback(Result.failure(NotImplementedError(...)))`  
**Target Implementation:**
```kotlin
override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
    // Tasks auto-start after node assignment in canonical workflows
    // No explicit start needed - this is a no-op success
    callback(Result.success(Unit))
}
```

**Rationale:** Canonical workflows auto-start tasks when assigned to compute node (TaskManager handles lifecycle)

**Verification:**
- [ ] Compiles without errors
- [ ] Always returns success
- [ ] Documents auto-start behavior

---

#### 1.3 cancelTask() - Cancel Task Execution
**Current State:** `callback(Result.failure(NotImplementedError(...)))`  
**Target Implementation:** ⚠️ **DEPENDS ON QUESTION 1 RESOLUTION**

**Option A (Direct Task Access):**
```kotlin
override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
    try {
        val task = myNode?.taskManager?.activeTasks?.get(taskId)
        if (task != null) {
            task.state = TaskState.CANCELLED
            callback(Result.success(Unit))
        } else {
            callback(Result.failure(IllegalArgumentException("Task not found: $taskId")))
        }
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("cancelTask", e)
    }
}
```

**Option B (TaskManager Method - if added):**
```kotlin
override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            myNode?.taskManager?.cancelTask(taskId)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
            onOperationFailed?.invoke("cancelTask", e)
        }
    }
}
```

**Dependencies:**
- Resolution of QUESTION 1
- `import com.ustadmobile.meshrabiya.service.compute.TaskState` (if Option A)
- Access: `myNode.taskManager.activeTasks` (protected property)

**Verification:**
- [ ] Compiles without errors
- [ ] Handles task not found case
- [ ] Sets task state to CANCELLED
- [ ] Invokes callback appropriately
- [ ] Handles exceptions

---

#### 1.4 getJobTypes() - Return Supported Job Types
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getJobTypes(): List<JobType> {
    return listOf(
        JobType.ML_INFERENCE,
        JobType.DATA_PROCESSING,
        JobType.CUSTOM
    )
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.service.compute.model.JobType` (already imported)

**Verification:**
- [ ] Compiles without errors
- [ ] Returns non-empty list
- [ ] JobType enum values exist

---

### SECTION 2: File Operations Implementation ⭐ HIGH PRIORITY
**Priority:** HIGH (Blocks OrbotMeshService usage)  
**Status:** ❌ NOT STARTED  
**Lines:** 213-254  
**Methods:** 5

#### 2.1 storeFile() - Store File in Distributed Storage
**Current State:** `callback(Result.failure(NotImplementedError(...)))`  
**Target Implementation:**
```kotlin
override fun storeFile(file: File, callback: (Result<String>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val storageClient = distributedStorageManager?.getDistributedStorageClient()
                ?: return@launch callback(Result.failure(IllegalStateException("Storage not initialized")))
            
            val fileData = file.readBytes()
            val fileRef = storageClient.storeFile(
                path = file.absolutePath,
                data = fileData,
                priority = 5, // Medium priority
                replicationLevel = 3, // Default replication
                owner = myNode?.nodeId?.toString() ?: "unknown",
                recipients = emptyList() // Public file
            )
            
            if (fileRef != null) {
                callback(Result.success(fileRef.fileId))
                onFileStored?.invoke(fileRef.fileId, file)
            } else {
                callback(Result.failure(Exception("Storage failed: null FileReference")))
                onOperationFailed?.invoke("storeFile", Exception("null FileReference"))
            }
        } catch (e: Exception) {
            callback(Result.failure(e))
            onOperationFailed?.invoke("storeFile", e)
        }
    }
}
```

**Dependencies:**
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- Access: `distributedStorageManager.getDistributedStorageClient()` method
- Access: `myNode.nodeId` property

**Verification:**
- [ ] Compiles without errors
- [ ] Reads file bytes correctly
- [ ] Calls storageClient.storeFile() with correct parameters
- [ ] Handles null FileReference
- [ ] Invokes onFileStored callback
- [ ] Handles exceptions with onOperationFailed

---

#### 2.2 retrieveFile() - Retrieve File from Distributed Storage
**Current State:** `callback(Result.failure(NotImplementedError(...)))`  
**Target Implementation:**
```kotlin
override fun retrieveFile(fileId: String, callback: (Result<File>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val storageClient = distributedStorageManager?.getDistributedStorageClient()
                ?: return@launch callback(Result.failure(IllegalStateException("Storage not initialized")))
            
            // Create FileReference from fileId (need to query fileMetadataStore)
            val metadata = distributedStorageManager?.fileMetadataStore?.get(fileId)
                ?: return@launch callback(Result.failure(IllegalArgumentException("File not found: $fileId")))
            
            val fileData = storageClient.retrieveFile(metadata)
                ?: return@launch callback(Result.failure(Exception("Retrieval failed: null data")))
            
            // Write to temp file
            val tempFile = File.createTempFile("mesh_retrieve_", ".tmp")
            tempFile.writeBytes(fileData)
            
            callback(Result.success(tempFile))
            onFileRetrieved?.invoke(fileId, tempFile)
        } catch (e: Exception) {
            callback(Result.failure(e))
            onOperationFailed?.invoke("retrieveFile", e)
        }
    }
}
```

**Dependencies:**
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- `import java.io.File`
- Access: `distributedStorageManager.fileMetadataStore` property
- Access: `distributedStorageManager.getDistributedStorageClient()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Queries fileMetadataStore for FileReference
- [ ] Calls storageClient.retrieveFile()
- [ ] Writes data to temp file
- [ ] Invokes onFileRetrieved callback
- [ ] Handles exceptions

---

#### 2.3 streamFile() - Stream File from Distributed Storage
**Current State:** `callback(Result.failure(NotImplementedError(...)))`  
**Target Implementation:** ⚠️ **DEPENDS ON QUESTION 2 RESOLUTION**

**Option A (Chunked Retrieval - if FileChunkStore supports it):**
```kotlin
override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val metadata = distributedStorageManager?.fileMetadataStore?.get(fileId)
                ?: return@launch callback(Result.failure(IllegalArgumentException("File not found: $fileId")))
            
            // Stream chunks progressively via FileChunkStore
            for (chunkId in metadata.chunkIds) {
                val chunk = myNode?.fileChunkStore?.getChunk(chunkId)
                // TODO: Invoke progress callback with chunk data
            }
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
            onOperationFailed?.invoke("streamFile", e)
        }
    }
}
```

**Option B (Not Supported):**
```kotlin
override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
    callback(Result.failure(UnsupportedOperationException("streamFile not supported in canonical workflows - use retrieveFile()")))
}
```

**Dependencies:**
- Resolution of QUESTION 2
- Access: `myNode.fileChunkStore` (if Option A)
- Progress callback mechanism (if Option A)

**Verification:**
- [ ] Compiles without errors
- [ ] Documents streaming approach
- [ ] Handles exceptions

---

#### 2.4 deleteFile() - Delete File from Distributed Storage
**Current State:** `callback(Result.failure(NotImplementedError(...)))`  
**Target Implementation:**
```kotlin
override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Remove from fileMetadataStore
            distributedStorageManager?.fileMetadataStore?.remove(fileId)
            
            // Cleanup chunk replicas from tracker
            val chunkTracker = distributedStorageManager?.chunkReplicaTracker
            // TODO: Need API to remove all chunk replicas for fileId
            
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
            onOperationFailed?.invoke("deleteFile", e)
        }
    }
}
```

**Dependencies:**
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- Access: `distributedStorageManager.fileMetadataStore.remove()` method
- Access: `distributedStorageManager.chunkReplicaTracker` (need removal API)

**Verification:**
- [ ] Compiles without errors
- [ ] Removes metadata from store
- [ ] Cleans up chunk replicas
- [ ] Handles exceptions

---

#### 2.5 getAllMeshFiles() - List All Stored Files
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getAllMeshFiles(): List<MeshFile> {
    return distributedStorageManager?.fileMetadataStore?.values?.map { fileRef ->
        MeshFile(
            fileId = fileRef.fileId,
            fileName = fileRef.path.substringAfterLast('/'),
            filePath = fileRef.path,
            fileSize = fileRef.totalSize,
            timestamp = System.currentTimeMillis(), // TODO: Get actual timestamp from metadata
            replicationLevel = fileRef.chunkIds.size / 10, // Approximate
            owner = fileRef.owner
        )
    } ?: emptyList()
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshFile` (already imported)
- Access: `distributedStorageManager.fileMetadataStore.values` collection

**Verification:**
- [ ] Compiles without errors
- [ ] Converts FileReference to MeshFile correctly
- [ ] Handles null distributedStorageManager
- [ ] Returns empty list when no files

---

### SECTION 3: Gateway Controls Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 147-165  
**Methods:** 5

#### 3.1 setTorGatewayEnabled() - Enable/Disable Tor Gateway Role
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        val currentRoles = emergentRoleManager?.getCurrentMeshRoles()?.toMutableSet() ?: mutableSetOf()
        
        if (enabled) {
            currentRoles.add(MeshRole.TOR_GATEWAY)
        } else {
            currentRoles.remove(MeshRole.TOR_GATEWAY)
        }
        
        emergentRoleManager?.setPreferredRoles(currentRoles)
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.getCurrentMeshRoles()` method
- Access: `emergentRoleManager.setPreferredRoles()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Adds MeshRole.TOR_GATEWAY when enabled=true
- [ ] Removes MeshRole.TOR_GATEWAY when enabled=false
- [ ] Preserves other roles
- [ ] Handles exceptions

---

#### 3.2 getTorGatewayStatus() - Check Tor Gateway Role Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getTorGatewayStatus(): Boolean {
    return emergentRoleManager?.currentMeshRoles?.value?.contains(MeshRole.TOR_GATEWAY) ?: false
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property

**Verification:**
- [ ] Compiles without errors
- [ ] Returns true when TOR_GATEWAY role active
- [ ] Returns false when role not active or manager null

---

#### 3.3 setInternetGatewayEnabled() - Enable/Disable Internet Gateway Role
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        val currentRoles = emergentRoleManager?.getCurrentMeshRoles()?.toMutableSet() ?: mutableSetOf()
        
        if (enabled) {
            currentRoles.add(MeshRole.CLEARNET_GATEWAY)
        } else {
            currentRoles.remove(MeshRole.CLEARNET_GATEWAY)
        }
        
        emergentRoleManager?.setPreferredRoles(currentRoles)
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.getCurrentMeshRoles()` method
- Access: `emergentRoleManager.setPreferredRoles()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Adds MeshRole.CLEARNET_GATEWAY when enabled=true
- [ ] Removes MeshRole.CLEARNET_GATEWAY when enabled=false
- [ ] Preserves other roles
- [ ] Handles exceptions

---

#### 3.4 getInternetGatewayStatus() - Check Internet Gateway Role Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getInternetGatewayStatus(): Boolean {
    return emergentRoleManager?.currentMeshRoles?.value?.contains(MeshRole.CLEARNET_GATEWAY) ?: false
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property

**Verification:**
- [ ] Compiles without errors
- [ ] Returns true when CLEARNET_GATEWAY role active
- [ ] Returns false when role not active or manager null

---

#### 3.5 getGatewayStatus() - Check Any Gateway Role Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getGatewayStatus(): Boolean {
    val roles = emergentRoleManager?.currentMeshRoles?.value ?: return false
    return roles.contains(MeshRole.TOR_GATEWAY) || roles.contains(MeshRole.CLEARNET_GATEWAY)
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property

**Verification:**
- [ ] Compiles without errors
- [ ] Returns true when either gateway role active
- [ ] Returns false when no gateway roles or manager null

---

### SECTION 4: Storage Participation Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 169-196  
**Methods:** 5

#### 4.1 setStorageParticipationEnabled() - Enable/Disable Storage Participation
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        val config = StorageParticipationConfig(
            enabled = enabled,
            quotaMB = 1024, // Default 1GB
            allowedDirectories = listOf("/storage/mesh"), // Default path
            encryptionEnabled = true
        )
        
        distributedStorageManager?.configureStorageParticipation(config)
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageParticipationConfig` (verify class exists)
- Access: `distributedStorageManager.configureStorageParticipation()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Creates StorageParticipationConfig with defaults
- [ ] Calls configureStorageParticipation()
- [ ] Handles exceptions

---

#### 4.2 getStorageParticipationStatus() - Check Storage Participation Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getStorageParticipationStatus(): Boolean {
    return distributedStorageManager?.participationEnabled?.value ?: false
}
```

**Dependencies:**
- Access: `distributedStorageManager.participationEnabled` StateFlow property

**Verification:**
- [ ] Compiles without errors
- [ ] Returns participation enabled state
- [ ] Handles null manager

---

#### 4.3 getStorageAllocations() - List Storage Allocations
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getStorageAllocations(): List<StorageAllocation> {
    val stats = distributedStorageManager?.storageStats?.value ?: return emptyList()
    
    return listOf(
        StorageAllocation(
            deviceId = "internal",
            deviceName = "Internal Storage",
            totalMB = stats.totalCapacityMB,
            allocatedMB = stats.allocatedMB,
            usedMB = stats.usedMB,
            availableMB = stats.availableMB
        )
    )
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageAllocation` (already imported)
- Access: `distributedStorageManager.storageStats` StateFlow property

**Verification:**
- [ ] Compiles without errors
- [ ] Converts storageStats to StorageAllocation list
- [ ] Handles null stats

---

#### 4.4 setStorageAllocation() - Update Storage Allocation
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit) {
    try {
        distributedStorageManager?.storageQuotaManager?.updateConfiguration(
            quotaMB = allocatedMB
        )
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- Access: `distributedStorageManager.storageQuotaManager.updateConfiguration()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Calls updateConfiguration with new quota
- [ ] Handles exceptions

---

#### 4.5 getAvailableStorageDevices() - List Available Storage Devices
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getAvailableStorageDevices(): List<StorageDevice> {
    val context = appContext ?: return emptyList()
    
    val devices = mutableListOf<StorageDevice>()
    
    // Internal storage
    val internalDir = context.filesDir
    val internalStats = android.os.StatFs(internalDir.absolutePath)
    devices.add(StorageDevice(
        deviceId = "internal",
        deviceName = "Internal Storage",
        devicePath = internalDir.absolutePath,
        totalMB = (internalStats.totalBytes / 1024 / 1024),
        availableMB = (internalStats.availableBytes / 1024 / 1024),
        isRemovable = false
    ))
    
    // External storage (if available)
    if (android.os.Environment.getExternalStorageState() == android.os.Environment.MEDIA_MOUNTED) {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val externalStats = android.os.StatFs(externalDir.absolutePath)
            devices.add(StorageDevice(
                deviceId = "external",
                deviceName = "External Storage",
                devicePath = externalDir.absolutePath,
                totalMB = (externalStats.totalBytes / 1024 / 1024),
                availableMB = (externalStats.availableBytes / 1024 / 1024),
                isRemovable = true
            ))
        }
    }
    
    return devices
}
```

**Dependencies:**
- `import android.os.StatFs`
- `import android.os.Environment`
- `import com.ustadmobile.meshrabiya.storage.StorageDevice` (already imported)
- Access: `appContext` property

**Verification:**
- [ ] Compiles without errors
- [ ] Enumerates internal storage
- [ ] Enumerates external storage if mounted
- [ ] Calculates correct sizes
- [ ] Handles null context

---

### SECTION 5: Enhanced State Methods Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 99, 138-142  
**Methods:** 4

#### 5.1 getFitnessScore() - Calculate Node Fitness Score
**Current State:** `return 0`  
**Target Implementation:**
```kotlin
override fun getFitnessScore(): Int {
    val capabilities = myNode?.getCurrentNodeCapabilities() ?: return 0
    val normalizedFitness = emergentRoleManager?.calculateNormalizedFitness(capabilities) ?: 0.0
    return (normalizedFitness * 100).toInt()
}
```

**Dependencies:**
- Access: `myNode.getCurrentNodeCapabilities()` method
- Access: `emergentRoleManager.calculateNormalizedFitness()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Returns 0-100 fitness score
- [ ] Handles null node/manager

---

#### 5.2 getMeshStatus() - Get Current Mesh State
**Current State:** `return MeshState.UNKNOWN`  
**Target Implementation:**
```kotlin
override fun getMeshStatus(): MeshState {
    val nodeState = myNode?.currentNodeState ?: return MeshState.STOPPED
    val neighbors = myNode?.neighbors()?.size ?: 0
    
    return when {
        nodeState.hotspotEnabled && neighbors > 0 -> MeshState.CONNECTED
        nodeState.hotspotEnabled -> MeshState.IDLE
        else -> MeshState.STOPPED
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.MeshState` (already imported)
- Access: `myNode.currentNodeState` property
- Access: `myNode.neighbors()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Returns CONNECTED when hotspot + neighbors
- [ ] Returns IDLE when hotspot only
- [ ] Returns STOPPED when no hotspot
- [ ] Handles null node

---

#### 5.3 getNetworkInfo() - Get Mesh Network Information
**Current State:** `return NetworkInfo()`  
**Target Implementation:**
```kotlin
override fun getNetworkInfo(): NetworkInfo {
    val topologySize = myNode?.originatingMessageManager?.getAllOriginatingMessages()?.size ?: 0
    val neighborCount = myNode?.neighbors()?.size ?: 0
    val roles = emergentRoleManager?.currentMeshRoles?.value ?: emptySet()
    
    return NetworkInfo(
        topologySize = topologySize,
        directPeers = neighborCount,
        activeRoles = roles.map { it.name },
        meshId = myNode?.nodeId?.toString() ?: "unknown"
    )
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.NetworkInfo` (already imported)
- Access: `myNode.originatingMessageManager.getAllOriginatingMessages()` method
- Access: `myNode.neighbors()` method
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property
- Access: `myNode.nodeId` property

**Verification:**
- [ ] Compiles without errors
- [ ] Aggregates topology size
- [ ] Aggregates neighbor count
- [ ] Aggregates active roles
- [ ] Handles null node/manager

---

#### 5.4 getNodeInfo() - Get Specific Node Information
**Current State:** `return NodeInfo()`  
**Target Implementation:**
```kotlin
override fun getNodeInfo(nodeId: String): NodeInfo {
    val nodeIdInt = nodeId.toIntOrNull() ?: return NodeInfo()
    val originatingMsg = myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeIdInt)
        ?: return NodeInfo()
    
    return NodeInfo(
        nodeId = nodeId,
        hopCount = originatingMsg.hopCount.toInt(),
        lastSeen = System.currentTimeMillis(), // TODO: Get actual timestamp
        isNeighbor = myNode?.neighbors()?.any { it.first == nodeIdInt } ?: false
    )
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.NodeInfo` (already imported)
- Access: `myNode.originatingMessageManager.findOriginatingMessageFor()` method
- Access: `myNode.neighbors()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Queries originatingMessageManager
- [ ] Returns hop count
- [ ] Checks neighbor status
- [ ] Handles invalid nodeId
- [ ] Handles node not found

---

### SECTION 6: Event Handler Wiring Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 83-96 (in initMesh method)  
**Methods:** 3 handler registrations

#### 6.1 Wire Mesh State Change Handler
**Current State:** Handler registered but never invoked  
**Target Implementation:** Add to `initMesh()` method:
```kotlin
// In initMesh(), after manager initialization:

// Wire mesh state change handler
CoroutineScope(Dispatchers.IO).launch {
    myNode?.state?.collect { newState ->
        val meshState = when {
            newState.hotspotEnabled && (myNode?.neighbors()?.size ?: 0) > 0 -> MeshState.CONNECTED
            newState.hotspotEnabled -> MeshState.IDLE
            else -> MeshState.STOPPED
        }
        onMeshStateChanged?.invoke(meshState)
    }
}
```

**Dependencies:**
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- `import kotlinx.coroutines.flow.collect`
- Access: `myNode.state` Flow property

**Verification:**
- [ ] Compiles without errors
- [ ] Collects myNode.state Flow
- [ ] Invokes onMeshStateChanged callback
- [ ] Handles coroutine lifecycle

---

#### 6.2 Wire Peer Count Change Handler
**Current State:** Handler registered but never invoked  
**Target Implementation:** Add to `initMesh()` method:
```kotlin
// In initMesh(), after manager initialization:

// Wire peer count change handler (poll-based)
CoroutineScope(Dispatchers.IO).launch {
    var lastCount = 0
    while (true) {
        val currentCount = myNode?.neighbors()?.size ?: 0
        if (currentCount != lastCount) {
            onPeerCountChanged?.invoke(currentCount)
            lastCount = currentCount
        }
        kotlinx.coroutines.delay(5000) // Poll every 5 seconds
    }
}
```

**Dependencies:**
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- `import kotlinx.coroutines.delay`
- Access: `myNode.neighbors()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Polls neighbor count
- [ ] Invokes onPeerCountChanged when changed
- [ ] Handles coroutine lifecycle
- [ ] Appropriate polling interval

---

#### 6.3 Wire Gossip Message Handler
**Current State:** Handler registered but `addGossipListener` commented out  
**Target Implementation:** Add to `initMesh()` method:
```kotlin
// In initMesh(), after manager initialization:

// Wire gossip message handler
myNode?.getMeshGossipService()?.addGossipListener { senderId, messageBytes ->
    onGossipMessage?.invoke(senderId, messageBytes)
}
```

**Dependencies:**
- Access: `myNode.getMeshGossipService().addGossipListener()` method

**Verification:**
- [ ] Compiles without errors
- [ ] Registers gossip listener
- [ ] Invokes onGossipMessage callback
- [ ] Handles null gossip service

---

### SECTION 7: Drop Folder Implementation ⭐ LOW PRIORITY
**Priority:** LOW (Not in canonical workflows yet)  
**Status:** ❌ NOT STARTED  
**Lines:** 200-209  
**Methods:** 3

#### 7.1 selectDropFolder() - Set Drop Folder Path
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:** ⚠️ **DEPENDS ON QUESTION 3 RESOLUTION**

**Option A (With Auto-Sync):**
```kotlin
private var dropFolderPath: String? = null
private var dropFolderObserver: FileObserver? = null

override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
    try {
        dropFolderPath = path
        
        // Stop existing observer
        dropFolderObserver?.stopWatching()
        
        // Create new FileObserver for auto-sync
        dropFolderObserver = object : FileObserver(path, CREATE or MODIFY) {
            override fun onEvent(event: Int, filename: String?) {
                filename?.let {
                    val file = File(path, it)
                    if (file.isFile) {
                        storeFile(file) { result ->
                            result.onSuccess { fileId ->
                                onFileAddedToDropFolder?.invoke(fileId, file)
                            }
                        }
                    }
                }
            }
        }
        dropFolderObserver?.startWatching()
        
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Option B (Manual Sync Only):**
```kotlin
private var dropFolderPath: String? = null

override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
    try {
        dropFolderPath = path
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- Resolution of QUESTION 3
- `import android.os.FileObserver` (if Option A)
- Access to storeFile() method (if Option A)

**Verification:**
- [ ] Compiles without errors
- [ ] Sets dropFolderPath
- [ ] Creates FileObserver (if Option A)
- [ ] Auto-syncs files (if Option A)
- [ ] Handles exceptions

---

#### 7.2 getDropFolder() - Get Drop Folder Path
**Current State:** `return null`  
**Target Implementation:**
```kotlin
override fun getDropFolder(): File? {
    return dropFolderPath?.let { File(it) }
}
```

**Dependencies:**
- `dropFolderPath` property from 7.1

**Verification:**
- [ ] Compiles without errors
- [ ] Returns File for set path
- [ ] Returns null when not set

---

#### 7.3 getDropFolderFiles() - List Drop Folder Files
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getDropFolderFiles(): List<File> {
    val folder = getDropFolder() ?: return emptyList()
    return folder.listFiles()?.filter { it.isFile } ?: emptyList()
}
```

**Dependencies:**
- `getDropFolder()` method from 7.2

**Verification:**
- [ ] Compiles without errors
- [ ] Lists folder children
- [ ] Filters for files only
- [ ] Handles null folder

---

### SECTION 8: OrbotMeshService Refactoring ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM (Depends on Section 2 completion)  
**Status:** ❌ NOT STARTED  
**File:** `orbotservice/src/main/java/org/torproject/android/service/OrbotMeshService.kt`  
**Current Lines:** 91  
**Changes:** 5 major refactorings

#### 8.1 Fix Deprecated Imports (CRITICAL - BLOCKS COMPILATION)
**Current State:** Lines 8-10 - 10 compilation errors from removed classes  
**Errors:**
```
Unresolved reference: DataStore
Unresolved reference: MeshFile  
Unresolved reference: ReplicationManager
```

**Target Implementation:**
```kotlin
// REMOVE these imports (lines 8-10):
// import com.ustadmobile.meshrabiya.storage.DataStore
// import com.ustadmobile.meshrabiya.storage.MeshFile
// import com.ustadmobile.meshrabiya.storage.ReplicationManager

// REMOVE these property declarations (lines 33-35):
// private lateinit var replicationManager: ReplicationManager
// private lateinit var dataStore: DataStore

// REMOVE this initialization (line 44):
// dataStore = DataStore.getInstance(applicationContext)

// REMOVE this initialization (line 46):
// replicationManager = ReplicationManager(applicationContext)
```

**Dependencies:**
- None (pure deletion)

**Verification:**
- [ ] Deprecated imports removed
- [ ] Property declarations removed
- [ ] Initialization calls removed
- [ ] Compiles without errors

**Impact Analysis:**
- `DataStore` removed: Was used at line 44 only for initialization (unused)
- `ReplicationManager` removed: Was used at lines 46 (init) and 85 (replicateFile call)
- `MeshFile` removed: Not actually used in this file (import only)
- Line 85 `replicationManager.replicateFile(fileId, file)` needs replacement

---

#### 8.2 Remove Redundant storeReceivedFile() Wrapper
**Current State:** Lines 54-72 - redundant wrapper around `meshrabiyaApi.storeFile()`  
**Analysis:** Method provides no value beyond direct API call - just forwards callbacks

**Target Implementation:**
```kotlin
// DELETE entire method (lines 54-72)
// Method is pure delegation with no additional logic

// UPDATE onMeshFileReceived (line 74-91) to call API directly:
fun onMeshFileReceived(file: File, senderNodeId: String) {
    try {
        meshrabiyaApi.storeFile(file) { result ->
            result.fold(
                onSuccess = { fileId ->
                    println("INFO: File received, stored with fileId $fileId from sender $senderNodeId")
                    // Replication now handled by DistributedStorageManager internally
                },
                onFailure = { exception ->
                    println("ERROR: Failed to process received file from $senderNodeId: ${exception.message}")
                }
            )
        }
    } catch (e: Exception) {
        println("ERROR: Failed to process received file from $senderNodeId: ${e.message}")
    }
}
```

**Rationale:**
- `storeReceivedFile()` just calls `meshrabiyaApi.storeFile()` and forwards callbacks
- No additional logic, validation, or transformation
- Replication now handled by DistributedStorageManager (canonical workflows), not ReplicationManager
- Direct API call is clearer and removes indirection

**Verification:**
- [ ] storeReceivedFile() method deleted (lines 54-72)
- [ ] onMeshFileReceived() updated to call API directly
- [ ] ReplicationManager.replicateFile() call removed (deprecated)
- [ ] Compiles without errors

---

#### 8.3 Add Tor Proxy Integration Handler
**Current State:** No Tor proxy integration in OrbotMeshService  
**Purpose:** Enable mesh to route through Tor when Tor service is ready

**Target Implementation:** Add new method after onCreate():
```kotlin
/**
 * Called by OrbotService when Tor SOCKS proxy is ready.
 * Configures mesh to route all traffic through Tor for anonymity.
 */
fun onTorReady(socksPort: Int) {
    try {
        meshrabiyaApi.setProxy("127.0.0.1", socksPort)
        meshrabiyaApi.setProxyActive(true)
        println("INFO: Mesh configured to use Tor SOCKS proxy on port $socksPort")
    } catch (e: Exception) {
        println("ERROR: Failed to configure Tor proxy: ${e.message}")
    }
}

/**
 * Called by OrbotService when Tor service stops.
 * Disables proxy routing.
 */
fun onTorStopped() {
    try {
        meshrabiyaApi.setProxyActive(false)
        println("INFO: Mesh proxy disabled (Tor stopped)")
    } catch (e: Exception) {
        println("ERROR: Failed to disable Tor proxy: ${e.message}")
    }
}
```

**Integration Point:**
- OrbotService.kt must call `orbotMeshService.onTorReady(socksPort)` when Tor starts
- OrbotService.kt must call `orbotMeshService.onTorStopped()` when Tor stops

**Dependencies:**
- MeshrabiyaApi.setProxy() (already implemented, lines 71-73 of MeshrabiyaApiImpl)
- MeshrabiyaApi.setProxyActive() (already implemented, lines 75-77 of MeshrabiyaApiImpl)

**Verification:**
- [ ] onTorReady() method added
- [ ] onTorStopped() method added
- [ ] Called from OrbotService when Tor starts/stops
- [ ] Compiles without errors
- [ ] Proxy configuration tested

---

#### 8.4 Wire Event Handlers in onCreate()
**Current State:** No event handler registration in onCreate() - handlers never invoked  
**Purpose:** Connect MeshrabiyaApi callbacks to OrbotMeshService lifecycle

**Target Implementation:** Update onCreate() (lines 38-47):
```kotlin
override fun onCreate() {
    super.onCreate()
    
    // Initialize MeshrabiyaApi singleton
    meshrabiyaApi = MeshrabiyaApiImpl.getInstance()
    meshrabiyaApi.initMesh(applicationContext)
    
    // Wire file operation handlers
    meshrabiyaApi.setOnFileStored { fileId, file ->
        println("INFO: File stored in mesh: $fileId (${file.name})")
        // TODO: Broadcast to UI if needed
    }
    
    meshrabiyaApi.setOnFileRetrieved { fileId, file ->
        println("INFO: File retrieved from mesh: $fileId (${file.name})")
        // TODO: Notify user of received file
    }
    
    meshrabiyaApi.setOnPermissionUpdated { fileId, success ->
        if (success) {
            println("INFO: File permissions updated: $fileId")
        } else {
            println("WARN: Failed to update file permissions: $fileId")
        }
    }
    
    meshrabiyaApi.setOnOperationFailed { operation, error ->
        println("ERROR: Mesh operation '$operation' failed: ${error.message}")
        // TODO: Show error notification to user
    }
    
    meshrabiyaApi.setOnFileShared { fileId, recipientId ->
        println("INFO: File $fileId shared with node $recipientId")
    }
    
    meshrabiyaApi.setOnFileAddedToDropFolder { fileId, file ->
        println("INFO: File auto-uploaded from drop folder: $fileId (${file.name})")
    }
    
    // Wire mesh state handlers
    meshrabiyaApi.setOnMeshStateChanged { newState ->
        println("INFO: Mesh state changed to: $newState")
        // TODO: Broadcast state change to UI
    }
    
    meshrabiyaApi.setOnPeerCountChanged { newCount ->
        println("INFO: Mesh peer count changed to: $newCount")
        // TODO: Update UI with peer count
    }
    
    meshrabiyaApi.setOnGossipMessage { senderId, messageBytes ->
        println("INFO: Received gossip message from node $senderId (${messageBytes.size} bytes)")
        // TODO: Process mesh-level messages
    }
    
    meshrabiyaApi.setOnGatewayTraffic { packet ->
        // Return false to allow default routing, true to intercept
        false
    }
}
```

**Rationale:**
- All 10 event handlers are registered but never invoked (no-op)
- Handlers must be wired in onCreate() to receive mesh events
- File handlers coordinate file lifecycle (storage, retrieval, sharing)
- Mesh state handlers track network changes
- Gateway handler allows custom traffic routing

**Dependencies:**
- All setOn*() methods from MeshrabiyaApi interface
- Section 6 implementation (Event Handler Wiring in MeshrabiyaApiImpl)

**Verification:**
- [ ] All 10 event handlers registered in onCreate()
- [ ] Handlers log events appropriately
- [ ] Compiles without errors
- [ ] Handlers invoked when events occur (requires Section 6 complete)

---

#### 8.5 Add Missing onBind() IBinder Return (Optional Enhancement)
**Current State:** Line 48 - returns null (service cannot be bound)  
**Purpose:** Allow OrbotService to bind to OrbotMeshService for direct communication

**Target Implementation (Optional):**
```kotlin
// Create Binder interface for OrbotService communication
private val binder = MeshServiceBinder()

inner class MeshServiceBinder : Binder() {
    fun getService(): OrbotMeshService = this@OrbotMeshService
}

override fun onBind(intent: Intent?): IBinder? = binder
```

**Rationale:**
- Currently service cannot be bound (returns null)
- Binding allows OrbotService to call onTorReady() directly
- Alternative: Use broadcasts/intents (current approach)

**Decision Required:**
- [ ] Keep null return (use broadcasts for communication)
- [ ] Implement binder (use direct method calls)

**Verification (if implemented):**
- [ ] Binder class added
- [ ] onBind() returns binder instance
- [ ] OrbotService binds to service
- [ ] Compiles without errors

---

### OrbotMeshService Summary

**Current Issues:**
1. ❌ 10 compilation errors from deprecated imports (DataStore, MeshFile, ReplicationManager)
2. ❌ Redundant storeReceivedFile() wrapper method
3. ❌ No Tor proxy integration
4. ❌ Event handlers registered but never invoked
5. ❌ ReplicationManager.replicateFile() call uses deprecated API

**After Refactoring:**
1. ✅ All deprecated imports removed
2. ✅ Direct MeshrabiyaApi usage (no wrappers)
3. ✅ Tor proxy integration (onTorReady/onTorStopped)
4. ✅ All 10 event handlers wired in onCreate()
5. ✅ Replication handled by DistributedStorageManager (canonical workflows)
6. ✅ 0 compilation errors

**Lines Changed:**
- Deleted: ~35 lines (imports, properties, storeReceivedFile method)
- Added: ~65 lines (onTorReady, onTorStopped, event handler wiring)
- Modified: ~10 lines (onMeshFileReceived refactor)
- **Net change:** +30 lines (final ~120 lines total)

**Integration Requirements:**
- OrbotService must call `orbotMeshService.onTorReady(socksPort)` when Tor starts
- OrbotService must call `orbotMeshService.onTorStopped()` when Tor stops
- Event handlers require Section 6 implementation (MeshrabiyaApiImpl event wiring)

**Dependencies:**
- Section 2 complete (File Operations in MeshrabiyaApiImpl)
- Section 6 complete (Event Handler Wiring in MeshrabiyaApiImpl)
- MeshrabiyaApi.setProxy/setProxyActive methods (already implemented)

---

## Import Requirements

### New Imports Needed for MeshrabiyaApiImpl.kt
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import com.ustadmobile.meshrabiya.service.compute.LocalComputeTaskRequest
import com.ustadmobile.meshrabiya.service.compute.TaskState
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.storage.StorageParticipationConfig
import android.os.StatFs
import android.os.Environment
import android.os.FileObserver // If drop folder auto-sync implemented
```

---

## Build Verification Strategy

### After Each Section:
1. Run focused compile: `: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee build_output.log`
2. Check for errors: `grep -E "^e: " build_output.log`
3. Fix any compilation errors before proceeding

### After All Sections:
1. Full app build: `: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :app:assembleDebug --console=plain 2>&1 | tee build_output.log`
2. Check OrbotMeshService errors: `grep -E "^e: " build_output.log | grep OrbotMeshService`
3. Verify 0 errors in final build

---

## Success Criteria

- ✅ All 26+ methods fully implemented (NO stubs, TODOs, NotImplementedErrors)
- ✅ All imports resolved
- ✅ 0 compilation errors in MeshrabiyaApiImpl.kt
- ✅ 0 compilation errors in OrbotMeshService.kt
- ✅ All event handlers wired and invocable
- ✅ Drop folder implementation complete (or documented limitation)
- ✅ Full app build succeeds (`:app:assembleDebug`)
- ✅ All 3 open questions resolved with documented decisions
- ✅ INTERIM_COMMIT_LOG.md updated with complete implementation details

---

## Completion Tracking

### Overall Progress: 0% (0/8 sections complete)

**Section Status:**
- [ ] Section 1: Compute/Task API (0/4 methods)
- [ ] Section 2: File Operations (0/5 methods)
- [ ] Section 3: Gateway Controls (0/5 methods)
- [ ] Section 4: Storage Participation (0/5 methods)
- [ ] Section 5: Enhanced State (0/4 methods)
- [ ] Section 6: Event Handlers (0/3 handlers)
- [ ] Section 7: Drop Folder (0/3 methods)
- [ ] Section 8: OrbotMeshService Refactor (0/3 changes)

**Open Questions:**
- [ ] Question 1: Task Status/Cancellation - UNRESOLVED
- [ ] Question 2: Stream File - UNRESOLVED
- [ ] Question 3: Drop Folder Auto-Sync - UNRESOLVED

**Build Verification:**
- [ ] Section 1 compile verified
- [ ] Section 2 compile verified
- [ ] Section 3 compile verified
- [ ] Section 4 compile verified
- [ ] Section 5 compile verified
- [ ] Section 6 compile verified
- [ ] Section 7 compile verified
- [ ] Section 8 compile verified
- [ ] Full app build verified

---

## Next Steps

1. **RESOLVE OPEN QUESTIONS** (Required before implementation)
   - Review MeshrabiyaApi interface for required signatures
   - Research TaskManager for task status/cancellation approach
   - Research FileChunkStore API for streaming support
   - Decide on drop folder auto-sync vs manual approach

2. **IMPLEMENT SECTION 1** (Compute/Task API - HIGHEST PRIORITY)
   - Implement all 4 methods
   - Add required imports
   - Verify compilation

3. **IMPLEMENT SECTION 2** (File Operations - HIGH PRIORITY)
   - Implement all 5 methods
   - Add required imports
   - Verify compilation

4. **IMPLEMENT SECTIONS 3-7** (Medium/Low Priority)
   - Follow priority order
   - Verify compilation after each section

5. **IMPLEMENT SECTION 8** (OrbotMeshService Refactor)
   - Remove deprecated imports
   - Wire event handlers
   - Verify compilation

6. **FINAL VERIFICATION**
   - Full app build
   - Update INTERIM_COMMIT_LOG.md
   - Commit with detailed message

---

**END OF PLAN**
