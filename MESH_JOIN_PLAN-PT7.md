# MESH JOIN PLAN - PART 7: INTEGRATION, TESTING & REMAINING UNCERTAINTIES

## Integration Flow Diagrams

### QR Code Display Flow

```
┌────────────────────────────────────────────────────────────────┐
│ USER: Taps "Start Mesh" or header (when already connected)    │
└───────────────────────────────┬────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────┐
│ EnhancedMeshFragment: Detects mesh CONNECTED/CONNECTING       │
│ - Calls updateUI() method                                      │
│ - Auto-expands pane if not already expanded                    │
└───────────────────────────────┬────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────┐
│ Fragment: Calls meshrabiyaApi.getHotspotInfo()                │
│ - Returns HotspotInfoDto or null                               │
└───────────────────────────────┬────────────────────────────────┘
                                │
                    ┌───────────┴────────────┐
                    │                        │
                    ▼                        ▼
        ┌────────────────────┐  ┌────────────────────────┐
        │ HotspotInfo Found  │  │ HotspotInfo == null    │
        └────────┬───────────┘  └───────────┬────────────┘
                 │                           │
                 ▼                           ▼
┌────────────────────────────────┐  ┌──────────────────────┐
│ expandPane(showCamera = false) │  │ Show error Snackbar  │
│ - Shows QR container           │  │ "No mesh active"     │
│ - Hides camera container       │  │ collapsePane()       │
└────────────────┬───────────────┘  └──────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────────────────────────────┐
│ generateAndDisplayQRCode(ssid, password)                       │
│ 1. Creates WiFi QR format: WIFI:T:WPA;S:ssid;P:password;;    │
│ 2. Uses QRCode library to generate bitmap                     │
│ 3. Renders to ImageView                                        │
│ 4. Updates network info text (SSID)                           │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────┐
│ USER: Can now tap "Copy" button or share screen with others   │
│ - Other devices scan QR code to join                          │
└────────────────────────────────────────────────────────────────┘
```

---

### Scan and Join Flow

```
┌────────────────────────────────────────────────────────────────┐
│ USER: Taps "Join Mesh" button                                 │
└───────────────────────────────┬────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────┐
│ EnhancedMeshFragment: Check camera permission                 │
└───────────────────────────────┬────────────────────────────────┘
                                │
                    ┌───────────┴────────────┐
                    │                        │
                    ▼                        ▼
        ┌────────────────────┐  ┌────────────────────────┐
        │ Permission Granted │  │ Permission NOT Granted │
        └────────┬───────────┘  └───────────┬────────────┘
                 │                           │
                 │                           ▼
                 │              ┌─────────────────────────┐
                 │              │ requestPermissions()    │
                 │              │ → onRequestPermissions  │
                 │              │   Result callback       │
                 │              └────────────┬────────────┘
                 │                           │
                 │           ┌───────────────┴───────────┐
                 │           │                           │
                 │           ▼                           ▼
                 │  ┌─────────────────┐     ┌────────────────────┐
                 │  │ Granted → START │     │ Denied → Snackbar  │
                 │  └─────────┬───────┘     │ "Permission needed"│
                 │            │              │ with Settings link │
                 └────────────┤              └────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────┐
│ expandPane(showCamera = true)                                  │
│ - Shows camera container                                       │
│ - Hides QR container                                           │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────┐
│ startQRScanning()                                              │
│ 1. ProcessCameraProvider.getInstance()                        │
│ 2. Create Preview use case → bind to PreviewView              │
│ 3. Create ImageAnalysis use case → bind to QRCodeAnalyzer     │
│ 4. Bind both to lifecycle                                     │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────┐
│ QRCodeAnalyzer: ML Kit barcode scanning (every 300ms)         │
│ - Analyzes camera frames for QR codes                         │
│ - Throttled to avoid overprocessing                           │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                    ▼                         ▼
        ┌────────────────────┐    ┌──────────────────┐
        │ QR Code Detected   │    │ No QR / Invalid  │
        └────────┬───────────┘    │ Keep scanning... │
                 │                 └──────────────────┘
                 ▼
┌────────────────────────────────────────────────────────────────┐
│ handleScannedQRCode(qrData)                                    │
│ 1. Check cooldown (3 seconds since last scan)                 │
│ 2. Parse WiFi QR format: WIFI:T:WPA;S:ssid;P:password;;      │
│ 3. Extract SSID and password                                  │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                    ▼                         ▼
        ┌────────────────────┐    ┌──────────────────────┐
        │ Valid WiFi QR      │    │ Invalid Format       │
        └────────┬───────────┘    │ Show error Snackbar  │
                 │                 │ Continue scanning    │
                 │                 └──────────────────────┘
                 ▼
┌────────────────────────────────────────────────────────────────┐
│ stopQRScanning()                                               │
│ - Unbind camera                                                │
│ - Release resources                                            │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────┐
│ meshrabiyaApi.joinMesh(ssid, password, callback)              │
│ 1. Validate myNode != null                                    │
│ 2. Create WifiConnectConfig                                   │
│ 3. Call myNode.connectAsStation(config)                       │
│ 4. Wait for connection (30 second timeout)                    │
└────────────────────────────────┬───────────────────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                    ▼                         ▼
        ┌────────────────────┐    ┌──────────────────────┐
        │ Connection Success │    │ Connection Failed    │
        └────────┬───────────┘    │ - Timeout            │
                 │                 │ - Wrong password     │
                 │                 │ - Network not found  │
                 │                 └───────────┬──────────┘
                 │                             │
                 ▼                             ▼
┌──────────────────────────────┐  ┌───────────────────────┐
│ Snackbar: "Successfully      │  │ Snackbar: Error msg   │
│ joined mesh network!"        │  │ collapsePane()        │
│ collapsePane()               │  │ Allow retry           │
│ updateUI() → show CONNECTED  │  └───────────────────────┘
└──────────────┬───────────────┘
               │
               ▼
┌────────────────────────────────────────────────────────────────┐
│ After 1 second: Auto-expand and show QR of joined network     │
│ - expandPane(showCamera = false)                              │
│ - generateAndDisplayQRCode(ssid, password)                    │
│ - User now has same network info for sharing                  │
└────────────────────────────────────────────────────────────────┘
```

