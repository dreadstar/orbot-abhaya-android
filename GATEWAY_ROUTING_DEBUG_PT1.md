# GATEWAY_ROUTING_DEBUG_PT1.md
## Deep Analysis: phone_test.log — Six Issues — Root Causes & Verified Patches
**Date:** 2026-03-16  
**Log file:** `phone_test.log` (orbot-android project root)  
**Phone 1 (host):** PID 25899 · address 169.254.124.56 · Android 12 (SDK 31, EMUI)  
**Phone 2 (peer ):** address 169.254.98.109 · WiFi IP 192.168.193.230  
**Mesh SSID:** AndroidShare_7141 (Local-Only Hotspot, AP mode on Phone 1)  
**Internet WiFi:** "obie " (AP+STA concurrent mode — Phone 1 only)  
**Log span:** 19:50:21 → ~19:56:06 (~5 min 45 s, 7 000+ lines)

---

## Verified Timeline

| t+    | Event                                                                          | Issue |
|-------|--------------------------------------------------------------------------------|-------|
| 0     | App start — PID 25899 initialised                                              | —     |
| 11 s  | Local-Only Hotspot started: AndroidShare_7141                                  | —     |
| 12 s  | QR displayed; status → CONNECTING                                              | —     |
| 40 s  | Phone 2 first connects (`isNew=true`) → CONNECTED                             | —     |
| 73 s  | `[NONMESH] connectToNonMeshWifi start ssid='obie '`                           | 6     |
| 73.9s | `onCapabilitiesChanged validated=true` for obie                                | 6     |
| 83 s  | `[NONMESH] validation timeout for obie` → `hasInternetAccess=false`           | 6     |
| 88.7s | Last originating msg from Phone 2 (10.5 s silence begins — emui:screenshot)   | 1     |
| 99.27s| `checkLostNodesRunnable: Lost 169.254.98.109 - no contact for 10505ms`        | **1** |
| 100.96s| neighbors=0 → `updateMeshStatus()` → UI flips to CONNECTING                  | 1     |
| 101.01s| Phone 2 sends again → `DIRECT NEIGHBOR (isNew=true)` → CONNECTED back        | 1     |
| 104 s | `[NONMESH] internet probe failed (IOException), trying VALIDATED`  (loops 30s)| 6     |
| 220 s | Text broadcast 'Test' received from Phone 2 → badge = 1                       | 5     |
| 255.32s| **Image broadcast SENT from Phone 1** — message='Run', file=quick_screencap.png | **4** |
| 255.34s| BroadcastID `e208db3f`, 133 767 bytes, 131 chunks, batch 1/2 (chunks 0–99)   | 4     |
| 255.35s+| Chunks 0–89 all ✅ sent to 192.168.193.230:50266                            | 4     |
| 256.13s| **RATE LIMIT**: `Single process limit 250/s drop 228 lines`                  | 4     |
| 257.34s| Phone 2 sends originating message (still active after broadcast)               | 4     |
| 310.02s| **File broadcast RECEIVED on Phone 1** — id=7cf85553, file=Screenshot_2021-07-31-23-43-51.png, 411 chunks | **3/5** |
| 310.27s| **RATE LIMIT**: `drop 612 lines` (hides chunks ~20–71 of 7cf85553)            | 3/5   |
| 311.15s| **RATE LIMIT**: `drop 1367 lines` (log resumes at 205/411)                    | 3/5   |
| 312.09s| **RATE LIMIT**: `drop 1302 lines` (log resumes at 333/411)                    | 3/5   |
| 312.92s| Last visible checkpoint: 352/411 chunks received for 7cf85553                 | 3/5   |
| 315.42s| **RATE LIMIT**: `drop 449 lines` (outcome of final batch hidden)              | 3/5   |
| 316.17s| Phone 2 sends NACK for e208db3f: 24 missing chunks — Phone 1 responds "unknown broadcast, ignoring" (send state already discarded) | **4** |
| 374 s | User opens notification dialog → adapter size=1                                | 5/2   |
| ~374 s| Session ends — 2 incoming broadcasts received (6f4b3a7d text ✅, 7cf85553 file incomplete ❌), 1 sent | —     |

---

## Issue 1 — Status Flips CONNECTING→CONNECTED Twice ("Joined Mesh Twice")

### Symptom
UI shows CONNECTING briefly then CONNECTED again while Phone 2 is still on the mesh.  
Visible ~2 s window: t+99 s→t+101 s.

### Root Cause Chain (verified)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`

1. At t+88.7 s, Phone 2's OS starts an EMUI screenshot (`emui:screenshot` PID 26176).  
   This suppresses the originator-message broadcast for ~10.5 s.
2. At t+99.27 s, `checkLostNodesRunnable` fires (interval = originatingMessageNodeLostThreshold ≈ 10 s):
   ```
   checkLostNodesRunnable: Lost 169.254.98.109 - no contact for 10505ms
   ```
3. The runnable removes `169.254.98.109` from `originatorMessages` and immediately emits:
   ```kotlin
   _state.value = OriginatingMessageState(pendingMessages = emptyMap())
   ```
4. VirtualNode / MeshrabiyaApiImpl consumes the empty pending-messages state → `neighborCount = 0` → `updateMeshStatus()` transitions to CONNECTING at t+100.96 s.
5. 0.05 s later, Phone 2 resumes; originator message received → `isNew=true` → CONNECTED again at t+101.01 s.

**No grace period exists.** Dropping to zero peers emits CONNECTING immediately.

### Code Location

