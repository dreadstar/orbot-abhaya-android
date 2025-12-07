# MeshrabiyaApiImpl Complete Implementation Plan v2.0 - PART 3 of 4

**Date:** 2025-12-05  
**Status:** PLANNED - NOT STARTED  
**Priority:** CRITICAL (Blocks OrbotMeshService and full app functionality)

---

## ⚠️ CRITICAL IMPLEMENTATION MANDATE

**This plan is NOT a suggestion. It is a complete, literal implementation specification.**

Every code block in this document MUST be implemented exactly as written. NO stubs, NO TODOs, NO NotImplementedErrors, NO placeholders. Agents must copy the provided code directly into the target files and verify compilation success.

**Completion criteria:** 100% of methods implemented, 0% stub code remaining, full compilation success.

---

## PART 3 CONTENTS

This part covers:
- **Section 6:** Event Handler Wiring (3 handler registrations)
- **Section 7:** Drop Folder Implementation - Enhanced Version (10 subsections)

**Sections in other parts:**
- Part 1: Header, Executive Summary, User Clarifications, Sections 1-2
- Part 2: Sections 3-5
- Part 4: Section 8, NEW Sections 9-10, Imports, Tracking

---

## SECTION 6: Event Handler Wiring Implementation ⭐ MEDIUM PRIORITY

**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 83-96 (in initMesh method)  
**Methods:** 3 handler registrations

**Implementation Location:** All changes go in the `initMesh()` method of `MeshrabiyaApiImpl.kt`, after manager initialization.

---

### 6.1 Wire Mesh State Change Handler

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
- Access: `myNode.neighbors()` method
- `MeshState` enum (already imported)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Collects myNode.state Flow in IO coroutine
- [ ] Maps NodeState to MeshState correctly (hotspot + neighbors = CONNECTED)
- [ ] Invokes onMeshStateChanged callback when state changes
- [ ] Handles null myNode gracefully
- [ ] Coroutine launched in appropriate scope
- [ ] Flow collection continues for lifecycle of mesh

**Notes:**
- Uses Flow.collect to observe NodeState changes
- Maps hotspot + neighbor status to user-facing MeshState
- Coroutine runs in IO dispatcher (non-blocking)

---

### 6.2 Wire Peer Count Change Handler

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

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Polls neighbor count every 5 seconds
- [ ] Invokes onPeerCountChanged only when count changes
- [ ] Handles null myNode gracefully
- [ ] Coroutine launched in IO dispatcher
- [ ] Polling interval appropriate (not too frequent)
- [ ] While loop continues for lifecycle of mesh

**Notes:**
- Poll-based approach (no direct Flow for neighbor count)
- 5-second interval balances responsiveness vs overhead
- Detects changes only (not redundant callbacks)

---

### 6.3 Wire Gossip Message Handler

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
- Access: `myNode.getMeshGossipService()` method
- Access: `MeshGossipService.addGossipListener()` method

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Registers gossip listener on MeshGossipService
- [ ] Invokes onGossipMessage callback with senderId and messageBytes
- [ ] Handles null myNode gracefully
- [ ] Handles null gossip service gracefully
- [ ] Listener registered once (not multiple times on re-init)

**Notes:**
- Simple delegation to registered callback
- Gossip service may be null if not yet initialized
- Listener lifecycle tied to myNode lifecycle

---

## SECTION 7: Drop Folder Implementation - ENHANCED VERSION ⭐ HIGH PRIORITY

**Priority:** HIGH (User Clarification 3 - critical feature)  
**Status:** ❌ NOT STARTED  
**Lines:** Various (new properties + methods 200-209)  
**Subsections:** 10 (complete auto-sync system)