---

## State Management

### Fragment State Variables

**EnhancedMeshFragment.kt:**

```kotlin
// ========================================
// STATE VARIABLES FOR QR/CAMERA
// ========================================

/** Camera executor for background processing */
private lateinit var cameraExecutor: ExecutorService

/** Current camera instance (for flashlight control) */
private var currentCamera: Camera? = null

/** Is camera currently active and scanning? */
private var isCameraActive: Boolean = false

/** Is user in "Join Mesh" mode (scanning vs. displaying QR)? */
private var isJoinMeshMode: Boolean = false

/** Is flashlight currently on? */
private var isFlashlightOn: Boolean = false

/** Last scanned QR code data (to prevent duplicate processing) */
private var lastScannedQRCode: String? = null

/** End time of current scan cooldown */
private var scanCooldownEndTime: Long = 0
```

### State Transitions

```
┌───────────────────────────────────────────────────────────────┐
│                      INITIAL STATE                            │
│ - isCameraActive = false                                      │
│ - isJoinMeshMode = false                                      │
│ - Pane collapsed                                              │
└───────────────────────────┬───────────────────────────────────┘
                            │
        ┌───────────────────┴────────────────────┐
        │                                        │
        ▼                                        ▼
┌─────────────────────┐              ┌─────────────────────────┐
│ USER: Tap header    │              │ USER: Tap "Join Mesh"   │
│ (Show QR)           │              │ (Scan QR)               │
└──────────┬──────────┘              └──────────┬──────────────┘
           │                                    │
           ▼                                    ▼
┌─────────────────────────────────┐  ┌─────────────────────────┐
│ QR DISPLAY STATE                │  │ CAMERA SCANNING STATE   │
│ - isCameraActive = false        │  │ - isCameraActive = true │
│ - isJoinMeshMode = false        │  │ - isJoinMeshMode = true │
│ - Pane expanded, QR visible     │  │ - Pane expanded, camera │
│ - Camera container hidden       │  │ - QR container hidden   │
└──────────┬──────────────────────┘  └──────────┬──────────────┘
           │                                    │
           │  ┌─────────────────────────────────┤
           │  │ QR detected & parsed            │
           │  ▼                                 │
           │  ┌─────────────────────────────────┴──┐
           │  │ CONNECTING STATE                   │
           │  │ - Camera stopped                   │
           │  │ - Pane shows "Joining..." overlay  │
           │  │ - Waiting for connection result    │
           │  └───────────┬────────────────────────┘
           │              │
           │  ┌───────────┴───────────┐
           │  │                       │
           │  ▼                       ▼
           │  ┌──────────┐    ┌───────────────┐
           │  │ SUCCESS  │    │ FAILURE       │
           │  └────┬─────┘    └────┬──────────┘
           │       │               │
           │       ▼               ▼
           │  ┌─────────────────────────────┐
           │  │ CONNECTED QR DISPLAY STATE  │
           │  │ - Shows QR of joined network│
           │  │ - User can share with others│
           │  └─────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────┐
│ USER: Tap header again or "Cancel"                          │
│ → Collapse pane → Return to INITIAL STATE                   │
└──────────────────────────────────────────────────────────────┘
```