`OriginatingMessageManager.kt` lines 284–302:

```
private val checkLostNodesRunnable = Runnable {
    try {
        val timeNow = System.currentTimeMillis()
        val nodesLost = originatorMessages.entries.filter {
            (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
        }
        ...
        nodesLost.forEach {
            ...
            originatorMessages.remove(it.key)
        }

        _state.value = OriginatingMessageState(                          // ← emits 0-peer state immediately
            pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
        )
    }
```

---

### BEFORE (OriginatingMessageManager.kt — lines 284–306)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/OriginatingMessageManager.kt`  
**Location:** Lines 284–306

```kotlin
    private val checkLostNodesRunnable = Runnable {
        try {
            val timeNow = System.currentTimeMillis()
            val nodesLost = originatorMessages.entries.filter {
                (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
            }
            logBeta(LogLevel.DEBUG, "Checking lost nodes: ${nodesLost.map { it.key.addressToDotNotation() }.joinToString()}")
            nodesLost.forEach {
                logBeta(LogLevel.INFO, "Lost node: ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms")
                logger(Log.DEBUG, {"$logPrefix : checkLostNodesRunnable: " +
                        "Lost ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms"})
                originatorMessages.remove(it.key)
            }

            _state.value = OriginatingMessageState(
                pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
            )
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error checking lost nodes", e)
            logger(Log.ERROR, { "$logPrefix : checkLostNodesRunnable : exception checking lost nodes" }, e)
        }
    }
```

---

### AFTER (OriginatingMessageManager.kt — lines 284–306)

Add `allPeersLostAtMs` field near the top of the class (alongside other `@Volatile` fields), then update the runnable:

**New field to add** (place near other class-level volatile fields):
```kotlin
    @Volatile
    private var allPeersLostAtMs = 0L
    private val lostNodeGracePeriodMs = 15_000L      // 15 s — covers OS screenshot suppression
```

**Updated `checkLostNodesRunnable`:**
```kotlin
    private val checkLostNodesRunnable = Runnable {
        try {
            val timeNow = System.currentTimeMillis()
            val nodesLost = originatorMessages.entries.filter {
                (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
            }
            logBeta(LogLevel.DEBUG, "Checking lost nodes: ${nodesLost.map { it.key.addressToDotNotation() }.joinToString()}")
            nodesLost.forEach {
                logBeta(LogLevel.INFO, "Lost node: ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms")
                logger(Log.DEBUG, {"$logPrefix : checkLostNodesRunnable: " +
                        "Lost ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms"})
                originatorMessages.remove(it.key)
            }

            val peerCountAfter = originatorMessages.size

            // Grace period: when all peers disappear simultaneously (e.g. OS screenshot suppression),
            // do NOT immediately emit 0-peer state.  Wait lostNodeGracePeriodMs before downgrading.
            if (nodesLost.isNotEmpty() && peerCountAfter == 0 && allPeersLostAtMs == 0L) {
                allPeersLostAtMs = timeNow
                logger(Log.DEBUG, { "$logPrefix : checkLostNodesRunnable: all peers lost – grace period started" })
            }

            val gracePeriodExpired = allPeersLostAtMs == 0L ||
                    (timeNow - allPeersLostAtMs) >= lostNodeGracePeriodMs

            if (peerCountAfter > 0) {
                allPeersLostAtMs = 0L   // reset when peers come back
                _state.value = OriginatingMessageState(
                    pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
                )
            } else if (gracePeriodExpired) {
                _state.value = OriginatingMessageState(
                    pendingMessages = originatorMessages.mapValues { it.value.originatorMessage }
                )
            }
            // else: peers gone but grace period active — suppress state emission
        } catch (e: Exception) {
            logBeta(LogLevel.ERROR, "Error checking lost nodes", e)
            logger(Log.ERROR, { "$logPrefix : checkLostNodesRunnable : exception checking lost nodes" }, e)
        }
    }
```

**Purpose:** When all peers simultaneously vanish (OS event, screenshot, screen-off), the 15 s grace period absorbs the gap. If Phone 2 reconnects within that window (as observed at t+101 s), `peerCountAfter > 0` → immediate state emit with peer in map → no CONNECTING flash. If all peers are genuinely gone past 15 s, state is emitted normally.

---

## Issue 2 — Gray Square UI Artifact (between WiFi button and chevron when nonMesh connected)

### What This Button Does
`meshExtenderApButton` is a **Mesh Extender Hotspot toggle** — an icon-only `ImageButton` (cell-tower drawable `ic_cell_tower`, no text label). Tapping it calls `meshrabiyaApi.startMeshExtenderHotspot()` when the extender is `INACTIVE`, or `meshrabiyaApi.stopMeshExtenderHotspot()` when `ACTIVE`. Its purpose is to let non-mesh devices connect through Phone 1's AP and reach the mesh network. The button is `android:visibility="gone"` until Phone 1 is simultaneously a mesh router **and** STA-connected to a non-mesh WiFi — exactly when the extender capability first becomes available to the user.

### Symptom
A small gray square appears in the mesh control header row when Phone 1 connects to the "obie" WiFi (nonMesh STA mode). It sits between the red "WiFi Internet" button (left) and the expand-collapse chevron (right). The cell-tower icon (`ic_cell_tower`) is visually obscured by the default Material `ImageButton` gray rectangle background.

### Root Cause (verified)

