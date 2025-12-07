# ACTUAL TASK EXECUTION FLOW ANALYSIS
**Date:** December 4, 2025  
**Analysis Method:** Code literal reading (not comments)  
**Scope:** Complete flow from task creation to execution

---

## EXECUTION FLOW DIAGRAM

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     CLIENT SIDE (Task Requester)                        │
└─────────────────────────────────────────────────────────────────────────┘

1. API/User Request
   └─> DistributedComputeClient.processTaskRequest(LocalComputeTaskRequest)
       │
       ├─> Creates TrackedRequest (status: PENDING)
       ├─> activeRequests[taskId] = TrackedRequest
       │
       └─> virtualNode.getCoreGossipBroadcastService()
                      .sendBroadcast(ComputeTaskRequestMessage)
                      │
                      └──────────────────────────────────┐
                                                         │
                                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                   MESH NETWORK LAYER (Broadcast)                        │
└─────────────────────────────────────────────────────────────────────────┘
                                                         │
                      ┌──────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    SERVER SIDE (Compute Node)                           │
└─────────────────────────────────────────────────────────────────────────┘

2. VirtualNode.route()
   └─> MeshEcosystemListener.routeMessage(senderId, ComputeTaskRequestMessage)
       │
       ├─> Checks currentRoles.contains(MeshRole.COMPUTE_NODE)
       │
       └─> scope.launch {
               DistributedComputeServer.handleIncomingComputeTaskRequest()
           }
           │
           ├─> Looks up services[request.serviceId]
           ├─> Checks hasRequiredCapabilities()
           ├─> Calculates fitnessScore
           ├─> Gets ML capabilities (empty list for now)
           ├─> Calculates currentLoad (activeJobCount.get())
           │
           └─> sendDirectMessage(requesterNodeAddress, ComputeNodeResponseMessage)
               │
               └──────────────────────────────────┐
                                                  │
                                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                CLIENT SIDE (Response Collection)                        │
└─────────────────────────────────────────────────────────────────────────┘

3. VirtualNode.route()
   └─> MeshEcosystemListener.routeMessage(senderId, ComputeNodeResponseMessage)
       │
       └─> DistributedComputeClient.handleComputeNodeResponse(response)
           │
           ├─> Gets TrackedRequest from activeRequests[taskId]
           │
           └─> If response.available: tracked.candidateNodes.add(response)

4. (After timeout) DistributedComputeClient.selectAndAssignNode(taskId)
   │
   ├─> Sorts candidateNodes by:
   │   • estimatedLatencyMs (ascending)
   │   • currentLoad (ascending)
   │   • mlKitFeatures.size (descending)
   │
   ├─> selected = ranked.first()
   ├─> tracked.status = TaskRequestStatus.ASSIGNED
   │
   └─> virtualNode.sendEcosystemMessage(
           node.nodeAddress, TaskAssignmentMessage.toBytes())
       │
       └──────────────────────────────────┐
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│               SERVER SIDE (Task Acceptance & Execution)                 │
└─────────────────────────────────────────────────────────────────────────┘

5. VirtualNode.route()
   └─> MeshEcosystemListener.routeMessage(senderId, TaskAssignmentMessage)
       │
       └─> scope.launch {
               DistributedComputeServer.handleTaskAssignmentMessage()
           }
           │
           ├─> Looks up services[serviceId]
           ├─> Validates hasRequiredCapabilities()
           │
           ├─> Creates TaskExecutionContext from TaskAssignmentMessage
           │   • taskId, executorType, jobType, codeBundle
           │   • inputManifest (from inputFiles)
           │   • requesterNodeId, accessScope
           │
           ├─> TaskManager.addTask(executionContext, serviceEntry)
           │   │
           │   ├─> Generates RSA keypair (KeyPairGenerator.getInstance("RSA"))
           │   │   • publicKey (Base64 encoded)
           │   │   • privateKey (bytes)
           │   │
           │   ├─> TaskSandboxManager.createSandbox(taskId)
           │   │   │
           │   │   └─> Creates directories:
           │   │       • {filesDir}/task_sandboxes/{taskId}/
           │   │       • {filesDir}/task_sandboxes/{taskId}/inputs/
           │   │       • {filesDir}/task_sandboxes/{taskId}/outputs/
           │   │
           │   ├─> Creates Task object:
           │   │   • taskId, executionContext, serviceId
           │   │   • publicKey, privateKey
           │   │   • sandboxDir, executable
           │   │   • state = TaskState.PREPARING
           │   │
           │   ├─> activeTasks[taskId] = task
           │   │
           │   └─> TaskInputFileManager.trackExpectedFiles(taskId, inputManifest)
           │       • expectedFiles[taskId] = inputManifest.map{fileId}
           │
           ├─> activeJobCount.incrementAndGet()
           │
           ├─> sendTaskAcceptance(senderAddress, task)
           │   • Sends TaskAcceptanceMessage with task.publicKey
           │
           └─> scope.launch {
                   TaskManager.prepareTask(taskId, serviceEntry)
               }

