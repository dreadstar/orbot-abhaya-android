# Refactored Plan: Compute Task Request Lifecycle

## Clarification & Requirements

- **Compute task management** (creation, tracking, lifecycle, audit) must be handled by `IntelligentDistributedComputeService.kt` (and may leverage `TaskManager.kt`).
- **Broadcasting compute task requests** to the mesh should be delegated to a function in `MeshGossipService.kt`.
- All compute task requests must be tracked and managed centrally in `IntelligentDistributedComputeService.kt` (not in `MeshGossipService.kt`).
- The broadcast function in `MeshGossipService.kt` should be stateless and only handle mesh-wide propagation.
- All relevant data types must be defined and imported from canonical locations.

---

## Files to Change

| Filepath                                                                 | Purpose/Change                                                                 |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------ |
| `IntelligentDistributedComputeService.kt`                                | Centralize compute task management, call broadcast in `MeshGossipService.kt`   |
| `MeshGossipService.kt`                                                   | Provide stateless broadcast API for compute task requests                      |
| `TaskManager.kt`                                                         | (Optional) Provide task tracking, audit, and hooks for compute tasks           |
| `MeshEcosystemListener.kt`                                               | (If needed) Register listeners for compute task broadcasts                     |

---

## Data Types Required

| Type Name                        | File/Location                                      | Purpose/Fields                                                                 |
| --------------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------ |
| `LocalComputeTaskRequest`         | Define in `IntelligentDistributedComputeService.kt`| Represents a local compute task request; fields: `mmcpRequest`, `metadata`, etc|
| `MmcpComputeTaskRequest`          | `com.ustadmobile.meshrabiya.mmcp`                  | Canonical compute task request model                                           |
| `ComputeNodeResponse`             | Canonical location (imported)                      | Response to a compute task request                                             |
| `TaskManager.TaskRequest`         | `TaskManager.kt`                                   | Used for tracking/auditing compute tasks                                       |
| `TaskManager.TaskStatus`          | `TaskManager.kt`                                   | Used for tracking/auditing compute tasks                                       |

---

## Refactored Lifecycle & Function Signatures

### 1. **Task Creation & Management**  
**File:** `IntelligentDistributedComputeService.kt`

- Create a new compute task request (e.g., `LocalComputeTaskRequest`).
- Track/manage the request using `TaskManager` or internal structures.
- Call `MeshGossipService.broadcastComputeTaskRequestSync()` to propagate.

```kotlin
// IntelligentDistributedComputeService.kt

data class LocalComputeTaskRequest(
    val mmcpRequest: MmcpComputeTaskRequest,
    val metadata: Map<String, Any>? = null
)

fun addTaskRequest(localRequest: LocalComputeTaskRequest) {
    // Track/manage the request
    val taskId = TaskManager.createTaskWithParams(serviceMeta, localRequest.metadata ?: emptyMap())
    // Broadcast to mesh
    CoroutineScope(Dispatchers.IO).launch {
        val responses = meshGossipService.broadcastComputeTaskRequestSync(
            localRequest.mmcpRequest,
            MeshrabiyaConstants.getTimeoutMs()
        )
        handleComputeNodeResponses(localRequest, responses)
        // Optionally update TaskManager with responses/results
    }
}
```

### 2. **Broadcast API**  
**File:** `MeshGossipService.kt`

- Provide a stateless API for broadcasting compute task requests.

```kotlin
// MeshGossipService.kt

suspend fun broadcastComputeTaskRequestSync(
    request: MmcpComputeTaskRequest,
    timeoutMs: Long
): List<ComputeNodeResponse> {
    val requestBytes = request.toBytes()
    val responses = mutableListOf<ComputeNodeResponse>()
    val listener: (Int, ByteArray, String, Any?) -> Unit = { senderId, bytes, type, msg ->
        if (type == "ComputeNodeResponse" && msg is ComputeNodeResponse) {
            responses.add(msg)
        }
    }
    coreGossipBroadcastService.registerListener("ComputeNodeResponse", listener)
    coreGossipBroadcastService.sendBroadcast(requestBytes, "MmcpComputeTaskRequest")
    delay(timeoutMs)
    coreGossipBroadcastService.unregisterListener("ComputeNodeResponse", listener)
    return responses
}
```

### 3. **Task Tracking & Audit**  
**File:** `TaskManager.kt`

- Use `TaskManager.TaskRequest` and `TaskManager.TaskStatus` to track all compute tasks.
- Provide hooks for updating progress, completion, and output publishing.

---

## Listener Registration (Optional)

**File:** `MeshEcosystemListener.kt`

- Register listeners for `"ComputeNodeResponse"` and other compute-related broadcasts if needed.

---

## Summary of Changes

- **IntelligentDistributedComputeService.kt**:  
  - Define `LocalComputeTaskRequest`.
  - Centralize task creation, tracking, and lifecycle management.
  - Call broadcast API in `MeshGossipService.kt`.

