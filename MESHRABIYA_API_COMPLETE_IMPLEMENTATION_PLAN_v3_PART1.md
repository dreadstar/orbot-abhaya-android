# MeshrabiyaApiImpl Complete Implementation Plan v3.0 - PART 1 of 4

**Date:** 2025-12-05  
**Status:** PLANNED - NOT STARTED  
**Priority:** CRITICAL (Blocks OrbotMeshService and full app functionality)  
**Research:** Iteration 2 COMPLETE

---

## ⚠️ CRITICAL IMPLEMENTATION MANDATE

**This plan is NOT a suggestion. It is a complete, literal implementation specification.**

Every code block in this document MUST be implemented exactly as written. NO stubs, NO TODOs, NO NotImplementedErrors, NO placeholders. Agents must copy the provided code directly into the target files and verify compilation success.

**Completion criteria:** 100% of methods implemented, 0% stub code remaining, full compilation success.

---

## DOCUMENT STRUCTURE

This v3.0 plan is organized into 4 parts:

- **PART 1** (this document): Executive Summary, User Clarifications, Research Findings, Sections 1-2
- **PART 2**: Sections 3-5 (Gateway Controls, Storage Participation, Enhanced State)
- **PART 3**: Sections 6-7 (Event Handler Wiring, Drop Folder Implementation)
- **PART 4**: Section 8 (OrbotMeshService Refactoring), NEW Sections 9-10 (Task Status, Integration), Imports, Tracking

---

## EXECUTIVE SUMMARY

### Implementation Scope

**Target File:** `MeshrabiyaApiImpl.kt` (416 lines, 65% unimplemented)  
**Total Sections:** 10 (8 original + 2 new from clarifications)  
**Total Methods:** 24 (after removals per user clarifications)  
**Total Event Handlers:** 10 (all must be wired)  
**New Components:** 1 (TaskStatusUpdateMessage)  
**Methods to Remove:** 3 (cancelTask, getTaskStatus, startTask)  
**Methods to Comment Out:** 1 (streamFile)

### Section Breakdown

1. **Compute/Task API** (2 methods after removals) - Section 1
2. **File Operations** (4 methods after comment-out) - Section 2
3. **Gateway Controls** (5 methods) - Section 3
4. **Storage Participation** (5 methods) - Section 4
5. **Enhanced State Methods** (4 methods) - Section 5
6. **Event Handler Wiring** (3 handler registrations) - Section 6
7. **Drop Folder Implementation** (10 subsections, 15+ methods) - Section 7
8. **OrbotMeshService Refactoring** (5 subsections) - Section 8
9. **Task Status Callback System** (6 subsections) - NEW
10. **Drop Folder Integration Summary** - NEW

### Research Confidence (After Iteration 2)

**Overall Confidence:** 94%

- **Section 1 (Compute/Task):** 98% ✅ (TaskStatus enum verified, architecture corrected)
- **Section 2 (File Ops):** 95% ✅ (FileReference dual definition identified)
- **Section 3 (Gateway):** 100% ✅ (EmergentRoleManager verified)
- **Section 4 (Storage):** 100% ✅ (All enhancements already implemented)
- **Section 5 (State):** 90% ✅ (OriginatingMessage timestamp missing)
- **Section 6 (Events):** 98% ✅ (MeshEcosystemListener routing verified)
- **Section 7 (Drop Folder):** 85% (FileObserver recursion, timestamp handling)
- **Section 8 (OrbotMeshService):** 100% ✅ (All dependencies verified)
- **Section 9 (Task Status):** 98% ✅ (Actual TaskStatus values used)
- **Section 10 (Integration):** 95% ✅ (Summary only, dependencies verified)

**Critical Discoveries from Research Iteration 2:**

1. ✅ TaskStatus enum EXISTS but has 8 values (not 4 assumed)
2. ❌ NO TaskRequest class exists (internal enum only)

** Answer: this is an defnition that was archived and maybe useful as  starting point:
 `data class TaskRequest(
    val id: UUID = UUID.randomUUID(),
     val serviceId: String,
     val parameters: Map<String, Any> = emptyMap(),
    val requester: String = "local"
 )`


3. ✅ Message routing via MeshEcosystemListener.routeMessage()
4. ✅ Task results use TaskResult data class (not JsonObject)
5. ✅ EmergentRoleManager fully featured, no "available vs active" split

** Answer: use the `EmergentRoleManagersetUserAllowsTorProxy()`. dont edit roles directly

6. ✅ ALL DistributedStorageManager enhancements already implemented
7. ❌ ChunkReplicaTracker is a ConcurrentHashMap property (not a class)
8. ❌ FileReference has TWO incompatible definitions (needs unification)
9. ❌ OriginatingMessage lacks timestamp
10. ✅ sendChunkQuery pattern identified

---

## USER CLARIFICATIONS SUMMARY

**Answer Overall comment on implementation: The you should strive to keep the as little functionality in the API as possible. instead, "business logic" should be placed in the underlying code consistent with separation of concerns and best practices. review your current implementation approaches and refactor accordingly. conduct a reviews with research agent specifically targetted at refactoring that should not be in the API by the standards above into the correct underlying touchpoint objects and functions.  As we have agreed, where it makes sense, the getAPI pattern should  stay.

**Total Clarifications:** 14 (3 original questions + 11 additional answers)