6. TaskManager.prepareTask(taskId, serviceEntry)
   │
   ├─> Gets task from activeTasks[taskId]
   │
   └─> TaskInputFileManager.shouldProceedToExecution(task, serviceEntry)
       │
       ├─> If !serviceEntry.hasInputFiles: return true
       │
       └─> Else: checks expectedFiles[taskId] == receivedFiles[taskId]
           │
           ├─> If ready:
           │   │
           │   └─> TaskExecutionCoordinator.executeTask(task, sandboxPaths, serviceEntry)
           │       │
           │       ├─> task.state = TaskState.EXECUTING
           │       ├─> task.startedAt = currentTimeMillis()
           │       │
           │       ├─> Gets executor = executors[task.executionContext.executorType]
           │       │   • "JVMExecutor" → JVMExecutor()
           │       │   • "JSExecutor" → JSExecutor()
           │       │   • "MLNativeExecutor" → MLNativeExecutor(context)
           │       │
           │       ├─> Reads inputFiles from sandboxPaths.inputs directory
           │       │
           │       ├─> executor.execute(executionContext, inputFiles, containerId)
           │       │   │
           │       │   └─> [EXAMPLE: JVMExecutor.execute()]
           │       │       │
           │       │       ├─> Creates /tmp/jvm_workspace_{containerId}/
           │       │       │   • inputs/, outputs/, classes/
           │       │       │
           │       │       ├─> If JAR: extractJar(codeBundle, classesDir)
           │       │       │   Else: writes codeBundle as Main.class
           │       │       │
           │       │       ├─> Writes inputFiles to inputs/ directory
           │       │       │
           │       │       ├─> Creates isolated URLClassLoader:
           │       │       │   • URLClassLoader(classesDir.toURI(), null)
           │       │       │   • null parent = no system classes
           │       │       │
           │       │       ├─> Loads main class and invokes:
           │       │       │   • mainClass.getMethod("main", Array<String>::class)
           │       │       │   • mainMethod.invoke(null, arrayOf<String>())
           │       │       │
           │       │       ├─> Collects outputs from outputs/ directory
           │       │       │
           │       │       ├─> Returns ExecutionResult:
           │       │       │   • success, outputManifest, executionTimeMs
           │       │       │
           │       │       └─> workspaceDir.deleteRecursively() (cleanup)
           │       │
           │       ├─> If result.success:
           │       │   • task.state = TaskState.COMPLETED
           │       │   Else:
           │       │   • task.state = TaskState.FAILED
           │       │
           │       └─> task.completedAt = currentTimeMillis()
           │
           └─> Else (not ready):
               • task.state = TaskState.WAITING_FOR_INPUT

7. (After execution completes in scope.launch)
   │
   ├─> Gets completedTask from taskManager.getTask(taskId)
   │
   └─> sendTaskCompletion(senderAddress, completedTask, serviceEntry)
       │
       └─> Sends TaskCompletedMessage back to client
           │
           └──────────────────────────────────┐
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  CLIENT SIDE (Result Receipt)                           │
└─────────────────────────────────────────────────────────────────────────┘

8. VirtualNode.route()
   └─> MeshEcosystemListener.routeMessage(senderId, TaskCompletedMessage)
       │
       └─> scope.launch {
               DistributedComputeClient.handleTaskCompletionMessage(message)
           }

