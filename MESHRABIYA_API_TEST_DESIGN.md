# Meshrabiya API Test Design: Method Categorization & Test Scenarios

**Date:** December 6, 2025  
**Source:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

---

## Pattern A: Callback-Based Async (uses `Result<T>` callback)

These methods accept a callback parameter `(Result<T>) -> Unit` that is invoked when the async operation completes.

### A1. Mesh Network Controls

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `startMesh(callback: (Result<Unit>) -> Unit)` | `Unit` | Inside `runBlocking` after `setWifiHotspotEnabled(true)` | Wraps `myNode?.setWifiHotspotEnabled()` in try-catch |
| `stopMesh(callback: (Result<Unit>) -> Unit)` | `Unit` | Inside `runBlocking` after `setWifiHotspotEnabled(false)` | Wraps `myNode?.setWifiHotspotEnabled()` in try-catch |

**Callback Contract:**
- `Result.success(Unit)` - WiFi hotspot state changed successfully
- `Result.failure(e: Exception)` - Operation threw exception

---

### A2. Gateway Controls

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately after `setPreferredRoles()` | Modifies `MeshRole.TOR_GATEWAY` in role set |
| `setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately after `setPreferredRoles()` | Modifies `MeshRole.CLEARNET_GATEWAY` in role set |
| `setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit)` | `Unit` | Inside `runBlocking` after DataStore write | Persists to DataStore, updates `currentGatewayPreference` |

**Callback Contract:**
- `Result.success(Unit)` - Role/preference updated successfully
- `Result.failure(IllegalStateException)` - `myNode?.emergentRoleManager == null` or `appContext == null`
- `Result.failure(Exception)` - Operation threw exception

---

### A3. Storage Participation

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)` | `Unit` | After `configureStorageParticipation()` | Creates `StorageParticipationConfig` and applies |
| `setStorageAllocation(deviceId: String, allocatedMB: Long, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately (no-op) | **NOT IMPLEMENTED** - returns success without action |

**Callback Contract:**
- `Result.success(Unit)` - Storage participation configured
- `Result.failure(IllegalStateException)` - `myNode?.distributedStorageManager == null`
- `Result.failure(Exception)` - Operation threw exception

---

### A4. Drop Folder Management

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately (no-op) | **NOT IMPLEMENTED** - returns success without action |

---

### A5. File Operations

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `storeFile(file: File, callback: (Result<String>) -> Unit)` | `String` (fileId) | Never invoked | **NOT IMPLEMENTED** - returns `Result.failure(NotImplementedError)` |
| `retrieveFile(fileId: String, callback: (Result<File>) -> Unit)` | `File` | Inside `CoroutineScope(IO).launch` after file write | Async coroutine retrieves from storage, writes to disk, invokes callback |
| `streamFile(fileId: String, callback: (Result<Unit>) -> Unit)` | `Unit` | Never invoked | **NOT IMPLEMENTED** - returns `Result.failure(NotImplementedError)` |
| `deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately after validation | **PARTIAL** - validates existence, returns success (no actual deletion) |

**Callback Contract:**
- `retrieveFile`:
  - `Result.success(File)` - File retrieved and written to disk
  - `Result.failure(IllegalArgumentException)` - `fileId.isBlank()`
  - `Result.failure(IllegalStateException)` - Storage manager or context not initialized
  - `Result.failure(FileNotFoundException)` - File metadata not found or file data unavailable
  - `Result.failure(Exception)` - Operation threw exception
- `deleteFile`:
  - `Result.success(Unit)` - File metadata exists (would be deleted)
  - `Result.failure(IllegalArgumentException)` - `fileId.isBlank()`
  - `Result.failure(IllegalStateException)` - Storage manager not initialized
  - `Result.failure(FileNotFoundException)` - File metadata not found

---

### A6. Distributed Service Layer

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately (no-op) | **NOT IMPLEMENTED** - returns success without action |

---

### A7. Compute/Task Operations

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `startTask(taskId: String, callback: (Result<Unit>) -> Unit)` | `Unit` | Never invoked | **NOT IMPLEMENTED** - returns `Result.failure(NotImplementedError)` |
| `cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)` | `Unit` | Never invoked | **NOT IMPLEMENTED** - returns `Result.failure(NotImplementedError)` |

---

### A8. Settings and State

| Method | Success Type | Callback Invocation Timing | Implementation Notes |
|--------|--------------|---------------------------|---------------------|
| `setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit)` | `Unit` | Immediately (no-op) | **NOT IMPLEMENTED** - returns success without action |

---

## Pattern B: Direct Return (synchronous)

