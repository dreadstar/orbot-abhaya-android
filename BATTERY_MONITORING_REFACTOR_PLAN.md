# Battery Monitoring Integration - Refactoring Plan

**Date:** November 10, 2025  
**Objective:** Implement proper battery monitoring in EmergentRoleManager and integrate it with FitnessScore and NodeCapabilitySnapshot architectures.

---

## Executive Summary

Replace the hardcoded `batteryLevel = 0.5f` in `calculateFitnessScore()` with real Android battery monitoring that properly integrates with:
1. **FitnessScore** (network position metrics for mesh routing roles)
2. **NodeCapabilitySnapshot** (hardware resource metrics for ecosystem roles)

The solution leverages existing `AndroidDeviceCapabilityManager.getBatteryInfo()` and ensures separation of concerns between network fitness and hardware capabilities.

---

## Current State Analysis

### Problem Location
**File:** `EmergentRoleManager.kt`  
**Line:** 470 in `calculateFitnessScore()`  
```kotlin
val batteryLevel = 0.5f // TODO: Implement battery level monitoring
```

### Current Architecture

#### FitnessScore (Network Position Metrics)
- **Purpose:** Measures fitness to serve as Router/Gateway mesh roles
- **Data Class:** `FitnessScore(signalStrength: Int, batteryLevel: Float, clientCount: Int)`
- **Range:** `batteryLevel` is 0.0-1.0 (Float)
- **Usage:** Indicates battery health for sustained mesh routing operations
- **Location:** Line 103-110 in EmergentRoleManager.kt

#### NodeCapabilitySnapshot (Hardware Resource Metrics)
- **Purpose:** Measures resources for ecosystem roles (Storage, Compute)
- **Data Class:** `NodeCapabilitySnapshot(nodeId, resources, batteryInfo, thermalState, networkQuality, stability)`
- **Battery Representation:** `BatteryInfo(level: Int, isCharging, temperatureCelsius, health, chargingSource)`
- **Range:** `level` is 0-100 (Int percentage)
- **Usage:** Resource allocation decisions for distributed compute/storage
- **Location:** Line 33-46 in EmergentRoleManager.kt

#### Existing Battery Monitoring
- **Class:** `AndroidDeviceCapabilityManager`
- **Method:** `getBatteryInfo(): BatteryInfo` (lines 159-207)
- **Data Source:** Android BatteryManager via Intent.ACTION_BATTERY_CHANGED
- **Capabilities:**
  - Battery level percentage (0-100)
  - Charging state (isCharging: Boolean)
  - Battery temperature (temperatureCelsius: Int)
  - Battery health (BatteryHealth: GOOD, DEGRADED, POOR)
  - Charging source (USB, AC, WIRELESS)
  - Estimated time remaining (not reliably available on Android)

---

## Architectural Integration Strategy

### Separation of Concerns

```
┌─────────────────────────────────────────────────────────────┐
│                    EmergentRoleManager                      │
│                                                             │
│  ┌─────────────────────┐      ┌──────────────────────────┐ │
│  │   FitnessScore      │      │ NodeCapabilitySnapshot   │ │
│  │  (Network Position) │      │  (Hardware Resources)    │ │
│  │                     │      │                          │ │
│  │  signalStrength     │      │  resources               │ │
│  │  batteryLevel 0-1   │◄─────┤  batteryInfo             │ │
│  │  clientCount        │      │  thermalState            │ │
│  └─────────────────────┘      │  networkQuality          │ │
│           ▲                   │  stability               │ │
│           │                   └──────────────────────────┘ │
│           │                              ▲                 │
│           │                              │                 │
└───────────┼──────────────────────────────┼─────────────────┘
            │                              │
            └──────────────┬───────────────┘
                           │
                           ▼
            ┌──────────────────────────────┐
            │  BatteryMonitoringService    │
            │  (New Unified Interface)     │
            │                              │
            │  - getBatteryLevel() → 0-1   │
            │  - getBatteryInfo() → Full   │
            │  - startMonitoring()         │
            │  - stopMonitoring()          │
            └──────────────┬───────────────┘
                           │
                           ▼
            ┌──────────────────────────────┐
            │ AndroidDeviceCapabilityMgr   │
            │  getBatteryInfo()            │
            └──────────────────────────────┘
                           │
                           ▼
            ┌──────────────────────────────┐
            │   Android BatteryManager     │
            │   (System Service)           │
            └──────────────────────────────┘
```