**User Clarification 3 Summary:**
- Use Android FileObserver for monitoring
- Store DropFolder configuration in DistributedStorageManager
- DropFolder contains: folder path + list of StoreFileTriggers
- StoreFileTrigger contains: id, subPath, recipients list
- API provides setStoreFileTrigger() for CRUD operations
- FileObserver detects new files and matches against trigger paths
- Distinct recipients from matching triggers used for storeFile()
- retrieveFile ignore logic prevents re-triggering on delivered files

---

### 7.1 Add DropFolder and StoreFileTrigger Data Structures

**Location:** `DistributedStorageManager.kt` (add new data classes)

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager.kt (top-level or companion object)

/**
 * Configuration for drop folder auto-sync.
 * 
 * @property folderPath Absolute path to monitored folder
 * @property triggers List of auto-store triggers for subpaths
 */
data class DropFolder(
    val folderPath: String,
    val triggers: MutableList<StoreFileTrigger> = mutableListOf()
)

/**
 * Trigger configuration for auto-storing files in drop folder subpaths.
 * 
 * @property id Unique incrementing ID
 * @property subPath Relative path within drop folder (must be unique)
 * @property recipients List of recipient node IDs for auto-store
 */
data class StoreFileTrigger(
    val id: Int,
    val subPath: String,
    val recipients: List<String>
)
```

**Location in DistributedStorageManager:** Add property to store configuration:

```kotlin
// Add to DistributedStorageManager class properties:

/**
 * Drop folder configuration for auto-sync.
 */
private var dropFolder: DropFolder? = null

/**
 * Next trigger ID for unique identification.
 */
private var nextTriggerId: Int = 1
```

**Dependencies:**
- None (pure data classes)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] DropFolder data class defined with folderPath and triggers
- [ ] StoreFileTrigger data class defined with id, subPath, recipients
- [ ] dropFolder property added to DistributedStorageManager
- [ ] nextTriggerId property added to DistributedStorageManager
- [ ] Data classes use appropriate types (String, List, MutableList)

---

### 7.2 Add setStoreFileTrigger() Method to DistributedStorageManager

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Create, update, or delete a StoreFileTrigger in the drop folder configuration.
 * 
 * @param trigger Trigger to create/update (matched by subPath)
 * @param delete If true, delete the trigger; otherwise create/update
 */
fun setStoreFileTrigger(trigger: StoreFileTrigger, delete: Boolean = false) {
    val currentDropFolder = dropFolder ?: run {
        if (!delete) {
            println("WARN: setStoreFileTrigger called but no drop folder configured")
        }
        return
    }
    
    synchronized(currentDropFolder.triggers) {
        // Find existing trigger by exact subPath match
        val existingIndex = currentDropFolder.triggers.indexOfFirst { it.subPath == trigger.subPath }
        
        if (delete) {
            if (existingIndex >= 0) {
                currentDropFolder.triggers.removeAt(existingIndex)
                println("INFO: Deleted trigger for subPath: ${trigger.subPath}")
            } else {
                println("WARN: Delete requested but no trigger found for subPath: ${trigger.subPath}")
            }
        } else {
            if (existingIndex >= 0) {
                // Update existing trigger
                currentDropFolder.triggers[existingIndex] = trigger
                println("INFO: Updated trigger for subPath: ${trigger.subPath}")
            } else {
                // Create new trigger
                currentDropFolder.triggers.add(trigger)
                println("INFO: Created trigger for subPath: ${trigger.subPath}")
            }
        }
    }
}
```

**Dependencies:**
- `DropFolder` and `StoreFileTrigger` data classes from 7.1
- `dropFolder` property from 7.1

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Creates new trigger when subPath not found and delete=false
- [ ] Updates existing trigger when subPath matches and delete=false
- [ ] Deletes trigger when subPath matches and delete=true
- [ ] Uses exact subPath match for identification
- [ ] Thread-safe (synchronized on triggers list)
- [ ] Handles null dropFolder gracefully
- [ ] Logs all operations

---