These methods return values immediately without async operations.

### B1. Mesh State & Network Info

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getNodeRole(): Byte` | `Byte` | `emergentRoleManager?.getCurrentMeshRoles()?.firstOrNull()?.ordinal?.toByte()` | Returns `0` if null |
| `getFitnessScore(): Int` | `Int` | Not implemented | Always returns `0` |
| `getConnectionUri(): String` | `String` | `myNode?.currentNodeState?.connectUri` | Returns `""` if null |
| `getLocalNodeState(): LocalNodeState` | `LocalNodeState` | `myNode?.currentNodeState` | **THROWS** `IllegalStateException` if null |
| `getNeighbors(): List<Int>` | `List<Int>` | `myNode?.neighbors()?.map { it.first }` | Returns `emptyList()` if null |
| `getHopCountToNode(nodeId: Int): Int?` | `Int?` | `myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeId)?.hopCount?.toInt()` | Returns `null` if not found |
| `getConnectLink(): String?` | `String?` | `myNode?.currentNodeState?.connectUri` | Returns `null` if null |
| `getConnectLinkFlow(): Flow<String?>` | `Flow<String?>` | `myNode?.state?.map { it.connectUri }` | Returns `flowOf(null)` if null |

---

### B2. Mesh Network Controls

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getMeshStatus(): MeshState` | `MeshState` | Based on `myNode?.neighbors()?.size` | Returns `DISCONNECTED` if `myNode == null` |
| `getPeerCount(): Int` | `Int` | `myNode?.neighbors()?.size` | Returns `0` if null |
| `getNetworkInfo(): NetworkInfo` | `NetworkInfo` | `myNode?.originatingMessageManager.getTopologyMapInfo()` | Returns empty `NetworkInfo()` if `myNode == null` |
| `getNodeInfo(nodeId: String): NodeInfo` | `NodeInfo` | `myNode?.originatingMessageManager.getTopologyMapInfo()` | Returns empty `NodeInfo()` if `myNode == null` or nodeId invalid |

---

### B3. Gateway Controls

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getTorGatewayStatus(): Boolean` | `Boolean` | `emergentRoleManager?.getCurrentMeshRoles()?.contains(MeshRole.TOR_GATEWAY)` | Returns `false` if null |
| `getInternetGatewayStatus(): Boolean` | `Boolean` | `emergentRoleManager?.getCurrentMeshRoles()?.contains(MeshRole.CLEARNET_GATEWAY)` | Returns `false` if null |
| `getGatewayStatus(): Boolean` | `Boolean` | Checks if roles contain `TOR_GATEWAY` or `CLEARNET_GATEWAY` or `I2P_GATEWAY` | Returns `false` if null |
| `getGatewayPreference(): GatewayPreference` | `GatewayPreference` | `currentGatewayPreference` volatile field | Always returns valid enum value |
| `isTorActive(): Boolean` | `Boolean` | `isTorRunning` volatile field | Always returns valid boolean |

---

### B4. Proxy Controls

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `setProxy(host: String, port: Int)` | `Unit` | Calls `myNode?.setProxy(host, port)` | Silent no-op if `myNode == null` |
| `setProxyActive(active: Boolean)` | `Unit` | Calls `myNode?.setProxyActive(active)` | Silent no-op if `myNode == null` |

---

### B5. Storage Participation

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getStorageParticipationStatus(): Boolean` | `Boolean` | `distributedStorageManager?.participationEnabled?.value` | Returns `false` if null |
| `getAvailableStorageDevices(): List<StorageDevice>` | `List<StorageDevice>` | Not implemented | Always returns `emptyList()` |
| `getStorageAllocations(): List<StorageAllocation>` | `List<StorageAllocation>` | Not implemented | Always returns `emptyList()` |
| `enableDistributedStorage()` | `Unit` | Calls `storageManager?.registerWithEcosystemListener()` | Silent no-op if managers null |
| `disableDistributedStorage()` | `Unit` | Calls `storageManager?.unregisterFromEcosystemListener()` | Silent no-op if managers null |
| `isComputeLayerParticipating(): Boolean` | `Boolean` | Not implemented | Always returns `false` |

---

### B6. Drop Folder Management

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getDropFolder(): File?` | `File?` | Not implemented | Always returns `null` |
| `getDropFolderFiles(): List<File>` | `List<File>` | Not implemented | Always returns `emptyList()` |

---

### B7. File Operations

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getAllMeshFiles(): List<MeshFile>` | `List<MeshFile>` | `distributedStorageManager?.fileMetadataStore` | Returns `emptyList()` if null or on exception |

---

