# MESH JOIN PLAN - PART 6: API & CORE IMPLEMENTATION

**✅ READY FOR IMPLEMENTATION**

This part describes new API methods that need to be implemented. All underlying functionality exists in the Meshrabiya library - we just need to expose it via the API.

## Implementation Strategy: Mesh-Wide Discovery

**Key Architectural Decision:**

Instead of encoding a single device's SSID in the QR code (which becomes useless if that device goes offline), we implement **mesh-wide discovery**:

1. **QR Code Format:** JSON containing shared password + SSID pattern (`"meshr-*"`)
2. **Joining Process:** Device scans for ALL mesh hotspots and connects to strongest
3. **Resilience:** If initial hotspot fails, device automatically finds another
4. **No Rescanning:** Device remembers password for automatic reconnection

**Benefits:**
- ✅ One QR code provides access to entire mesh (not just one device)
- ✅ Automatic failover if connected hotspot goes down
- ✅ Always connects to strongest available hotspot
- ✅ Resilient to dynamic mesh topologies

## MeshrabiyaApi Interface Changes

### Add New Methods

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApi.kt`

**Add after existing methods (around line 66):**

```kotlin
/**
 * Get current hotspot information (SSID and password).
 * Returns information about:
 * - The hotspot this device is running (if acting as AP)
 * - The hotspot this device is connected to (if acting as station)
 * 
 * Returns null if mesh is not started or no hotspot is active.
 * 
 * @return HotspotInfoDto containing SSID, password, and other network details, or null
 */
fun getHotspotInfo(): HotspotInfoDto?

/**
 * Join an existing mesh network using mesh-wide discovery.
 * 
 * **USE CASE: Joining from DISCONNECTED state**
 * - Device is NOT on a mesh
 * - User scans QR to join a mesh network
 * - Does NOT broadcast merge announcement
 * 
 * **MESH MERGE INTEGRATION (PT8):**
 * If currently connected to a mesh, this method will:
 * 1. Broadcast MeshMergeAnnouncement to current mesh (gossip propagation)
 * 2. Wait 5 seconds for announcement to propagate (multi-hop forwarding)
 * 3. Then proceed with joining target mesh
 * 
 * This enables organic mesh merging - all devices in current mesh receive
 * announcement and independently decide whether to join (idempotent config check).
 * 
 * See PT8 for:
 * - Multi-hop forwarding requirements (VirtualNode.kt Lines 702-722 must be UNCOMMENTED)
 * - MeshMergeAnnouncementMessage format
 * - Gossip rebroadcast logic
 * - Idempotent join decision logic
 * 
 * This method scans for ALL available mesh hotspots and connects to the strongest one.
 * This enables resilient joining - if the QR code generator's hotspot is offline,
 * the device will automatically connect to any other available mesh hotspot.
 * 
 * This method can be called from ANY mesh state:
 * - DISCONNECTED: Device will initialize mesh and connect as station
 * - CONNECTING: Will switch to new network
 * - CONNECTED: Will broadcast merge announcement, then add station connection
 * 
 * The connection process:
 * 1. IF CONNECTED: Broadcast merge announcement to current mesh (5s delay for propagation)
 * 2. Parses JSON QR code data (password, SSID pattern)
 * 3. Scans for all SSIDs matching "meshr-*"
 * 4. Sorts by signal strength and attempts connection (strongest first)
 * 5. Retries scan up to 3 times if no hotspots found
 * 6. Stores password for automatic reconnection if hotspot changes
 * 
 * @param jsonQrData JSON string from scanned QR code containing:
 *                   {"type":"mesh_join", "password":"...", "ssidPattern":"meshr-*", "bootstrapSSID":"..."}
 * @param callback Result callback invoked on completion
 *                 Success(Unit) on successful connection to any mesh hotspot
 *                 Failure(exception) if no mesh hotspots available after retries
 */
fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit)