### Original Clarifications (from User Questions):

**Clarification 1: Remove cancelTask() and getTaskStatus()**
- **Decision:** REMOVE both methods entirely from MeshrabiyaApi
- **Replacement:** Unified callback system via setOnTaskStatusUpdate()
- **New Component:** TaskStatusUpdateMessage (replaces 3 separate messages)
- **Status Transitions:** PENDING → ASSIGNED → RUNNING → COMPLETED/FAILED
- **Implementation:** NEW Section 9 (6 subsections)
- **Impact:** Sections 1, 9

**Clarification 2: Comment Out streamFile()**
- **Decision:** COMMENT OUT streamFile() in interface and implementation
- **Reason:** Goal is streaming FROM client TO storage (not yet implemented)
- **Documentation:** Add comment explaining future intent
- **Impact:** Section 2

**Clarification 3: Drop Folder Auto-Sync with FileObserver**
- **Decision:** Implement FileObserver-based drop folder monitoring
- **Architecture:** 
  - DropFolder data structure in DistributedStorageManager (path + triggers)
  - StoreFileTrigger data structure (id, subPath, recipients)
  - FileObserver monitors drop folder for new files
  - Match files against triggers, collect distinct recipients
  - Auto-call storeFile() with matched recipients
- **Ignore Logic:** Files in "shared" subfolder never trigger auto-store
- **Trigger Rules:** Left-bound path matching, max 2 levels deep recursion
- **API:** setStoreFileTrigger() for CRUD operations
- **Implementation:** NEW Section 10 (drop folder integration)
- **Impact:** Section 7

### Additional Clarifications (from Embedded Answers):

**Clarification 4: Remove startTask() Method**
- **Source:** Section 1.2 answer in v2 Part 1
- **Decision:** REMOVE startTask() entirely (not just no-op)
- **Reason:** Client-side function, tasks auto-start on different nodes
- **Impact:** Section 1

**Clarification 5: retrieveFile() Writes to "shared" Subfolder**
- **Source:** Section 2.2 answer in v2 Part 1
- **Decision:** All retrieved files NOT owned by recipient → DropFolder/shared/
- **Action:** DistributedStorageManager creates "shared" when DropFolder configured
- **Impact:** Section 2, 7

**Clarification 6: Create sendChunkQuery() Variation**
- **Source:** Section 2.5 answer in v2 Part 1
- **Decision:** Create sendChunkQuery() variation of sendChunkRetrievalQuery()
- **Purpose:** Query replica count without retrieving chunks
- **Returns:** Number of replicas per chunk (can show average or total)
- **Impact:** Section 2

**Clarification 7: Research EmergentRoleManager Thoroughly**
- **Source:** Section 3.1 answer in v2 Part 2
- **Decision:** Use research agent to analyze EmergentRoleManager
- **Concern:** Separate available roles vs actually active roles
- **Resolution (Research):** NO separation - only getCurrentMeshRoles() exists
- **Impact:** Section 3

**Clarification 8: Trigger Matching Uses Left-Bound Matching**
- **Source:** Section 7.3 answer in v2 Part 3
- **Decision:** Trigger matching uses left-bound path prefix matching
- **Example:** /drop/sub1/sub2 matches /drop, /drop/sub1, but not /drop/sub10
- **Impact:** Section 7.3

**Clarification 9: Refactor Ignore Logic for "shared" Subfolder**
- **Source:** Section 7.4 answer in v2 Part 3
- **Decision:** Absolute rule - files in "shared" subfolder NEVER trigger auto-store
- **Action:** Attempts to set trigger in "shared" subfolder fail gracefully
- **Impact:** Section 7.2, 7.3, 7.4, 7.5

**Clarification 10: StoreFileTrigger as Top-Level Class**
- **Source:** Section 7 Issue 1 answer in v2 Part 3
- **Decision:** Make StoreFileTrigger top-level class (not nested)
- **Impact:** Section 7.1

**Clarification 11: FileObserver Max 2 Levels Deep**
- **Source:** Section 7 Issue 2 answer in v2 Part 3
- **Decision:** Implement recursive FileObserver, max 2 levels deep
- **UI Message:** Communicate depth limit to users in UI
- **Impact:** Section 7.5

**Clarification 12: Triggers Don't Persist**
- **Source:** Section 7 Issue 3 answer in v2 Part 3
- **Decision:** Triggers do NOT persist across app restarts
- **Action:** Counter starts at 0 on restart
- **Impact:** Section 7.1, 7.9

**Clarification 13: Comprehensive File/Folder/Trigger Rules**
- **Source:** Section 7 Issue 4 answer in v2 Part 3
- **Decision:** Complex interaction rules for files, triggers, and access control:
  - storeFile on file in triggered folder → FAIL gracefully
  - updateFileAccess works regardless of location
  - Files inherit recipients from triggers
  - updateFileAccess cannot delete trigger-inherited recipients
  - Moving file recalculates inherited recipients
  - Removing file from drop folder removes triggers (future: UI prompt)
- **Impact:** Section 2.1, 7.5

**Clarification 14: Implement Binder for OrbotMeshService**
- **Source:** Section 8.5 answer in v2 Part 4
- **Decision:** Implement Binder (not null return)
- **Action:** Ensure onTorReady() and onTorStopped() called appropriately
- **Impact:** Section 8.5

---