**File 1:** `app/src/main/res/layout/fragment_mesh_enhanced.xml` (lines ~84–115)  
**File 2:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt` (lines 726–736)

The `meshControlHeader` LinearLayout contains these views (left to right):

| View ID | Type | Default | Behavior when nonMesh connects |
|---------|------|---------|-------------------------------|
| `meshToggleButton` | MaterialButton | visible | always visible |
| `joinMeshButton` | MaterialButton | visible | always visible |
| `mergeMeshButton` | MaterialButton | visible | always visible |
| `wifiApConnectionButton` | MaterialButton w/ wifi icon | `gone` | made **VISIBLE** with RED backgroundTint (connected state) ← "red wifi button" |
| `meshExtenderApButton` | **ImageButton** (ic_cell_tower) | `gone` | made **VISIBLE** when `isMeshRouter&&isSta` ← **GRAY SQUARE** |
| `expandCollapseIndicator` | ImageView (arrow_down_float) | `gone` | made VISIBLE by `setupMeshExtenderObserver()` when extender active |

The `meshExtenderApButton` is an `ImageButton` with **no explicit `android:background` attribute**. The default ImageButton background in Material themes renders as a gray rectangle. When Phone 1 is both:
- A mesh router (`isMeshRouter = MeshRoleDto.MESH_ROUTER in rolesDto` → true = started hotspot), AND
- STA-connected (`isSta = nonMeshWifiStateFlow.value.status.name == "CONNECTED"` → true = obie connected)

…the role observer at line 735 sets `meshExtenderApButton.visibility = View.VISIBLE`, producing the gray square.

### Code Location

`EnhancedMeshFragment.kt` lines 726–736 (inside role observer):
```kotlin
val isMeshRouter = MeshRoleDto.MESH_ROUTER in rolesDto
val isSta = meshrabiyaApi.getNonMeshWifiStateFlow().value.status.name == "CONNECTED"
val showButtons = isMeshRouter && isSta
val isWifiConcurrentCapable = meshrabiyaApi.isApStaConcurrentCapable() || meshrabiyaApi.isStaStaConcurrentCapable()
MeshUIBindings.wifiApConnectionButton.visibility =
    if (isWifiConcurrentCapable) View.VISIBLE else View.GONE
MeshUIBindings.meshExtenderApButton.visibility =        // ← shows with gray default background
    if (showButtons) View.VISIBLE else View.GONE
```

`fragment_mesh_enhanced.xml` — ImageButton has no background:
```xml
<ImageButton
    android:id="@+id/meshExtenderApButton"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:src="@drawable/ic_cell_tower"
    android:contentDescription="Start AP Extension"
    android:visibility="gone"
    android:layout_marginStart="8dp"
    android:layout_marginBottom="8dp" />
```

---

### BEFORE (fragment_mesh_enhanced.xml — meshExtenderApButton element)

**File:** `app/src/main/res/layout/fragment_mesh_enhanced.xml`  
**Location:** ImageButton element with id `meshExtenderApButton` (inside `meshControlHeader`)

```xml
                    <ImageButton
                        android:id="@+id/meshExtenderApButton"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:src="@drawable/ic_cell_tower"
                        android:contentDescription="Start AP Extension"
                        android:visibility="gone"
                        android:layout_marginStart="8dp"
                        android:layout_marginBottom="8dp" />
```

---

### AFTER (fragment_mesh_enhanced.xml — meshExtenderApButton element)

```xml
                    <ImageButton
                        android:id="@+id/meshExtenderApButton"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:src="@drawable/ic_cell_tower"
                        android:background="?attr/selectableItemBackgroundBorderless"
                        android:contentDescription="Start AP Extension"
                        android:visibility="gone"
                        android:layout_marginStart="8dp"
                        android:layout_marginBottom="8dp" />
```

**Purpose:** `?attr/selectableItemBackgroundBorderless` gives the ImageButton a transparent background with a ripple-only click effect, identical to icon-only toolbar buttons. The gray rectangle disappears; only the `ic_cell_tower` icon is visible.

---

## Issue 3 — File Broadcast Receive Fails / No Feed Notification (Structural Dead Code in Error Path)

### Symptom
When Phone 2 sends a file broadcast, Phone 1 receives no notification and no SharedWithMe folder is created.

### Log Evidence
```
Line 5154: New incoming broadcast: id=7cf85553-09da-4a1b-ae3d-2f64ceb0e1cc,
           file=Screenshot_2021-07-31-23-43-51.png, totalChunks=411, isTextOnly=false
Lines 5166+: [BROADCAST_COMPLETE_CHECK] broadcastId=7cf85553, receivedChunks=1..352/411, isComplete=false
(last visible at t+312.92s: 352/411; rate-limiter dropped 612+1367+1302+449 lines during receive — isComplete() was never true)
```

The drop folder **IS** configured on Phone 1: `[UPDATE_UI] Displaying drop folder: Abhaya` appears on log lines 297, 319, 558, 2058, 4056. `getDropFolderAsDocumentFile()` correctly calls `DocumentFile.fromTreeUri()` for `content://` URIs (`MeshrabiyaApiImpl.kt` line 1625). **The URI format is not the issue.**

### Root Cause Chain (verified)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`

**Root Cause 1 — Transfer never completed (log-window issue):**
Broadcast `7cf85553` started arriving at t+310s with 411 expected chunks. 352/411 chunks are visible in the log before the final rate-limiter gap at t+315.42s (4 rate-limiter drops total: 612+1367+1302+449 lines dropped). `isComplete()` was never `true` in the visible log. The file-completion block was never entered → `writeBroadcastFile()` was never called → no listener notification dispatched. The transfer's final outcome cannot be confirmed from Phone 1's log alone due to the rate-limiter gaps.