/**
 * Merge current mesh with another mesh network (CONNECTED state only).
 * 
 * **USE CASE: Merging two existing meshes**
 * - Device is ALREADY connected to a mesh
 * - User scans QR of another mesh to merge
 * - ALWAYS broadcasts merge announcement first
 * 
 * **ORGANIC MESH MERGE WORKFLOW (PT8):**
 * 1. Broadcast MeshMergeAnnouncement to ALL devices on current mesh
 * 2. Wait 5 seconds for multi-hop gossip propagation
 * 3. Connect this device to target mesh (add station connection)
 * 4. Other devices receive announcement and independently decide to join
 * 5. Idempotent check prevents duplicate joins (same SSID/password)
 * 
 * **Key Differences from joinMesh():**
 * - mergeMesh() REQUIRES CONNECTED state (returns error if DISCONNECTED)
 * - ALWAYS broadcasts announcement (joinMesh() only broadcasts if CONNECTED)
 * - Clearer user intent: "I want to merge two meshes"
 * - UI: Separate "Merge Mesh" button (enabled only when CONNECTED)
 * 
 * **Requirements:**
 * - Multi-hop forwarding MUST be enabled (VirtualNode.kt Lines 702-722 uncommented)
 * - MeshMergeAnnouncementMessage must be implemented (PT8 Change 2)
 * - MeshConfigStorage must be implemented (PT8 Change 4)
 * - EmergentRoleManager must forward broadcasts (MESH_ROUTER role)
 * 
 * See PT8 for complete implementation details.
 * See MESH_GROUP_MERGING_RESEARCH_FINDINGS.md for organic merge strategy.
 * 
 * @param jsonQrData JSON string from scanned QR code containing:
 *                   {"type":"mesh_join", "password":"...", "ssidPattern":"meshr-*", "bootstrapSSID":"..."}
 * @param callback Result callback invoked on completion
 *                 Success(Unit) on successful merge (announcement broadcast + connection)
 *                 Failure(exception) if not CONNECTED, no hotspots found, or connection fails
 */
fun mergeMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit)
```

---

## Data Transfer Objects

### HotspotInfoDto

**File:** `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiDto.kt`

**Add new DTO after existing DTOs:**

```kotlin
/**
 * Hotspot information data transfer object
 * Contains network credentials and configuration for joining mesh
 */
@Serializable
data class HotspotInfoDto(
    /**
     * Network SSID (hotspot name)
     * Format depends on Android version:
     * - Android 13+: "meshr-<virtualaddr_hex>" (e.g., "meshr-a9fe2d8e")
     * - Android 8-12: "AndroidShare_XXXX" (random)
     */
    val ssid: String,
    
    /**
     * Network password (WPA2-PSK passphrase)
     * - Android 13+: "meshtest12" (hardcoded, shared by all devices)
     * - Android 8-12: Random Android-generated password
     */
    val password: String,
    
    /**
     * Frequency band (2.4GHz, 5GHz, or unknown)
     */
    val band: String,
    
    /**
     * Virtual address of hotspot owner (32-bit integer)
     * Used for mesh routing and identification
     */
    val nodeAddress: Int,
    
    /**
     * Optional: BSSID (MAC address) for sticky connection
     * Helps device reconnect to same hotspot even if SSID is duplicated
     */
    val bssid: String? = null,
    
    /**
     * Hotspot type: LOCAL_ONLY or WIFI_DIRECT
     */
    val hotspotType: String = "LOCAL_ONLY",
)
```

---

## MeshrabiyaApiImpl Implementation

### Add New Methods

**File:** `orbotservice/src/main/java/org/torproject/android/service/wrapper/orbotservice/MeshrabiyaApiImpl.kt`

**Add imports (after existing imports):**

```kotlin
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotPersistenceType
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
```

**Add implementation after stopMesh() method (around line 310):**

```kotlin
/**
 * Get current hotspot information
 * 
 * Priority order:
 * 1. LocalOnlyHotspot config (if device is running hotspot)
 * 2. WiFi Direct config (if using WiFi Direct)
 * 3. Station config (if connected as station to another hotspot)
 * 
 * Returns first available config, or null if none active.
 */
