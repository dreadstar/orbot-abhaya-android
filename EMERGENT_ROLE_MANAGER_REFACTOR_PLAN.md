# EmergentRoleManager Refactoring Plan - Corrected and Verified

**Date**: 2025-01-15  
**Status**: Ready for Execution  
**Approach**: Start from clean uncorrupted baseline, apply systematic deprecation

---

## CRITICAL CLARIFICATIONS INCORPORATED

### ✅ Verified Current Filesystem State

**Files Already Deprecated (.md)**:
- ✅ `EnhancedGossipMessage.kt.md` - Already moved to .md
- ✅ `MmcpGatewayAnnouncement.md` - Already moved to .md  
- ✅ `MmcpNodeAnnouncement.md` - Already moved to .md
- ✅ `MeshRole.kt.md` (mmcp package) - Already moved to .md

**Canonical Active Files (.kt)**:
- ✅ `MeshRole.kt` (vnet package) - Canonical location
- ✅ `MmcpMessage.kt` - Active base class (MmcpMessage.md also exists - error state)
- ✅ `EmergentRoleManager.kt` - Active but corrupted

**Clean Baseline Available**:
- ✅ `EmergentRoleManger_uncorrupt.md` - Clean version before corruption (964 lines)

### ✅ Strategy Correction

**OLD APPROACH** (caused errors):
- Edit corrupted EmergentRoleManager.kt in place
- Try to fix syntax errors while refactoring
- Referenced .kt files that were already .md

**NEW APPROACH** (clean and safe):
1. Copy clean `EmergentRoleManger_uncorrupt.md` → `EmergentRoleManager.kt`
2. Work from known-good baseline
3. Apply deprecation systematically
4. Verify filesystem state before each reference

---

## PHASE 1: BASELINE RESTORATION

### Step 1.1: Backup Current Corrupted File
```bash
cd /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet
cp EmergentRoleManager.kt EmergentRoleManager.kt.corrupted.bak
```

**Purpose**: Preserve corrupted version for forensics

---

### Step 1.2: Restore Clean Baseline
```bash
cd /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet
cp EmergentRoleManger_uncorrupt.md EmergentRoleManager.kt
```

**Result**: EmergentRoleManager.kt now contains clean uncorrupted code (964 lines)

**Verification**:
```bash
wc -l EmergentRoleManager.kt  # Should show 964 lines
head -1 EmergentRoleManager.kt  # Should show: package com.ustadmobile.meshrabiya.vnet
```

---

## PHASE 2: COMMENT OUT DEPRECATED ANNOUNCEMENT CODE

### Step 2.1: Comment Out MmcpGatewayAnnouncement Import (Line 20)

**Location**: Line 20 in clean baseline

**Current Code**:
```kotlin
import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement
```

**New Code**:
```kotlin
// DEPRECATED: MmcpGatewayAnnouncement - part of quorum/announcement false start
// File moved to MmcpGatewayAnnouncement.md (already deprecated on filesystem)
// import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement
```

---

### Step 2.2: Comment Out announceGatewayCapability() Method (Lines ~632-720)

**Location**: Search for `private suspend fun announceGatewayCapability()`

**Action**: Wrap entire method in block comment:
```kotlin
    // DEPRECATED: Gateway announcement functionality - part of quorum false start
    // Pre-announcement pattern unused, replaced by on-demand query
    // MmcpGatewayAnnouncement.md already deprecated on filesystem
    /*
    private suspend fun announceGatewayCapability() {
        val startTime = System.currentTimeMillis()
        
        try {
            safeLog(LogLevel.DETAILED, "Starting gateway capability announcement")
            
            // ... [entire method body lines ~632-720] ...
            
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            safeLog(LogLevel.ERROR, "Failed to announce gateway capability: ${e.message}", e)
        }
    }
    */
```

---

### Step 2.3: Comment Out estimateNetworkCapacity() Method (Lines ~722-762)

**Location**: Search for `private suspend fun estimateNetworkCapacity()`

**Action**: Wrap entire method in block comment:
```kotlin
    // DEPRECATED: Network capacity estimation - part of quorum/announcement false start
    // Used only by announceGatewayCapability() which is deprecated
    /*
    private suspend fun estimateNetworkCapacity(): MmcpGatewayAnnouncement.BandwidthCapacity {
        val startTime = System.currentTimeMillis()
        
        try {
            // ... [entire method body] ...
        } catch (e: Exception) {
            // ... [exception handling] ...
        }
    }
    */
```

---

