# MeshrabiyaApiImpl Complete Implementation Plan v3.0 - PART 3 of 4

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

## PART 3 CONTENTS

This part covers:
- **Section 6:** Event Handler Wiring (3 handler registrations)
- **Section 7:** Drop Folder Implementation - Enhanced Version (10 subsections)

**Sections in other parts:**
- Part 1: Executive Summary, User Clarifications, Research Findings, Sections 1-2
- Part 2: Sections 3-5 (Gateway Controls, Storage Participation, Enhanced State)
- Part 4: Section 8 (OrbotMeshService Refactoring), NEW Sections 9-10, Imports, Tracking

**User Clarifications Applied in This Part:**
- Clarification 3: Drop Folder Auto-Sync with FileObserver
- Clarification 5: retrieveFile() writes to "shared" subfolder
- Clarification 8: Trigger matching uses left-bound matching
- Clarification 9: Refactor ignore logic for "shared" subfolder (absolute rule)
- Clarification 10: StoreFileTrigger as top-level class
- Clarification 11: FileObserver max 2 levels deep
- Clarification 12: Triggers don't persist (counter starts at 0)
- Clarification 13: Comprehensive file/folder/trigger interaction rules

---

## SECTION 6: Event Handler Wiring Implementation ⭐ MEDIUM PRIORITY

**Priority:** MEDIUM (Infrastructure)  
**Status:** ❌ NOT STARTED  
**Lines:** 83-96 (in initMesh method)  
**Methods:** 3 handler registrations  
**Confidence:** 98% ✅ (MeshEcosystemListener routing verified)

**Implementation Location:** All changes go in the `initMesh()` method of `MeshrabiyaApiImpl.kt`, after manager initialization.

---

### 6.1 Wire Mesh State Change Handler ✅ IMPLEMENT

**Current State:** Handler registered but never invoked  
**Purpose:** Monitor mesh state changes and invoke callback

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

### 6.2 Wire Peer Count Change Handler ✅ IMPLEMENT

**Current State:** Handler registered but never invoked  
**Purpose:** Monitor peer count changes and invoke callback

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

### 6.3 Wire Gossip Message Handler ✅ IMPLEMENT

**Current State:** Handler registered but `addGossipListener` commented out  
**Purpose:** Monitor gossip messages and invoke callback

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

## SECTION 6 OUTSTANDING QUESTIONS

**None** - All event handler patterns verified

---

## SECTION 7: Drop Folder Implementation - ENHANCED VERSION ⭐ HIGHEST PRIORITY

**Priority:** HIGHEST (User Clarification 3 - critical feature)  
**Status:** ❌ NOT STARTED  
**Lines:** Various (new properties + methods 200-209)  
**Subsections:** 10 (complete auto-sync system)  
**Confidence:** 85% (FileObserver recursion complexity, concurrent operations)

**User Clarifications Applied (8 total):**
- Clarification 3: FileObserver-based drop folder monitoring
- Clarification 5: retrieveFile files to "shared" subfolder
- Clarification 8: Left-bound path matching for triggers
- Clarification 9: "shared" subfolder absolute exception (never triggers)
- Clarification 10: StoreFileTrigger top-level class
- Clarification 11: FileObserver max 2 levels deep recursion
- Clarification 12: Triggers don't persist, counter starts at 0
- Clarification 13: Comprehensive file/folder/trigger interaction rules

**Clarification 3 Summary:**
- Use Android FileObserver for monitoring
- Store DropFolder configuration in DistributedStorageManager
- DropFolder contains: folder path + list of StoreFileTriggers
- StoreFileTrigger contains: id, subPath, recipients list
- API provides setStoreFileTrigger() for CRUD operations
- FileObserver detects new files and matches against trigger paths
- Distinct recipients from matching triggers used for storeFile()