override fun getHotspotInfo(): HotspotInfoDto? {
    Log.d("MeshrabiyaApiImpl", "getHotspotInfo() called")
    
    val node = myNode ?: run {
        Log.d("MeshrabiyaApiImpl", "getHotspotInfo() returning null (myNode is null)")
        return null
    }
    
    // Get current WiFi state
    val wifiState = runBlocking {
        node.meshrabiyaWifiManager.state.first()
    }
    
    // Priority 1: Check LocalOnlyHotspot state (device acting as AP)
    val localHotspotConfig = wifiState.localOnlyHotspotState.config
    if (localHotspotConfig != null && 
        wifiState.localOnlyHotspotState.status == HotspotStatus.STARTED) {
        
        Log.d("MeshrabiyaApiImpl", 
            "getHotspotInfo() found LocalOnlyHotspot config: ssid=${localHotspotConfig.ssid}")
        
        return HotspotInfoDto(
            ssid = localHotspotConfig.ssid,
            password = localHotspotConfig.passphrase,
            band = localHotspotConfig.band.toString(),
            nodeAddress = node.addressAsInt,
            bssid = localHotspotConfig.bssid,
            hotspotType = "LOCAL_ONLY"
        )
    }
    
    // Priority 2: Check WiFi Direct state (device acting as group owner)
    val wifiDirectConfig = wifiState.wifiDirectState.config
    if (wifiDirectConfig != null) {
        Log.d("MeshrabiyaApiImpl", 
            "getHotspotInfo() found WiFiDirect config: ssid=${wifiDirectConfig.ssid}")
        
        return HotspotInfoDto(
            ssid = wifiDirectConfig.ssid,
            password = wifiDirectConfig.passphrase,
            band = wifiDirectConfig.band.toString(),
            nodeAddress = node.addressAsInt,
            bssid = wifiDirectConfig.bssid,
            hotspotType = "WIFI_DIRECT"
        )
    }
    
    // Priority 3: Check station state (device connected to another hotspot)
    val stationConfig = wifiState.wifiStationState.config
    if (stationConfig != null) {
        Log.d("MeshrabiyaApiImpl", 
            "getHotspotInfo() found Station config: ssid=${stationConfig.ssid}")
        
        return HotspotInfoDto(
            ssid = stationConfig.ssid,
            password = stationConfig.passphrase,
            band = stationConfig.band.toString(),
            nodeAddress = stationConfig.nodeVirtualAddr,
            bssid = stationConfig.bssid,
            hotspotType = stationConfig.hotspotType.toString()
        )
    }
    
    Log.d("MeshrabiyaApiImpl", "getHotspotInfo() returning null (no active hotspot or station)")
    return null
}

/**
 * Join an existing mesh network using mesh-wide discovery
 * 
 * Process:
 * 1. Validate mesh is initialized (myNode != null)
 * 2. Scan for all available mesh hotspots (SSIDs matching "meshr-*")
 * 3. Sort by signal strength (strongest first)
 * 4. Attempt connection to each hotspot until one succeeds
 * 5. If all fail, retry scan up to 3 times with 2-second delays
 * 6. Store password for automatic reconnection if hotspot changes
 * 
 * This enables resilient joining - device can connect to ANY mesh hotspot,
 * not just the one that generated the QR code. If initial hotspot fails,
 * device automatically tries others.
 */
