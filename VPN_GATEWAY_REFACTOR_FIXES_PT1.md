# VPN Gateway Refactor — Outstanding Manual Changes
**Created:** 2026-04-03
**Source:** VPN_GATEWAY_REFACTOR_IMPLEMENTATION.md

All changes are listed **bottom-to-top** within each file so edits do not shift
line numbers for subsequent changes in the same file.
All BEFORE text is verbatim from disk as of 2026-04-03.

---

## File 1 — `MeshrabiyaApiImpl.kt`

**Full path:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

---

### A-6 — Delete log helpers at bottom of class (last lines before closing `}`)

**BEFORE (last ~10 lines of the class):**
```kotlin
    private fun logNonMeshState(correlationId: String, prefix: String, state: NonMeshWifiConnectionStateDto) {
        Log.d(TAG, "[NONMESH_FLOW][$correlationId][$prefix] status=${state.status} connectedSsid=${state.connectedSsid} hasInternetAccess=${state.hasInternetAccess} error=${state.errorMessage}")
    }

    private fun logNetworkInfo(correlationId: String, info: NetworkInfoDto?) {
        Log.d(TAG, "[NONMESH_FLOW][$correlationId] networkInfo: peers=${info?.connectedPeers} nonMeshSsid=${info?.nonMeshSsid} nonMeshHasInternet=${info?.nonMeshHasInternet}")
    }

}
```

**AFTER:**
```kotlin
}
```

---

### A-5 — Delete `isInternetWifiFeatureAvailable()` (~line 2572)

**BEFORE:**
```kotlin
    override fun isInternetWifiFeatureAvailable(): Boolean {
        val node = myNode ?: return false
        val wifiState = node.meshrabiyaWifiManager.currentWifiState
        if (wifiState.hotspotIsStarted && wifiState.concurrentApStationSupported) {
            return true
        }
        if (!wifiState.hotspotIsStarted &&
            wifiState.wifiStationState.status == com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState.Status.AVAILABLE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            wifiState.staStaConcurrencySupported) {
            return true
        }
        return false
    }

    override fun isWifiEnabled(): Boolean {
```

**AFTER:**
```kotlin
    override fun isWifiEnabled(): Boolean {
```

---

### A-4 — Delete `scanAvailableWifiNetworks()` (~line 2529)

**BEFORE:**
```kotlin
    override suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto> {
        val ctx = appContext ?: return emptyList()
        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        // Block until the OS signals SCAN_RESULTS_AVAILABLE (guarantees fresh results).
        // Falls back to cached results if broadcast doesn't arrive within 5 s.
        val scanCompleted = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
                ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                cont.invokeOnCancellation {
                    try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                @Suppress("DEPRECATION")
                wifiManager.startScan()
            }
        }
        if (scanCompleted == null) {
            Log.w(TAG, "scanAvailableWifiNetworks: scan broadcast timed out, using cached results")
        }
        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults ?: return emptyList()
        val list = results
            .filter { it.SSID.isNotEmpty() }
            .map { scanResult ->
                NonMeshWifiNetworkDto(
                    ssid = scanResult.SSID,
                    bssid = scanResult.BSSID,
                    signalStrength = scanResult.level,
                    isSecured = scanResult.capabilities.contains("WPA") ||
                                scanResult.capabilities.contains("WEP"),
                )
            }
            .sortedByDescending { it.signalStrength }
        Log.i(TAG, "scanAvailableWifiNetworks: found ${list.size} SSIDs ${list.map{it.ssid}}")
        return list
    }

    override fun isInternetWifiFeatureAvailable(): Boolean {
```

**AFTER:**
```kotlin
    override fun isInternetWifiFeatureAvailable(): Boolean {
```

---

### A-3 — Delete `getNonMeshWifiStateFlow()` (~line 2525)

**BEFORE:**
```kotlin
    override fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto> {
        return _nonMeshWifiState.asStateFlow()
    }

    override suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto> {
```

**AFTER:**
```kotlin
    override suspend fun scanAvailableWifiNetworks(): List<NonMeshWifiNetworkDto> {
```

---

### A-2 — Delete `disconnectFromNonMeshWifi()` (~line 2512)

**BEFORE:**
```kotlin
    override suspend fun disconnectFromNonMeshWifi(): Boolean {
        val node = myNode ?: return false
        // Notify mesh peers before dropping internet WiFi if this node acts as a gateway
        val roles = emergentRoleManager?.getCurrentMeshRoles() ?: emptySet()
        if (roles.any { it == MeshRole.TOR_GATEWAY || it == MeshRole.CLEARNET_GATEWAY }) {
            node.broadcastGatewayDown()
        }
        node.meshrabiyaWifiManager.disconnectFromInternetWifi()
        _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.IDLE)
        _networkInfoFlow.value = getNetworkInfo()
        return true
    }

    override fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto> {
```

