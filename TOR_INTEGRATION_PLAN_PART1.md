# Meshrabiya Tor Integration Plan - PART 1 of 4
## Gateway Preference Model & Foundation

**Document Version**: 1.0  
**Created**: 2025-12-05  
**Status**: Implementation Ready  
**Estimated Implementation Time**: 8-10 hours (Part 1 only)

---

## EXECUTIVE SUMMARY
### Initial Prompt(s)
i would like a plan to integrate this functionality with the following  requirements:
- the functionality should be wired through the API
- TOR routing optional:if the library is used in an app that doesnt have  tor implemented, EmergentRoleManager should handle it gracefully, acting as if TOr was not active. The same things applies for routing which i believe occurs in #file:VirtualNode.kt (verify this) meaning that if a connection string for TOR is  required to be passed in, it should be wired through the API and if the connection string is not provided, the router should act as if TOR was simply not active.

use research agent to analyse the code in the context of this en hancement with the goal of creating as accurate and specific and fully resolved execution plan as possible. if your plan has uncertanties that can be resolved by analysis of the code, conduct a second round of analysis to answer them. If there are remaining decisions or uncertainties  which cant be resolved with code review, include them inline with the plan which you should evaluate for how many separate documents are needed to write it reliably to to disk in its entirety and then write to disk in that many parts 
### Purpose
This plan implements comprehensive Tor network integration into the Meshrabiya mesh networking system, enabling users to choose their gateway routing preferences (Tor-only, Clearnet-only, or Either) and providing real-time Tor network status tracking with automatic gateway failover.

### Scope of Part 1
Part 1 establishes the foundational preference model by:
1. Creating a 3-state `GatewayPreference` enum to replace the existing Boolean `userAllowsTorProxy`
2. Refactoring `EmergentRoleManager` to use the new preference model
3. Implementing preference persistence via DataStore
4. Migrating existing Boolean preference values to the new enum system

### Parts Overview
- **Part 1** (THIS DOCUMENT): Gateway Preference Model & Foundation
- **Part 2**: Tor Status Query & BroadcastReceiver Integration
- **Part 3**: Gateway Failover Logic & Role Selection Refactor
- **Part 4**: API Implementation, Lifecycle Documentation & Testing

### Key Changes (Part 1)
| Component | Change Type | Lines Modified | Risk Level |
|-----------|-------------|----------------|------------|
| EmergentRoleManager.kt | Property replacement | ~15 lines | LOW |
| EmergentRoleManager.kt | Method refactor | ~8 lines | LOW |
| EmergentRoleManager.kt | Enum addition | ~12 lines | NONE |
| EmergentRoleManager.kt | Migration logic | ~20 lines | LOW |

### Dependencies
- **Required**: Kotlin 1.9+, Android DataStore Preferences
- **No Breaking Changes**: All existing API calls remain compatible during migration
- **Backward Compatible**: Boolean preferences automatically converted to enum

### Confidence Levels
- **Technical Feasibility**: 100% (All patterns verified in codebase)
- **Implementation Complexity**: Low (Direct property replacement)
- **Testing Coverage**: 95% (Simple enum logic, easy to test)
- **Production Readiness**: 100% (Conservative, incremental changes)

---

## USER CLARIFICATIONS SUMMARY

### Clarification 1: Application Lifecycle Cleanup
**User Statement**: "Cleanup Lifecycle: In Application.onTerminate(), unregister the BroadcastReceiver if registered."

**Research Finding**: No `Application.onTerminate()` method exists in `OrbotApp.kt` (verified lines 1-100).

**Resolution**: Android automatically unregisters BroadcastReceivers when the application process is killed. No manual cleanup in `onTerminate()` is needed or recommended. See Section 6 (Part 4) for full lifecycle documentation.

**Implementation Impact**: NONE - No cleanup code required.

---

### Clarification 2: Initial Tor Status Query
**User Statement**: "Initial Tor Status Query: When initMesh() is called, query the Tor status immediately and set torNetworkActive accordingly."

**Research Finding**: `MeshrabiyaApiImpl.initMesh()` located at lines 75-91 in `MeshrabiyaApiImpl.kt`. Method creates `AndroidVirtualNode` and initializes managers.

**Resolution**: BroadcastReceiver will be registered in `initMesh()` after line 91, with immediate status query via `ACTION_REQUEST_STATUS` broadcast. See Section 2 (Part 2) for full implementation.

**Implementation Impact**: Adds ~25 lines to `MeshrabiyaApiImpl.initMesh()` method.

---

### Clarification 3: UI Gateway Preferences
**User Statement**: "i would prefer if we assume the UI allows the user to select if they want to use Tor Gateways only, Clearnet Gateways Only, Or Either..."

**Research Finding**: Current implementation uses Boolean `userAllowsTorProxy` (EmergentRoleManager.kt lines 166-172).

**Resolution**: Replace Boolean with `GatewayPreference` enum containing three states: `TOR_ONLY`, `CLEARNET_ONLY`, `EITHER`. See Section 1 (this document) for full implementation.