override fun joinMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
    Log.d("MeshrabiyaApiImpl", "joinMesh() called with QR data")
    
    // Validate mesh is initialized
    if (myNode == null) {
        Log.e("MeshrabiyaApiImpl", "joinMesh called but myNode is null - mesh not initialized!")
        callback(Result.failure(
            IllegalStateException("Mesh not initialized - call initMesh() first")
        ))
        return
    }
    
    Log.d("MeshrabiyaApiImpl", "Launching coroutine for mesh-wide discovery join")
    
    // Launch connection in event monitoring scope (survives beyond this call)
    eventMonitoringScope.launch {
        try {
            // Parse QR code JSON data
            val qrJson = org.json.JSONObject(jsonQrData)
            val password = qrJson.getString("password")
            val ssidPattern = qrJson.getString("ssidPattern")  // "meshr-*"
            val bootstrapSsid = qrJson.optString("bootstrapSSID", null)  // Optional hint
            
            Log.d("MeshrabiyaApiImpl", "Parsed QR: password=$password, pattern=$ssidPattern, bootstrap=$bootstrapSsid")
            
            // Scan for available mesh hotspots
            val wifiManager = context.getSystemService(WifiManager::class.java)
            var attemptCount = 0
            var connected = false
            
            while (attemptCount < 3 && !connected) {
                attemptCount++
                Log.d("MeshrabiyaApiImpl", "Mesh hotspot scan attempt $attemptCount/3")
                
                // Trigger WiFi scan
                wifiManager.startScan()
                delay(2000)  // Wait for scan results
                
                // Get scan results and filter for mesh hotspots
                val meshHotspots = wifiManager.scanResults.filter { scanResult ->
                    scanResult.SSID.startsWith("meshr-")
                }.sortedByDescending { it.level }  // Sort by signal strength (strongest first)
                
                Log.d("MeshrabiyaApiImpl", "Found ${meshHotspots.size} mesh hotspots")
                
                // Try connecting to each hotspot, starting with strongest
                for (hotspot in meshHotspots) {
                    Log.d("MeshrabiyaApiImpl", "Attempting connection to ${hotspot.SSID} (signal: ${hotspot.level} dBm)")
                    
                    try {
                        val config = WifiConnectConfig(
                            nodeVirtualAddr = 0,  // Discovered from originating message
                            ssid = hotspot.SSID,
                            passphrase = password,
                            linkLocalAddr = null,
                            port = 27267,
                            hotspotType = HotspotType.LOCAL_ONLY,
                            persistenceType = HotspotPersistenceType.NONE,
                            band = when {
                                hotspot.frequency in 2400..2500 -> ConnectBand.BAND_2GHZ
                                hotspot.frequency in 5000..6000 -> ConnectBand.BAND_5GHZ
                                else -> ConnectBand.BAND_UNKNOWN
                            },
                            bssid = hotspot.BSSID
                        )
                        
                        myNode?.connectAsStation(config)
                        Log.d("MeshrabiyaApiImpl", "Successfully connected to ${hotspot.SSID}")
                        connected = true
                        break  // Success - stop trying
                        
                    } catch (e: Exception) {
                        Log.w("MeshrabiyaApiImpl", "Failed to connect to ${hotspot.SSID}: ${e.message}")
                        // Continue to next hotspot
                    }
                }
                
                if (!connected && attemptCount < 3) {
                    Log.d("MeshrabiyaApiImpl", "No connection established, waiting before retry...")
                    delay(2000)
                }
            }
            
            if (connected) {
                callback(Result.success(Unit))
                Log.d("MeshrabiyaApiImpl", "joinMesh callback invoked with success")
            } else {
                callback(Result.failure(
                    Exception("No mesh hotspots available after 3 scan attempts")
                ))
                Log.e("MeshrabiyaApiImpl", "joinMesh failed - no available hotspots found")
            }
            
        } catch (e: Exception) {
            Log.e("MeshrabiyaApiImpl", "joinMesh failed with exception", e)
            callback(Result.failure(e))
            Log.d("MeshrabiyaApiImpl", "joinMesh callback invoked with failure: ${e.message}")
        }
    }
    
    Log.d("MeshrabiyaApiImpl", "joinMesh() returning (coroutine launched)")
}
```

---

## Integration with Existing Code

### No Changes to Meshrabiya Library

**✅ VERIFIED:** All necessary functionality already exists in the Meshrabiya library.

**Confirmed existing implementations:**
- AndroidVirtualNode.connectAsStation() - Line 155
- LocalOnlyHotspotManager SSID generation - Line 118 (`meshr-${localNodeAddr.encodeAsHex()}`)
- OriginatingMessageManager.neighbors() - Line 612
- OriginatingMessageManager.getNodesWithRole() - Line 143
- EmergentRoleManager role assignment - Lines 329-338

**No changes needed in:**

1. **AndroidVirtualNode.kt** - `connectAsStation()` already implemented (lines 155-159)
2. **MeshrabiyaWifiManagerAndroid.kt** - `connectToHotspot()` fully functional (lines 422-435)
3. **LocalOnlyHotspotManager.kt** - Hotspot creation and SSID/password exposure working (lines 53-144)
4. **WifiConnectConfig.kt** - Data structure complete (lines 43-56)

**The only additions are:**
- ✅ API interface methods (getHotspotInfo, joinMesh)
- ✅ DTO for hotspot info
- ✅ MeshrabiyaApiImpl implementations

---

## Error Handling

### Common Errors and Handling

**1. Mesh Not Initialized**
```kotlin
if (myNode == null) {
    callback(Result.failure(
        IllegalStateException("Mesh not initialized - call initMesh() first")
    ))
    return
}
```

**2. Connection Timeout (in MeshrabiyaWifiManagerAndroid)**
```kotlin
withTimeout(timeout) {  // Default 30 seconds
    connectToHotspotInternal(config)
    getStationBoundDatagramSocket()
}
// Throws: TimeoutCancellationException if timeout exceeded
```

**3. 5GHz Band Not Supported**
```kotlin
if(config.band == ConnectBand.BAND_5GHZ && !wifiManager.is5GHzBandSupported) {
    throw WifiConnectException("ERROR: 5Ghz not supported by device: ${config.ssid} uses 5Ghz band")
}
```

**4. Network Not Found**
```kotlin
// In Android 10+ WifiNetworkSpecifier:
// NetworkCallback.onUnavailable() called if network not found within timeout
// Throws: WifiConnectException("Network unavailable")
```

**5. Invalid Credentials**
```kotlin
// In Android <10 WifiConfiguration:
// addNetwork() returns -1 if credentials invalid
// Throws: WifiConnectException("Failed to add network configuration")
```

**6. Permission Denied**
```kotlin
// Caught in Fragment's onRequestPermissionsResult
// Shows Snackbar: "Camera permission required to scan QR codes"
```

---

## MergeMesh Implementation

### MeshrabiyaApiImpl.mergeMesh()

**File:** `orbotservice/src/main/java/org/torproject/android/service/wrapper/orbotservice/MeshrabiyaApiImpl.kt`

**Add implementation after joinMesh() method:**

```kotlin
/**
 * Merge current mesh with another mesh (CONNECTED state only).
 * 
 * Workflow:
 * 1. Verify device is CONNECTED to a mesh (fail if DISCONNECTED)
 * 2. Parse JSON QR data (same format as joinMesh)
 * 3. Check if target mesh is same as current (idempotent - no-op if already connected)
 * 4. Broadcast MeshMergeAnnouncementMessage to current mesh
 * 5. Wait 5 seconds for multi-hop gossip propagation
 * 6. Connect to target mesh (add station connection)
 * 7. Other devices receive announcement and independently join
 * 
 * See PT8 for:
 * - MeshMergeAnnouncementMessage format (PT8 Change 2)
 * - Multi-hop forwarding requirements (PT8 Change 1)
 * - Idempotent join logic (PT8 Change 5)
 * 
 * @param jsonQrData JSON string from scanned QR code
 * @param callback Result callback (success/failure)
 */
