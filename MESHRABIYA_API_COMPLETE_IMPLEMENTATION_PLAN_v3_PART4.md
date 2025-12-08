# MeshrabiyaApiImpl Complete Implementation Plan v3.0 - PART 4 of 4

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

## PART 4 CONTENTS

This part covers:
- **Section 8:** OrbotMeshService Refactoring (5 subsections)
- **Section 9:** Task Status Callback System - NEW (6 subsections)
- **Section 10:** Drop Folder Integration Summary - NEW
- **Import Requirements:** All files with import statements
- **Implementation Tracking:** Complete checklist

**Sections in other parts:**
- Part 1: Executive Summary, User Clarifications, Research Findings, Sections 1-2
- Part 2: Sections 3-5 (Gateway Controls, Storage Participation, Enhanced State)
- Part 3: Sections 6-7 (Event Handlers, Drop Folder)

**User Clarifications Applied in This Part:**
- Clarification 1: Task status callback system (replaces getTaskStatus)
- Clarification 14: Binder implementation for OrbotMeshService

**Research Findings Applied in This Part:**
- Finding 1: TaskStatus actual values (8 values, not 4)
- Finding 2: TaskRequest class doesn't exist publicly
- Finding 3: MeshEcosystemListener routing pattern
- Finding 4: TaskResult data class structure

---

## SECTION 8: OrbotMeshService Refactoring ⭐ HIGH PRIORITY

**Priority:** HIGH (Service infrastructure)  
**Status:** ❌ NOT STARTED  
**Subsections:** 5  
**Confidence:** 95% ✅ (Clarification 14 provides Binder requirement)

**Overview:** OrbotMeshService currently has deprecated imports, redundant wrappers, and no Binder implementation. This section refactors the service for correctness and completeness.

---

### 8.1 Fix Deprecated onStart Import ✅ IMPLEMENT

**Location:** `OrbotMeshService.kt`

**Current Issue:** Deprecated import causing warnings

**Target Implementation:**

```kotlin
// Replace deprecated import:
// OLD: import androidx.lifecycle.LifecycleService.onStart
// NEW:
import android.content.Intent
import android.os.IBinder

// Remove onStart() override if using LifecycleService
// LifecycleService handles lifecycle automatically
```

**Verification Checklist:**
- [ ] Removed deprecated import
- [ ] Compiles without warnings
- [ ] Service lifecycle works correctly

---

### 8.2 Remove Redundant API Wrappers ✅ IMPLEMENT

**Location:** `OrbotMeshService.kt`

**Current State:** Service has methods that simply call MeshrabiyaApiImpl methods

**Target Implementation:**

```kotlin
// Remove these redundant wrapper methods from OrbotMeshService:
// - All methods that just call meshApi.methodName()
// - Keep only service-specific lifecycle methods (onCreate, onBind, onDestroy)

// Example removal:
// OLD:
// fun addTask(...) = meshApi.addTask(...)
// fun storeFile(...) = meshApi.storeFile(...)
// etc.

// NEW: Clients access MeshrabiyaApi instance directly via Binder (see 8.5)
```

**Verification Checklist:**
- [ ] Removed all redundant API wrappers
- [ ] Service only contains lifecycle methods
- [ ] Compiles without errors

---

### 8.3 Add Tor Proxy Integration (If Needed) ✅ IMPLEMENT

**Location:** `OrbotMeshService.kt`

**Current State:** May need Tor proxy configuration for gateway features

**Target Implementation:**

```kotlin
// Add to OrbotMeshService onCreate():

override fun onCreate() {
    super.onCreate()
    
    // Initialize Tor proxy settings if acting as gateway
    // This is placeholder - actual Tor integration depends on Orbot architecture
    // May require:
    // - Connecting to local Tor daemon
    // - Configuring SOCKS proxy settings
    // - Routing mesh traffic through Tor when TOR_GATEWAY role active
    
    // TODO: Integrate with existing Orbot Tor management
    println("INFO: OrbotMeshService created")
}
```

**Verification Checklist:**
- [ ] onCreate() logs service creation
- [ ] Tor integration point documented (if needed later)
- [ ] Compiles without errors

**Outstanding Question:**
- ⚠️ **UNCERTAINTY Q8.1:** What is the correct Tor proxy integration pattern for Orbot?
- **Priority:** LOW (may not be needed immediately)
- **Fallback:** Document integration point for future work

*Answer: I have created orbot-tor-connection-analysis.md. use research agent to analyze  it and codebase for an thorough analysis of Orbot TOR Implementation in the official app and teh Orbot App portion of this app

---

### 8.4 Wire Event Handlers to MeshrabiyaApi ✅ IMPLEMENT

**Location:** `OrbotMeshService.kt`

**Current State:** Service may need to register callbacks with MeshrabiyaApi

**Target Implementation:**

