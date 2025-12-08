# Tor Integration Plan V3 - Part 2: Orbot VPN Integration & Proxy Rules Precedence
**Version:** 3.0  
**Date:** January 2025  
**Status:** Final Design  
**Dependencies:** Part 1 (VirtualPacketHeader extension) must be complete

---

## EXECUTIVE SUMMARY

This is Part 2 of the V3 Tor Integration Plan, implementing Orbot VPN per-app proxy rules integration and the "proxy rules supersede preference" requirement from Answer Block 1.

### Key Requirement from Answer Block 1

> "The Orbot App also has functinality to select if all traffic or just selected apps should use TOR. Ideally, i would like traffic apps on the device to follow the same rules, if the node internet/TOR connection is down and traffic can then be directed to Gateways across the mesh. **Ideally, the OrbotApp TOr PRoxy rules would supercede the TOR_ONLY, CLEARNET_ONLY, EITHER selection for traffic from other apps on the device.**"

### Part 2 Scope

- Cross-module VPN settings access (Meshrabiya library → Orbot app)
- TorifiedApp list reading from SharedPreferences
- Per-app proxy rules precedence logic
- Dynamic gateway type determination (replaces Part 1 stub)
- Orbot Tor status monitoring (BroadcastReceiver from V2)
- GatewayPreference enum and persistence

---

## 2.1 ORBOT VPN ARCHITECTURE (RESEARCH FINDINGS)

### VPN App Selection Storage

**Research Findings** (from AppManagerActivity.kt and TorifiedApp.kt):

**SharedPreferences Key**: `"PrefTord"` (defined in OrbotConstants.PREFS_KEY_TORIFIED)

**Storage Format**: Pipe-delimited string of package names
```kotlin
// Example stored value:
"com.android.chrome|org.mozilla.firefox|com.whatsapp|org.telegram.messenger"
```

**Reading Logic** (from TorifiedApp.kt line 54-59):
```kotlin
val torifiedPackages = prefs
    .getString(OrbotConstants.PREFS_KEY_TORIFIED, "")
    ?.split("|")
    ?.filter { it.isNotBlank() }
    ?.sorted()
    ?: emptyList()
```

**Writing Logic** (from AppManagerActivity.kt line 258):
```kotlin
private fun saveAppSettings() {
    val tordApps = StringBuilder()
    allApps?.forEach { app ->
        if (app.isTorified) {
            tordApps.append(app.packageName)
            tordApps.append("|")
        }
    }
    mPrefs?.edit()?.apply {
        putString(OrbotConstants.PREFS_KEY_TORIFIED, tordApps.toString())
        apply()
    }
}
```

### TorifiedApp Data Class

**File**: `orbotservice/src/main/java/org/torproject/android/service/vpn/TorifiedApp.kt`

**Structure**:
```kotlin
@Serializable
class TorifiedApp : Comparable<TorifiedApp> {
    var isEnabled: Boolean = false
    var uid: Int = 0
    var username: String? = null
    var procname: String? = null
    var name: String? = null  // Human-readable app name
    var packageName: String = ""  // Unique identifier (e.g., "com.android.chrome")
    var isTorified: Boolean = false  // TRUE = use Tor, FALSE = clearnet
    var usesInternet: Boolean = false
}
```

**Key Fields for V3**:
- `packageName`: Unique app identifier (e.g., "com.android.chrome")
- `isTorified`: TRUE = app uses Tor, FALSE = app uses clearnet
- `uid`: Linux UID for matching packets to apps

### Prefs Object Location

**File**: `orbotservice/src/main/java/org/torproject/android/service/util/Prefs.kt`

**Structure**:
```kotlin
object Prefs {
    private var prefs: SharedPreferences? = null
    
    @JvmStatic
    fun setContext(context: Context?) {
        if (prefs == null) prefs = getSharedPrefs(context)
    }
    
    @JvmStatic
    fun getSharedPrefs(context: Context?): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }
}
```

**Access Pattern**:
```kotlin
// Get SharedPreferences
val prefs = Prefs.getSharedPrefs(context)

// Read torified apps
val torifiedAppsString = prefs.getString("PrefTord", "")
val torifiedPackages = torifiedAppsString.split("|").filter { it.isNotBlank() }

// Check if app is torified
val isAppTorified = torifiedPackages.contains("com.example.app")
```

---

## 2.2 CROSS-MODULE ACCESS PATTERN

### Challenge