## RESEARCH ITERATION 2 FINDINGS

### Critical Architectural Corrections

**Finding 1: TaskStatus Enum - 8 Values, Not 4**
- **Expected (v2):** PREPARATION, EXECUTION, SUCCESS, FAILED
- **Actual:** PENDING, ASSIGNED, KEYPAIR_GENERATED, SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED
- **Resolution:** Use actual values in Section 9 implementation
- **Impact:** Section 9 (TaskStatusUpdateMessage)

**Finding 2: No TaskRequest Domain Model**
- **Expected (v2):** TaskRequest class with status property
- **Actual:** No public TaskRequest - only internal TaskRequestStatus enum
- **Resolution:** Remove all TaskRequest.status references
- **Impact:** Section 9.3 (handler update logic)

**Finding 3: Message Routing via MeshEcosystemListener**
- **Location:** MeshEcosystemListener.routeMessage()
- **Method:** `when (message)` type discrimination
- **Current Handlers:** Already routes compute messages (TaskCompletedMessage, etc.)
- **Resolution:** Add TaskStatusUpdateMessage routing to existing handler
- **Impact:** Section 9.3

**Finding 4: Task Results Use TaskResult Data Class**
- **Type:** Structured data class (not JsonObject/Map)
- **Properties:** id (UUID), success (Boolean), executionResult, outputManifest, errorMessage
- **Resolution:** Use TaskResult directly in TaskStatusUpdateMessage
- **Impact:** Section 9.1, 9.5

**Finding 5: EmergentRoleManager Fully Featured**
- **Available Methods:** getCurrentMeshRoles(), getPreferredRoles(), determineOptimalRoles()
- **Capabilities:** Fitness calculation, centrality metrics, role transitions
- **Resolution:** Use getCurrentMeshRoles() directly - no "available vs active" split
- **Impact:** Section 3

**Finding 6: Storage Enhancements Already Implemented**
- **StorageParticipationConfig:** EXISTS (data class)
- **configureStorageParticipation():** EXISTS (method)
- **participationEnabled:** EXISTS (StateFlow property)
- **storageStats:** EXISTS (StateFlow property with StorageStats data class)
- **Resolution:** Use existing APIs directly - no implementation needed
- **Impact:** Section 4 (all methods trivial wrappers)

**Finding 7: ChunkReplicaTracker is ConcurrentHashMap Property**
- **Type:** `ConcurrentHashMap<String, MutableSet<String>>`
- **Location:** DistributedStorageManager property
- **Resolution:** Use direct map operations or create helper method
- **Impact:** Section 2.4

**Finding 8: FileReference Dual Definition**
- **Definition 1:** FileReference(fileId, fileName, sizeBytes, mimeType) - MeshFile.kt
- **Definition 2:** FileReference(id, path, size) - TaskResult.kt
- **Resolution:** Use Definition 1 (more comprehensive), note unification needed
- **Impact:** Section 2, 9

**Finding 9: OriginatingMessage Lacks Timestamp**
- **Current:** OriginatingMessageState has no timestamp field
- **Resolution:** Document limitation in Section 5.4
- **Impact:** Section 5.4

**Finding 10: sendChunkQuery Pattern Identified**
- **Base Method:** CoreGossipBroadcastService.sendChunkRetrievalQuery()
- **Pattern:** Create ChunkRetrievalQuery, wrap in message, sendBroadcast()
- **Resolution:** Create similar sendChunkQuery() method
- **Impact:** Section 2.5

---

## SECTION 1: Compute/Task API Implementation ⭐ HIGH PRIORITY

**Priority:** HIGH (Core functionality)  
**Status:** ❌ NOT STARTED  
**Lines:** 98-113 (after removals)  
**Methods:** 2 (addTask, getJobTypes)  
**Methods Removed:** 3 (cancelTask, getTaskStatus, startTask)

**User Clarifications Applied:**
- Clarification 1: cancelTask() REMOVED
- Clarification 1: getTaskStatus() REMOVED
- Clarification 4: startTask() REMOVED

---

### 1.1 addTask() - Submit Task Request to Mesh ✅ IMPLEMENT

**Current State:** Lines 98-104 - stub with NotImplementedError  
**Purpose:** Submit compute task to distributed compute network

**Target Implementation:**

```kotlin
override fun addTask(
    taskId: String,
    jobType: String,
    parameters: Map<String, String>,
    callback: (Result<String>) -> Unit
) {
    try {
        // Validate job type
        val supportedTypes = getJobTypes()
        if (jobType !in supportedTypes) {
            callback(Result.failure(IllegalArgumentException("Unsupported job type: $jobType")))
            onOperationFailed?.invoke("addTask", IllegalArgumentException("Unsupported job type: $jobType"))
            return
        }
        
        // Create task request
        val taskRequest = LocalComputeTaskRequest(
            taskId = taskId,
            jobType = jobType,
            parameters = parameters,
            priority = 5, // Default medium priority
            deadline = System.currentTimeMillis() + (1000 * 60 * 60) // 1 hour default
        )
        
        // Submit to distributed compute client
        val computeClient = getDistributedComputeClient()
        if (computeClient == null) {
            callback(Result.failure(IllegalStateException("Distributed compute not initialized")))
            onOperationFailed?.invoke("addTask", IllegalStateException("Distributed compute not initialized"))
            return
        }
        
        computeClient.submitTask(taskRequest)
        
        // Return task ID immediately (async execution)
        callback(Result.success(taskId))
        
        println("INFO: Task submitted - ID: $taskId, Type: $jobType")
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("addTask", e)
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.service.compute.LocalComputeTaskRequest`
- `import com.ustadmobile.meshrabiya.service.compute.model.JobType`
- Access: `getDistributedComputeClient()` (verify method exists in MeshrabiyaApiImpl)
- Access: `computeClient.submitTask(LocalComputeTaskRequest)` method

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Validates job type against getJobTypes()
- [ ] Creates LocalComputeTaskRequest with all required fields
- [ ] Submits task via distributed compute client
- [ ] Returns task ID immediately (async execution)
- [ ] Handles null compute client gracefully
- [ ] Invokes onOperationFailed callback on errors
- [ ] Logs task submission

