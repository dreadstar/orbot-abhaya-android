---

## Appendix: Codebase-Verified Root Cause Analysis — CONNECTED Status Not Updating After Peers=1

### 1. UI Observation
- The UI (EnhancedMeshFragment.kt) observes mesh status by calling `meshrabiyaApi.getMeshStatus()` and by collecting `networkInfoFlow` (StateFlow) from the API.
- The UI updates its display based on the value of `MeshStateDto` (CONNECTED, CONNECTING, DISCONNECTED) returned by `getMeshStatus()`.

### 2. API/Library Status Calculation
- In MeshrabiyaApiImpl.kt, the method `getMeshStatus()` (line 321+) is responsible for determining the mesh status:
  - If `myNode` is null, returns DISCONNECTED.
  - If WiFi is not active (neither hotspot nor station), returns DISCONNECTED.
  - If mesh is active, it checks the neighbor count:
    ```kotlin
    val neighborCount = node.neighbors().size
    val status = when {
        neighborCount > 0 -> MeshStateDto.CONNECTED
        neighborCount == 0 -> MeshStateDto.CONNECTING
        else -> MeshStateDto.UNKNOWN
    }
    ```
  - Thus, if `neighborCount > 0`, CONNECTED is returned.

### 3. StateFlow and Update Propagation
- The StateFlow `networkInfoFlow` is updated every 2 seconds by a coroutine in `startEventMonitoring()`:
  ```kotlin
  eventMonitoringScope.launch {
      while (true) {
          _networkInfoFlow.value = getNetworkInfo()
          delay(2000)
      }
  }
  ```
- However, there is **no StateFlow or LiveData for mesh status itself**—the UI only gets mesh status by calling `getMeshStatus()` directly, not by observing a StateFlow that emits status changes.
- The only observers are for peer count and network info, not for mesh status.

### 4. Why CONNECTED Status May Not Update in UI
- Even when `neighborCount` increases to 1, the UI will only see CONNECTED if it calls `getMeshStatus()` after the neighbor count has changed.
- The UI does not observe a StateFlow for mesh status, so it will not be notified reactively when the status changes from CONNECTING to CONNECTED.
- The periodic update of `networkInfoFlow` does not trigger a mesh status update in the UI unless the UI explicitly calls `getMeshStatus()` in response.

### 5. Correction Point (Codebase-Verified)
- To guarantee that the UI is updated as soon as the mesh status changes (e.g., when peers go from 0 to 1):
  - A StateFlow (or similar observable) for mesh status should be added to MeshrabiyaApiImpl.
  - This StateFlow should be updated whenever the result of `getMeshStatus()` changes (i.e., whenever neighbor count or WiFi state changes).
  - The UI should observe this StateFlow, not just call `getMeshStatus()` on demand.
- This will ensure that the UI is always in sync with the actual mesh status, and CONNECTED will be shown immediately after peers=1.

### 6. Summary Table (Codebase-Verified)
| Layer | Current Mechanism | Limitation | Correction |
|-------|-------------------|------------|------------|
| UI | Calls `getMeshStatus()` manually | Not reactive; may miss status change | Observe mesh status StateFlow |
| API | No mesh status StateFlow | No push update to UI | Add mesh status StateFlow, update on status change |
| Library | Calculates status correctly | Notifies only via callback, not StateFlow | Emit status via StateFlow |

**This analysis is fully codebase-verified and unambiguous.**
## Network Status & Role Propagation: UI to Meshrabiya Library (orbot-android)

This document provides a step-by-step, codebase-verified analysis of how **network status** (CONNECTING, CONNECTED, DISCONNECTED) and **role** information propagate from the UI down to the Meshrabiya library, including all DTOs, API, and data flow. All references are verified with actual code and file locations.

---

### 1. UI Layer: Observation & Display