### 7.3 Add matchTriggersForFile() Helper Method

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Find all triggers that match a file path within the drop folder.
 * Returns distinct list of recipients from all matching triggers.
 * 
 * @param filePath Absolute path to file
 * @return Distinct list of recipient node IDs, or empty list if no matches
 */
private fun matchTriggersForFile(filePath: String): List<String> {
    val currentDropFolder = dropFolder ?: return emptyList()
    
    // Convert to relative path within drop folder
    val relativePath = if (filePath.startsWith(currentDropFolder.folderPath)) {
        filePath.removePrefix(currentDropFolder.folderPath).removePrefix("/")
    } else {
        return emptyList() // File not in drop folder
    }
    
    // Find all triggers with matching subPath
    val matchingTriggers = synchronized(currentDropFolder.triggers) {
        currentDropFolder.triggers.filter { trigger ->
            relativePath.startsWith(trigger.subPath)
        }
    }
    
    // Collect distinct recipients from all matching triggers
    val recipients = matchingTriggers.flatMap { it.recipients }.distinct()
    
    if (recipients.isNotEmpty()) {
        println("INFO: File '$relativePath' matched ${matchingTriggers.size} trigger(s), ${recipients.size} distinct recipient(s)")
    }
    
    return recipients
}
```

**Answer: `matchTriggersForFile()` needs to match triggers with any left bound matches, meaning that comparison of `/drop/sub1/sub2/sub3` would match:
   - /drop
   - /drop/sub1
   - /drop/sub1/sub2
and would not match:
   - /drop/sub10

Any trigger in the list will have a unique path because if a duplicate path is sent via `setStoreFileTrigger()`, an update will be done instead of a create.

**Dependencies:**
- `DropFolder` and `StoreFileTrigger` data classes from 7.1
- `dropFolder` property from 7.1

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Converts absolute path to relative path within drop folder
- [ ] Matches triggers by subPath prefix (file in subdirectory matches parent trigger)
- [ ] Returns distinct recipients from all matching triggers
- [ ] Returns empty list when no triggers match
- [ ] Returns empty list when file not in drop folder
- [ ] Thread-safe (synchronized on triggers list)
- [ ] Logs matching results

---

### 7.4 Add shouldIgnoreFile() Ignore Logic for retrieveFile

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Set of file paths that should be ignored by FileObserver.
 * Used to prevent re-triggering when retrieveFile delivers to drop folder.
 */
private val ignoredFilePaths = mutableSetOf<String>()

/**
 * Mark a file path to be ignored by FileObserver triggers.
 * Used when retrieveFile delivers a file to drop folder.
 * 
 * @param filePath Absolute path to ignore
 */
fun markFileAsIgnored(filePath: String) {
    synchronized(ignoredFilePaths) {
        ignoredFilePaths.add(filePath)
    }
    println("INFO: Marked file as ignored: $filePath")
}

/**
 * Check if a file should be ignored by FileObserver triggers.
 * 
 * @param filePath Absolute path to check
 * @return True if file should be ignored
 */
private fun shouldIgnoreFile(filePath: String): Boolean {
    synchronized(ignoredFilePaths) {
        return if (ignoredFilePaths.contains(filePath)) {
            ignoredFilePaths.remove(filePath) // One-time ignore
            println("INFO: Ignoring file (marked as retrieved): $filePath")
            true
        } else {
            false
        }
    }
}
```

**Answer: I do not like this implementation with onetime igonre. refactor the ignore to apply to any file in the shared subfolder under the drop folder as an absolute rule. And attempts to set a trigger in the shared sub folder should fail gracefully with error response. Go back and refactor that portion of the Plan  as well as any impacts to the FileObser to optimally implement  this change

**Dependencies:**
- None (pure logic)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] ignoredFilePaths set is thread-safe
- [ ] markFileAsIgnored() adds path to ignore set
- [ ] shouldIgnoreFile() checks and removes path (one-time ignore)
- [ ] One-time ignore behavior (path removed after check)
- [ ] Thread-safe (synchronized on ignoredFilePaths)
- [ ] Logs ignore operations