**Module Structure**:
```
orbot-android/
├── app/                      # Orbot UI, VPN settings, AppManagerActivity
│   └── src/main/java/org/torproject/android/ui/
│       └── AppManagerActivity.kt
├── orbotservice/            # Tor service, TorifiedApp, Prefs
│   └── src/main/java/org/torproject/android/service/
│       ├── util/Prefs.kt
│       └── vpn/TorifiedApp.kt
└── Meshrabiya/
    └── lib-meshrabiya/      # Mesh library (needs VPN settings access)
        └── src/main/java/com/ustadmobile/meshrabiya/
            └── MeshrabiyaApiImpl.kt
```

**Problem**: Meshrabiya library (in `/Meshrabiya`) needs to read Orbot VPN settings (stored via `/orbotservice` Prefs)

**Solution**: SharedPreferences are app-global, accessible from any module via context

### V3 Access Pattern: Direct SharedPreferences Read

**Approach**: Meshrabiya library reads SharedPreferences directly using Prefs.getSharedPrefs()

**Advantages**:
- Simple: No callbacks or interfaces needed
- Direct: Library reads same SharedPreferences as app
- Testable: Can mock SharedPreferences in tests
- Consistent: Uses existing Prefs.kt infrastructure

**Implementation**:

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt`

```kotlin
package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.SharedPreferences
import org.torproject.android.service.util.Prefs
import org.torproject.android.service.OrbotConstants

/**
 * Checks if a package is configured to use Tor via Orbot VPN settings.
 * 
 * Reads from SharedPreferences key "PrefTord" which contains pipe-delimited
 * list of package names that should route through Tor.
 *
 * @param packageName Android package name (e.g., "com.android.chrome")
 * @param context Android context for SharedPreferences access
 * @return true if package should use Tor, false if clearnet
 */
private fun isPackageTorified(
    packageName: String,
    context: Context
): Boolean {
    val prefs: SharedPreferences = Prefs.getSharedPrefs(context)
    
    val torifiedAppsString = prefs.getString(
        OrbotConstants.PREFS_KEY_TORIFIED,  // "PrefTord"
        ""
    ) ?: ""
    
    if (torifiedAppsString.isEmpty()) {
        return false  // No apps configured for Tor
    }
    
    val torifiedPackages = torifiedAppsString
        .split("|")
        .filter { it.isNotBlank() }
    
    return torifiedPackages.contains(packageName)
}
```

**Usage Example**:
```kotlin
val packageName = "com.android.chrome"
val usesTor = isPackageTorified(packageName, context)

if (usesTor) {
    // Chrome is configured to use Tor
    gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR
} else {
    // Chrome uses clearnet
    gatewayType = VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
}
```

**Testing**:
```kotlin
@Test
fun isPackageTorified_packageInList_returnsTrue() {
    // Setup mock SharedPreferences
    val mockPrefs = mockk<SharedPreferences>()
    every { mockPrefs.getString("PrefTord", "") } returns 
        "com.android.chrome|org.mozilla.firefox"
    
    val result = isPackageTorified("com.android.chrome", mockContext)
    
    assertTrue(result)
}

@Test
fun isPackageTorified_packageNotInList_returnsFalse() {
    val mockPrefs = mockk<SharedPreferences>()
    every { mockPrefs.getString("PrefTord", "") } returns 
        "com.android.chrome|org.mozilla.firefox"
    
    val result = isPackageTorified("com.whatsapp", mockContext)
    
    assertFalse(result)
}
```

---

## 2.3 PACKET UID EXTRACTION

### Challenge: Mapping Packets to Apps

**Problem**: Need to determine which Android app originated a packet to apply per-app VPN rules

**Android Approach**: Each app has a unique Linux UID (User ID)

**Packet Marking**: Android VPN framework can mark packets with originating UID

### UID Extraction from DatagramPacket

**Research Finding**: VpnService can query UID for socket connections

**V3 Approach**: Extract UID from packet metadata (if available)

**Stub Implementation** (Part 2 - full implementation requires VPN service integration):

```kotlin
/**
 * Extracts the Android UID of the app that originated this packet.
 * 
 * This requires VPN service integration to mark packets with UIDs.
 * For now, returns null (stub for Part 2).
 *
 * Part 3 TODO: Integrate with VpnService to mark packets with source UID
 *
 * @param packet Source DatagramPacket
 * @return Android UID, or null if cannot be determined
 */
private fun extractPacketUid(packet: DatagramPacket?): Int? {
    // STUB for Part 2
    // Full implementation requires VPN service integration
    return null
    
    // Part 3 TODO:
    // 1. Access VpnService.protect() metadata
    // 2. Query ConnectivityManager for socket UID
    // 3. Use /proc/net/tcp to map source port → UID
    // 4. Return UID for package name lookup
}
```

### UID to Package Name Mapping

**Android PackageManager Approach**:

```kotlin
/**
 * Maps Android UID to package name.
 * 
 * Uses PackageManager to query which app owns a UID.
 *
 * @param uid Android UID (e.g., 10123)
 * @param context Android context for PackageManager access
 * @return Package name (e.g., "com.android.chrome"), or null if not found
 */