**Clarification 9 (CRITICAL - Refactored from v2):**
- **Absolute Rule:** Files in "shared" subfolder NEVER trigger auto-store
- **Trigger Creation:** Attempts to set trigger in "shared" subfolder fail gracefully
- **No Ignore Tracking:** Don't use one-time ignore - "shared" is permanent exception

**Clarification 13 (Complex Interaction Rules):**
1. storeFile on file in triggered folder → FAIL gracefully with error
2. updateFileAccess works regardless of location
3. Files inherit recipients from triggers (cannot be deleted via updateFileAccess)
4. Moving file within drop folder recalculates inherited recipients
5. Removing file from drop folder removes triggers (future: UI prompt for mesh delete)

---

### 7.1 Add DropFolder and StoreFileTrigger Data Structures ✅ IMPLEMENT

**Location:** Top-level classes (new file or add to DistributedStorageManager.kt)

**Target Implementation:**

```kotlin
// Top-level data classes (per Clarification 10)

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
 * Per Clarification 12: IDs are not persisted across app restarts.
 * Counter starts at 0 on each app launch.
 * 
 * @property id Unique incrementing ID (resets on restart)
 * @property subPath Relative path within drop folder (must be unique)
 * @property recipients List of recipient node IDs for auto-store
 */
data class StoreFileTrigger(
    val id: Int,
    val subPath: String,
    val recipients: List<String>
)
```

**Location in DistributedStorageManager:** Add properties to store configuration:

```kotlin
// Add to DistributedStorageManager class properties:

/**
 * Drop folder configuration for auto-sync.
 */
private var dropFolder: DropFolder? = null

/**
 * Next trigger ID for unique identification.
 * Per Clarification 12: Starts at 0 on each app launch (not persisted).
 */
private var nextTriggerId: Int = 0
```

**Dependencies:**
- None (pure data classes)

**User Clarifications Applied:**
- Clarification 10: StoreFileTrigger as top-level class (not nested)
- Clarification 12: Counter starts at 0 (not persisted)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] DropFolder data class defined with folderPath and triggers
- [ ] StoreFileTrigger data class defined with id, subPath, recipients
- [ ] dropFolder property added to DistributedStorageManager
- [ ] nextTriggerId property added (starts at 0)
- [ ] Data classes use appropriate types (String, List, MutableList)
- [ ] nextTriggerId documented as non-persistent

---

### 7.2 Add setStoreFileTrigger() Method to DistributedStorageManager ✅ IMPLEMENT

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Create, update, or delete a StoreFileTrigger in the drop folder configuration.
 * 
 * Per Clarification 9: Attempts to set trigger in "shared" subfolder fail gracefully.
 * Per Clarification 13: Triggers define inherited recipients for file access control.
 * 
 * @param trigger Trigger to create/update (matched by subPath)
 * @param delete If true, delete the trigger; otherwise create/update
 * @return True if operation succeeded, false if failed (e.g., "shared" subfolder)
 */