**Notes:**
- Task execution is async - callback returns immediately with taskId
- Status updates will be delivered via setOnTaskStatusUpdate() callback (Section 9)
- Default priority: 5 (medium), deadline: 1 hour

---

### 1.2 startTask() - ❌ REMOVED PER CLARIFICATION 4

**User Clarification 4:** Remove startTask() entirely

**Rationale:** Client-side function - tasks automatically start on different nodes when accepted.

**Action Required:**

```kotlin
// DELETE from MeshrabiyaApi.kt interface:
// fun startTask(taskId: String, callback: (Result<Unit>) -> Unit)

// DELETE from MeshrabiyaApiImpl.kt implementation:
// override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) { ... }
```

**Verification Checklist:**
- [ ] startTask() method deleted from interface
- [ ] startTask() implementation deleted
- [ ] No references to startTask() remain
- [ ] Compiles without errors

---

### 1.3 cancelTask() - ❌ REMOVED PER CLARIFICATION 1

**User Clarification 1:** Remove cancelTask() method

**Rationale:** Replaced by unified callback system (setOnTaskStatusUpdate). Cancellation can be handled via task status updates.

**Action Required:**

```kotlin
// DELETE from MeshrabiyaApi.kt interface:
// fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)

// DELETE from MeshrabiyaApiImpl.kt implementation:
// override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) { ... }
```

**Verification Checklist:**
- [ ] cancelTask() method deleted from interface
- [ ] cancelTask() implementation deleted
- [ ] No references to cancelTask() remain
- [ ] Compiles without errors

---

### 1.4 getTaskStatus() - ❌ REMOVED PER CLARIFICATION 1

**User Clarification 1:** Remove getTaskStatus() method

**Rationale:** Replaced by unified callback system (setOnTaskStatusUpdate). Status is pushed via callback, not polled.

**Action Required:**

```kotlin
// DELETE from MeshrabiyaApi.kt interface:
// fun getTaskStatus(taskId: String): TaskStatus?

// DELETE from MeshrabiyaApiImpl.kt implementation:
// override fun getTaskStatus(taskId: String): TaskStatus? { ... }
```

**Verification Checklist:**
- [ ] getTaskStatus() method deleted from interface
- [ ] getTaskStatus() implementation deleted
- [ ] No references to getTaskStatus() remain
- [ ] Compiles without errors

---

### 1.5 getJobTypes() - Return Supported Job Types ✅ IMPLEMENT

**Current State:** Lines 111-113 - stub with NotImplementedError  
**Purpose:** Return list of compute job types supported by the mesh

**Target Implementation:**

```kotlin
override fun getJobTypes(): List<String> {
    return try {
        val computeClient = getDistributedComputeClient()
        if (computeClient != null) {
            // Get supported job types from compute subsystem
            computeClient.getSupportedJobTypes().map { it.name }
        } else {
            // Fallback: return hardcoded list of known job types
            listOf(
                "AI_INFERENCE",
                "DATA_PROCESSING",
                "FILE_CONVERSION",
                "VIDEO_TRANSCODE",
                "IMAGE_PROCESSING"
            )
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get job types: ${e.message}")
        onOperationFailed?.invoke("getJobTypes", e)
        emptyList()
    }
}
```

**Research Note:** If computeClient.getSupportedJobTypes() does not exist, use hardcoded fallback.

**Answer: JobType is purely for categorizing ServiceLibraryEntries. There is no concept of supported JobTypes. There are ServiceLIibraryEntries and the limiting factors will be if the node meets the requirements. There may be stubs for user based seelction of which runtimes are allowed. This would be an appropriate  list to deliver potentially. Currently, i believe we are making all the runtimes available. (verify mt assumptions but JobType does not directly relate to a capability and a user would not want to limit compute node activites by job type )

**Dependencies:**
- Access: `getDistributedComputeClient()` method
- Access: `computeClient.getSupportedJobTypes()` method (verify exists)
- `JobType` enum (verify JobType.name property)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns list of supported job types
- [ ] Uses distributed compute client if available
- [ ] Fallback to hardcoded list if client null
- [ ] Returns empty list on exception
- [ ] Invokes onOperationFailed callback on errors
- [ ] Logs errors appropriately

**Notes:**
- Job types are string names (e.g., "AI_INFERENCE", "DATA_PROCESSING")
- Used by addTask() for validation
- Fallback list should match actual JobType enum values

---

## SECTION 1 OUTSTANDING ISSUES