### B8. Distributed Service Layer

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getAvailableServices(): List<String>` | `List<String>` | Not implemented | Always returns `emptyList()` |
| `getServiceParticipationStatus(serviceId: String): Boolean` | `Boolean` | Not implemented | Always returns `false` |

---

### B9. Settings and State

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getSettings(): Map<String, Any>` | `Map<String, Any>` | Hardcoded map with empty values | Always returns valid map |

---

### B10. Service Bundle & Gateway Controls

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `getMeshTrafficRouterStatus(): String` | `String` | Not implemented | Always returns `"Inactive"` |

---

### B11. Context Management

| Method | Return Type | Data Source | Null Safety |
|--------|-------------|-------------|-------------|
| `provideAppContext(context: Context)` | `Unit` | Stores `context.applicationContext` in `appContext` | N/A |
| `getAppContext(): Context?` | `Context?` | Returns `appContext` volatile field | Returns `null` if not set |

---

## Pattern C: ApiResult Return

These methods return `ApiResult` (sealed class with `Success` or `Failure(Throwable)`).

| Method | Success Condition | Failure Conditions | Side Effects |
|--------|------------------|-------------------|--------------|
| `addTask(requestParams: Map<String, Any>): ApiResult` | Task submitted to compute client | - Missing `taskType` parameter<br>- Invalid priority (< 0 or > 10)<br>- `myNode?.obtainDistributedComputeClient() == null`<br>- Exception during submission | Launches coroutine to call `computeClient.processTaskRequest()` |

**Method Details:**

```kotlin
fun addTask(requestParams: Map<String, Any>): ApiResult
```

**Required Parameters:**
- `taskType` (String): Execution engine - `"python"`, `"jvm"`, `"javascript"`, `"ml-native"`

**Optional Parameters:**
- `taskId` (String): Unique task identifier (auto-generated if not provided)
- `priority` (Int): Task priority 0-10 (default: 5)

**Return Values:**
- `ApiResult.Success` - Task request created and submitted asynchronously
- `ApiResult.Failure(IllegalArgumentException)` - Missing `taskType` or invalid priority
- `ApiResult.Failure(IllegalStateException)` - Compute client not initialized
- `ApiResult.Failure(Exception)` - Exception during task creation

**Implementation Notes:**
- Creates `LocalComputeTaskRequest` with generated `requestId`
- Submits via `CoroutineScope(Dispatchers.IO).launch { computeClient.processTaskRequest(request) }`
- Returns immediately (Success/Failure based on validation, not task completion)
- Task status updates delivered via `setOnTaskStatusUpdate()` callback

---

## Pattern D: Event Registration (setOnXYZ methods)

These methods register callback handlers that are invoked when specific events occur.

### D1. File Operation Events

| Method | Handler Signature | Invocation Trigger | Implementation |
|--------|------------------|-------------------|----------------|
| `setOnFileRetrieved(handler)` | `(fileId: String, file: File) -> Unit` | After `retrieveFile()` writes file to disk | Invoked in `retrieveFile()` coroutine before success callback |
| `setOnFileStored(handler)` | `(fileId: String, file: File) -> Unit` | **NEVER** - `storeFile()` not implemented | Would be invoked after file stored to mesh |
| `setOnPermissionUpdated(handler)` | `(fileId: String, success: Boolean) -> Unit` | **NEVER** - No permission update implementation | Would be invoked after file permission change |
| `setOnFileShared(handler)` | `(fileId: String, recipientId: String) -> Unit` | **NEVER** - No file sharing implementation | Would be invoked after file shared with peer |
| `setOnFileAddedToDropFolder(handler)` | `(fileId: String, file: File) -> Unit` | **NEVER** - Drop folder not implemented | Would be invoked when file added to drop folder |

---

### D2. Mesh State Events

| Method | Handler Signature | Invocation Trigger | Implementation |
|--------|------------------|-------------------|----------------|
| `setOnMeshStateChanged(handler)` | `(newState: MeshState) -> Unit` | When `getMeshStatus()` returns different value from previous check | Polled every 1000ms in `stateMonitorJob` coroutine (launched in `startEventMonitoring()`) |
| `setOnPeerCountChanged(handler)` | `(newCount: Int) -> Unit` | When `getPeerCount()` returns different value from previous check | Polled every 1000ms in `peerMonitorJob` coroutine (launched in `startEventMonitoring()`) |

---

### D3. Operation Events

| Method | Handler Signature | Invocation Trigger | Implementation |
|--------|------------------|-------------------|----------------|
| `setOnOperationFailed(handler)` | `(operation: String, error: Throwable) -> Unit` | On exception in `retrieveFile()`, `getAllMeshFiles()`, or `addTask()` | Invoked before failure callback in try-catch blocks |