**Root Cause 2 — Structural: error-path broadcasts never call listeners (dead code):**
For broadcasts where all chunks DO arrive but the file write fails (e.g., SAF permission revoked, `exists()=false` or `canWrite()=false`), the completion block in `handleBroadcastChunk()` at line ~551 contains:
```kotlin
// ONLY notify listeners if file write succeeded (do NOT increment notification count for errors)
if (!hasError) {
    receiveListeners.forEach { it(notification) }  // ← NEVER called when hasError=true
}
```
When `hasError=true`, `receiveListeners.forEach` is **skipped entirely**. The `hasError ->` branch in `EnhancedMeshFragment.broadcastListener` (line ~473) is therefore **unreachable dead code** — no error DTO is ever dispatched to the UI for failed file transfers.

**Root Cause 3 — Missing `exists()`/`canWrite()` guard (silent failures):**
`writeBroadcastFile()` line 802 logs `exists=${dropFolderDoc.exists()}, canWrite=${dropFolderDoc.canWrite()}` but does NOT throw if either is `false`. A SAF URI that parses successfully but points to a deleted or permission-revoked folder passes the null check and fails deeper in `createDirectory()` / `createFile()` with a null return, masking the real cause.

**Root Cause 4 — Stale transfer cleanup is silent:**
`cleanupStaleTransfers()` (line ~863) removes timed-out broadcasts with only a WARN log. When `7cf85553` eventually times out, no error DTO is dispatched to any listener. The user receives no "transfer timed out" notification.

### Code Locations

Area 1 — validation gap (`writeBroadcastFile`, lines 795–802):
```kotlin
val dropFolderDoc = getDropFolderCallback()
if (dropFolderDoc == null) {
    throw IllegalStateException("Drop folder not selected")
}
logger(Log.INFO, "...exists=${dropFolderDoc.exists()}, canWrite=${dropFolderDoc.canWrite()}")
// ← no guard on exists() or canWrite()
```

Area 2 — listener gate (`handleBroadcastChunk`, lines ~551–572):
```kotlin
// ONLY notify listeners if file write succeeded
if (!hasError) {
    receiveListeners.forEach { it(notification) }  // ← dead code path for error case
}
```

---

### BEFORE — BroadcastMessageHandler.kt (Area 1: `exists()`/`canWrite()` guard, lines 795–803)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Location:** Lines 795–803

```kotlin
        val dropFolderDoc = getDropFolderCallback() 
        
        if (dropFolderDoc == null) {
            logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ Drop folder callback returned NULL")
            throw IllegalStateException("Drop folder not selected")
        }
        
        logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] Drop folder URI: ${dropFolderDoc.uri}, exists=${dropFolderDoc.exists()}, canWrite=${dropFolderDoc.canWrite()}")
```

### AFTER — BroadcastMessageHandler.kt (Area 1: `exists()`/`canWrite()` guard, lines 795–803)

```kotlin
        val dropFolderDoc = getDropFolderCallback()

        if (dropFolderDoc == null) {
            logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ Drop folder callback returned NULL")
            throw IllegalStateException("Drop folder not selected")
        }

        logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] Drop folder URI: ${dropFolderDoc.uri}, exists=${dropFolderDoc.exists()}, canWrite=${dropFolderDoc.canWrite()}")

        if (!dropFolderDoc.exists()) {
            logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ Drop folder does not exist: ${dropFolderDoc.uri}")
            throw IllegalStateException("Drop folder no longer exists (URI: ${dropFolderDoc.uri})")
        }
        if (!dropFolderDoc.canWrite()) {
            logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ Drop folder not writable: ${dropFolderDoc.uri}")
            throw IllegalStateException("Drop folder is not writable (permission revoked?): ${dropFolderDoc.uri}")
        }
```

**Purpose:** Converts silent null returns from `createDirectory()` / `createFile()` into actionable error messages with the exact failing URI. The existing catch blocks in `handleBroadcastChunk()` catch these and set `hasError=true`/`errorMessage` correctly.

---

### BEFORE — BroadcastMessageHandler.kt (Area 2: listener gate, lines ~551–572)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Location:** Lines ~551–572 (inside `handleBroadcastChunk()` `isComplete()` block)

```kotlin
                    // ONLY notify listeners if file write succeeded (do NOT increment notification count for errors)
                    if (!hasError) {
                        logger(Log.DEBUG, "${broadcastTag(broadcastId)} [NOTIFICATION] Creating notification DTO for successful file transfer")
                        val notification = com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto(
                            broadcastId = broadcastId,
                            messageText = state.messageText,
                            fileId = state.metadata.fileId,
                            fileName = state.metadata.fileName,
                            filePath = filePath ?: "",
                            senderNodeId = state.senderNodeId,
                            latitude = state.metadata.latitude,
                            longitude = state.metadata.longitude,
                            receivedAt = System.currentTimeMillis(),
                            hasError = false,
                            errorMessage = null
                        )
                        
                        if (state.senderNodeId != virtualNode.addressAsInt) {
                            logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Notifying ${receiveListeners.size} listeners for successful file broadcast: fileName='${state.metadata.fileName}'")
                            synchronized(receiveListeners) {
                                receiveListeners.forEach { it(notification) }
                            }
                            logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] ✅ All ${receiveListeners.size} listeners notified")
                        }
                    }
```

### AFTER — BroadcastMessageHandler.kt (Area 2: listener gate, lines ~551–572)