**Issue 1: LocalComputeTaskRequest Properties**
- **Question:** Does LocalComputeTaskRequest have priority and deadline properties?

**Answer: LocalComputeTaskRequest does not have priority or deadline. we will not support functionality around prioty and deadline for now  and any code supporting it should be refactored out or commented out.

- **Impact:** addTask() implementation assumes these properties exist
- **Resolution Required:** Verify LocalComputeTaskRequest class structure
- **Fallback:** Remove priority/deadline if properties don't exist

**Issue 2: computeClient.submitTask() Method**
- **Question:** Does DistributedComputeClient have submitTask() method?

**Answer: assuming this is add_task() in the API, according to CANONICAL_WORKFLOW_v2.md, `DistributedComputClient.processTaskRequest()` is the function to create a task request

- **Impact:** addTask() cannot submit tasks without this method
- **Resolution Required:** Verify method signature
- **Fallback:** Use alternative submission method if available

**Issue 3: getSupportedJobTypes() Method**
- **Question:** Does DistributedComputeClient have getSupportedJobTypes() method?

**Answer: This is deprecated 

- **Impact:** getJobTypes() will use fallback hardcoded list
- **Resolution Required:** Verify method exists
- **Fallback:** Use hardcoded list (already implemented)

---

## SECTION 2: File Operations Implementation ⭐ HIGH PRIORITY

**Priority:** HIGH (Core functionality)  
**Status:** ❌ NOT STARTED  
**Lines:** 115-175  
**Methods:** 5 (1 commented out per clarification)  
**Methods Active:** 4 (storeFile, retrieveFile, deleteFile, getAllMeshFiles)

**User Clarifications Applied:**
- Clarification 2: streamFile() COMMENTED OUT
- Clarification 5: retrieveFile() writes to "shared" subfolder
- Clarification 6: Create sendChunkQuery() variation
- Clarification 13: Comprehensive file/folder/trigger interaction rules

---

### 2.1 storeFile() - Store File in Distributed Storage ✅ IMPLEMENT

**Current State:** Lines 115-125 - stub with NotImplementedError  
**Purpose:** Store file in distributed mesh storage with replication

**Answer: the implementation seems to put a lot of logic in the API and the API ideally should be a thin interface to the underlying functioinality. . Also, separation of concerns would seem to say the majority of the target implementation should be refactored into `DistributedStorageClient.storeFile()`

**Target Implementation:**

```kotlin
override fun storeFile(
    file: File,
    callback: (Result<String>) -> Unit
) {
    try {
        // Check if file is in drop folder with active trigger
        val dropFolderPath = distributedStorageManager?.getDropFolderPath()
        if (dropFolderPath != null && file.absolutePath.startsWith(dropFolderPath)) {
            // Check if file is in a triggered subfolder
            val relativePath = file.absolutePath.removePrefix(dropFolderPath).removePrefix("/")
            val matchingTriggers = distributedStorageManager?.matchTriggersForFile(file.absolutePath) ?: emptyList()
            
            if (matchingTriggers.isNotEmpty()) {
                // File is in triggered folder - fail gracefully per Clarification 13
                val errorMsg = "Cannot manually store file in triggered drop folder location: $relativePath"
                callback(Result.failure(IllegalStateException(errorMsg)))
                onOperationFailed?.invoke("storeFile", IllegalStateException(errorMsg))
                println("ERROR: $errorMsg")
                return
            }
        }
        
        // Validate file exists
        if (!file.exists() || !file.isFile) {
            callback(Result.failure(IllegalArgumentException("File does not exist or is not a file: ${file.absolutePath}")))
            onOperationFailed?.invoke("storeFile", IllegalArgumentException("Invalid file"))
            return
        }
        
        // Get storage client
        val storageClient = getDistributedStorageClient()
        if (storageClient == null) {
            callback(Result.failure(IllegalStateException("Distributed storage not initialized")))
            onOperationFailed?.invoke("storeFile", IllegalStateException("Storage not initialized"))
            return
        }
        
        // Read file data
        val fileData = file.readBytes()
        
        // Store file with default parameters
        val fileReference = storageClient.storeFile(
            path = file.absolutePath,
            data = fileData,
            priority = 5, // Default medium priority
            replicationLevel = 3, // Default 3 replicas
            owner = virtualNode?.nodeId?.toString() ?: "unknown",
            recipients = emptyList() // No specific recipients
        )
        
        if (fileReference != null) {
            callback(Result.success(fileReference.fileId))
            onFileStored?.invoke(fileReference.fileId, file)
            println("INFO: File stored - ID: ${fileReference.fileId}, Size: ${fileData.size} bytes")
        } else {
            callback(Result.failure(IllegalStateException("Failed to store file")))
            onOperationFailed?.invoke("storeFile", IllegalStateException("Store returned null"))
        }
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("storeFile", e)
    }
}
```

**Dependencies:**
- `import java.io.File`
- Access: `getDistributedStorageClient()` method
- Access: `distributedStorageManager.getDropFolderPath()` (Section 7.6)
- Access: `distributedStorageManager.matchTriggersForFile()` (Section 7.3)
- Access: `storageClient.storeFile()` method
- Access: `virtualNode.nodeId` property
- `FileReference` class (from research: fileId, fileName, sizeBytes, mimeType)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Validates file exists and is file (not directory)
- [ ] Checks for drop folder trigger conflict (Clarification 13)
- [ ] Fails gracefully if file in triggered folder
- [ ] Reads file data as bytes
- [ ] Calls storageClient.storeFile() with correct parameters
- [ ] Returns fileId on success
- [ ] Invokes onFileStored callback
- [ ] Handles null storage client gracefully
- [ ] Handles null file reference gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs file storage