**AFTER:**
```kotlin
    override fun getNonMeshWifiStateFlow(): StateFlow<NonMeshWifiConnectionStateDto> {
```

---

### A-1 — Delete `connectToNonMeshWifi()` (~line 2449)

**BEFORE:**
```kotlin
    // ========================================
    override suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): NonMeshWifiConnectionStateDto {
        val correlationId = UUID.randomUUID().toString()
        Log.i(TAG, "[NONMESH_FLOW][$correlationId] connectToNonMeshWifi start ssid='$ssid' passphrasePresent=${passphrase.isNotEmpty()} meshInitialized=${myNode != null}")

        getHotspotInfo()?.ssid?.let { current ->
            if (current == ssid) {
                val failed = NonMeshWifiConnectionStateDto(
                    status = NonMeshWifiStatusDto.FAILED,
                    errorMessage = "Cannot connect to own hotspot"
                )
                Log.w(TAG, "[NONMESH_FLOW][$correlationId] abort - cannot connect to own hotspot (self ssid=$ssid)")
                _nonMeshWifiState.value = failed
                return failed
            }
        }

        _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(status = NonMeshWifiStatusDto.CONNECTING)
        Log.d(TAG, "[NONMESH_FLOW][$correlationId] state=CONNECTING")

        val result = try {
            myNode?.meshrabiyaWifiManager
                ?.connectToInternetWifi(ssid, passphrase)
                ?: Result.failure(IllegalStateException("Mesh node unavailable"))
        } catch (e: Exception) {
            Log.e(TAG, "[NONMESH_FLOW][$correlationId] exception in manager connectToInternetWifi", e)
            Result.failure(e)
        }

        if (result.isSuccess) {
            Log.i(TAG, "[NONMESH_FLOW][$correlationId] manager reported success for $ssid")
            _nonMeshWifiState.value = NonMeshWifiConnectionStateDto(
                status = NonMeshWifiStatusDto.CONNECTED,
                connectedSsid = ssid,
            )

            val finalState = try {
                withTimeout(10_000) {
                    _nonMeshWifiState.first { it.hasInternetAccess }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "[NONMESH_FLOW][$correlationId] validation timeout for $ssid; current=${_nonMeshWifiState.value}")
                _nonMeshWifiState.value
            }

            Log.i(TAG, "[NONMESH_FLOW][$correlationId] final state for $ssid = $finalState")
            _networkInfoFlow.value = getNetworkInfo()
            return finalState
        } else {
            val error = result.exceptionOrNull()
            Log.w(TAG, "[NONMESH_FLOW][$correlationId] connection failed for $ssid; error=${error?.message}", error)
            val failed = NonMeshWifiConnectionStateDto(
                status = NonMeshWifiStatusDto.FAILED,
                errorMessage = error?.message,
            )
            _nonMeshWifiState.value = failed
            _networkInfoFlow.value = getNetworkInfo()
            return failed
        }
    }

    override suspend fun disconnectFromNonMeshWifi(): Boolean {
```

**AFTER:**
```kotlin
    override suspend fun disconnectFromNonMeshWifi(): Boolean {
```

---

### A-0 — Fix `getNetworkInfo()` helper (~line 898)

**BEFORE:**
```kotlin
        // if the node has a non-mesh connection, include it
        val nonMeshState = _nonMeshWifiState.value
        val nonMeshSsid = nonMeshState.connectedSsid
        // IP address comes from the WifiManager's internetWifiNetworkStateFlow so that
        // MeshrabiyaApiImpl contains no networking logic of its own.
        val nonMeshIp = node.meshrabiyaWifiManager.internetWifiNetworkStateFlow.value.ipAddress
        val nonMeshHasInternet = nonMeshState.hasInternetAccess
            .takeIf { nonMeshState.status == NonMeshWifiStatusDto.CONNECTED }

        if (nonMeshSsid != null) {
            Log.d(TAG, "[NETWORKINFO] non-mesh connected ssid=$nonMeshSsid ip=$nonMeshIp internet=$nonMeshHasInternet")
        }

        return NetworkInfoDto(
            bssid = "",
            ssid = "",
            ipAddress = node.addressAsInt.addressToDotNotation(),
            isConnected = true,
            connectedPeers = connectedNeighbors,
            torGateways = torGateways,
            clearnetGateways = clearnetGateways,
            nonMeshSsid = nonMeshSsid,
            nonMeshIpAddress = nonMeshIp,
            nonMeshHasInternet = nonMeshHasInternet
        )
    }
    override fun getNodeId(): Int {
```