### Key Principles

1. **Single Source of Truth:** AndroidDeviceCapabilityManager.getBatteryInfo() is the authoritative battery data source
2. **Fitness Uses Float 0-1:** FitnessScore.batteryLevel represents normalized fitness (0.0 = unusable, 1.0 = excellent)
3. **Capabilities Use Int 0-100:** BatteryInfo.level represents precise percentage for resource decisions
4. **Conversion Formula:** `batteryLevel_float = batteryInfo.level / 100.0f`
5. **Lifecycle Management:** Battery monitoring starts in init block, stops in stopHardwareMonitoring()
6. **Graceful Degradation:** Test environments handle missing BatteryManager gracefully

---

## Refactoring Plan - 5 Steps

### Step 1: Add Battery Level Cache Property
**File:** `EmergentRoleManager.kt`  
**Location:** After line 121 (after ConnectivityMonitor property)

**Add Property:**
```kotlin
// Battery monitoring cache (updated periodically from DeviceCapabilityManager)
@Volatile
private var cachedBatteryLevel: Float = 0.5f // 0-1 normalized, default to middle
private var lastBatteryCheck: Long = 0L
private val BATTERY_CACHE_DURATION_MS = 30_000L // 30 seconds cache
```

**Rationale:**
- Avoids expensive BatteryManager calls on every `calculateFitnessScore()` invocation
- 30-second cache aligns with AdaptivePowerManager's BATTERY_CHECK_INTERVAL_MS
- @Volatile ensures thread-safe reads from coroutines
- Graceful default (0.5f) for initialization before first measurement

---

### Step 2: Add Battery Update Method
**File:** `EmergentRoleManager.kt`  
**Location:** After `calculateFitnessScore()` method (after line 478)

**Add Method:**
```kotlin
/**
 * Updates cached battery level from DeviceCapabilityManager.
 * Called periodically by hardware monitoring loop and on-demand when cache expires.
 * 
 * Converts BatteryInfo.level (0-100 Int) to FitnessScore.batteryLevel (0.0-1.0 Float).
 * 
 * Battery fitness thresholds:
 * - 80-100%: Excellent fitness (1.0) - sustained routing capable
 * - 50-79%:  Good fitness (0.5-0.8) - normal operation
 * - 20-49%:  Fair fitness (0.2-0.5) - reduced routing priority
 * - 0-19%:   Poor fitness (0.0-0.2) - avoid routing, preserve battery
 * 
 * Charging state bonus: +0.1 fitness if charging (allows low-battery nodes to participate)
 */
private suspend fun updateBatteryLevel() {
    try {
        val batteryInfo = deviceCapabilityManager.getBatteryInfo()
        val levelPct = batteryInfo.level // 0-100 Int
        
        // Convert to normalized fitness score (0.0-1.0)
        var fitness = when {
            levelPct >= 80 -> 1.0f
            levelPct >= 50 -> 0.5f + ((levelPct - 50) / 30.0f) * 0.3f // 0.5-0.8 range
            levelPct >= 20 -> 0.2f + ((levelPct - 20) / 30.0f) * 0.3f // 0.2-0.5 range
            else -> (levelPct / 20.0f) * 0.2f // 0.0-0.2 range
        }
        
        // Charging bonus: increase fitness by 0.1 if plugged in
        if (batteryInfo.isCharging) {
            fitness = (fitness + 0.1f).coerceIn(0.0f, 1.0f)
        }
        
        cachedBatteryLevel = fitness
        lastBatteryCheck = System.currentTimeMillis()
        
        betaTestLogger.log(
            LogLevel.DETAILED,
            "EmergentRoleManager",
            "Battery fitness updated: level=${levelPct}%, fitness=${fitness}, charging=${batteryInfo.isCharging}"
        )
    } catch (e: Exception) {
        betaTestLogger.log(
            LogLevel.BASIC,
            "EmergentRoleManager",
            "Failed to update battery level: ${e.message}, using cached=${cachedBatteryLevel}"
        )
        // Keep cached value, don't reset to default
    }
}

/**
 * Gets current battery fitness level with cache expiration check.
 * Returns cached value if fresh (< 30s old), otherwise triggers update.
 */
private suspend fun getBatteryFitnessLevel(): Float {
    val now = System.currentTimeMillis()
    if (now - lastBatteryCheck > BATTERY_CACHE_DURATION_MS) {
        updateBatteryLevel()
    }
    return cachedBatteryLevel
}
```