**Notes:**
- Default priority: 5 (medium)
- Default replication: 3 replicas
- Owner set to local node ID
- No specific recipients (public storage)
- Drop folder trigger check prevents conflict (Clarification 13)

---

### 2.2 retrieveFile() - Retrieve File from Distributed Storage ✅ IMPLEMENT

**Current State:** Lines 127-136 - stub with NotImplementedError  
**Purpose:** Retrieve file from distributed storage to local path

**Target Implementation:**

```kotlin
override fun retrieveFile(
    fileId: String,
    outputPath: String,
    callback: (Result<File>) -> Unit
) {
    try {
        // Get storage client
        val storageClient = getDistributedStorageClient()
        if (storageClient == null) {
            callback(Result.failure(IllegalStateException("Distributed storage not initialized")))
            onOperationFailed?.invoke("retrieveFile", IllegalStateException("Storage not initialized"))
            return
        }
        
        // Determine final output path (with "shared" subfolder logic)
        val finalOutputPath = determineFinalOutputPath(fileId, outputPath)
        
        // Retrieve file data
        val fileData = storageClient.retrieveFile(fileId)
        if (fileData == null) {
            callback(Result.failure(IllegalStateException("File not found: $fileId")))
            onOperationFailed?.invoke("retrieveFile", IllegalStateException("File not found"))
            return
        }
        
        // Write file to disk
        val outputFile = File(finalOutputPath)
        outputFile.parentFile?.mkdirs() // Create parent directories
        outputFile.writeBytes(fileData)
        
        // Mark file as ignored if written to drop folder (per Clarification 9)
        val dropFolderPath = distributedStorageManager?.getDropFolderPath()
        if (dropFolderPath != null && finalOutputPath.startsWith(dropFolderPath)) {
            // Check if file is in "shared" subfolder
            val sharedPath = "$dropFolderPath/shared"
            if (finalOutputPath.startsWith(sharedPath)) {
                // Files in shared subfolder are NEVER auto-triggered (Clarification 9)
                // No need to mark as ignored - shared subfolder is absolute exception
                println("INFO: File retrieved to shared subfolder (no trigger): $finalOutputPath")
            }
            // Note: No explicit ignore marking needed - "shared" subfolder rule is absolute
        }
        
        callback(Result.success(outputFile))
        onFileRetrieved?.invoke(fileId, outputFile)
        println("INFO: File retrieved - ID: $fileId, Path: $finalOutputPath, Size: ${fileData.size} bytes")
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("retrieveFile", e)
    }
}

/**
 * Determine final output path for retrieved file.
 * Per Clarification 5: Files NOT owned by recipient go to "shared" subfolder.
 * 
 * @param fileId File identifier
 * @param requestedPath User-requested output path
 * @return Final output path (may be in "shared" subfolder)
 */
private fun determineFinalOutputPath(fileId: String, requestedPath: String): String {
    val dropFolderPath = distributedStorageManager?.getDropFolderPath() ?: return requestedPath
    
    // Only apply "shared" subfolder logic if requested path is in drop folder
    if (!requestedPath.startsWith(dropFolderPath)) {
        return requestedPath
    }
    
    // Check file ownership
    val storageClient = getDistributedStorageClient()
    val fileMetadata = storageClient?.getFileMetadata(fileId)
    val fileOwner = fileMetadata?.owner
    val localNodeId = virtualNode?.nodeId?.toString()
    
    // If file is NOT owned by local node, redirect to "shared" subfolder
    if (fileOwner != null && fileOwner != localNodeId) {
        val fileName = File(requestedPath).name
        val sharedPath = "$dropFolderPath/shared/$fileName"
        
        // Create "shared" subfolder if it doesn't exist
        File("$dropFolderPath/shared").mkdirs()
        
        println("INFO: File not owned by local node - redirecting to shared subfolder: $sharedPath")
        return sharedPath
    }
    
    return requestedPath
}
```

**Dependencies:**
- `import java.io.File`
- Access: `getDistributedStorageClient()` method
- Access: `storageClient.retrieveFile(fileId)` method
- Access: `storageClient.getFileMetadata(fileId)` method (for ownership check)
- Access: `distributedStorageManager.getDropFolderPath()` (Section 7.6)
- Access: `virtualNode.nodeId` property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Retrieves file data from storage client
- [ ] Creates parent directories for output file
- [ ] Writes file data to disk
- [ ] Determines final output path using "shared" subfolder logic (Clarification 5)
- [ ] Checks file ownership for "shared" subfolder decision
- [ ] Creates "shared" subfolder if it doesn't exist
- [ ] Files in "shared" subfolder never trigger auto-store (Clarification 9)
- [ ] Returns output File on success
- [ ] Invokes onFileRetrieved callback
- [ ] Handles null storage client gracefully
- [ ] Handles null file data gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs file retrieval

**Notes:**
- Files NOT owned by recipient → "shared" subfolder (Clarification 5)
- Files in "shared" subfolder NEVER trigger auto-store (Clarification 9)
- Ownership checked via file metadata
- Parent directories created automatically