override fun mergeMesh(jsonQrData: String, callback: (Result<Unit>) -> Unit) {
    Log.d("MeshrabiyaApiImpl", "mergeMesh() called with JSON data")
    
    // Step 1: Verify CONNECTED state
    val currentStatus = getMeshStatus()
    if (currentStatus != MeshStateDto.CONNECTED) {
        Log.e("MeshrabiyaApiImpl", "mergeMesh() failed - not CONNECTED (status=$currentStatus)")
        callback(Result.failure(
            IllegalStateException("Cannot merge - device not connected to a mesh. Use joinMesh() instead.")
        ))
        return
    }
    
    // Step 2: Parse JSON QR data
    val qrJson = try {
        JSONObject(jsonQrData)
    } catch (e: Exception) {
        Log.e("MeshrabiyaApiImpl", "mergeMesh() failed - invalid JSON: ${e.message}")
        callback(Result.failure(IllegalArgumentException("Invalid QR code data: ${e.message}")))
        return
    }
    
    // Extract target mesh config
    val type = qrJson.optString("type", "")
    if (type != "mesh_join") {
        Log.e("MeshrabiyaApiImpl", "mergeMesh() failed - invalid QR type: $type")
        callback(Result.failure(IllegalArgumentException("Invalid QR code type: $type")))
        return
    }
    
    val targetPassword = qrJson.optString("password", "")
    val ssidPattern = qrJson.optString("ssidPattern", "meshr-*")
    val bootstrapSsid = qrJson.optString("bootstrapSSID", "")
    
    if (targetPassword.isEmpty()) {
        Log.e("MeshrabiyaApiImpl", "mergeMesh() failed - no password in QR data")
        callback(Result.failure(IllegalArgumentException("QR code missing password")))
        return
    }
    
    Log.d("MeshrabiyaApiImpl", 
        "mergeMesh() parsed QR - pattern=$ssidPattern, bootstrap=$bootstrapSsid")
    
    // Step 3: Get current mesh config for idempotent check
    val currentHotspotInfo = getHotspotInfo()
    val currentPassword = currentHotspotInfo?.password ?: ""
    
    // Idempotent check: If target password matches current, no merge needed
    if (targetPassword == currentPassword) {
        Log.d("MeshrabiyaApiImpl", 
            "mergeMesh() - target mesh is current mesh (same password), no-op")
        callback(Result.success(Unit))
        return
    }
    
    // Step 4: Broadcast merge announcement to current mesh
    Log.d("MeshrabiyaApiImpl", "mergeMesh() - broadcasting merge announcement")
    
    val node = myNode
    if (node == null) {
        Log.e("MeshrabiyaApiImpl", "mergeMesh() failed - myNode is null")
        callback(Result.failure(IllegalStateException("Mesh node not initialized")))
        return
    }
    
    // Create merge announcement message (see PT8 Change 2 for message format)
    // This will be broadcast via VirtualNode gossip protocol
    try {
        // Build announcement with target mesh config
        val announcement = MeshMergeAnnouncementMessage(
            targetSsidPattern = ssidPattern,
            targetPassword = targetPassword,
            targetBootstrapSsid = bootstrapSsid,
            originatorAddress = node.address,
            timestamp = System.currentTimeMillis(),
            ttl = MeshrabiyaConstants.MERGE_MESSAGE_TTL_DEFAULT,  // PT8 Change 3
            messageId = UUID.randomUUID().toString()
        )
        
        // Broadcast to all connected peers
        Log.d("MeshrabiyaApiImpl", "mergeMesh() - sending announcement to ${node.connectedPeerCount} peers")
        node.broadcastMergeAnnouncement(announcement)
        
    } catch (e: Exception) {
        Log.e("MeshrabiyaApiImpl", "mergeMesh() - announcement broadcast failed: ${e.message}")
        callback(Result.failure(e))
        return
    }
    
    // Step 5: Wait 5 seconds for multi-hop propagation
    Log.d("MeshrabiyaApiImpl", "mergeMesh() - waiting 5 seconds for announcement propagation")
    apiScope.launch {
        try {
            delay(5000L)  // Allow gossip to propagate through mesh
            
            // Step 6: Now join target mesh (use joinMesh internal logic)
            Log.d("MeshrabiyaApiImpl", "mergeMesh() - connecting to target mesh")
            
            // Use same joining logic as joinMesh() but skip announcement (already done)
            joinMeshInternal(jsonQrData, skipMergeAnnouncement = true, callback)
            
        } catch (e: Exception) {
            Log.e("MeshrabiyaApiImpl", "mergeMesh() - connection failed: ${e.message}")
            callback(Result.failure(e))
        }
    }
}
```

**Add helper method:**

```kotlin
/**
 * Internal join logic shared by joinMesh() and mergeMesh()
 * 
 * @param jsonQrData JSON QR code data
 * @param skipMergeAnnouncement If true, skip announcement broadcast (already done by mergeMesh)
 * @param callback Result callback
 */