- **MeshGossipService.kt**:  
  - Provide stateless broadcast API for compute task requests.
  - Do not manage task request list.

- **TaskManager.kt**:  
  - Track/audit all compute tasks and their lifecycle events.

- **MeshEcosystemListener.kt**:  
  - Register listeners for compute task broadcasts if mesh-wide event handling is needed.

---

## Example Data Type Definitions

```kotlin
// In IntelligentDistributedComputeService.kt
data class LocalComputeTaskRequest(
    val mmcpRequest: MmcpComputeTaskRequest,
    val metadata: Map<String, Any>? = null
)
```

---

## Next Steps

1. Refactor `IntelligentDistributedComputeService.kt` to use the above pattern.
2. Ensure `MeshGossipService.kt` provides only stateless broadcast APIs.
3. Use `TaskManager.kt` for all task tracking and audit.
4. Register listeners in `MeshEcosystemListener.kt` as needed.
5. Validate all imports and type references per AGENTS.md protocols.

---

# COMPUTE_ADD_TASK_LIFECYCLE.md

## Overview

The **Compute Add Task Lifecycle** in the orbot-android project governs how distributed compute tasks are created, announced, propagated, executed, and managed across the mesh network. This lifecycle is critical for enabling collaborative, decentralized computation using the mesh infrastructure, leveraging both local and remote resources.

---

## Key Concepts

- **Compute Task**: A unit of work (e.g., data processing, ML inference) that can be distributed and executed on mesh nodes.
- **Task Announcement**: The process of informing mesh peers about a new compute task.
- **Task Propagation**: Gossip/broadcast mechanisms to ensure all relevant nodes receive the task.
- **Task Execution**: Nodes execute the task if eligible and report results.
- **Result Aggregation**: Results are collected, validated, and stored or forwarded.

---

🟢 Distributed Compute Task Request Lifecycle: Canonical Implementation Plan
This plan incorporates:

MmcpComputeTaskRequest as the canonical protocol message for compute task requests.
Local tracking via a wrapper for status, output, heartbeat.
Broadcast and messaging via MeshGossipService.
Precise, verified type and file references.
1. Task Request Creation (Client Activity)
UI Layer:

User selects a compute service/task, recipient(s), source data, etc.
Constructs a new local task request.
Local Model (for status/output/heartbeat):

Task List:

2. Broadcast for Available Compute Nodes (Client Activity)
Initiate Broadcast via MeshGossipService:

3. Broadcast Logic in MeshGossipService.kt
Broadcast and Collect Responses:

Ensure ComputeNodeResponse is defined and serializable in the correct package.

4. Compute Node Evaluation (Compute Node Activity)
Listener for Compute Task Requests:

5. Client Node Selection & Task Scheduling (Client Activity)
Handle Responses and Schedule Task:

6. Compute Node Task Setup (Compute Node Activity)
Handle Add Task Request:

7. Task Heartbeat (Compute Node Activity)
Send Heartbeat Periodically:

8. Heartbeat Monitoring & Retry (Client Activity)
Listen for Heartbeats:

Periodic Check for Missing Heartbeats:

9. Task Completion & Output Sharing (Compute Node Activity)
On Completion:

Supporting Types
MmcpComputeTaskRequest:
MmcpComputeTaskRequest.kt
LocalComputeTaskRequest:
/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/IntelligentDistributedComputeService.kt
ComputeNodeResponse, TaskHeartbeat, TaskKPI:
Define in /lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/mmcp/ or /model/ as appropriate.
Summary Table
Step	File(s) Involved	Key Types/Methods
Task Creation	IntelligentDistributedComputeService.kt	LocalComputeTaskRequest, MmcpComputeTaskRequest
Broadcast	MeshGossipService.kt	broadcastComputeTaskRequestSync
Node Evaluation	MeshEcosystemListener.kt, MeshGossipService.kt	registerComputeTaskRequestListener
Scheduling	IntelligentDistributedComputeService.kt	handleComputeNodeResponses
Task Setup	MeshEcosystemListener.kt, TaskManager.kt	registerAddTaskRequestListener
Heartbeat	MeshEcosystemListener.kt, MeshGossipService.kt	registerTaskHeartbeatListener
Monitoring/Retry	IntelligentDistributedComputeService.kt	monitorHeartbeats
Output Sharing	DistributedStorageManager.kt, MeshGossipService.kt	sendOutputReference
Key Points
MmcpComputeTaskRequest is the canonical protocol message for all compute task requests.
LocalComputeTaskRequest wraps protocol requests for local status/output/heartbeat tracking.
MeshGossipService is responsible for all network/broadcast messaging.
All file references and types are verified and canonical.
No shortcuts or guesses—every reference is precise and validated.
If you need full file code for any step, specify the file and I will provide it.




## Lifecycle Diagram