**Integration Point:** `DistributedStorageClient.retrieveFile()` must call `markFileAsIgnored()` when writing file to drop folder.

---

### 7.5 Add FileObserver Setup in DistributedStorageManager

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * FileObserver for monitoring drop folder.
 */
private var fileObserver: FileObserver? = null

/**
 * Start monitoring drop folder for new files.
 * 
 * @param folderPath Absolute path to monitor
 */
private fun startDropFolderMonitoring(folderPath: String) {
    // Stop existing observer
    fileObserver?.stopWatching()
    
    // Create new FileObserver for drop folder and all subdirectories
    fileObserver = object : FileObserver(folderPath, CREATE or MODIFY or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            path?.let { filename ->
                val fullPath = "$folderPath/$filename"
                val file = File(fullPath)
                
                // Only process files (not directories)
                if (!file.isFile) return
                
                // Check if file should be ignored (from retrieveFile)
                if (shouldIgnoreFile(fullPath)) return
                
                // Match against triggers
                val recipients = matchTriggersForFile(fullPath)
                if (recipients.isNotEmpty()) {
                    println("INFO: Auto-storing file from drop folder: $fullPath")
                    
                    // Call storeFile with matched recipients
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val storageClient = getDistributedStorageClient()
                            val fileData = file.readBytes()
                            
                            val fileRef = storageClient?.storeFile(
                                path = fullPath,
                                data = fileData,
                                priority = 5,
                                replicationLevel = 3,
                                owner = virtualNode.nodeId.toString(),
                                recipients = recipients
                            )
                            
                            if (fileRef != null) {
                                println("INFO: Auto-stored file: ${fileRef.fileId} (${recipients.size} recipients)")
                            } else {
                                println("ERROR: Auto-store failed for: $fullPath")
                            }
                        } catch (e: Exception) {
                            println("ERROR: Auto-store exception for $fullPath: ${e.message}")
                        }
                    }
                }
            }
        }
    }
    
    fileObserver?.startWatching()
    println("INFO: Started drop folder monitoring: $folderPath")
}

/**
 * Stop monitoring drop folder.
 */
private fun stopDropFolderMonitoring() {
    fileObserver?.stopWatching()
    fileObserver = null
    println("INFO: Stopped drop folder monitoring")
}
```

**Dependencies:**
- `import android.os.FileObserver`
- `import java.io.File`
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- Access: `getDistributedStorageClient()` method
- Access: `virtualNode.nodeId` property
- `matchTriggersForFile()` from 7.3
- `shouldIgnoreFile()` from 7.4

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Creates FileObserver for CREATE, MODIFY, MOVED_TO events
- [ ] Monitors entire drop folder (including subdirectories)
- [ ] Filters for files only (not directories)
- [ ] Checks shouldIgnoreFile() before processing
- [ ] Matches file against triggers via matchTriggersForFile()
- [ ] Calls storeFile() with matched recipients
- [ ] Launches coroutine in IO dispatcher
- [ ] Handles exceptions during auto-store
- [ ] Stops existing observer before starting new one
- [ ] Logs all operations

**Notes:**
- FileObserver monitors recursively (subdirectories included)
- CREATE, MODIFY, MOVED_TO events cover all file additions
- One-time ignore prevents retrieveFile loop

---

### 7.6 Add configureDropFolder() Method to DistributedStorageManager

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Configure drop folder for auto-sync.
 * Creates DropFolder configuration and starts FileObserver monitoring.
 * 
 * @param folderPath Absolute path to drop folder
 * @return True if configuration successful
 */
fun configureDropFolder(folderPath: String): Boolean {
    return try {
        // Validate folder exists
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            println("ERROR: Drop folder does not exist or is not a directory: $folderPath")
            return false
        }
        
        // Stop existing monitoring
        stopDropFolderMonitoring()
        
        // Create new DropFolder configuration
        dropFolder = DropFolder(
            folderPath = folderPath,
            triggers = mutableListOf()
        )
        
        // Start FileObserver monitoring
        startDropFolderMonitoring(folderPath)
        
        println("INFO: Configured drop folder: $folderPath")
        true
    } catch (e: Exception) {
        println("ERROR: Failed to configure drop folder: ${e.message}")
        false
    }
}

/**
 * Get current drop folder path.
 * 
 * @return Drop folder path or null if not configured
 */
fun getDropFolderPath(): String? {
    return dropFolder?.folderPath
}

/**
 * Get all configured triggers.
 * 
 * @return List of triggers (copy, not mutable)
 */
fun getDropFolderTriggers(): List<StoreFileTrigger> {
    return synchronized(dropFolder?.triggers ?: return emptyList()) {
        dropFolder?.triggers?.toList() ?: emptyList()
    }
}
```

