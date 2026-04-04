# VPN Gateway Refactor Plan
**Created:** 2026-06-25  
**Status:** DRAFT — awaiting implementation  
**Branch:** feature/vpn-gateway-routing  

---

## 0. Purpose

Replace every function currently gated on a manually-connected non-mesh WiFi
("internet WiFi") with equivalent function gated on Orbot VPN being active.

Specifically:

1. Remove nonMeshWifi UI controls from `EnhancedMeshFragment`.
2. Rewire gateway packet forwarding (clearnet + TOR) to use the OS-level VPN
   socket path instead of binding to `internetWifiNetwork`.
3. Refactor "green dot" internet availability logic to use VPN state instead of
   nonMeshWifi state.
4. Gate originator-message gateway flags on VPN active, not on WiFi connected.
5. Propagate VPN state into Meshrabiya via clean `MeshrabiyaApi` / DTO surface.
6. Delete all nonMeshWifi dead code.

---

## 1. Architectural Change Summary

### Before

```
[User taps WiFi button]
      │
      ▼
MeshrabiyaApi.connectToNonMeshWifi()
      │
      ▼
MeshrabiyaWifiManagerAndroid.connectToInternetWifi()
      │
      ▼
NodeCapabilitySnapshot.hasNonMeshInternetAccess = true
      │
      ▼
EmergentRoleManager → broadcasts gateway available in originator message
      │
      ▼
ClearnetGatewayForwarder.forward()
    internetWifiNetwork.bindSocket(socket)  ← bypasses VPN, goes direct WiFi
    socket.connect(destinationIp, port)
```

### After

```
[Orbot VPN starts]
      │
      ▼
OrbotService broadcasts ACTION_STATUS { EXTRA_STATUS=STATUS_ON,
                                         EXTRA_SOCKS_PROXY_PORT=9050 }
      │
      ▼
[OrbotMeshService or ConnectFragment receives broadcast]
      │
      ▼
MeshrabiyaApi.notifyVpnStateChanged(VpnStateDto(active=true, socksPort=9050))
      │
      ▼
MeshrabiyaApiImpl → updates internal _vpnStateFlow
      │
      ▼
NodeCapabilitySnapshot.hasVpnInternetAccess = true
      │
      ▼
EmergentRoleManager → broadcasts gateway available in originator message
      │
      ▼
ClearnetGatewayForwarder.forward()
    // No bindSocket() call.
    // Socket routes through VPN (Orbot TUN) automatically.
    socket.connect(destinationIp, port)
```

**Why this works without SOCKS5:** The Orbot VPN creates a TUN interface that
intercepts ALL outbound sockets from the device process unless `VpnService.protect()`
is called on them. `ClearnetGatewayForwarder` is not the VPN service; it never
calls `protect()`. Removing the `bindSocket()` call means the outbound socket runs
through the TUN → Tor → internet. No explicit SOCKS5 plumbing needed.

---

## 2. New DTO: `VpnStateDto`

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt`

Add at end of file (before closing brace of package scope):

```kotlin
data class VpnStateDto(
    val active: Boolean = false,
    val socksPort: Int = 9050,
    val httpPort: Int = 8118
)
```

No serialization annotations needed — this DTO is passed in-process only; it
does not cross a network boundary.

---

## 3. `MeshrabiyaApi.kt` Changes

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt`

### 3.1 Remove (4 methods)

| Method | Approximate line |
|--------|-----------------|
| `suspend fun connectToNonMeshWifi(ssid: String, password: String?)` | ~457 |
| `suspend fun disconnectFromNonMeshWifi()` | ~458 |
| `fun getNonMeshWifiStateFlow(): Flow<NonMeshWifiConnectionStateDto>` | ~470 |
| `suspend fun scanAvailableWifiNetworks(): List<ScanResult>` | ~480 |

### 3.2 Add (2 methods)

```kotlin
/**
 * Called by the app layer whenever Orbot VPN transitions: started → active,
 * or active → stopped.  Meshrabiya uses this to gate gateway functionality.
 */
fun notifyVpnStateChanged(vpnState: VpnStateDto)

/** Current VPN state as an observable flow. */
fun getVpnStateFlow(): Flow<VpnStateDto>
```

---

## 4. `MeshrabiyaApiImpl.kt` Changes

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

### 4.1 New StateFlow (add near line 171, alongside other StateFlow fields)

```kotlin
private val _vpnState = MutableStateFlow(VpnStateDto())
```

### 4.2 Remove StateFlows (lines ~171, ~220)

```kotlin
// DELETE these two lines:
private val _nonMeshWifiState = MutableStateFlow(
    NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE)
)
private val _nonMeshInternetConfirmed = MutableStateFlow(false)
```

### 4.3 Implement new API methods (replace removed impl bodies at lines ~2490–2564)

```kotlin
override fun notifyVpnStateChanged(vpnState: VpnStateDto) {
    _vpnState.value = vpnState
}

override fun getVpnStateFlow(): Flow<VpnStateDto> = _vpnState.asStateFlow()
```

### 4.4 Update `combine(...)` block (lines ~330–370)

The block currently combines `_nonMeshWifiState` and
`node.meshrabiyaWifiManager.internetWifiNetworkStateFlow`.

**Remove** those two inputs and their associated local variables.

**Replace** the `nonMeshHasInternet` derived value with `vpnHasInternet`:

```kotlin
combine(
    node.state.map { it.toDto() },
    node.originatingMessageManager.topologyMapFlow,
    _vpnState,
    _meshInternetViaGatewayConfirmed
) { nodeState, topology, vpnState, meshInternetViaGatewayConfirmed ->
    val vpnHasInternet = vpnState.active

    // Determine if VPN is tunneling over WiFi (vs. cellular).
    // When VPN is active, activeNetwork is the TUN interface whose capabilities
    // include both TRANSPORT_VPN and the underlying transport.  This correctly
    // identifies "VPN over WiFi" on Android 21+.
    val vpnOverWifi = vpnState.active && run {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    NetworkInfoDto(
        // ...keep existing fields that have nothing to do with WiFi...
        vpnHasInternet = vpnHasInternet,
        vpnOverWifi = vpnOverWifi,
        meshInternetGatewayAvailable = meshInternetViaGatewayConfirmed
    )
}
```