---

### D4. Task Events

| Method | Handler Signature | Invocation Trigger | Implementation |
|--------|------------------|-------------------|----------------|
| `setOnTaskStatusUpdate(handler)` | `(taskId: String, status: String) -> Unit` | When `triggerTaskStatusUpdate()` called by `MeshEcosystemListener` | Public method `triggerTaskStatusUpdate()` invokes registered handler |

---

### D5. Network Events

| Method | Handler Signature | Invocation Trigger | Implementation |
|--------|------------------|-------------------|----------------|
| `setOnGatewayTraffic(handler)` | `(packet: VirtualPacket) -> Boolean` | **UNKNOWN** - No invocation in implementation | Would be invoked when gateway routes packet |
| `setOnGossipMessage(handler)` | `(senderId: Int, messageBytes: ByteArray) -> Unit` | **NEVER** - Commented implementation | Would call `myNode?.addGossipListener(handler)` |

---

## Pattern E: Lifecycle Methods

| Method | Purpose | Side Effects |
|--------|---------|--------------|
| `initMesh(context: Context)` | Initialize all mesh components | Creates `AndroidVirtualNode`, managers, loads gateway preference, registers Tor status monitor, starts event monitoring coroutines |

**Implementation Details:**
1. Creates `AndroidVirtualNode` with DataStore
2. Assigns `emergentRoleManager`, `distributedStorageManager` from node
3. Loads `currentGatewayPreference` from DataStore (blocking)
4. Registers `TorStatusMonitor` BroadcastReceiver
5. Calls `startEventMonitoring()` to launch polling coroutines

---

# Test Requirements Matrix

## Unit Test Requirements by Pattern

### Pattern A: Callback-Based Async

**What to Mock:**
- `myNode.setWifiHotspotEnabled()`
- `myNode.emergentRoleManager.getCurrentMeshRoles()`
- `myNode.emergentRoleManager.setPreferredRoles()`
- `myNode.distributedStorageManager.configureStorageParticipation()`
- `myNode.distributedStorageManager.getFileMetadata()`
- `myNode.distributedStorageManager.retrieveFile()`
- `appContext.dataStore`
- `context.getExternalFilesDir()`

**How to Verify Success:**
1. Create a callback that captures the `Result<T>` parameter
2. Invoke the method under test
3. Assert `capturedResult.isSuccess == true`
4. Assert `capturedResult.getOrNull()` equals expected value

**Error Scenarios:**

| Scenario | Expected Callback | Test Setup |
|----------|------------------|------------|
| `myNode == null` | `Result.failure(IllegalStateException)` | Set `myNode = null` before call |
| `emergentRoleManager == null` | `Result.failure(IllegalStateException)` | Mock `myNode.emergentRoleManager` to return null |
| `distributedStorageManager == null` | `Result.failure(IllegalStateException)` | Mock `myNode.distributedStorageManager` to return null |
| `appContext == null` | `Result.failure(IllegalStateException)` | Set `appContext = null` before call |
| Invalid parameters | `Result.failure(IllegalArgumentException)` | Pass blank `fileId`, invalid priority, etc. |
| Underlying method throws | `Result.failure(Exception)` | Mock to throw exception |

**Example Test:**

```kotlin
@Test
fun `setTorGatewayEnabled success`() = runTest {
    // Arrange
    val mockRoleManager = mockk<EmergentRoleManager>()
    every { mockRoleManager.getCurrentMeshRoles() } returns mutableSetOf()
    every { mockRoleManager.setPreferredRoles(any()) } just Runs
    every { myNode.emergentRoleManager } returns mockRoleManager
    
    var capturedResult: Result<Unit>? = null
    
    // Act
    api.setTorGatewayEnabled(true) { result ->
        capturedResult = result
    }
    
    // Assert
    assertNotNull(capturedResult)
    assertTrue(capturedResult!!.isSuccess)
    verify { mockRoleManager.setPreferredRoles(match { it.contains(MeshRole.TOR_GATEWAY) }) }
}

@Test
fun `setTorGatewayEnabled when roleManager null`() = runTest {
    // Arrange
    every { myNode.emergentRoleManager } returns null
    var capturedResult: Result<Unit>? = null
    
    // Act
    api.setTorGatewayEnabled(true) { result ->
        capturedResult = result
    }
    
    // Assert
    assertNotNull(capturedResult)
    assertTrue(capturedResult!!.isFailure)
    assertTrue(capturedResult!!.exceptionOrNull() is IllegalStateException)
}
```

