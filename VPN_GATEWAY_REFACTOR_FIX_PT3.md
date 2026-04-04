# VPN Gateway Refactor — Runtime Bug Fixes PT3
**Created:** 2026-04-03  
**Mode:** INFORMATIONAL (no file mutations until user says "apply patch")  
**Source:** Phase 0–9 Debug-Patch-Strategy analysis of 5 post-install runtime bugs

---

## Phase 0 — Error Enumeration

```
ERROR #1
  Message : VPN state row (internetWifiRow + chips + green dot) never appears
            in the Network Information card when Orbot VPN is active
  File    : app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
  Line    : 830 (setupVpnStatusObserver — visibility guard on vpnState.active)
  Symbol  : _vpnStateFlow / notifyVpnStateChanged / TorStatusMonitor.register()
  Status  : RESOLVED by Change A-1

ERROR #2
  Message : meshInternetGreenDot absent on Phone 1 (gateway / VPN node) despite
            VPN tunnel being active and providing internet access
  File    : app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
  Line    : 807 (setupMeshInternetGreenDotObserver — combines gatewayAvailable || vpnState.active)
  Symbol  : _vpnStateFlow / getVpnStateFlow / TorStatusMonitor.register()
  Status  : RESOLVED by Change A-1 (same root cause as #1)

ERROR #3
  Message : meshChipAp disappears after screen sleep on Phone 1 even though
            WiFi Direct AP is still hardware-active; returns only on next
            networkInfo emission with a fresh apActive snapshot
  File    : app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
  Line    : 787 (setupNetworkInfoObserver — val apActive = …meshApActiveFlow?.value)
  Symbol  : meshApActiveFlow / meshChipAp / setupWifiStateObserver
  Status  : RESOLVED by Change A-3

ERROR #4
  Message : meshInternetGreenDot absent on Phone 2 (joining station) despite
            torGateways=1 visible in topology; gateway internet probe never fires
  File    : Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
  Line    : 225 (declaration: private var meshInternetCheckJob: Job? = null — never launched)
  Symbol  : meshInternetCheckJob / _meshInternetViaGatewayConfirmed / checkInternetViaMeshGateway
  Status  : RESOLVED by Change A-2

ERROR #5
  Message : Starting or joining the mesh does not prompt the "disable battery
            optimization" Snackbar; only VPN flows call PermissionManager
  File    : app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
  Line    : 1149 (startMeshWithPermissionCheck — no PermissionManager call)
            Line 2244 (joinMesh success callback — no PermissionManager call)
  Symbol  : PermissionManager.requestBatteryPermissions
  Status  : RESOLVED by Changes A-4 / A-5 / A-6
```

---

## Phase 1 — Symbol and Type Verification (key findings)

| Symbol | File | Line | Finding |
|--------|------|------|---------|
| `TorStatusMonitor.register()` | TorStatusMonitor.kt | 109 | Uses `RECEIVER_NOT_EXPORTED` → blocks Orbot's external broadcast on Android 13+ |
| `_vpnStateFlow` | MeshrabiyaApiImpl.kt | 172 | `MutableStateFlow(VpnStateDto(active=false))` — default INACTIVE |
| `notifyVpnStateChanged()` | MeshrabiyaApiImpl.kt | 1543 | `_vpnStateFlow.value = vpnState` |
| `setupVpnStatusObserver()` | EnhancedMeshFragment.kt | 822 | Shows `internetWifiRow` only when `vpnState.active == true` |
| `setupMeshInternetGreenDotObserver()` | EnhancedMeshFragment.kt | 807 | `combine(gatewayFlow, vpnFlow) { g, v -> g || v.active }` — always false while vpnState.active always false |
| `meshInternetCheckJob` | MeshrabiyaApiImpl.kt | 225 | Declared, **never launched**. Only one grep match in all *.kt source — the declaration itself |
| `checkInternetViaMeshGateway()` | MeshrabiyaApiImpl.kt | 507 | Complete, correct probe implementation — just never called |
| `_meshInternetViaGatewayConfirmed` | MeshrabiyaApiImpl.kt | 224 | `MutableStateFlow(false)` — never set to true |
| `MESH_INTERNET_CHECK_INTERVAL_MS` | MeshrabiyaApiImpl.kt | 151 | `30_000L` |
| `eventMonitoringScope` | MeshrabiyaApiImpl.kt | 220 | `CoroutineScope(Dispatchers.Default)` — available for new launch |
| `setupWifiStateObserver()` | EnhancedMeshFragment.kt | ~870 | Correct reactive owner of `meshChipAp`/`meshChipSta` |
| `PermissionManager.requestBatteryPermissions()` | PermissionManager.kt | 20 | `fun requestBatteryPermissions(activity: FragmentActivity, view: View)` — 0 call sites in EnhancedMeshFragment |

---