```kotlin
// Add to OrbotMeshService onCreate(), after MeshrabiyaApi initialization:

// Wire event handlers (from Section 6)
meshApi?.apply {
    onMeshStateChanged = { state ->
        // Broadcast mesh state change to clients
        val intent = Intent("org.torproject.orbot.MESH_STATE_CHANGED")
        intent.putExtra("state", state.name)
        sendBroadcast(intent)
    }
    
    onPeerCountChanged = { count ->
        // Broadcast peer count change to clients
        val intent = Intent("org.torproject.orbot.PEER_COUNT_CHANGED")
        intent.putExtra("count", count)
        sendBroadcast(intent)
    }
    
    onGossipMessage = { senderId, messageBytes ->
        // Log gossip message (or forward to interested clients)
        println("INFO: Gossip message from $senderId (${messageBytes.size} bytes)")
    }
    
    onOperationFailed = { operation, exception ->
        // Log operation failures
        println("ERROR: Operation $operation failed: ${exception.message}")
    }
}
```

**Dependencies:**
- `import android.content.Intent`

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Event handlers registered on MeshrabiyaApi
- [ ] Broadcasts sent for mesh state and peer count changes
- [ ] Gossip messages logged
- [ ] Operation failures logged

---

### 8.5 Implement Binder for Service Access (CRITICAL) ✅ IMPLEMENT

**Location:** `OrbotMeshService.kt`

**Per Clarification 14:** Must implement Binder (not return null)

**Target Implementation:**

```kotlin
// Add to OrbotMeshService class:

/**
 * Binder for client access to MeshrabiyaApi.
 * Per Clarification 14: Must return actual Binder (not null).
 */
inner class MeshBinder : Binder() {
    /**
     * Get MeshrabiyaApi instance for direct method calls.
     * 
     * @return MeshrabiyaApi instance or null if not initialized
     */
    fun getApi(): MeshrabiyaApi? = meshApi
}

private val binder = MeshBinder()

override fun onBind(intent: Intent?): IBinder {
    super.onBind(intent)
    // Per Clarification 14: Return actual Binder (not null)
    return binder
}
```

**Dependencies:**
- `import android.os.Binder`
- `import android.os.IBinder`
- `import android.content.Intent`

**User Clarifications Applied:**
- Clarification 14: Implement Binder (not null return)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] MeshBinder inner class defined
- [ ] onBind() returns MeshBinder instance (not null)
- [ ] getApi() returns MeshrabiyaApi instance
- [ ] Clients can access MeshrabiyaApi via service binding

**Usage Pattern for Clients:**

```kotlin
// Example client code:
val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? OrbotMeshService.MeshBinder
        val meshApi = binder?.getApi()
        
        // Use meshApi methods directly
        meshApi?.addTask(jobType, inputPath, outputPath) { result ->
            // Handle result
        }
    }
    
    override fun onServiceDisconnected(name: ComponentName?) {
        // Handle disconnect
    }
}

bindService(Intent(this, OrbotMeshService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
```

---

## SECTION 8 OUTSTANDING QUESTIONS

### Q8.1: Tor Proxy Integration Pattern
- **Question:** What is the correct Tor proxy integration pattern for Orbot?
- **Impact:** Gateway features may need Tor proxy routing
- **Source:** Section 8.3
- **Status:** UNRESOLVED
- **Priority:** LOW (may not be needed immediately)
- **Fallback:** Document integration point for future work

*Answer: I have created orbot-tor-connection-analysis.md. use research agent to analyze  it and codebase for an thorough analysis of Orbot TOR Implementation in the official app and teh Orbot App portion of this app

---

## SECTION 9: Task Status Callback System - NEW ⭐ HIGHEST PRIORITY

**Priority:** HIGHEST (User Clarification 1 - major architectural change)  
**Status:** ❌ NOT STARTED  
**Subsections:** 6  
**Confidence:** 90% ✅ (Research Findings 1-4 provide architecture)

**Per Clarification 1:** Replace getTaskStatus/cancelTask with callback system for task status updates

**Research Findings Applied:**
- Finding 1: TaskStatus has 8 values (PENDING, ASSIGNED, KEYPAIR_GENERATED, SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED)
- Finding 2: NO public TaskRequest class (internal enum only)
- Finding 3: MeshEcosystemListener.routeMessage() uses type discrimination
- Finding 4: TaskResult is structured data class (not JsonObject)

---

### 9.1 Create TaskStatusUpdateMessage Data Class ✅ IMPLEMENT

**Location:** New file `app/src/main/java/org/torproject/android/service/meshrabiya/messages/TaskStatusUpdateMessage.kt`

**Per Research Finding 1:** Use actual TaskStatus values (8 total)

**Target Implementation:**