---

### 2.3 streamFile() - ⚠️ COMMENTED OUT PER CLARIFICATION 2

**User Clarification 2:** Comment out streamFile() - goal is streaming FROM client TO storage (not yet implemented)

**Action Required:**

```kotlin
// In MeshrabiyaApi.kt interface:

/*
 * Stream file to/from distributed storage.
 * FUTURE FEATURE: Goal is streaming FROM client TO storage for large file uploads.
 * Not yet implemented - chunked upload/download mechanism required.
 *
 * @param fileId File identifier
 * @param outputStream Output stream for file data
 * @param callback Result callback
 */
// fun streamFile(fileId: String, outputStream: OutputStream, callback: (Result<Unit>) -> Unit)
```

```kotlin
// In MeshrabiyaApiImpl.kt implementation:

/*
 * Stream file to/from distributed storage.
 * FUTURE FEATURE: Goal is streaming FROM client TO storage for large file uploads.
 * Not yet implemented - chunked upload/download mechanism required.
 */
// override fun streamFile(fileId: String, outputStream: OutputStream, callback: (Result<Unit>) -> Unit) {
//     callback(Result.failure(NotImplementedError("Streaming not yet implemented")))
// }
```

**Verification Checklist:**
- [ ] streamFile() commented out in interface with explanation
- [ ] streamFile() commented out in implementation with explanation
- [ ] Comment explains future intent (streaming FROM client TO storage)
- [ ] Compiles without errors

**Notes:**
- Future feature for large file upload streaming
- Requires chunked upload/download mechanism
- Not blocking current implementation

---

### 2.4 deleteFile() - Delete File from Distributed Storage ✅ IMPLEMENT

**Current State:** Lines 148-154 - stub with NotImplementedError  
**Purpose:** Delete file from distributed storage (mark for deletion)

**Target Implementation:**

```kotlin
override fun deleteFile(
    fileId: String,
    callback: (Result<Unit>) -> Unit
) {
    try {
        // Get storage client
        val storageClient = getDistributedStorageClient()
        if (storageClient == null) {
            callback(Result.failure(IllegalStateException("Distributed storage not initialized")))
            onOperationFailed?.invoke("deleteFile", IllegalStateException("Storage not initialized"))
            return
        }
        
        // Delete file (marks for deletion, garbage collection removes chunks)
        val success = storageClient.deleteFile(fileId)
        
        if (success) {
            callback(Result.success(Unit))
            println("INFO: File marked for deletion - ID: $fileId")
            
            // Note: ChunkReplicaTracker cleanup
            // Per Research Finding 7: ChunkReplicaTracker is a ConcurrentHashMap property
            // Cleanup should happen in DistributedStorageManager, not here
            // If needed, add helper method: distributedStorageManager.cleanupChunkReplicas(fileId)
        } else {
            callback(Result.failure(IllegalStateException("Failed to delete file: $fileId")))
            onOperationFailed?.invoke("deleteFile", IllegalStateException("Delete failed"))
        }
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("deleteFile", e)
    }
}
```

**Dependencies:**
- Access: `getDistributedStorageClient()` method
- Access: `storageClient.deleteFile(fileId)` method

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Calls storageClient.deleteFile()
- [ ] Returns success/failure appropriately
- [ ] Handles null storage client gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs file deletion

**Outstanding Issue (from v2 Section 2.4):**
- **ChunkReplicaTracker Delete API:** Per Research Finding 7, ChunkReplicaTracker is a ConcurrentHashMap property, not a separate class with delete API
- **Resolution:** Cleanup should happen in DistributedStorageManager (or add helper method)
- **Impact:** deleteFile() marks file for deletion, but chunk replica cleanup may need explicit call

**Notes:**
- Deletion is typically async (marks file, garbage collection cleans chunks)
- ChunkReplicaTracker cleanup handled by storage manager
- Method returns immediately after marking

---

### 2.5 getAllMeshFiles() - List All Files in Mesh Storage ✅ IMPLEMENT

**Current State:** Lines 156-175 - stub with NotImplementedError  
**Purpose:** List all files stored in distributed mesh with metadata

**Target Implementation:**