### Step 2.4: Comment Out measureNetworkLatency() Method (Lines ~764-815)

**Location**: Search for `private suspend fun measureNetworkLatency()`

**Action**: Wrap entire method in block comment:
```kotlin
    // DEPRECATED: Network latency measurement - part of quorum/announcement false start
    // Used only by announceGatewayCapability() which is deprecated
    /*
    private suspend fun measureNetworkLatency(): MmcpGatewayAnnouncement.NetworkLatency {
        val startTime = System.currentTimeMillis()
        
        try {
            // ... [entire method body] ...
        } catch (e: Exception) {
            // ... [exception handling] ...
        }
    }
    */
```

---

### Step 2.5: Comment Out getSupportedProtocols() Method (Lines ~850-857)

**Location**: Search for `private fun getSupportedProtocols(gatewayType: MmcpGatewayAnnouncement.GatewayType)`

**Action**: Wrap entire method in block comment:
```kotlin
    // DEPRECATED: Protocol list generation - part of quorum/announcement false start
    // Used only by announceGatewayCapability() which is deprecated
    /*
    private fun getSupportedProtocols(gatewayType: MmcpGatewayAnnouncement.GatewayType): Set<String> {
        return when (gatewayType) {
            MmcpGatewayAnnouncement.GatewayType.CLEARNET -> setOf("HTTP", "HTTPS", "DNS", "FTP")
            MmcpGatewayAnnouncement.GatewayType.TOR -> setOf("HTTP", "HTTPS", "SOCKS5")
            MmcpGatewayAnnouncement.GatewayType.I2P -> setOf("HTTP", "HTTPS", "I2P")
        }
    }
    */
```

---

### Step 2.6: Remove Call to announceGatewayCapability() in handleGatewayRoleTransitions()

**Location**: Search for `scope.launch { announceGatewayCapability() }` inside `handleGatewayRoleTransitions()`

**Current Code** (approximate lines 530-551):
```kotlin
    private fun handleGatewayRoleTransitions(addedRoles: Set<MeshRole>, removedRoles: Set<MeshRole>) {
        try {
            // Check for gateway role additions
            when {
                MeshRole.TOR_GATEWAY in addedRoles -> {
                    activateGatewayRouting(GatewayMode.TOR_GATEWAY)
                    scope.launch { announceGatewayCapability() }  // <-- REMOVE THIS
                }
                MeshRole.CLEARNET_GATEWAY in addedRoles -> {
                    activateGatewayRouting(GatewayMode.CLEARNET_GATEWAY)
                    scope.launch { announceGatewayCapability() }  // <-- REMOVE THIS
                }
                MeshRole.I2P_GATEWAY in addedRoles -> {
                    activateGatewayRouting(GatewayMode.I2P_GATEWAY)
                    scope.launch { announceGatewayCapability() }  // <-- REMOVE THIS
                }
            }
            
            // Check for gateway role removals
            if (removedRoles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY) }) {
                deactivateGatewayRouting()
            }
            
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "EmergentRole: Failed to handle gateway role transitions: ${e.message}")
        }
    }
```

**New Code**:
```kotlin
    private fun handleGatewayRoleTransitions(addedRoles: Set<MeshRole>, removedRoles: Set<MeshRole>) {
        try {
            // Check for gateway role additions
            when {
                MeshRole.TOR_GATEWAY in addedRoles -> {
                    activateGatewayRouting(GatewayMode.TOR_GATEWAY)
                    // DEPRECATED: announceGatewayCapability() - part of quorum false start
                    // scope.launch { announceGatewayCapability() }
                }
                MeshRole.CLEARNET_GATEWAY in addedRoles -> {
                    activateGatewayRouting(GatewayMode.CLEARNET_GATEWAY)
                    // DEPRECATED: announceGatewayCapability() - part of quorum false start
                    // scope.launch { announceGatewayCapability() }
                }
                MeshRole.I2P_GATEWAY in addedRoles -> {
                    activateGatewayRouting(GatewayMode.I2P_GATEWAY)
                    // DEPRECATED: announceGatewayCapability() - part of quorum false start
                    // scope.launch { announceGatewayCapability() }
                }
            }
            
            // Check for gateway role removals
            if (removedRoles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY) }) {
                deactivateGatewayRouting()
            }
            
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "EmergentRole: Failed to handle gateway role transitions: ${e.message}")
        }
    }
```

---

### Step 2.7: Comment Out processNodeAnnouncement() Method