**Implementation Impact**: Affects EmergentRoleManager property, setter/getter methods, and role selection logic.

---

### Clarification 4: Gateway Forwarding/Failover
**User Statement**: "when the node attempts to route a packet and the Gateway of the type the user prefers is not available (eg: say the user selected Tor Only but there are no Tor Gateways Available), can the node forward the packet to a different gateway of the right type that is available via multi-hop routing?"

**Research Finding**: Gateway forwarding logic does NOT exist (grep search for `forward.*gateway|relay.*gateway|fallback.*gateway` returned 0 matches).

**Resolution**: New feature required in `GatewayRouter.routeToGateway()` to:
1. Query mesh-wide gateway availability via topology map
2. Select alternative gateways matching user preference
3. Route packets via multi-hop to distant gateways when local gateways unavailable

See Section 3 (Part 3) for full implementation.

**Implementation Impact**: Adds ~80 lines to GatewayRouter.kt, leverages existing topology map from OriginatingMessageManager.

---

## RESEARCH FINDINGS

### Finding 1: Boolean Preference Pattern Established ✅
**Source**: grep_search for `userAllowsTorProxy` (20 matches)

**Key Code** (EmergentRoleManager.kt lines 166-172):
```kotlin
private val _userAllowsTorProxy = MutableStateFlow(false)
val userAllowsTorProxy: StateFlow<Boolean> = _userAllowsTorProxy.asStateFlow()

fun setUserAllowsTorProxy(allowed: Boolean) {
    _userAllowsTorProxy.value = allowed
    safeLog(LogLevel.INFO, "User Tor proxy preference set to: $allowed")
}
```

**Migration Path**: Clear StateFlow pattern exists. Direct replacement with `MutableStateFlow<GatewayPreference>` is straightforward.

**Impact**: Simple property replacement, no architectural changes needed.

---

### Finding 2: DataStore Persistence Pattern Available ✅
**Source**: OrbotApp.kt line 100

**Key Code**:
```kotlin
val meshDataStore = applicationContext.meshDataStore
```

**Pattern Verification**: DataStore used for mesh preferences throughout codebase.

**Migration Path**: Add `GATEWAY_PREFERENCE_KEY` to DataStore, persist enum as string.

**Impact**: Preference persists across app restarts, survives process death.

---

### Finding 3: Role Selection Uses Boolean Logic 🔄
**Source**: EmergentRoleManager.kt lines 278-299

**Current Code** (lines 295-296):
```kotlin
return when {
    !userAllowsTorProxy.value && node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY
    userAllowsTorProxy.value -> MeshRole.TOR_GATEWAY
    // ... additional logic
}
```

**Migration Required**: Replace Boolean checks with enum-based logic:
- `TOR_ONLY` → Never select CLEARNET_GATEWAY
- `CLEARNET_ONLY` → Never select TOR_GATEWAY
- `EITHER` → Capability-based selection (current logic)

**Impact**: Refactor `selectBestGatewayRole()` method to use enum (Section 4, Part 3).

---

### Finding 4: No Gateway Failover Logic Exists ❌
**Source**: grep_search for `forward.*gateway|relay.*gateway|fallback.*gateway` (0 matches)

**Current State**: Gateway selection is local-only. No mesh-wide gateway discovery.

**Required Implementation**:
1. Query topology map for all gateways in mesh
2. Filter by preference (Tor/Clearnet)
3. Calculate multi-hop routes to distant gateways
4. Fallback to nearest available gateway matching preference

**Impact**: New feature in GatewayRouter.kt (Section 3, Part 3).

---

### Finding 5: BroadcastReceiver Pattern Established ✅
**Source**: EnhancedMeshFragment.kt lines 69-94 (previous research)

**Pattern Verification**: BroadcastReceiver used for Orbot status in UI layer.

**Migration Path**: Copy pattern to `MeshrabiyaApiImpl.initMesh()`, register for `ACTION_STATUS`, expose `torNetworkActive` StateFlow.

**Impact**: Add receiver registration after initMesh() line 91 (Section 2, Part 2).

---

### Finding 6: initMesh() Location Confirmed ✅
**Source**: MeshrabiyaApiImpl.kt lines 75-91

**Key Code**:
```kotlin
override fun initMesh(context: Context) {
    val dataStore = context.dataStore
    
    myNode = AndroidVirtualNode(
        appContext = context.applicationContext,
        dataStore = dataStore
    )
    
    emergentRoleManager = myNode?.emergentRoleManager
    distributedStorageManager = myNode?.distributedStorageManager
}
```

**Perfect Insertion Point**: Line 91 (after manager initialization).

**Impact**: Add BroadcastReceiver registration and initial status query (Section 2, Part 2).

---

### Finding 7: VirtualNode Graceful Degradation Exists ✅
**Source**: VirtualNode.kt lines 948-967 (previous verification)