**Rationale:**
- **Fitness Thresholds:** Maps battery percentage to routing capability
  - High battery (80%+): Full routing capability
  - Medium battery (50-79%): Normal operation
  - Low battery (20-49%): Reduced priority, still functional
  - Critical battery (<20%): Minimal routing, preserve battery
- **Charging Bonus:** Nodes plugged in can route even at lower battery levels
- **Non-linear Mapping:** Higher differentiation at critical levels (0-20%) for better routing decisions
- **Cache Validity Check:** getBatteryFitnessLevel() auto-updates if stale
- **Error Resilience:** Maintains last valid measurement on failure

---

### Step 3: Update calculateFitnessScore() to Use Battery Cache
**File:** `EmergentRoleManager.kt`  
**Location:** Line 470 in `calculateFitnessScore()`

**Replace:**
```kotlin
val batteryLevel = 0.5f // TODO: Implement battery level monitoring
```

**With:**
```kotlin
// Use cached battery fitness level (updated periodically by monitoring loop)
// Falls back to blocking call if cache is stale - this is intentional for accuracy
val batteryLevel = runBlocking { getBatteryFitnessLevel() }
```

**Rationale:**
- Uses cached value if fresh (30s), avoiding frequent BatteryManager calls
- `runBlocking` acceptable here because:
  - Cache hit is instant (no blocking)
  - Cache miss triggers single suspend call (30s interval max)
  - Method is already called from coroutine context in most cases
- Maintains accuracy: always checks cache freshness before returning

---

### Step 4: Integrate Battery Monitoring into Hardware Monitoring Loop
**File:** `EmergentRoleManager.kt`  
**Location:** Lines 832-876 in `startHardwareMonitoring()`

**Find the monitoring loop that calls `updateCapabilities()` periodically.**

**Add Battery Update Call:**
```kotlin
override fun startHardwareMonitoring(intervalMs: Long) {
    if (hardwareMonitoringJob?.isActive == true) {
        betaTestLogger.log(
            LogLevel.BASIC,
            "EmergentRoleManager",
            "Hardware monitoring already running"
        )
        return
    }

    deviceCapabilityManager.startMonitoring(intervalMs)
    
    hardwareMonitoringJob = scope.launch {
        betaTestLogger.log(
            LogLevel.BASIC,
            "EmergentRoleManager",
            "Started hardware monitoring (interval: ${intervalMs}ms)"
        )
        
        while (isActive) {
            try {
                // Update capabilities snapshot
                updateCapabilities()
                
                // Update cached battery fitness level
                updateBatteryLevel() // ← NEW: Periodic battery update
                
                delay(intervalMs)
            } catch (e: Exception) {
                betaTestLogger.log(
                    LogLevel.BASIC,
                    "EmergentRoleManager",
                    "Hardware monitoring error: ${e.message}"
                )
            }
        }
    }
}
```