private fun getPackageNameForUid(uid: Int, context: Context): String? {
    val packageManager = context.packageManager
    val packages = packageManager.getPackagesForUid(uid)
    
    // UID can map to multiple packages (shared UID)
    // Return first package (or implement priority logic)
    return packages?.firstOrNull()
}
```

**Example**:
```kotlin
val uid = extractPacketUid(datagramPacket)  // Returns 10123
val packageName = getPackageNameForUid(uid, context)  // Returns "com.android.chrome"
val usesTor = isPackageTorified(packageName, context)  // Checks VPN settings
```

---

## 2.4 GATEWAY PREFERENCE ENUM

### GatewayPreference Definition

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/GatewayPreference.kt`

**Implementation** (from V2, unchanged in V3):

```kotlin
package com.ustadmobile.meshrabiya.api

/**
 * Enum representing the user's global gateway preference for internet-bound traffic.
 * 
 * This preference determines which type of gateway to use when routing packets
 * to the internet via mesh gateways.
 *
 * Note: Per-app VPN settings (from Orbot) SUPERSEDE this global preference.
 */
enum class GatewayPreference {
    /**
     * Only use Tor gateways for internet traffic.
     * Privacy-first mode.
     * If no Tor gateway available, packets are dropped.
     */
    TOR_ONLY,

    /**
     * Only use clearnet gateways for internet traffic.
     * Direct connection mode.
     * If no clearnet gateway available, packets are dropped.
     */
    CLEARNET_ONLY,

    /**
     * Use either Tor or clearnet gateways.
     * Prefers Tor (privacy-first), falls back to clearnet.
     * Most flexible mode.
     */
    EITHER;

    companion object {
        /**
         * Default preference: TOR_ONLY (privacy-first)
         */
        val DEFAULT = TOR_ONLY
    }
}
```

**Usage**:
```kotlin
// Set user preference
meshrabiyaApi.setGatewayPreference(GatewayPreference.TOR_ONLY)

// Get current preference
val pref = meshrabiyaApi.getGatewayPreference()  // Returns TOR_ONLY

// Apply preference (after checking VPN rules)
when (effectivePreference) {
    GatewayPreference.TOR_ONLY -> gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR
    GatewayPreference.CLEARNET_ONLY -> gatewayType = VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
    GatewayPreference.EITHER -> gatewayType = preferTorGateway()  // Prefers Tor, fallback clearnet
}
```

### Preference Persistence

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt`

**DataStore Key**: `"gateway_preference"` (from V2)

**Implementation**:

```kotlin
class MeshrabiyaApiImpl(
    private val context: Context,
    // ... other parameters
) : MeshrabiyaApi {

    private val dataStore: DataStore<Preferences> = context.createDataStore(
        name = "meshrabiya_preferences"
    )

    private val GATEWAY_PREFERENCE_KEY = stringPreferencesKey("gateway_preference")

    override suspend fun setGatewayPreference(preference: GatewayPreference) {
        dataStore.edit { prefs ->
            prefs[GATEWAY_PREFERENCE_KEY] = preference.name  // "TOR_ONLY", "CLEARNET_ONLY", "EITHER"
        }
    }

    override suspend fun getGatewayPreference(): GatewayPreference {
        val prefName = dataStore.data.first()[GATEWAY_PREFERENCE_KEY]
        return if (prefName != null) {
            GatewayPreference.valueOf(prefName)
        } else {
            GatewayPreference.DEFAULT  // TOR_ONLY
        }
    }

    // Expose as StateFlow for reactive UI
    val gatewayPreferenceFlow: StateFlow<GatewayPreference> = dataStore.data
        .map { prefs ->
            val prefName = prefs[GATEWAY_PREFERENCE_KEY]
            if (prefName != null) {
                GatewayPreference.valueOf(prefName)
            } else {
                GatewayPreference.DEFAULT
            }
        }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = GatewayPreference.DEFAULT
        )
}
```

**Testing**:
```kotlin
@Test
fun gatewayPreference_defaultValue_isTorOnly() = runBlocking {
    val api = MeshrabiyaApiImpl(context, ...)
    
    val pref = api.getGatewayPreference()
    
    assertEquals(GatewayPreference.TOR_ONLY, pref)
}