---

### Pattern B: Direct Return

**What to Mock:**
- `myNode.currentNodeState`
- `myNode.neighbors()`
- `myNode.originatingMessageManager.getTopologyMapInfo()`
- `myNode.originatingMessageManager.findOriginatingMessageFor()`
- `myNode.emergentRoleManager.getCurrentMeshRoles()`
- `myNode.distributedStorageManager.participationEnabled`
- `myNode.distributedStorageManager.fileMetadataStore`
- `myNode.state` (Flow)

**How to Verify Success:**
1. Mock dependencies to return specific values
2. Invoke the method under test
3. Assert return value equals expected

**Error Scenarios:**

| Scenario | Expected Return | Test Setup |
|----------|----------------|------------|
| `myNode == null` | Default safe value (0, false, "", null, emptyList()) | Set `myNode = null` |
| `manager == null` | Default safe value | Mock manager property to return null |
| Invalid input | Default safe value or empty | Pass invalid nodeId, etc. |
| Exception thrown | `IllegalStateException` (only `getLocalNodeState()`) | Mock to throw exception |

**Example Test:**

```kotlin
@Test
fun `getPeerCount returns neighbor count`() {
    // Arrange
    every { myNode.neighbors() } returns listOf(
        Pair(1, mockk()),
        Pair(2, mockk()),
        Pair(3, mockk())
    )
    
    // Act
    val count = api.getPeerCount()
    
    // Assert
    assertEquals(3, count)
}

@Test
fun `getPeerCount returns 0 when myNode null`() {
    // Arrange
    api.myNode = null
    
    // Act
    val count = api.getPeerCount()
    
    // Assert
    assertEquals(0, count)
}

@Test
fun `getLocalNodeState throws when myNode null`() {
    // Arrange
    api.myNode = null
    
    // Act & Assert
    assertThrows<IllegalStateException> {
        api.getLocalNodeState()
    }
}
```

---

### Pattern C: ApiResult Return

**What to Mock:**
- `myNode.obtainDistributedComputeClient()`
- `computeClient.processTaskRequest()`

**How to Verify Success:**
1. Mock `obtainDistributedComputeClient()` to return mock client
2. Invoke `addTask()` with valid parameters
3. Assert `result is ApiResult.Success`

**Error Scenarios:**

| Scenario | Expected Return | Test Setup |
|----------|----------------|------------|
| Missing `taskType` | `ApiResult.Failure(IllegalArgumentException)` | Omit `taskType` from params |
| Invalid priority | `ApiResult.Failure(IllegalArgumentException)` | Pass `priority = 15` |
| `computeClient == null` | `ApiResult.Failure(IllegalStateException)` | Mock `obtainDistributedComputeClient()` to return null |
| Exception during creation | `ApiResult.Failure(Exception)` | Mock to throw exception |

**Example Test:**

```kotlin
@Test
fun `addTask success`() = runTest {
    // Arrange
    val mockClient = mockk<DistributedComputeClient>()
    coEvery { mockClient.processTaskRequest(any()) } just Runs
    every { myNode.obtainDistributedComputeClient() } returns mockClient
    
    val params = mapOf(
        "taskType" to "python",
        "priority" to 7
    )
    
    // Act
    val result = api.addTask(params)
    
    // Assert
    assertTrue(result is ApiResult.Success)
    coVerify { mockClient.processTaskRequest(match { 
        it.taskType == "python" && it.priority == 7 
    }) }
}

@Test
fun `addTask missing taskType`() {
    // Arrange
    val params = mapOf("priority" to 5)
    
    // Act
    val result = api.addTask(params)
    
    // Assert
    assertTrue(result is ApiResult.Failure)
    assertTrue((result as ApiResult.Failure).error is IllegalArgumentException)
}
```

---

### Pattern D: Event Registration

**What to Mock:**
- Nothing (testing callback registration and invocation)

**How to Verify Success:**
1. Register a mock handler
2. Trigger the event (call internal method or simulate condition)
3. Verify handler was invoked with expected parameters

**Error Scenarios:**
- Handler not set: Event should be silently ignored (null-safe invocation `handler?.invoke()`)

**Example Test:**

```kotlin
@Test
fun `setOnTaskStatusUpdate invokes handler`() {
    // Arrange
    var capturedTaskId: String? = null
    var capturedStatus: String? = null
    
    api.setOnTaskStatusUpdate { taskId, status ->
        capturedTaskId = taskId
        capturedStatus = status
    }
    
    // Act
    api.triggerTaskStatusUpdate("task-123", "COMPLETED")
    
    // Assert
    assertEquals("task-123", capturedTaskId)
    assertEquals("COMPLETED", capturedStatus)
}

@Test
fun `triggerTaskStatusUpdate when handler not set`() {
    // Arrange
    // (no handler registered)
    
    // Act & Assert (should not throw)
    api.triggerTaskStatusUpdate("task-123", "COMPLETED")
}
```

