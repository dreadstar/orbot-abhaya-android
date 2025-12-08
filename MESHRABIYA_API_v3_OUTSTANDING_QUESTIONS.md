# MeshrabiyaApi v3.0 Implementation - Outstanding Questions

**Date:** 2025-12-05  
**Status:** ACTIVE - Questions consolidated from v3 plan development  
**Purpose:** Track all uncertainties requiring resolution before/during implementation

---

## QUESTION TRACKING

**Total Questions:** 15 (4 from Part 1, 3 from Part 2, 2 from Part 3, 3 from Part 4)  
**Resolved:** 0  
**Remaining:** 15

---

## SECTION 1: COMPUTE/TASK API

### Q1.1: LocalComputeTaskRequest Properties
- **Question:** Does LocalComputeTaskRequest have `priority` and `deadline` properties?
- **Impact:** addTask() implementation assumes these properties exist
- **Source:** Section 1.1 implementation
- **Status:** UNRESOLVED
- **Fallback:** Remove priority/deadline if properties don't exist

### Q1.2: DistributedComputeClient.submitTask() Method
- **Question:** Does DistributedComputeClient have `submitTask()` method with LocalComputeTaskRequest parameter?
- **Impact:** addTask() cannot submit tasks without this method
- **Source:** Section 1.1 implementation
- **Status:** UNRESOLVED
- **Fallback:** Use alternative submission method if available

### Q1.3: DistributedComputeClient.getSupportedJobTypes() Method
- **Question:** Does DistributedComputeClient have `getSupportedJobTypes()` method?
- **Impact:** getJobTypes() will use fallback hardcoded list
- **Source:** Section 1.5 implementation
- **Status:** UNRESOLVED
- **Fallback:** Use hardcoded list (already implemented)

---

## SECTION 2: FILE OPERATIONS

### Q2.1: FileReference Dual Definition (ARCHITECTURAL)
- **Problem:** TWO incompatible FileReference definitions exist
  - Definition 1: `FileReference(fileId, fileName, sizeBytes, mimeType)` - MeshFile.kt
  - Definition 2: `FileReference(id, path, size)` - TaskResult.kt
- **Impact:** Ambiguity in which FileReference to use throughout codebase
- **Source:** Research Finding 8
- **Status:** UNRESOLVED - ARCHITECTURAL ISSUE
- **Current Approach:** Use Definition 1 (more comprehensive)
- **Future Work:** Unify FileReference classes into single definition

### Q2.2: FileReference Timestamp Missing
- **Problem:** Neither FileReference definition has timestamp field
- **Current Workaround:** Using `System.currentTimeMillis()` as fallback in getAllMeshFiles()
- **Better Solution:** Use `FileMetadata.createdAt` (verified exists in research)
- **Impact:** Timestamp in getAllMeshFiles() not accurate
- **Source:** Research Finding 8, Section 2.5
- **Status:** UNRESOLVED
- **Future Work:** Add timestamp to FileReference or migrate to FileMetadata

### Q2.3: ChunkReplicaTracker Access Method
- **Question:** Does DistributedStorageManager have `getChunkReplicaTracker()` accessor method?
- **Current Assumption:** Accessor exists or direct property access available
- **Impact:** getReplicaCount() needs access to chunk replica tracker
- **Source:** Section 2.5 implementation
- **Status:** UNRESOLVED
- **Fallback:** Use direct property access if accessor doesn't exist

### Q2.4: DistributedStorageClient.getFileMetadata() Method
- **Question:** Does DistributedStorageClient have `getFileMetadata(fileId)` method that returns FileMetadata with `owner` property?
- **Impact:** determineFinalOutputPath() needs ownership check for "shared" subfolder logic
- **Source:** Section 2.2 implementation
- **Status:** UNRESOLVED
- **Fallback:** Skip ownership check, always use requested path (loses "shared" subfolder feature)

---

## QUESTIONS ADDED FROM PART 2 (Sections 3-5)

### Q3.1: EmergentRoleManager.setPreferredRoles() Method
- **Question:** Does EmergentRoleManager have `setPreferredRoles(roles: Set<MeshRole>)` method?
- **Impact:** setTorGatewayEnabled() and setInternetGatewayEnabled() cannot apply role changes without this method
- **Source:** Section 3.1, 3.3 implementations
- **Status:** UNRESOLVED
- **Priority:** CRITICAL
- **Research Note:** Research Finding 5 confirmed `getPreferredRoles()` exists, but not setter
- **Fallback:** Use alternative role assignment method if available

### Q5.1: VirtualNode.getNodeCapabilities() Method
- **Question:** Does VirtualNode have `getNodeCapabilities()` method returning NodeCapabilitySnapshot?
- **Impact:** getFitnessScore() and getNodeInfo() need access to node capabilities
- **Source:** Section 5.1, 5.3 implementations
- **Status:** UNRESOLVED
- **Priority:** HIGH
- **Fallback:** Return default fitness score 0 if method doesn't exist

### Q5.2: OriginatingMessage Timestamp (ARCHITECTURAL)
- **Problem:** OriginatingMessageState lacks timestamp field
- **Impact:** Cannot track last-seen time for non-neighbor nodes in getNodeInfo()
- **Source:** Research Finding 9, Section 5.4
- **Status:** UNRESOLVED - ARCHITECTURAL LIMITATION
- **Priority:** MEDIUM
- **Current Approach:** Only return info for local node and direct neighbors
- **Future Work:** Add timestamp field to OriginatingMessageState

---

## SECTION 7: DROP FOLDER IMPLEMENTATION

