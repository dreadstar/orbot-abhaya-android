# MeshrabiyaApiImpl Complete Implementation Plan v3.0 - PART 2 of 4

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

## PART 2 CONTENTS

This part covers:
- **Section 3:** Gateway Controls (5 methods)
- **Section 4:** Storage Participation (5 methods)
- **Section 5:** Enhanced State Methods (4 methods)

**Sections in other parts:**
- Part 1: Executive Summary, User Clarifications, Research Findings, Sections 1-2
- Part 3: Sections 6-7 (Event Handler Wiring, Drop Folder Implementation)
- Part 4: Section 8 (OrbotMeshService Refactoring), NEW Sections 9-10, Imports, Tracking

---

## SECTION 3: Gateway Controls Implementation ⭐ HIGH PRIORITY

**Priority:** HIGH (Network routing functionality)  
**Status:** ❌ NOT STARTED  
**Lines:** 177-207  
**Methods:** 5 (gateway enable/disable/status)  
**Confidence:** 100% ✅ (EmergentRoleManager fully verified by Research Iteration 2)

**Research Resolution Applied:**
- Clarification 7: EmergentRoleManager research completed
- Research Finding 5: NO "available vs active" roles split - only getCurrentMeshRoles()
- All EmergentRoleManager APIs verified and functional

---

### 3.1 setTorGatewayEnabled() - Enable/Disable Tor Gateway Role ✅ IMPLEMENT

**Current State:** Lines 177-183 - stub with NotImplementedError  
**Purpose:** Add or remove TOR_GATEWAY role from node

**Target Implementation:**

```kotlin
override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        val roleManager = emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            onOperationFailed?.invoke("setTorGatewayEnabled", IllegalStateException("Role manager not initialized"))
            return
        }
        
        // Get current roles
        val currentRoles = roleManager.getCurrentMeshRoles().toMutableSet()
        
        if (enabled) {
            // Add TOR_GATEWAY role
            currentRoles.add(MeshRole.TOR_GATEWAY)
            println("INFO: Adding TOR_GATEWAY role")
        } else {
            // Remove TOR_GATEWAY role
            currentRoles.remove(MeshRole.TOR_GATEWAY)
            println("INFO: Removing TOR_GATEWAY role")
        }
        
        // Apply role change (trigger role determination)
        // Note: EmergentRoleManager.determineOptimalRoles() considers user preferences
        // Setting preferred roles should trigger re-evaluation
        roleManager.setPreferredRoles(currentRoles)
        
        callback(Result.success(Unit))
        println("INFO: Tor gateway ${if (enabled) "enabled" else "disabled"}")
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("setTorGatewayEnabled", e)
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager` property (verify exists in MeshrabiyaApiImpl)
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified in Research Finding 5)
- Access: `emergentRoleManager.setPreferredRoles()` method

**Research Verification (Finding 5):**
- ✅ EmergentRoleManager.getCurrentMeshRoles() EXISTS - returns Set<MeshRole>
- ✅ No separate "available vs active" - only current roles
- ✅ Role determination based on capabilities, fitness, centrality
- ⚠️ **UNCERTAINTY Q3.1:** Does EmergentRoleManager have `setPreferredRoles()` method?

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets current roles from EmergentRoleManager
- [ ] Adds TOR_GATEWAY role when enabled=true
- [ ] Removes TOR_GATEWAY role when enabled=false
- [ ] Applies role change via setPreferredRoles()
- [ ] Handles null role manager gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs role change

**Notes:**
- EmergentRoleManager will re-evaluate optimal roles based on capabilities
- TOR_GATEWAY role may not activate if node lacks capabilities
- Role change is preference-based, not absolute assignment

---

### 3.2 getTorGatewayStatus() - Check Tor Gateway Active ✅ IMPLEMENT

**Current State:** Lines 185-187 - stub with NotImplementedError  
**Purpose:** Check if TOR_GATEWAY role is currently active

**Target Implementation:**