**AFTER:**
```kotlin
        val vpn = _vpnStateFlow.value
        return NetworkInfoDto(
            bssid = "",
            ssid = "",
            ipAddress = node.addressAsInt.addressToDotNotation(),
            isConnected = true,
            connectedPeers = connectedNeighbors,
            torGateways = torGateways,
            clearnetGateways = clearnetGateways,
            vpnHasInternet = vpn.active,
            vpnOverWifi = vpn.vpnOverWifi,
        )
    }
    override fun getNodeId(): Int {
```

---

### A-(-1) — Delete `checkNonMeshInternetAccess()` private method (~line 556)

Search for `private suspend fun checkNonMeshInternetAccess(node: AndroidVirtualNode): Boolean =`
and delete the entire method from that line through its closing `}`.

The method uses `withContext(Dispatchers.IO)` and makes an HTTP HEAD request —
delete from the `private suspend fun` line through the closing `}` of the
`withContext` block.

---

### A-(-2) — Delete `_nonMeshWifiState` combine block in `startEventMonitoring()` (~line 473)

**BEFORE:**
```kotlin
        

        // Mesh gateway internet check — runs on ANY mesh-connected node (AP or STA)
        // This is intentionally a SEPARATE top-level launch, not nested inside the
        // nonMeshWifiState collector. A pure STA node (Phone 2) must reach this path
        // even when it has no upstream WiFi of its own.
        eventMonitoringScope.launch {
            combine(
                node.originatingMessageManager.topologyMapFlow,
                _currentMeshRolesFlow,
                _nonMeshWifiState
            ) { topology, localRoles, nonMeshState ->
                val hasRemoteGateway = topology.values.any { nodeInfo ->
                    (nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) ||
                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY)) &&
                    !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                }
                val isLocalGateway = MeshRoleDto.CLEARNET_GATEWAY in localRoles ||
                                    MeshRoleDto.TOR_GATEWAY in localRoles
                val nonMeshConnected = nonMeshState.status == NonMeshWifiStatusDto.CONNECTED
                val apActive = _meshApActiveFlow.value
                val shouldCheck = hasRemoteGateway ||
                                (apActive && isLocalGateway && nonMeshConnected)
                shouldCheck
            }
            .distinctUntilChanged()
            .collect { shouldCheck ->
                meshInternetCheckJob?.cancel()
                meshInternetCheckJob = null
                if (shouldCheck) {
                    val capturedNode = node
                    meshInternetCheckJob = launch {
                        // Helper to run one probe cycle (extracted to avoid repetition).
                        suspend fun runProbe(): Boolean {
                            val currentLocalRoles = _currentMeshRolesFlow.value
                            val currentIsLocalGateway =
                                MeshRoleDto.CLEARNET_GATEWAY in currentLocalRoles ||
                                MeshRoleDto.TOR_GATEWAY in currentLocalRoles
                            val currentHasRemote = capturedNode.originatingMessageManager
                                .getTopologyMapInfo().values.any { nodeInfo ->
                                    (nodeInfo.hasRole(MeshRole.CLEARNET_GATEWAY) ||
                                    nodeInfo.hasRole(MeshRole.TOR_GATEWAY)) &&
                                    !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
                                }
                            return if (currentIsLocalGateway && !currentHasRemote) {
                                checkNonMeshInternetAccess(capturedNode)
                            } else {
                                checkInternetViaMeshGateway()
                            }
                        }

                        // Fire immediately when a gateway first becomes visible in topology.
                        // Previously this had delay() first, causing a 30-second blind window
                        // after every join before the green dot could appear.
                        _meshInternetViaGatewayConfirmed.value = runProbe()

                        // Then continue at the normal periodic interval.
                        while (true) {
                            delay(MESH_INTERNET_CHECK_INTERVAL_MS)
                            _meshInternetViaGatewayConfirmed.value = runProbe()
                        }
                    }
                } else {
                    _meshInternetViaGatewayConfirmed.value = false
                }
            }
        }
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
```

**AFTER:**
```kotlin
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
```

Delete from the two blank lines + `// Mesh gateway internet check —` comment through the
closing `}` of the `eventMonitoringScope.launch` block. Do NOT delete the `}` on its own
line that closes `startEventMonitoring()`, nor the `/**\n * Section 6:` javadoc that follows.

---

## File 2 — `EnhancedMeshFragment.kt`

**Full path:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

---

### B-7 — Delete entire WiFi Internet Connection section (~lines 2607–2685)

