# MeshrabiyaApiImpl Complete Implementation Plan v2.0 - PART 4 of 4

**Date:** 2025-12-05  
**Status:** PLANNED - NOT STARTED  
**Priority:** CRITICAL (Blocks OrbotMeshService and full app functionality)

---

## ⚠️ CRITICAL IMPLEMENTATION MANDATE

**This plan is NOT a suggestion. It is a complete, literal implementation specification.**

Every code block in this document MUST be implemented exactly as written. NO stubs, NO TODOs, NO NotImplementedErrors, NO placeholders. Agents must copy the provided code directly into the target files and verify compilation success.

**Completion criteria:** 100% of methods implemented, 0% stub code remaining, full compilation success.

---

## PART 4 CONTENTS

This part covers:
- **Section 8:** OrbotMeshService Refactoring (5 subsections)
- **NEW Section 9:** Task Status Callback System (6 subsections) - User Clarification 1 implementation
- **NEW Section 10:** Drop Folder Integration Notes (summary of Section 7 integration)
- **Import Requirements:** All required imports for all files
- **Completion Tracking:** Final checklist and verification

**Sections in other parts:**
- Part 1: Header, Executive Summary, User Clarifications, Sections 1-2
- Part 2: Sections 3-5
- Part 3: Sections 6-7

---

## SECTION 8: OrbotMeshService Refactoring ⭐ MEDIUM PRIORITY

**Priority:** MEDIUM (Depends on Section 2 completion)  
**Status:** ❌ NOT STARTED  
**File:** `orbotservice/src/main/java/org/torproject/android/service/OrbotMeshService.kt`  
**Current Lines:** 91  
**Changes:** 5 major refactorings

---

### 8.1 Fix Deprecated Imports (CRITICAL - BLOCKS COMPILATION)

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

**Verification Checklist:**
- [ ] Deprecated imports removed
- [ ] Property declarations removed
- [ ] Initialization calls removed
- [ ] Compiles without errors
- [ ] No references to DataStore, MeshFile, or ReplicationManager remain

**Impact Analysis:**
- `DataStore` removed: Was used at line 44 only for initialization (unused)
- `ReplicationManager` removed: Was used at lines 46 (init) and 85 (replicateFile call)
- `MeshFile` removed: Not actually used in this file (import only)
- Line 85 `replicationManager.replicateFile(fileId, file)` needs replacement

---

### 8.2 Remove Redundant storeReceivedFile() Wrapper

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

**Verification Checklist:**
- [ ] storeReceivedFile() method deleted (lines 54-72)
- [ ] onMeshFileReceived() updated to call API directly
- [ ] ReplicationManager.replicateFile() call removed (deprecated)
- [ ] Compiles without errors
- [ ] File storage still functional via MeshrabiyaApi

---

### 8.3 Add Tor Proxy Integration Handler

**Current State:** No Tor proxy integration in OrbotMeshService  
**Purpose:** Enable mesh to route through Tor when Tor service is ready

**Target Implementation:** Add new methods after onCreate():

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

**Verification Checklist:**
- [ ] onTorReady() method added
- [ ] onTorStopped() method added
- [ ] Called from OrbotService when Tor starts/stops
- [ ] Compiles without errors
- [ ] Proxy configuration tested with Tor running
- [ ] Mesh traffic routes through Tor when active

---

### 8.4 Wire Event Handlers in onCreate()

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

**Verification Checklist:**
- [ ] All 10 event handlers registered in onCreate()
- [ ] Handlers log events appropriately
- [ ] Compiles without errors
- [ ] Handlers invoked when events occur (requires Section 6 complete)
- [ ] File operations trigger callbacks
- [ ] State changes trigger callbacks

---

### 8.5 Add Missing onBind() IBinder Return (Optional Enhancement)

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
- [ ] Keep null return (use broadcasts for communication) ← **RECOMMENDED**
- [x] Implement binder (use direct method calls)

**Answer: Implement Binder and ensure `onTorReady()` and `onTorStopped()` are called appropriately

**Verification Checklist (if implemented):**
- [ ] Binder class added
- [ ] onBind() returns binder instance
- [ ] OrbotService binds to service successfully
- [ ] Compiles without errors