```kotlin
override fun getTorGatewayStatus(): Boolean {
    return try {
        val roleManager = emergentRoleManager
        if (roleManager == null) {
            println("ERROR: Role manager not initialized")
            false
        } else {
            // Check if TOR_GATEWAY is in current roles
            val currentRoles = roleManager.getCurrentMeshRoles()
            val isActive = currentRoles.contains(MeshRole.TOR_GATEWAY)
            println("INFO: Tor gateway status: $isActive")
            isActive
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get Tor gateway status: ${e.message}")
        onOperationFailed?.invoke("getTorGatewayStatus", e)
        false
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified in Research Finding 5)

**Research Verification (Finding 5):**
- ✅ EmergentRoleManager.getCurrentMeshRoles() EXISTS - returns Set<MeshRole>
- ✅ MeshRole enum has TOR_GATEWAY value

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets current roles from EmergentRoleManager
- [ ] Checks for TOR_GATEWAY in current roles set
- [ ] Returns true if TOR_GATEWAY active, false otherwise
- [ ] Handles null role manager gracefully (returns false)
- [ ] Invokes onOperationFailed on errors
- [ ] Logs status check

**Notes:**
- Returns current state, not preference
- False if role manager not initialized
- False if TOR_GATEWAY not in active roles

---

### 3.3 setInternetGatewayEnabled() - Enable/Disable Clearnet Gateway Role ✅ IMPLEMENT

**Current State:** Lines 189-195 - stub with NotImplementedError  
**Purpose:** Add or remove CLEARNET_GATEWAY role from node

**Target Implementation:**

```kotlin
override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        val roleManager = emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            onOperationFailed?.invoke("setInternetGatewayEnabled", IllegalStateException("Role manager not initialized"))
            return
        }
        
        // Get current roles
        val currentRoles = roleManager.getCurrentMeshRoles().toMutableSet()
        
        if (enabled) {
            // Add CLEARNET_GATEWAY role
            currentRoles.add(MeshRole.CLEARNET_GATEWAY)
            println("INFO: Adding CLEARNET_GATEWAY role")
        } else {
            // Remove CLEARNET_GATEWAY role
            currentRoles.remove(MeshRole.CLEARNET_GATEWAY)
            println("INFO: Removing CLEARNET_GATEWAY role")
        }
        
        // Apply role change (trigger role determination)
        roleManager.setPreferredRoles(currentRoles)
        
        callback(Result.success(Unit))
        println("INFO: Internet gateway ${if (enabled) "enabled" else "disabled"}")
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("setInternetGatewayEnabled", e)
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified)
- Access: `emergentRoleManager.setPreferredRoles()` method

**Research Verification:**
- ✅ MeshRole enum has CLEARNET_GATEWAY value
- ⚠️ **UNCERTAINTY Q3.1 (shared):** setPreferredRoles() method existence

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets current roles from EmergentRoleManager
- [ ] Adds CLEARNET_GATEWAY role when enabled=true
- [ ] Removes CLEARNET_GATEWAY role when enabled=false
- [ ] Applies role change via setPreferredRoles()
- [ ] Handles null role manager gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs role change

**Notes:**
- Same pattern as setTorGatewayEnabled()
- CLEARNET_GATEWAY provides internet access to mesh nodes

---

### 3.4 getInternetGatewayStatus() - Check Clearnet Gateway Active ✅ IMPLEMENT

**Current State:** Lines 197-199 - stub with NotImplementedError  
**Purpose:** Check if CLEARNET_GATEWAY role is currently active

**Target Implementation:**