**Dependencies:**
- `import java.io.File`
- `DropFolder` and `StoreFileTrigger` data classes from 7.1
- `startDropFolderMonitoring()` from 7.5
- `stopDropFolderMonitoring()` from 7.5

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Validates folder exists and is directory
- [ ] Stops existing monitoring before reconfiguring
- [ ] Creates DropFolder with empty triggers list
- [ ] Starts FileObserver monitoring
- [ ] Returns true on success, false on failure
- [ ] getDropFolderPath() returns current path or null
- [ ] getDropFolderTriggers() returns immutable copy
- [ ] Thread-safe (synchronized on triggers list)
- [ ] Handles exceptions gracefully
- [ ] Logs all operations

---

### 7.7 Update DistributedStorageClient.retrieveFile() to Mark Ignored Files

**Location:** `DistributedStorageClient.kt` (modify existing method)

**Current State:** `retrieveFile()` writes file to storage, does not consider drop folder

**Target Implementation:** Add ignore marking when writing to drop folder:

```kotlin
// In DistributedStorageClient.retrieveFile() method:
// After writing file to disk, add:

// Check if file is being written to drop folder
val dropFolderPath = distributedStorageManager.getDropFolderPath()
if (dropFolderPath != null && outputPath.startsWith(dropFolderPath)) {
    // Mark file as ignored to prevent re-triggering
    distributedStorageManager.markFileAsIgnored(outputPath)
}
```

**Integration Point:** Add this check after file write, before return statement.

**Dependencies:**
- Access: `distributedStorageManager.getDropFolderPath()` from 7.6
- Access: `distributedStorageManager.markFileAsIgnored()` from 7.4

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Checks if output path is in drop folder
- [ ] Calls markFileAsIgnored() when writing to drop folder
- [ ] Does not affect retrieveFile() behavior for non-drop-folder paths
- [ ] Prevents FileObserver re-triggering on retrieved files

---

### 7.8 Add MeshrabiyaApi Methods for Drop Folder Configuration

**Location:** `MeshrabiyaApi.kt` (interface) and `MeshrabiyaApiImpl.kt` (implementation)

**Target Implementation for Interface:**

```kotlin
// Add to MeshrabiyaApi.kt interface:

/**
 * Configure drop folder for auto-sync.
 * 
 * @param path Absolute path to drop folder
 * @param callback Result callback (success=true if configured)
 */
fun selectDropFolder(path: String, callback: (Result<Boolean>) -> Unit)

/**
 * Get current drop folder path.
 * 
 * @return Drop folder File or null if not configured
 */
fun getDropFolder(): File?

/**
 * List all files in drop folder.
 * 
 * @return List of files in drop folder
 */
fun getDropFolderFiles(): List<File>
```

**Target Implementation for MeshrabiyaApiImpl:**