**Rationale:**
- Battery updates piggyback on existing monitoring loop (same interval)
- Avoids creating separate coroutine for battery monitoring
- Ensures battery cache stays fresh for calculateFitnessScore() calls
- Error handling per-iteration prevents cascading failures

---

### Step 5: Add Initial Battery Reading in Init Block
**File:** `EmergentRoleManager.kt`  
**Location:** Lines 214-221 (existing init block)

**Update Init Block:**
```kotlin
init {
    try {
        connectivityMonitor?.startMonitoring()
        betaTestLogger.log(LogLevel.BASIC, "EmergentRoleManager", "Started connectivity monitoring")
        
        // Initialize battery cache with first reading
        scope.launch {
            updateBatteryLevel()
            betaTestLogger.log(LogLevel.BASIC, "EmergentRoleManager", "Initial battery level: $cachedBatteryLevel")
        }
    } catch (e: Exception) {
        betaTestLogger.log(LogLevel.BASIC, "EmergentRoleManager", 
            "Failed to start connectivity monitoring (test environment?): ${e.message}")
    }
}
```

**Rationale:**
- Populates battery cache immediately on EmergentRoleManager creation
- Prevents reliance on default 0.5f value for first few seconds
- Asynchronous launch doesn't block initialization
- Graceful handling for test environments

---

## Integration Testing Strategy

### Unit Tests (New Test File)
**File:** `EmergentRoleManagerBatteryTest.kt`

```kotlin
@Test
fun `test battery fitness mapping - high battery`() = runTest {
    // Given: 85% battery, not charging
    mockBatteryInfo(level = 85, isCharging = false)
    
    // When
    val fitness = emergentRoleManager.calculateFitnessScore()
    
    // Then
    assertEquals(1.0f, fitness.batteryLevel, 0.01f)
}

@Test
fun `test battery fitness mapping - critical battery with charging bonus`() = runTest {
    // Given: 15% battery, charging
    mockBatteryInfo(level = 15, isCharging = true)
    
    // When
    val fitness = emergentRoleManager.calculateFitnessScore()
    
    // Then: Should have low fitness + charging bonus
    assertTrue(fitness.batteryLevel in 0.2f..0.3f) // ~0.15 base + 0.1 bonus
}

@Test
fun `test battery cache expiration triggers update`() = runTest {
    // Given: Fresh cache
    mockBatteryInfo(level = 80, isCharging = false)
    advanceTimeBy(35_000L) // 35 seconds (past 30s cache duration)
    
    // When
    val fitness = emergentRoleManager.calculateFitnessScore()
    
    // Then: Should have triggered new getBatteryInfo() call
    verify(exactly = 2) { deviceCapabilityManager.getBatteryInfo() } // Init + expired
}
```

### Integration Tests
**File:** `EmergentRoleManagerIntegrationTest.kt`

```kotlin
@Test
fun `test FitnessScore and NodeCapabilitySnapshot use consistent battery data`() = runTest {
    // Given: Real AndroidDeviceCapabilityManager
    val manager = EmergentRoleManager(context, virtualNode, betaTestLogger)
    manager.startHardwareMonitoring(1000L)
    delay(100L) // Let first update complete
    
    // When
    val fitness = manager.calculateFitnessScore()
    val capabilities = manager.getCurrentCapabilities()
    
    // Then: Battery values should be consistent
    val fitnessAsPercent = (fitness.batteryLevel * 100).toInt()
    val capabilitiesPercent = capabilities.batteryInfo.level
    assertTrue(abs(fitnessAsPercent - capabilitiesPercent) < 20) // Allow threshold mapping variance
}

@Test
fun `test battery monitoring survives DeviceCapabilityManager errors`() = runTest {
    // Given: getBatteryInfo() throws exception after first successful call
    mockBatteryInfo(level = 70, isCharging = false)
    manager.startHardwareMonitoring(1000L)
    delay(100L) // First successful update
    
    every { deviceCapabilityManager.getBatteryInfo() } throws RuntimeException("BatteryManager unavailable")
    
    // When: Trigger update after error
    delay(1100L) // Next monitoring cycle
    val fitness = manager.calculateFitnessScore()
    
    // Then: Should use cached value from first successful read
    assertTrue(fitness.batteryLevel in 0.6f..0.8f) // 70% maps to ~0.7
}
```