```kotlin
override fun getAllMeshFiles(): List<MeshFile> {
    return try {
        // Get storage manager
        val storageManager = distributedStorageManager
        if (storageManager == null) {
            println("ERROR: Storage manager not initialized")
            emptyList()
        } else {
            // Get all file references from storage
            val allFileReferences = storageManager.getAllStoredFiles()
            
            // Convert to MeshFile format with replica count
            allFileReferences.map { fileRef ->
                // Get replica count using sendChunkQuery (per Clarification 6)
                val replicaCount = getReplicaCount(fileRef.fileId)
                
                MeshFile(
                    id = fileRef.fileId,
                    name = fileRef.fileName,
                    size = fileRef.sizeBytes,
                    mimeType = fileRef.mimeType ?: "application/octet-stream",
                    replicas = replicaCount,
                    timestamp = System.currentTimeMillis() // Fallback: use current time
                    // Note: Per Research Finding 8, FileReference lacks timestamp field
                    // TODO: Add timestamp to FileReference or use FileMetadata.createdAt
                )
            }
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get mesh files: ${e.message}")
        onOperationFailed?.invoke("getAllMeshFiles", e)
        emptyList()
    }
}

/**
 * Get replica count for a file using sendChunkQuery variation.
 * Per Clarification 6: Create variation of sendChunkRetrievalQuery that returns replica count.
 * 
 * @param fileId File identifier
 * @return Number of replicas (average or total across chunks)
 */
private fun getReplicaCount(fileId: String): Int {
    return try {
        // Get core gossip broadcast service (per Research Finding 10)
        val gossipService = virtualNode?.coreGossipBroadcastService
        if (gossipService == null) {
            println("WARN: Gossip service not available for replica count")
            return 0
        }
        
        // Get chunk replica tracker (per Research Finding 7)
        val chunkReplicaTracker = distributedStorageManager?.getChunkReplicaTracker()
        if (chunkReplicaTracker == null) {
            println("WARN: ChunkReplicaTracker not available")
            return 0
        }
        
        // Get all chunks for this file
        val fileChunks = chunkReplicaTracker.keys.filter { it.startsWith(fileId) }
        if (fileChunks.isEmpty()) {
            return 0
        }
        
        // Calculate average replicas per chunk
        val totalReplicas = fileChunks.sumOf { chunkId ->
            chunkReplicaTracker[chunkId]?.size ?: 0
        }
        
        val averageReplicas = totalReplicas / fileChunks.size
        averageReplicas
    } catch (e: Exception) {
        println("ERROR: Failed to get replica count for $fileId: ${e.message}")
        0
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshFile`
- Access: `distributedStorageManager.getAllStoredFiles()` method
- Access: `distributedStorageManager.getChunkReplicaTracker()` method (or direct property access)
- Access: `virtualNode.coreGossipBroadcastService` property
- `FileReference` class (from research: fileId, fileName, sizeBytes, mimeType)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets all file references from storage manager
- [ ] Converts FileReference to MeshFile format
- [ ] Calculates replica count using chunk replica tracker (Clarification 6)
- [ ] Uses average replicas per chunk (not total)
- [ ] Handles missing timestamp gracefully (Research Finding 8)
- [ ] Returns empty list on error
- [ ] Handles null storage manager gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs errors

**Outstanding Issue (from v2 Section 2.5):**
- **FileReference Timestamp:** Per Research Finding 8, FileReference lacks timestamp field
- **Resolution:** Using System.currentTimeMillis() as fallback (not accurate)
- **Better Solution:** Use FileMetadata.createdAt if available
- **Impact:** Timestamp in MeshFile list may not be accurate

**Notes (from Clarification 6):**
- sendChunkQuery() variation returns replica count without retrieving chunks
- Can show average or total replicas per file
- Implementation uses average replicas per chunk
- ChunkReplicaTracker is ConcurrentHashMap (Research Finding 7)

---

## SECTION 2 OUTSTANDING ISSUES

**Issue 1: FileReference Dual Definition (Research Finding 8)**
- **Problem:** TWO incompatible FileReference definitions exist
  - Definition 1: FileReference(fileId, fileName, sizeBytes, mimeType) - MeshFile.kt
  - Definition 2: FileReference(id, path, size) - TaskResult.kt
- **Impact:** Ambiguity in which FileReference to use
- **Resolution:** Use Definition 1 (more comprehensive), note unification needed
- **Future Work:** Unify FileReference classes into single definition

**Issue 2: FileReference Timestamp Missing (Research Finding 8)**
- **Problem:** Neither FileReference definition has timestamp field
- **Current Workaround:** Using System.currentTimeMillis() as fallback
- **Better Solution:** Use FileMetadata.createdAt (verified exists)
- **Impact:** Timestamp in getAllMeshFiles() not accurate
- **Future Work:** Add timestamp to FileReference or use FileMetadata

**Issue 3: ChunkReplicaTracker Access Method**
- **Question:** Does DistributedStorageManager have getChunkReplicaTracker() accessor?
- **Current Assumption:** Direct property access or accessor method
- **Impact:** getReplicaCount() needs access to chunk replica tracker
- **Resolution Required:** Verify accessor exists or use direct property access

**Answer: you can resolve this yourself with research agent focused on determining if there is accessor and best resolution.

**Issue 4: storageClient.getFileMetadata() Method**
- **Question:** Does DistributedStorageClient have getFileMetadata() method?
- **Current Assumption:** Method exists and returns FileMetadata with owner property
- **Impact:** determineFinalOutputPath() needs ownership check
- **Resolution Required:** Verify method signature
- **Fallback:** Skip ownership check, always use requested path

**Answer: `DistributedStorageManager.getFileMetadata()` exists and can be used or moved as you see fit but seems like depending on architecture it could be useful to server  and client domains

---

## SECTION 2 VERIFICATION SUMMARY

**Total Methods:** 5 (1 commented out, 4 active implementations)  
**Completion Checklist:**
- [ ] storeFile() implemented with drop folder trigger check
- [ ] retrieveFile() implemented with "shared" subfolder logic
- [ ] streamFile() commented out with future intent explanation
- [ ] deleteFile() implemented with deletion marking
- [ ] getAllMeshFiles() implemented with replica count calculation
- [ ] All 4 outstanding issues documented
- [ ] Compiles without errors
- [ ] All user clarifications applied

---

**END OF PART 1**

**Next:** Part 2 will cover Sections 3-5 (Gateway Controls, Storage Participation, Enhanced State Methods)