---

### SECTION 8 SUMMARY

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

## NEW SECTION 9: Task Status Callback System ⭐ HIGHEST PRIORITY

**Priority:** HIGHEST (User Clarification 1 - critical refactor)  
**Status:** ❌ NOT STARTED  
**Scope:** Replace 3 separate task status messages with unified callback system  
**Subsections:** 6

**User Clarification 1 Summary:**
- REMOVE: cancelTask() and getTaskStatus() methods from MeshrabiyaApi
- CREATE: Single TaskStatusUpdateMessage replacing 3 separate messages
- ADD: setOnTaskStatusUpdate() callback registration in MeshrabiyaApi
- REFACTOR: DistributedComputeClient handlers to use unified message
- UPDATE: DistributedComputeServer senders to use unified message
- UPDATE: MeshEcosystemListener routing for new message type

---

### 9.1 Create TaskStatusUpdateMessage Data Class

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/` (new file or add to existing messages file)

**Target Implementation:**

```kotlin
package com.ustadmobile.meshrabiya.service.compute

import com.ustadmobile.meshrabiya.ext.requireString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unified task status update message for all task lifecycle phases.
 * Replaces TaskCompletedMessage, TaskAcceptanceMessage, and TaskCompletionAckMessage.
 * 
 * Status transitions:
 * - PREPARATION: Task accepted, preparing for execution
 * - EXECUTION: Task execution started
 * - SUCCESS: Task completed successfully
 * - FAILED: Task execution failed
 * 
 * @property taskId Unique task identifier
 * @property status Current task status (from TaskStatus enum)
 * @property message Optional status message or error description
 * @property result Optional task result data (for SUCCESS status)
 */