**Location**: Search for `fun processNodeAnnouncement(nodeId: String, meshRoles: Set<MeshRole>)`

**Action**: Wrap entire method in block comment:
```kotlin
    // DEPRECATED: processNodeAnnouncement() - part of quorum/announcement false start
    // Node announcements replaced by on-demand capability queries
    // MmcpNodeAnnouncement.md already deprecated on filesystem
    /*
    fun processNodeAnnouncement(nodeId: String, meshRoles: Set<MeshRole>) {
        val current = _meshIntelligence.value
        
        // Count active roles
        val activeGateways = if (meshRoles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY) }) {
            current.activeGateways + 1
        } else {
            current.activeGateways
        }
        
        // ... [rest of method body] ...
        
        _meshIntelligence.value = updated
        safeLog(LogLevel.DEBUG, "Updated mesh intelligence from node $nodeId: $updated")
    }
    */
```

**Result**: ~200 lines of announcement code safely deprecated

---

## PHASE 3: CREATE CANONICAL DEVICE METRICS TYPES

### Step 3.1: Create vnet/hardware/DeviceMetrics.kt

**File**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/hardware/DeviceMetrics.kt`

**Purpose**: Extract essential device capability types from deprecated EnhancedGossipMessage.kt.md

**Content**:
```kotlin
package com.ustadmobile.meshrabiya.vnet.hardware

import kotlinx.serialization.Serializable
import java.net.NetworkInterface
import kotlin.time.Duration

/**
 * Canonical device hardware capability metrics for emergent role management.
 * 
 * EXTRACTION NOTE: These types were originally defined in EnhancedGossipMessage.kt
 * which has been deprecated to .md. The gossip protocol was a false start, but these
 * device capability types are essential for compute task distribution, storage management,
 * and mesh role assignment.
 * 
 * Extracted: 2025-01-15
 * Original: mmcp/EnhancedGossipMessage.kt.md (now deprecated)
 * Used by: EmergentRoleManager.kt, compute service, storage service
 */

/**
 * Device resource capabilities for role assignment and task distribution.
 * 
 * Used to assess node fitness for mesh roles (STORAGE_NODE, COMPUTE_NODE, etc.)
 */
@Serializable
data class ResourceCapabilities(
    val availableCPU: Float, // 0.0-1.0 normalized
    val availableRAM: Long, // bytes
    val availableBandwidth: Long, // bytes/sec
    val storageOffered: Long, // bytes
    val batteryLevel: Int, // 0-100 percentage
    val thermalThrottling: Boolean,
    val powerState: PowerState,
    val networkInterfaces: Set<SerializableNetworkInterfaceInfo>
)

/**
 * Serializable representation of NetworkInterface for mesh communication.
 * Allows network interface information to be shared across mesh nodes.
 */
@Serializable
data class SerializableNetworkInterfaceInfo(
    val name: String,
    val displayName: String?,
    val mtu: Int,
    val isLoopback: Boolean,
    val supportsMulticast: Boolean,
    val isPointToPoint: Boolean,
    val isVirtual: Boolean,
    val interfaceAddresses: List<String>, // String representation of addresses
    val inetAddresses: List<String> // String representation of addresses
)

/**
 * Battery state information for power-aware role management.
 * 
 * Informs decisions about taking on power-intensive roles (COMPUTE_NODE, MESH_ROUTER).
 */
@Serializable
data class BatteryInfo(
    val level: Int, // 0-100 percentage
    val isCharging: Boolean,
    val estimatedTimeRemaining: Duration?,
    val temperatureCelsius: Int,
    val health: BatteryHealth,
    val chargingSource: ChargingSource?
)

/**
 * Battery health status.
 */
@Serializable
enum class BatteryHealth {
    GOOD, DEGRADED, POOR
}

/**
 * Charging source type.
 */
@Serializable
enum class ChargingSource {
    AC, USB, WIRELESS, UNKNOWN
}

/**
 * Device power state for role eligibility assessment.
 * 
 * Nodes in POWER_SAVE_MODE or BATTERY_CRITICAL should avoid compute/routing roles.
 */
@Serializable
enum class PowerState {
    PLUGGED_IN,       // AC power - can take any role
    BATTERY_HIGH,     // >70% - can take any role
    BATTERY_MEDIUM,   // 30-70% - normal operation
    BATTERY_LOW,      // 15-30% - avoid compute roles
    BATTERY_CRITICAL, // <15% - minimal participation only
    POWER_SAVE_MODE   // User-enabled power saving
}