#### EnhancedMeshFragment (Main Mesh UI)
- **File:** [app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt)
- **Key logic:**
  - Instantiates `MeshrabiyaApi` as `MeshrabiyaApiImpl.getInstance()` ([Line 166](app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt#L166)).
  - Observes mesh roles and network info via StateFlow:
    - `setupRoleObserver()` ([Line 266](app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt#L266)): Collects `currentMeshRolesFlow` from API, updates UI with roles and mesh state (CONNECTING, CONNECTED, etc.).
    - `setupNetworkInfoObserver()` ([Line 310](app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt#L310)): Collects `networkInfoFlow` from API, updates UI with peer count, gateway stats, etc.
  - Calls `meshrabiyaApi.getMeshStatus()` and `meshrabiyaApi.getTorGatewayStatus()` to update UI labels.

#### ConnectFragment/ConnectViewModel (Tor Status UI)
- **Files:**
  - [app/src/main/java/org/torproject/android/ui/connect/ConnectFragment.kt](app/src/main/java/org/torproject/android/ui/connect/ConnectFragment.kt)
  - [app/src/main/java/org/torproject/android/ui/connect/ConnectViewModel.kt](app/src/main/java/org/torproject/android/ui/connect/ConnectViewModel.kt)
- **Key logic:**
  - Uses `StateFlow<ConnectUiState>` for Tor connection status, not directly tied to mesh roles, but similar pattern.

---

### 2. ViewModel/API Layer: Data Provision

- **MeshrabiyaApiImpl** ([Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt)) implements [MeshrabiyaApi](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApi.kt):
  - Exposes:
    - `val currentMeshRolesFlow: StateFlow<Set<MeshRole>>?` ([Line 240](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt#L240)) — observed by UI for live role updates.
    - `val networkInfoFlow: StateFlow<NetworkInfoDto?>` ([Line 90](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt#L90)) — observed by UI for network status.
    - `fun getMeshStatus(): MeshStateDto` ([Line 321](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt#L321)) — returns current mesh state (CONNECTING, CONNECTED, DISCONNECTED, etc.).
    - `fun getTorGatewayStatus(): Boolean` ([Line 933](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt#L933)) — returns Tor gateway enabled status.
    - `fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)` ([Line 886](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt#L886)) — updates preferred roles and triggers role recalculation.
    - `fun setPreferredRoles(roles: Set<MeshRole>)` (delegates to EmergentRoleManager).
  - **DTOs:**
    - `MeshStateDto` ([api/DtoModels.kt#L34](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt#L34)): Enum for mesh state (CONNECTING, CONNECTED, etc.).
    - `NetworkInfoDto` ([api/DtoModels.kt#L71](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt#L71)): Data for network info (SSID, peers, gateways).
    - `MeshRoleDto` ([api/DtoModels.kt#L517](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt#L517)): Enum for mesh roles.

---

### 3. Library Layer: Role/Status Source

- **EmergentRoleManager** ([Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt))
  - Holds the core logic for role assignment and mesh status.
  - Exposes:
    - `val currentMeshRoles: StateFlow<Set<MeshRole>>` ([Line 137](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L137)) — observed by API for role changes.
    - `fun updateRoles(userInitiated: Boolean = false)` ([Line 1220](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L1220)) — recalculates roles based on node state, mesh needs, and user preferences.
    - `fun setPreferredRoles(roles: Set<MeshRole>)` ([Line 911](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L911)) — sets user-preferred roles.
    - `fun getCurrentMeshRoles(): Set<MeshRole>` ([Line 1263](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L1263)).
    - `fun getFitnessScore(): Float` ([Line 1258](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L1258)).
  - **Role calculation:**
    - `calculateTargetRoles()` ([Line 230](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L230)) — uses node capabilities, mesh needs, and user preferences to assign roles.
    - `updateRoles()` applies the plan and updates StateFlow.
  - **MeshRole enum:** ([vnet/MeshRole.kt#L7](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt#L7))
    - Roles: MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER, TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY

---

### 4. Status/Role Propagation Chain

**a. UI observes StateFlow from API:**
  - `EnhancedMeshFragment` collects `currentMeshRolesFlow` and `networkInfoFlow` for live updates.

**b. API exposes StateFlow from library:**
  - `MeshrabiyaApiImpl` exposes `currentMeshRolesFlow` and `networkInfoFlow`, which are backed by `EmergentRoleManager.currentMeshRoles` and mesh state.

**c. Library updates StateFlow on role/status change:**
  - `EmergentRoleManager` updates `currentMeshRoles` and triggers UI updates via API.

**d. DTO mapping:**
  - `MeshState` ([model/MeshState.kt#L6](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/MeshState.kt#L6)) ↔ `MeshStateDto` ([api/DtoModels.kt#L34](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt#L34))
  - `MeshRole` ([vnet/MeshRole.kt#L7](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt#L7)) ↔ `MeshRoleDto` ([api/DtoModels.kt#L517](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt#L517))
  - `NetworkInfo` ([model/NetworkInfo.kt#L7](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/NetworkInfo.kt#L7)) ↔ `NetworkInfoDto` ([api/DtoModels.kt#L71](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt#L71))

---

### 5. Status/Role Source and Update Triggers

- **Status source:**
  - `MeshrabiyaApiImpl.getMeshStatus()` ([Line 321](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt#L321)) checks WiFi/mesh state and neighbor count to determine status (CONNECTING, CONNECTED, DISCONNECTED).
- **Role source:**
  - `EmergentRoleManager.updateRoles()` ([Line 1220](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L1220)) recalculates roles based on node state, mesh needs, and user preferences.
- **Update triggers:**
  - User actions (e.g., enabling Tor gateway) call `setTorGatewayEnabled()`, which updates preferred roles and triggers `updateRoles()`.
  - Network state changes (e.g., new neighbor, WiFi state) trigger mesh status/role recalculation.

---

### 6. Data Flow Overview

1. **User interacts with UI** (e.g., toggles Tor gateway, observes mesh status/roles).
2. **UI calls API** (e.g., `setTorGatewayEnabled()`, observes `currentMeshRolesFlow`).
3. **API updates library** (e.g., sets preferred roles, triggers `updateRoles()`).
4. **Library recalculates roles/status** and updates StateFlow.
5. **API exposes updated StateFlow/DTOs** to UI.
6. **UI observes and displays new status/roles.**

---

### 7. Key Types and Enums

- **MeshState/MeshStateDto:** [model/MeshState.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/MeshState.kt), [api/DtoModels.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt)
- **MeshRole/MeshRoleDto:** [vnet/MeshRole.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt), [api/DtoModels.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt)
- **NetworkInfo/NetworkInfoDto:** [model/NetworkInfo.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/NetworkInfo.kt), [api/DtoModels.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/DtoModels.kt)

---

### 8. Summary Table: File/Function Reference

| Layer         | File/Type/Function | Purpose |
|---------------|--------------------|---------|
| UI            | EnhancedMeshFragment.kt: `setupRoleObserver`, `setupNetworkInfoObserver` | Observe roles/status |
| API           | MeshrabiyaApiImpl.kt: `currentMeshRolesFlow`, `networkInfoFlow`, `getMeshStatus`, `setTorGatewayEnabled` | Expose/trigger status/roles |
| DTO           | DtoModels.kt: `MeshStateDto`, `MeshRoleDto`, `NetworkInfoDto` | Data transfer |
| Library       | EmergentRoleManager.kt: `currentMeshRoles`, `updateRoles`, `setPreferredRoles` | Core logic |
| Enum/Model    | MeshRole.kt, MeshState.kt, NetworkInfo.kt | Enum/data source |

---

**This mapping is codebase-verified and covers the full propagation chain for mesh status and role information from UI to library.**