### Manual Testing Checklist
1. **Battery Level Changes:**
   - Verify FitnessScore updates as battery drains (100% → 50% → 20%)
   - Confirm log messages show "Battery fitness updated" every 30s
   
2. **Charging State:**
   - Plug in charger at 25% battery
   - Verify fitness increases by ~0.1 (charging bonus)
   - Unplug and verify fitness drops back
   
3. **Role Assignment Impact:**
   - At 80%+ battery: Node should accept Gateway/Router roles
   - At 15% battery (not charging): Node should decline routing roles
   - At 15% battery (charging): Node may accept roles due to bonus
   
4. **Cache Behavior:**
   - Monitor logs for "Battery fitness updated" frequency (should be ~30s)
   - Verify no BatteryManager calls between cache updates (performance)
   
5. **Fallback Scenarios:**
   - Run in unit test environment (no Context): Should use 0.5f default gracefully
   - Mock BatteryManager failure: Should maintain last valid cache value

---

## Rollout Strategy

### Phase 1: Implementation (This Session)
- ✅ **Step 1:** Add battery cache properties
- ✅ **Step 2:** Add updateBatteryLevel() and getBatteryFitnessLevel() methods
- ✅ **Step 3:** Update calculateFitnessScore() to use cache
- ✅ **Step 4:** Integrate into hardware monitoring loop
- ✅ **Step 5:** Add initial battery reading in init block

### Phase 2: Testing (Next Session)
- Write unit tests for battery fitness thresholds
- Write integration tests for cache behavior
- Manual testing on physical device with battery drain
- Performance testing (ensure no battery drain from monitoring)

### Phase 3: Validation (Post-Deployment)
- Monitor logs for "Battery fitness updated" messages
- Verify role assignment correlates with battery level
- Check for error rates in updateBatteryLevel() (should be <1%)
- Validate FitnessScore and NodeCapabilitySnapshot consistency

### Phase 4: Future Enhancements
- **Predictive Battery Drain:** Machine learning model predicts battery impact of accepting role
- **Hysteresis:** Prevent role thrashing at threshold boundaries (e.g., 20% ±2%)
- **Battery Health Adjustment:** Factor BatteryHealth.DEGRADED/POOR into fitness calculation
- **Temperature Throttling:** Reduce fitness during high battery temperature (>40°C)

---

## Known Limitations & TODOs

### Current Limitations
1. **No Battery History:** Only current level, no trend analysis (draining fast vs slow)
2. **Threshold Hardcoded:** Fitness thresholds (80%, 50%, 20%) not user-configurable
3. **No Time-to-Empty:** Android doesn't reliably provide estimated time remaining
4. **Charging Bonus Fixed:** 0.1 bonus regardless of charging speed (USB vs AC)

### Future TODOs
1. **Battery Trend Analysis:**
   - Track battery level over time (last 5 minutes)
   - Reduce fitness if draining rapidly (e.g., -10%/10min)
   - Use historical data from AdaptivePowerManager
   
2. **User-Configurable Thresholds:**
   - UI sliders for battery fitness thresholds
   - "Conservative" vs "Aggressive" battery profiles
   - Sync with AdaptivePowerManager.PowerSettings
   
3. **Health-Adjusted Fitness:**
   ```kotlin
   val healthMultiplier = when (batteryInfo.health) {
       BatteryHealth.GOOD -> 1.0f
       BatteryHealth.DEGRADED -> 0.8f
       BatteryHealth.POOR -> 0.5f
   }
   fitness *= healthMultiplier
   ```
   
