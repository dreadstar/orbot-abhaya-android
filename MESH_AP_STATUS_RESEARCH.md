=== PHASE 1: MeshStatus Enum Definition ===
file: Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/model/DtoModels.kt

MeshStateDto:
```
enum class MeshStateDto {
    INITIALIZING, CONNECTING, CONNECTED, DISCONNECTED, ERROR, UNKNOWN;
}
```

=== PHASE 2: MeshrabiyaApiImpl — Flows and Update Sources ===
file: Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt

StateFlows:
- _networkInfoFlow: MutableStateFlow<NetworkInfoDto?>  -> networkInfoFlow
- _wifiStateFlow: MutableStateFlow<MeshrabiyaWifiStateDto?> -> wifiStateFlow
- _meshStatusFlow: MutableStateFlow<MeshStateDto> -> meshStatusFlow
- _networkOverviewMetricsFlow, _nonMeshWifiState, _meshExtenderHotspotState, _meshApActiveFlow, _currentMeshRolesFlow etc.

Status updates from:
- peer count derived from node.state.originatorMessages hopCount==1
- WiFi physical link from node.meshrabiyaWifiManager.state (hotspot started or station available)
- in startEventMonitoring() mapping flows and updating _meshStatusFlow.

=== PHASE 3: Status Derivation Logic ===
file: MeshrabiyaApiImpl.kt (startEventMonitoring in lines near 280-330)

peer-count logic:
```
if (currentCount > 0 && _meshStatusFlow.value == MeshStateDto.CONNECTING) {
    _meshStatusFlow.value = MeshStateDto.CONNECTED
} else if (currentCount == 0 && _meshStatusFlow.value == MeshStateDto.CONNECTED) {
    _meshStatusFlow.value = MeshStateDto.CONNECTING
}
```

physical-link logic:
```
val hasPhysicalLink = apActive || staActive
if (hasPhysicalLink && _meshStatusFlow.value == MeshStateDto.DISCONNECTED) {
    _meshStatusFlow.value = MeshStateDto.CONNECTING
} else if (!hasPhysicalLink && (_meshStatusFlow.value == MeshStateDto.CONNECTING || _meshStatusFlow.value == MeshStateDto.CONNECTED)) {
    _meshStatusFlow.value = MeshStateDto.DISCONNECTED
}
```

=== PHASE 4: Peer List Lifecycle ===
file unreachable: no explicit MeshrabiyaConfig in repo (search matched none);
peer removal paths from originatorMessages counts in node.state.
No explicit onPeerLost or peerTimeout fields found in codebase by grep.

=== PHASE 5: UI Observation Layer ===
file: app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

status collector:
```
meshrabiyaApi.meshStatusFlow.collect { status ->
  MeshUIBindings.meshStatusText.text = status.toString()
  updateButtonStates(status)
  ...
}
```

onResume() calls meshrabiyaApi.refreshMeshStatus().

=== PHASE 6: AP Mode Detection and Usage ===
file: MeshrabiyaApiImpl.kt

AP mode detection:
- apActive = wifiState.hotspotIsStarted
- staActive = wifiState.wifiStationState.status == WifiStationState.Status.AVAILABLE
- hasPhysicalLink = apActive || staActive

Used to switch DISCONNECTED/CONNECTING only; CONNECTED/CONNECTING from neighbor peer count.

=== SUMMARY OF GAPS FOUND ===
- Phase 1: MeshStateDto is shared and does not distinguish AP vs STA.
- Phase 2: flows are mostly correct, status is derived from both peer count and wifi state.
- Phase 3: status logic does not enforce explicit AP role meaning in CONNECTED state, could be stale.
- Phase 4: no explicit peer timeout or onPeerLost in visible code; peer removals depend on topology updates.
- Phase 5: UI observes meshStatusFlow directly and may show stale CONNECTED if flow isn't updated.
- Phase 6: AP-mode contributions are present only as physical link indicator, not explicit status-per-mode.