```kotlin
package org.torproject.android.service.meshrabiya.messages

import org.torproject.android.service.meshrabiya.compute.TaskStatus
import org.torproject.android.service.meshrabiya.compute.TaskResult

/**
 * Message for task status updates sent via mesh gossip.
 * Per Clarification 1: Replaces getTaskStatus() with callback system.
 * Per Research Finding 1: Uses actual TaskStatus values (8 total).
 */
data class TaskStatusUpdateMessage(
    /**
     * Task ID (from addTask result).
     */
    val taskId: String,
    
    /**
     * Current task status.
     * Per Finding 1: PENDING, ASSIGNED, KEYPAIR_GENERATED, SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED
     */
    val status: TaskStatus,
    
    /**
     * Progress percentage (0-100) for RUNNING status.
     */
    val progress: Int? = null,
    
    /**
     * Task result (only present when status=COMPLETED).
     * Per Research Finding 4: TaskResult data class (not JsonObject).
     */
    val result: TaskResult? = null,
    
    /**
     * Error message (only present when status=FAILED).
     */
    val errorMessage: String? = null,
    
    /**
     * Worker node ID (node executing the task).
     */
    val workerId: String? = null,
    
    /**
     * Timestamp of status update.
     */
    val timestamp: Long = System.currentTimeMillis()
)
```

**Dependencies:**
- `import org.torproject.android.service.meshrabiya.compute.TaskStatus`
- `import org.torproject.android.service.meshrabiya.compute.TaskResult`

**Research Findings Applied:**
- Finding 1: TaskStatus 8 values (not 4 assumed in v2)
- Finding 4: TaskResult data class (structured, not JsonObject)

**Verification Checklist:**
- [ ] File created in correct package
- [ ] Compiles without errors
- [ ] Uses actual TaskStatus enum (8 values)
- [ ] Uses TaskResult data class for result
- [ ] Includes all necessary fields (taskId, status, progress, result, error, workerId, timestamp)
- [ ] Data class properly defined (immutable)

---

### 9.2 Add onTaskStatusUpdate Callback to MeshrabiyaApi ✅ IMPLEMENT

**Location:** `MeshrabiyaApi.kt` (interface) and `MeshrabiyaApiImpl.kt` (implementation)

**Target Implementation for Interface:**

```kotlin
// Add to MeshrabiyaApi.kt:

/**
 * Callback for task status updates.
 * Invoked when TaskStatusUpdateMessage received via mesh gossip.
 */
var onTaskStatusUpdate: ((taskId: String, status: TaskStatus, progress: Int?, result: TaskResult?, error: String?) -> Unit)?
```

**Target Implementation for MeshrabiyaApiImpl:**

```kotlin
// Add to MeshrabiyaApiImpl.kt properties:

override var onTaskStatusUpdate: ((taskId: String, status: TaskStatus, progress: Int?, result: TaskResult?, error: String?) -> Unit)? = null
```

**Dependencies:**
- `import org.torproject.android.service.meshrabiya.compute.TaskStatus`
- `import org.torproject.android.service.meshrabiya.compute.TaskResult`

**Verification Checklist:**
- [ ] Compiles without errors (interface + implementation)
- [ ] Callback signature matches TaskStatusUpdateMessage fields
- [ ] Property defined as nullable (clients can opt-in to status updates)

---

### 9.3 Register TaskStatusUpdateMessage Handler in MeshEcosystemListener ✅ IMPLEMENT

**Location:** `MeshEcosystemListener.kt` (or wherever message routing happens)

**Per Research Finding 3:** Use MeshEcosystemListener.routeMessage() with type discrimination

**Target Implementation:**

```kotlin
// Add to MeshEcosystemListener.routeMessage() method:

// In routeMessage(message: Any) when() block:
when (message) {
    // ... existing cases ...
    
    is TaskStatusUpdateMessage -> {
        println("INFO: Task status update: task=${message.taskId}, status=${message.status}")
        
        // Invoke callback on MeshrabiyaApiImpl
        meshrabiyaApi?.onTaskStatusUpdate?.invoke(
            message.taskId,
            message.status,
            message.progress,
            message.result,
            message.errorMessage
        )
    }
    
    // ... other cases ...
}
```

**Dependencies:**
- `import org.torproject.android.service.meshrabiya.messages.TaskStatusUpdateMessage`
- Access: `meshrabiyaApi` instance in MeshEcosystemListener

**Research Findings Applied:**
- Finding 3: MeshEcosystemListener routing uses type discrimination

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] TaskStatusUpdateMessage case added to routeMessage()
- [ ] Invokes onTaskStatusUpdate callback with all fields
- [ ] Logs status update
- [ ] Handles null callback gracefully

**Outstanding Question:**
- ⚠️ **UNCERTAINTY Q9.1:** How to access meshrabiyaApi instance in MeshEcosystemListener?
- **Priority:** MEDIUM
- **Fallback:** Pass meshrabiyaApi as constructor parameter to MeshEcosystemListener

**Answer: use Singleon instance (should not require parameters)
---

### 9.4 Update DistributedComputeClient to Send Status Updates ✅ IMPLEMENT

**Location:** `DistributedComputeClient.kt`

**Per Research Finding 2:** NO public TaskRequest class - use internal state tracking

**Target Implementation:**