---

## Testing Plan

### Phase 1: Unit Tests

**File:** `orbotservice/src/test/java/org/torproject/android/service/wrapper/orbotservice/MeshrabiyaApiImplTest.kt`

**Test cases:**

1. ✅ `getHotspotInfo_whenMeshNotStarted_returnsNull`
2. ✅ `getHotspotInfo_whenHotspotActive_returnsConfig`
3. ✅ `getHotspotInfo_whenConnectedAsStation_returnsStationConfig`
4. ✅ `joinMesh_whenMeshNotInitialized_fails`
5. ✅ `joinMesh_withValidCredentials_succeeds`
6. ✅ `joinMesh_withInvalidCredentials_fails`
7. ✅ `joinMesh_withTimeout_fails`

**File:** `app/src/test/java/org/torproject/android/ui/mesh/EnhancedMeshFragmentTest.kt`

**Test cases:**

1. ✅ `generateQRCode_withValidSSID_generatesImage`
2. ✅ `parseWiFiQR_withValidFormat_extractsCredentials`
3. ✅ `parseWiFiQR_withInvalidFormat_returnsNull`
4. ✅ `handleScannedQR_withCooldown_ignoresDuplicate`
5. ✅ `expandPane_withCamera_showsCameraContainer`
6. ✅ `expandPane_withQR_showsQRContainer`
7. ✅ `collapsePane_stopsCamera`

---

### Phase 2: Integration Tests (Manual)

#### Test 1: QR Code Display

**Prerequisites:**
- 1 Android device (13+ recommended)
- Orbot installed with mesh functionality

**Steps:**
1. Open Orbot → Enhanced Mesh tab
2. Tap "Start Mesh"
3. Wait for CONNECTED status
4. Verify QR code pane auto-expands
5. Verify QR code displays with valid image
6. Verify network info shows SSID (starts with "meshr-" or "AndroidShare_")
7. Tap "Copy" button
8. Verify "Network info copied" Snackbar appears
9. Paste into notes app → Verify SSID and password visible
10. Tap header to collapse pane
11. Tap header again to re-expand → Verify QR persists

**Expected Results:**
✅ QR code displays immediately when mesh starts  
✅ Network info matches actual hotspot SSID  
✅ Copy to clipboard works  
✅ Pane expands/collapses smoothly  

---

#### Test 2: Join Mesh via QR Code

**Prerequisites:**
- 2 Android devices (A = host, B = client)
- Both devices on different WiFi networks or disconnected

**Steps:**
1. Device A: Start mesh → Display QR code
2. Device B: Open Orbot → Enhanced Mesh tab
3. Device B: Tap "Join Mesh" button
4. Device B: Grant camera permission if requested
5. Device B: Point camera at Device A's QR code
6. Device B: Verify "QR Code detected! Joining..." appears
7. Device B: Wait up to 30 seconds for connection
8. Device B: Verify "Successfully joined mesh network!" Snackbar
9. Device B: Verify status changes to CONNECTED
10. Device B: Verify QR code auto-displays (same network as Device A)
11. Device A: Check mesh status → Verify shows 2 connected nodes
12. Device B: Check mesh status → Verify shows 2 connected nodes