**BEFORE (last ~80 lines of file):**
```kotlin
    // ========================================
    // WiFi Internet Connection (WIFI_AP_CON / Change 16)
    // ========================================

    private fun showInternetWifiConnectionDialog() {
        val scanningDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Scanning for networks…")
            .setMessage("Please wait")
            .setCancelable(false)
            .create()
        scanningDialog.show()
        lifecycleScope.launch {
            val networks = meshrabiyaApi.scanAvailableWifiNetworks()
            scanningDialog.dismiss()
            if (networks.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No WiFi networks found. Ensure location permission is granted.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val ssidList = networks.map { "${it.ssid} (${it.signalStrength} dBm)" }.toTypedArray()
            var selectedIndex = 0

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Connect to Internet WiFi")
                .setSingleChoiceItems(ssidList, 0) { _, which -> selectedIndex = which }
                .setPositiveButton("Connect") { _, _ ->
                    val selected = networks[selectedIndex]
                    android.util.Log.d("EnhancedMeshFragment",
                        "[WIFI] user selected SSID=${selected.ssid}, secured=${selected.isSecured}")
                    if (selected.isSecured) {
                        showPassphraseDialog(selected.ssid)
                    } else {
                        connectToInternetWifi(selected.ssid, "")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showPassphraseDialog(ssid: String) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "WiFi Password"
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Connect to $ssid")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                connectToInternetWifi(ssid, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToInternetWifi(ssid: String, passphrase: String) {
        lifecycleScope.launch {
            val result = meshrabiyaApi.connectToNonMeshWifi(ssid, passphrase)
            // Avoid importing NonMeshWifiStatus enum — use DTO field checks instead.
            val message = when {
                result.connectedSsid != null -> "Connected to $ssid"
                result.errorMessage != null  -> "Connection failed: ${result.errorMessage}"
                else                         -> "Connecting to $ssid..."
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
```

**AFTER:**
```kotlin
}
```

---

### B-6 — Replace `setupNonMeshWifiObserver()` with `setupVpnStatusObserver()` (~line 850)

**BEFORE:**
```kotlin
    private fun setupNonMeshWifiObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getNonMeshWifiStateFlow().collect { nonMeshState ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    val connected = nonMeshState.status.name == "CONNECTED"
                    if (connected) {
                        MeshUIBindings.wifiApConnectionButton.setText(R.string.wifi_internet)
                        
                        MeshUIBindings.wifiApConnectionButton.setIconResource(R.drawable.ic_stop)
                        MeshUIBindings.wifiApConnectionButton.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                        MeshUIBindings.wifiApConnectionButton.setTextColor(android.graphics.Color.WHITE)
                    } else {
                        MeshUIBindings.wifiApConnectionButton.setText(R.string.wifi_internet)
                        MeshUIBindings.wifiApConnectionButton.setIconResource(R.drawable.ic_wifi)
                    }
                }
            }
        }
    }

    private fun setupMeshExtenderObserver() {
```

**AFTER:**
```kotlin
    private fun setupVpnStatusObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getVpnStateFlow().collect { vpnState ->
                if (!deferredViewsInitialized) return@collect
                activity?.runOnUiThread {
                    MeshUIBindings.vpnStatusRow.visibility =
                        if (vpnState.active) View.VISIBLE else View.GONE
                    if (vpnState.active) {
                        val portText = vpnState.socksPort?.let { ":$it" } ?: ""
                        MeshUIBindings.vpnStatusText.text = "Orbot VPN$portText"
                        MeshUIBindings.vpnTransportChip.visibility = View.VISIBLE
                        MeshUIBindings.vpnStatusGreenDot.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupMeshExtenderObserver() {
```

---

### B-5 — Replace `setupMeshInternetGreenDotObserver()` body (~line 823)

**BEFORE:**
```kotlin
	private fun setupMeshInternetGreenDotObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            meshrabiyaApi.getMeshInternetGatewayAvailableFlow().collect { gatewayAvailable ->
                if (!deferredViewsInitialized) return@collect
                val nonMeshInternet = (meshrabiyaApi as? MeshrabiyaApiImpl)
                    ?.networkInfoFlow?.value?.nonMeshHasInternet == true
                val hasAnyInternet = nonMeshInternet || gatewayAvailable
                activity?.runOnUiThread {
                    MeshUIBindings.meshInternetGreenDot.visibility =
                        if (hasAnyInternet) View.VISIBLE else View.GONE
                }
            }
        }
    }

	// private fun setupMeshInternetGreenDotObserver() {
```

**AFTER:**
```kotlin
	private fun setupMeshInternetGreenDotObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                meshrabiyaApi.getMeshInternetGatewayAvailableFlow(),
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
        }
    }

	// private fun setupMeshInternetGreenDotObserver() {
```