```kotlin
// Add to DistributedComputeClient class:

/**
 * Send task status update via mesh gossip.
 * Per Clarification 1: Replaces getTaskStatus() polling with push notifications.
 */
private suspend fun sendTaskStatusUpdate(
    taskId: String,
    status: TaskStatus,
    progress: Int? = null,
    result: TaskResult? = null,
    errorMessage: String? = null,
    recipientId: String
) {
    val message = TaskStatusUpdateMessage(
        taskId = taskId,
        status = status,
        progress = progress,
        result = result,
        errorMessage = errorMessage,
        workerId = virtualNode.nodeId.toString()
    )
    
    // Send via gossip to task requester
    try {
        virtualNode.getMeshGossipService()?.sendGossipMessage(recipientId, message)
        println("INFO: Sent task status update: task=$taskId, status=$status, recipient=$recipientId")
    } catch (e: Exception) {
        println("ERROR: Failed to send task status update: ${e.message}")
    }
}

// Call sendTaskStatusUpdate() at key points in task lifecycle:

// When task accepted:
suspend fun acceptTask(taskId: String, requesterId: String) {
    // ... existing logic ...
    sendTaskStatusUpdate(taskId, TaskStatus.ASSIGNED, recipientId = requesterId)
}

// When keypair generated (if applicable):
suspend fun generateKeypair(taskId: String, requesterId: String) {
    // ... existing logic ...
    sendTaskStatusUpdate(taskId, TaskStatus.KEYPAIR_GENERATED, recipientId = requesterId)
}

// When task scheduled:
suspend fun scheduleTask(taskId: String, requesterId: String) {
    // ... existing logic ...
    sendTaskStatusUpdate(taskId, TaskStatus.SCHEDULED, recipientId = requesterId)
}

// When task starts:
suspend fun startTask(taskId: String, requesterId: String) {
    // ... existing logic ...
    sendTaskStatusUpdate(taskId, TaskStatus.RUNNING, progress = 0, recipientId = requesterId)
}

// During task execution (progress updates):
suspend fun updateTaskProgress(taskId: String, progress: Int, requesterId: String) {
    sendTaskStatusUpdate(taskId, TaskStatus.RUNNING, progress = progress, recipientId = requesterId)
}

// When task completes:
suspend fun completeTask(taskId: String, result: TaskResult, requesterId: String) {
    sendTaskStatusUpdate(taskId, TaskStatus.COMPLETED, result = result, recipientId = requesterId)
}

// When task fails:
suspend fun failTask(taskId: String, error: String, requesterId: String) {
    sendTaskStatusUpdate(taskId, TaskStatus.FAILED, errorMessage = error, recipientId = requesterId)
}

// When task cancelled:
suspend fun cancelTask(taskId: String, requesterId: String) {
    sendTaskStatusUpdate(taskId, TaskStatus.CANCELLED, recipientId = requesterId)
}
```

**Dependencies:**
- `import org.torproject.android.service.meshrabiya.messages.TaskStatusUpdateMessage`
- `import org.torproject.android.service.meshrabiya.compute.TaskStatus`
- `import org.torproject.android.service.meshrabiya.compute.TaskResult`
- Access: `virtualNode.getMeshGossipService()`
- Access: `virtualNode.nodeId`

**Research Findings Applied:**
- Finding 1: TaskStatus 8 values (all used in different lifecycle stages)
- Finding 2: No TaskRequest.status (use internal tracking)
- Finding 4: TaskResult data class

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] sendTaskStatusUpdate() method added
- [ ] All 8 TaskStatus values used appropriately
- [ ] Status updates sent at all lifecycle stages
- [ ] Progress updates sent during RUNNING state
- [ ] Result included in COMPLETED status
- [ ] Error message included in FAILED status
- [ ] Gossip service handles message sending
- [ ] Exceptions handled gracefully

**Outstanding Question:**
- ⚠️ **UNCERTAINTY Q9.2:** How to track task requester ID for status updates?

**Answer: task request status UI updates are recieved by the requester/owner. so you shouldnt need the requester id. there is a single human requester for tasks on a given client node. when we get to the future implementation of workflow tasks, we may have to deal with a task on a compute server node requesting a task, but we will determine how to hanlde that later. If you are talking about the client side sending the task update to the client side, the requesterId is the nodeaddress of the client node and should be set as a parameter during the client side portion of assign task.

- **Priority:** HIGH
- **Current Approach:** Pass requesterId as parameter to task methods
- **Full Solution:** May require task metadata storage

---

### 9.5 Update addTask() to Return Task ID Immediately ✅ IMPLEMENT

**Location:** `MeshrabiyaApiImpl.kt` Section 1.1

**Current State:** addTask() implementation from Part 1