```kotlin
// Add to MeshrabiyaApiImpl.kt class:

override fun selectDropFolder(path: String, callback: (Result<Boolean>) -> Unit) {
    try {
        val success = distributedStorageManager?.configureDropFolder(path) ?: false
        if (success) {
            callback(Result.success(true))
        } else {
            callback(Result.failure(IllegalArgumentException("Failed to configure drop folder: $path")))
        }
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("selectDropFolder", e)
    }
}

override fun getDropFolder(): File? {
    return distributedStorageManager?.getDropFolderPath()?.let { File(it) }
}

override fun getDropFolderFiles(): List<File> {
    val folder = getDropFolder() ?: return emptyList()
    return folder.listFiles()?.filter { it.isFile } ?: emptyList()
}
```

**Dependencies:**
- `import java.io.File`
- Access: `distributedStorageManager.configureDropFolder()` from 7.6
- Access: `distributedStorageManager.getDropFolderPath()` from 7.6

**Verification Checklist:**
- [ ] Compiles without errors (interface + implementation)
- [ ] selectDropFolder() calls configureDropFolder() on manager
- [ ] selectDropFolder() returns success/failure appropriately
- [ ] getDropFolder() returns File for configured path or null
- [ ] getDropFolderFiles() lists files in drop folder
- [ ] Filters for files only (not directories)
- [ ] Handles null manager gracefully
- [ ] Invokes onOperationFailed on exceptions

---

### 7.9 Add MeshrabiyaApi Methods for Trigger Management

**Location:** `MeshrabiyaApi.kt` (interface) and `MeshrabiyaApiImpl.kt` (implementation)

**Target Implementation for Interface:**

```kotlin
// Add to MeshrabiyaApi.kt interface:

/**
 * Create or update a drop folder trigger.
 * 
 * @param subPath Relative path within drop folder
 * @param recipients List of recipient node IDs
 * @param callback Result callback (success=trigger ID)
 */
fun createDropFolderTrigger(subPath: String, recipients: List<String>, callback: (Result<Int>) -> Unit)

/**
 * Update existing drop folder trigger.
 * 
 * @param triggerId Trigger ID to update
 * @param subPath New relative path (must be unique)
 * @param recipients New recipient list
 * @param callback Result callback
 */
fun updateDropFolderTrigger(triggerId: Int, subPath: String, recipients: List<String>, callback: (Result<Unit>) -> Unit)

/**
 * Delete drop folder trigger.
 * 
 * @param triggerId Trigger ID to delete
 * @param callback Result callback
 */
fun deleteDropFolderTrigger(triggerId: Int, callback: (Result<Unit>) -> Unit)

/**
 * List all drop folder triggers.
 * 
 * @return List of configured triggers
 */
fun getDropFolderTriggers(): List<DropFolderTriggerInfo>

/**
 * DTO for trigger information exposed via API.
 */
data class DropFolderTriggerInfo(
    val id: Int,
    val subPath: String,
    val recipients: List<String>
)
```

**Target Implementation for MeshrabiyaApiImpl:**