@Test
fun setGatewayPreference_persistsValue() = runBlocking {
    val api = MeshrabiyaApiImpl(context, ...)
    
    api.setGatewayPreference(GatewayPreference.CLEARNET_ONLY)
    val pref = api.getGatewayPreference()
    
    assertEquals(GatewayPreference.CLEARNET_ONLY, pref)
}
```

---

## 2.5 PROXY RULES PRECEDENCE LOGIC

### Requirement from Answer Block 1

> "**Ideally, the OrbotApp TOr PRoxy rules would supercede the TOR_ONLY, CLEARNET_ONLY, EITHER selection for traffic from other apps on the device.**"

### V3 Precedence Hierarchy

**Priority (highest to lowest)**:

1. **Packet Header Gateway Type** (explicit in-packet request)
   - If `gatewayType == GATEWAY_TYPE_TOR` → Use Tor gateway
   - If `gatewayType == GATEWAY_TYPE_CLEARNET` → Use clearnet gateway
   - If `gatewayType == GATEWAY_TYPE_NONE` → Continue to next priority

2. **Orbot VPN Per-App Rules** (proxy rules supersede preference)
   - Extract packet source app UID
   - Map UID → package name
   - Check if package is torified (in "PrefTord" list)
   - If YES → Use Tor gateway (GATEWAY_TYPE_TOR)
   - If NO → Use clearnet gateway (GATEWAY_TYPE_CLEARNET)
   - If UNKNOWN (UID not extractable) → Continue to next priority

3. **User's Global Gateway Preference** (fallback)
   - Read preference from DataStore
   - Apply TOR_ONLY, CLEARNET_ONLY, or EITHER
   - EITHER prefers Tor, fallbacks to clearnet

### Implementation

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**Method**: `determineGatewayType()` (replaces Part 1 stub)

```kotlin
/**
 * Determines the gateway type for a packet based on V3 precedence rules.
 *
 * Precedence (highest to lowest):
 * 1. Packet header gateway type (explicit request)
 * 2. Orbot VPN per-app rules (proxy rules supersede preference)
 * 3. User's global gateway preference
 *
 * @param destinationAddr Virtual address of packet destination
 * @param packet Source DatagramPacket (for UID extraction)
 * @param existingGatewayType Gateway type from packet header (if forwarding)
 * @return Gateway type: NONE (0), TOR (1), or CLEARNET (2)
 */
private fun determineGatewayType(
    destinationAddr: Int,
    packet: DatagramPacket?,
    existingGatewayType: Byte = VirtualPacketHeader.GATEWAY_TYPE_NONE
): Byte {
    // 1. Check if destination is on mesh topology
    if (isDestinationOnMesh(destinationAddr)) {
        // Mesh-local traffic, no gateway needed
        return VirtualPacketHeader.GATEWAY_TYPE_NONE
    }

    // Destination is internet-bound, need gateway

    // 2. PRIORITY 1: Check packet header for explicit gateway type
    if (existingGatewayType != VirtualPacketHeader.GATEWAY_TYPE_NONE) {
        // Packet already has explicit gateway request
        logger.debug { 
            "Using explicit gateway type from packet header: $existingGatewayType" 
        }
        return existingGatewayType
    }

    // 3. PRIORITY 2: Check Orbot VPN per-app rules (supersedes preference)
    val uid = extractPacketUid(packet)
    if (uid != null) {
        val packageName = getPackageNameForUid(uid, context)
        if (packageName != null) {
            val isTorified = isPackageTorified(packageName, context)
            
            val gatewayType = if (isTorified) {
                VirtualPacketHeader.GATEWAY_TYPE_TOR
            } else {
                VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
            }
            
            logger.debug { 
                "VPN per-app rule for $packageName: gatewayType=$gatewayType" 
            }
            
            return gatewayType  // Orbot VPN rules SUPERSEDE preference
        } else {
            logger.warn { "Could not map UID $uid to package name" }
        }
    } else {
        logger.debug { "Could not extract UID from packet (VPN not active?)" }
    }

    // 4. PRIORITY 3: Apply user's global gateway preference (fallback)
    val preference = getGatewayPreference()  // From DataStore
    
    val gatewayType = when (preference) {
        GatewayPreference.TOR_ONLY -> {
            VirtualPacketHeader.GATEWAY_TYPE_TOR
        }
        GatewayPreference.CLEARNET_ONLY -> {
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
        }
        GatewayPreference.EITHER -> {
            // Prefer Tor (privacy-first), but allow clearnet fallback
            // Gateway selection logic will handle fallback
            VirtualPacketHeader.GATEWAY_TYPE_TOR
        }
    }
    
    logger.debug { 
        "Using global gateway preference: $preference → gatewayType=$gatewayType" 
    }
    
    return gatewayType
}
```

**Helper Methods**:

```kotlin
/**
 * Checks if destination address is on the mesh topology.
 * 
 * @param destinationAddr Virtual address to check
 * @return true if address is a mesh node, false if internet-bound
 */
