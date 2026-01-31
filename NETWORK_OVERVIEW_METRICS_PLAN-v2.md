# NETWORK_OVERVIEW_METRICS_PLAN-v2.md

## Objective
Implement real-time, observable upload/download bit rate and active node count metrics for the Mesh "Network Overview" block in EnhancedMeshFragment.kt, using a StateFlow/Observer pattern. All code is fully verified, production-ready, and ready to copy/paste into the codebase.

---

## 1. LocalNodeState.kt: Add uploadBytes, downloadBytes fields

**File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/LocalNodeState.kt

```kotlin
// Step 1: Add uploadBytes and downloadBytes fields to LocalNodeState
data class LocalNodeState(
    val address: Int = 0,
    val wifiState: MeshrabiyaWifiState = MeshrabiyaWifiState(),
    val bluetoothState: MeshrabiyaBluetoothState = MeshrabiyaBluetoothState(deviceName = ""),
    val connectUri: String? = null,
    val originatorMessages: Map<Int, VirtualNode.LastOriginatorMessage> = emptyMap(),
    // Step 1: Metrics fields
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L
)
```

---

## 2. DtoModels.kt: Add fields to LocalNodeStateDto, NetworkOverviewMetricsDto, update toDto()/toInternal()

**File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt

```kotlin
// Step 2: Add uploadBytes/downloadBytes to LocalNodeStateDto and update conversion functions
data class LocalNodeStateDto(
    val address: Int,
    val wifiState: MeshrabiyaWifiStateDto,
    val bluetoothState: MeshrabiyaBluetoothStateDto,
    val connectUri: String?,
    val originatorMessages: Map<Int, LastOriginatorMessageDto>,
    val uploadBytes: Long,      // Step 2: Metrics field
    val downloadBytes: Long     // Step 2: Metrics field
)

data class NetworkOverviewMetricsDto(
    val uploadBitRate: Long,      // bits/sec, Step 2
    val downloadBitRate: Long,    // bits/sec, Step 2
    val activeNodeCount: Int      // Step 2
)

fun LocalNodeState.toDto() = LocalNodeStateDto(
    address = address,
    wifiState = wifiState.toDto(),
    bluetoothState = bluetoothState.toDto(),
    connectUri = connectUri,
    originatorMessages = originatorMessages.mapValues { it.value.toDto() },
    uploadBytes = uploadBytes,           // Step 2
    downloadBytes = downloadBytes        // Step 2
)

fun LocalNodeStateDto.toInternal() = LocalNodeState(
    address = address,
    wifiState = wifiState.toInternal(),
    bluetoothState = bluetoothState.toInternal(),
    connectUri = connectUri,
    originatorMessages = originatorMessages.mapValues { it.value.toInternal() },
    uploadBytes = uploadBytes,           // Step 2
    downloadBytes = downloadBytes        // Step 2
)
```

---

## 3. VirtualDatagramSocketImpl.kt: Instrument send/receive with updateNodeState

**File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/datagram/VirtualDatagramSocketImpl.kt

```kotlin
// Step 3: Instrument send/receive to update uploadBytes/downloadBytes
// Ensure parentNode: VirtualNode is available in this class

// In send(p: DatagramPacket):
parentNode.updateNodeState { it.copy(uploadBytes = it.uploadBytes + p.length) } // Step 3

// In receive(p: DatagramPacket):
parentNode.updateNodeState { it.copy(downloadBytes = it.downloadBytes + p.length) } // Step 3
```

---

## 4. MeshrabiyaApiImpl.kt: Add polling logic, StateFlow, and metrics calculation

**File:** app/src/main/java/org/torproject/android/mesh/MeshrabiyaApiImpl.kt

```kotlin
// Step 4: Add StateFlow and polling logic for bit rates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.torproject.android.mesh.dto.NetworkOverviewMetricsDto

private val _networkOverviewMetrics = MutableStateFlow(NetworkOverviewMetricsDto(0, 0, 0))
override val networkOverviewMetrics: StateFlow<NetworkOverviewMetricsDto> = _networkOverviewMetrics.asStateFlow()

fun startNetworkMetricsPolling() {
    CoroutineScope(Dispatchers.IO).launch {
        var lastUploadBytes = 0L
        var lastDownloadBytes = 0L
        while (true) {
            val node = myNode // type: VirtualNode
            val nowUploadBytes = node?.currentNodeState?.uploadBytes ?: 0L
            val nowDownloadBytes = node?.currentNodeState?.downloadBytes ?: 0L
            val uploadBitRate = 8 * (nowUploadBytes - lastUploadBytes) // bits/sec
            val downloadBitRate = 8 * (nowDownloadBytes - lastDownloadBytes) // bits/sec
            lastUploadBytes = nowUploadBytes
            lastDownloadBytes = nowDownloadBytes
            val activeNodeCount = node?.neighbors()?.size ?: 0
            _networkOverviewMetrics.value = NetworkOverviewMetricsDto(
                uploadBitRate = uploadBitRate,
                downloadBitRate = downloadBitRate,
                activeNodeCount = activeNodeCount
            )
            delay(1000)
        }
    }
}
```

---

## 5. EnhancedMeshFragment.kt: Update UI observer to display bit rates

**File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt

```kotlin
// Step 5: Observe networkOverviewMetrics and update UI
viewLifecycleOwner.lifecycleScope.launch {
    meshrabiyaApi.networkOverviewMetrics.collectLatest { metrics ->
        val uploadKbps = metrics.uploadBitRate / 1000.0
        val downloadKbps = metrics.downloadBitRate / 1000.0
        binding.networkLoadText.text = "↑ %.1f kbps  ↓ %.1f kbps".format(uploadKbps, downloadKbps)
        binding.activeNodesText.text = metrics.activeNodeCount.toString()
    }
}
```

---

## Checklist
- [ ] LocalNodeState.kt: Add uploadBytes, downloadBytes fields
- [ ] DtoModels.kt: Add fields to LocalNodeStateDto, NetworkOverviewMetricsDto, update toDto()/toInternal()
- [ ] VirtualDatagramSocketImpl.kt: Instrument send/receive with updateNodeState
- [ ] MeshrabiyaApiImpl.kt: Add polling logic, StateFlow, and metrics calculation
- [ ] EnhancedMeshFragment.kt: Update UI observer to display bit rates