```kotlin
// Add to MeshrabiyaApiImpl.kt class:

override fun createDropFolderTrigger(subPath: String, recipients: List<String>, callback: (Result<Int>) -> Unit) {
    try {
        val manager = distributedStorageManager 
            ?: return callback(Result.failure(IllegalStateException("Storage manager not initialized")))
        
        // Get next trigger ID
        val triggerId = nextDropFolderTriggerId++
        
        val trigger = StoreFileTrigger(
            id = triggerId,
            subPath = subPath,
            recipients = recipients
        )
        
        manager.setStoreFileTrigger(trigger, delete = false)
        callback(Result.success(triggerId))
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("createDropFolderTrigger", e)
    }
}

override fun updateDropFolderTrigger(triggerId: Int, subPath: String, recipients: List<String>, callback: (Result<Unit>) -> Unit) {
    try {
        val manager = distributedStorageManager 
            ?: return callback(Result.failure(IllegalStateException("Storage manager not initialized")))
        
        val trigger = StoreFileTrigger(
            id = triggerId,
            subPath = subPath,
            recipients = recipients
        )
        
        manager.setStoreFileTrigger(trigger, delete = false)
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("updateDropFolderTrigger", e)
    }
}

override fun deleteDropFolderTrigger(triggerId: Int, callback: (Result<Unit>) -> Unit) {
    try {
        val manager = distributedStorageManager 
            ?: return callback(Result.failure(IllegalStateException("Storage manager not initialized")))
        
        // Find trigger by ID
        val triggers = manager.getDropFolderTriggers()
        val triggerToDelete = triggers.find { it.id == triggerId }
            ?: return callback(Result.failure(IllegalArgumentException("Trigger not found: $triggerId")))
        
        manager.setStoreFileTrigger(triggerToDelete, delete = true)
        callback(Result.success(Unit))
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("deleteDropFolderTrigger", e)
    }
}

override fun getDropFolderTriggers(): List<DropFolderTriggerInfo> {
    val triggers = distributedStorageManager?.getDropFolderTriggers() ?: return emptyList()
    return triggers.map { trigger ->
        DropFolderTriggerInfo(
            id = trigger.id,
            subPath = trigger.subPath,
            recipients = trigger.recipients
        )
    }
}

// Add property to track next trigger ID:
private var nextDropFolderTriggerId: Int = 1
```

**Dependencies:**
- `StoreFileTrigger` data class (must be accessible from MeshrabiyaApiImpl)
- Access: `distributedStorageManager.setStoreFileTrigger()` from 7.2
- Access: `distributedStorageManager.getDropFolderTriggers()` from 7.6

**Verification Checklist:**
- [ ] Compiles without errors (interface + implementation)
- [ ] createDropFolderTrigger() generates unique IDs
- [ ] createDropFolderTrigger() calls setStoreFileTrigger with delete=false
- [ ] updateDropFolderTrigger() updates existing trigger
- [ ] deleteDropFolderTrigger() finds trigger by ID
- [ ] deleteDropFolderTrigger() calls setStoreFileTrigger with delete=true
- [ ] getDropFolderTriggers() converts to DTO list
- [ ] Handles null manager gracefully
- [ ] Invokes onOperationFailed on exceptions
- [ ] nextDropFolderTriggerId increments correctly

---

### 7.10 Add Event Callback for Drop Folder Auto-Store

**Location:** `MeshrabiyaApiImpl.kt`

**Current State:** `onFileAddedToDropFolder` callback exists but never invoked

**Target Implementation:** Invoke callback in FileObserver handler (from 7.5):

```kotlin
// In FileObserver.onEvent (from 7.5), after successful storeFile:

if (fileRef != null) {
    println("INFO: Auto-stored file: ${fileRef.fileId} (${recipients.size} recipients)")
    
    // Invoke callback on main thread
    withContext(Dispatchers.Main) {
        onFileAddedToDropFolder?.invoke(fileRef.fileId, file)
    }
} else {
    println("ERROR: Auto-store failed for: $fullPath")
}
```

**Integration Point:** This code goes in the FileObserver.onEvent() method added in Section 7.5.

**Dependencies:**
- `import kotlinx.coroutines.withContext`
- `import kotlinx.coroutines.Dispatchers`
- Access: `onFileAddedToDropFolder` callback property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Callback invoked after successful auto-store
- [ ] Callback receives fileId and File
- [ ] Callback invoked on Main dispatcher (UI-safe)
- [ ] Callback not invoked on failure

---

## SECTION 7 OUTSTANDING ISSUES

**Issue 1: StoreFileTrigger Visibility**
- **Question:** Should `StoreFileTrigger` be top-level class or nested in `DistributedStorageManager`?
- **Impact:** Affects import statements in `MeshrabiyaApiImpl.kt`
- **Recommendation:** Make top-level for easier access, or add public accessor method

**Answer:  top level is fine.