**Expected Results:**
✅ Camera starts on "Join Mesh" click  
✅ QR code detected within 2 seconds of pointing  
✅ Connection succeeds within 30 seconds  
✅ Both devices show CONNECTED  
✅ Topology shows 2 nodes on both devices  
✅ Device B can now display same QR for Device C to join  

---

#### Test 3: Invalid QR Code Handling

**Prerequisites:**
- 1 Android device
- Non-WiFi QR code (e.g., URL, vCard)

**Steps:**
1. Device: Tap "Join Mesh"
2. Device: Point camera at non-WiFi QR code
3. Verify "Invalid QR code format" Snackbar appears
4. Verify camera remains active (can scan again)
5. Scan valid WiFi QR → Verify join proceeds normally

**Expected Results:**
✅ Invalid QR shows error without crashing  
✅ Camera stays active for retry  
✅ Valid QR processed after invalid attempt  

---

#### Test 4: Permission Denied Handling

**Prerequisites:**
- 1 Android device
- Camera permission NOT granted

**Steps:**
1. Device: Revoke camera permission (Settings → Apps → Orbot → Permissions)
2. Open Orbot → Enhanced Mesh tab
3. Tap "Join Mesh"
4. Verify permission request dialog appears
5. Tap "Deny"
6. Verify Snackbar: "Camera permission required..." with "Settings" action
7. Tap "Settings" → Verify opens app settings
8. Grant permission
9. Return to app → Tap "Join Mesh" again
10. Verify camera starts without re-request

**Expected Results:**
✅ Permission requested on first "Join Mesh" click  
✅ Error shown if denied, with path to settings  
✅ Works correctly after permission granted  

---

#### Test 5: Connection Timeout

**Prerequisites:**
- 1 Android device
- Device A displays QR, then turns off WiFi/hotspot

**Steps:**
1. Device B: Scan Device A's QR code
2. Device A: Immediately turn off WiFi/hotspot
3. Device B: Wait 30 seconds
4. Verify error Snackbar: "Failed to join: timeout" (or similar)
5. Verify camera pane collapses
6. Verify can retry by tapping "Join Mesh" again

**Expected Results:**
✅ Timeout handled gracefully (no crash)  
✅ Clear error message shown  
✅ Can retry connection  

---

#### Test 6: Flashlight Toggle

**Prerequisites:**
- 1 Android device with flashlight
- Dark environment

**Steps:**
1. Tap "Join Mesh"
2. Camera starts
3. Tap "Flashlight" button
4. Verify button text changes to "Flashlight: ON"
5. Verify device flashlight activates
6. Tap "Flashlight" button again
7. Verify button text changes to "Flashlight: OFF"
8. Verify device flashlight deactivates
9. Tap "Cancel" → Verify flashlight turns off

**Expected Results:**
✅ Flashlight toggles on/off  
✅ Button text updates correctly  
✅ Flashlight turns off when camera stops  

---

### Phase 3: Compatibility Testing

#### Android Version Matrix

| Android Version | LocalOnlyHotspot | Concurrent AP+STA | Expected Behavior |
|-----------------|------------------|-------------------|-------------------|
| **13+ (Tiramisu)** | ✅ Supported | ✅ Yes | Seamless, deterministic SSID (`meshr-<hex>`) |
| **10-12 (Q-S)** | ✅ Supported | ⚠️ Device-dependent | Works, but may not support concurrent |
| **8-9 (Oreo-Pie)** | ✅ Supported | ❌ No | Works, but random SSID (`AndroidShare_XXXX`) |

**Test on each Android version:**
1. ✅ QR code generation
2. ✅ QR code scanning
3. ✅ Two-device mesh join
4. ✅ SSID format validation
5. ✅ Password consistency

---

## Remaining Uncertainties

### Question 1: Mesh-Wide Hotspot Discovery Service

**Current State:**
- Users must manually scan QR codes to discover nearby hotspots
- No automatic discovery of available mesh networks in range

**Uncertainty:**
Should we implement a mesh-wide hotspot discovery service where:
1. All mesh nodes broadcast their hotspot info periodically
2. Devices can see a list of available mesh networks without scanning QR
3. Tap a network from list → auto-join without QR code

**Pros:**
✅ More convenient (no QR code needed)  
✅ Can show signal strength and node count  
✅ Similar to standard WiFi network list UI  