@Serializable
data class TaskStatusUpdateMessage(
    val taskId: String,
    val status: TaskStatus,
    val message: String? = null,
    val result: JsonObject? = null
) {
    fun toJson(): JsonObject {
        return buildJsonObject {
            put("taskId", taskId)
            put("status", status.name)
            message?.let { put("message", it) }
            result?.let { put("result", it) }
        }
    }
    
    companion object {
        fun fromJson(json: JsonObject): TaskStatusUpdateMessage {
            return TaskStatusUpdateMessage(
                taskId = json.requireString("taskId"),
                status = TaskStatus.valueOf(json.requireString("status")),
                message = json["message"]?.toString()?.removeSurrounding("\""),
                result = json["result"] as? JsonObject
            )
        }
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.ext.requireString`
- `import kotlinx.serialization.Serializable`
- `import kotlinx.serialization.json.JsonObject`
- `import kotlinx.serialization.json.buildJsonObject`
- `import kotlinx.serialization.json.put`
- TaskStatus enum (verify exists and has PREPARATION, EXECUTION, SUCCESS, FAILED values)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] TaskStatus enum exists with all 4 required values
- [ ] toJson() serializes all fields correctly
- [ ] fromJson() deserializes all fields correctly
- [ ] Handles null message and result fields
- [ ] requireString() extension function exists

---

### 9.2 Add setOnTaskStatusUpdate() to MeshrabiyaApi

**Location:** `MeshrabiyaApi.kt` (interface) and `MeshrabiyaApiImpl.kt` (implementation)

**Target Implementation for Interface:**

```kotlin
// Add to MeshrabiyaApi.kt interface:

/**
 * Register callback for task status updates.
 * Called for all task lifecycle transitions: PREPARATION, EXECUTION, SUCCESS, FAILED.
 * 
 * @param callback Invoked with taskId, status, message, and optional result
 */
fun setOnTaskStatusUpdate(callback: (taskId: String, status: TaskStatus, message: String?, result: JsonObject?) -> Unit)
```

**Target Implementation for MeshrabiyaApiImpl:**

```kotlin
// Add to MeshrabiyaApiImpl.kt class properties:

/**
 * Callback for task status updates.
 */
private var onTaskStatusUpdate: ((String, TaskStatus, String?, JsonObject?) -> Unit)? = null

// Add to MeshrabiyaApiImpl.kt class methods:

override fun setOnTaskStatusUpdate(callback: (taskId: String, status: TaskStatus, message: String?, result: JsonObject?) -> Unit) {
    this.onTaskStatusUpdate = callback
}
```

**Dependencies:**
- TaskStatus enum (already used in project)
- `import kotlinx.serialization.json.JsonObject`

**Verification Checklist:**
- [ ] Compiles without errors (interface + implementation)
- [ ] Callback property added to MeshrabiyaApiImpl
- [ ] setOnTaskStatusUpdate() stores callback correctly
- [ ] Callback signature matches TaskStatusUpdateMessage fields

---

### 9.3 Update DistributedComputeClient Handlers

**Location:** `DistributedComputeClient.kt`

**Current State:** Separate handlers for TaskCompletedMessage, TaskAcceptanceMessage, TaskCompletionAckMessage

**Target Implementation:**

```kotlin
// Add to DistributedComputeClient message handlers:

/**
 * Handle unified task status update message.
 * Updates activeRequests and invokes UI callback via MeshrabiyaApi.
 */
private fun handleTaskStatusUpdate(message: TaskStatusUpdateMessage, fromAddr: Int) {
    println("INFO: Task status update - taskId=${message.taskId}, status=${message.status}, from=$fromAddr")
    
    // Update activeRequests list
    synchronized(activeRequests) {
        val request = activeRequests.find { it.taskId == message.taskId }
        if (request != null) {
            // Update TaskRequest status
            request.status = message.status
            
            // If terminal status, optionally remove from activeRequests
            if (message.status == TaskStatus.SUCCESS || message.status == TaskStatus.FAILED) {
                // Keep in list for UI access, or remove if desired
                // activeRequests.remove(request)
            }
            
            println("INFO: Updated TaskRequest status: taskId=${message.taskId}, status=${message.status}")
        } else {
            println("WARN: Task status update for unknown taskId: ${message.taskId}")
        }
    }
    
    // Invoke UI callback if configured
    val apiCallback = MeshrabiyaApiImpl.getInstance().getTaskStatusUpdateCallback()
    apiCallback?.invoke(message.taskId, message.status, message.message, message.result)
}
```

**Integration Point:** Wire handler in message routing (MeshEcosystemListener or similar):

```kotlin
// In message routing logic:
when (messageType) {
    "TaskStatusUpdate" -> {
        val statusUpdate = TaskStatusUpdateMessage.fromJson(messageJson)
        handleTaskStatusUpdate(statusUpdate, fromAddr)
    }
    // ... other message types
}
```

**Dependencies:**
- TaskStatusUpdateMessage from 9.1
- Access to MeshrabiyaApiImpl.getInstance() (verify method exists)
- TaskRequest.status property (verify exists and is mutable)
- activeRequests list (verify exists as ConcurrentHashMap or similar)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] handleTaskStatusUpdate() updates activeRequests correctly
- [ ] Finds TaskRequest by taskId successfully
- [ ] Updates TaskRequest.status field
- [ ] Invokes MeshrabiyaApi callback
- [ ] Handles unknown taskId gracefully
- [ ] Thread-safe (synchronized on activeRequests)
- [ ] Wired into message routing correctly

---

### 9.4 Add getTaskStatusUpdateCallback() Accessor to MeshrabiyaApiImpl

**Location:** `MeshrabiyaApiImpl.kt`

**Purpose:** Allow DistributedComputeClient to access callback without exposing mutable property

**Target Implementation:**

```kotlin
// Add to MeshrabiyaApiImpl.kt class methods:

/**
 * Get task status update callback for internal use by DistributedComputeClient.
 * 
 * @return Callback function or null if not set
 */
fun getTaskStatusUpdateCallback(): ((String, TaskStatus, String?, JsonObject?) -> Unit)? {
    return onTaskStatusUpdate
}
```

**Dependencies:**
- onTaskStatusUpdate property from 9.2

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns callback correctly
- [ ] Returns null when callback not set
- [ ] Used by DistributedComputeClient in 9.3

---

### 9.5 Update DistributedComputeServer to Send TaskStatusUpdateMessage

**Location:** `DistributedComputeServer.kt` (or TaskManager.kt where status messages are sent)

**Current State:** Sends separate TaskCompletedMessage, TaskAcceptanceMessage, TaskCompletionAckMessage

**Target Implementation:**

Replace all 3 message sends with unified TaskStatusUpdateMessage:

**PREPARATION Phase (when task accepted):**
```kotlin
// When task is accepted and preparing:
val statusUpdate = TaskStatusUpdateMessage(
    taskId = task.taskId,
    status = TaskStatus.PREPARATION,
    message = "Task accepted and preparing for execution"
)
sendMessageToNode(requestorNodeId, "TaskStatusUpdate", statusUpdate.toJson())
```

**EXECUTION Phase (when task starts):**
```kotlin
// When task execution starts:
val statusUpdate = TaskStatusUpdateMessage(
    taskId = task.taskId,
    status = TaskStatus.EXECUTION,
    message = "Task execution started"
)
sendMessageToNode(requestorNodeId, "TaskStatusUpdate", statusUpdate.toJson())
```

**SUCCESS Phase (when task completes successfully):**
```kotlin
// When task completes successfully:
val statusUpdate = TaskStatusUpdateMessage(
    taskId = task.taskId,
    status = TaskStatus.SUCCESS,
    message = "Task completed successfully",
    result = task.resultData // JsonObject with task results
)
sendMessageToNode(requestorNodeId, "TaskStatusUpdate", statusUpdate.toJson())
```

**FAILED Phase (when task fails):**
```kotlin
// When task execution fails:
val statusUpdate = TaskStatusUpdateMessage(
    taskId = task.taskId,
    status = TaskStatus.FAILED,
    message = errorMessage // Error description
)
sendMessageToNode(requestorNodeId, "TaskStatusUpdate", statusUpdate.toJson())
```

**Dependencies:**
- TaskStatusUpdateMessage from 9.1
- sendMessageToNode() method (verify signature)
- Task.taskId, Task.resultData properties (verify exist)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] All 4 status transitions implemented
- [ ] PREPARATION sent when task accepted
- [ ] EXECUTION sent when task starts
- [ ] SUCCESS sent with result data
- [ ] FAILED sent with error message
- [ ] Old message sends removed (TaskCompletedMessage, etc.)
- [ ] Message type string "TaskStatusUpdate" correct

---

### 9.6 Remove Obsolete Methods and Messages

**Files to Modify:**
1. `MeshrabiyaApi.kt` and `MeshrabiyaApiImpl.kt` - Remove cancelTask() and getTaskStatus()
2. Message files - Remove TaskCompletedMessage, TaskAcceptanceMessage, TaskCompletionAckMessage classes
3. Message routing - Remove handlers for obsolete message types

**Target Implementation:**

**Remove from MeshrabiyaApi.kt and MeshrabiyaApiImpl.kt:**
```kotlin
// DELETE these method signatures and implementations:
// fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)
// fun getTaskStatus(taskId: String): TaskStatus?
```

**Remove obsolete message classes:**
```kotlin
// DELETE these files or classes:
// TaskCompletedMessage.kt (or data class in messages file)
// TaskAcceptanceMessage.kt (or data class in messages file)
// TaskCompletionAckMessage.kt (or data class in messages file)
```

**Remove from message routing:**
```kotlin
// DELETE these handlers from MeshEcosystemListener or similar:
// when (messageType) {
//     "TaskCompleted" -> handleTaskCompleted(...)
//     "TaskAcceptance" -> handleTaskAcceptance(...)
//     "TaskCompletionAck" -> handleTaskCompletionAck(...)
// }
```

**Dependencies:**
- None (pure deletion)

**Verification Checklist:**
- [ ] cancelTask() removed from interface and implementation
- [ ] getTaskStatus() removed from interface and implementation
- [ ] TaskCompletedMessage class deleted
- [ ] TaskAcceptanceMessage class deleted
- [ ] TaskCompletionAckMessage class deleted
- [ ] Old message handlers removed from routing
- [ ] No references to obsolete messages remain
- [ ] Compiles without errors
- [ ] No broken imports

---

### SECTION 9 OUTSTANDING ISSUES

**Issue 1: TaskStatus Enum Values**
- **Question:** Does TaskStatus enum have PREPARATION, EXECUTION, SUCCESS, FAILED values?
- **Impact:** TaskStatusUpdateMessage will not compile if values missing
- **Action Required:** Verify TaskStatus enum, add missing values if needed

**Issue 2: TaskRequest.status Property**
- **Question:** Is TaskRequest.status mutable? What is its current type?
- **Impact:** Cannot update status in activeRequests if property is immutable or wrong type
- **Action Required:** Verify TaskRequest class, make status mutable if needed

**Issue 3: Message Routing Entry Point**
- **Question:** Where exactly are incoming messages routed (MeshEcosystemListener, DistributedComputeClient, other)?
- **Impact:** Cannot wire handleTaskStatusUpdate() without knowing routing location
- **Action Required:** Locate message routing code, add TaskStatusUpdate handler

**Issue 4: Task Result Data Format**
- **Question:** What format is task result data in (JsonObject, Map, custom class)?
- **Impact:** Cannot populate TaskStatusUpdateMessage.result field correctly
- **Action Required:** Verify task result format, add conversion if needed

---

### SECTION 9 VERIFICATION SUMMARY

**Total Subsections:** 6  
**Total New Classes:** 1 (TaskStatusUpdateMessage)  
**Total New Methods:** 2 (setOnTaskStatusUpdate, getTaskStatusUpdateCallback)  
**Total Methods Removed:** 2 (cancelTask, getTaskStatus)  
**Total Message Classes Removed:** 3 (TaskCompletedMessage, TaskAcceptanceMessage, TaskCompletionAckMessage)

**Completion Checklist:**
- [ ] TaskStatusUpdateMessage created and tested
- [ ] setOnTaskStatusUpdate() added to MeshrabiyaApi
- [ ] onTaskStatusUpdate callback property added to MeshrabiyaApiImpl
- [ ] getTaskStatusUpdateCallback() accessor added
- [ ] DistributedComputeClient handler implemented
- [ ] DistributedComputeServer senders updated (all 4 phases)
- [ ] Message routing wired for TaskStatusUpdate
- [ ] cancelTask() and getTaskStatus() removed
- [ ] Obsolete message classes removed
- [ ] Obsolete message handlers removed
- [ ] All 4 outstanding issues resolved
- [ ] Compiles without errors
- [ ] Task status updates functional end-to-end

---

## NEW SECTION 10: Drop Folder Integration Summary

**Status:** Implementation detailed in Section 7 (Part 3)  
**Purpose:** Summary of key integration points for drop folder auto-sync

This section serves as a quick reference for the complete drop folder implementation detailed in Section 7 of Part 3.

### Key Components (from Section 7):

1. **Data Structures** (Section 7.1):
   - `DropFolder` data class (folderPath + triggers list)
   - `StoreFileTrigger` data class (id, subPath, recipients)

2. **DistributedStorageManager Methods** (Sections 7.2-7.6):
   - `setStoreFileTrigger()` - CRUD operations for triggers
   - `matchTriggersForFile()` - Find matching triggers for file path
   - `shouldIgnoreFile()` - Check if file should be ignored
   - `markFileAsIgnored()` - Mark file to ignore (called by retrieveFile)
   - `startDropFolderMonitoring()` - Start FileObserver
   - `stopDropFolderMonitoring()` - Stop FileObserver
   - `configureDropFolder()` - Main configuration method
   - `getDropFolderPath()` - Get current path
   - `getDropFolderTriggers()` - Get all triggers

3. **MeshrabiyaApi Methods** (Sections 7.8-7.9):
   - `selectDropFolder()` - Configure drop folder
   - `getDropFolder()` - Get drop folder File
   - `getDropFolderFiles()` - List files in drop folder
   - `createDropFolderTrigger()` - Create new trigger
   - `updateDropFolderTrigger()` - Update existing trigger
   - `deleteDropFolderTrigger()` - Delete trigger
   - `getDropFolderTriggers()` - List all triggers

4. **Integration Points**:
   - FileObserver monitors CREATE, MODIFY, MOVED_TO events
   - Matches file paths against trigger subPaths
   - Collects distinct recipients from matching triggers
   - Calls `storeFile()` with matched recipients
   - retrieveFile marks files as ignored to prevent loops
   - Event callback `onFileAddedToDropFolder` invoked on auto-store

### Workflow Summary:

```
1. UI calls selectDropFolder(path) → configureDropFolder() in DistributedStorageManager
2. FileObserver starts monitoring path
3. UI calls createDropFolderTrigger(subPath, recipients) → adds trigger to list
4. User adds file to drop folder (or subfolder)
5. FileObserver detects file creation
6. Check shouldIgnoreFile() → return false (not from retrieveFile)
7. matchTriggersForFile() → find triggers matching file path
8. Collect distinct recipients from matching triggers
9. Call storeFile(file, recipients) → store in distributed storage
10. Invoke onFileAddedToDropFolder callback → notify UI
```

### Critical Implementation Notes:

- **Ignore Logic:** retrieveFile() MUST call markFileAsIgnored() when writing to drop folder
- **Thread Safety:** All trigger operations synchronized on triggers list
- **Recursive Monitoring:** FileObserver monitors 2 levels deep of  drop folder tree

- **Distinct Recipients:** Avoid duplicate recipients when multiple triggers match

**For full implementation details, see Section 7 in Part 3.**

---

## IMPORT REQUIREMENTS

### MeshrabiyaApiImpl.kt

**New Imports Required:**
```kotlin
// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Flow
import kotlinx.coroutines.flow.collect

// File
import java.io.File

// Android
import android.os.StatFs
import android.os.Environment

// Serialization (for Section 9)
import kotlinx.serialization.json.JsonObject
```

**Verify Existing Imports:**
```kotlin
import com.ustadmobile.meshrabiya.service.compute.LocalComputeTaskRequest
import com.ustadmobile.meshrabiya.service.compute.TaskStatus
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.vnet.MeshFile
```

---

### DistributedStorageManager.kt

**New Imports Required:**
```kotlin
// File handling
import java.io.File

// Android FileObserver
import android.os.FileObserver

// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
```

---

### DistributedComputeClient.kt

**New Imports Required:**
```kotlin
// Section 9 - Task Status
import com.ustadmobile.meshrabiya.service.compute.TaskStatusUpdateMessage
import com.ustadmobile.meshrabiya.service.compute.TaskStatus
import kotlinx.serialization.json.JsonObject
```

---

### DistributedComputeServer.kt

**New Imports Required:**
```kotlin
// Section 9 - Task Status
import com.ustadmobile.meshrabiya.service.compute.TaskStatusUpdateMessage
import com.ustadmobile.meshrabiya.service.compute.TaskStatus
import kotlinx.serialization.json.JsonObject
```

---

### MeshrabiyaApi.kt (Interface)

**New Imports Required:**
```kotlin
// Section 9 - Task Status
import com.ustadmobile.meshrabiya.service.compute.TaskStatus
import kotlinx.serialization.json.JsonObject
```

---

### TaskStatusUpdateMessage.kt (NEW FILE)

**All Imports Required:**
```kotlin
package com.ustadmobile.meshrabiya.service.compute

import com.ustadmobile.meshrabiya.ext.requireString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
```

---

### OrbotMeshService.kt

**Imports to Remove:**
```kotlin
// REMOVE these:
// import com.ustadmobile.meshrabiya.storage.DataStore
// import com.ustadmobile.meshrabiya.storage.MeshFile
// import com.ustadmobile.meshrabiya.storage.ReplicationManager
```

**No New Imports Required** (all existing imports sufficient)

---

## COMPLETION TRACKING

### Overall Implementation Progress

**Section 1: Compute/Task API** (5 methods)
- [ ] 1.1 addTask() - Submit task request
- [ ] 1.2 startTask() - Auto-start (no-op)
- [ ] 1.3 cancelTask() - REMOVED (see Section 9)
- [ ] 1.4 getTaskStatus() - REMOVED (see Section 9)
- [ ] 1.5 getJobTypes() - Return supported types

**Section 2: File Operations** (5 methods)
- [ ] 2.1 storeFile() - Store in distributed storage
- [ ] 2.2 retrieveFile() - Retrieve from storage
- [ ] 2.3 streamFile() - COMMENTED OUT per user
- [ ] 2.4 deleteFile() - Delete from storage
- [ ] 2.5 getAllMeshFiles() - List all files

**Section 3: Gateway Controls** (5 methods)
- [ ] 3.1 setTorGatewayEnabled() - Add/remove TOR_GATEWAY role
- [ ] 3.2 getTorGatewayStatus() - Check TOR_GATEWAY active
- [ ] 3.3 setInternetGatewayEnabled() - Add/remove CLEARNET_GATEWAY
- [ ] 3.4 getInternetGatewayStatus() - Check CLEARNET_GATEWAY active
- [ ] 3.5 getGatewayStatus() - Check any gateway active

**Section 4: Storage Participation** (5 methods)
- [ ] 4.1 setStorageParticipationEnabled() - Configure participation
- [ ] 4.2 getStorageParticipationStatus() - Check enabled
- [ ] 4.3 getStorageAllocations() - List allocations
- [ ] 4.4 setStorageAllocation() - Update quota
- [ ] 4.5 getAvailableStorageDevices() - List devices

**Section 5: Enhanced State Methods** (4 methods)
- [ ] 5.1 getFitnessScore() - Calculate fitness 0-100
- [ ] 5.2 getMeshStatus() - Get CONNECTED/IDLE/STOPPED
- [ ] 5.3 getNetworkInfo() - Aggregate topology/peers/roles
- [ ] 5.4 getNodeInfo() - Query specific node

**Section 6: Event Handler Wiring** (3 handlers)
- [ ] 6.1 Wire mesh state change handler
- [ ] 6.2 Wire peer count change handler
- [ ] 6.3 Wire gossip message handler

**Section 7: Drop Folder Implementation** (10 subsections)
- [ ] 7.1 Add data structures (DropFolder, StoreFileTrigger)
- [ ] 7.2 Add setStoreFileTrigger() to DistributedStorageManager
- [ ] 7.3 Add matchTriggersForFile() helper
- [ ] 7.4 Add shouldIgnoreFile() ignore logic
- [ ] 7.5 Add FileObserver setup
- [ ] 7.6 Add configureDropFolder() method
- [ ] 7.7 Update retrieveFile() ignore marking
- [ ] 7.8 Add MeshrabiyaApi drop folder methods
- [ ] 7.9 Add MeshrabiyaApi trigger management methods
- [ ] 7.10 Add event callback invocation

**Section 8: OrbotMeshService Refactoring** (5 subsections)
- [ ] 8.1 Fix deprecated imports
- [ ] 8.2 Remove redundant storeReceivedFile() wrapper
- [ ] 8.3 Add Tor proxy integration handler
- [ ] 8.4 Wire event handlers in onCreate()
- [ ] 8.5 Add onBind() IBinder return (optional)

**Section 9: Task Status Callback System** (6 subsections)
- [ ] 9.1 Create TaskStatusUpdateMessage data class
- [ ] 9.2 Add setOnTaskStatusUpdate() to MeshrabiyaApi
- [ ] 9.3 Update DistributedComputeClient handlers
- [ ] 9.4 Add getTaskStatusUpdateCallback() accessor
- [ ] 9.5 Update DistributedComputeServer senders
- [ ] 9.6 Remove obsolete methods and messages

---

### File Modification Summary

**Files to Create:**
1. `TaskStatusUpdateMessage.kt` - New unified status message (Section 9.1)

**Files to Modify:**
1. `MeshrabiyaApiImpl.kt` - Implement 24 methods + callbacks (Sections 1-6, 9.2, 9.4)
2. `MeshrabiyaApi.kt` - Add 10 new method signatures (Sections 7.8-7.9, 9.2)
3. `DistributedStorageManager.kt` - Add 8 methods + 2 data classes (Section 7)
4. `DistributedStorageClient.kt` - Update retrieveFile() (Section 7.7)
5. `DistributedComputeClient.kt` - Add status handler (Section 9.3)
6. `DistributedComputeServer.kt` - Update status senders (Section 9.5)
7. `OrbotMeshService.kt` - Refactor and wire handlers (Section 8)
8. Message routing file - Add TaskStatusUpdate routing (Section 9.3)

**Files to Delete or Clean:**
1. `TaskCompletedMessage.kt` - Remove obsolete message (Section 9.6)
2. `TaskAcceptanceMessage.kt` - Remove obsolete message (Section 9.6)
3. `TaskCompletionAckMessage.kt` - Remove obsolete message (Section 9.6)

---

### Research Tasks Remaining

**Research Iteration 2 Required:**
User requested 2 iterations of research for Section 9 (Task Status Callback System)

**Iteration 2 Goals:**
1. Verify TaskStatus enum has PREPARATION, EXECUTION, SUCCESS, FAILED values
2. Verify TaskRequest.status property exists and is mutable
3. Locate message routing entry point for TaskStatusUpdate
4. Verify task result data format (JsonObject compatibility)
5. Resolve any ambiguities from Section 9 Outstanding Issues

**Research Iteration 2 Not Yet Conducted**

---

### Outstanding Questions for User

**From Section 7 (Drop Folder):**
1. Should StoreFileTrigger be top-level class or nested in DistributedStorageManager?
2. Does Android FileObserver monitor subdirectories recursively?
3. Should trigger IDs persist across app restarts?
4. How to handle concurrent storeFile() calls for same file?

**From Section 9 (Task Status):**
1. Does TaskStatus enum have PREPARATION, EXECUTION, SUCCESS, FAILED values?
2. Is TaskRequest.status mutable? What is its type?
3. Where is message routing entry point (MeshEcosystemListener location)?
4. What format is task result data (JsonObject, Map, custom class)?

---

### Final Verification Checklist

**Compilation:**
- [ ] All files compile without errors
- [ ] All imports resolve correctly
- [ ] All method signatures match interfaces
- [ ] All data classes serialize/deserialize correctly

**Functionality:**
- [ ] All 26+ methods implemented (0 stubs remaining)
- [ ] All event handlers wired and functional
- [ ] Drop folder auto-sync functional
- [ ] Task status callbacks functional
- [ ] Tor proxy integration functional

**Testing:**
- [ ] File operations tested (store, retrieve, delete, list)
- [ ] Gateway controls tested (enable, disable, status)
- [ ] Storage participation tested
- [ ] State methods tested (fitness, status, network info)
- [ ] Event callbacks tested (all 10 handlers)
- [ ] Drop folder tested (monitoring, triggers, auto-store)
- [ ] Task status updates tested (all 4 phases)
- [ ] OrbotMeshService tested (no deprecated code)

**Documentation:**
- [ ] All implementations match plan specifications
- [ ] All user clarifications incorporated
- [ ] All outstanding issues documented
- [ ] All integration points documented

---

## IMPLEMENTATION ORDER RECOMMENDATION

**Phase 1: Core API (Highest Priority)**
1. Section 1: Compute/Task API (except cancelTask/getTaskStatus)
2. Section 2: File Operations (except streamFile - comment out)
3. Section 5: Enhanced State Methods

**Phase 2: Infrastructure**
4. Section 9: Task Status Callback System (complete all 6 subsections)
5. Section 6: Event Handler Wiring

**Phase 3: Advanced Features**
6. Section 3: Gateway Controls
7. Section 4: Storage Participation
8. Section 7: Drop Folder Implementation (complete all 10 subsections)

**Phase 4: Integration**
9. Section 8: OrbotMeshService Refactoring
10. Final testing and verification

**Rationale:** This order ensures core functionality is implemented first, followed by infrastructure for callbacks and events, then advanced features, and finally integration with OrbotMeshService.

---

**END OF PART 4**

**This completes the v2.0 implementation plan. All 4 parts are now available.**

**Total Sections:** 10 (8 original + 2 new)  
**Total Methods:** 26+ across all sections  
**Total Files Modified:** 8  
**Total Files Created:** 1  
**Total Files Deleted:** 3

**Next Action:** Conduct Research Iteration 2 to resolve outstanding questions, then begin implementation following the recommended phase order.