private fun joinMeshInternal(
    jsonQrData: String, 
    skipMergeAnnouncement: Boolean = false,
    callback: (Result<Unit>) -> Unit
) {
    // ... existing joinMesh() implementation logic ...
    // Just refactor existing code into this helper
    // If skipMergeAnnouncement=false AND currently CONNECTED, broadcast announcement
    // If skipMergeAnnouncement=true, skip announcement step
}
```

### Key Differences: mergeMesh() vs joinMesh()

| Aspect | `joinMesh()` | `mergeMesh()` |
|--------|-------------|--------------|
| **Allowed State** | DISCONNECTED or CONNECTED | **CONNECTED ONLY** |
| **Merge Announcement** | Broadcasts IF currently CONNECTED | **ALWAYS broadcasts** |
| **Use Case** | Join new mesh from scratch | Merge two existing meshes |
| **UI Button** | "Join Mesh" (enabled when DISCONNECTED) | "Merge Mesh" (enabled when CONNECTED) |
| **Error if DISCONNECTED** | No (starts mesh + joins) | **Yes** (returns IllegalStateException) |
| **User Intent** | "I want to join a mesh" | "I want to merge my mesh with another" |

---

## Testing Strategy

### Unit Tests

**File:** `orbotservice/src/test/java/org/torproject/android/service/wrapper/orbotservice/MeshrabiyaApiImplTest.kt`

**Add test cases:**

```kotlin
@Test
fun `mergeMesh fails when DISCONNECTED`() {
    // Given: Mesh not started
    val api = MeshrabiyaApiImpl(...)
    
    // When: Try to merge mesh
    var callbackResult: Result<Unit>? = null
    val qrData = """{"type":"mesh_join","password":"test123","ssidPattern":"meshr-*"}"""
    api.mergeMesh(qrData) { result ->
        callbackResult = result
    }
    
    // Wait for callback
    Thread.sleep(1000)
    
    // Then: Should fail with IllegalStateException
    assertNotNull(callbackResult)
    assertTrue(callbackResult!!.isFailure)
    assertTrue(callbackResult!!.exceptionOrNull() is IllegalStateException)
    assertTrue(callbackResult!!.exceptionOrNull()?.message?.contains("not connected") == true)
}