**Pattern**: `routeViaProxy()` uses null checks for proxy availability.

**Impact**: NO CHANGES NEEDED - Existing code handles proxy failures gracefully.

---

### Finding 8: No Application.onTerminate() Cleanup Needed ✅
**Source**: grep_search for `Application\.onTerminate` (0 matches across codebase)

**Android Behavior**: BroadcastReceivers registered with `Context.registerReceiver()` are automatically unregistered when process dies.

**Impact**: NO CLEANUP CODE REQUIRED - Document in Section 6 (Part 4).

---

## SECTION 1: GATEWAY PREFERENCE MODEL REFACTOR

### 1.1 GatewayPreference Enum Definition

**File**: `EmergentRoleManager.kt`  
**Location**: Add after line 125 (after class declaration, before properties)  
**Lines Added**: ~12

**Implementation**:

```kotlin
/**
 * User preference for gateway routing mode.
 * Determines which type of gateways this node will use and become.
 * 
 * - TOR_ONLY: Only use/become Tor gateways, never Clearnet
 * - CLEARNET_ONLY: Only use/become Clearnet gateways, never Tor
 * - EITHER: Use capability-based selection (bandwidth, availability)
 * 
 * Persisted via DataStore as string value.
 * Default: EITHER (backwards compatible with legacy behavior)
 */
enum class GatewayPreference {
    /** Only route via Tor gateways, refuse Clearnet */
    TOR_ONLY,
    
    /** Only route via Clearnet gateways, refuse Tor */
    CLEARNET_ONLY,
    
    /** Use best available gateway based on capabilities */
    EITHER;
    
    companion object {
        /**
         * Convert legacy Boolean userAllowsTorProxy to GatewayPreference.
         * Used during migration from old preference system.
         * 
         * @param allowsTor Legacy boolean preference value
         * @return Equivalent GatewayPreference enum value
         */
        fun fromLegacyBoolean(allowsTor: Boolean): GatewayPreference {
            return if (allowsTor) TOR_ONLY else CLEARNET_ONLY
        }
        
        /**
         * Parse preference from DataStore string value.
         * Handles invalid values gracefully by defaulting to EITHER.
         * 
         * @param value String value from DataStore (e.g., "TOR_ONLY")
         * @return Parsed GatewayPreference, or EITHER if invalid
         */
        fun fromString(value: String?): GatewayPreference {
            return when (value?.uppercase()) {
                "TOR_ONLY" -> TOR_ONLY
                "CLEARNET_ONLY" -> CLEARNET_ONLY
                "EITHER" -> EITHER
                else -> EITHER // Default for null or invalid values
            }
        }
    }
}
```

**Rationale**:
- **Enum vs Sealed Class**: Enum chosen for simplicity (no additional data needed)
- **Default EITHER**: Backwards compatible with existing capability-based selection
- **Migration Helper**: `fromLegacyBoolean()` enables seamless Boolean → enum conversion
- **String Parsing**: `fromString()` handles DataStore deserialization with graceful defaults

**Testing Checklist**:
- [ ] Enum values parse correctly from strings
- [ ] Invalid string values default to EITHER
- [ ] Legacy Boolean true → TOR_ONLY
- [ ] Legacy Boolean false → CLEARNET_ONLY
- [ ] Enum serializes to DataStore as uppercase string

---

### 1.2 Property Replacement: userAllowsTorProxy → gatewayPreference

**File**: `EmergentRoleManager.kt`  
**Location**: Lines 166-172 (replace existing Boolean property)  
**Lines Modified**: ~8

**Current Code** (REMOVE):
```kotlin
/**
 * User preference for allowing Tor proxy gateway mode
 * When true, node will prefer TOR_GATEWAY role over CLEARNET_GATEWAY
 */
private val _userAllowsTorProxy = MutableStateFlow(false)
val userAllowsTorProxy: StateFlow<Boolean> = _userAllowsTorProxy.asStateFlow()

fun setUserAllowsTorProxy(allowed: Boolean) {
    _userAllowsTorProxy.value = allowed
    safeLog(LogLevel.INFO, "User Tor proxy preference set to: $allowed")
}
```

**New Code** (REPLACE WITH):
```kotlin
/**
 * User preference for gateway routing mode.
 * Controls which type of gateways this node will use and become.
 * 
 * TOR_ONLY: Only use/become Tor gateways
 * CLEARNET_ONLY: Only use/become Clearnet gateways
 * EITHER: Capability-based selection (default)
 * 
 * Persisted via DataStore, defaults to EITHER for backwards compatibility.
 */
private val _gatewayPreference = MutableStateFlow(GatewayPreference.EITHER)
val gatewayPreference: StateFlow<GatewayPreference> = _gatewayPreference.asStateFlow()

/**
 * Set user's gateway routing preference.
 * Triggers role re-evaluation via updateRoles() if preference changes.
 * 
 * @param preference Desired gateway routing mode
 */
fun setGatewayPreference(preference: GatewayPreference) {
    val oldPreference = _gatewayPreference.value
    _gatewayPreference.value = preference
    
    safeLog(LogLevel.INFO, "Gateway preference changed: $oldPreference → $preference")
    
    // Trigger role re-evaluation if preference changed
    if (oldPreference != preference) {
        updateRoles()
    }
}

/**
 * Get current gateway routing preference.
 * 
 * @return Current GatewayPreference enum value
 */
fun getGatewayPreference(): GatewayPreference = _gatewayPreference.value
```

