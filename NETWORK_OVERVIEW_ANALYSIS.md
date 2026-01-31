## Network Overview Card: Codebase-Verified Implementation Trace (2026-01-29)

### 1. Layout Definition

- **File:** app/src/main/res/layout/fragment_mesh_enhanced.xml
  - **Lines 324–419:**
    - `<com.google.android.material.card.MaterialCardView android:id="@+id/networkOverviewCard">` defines the Network Overview card.
    - Contains three key TextViews:
      - `@+id/text_active_node_count` ("Active Nodes")
      - `@+id/text_upload_bitrate` ("Upload Rate")
      - `@+id/text_download_bitrate` ("Download Rate")
    - All are direct children of the card's layout.

### 2. View Binding

- **File:** app/src/main/java/org/torproject/android/ui/mesh/MeshUIBindings.kt
  - **Object:** `MeshUIBindings`
    - **Properties:**
      - `lateinit var networkOverviewCard: MaterialCardView` (line 59)
      - `lateinit var textActiveNodeCount: TextView` (line 20)
      - `lateinit var textUploadBitrate: TextView` (line 18)
      - `lateinit var textDownloadBitrate: TextView` (line 19)
    - **Function:** `bindImmediateViews(view: View)` (line 118)
      - Binds the above properties using `findViewById` to the corresponding IDs in the layout.

### 3. Fragment Instantiation and Layout Inflation

- **File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
  - **Function:** `onCreateView` (line 151)
    - Inflates `R.layout.fragment_mesh_enhanced` and calls `MeshUIBindings.bindImmediateViews(view)`.
  - **Function:** `onViewCreated` (line 160)
    - Sets up observers and listeners.
    - Launches a coroutine to observe `networkOverviewMetricsFlow` and update the UI.

### 4. UI Update Logic

- **File:** app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt
  - **Function:** `updateNetworkOverviewUI(metrics: com.ustadmobile.meshrabiya.api.model.NetworkOverviewMetricsDto)` (line 286)
    - Sets:
      - `MeshUIBindings.textUploadBitrate.text = "${metrics.uploadBps} Bps"`
      - `MeshUIBindings.textDownloadBitrate.text = "${metrics.downloadBps} Bps"`
      - `MeshUIBindings.textActiveNodeCount.text = "${metrics.activeNodeCount} nodes"`
  - **Coroutine:** (line 206)
    - `viewLifecycleOwner.lifecycleScope.launch { ... networkOverviewMetricsFlow.collect { metrics -> updateNetworkOverviewUI(metrics) } }`

### 5. Data Source: StateFlow and DTO

- **File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt
  - **Property:** `override val networkOverviewMetricsFlow: StateFlow<NetworkOverviewMetricsDto>` (line 114)
    - Backed by `_networkOverviewMetricsFlow: MutableStateFlow<NetworkOverviewMetricsDto>` (line 113)
  - **Coroutine:** (line 240)
    - Polls every second, computes upload/download rates and active node count, and updates `_networkOverviewMetricsFlow.value`.
    - Calculation:
      - `uploadBps = uploadNow - lastUploadBytes`
      - `downloadBps = downloadNow - lastDownloadBytes`
      - `activeNodeCount = node.neighbors().size + 1`
    - Emits `NetworkOverviewMetricsDto(uploadBps, downloadBps, activeNodeCount)`.

### 6. DTO Definition

- **File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt
  - **Data class:** `NetworkOverviewMetricsDto` (line 39)
    - Fields:
      - `uploadBps: Long`
      - `downloadBps: Long`
      - `activeNodeCount: Int`

### 7. Underlying Data: Node State

- **File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt
  - **Property:** `myNode: AndroidVirtualNode?` (MeshrabiyaApiImpl)
    - Provides `currentNodeState.uploadBytes` and `currentNodeState.downloadBytes`.
    - `neighbors().size` for active node count.
- **File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt
  - **Property:** `currentNodeState: LocalNodeState`
    - Fields: `uploadBytes: Long`, `downloadBytes: Long` (from LocalNodeState)
    - Method: `neighbors()` returns active neighbor nodes.
- **File:** Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/LocalNodeState.kt
  - **Data class:** `LocalNodeState`
    - Fields: `uploadBytes: Long`, `downloadBytes: Long`

### 8. Data Propagation Path (Stepwise)

1. **AndroidVirtualNode** updates `LocalNodeState` with upload/download bytes.
2. **MeshrabiyaApiImpl** polls these values every second, computes deltas, and emits a new `NetworkOverviewMetricsDto` via `_networkOverviewMetricsFlow`.
3. **EnhancedMeshFragment** observes `networkOverviewMetricsFlow` and calls `updateNetworkOverviewUI`.
4. **updateNetworkOverviewUI** sets the text of the three TextViews in the Network Overview card.

### 9. Verification Steps

- All file paths, class names, method signatures, and property names have been verified by literal disk read.
- No overlays, duplicate layouts, or alternate fragments exist for the Network Overview card.
- All data flows are unambiguous and duplication-free.
- All integration points (StateFlow, DTO, UI binding) are present and correct.

### 10. Ambiguities or Duplications

- **None found.** All references are unique and codebase-verified.

---
**Generated: 2026-01-29, by literal codebase trace.**