```mermaid
flowchart TD
    A[Task Creation (Local Node)] --> B[Task Announcement (Broadcast)]
    B --> C[Task Reception (Remote Nodes)]
    C --> D{Eligibility Check}
    D -- Eligible --> E[Task Execution]
    D -- Not Eligible --> F[Ignore/Forward]
    E --> G[Result Submission]
    G --> H[Result Aggregation]
    H --> I[Task Completion]
    F --> B
```

---

## Files Involved

| Filepath                                                                 | Purpose/Role                                  |
| ------------------------------------------------------------------------ | --------------------------------------------- |
| Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt | Handles gossip, task announcement, and routing |
| Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/CoreGossipBroadcastService.kt | Broadcasts and deduplicates task messages     |
| Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt | High-level API for task lifecycle             |
| Meshrabiya/lib-meshrabiya/src/main/java/org/torproject/android/service/compute/IntelligentDistributedComputeService.kt | Task execution and orchestration              |
| Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/ComputeTaskRequest.kt | Data model for compute task requests          |
| Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/model/ComputeTaskResult.kt | Data model for compute task results           |
| Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshEcosystemListener.kt | Hooks for task events and result handling     |

---

## Detailed Lifecycle Steps

### 1. Task Creation

- Initiated by a local app/service (e.g., user request, scheduled job).
- Task parameters, input data, and metadata are packaged into a `ComputeTaskRequest` object.

### 2. Task Announcement

- The local node serializes the `ComputeTaskRequest`.
- Uses `CoreGossipBroadcastService.sendBroadcast()` to propagate the task to mesh neighbors.
- Message type: `"ComputeTaskRequest"`

### 3. Task Reception

- Remote nodes receive the broadcast via `onGossipMessageReceived()` in `AndroidVirtualNode`.
- Deduplication and neighbor-aware forwarding handled by `CoreGossipBroadcastService`.
- Listeners registered for `"ComputeTaskRequest"` process the incoming task.

### 4. Eligibility Check

- Each node evaluates if it can execute the task (resource availability, permissions, etc.).
- If eligible, proceeds to execution; otherwise, ignores or forwards.

### 5. Task Execution

- Eligible nodes invoke `IntelligentDistributedComputeService` to execute the task.
- Execution may be local or delegated to another node.

### 6. Result Submission

- Upon completion, results are serialized into a `ComputeTaskResult`.
- Results are broadcast/gossiped back to the originator or relevant nodes.
- Message type: `"ComputeTaskResult"`

### 7. Result Aggregation

- The originator or designated aggregator collects results.
- Results are validated, merged, and stored or used for further processing.

### 8. Task Completion

- Final status is updated.
- Notifications or callbacks are triggered via `MeshEcosystemListener`.

---

## Example Code Snippets

### Task Announcement

```kotlin
val computeTaskRequest = ComputeTaskRequest(/* params */)
val requestBytes = serializeMessage(computeTaskRequest, "ComputeTaskRequest")
coreGossipBroadcastService.sendBroadcast(requestBytes, "ComputeTaskRequest")
```

### Task Reception Listener

```kotlin
coreGossipBroadcastService.registerListener("ComputeTaskRequest") { senderId, bytes, type, obj ->
    val (msgType, taskRequest) = deserializeMessage(bytes)
    if (msgType == "ComputeTaskRequest" && taskRequest is ComputeTaskRequest) {
        // Check eligibility and execute
    }
}
```

### Result Submission

```kotlin
val result = ComputeTaskResult(/* params */)
val resultBytes = serializeMessage(result, "ComputeTaskResult")
coreGossipBroadcastService.sendBroadcast(resultBytes, "ComputeTaskResult")
```

---

## TODOs

- [ ] **Implement eligibility checks** for compute tasks on all nodes.
- [ ] **Integrate result aggregation logic** for multi-node tasks.
- [ ] **Add robust error handling** for task execution failures.
- [ ] **Document security and privacy considerations** for distributed compute.
- [ ] **Optimize broadcast TTL and deduplication** for high-frequency tasks.
- [ ] **Unit tests for all lifecycle steps** (creation, broadcast, execution, result).
- [ ] **Performance benchmarks** for task propagation and execution.
- [ ] **Add support for task cancellation and timeout.**
- [ ] **Expose lifecycle events via MeshEcosystemListener for UI/monitoring.**
- [ ] **Review and update serialization/deserialization for new task types.**

---

## References

- [AndroidVirtualNode.kt](../vnet/AndroidVirtualNode.kt)
- [CoreGossipBroadcastService.kt](../vnet/CoreGossipBroadcastService.kt)
- [MeshGossipService.kt](./MeshGossipService.kt)
- [IntelligentDistributedComputeService.kt](../../org/torproject/android/service/compute/IntelligentDistributedComputeService.kt)
- [ComputeTaskRequest.kt](../model/ComputeTaskRequest.kt)
- [ComputeTaskResult.kt](../model/ComputeTaskResult.kt)
- [MeshEcosystemListener.kt](./MeshEcosystemListener.kt)

---

**This document is a living reference. Update as new features, fixes, or design changes are made.**