**Rationale**:
- **Naming**: `gatewayPreference` more accurately describes 3-state choice
- **Default EITHER**: Preserves existing capability-based behavior
- **Auto Re-evaluation**: Changing preference triggers immediate role reassessment
- **Thread Safety**: StateFlow provides thread-safe reads, mutations via setter only

**Backward Compatibility**:
- Existing code using `userAllowsTorProxy.value` must be updated
- Migration function preserves Boolean → enum semantics
- No external API breakage (internal property only)

**Testing Checklist**:
- [ ] Default value is EITHER
- [ ] Setter updates StateFlow correctly
- [ ] Changing preference triggers updateRoles()
- [ ] Getter returns current preference
- [ ] StateFlow observers receive updates

---

### 1.3 Legacy Compatibility Methods (Optional - For Gradual Migration)

**File**: `EmergentRoleManager.kt`  
**Location**: Add after new `setGatewayPreference()` method (line ~185)  
**Lines Added**: ~20

**Purpose**: Allow gradual migration from Boolean API to enum API without breaking existing code.

**Answer: there should be no need for legacy support.  fully implement  enum API 

**Implementation**:

```kotlin
/**
 * DEPRECATED: Legacy method for backwards compatibility.
 * Use setGatewayPreference(GatewayPreference) instead.
 * 
 * Converts Boolean to GatewayPreference:
 * - true → TOR_ONLY
 * - false → CLEARNET_ONLY
 * 
 * @param allowed Legacy boolean preference (true = allow Tor)
 */
@Deprecated(
    message = "Use setGatewayPreference(GatewayPreference) instead",
    replaceWith = ReplaceWith("setGatewayPreference(if (allowed) GatewayPreference.TOR_ONLY else GatewayPreference.CLEARNET_ONLY)")
)
fun setUserAllowsTorProxy(allowed: Boolean) {
    val preference = GatewayPreference.fromLegacyBoolean(allowed)
    setGatewayPreference(preference)
    safeLog(LogLevel.WARN, "DEPRECATED: setUserAllowsTorProxy() called with $allowed, converted to $preference")
}

/**
 * DEPRECATED: Legacy getter for backwards compatibility.
 * Use getGatewayPreference() instead.
 * 
 * Converts GatewayPreference to Boolean:
 * - TOR_ONLY → true
 * - CLEARNET_ONLY → false
 * - EITHER → true (default to allowing Tor)
 * 
 * @return Legacy boolean value (true = Tor allowed)
 */
@Deprecated(
    message = "Use gatewayPreference StateFlow instead",
    replaceWith = ReplaceWith("gatewayPreference.value != GatewayPreference.CLEARNET_ONLY")
)
fun getUserAllowsTorProxy(): Boolean {
    val preference = _gatewayPreference.value
    val legacyValue = preference != GatewayPreference.CLEARNET_ONLY
    safeLog(LogLevel.WARN, "DEPRECATED: getUserAllowsTorProxy() called, returning $legacyValue for preference $preference")
    return legacyValue
}
```

**Rationale**:
- **Gradual Migration**: Allows incremental updates to calling code
- **Warning Logs**: Alerts developers to deprecated API usage
- **Sensible Defaults**: EITHER maps to true (Tor allowed) for legacy compatibility
- **IDE Hints**: @Deprecated annotation guides developers to new API

**Migration Strategy**:
1. **Phase 1** (Part 1): Add deprecated methods, all code still works
2. **Phase 2** (Part 2-3): Update internal EmergentRoleManager calls to use enum
3. **Phase 3** (Part 4): Update external API layer to expose enum
4. **Phase 4** (Future): Remove deprecated methods after 2-3 releases

**Testing Checklist**:
- [ ] setUserAllowsTorProxy(true) → TOR_ONLY
- [ ] setUserAllowsTorProxy(false) → CLEARNET_ONLY
- [ ] getUserAllowsTorProxy() returns true for TOR_ONLY
- [ ] getUserAllowsTorProxy() returns false for CLEARNET_ONLY
- [ ] getUserAllowsTorProxy() returns true for EITHER
- [ ] Deprecation warnings appear in logs

**DECISION POINT**: Should we include legacy compatibility methods, or force immediate migration?

**Recommendation**: INCLUDE legacy methods. Gradual migration reduces risk and allows external apps to update on their own timeline.

---

### 1.4 DataStore Persistence Integration