**Cons:**
❌ Privacy concerns (broadcasting SSID and password)  
❌ Security implications (anyone can see mesh credentials)  
❌ Conflicts with Android's WiFi privacy features  
❌ Added complexity in UI and backend  

**Recommendation:**
- **Phase 1 (Current Plan):** QR code only (secure, explicit, user-controlled)
- **Phase 2 (Optional):** Discovery service with opt-in flag:
  - "Allow my mesh to be discoverable" setting
  - Encrypted broadcast (only mesh members can decrypt)
  - Time-limited discovery (e.g., 5 minutes after mesh start)

**Decision Needed:** Implement discovery now, or wait for user feedback on QR-only approach?

---

### Question 2: User-Configurable Password

**Current State:**
- Android 13+: Hardcoded password `"meshtest12"` shared by all devices
- Android 8-12: Random password per device

**Uncertainty:**
Should we allow users to set a custom mesh password?

**Pros:**
✅ Enhanced security (not using default password)  
✅ User control over mesh access  
✅ Prevents unauthorized joining  

**Cons:**
❌ UX friction (must configure before starting mesh)  
❌ Password synchronization complexity (all devices must use same password)  
❌ QR codes become user-specific (can't join another mesh without knowing password)  
❌ Breaks automatic mesh formation (all devices must agree on password)  

**Current Behavior:**
- Meshrabiya library hardcodes `"meshtest12"` in LocalOnlyHotspotManager.kt (line 103)
- Changing requires library modification
- All devices in same mesh must use same password

**Recommendation:**
- **Phase 1:** Keep hardcoded password for simplicity
- **Phase 2:** Add optional custom password setting:
  - Default: `"meshtest12"` (backward compatible)
  - Advanced settings: "Custom mesh password" field
  - Show warning: "All devices must use same password"
  - Update QR code generation to use custom password

**Decision Needed:** Implement custom password now, or wait for security audit?

---

### Question 3: Coordinated Hotspot Config Exchange Protocol

**Current State:**
- Each device generates its own SSID independently
- No centralized coordination of hotspot parameters
- Devices discover each other via mesh protocol after joining

**Uncertainty:**
Should we implement a protocol for coordinated hotspot configuration exchange?

**Example Scenario:**
- Device A starts mesh with SSID `meshr-a9fe2d8e`
- Device B scans QR and joins Device A
- Device B starts its own hotspot with SSID `meshr-7f3c6820`
- Device C wants to join mesh:
  - Option 1: Scan Device A's QR → joins Device A only
  - Option 2: Scan Device B's QR → joins Device B only
  - **Problem:** Device C doesn't know both hotspots are part of same mesh

**Proposed Solution:**
Implement mesh-wide hotspot registry:
1. When Device B joins Device A, it receives:
   - List of all active hotspots in mesh
   - Their SSIDs, passwords, and virtual addresses
2. Device B broadcasts its own hotspot info to all mesh members
3. All devices maintain synchronized list of available hotspots
4. When displaying QR code, optionally show:
   - Primary hotspot (own)
   - Alternate hotspots (from registry)
5. Joining device can choose closest/strongest hotspot

**Pros:**
✅ Redundancy (can join via any mesh member)  
✅ Load balancing (spread connections across hotspots)  
✅ Better resilience (if one hotspot fails, use another)  

**Cons:**
❌ Increased complexity (registry sync, conflict resolution)  
❌ Privacy concerns (all devices know all hotspots)  
❌ Stale data risk (hotspot stops but registry not updated)  

**Recommendation:**
- **Phase 1:** Single hotspot per QR code (simple, current plan)
- **Phase 2:** Implement hotspot registry:
  - Add `HotspotRegistryManager` component
  - Broadcast hotspot info via originating messages
  - Store registry in NodeTopologyInfo
  - Update QR generation to optionally show multiple hotspots
  - Add UI: "Show alternate hotspots" button

**Decision Needed:** Registry protocol now, or optimize Phase 1 first?

---

### Question 4: Android 8-12 Manual Confirmation UI

**Current State:**
- Android 8-12 devices cannot run concurrent AP+STA
- Switching between hotspot and station requires stopping one mode
- No UI confirmation for disruptive mode switches

**Uncertainty:**
Should we add a confirmation dialog for disruptive mode switches on Android 8-12?

**Example Scenario:**
- Device A (Android 10) is running hotspot
- Device B connects to Device A
- Device A wants to join another mesh (via QR scan)
- **Current behavior:** Automatically stops hotspot, disconnects Device B
- **Proposed behavior:** Show dialog:
  - "Joining another mesh will stop your hotspot and disconnect X clients. Continue?"
  - [Cancel] [Join Anyway]

**Pros:**
✅ Prevents accidental disconnections  
✅ Transparent about consequences  
✅ Gives user control over disruptive actions  

**Cons:**
❌ Added UX friction  
❌ May confuse users who don't understand AP+STA limitations  
❌ Inconsistent behavior vs. Android 13+ (no dialog needed)  

**Recommendation:**
- **Android 13+:** No confirmation (seamless)
- **Android 8-12:** Show confirmation dialog if:
  1. Device is currently running hotspot, AND
  2. Other devices are connected (client count > 0), AND
  3. User is attempting to join another mesh
- **Dialog message:**
  ```
  Joining another mesh will stop your hotspot.
  
  Currently connected: 2 devices
  
  They will be disconnected. Continue?
  
  [Cancel]  [Join Anyway]
  ```

**Decision Needed:** Implement confirmation dialog now, or wait for user feedback?

---

### Question 5: QR Code Versioning and Backward Compatibility

**Current State:**
- QR codes use standard WiFi format: `WIFI:T:WPA;S:ssid;P:password;;`
- No version information
- No mesh-specific metadata (node address, mesh version, capabilities)

**Uncertainty:**
Should we define a custom QR format with versioning for future extensibility?

**Proposed Custom Format:**
```
MESH:V:1;S:meshr-a9fe2d8e;P:meshtest12;A:2852150414;C:ap,sta;M:v1.0.0;;
```

Where:
- `V:1` = Format version (for backward compatibility)
- `S:` = SSID
- `P:` = Password
- `A:` = Virtual address (32-bit int)
- `C:` = Capabilities (ap, sta, concurrent, 5ghz, etc.)
- `M:` = Mesh software version

**Pros:**
✅ Future-proof (can add fields without breaking old clients)  
✅ Mesh-specific (can include metadata not in WiFi format)  
✅ Better error messages (can check version compatibility)  

**Cons:**
❌ Not compatible with standard QR scanners (won't auto-join)  
❌ Requires custom parsing in all clients  
❌ Breaks interoperability with non-Orbot mesh apps  

**Recommendation:**
- **Phase 1:** Use standard WiFi format (maximum compatibility)
- **Phase 2:** Support both formats:
  - Generate: WiFi format by default, custom format if "Advanced mode" enabled
  - Parse: Auto-detect format (WiFi or MESH) and extract credentials
  - Fallback: If custom format fails, try WiFi format
- **Version strategy:**
  - V1: WiFi format only
  - V2: WiFi + optional custom format
  - V3: Custom format by default, WiFi as fallback

**Decision Needed:** Custom format now, or wait for identified need?

---

## Future Enhancements

### Phase 2 Features (Post-MVP)

#### 1. Automatic Hotspot Recovery ⚠️ NOT YET IMPLEMENTED

**Status:** Proposed in MESH_JOIN_PLAN-PT2.md but not required for Phase 1 QR joining.

**Description:** Implement connection loss monitoring and automatic hotspot promotion

**Components:**
- Connection loss detector (monitor WiFi state changes)
- Retry logic (3 attempts with exponential backoff)
- Alternate hotspot search (use existing `getNodesWithRole(MESH_ROUTER)`)
- Coordinated promotion (neighbor-based backoff using existing `neighbors().size`)

**Can use existing APIs:**
- `originatingMessageManager.neighbors().size` for neighbor counting
- `originatingMessageManager.getNodesWithRole(MeshRole.MESH_ROUTER)` for finding routers
- `setWifiHotspotEnabled()` for promotion
- EmergentRoleManager automatically assigns MESH_ROUTER role

**Complexity:** Medium (1-2 weeks)

---

#### 2. Mesh Network List UI

**Description:** Show list of discoverable mesh networks (similar to WiFi list)

**UI Design:**
```
┌─────────────────────────────────────┐
│ Available Mesh Networks             │
├─────────────────────────────────────┤
│ 🟢 meshr-a9fe2d8e                   │
│    3 nodes · 5 GHz · Signal: ▂▄▆█   │
│    [Join]                           │
├─────────────────────────────────────┤
│ 🟢 meshr-7f3c6820                   │
│    5 nodes · 2.4 GHz · Signal: ▂▄   │
│    [Join]                           │
├─────────────────────────────────────┤
│ ⚪ meshr-2e9b4f17                   │
│    1 node · 5 GHz · Signal: ▂       │
│    [Join]                           │
└─────────────────────────────────────┘
```

**Complexity:** High (2-3 weeks, requires discovery protocol)

---

#### 3. NFC Mesh Joining

**Description:** Tap two devices together to join mesh (alternative to QR)

**Protocol:**
1. Device A: Write SSID/password to NFC tag (NDEF WiFi format)
2. Device B: Read NFC tag via Intent filter
3. Device B: Auto-join mesh (same as QR scan)

**Advantages:**
✅ Faster than QR scan (just tap)  
✅ Works in dark/bright environments  
✅ More intuitive for non-technical users  

**Complexity:** Low (1 week, NFC API is well-documented)

---

#### 4. Multi-Hop QR Relay

**Description:** Device C joins via Device B's QR, even if Device B isn't running hotspot

**Process:**
1. Device B displays QR with primary hotspot (Device A)
2. Device C scans Device B's QR
3. Device C connects to Device A (not Device B)
4. Device C discovers full mesh topology via originating messages

**Benefits:**
✅ Can join mesh from any member (not just hotspot owners)  
✅ Scales better (don't need to find specific hotspot)  

**Complexity:** Medium (requires hotspot registry, covered in Question 3)

---

#### 5. Mesh Password Manager

**Description:** Keychain/password manager integration for storing mesh credentials

**Features:**
- Save mesh SSID/password to Android Keychain
- Auto-fill credentials when joining known mesh
- Export credentials to password manager (1Password, Bitwarden)
- Import credentials from password manager

**Complexity:** Low-Medium (1-2 weeks, Keychain API available)

---

## Success Metrics

### Phase 1 Goals (Current Implementation)

**Functional Requirements:**
✅ Users can display QR code when mesh is active  
✅ Users can scan QR code to join mesh  
✅ Connection success rate >90% within 30 seconds  
✅ QR code is readable by standard scanners  
✅ No crashes on permission denial or invalid QR  

**User Experience:**
✅ QR display: <2 seconds from mesh start  
✅ QR scan: <3 seconds from pointing camera  
✅ Join process: <30 seconds total  
✅ Error messages: Clear and actionable  
✅ Pane controls: Smooth expand/collapse animations  

**Code Quality:**
✅ Unit test coverage: >80%  
✅ Integration test coverage: 100% of critical paths  
✅ No lint errors or warnings  
✅ Logging: Comprehensive for debugging  
✅ Documentation: Complete API docs and user guide  

---

## Deployment Checklist

### Pre-Deployment

- [ ] All unit tests passing
- [ ] All integration tests passing on Android 13+ device
- [ ] All integration tests passing on Android 8-12 device
- [ ] Manual testing on 3+ device types (Samsung, Pixel, etc.)
- [ ] No lint errors or warnings
- [ ] Code review completed
- [ ] Documentation updated (README, KNOWLEDGE, API docs)
- [ ] Camera permission added to manifest
- [ ] Gradle build succeeds with no errors
- [ ] APK size increase <5 MB (QR library is lightweight)

### Deployment

- [ ] Create feature branch: `feature/mesh-qr-join`
- [ ] Commit all changes with detailed message
- [ ] Push to GitHub
- [ ] Create pull request with:
  - Description of changes
  - Screenshots/videos of QR flow
  - Test results summary
  - Known limitations
- [ ] Merge after approval
- [ ] Tag release: `v1.x.x-mesh-qr`
- [ ] Build release APK
- [ ] Test release APK on 2+ devices
- [ ] Upload to F-Droid or internal distribution

### Post-Deployment

- [ ] Monitor crash reports (Sentry, Firebase, etc.)
- [ ] Collect user feedback
- [ ] Track success metrics:
  - QR scan success rate
  - Average join time
  - Connection failures
  - Permission denial rate
- [ ] Plan Phase 2 features based on feedback
- [ ] Update KNOWLEDGE doc with lessons learned

---

## Known Limitations

### Phase 1 Scope

**Not Included:**
- ❌ Automatic hotspot discovery (requires discovery protocol)
- ❌ Custom password configuration (hardcoded `"meshtest12"`)
- ❌ Hotspot registry/coordination (single hotspot per QR)
- ❌ Manual confirmation dialogs (Android 8-12 mode switches)
- ❌ Custom QR format with versioning
- ❌ NFC joining
- ❌ Multi-hop QR relay
- ❌ Password manager integration

**Reason:** Minimize scope for reliable Phase 1 delivery. All excluded features can be added in Phase 2 without breaking changes.

---

### Platform Limitations

**Android 8-12:**
- Cannot run concurrent AP+STA (disruptive mode switches)
- Random SSID format (not deterministic)
- Random password (different per device)

**Android 13+:**
- LocalOnlyHotspot restrictions (no internet-sharing, Android policies)

**All Versions:**
- Requires location permission for WiFi scanning (Android privacy)
- Requires camera permission for QR scanning
- 30-second timeout for connection establishment

---

## Summary

### Phase 1 Deliverables (Documentation Complete)

✅ **Part 1:** Architecture research and Q&A  
⚠️ **Part 2:** Hotspot recovery strategy (PHASE 2 - not required for QR join)  
✅ **Part 3:** Mesh topology and role system (VERIFIED against EmergentRoleManager)  
✅ **Part 4:** UI layout and drawable resources  
✅ **Part 5:** Fragment logic, QR generation, camera scanning  
🔧 **Part 6:** API implementation (getHotspotInfo, joinMesh) - READY TO IMPLEMENT  
✅ **Part 7:** Integration flow, testing plan, remaining uncertainties

**Implementation Status:**
- ✅ All underlying mesh functionality exists (verified)
- 🔧 New API methods need implementation (Part 6)
- 🔧 UI components need implementation (Parts 4-5)
- ⚠️ Automatic recovery is Phase 2 (optional)  

### Implementation Roadmap

**Week 1: API & Core (Part 6)**
- Implement MeshrabiyaApi interface methods
- Add HotspotInfoDto
- Implement MeshrabiyaApiImpl logic
- Write unit tests

**Week 2: UI & Fragment (Parts 4-5)**
- Update fragment_enhanced_mesh.xml layout
- Add drawable resources
- Update MeshUIBindings
- Implement button handlers
- Implement QR generation
- Implement camera scanning

**Week 3: Testing & Polish**
- Manual integration testing (QR display, scan, join)
- Compatibility testing (Android 8-12 vs 13+)
- Permission flow testing
- Error handling testing
- Code review and fixes

**Week 4: Deployment**
- Final testing on 3+ device types
- Documentation updates
- Release build
- Internal distribution
- Monitor and iterate

### Phase 2 Planning

**Priority 1 (High Impact):**
1. Automatic hotspot recovery (connection loss handling)
2. NFC mesh joining (faster, more intuitive)

**Priority 2 (Medium Impact):**
3. Mesh network list UI (discovery protocol)
4. Custom password configuration
5. Multi-hop QR relay (hotspot registry)

**Priority 3 (Low Impact):**
6. Manual confirmation dialogs (Android 8-12)
7. Custom QR format with versioning
8. Password manager integration

**Decisions Needed Before Phase 2:**
- [ ] Mesh-wide discovery: Implement now or wait for feedback?
- [ ] Custom password: Security audit needed?
- [ ] Hotspot registry: Protocol design review?
- [ ] Manual confirmations: UX testing with real users?
- [ ] Custom QR format: Interoperability concerns?

---

## Final Notes

This comprehensive plan covers every aspect of QR code-based mesh joining, from architecture research to deployment. All user questions have been answered with code evidence. All implementation details have been specified with file paths and line numbers. All remaining uncertainties have been documented for future decision-making.

**The plan is ready for implementation.**

**Next action:** Begin implementation with Part 6 (API methods), as they have no UI dependencies and can be unit tested immediately.

**Estimated total implementation time:** 3-4 weeks (full-time), 6-8 weeks (part-time)

**Success criteria:** Two devices can join mesh via QR code within 30 seconds, with >90% success rate on first attempt.