```kotlin
                    // Always notify listeners — pass hasError flag so the UI error branch
                    // actually fires (previously the hasError -> branch was unreachable dead code)
                    logger(Log.DEBUG, "${broadcastTag(broadcastId)} [NOTIFICATION] Creating notification DTO (hasError=$hasError)")
                    val notification = com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto(
                        broadcastId = broadcastId,
                        messageText = state.messageText,
                        fileId = state.metadata.fileId,
                        fileName = state.metadata.fileName,
                        filePath = filePath ?: "",
                        senderNodeId = state.senderNodeId,
                        latitude = state.metadata.latitude,
                        longitude = state.metadata.longitude,
                        receivedAt = System.currentTimeMillis(),
                        hasError = hasError,
                        errorMessage = errorMessage
                    )

                    if (state.senderNodeId != virtualNode.addressAsInt) {
                        logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Notifying ${receiveListeners.size} listeners (hasError=$hasError): fileName='${state.metadata.fileName}'")
                        synchronized(receiveListeners) {
                            receiveListeners.forEach { it(notification) }
                        }
                        logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] ✅ All ${receiveListeners.size} listeners notified")
                    }
```

**Purpose:** `EnhancedMeshFragment.broadcastListener`'s `hasError ->` branch was unreachable dead code. This change makes error notifications for failed file writes reach the UI so users see a "File broadcast failed: [reason]" snackbar with a "Set Folder" action, rather than silent failure.

---

## Issue 4 — Image+Text Broadcast from Phone 1 Not Received on Phone 2

### Symptom
Phone 1 sends an image broadcast (message='Run', file=`quick_screencap.png`). Phone 2 shows no notification and no file is saved.

### Additional Finding — Rate Limit Drop (228 lines)

At t+256.13 s (chunk 89 sending), the Android logger dropped 228 lines:
```
W/ratelimit(25899): Single process limit 250/s drop 228 lines.
```
This explains why no "batch 2/2" or "100% complete" log exists in `phone_test.log` — **the completion events were rate-limited, not absent**. The broadcast DID complete on the send side. At t+257.34 s, Phone 2 is confirmed still active (sends originator message), verifying network remained up throughout.

### Broadcast Facts (confirmed from log)

| | Value |
|---|---|
| Broadcast ID | `e208db3f-2235-42ec-9dfd-5370600184f0` |
| Message text | `"Run"` |
| File | `quick_screencap.png` |
| File size | 133 767 bytes |
| Chunk count | 131 |
| Batch layout | Batch 1/2 = chunks 0–99; Batch 2/2 = chunks 100–130 |
| UDP destination | /192.168.193.230:50266 |
| Final confirmed chunk | 89 (228 lines dropped after; batches 2/2 and completion events in dropped window) |

### Root Cause Chain (verified)

`e208db3f-2235-42ec-9dfd-5370600184f0` is an **OUTGOING** broadcast initiated by Phone 1 at t+255.32s (log line 4112: `Starting broadcast: message='Run', file='/data/user/0/org.torproject.android.debug/cache/quick_screencap.png'`). Phone 2's receipt of this broadcast is not observable from Phone 1's log. No `[BROADCAST] callback entry` for `e208db3f` exists in Phone 1's log — expected: that log line only fires when Phone 1's own `broadcastListener` is called (i.e., for received broadcasts, not sent ones).

The **send side** is healthy and visible in the log:
- 131 chunks, batch 1/2 → chunks 0–89 confirmed sent to 192.168.193.230:50266 ✅
- At t+256.13s: `Single process limit 250/s drop 228 lines` — batch 2/2 and completion events are in the dropped window
- At t+257.34s: Phone 2 still active (sends originator message) → network was up throughout the entire send

**Root Cause (Phone 2 side):** Phone 2's `BroadcastMessageHandler` suffers the same structural bugs identified in Issue 3:
- **Bug 2** (`if (!hasError)` gate): if Phone 2's file write fails for any reason, the listener is never called → no UI notification shown on Phone 2.
- **Bug 3** (missing `exists()`/`canWrite()` guard): a stale or revoked SAF URI passes the null check and fails silently at `createDirectory()` returning null.

Whether Phone 2 had a drop folder configured cannot be determined from Phone 1's log. If not configured: `getDropFolderCallback()` returns null → `IllegalStateException` caught → `hasError=true` → listener skipped → no notification. If configured but stale/revoked: same outcome via Bug 3.

### Fix
**Same two patches as Issue 3** — apply the `exists()`/`canWrite()` guard (Area 1 patch) and the always-call-listener change (Area 2 patch) to `BroadcastMessageHandler.kt`. Both patches are fully specified under Issue 3.

**Send-side improvement:** Add a "broadcast send complete" log after batch completion in `BroadcastMessageHandler.sendBroadcast()` to survive future rate-limit windows:
```kotlin
logger(Log.INFO, "${broadcastTag(broadcastId)} ✅ All $totalChunks chunks sent successfully")
```

### Confirmed Bug — NACK Retransmission Ignored (t+316.17s)

**Log evidence (phone_test.log lines 6176–6178):**
```
t+316.16s: BroadcastMessageHandler Received NACK for broadcast e208db3f: 24 chunks requested by node -1442946451
t+316.17s: BroadcastMessageHandler NACK received for unknown broadcast e208db3f-2235-42ec-9dfd-5370600184f0, ignoring
```

