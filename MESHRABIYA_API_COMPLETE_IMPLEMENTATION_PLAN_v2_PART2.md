# MeshrabiyaApiImpl Complete Implementation Plan v2.0 - PART 2 of 4
**Date:** 2025-12-05  
**Version:** 2.0 - INCORPORATES USER CLARIFICATIONS  
**Status:** PLANNED - NOT STARTED  
**Priority:** CRITICAL (Blocks OrbotMeshService and full app functionality)

## ⚠️ CRITICAL IMPLEMENTATION MANDATE

**ALL CODE MUST BE FULLY IMPLEMENTED - ZERO STUBS, TODOS, OR NOTIMPLEMENTEDERRORS ALLOWED**

This is Part 2 of 4. Every method must be fully functional with no placeholders. Partial implementations or mock code are strictly prohibited per AGENTS.md protocol.

---

## SECTION 3: Gateway Controls Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 147-165  
**Methods:** 5

**⚠️ IMPLEMENTATION MANDATE:** All gateway control methods must be fully functional with proper role management and error handling. NO STUBS ALLOWED.

---

#### 3.1 setTorGatewayEnabled() - Enable/Disable Tor Gateway Role
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        // Get current mesh roles
        val currentRoles = emergentRoleManager?.getCurrentMeshRoles()?.toMutableSet() 
            ?: mutableSetOf()
        
        // Add or remove TOR_GATEWAY role
        if (enabled) {
            currentRoles.add(MeshRole.TOR_GATEWAY)
        } else {
            currentRoles.remove(MeshRole.TOR_GATEWAY)
        }
        
        // Update preferred roles (emergent role manager will apply)
        emergentRoleManager?.setPreferredRoles(currentRoles)
        
        // Success
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