private fun isDestinationOnMesh(destinationAddr: Int): Boolean {
    // Check if destination is in node topology
    val node = topology.getNodeByAddress(destinationAddr)
    return node != null
}

/**
 * Gets current gateway preference from DataStore.
 * 
 * @return Current preference, or DEFAULT (TOR_ONLY) if not set
 */
private fun getGatewayPreference(): GatewayPreference {
    // Read from StateFlow (reactive)
    return meshrabiyaApi.gatewayPreferenceFlow.value
}
```

### Example Scenarios

**Scenario 1: Chrome with Tor VPN enabled**
```kotlin
// Packet from Chrome (UID 10123)
// Chrome is in "PrefTord" list (isTorified = true)
// User's global preference = CLEARNET_ONLY

val uid = extractPacketUid(packet)  // 10123
val packageName = getPackageNameForUid(uid, context)  // "com.android.chrome"
val isTorified = isPackageTorified(packageName, context)  // TRUE

// Result: GATEWAY_TYPE_TOR (VPN rule supersedes CLEARNET_ONLY preference)
```

**Scenario 2: WhatsApp without Tor VPN**
```kotlin
// Packet from WhatsApp (UID 10456)
// WhatsApp NOT in "PrefTord" list (isTorified = false)
// User's global preference = TOR_ONLY

val uid = extractPacketUid(packet)  // 10456
val packageName = getPackageNameForUid(uid, context)  // "com.whatsapp"
val isTorified = isPackageTorified(packageName, context)  // FALSE

// Result: GATEWAY_TYPE_CLEARNET (VPN rule supersedes TOR_ONLY preference)
```

**Scenario 3: Unknown app (UID not extractable)**
```kotlin
// Packet with no UID metadata (VPN not active)
// User's global preference = EITHER

val uid = extractPacketUid(packet)  // null
// Cannot apply VPN rules

val preference = getGatewayPreference()  // EITHER
// Result: GATEWAY_TYPE_TOR (EITHER prefers Tor, allows clearnet fallback)
```

---

## 2.6 TOR STATUS MONITORING (FROM V2)

### Requirement

Monitor Orbot Tor service status to determine if Tor gateways are available.

### BroadcastReceiver Implementation

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt`

**Implementation** (from V2 plan):

```kotlin
class MeshrabiyaApiImpl(
    private val context: Context,
    // ... other parameters
) : MeshrabiyaApi {

    // Tor status as StateFlow (reactive)
    private val _torStatus = MutableStateFlow(false)
    override val torStatus: StateFlow<Boolean> = _torStatus.asStateFlow()

    private var torStatusReceiver: BroadcastReceiver? = null

    override suspend fun initMesh(
        config: MeshConfig
    ) {
        // ... existing initMesh code ...

        // Register BroadcastReceiver for Tor status
        registerTorStatusReceiver()
    }

    /**
     * Registers BroadcastReceiver to monitor Orbot Tor status changes.
     * 
     * Listens for intent: org.torproject.android.intent.action.STATUS
     * Parses EXTRA_STATUS string and updates torStatus StateFlow.
     */
    private fun registerTorStatusReceiver() {
        torStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "org.torproject.android.intent.action.STATUS") {
                    val status = intent.getStringExtra("org.torproject.android.intent.extra.STATUS")
                    
                    // Conservative mapping: Only "ON" = true
                    val isTorRunning = (status == "ON")
                    
                    logger.info { "Tor status changed: $status → isTorRunning=$isTorRunning" }
                    
                    _torStatus.value = isTorRunning
                    
                    // Trigger role update (SERVER-side)
                    if (this@MeshrabiyaApiImpl is EmergentRoleManager) {
                        updateRoles()
                    }
                }
            }
        }

        val filter = IntentFilter("org.torproject.android.intent.action.STATUS")
        context.registerReceiver(torStatusReceiver, filter)
        
        logger.info { "Registered Tor status BroadcastReceiver" }
    }

    override suspend fun shutdownMesh() {
        // ... existing shutdown code ...

        // Unregister BroadcastReceiver
        if (torStatusReceiver != null) {
            context.unregisterReceiver(torStatusReceiver)
            torStatusReceiver = null
            logger.info { "Unregistered Tor status BroadcastReceiver" }
        }
    }
}
```

**Tor Status Values** (from V2 research):
- `"ON"`: Tor is fully connected
- `"STARTING"`: Tor is starting (V2 decision: treat as FALSE)
- `"STOPPING"`: Tor is stopping (FALSE)
- `"OFF"`: Tor is off (FALSE)