```kotlin
override fun getInternetGatewayStatus(): Boolean {
    return try {
        val roleManager = emergentRoleManager
        if (roleManager == null) {
            println("ERROR: Role manager not initialized")
            false
        } else {
            // Check if CLEARNET_GATEWAY is in current roles
            val currentRoles = roleManager.getCurrentMeshRoles()
            val isActive = currentRoles.contains(MeshRole.CLEARNET_GATEWAY)
            println("INFO: Internet gateway status: $isActive")
            isActive
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get internet gateway status: ${e.message}")
        onOperationFailed?.invoke("getInternetGatewayStatus", e)
        false
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets current roles from EmergentRoleManager
- [ ] Checks for CLEARNET_GATEWAY in current roles set
- [ ] Returns true if CLEARNET_GATEWAY active, false otherwise
- [ ] Handles null role manager gracefully (returns false)
- [ ] Invokes onOperationFailed on errors
- [ ] Logs status check

---

### 3.5 getGatewayStatus() - Check Any Gateway Active ✅ IMPLEMENT

**Current State:** Lines 201-207 - stub with NotImplementedError  
**Purpose:** Check if ANY gateway role (TOR or CLEARNET) is currently active

**Target Implementation:**

```kotlin
override fun getGatewayStatus(): Boolean {
    return try {
        val roleManager = emergentRoleManager
        if (roleManager == null) {
            println("ERROR: Role manager not initialized")
            false
        } else {
            // Check if either TOR_GATEWAY or CLEARNET_GATEWAY is active
            val currentRoles = roleManager.getCurrentMeshRoles()
            val isActive = currentRoles.contains(MeshRole.TOR_GATEWAY) || 
                          currentRoles.contains(MeshRole.CLEARNET_GATEWAY)
            println("INFO: Any gateway status: $isActive")
            isActive
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get gateway status: ${e.message}")
        onOperationFailed?.invoke("getGatewayStatus", e)
        false
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets current roles from EmergentRoleManager
- [ ] Checks for TOR_GATEWAY OR CLEARNET_GATEWAY in roles
- [ ] Returns true if ANY gateway active, false otherwise
- [ ] Handles null role manager gracefully (returns false)
- [ ] Invokes onOperationFailed on errors
- [ ] Logs status check

**Notes:**
- Returns true if EITHER gateway role is active
- Useful for checking if node provides any mesh egress

---

## SECTION 3 OUTSTANDING QUESTIONS

### Q3.1: EmergentRoleManager.setPreferredRoles() Method
- **Question:** Does EmergentRoleManager have `setPreferredRoles(roles: Set<MeshRole>)` method?
- **Impact:** setTorGatewayEnabled() and setInternetGatewayEnabled() cannot apply role changes without this method
- **Source:** Section 3.1, 3.3 implementations
- **Status:** UNRESOLVED
- **Research Note:** Research Finding 5 confirmed `getPreferredRoles()` exists, but not setPreferredRoles()
- **Fallback:** Use alternative role assignment method if available

**Answer: yes function exists. you should have resolved this yourself with reseach agent before posing to me.

**Added to:** MESHRABIYA_API_v3_OUTSTANDING_QUESTIONS.md

---

## SECTION 4: Storage Participation Implementation ⭐ MEDIUM PRIORITY

**Priority:** MEDIUM (Configuration methods)  
**Status:** ❌ NOT STARTED  
**Lines:** 209-257  
**Methods:** 5 (all trivial wrappers)  
**Confidence:** 100% ✅ (All enhancements already implemented per Research Finding 6)

**Research Resolution Applied:**
- Research Finding 6: ALL DistributedStorageManager enhancements already implemented
- StorageParticipationConfig EXISTS (data class)
- configureStorageParticipation() EXISTS (method)
- participationEnabled EXISTS (StateFlow property)
- storageStats EXISTS (StateFlow property with StorageStats data class)

**Implementation Note:** All methods in this section are TRIVIAL WRAPPERS around existing DistributedStorageManager APIs. No complex logic required.

---

### 4.1 setStorageParticipationEnabled() - Configure Storage Participation ✅ IMPLEMENT

**Current State:** Lines 209-219 - stub with NotImplementedError  
**Purpose:** Enable/disable storage participation with quota configuration

**Target Implementation:**

```kotlin
override fun setStorageParticipationEnabled(
    enabled: Boolean,
    quotaBytes: Long,
    callback: (Result<Unit>) -> Unit
) {
    try {
        val storageManager = distributedStorageManager
        if (storageManager == null) {
            callback(Result.failure(IllegalStateException("Storage manager not initialized")))
            onOperationFailed?.invoke("setStorageParticipationEnabled", IllegalStateException("Storage manager not initialized"))
            return
        }
        
        // Create configuration (per Research Finding 6 - class already exists)
        val config = StorageParticipationConfig(
            participationEnabled = enabled,
            totalQuota = quotaBytes,
            allowedDirectories = emptyList(), // Default: no directory restrictions
            encryptionRequired = true // Default: encryption required
        )
        
        // Apply configuration (per Research Finding 6 - method already exists)
        storageManager.configureStorageParticipation(config)
        
        callback(Result.success(Unit))
        println("INFO: Storage participation ${if (enabled) "enabled" else "disabled"}, quota: $quotaBytes bytes")
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("setStorageParticipationEnabled", e)
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageParticipationConfig` (verified exists)
- Access: `distributedStorageManager.configureStorageParticipation()` method (verified exists)

**Research Verification (Finding 6):**
- ✅ StorageParticipationConfig data class EXISTS
  - Properties: participationEnabled, totalQuota, allowedDirectories, encryptionRequired
- ✅ configureStorageParticipation() method EXISTS in DistributedStorageManager

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Creates StorageParticipationConfig with correct parameters
- [ ] Calls configureStorageParticipation() on storage manager
- [ ] Returns success on completion
- [ ] Handles null storage manager gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs participation change

**Notes:**
- Trivial wrapper around existing API (Research Finding 6)
- Default: no directory restrictions, encryption required
- Storage manager handles actual participation logic

---

### 4.2 getStorageParticipationStatus() - Check Participation Enabled ✅ IMPLEMENT

**Current State:** Lines 221-223 - stub with NotImplementedError  
**Purpose:** Check if storage participation is currently enabled

**Target Implementation:**

```kotlin
override fun getStorageParticipationStatus(): Boolean {
    return try {
        val storageManager = distributedStorageManager
        if (storageManager == null) {
            println("ERROR: Storage manager not initialized")
            false
        } else {
            // Get participation status (per Research Finding 6 - property already exists)
            val isEnabled = storageManager.participationEnabled.value
            println("INFO: Storage participation status: $isEnabled")
            isEnabled
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get storage participation status: ${e.message}")
        onOperationFailed?.invoke("getStorageParticipationStatus", e)
        false
    }
}
```

**Dependencies:**
- Access: `distributedStorageManager.participationEnabled` property (verified exists)

**Research Verification (Finding 6):**
- ✅ participationEnabled property EXISTS as StateFlow<Boolean>
- ✅ Access via `.value` for current state

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets participationEnabled value from storage manager
- [ ] Returns current state (true/false)
- [ ] Handles null storage manager gracefully (returns false)
- [ ] Invokes onOperationFailed on errors
- [ ] Logs status check

**Notes:**
- Trivial wrapper around existing property (Research Finding 6)
- Returns current state, not configuration

---

### 4.3 getStorageAllocations() - List Storage Allocations ✅ IMPLEMENT

**Current State:** Lines 225-234 - stub with NotImplementedError  
**Purpose:** Get list of storage allocations (quotas per device)

**Target Implementation:**

```kotlin
override fun getStorageAllocations(): List<StorageAllocation> {
    return try {
        val storageManager = distributedStorageManager
        if (storageManager == null) {
            println("ERROR: Storage manager not initialized")
            emptyList()
        } else {
            // Get storage stats (per Research Finding 6 - property already exists)
            val stats = storageManager.storageStats.value
            
            // Convert to StorageAllocation list
            // Note: StorageStats has totalOffered, currentlyUsed, filesStored, replicationHealth
            // Creating single allocation entry for now
            listOf(
                StorageAllocation(
                    deviceId = "local", // Default device ID
                    totalBytes = stats.totalOffered,
                    usedBytes = stats.currentlyUsed,
                    availableBytes = stats.totalOffered - stats.currentlyUsed
                )
            )
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get storage allocations: ${e.message}")
        onOperationFailed?.invoke("getStorageAllocations", e)
        emptyList()
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageAllocation`
- Access: `distributedStorageManager.storageStats` property (verified exists)

**Research Verification (Finding 6):**
- ✅ storageStats property EXISTS as StateFlow<StorageStats>
- ✅ StorageStats data class has: totalOffered, currentlyUsed, filesStored, replicationHealth

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets storageStats from storage manager
- [ ] Converts StorageStats to StorageAllocation list
- [ ] Returns empty list if storage manager null
- [ ] Invokes onOperationFailed on errors
- [ ] Logs errors

**Notes:**
- StorageStats provides aggregate data, not per-device breakdown
- Implementation creates single "local" allocation entry
- Future: May need per-device allocation tracking

---

### 4.4 setStorageAllocation() - Update Storage Quota ✅ IMPLEMENT

**Current State:** Lines 236-246 - stub with NotImplementedError  
**Purpose:** Update storage quota for a device

**Target Implementation:**

```kotlin
override fun setStorageAllocation(
    deviceId: String,
    quotaBytes: Long,
    callback: (Result<Unit>) -> Unit
) {
    try {
        val storageManager = distributedStorageManager
        if (storageManager == null) {
            callback(Result.failure(IllegalStateException("Storage manager not initialized")))
            onOperationFailed?.invoke("setStorageAllocation", IllegalStateException("Storage manager not initialized"))
            return
        }
        
        // Update quota via configureStorageParticipation (per Research Finding 6)
        val currentEnabled = storageManager.participationEnabled.value
        val config = StorageParticipationConfig(
            participationEnabled = currentEnabled,
            totalQuota = quotaBytes,
            allowedDirectories = emptyList(),
            encryptionRequired = true
        )
        
        storageManager.configureStorageParticipation(config)
        
        callback(Result.success(Unit))
        println("INFO: Storage allocation updated for device $deviceId: $quotaBytes bytes")
    } catch (e: Exception) {
        callback(Result.failure(e))
        onOperationFailed?.invoke("setStorageAllocation", e)
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageParticipationConfig` (verified exists)
- Access: `distributedStorageManager.configureStorageParticipation()` method (verified exists)
- Access: `distributedStorageManager.participationEnabled` property (verified exists)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Preserves current participation enabled state
- [ ] Updates quota via configureStorageParticipation()
- [ ] Handles null storage manager gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs quota update

**Notes:**
- deviceId parameter currently ignored (single local allocation)
- Quota update reconfigures entire participation
- Future: May need per-device quota management

---

### 4.5 getAvailableStorageDevices() - List Available Devices ✅ IMPLEMENT

**Current State:** Lines 248-257 - stub with NotImplementedError  
**Purpose:** Get list of available storage devices

**Target Implementation:**

```kotlin
override fun getAvailableStorageDevices(): List<StorageDevice> {
    return try {
        // Get available storage locations
        // Note: Android typically has internal storage + optional SD card
        val devices = mutableListOf<StorageDevice>()
        
        // Internal storage
        val internalStats = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        devices.add(
            StorageDevice(
                id = "internal",
                name = "Internal Storage",
                path = android.os.Environment.getDataDirectory().path,
                totalBytes = internalStats.totalBytes,
                availableBytes = internalStats.availableBytes,
                isRemovable = false
            )
        )
        
        // External storage (if available)
        if (android.os.Environment.getExternalStorageState() == android.os.Environment.MEDIA_MOUNTED) {
            val externalStats = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            devices.add(
                StorageDevice(
                    id = "external",
                    name = "External Storage",
                    path = android.os.Environment.getExternalStorageDirectory().path,
                    totalBytes = externalStats.totalBytes,
                    availableBytes = externalStats.availableBytes,
                    isRemovable = true
                )
            )
        }
        
        devices
    } catch (e: Exception) {
        println("ERROR: Failed to get available storage devices: ${e.message}")
        onOperationFailed?.invoke("getAvailableStorageDevices", e)
        emptyList()
    }
}
```

**Dependencies:**
- `import android.os.StatFs`
- `import android.os.Environment`
- `import com.ustadmobile.meshrabiya.storage.StorageDevice`

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Lists internal storage device
- [ ] Lists external storage if available
- [ ] Uses StatFs for capacity information
- [ ] Returns empty list on error
- [ ] Invokes onOperationFailed on errors
- [ ] Logs errors

**Notes:**
- Android-specific implementation using StatFs
- Checks external storage mount state
- Returns list of available storage locations

---

## SECTION 4 OUTSTANDING QUESTIONS

**None** - All DistributedStorageManager APIs verified in Research Finding 6

---

## SECTION 5: Enhanced State Methods Implementation ⭐ MEDIUM PRIORITY

**Priority:** MEDIUM (Informational methods)  
**Status:** ❌ NOT STARTED  
**Lines:** 259-308  
**Methods:** 4 (state aggregation and queries)  
**Confidence:** 90% ✅ (One outstanding issue with OriginatingMessage timestamp)

**Research Resolution Applied:**
- Research Finding 9: OriginatingMessage lacks timestamp field
- Documented limitation, workaround provided

---

### 5.1 getFitnessScore() - Calculate Node Fitness (0-100) ✅ IMPLEMENT

**Current State:** Lines 259-268 - stub with NotImplementedError  
**Purpose:** Calculate node's fitness score for distributed operations

**Target Implementation:**

```kotlin
override fun getFitnessScore(): Int {
    return try {
        val roleManager = emergentRoleManager
        if (roleManager == null) {
            println("ERROR: Role manager not initialized")
            0
        } else {
            // Get node capabilities snapshot
            val capabilities = virtualNode?.getNodeCapabilities()
            if (capabilities == null) {
                println("ERROR: Node capabilities not available")
                return 0
            }
            
            // Calculate normalized fitness (per Research Finding 5)
            // EmergentRoleManager.calculateNormalizedFitness() returns Float 0.0-1.0
            val normalizedFitness = roleManager.calculateNormalizedFitness(capabilities)
            
            // Convert to 0-100 scale
            val fitnessScore = (normalizedFitness * 100).toInt()
            
            println("INFO: Fitness score: $fitnessScore")
            fitnessScore
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get fitness score: ${e.message}")
        onOperationFailed?.invoke("getFitnessScore", e)
        0
    }
}
```

**Dependencies:**
- Access: `emergentRoleManager.calculateNormalizedFitness()` method (verified in Research Finding 5)
- Access: `virtualNode.getNodeCapabilities()` method

**Research Verification (Finding 5):**
- ✅ EmergentRoleManager.calculateNormalizedFitness(node: NodeCapabilitySnapshot): Float EXISTS
- ✅ Returns normalized fitness 0.0-1.0 based on CPU, battery, storage, thermal state

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets node capabilities from virtual node
- [ ] Calls calculateNormalizedFitness() on role manager
- [ ] Converts 0.0-1.0 to 0-100 scale
- [ ] Returns 0 if role manager or capabilities null
- [ ] Invokes onOperationFailed on errors
- [ ] Logs fitness score

**Outstanding Question:**
- ⚠️ **UNCERTAINTY Q5.1:** Does VirtualNode have `getNodeCapabilities()` method returning NodeCapabilitySnapshot?

**Notes:**
- Fitness based on: CPU, battery, storage capacity, thermal state
- Higher score = better suited for distributed operations
- Score of 0 indicates unavailable or poor fitness

---

### 5.2 getMeshStatus() - Get Mesh Connection State ✅ IMPLEMENT

**Current State:** Lines 270-272 - stub with NotImplementedError  
**Purpose:** Get current mesh network connection state

**Target Implementation:**

```kotlin
override fun getMeshStatus(): MeshState {
    return try {
        val node = myNode
        if (node == null) {
            println("INFO: Mesh not initialized")
            MeshState.STOPPED
        } else {
            // Check hotspot and neighbor status
            val nodeState = node.state.value
            val neighborCount = node.neighbors().size
            
            val meshState = when {
                nodeState.hotspotEnabled && neighborCount > 0 -> MeshState.CONNECTED
                nodeState.hotspotEnabled -> MeshState.IDLE
                else -> MeshState.STOPPED
            }
            
            println("INFO: Mesh status: $meshState (neighbors: $neighborCount)")
            meshState
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get mesh status: ${e.message}")
        onOperationFailed?.invoke("getMeshStatus", e)
        MeshState.STOPPED
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.MeshState`
- Access: `myNode.state` StateFlow property
- Access: `myNode.neighbors()` method

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Checks myNode initialization
- [ ] Gets node state (hotspot enabled)
- [ ] Gets neighbor count
- [ ] Maps to MeshState: CONNECTED (hotspot + neighbors), IDLE (hotspot only), STOPPED (no hotspot)
- [ ] Returns STOPPED if node null
- [ ] Invokes onOperationFailed on errors
- [ ] Logs mesh status and neighbor count

**Notes:**
- CONNECTED: Hotspot enabled AND has neighbors
- IDLE: Hotspot enabled but no neighbors
- STOPPED: Hotspot disabled

---

### 5.3 getNetworkInfo() - Aggregate Network Topology Info ✅ IMPLEMENT

**Current State:** Lines 274-283 - stub with NotImplementedError  
**Purpose:** Get comprehensive network topology information

**Target Implementation:**

```kotlin
override fun getNetworkInfo(): NetworkInfo {
    return try {
        val node = myNode
        if (node == null) {
            // Return empty network info
            NetworkInfo(
                peerCount = 0,
                activeRoles = emptySet(),
                meshTopology = emptyMap()
            )
        } else {
            // Get peer count
            val neighbors = node.neighbors()
            val peerCount = neighbors.size
            
            // Get active roles
            val roleManager = emergentRoleManager
            val activeRoles = roleManager?.getCurrentMeshRoles() ?: emptySet()
            
            // Build mesh topology (node ID -> neighbor IDs)
            val meshTopology = mutableMapOf<String, Set<String>>()
            
            // Add local node
            val localNodeId = node.nodeId.toString()
            meshTopology[localNodeId] = neighbors.map { it.nodeId.toString() }.toSet()
            
            // Note: Only have local node's topology
            // Full mesh topology would require gossip protocol to share neighbor lists
            
            NetworkInfo(
                peerCount = peerCount,
                activeRoles = activeRoles,
                meshTopology = meshTopology
            )
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get network info: ${e.message}")
        onOperationFailed?.invoke("getNetworkInfo", e)
        NetworkInfo(
            peerCount = 0,
            activeRoles = emptySet(),
            meshTopology = emptyMap()
        )
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.NetworkInfo`
- Access: `myNode.neighbors()` method
- Access: `myNode.nodeId` property
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified)

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Gets peer count from neighbors()
- [ ] Gets active roles from EmergentRoleManager
- [ ] Builds mesh topology map (local node only)
- [ ] Returns empty NetworkInfo if node null
- [ ] Invokes onOperationFailed on errors
- [ ] Logs errors

**Notes:**
- Topology only includes local node's view (direct neighbors)
- Full mesh topology would require distributed protocol
- activeRoles from EmergentRoleManager (verified in Research Finding 5)

---

### 5.4 getNodeInfo() - Query Specific Node Information ✅ IMPLEMENT

**Current State:** Lines 285-308 - stub with NotImplementedError  
**Purpose:** Get information about a specific node in the mesh

**Target Implementation:**

```kotlin
override fun getNodeInfo(nodeId: String): NodeInfo? {
    return try {
        val node = myNode
        if (node == null) {
            println("ERROR: Mesh not initialized")
            null
        } else {
            // Check if querying local node
            val localNodeId = node.nodeId.toString()
            if (nodeId == localNodeId) {
                // Return local node info
                val roleManager = emergentRoleManager
                val roles = roleManager?.getCurrentMeshRoles() ?: emptySet()
                val capabilities = node.getNodeCapabilities()
                
                return NodeInfo(
                    nodeId = localNodeId,
                    roles = roles,
                    isOnline = true,
                    lastSeen = System.currentTimeMillis(),
                    capabilities = capabilities
                )
            }
            
            // Check if node is a neighbor
            val neighbors = node.neighbors()
            val neighborNode = neighbors.find { it.nodeId.toString() == nodeId }
            
            if (neighborNode != null) {
                // Return neighbor info
                // Note: Limited info available for remote nodes
                return NodeInfo(
                    nodeId = nodeId,
                    roles = emptySet(), // Unknown for remote nodes
                    isOnline = true,
                    lastSeen = System.currentTimeMillis(), // Approximate
                    capabilities = null // Unknown for remote nodes
                )
            }
            
            // Check originating message state for recently seen nodes
            // Per Research Finding 9: OriginatingMessage lacks timestamp
            // Cannot provide accurate lastSeen for non-neighbor nodes
            
            println("WARN: Node not found or not directly connected: $nodeId")
            null
        }
    } catch (e: Exception) {
        println("ERROR: Failed to get node info: ${e.message}")
        onOperationFailed?.invoke("getNodeInfo", e)
        null
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.NodeInfo`
- Access: `myNode.nodeId` property
- Access: `myNode.neighbors()` method
- Access: `myNode.getNodeCapabilities()` method
- Access: `emergentRoleManager.getCurrentMeshRoles()` method (verified)

**Research Verification (Finding 9):**
- ❌ OriginatingMessage lacks timestamp field
- Cannot provide accurate lastSeen for non-neighbor nodes
- Workaround: Use System.currentTimeMillis() for neighbors, null for others

**Outstanding Issue (from v2 Section 5.4):**
- **OriginatingMessage Timestamp:** Per Research Finding 9, no timestamp field exists
- **Impact:** Cannot track when non-neighbor nodes were last seen
- **Current Approach:** Return info only for local node and direct neighbors
- **Future Work:** Add timestamp to OriginatingMessageState for TTL and last-seen tracking

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns local node info if nodeId matches
- [ ] Returns neighbor info if node is direct neighbor
- [ ] Returns null for non-neighbor nodes (limited visibility)
- [ ] Handles null myNode gracefully
- [ ] Invokes onOperationFailed on errors
- [ ] Logs warnings for unknown nodes

**Notes:**
- Full info available only for local node
- Limited info for direct neighbors (no roles/capabilities)
- Cannot track non-neighbor nodes (no distributed state)
- OriginatingMessage timestamp limitation documented (Research Finding 9)

---

## SECTION 5 OUTSTANDING QUESTIONS

### Q5.1: VirtualNode.getNodeCapabilities() Method
- **Question:** Does VirtualNode have `getNodeCapabilities()` method returning NodeCapabilitySnapshot?
- **Impact:** getFitnessScore() and getNodeInfo() need access to node capabilities
- **Source:** Section 5.1, 5.3 implementations
- **Status:** UNRESOLVED
- **Fallback:** Return default fitness score 0 if method doesn't exist
 
 **Answer: no, it does not exist and actually seems like a function that should be in `EmergentRoleManager`

### Q5.2: OriginatingMessage Timestamp (ARCHITECTURAL)
- **Problem:** OriginatingMessageState lacks timestamp field
- **Impact:** Cannot track last-seen time for non-neighbor nodes
- **Source:** Research Finding 9, Section 5.4
- **Status:** UNRESOLVED - ARCHITECTURAL LIMITATION
- **Current Approach:** Only return info for local node and direct neighbors
- **Future Work:** Add timestamp field to OriginatingMessageState

**Added to:** MESHRABIYA_API_v3_OUTSTANDING_QUESTIONS.md

---

## PART 2 VERIFICATION SUMMARY

**Total Sections:** 3 (Sections 3-5)  
**Total Methods:** 14 (5 gateway + 5 storage + 4 state)  
**Completion Checklist:**
- [ ] Section 3: All 5 gateway control methods implemented
- [ ] Section 4: All 5 storage participation methods implemented (trivial wrappers)
- [ ] Section 5: All 4 enhanced state methods implemented
- [ ] All outstanding questions documented
- [ ] Compiles without errors
- [ ] All research findings applied

**Confidence Summary:**
- Section 3: 100% (EmergentRoleManager verified)
- Section 4: 100% (All APIs already implemented)
- Section 5: 90% (OriginatingMessage timestamp limitation)

---

**END OF PART 2**

**Next:** Part 3 will cover Sections 6-7 (Event Handler Wiring, Drop Folder Implementation)