### Q7.1: onFileAddedToDropFolder Callback Existence
- **Question:** Does `onFileAddedToDropFolder` callback property exist in MeshrabiyaApiImpl?
- **Impact:** FileObserver invokes callback after successful auto-store of files from drop folder
- **Source:** Part 3 Section 7.5 (FileObserver implementation)
- **Status:** UNRESOLVED
- **Priority:** MEDIUM
- **Code Context:**
  ```kotlin
  // In FileObserver.onEvent():
  withContext(Dispatchers.Main) {
      onFileAddedToDropFolder?.invoke(fileRef.fileId, file)
  }
  ```
- **Fallback:** Remove callback invocation if property doesn't exist; auto-store still functions
- **Resolution Needed:** Check MeshrabiyaApiImpl for callback property declaration

### Q7.2: updateFileAccess Integration with Inherited Recipients
- **Question:** How to prevent updateFileAccess from removing trigger-inherited recipients?
- **Impact:** Rule 3 (Clarification 13) requires inherited recipients cannot be removed via updateFileAccess
- **Source:** Part 3 Section 7.10 (Comprehensive file/folder/trigger rules)
- **Status:** UNRESOLVED - INTEGRATION COMPLEXITY
- **Priority:** HIGH
- **Code Context:**
  - Files in triggered folders inherit recipients from trigger configuration
  - Users can add explicit recipients via updateFileAccess
  - Inherited recipients should be protected (not removable)
- **Current Approach:** Provide `getInheritedRecipients()` method for UI awareness
- **Full Solution:** Requires updateFileAccess method modification to:
  1. Merge inherited recipients with explicit recipients
  2. Prevent removal of inherited recipients
  3. Only allow removal of explicit recipients
- **Fallback:** Document inherited recipients via getInheritedRecipients(), rely on UI to prevent conflicts

---

## SECTION 8: ORBOTMESHSERVICE REFACTORING

### Q8.1: Tor Proxy Integration Pattern
- **Question:** What is the correct Tor proxy integration pattern for Orbot?
- **Impact:** Gateway features may need Tor proxy routing when TOR_GATEWAY role is active
- **Source:** Part 4 Section 8.3
- **Status:** UNRESOLVED
- **Priority:** LOW (may not be needed immediately)
- **Code Context:**
  - Service may need to route mesh traffic through Tor daemon
  - May require SOCKS proxy configuration
  - Integration depends on existing Orbot Tor management architecture
- **Fallback:** Document integration point for future work; gateway features work without Tor initially
- **Resolution Needed:** Consult Orbot Tor management documentation

---

## SECTION 9: TASK STATUS CALLBACK SYSTEM

### Q9.1: MeshrabiyaApi Access in MeshEcosystemListener
- **Question:** How to access meshrabiyaApi instance in MeshEcosystemListener for callback invocation?
- **Impact:** Cannot invoke onTaskStatusUpdate callback without meshrabiyaApi reference
- **Source:** Part 4 Section 9.3 (Message routing)
- **Status:** UNRESOLVED
- **Priority:** MEDIUM
- **Code Context:**
  ```kotlin
  // In MeshEcosystemListener.routeMessage():
  when (message) {
      is TaskStatusUpdateMessage -> {
          meshrabiyaApi?.onTaskStatusUpdate?.invoke(...) // Need meshrabiyaApi reference
      }
  }
  ```
- **Fallback:** Pass meshrabiyaApi as constructor parameter to MeshEcosystemListener
- **Resolution Needed:** Check MeshEcosystemListener constructor/initialization

### Q9.2: Task Requester ID Tracking
- **Question:** How to track task requester ID for status update routing?
- **Impact:** Status updates cannot be sent to correct node without knowing who requested the task
- **Source:** Part 4 Section 9.4 (DistributedComputeClient status sending)
- **Status:** UNRESOLVED
- **Priority:** HIGH
- **Code Context:**
  - Task lifecycle methods need requesterId parameter
  - sendTaskStatusUpdate() needs recipientId for gossip routing
  - Current approach: Pass requesterId to all task methods
  - Full solution: Task metadata storage (taskId → requesterId mapping)
- **Current Approach:** Pass requesterId as parameter to acceptTask(), scheduleTask(), startTask(), etc.
- **Full Solution:** Implement task metadata store:
  ```kotlin
  private val taskRequesters = ConcurrentHashMap<String, String>() // taskId → requesterId
  ```
- **Fallback:** Use current approach (parameter passing) until metadata store implemented

---

## RESOLUTION PRIORITY

**CRITICAL (Blocks Implementation):**
- Q1.2: submitTask() method signature
- Q2.1: FileReference unification (architectural)
- Q3.1: setPreferredRoles() method (gateway controls)

**HIGH (Impacts Features):**
- Q2.4: getFileMetadata() for ownership check
- Q2.3: ChunkReplicaTracker access
- Q5.1: getNodeCapabilities() method
- Q7.2: Inherited recipients protection (integration complexity)
- Q9.2: Task requester ID tracking

**MEDIUM (Has Workarounds):**
- Q1.1: LocalComputeTaskRequest properties
- Q1.3: getSupportedJobTypes() method
- Q2.2: FileReference timestamp
- Q5.2: OriginatingMessage timestamp (architectural)
- Q7.1: onFileAddedToDropFolder callback
- Q9.1: MeshrabiyaApi access in listener

**LOW (Future Enhancements):**
- Q8.1: Tor proxy integration pattern

---

**This document will be updated as Parts 2-4 are created**