/**
 * Thermal state for throttling-aware role management.
 * 
 * Nodes in THROTTLING or CRITICAL should drop compute roles to cool down.
 */
@Serializable
enum class ThermalState {
    COOL,       // <40°C - optimal for all roles
    WARM,       // 40-50°C - normal operation
    HOT,        // 50-60°C - reduce compute load
    THROTTLING, // 60-70°C - CPU throttled, avoid compute
    CRITICAL    // >70°C - emergency, minimal participation
}

/**
 * Helper function to convert NetworkInterface to serializable form.
 * 
 * Safely handles exceptions when querying interface properties.
 */
fun NetworkInterface.toSerializable(): SerializableNetworkInterfaceInfo {
    return SerializableNetworkInterfaceInfo(
        name = this.name,
        displayName = this.displayName,
        mtu = runCatching { this.mtu }.getOrDefault(-1),
        isLoopback = runCatching { this.isLoopback }.getOrDefault(false),
        supportsMulticast = runCatching { this.supportsMulticast() }.getOrDefault(false),
        isPointToPoint = runCatching { this.isPointToPoint }.getOrDefault(false),
        isVirtual = runCatching { this.isVirtual }.getOrDefault(false),
        interfaceAddresses = runCatching { 
            this.interfaceAddresses.map { it.toString() } 
        }.getOrDefault(emptyList()),
        inetAddresses = runCatching { 
            this.inetAddresses.toList().map { it.toString() } 
        }.getOrDefault(emptyList())
    )
}

/**
 * Helper function to get all network interfaces in serializable form.
 * 
 * Used by DeviceCapabilityManager to collect network interface information.
 */
fun getNetworkInterfaces(): Set<SerializableNetworkInterfaceInfo> {
    return try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.mapNotNull { ni ->
            try {
                ni.toSerializable()
            } catch (e: Exception) {
                null // Skip interfaces that fail to serialize
            }
        }?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }
}
```

**No Backup Needed**: This is a new file

---

## PHASE 4: UPDATE EMERGENT ROLE MANAGER IMPORTS

### Step 4.1: Update Device Capability Imports (Lines 15-19)

**Location**: Top of file, import section

**Current Code**:
```kotlin
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.mmcp.ResourceCapabilities
import com.ustadmobile.meshrabiya.mmcp.BatteryInfo
import com.ustadmobile.meshrabiya.mmcp.ThermalState
import com.ustadmobile.meshrabiya.mmcp.PowerState
import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement  // Line 20 - already commented in Step 2.1
```

**New Code**:
```kotlin
// UPDATED: MeshRole moved from mmcp to vnet package (canonical location)
import com.ustadmobile.meshrabiya.vnet.MeshRole

// UPDATED: Device capability types extracted to vnet/hardware/DeviceMetrics.kt
// Original location (mmcp/EnhancedGossipMessage.kt) deprecated to .md
import com.ustadmobile.meshrabiya.vnet.hardware.ResourceCapabilities
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryInfo
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryHealth
import com.ustadmobile.meshrabiya.vnet.hardware.ChargingSource
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState
import com.ustadmobile.meshrabiya.vnet.hardware.PowerState
import com.ustadmobile.meshrabiya.vnet.hardware.SerializableNetworkInterfaceInfo

// DEPRECATED: MmcpGatewayAnnouncement - part of quorum/announcement false start
// File moved to MmcpGatewayAnnouncement.md (already deprecated on filesystem)
// import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement
```

**Note**: This replaces lines 15-20 in the clean baseline

---

## PHASE 5: VERIFY COMPILATION

### Step 5.1: Clean Build Test

```bash
cd /Users/dreadstar/workspace/orbot-android
: > compile_check.log
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee compile_check.log
```

**Expected**: Clean compilation with 0 errors

---

### Step 5.2: Verify No Unresolved References

```bash
grep "Unresolved reference" compile_check.log | grep -E "(ResourceCapabilities|BatteryInfo|ThermalState|PowerState|MeshRole|MmcpGatewayAnnouncement)"
```

**Expected**: No matches (all types resolved)

---

### Step 5.3: Check Error Count

```bash
grep -c "error:" compile_check.log
```

**Expected**: 0

---

## PHASE 6: UPDATE OTHER FILES (IF NEEDED)

### Step 6.1: Find Files with Old mmcp Imports

```bash
cd /Users/dreadstar/workspace/orbot-android
grep -r "import com.ustadmobile.meshrabiya.mmcp.ResourceCapabilities" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.BatteryInfo" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.ThermalState" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.PowerState" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.MeshRole" --include="*.kt" Meshrabiya/
```

**Known Candidate**: `GatewayProtocolIntegrationTest.kt` (uses ResourceCapabilities)

---

### Step 6.2: Update Each File's Imports

For each file found (example: GatewayProtocolIntegrationTest.kt):

1. **Backup**:
   ```bash
   cp GatewayProtocolIntegrationTest.kt GatewayProtocolIntegrationTest.kt.bak
   ```

2. **Update imports**:
   ```kotlin
   // OLD
   import com.ustadmobile.meshrabiya.mmcp.ResourceCapabilities
   
   // NEW
   import com.ustadmobile.meshrabiya.vnet.hardware.ResourceCapabilities
   ```

3. **Test compilation**:
   ```bash
   ./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain
   ```

---

## PHASE 7: MARK ENHANCED GOSSIP MESSAGE AS DEPRECATED

### Step 7.1: Add Deprecation Header to EnhancedGossipMessage.kt.md

**File**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/EnhancedGossipMessage.kt.md`