fun setStoreFileTrigger(trigger: StoreFileTrigger, delete: Boolean = false): Boolean {
    val currentDropFolder = dropFolder ?: run {
        if (!delete) {
            println("WARN: setStoreFileTrigger called but no drop folder configured")
        }
        return false
    }
    
    // Per Clarification 9: Reject triggers in "shared" subfolder
    if (!delete && (trigger.subPath == "shared" || trigger.subPath.startsWith("shared/"))) {
        println("ERROR: Cannot set trigger in 'shared' subfolder: ${trigger.subPath}")
        return false
    }
    
    synchronized(currentDropFolder.triggers) {
        // Find existing trigger by exact subPath match
        val existingIndex = currentDropFolder.triggers.indexOfFirst { it.subPath == trigger.subPath }
        
        if (delete) {
            if (existingIndex >= 0) {
                currentDropFolder.triggers.removeAt(existingIndex)
                println("INFO: Deleted trigger for subPath: ${trigger.subPath}")
                return true
            } else {
                println("WARN: Delete requested but no trigger found for subPath: ${trigger.subPath}")
                return false
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
            return true
        }
    }
}
```

**Dependencies:**
- `DropFolder` and `StoreFileTrigger` data classes from 7.1
- `dropFolder` property from 7.1

**User Clarifications Applied:**
- Clarification 9: Reject triggers in "shared" subfolder with error
- Clarification 13: Triggers define inherited recipients

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Creates new trigger when subPath not found and delete=false
- [ ] Updates existing trigger when subPath matches and delete=false
- [ ] Deletes trigger when subPath matches and delete=true
- [ ] Rejects triggers in "shared" subfolder (Clarification 9)
- [ ] Uses exact subPath match for identification
- [ ] Thread-safe (synchronized on triggers list)
- [ ] Handles null dropFolder gracefully
- [ ] Logs all operations
- [ ] Returns boolean success status

---

### 7.3 Add matchTriggersForFile() Helper Method ✅ IMPLEMENT

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Find all triggers that match a file path within the drop folder.
 * Returns distinct list of recipients from all matching triggers.
 * 
 * Per Clarification 8: Uses left-bound path matching.
 * Example: /drop/sub1/sub2 matches /drop, /drop/sub1, but NOT /drop/sub10
 * 
 * Per Clarification 9: Files in "shared" subfolder NEVER match triggers (absolute exception).
 * 
 * @param filePath Absolute path to file
 * @return Distinct list of recipient node IDs, or empty list if no matches
 */
internal fun matchTriggersForFile(filePath: String): List<String> {
    val currentDropFolder = dropFolder ?: return emptyList()
    
    // Convert to relative path within drop folder
    val relativePath = if (filePath.startsWith(currentDropFolder.folderPath)) {
        filePath.removePrefix(currentDropFolder.folderPath).removePrefix("/")
    } else {
        return emptyList() // File not in drop folder
    }
    
    // Per Clarification 9: Files in "shared" subfolder never match
    if (relativePath.startsWith("shared/") || relativePath == "shared") {
        println("INFO: File in 'shared' subfolder - no triggers match: $relativePath")
        return emptyList()
    }
    
    // Find all triggers with matching subPath (per Clarification 8: left-bound matching)
    val matchingTriggers = synchronized(currentDropFolder.triggers) {
        currentDropFolder.triggers.filter { trigger ->
            // Left-bound matching: /drop/sub1/sub2 matches /drop and /drop/sub1
            // But NOT /drop/sub10 (not a true path prefix)
            relativePath == trigger.subPath || 
            relativePath.startsWith("${trigger.subPath}/")
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

**Dependencies:**
- `DropFolder` and `StoreFileTrigger` data classes from 7.1
- `dropFolder` property from 7.1

**User Clarifications Applied:**
- Clarification 8: Left-bound path matching (not substring match)
- Clarification 9: "shared" subfolder absolute exception

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Converts absolute path to relative path within drop folder
- [ ] Matches triggers by left-bound path prefix (Clarification 8)
- [ ] Does NOT match partial path names (e.g., sub1 vs sub10)
- [ ] Returns empty list for files in "shared" subfolder (Clarification 9)
- [ ] Returns distinct recipients from all matching triggers
- [ ] Returns empty list when no triggers match
- [ ] Returns empty list when file not in drop folder
- [ ] Thread-safe (synchronized on triggers list)
- [ ] Logs matching results

**Notes (Clarification 8):**
- Left-bound matching example:
  - Path: `/drop/sub1/sub2/file.txt`
  - Matches: `/drop`, `/drop/sub1`, `/drop/sub1/sub2`
  - Does NOT match: `/drop/sub10` (not a path prefix)

---

### 7.4 Add "shared" Subfolder Absolute Exception (REFACTORED) ✅ IMPLEMENT

**Location:** `DistributedStorageManager.kt` and FileObserver

**Per Clarification 9:** Refactor ignore logic - "shared" subfolder is absolute exception (no ignore tracking needed)

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Check if a file path is in the "shared" subfolder.
 * Per Clarification 9: Files in "shared" subfolder NEVER trigger auto-store (absolute rule).
 * 
 * @param filePath Absolute path to check
 * @return True if file is in "shared" subfolder
 */
private fun isInSharedSubfolder(filePath: String): Boolean {
    val currentDropFolder = dropFolder ?: return false
    
    val relativePath = if (filePath.startsWith(currentDropFolder.folderPath)) {
        filePath.removePrefix(currentDropFolder.folderPath).removePrefix("/")
    } else {
        return false
    }
    
    return relativePath.startsWith("shared/") || relativePath == "shared"
}
```

**No ignore tracking needed:**
- v2 implementation had `ignoredFilePaths` set and `markFileAsIgnored()` method
- Per Clarification 9: Remove these - "shared" subfolder is permanent exception
- No one-time ignore logic - just check path every time

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Checks if file is in "shared" subfolder
- [ ] Returns true for files in "shared" or "shared/" paths
- [ ] Returns false for files outside drop folder
- [ ] No ignore tracking needed (permanent exception)

**Integration:** FileObserver will call `isInSharedSubfolder()` before processing files

---

### 7.5 Add FileObserver Setup with Recursive Monitoring ✅ IMPLEMENT

**Location:** `DistributedStorageManager.kt`

**Per Clarification 11:** Implement recursive FileObserver, max 2 levels deep

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * FileObserver for monitoring drop folder.
 * Per Clarification 11: Recursive monitoring, max 2 levels deep.
 */
private var fileObservers: MutableList<FileObserver> = mutableListOf()

/**
 * Start monitoring drop folder for new files.
 * Per Clarification 11: Monitors drop folder recursively, max 2 levels deep.
 * 
 * @param folderPath Absolute path to monitor
 */
private fun startDropFolderMonitoring(folderPath: String) {
    // Stop existing observers
    stopDropFolderMonitoring()
    
    // Create observers for drop folder and subdirectories (max 2 levels)
    setupRecursiveObservers(folderPath, currentDepth = 0, maxDepth = 2)
    
    // Start all observers
    fileObservers.forEach { it.startWatching() }
    println("INFO: Started drop folder monitoring (max 2 levels deep): $folderPath")
}

/**
 * Setup recursive FileObservers for a directory.
 * Per Clarification 11: Max 2 levels deep from drop folder root.
 * 
 * @param path Directory path to monitor
 * @param currentDepth Current depth (0 = drop folder root)
 * @param maxDepth Maximum depth to monitor
 */
private fun setupRecursiveObservers(path: String, currentDepth: Int, maxDepth: Int) {
    if (currentDepth > maxDepth) return
    
    // Create observer for current directory
    val observer = object : FileObserver(path, CREATE or MODIFY or MOVED_TO) {
        override fun onEvent(event: Int, filename: String?) {
            filename?.let { fname ->
                val fullPath = "$path/$fname"
                val file = File(fullPath)
                
                // If it's a directory and we're not at max depth, create observer for it
                if (file.isDirectory && currentDepth < maxDepth) {
                    setupRecursiveObservers(fullPath, currentDepth + 1, maxDepth)
                    return
                }
                
                // Only process files (not directories)
                if (!file.isFile) return
                
                // Per Clarification 9: Files in "shared" subfolder never trigger
                if (isInSharedSubfolder(fullPath)) {
                    println("INFO: File in 'shared' subfolder - ignoring: $fullPath")
                    return
                }
                
                // Match against triggers
                val recipients = matchTriggersForFile(fullPath)
                if (recipients.isEmpty()) {
                    println("INFO: No triggers matched for file: $fullPath")
                    return
                }
                
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
                            
                            // Invoke callback on main thread
                            withContext(Dispatchers.Main) {
                                onFileAddedToDropFolder?.invoke(fileRef.fileId, file)
                            }
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
    
    fileObservers.add(observer)
    
    // Setup observers for existing subdirectories
    if (currentDepth < maxDepth) {
        val dir = File(path)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { subdir ->
            setupRecursiveObservers(subdir.absolutePath, currentDepth + 1, maxDepth)
        }
    }
}

/**
 * Stop monitoring drop folder.
 */
private fun stopDropFolderMonitoring() {
    fileObservers.forEach { it.stopWatching() }
    fileObservers.clear()
    println("INFO: Stopped drop folder monitoring")
}
```

**Dependencies:**
- `import android.os.FileObserver`
- `import android.os.FileObserver.CREATE`
- `import android.os.FileObserver.MODIFY`
- `import android.os.FileObserver.MOVED_TO`
- `import java.io.File`
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- `import kotlinx.coroutines.withContext`
- Access: `getDistributedStorageClient()` method
- Access: `virtualNode.nodeId` property
- `matchTriggersForFile()` from 7.3
- `isInSharedSubfolder()` from 7.4

**User Clarifications Applied:**
- Clarification 9: "shared" subfolder absolute exception
- Clarification 11: Recursive monitoring, max 2 levels deep
- Clarification 13: Auto-store triggers only for files with matching triggers

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Creates FileObserver for CREATE, MODIFY, MOVED_TO events
- [ ] Monitors drop folder recursively, max 2 levels deep (Clarification 11)
- [ ] Creates observer for newly created subdirectories
- [ ] Filters for files only (not directories)
- [ ] Checks isInSharedSubfolder() before processing (Clarification 9)
- [ ] Matches file against triggers via matchTriggersForFile()
- [ ] Calls storeFile() with matched recipients
- [ ] Launches coroutine in IO dispatcher
- [ ] Handles exceptions during auto-store
- [ ] Stops existing observers before starting new ones
- [ ] Logs all operations
- [ ] Invokes onFileAddedToDropFolder callback on success

**Notes (Clarification 11):**
- 2 levels deep example:
  - Level 0: /drop
  - Level 1: /drop/sub1
  - Level 2: /drop/sub1/sub2
  - Level 3+: NOT monitored (exceeds max depth)

**Outstanding Question:**
- ⚠️ **UNCERTAINTY Q7.1:** Does `onFileAddedToDropFolder` callback exist in MeshrabiyaApiImpl?

---

### 7.6 Add configureDropFolder() Method to DistributedStorageManager ✅ IMPLEMENT

**Location:** `DistributedStorageManager.kt`

**Target Implementation:**

```kotlin
// Add to DistributedStorageManager class:

/**
 * Configure drop folder for auto-sync.
 * Creates DropFolder configuration and starts FileObserver monitoring.
 * Per Clarification 11: Monitors recursively, max 2 levels deep.
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
        // Per Clarification 12: Triggers don't persist (empty list on startup)
        dropFolder = DropFolder(
            folderPath = folderPath,
            triggers = mutableListOf()
        )
        
        // Create "shared" subfolder if it doesn't exist (per Clarification 5)
        val sharedFolder = File(folderPath, "shared")
        if (!sharedFolder.exists()) {
            sharedFolder.mkdirs()
            println("INFO: Created 'shared' subfolder: ${sharedFolder.absolutePath}")
        }
        
        // Start FileObserver monitoring (per Clarification 11: max 2 levels)
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

**User Clarifications Applied:**
- Clarification 5: Create "shared" subfolder automatically
- Clarification 11: Max 2 levels deep monitoring
- Clarification 12: Empty triggers list on startup (not persisted)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Validates folder exists and is directory
- [ ] Stops existing monitoring before reconfiguring
- [ ] Creates DropFolder with empty triggers list (Clarification 12)
- [ ] Creates "shared" subfolder if it doesn't exist (Clarification 5)
- [ ] Starts FileObserver monitoring (max 2 levels - Clarification 11)
- [ ] Returns true on success, false on failure
- [ ] getDropFolderPath() returns current path or null
- [ ] getDropFolderTriggers() returns immutable copy
- [ ] Thread-safe (synchronized on triggers list)
- [ ] Handles exceptions gracefully
- [ ] Logs all operations

---

### 7.7 Update DistributedStorageClient.retrieveFile() Integration ✅ IMPLEMENT

**Location:** `DistributedStorageClient.kt` (modify existing method)

**Current State:** `retrieveFile()` writes file to storage, already has "shared" subfolder logic from Section 2.2

**Per Clarification 9:** No explicit ignore marking needed - "shared" subfolder is absolute exception

**Target Implementation:** Verify "shared" subfolder logic in retrieveFile() (already implemented in Section 2.2):

```kotlin
// In DistributedStorageClient.retrieveFile() method (from Section 2.2):
// This logic already implements Clarification 5 + Clarification 9

// determineFinalOutputPath() already redirects non-owned files to "shared" subfolder
// FileObserver checks isInSharedSubfolder() and skips processing
// No additional changes needed - clarifications already integrated
```

**Verification Checklist:**
- [ ] retrieveFile() uses determineFinalOutputPath() (Section 2.2)
- [ ] Files redirected to "shared" subfolder for non-owned files (Clarification 5)
- [ ] FileObserver skips "shared" subfolder (Clarification 9)
- [ ] No explicit ignore marking needed (refactored from v2)

**Notes:**
- Integration already complete via Section 2.2 implementation
- Clarification 9 refactored one-time ignore to absolute exception
- "shared" subfolder logic prevents auto-store loops

---

### 7.8 Add MeshrabiyaApi Methods for Drop Folder Configuration ✅ IMPLEMENT

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

### 7.9 Add MeshrabiyaApi Methods for Trigger Management ✅ IMPLEMENT

**Location:** `MeshrabiyaApi.kt` (interface) and `MeshrabiyaApiImpl.kt` (implementation)

**Target Implementation for Interface:**

```kotlin
// Add to MeshrabiyaApi.kt interface:

/**
 * Create or update a drop folder trigger.
 * Per Clarification 9: Fails if subPath is in "shared" subfolder.
 * 
 * @param subPath Relative path within drop folder
 * @param recipients List of recipient node IDs
 * @param callback Result callback (success=trigger ID, failure if "shared" subfolder)
 */
fun createDropFolderTrigger(subPath: String, recipients: List<String>, callback: (Result<Int>) -> Unit)

/**
 * Update existing drop folder trigger.
 * Per Clarification 9: Fails if new subPath is in "shared" subfolder.
 * 
 * @param triggerId Trigger ID to update
 * @param subPath New relative path (must be unique, not in "shared")
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
        
        // Per Clarification 12: Get next trigger ID (starts at 0, not persisted)
        val triggerId = nextDropFolderTriggerId++
        
        val trigger = StoreFileTrigger(
            id = triggerId,
            subPath = subPath,
            recipients = recipients
        )
        
        // Per Clarification 9: setStoreFileTrigger() will reject "shared" subfolder
        val success = manager.setStoreFileTrigger(trigger, delete = false)
        
        if (success) {
            callback(Result.success(triggerId))
        } else {
            callback(Result.failure(IllegalArgumentException("Failed to create trigger (may be in 'shared' subfolder): $subPath")))
        }
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
        
        // Per Clarification 9: setStoreFileTrigger() will reject "shared" subfolder
        val success = manager.setStoreFileTrigger(trigger, delete = false)
        
        if (success) {
            callback(Result.success(Unit))
        } else {
            callback(Result.failure(IllegalArgumentException("Failed to update trigger (may be in 'shared' subfolder): $subPath")))
        }
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
        
        val success = manager.setStoreFileTrigger(triggerToDelete, delete = true)
        
        if (success) {
            callback(Result.success(Unit))
        } else {
            callback(Result.failure(IllegalStateException("Failed to delete trigger: $triggerId")))
        }
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

// Add property to track next trigger ID (per Clarification 12: starts at 0):
private var nextDropFolderTriggerId: Int = 0
```

**Dependencies:**
- `StoreFileTrigger` data class (top-level per Clarification 10)
- Access: `distributedStorageManager.setStoreFileTrigger()` from 7.2
- Access: `distributedStorageManager.getDropFolderTriggers()` from 7.6

**User Clarifications Applied:**
- Clarification 9: Reject "shared" subfolder in create/update
- Clarification 10: StoreFileTrigger top-level (accessible from MeshrabiyaApiImpl)
- Clarification 12: nextDropFolderTriggerId starts at 0 (not persisted)

**Verification Checklist:**
- [ ] Compiles without errors (interface + implementation)
- [ ] createDropFolderTrigger() generates unique IDs (starts at 0 - Clarification 12)
- [ ] createDropFolderTrigger() rejects "shared" subfolder (Clarification 9)
- [ ] updateDropFolderTrigger() updates existing trigger
- [ ] updateDropFolderTrigger() rejects "shared" subfolder (Clarification 9)
- [ ] deleteDropFolderTrigger() finds trigger by ID
- [ ] deleteDropFolderTrigger() calls setStoreFileTrigger with delete=true
- [ ] getDropFolderTriggers() converts to DTO list
- [ ] Handles null manager gracefully
- [ ] Invokes onOperationFailed on exceptions
- [ ] nextDropFolderTriggerId increments correctly

---

### 7.10 Complex File/Folder/Trigger Interaction Rules (COMPREHENSIVE) ✅ IMPLEMENT

**Per Clarification 13:** Implement comprehensive interaction rules for files, folders, and triggers

**Rules to Implement:**

**Rule 1: storeFile on file in triggered folder → FAIL gracefully**
- Already implemented in Section 2.1 (storeFile() implementation)
- Checks if file is in drop folder with active trigger
- Fails with error if trigger found

**Rule 2: updateFileAccess works regardless of location**
- No special handling needed - method works normally
- Can add/remove recipients via updateFileAccess

**Rule 3: Files inherit recipients from triggers (cannot be deleted via updateFileAccess)**
- Requires tracking inherited recipients separately from explicit recipients
- updateFileAccess cannot remove inherited recipients

**Target Implementation:** Add to DistributedStorageManager:

```kotlin
// Add to DistributedStorageManager class:

/**
 * Get inherited recipients for a file based on drop folder triggers.
 * Per Clarification 13: Files in triggered folders inherit recipients.
 * 
 * @param filePath Absolute path to file
 * @return List of inherited recipient node IDs
 */
fun getInheritedRecipients(filePath: String): List<String> {
    return matchTriggersForFile(filePath)
}

/**
 * Check if a file has trigger-inherited recipients.
 * Per Clarification 13: Inherited recipients cannot be removed via updateFileAccess.
 * 
 * @param filePath Absolute path to file
 * @return True if file has inherited recipients
 */
fun hasInheritedRecipients(filePath: String): Boolean {
    return matchTriggersForFile(filePath).isNotEmpty()
}
```

**Rule 4: Moving file within drop folder recalculates inherited recipients**
- FileObserver will detect file creation at new location
- matchTriggersForFile() automatically recalculates based on new path
- Old file reference should be updated with new recipients

**Rule 5: Removing file from drop folder removes triggers**
- FileObserver cannot detect file removal outside drop folder
- Future enhancement: UI callback to prompt user for mesh delete

**Target Implementation:** Document limitation:

```kotlin
// In FileObserver setup (Section 7.5):
// Note: FileObserver does NOT detect file removal via external means
// If file is moved/deleted outside the app, triggers remain but file is gone
// Future enhancement: Add onFileRemovedFromDropFolder callback for UI prompts
```

**Verification Checklist:**
- [ ] Rule 1: storeFile check implemented (Section 2.1)
- [ ] Rule 2: updateFileAccess works normally (no special handling)
- [ ] Rule 3: getInheritedRecipients() method added
- [ ] Rule 3: hasInheritedRecipients() method added
- [ ] Rule 4: FileObserver recalculates on file creation (automatic)
- [ ] Rule 5: Limitation documented (future UI callback needed)

**Outstanding Question:**
- ⚠️ **UNCERTAINTY Q7.2:** How to prevent updateFileAccess from removing inherited recipients?
- Current approach: Document inherited recipients via getInheritedRecipients()
- Full solution requires updateFileAccess integration

**Answer:  on the owner client node, when an UpdateAccessEvent is genrated by the user on a given file, we have a mechanism to create the inherited recipients.  We can use that list to always enforce they are present in the request and that any removal of access ignores attempts to remove recipients in the inherited list.  in the UI to update access those recipients would appear as disabled selections.

---

## SECTION 7 OUTSTANDING QUESTIONS

### Q7.1: onFileAddedToDropFolder Callback
- **Question:** Does `onFileAddedToDropFolder` callback property exist in MeshrabiyaApiImpl?
- **Impact:** FileObserver invokes callback after successful auto-store
- **Source:** Section 7.5 implementation
- **Status:** UNRESOLVED
- **Priority:** MEDIUM
- **Fallback:** Remove callback invocation if property doesn't exist

**Answer: yes it exists.

### Q7.2: updateFileAccess Integration with Inherited Recipients
- **Question:** How to prevent updateFileAccess from removing trigger-inherited recipients?
- **Impact:** Rule 3 (Clarification 13) requires inherited recipients protection
- **Source:** Section 7.10 implementation
- **Status:** UNRESOLVED - INTEGRATION COMPLEXITY
- **Priority:** HIGH
- **Current Approach:** Provide getInheritedRecipients() method for UI awareness
- **Full Solution:** Requires updateFileAccess method modification

**Added to:** MESHRABIYA_API_v3_OUTSTANDING_QUESTIONS.md

**Answer: on the owner client node, when an UpdateAccessEvent is genrated by the user on a given file, we have a mechanism to create the inherited recipients.  We can use that list to always enforce they are present in the request and that any removal of access ignores attempts to remove recipients in the inherited list.  in the UI to update access those recipients would appear as disabled selections.

---

## SECTION 7 VERIFICATION SUMMARY

**Total Subsections:** 10  
**Total New Methods in DistributedStorageManager:** 10+  
**Total New Methods in MeshrabiyaApi:** 7  
**Total Data Classes:** 2 (DropFolder, StoreFileTrigger)  
**Total Properties:** 3

**User Clarifications Applied:** 8 (Clarifications 3, 5, 8, 9, 10, 11, 12, 13)

**Completion Checklist:**
- [ ] All 10 subsections implemented
- [ ] DropFolder and StoreFileTrigger data classes added (top-level - Clarification 10)
- [ ] DistributedStorageManager methods added (10+ methods)
- [ ] MeshrabiyaApi methods added (7 methods)
- [ ] FileObserver setup and recursive monitoring functional (max 2 levels - Clarification 11)
- [ ] "shared" subfolder absolute exception (Clarification 9)
- [ ] Left-bound trigger matching (Clarification 8)
- [ ] Triggers don't persist (Clarification 12)
- [ ] Trigger CRUD operations functional
- [ ] Auto-store triggers on file creation
- [ ] Complex file/folder/trigger rules implemented (Clarification 13)
- [ ] All outstanding questions documented

---

**END OF PART 3**

**Next:** Part 4 will cover Section 8 (OrbotMeshService Refactoring), NEW Sections 9-10 (Task Status, Integration), Imports, Tracking