4. **Temperature Protection:**
   ```kotlin
   if (batteryInfo.temperatureCelsius > 40) {
       fitness *= 0.7f // Reduce fitness during high temp
   }
   ```

---

## Files Modified Summary

1. **EmergentRoleManager.kt:**
   - Add 3 battery cache properties (after line 121)
   - Add updateBatteryLevel() method (after line 478)
   - Add getBatteryFitnessLevel() method (after updateBatteryLevel)
   - Update calculateFitnessScore() line 470 (replace TODO)
   - Update startHardwareMonitoring() (add updateBatteryLevel() call)
   - Update init block lines 214-221 (add initial battery read)

2. **EmergentRoleManagerBatteryTest.kt (NEW):**
   - Unit tests for battery fitness thresholds
   - Cache expiration tests
   - Charging bonus tests
   - Error handling tests

3. **EmergentRoleManagerIntegrationTest.kt (UPDATE):**
   - Add consistency test (FitnessScore vs NodeCapabilitySnapshot battery)
   - Add error resilience test

4. **KNOWLEDGE-11102025.md (UPDATE):**
   - Add "TODOs Completed" section for battery monitoring
   - Update architectural rules to include battery monitoring lifecycle

---

## Success Criteria

✅ **Functionality:**
- [ ] calculateFitnessScore() returns real battery fitness (not 0.5f)
- [ ] Battery cache updates every 30 seconds via hardware monitoring loop
- [ ] Charging bonus (+0.1) applied correctly
- [ ] Fitness thresholds map correctly (80%→1.0, 50%→0.6, 20%→0.3, 10%→0.1)

✅ **Architecture:**
- [ ] FitnessScore uses Float 0-1 (network fitness)
- [ ] NodeCapabilitySnapshot uses Int 0-100 (resource metrics)
- [ ] Single source of truth: AndroidDeviceCapabilityManager.getBatteryInfo()
- [ ] No tight coupling between FitnessScore and BatteryInfo

✅ **Performance:**
- [ ] BatteryManager called ≤ once per 30 seconds (cache hit rate >95%)
- [ ] No measurable battery drain from monitoring
- [ ] calculateFitnessScore() completes in <5ms (cache hit)

✅ **Reliability:**
- [ ] Graceful degradation in test environments (no Context)
- [ ] Error handling preserves last valid cache value
- [ ] Init block populates cache immediately (no 30s delay)
- [ ] Thread-safe (@Volatile) for concurrent access

✅ **Testing:**
- [ ] Unit tests cover all fitness thresholds
- [ ] Integration tests verify consistency with capabilities
- [ ] Manual testing confirms role assignment responds to battery level

---

## References

1. **KNOWLEDGE-11102025.md** - FitnessScore architecture and TODOs
2. **AndroidDeviceCapabilityManager.kt** (lines 159-207) - Battery data source
3. **AdaptivePowerManager.kt** (lines 223-286) - Battery monitoring pattern
4. **EmergentRoleManager.kt** (lines 103-110) - FitnessScore data class
5. **EmergentRoleManager.kt** (lines 33-46) - NodeCapabilitySnapshot data class

---

## Approval & Next Steps

**Status:** ✅ Plan Finalized - Ready for Implementation

**Next Action:** Execute 5-step refactoring plan

**Estimated Time:** 30-45 minutes (implementation + initial testing)

**Questions Before Proceeding:**
1. Confirm fitness thresholds (80%, 50%, 20%) are appropriate for your use case?
2. Confirm 30-second cache duration balances accuracy vs performance?
3. Confirm charging bonus of +0.1 fitness is reasonable?

**Once approved, agent will:**
1. Implement all 5 steps in sequence
2. Mark each step completed in todo list
3. Run compile check after each major change
4. Create unit tests for battery fitness mapping
5. Update KNOWLEDGE-11102025.md with completion status