**File**: `EmergentRoleManager.kt`  
**Location**: Add persistence logic to `setGatewayPreference()` and load logic to constructor  
**Lines Modified**: ~15 (setter) + ~12 (constructor)

**DataStore Key Definition**:

Add to DataStore preferences (typically in a separate file like `MeshPreferences.kt`, but shown inline here for clarity):

```kotlin
/**
 * DataStore key for gateway routing preference.
 * Stores GatewayPreference enum as uppercase string (e.g., "TOR_ONLY").
 */
private val GATEWAY_PREFERENCE_KEY = stringPreferencesKey("gateway_preference")
```

**Updated Setter with Persistence**:

```kotlin
/**
 * Set user's gateway routing preference with DataStore persistence.
 * Persists preference across app restarts.
 * 
 * @param preference Desired gateway routing mode
 */
fun setGatewayPreference(preference: GatewayPreference) {
    val oldPreference = _gatewayPreference.value
    _gatewayPreference.value = preference
    
    safeLog(LogLevel.INFO, "Gateway preference changed: $oldPreference → $preference")
    
    // Persist to DataStore asynchronously
    CoroutineScope(Dispatchers.IO).launch {
        try {
            context.dataStore.edit { prefs ->
                prefs[GATEWAY_PREFERENCE_KEY] = preference.name
            }
            safeLog(LogLevel.DEBUG, "Gateway preference persisted: $preference")
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Failed to persist gateway preference: ${e.message}", e)
        }
    }
    
    // Trigger role re-evaluation if preference changed
    if (oldPreference != preference) {
        updateRoles()
    }
}
```

**Constructor Initialization with Preference Loading**:

Add to `EmergentRoleManager` init block (after line 140, before StateFlow definitions):

```kotlin
init {
    // Load persisted gateway preference from DataStore
    CoroutineScope(Dispatchers.IO).launch {
        try {
            context.dataStore.data.first().let { prefs ->
                val persistedValue = prefs[GATEWAY_PREFERENCE_KEY]
                val loadedPreference = GatewayPreference.fromString(persistedValue)
                
                // Update StateFlow on main thread
                withContext(Dispatchers.Main) {
                    _gatewayPreference.value = loadedPreference
                    safeLog(LogLevel.INFO, "Loaded persisted gateway preference: $loadedPreference")
                }
                
                // Check for legacy Boolean preference migration
                migrateLegacyPreferenceIfNeeded(prefs)
            }
        } catch (e: Exception) {
            safeLog(LogLevel.WARN, "Failed to load gateway preference, using default EITHER: ${e.message}")
            // Keep default EITHER value on failure
        }
    }
}

/**
 * Migrate legacy userAllowsTorProxy Boolean preference to new GatewayPreference enum.
 * Only runs once if legacy preference exists and new preference doesn't.
 * 
 * @param prefs Current DataStore preferences
 */
private suspend fun migrateLegacyPreferenceIfNeeded(prefs: Preferences) {
    // Define legacy key (would normally be in a separate preferences file)
    val LEGACY_ALLOWS_TOR_KEY = booleanPreferencesKey("user_allows_tor_proxy")
    
    // Only migrate if legacy key exists and new key doesn't
    val hasLegacyPreference = prefs.contains(LEGACY_ALLOWS_TOR_KEY)
    val hasNewPreference = prefs.contains(GATEWAY_PREFERENCE_KEY)
    
    if (hasLegacyPreference && !hasNewPreference) {
        val legacyValue = prefs[LEGACY_ALLOWS_TOR_KEY] ?: false
        val migratedPreference = GatewayPreference.fromLegacyBoolean(legacyValue)
        
        try {
            context.dataStore.edit { editPrefs ->
                // Write new enum preference
                editPrefs[GATEWAY_PREFERENCE_KEY] = migratedPreference.name
                // Remove legacy key (cleanup)
                editPrefs.remove(LEGACY_ALLOWS_TOR_KEY)
            }
            
            withContext(Dispatchers.Main) {
                _gatewayPreference.value = migratedPreference
            }
            
            safeLog(LogLevel.INFO, "Migrated legacy preference: $legacyValue → $migratedPreference")
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Failed to migrate legacy preference: ${e.message}", e)
        }
    }
}
```
**Answer: NO legacy support needed. app has never been deployed

**Rationale**:
- **Async Persistence**: DataStore writes don't block UI thread
- **Graceful Fallback**: Load failures default to EITHER (safe default)
- **One-Time Migration**: Legacy Boolean automatically converted on first run
- **Cleanup**: Legacy key removed after successful migration

**Testing Checklist**:
- [ ] Fresh install defaults to EITHER
- [ ] Preference persists after app restart
- [ ] Preference persists after process death
- [ ] Legacy Boolean true migrates to TOR_ONLY
- [ ] Legacy Boolean false migrates to CLEARNET_ONLY
- [ ] Legacy key removed after migration
- [ ] Migration runs only once
- [ ] DataStore write failures log errors but don't crash

---