---

### Pattern E: Lifecycle Methods

**What to Mock:**
- `Context.dataStore`
- `AndroidVirtualNode` constructor
- `TorStatusMonitor.register()`
- `TorStatusMonitor.requestStatusUpdate()`

**How to Verify Success:**
1. Mock all dependencies
2. Call `initMesh(context)`
3. Verify all managers are assigned
4. Verify event monitoring started

**Error Scenarios:**
- Not applicable (method doesn't throw, silently initializes)

**Example Test:**

```kotlin
@Test
fun `initMesh initializes all components`() = runTest {
    // Arrange
    val mockContext = mockk<Context>(relaxed = true)
    val mockDataStore = mockk<DataStore<Preferences>>()
    every { mockContext.dataStore } returns mockDataStore
    coEvery { mockDataStore.data } returns flowOf(emptyPreferences())
    
    // Act
    api.initMesh(mockContext)
    
    // Assert
    assertNotNull(api.myNode)
    assertNotNull(api.emergentRoleManager)
    assertNotNull(api.distributedStorageManager)
    verify { torStatusMonitor.register(mockContext) }
    verify { torStatusMonitor.requestStatusUpdate(mockContext) }
}
```

---

# Integration Test Workflows

## Workflow 1: Full Gateway Lifecycle (Tor)

**Purpose:** Test end-to-end gateway role management

**Steps:**
```kotlin
@Test
fun `gateway lifecycle - enable and disable Tor gateway`() = runTest {
    // 1. Initialize mesh
    val context = ApplicationProvider.getApplicationContext<Context>()
    api.initMesh(context)
    
    // 2. Verify myNode initialized
    assertNotNull(api.myNode)
    
    // 3. Enable Tor gateway
    var setResult: Result<Unit>? = null
    api.setTorGatewayEnabled(true) { result ->
        setResult = result
    }
    
    // 4. Verify callback receives success
    delay(100) // Allow async completion
    assertNotNull(setResult)
    assertTrue(setResult!!.isSuccess)
    
    // 5. Query gateway status
    val isEnabled = api.getTorGatewayStatus()
    assertTrue(isEnabled)
    
    // 6. Disable Tor gateway
    api.setTorGatewayEnabled(false) { result ->
        setResult = result
    }
    
    // 7. Verify disabled
    delay(100)
    assertTrue(setResult!!.isSuccess)
    assertFalse(api.getTorGatewayStatus())
}
```

**Mocking Requirements:**
- `EmergentRoleManager` to track role changes
- Verify `setPreferredRoles()` called with correct sets

**Expected Outcomes:**
- After enable: `getCurrentMeshRoles()` contains `TOR_GATEWAY`
- After disable: `getCurrentMeshRoles()` does not contain `TOR_GATEWAY`

---

## Workflow 2: File Operations (Retrieve)

**Purpose:** Test file retrieval from distributed storage

**Steps:**
```kotlin
@Test
fun `file operations - retrieve existing file`() = runTest {
    // 1. Initialize mesh and storage
    val context = ApplicationProvider.getApplicationContext<Context>()
    api.initMesh(context)
    api.enableDistributedStorage()
    
    // 2. Mock file metadata
    val fileId = "test-file-123"
    val mockMetadata = FileMetadata(
        fileId = fileId,
        path = "/data/testfile.txt",
        sizeBytes = 1024,
        owner = "10.0.0.1",
        createdAt = System.currentTimeMillis()
    )
    val mockStorageManager = mockk<DistributedStorageManager>()
    every { mockStorageManager.getFileMetadata(fileId) } returns mockMetadata
    coEvery { mockStorageManager.retrieveFile(any()) } returns "file content".toByteArray()
    every { api.myNode?.distributedStorageManager } returns mockStorageManager
    
    // 3. Retrieve file
    var retrieveResult: Result<File>? = null
    var eventFileId: String? = null
    var eventFile: File? = null
    
    api.setOnFileRetrieved { fId, f ->
        eventFileId = fId
        eventFile = f
    }
    
    api.retrieveFile(fileId) { result ->
        retrieveResult = result
    }
    
    // 4. Verify callback receives file
    delay(500) // Allow coroutine completion
    assertNotNull(retrieveResult)
    assertTrue(retrieveResult!!.isSuccess)
    
    val file = retrieveResult!!.getOrNull()
    assertNotNull(file)
    assertTrue(file!!.exists())
    assertEquals("testfile.txt", file.name)
    
    // 5. Verify event callback invoked
    assertEquals(fileId, eventFileId)
    assertEquals(file, eventFile)
}
```

**Mocking Requirements:**
- `DistributedStorageManager.getFileMetadata()`
- `DistributedStorageManager.retrieveFile()`
- `Context.getExternalFilesDir()`

**Expected Outcomes:**
- File written to `MeshrabiyaFiles/received/shared/testfile.txt`
- `onFileRetrieved` callback invoked before success callback
- File content matches retrieved data

---

## Workflow 3: Task Submission and Status Monitoring

**Purpose:** Test compute task submission and status updates

**Steps:**
```kotlin
@Test
fun `task lifecycle - submit and monitor status`() = runTest {
    // 1. Initialize mesh
    val context = ApplicationProvider.getApplicationContext<Context>()
    api.initMesh(context)
    
    // 2. Mock compute client
    val mockClient = mockk<DistributedComputeClient>()
    coEvery { mockClient.processTaskRequest(any()) } just Runs
    every { api.myNode?.obtainDistributedComputeClient() } returns mockClient
    
    // 3. Register status update callback
    var statusTaskId: String? = null
    var statusUpdate: String? = null
    
    api.setOnTaskStatusUpdate { taskId, status ->
        statusTaskId = taskId
        statusUpdate = status
    }
    
    // 4. Submit task
    val taskId = "task-456"
    val params = mapOf(
        "taskId" to taskId,
        "taskType" to "python",
        "priority" to 8
    )
    
    val result = api.addTask(params)
    
    // 5. Verify submission succeeded
    assertTrue(result is ApiResult.Success)
    coVerify { mockClient.processTaskRequest(match { 
        it.taskId == taskId && it.taskType == "python" 
    }) }
    
    // 6. Simulate status update from mesh
    api.triggerTaskStatusUpdate(taskId, "RUNNING")
    
    // 7. Verify status callback invoked
    assertEquals(taskId, statusTaskId)
    assertEquals("RUNNING", statusUpdate)
    
    // 8. Simulate completion
    api.triggerTaskStatusUpdate(taskId, "COMPLETED")
    assertEquals("COMPLETED", statusUpdate)
}
```

**Mocking Requirements:**
- `DistributedComputeClient.processTaskRequest()`

**Expected Outcomes:**
- `addTask()` returns `ApiResult.Success`
- `processTaskRequest()` called with correct parameters
- Status updates delivered to callback

---

## Workflow 4: Event Callback Triggering (State Changes)

**Purpose:** Test mesh state change detection and callback invocation

**Steps:**
```kotlin
@Test
fun `event monitoring - detect mesh state changes`() = runTest {
    // 1. Initialize mesh
    val context = ApplicationProvider.getApplicationContext<Context>()
    api.initMesh(context)
    
    // 2. Mock neighbor list to simulate state changes
    val mockNeighbors = mutableListOf<Pair<Int, Any>>()
    every { api.myNode?.neighbors() } returns mockNeighbors
    
    // 3. Register state change callback
    var stateChangeCount = 0
    var latestState: MeshState? = null
    
    api.setOnMeshStateChanged { newState ->
        stateChangeCount++
        latestState = newState
    }
    
    // 4. Initial state should be DISCONNECTED
    assertEquals(MeshState.DISCONNECTED, api.getMeshStatus())
    
    // 5. Add neighbor to simulate connection
    mockNeighbors.add(Pair(1, mockk()))
    
    // 6. Wait for polling interval (1s) + buffer
    delay(1500)
    
    // 7. Verify callback invoked with CONNECTED
    assertEquals(1, stateChangeCount)
    assertEquals(MeshState.CONNECTED, latestState)
    
    // 8. Remove neighbor to simulate disconnection
    mockNeighbors.clear()
    delay(1500)
    
    // 9. Verify callback invoked again
    assertEquals(2, stateChangeCount)
    assertEquals(MeshState.DISCONNECTED, latestState)
}
```

**Mocking Requirements:**
- `myNode.neighbors()` to return mutable list

**Expected Outcomes:**
- Callback invoked when state changes from DISCONNECTED → CONNECTED
- Callback invoked when state changes from CONNECTED → DISCONNECTED
- Polling interval: 1000ms

---

## Workflow 5: Gateway Preference Persistence

**Purpose:** Test gateway preference storage and retrieval

**Steps:**
```kotlin
@Test
fun `gateway preference - persist and load`() = runTest {
    // 1. Setup mock DataStore
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mockDataStore = mockk<DataStore<Preferences>>()
    val prefsFlow = MutableStateFlow(emptyPreferences())
    
    every { context.dataStore } returns mockDataStore
    coEvery { mockDataStore.data } returns prefsFlow
    coEvery { mockDataStore.edit(any()) } coAnswers {
        val transform = arg<suspend (MutablePreferences) -> Unit>(0)
        val mutablePrefs = MutablePreferences()
        transform(mutablePrefs)
        prefsFlow.value = mutablePrefs.toPreferences()
    }
    
    // 2. Initialize mesh
    api.initMesh(context)
    
    // 3. Set gateway preference to TOR_ONLY
    var setResult: Result<Unit>? = null
    api.setGatewayPreference(GatewayPreference.TOR_ONLY) { result ->
        setResult = result
    }
    
    // 4. Verify preference saved
    assertTrue(setResult!!.isSuccess)
    assertEquals(GatewayPreference.TOR_ONLY, api.getGatewayPreference())
    
    // 5. Reinitialize (simulate app restart)
    api.initMesh(context)
    
    // 6. Verify preference loaded from storage
    assertEquals(GatewayPreference.TOR_ONLY, api.getGatewayPreference())
}
```

**Mocking Requirements:**
- `DataStore<Preferences>` with mutable state
- `Context.dataStore` extension property

**Expected Outcomes:**
- Preference persisted to DataStore
- Preference loaded on `initMesh()`
- `currentGatewayPreference` volatile field updated

---

# Summary: Testing by Method Category

| Category | Method Count | Callback-Based | Direct Return | ApiResult | Event Registration |
|----------|-------------|----------------|---------------|-----------|-------------------|
| **Mesh Init** | 1 | 0 | 0 | 0 | 1 (lifecycle) |
| **Mesh State** | 8 | 0 | 8 | 0 | 0 |
| **Mesh Control** | 2 | 2 | 0 | 0 | 0 |
| **Proxy Control** | 2 | 0 | 2 | 0 | 0 |
| **Gateway Control** | 6 | 3 | 3 | 0 | 0 |
| **Storage Participation** | 7 | 2 | 5 | 0 | 0 |
| **Drop Folder** | 3 | 1 | 2 | 0 | 0 |
| **File Operations** | 5 | 4 | 1 | 0 | 0 |
| **Service Layer** | 3 | 1 | 2 | 0 | 0 |
| **Compute/Task** | 3 | 2 | 0 | 1 | 0 |
| **Settings** | 2 | 1 | 1 | 0 | 0 |
| **Service Bundle** | 2 | 0 | 1 | 0 | 1 |
| **Event Registration** | 11 | 0 | 0 | 0 | 11 |
| **Context Management** | 2 | 0 | 2 | 0 | 0 |
| **TOTAL** | **57** | **16** | **27** | **1** | **13** |

---

# Implementation Status Summary

| Status | Method Count | Examples |
|--------|-------------|----------|
| **IMPLEMENTED** | 32 | `getMeshStatus()`, `setTorGatewayEnabled()`, `retrieveFile()`, `addTask()` |
| **NOT IMPLEMENTED** | 17 | `storeFile()`, `streamFile()`, `startTask()`, `cancelTask()`, `getDropFolder()` |
| **PARTIAL** | 8 | `deleteFile()` (validates but doesn't delete), `setStorageAllocation()` (no-op) |

**Key Implementation Gaps:**
- File storage operations (`storeFile()`, `streamFile()`)
- Task lifecycle control (`startTask()`, `cancelTask()`)
- Drop folder management (all methods)
- Storage device enumeration and allocation
- Service participation controls
- Gossip message handling
- Gateway traffic monitoring

---

# Test Coverage Recommendations

## High Priority (Core Functionality)
1. **Gateway Controls** - 6 methods, all implemented
2. **Mesh State/Network Info** - 8 methods, all implemented
3. **Event Monitoring** - State/peer count callbacks
4. **Task Submission** - `addTask()` and status updates

## Medium Priority (Partial Implementation)
5. **File Retrieval** - `retrieveFile()`, `deleteFile()`, `getAllMeshFiles()`
6. **Storage Participation** - `setStorageParticipationEnabled()`, status query
7. **Settings Persistence** - Gateway preference save/load

## Low Priority (Not Implemented)
8. **File Storage** - Defer until canonical workflows implemented
9. **Task Lifecycle** - Defer until workflows implemented
10. **Drop Folder** - Defer until implemented

---

**End of Document**