**Conservative Mapping** (from V2 decision):
```kotlin
val isTorRunning = (status == "ON")
// Only "ON" = true
// All other states ("STARTING", "STOPPING", "OFF", null) = false
```

### Server-Side Role Update

**Requirement**: When Tor status changes, update this node's gateway role (SERVER-side).

**Implementation**:

```kotlin
/**
 * Updates this node's gateway roles based on Tor status.
 * 
 * Called when Tor status changes (BroadcastReceiver callback).
 * Only affects SERVER-side role (what gateway type this node advertises).
 */
fun updateRoles() {
    val isTorRunning = torStatus.value
    
    if (isTorRunning) {
        // Tor is ON, advertise as TOR_GATEWAY
        emergentRoleManager.advertiseAsGateway(GatewayType.TOR)
        logger.info { "Advertising as TOR_GATEWAY (Tor is ON)" }
    } else {
        // Tor is OFF, only advertise CLEARNET_GATEWAY
        emergentRoleManager.removeGatewayRole(GatewayType.TOR)
        logger.info { "Removed TOR_GATEWAY role (Tor is OFF)" }
    }
}
```

---

## 2.7 API INTERFACE UPDATES

### MeshrabiyaApi.kt

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApi.kt`

**New Methods** (V3 additions):

```kotlin
interface MeshrabiyaApi {
    // ... existing methods ...

    /**
     * Sets the user's global gateway preference for internet-bound traffic.
     * 
     * Note: Per-app VPN settings (from Orbot) supersede this preference.
     *
     * @param preference TOR_ONLY, CLEARNET_ONLY, or EITHER
     */
    suspend fun setGatewayPreference(preference: GatewayPreference)

    /**
     * Gets the current global gateway preference.
     *
     * @return Current preference, or DEFAULT (TOR_ONLY) if not set
     */
    suspend fun getGatewayPreference(): GatewayPreference

    /**
     * Reactive flow of gateway preference changes.
     * UI can observe this to update preference selector.
     */
    val gatewayPreferenceFlow: StateFlow<GatewayPreference>

    /**
     * Reactive flow of Tor service status.
     * true = Tor is ON, false = Tor is OFF/STARTING/STOPPING
     */
    val torStatus: StateFlow<Boolean>

    /**
     * Checks if a package is configured to use Tor via Orbot VPN settings.
     * 
     * @param packageName Android package name (e.g., "com.android.chrome")
     * @return true if package uses Tor, false if clearnet, null if unknown
     */
    suspend fun isPackageTorified(packageName: String): Boolean?
}
```

### MeshrabiyaApiImpl.kt Updates

**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt`

**Implementation**:

```kotlin
class MeshrabiyaApiImpl(
    private val context: Context,
    // ... other parameters
) : MeshrabiyaApi {

    // DataStore for preference persistence
    private val dataStore: DataStore<Preferences> = context.createDataStore(
        name = "meshrabiya_preferences"
    )

    private val GATEWAY_PREFERENCE_KEY = stringPreferencesKey("gateway_preference")

    // Tor status StateFlow
    private val _torStatus = MutableStateFlow(false)
    override val torStatus: StateFlow<Boolean> = _torStatus.asStateFlow()

    // Gateway preference StateFlow
    override val gatewayPreferenceFlow: StateFlow<GatewayPreference> = dataStore.data
        .map { prefs ->
            val prefName = prefs[GATEWAY_PREFERENCE_KEY]
            if (prefName != null) {
                GatewayPreference.valueOf(prefName)
            } else {
                GatewayPreference.DEFAULT  // TOR_ONLY
            }
        }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = GatewayPreference.DEFAULT
        )

    override suspend fun setGatewayPreference(preference: GatewayPreference) {
        dataStore.edit { prefs ->
            prefs[GATEWAY_PREFERENCE_KEY] = preference.name
        }
        logger.info { "Gateway preference set to: $preference" }
    }

    override suspend fun getGatewayPreference(): GatewayPreference {
        return gatewayPreferenceFlow.value
    }

    override suspend fun isPackageTorified(packageName: String): Boolean? {
        val prefs = Prefs.getSharedPrefs(context)
        
        val torifiedAppsString = prefs.getString(
            OrbotConstants.PREFS_KEY_TORIFIED,  // "PrefTord"
            ""
        ) ?: return null
        
        if (torifiedAppsString.isEmpty()) {
            return null  // No VPN settings configured
        }
        
        val torifiedPackages = torifiedAppsString
            .split("|")
            .filter { it.isNotBlank() }
        
        return torifiedPackages.contains(packageName)
    }

    // ... BroadcastReceiver registration from Section 2.6 ...
}
```