## Root Cause Summary

### Bugs #1 + #2 — Same root cause

`TorStatusMonitor.register()` registers with `ContextCompat.RECEIVER_NOT_EXPORTED`.  
On Android 13+, this flag silently blocks all broadcasts from external packages.  
Orbot is a separate package (`org.torproject.android` broadcast, received by the mesh library).  
Result: `onReceive()` never fires → `notifyVpnStateChanged()` never called → `_vpnStateFlow` permanently `VpnStateDto(active=false)` → `setupVpnStatusObserver()` never shows the VPN row, and `setupMeshInternetGreenDotObserver()` combine always yields `false`.

**Fix:** Change `RECEIVER_NOT_EXPORTED` → `RECEIVER_EXPORTED`.

### Bug #3 — Two observers fighting

`setupNetworkInfoObserver()` reads a **snapshot** `meshApActiveFlow.value` on every `networkInfo` emission and writes `meshChipAp` / `meshChipSta`.  
`setupWifiStateObserver()` also reactively owns those same chips via `wifiStateFlow.collect`.  
Any `networkInfo` emission that arrives while `meshApActiveFlow.value == false` (e.g., during hotspot restart, sleep/wake) overwrites the correct `VISIBLE` state with `GONE`, hiding the chip for the rest of the session.

**Fix:** Remove the snapshot-based chip block from `setupNetworkInfoObserver()`. `setupWifiStateObserver()` is the sole correct reactive owner.

### Bug #4 — meshInternetCheckJob never launched

The comment on line 223 says "Set by periodic `checkInternetViaMeshGateway()`."  
The job variable is declared at line 225.  
A codebase-wide grep for `meshInternetCheckJob =` in all `*.kt` source files returns **exactly one match** — the declaration itself. The job is never assigned, never started. `_meshInternetViaGatewayConfirmed` is permanently `false`. Phone 2 never gets a green dot even when a working gateway is in the topology.

**Fix:** Launch `meshInternetCheckJob` from `startEventMonitoring()` with an immediate first probe (no initial delay) followed by 30 s periodic loop. Cancel it in `stopEventMonitoring()`.

### Bug #5 — Battery optimization not requested on mesh start/join

`PermissionManager.requestBatteryPermissions()` exists and is correct (shows Snackbar, no-ops if already exempt). It is called 0 times from `EnhancedMeshFragment`. The mesh service can be killed by Android's Doze/battery optimizer after screen-off, which also contributes to the AP chip disappearance in Bug #3.

**Fix:** Call `PermissionManager.requestBatteryPermissions()` at the start of `startMeshWithPermissionCheck()` and inside the `joinMesh()` success branch.

---

## Changes