**Action**: Add header comment at top of file:

```kotlin
/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DEPRECATION NOTICE - File Moved to .md (Not Active Code)
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This file is DEPRECATED and marked as documentation (.md extension).
 * 
 * REASON FOR DEPRECATION:
 * Enhanced Gossip Protocol was a false start - part of abandoned quorum-based
 * coordination approach. Pre-announcement patterns don't work well for mesh
 * networks with dynamic membership. Replaced by on-demand capability queries.
 * 
 * ESSENTIAL TYPES EXTRACTED:
 * The following types were essential and have been moved to canonical locations:
 * 
 * - ResourceCapabilities     → vnet/hardware/DeviceMetrics.kt
 * - BatteryInfo              → vnet/hardware/DeviceMetrics.kt
 * - BatteryHealth            → vnet/hardware/DeviceMetrics.kt
 * - ChargingSource           → vnet/hardware/DeviceMetrics.kt
 * - ThermalState             → vnet/hardware/DeviceMetrics.kt
 * - PowerState               → vnet/hardware/DeviceMetrics.kt
 * - SerializableNetworkInterfaceInfo → vnet/hardware/DeviceMetrics.kt
 * 
 * DEPRECATED CODE REMAINS:
 * - GossipMessageType enum (45 types including QUORUM_*, SERVICE_*)
 * - GossipPayload sealed class hierarchy
 * - NodeStatePayload, ServicePayload, I2PPayload, etc.
 * - Message creation utilities (createNodeAnnouncement, etc.)
 * - QuorumAction, QuorumType enums
 * 
 * DO NOT IMPORT FROM THIS FILE IN NEW CODE.
 * Use vnet/hardware/DeviceMetrics.kt for device capability types.
 * 
 * Deprecated: 2025-01-15
 * Original Purpose: Enhanced gossip protocol with quorum coordination
 * Replacement: On-demand query pattern, emergent role behavior
 * ═══════════════════════════════════════════════════════════════════════════
 */
package com.ustadmobile.meshrabiya.mmcp
```

---

## PHASE 8: DOCUMENTATION AND COMMIT

### Step 8.1: Update INTERIM_COMMIT_LOG.md

**File**: `/Users/dreadstar/workspace/orbot-android/INTERIM_COMMIT_LOG.md`