```

---

## KEY OBJECTS & RESPONSIBILITIES

| Object | Responsibility | Key Methods Called |
|--------|---------------|-------------------|
| **DistributedComputeClient** | Client-side task orchestration | `processTaskRequest()`, `handleComputeNodeResponse()`, `selectAndAssignNode()` |
| **DistributedComputeServer** | Server-side request handling | `handleIncomingComputeTaskRequest()`, `handleTaskAssignmentMessage()` |
| **TaskManager** | Task lifecycle & registry | `addTask()`, `prepareTask()`, `getTask()` |
| **TaskSandboxManager** | Filesystem isolation | `createSandbox()`, `getSandboxPaths()`, `writeInputFile()` |
| **TaskInputFileManager** | Input file tracking | `trackExpectedFiles()`, `shouldProceedToExecution()`, `handleFileAccessUpdate()` |
| **TaskExecutionCoordinator** | Executor routing | `executeTask()` |
| **JVMExecutor / JSExecutor / MLNativeExecutor** | Code execution | `execute()`, `validateCodeBundle()` |
| **MeshEcosystemListener** | Message routing | `routeMessage()` |
| **VirtualNode** | Network layer | `route()`, `sendEcosystemMessage()`, `getCoreGossipBroadcastService()` |

---

## ISOLATION MECHANISMS (CURRENT)

### **1. Filesystem Isolation**
```kotlin
// TaskSandboxManager.createSandbox()
baseDir = {filesDir}/task_sandboxes/{taskId}/
inputsDir = {filesDir}/task_sandboxes/{taskId}/inputs/
outputsDir = {filesDir}/task_sandboxes/{taskId}/outputs/
```

### **2. ClassLoader Isolation**
```kotlin
// JVMExecutor.execute()
val classLoader = URLClassLoader(
    arrayOf(classesDir.toURI().toURL()),
    null  // No parent classloader = isolated from system
)
```

### **3. Process Isolation**
**NOT IMPLEMENTED** - All code runs in same process as app

---

## CRITICAL FINDINGS

1. **StrangersSafeComputeEngine is NOT used**
   - No calls to `createContainer()` or `forkIsolatedProcess()`
   - ProcessBuilder code exists but never called

2. **ProcessBuilder is NOT in active flow**
   - `forkIsolatedProcess()` exists but never called
   - ProcessBuilder code (line 378-382) is dead code

3. **Actual isolation:**
   - **Filesystem:** Directory-based (`TaskSandboxManager`)
   - **Classloading:** `URLClassLoader(null parent)` = isolated from system classes
   - **Process:** None - same process as app
   - **Network:** None enforced (but executors don't attempt network)

4. **TaskLifecycleManager is NOT in flow**
   - Not called by any active code
   - Has placeholder implementations only
   - References non-existent methods

---

## DATA FLOW

```
User Request
    ↓
LocalComputeTaskRequest {taskId, taskType, priority, metadata}
    ↓
ComputeTaskRequestMessage {taskId, serviceId, inputParams, metadata}
    ↓ (broadcast)
ComputeNodeResponseMessage {nodeAddress, available, latency, load, mlCapabilities}
    ↓ (collect responses)
TaskAssignmentMessage {taskId, executorType, jobType, codeBundle, inputFiles, ...}
    ↓
TaskExecutionContext {taskId, executorType, jobType, codeBundle, inputManifest, requesterNodeId}
    ↓
Task {taskId, executionContext, serviceId, publicKey, privateKey, sandboxDir, executable, state}
    ↓
Execute via JVMExecutor/JSExecutor/MLNativeExecutor
    ↓
ExecutionResult {taskId, success, outputManifest, executionTimeMs, errorMessage}
    ↓
TaskCompletedMessage {taskId, result}
    ↓
Client receives result
```

---

## FILE LOCATIONS

| Component | File Path |
|-----------|-----------|
| DistributedComputeClient | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/DistributedComputeClient.kt` |
| DistributedComputeServer | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/DistributedComputeServer.kt` |
| TaskManager | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskManager.kt` |
| TaskSandboxManager | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskSandboxManager.kt` |
| TaskInputFileManager | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskInputFileManager.kt` |
| TaskExecutionCoordinator | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/TaskExecutionCoordinator.kt` |
| JVMExecutor | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executor/JVMExecutor.kt` |
| JSExecutor | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executor/JSExecutor.kt` |
| MLNativeExecutor | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/executor/MLNativeExecutor.kt` |
| MeshEcosystemListener | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshEcosystemListener.kt` |
| VirtualNode | `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt` |

---

**Generated:** December 4, 2025  
**Method:** Literal code analysis (no reliance on comments or documentation)