### 4.5 Remove + replace gateway-down trigger

- `connectToNonMeshWifi()` implementation (lines ~2490–2544)
- `disconnectFromNonMeshWifi()` implementation (lines ~2550–2564)
- The **sole** `broadcastGatewayDown()` call site is `MeshrabiyaApiImpl.kt L2555`, inside
  the internet-WiFi-lost handler. **Do NOT simply delete it — replace it** with a
  VPN-deactivation trigger. When `notifyVpnStateChanged()` receives `active=false`,
  call `broadcastGatewayDown()` to tell mesh peers this node is no longer a gateway:

```kotlin
// In notifyVpnStateChanged() implementation:
override fun notifyVpnStateChanged(vpnState: VpnStateDto) {
    val wasActive = _vpnState.value.active
    _vpnState.value = vpnState
    if (wasActive && !vpnState.active) {
        // VPN just turned off — tell mesh peers this node is no longer a gateway
        myNode?.broadcastGatewayDown()
    }
}
```

---

## 5. `DtoModels.kt` — `NetworkInfoDto` Changes

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt`

### 5.1 Remove from `NetworkInfoDto` constructor

```kotlin
// DELETE these three fields (~line 104 area):
val nonMeshSsid: String? = null,
val nonMeshIpAddress: String? = null,
val nonMeshHasInternet: Boolean? = null,
```

### 5.2 Add to `NetworkInfoDto` constructor

```kotlin
val vpnHasInternet: Boolean = false,
val vpnOverWifi: Boolean = false,   // VPN tunnel's underlying transport is WiFi (not cellular)
```

`vpnOverWifi = true` means the VPN is routing internet over the device's WiFi STA connection.
On devices without STA+AP concurrency (e.g. Phone 2 — LML211BL, MSM8937), starting a mesh
hotspot OR joining another mesh AP will disconnect wlan0 from the internet AP, killing the VPN.
This field drives the button-gating logic in §11.6.

### 5.3 Delete entire `NonMeshWifiConnectionStateDto` and `NonMeshWifiStatusDto`

Lines 743–770 (approx). Both become unreferenced after the above changes.

---

## 6. `EmergentRoleManager.kt` — Gateway Flag Condition

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

### 6.1 `NodeCapabilitySnapshot` field rename

```kotlin
// BEFORE (~line 59):
data class NodeCapabilitySnapshot(
    val hasNonMeshInternetAccess: Boolean = false,
    // ...
)