**Add Entry**:
```markdown
### 2025-01-15 - EmergentRoleManager Refactoring: Remove Deprecated Announcements

**Approach**: Restored from clean baseline, systematic deprecation

**Files Modified**:
- EmergentRoleManager.kt (restored from EmergentRoleManger_uncorrupt.md, then refactored)
- Created: vnet/hardware/DeviceMetrics.kt (canonical device capability types)
- Updated: EnhancedGossipMessage.kt.md (added deprecation header)

**Changes Made**:

1. **Baseline Restoration**:
   - Backed up corrupted EmergentRoleManager.kt → EmergentRoleManager.kt.corrupted.bak
   - Restored clean version from EmergentRoleManger_uncorrupt.md (964 lines)
   - Verified filesystem state (EnhancedGossipMessage already .md, MmcpGatewayAnnouncement already .md)

2. **Deprecated Announcement Code** (~200 lines):
   - Commented MmcpGatewayAnnouncement import (line 20)
   - Commented announceGatewayCapability() method (lines ~632-720)
   - Commented estimateNetworkCapacity() helper (lines ~722-762)
   - Commented measureNetworkLatency() helper (lines ~764-815)
   - Commented getSupportedProtocols() helper (lines ~850-857)
   - Commented processNodeAnnouncement() method (lines ~440-490)
   - Removed calls to announceGatewayCapability() in handleGatewayRoleTransitions()

3. **Created DeviceMetrics.kt**:
   - Package: vnet/hardware
   - Types extracted: ResourceCapabilities, BatteryInfo, ThermalState, PowerState
   - Enums: BatteryHealth, ChargingSource
   - Supporting: SerializableNetworkInterfaceInfo
   - Helpers: toSerializable(), getNetworkInterfaces()
   - Comprehensive documentation of extraction rationale

4. **Updated EmergentRoleManager.kt Imports**:
   - MeshRole: mmcp → vnet (canonical location)
   - ResourceCapabilities: mmcp → vnet/hardware
   - BatteryInfo: mmcp → vnet/hardware
   - ThermalState: mmcp → vnet/hardware
   - PowerState: mmcp → vnet/hardware
   - Added: BatteryHealth, ChargingSource, SerializableNetworkInterfaceInfo

**Testing**:
- Clean build: 0 errors
- All imports resolve to canonical locations
- No unresolved references to deprecated types

**Rationale**:
- Announcement functionality part of quorum false start (already deprecated to .md on filesystem)
- Pre-announcement assumes stable topology (doesn't work for dynamic mesh)
- On-demand query pattern superior for ephemeral connections
- Device metrics types needed across multiple packages (vnet, compute, storage)
- Starting from clean baseline prevented cascading syntax errors

**Lessons Learned**:
- Always verify filesystem state before making assumptions about .kt vs .md
- Restore from clean baseline instead of editing corrupted code
- Comment out deprecated code, don't delete (preserves history)
- Extract shared types before deprecating container files
- Systematic approach prevents errors: baseline → deprecate → extract → update imports
```

---

### Step 8.2: Create Today's KNOWLEDGE Doc

**File**: `/Users/dreadstar/workspace/orbot-android/KNOWLEDGE-01152025.md`

**Content**:
```markdown
# KNOWLEDGE - 01/15/2025

## EmergentRoleManager Refactoring - Clean Baseline Approach

### Problem Context

**Initial State**:
- EmergentRoleManager.kt corrupted with syntax errors (lines 755-762: orphaned code)
- ~150 lines of deprecated announcement code (MmcpGatewayAnnouncement)
- Imports from deprecated mmcp package
- Confusion about which files were .kt vs .md on filesystem

**Previous Approach Issues**:
- Attempted to edit corrupted file in place
- Referenced .kt files that were already .md on disk
- Syntax errors compounded during refactoring
- Assumptions about filesystem state were incorrect

### Solution: Clean Baseline Restoration

**Strategy**:
1. ✅ Verify actual filesystem state (list_dir to check .kt vs .md)
2. ✅ Start from known-good baseline (EmergentRoleManger_uncorrupt.md)
3. ✅ Apply deprecation systematically to clean code
4. ✅ Create canonical types before updating imports

**Execution**:
1. Backed up corrupted version
2. Copied clean uncorrupted .md → .kt
3. Commented out announcement code (~200 lines)
4. Created DeviceMetrics.kt with extracted types
5. Updated imports to canonical locations

### Filesystem State Verification

**Already Deprecated (.md)** at time of refactoring:
- `EnhancedGossipMessage.kt.md` - Gossip protocol false start
- `MmcpGatewayAnnouncement.md` - Gateway announcements
- `MmcpNodeAnnouncement.md` - Node announcements
- `MeshRole.kt.md` (mmcp package) - Over-engineered role list

**Canonical Active (.kt)**:
- `MeshRole.kt` (vnet package) - 7 roles (MESH_PARTICIPANT, STORAGE_NODE, etc.)
- `DeviceMetrics.kt` (vnet/hardware) - Device capability types
- `EmergentRoleManager.kt` (vnet) - Role management logic

### Architectural Insights

#### Why Announcements Failed

**False Assumption**: Pre-announce capabilities, other nodes query announcements
**Reality**: Mesh topology is dynamic, announcements become stale quickly
**Better Approach**: On-demand query when capability needed

**Example**:
- Old: Node announces "I'm a gateway" → stores in DHT → others query DHT
- New: Node needs gateway → broadcasts "who's a gateway?" → capable nodes respond

**Benefits of On-Demand**:
- Always fresh information (no stale announcements)
- Handles node departure gracefully (no cleanup needed)
- Works with ephemeral connections (no persistent state required)

#### Type Dependency Resolution

**ResourceCapabilities had 3 definitions**:
1. `mmcp/EnhancedGossipMessage.kt.md` - Most complete (battery, thermal, network)
2. `compute/mesh/ClusterState.kt` - Simplified (CPU, RAM, storage, GPU/NPU flags)
3. `vnet/hardware/DeviceMetrics.kt` - **NEW CANONICAL** (extracted from #1)

**Decision Matrix**:
- **EmergentRoleManager**: Uses vnet/hardware (full device state for role fitness)
- **Compute Service**: Uses compute/ClusterState (performance metrics only)
- **Rationale**: Different domains, different needs, both valid

#### Files Can Be Partially Deprecated

**EnhancedGossipMessage.kt.md contains**:
- ✅ **Essential**: Device capability types → extracted to DeviceMetrics.kt
- ❌ **Deprecated**: Gossip protocol infrastructure → remains in .md

**Lesson**: Not all code in a deprecated file is bad - extract gems, discard infrastructure

### Rules Established

#### Rule: Always Verify Filesystem Before Assumptions
```bash
# DON'T: Assume file extension
# Assumption: "EnhancedGossipMessage.kt has the types"