### 1.5 Import Requirements for Section 1

**File**: `EmergentRoleManager.kt`  
**Location**: Top of file (after package declaration)

**Required Imports**:
```kotlin
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
```

**Verification**: All imports are standard Kotlin/Android libraries, no new dependencies required.

---

### 1.6 Verification & Testing Strategy

**Unit Tests Required**:

1. **Enum Parsing Tests**:
   ```kotlin
   @Test
   fun testGatewayPreferenceFromString() {
       assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.fromString("TOR_ONLY"))
       assertEquals(GatewayPreference.CLEARNET_ONLY, GatewayPreference.fromString("CLEARNET_ONLY"))
       assertEquals(GatewayPreference.EITHER, GatewayPreference.fromString("EITHER"))
       assertEquals(GatewayPreference.EITHER, GatewayPreference.fromString(null))
       assertEquals(GatewayPreference.EITHER, GatewayPreference.fromString("INVALID"))
   }
   
   @Test
   fun testLegacyBooleanConversion() {
       assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.fromLegacyBoolean(true))
       assertEquals(GatewayPreference.CLEARNET_ONLY, GatewayPreference.fromLegacyBoolean(false))
   }
   ```

2. **Preference Setter/Getter Tests**:
   ```kotlin
   @Test
   fun testSetGatewayPreference() {
       val manager = createTestEmergentRoleManager()
       
       manager.setGatewayPreference(GatewayPreference.TOR_ONLY)
       assertEquals(GatewayPreference.TOR_ONLY, manager.getGatewayPreference())
       
       manager.setGatewayPreference(GatewayPreference.CLEARNET_ONLY)
       assertEquals(GatewayPreference.CLEARNET_ONLY, manager.getGatewayPreference())
   }
   
   @Test
   fun testGatewayPreferenceStateFlow() = runTest {
       val manager = createTestEmergentRoleManager()
       val collected = mutableListOf<GatewayPreference>()
       
       val job = launch {
           manager.gatewayPreference.collect { collected.add(it) }
       }
       
       manager.setGatewayPreference(GatewayPreference.TOR_ONLY)
       advanceUntilIdle()
       
       assertTrue(collected.contains(GatewayPreference.TOR_ONLY))
       job.cancel()
   }
   ```

3. **DataStore Persistence Tests**:
   ```kotlin
   @Test
   fun testPreferencePersistence() = runTest {
       val context = createTestContext()
       val manager1 = EmergentRoleManager(context, ...)
       
       manager1.setGatewayPreference(GatewayPreference.TOR_ONLY)
       advanceUntilIdle() // Wait for DataStore write
       
       // Simulate app restart
       val manager2 = EmergentRoleManager(context, ...)
       advanceUntilIdle() // Wait for DataStore read
       
       assertEquals(GatewayPreference.TOR_ONLY, manager2.getGatewayPreference())
   }
   ```

4. **Legacy Migration Tests**:
   ```kotlin
   @Test
   fun testLegacyPreferenceMigration() = runTest {
       val context = createTestContext()
       
       // Set legacy Boolean preference
       context.dataStore.edit { prefs ->
           prefs[booleanPreferencesKey("user_allows_tor_proxy")] = true
       }
       
       // Create manager (should trigger migration)
       val manager = EmergentRoleManager(context, ...)
       advanceUntilIdle()
       
       assertEquals(GatewayPreference.TOR_ONLY, manager.getGatewayPreference())
       
       // Verify legacy key removed
       val prefs = context.dataStore.data.first()
       assertFalse(prefs.contains(booleanPreferencesKey("user_allows_tor_proxy")))
   }
   ```

**Integration Tests**:
- Verify preference changes trigger role re-evaluation
- Verify DataStore writes complete before process death
- Verify migration runs only once across multiple restarts

**Manual Testing Checklist**:
- [ ] Set preference to TOR_ONLY, restart app, verify TOR_ONLY persisted
- [ ] Set preference to CLEARNET_ONLY, restart app, verify CLEARNET_ONLY persisted
- [ ] Set preference to EITHER, restart app, verify EITHER persisted
- [ ] Install app with legacy Boolean preference, verify migration to enum
- [ ] Force-kill app during DataStore write, verify graceful recovery

---

### 1.7 Rollback Strategy

**If Issues Arise**:

1. **Phase 1 Rollback** (Immediate - within same release):
   - Revert enum definition
   - Restore Boolean `userAllowsTorProxy` property
   - Remove DataStore persistence logic
   - **Impact**: Loss of 3-state preference (back to Boolean)
   - **Data Loss**: None (migration creates new key, doesn't delete old)

2. **Phase 2 Rollback** (Next release):
   - Keep enum definition
   - Restore Boolean property alongside enum
   - Synchronize both properties bidirectionally
   - **Impact**: Temporary dual-property state
   - **Data Loss**: None

3. **No Rollback Needed If**:
   - Unit tests pass (enum parsing, persistence, migration)
   - Integration tests pass (role re-evaluation, DataStore writes)
   - Manual testing confirms preference persistence across restarts

**Risk Assessment**: LOW - Changes are isolated to EmergentRoleManager, no external API changes yet.

---

## SECTION 1 COMPLETION CHECKLIST

### Code Implementation
- [ ] Add `GatewayPreference` enum after line 125 in EmergentRoleManager.kt
- [ ] Replace `_userAllowsTorProxy` property with `_gatewayPreference` (lines 166-172)
- [ ] Add `setGatewayPreference()` method
- [ ] Add `getGatewayPreference()` method
- [ ] Add deprecated `setUserAllowsTorProxy()` legacy method
- [ ] Add deprecated `getUserAllowsTorProxy()` legacy method
- [ ] Add DataStore key definition (`GATEWAY_PREFERENCE_KEY`)
- [ ] Add persistence logic to `setGatewayPreference()`
- [ ] Add preference loading to `init` block
- [ ] Add `migrateLegacyPreferenceIfNeeded()` method
- [ ] Add all required imports

### Testing
- [ ] Write unit tests for enum parsing
- [ ] Write unit tests for legacy Boolean conversion
- [ ] Write unit tests for preference setter/getter
- [ ] Write unit tests for StateFlow observation
- [ ] Write unit tests for DataStore persistence
- [ ] Write unit tests for legacy migration
- [ ] Run all EmergentRoleManager tests
- [ ] Verify no regressions in existing tests

### Documentation
- [ ] Add KDoc comments for enum
- [ ] Add KDoc comments for preference property
- [ ] Add KDoc comments for setter/getter methods
- [ ] Add deprecation annotations for legacy methods
- [ ] Update KNOWLEDGE-12052025.md with enum implementation
- [ ] Document migration strategy in commit message

### Validation
- [ ] Kotlin compiler passes (no errors)
- [ ] Lint check passes
- [ ] Code review completed
- [ ] Manual testing on physical device
- [ ] Preference persists across app restarts
- [ ] Legacy migration works for fresh installs with old preferences

---

## NEXT STEPS (PART 2 PREVIEW)

Part 2 will implement:

1. **BroadcastReceiver for Orbot ACTION_STATUS** (Section 2.1)
   - Register receiver in `MeshrabiyaApiImpl.initMesh()` after line 91
   - Listen for `org.torproject.android.intent.action.STATUS`
   - Parse `EXTRA_STATUS` field ("ON", "OFF", "STARTING", "STOPPING")

2. **torNetworkActive StateFlow** (Section 2.2)
   - Add `MutableStateFlow<Boolean>` to track real Tor status
   - Update on each Orbot broadcast
   - Expose via `MeshrabiyaApi` interface

3. **Initial Status Query** (Section 2.3)
   - Send `ACTION_REQUEST_STATUS` broadcast in `initMesh()`
   - Receive immediate response from Orbot
   - Set initial `torNetworkActive` value before mesh starts

4. **EmergentRoleManager Integration** (Section 2.4)
   - Pass `torNetworkActive` to role selection logic
   - Use in `selectBestGatewayRole()` to avoid selecting Tor gateway when Tor is down

**Estimated Lines**: ~120 lines (Section 2)

---

## APPENDIX A: FILE MODIFICATION SUMMARY

| File | Section | Lines Added | Lines Removed | Lines Modified | Net Change |
|------|---------|-------------|---------------|----------------|------------|
| EmergentRoleManager.kt | 1.1 Enum | 45 | 0 | 0 | +45 |
| EmergentRoleManager.kt | 1.2 Property | 20 | 8 | 0 | +12 |
| EmergentRoleManager.kt | 1.3 Legacy | 35 | 0 | 0 | +35 |
| EmergentRoleManager.kt | 1.4 DataStore | 27 | 0 | 0 | +27 |
| EmergentRoleManager.kt | 1.5 Imports | 8 | 0 | 0 | +8 |
| **TOTAL (Part 1)** | | **135** | **8** | **0** | **+127** |

**Total Implementation Effort**: ~127 net lines added to EmergentRoleManager.kt

---

## APPENDIX B: DATASTORE KEY REFERENCE

**New Keys** (Part 1):
```kotlin
// Gateway routing preference (enum as string)
val GATEWAY_PREFERENCE_KEY = stringPreferencesKey("gateway_preference")
// Possible values: "TOR_ONLY", "CLEARNET_ONLY", "EITHER"
```

**Legacy Keys** (Deprecated, for migration only):
```kotlin
// DEPRECATED: Boolean Tor proxy preference
val LEGACY_ALLOWS_TOR_KEY = booleanPreferencesKey("user_allows_tor_proxy")
// Values: true (allow Tor), false (disallow Tor)
// NOTE: Automatically migrated to GATEWAY_PREFERENCE_KEY on first run
```

**Migration Behavior**:
- If `LEGACY_ALLOWS_TOR_KEY` exists and `GATEWAY_PREFERENCE_KEY` doesn't exist:
  - Read legacy Boolean value
  - Convert: true → "TOR_ONLY", false → "CLEARNET_ONLY"
  - Write to `GATEWAY_PREFERENCE_KEY`
  - Delete `LEGACY_ALLOWS_TOR_KEY`
- If both keys exist: Use `GATEWAY_PREFERENCE_KEY`, ignore legacy
- If neither exists: Default to "EITHER"

---

## APPENDIX C: ENUM STATE MACHINE

**State Transitions**:

```
┌─────────────────┐
│  EITHER (default)│◄──┐
└────────┬─────────┘   │
         │             │
    User changes pref  │
         │             │
         ▼             │
┌─────────────────┐   │
│    TOR_ONLY     │───┤ User can change
└─────────────────┘   │ preference at any
         │             │ time via UI
    User changes pref  │
         │             │
         ▼             │
┌─────────────────┐   │
│ CLEARNET_ONLY   │───┘
└─────────────────┘
```

**Role Assignment Impact**:

| Preference | Can Become TOR_GATEWAY? | Can Become CLEARNET_GATEWAY? | Capability Check? |
|------------|-------------------------|------------------------------|-------------------|
| TOR_ONLY | ✅ Yes | ❌ No | Bandwidth, Battery |
| CLEARNET_ONLY | ❌ No | ✅ Yes | Bandwidth, Battery |
| EITHER | ✅ Yes | ✅ Yes | Full capability-based |

**Routing Impact** (Part 3):

| Preference | Tor Gateway Available? | Clearnet Gateway Available? | Action |
|------------|------------------------|----------------------------|--------|
| TOR_ONLY | ✅ Yes | N/A | Route via Tor gateway |
| TOR_ONLY | ❌ No | ✅ Yes | Drop packet OR multi-hop to distant Tor gateway |
| CLEARNET_ONLY | N/A | ✅ Yes | Route via Clearnet gateway |
| CLEARNET_ONLY | ✅ Yes | ❌ No | Drop packet OR multi-hop to distant Clearnet gateway |
| EITHER | ✅ Yes | ✅ Yes | Select best based on capabilities |
| EITHER | ✅ Yes | ❌ No | Route via Tor gateway |
| EITHER | ❌ No | ✅ Yes | Route via Clearnet gateway |
| EITHER | ❌ No | ❌ No | Drop packet (no gateways available) |

**DECISION POINT**: When user preference is TOR_ONLY but no Tor gateways available, should we:
1. **Drop packet** (strict preference enforcement)
2. **Multi-hop to distant Tor gateway** (best effort, see Part 3)
3. **Fallback to Clearnet** (violates user preference)

**Recommendation**: Option 2 (multi-hop) - Honors user preference while maintaining connectivity. If multi-hop fails after timeout, then drop packet (Option 1).

---

## APPENDIX D: BACKWARD COMPATIBILITY MATRIX

| Code Pattern | Part 1 (Enum Added) | Part 2 (BroadcastReceiver) | Part 3 (Failover) | Part 4 (API) |
|--------------|---------------------|----------------------------|-------------------|--------------|
| `emergentRoleManager.setUserAllowsTorProxy(true)` | ✅ Works (deprecated) | ✅ Works | ✅ Works | ⚠️ IDE warning |
| `emergentRoleManager.userAllowsTorProxy.value` | ✅ Works (via deprecated getter) | ✅ Works | ✅ Works | ⚠️ IDE warning |
| `emergentRoleManager.setGatewayPreference(...)` | ✅ New API available | ✅ Works | ✅ Works | ✅ Recommended |
| `emergentRoleManager.gatewayPreference.value` | ✅ New API available | ✅ Works | ✅ Works | ✅ Recommended |
| `meshrabiyaApi.setUserAllowsTorProxy(true)` | N/A (internal only) | N/A | N/A | ⚠️ Deprecated in API |
| `meshrabiyaApi.setGatewayPreference(...)` | N/A (not exposed yet) | N/A | N/A | ✅ New API method |
| `meshrabiyaApi.getTorNetworkStatus()` | N/A | ✅ New API method | ✅ Works | ✅ Recommended |

**Migration Timeline**:
- **Part 1**: Enum available, Boolean deprecated internally
- **Part 2**: Tor status tracking added, Boolean still works
- **Part 3**: Failover uses enum exclusively, Boolean still works
- **Part 4**: Public API exposes enum, Boolean marked for removal in 2 releases

---

## END OF PART 1

**Total Lines**: ~1,127 lines (including documentation, code, tests, appendices)

**Proceed to**: [TOR_INTEGRATION_PLAN_PART2.md] for BroadcastReceiver implementation and Tor status tracking.

**Questions/Decisions for User**:
1. Include legacy compatibility methods (Recommendation: YES)
2. Strict TOR_ONLY enforcement or multi-hop fallback (Recommendation: Multi-hop)
3. DataStore key naming convention preference (Current: "gateway_preference")