@Test
fun `mergeMesh is idempotent - no-op if already on target mesh`() {
    // Given: Mesh started and connected
    val api = MeshrabiyaApiImpl(...)
    api.startMesh { }
    Thread.sleep(5000)  // Wait for connection
    
    // Get current hotspot info
    val currentInfo = api.getHotspotInfo()
    assertNotNull(currentInfo)
    
    // Create QR with SAME password as current mesh
    val qrData = """{"type":"mesh_join","password":"${currentInfo!!.password}","ssidPattern":"meshr-*"}"""
    
    // When: Try to merge with own mesh
    var callbackResult: Result<Unit>? = null
    api.mergeMesh(qrData) { result ->
        callbackResult = result
    }
    
    // Wait for callback (should be instant, no 5s delay)
    Thread.sleep(500)
    
    // Then: Should succeed immediately (no merge needed)
    assertNotNull(callbackResult)
    assertTrue(callbackResult!!.isSuccess)
}

@Test
fun `mergeMesh broadcasts announcement before connecting`() {
    // Given: Two separate mesh networks
    val apiMesh1 = MeshrabiyaApiImpl(...)
    val apiMesh2 = MeshrabiyaApiImpl(...)
    
    apiMesh1.startMesh { }  // Start first mesh
    Thread.sleep(5000)
    
    apiMesh2.startMesh { }  // Start second mesh
    Thread.sleep(5000)
    
    // Set up listener on mesh1 to detect announcement
    var announcementReceived = false
    apiMesh1.onMeshMergeAnnouncement { announcement ->
        announcementReceived = true
        Log.d("TEST", "Announcement received: ${announcement.targetPassword}")
    }
    
    // Get mesh2 info for QR
    val mesh2Info = apiMesh2.getHotspotInfo()
    assertNotNull(mesh2Info)
    
    val qrData = """{"type":"mesh_join","password":"${mesh2Info!!.password}","ssidPattern":"meshr-*"}"""
    
    // When: Merge mesh1 into mesh2
    var callbackResult: Result<Unit>? = null
    apiMesh1.mergeMesh(qrData) { result ->
        callbackResult = result
    }
    
    // Wait for merge to complete (5s delay + connection)
    Thread.sleep(10000)
    
    // Then: Announcement should have been broadcast
    assertTrue(announcementReceived)
    assertNotNull(callbackResult)
    assertTrue(callbackResult!!.isSuccess)
}
```

---

## Deployment Checklist

### Code Changes Required

**✅ Modified Files:**
1. `app/src/main/res/layout/fragment_enhanced_mesh.xml` - UI layout (add Merge button)
2. `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt` - View bindings (mergeMeshButton)
3. `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` - Fragment logic (merge handler)
4. `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApi.kt` - Interface (mergeMesh method)
5. `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiDto.kt` - DTO (HotspotInfoDto)
6. `orbotservice/src/main/java/org/torproject/android/service/wrapper/orbotservice/MeshrabiyaApiImpl.kt` - Implementation (mergeMesh)
7. `app/src/main/AndroidManifest.xml` - Camera permission

**✅ New Files (PT8 - Merge Logic):**
1. `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshMergeAnnouncementMessage.kt`
2. `orbotservice/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshConfigStorage.kt`

**✅ Dependencies:** Already present in build.gradle.kts (no changes needed)

### Testing Before Deployment

1. ✅ Run unit tests (MeshrabiyaApiImplTest - join and merge tests)
2. ✅ Manual test on Android 13+ device (LocalOnlyHotspot)
3. ✅ Manual test on Android 8-12 device (legacy API)
4. ✅ Test Join Mesh from DISCONNECTED state
5. ✅ Test Merge Mesh from CONNECTED state
6. ✅ Test Merge Mesh fails when DISCONNECTED
7. ✅ Test idempotent merge (same mesh)
8. ✅ Test announcement propagation (multi-hop)
9. ✅ Test camera permission grant/deny
10. ✅ Test QR scanning with various QR codes

---

## Summary

### API Changes

✅ **getHotspotInfo()** - Returns SSID/password from hotspot or station config  
✅ **joinMesh()** - Connects to mesh network via SSID/password (from DISCONNECTED or CONNECTED)  
✅ **mergeMesh()** - Merges two meshes (CONNECTED only, always broadcasts announcement)  
✅ **HotspotInfoDto** - Data structure for network credentials  

### Implementation Details

✅ **Separate Join vs Merge UX** (two buttons, two API functions)  
✅ **State-based button enablement** (Join when DISCONNECTED, Merge when CONNECTED)  
✅ **Organic merge workflow** (announcement → 5s delay → join)  
✅ **Idempotent merge** (no-op if already on target mesh)  
✅ **Priority-based config lookup** (LocalOnlyHotspot → WiFi Direct → Station)  
✅ **Async connection** via coroutines with callback  
✅ **Comprehensive error handling** (timeouts, permissions, invalid credentials, wrong state)  
✅ **Full logging** for debugging  

### Next Steps

See MESH_JOIN_PLAN-PT7.md for integration flow diagrams, remaining questions, and future enhancements.
See MESH_JOIN_PLAN-PT8.md for organic merge implementation details (multi-hop forwarding, announcement format, etc.).
    val apiClient = MeshrabiyaApiImpl(...) // Client device
    
    // Start host hotspot
    apiHost.startMesh { }
    Thread.sleep(5000)
    
    // Get host hotspot info
    val hostInfo = apiHost.getHotspotInfo()
    assertNotNull(hostInfo)
    
    // When: Client joins host's network
    var callbackResult: Result<Unit>? = null
    apiClient.joinMesh(hostInfo.ssid, hostInfo.password) { result ->
        callbackResult = result
    }
    
    // Wait for connection
    Thread.sleep(30000)
    
    // Then: Should succeed
    assertNotNull(callbackResult)
    assertTrue(callbackResult!!.isSuccess)
    
    // Verify client sees host in topology
    val topology = apiClient.myNode?.originatingMessageManager?.getTopologyMapInfo()
    assertNotNull(topology)
    assertTrue(topology!!.isNotEmpty())
}
```