---

## 2.8 TESTING STRATEGY (PART 2)

### Unit Tests: GatewayPreferenceTest.kt

**File**: `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/api/GatewayPreferenceTest.kt`

```kotlin
package com.ustadmobile.meshrabiya.api

import org.junit.Assert.*
import org.junit.Test

class GatewayPreferenceTest {

    @Test
    fun defaultPreference_isTorOnly() {
        assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.DEFAULT)
    }

    @Test
    fun enumValues_haveExpectedNames() {
        assertEquals("TOR_ONLY", GatewayPreference.TOR_ONLY.name)
        assertEquals("CLEARNET_ONLY", GatewayPreference.CLEARNET_ONLY.name)
        assertEquals("EITHER", GatewayPreference.EITHER.name)
    }

    @Test
    fun valueOf_parsesFromString() {
        val pref = GatewayPreference.valueOf("TOR_ONLY")
        assertEquals(GatewayPreference.TOR_ONLY, pref)
    }
}
```

### Unit Tests: VpnRulesPrecedenceTest.kt

**File**: `Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/VpnRulesPrecedenceTest.kt`

```kotlin
package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.api.GatewayPreference
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VpnRulesPrecedenceTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var api: MeshrabiyaApiImpl

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        every { Prefs.getSharedPrefs(mockContext) } returns mockPrefs
    }

    @Test
    fun determineGatewayType_vpnRuleTor_supersedesClearnetPreference() = runBlocking {
        // Setup: Global preference = CLEARNET_ONLY
        api.setGatewayPreference(GatewayPreference.CLEARNET_ONLY)
        
        // Setup: Chrome is torified in VPN settings
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        // Create packet from Chrome (UID 10123)
        val uid = 10123
        val packageName = "com.android.chrome"
        
        // Expect: Gateway type = TOR (VPN rule supersedes CLEARNET_ONLY)
        val gatewayType = api.determineGatewayType(
            destinationAddr = INTERNET_ADDR,
            uid = uid
        )
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
    }

    @Test
    fun determineGatewayType_vpnRuleClearnet_supersedesTorPreference() = runBlocking {
        // Setup: Global preference = TOR_ONLY
        api.setGatewayPreference(GatewayPreference.TOR_ONLY)
        
        // Setup: WhatsApp is NOT torified in VPN settings
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        // Create packet from WhatsApp (UID 10456)
        val uid = 10456
        val packageName = "com.whatsapp"
        
        // Expect: Gateway type = CLEARNET (VPN rule supersedes TOR_ONLY)
        val gatewayType = api.determineGatewayType(
            destinationAddr = INTERNET_ADDR,
            uid = uid
        )
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, gatewayType)
    }

    @Test
    fun determineGatewayType_noVpnRule_usesGlobalPreference() = runBlocking {
        // Setup: Global preference = TOR_ONLY
        api.setGatewayPreference(GatewayPreference.TOR_ONLY)
        
        // Setup: No VPN settings configured
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        // Create packet with no UID (cannot apply VPN rules)
        val uid = null
        
        // Expect: Gateway type = TOR (fallback to global preference)
        val gatewayType = api.determineGatewayType(
            destinationAddr = INTERNET_ADDR,
            uid = uid
        )
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
    }
}
```

### Integration Tests: TorStatusMonitoringTest.kt

```kotlin
package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.Intent
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TorStatusMonitoringTest {

    @Test
    fun torStatusReceiver_onStatus_updatesTorStatus() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val api = MeshrabiyaApiImpl(mockContext, ...)
        
        // Initial state: Tor OFF
        assertEquals(false, api.torStatus.value)
        
        // Simulate Tor status broadcast: ON
        val intent = Intent("org.torproject.android.intent.action.STATUS")
        intent.putExtra("org.torproject.android.intent.extra.STATUS", "ON")
        
        api.torStatusReceiver.onReceive(mockContext, intent)
        
        // Verify: Tor status updated to ON
        assertEquals(true, api.torStatus.value)
    }

    @Test
    fun torStatusReceiver_onStatusStarting_remainsFalse() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val api = MeshrabiyaApiImpl(mockContext, ...)
        
        // Simulate Tor status broadcast: STARTING
        val intent = Intent("org.torproject.android.intent.action.STATUS")
        intent.putExtra("org.torproject.android.intent.extra.STATUS", "STARTING")
        
        api.torStatusReceiver.onReceive(mockContext, intent)
        
        // Verify: Tor status remains FALSE (conservative mapping)
        assertEquals(false, api.torStatus.value)
    }
}
```

---

## 2.9 IMPLEMENTATION CHECKLIST (PART 2)