# DO: Check actual state
ls -la Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/*.kt
ls -la Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/*.md
```

**Context**: I assumed EnhancedGossipMessage.kt was active, but it was already .md

#### Rule: Start from Clean Baseline When Corrupted
```bash
# DON'T: Try to fix syntax errors while refactoring
# Risk: Compound errors, lose track of changes

# DO: Restore known-good version first
cp file_uncorrupt.md file.kt
# THEN: Apply changes systematically
```

**Context**: Editing corrupted EmergentRoleManager.kt caused cascading errors

#### Rule: Extract Shared Types Before Deprecating Container
```
# DON'T: Deprecate file → breaks imports → scramble to fix
EnhancedGossipMessage.kt → .md
EmergentRoleManager.kt: "Unresolved reference: ResourceCapabilities"
PANIC MODE

# DO: Extract first, update imports, then deprecate
1. Create DeviceMetrics.kt with types
2. Update all imports to new location
3. Verify compilation passes
4. THEN deprecate EnhancedGossipMessage.kt → .md
```

**Context**: Types were used by multiple files, needed canonical home first

#### Rule: Comment Out Deprecated Code, Don't Delete
```kotlin
// DON'T: Delete deprecated code
// Lost: Implementation details, rationale, historical context

// DO: Comment out with explanation
// DEPRECATED: announceGatewayCapability() - part of quorum false start
// Reason: Pre-announcement doesn't work for dynamic mesh topology
// Replaced by: On-demand query pattern (see MeshGossipService)
/*
private suspend fun announceGatewayCapability() {
    // ... [preserved implementation] ...
}
*/
```

**Context**: Future developers need to understand WHY we deprecated, not just WHAT

### Testing Approach

**Systematic Verification**:
1. ✅ Restore baseline → verify line count matches
2. ✅ Comment out code → compile → verify no syntax errors
3. ✅ Create new file → compile → verify new imports resolve
4. ✅ Update imports → compile → verify old imports gone
5. ✅ Final build → zero errors

**Never Assume Success**: Compile after each major step

### Next Steps

1. ⏳ **Update GatewayProtocolIntegrationTest.kt** imports (mmcp → vnet/hardware)
2. ⏳ **Search for other mmcp imports** in test files
3. ⏳ **Run full test suite** to verify behavioral equivalence
4. ⏳ **Update architecture docs** with new package structure

### Decision Log

**Q**: Why keep `MmcpMessage.kt` and `MmcpMessage.md`?  
**A**: ERROR STATE - should only be .kt (base class). Need to delete .md copy.

**Q**: Why not move MeshRole from vnet back to mmcp?  
**A**: vnet is canonical. mmcp/MeshRole.kt.md was over-engineered (15 roles). vnet version has 7 essential roles.

**Q**: Why create DeviceMetrics.kt instead of using compute/ClusterState?  
**A**: Different domains. ClusterState is compute-specific. DeviceMetrics is for emergent role management (includes battery, thermal, charging state).

**Q**: Why not delete announceGatewayCapability() code?  
**A**: Preserve implementation for historical reference. Comments explain why it failed and what replaced it.

### References

- **EmergentRoleManger_uncorrupt.md**: Clean baseline (964 lines)
- **QUORUM_SERVICE_ANNOUNCEMENT_DEPRECATION_ANALYSIS.md**: Original deprecation rationale
- **EMERGENT_ROLE_MANAGER_REFACTOR_PLAN.md**: Execution plan (this session)
- **DeviceMetrics.kt**: Canonical device capability types
```

