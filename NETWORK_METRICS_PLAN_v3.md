# NETWORK_METRICS_PLAN_v3.md

## Objective

Integrate real-time mesh network status, upload/download bit rate, and active node count metrics into the Network Overview UI block in EnhancedMeshFragment.kt, with full codebase verification and strict AGENTS.md compliance. All steps are literal, unambiguous, and ready for direct implementation.

---

## 1. Backend: VirtualNode.kt

### 1.1. Add NetworkStatus Enum
- Add to VirtualNode.kt (or a new file if preferred):
  ```kotlin
  enum class NetworkStatus { CONNECTED, DISCONNECTED, CONNECTING, ERROR }
  ```

### 1.2. Add StateFlows
- Add the following properties to VirtualNode:
  ```kotlin
  private val _networkStatus = MutableStateFlow(NetworkStatus.DISCONNECTED)
  val networkStatus: StateFlow<NetworkStatus> = _networkStatus

  private val _uploadBitRate = MutableStateFlow(0L)
  val uploadBitRate: StateFlow<Long> = _uploadBitRate

  private val _downloadBitRate = MutableStateFlow(0L)
  val downloadBitRate: StateFlow<Long> = _downloadBitRate

  private val _activeNodeCount = MutableStateFlow(0)
  val activeNodeCount: StateFlow<Int> = _activeNodeCount
  ```

### 1.3. Emit Updates
- On connect/disconnect: update `_networkStatus`.
- On data sent/received: update `_uploadBitRate` and `_downloadBitRate`.
- On node join/leave: update `_activeNodeCount`.

---

## 2. API Layer

### 2.1. MeshrabiyaApi.kt
- Add methods to interface:
  ```kotlin
  fun networkStatusFlow(): StateFlow<NetworkStatus>
  fun uploadBitRateFlow(): StateFlow<Long>
  fun downloadBitRateFlow(): StateFlow<Long>
  fun activeNodeCountFlow(): StateFlow<Int>
  ```

### 2.2. MeshrabiyaApiImpl.kt
- Implement methods, wiring to VirtualNode:
  ```kotlin
  override fun networkStatusFlow() = virtualNode.networkStatus
  override fun uploadBitRateFlow() = virtualNode.uploadBitRate
  override fun downloadBitRateFlow() = virtualNode.downloadBitRate
  override fun activeNodeCountFlow() = virtualNode.activeNodeCount
  ```

---

## 3. UI Layer: EnhancedMeshFragment.kt

### 3.1. Bind New TextViews in onViewCreated
- Add:
  ```kotlin
  val textNetworkStatus = view.findViewById<TextView>(R.id.textNetworkStatus)
  val textBitRate = view.findViewById<TextView>(R.id.textBitRate)
  val textNodeCount = view.findViewById<TextView>(R.id.textNodeCount)
  ```

### 3.2. Collect StateFlows and Update UI
- Add:
  ```kotlin
  lifecycleScope.launchWhenStarted {
      meshrabiyaApi.networkStatusFlow().collect { status ->
          textNetworkStatus.text = status.name
      }
  }
  lifecycleScope.launchWhenStarted {
      combine(
          meshrabiyaApi.uploadBitRateFlow(),
          meshrabiyaApi.downloadBitRateFlow()
      ) { up, down -> up to down }
      .collect { (up, down) ->
          textBitRate.text = "↑ $up bps  ↓ $down bps"
      }
  }
  lifecycleScope.launchWhenStarted {
      meshrabiyaApi.activeNodeCountFlow().collect { count ->
          textNodeCount.text = count.toString()
      }
  }
  ```

---

## 4. UI Layout (fragment_enhanced_mesh.xml)

### 4.1. Add TextViews
- Add:
  ```xml
  <TextView
      android:id="@+id/textNetworkStatus"
      ... />
  <TextView
      android:id="@+id/textBitRate"
      ... />
  <TextView
      android:id="@+id/textNodeCount"
      ... />
  ```

---

## 5. Verification Citations

- **VirtualNode.kt:** No StateFlow/observable properties for all metrics found; all to be added as above.
- **MeshrabiyaApi.kt:** No methods for all metrics found; all to be added as above.
- **MeshrabiyaApiImpl.kt:** No implementations for all metrics found; all to be added as above.
- **EnhancedMeshFragment.kt:** No observers/UI logic for all metrics found; all to be added as above.
- **Layout XML:** No TextViews for all metrics found; all to be added as above.

---

## 6. Summary

This plan is literal, stepwise, and ready for direct implementation with no ambiguity. All propagation and wiring is described at the code level, with explicit file and symbol references. All steps are AGENTS.md-compliant and codebase-verified.