### Change A-1 — Fix TorStatusMonitor broadcast reception
**Fixes:** Bugs #1 and #2  
**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/TorStatusMonitor.kt`  
**Lines:** 109–120  
**File size:** 242 lines — **editable with replace_string_in_file**

**BEFORE (lines 109–120):**
```kotlin
        try {
            val filter = IntentFilter(ACTION_TOR_STATUS)
            ContextCompat.registerReceiver(
                context,
                this,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isRegistered = true
            Log.i(TAG, "TorStatusMonitor registered for Orbot status broadcasts")
        } catch (e: Exception) {
```

**AFTER (lines 109–120):**
```kotlin
        try {
            val filter = IntentFilter(ACTION_TOR_STATUS)
            ContextCompat.registerReceiver(
                context,
                this,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isRegistered = true
            Log.i(TAG, "TorStatusMonitor registered for Orbot status broadcasts")
        } catch (e: Exception) {
```

**Pattern uniqueness check:** grep for `ContextCompat.RECEIVER_NOT_EXPORTED` in TorStatusMonitor.kt → 1 match at line 114 ✓

---

### Change A-2 — Launch meshInternetCheckJob in startEventMonitoring
**Fixes:** Bug #4  
**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`  
**Lines:** 465–484  
**File size:** 2636 lines — **manual edit required (>800L)**

**BEFORE (lines 465–484):**
```kotlin
                if (_meshStatusFlow.value != derived) {
                    Log.d(TAG, "[LIVENESS] liveCount=$liveCount evicted=$evicted → $derived")
                    _meshStatusFlow.value = derived
                }
            }
        }
        
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
     */
    private fun stopEventMonitoring() {
        stateMonitorJob?.cancel()
        peerMonitorJob?.cancel()
        stateMonitorJob = null
        peerMonitorJob = null
    }
```

**AFTER (lines 465–484):**
```kotlin
                if (_meshStatusFlow.value != derived) {
                    Log.d(TAG, "[LIVENESS] liveCount=$liveCount evicted=$evicted → $derived")
                    _meshStatusFlow.value = derived
                }
            }
        }

        meshInternetCheckJob?.cancel()
        meshInternetCheckJob = eventMonitoringScope.launch {
            while (true) {
                val confirmed = checkInternetViaMeshGateway()
                if (_meshInternetViaGatewayConfirmed.value != confirmed) {
                    _meshInternetViaGatewayConfirmed.value = confirmed
                    Log.d(TAG, "[MESH_CHECK] Gateway internet confirmed=$confirmed")
                }
                delay(MESH_INTERNET_CHECK_INTERVAL_MS)
            }
        }
        
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
     */
    private fun stopEventMonitoring() {
        stateMonitorJob?.cancel()
        peerMonitorJob?.cancel()
        meshInternetCheckJob?.cancel()
        stateMonitorJob = null
        peerMonitorJob = null
        meshInternetCheckJob = null
    }
```

**Pattern uniqueness check:** grep for `[LIVENESS] liveCount=$liveCount evicted=$evicted → $derived` → 1 match at line 466 ✓

**Notes:**
- Loop probes immediately (no initial `delay`) — Phone 2 sees the green dot within seconds of topology showing `torGateways=1`, not after a 30 s blind window.
- `delay(MESH_INTERNET_CHECK_INTERVAL_MS)` at the **end** of the loop body gives the desired 30 s cadence after the first probe.
- Both `meshInternetCheckJob?.cancel()` lines are safe no-ops if the job is null.

---

### Change A-3 — Remove stale-snapshot chip logic from setupNetworkInfoObserver
**Fixes:** Bug #3  
**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 779–806  
**File size:** 2677 lines — **manual edit required (>800L)**

**BEFORE (lines 779–806):**
```kotlin
                        MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
                        MeshUIBindings.networkStatsText.text =
                            "Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
                        // meshChipAp: visible when this device is running a mesh hotspot.
                        // meshChipSta: visible when this device joined the mesh as a station.
                        // These are mutually exclusive in single-radio mode but both may be
                        // true on a device with AP+STA concurrency (e.g. Phone 1).
                        // apActive drives the AP chip; STA is inferred as connected-but-not-AP.
                        val meshStatus = meshrabiyaApi.meshStatusFlow.value
                        val meshConnected = meshStatus == MeshStateDto.CONNECTED ||
                                            meshStatus == MeshStateDto.CONNECTING
                        val apActive = (meshrabiyaApi as? MeshrabiyaApiImpl)
                            ?.meshApActiveFlow?.value ?: false
                        val staActive = meshConnected && !apActive
                        MeshUIBindings.meshChipAp.visibility =
                            if (apActive) View.VISIBLE else View.GONE
                        MeshUIBindings.meshChipSta.visibility =
                            if (staActive) View.VISIBLE else View.GONE
                        // NOTE: meshInternetGreenDot is intentionally NOT set here.
                        // It is driven exclusively by setupMeshInternetGreenDotObserver()
                        // which collects getMeshInternetGatewayAvailableFlow() as a live
                        // flow. Combining a snapshot .value here caused the dot to miss
                        // updates that arrived between networkInfo emissions.
                        // internetWifiRow is controlled exclusively by setupVpnStatusObserver().
                        // No nonMeshSsid logic here — that field no longer exists in NetworkInfoDto.
```

**AFTER (lines 779–806):**
```kotlin
                        MeshUIBindings.meshIpAddressText.text = networkInfo.ipAddress
                        MeshUIBindings.networkStatsText.text =
                            "Peers: ${networkInfo.connectedPeers} | Tor Gateways: ${networkInfo.torGateways} | Clearnet: ${networkInfo.clearnetGateways}"
                        // NOTE: meshInternetGreenDot is intentionally NOT set here.
                        // It is driven exclusively by setupMeshInternetGreenDotObserver()
                        // which collects getMeshInternetGatewayAvailableFlow() as a live
                        // flow. Combining a snapshot .value here caused the dot to miss
                        // updates that arrived between networkInfo emissions.
                        // meshChipAp/meshChipSta are controlled exclusively by
                        // setupWifiStateObserver() which reactively collects wifiStateFlow.
                        // Using a snapshot .value here caused the chip to disappear whenever
                        // networkInfoFlow emitted during a meshApActiveFlow transition.
                        // internetWifiRow is controlled exclusively by setupVpnStatusObserver().
                        // No nonMeshSsid logic here — that field no longer exists in NetworkInfoDto.
```

**Pattern uniqueness check:** grep for `val apActive = (meshrabiyaApi as? MeshrabiyaApiImpl)` in EnhancedMeshFragment.kt → 1 match at line 787 ✓

---

### Change A-4 — Add PermissionManager import to EnhancedMeshFragment
**Fixes:** Bug #5 (prerequisite)  
**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 3–5  
**File size:** 2677 lines — **manual edit required (>800L)**

**BEFORE (lines 1–6):**
```kotlin
package org.torproject.android.ui.mesh

import org.torproject.android.R
// import com.ustadmobile.meshrabiya.model.MeshState

import android.Manifest
```

**AFTER (lines 1–6):**
```kotlin
package org.torproject.android.ui.mesh

import org.torproject.android.R
import org.torproject.android.ui.v3onionservice.PermissionManager
// import com.ustadmobile.meshrabiya.model.MeshState

import android.Manifest
```

**Pattern uniqueness check:** grep for `import org.torproject.android.R` at top of file → 1 match at line 3 ✓

---

### Change A-5 — Request battery permission on Start Mesh
**Fixes:** Bug #5  
**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 1149–1162  
**File size:** 2677 lines — **manual edit required (>800L)**

**BEFORE (lines 1149–1162):**
```kotlin
	private fun startMeshWithPermissionCheck() {
		if (!checkLocationPermissions()) {
			android.util.Log.e("EnhancedMeshFragment", "startMeshWithPermissionCheck called but permissions still not granted!")
			return
		}
		
		android.util.Log.d("EnhancedMeshFragment", "Calling startMesh() after permission grant")
		var meshOperationInProgress = true
		MeshUIBindings.meshToggleButton.isEnabled = false
		
		meshrabiyaApi.startMesh { result ->
			// Callback runs on background thread - must switch to main thread for UI updates
			activity?.runOnUiThread {
```

**AFTER (lines 1149–1162):**
```kotlin
	private fun startMeshWithPermissionCheck() {
		if (!checkLocationPermissions()) {
			android.util.Log.e("EnhancedMeshFragment", "startMeshWithPermissionCheck called but permissions still not granted!")
			return
		}

		PermissionManager.requestBatteryPermissions(requireActivity(), requireView())

		android.util.Log.d("EnhancedMeshFragment", "Calling startMesh() after permission grant")
		var meshOperationInProgress = true
		MeshUIBindings.meshToggleButton.isEnabled = false
		
		meshrabiyaApi.startMesh { result ->
			// Callback runs on background thread - must switch to main thread for UI updates
			activity?.runOnUiThread {
```

**Pattern uniqueness check:** grep for `Calling startMesh() after permission grant` → 1 match at line 1155 ✓

---

### Change A-6 — Request battery permission on Join Mesh success
**Fixes:** Bug #5  
**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`  
**Lines:** 2244–2256  
**File size:** 2677 lines — **manual edit required (>800L)**

**BEFORE (lines 2244–2256):**
```kotlin
					if (result.isSuccess) {
						android.util.Log.d("EnhancedMeshFragment", "joinMesh() succeeded")
						Snackbar.make(
							requireView(),
							"Successfully joined mesh network",
							Snackbar.LENGTH_LONG
						).show()
						isJoinMeshMode = false
					} else {
						android.util.Log.e("EnhancedMeshFragment", "joinMesh() failed: ${result.exceptionOrNull()?.message}")
						Snackbar.make(
							requireView(),
							"Failed to join mesh: ${result.exceptionOrNull()?.message}",
```

**AFTER (lines 2244–2256):**
```kotlin
					if (result.isSuccess) {
						android.util.Log.d("EnhancedMeshFragment", "joinMesh() succeeded")
						Snackbar.make(
							requireView(),
							"Successfully joined mesh network",
							Snackbar.LENGTH_LONG
						).show()
						isJoinMeshMode = false
						PermissionManager.requestBatteryPermissions(requireActivity(), requireView())
					} else {
						android.util.Log.e("EnhancedMeshFragment", "joinMesh() failed: ${result.exceptionOrNull()?.message}")
						Snackbar.make(
							requireView(),
							"Failed to join mesh: ${result.exceptionOrNull()?.message}",
```

**Pattern uniqueness check:** grep for `Successfully joined mesh network` → 1 match at line 2248 ✓

---

## Change Summary

| Change | File | Lines | Bugs Fixed | Editable |
|--------|------|-------|------------|----------|
| A-1 | TorStatusMonitor.kt | 114 | #1, #2 | `replace_string_in_file` (242L) |
| A-2 | MeshrabiyaApiImpl.kt | 465–484 | #4 | Manual (2636L) |
| A-3 | EnhancedMeshFragment.kt | 779–806 | #3 | Manual (2677L) |
| A-4 | EnhancedMeshFragment.kt | 3–5 | #5 prereq | Manual (2677L) |
| A-5 | EnhancedMeshFragment.kt | 1149–1162 | #5 start | Manual (2677L) |
| A-6 | EnhancedMeshFragment.kt | 2244–2256 | #5 join | Manual (2677L) |

**Apply order:** A-6 → A-5 → A-4 → A-3 → A-2 (bottom-to-top within each file to preserve line numbers), then A-1 (separate file, can be applied any time).

Say **"apply patch"** to enter PATCH mode and apply all changes.