### File: api/GatewayPreference.kt

- [ ] Create enum with TOR_ONLY, CLEARNET_ONLY, EITHER values
- [ ] Add DEFAULT companion constant = TOR_ONLY
- [ ] Add KDoc for each enum value
- [ ] Unit tests for enum values and default

### File: MeshrabiyaApi.kt

- [ ] Add `setGatewayPreference(preference: GatewayPreference)` method
- [ ] Add `getGatewayPreference(): GatewayPreference` method
- [ ] Add `gatewayPreferenceFlow: StateFlow<GatewayPreference>` property
- [ ] Add `torStatus: StateFlow<Boolean>` property
- [ ] Add `isPackageTorified(packageName: String): Boolean?` method
- [ ] Update interface KDoc

### File: MeshrabiyaApiImpl.kt

- [ ] Add DataStore for gateway preference persistence
- [ ] Implement `setGatewayPreference()` with DataStore.edit()
- [ ] Implement `getGatewayPreference()` reading from StateFlow
- [ ] Create `gatewayPreferenceFlow` from DataStore.data.map()
- [ ] Add `_torStatus` MutableStateFlow
- [ ] Implement `torStatus` as asStateFlow()
- [ ] Implement `isPackageTorified()` reading from SharedPreferences
- [ ] Add `registerTorStatusReceiver()` method
- [ ] Create BroadcastReceiver for "org.torproject.android.intent.action.STATUS"
- [ ] Implement conservative status mapping ("ON" = true, all else = false)
- [ ] Add receiver registration in `initMesh()`
- [ ] Add receiver unregistration in `shutdownMesh()`
- [ ] Test preference persistence (set → get → verify)
- [ ] Test Tor status monitoring (broadcast → StateFlow update)

### File: vnet/VirtualNode.kt

- [ ] Update `determineGatewayType()` from stub to full implementation
- [ ] Implement `isDestinationOnMesh()` using topology
- [ ] Implement precedence logic:
  - [ ] Check packet header gateway type first
  - [ ] Check VPN per-app rules second (supersedes preference)
  - [ ] Apply global preference third (fallback)
- [ ] Add `extractPacketUid()` stub (full implementation in Part 3)
- [ ] Add `getPackageNameForUid()` using PackageManager
- [ ] Add `isPackageTorified()` calling MeshrabiyaApi method
- [ ] Add logging for precedence decisions
- [ ] Test with mocked VPN settings
- [ ] Test with mocked preferences

### Testing

- [ ] Unit tests: GatewayPreference enum
- [ ] Unit tests: Preference persistence (DataStore)
- [ ] Unit tests: VPN rules precedence logic
- [ ] Unit tests: Tor status monitoring
- [ ] Integration tests: End-to-end gateway type determination
- [ ] Manual testing: Set VPN app, verify gateway type
- [ ] Manual testing: Change preference, verify fallback

---

## 2.10 PART 2 COMPLETION CRITERIA

Part 2 is complete when:

- [ ] GatewayPreference enum created and tested
- [ ] Preference persistence via DataStore implemented
- [ ] VPN per-app rules reading from SharedPreferences works
- [ ] Proxy rules precedence logic implemented (supersedes preference)
- [ ] Tor status monitoring via BroadcastReceiver functional
- [ ] API interface updated with new methods
- [ ] `determineGatewayType()` fully implemented with precedence
- [ ] All unit tests pass
- [ ] Integration tests pass
- [ ] Clean build succeeds

**Next**: Part 3 will implement gateway routing in VirtualNode.route() method.

---

## PART 2 SUMMARY

**Changes Implemented**:
1. Cross-module VPN settings access (SharedPreferences)
2. GatewayPreference enum (TOR_ONLY, CLEARNET_ONLY, EITHER)
3. Preference persistence via DataStore
4. Orbot VPN per-app rules reading ("PrefTord" key)
5. Proxy rules precedence logic (VPN rules supersede preference)
6. Tor status monitoring (BroadcastReceiver)
7. Dynamic gateway type determination (replaces Part 1 stub)

**Impact**:
- Enables per-app gateway routing (Chrome → Tor, WhatsApp → clearnet)
- Honors Orbot VPN settings (proxy rules supersede global preference)
- Reactive Tor status monitoring (updates gateway roles)

**Estimated Effort**: 8-10 hours
- Code changes: 4-5 hours
- Testing: 3-4 hours
- Integration & verification: 1-2 hours

**Dependencies for Part 3**:
- Gateway type determination functional
- VPN rules precedence implemented
- Tor status monitoring active

---

**END OF PART 2**

**Next**: Part 3 - Gateway Routing Implementation in VirtualNode.route()