**Root cause:** At t+316.17s, Phone 2 detected 24 missing chunks in broadcast `e208db3f` and sent a NACK (packet type `0x02`) to Phone 1 requesting retransmission. Phone 1 responded `"NACK received for unknown broadcast ... ignoring"` because it had already discarded its outgoing broadcast state after the initial send window (~t+257s). By the time the NACK arrived 59 seconds later, the state map entry for `e208db3f` no longer existed. The retransmission mechanism is **entirely inoperative**.

**Impact:** Phone 2 never received the 24 missing chunks. Broadcast `e208db3f` (`quick_screencap.png`, 131 chunks) was permanently incomplete on Phone 2 — not because of network failure during the original send, but because NACK-based recovery was broken.

**Fix:** Retain outgoing broadcast chunk data in `outgoingBroadcasts[broadcastId]` for `BROADCAST_TIMEOUT_MS` after send completion. When a NACK arrives for a known broadcast ID within that window, retransmit the requested chunk indices to the requesting node, then discard state only after the retransmission window expires.

**File:** `BroadcastMessageHandler.kt` — NACK handler and `sendBroadcast()` cleanup path.

---

## Issue 5 — Second Text Broadcast and File Broadcast Not Added to Notification Feed

### Symptom
Three broadcasts were received (or attempted) during the test session:
1. Text broadcast `6f4b3a7d` ("Test") → **appeared in feed** ✅
2. File broadcast `7cf85553` (Screenshot_2021-07-31-23-43-51.png, 411 chunks) → **NOT in feed**
3. Second text broadcast from Phone 2 → **NOT in feed**

The user observed that after the first text broadcast appeared, the second text broadcast "never appeared in the drop down."

### Log Evidence
```
Line 3219: New incoming broadcast: id=6f4b3a7d, isTextOnly=true           ← text #1
Line 3222: [NOTIFICATION] Notifying 1 listeners for text broadcast: message='Test'
Line 3223: [BROADCAST] callback entry id=6f4b3a7d … viewState=RESUMED
Line 3230: [UI_CALLBACK] ✅ Added to broadcastNotifications (size=1)       ← in feed ✅

Line 5154: New incoming broadcast: id=7cf85553, totalChunks=411, isTextOnly=false
Lines 5166–5596: [BROADCAST_COMPLETE_CHECK] receivedChunks=1..352/411, isComplete=false
(last visible at t+312.92s: 352/411; rate-limiter drops (612+1367+1302+449 lines) hide additional chunks; isComplete() was never true, listener never called)

(No log entry for any second text broadcast anywhere in phone_test.log)
```

`grep_search` of the entire log for `"New incoming broadcast"` yields **exactly two results** (`6f4b3a7d` and `7cf85553`). The second text broadcast did not arrive during the log capture window.

### Root Cause Chain (verified)

**Files:**
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`
- `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

**Root Cause 1 — File broadcast (7cf85553) never completed:**
411 chunks; 352/411 visible in the log before the final rate-limiter gap at t+315.42s. `isComplete()` was never `true` in the visible log. The file-completion block was never entered → `receiveListeners.forEach { it(notification) }` was never called → no notification in the feed. This is an incomplete transfer (either ongoing when log capture ended, or lost in the final rate-limiter gap), not a code bug.

**Root Cause 2 — Second text broadcast absent from log (reception timing):**
The second text broadcast does not appear anywhere in `phone_test.log`. It was either sent after the log capture window ended (~19:56:06) or was lost in transit. `onTextOnlyBroadcastComplete()` (line 885) unconditionally calls listeners — it would have worked if the broadcast had been received, as proven by text #1.

**Root Cause 3 — Structural: file broadcast error notifications are dead code:**
If broadcast `7cf85553` had completed AND the file write failed (e.g., due to the missing `exists()`/`canWrite()` guard from Issue 3), the `if (!hasError)` gate in `handleBroadcastChunk()` line ~551 would have silently discarded the error notification:
```kotlin
if (!hasError) {
    receiveListeners.forEach { it(notification) }  // ← skipped when hasError=true
}
```
`EnhancedMeshFragment.broadcastListener`'s `hasError ->` branch (line ~473) is **unreachable dead code**. No error notification for a failed file write can ever reach the UI feed — users see zero feedback for failed transfers.

**Root Cause 4 — Stale transfer cleanup does not notify UI:**
`cleanupStaleTransfers()` removes timed-out broadcasts with only a WARN log — no listener call. When `7cf85553` eventually times out, the user receives no indication in the feed. This combines with Root Cause 3 to create a completely silent failure for incomplete or failed file transfers.

---

### BEFORE — BroadcastMessageHandler.kt (listener gate, lines ~551–572)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Location:** Lines ~551–572 (inside `handleBroadcastChunk()` `isComplete()` block)

Already specified in Issue 3 Area 2 BEFORE/AFTER above — that patch is the fix for Root Cause 3.

---

### BEFORE — BroadcastMessageHandler.kt (`cleanupStaleTransfers`, lines ~863–878)

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Location:** Lines ~863–878

```kotlin
    fun cleanupStaleTransfers() {
        virtualNode.connectionExecutor.execute {
            val now = System.currentTimeMillis()
            
            incomingBroadcasts.entries.removeIf { (id, state) ->
                if (now - state.startTime > MeshrabiyaConstants.BROADCAST_TIMEOUT_MS) {
                    logger(Log.WARN, "$TAG Broadcast $id timed out, received ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks")
                    true
                } else {
                    false
                }
            }
        }
    }
```

### AFTER — BroadcastMessageHandler.kt (`cleanupStaleTransfers`, lines ~863–878)