---

### B-4 — Replace nonMesh block inside `setupNetworkInfoObserver()` (~line 806)

**BEFORE:**
```kotlin
                        if (!networkInfo.nonMeshSsid.isNullOrEmpty()) {
                            android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] non‑mesh SSID present: ${networkInfo.nonMeshSsid}")
                            MeshUIBindings.internetWifiRow.visibility = View.VISIBLE
                            MeshUIBindings.internetWifiIpText.text = networkInfo.nonMeshIpAddress ?: "--"
                            MeshUIBindings.internetWifiChipSta.visibility = View.VISIBLE
                            MeshUIBindings.internetWifiGreenDot.visibility =
                                if (networkInfo.nonMeshHasInternet == true) View.VISIBLE else View.GONE
                        } else {
                            android.util.Log.d("EnhancedMeshFragment", "[NETWORK_INFO_OBSERVER] non‑mesh SSID empty – hiding row")
                            MeshUIBindings.internetWifiRow.visibility = View.GONE
                        }
```

**AFTER:**
```kotlin
                        // vpnStatusRow is driven exclusively by setupVpnStatusObserver().
                        // No nonMeshSsid / nonMeshIpAddress references remain here.
```

---

### B-3 — Delete `wifiApConnectionButton` click handler (~line 1079)

**BEFORE:**
```kotlin
		MeshUIBindings.wifiApConnectionButton.setOnClickListener {
			val wifiStatus = meshrabiyaApi.getNonMeshWifiStateFlow().value.status
			if (wifiStatus.name == "CONNECTED") {
				lifecycleScope.launch {
					meshrabiyaApi.disconnectFromNonMeshWifi()
				}
			} else {
				showInternetWifiConnectionDialog()
			}
		}

		MeshUIBindings.meshExtenderApButton.setOnClickListener {
```

**AFTER:**
```kotlin
		// wifiApConnectionButton removed — nonMeshWifi feature replaced by VPN gateway.

		MeshUIBindings.meshExtenderApButton.setOnClickListener {
```

---

### B-2 — Delete `wifiApConnectionButton` visibility + `isSta` variable in role observer (~line 731)

**BEFORE:**
```kotlin
                    val isMeshRouter = MeshRoleDto.MESH_ROUTER in rolesDto
                    val isSta =
                        meshrabiyaApi.getNonMeshWifiStateFlow().value.status.name == "CONNECTED"

                    
                    val showButtons = isMeshRouter && isSta
                    val isWifiConcurrentCapable = meshrabiyaApi.isApStaConcurrentCapable() || meshrabiyaApi.isStaStaConcurrentCapable()
                    MeshUIBindings.wifiApConnectionButton.visibility =
                        if (isWifiConcurrentCapable) View.VISIBLE else View.GONE
                    MeshUIBindings.meshExtenderApButton.visibility =
                        if (showButtons) View.VISIBLE else View.GONE
```

**AFTER:**
```kotlin
                    val isMeshRouter = MeshRoleDto.MESH_ROUTER in rolesDto
                    val showButtons = isMeshRouter
                    MeshUIBindings.meshExtenderApButton.visibility =
                        if (showButtons) View.VISIBLE else View.GONE
```

---

### B-1 — Update call site + imports (lines ~416 and 26–32, top of file — do last)

#### Call site (~line 416):

**BEFORE:**
```kotlin
        setupNonMeshWifiObserver()
```

**AFTER:**
```kotlin
        setupVpnStatusObserver()
```

#### Import block (lines 26–32):

**BEFORE:**
```kotlin
import com.ustadmobile.meshrabiya.api.model.MeshStateDto
import com.ustadmobile.meshrabiya.api.model.NonMeshWifiNetworkDto
import com.ustadmobile.meshrabiya.api.model.DropFolderItemDto
import com.ustadmobile.meshrabiya.api.model.NetworkInfoDto
import com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.MeshExtenderHotspotStateDto
```

**AFTER:**
```kotlin
import com.ustadmobile.meshrabiya.api.model.MeshStateDto
import com.ustadmobile.meshrabiya.api.model.DropFolderItemDto
import com.ustadmobile.meshrabiya.api.model.NetworkInfoDto
import com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto
import com.ustadmobile.meshrabiya.api.model.MeshRoleDto
import com.ustadmobile.meshrabiya.api.model.MeshExtenderHotspotStateDto
import com.ustadmobile.meshrabiya.api.model.VpnStateDto
```

(`NonMeshWifiNetworkDto` deleted — `VpnStateDto` added.)