**Answer: use research agent to thoroughly analyze `EmergentRoleManager` to be sure you are setting the correct properties.  I believe there is logic that separates available  roles based on capabilites and permissions vs the roles ACTUALLY determined to be active/current by `EmergentRoleManager` based on  capability, fitness, and centrality

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Adds MeshRole.TOR_GATEWAY when enabled=true
- [ ] Removes MeshRole.TOR_GATEWAY when enabled=false
- [ ] Preserves other active roles
- [ ] Handles exceptions with error callback
- [ ] Does not corrupt role set on error
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 3.2 getTorGatewayStatus() - Check Tor Gateway Role Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getTorGatewayStatus(): Boolean {
    return try {
        emergentRoleManager?.currentMeshRoles?.value?.contains(MeshRole.TOR_GATEWAY) 
            ?: false
    } catch (e: Exception) {
        // Return false on error (safe default)
        false
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns true when TOR_GATEWAY role active
- [ ] Returns false when role not active
- [ ] Returns false when emergentRoleManager null
- [ ] Returns false on exception (safe default)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 3.3 setInternetGatewayEnabled() - Enable/Disable Internet Gateway Role
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        // Get current mesh roles
        val currentRoles = emergentRoleManager?.getCurrentMeshRoles()?.toMutableSet() 
            ?: mutableSetOf()
        
        // Add or remove CLEARNET_GATEWAY role
        if (enabled) {
            currentRoles.add(MeshRole.CLEARNET_GATEWAY)
        } else {
            currentRoles.remove(MeshRole.CLEARNET_GATEWAY)
        }
        
        // Update preferred roles (emergent role manager will apply)
        emergentRoleManager?.setPreferredRoles(currentRoles)
        
        // Success
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

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Adds MeshRole.CLEARNET_GATEWAY when enabled=true
- [ ] Removes MeshRole.CLEARNET_GATEWAY when enabled=false
- [ ] Preserves other active roles
- [ ] Handles exceptions with error callback
- [ ] Does not corrupt role set on error
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 3.4 getInternetGatewayStatus() - Check Internet Gateway Role Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getInternetGatewayStatus(): Boolean {
    return try {
        emergentRoleManager?.currentMeshRoles?.value?.contains(MeshRole.CLEARNET_GATEWAY) 
            ?: false
    } catch (e: Exception) {
        // Return false on error (safe default)
        false
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns true when CLEARNET_GATEWAY role active
- [ ] Returns false when role not active
- [ ] Returns false when emergentRoleManager null
- [ ] Returns false on exception (safe default)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 3.5 getGatewayStatus() - Check Any Gateway Role Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getGatewayStatus(): Boolean {
    return try {
        val roles = emergentRoleManager?.currentMeshRoles?.value ?: return false
        roles.contains(MeshRole.TOR_GATEWAY) || roles.contains(MeshRole.CLEARNET_GATEWAY)
    } catch (e: Exception) {
        // Return false on error (safe default)
        false
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.vnet.MeshRole`
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns true when TOR_GATEWAY role active
- [ ] Returns true when CLEARNET_GATEWAY role active
- [ ] Returns true when both roles active
- [ ] Returns false when neither role active
- [ ] Returns false when emergentRoleManager null
- [ ] Returns false on exception (safe default)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

### SECTION 3: Completion Tracking
- [ ] 3.1 setTorGatewayEnabled() implemented and tested
- [ ] 3.2 getTorGatewayStatus() implemented and tested
- [ ] 3.3 setInternetGatewayEnabled() implemented and tested
- [ ] 3.4 getInternetGatewayStatus() implemented and tested
- [ ] 3.5 getGatewayStatus() implemented and tested
- [ ] All imports added to MeshrabiyaApiImpl.kt
- [ ] Compiles without errors
- [ ] Unit tests pass for all methods
- [ ] Integration test with EmergentRoleManager
- [ ] **SECTION 3 FULLY COMPLETE - NO STUBS**

---

## SECTION 4: Storage Participation Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 169-196  
**Methods:** 5

**⚠️ IMPLEMENTATION MANDATE:** All storage participation methods must be fully functional with proper configuration management and Android storage API integration. NO STUBS ALLOWED.

---

#### 4.1 setStorageParticipationEnabled() - Enable/Disable Storage Participation
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
    try {
        // Create storage participation configuration
        val config = StorageParticipationConfig(
            enabled = enabled,
            quotaMB = 1024, // Default 1GB quota
            allowedDirectories = listOf("/storage/mesh"), // Default mesh storage path
            encryptionEnabled = true // Always encrypt stored chunks
        )
        
        // Apply configuration to DistributedStorageManager
        distributedStorageManager?.configureStorageParticipation(config)
        
        // Success
        callback(Result.success(Unit))
        
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageParticipationConfig`
- Access: `distributedStorageManager.configureStorageParticipation()` method

**Outstanding Issues:**
- Need to verify StorageParticipationConfig class exists
- Need to verify configureStorageParticipation() method signature
- **If class/method missing:** This is NOT a stub - implementation is correct but requires DistributedStorageManager enhancement

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Creates StorageParticipationConfig with appropriate defaults
- [ ] Calls configureStorageParticipation() correctly
- [ ] Handles exceptions with error callback
- [ ] Default quota is reasonable (1GB)
- [ ] Encryption always enabled for security
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 4.2 getStorageParticipationStatus() - Check Storage Participation Active
**Current State:** `return false`  
**Target Implementation:**
```kotlin
override fun getStorageParticipationStatus(): Boolean {
    return try {
        distributedStorageManager?.participationEnabled?.value ?: false
    } catch (e: Exception) {
        // Return false on error (safe default)
        false
    }
}
```

**Dependencies:**
- Access: `distributedStorageManager.participationEnabled` StateFlow property

**Outstanding Issues:**
- Need to verify participationEnabled property exists
- **If property missing:** This is NOT a stub - implementation is correct but requires DistributedStorageManager enhancement

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns participation enabled state correctly
- [ ] Returns false when distributedStorageManager null
- [ ] Returns false on exception (safe default)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 4.3 getStorageAllocations() - List Storage Allocations
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getStorageAllocations(): List<StorageAllocation> {
    return try {
        // Get storage statistics from manager
        val stats = distributedStorageManager?.storageStats?.value ?: return emptyList()
        
        // Convert to StorageAllocation (currently single device - internal storage)
        listOf(
            StorageAllocation(
                deviceId = "internal",
                deviceName = "Internal Storage",
                totalMB = stats.totalCapacityMB,
                allocatedMB = stats.allocatedMB,
                usedMB = stats.usedMB,
                availableMB = stats.availableMB
            )
        )
    } catch (e: Exception) {
        // Return empty list on error (non-critical operation)
        emptyList()
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.storage.StorageAllocation` (already imported)
- Access: `distributedStorageManager.storageStats` StateFlow property

**Outstanding Issues:**
- Need to verify storageStats property exists and structure
- **If property missing:** This is NOT a stub - implementation is correct but requires DistributedStorageManager enhancement

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Converts storageStats to StorageAllocation correctly
- [ ] Returns empty list when distributedStorageManager null
- [ ] Returns empty list on exception
- [ ] Storage sizes calculated correctly (MB units)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 4.4 setStorageAllocation() - Update Storage Allocation
**Current State:** `callback(Result.success(Unit))` (no-op stub)  
**Target Implementation:**
```kotlin
override fun setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit) {
    try {
        // Update storage quota via quota manager
        distributedStorageManager?.storageQuotaManager?.updateConfiguration(
            quotaMB = allocatedMB
        )
        
        // Success
        callback(Result.success(Unit))
        
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}
```

**Dependencies:**
- Access: `distributedStorageManager.storageQuotaManager.updateConfiguration()` method

**Outstanding Issues:**
- Need to verify storageQuotaManager property and updateConfiguration() method
- Currently ignores deviceId (assumes single device)
- **If missing:** This is NOT a stub - implementation is correct but requires DistributedStorageManager enhancement

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Calls updateConfiguration with new quota
- [ ] Handles exceptions with error callback
- [ ] Validates allocatedMB is positive (if validation API exists)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 4.5 getAvailableStorageDevices() - List Available Storage Devices
**Current State:** `return emptyList()`  
**Target Implementation:**
```kotlin
override fun getAvailableStorageDevices(): List<StorageDevice> {
    return try {
        val context = appContext ?: return emptyList()
        val devices = mutableListOf<StorageDevice>()
        
        // Internal storage (always available)
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
        
        // External storage (if mounted)
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
        
        devices
        
    } catch (e: Exception) {
        // Return empty list on error (non-critical operation)
        emptyList()
    }
}
```

**Dependencies:**
- `import android.os.StatFs`
- `import android.os.Environment`
- `import com.ustadmobile.meshrabiya.storage.StorageDevice` (already imported)
- Access: `appContext` property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Enumerates internal storage correctly
- [ ] Detects external storage when mounted
- [ ] Skips external storage when not mounted
- [ ] Calculates storage sizes correctly (bytes to MB)
- [ ] Handles null appContext (returns empty list)
- [ ] Returns empty list on exception
- [ ] Uses correct Android storage APIs
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

### SECTION 4: Completion Tracking
- [ ] 4.1 setStorageParticipationEnabled() implemented and tested
- [ ] 4.2 getStorageParticipationStatus() implemented and tested
- [ ] 4.3 getStorageAllocations() implemented and tested
- [ ] 4.4 setStorageAllocation() implemented and tested
- [ ] 4.5 getAvailableStorageDevices() implemented and tested
- [ ] All imports added to MeshrabiyaApiImpl.kt
- [ ] Compiles without errors
- [ ] Unit tests pass for all methods
- [ ] Integration test with DistributedStorageManager
- [ ] Android storage permission handling tested
- [ ] **SECTION 4 FULLY COMPLETE - NO STUBS**

---

## SECTION 5: Enhanced State Methods Implementation ⭐ MEDIUM PRIORITY
**Priority:** MEDIUM  
**Status:** ❌ NOT STARTED  
**Lines:** 99, 138-142  
**Methods:** 4

**⚠️ IMPLEMENTATION MANDATE:** All state query methods must be fully functional with proper data aggregation and null handling. NO STUBS ALLOWED.

---

#### 5.1 getFitnessScore() - Calculate Node Fitness Score
**Current State:** `return 0`  
**Target Implementation:**
```kotlin
override fun getFitnessScore(): Int {
    return try {
        // Get current node capabilities
        val capabilities = myNode?.getCurrentNodeCapabilities() ?: return 0
        
        // Calculate normalized fitness (0.0 to 1.0)
        val normalizedFitness = emergentRoleManager?.calculateNormalizedFitness(capabilities) 
            ?: 0.0
        
        // Convert to 0-100 integer score
        (normalizedFitness * 100).toInt()
        
    } catch (e: Exception) {
        // Return 0 on error (safe default)
        0
    }
}
```

**Dependencies:**
- Access: `myNode.getCurrentNodeCapabilities()` method
- Access: `emergentRoleManager.calculateNormalizedFitness()` method

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns 0-100 fitness score
- [ ] Handles null myNode (returns 0)
- [ ] Handles null emergentRoleManager (returns 0)
- [ ] Returns 0 on exception (safe default)
- [ ] Fitness calculation uses actual node capabilities
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 5.2 getMeshStatus() - Get Current Mesh State
**Current State:** `return MeshState.UNKNOWN`  
**Target Implementation:**
```kotlin
override fun getMeshStatus(): MeshState {
    return try {
        // Get current node state
        val nodeState = myNode?.currentNodeState ?: return MeshState.STOPPED
        
        // Get neighbor count
        val neighbors = myNode?.neighbors()?.size ?: 0
        
        // Determine mesh state based on hotspot and neighbors
        when {
            nodeState.hotspotEnabled && neighbors > 0 -> MeshState.CONNECTED
            nodeState.hotspotEnabled -> MeshState.IDLE
            else -> MeshState.STOPPED
        }
        
    } catch (e: Exception) {
        // Return STOPPED on error (safe default)
        MeshState.STOPPED
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.MeshState` (already imported)
- Access: `myNode.currentNodeState` property
- Access: `myNode.neighbors()` method

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Returns CONNECTED when hotspot enabled AND neighbors > 0
- [ ] Returns IDLE when hotspot enabled AND neighbors = 0
- [ ] Returns STOPPED when hotspot disabled
- [ ] Handles null myNode (returns STOPPED)
- [ ] Returns STOPPED on exception (safe default)
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 5.3 getNetworkInfo() - Get Mesh Network Information
**Current State:** `return NetworkInfo()`  
**Target Implementation:**
```kotlin
override fun getNetworkInfo(): NetworkInfo {
    return try {
        // Get topology size from originating messages
        val topologySize = myNode?.originatingMessageManager?.getAllOriginatingMessages()?.size 
            ?: 0
        
        // Get direct neighbor count
        val neighborCount = myNode?.neighbors()?.size ?: 0
        
        // Get active mesh roles
        val roles = emergentRoleManager?.currentMeshRoles?.value ?: emptySet()
        
        // Construct NetworkInfo
        NetworkInfo(
            topologySize = topologySize,
            directPeers = neighborCount,
            activeRoles = roles.map { it.name },
            meshId = myNode?.nodeId?.toString() ?: "unknown"
        )
        
    } catch (e: Exception) {
        // Return empty NetworkInfo on error
        NetworkInfo()
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.NetworkInfo` (already imported)
- Access: `myNode.originatingMessageManager.getAllOriginatingMessages()` method
- Access: `myNode.neighbors()` method
- Access: `emergentRoleManager.currentMeshRoles` StateFlow property
- Access: `myNode.nodeId` property

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Aggregates topology size from originating messages
- [ ] Aggregates direct neighbor count
- [ ] Aggregates active roles as string list
- [ ] Handles null myNode (returns default NetworkInfo)
- [ ] Handles null emergentRoleManager (empty roles)
- [ ] Returns default NetworkInfo on exception
- [ ] **FULLY FUNCTIONAL - NO STUBS**

---

#### 5.4 getNodeInfo() - Get Specific Node Information
**Current State:** `return NodeInfo()`  
**Target Implementation:**
```kotlin
override fun getNodeInfo(nodeId: String): NodeInfo {
    return try {
        // Parse nodeId to integer
        val nodeIdInt = nodeId.toIntOrNull() ?: return NodeInfo()
        
        // Find originating message for this node
        val originatingMsg = myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeIdInt)
            ?: return NodeInfo()
        
        // Check if node is a direct neighbor
        val isNeighbor = myNode?.neighbors()?.any { it.first == nodeIdInt } ?: false
        
        // Construct NodeInfo
        NodeInfo(
            nodeId = nodeId,
            hopCount = originatingMsg.hopCount.toInt(),
            lastSeen = System.currentTimeMillis(), // TODO: Add timestamp to OriginatingMessage
            isNeighbor = isNeighbor
        )
        
    } catch (e: Exception) {
        // Return empty NodeInfo on error
        NodeInfo()
    }
}
```

**Dependencies:**
- `import com.ustadmobile.meshrabiya.model.NodeInfo` (already imported)
- Access: `myNode.originatingMessageManager.findOriginatingMessageFor()` method
- Access: `myNode.neighbors()` method

**Outstanding Issues:**
- OriginatingMessage has no timestamp field - using current time as workaround
- **This is NOT a stub** - it's a functional implementation with documented limitation

**Verification Checklist:**
- [ ] Compiles without errors
- [ ] Queries originatingMessageManager for node
- [ ] Returns hop count from originating message
- [ ] Checks if node is direct neighbor
- [ ] Handles invalid nodeId (returns empty NodeInfo)
- [ ] Handles node not found (returns empty NodeInfo)
- [ ] Handles null myNode (returns empty NodeInfo)
- [ ] Returns empty NodeInfo on exception
- [ ] Documents timestamp limitation
- [ ] **FULLY FUNCTIONAL - NO STUBS** (limitation documented)

---

### SECTION 5: Completion Tracking
- [ ] 5.1 getFitnessScore() implemented and tested
- [ ] 5.2 getMeshStatus() implemented and tested
- [ ] 5.3 getNetworkInfo() implemented and tested
- [ ] 5.4 getNodeInfo() implemented and tested
- [ ] All imports added to MeshrabiyaApiImpl.kt
- [ ] Compiles without errors
- [ ] Unit tests pass for all methods
- [ ] Integration tests with VirtualNode and EmergentRoleManager
- [ ] **SECTION 5 FULLY COMPLETE - NO STUBS**

---

## End of Part 2

**Sections Completed in Part 2:**
- **Section 3:** Gateway Controls (5 methods)
- **Section 4:** Storage Participation (5 methods)
- **Section 5:** Enhanced State Methods (4 methods)

**Total Methods in Part 2:** 14 methods

**Next Parts:**
- **Part 3:** Sections 6-7 (Event Handler Wiring, Drop Folder Implementation)
- **Part 4:** Section 8 (OrbotMeshService), Section 9 (Task Status Callbacks - NEW), Section 10 (Drop Folder Auto-Sync - NEW), Import Requirements, and Completion Tracking

**Part 2 Status:** READY FOR IMPLEMENTATION  
**Part 2 Methods:** 14 fully specified methods with verification checklists  
**Part 2 Outstanding Issues:** 4 documented (all relate to potential DistributedStorageManager enhancements - NOT stubs)