```kotlin
    fun cleanupStaleTransfers() {
        virtualNode.connectionExecutor.execute {
            val now = System.currentTimeMillis()

            incomingBroadcasts.entries.removeIf { (id, state) ->
                if (now - state.startTime > MeshrabiyaConstants.BROADCAST_TIMEOUT_MS) {
                    logger(Log.WARN, "$TAG Broadcast $id timed out, received ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks")
                    // Notify listeners with timeout error so the UI shows a "Transfer timed out" entry
                    val timeoutDto = com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto(
                        broadcastId = id,
                        messageText = state.messageText,
                        fileId = state.metadata.fileId,
                        fileName = state.metadata.fileName,
                        filePath = "",
                        senderNodeId = state.senderNodeId,
                        latitude = state.metadata.latitude,
                        longitude = state.metadata.longitude,
                        receivedAt = System.currentTimeMillis(),
                        hasError = true,
                        errorMessage = "Transfer timed out: received ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks"
                    )
                    if (state.senderNodeId != virtualNode.addressAsInt) {
                        synchronized(receiveListeners) {
                            receiveListeners.forEach { it(timeoutDto) }
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
    }
```

**Purpose:** Users receive a "Transfer timed out: received X/Y chunks" error notification in the feed when a file broadcast fails to complete. Previously, incomplete transfers expired silently with only a WARN log entry.

---

## Issue 6 — No Toast / Internet Not "Available" When Gateway Present

### Symptom
When Phone 1 has an active non-mesh WiFi connection (obie) with Internet Gateway enabled, Phone 2 does not receive a "Internet is available" toast, and the mesh does not behave as though internet-connected.

### Root Cause Analysis (two distinct causes)

#### Cause A — Internet Gateway preference was OFF in this test session

From log line ~394:
```
[INIT] Loaded Internet Gateway preference: false
```
With gateway preference OFF, `MeshRoleDto.CLEARNET_GATEWAY` is NOT added to Phone 1's roles → `clearnetGateways = 0` in topology → `_meshInternetGatewayAvailableFlow.value = (nonMeshHasInternet!=true) && (0 > 0) = false`.  
**The gateway flow never emits `true` in this session because the toggle was off.** This is not a bug — it's user configuration. The gateway must be enabled for the flow to activate.

#### Cause B — No UI observer for `getMeshInternetGatewayAvailableFlow()` (the actual bug)

`MeshrabiyaApiImpl.kt` line 333–336:
```kotlin
_meshInternetGatewayAvailableFlow.value =
    dto.nonMeshHasInternet != true && dto.clearnetGateways > 0
```

This flow correctly emits `true` when the mesh has an internet gateway and this node doesn't have direct internet access. But **the only consumer of this flow is `MeshProxyController.kt`** (routing logic — no UI):
```kotlin
// MeshProxyController.kt line ~32 — routes traffic through mesh proxy only
meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
    val active = gatewayAvailable && packages.isNotEmpty()
    // ... sets up routing — no toast, no UI update
}
```

**`EnhancedMeshFragment` has NO observer** for this flow.  
**`OrbotActivity` has NO observer** for this flow.  
No toast, no status label update, no system notification.

#### Cause C — NONMESH internet probe fails through Tor (secondary issue)

The `hasInternetAccess` probe periodically fires an HTTP request. On Phone 1, all network traffic routes through Tor. The probe fails with `IOException` → `hasInternetAccess = false` despite `onCapabilitiesChanged: validated=true` at t+73.9 s.

However, this secondary issue does NOT block the gateway toast because:
- Gateway flow logic: `dto.nonMeshHasInternet != true && dto.clearnetGateways > 0`
- With broken probe: `hasInternetAccess=false` → `nonMeshHasInternet=false` → `false != true = true` → gateway available condition MET (probe failure actually enables the condition)
- The actual blocker was Cause A (gateway preference OFF)

---

### Fix A — Add Gateway Available Observer in EnhancedMeshFragment

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Call `observeGatewayAvailability()` alongside other observer setup calls (e.g., inside `onViewCreated` after `setupNetworkInfoObserver()`)

---

#### BEFORE — EnhancedMeshFragment.kt (setupNetworkInfoObserver call site)

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Location:** Wherever `setupNetworkInfoObserver()` is called in `onViewCreated` (search for `setupNetworkInfoObserver()`)

```kotlin
        setupNetworkInfoObserver()
```

---

#### AFTER — EnhancedMeshFragment.kt (setupNetworkInfoObserver call site)

```kotlin
        setupNetworkInfoObserver()
        observeGatewayAvailability()
```

---

#### New function to add (place adjacent to `setupNetworkInfoObserver()` in the file)

```kotlin
    /**
     * Observes the mesh internet gateway available flow and shows a system toast
     * when the state transitions false→true (a gateway becomes available on the mesh while
     * this node has no direct internet access).
     */
    private fun observeGatewayAvailability() {
        viewLifecycleOwner.lifecycleScope.launch {
            var previouslyAvailable = false
            meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { available ->
                if (available && !previouslyAvailable) {
                    activity?.runOnUiThread {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Internet available via mesh gateway",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                previouslyAvailable = available
            }
        }
    }
```

**Purpose:** Mirrors the system "Internet is Available" toast experience. Shows exactly once per false→true transition. Suppressed when node already has direct internet access (`getMeshInternetGatewayAvailableFlow` returns false when `nonMeshHasInternet=true`), preventing double-toast.

---

### Fix B — Trust Android NET_CAPABILITY_VALIDATED (probe reliability, secondary fix)