---

## PHASE 9: FINAL VERIFICATION

### Step 9.1: Full Build Test

```bash
cd /Users/dreadstar/workspace/orbot-android
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew clean
./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee full_build.log
```

**Expected**: BUILD SUCCESSFUL

---

### Step 9.2: Verify No Deprecated Imports Remain

```bash
grep -r "import com.ustadmobile.meshrabiya.mmcp.ResourceCapabilities" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.BatteryInfo" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.ThermalState" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.PowerState" --include="*.kt" Meshrabiya/
grep -r "import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement" --include="*.kt" Meshrabiya/
```

**Expected**: No matches (or only in test files that will be updated)

---

### Step 9.3: Count Lines Changed

```bash
cd /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet
wc -l EmergentRoleManger_uncorrupt.md EmergentRoleManager.kt

cd /Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/hardware
wc -l DeviceMetrics.kt
```

**Expected**:
- EmergentRoleManger_uncorrupt.md: 964 lines
- EmergentRoleManager.kt: ~850-900 lines (after commenting ~200 lines)
- DeviceMetrics.kt: ~200 lines

---

## SUCCESS CRITERIA

✅ **Baseline Restored**: EmergentRoleManager.kt from clean uncorrupted version  
✅ **Announcements Deprecated**: ~200 lines commented out with rationale  
✅ **DeviceMetrics Created**: Canonical types in vnet/hardware package  
✅ **Imports Updated**: All mmcp imports → vnet/vnet.hardware  
✅ **Compilation Clean**: 0 errors, 0 unresolved references  
✅ **Documentation Updated**: INTERIM_COMMIT_LOG.md, KNOWLEDGE-01152025.md  
✅ **Filesystem Verified**: Confirmed .kt vs .md state before all references  

---

## RISK MITIGATION

**Risk**: Copying uncorrupted .md → .kt might lose recent ML code additions  
**Mitigation**: Uncorrupted version is from BEFORE corruption, not before ML code. ML code is in separate methods, will be preserved.

**Risk**: Other files might import deprecated types  
**Mitigation**: Phase 6 searches for all deprecated imports and updates them systematically.

**Risk**: Tests might break with new imports  
**Mitigation**: Update test file imports (GatewayProtocolIntegrationTest.kt) before running full test suite.

**Risk**: MmcpMessage.kt AND MmcpMessage.md both exist (error state)  
**Mitigation**: Document this issue, resolve in separate cleanup task (not this refactor).

---

## EXECUTION CHECKLIST

- [ ] **Phase 1.1**: Backup corrupted EmergentRoleManager.kt
- [ ] **Phase 1.2**: Restore clean baseline from uncorrupted .md
- [ ] **Phase 2.1**: Comment MmcpGatewayAnnouncement import
- [ ] **Phase 2.2**: Comment announceGatewayCapability() method
- [ ] **Phase 2.3**: Comment estimateNetworkCapacity() method
- [ ] **Phase 2.4**: Comment measureNetworkLatency() method
- [ ] **Phase 2.5**: Comment getSupportedProtocols() method
- [ ] **Phase 2.6**: Remove announceGatewayCapability() calls
- [ ] **Phase 2.7**: Comment processNodeAnnouncement() method
- [ ] **Phase 3.1**: Create vnet/hardware/DeviceMetrics.kt
- [ ] **Phase 4.1**: Update EmergentRoleManager.kt imports
- [ ] **Phase 5.1**: Clean build test
- [ ] **Phase 5.2**: Verify no unresolved references
- [ ] **Phase 5.3**: Check error count (should be 0)
- [ ] **Phase 6.1**: Find files with old imports
- [ ] **Phase 6.2**: Update each file's imports
- [ ] **Phase 7.1**: Add deprecation header to EnhancedGossipMessage.kt.md
- [ ] **Phase 8.1**: Update INTERIM_COMMIT_LOG.md
- [ ] **Phase 8.2**: Create KNOWLEDGE-01152025.md
- [ ] **Phase 9.1**: Full build test
- [ ] **Phase 9.2**: Verify no deprecated imports remain
- [ ] **Phase 9.3**: Count lines changed

---

**END OF PLAN**

Ready to execute systematically, starting from clean baseline.