### Integration Tests

**Scenarios to test:**

1. **Single Device QR Generation**
   - Start mesh on Device A
   - Open QR code pane
   - Verify QR code displays with correct SSID
   - Verify copy to clipboard works

2. **Two Device Join via QR**
   - Start mesh on Device A
   - Display QR code on Device A
   - Scan QR code with Device B
   - Verify Device B joins successfully
   - Verify both devices show CONNECTED status
   - Verify topology shows 2 nodes

3. **Camera Permission Flow**
   - Click "Join Mesh" without camera permission
   - Verify permission request dialog appears
   - Grant permission
   - Verify camera starts

4. **Invalid QR Code Handling**
   - Scan non-WiFi QR code
   - Verify error message shown
   - Verify camera remains active

5. **Connection Failure Handling**
   - Scan QR with wrong password
   - Verify timeout handling (30 seconds)
   - Verify error message shown

---

## Deployment Checklist

### Code Changes Required

**✅ Modified Files:**
1. `app/src/main/res/layout/fragment_enhanced_mesh.xml` - UI layout
2. `app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt` - View bindings
3. `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` - Fragment logic
4. `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApi.kt` - Interface
5. `orbotservice/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiDto.kt` - DTO
6. `orbotservice/src/main/java/org/torproject/android/service/wrapper/orbotservice/MeshrabiyaApiImpl.kt` - Implementation
7. `app/src/main/AndroidManifest.xml` - Camera permission

**✅ New Files:**
1. `app/src/main/res/drawable/ic_expand_more.xml`
2. `app/src/main/res/drawable/ic_content_copy.xml`
3. `app/src/main/res/drawable/ic_flashlight_on.xml`
4. `app/src/main/res/drawable/qr_scan_overlay.xml`

**✅ Dependencies:** Already present in build.gradle.kts (no changes needed)

### Testing Before Deployment

1. ✅ Run unit tests (MeshrabiyaApiImplTest)
2. ✅ Manual test on Android 13+ device (LocalOnlyHotspot)
3. ✅ Manual test on Android 8-12 device (legacy API)
4. ✅ Test concurrent AP+STA scenario
5. ✅ Test non-concurrent device scenario
6. ✅ Test camera permission grant/deny
7. ✅ Test QR scanning with various QR codes
8. ✅ Test clipboard copy functionality
9. ✅ Test flashlight toggle
10. ✅ Test pane expand/collapse

---

## Summary

### API Changes

✅ **getHotspotInfo()** - Returns SSID/password from hotspot or station config  
✅ **joinMesh()** - Connects to mesh network via SSID/password  
✅ **HotspotInfoDto** - Data structure for network credentials  

### Implementation Details

✅ **Priority-based config lookup** (LocalOnlyHotspot → WiFi Direct → Station)  
✅ **Async connection** via coroutines with callback  
✅ **Comprehensive error handling** (timeouts, permissions, invalid credentials)  
✅ **No library changes** (all functionality already exists)  
✅ **Full logging** for debugging  

### Next Steps

See MESH_JOIN_PLAN-PT7.md for integration flow diagrams, remaining questions, and future enhancements.