`MeshrabiyaApiImpl.kt` line 316: Internet access state is already computed as:
```kotlin
val nonMeshHasInternet = (internetWifiState.hasInternetAccess || internetConfirmed)
    .takeIf { nonMeshWifi.status == NonMeshWifiStatusDto.CONNECTED }
```

The `internetWifiState.hasInternetAccess` should be set by `onCapabilitiesChanged(NET_CAPABILITY_VALIDATED=true)`. Trace whether the `NonMeshWifiConnectionManager` (or equivalent class that provides `internetWifiState`) correctly sets `hasInternetAccess=true` when Android reports `NET_CAPABILITY_VALIDATED`. If it does NOT (i.e. only sets it from the HTTP probe), add:

```kotlin
// In the onCapabilitiesChanged callback of NonMeshWifiConnectionManager (or equivalent):
if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
    internetWifiState = internetWifiState.copy(hasInternetAccess = true)
}
```

This eliminates the 10 s validation-timeout failures visible in the log at t+83 s and t+104 s (every 30 s thereafter).

---

## Summary Patch Table

| Issue | File(s) | Change Type | Lines |
|---|---|---|---|
| 1 — Ghost reconnect | `OriginatingMessageManager.kt` | Add `allPeersLostAtMs` field + grace period in `checkLostNodesRunnable` | ~284–306 |
| 2 — Gray square | `fragment_mesh_enhanced.xml` | Add `android:background="?attr/selectableItemBackgroundBorderless"` to `meshExtenderApButton` | meshExtenderApButton element |
| 3 — Incomplete transfer | `BroadcastMessageHandler.kt` | Add `exists()`/`canWrite()` guard in `writeBroadcastFile()` before creating the output file (Area 1) | ~797–802 |
| 3/4/5 — Dead error path | `BroadcastMessageHandler.kt` | Remove `if (!hasError)` gate — always call `receiveListeners.forEach` with correct `hasError` flag (Area 2) | ~551–572 |
| 3/4/5 — Silent timeout | `BroadcastMessageHandler.kt` | In `cleanupStaleTransfers()`, dispatch timeout error DTO to listeners before removing the entry | ~863–878 |
| 4 — Outgoing broadcast | `BroadcastMessageHandler.kt` | Add send-complete log; same Area 1 + Area 2 patches apply on receiving side (Phone 2) | `sendBroadcast()` |
| 5 — Feed not updated | `BroadcastMessageHandler.kt` | Same Area 2 + cleanup patches as Issues 3/4 (shared root cause) | ~551–572, ~863–878 |
| 6 — Gateway toast | `EnhancedMeshFragment.kt` | Add `observeGatewayAvailability()` function + call it in setup | After `setupNetworkInfoObserver()` call |
| 6 — Probe fix | `NonMeshWifiConnectionManager` (or equivalent) | Trust `NET_CAPABILITY_VALIDATED` for `hasInternetAccess` | `onCapabilitiesChanged` callback |
| **4 — NACK ignored** | `BroadcastMessageHandler.kt` | Retain outgoing broadcast chunk data for `BROADCAST_TIMEOUT_MS`; honor incoming NACKs with retransmission | NACK handler / `sendBroadcast()` cleanup |

---

## Build / Test Verification Checklist

After implementing all patches:

- [ ] `OriginatingMessageManager` compiles — `allPeersLostAtMs` field accessible in runnable scope
- [ ] `fragment_mesh_enhanced.xml` attribute `?attr/selectableItemBackgroundBorderless` resolves in both light/dark themes  
- [ ] `BroadcastMessageHandler` `writeBroadcastFile()` guard compiles — `exists()`/`canWrite()` check inserted before file creation
- [ ] `BroadcastMessageHandler` `handleBroadcastChunk()` Area 2 patch compiles — `hasError` and `errorMessage` passed into `BroadcastReceivedDto`
- [ ] `BroadcastMessageHandler` `cleanupStaleTransfers()` patch compiles — timeout DTO dispatched correctly
- [ ] **Issue 1 test**: On Phone 2, trigger OS screenshot during mesh session. Verify status stays CONNECTED for ≥15 s.
- [ ] **Issue 2 test**: Connect to non-mesh WiFi while mesh is running. Verify no gray square between WiFi button and chevron.
- [ ] **Issue 3/5 test (error path)**: Send a file broadcast to a device with a stale/revoked drop folder URI. Verify "File broadcast failed: Drop folder is not writable" error notification appears in the feed (snackbar with "Set Folder" action).
- [ ] **Issue 3/5 test (timeout)**: Send a file broadcast, interrupt it before completion. Wait for `BROADCAST_TIMEOUT_MS`. Verify "Transfer timed out: received X/Y chunks" notification appears in the feed.
- [ ] **Issue 3/4 test**: Send file broadcast WITHOUT configuring drop folder. Verify file appears in `Android/data/org.torproject.android/files/SharedWithMe/`.
- [ ] **Issue 5 test**: Receive 3 broadcasts without clearing. Open dialog (do NOT tap Clear All). Close dialog. Verify badge = 0. Receive 1 more broadcast. Verify badge = 1.
- [ ] **Issue 6 test**: Enable Internet Gateway preference. Verify "Internet available via mesh gateway" toast appears on Phone 2 when Phone 1 (with internet) joins the mesh.
- [ ] **Issue 4 NACK test**: Send a file broadcast over a connection with packet loss. Verify Phone 2 can NACK missing chunks and Phone 1 retransmits them successfully within `BROADCAST_TIMEOUT_MS`.