**Issue 2: FileObserver Recursive Monitoring**
- **Question:** Does Android FileObserver monitor subdirectories recursively?
- **Impact:** May need manual recursive observer setup for subdirectories
- **Recommendation:** Test with nested subdirectories, add manual recursion if needed

**Answer: We will implement recursive FIleObserver but only a max of 2 level depth of sub folders. this can be messaged to users in the UI

**Issue 3: Trigger ID Persistence**
- **Question:** Should trigger IDs persist across app restarts?
- **Impact:** IDs will reset to 1 on restart, potentially causing conflicts
- **Recommendation:** Persist nextTriggerId to storage or use timestamp-based IDs

**Answer: triggers should not persist and counter starts at 0 on restart

**Issue 4: Concurrent File Operations**
- **Question:** What happens if user manually calls storeFile() while FileObserver is auto-storing same file?
- **Impact:** Potential duplicate FileReference creation
- **Recommendation:** Add file lock or deduplication logic in storeFile()

**Answer: attempts to storeFile on a file in a folder with a trigger should fail gracefully with error returned. User can perform update file access update on a file regardless of location in the drop folder or monitored sub folders, to share the file with additonal entities. the files will inherit access from relevant triggers  and fileAccessUpdate can not delete   those recipients as long as the file remains in the folder.  if the file is moved to a differnt location in the drop folder, the inherited recipients should be recalculated. If a file is removed from the drop folder entirely the fileRefence and associated triggers should be removed (**Note** future enhancement to have callback so that UI can prompt user to trigger mesh delete )
---

## SECTION 7 VERIFICATION SUMMARY

**Total Subsections:** 10  
**Total New Methods in DistributedStorageManager:** 8  
**Total New Methods in MeshrabiyaApi:** 7  
**Total Data Classes:** 2  
**Total Properties:** 3

**Completion Checklist:**
- [ ] All 10 subsections implemented
- [ ] DropFolder and StoreFileTrigger data classes added
- [ ] DistributedStorageManager methods added (8 methods)
- [ ] MeshrabiyaApi methods added (7 methods)
- [ ] FileObserver setup and monitoring functional
- [ ] retrieveFile ignore logic integrated
- [ ] Trigger CRUD operations functional
- [ ] Auto-store triggers on file creation
- [ ] Event callback invoked on auto-store
- [ ] All 4 outstanding issues documented

---

## PART 3 COMPLETION TRACKING

**Section 6: Event Handler Wiring**
- [ ] 6.1 Wire Mesh State Change Handler - NOT STARTED
- [ ] 6.2 Wire Peer Count Change Handler - NOT STARTED
- [ ] 6.3 Wire Gossip Message Handler - NOT STARTED

**Section 7: Drop Folder Implementation**
- [ ] 7.1 Add Data Structures (DropFolder, StoreFileTrigger) - NOT STARTED
- [ ] 7.2 Add setStoreFileTrigger() to DistributedStorageManager - NOT STARTED
- [ ] 7.3 Add matchTriggersForFile() Helper - NOT STARTED
- [ ] 7.4 Add shouldIgnoreFile() Ignore Logic - NOT STARTED
- [ ] 7.5 Add FileObserver Setup - NOT STARTED
- [ ] 7.6 Add configureDropFolder() Method - NOT STARTED
- [ ] 7.7 Update retrieveFile() Ignore Marking - NOT STARTED
- [ ] 7.8 Add MeshrabiyaApi Drop Folder Methods - NOT STARTED
- [ ] 7.9 Add MeshrabiyaApi Trigger Management Methods - NOT STARTED
- [ ] 7.10 Add Event Callback Invocation - NOT STARTED

**Overall Progress:** 0% (0 of 13 items complete)

---

**END OF PART 3**

**Next:** Part 4 will cover Section 8 (OrbotMeshService Refactoring), NEW Section 9 (Task Status Callback System), NEW Section 10 (final integration), Import Requirements, and Completion Tracking.