**Per Clarification 1:** addTask returns task ID immediately (doesn't wait for completion)

**Target Implementation:** Update Section 1.1 implementation:

```kotlin
// Update addTask() implementation (Section 1.1):

override fun addTask(jobType: String, inputPath: String, outputPath: String, callback: (Result<String>) -> Unit) {
    try {
        // Validate job type
        val supportedTypes = distributedComputeClient?.getSupportedJobTypes()
            ?: listOf("text-processing", "image-processing", "data-analysis", "encryption", "hashing")
        
        if (jobType !in supportedTypes) {
            callback(Result.failure(IllegalArgumentException("Unsupported job type: $jobType. Supported: $supportedTypes")))
            return
        }
        
        // Create task request (per Finding 2: use LocalComputeTaskRequest if it exists)
        val taskRequest = LocalComputeTaskRequest(
            jobType = jobType,
            inputFilePath = inputPath,
            outputFilePath = outputPath,
            priority = 5, // Q1.1: Property existence unverified
            deadline = null // Q1.1: Property existence unverified
        )
        
        // Submit to compute client (returns task ID immediately)
        // Q1.2: submitTask() method signature unverified
        val taskId = distributedComputeClient?.submitTask(taskRequest)
        
        if (taskId != null) {
            // Per Clarification 1: Return task ID immediately (don't wait for completion)
            callback(Result.success(taskId))
            println("INFO: Task submitted: $taskId (type=$jobType)")
        } else {
            callback(Result.failure(IllegalStateException("Failed to submit task")))
        }
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("addTask", e)
    }
}
```

**User Clarifications Applied:**
- Clarification 1: Return task ID immediately (status updates via callback)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns task ID immediately via callback
- [ ] Does NOT wait for task completion
- [ ] Status updates received via onTaskStatusUpdate callback (Section 9.2)

---

### 9.6 Document Task Status Callback Usage Pattern ✅ IMPLEMENT

**Location:** Add to MeshrabiyaApi.kt interface documentation

**Target Implementation:**

```kotlin
// Add comprehensive documentation to MeshrabiyaApi.kt:

/**
 * MeshrabiyaApi - High-level interface for Orbot Mesh features.
 * 
 * TASK STATUS CALLBACK PATTERN:
 * ==============================
 * Per Clarification 1: Tasks use callback-based status updates (not polling).
 * 
 * Usage:
 * 1. Register onTaskStatusUpdate callback:
 *    ```
 *    meshApi.onTaskStatusUpdate = { taskId, status, progress, result, error ->
 *        when (status) {
 *            TaskStatus.PENDING -> println("Task queued")
 *            TaskStatus.ASSIGNED -> println("Task assigned to worker")
 *            TaskStatus.KEYPAIR_GENERATED -> println("Keypair generated (if applicable)")
 *            TaskStatus.SCHEDULED -> println("Task scheduled for execution")
 *            TaskStatus.RUNNING -> println("Task running: $progress%")
 *            TaskStatus.COMPLETED -> println("Task completed: $result")
 *            TaskStatus.FAILED -> println("Task failed: $error")
 *            TaskStatus.CANCELLED -> println("Task cancelled")
 *        }
 *    }
 *    ```
 * 
 * 2. Submit task (returns task ID immediately):
 *    ```
 *    meshApi.addTask("text-processing", "/input.txt", "/output.txt") { result ->
 *        val taskId = result.getOrNull()
 *        println("Task ID: $taskId")
 *        // Status updates received via onTaskStatusUpdate callback
 *    }
 *    ```
 * 
 * 3. Monitor status updates via callback (not polling getTaskStatus - removed per Clarification 1)
 * 
 * 4. No explicit cancelTask() - tasks auto-cancel on timeout or worker disconnect
 * 
 * TaskStatus Values (Per Research Finding 1):
 * - PENDING: Task queued, awaiting assignment
 * - ASSIGNED: Task assigned to worker node
 * - KEYPAIR_GENERATED: Keypair generated for task (if applicable)
 * - SCHEDULED: Task scheduled for execution
 * - RUNNING: Task executing (progress updates provided)
 * - COMPLETED: Task finished successfully (result provided)
 * - FAILED: Task failed (error message provided)
 * - CANCELLED: Task cancelled by system/timeout
 */
interface MeshrabiyaApi {
    // ... existing interface ...
}
```

**Verification Checklist:**
- [ ] Documentation added to interface
- [ ] All 8 TaskStatus values documented
- [ ] Usage pattern clear and complete
- [ ] Callback registration example provided
- [ ] Task submission example provided
- [ ] Clarification 1 referenced

---

## SECTION 9 OUTSTANDING QUESTIONS

### Q9.1: MeshrabiyaApi Access in MeshEcosystemListener
- **Question:** How to access meshrabiyaApi instance in MeshEcosystemListener for callback invocation?

**Answer: use singleton getInstance() ensure no parameters are required.

- **Impact:** Cannot invoke onTaskStatusUpdate callback without meshrabiyaApi reference
- **Source:** Section 9.3
- **Status:** UNRESOLVED
- **Priority:** MEDIUM
- **Fallback:** Pass meshrabiyaApi as constructor parameter to MeshEcosystemListener

### Q9.2: Task Requester ID Tracking
- **Question:** How to track task requester ID for status update routing?

**Answer: task tracking on a the client side is by the taskId of the request. that is how the UI will align the updates with the active tasks UI (FUTURE work). If you are talking about the client side sending the task update to the client side, the requesterId is the nodeaddress of the client node and should be set as a parameter during the client side portion of assign task.
- **Impact:** Status updates cannot be sent to correct node without requester ID
- **Source:** Section 9.4
- **Status:** UNRESOLVED
- **Priority:** HIGH
- **Current Approach:** Pass requesterId as parameter to task lifecycle methods
- **Full Solution:** May require task metadata storage (taskId → requesterId mapping)

---

## SECTION 10: DROP FOLDER INTEGRATION SUMMARY - NEW

**Priority:** MEDIUM (Documentation)  
**Purpose:** Summarize drop folder implementation for reference

**Drop Folder Features (Per Clarification 3):**
1. ✅ FileObserver-based auto-sync (max 2 levels deep - Clarification 11)
2. ✅ StoreFileTrigger configuration (per subpath)
3. ✅ Left-bound trigger matching (Clarification 8)
4. ✅ "shared" subfolder absolute exception (Clarification 9)
5. ✅ Trigger CRUD via MeshrabiyaApi
6. ✅ Auto-store on file creation (matched recipients)
7. ✅ Non-persistent triggers (counter resets - Clarification 12)
8. ✅ Comprehensive file/folder/trigger rules (Clarification 13)

**Drop Folder API Methods (Part 3 Section 7):**
- `selectDropFolder(path, callback)` - Configure drop folder
- `getDropFolder()` - Get current drop folder
- `getDropFolderFiles()` - List files in drop folder
- `createDropFolderTrigger(subPath, recipients, callback)` - Create trigger
- `updateDropFolderTrigger(triggerId, subPath, recipients, callback)` - Update trigger
- `deleteDropFolderTrigger(triggerId, callback)` - Delete trigger
- `getDropFolderTriggers()` - List all triggers

**Drop Folder Triggers (Data Structure):**
```kotlin
data class StoreFileTrigger(
    val id: Int,           // Unique ID (starts at 0, not persisted)
    val subPath: String,   // Relative path within drop folder
    val recipients: List<String> // Recipient node IDs for auto-store
)
```

**Drop Folder Workflow:**
1. User calls `selectDropFolder("/path/to/folder")` → configures drop folder
2. User creates triggers via `createDropFolderTrigger("sub1", ["nodeA", "nodeB"])`
3. User places file in `/path/to/folder/sub1/file.txt`
4. FileObserver detects new file
5. matchTriggersForFile() finds trigger for "sub1"
6. storeFile() called with recipients ["nodeA", "nodeB"]
7. File auto-synced to mesh with matched recipients
8. `onFileAddedToDropFolder` callback invoked (if exists - Q7.1)

**Drop Folder Constraints:**
- Max 2 levels deep monitoring (Clarification 11)
- "shared" subfolder NEVER triggers (Clarification 9)
- Triggers don't persist across app restarts (Clarification 12)
- Files in triggered folders cannot be manually stored (Clarification 13 Rule 1)

**Integration Points:**
- Section 2.1: storeFile() checks for active triggers
- Section 2.2: retrieveFile() writes to "shared" subfolder for non-owned files
- Section 7.5: FileObserver monitors drop folder and triggers auto-store
- Section 7.10: Comprehensive file/folder/trigger interaction rules

---

## IMPORT REQUIREMENTS

All implementation files require the following imports (organized by file):

### MeshrabiyaApiImpl.kt

```kotlin
// Core Android
import android.content.Context
import java.io.File

// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Mesh components
import org.torproject.android.service.meshrabiya.VirtualNode
import org.torproject.android.service.meshrabiya.compute.DistributedComputeClient
import org.torproject.android.service.meshrabiya.compute.LocalComputeTaskRequest
import org.torproject.android.service.meshrabiya.compute.TaskStatus
import org.torproject.android.service.meshrabiya.compute.TaskResult
import org.torproject.android.service.meshrabiya.storage.DistributedStorageManager
import org.torproject.android.service.meshrabiya.storage.DistributedStorageClient
import org.torproject.android.service.meshrabiya.storage.FileReference
import org.torproject.android.service.meshrabiya.storage.ChunkReplicaTracker
import org.torproject.android.service.meshrabiya.roles.EmergentRoleManager
import org.torproject.android.service.meshrabiya.roles.MeshRole
import org.torproject.android.service.meshrabiya.gossip.MeshGossipService
import org.torproject.android.service.meshrabiya.messages.TaskStatusUpdateMessage

// Data classes
import org.torproject.android.service.meshrabiya.DropFolder
import org.torproject.android.service.meshrabiya.StoreFileTrigger
```

### DistributedStorageManager.kt

```kotlin
// Android FileObserver
import android.os.FileObserver
import android.os.FileObserver.CREATE
import android.os.FileObserver.MODIFY
import android.os.FileObserver.MOVED_TO

// File I/O
import java.io.File

// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Mesh components
import org.torproject.android.service.meshrabiya.VirtualNode
import org.torproject.android.service.meshrabiya.storage.DistributedStorageClient
import org.torproject.android.service.meshrabiya.DropFolder
import org.torproject.android.service.meshrabiya.StoreFileTrigger
```

### DistributedComputeClient.kt

```kotlin
// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Mesh components
import org.torproject.android.service.meshrabiya.VirtualNode
import org.torproject.android.service.meshrabiya.compute.TaskStatus
import org.torproject.android.service.meshrabiya.compute.TaskResult
import org.torproject.android.service.meshrabiya.messages.TaskStatusUpdateMessage
import org.torproject.android.service.meshrabiya.gossip.MeshGossipService
```

### OrbotMeshService.kt

```kotlin
// Android Service
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

// Mesh components
import org.torproject.android.service.meshrabiya.MeshrabiyaApi
import org.torproject.android.service.meshrabiya.MeshrabiyaApiImpl
```

### MeshEcosystemListener.kt

```kotlin
// Mesh components
import org.torproject.android.service.meshrabiya.MeshrabiyaApi
import org.torproject.android.service.meshrabiya.messages.TaskStatusUpdateMessage
import org.torproject.android.service.meshrabiya.compute.TaskStatus
import org.torproject.android.service.meshrabiya.compute.TaskResult
```

### TaskStatusUpdateMessage.kt (NEW FILE)

```kotlin
package org.torproject.android.service.meshrabiya.messages

import org.torproject.android.service.meshrabiya.compute.TaskStatus
import org.torproject.android.service.meshrabiya.compute.TaskResult
```

---

## IMPLEMENTATION TRACKING CHECKLIST

### Section 1: Compute/Task API (Part 1)
- [ ] 1.1 addTask() - IMPLEMENT
- [ ] 1.2 startTask() - REMOVED (Clarification 4)
- [ ] 1.3 cancelTask() - REMOVED (Clarification 1)
- [ ] 1.4 getTaskStatus() - REMOVED (Clarification 1)
- [ ] 1.5 getJobTypes() - IMPLEMENT

### Section 2: File Operations (Part 1)
- [ ] 2.1 storeFile() - IMPLEMENT (with drop folder check)
- [ ] 2.2 retrieveFile() - IMPLEMENT (with "shared" subfolder)
- [ ] 2.3 streamFile() - COMMENTED OUT (Clarification 2)
- [ ] 2.4 deleteFile() - IMPLEMENT
- [ ] 2.5 getAllMeshFiles() - IMPLEMENT (with sendChunkQuery)

### Section 3: Gateway Controls (Part 2)
- [ ] 3.1 setTorGatewayEnabled() - IMPLEMENT
- [ ] 3.2 getTorGatewayStatus() - IMPLEMENT
- [ ] 3.3 setInternetGatewayEnabled() - IMPLEMENT
- [ ] 3.4 getInternetGatewayStatus() - IMPLEMENT
- [ ] 3.5 getGatewayStatus() - IMPLEMENT

### Section 4: Storage Participation (Part 2)
- [ ] 4.1 setStorageParticipationEnabled() - IMPLEMENT (trivial wrapper)
- [ ] 4.2 getStorageParticipationStatus() - IMPLEMENT (trivial wrapper)
- [ ] 4.3 getStorageAllocations() - IMPLEMENT (trivial wrapper)
- [ ] 4.4 setStorageAllocation() - IMPLEMENT (trivial wrapper)
- [ ] 4.5 getAvailableStorageDevices() - IMPLEMENT (StatFs)

### Section 5: Enhanced State Methods (Part 2)
- [ ] 5.1 getFitnessScore() - IMPLEMENT
- [ ] 5.2 getMeshStatus() - IMPLEMENT
- [ ] 5.3 getNetworkInfo() - IMPLEMENT
- [ ] 5.4 getNodeInfo() - IMPLEMENT

### Section 6: Event Handler Wiring (Part 3)
- [ ] 6.1 Wire mesh state change handler - IMPLEMENT
- [ ] 6.2 Wire peer count change handler - IMPLEMENT
- [ ] 6.3 Wire gossip message handler - IMPLEMENT

### Section 7: Drop Folder Implementation (Part 3)
- [ ] 7.1 DropFolder + StoreFileTrigger data structures - IMPLEMENT
- [ ] 7.2 setStoreFileTrigger() method - IMPLEMENT
- [ ] 7.3 matchTriggersForFile() method - IMPLEMENT
- [ ] 7.4 "shared" subfolder absolute exception - IMPLEMENT
- [ ] 7.5 FileObserver setup (max 2 levels) - IMPLEMENT
- [ ] 7.6 configureDropFolder() method - IMPLEMENT
- [ ] 7.7 retrieveFile() integration - VERIFY (from Section 2.2)
- [ ] 7.8 MeshrabiyaApi drop folder methods - IMPLEMENT
- [ ] 7.9 MeshrabiyaApi trigger management - IMPLEMENT
- [ ] 7.10 Complex file/folder/trigger rules - IMPLEMENT

### Section 8: OrbotMeshService Refactoring (Part 4)
- [ ] 8.1 Fix deprecated onStart import - IMPLEMENT
- [ ] 8.2 Remove redundant API wrappers - IMPLEMENT
- [ ] 8.3 Add Tor proxy integration point - IMPLEMENT
- [ ] 8.4 Wire event handlers - IMPLEMENT
- [ ] 8.5 Implement Binder (Clarification 14) - IMPLEMENT

### Section 9: Task Status Callback System (Part 4)
- [ ] 9.1 Create TaskStatusUpdateMessage - IMPLEMENT (NEW FILE)
- [ ] 9.2 Add onTaskStatusUpdate callback - IMPLEMENT
- [ ] 9.3 Register handler in MeshEcosystemListener - IMPLEMENT
- [ ] 9.4 Update DistributedComputeClient status sending - IMPLEMENT
- [ ] 9.5 Update addTask() to return ID immediately - IMPLEMENT
- [ ] 9.6 Document task status callback pattern - IMPLEMENT

### Section 10: Integration Summary (Part 4)
- [ ] Drop folder integration summary - DOCUMENTED

### Import Requirements
- [ ] MeshrabiyaApiImpl.kt imports - ADD
- [ ] DistributedStorageManager.kt imports - ADD
- [ ] DistributedComputeClient.kt imports - ADD
- [ ] OrbotMeshService.kt imports - ADD
- [ ] MeshEcosystemListener.kt imports - ADD
- [ ] TaskStatusUpdateMessage.kt (NEW FILE) - CREATE

---

## OUTSTANDING QUESTIONS SUMMARY (ALL PARTS)

**Total Questions:** 15 (4 from Part 1, 3 from Part 2, 2 from Part 3, 3 from Part 4)

**CRITICAL (Blocks Implementation):**
- Q1.2: submitTask() method signature
- Q2.1: FileReference unification (architectural)
- Q3.1: setPreferredRoles() method (gateway controls)

**HIGH (Impacts Features):**
- Q2.4: getFileMetadata() for ownership check
- Q2.3: ChunkReplicaTracker access
- Q5.1: getNodeCapabilities() method
- Q7.2: Inherited recipients protection
- Q9.2: Task requester ID tracking

**MEDIUM (Has Workarounds):**
- Q1.1: LocalComputeTaskRequest properties
- Q1.3: getSupportedJobTypes() method
- Q2.2: FileReference timestamp
- Q5.2: OriginatingMessage timestamp
- Q7.1: onFileAddedToDropFolder callback
- Q9.1: MeshrabiyaApi access in listener

**LOW (Future Enhancements):**
- Q8.1: Tor proxy integration pattern

**See:** `MESHRABIYA_API_v3_OUTSTANDING_QUESTIONS.md` for full details

---

## CONFIDENCE AND UNCERTAINTY SUMMARY

**Overall Confidence:** 92% ✅

**Per-Section Confidence:**
- Section 1 (Compute/Task API): 85% (3 questions - Q1.1, Q1.2, Q1.3)
- Section 2 (File Operations): 80% (4 questions - Q2.1, Q2.2, Q2.3, Q2.4)
- Section 3 (Gateway Controls): 95% (1 question - Q3.1)
- Section 4 (Storage Participation): 100% ✅ (all APIs verified)
- Section 5 (Enhanced State): 90% (2 questions - Q5.1, Q5.2)
- Section 6 (Event Handlers): 98% ✅ (routing pattern verified)
- Section 7 (Drop Folder): 85% (2 questions - Q7.1, Q7.2)
- Section 8 (OrbotMeshService): 95% (1 question - Q8.1)
- Section 9 (Task Status Callbacks): 85% (2 questions - Q9.1, Q9.2)
- Section 10 (Integration Summary): 100% ✅ (documentation only)

**Major Uncertainties:**
1. **FileReference Unification (Q2.1):** Two incompatible definitions exist - requires architectural decision
2. **Task Requester Tracking (Q9.2):** Status update routing needs requester ID tracking mechanism
3. **Inherited Recipients Protection (Q7.2):** updateFileAccess integration complexity

**Architectural Limitations:**
1. **Timestamps:** FileReference and OriginatingMessage lack timestamp fields (Q2.2, Q5.2)
2. **Task Request Model:** No public TaskRequest class (Research Finding 2)

**Fallback Strategies:**
- All 15 questions have documented fallbacks
- All CRITICAL questions have alternative approaches
- All MEDIUM/LOW questions have working implementations despite uncertainty

---

## DOCUMENT COUNT ESTIMATE

**v3 Plan Total:** 4 documents (~3,750 lines)
- Part 1: 1,050 lines ✅
- Part 2: 850 lines ✅
- Part 3: 850 lines ✅
- Part 4: 1,000 lines ✅

**Supporting Documents:**
- MESHRABIYA_API_v3_OUTSTANDING_QUESTIONS.md (~200 lines)
- Original v2 Plan: 4 parts (~3,000 lines for reference)

**Total Documentation:** 8 files (~7,950 lines)

---

**END OF PART 4 - v3 PLAN COMPLETE**

**Agents:** Before beginning ensure all Questions and decions have been answered or selected. IF they havent, report the outstanding items for my response. Otherwise Implement this plan exactly as written. Verify each section with compilation success. Document all deviations and resolutions. Target 100% completion, 0% stub code.