// AFTER:
data class NodeCapabilitySnapshot(
    val hasVpnInternetAccess: Boolean = false,
    // ...
)
```

Update every reference to `hasNonMeshInternetAccess` within this file to
`hasVpnInternetAccess`.

### 6.2 Capability population

**Research confirmed (round 3):** `AndroidVirtualNode` does NOT override
`getCurrentNodeCapabilities()`. The base `VirtualNode` implementation at L256 returns a
default snapshot with all values hardcoded — `hasNonMeshInternetAccess = false`.

**The actual population happens in `EmergentRoleManager.kt`**, not via capability
snapshot override. Confirmed locations:

- **L715:** Direct read from WiFi manager:
  ```kotlin
  hasNonMeshInternetAccess = (virtualNode.meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
      ?.internetWifiNetworkStateFlow?.value?.hasInternetAccess ?: false
  ```
- **L677:** `NodeCapabilitySnapshot.copy()` call that sets the field:
  ```kotlin
  enhancedSnapshot.copy(hasNonMeshInternetAccess = nonMeshInternetAccess)
  ```

**What to change in `EmergentRoleManager.kt`:**

Replace the WiFi read at **both** L677 (main code path) AND L715 (fallback catch block).
Round 4 research confirmed the identical cast appears at **both** locations:

```kotlin
// BEFORE (appears at BOTH L677 and L715 — two separate edits required):
hasNonMeshInternetAccess = (virtualNode.meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
    ?.internetWifiNetworkStateFlow?.value?.hasInternetAccess ?: false

// AFTER (vpnStateFlow is nullable constructor param — use null-safe accessor):
hasVpnInternetAccess = vpnStateFlow?.value?.active ?: false
```

**Injection chain (Round 4 confirmed — 3 files require constructor changes):**

`MeshrabiyaApiImpl._vpnStateFlow` → `AndroidVirtualNode(vpnStateFlow=)` → `VirtualNode(vpnStateFlow=)` → `VirtualNode.kt L286 EmergentRoleManager(vpnStateFlow=)`

1. `EmergentRoleManager.kt` — add: `private val vpnStateFlow: StateFlow<VpnStateDto>? = null` to constructor
2. `VirtualNode.kt` — add `vpnStateFlow: StateFlow<VpnStateDto>? = null` to constructor; pass it at the **only** EmergentRoleManager instantiation site (L286):
   ```kotlin
   open val emergentRoleManager: EmergentRoleManager = EmergentRoleManager(
       virtualNode = this,
       context = appContext,
       getTopologyMap = { originatingMessageManager.getTopologyMapInfo() },
       getCurrentNodeCapabilities = { getCurrentNodeCapabilities() },
       vpnStateFlow = vpnStateFlow    // ← add this line
   )
   ```
3. `AndroidVirtualNode.kt` — add `vpnStateFlow: StateFlow<VpnStateDto>? = null` to constructor; pass to `super` (VirtualNode) — see §7.

`_vpnStateFlow` (the field from §4.1) flows from `MeshrabiyaApiImpl` into `AndroidVirtualNode`
at construction (L260), through `VirtualNode`, and finally into `EmergentRoleManager`.

Also rename the field in `NodeCapabilitySnapshot` (§6.1) and update the `.copy()` at L677
to reference `hasVpnInternetAccess`.

The `NodeCapabilitySnapshot` test site at `MockDeviceCapabilityManager.kt L396` — update
field name only; test values are unchanged.

The gateway flags `KEY_TOR_GATEWAY_ENABLED` / `KEY_CLEARNET_GATEWAY_ENABLED`
remain as user preferences — no change. Only the runtime capability data source
changes.

---

## 7. `AndroidVirtualNode.kt` — Gateway Packet Guard

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`

### 7.1 Current code (lines 245–254)

**Research round 3 confirmed exact on-disk implementation:**
```kotlin
override fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean {
    val internetNetwork = meshrabiyaWifiManager.internetWifiNetwork  // L246
    return if (internetNetwork != null) {
        clearnetGatewayForwarder.forward(packet, internetNetwork)
        true
    } else {
        logger(Log.WARN, "$logPrefix CLEARNET gateway: no internet WiFi network bound, dropping packet", null)
        false
    }
}
```

### 7.2 Required change

`AndroidVirtualNode` needs access to VPN state for two reasons:
1. **Packet guard** (this section): `onClearnetGatewayPacket` must check VPN active before forwarding
2. **Injection pass-through** (§6.2): `AndroidVirtualNode` extends `VirtualNode`, which instantiates
   `EmergentRoleManager` at L286; VPN state must thread from `AndroidVirtualNode` through `VirtualNode` to reach `EmergentRoleManager`

Inject `vpnStateFlow: StateFlow<VpnStateDto>? = null` into the `AndroidVirtualNode` constructor.
Store as a field; use in `onClearnetGatewayPacket`; pass to `super(vpnStateFlow = vpnStateFlow)`
so `VirtualNode` receives it (required for §6.2 injection chain).

Do NOT pass `MeshrabiyaApiImpl` reference — that creates a circular dependency.

```kotlin
override fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean {
    val vpn = vpnStateProvider.vpnState  // new: injected/observable reference
    if (!vpn.active) {
        logger(Log.WARN, TAG, "onClearnetGatewayPacket: VPN not active, dropping")
        return false
    }
    clearnetGatewayForwarder.forward(packet)  // signature changes — see §8
    return true
}
```

**Injection options (choose one):**

- Constructor-inject a `() -> VpnStateDto` lambda (simplest, testable)
- Constructor-inject `StateFlow<VpnStateDto>` and read `.value`

Do NOT let `AndroidVirtualNode` take a dependency on `MeshrabiyaApiImpl`
directly (that would create a circular dependency — the node is created before the API).
Pass the flow by reference at construction time.

---

## 8. `ClearnetGatewayForwarder.kt` — Remove WiFi Binding

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/ClearnetGatewayForwarder.kt`

### 8.1 Current signature (research-verified: ClearnetGatewayForwarder.kt L31)

```kotlin
fun forward(packet: VirtualPacket, internetWifiNetwork: Network)
```

### 8.2 New signature

```kotlin
fun forward(packet: VirtualPacket)
```

### 8.3 Remove `internetWifiNetwork.bindSocket(socket)` call

```kotlin
// BEFORE:
val socket = Socket()
internetWifiNetwork.bindSocket(socket)   // ← DELETE this line
socket.connect(InetSocketAddress(destIp, destPort), CONNECT_TIMEOUT_MS)

// AFTER:
val socket = Socket()
// No bind — socket routes through active VPN (Orbot TUN) automatically.
socket.connect(InetSocketAddress(destIp, destPort), CONNECT_TIMEOUT_MS)
```

That is the only functional change inside this file. The class itself survives;
it just loses its WiFi dependency.

Update the `Network` import if it is now unused.

---

## 9. `VirtualNode.kt` — Stub Update

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**Research confirmed actual declaration at L1560:**
```kotlin
protected open fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean = false
```

**No change needed to VirtualNode.kt.** The base class stub already uses
`VirtualPacket` and returns `Boolean`. The `AndroidVirtualNode` override (§7)
must match this signature exactly: accept `VirtualPacket`, return `Boolean`
(`true` = handled, `false` = not handled).

The call site in VirtualNode.kt L1608 is:
```kotlin
if (onClearnetGatewayPacket(packet)) return
```
This confirms the `true` return value from the override is what signals
"packet was handled by the clearnet gateway forwarder."

---

## 10. `MeshrabiyaWifiManagerAndroid.kt` — Internet WiFi Removal

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/wifi/MeshrabiyaWifiManagerAndroid.kt`

### 10.1 Remove

- `var internetWifiNetwork: Network?` field
- `val internetWifiNetworkStateFlow: StateFlow<...>` field
- `fun connectToInternetWifi(ssid, password)` method
- `fun disconnectFromInternetWifi()` method
- `NetworkCallback` registration for non-mesh WiFi
- Any `WifiNetworkSpecifier` construction targeting a user-named SSID

These are all dead once `ClearnetGatewayForwarder` stops accepting a `Network`
parameter and `AndroidVirtualNode` stops referencing `internetWifiNetwork`.

### 10.2 Do NOT remove

- Everything related to mesh hotspot / AP / station management
- `meshWifiNetworkStateFlow` or equivalent mesh-channel tracking

---

## 11. `EnhancedMeshFragment.kt` — UI Changes

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

### 11.1 Remove UI elements (references at these approximate lines)

| Element | Line references | Action |
|---------|----------------|--------|
| `wifiApConnectionButton` | ~737, 857–865, 1079–1086 | **DELETE** — no VPN equivalent needed |
| `internetWifiRow` | ~808–815 | **REPURPOSE** — rename to `vpnStatusRow`, keep as VPN status row |
| `internetWifiIpText` | ~808–815 | **REPURPOSE** — rename to `vpnStatusText`, shows VPN port info |
| `internetWifiChipSta` | ~808–815 | **REPURPOSE** — rename to `vpnTransportChip`, change text to `"VPN"` |
| `internetWifiGreenDot` | ~811, 1290 | **REPURPOSE** — rename to `vpnStatusGreenDot`, wire to VPN active state |

Only `wifiApConnectionButton` is deleted. The four `internetWifi*` views are
repurposed in place — rename their IDs, update their text, rewire their observers.
See §11.8 and §12 for details.

### 11.2 Replace observer method

`setupNonMeshWifiObserver()` (~line 847) — delete the method body and its call site.
Replace with `setupVpnStatusObserver()` (see §11.8) at the same call site.

### 11.3 Remove dialog/helper methods

- `showInternetWifiConnectionDialog()` (~line 3200)
- `showPassphraseDialog()` (~line 3215)
- `connectToInternetWifi()` (~line 3230)

### 11.4 Remove API calls

Any direct invocation of:
- `meshrabiyaApi.connectToNonMeshWifi(...)`
- `meshrabiyaApi.disconnectFromNonMeshWifi()`
- `meshrabiyaApi.scanAvailableWifiNetworks()`
- `meshrabiyaApi.getNonMeshWifiStateFlow()`

### 11.5 Rewire green dot

**Current logic (~lines 200–210 of research output):**

```kotlin
getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
    if (!deferredViewsInitialized) return@collect
    val nonMeshInternet = (meshrabiyaApi as? MeshrabiyaApiImpl)
        ?.networkInfoFlow?.value?.nonMeshHasInternet == true
    val hasAnyInternet = nonMeshInternet || gatewayAvailable
    activity?.runOnUiThread {
        MeshUIBindings.meshInternetGreenDot.visibility =
            if (hasAnyInternet) View.VISIBLE else View.GONE
    }
}
```

**New logic:**

```kotlin
combine(
    getMeshInternetGatewayAvailableFlow(),
    meshrabiyaApi.getVpnStateFlow()
) { gatewayAvailable, vpnState ->
    gatewayAvailable || vpnState.active
}.collect { hasAnyInternet ->
    if (!deferredViewsInitialized) return@collect
    activity?.runOnUiThread {
        MeshUIBindings.meshInternetGreenDot.visibility =
            if (hasAnyInternet) View.VISIBLE else View.GONE
    }
}
```

**Rationale:** If this node is the MESH_ROUTER with VPN active, it IS the
gateway — `gatewayAvailable` will already be true via the originator message
flow. But if VPN just became active and the originator message hasn't propagated
yet, `vpnState.active` catches it immediately. For non-gateway nodes, only
`gatewayAvailable` fires.

### 11.6 VPN transport gating — Start Mesh and Join Mesh buttons

**Context:** On devices without STA+AP concurrency (e.g. Phone 2: LML211BL / MSM8937,
Android 8.1, confirmed single `wlan0`), if Orbot VPN is routing over WiFi, starting a mesh
hotspot or joining another mesh AP would disconnect `wlan0` from the internet AP and kill
the VPN. The buttons must be disabled in that state.

**New helper method in `EnhancedMeshFragment`:**

```kotlin
/**
 * Returns true if an active VPN is using WiFi as its underlying transport.
 * Detected by checking whether the active network has both TRANSPORT_VPN
 * and TRANSPORT_WIFI set — Android includes underlying transports in VPN
 * network capabilities.
 */
private fun isVpnOverWifi(): Boolean {
    val cm = requireContext().getSystemService(ConnectivityManager::class.java)
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
           caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
```

**Changes to `updateMeshUIState()`** (the authoritative button state method at L1510):

```kotlin
private fun updateMeshUIState(meshStatus: MeshStateDto) {
    val vpnOverWifi = isVpnOverWifi()  // NEW: compute once at top

    // --- meshToggleButton ("Start Mesh" / "Stop Mesh") ---
    // Existing: hidden if device has no AP hardware
    // NEW: disabled (but visible) if VPN is using WiFi — prevents killing VPN
    if (!meshrabiyaApi.isApCapable()) {
        MeshUIBindings.meshToggleButton.visibility = View.GONE
    } else {
        MeshUIBindings.meshToggleButton.visibility = View.VISIBLE
        MeshUIBindings.meshToggleButton.isEnabled =
            !vpnOverWifi &&                              // NEW gate
            meshStatus != MeshStateDto.INITIALIZING &&
            meshStatus != MeshStateDto.ERROR &&
            meshStatus != MeshStateDto.UNKNOWN
    }

    // --- joinMeshButton ("Join Mesh") ---
    // Existing: enabled only when DISCONNECTED
    // NEW: disabled if VPN over WiFi; fine red print text becomes visible
    MeshUIBindings.joinMeshButton.isEnabled =
        !vpnOverWifi && meshStatus == MeshStateDto.DISCONNECTED
    MeshUIBindings.vpnMeshWarningText.visibility =
        if (vpnOverWifi) View.VISIBLE else View.GONE   // NEW warning text view (see §12)

    // ... rest of existing state machine unchanged ...
}
```

**Note:** Use `View.GONE` for non-AP hardware (permanently incapable) and `isEnabled = false`
for VPN-over-WiFi (temporarily blocked, capability returns when VPN stops or switches to cellular).

### 11.8 VPN status row observer

Replace `setupNonMeshWifiObserver()` with `setupVpnStatusObserver()`. This drives the
repurposed views (`vpnStatusRow`, `vpnStatusText`, `vpnTransportChip`, `vpnStatusGreenDot`)
from the VPN state flow:

```kotlin
private fun setupVpnStatusObserver() {
    viewLifecycleOwner.lifecycleScope.launch {
        meshrabiyaApi.getVpnStateFlow().collect { vpnState ->
            if (!deferredViewsInitialized) return@collect
            activity?.runOnUiThread {
                val isActive = vpnState.active
                // Row visibility: always shown (collapsed when VPN OFF is acceptable
                // but keep visible so user can see VPN status at a glance)
                MeshUIBindings.vpnStatusRow.visibility = View.VISIBLE
                // Green dot: visible only when VPN is active
                MeshUIBindings.vpnStatusGreenDot.visibility =
                    if (isActive) View.VISIBLE else View.GONE
                // Chip text: always "VPN" (transport label, not a status)
                MeshUIBindings.vpnTransportChip.text = "VPN"
                // Status text: show SOCKS port when active, "Off" otherwise
                MeshUIBindings.vpnStatusText.text = if (isActive)
                    "SOCKS :${vpnState.socksPort}"
                else
                    "Off"
            }
        }
    }
}
```

Call `setupVpnStatusObserver()` from the same location where `setupNonMeshWifiObserver()` was called.

### 11.9 Mesh socket VPN routing — no changes needed

Research confirmed Meshrabiya uses `ConnectivityManager.bindProcessToNetwork(wifiNetwork)`
at `MeshrabiyaWifiManagerAndroid.kt L1450` to bind mesh sockets directly to the WiFi
network. This binding bypasses VPN tunnel routing automatically — mesh UDP traffic goes
directly via `wlan0`, not through the Orbot TUN interface. **No `VpnService.protect()`
calls are needed** and none should be added. The binding approach is the preferred
modern Android pattern.

---

## 12. Layout XML — Remove Wi-Fi UI Elements

**File (research-verified):**  
`app/src/main/res/layout/fragment_mesh_enhanced.xml` — `wifiApConnectionButton` at **L85**.

**IMPORTANT — selective removal only.** The WiFi button lives inside a `LinearLayout`
alongside four other buttons: Join Mesh, Merge Mesh, Mesh Extender AP, and
Expand/Collapse Indicator. **Delete ONLY the `wifiApConnectionButton` MaterialButton
view (L85–99).** Do NOT delete the parent `LinearLayout` or any sibling button.

**Repurpose (rename IDs, do NOT delete) the four `internetWifi*` views:**

| Old ID | New ID | Change |
|--------|--------|--------|
| `internetWifiRow` | `vpnStatusRow` | No structural change; row stays |
| `internetWifiIpText` | `vpnStatusText` | Text driven by observer (§11.8) |
| `internetWifiChipSta` | `vpnTransportChip` | Set `android:text="VPN"` (remove WiFi icon if any) |
| `internetWifiGreenDot` | `vpnStatusGreenDot` | Visibility wired to VPN active (§11.8) |

Rename the `android:id` attribute on each view in the XML. Update `MeshUIBindings`
with the four new field names, removing the four old ones.

No replacement views are needed. The existing mesh internet green dot
(`meshInternetGreenDot` or `MeshUIBindings.meshInternetGreenDot`) is retained
and re-wired per §11.5.

**Add new view:** A `TextView` for the VPN warning below `joinMeshButton`:

```xml
<TextView
    android:id="@+id/vpnMeshWarningText"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Stop VPN to connect Mesh"
    android:textColor="@color/red"
    android:textSize="11sp"
    android:visibility="gone"
    android:layout_gravity="center_horizontal"
    app:layout_constraintTop_toBottomOf="@id/joinMeshButton" />
```

Place this immediately after the `joinMeshButton` view. Bind it as
`MeshUIBindings.vpnMeshWarningText` (add to `MeshUIBindings` object). Visibility
is controlled by `updateMeshUIState()` per §11.6.

---

## 13. App Layer — VPN State Caller

This is the bridge between OrbotService broadcasts and Meshrabiya.

**Integration point: `TorStatusMonitor.onReceive()`** — NOT a new receiver in `OrbotMeshService`.

**Why TorStatusMonitor is the right place (Round 5 research confirmed):**
- Located at `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/TorStatusMonitor.kt`
- Already registered for `ACTION_STATUS` with `ContextCompat.RECEIVER_NOT_EXPORTED`
- Already calls `api.updateTorStatus(isTorActive)` — has direct `MeshrabiyaApiImpl` reference
- `requestStatusUpdate()` is already called at startup inside `MeshrabiyaApiImpl.initMesh()` (L281)
- Adding `notifyVpnStateChanged()` here handles BOTH startup and runtime cases with zero new infrastructure

**Startup ordering: both orderings handled automatically**

| Scenario | What happens |
|----------|--------------|
| Mesh starts, VPN activates later | TorStatusMonitor receiver catches the ACTION_STATUS broadcast; calls `notifyVpnStateChanged(active=true)` |
| VPN already active when mesh starts | `initMesh()` calls `requestStatusUpdate()` → Orbot's `replyWithStatus()` fires ACTION_STATUS immediately → receiver fires → `notifyVpnStateChanged(active=true)` |
| Neither running yet | `_vpnState` defaults to `VpnStateDto(active=false)` — correct |

`requestStatusUpdate()` sends a targeted ACTION_STATUS intent to Orbot (`setPackage("org.torproject.android")`),
which causes Orbot to call `replyWithStatus()` and broadcast its current state back. This is the
existing established pattern — do NOT change the `RECEIVER_NOT_EXPORTED` flag.

**What to add to `TorStatusMonitor.onReceive()`:**

```kotlin
// BEFORE (TorStatusMonitor.kt ~L182–204 current body):
override fun onReceive(context: Context?, intent: Intent?) {
    if (intent?.action != ACTION_TOR_STATUS) { ... return }
    val status = intent.getStringExtra(EXTRA_TOR_STATUS)
    val isTorActive = (status == STATUS_ON)
    val api = MeshrabiyaApiImpl.getInstance()
    api.updateTorStatus(isTorActive)
}

// AFTER — also extract SOCKS port and update VPN state:
override fun onReceive(context: Context?, intent: Intent?) {
    if (intent?.action != ACTION_TOR_STATUS) { ... return }
    val status = intent.getStringExtra(EXTRA_TOR_STATUS)
    val isTorActive = (status == STATUS_ON)
    val socksPort = intent.getIntExtra(
        OrbotConstants.EXTRA_SOCKS_PROXY_PORT,
        OrbotConstants.SOCKS_PROXY_PORT_DEFAULT.toInt()
    )
    val api = MeshrabiyaApiImpl.getInstance()
    api.updateTorStatus(isTorActive)
    api.notifyVpnStateChanged(VpnStateDto(active = isTorActive, socksPort = socksPort))
}
```

**No changes to `OrbotMeshService`** for VPN state — it already calls `initMesh()` which
registers TorStatusMonitor and calls `requestStatusUpdate()`. The startup case is already covered.

**OrbotStateReceiver note:** Round 5 research found an existing
`app/src/main/java/com/ustadmobile/orbotmeshrabiyaintegration/routing/OrbotStateReceiver.kt`
that also handles ACTION_STATUS. Do NOT add `notifyVpnStateChanged()` there — it is in a
different routing layer. TorStatusMonitor is the single source of truth for Orbot state inside Meshrabiya.

**OrbotConstants verified values (round 3 — all confirmed from literal source reads):**

| Constant name | Actual string value | Source |
|---|---|---|
| `OrbotConstants.EXTRA_SOCKS_PROXY_PORT` | `"org.torproject.android.intent.extra.SOCKS_PROXY_PORT"` | OrbotConstants.kt L88 ✅ |
| `OrbotConstants.SOCKS_PROXY_PORT_DEFAULT` | `"9050"` | OrbotConstants.kt L45 ✅ |
| `OrbotConstants.HTTP_PROXY_PORT_DEFAULT` | `"8118"` | OrbotConstants.kt L44 ✅ |
| `OrbotConstants.ACTION_STATUS` | `"org.torproject.android.intent.action.STATUS"` | TorStatusMonitoringTest.kt L146; plan .md docs ✅ |
| `OrbotConstants.EXTRA_STATUS` | `"org.torproject.android.intent.extra.STATUS"` | TorStatusMonitoringTest.kt L147 ✅ |
| `OrbotConstants.STATUS_ON` | `"ON"` | Test code L1102 ✅ |
| `OrbotConstants.STATUS_OFF` | `"OFF"` | Test code L1143 ✅ |

**Use the constant names in code** (`OrbotConstants.ACTION_STATUS`, etc.) — do NOT
hardcode the underlying strings. The constants resolved correctly in existing test code that
uses the literal strings directly.

---

## 14. Dead Code Deletion Summary

After all changes above compile cleanly, delete the following entirely.

| File | What to delete |
|------|----------------|
| `DtoModels.kt` | `NonMeshWifiConnectionStateDto` data class |
| `DtoModels.kt` | `NonMeshWifiStatusDto` enum |
| `DtoModels.kt` | `nonMeshSsid`, `nonMeshIpAddress`, `nonMeshHasInternet` in `NetworkInfoDto` |
| `MeshrabiyaApi.kt` | 4× nonMeshWifi method declarations |
| `MeshrabiyaApiImpl.kt` | `_nonMeshWifiState`, `_nonMeshInternetConfirmed` StateFlows |
| `MeshrabiyaApiImpl.kt` | `connectToNonMeshWifi()` + `disconnectFromNonMeshWifi()` bodies |
| `MeshrabiyaApiImpl.kt` | WiFi internet probe logic in `combine(...)` block |
| `MeshrabiyaApiImpl.kt` | `NetworkInfoDto` construction at **2 sites**: L356 and L956. Both pass `nonMeshSsid`, `nonMeshIpAddress`, `nonMeshHasInternet` — all three fields must be removed from both sites; add `vpnHasInternet = _vpnState.value.active` at both. |
| `MeshrabiyaWifiManagerAndroid.kt` | `internetWifiNetwork`, `internetWifiNetworkStateFlow`, `connectToInternetWifi()`, `disconnectFromInternetWifi()`, WiFi `NetworkCallback` for internet, `addWifiConnection()` / `createStationBoundSockets()` related to internet WiFi (L1023 `bindSocket` site) |
| `MeshInternetRelayServer.kt` | **Entire file** — Round 4 confirmed NEVER instantiated in production. `EmergentRoleManager` constructor declares `meshInternetRelayServer: MeshInternetRelayServer? = null` (default null); no call-site in the codebase passes a non-null value. Delete the entire file and remove the constructor parameter from `EmergentRoleManager`. Zero side effects. |
| `EnhancedMeshFragment.kt` | `wifiApConnectionButton` click handler; `setupNonMeshWifiObserver()` method body and call site; WiFi dialogs (`showInternetWifiConnectionDialog`, `showPassphraseDialog`, `connectToInternetWifi`); API calls to `connectToNonMeshWifi`, `disconnectFromNonMeshWifi`, `scanAvailableWifiNetworks`, `getNonMeshWifiStateFlow`. The four `internetWifi*` view references are **NOT deleted** — renamed to `vpnStatus*` per §11.1. |
| Layout XML | `wifiApConnectionButton` view only. The four `internetWifi*` view IDs are **renamed** (not deleted) per §12. |

Do NOT delete `ClearnetGatewayForwarder.kt` — it survives with the `Network`
parameter removed (see §8). `TorGatewayForwarder.kt` already uses loopback
(127.0.0.1) with no `bindSocket()` — confirmed, no change needed.

**Additional `bindSocket()` call sites — research round 3 resolved:**

| File | Line | `network` variable | Decision |
|------|------|--------------------|----------|
| `ChainSocket.kt` | L44 | `nextHop.network` from `virtualRouter.lookupNextHopForChainSocket()` — **mesh routing hop network** | **LEAVE ALONE** — not related to internet WiFi |
| `MeshInternetRelayServer.kt` | L95 | `internetNetwork` field (set by `start(network: Network?)` at L41) — **internet WiFi Network ✅** | **MUST UPDATE** — see below |
| `MeshrabiyaWifiManagerAndroid.kt` | L1023 | `network` from `getNetworkForInterface()` in `addWifiConnection()` / `createStationBoundSockets()` — **WiFi station connection network** | **Evaluate at §10** — part of station WiFi management; likely removed with internet WiFi code |

**`MeshInternetRelayServer` confirmed dead code (Round 4):**

Round 4 research confirmed `MeshInternetRelayServer` is **NEVER actually instantiated**.
`EmergentRoleManager` declares `meshInternetRelayServer: MeshInternetRelayServer? = null` as a
constructor parameter (default null), but no call-site in the codebase ever passes a non-null
value. The `start()` / `stop()` logic is completely unreachable in production.

**Action: Delete the entire `MeshInternetRelayServer.kt` file.** Also remove the
`meshInternetRelayServer: MeshInternetRelayServer? = null` parameter from the `EmergentRoleManager`
constructor. Option B (pass null) is already the de-facto state — the code is already effectively
in Option B mode at all times. Deleting is cleaner.

---

## 15. Implementation Order

**Pre-step — ALL open questions now resolved (research rounds 3 + 4):**
- `ChainSocket.kt` bindSocket → mesh routing hop → leave alone ✅
- `MeshInternetRelayServer.kt` → NEVER instantiated in production; entire file safe to delete with zero side effects ✅ *(Round 4)*
- `AndroidVirtualNode.getCurrentNodeCapabilities()` → NO override; population is in `EmergentRoleManager.kt` at **BOTH L677 AND L715** ✅ *(Round 4: two sites, not one)*
- `OrbotConstants` string values confirmed from test code ✅
- `AndroidVirtualNode` constructor change impacts: 2 sites (MeshrabiyaApiImpl L260, test-app App.kt L93) ✅
- `EmergentRoleManager` instantiation: single site at `VirtualNode.kt L286` (`open val emergentRoleManager`) ✅ *(Round 4)*
- Injection chain confirmed: 3 constructor changes needed — `EmergentRoleManager.kt`, `VirtualNode.kt`, `AndroidVirtualNode.kt` ✅ *(Round 4)*
- `OrbotMeshService` currently has NO `ACTION_STATUS` receiver; VPN state is bridged through `TorStatusMonitor.onReceive()` instead — already handles both startup and runtime cases ✅ *(Round 5)*
- `NetworkInfoDto` construction sites: 2 locations in `MeshrabiyaApiImpl.kt` at L356 and L956 ✅ *(Round 4)*
- Mesh socket protection: `bindProcessToNetwork(wifiNetwork)` at `MeshrabiyaWifiManagerAndroid.kt L1450` already bypasses VPN routing; no `protect()` calls needed ✅ *(Round 5)*
- Phone 2 capabilities confirmed: LML211BL / MSM8937 / Android 8.1 / single `wlan0`; no STA+AP concurrency; `isVpnOverWifi()` detection + button gating required ✅ *(Round 5)*

No further research needed before implementation.

Order matters to avoid compilation breaks mid-refactor.

1. **Add `VpnStateDto` to `DtoModels.kt`** — no dependents break yet.
2. **Update `NetworkInfoDto`** — remove 3 WiFi fields, add `vpnHasInternet`. Fix
   any construction sites that pass the removed fields.
3. **Rename `NodeCapabilitySnapshot.hasNonMeshInternetAccess`** →
   `hasVpnInternetAccess` in `EmergentRoleManager.kt`.
4. **Add `notifyVpnStateChanged` + `getVpnStateFlow` to `MeshrabiyaApi.kt`**;
   remove 4 nonMeshWifi declarations.
5. **Implement new methods in `MeshrabiyaApiImpl.kt`**; update `combine(...)`
   block; remove old WiFi impl bodies.
6. **Update `ClearnetGatewayForwarder.forward()` signature** (drop `Network` param);
   remove `bindSocket()` call.
7. **Update `AndroidVirtualNode.onClearnetGatewayPacket()`** — change guard
   condition; update `forward()` call site.
8. **Update `VirtualNode.kt`** — verify stub signature matches.
9. **Strip `MeshrabiyaWifiManagerAndroid.kt`** — remove internet WiFi fields and
   methods.
10. **Extend `TorStatusMonitor.onReceive()`** to extract SOCKS port and call
    `api.notifyVpnStateChanged()` alongside existing `api.updateTorStatus()` call.
    No new receiver in `OrbotMeshService` needed. Startup ordering is already
    handled by the existing `requestStatusUpdate()` call in `initMesh()` (see §13).
11. **Add `vpnOverWifi: Boolean` to `NetworkInfoDto`**; compute it in the
    `combine(...)` block using `ConnectivityManager.getNetworkCapabilities(activeNetwork)
    .hasTransport(TRANSPORT_VPN) && .hasTransport(TRANSPORT_WIFI)`.
12. **Add `isVpnOverWifi()` helper and update `updateMeshUIState()`** in
    `EnhancedMeshFragment` per §11.6 — gate `meshToggleButton.isEnabled` and
    `joinMeshButton.isEnabled` on `!vpnOverWifi`.
13. **Add `vpnMeshWarningText` TextView** to layout XML per §12; bind in
    `MeshUIBindings`; set visibility in `updateMeshUIState()`.
11. **Update `EnhancedMeshFragment.kt`** — remove WiFi UI, rewire green dot.
12. **Remove XML layout elements**; add `vpnMeshWarningText` TextView.
13. **Delete `NonMeshWifiConnectionStateDto` and `NonMeshWifiStatusDto`** — by
    this point they should be unreferenced; compiler confirms.
14. **Full build** — iterate on any remaining reference errors.

---

## 16. Verification Checklist

- [ ] Build compiles with zero errors
- [ ] No reference to `NonMeshWifi`, `nonMeshWifi`, `internetWifiNetwork`,
      `connectToNonMeshWifi`, `disconnectFromNonMeshWifi`, `scanAvailableWifiNetworks`
      anywhere in the codebase (run `grep_search` to confirm)
- [ ] Green dot appears in UI when Orbot VPN is ON and node has MESH_ROUTER role
- [ ] Green dot disappears when VPN is turned off
- [ ] Gateway flag in originator messages is set when VPN is ON and gateway pref
      is enabled
- [ ] Gateway flag is cleared when VPN is turned off
- [ ] Clearnet packets received by MESH_ROUTER node are forwarded when VPN active
- [ ] Clearnet packets are dropped (not forwarded) when VPN not active
- [ ] **Start Mesh button disabled (grayed out) when VPN is active over WiFi**
- [ ] **Start Mesh button enabled when VPN is active over cellular (not WiFi)**
- [ ] **Join Mesh button disabled when VPN is active over WiFi; "Stop VPN to connect Mesh" text visible in red below button**
- [ ] **Join Mesh button enabled when VPN is active over cellular**
- [ ] No WiFi connect button or WiFi-related UI remains in fragment
- [ ] Fragment builds without dead-import lint warnings

---

## 17. Known Risks

| Risk | Severity | Status | Mitigation |
|------|----------|--------|------------|
| Startup ordering: VPN already active when mesh starts — `_vpnState` stays `active=false` | Medium | ✅ Resolved | **Round 5:** `TorStatusMonitor.requestStatusUpdate()` is already called in `initMesh()` (L281). When VPN is already active, this triggers Orbot’s `replyWithStatus()` → ACTION_STATUS fires → `onReceive()` extension sets `notifyVpnStateChanged(active=true)`. No additional startup code needed. |
| Mesh sockets entering VPN tunnel (routing loop risk) | Medium | ✅ Resolved | **Round 5:** Meshrabiya uses `bindProcessToNetwork(wifiNetwork)` at `MeshrabiyaWifiManagerAndroid.kt L1450`. Bound sockets bypass VPN routing tables automatically. No `VpnService.protect()` calls needed. |
| VPN-over-WiFi blocks mesh operations on no-STA+AP-concurrency devices | Medium | ✅ Resolved | **Round 5:** Phone 2 (LML211BL, MSM8937) confirmed single `wlan0` — no STA+AP concurrency. Detection via `TRANSPORT_VPN && TRANSPORT_WIFI` on active network. Start Mesh disabled (`isEnabled=false`); Join Mesh disabled with red warning text per §11.6. |
| `TRANSPORT_VPN` capability not available on old API levels | Low | ✅ Resolved | `NetworkCapabilities.TRANSPORT_VPN` exists from API 21; Phone 2 is SDK 27; min SDK of the app is 21+ (confirmed from prior research). No compatibility shim needed. |
| `EmergentRoleManager` constructor change (VPN state injection) — downstream wiring not yet traced | Medium | ✅ Resolved | **Round 4:** Single instantiation at `VirtualNode.kt L286` (`open val emergentRoleManager`). Add `vpnStateFlow: StateFlow<VpnStateDto>? = null` to `EmergentRoleManager` constructor, to `VirtualNode` constructor, and pass at L286. |
| `AndroidVirtualNode` constructor change (VPN state injection) at MeshrabiyaApiImpl L260 and test-app App.kt L93 | Low | ✅ Resolved | **Round 4:** Both sites confirmed. Add `vpnStateFlow: StateFlow<VpnStateDto>? = null` to `AndroidVirtualNode` constructor; pass `_vpnStateFlow` at MeshrabiyaApiImpl L260; omit (default null) at test-app App.kt L93. |
| `MeshInternetRelayServer` dead code decision (delete vs. pass null) | Low | ✅ Resolved | **Round 4:** Never instantiated — `EmergentRoleManager` constructor param defaults to null; zero production call-sites exist. Delete entire file (Option A). Remove the constructor param slot from `EmergentRoleManager` too. |
| `OrbotConstants` ACTION_STATUS / EXTRA_STATUS / STATUS_ON delegate to JNI (`TorService.*`) — literal strings unknown | Medium | ✅ Resolved | Strings confirmed from test code: ACTION_STATUS="org.torproject.android.intent.action.STATUS", EXTRA_STATUS="org.torproject.android.intent.extra.STATUS", STATUS_ON="ON" |
| `AndroidVirtualNode.getCurrentNodeCapabilities()` override location | Medium | ✅ Resolved | No override exists; population is in `EmergentRoleManager.kt` L715 |
| `ChainSocket.kt` L44 — bindSocket may be dead | Medium | ✅ Resolved | ChainSocket uses mesh routing hop network; unrelated to internet WiFi; leave alone |
| `MeshInternetRelayServer.kt` L95 — bindSocket dead or live | Medium | ✅ Resolved | Uses `internetNetwork` (internet WiFi); becomes dead code — add to §14 delete list |
| `MeshrabiyaWifiManagerAndroid.kt` L1023 — bindSocket scope | Medium | ✅ Resolved | WiFi station connection network; removed as part of §10 internet WiFi code removal |
| `NetworkInfoDto` serialization — removing fields breaks JSON consumers | Medium-Low | ✅ Resolved | Research confirmed: no `@Serializable` annotation, no JSON/disk storage |
| `TorGatewayForwarder` has its own `bindSocket()` | Medium | ✅ Resolved | Already uses loopback (127.0.0.1); no `bindSocket()` call |
| `getMeshInternetGatewayAvailableFlow()` location (API or impl) | Low | ✅ Resolved | Confirmed on `MeshrabiyaApi` interface at L522; impl at L1502 |

---

## 18. Out of Scope

- SOCKS5 explicit proxying (unnecessary — VPN TUN intercepts unbound sockets)
- Backwards compatibility with older app versions (per NO_APP_VERSION_Backwards_COMPATIBILITY RULE)
- Any changes to how nodes that are NOT the MESH_ROUTER handle gateway packets
- Changes to `TorifiedBroadcastManager` or `BroadcastMessageHandler`
- Changes to Tor circuit management or `OrbotService` itself

---

*End of plan.*
