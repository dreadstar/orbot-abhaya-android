# Task Execution Layer Implementation - Questions Before Plan Creation

**Date**: January 9, 2025  
**Context**: Creating comprehensive task execution layer plan per user directive (remove DISTRIBUTED_STORAGE from compute, single-node execution, sandboxed tasks, resource monitoring, result storage coordination)

---

## CRITICAL ARCHITECTURE DECISIONS

### 1. Task Execution Coordinator Architecture
**Question**: Should we create a new `TaskExecutionCoordinator` class/object to handle execution, or extend `TaskManager` with execution capabilities?

**Context**:
- `TaskManager` is currently an **object** (singleton) for tracking task lifecycle (create, progress, complete)
- Has per-task hooks (accessUpdateHandlers, publishOutputHooks) but NO execution logic
- `IntelligentDistributedComputeService` handles request/response but also has NO execution after assignment
- `StrangersSafeComputeEngine` provides sandboxing infrastructure but is a **class** (needs instantiation)

**Options**:
- **Option A**: Extend `TaskManager` object with execution methods (maintains singleton, centralized task management)
- **Option B**: Create new `TaskExecutionCoordinator` object that uses TaskManager for tracking (separation of concerns)
- **Option C**: Make execution part of `IntelligentDistributedComputeService` (keeps compute service as primary handler)

**Recommendation Requested**: Which architecture best fits the existing design?

**Answer: extend TaskManager

---

### 2. Task Acceptance Message Handling
**Question**: Where/how is the "client sends acceptance message to compute node" currently implemented?

**Context**:
- User specified: "this would connect to the task execution lifecycle after the compute_node has responded to a broadcast request and the client_node has selected the given compute_node to run the task. client_node has then sent a message to accept the offer containing the task profile"
- `IntelligentDistributedComputeService.assignTaskToNode()` currently marks task as ASSIGNED but has `TODO: Send actual task assignment message` (line 274)
- No message type found for "task acceptance" in `GossipMessageType` enum or `MeshEcosystemMessage`

**Status**: 
- ❌ Task acceptance message protocol NOT YET IMPLEMENTED
- ✅ Request/response protocol exists (COMPUTE_TASK_REQUEST, ComputeNodeResponse)
- ❌ Assignment/acceptance message missing

**Required Clarification**: 
1. Should we define new `TASK_ASSIGNMENT` or `TASK_ACCEPTANCE` message type?
2. What should the message contain? (taskId, taskData, inputParameters, resourceLimits, deadlines?)
3. Should assignment be synchronous (wait for ack) or fire-and-forget?

**Answer: the message should be sent in IntelligentDistributedComputeService.assignTaskToNode()
---

### 3. Service Persistence Model
**Question**: Should services in `LocalDeviceServiceLibrary` persist across app restarts?

**Context**:
- `LocalDeviceServiceLibrary` is object (singleton) with **in-memory** mutableList storage
- Services currently lost when app restarts
- `ServicePackageManager` handles .meshsvc package installation but no persistence hook to LocalDeviceServiceLibrary

**Options**:
- **Option A**: Keep in-memory only (services re-register on app start, lightweight)
- **Option B**: Add persistence layer (SharedPreferences, SQLite, or file-based) to save installed services
- **Option C**: Hybrid: Built-in services in-memory, user-installed services persisted

**Recommendation Requested**: What's the expected service lifecycle?

**Answer: Service LIbrary should persist across restarts.  there are builtin  services and we will eventually add functionalty for a user to add services to the library. 

---

## RESOURCE MONITORING DESIGN

### 4. Resource Monitoring Location and Granularity
**Question**: Where should resource monitoring live and what granularity is needed?

**Context**:
- User requirement: "track and provide the total load from all tasks executing (ram cpu disk etc) for use in node capability calculations"
- Current `ResourceManager` interface has only `getClusterResourceState()` (CPU/memory utilization, availableNodes)
- `SimpleResourceManager` implementation returns hardcoded values (0.3 CPU, 0.5 memory)
- NO per-task resource tracking found
- `StrangersSafeComputeEngine.MicroContainer.ResourceLimits` defines maxMemoryBytes, maxCpuTimeMs but no monitoring hook

**Required Decisions**:
1. **Monitoring Location**: 
   - Extend `ResourceManager` interface for local node monitoring?
   - Create separate `TaskResourceMonitor` class?
   - Build into `TaskExecutionCoordinator`/`TaskManager`?

**Answer: make it part of TaskManager

2. **Granularity**:
   - Track total load only (sum of all tasks)?
   - Track per-task load (for debugging/optimization)?
   - Track per-container load (MicroContainer-level)?

**Answer: TaskManager should track totals for sum of all tasks and at a per task level 

3. **Metrics**:
   - RAM: Actual usage vs limit? Peak vs average?
   - CPU: Time used vs time available? Percentage?
   - Disk: I/O operations? Storage used?
   - Network: Bandwidth (even though containers have networkAccess=false)?

**Answer: RAM: Actual,Average and peak. CPU: time used, percentage of available. Disk: I/O operations and Storage used. Network: no

4. **Collection Method**:
   - Poll Android APIs (ActivityManager.MemoryInfo, /proc/stat)?
   - Monitor via StrangersSafeComputeEngine's container monitoring?
   - Use Android's `TrafficStats` or `Debug` classes?

**Recommendation Requested**: What level of resource monitoring is needed for "node capability calculations"?

**Answer: Monitor via StrangersSafeComputeEngine's container monitoring

---

### 5. Container Resource Monitoring Integration
**Question**: How should `StrangersSafeComputeEngine` report resource usage to TaskManager/ResourceMonitor?

**Context**:
- `StrangersSafeComputeEngine.monitorContainerExecution()` exists but returns placeholder data (lines 359-371)
- Returns `ExecutionTrace` with memoryUsed, cpuTimeUsed, syscallsUsed
- NO integration with TaskManager for live tracking

**Options**:
- **Option A**: StrangersSafeComputeEngine calls TaskManager.updateResourceUsage(taskId, metrics) periodically during execution
- **Option B**: TaskManager polls StrangersSafeComputeEngine.getContainerMetrics(containerId) on interval
- **Option C**: Event-based: StrangersSafeComputeEngine emits ResourceUpdateEvent that TaskManager subscribes to

**Recommendation Requested**: Callback pattern or polling for resource updates?

**Answer: TaskManager polls StrangersSafeComputeEngine.getContainerMetrics(containerId) on interval

---

## RESULT STORAGE COORDINATION

### 6. Task Result Format and Permission Model
**Question**: What format should task results use, and how do we specify "Owner and recipients" permissions?

**Context**:
- User requirement: "coordinate sending completion notification and writing results to DistributedStorage...with proper access permissions for Owner and recipients"
- Current `TaskManager.completeTask()` publishes files via `SandboxStorageProxy` with `AccessScope.TASK_ISOLATED` (lines 234-294)
- `SandboxStorageProxy.AccessScope` enum: TASK_ISOLATED, SERVICE_SHARED, MESH_GLOBAL
- Task outputs encoded as Base64 and stored with `StorageRequest.Store` (lines 267-274)
- Audit trail includes `recipients` and `owner` (OutputPublishAudit) but NO actual permission grant found

**Required Clarifications**:
1. **Result Format**:
   - Continue Base64 encoding for binary results?
   - JSON for structured results?
   - Support multiple output files per task?

**Answer: support mulitple output files  per task supported with an output manifest that can have zero or more file references. yes,Continue Base64 encoding for binary results to keep data encoding consistent (unless there is a good reason not to). data strucutre should be conducive to passing between nodes. messasgepack should also be used consistenntly

2. **Permission Model**:
   - Is `AccessScope` sufficient or do we need explicit ACL per file?
   - How are "recipients" specified? (List of onion addresses? Node IDs?)
   - Does "Owner" = task requester? Or task service author?

**Answer: From the distributed compute task perspective AccessScope should be sufficient because AccessScope will define the recipients of the output and otherewise the task only cares about the files to which it has access in Distributed Storage. Owner== task requestor.  task service author is the person or organization that creates the service which is then instantiated into a task when requested by the owner.

3. **Permission Grant Mechanism**:
   - Line 292 has `TODO: Explicitly grant access to recipients (update permissions, send notification, etc.)`
   - Should this call `DistributedStorageManager.updateAccessControl(fileId, recipients)`?
   - Do recipients get notified via gossip message or polling?

**Answer: Recipients are notified by Node to node messages. The userid is the same as the node public key to simplify knowing which node(s) to contact. TO be clear a task will only be publishing data or accessing data. so when a task completes and its output is written to distributed storage via TaskManager which then should initiate a stroage lifeccyle proces witha call to DistributedStorageManager.storeFile, which seems to be missing the AccessScope information in its paramete. Analyze the fucntion thoruohgly  and its parameters to verify if the Owner and recipient data are present and if they arent, add  steps to the plan to refactor that function and  the storeFile function in the api.

**Recommendation Requested**: What's the expected access control model for task outputs?

**Answer: review STORAGE_ENCRYPTION+PLAN.md for details on the plan for handling storage encryption with owner and recipient and task access. all of the plan detailed may not have been implemented. analyze the existing code to compare what exists to the propsed design in  the STORAGE_ENCRYPTION+PLAN.md plan.
---

## TASK TYPE PRESERVATION

### 7. Task Type Inventory and Mapping
**Question**: What task types should be preserved, and how do they map to executors?

**Context**:
- User directive: "aside from Distributed_storage and LiteRT tasks, the range of task types supported should be preserved"
- **JobType enum** (JobTypes.kt): IMAGE_PROCESSING, DATA_ANALYSIS, ML_PIPELINE, SENSOR_FUSION, COLLABORATIVE_FILTERING, **DISTRIBUTED_STORAGE** ← REMOVE
- **ComputeTaskType enum** (EnhancedGossipMessage.kt): IMAGE_PROCESSING, VIDEO_PROCESSING, DATA_ANALYSIS, ML_TRAINING, ML_INFERENCE, CRYPTOGRAPHIC, MAP_REDUCE, PIPELINE, GRAPH_PROCESSING
- **Executors.kt**: Only `PythonExecutor` interface exists (NO implementation), `LiteRTEngine` commented out
- **ML Services** (UnifiedMLServiceManager.kt): ML Kit Native, ML Kit Custom tiers (text recognition, face detection, object detection, translation, custom models)

**Current Mapping** (inferred):
- IMAGE_PROCESSING → PythonExecutor (OpenCV scripts) OR ML Kit Native (object detection)
- ML_INFERENCE → ML Kit Custom models OR PythonExecutor (model loading)
- DATA_ANALYSIS → PythonExecutor (Pandas, NumPy scripts)
- ML_TRAINING → PythonExecutor (model training scripts)? Or not supported?
- CRYPTOGRAPHIC → JVM native (Kotlin crypto libraries)?
- MAP_REDUCE → PythonExecutor (multi-step script)?
- PIPELINE/GRAPH_PROCESSING → PythonExecutor OR HybridServiceEntry?

**Questions**:
1. Should we support **all** ComputeTaskType values or subset?

**Answer: the current task types do not seem well organized. There should probably be task types and job types. The types should breakdown by the engines required (Python, JAVA,NVM, JAVASCRIPT, ML Native, WORKFLOW) and then job types(IMAGE_PROCESSING, VIDEO_PROCESSING, DATA_ANALYSIS,  ML_INFERENCE, CRYPTOGRAPHIC, MAP_REDUCE, ML_PIPELINE, GRAPH_PROCESSING). The task type and the job type are not linked allowing for greater flexibility on the implementation of Job_types.

2. What executor types do we need? (PythonExecutor, JVMExecutor, NativeExecutor, MLKitExecutor, HybridExecutor?)

**Answer: the minimal set to support the task types and job types i specified in above answer. we want to minimize the size of the app so consider lazy or as needed loading of the engines if possible

3. How do we map task types to service library entries (builtinLibraryEntries)?

**Answer: the task type should be identified in the service entry as well as the job_type

4. Should ML_TRAINING be single-node or is it too resource-intensive for mobile mesh?

**Answer: for now we will not support ML_training in Distributed Compute until we can design a multi node mesh solution to processing.

**Recommendation Requested**: Which task types are priority for initial implementation?

**Answer: i have reduced the list. so implement all of them 

---

## RUNTIME MANAGEMENT

### 8. Runtime Installation and Management
**Question**: Who installs and manages Python/Node.js/etc runtimes? How do we verify availability?

**Context**:
- `LocalDeviceServiceLibrary.Runtime` enum: JVM, NATIVE, PYTHON, NODEJS, RUST, GO, WASM (7 runtimes)
- `DeviceProfile` limits runtimes: BUDGET (JVM, NATIVE only), MID_RANGE (+PYTHON, NODEJS), FLAGSHIP (all 7)
- `ServiceManifest.RuntimeSpec` specifies language, version, runtime, execution mode
- NO runtime installation code found
- `LocalDeviceServiceLibrary.getAvailableRuntimes()` returns hardcoded list based on profile

**Assumptions**:
- JVM: Always available (Android app runs on JVM)
- NATIVE: Available via NDK/JNI
- PYTHON: Requires Chaquopy or python-for-android → NOT installed by default
- NODEJS: Requires J2V8 or similar → NOT installed by default
- RUST/GO/WASM: Cross-compilation to native or web runtime → NOT installed by default

**Questions**:
1. Should runtime installation be:
   - **Automatic**: App downloads/installs Python runtime on first use?
   - **Manual**: User installs via separate app (e.g., Termux for Python)?
   - **Bundled**: Ship Python/Node.js runtime in APK (increases size significantly)?

**Answer: Automatically installed and uninstalled , triggered by UI selections as part of Distributed Compute participation communicated via API 

2. How do we **verify runtime availability**?
   - Check for executable in PATH?
   - Try executing "python --version" or "node --version"?
   - Maintain registry of installed runtimes?

**Answer: maintain a registry based on the builtin and those installed  by mechanism described in previous answer. should have persistence across restarts

3. What if runtime missing?
   - Reject service execution?
   - Prompt user to install?
   - Fall back to alternative executor?

**Answer: a compute node would not response to a task request broadcast if it di not have the runtime to execute the task being requested

**Recommendation Requested**: What's the runtime installation strategy for mobile mesh?

---

## SANDBOXING INTEGRATION

### 9. Container Lifecycle and Task Injection
**Question**: How do we inject task code and data into `StrangersSafeComputeEngine` containers?

**Context**:
- `StrangersSafeComputeEngine.executeUntrustedCode()` exists but takes `codeBundle: ByteArray, input: ByteArray` (lines 255-298)
- **No implementation for**:
  - How to convert ServiceManifest + service code → codeBundle
  - How to serialize task input parameters → input ByteArray
  - How to deserialize output ByteArray → task result
- `MicroContainer.CommunicationPipe` uses named pipes (inputPipe, outputPipe, errorPipe) but creation/usage NOT implemented (placeholder at lines 301-325)
- `BulletproofSandbox` has similar architecture but also incomplete (lines 1-200 read)

**Required Details**:
1. **Code Bundle Format**:
   - Zip archive with Python script + manifest?
   - Single executable binary?
   - Language-specific format (Python .pyc, JVM .jar)?

**Answer: there are many more options than Python including bytecode so it needs to be langauage agnostic archive

2. **Input Serialization**:
   - JSON for structured parameters?
   - Protocol Buffers for efficiency?
   - Raw binary for images/models?

**Answer: these all sound like good selections.  remember task requests will  contain a manifest of input file references (not the actual files ) so the actual retrieval of those files for the task will be done via TaskManager leveraging DistributedStorage.retrieveFile functionality.

3. **Pipe Usage**:
   - Do we write to inputPipe before process starts or during execution?
   - Do we read from outputPipe in streaming fashion or block until completion?
   - How do we detect task completion vs timeout?

**Answer: we should load the initial data to the container before the task process is started. when task completes there should be a signal to TaskManager which includes an output manifest of files created by the task and which would need to be written to Distributed storage and the Users/task/nodes notified.

4. **Container Reuse**:
   - Create new container per task (clean slate but overhead)?
   - Pool containers and reuse (efficiency but state contamination risk)?
   - Per-service container (balance)?

**Answer: Create new container per task and deleted upon task competion or termination

**Recommendation Requested**: What's the container execution flow from task assignment to result extraction?

---

### 10. Multi-Runtime Sandboxing
**Question**: Does `StrangersSafeComputeEngine` support all runtimes (Python, Node.js, JVM, Native) or just Python?

**Context**:
- `StrangersSafeComputeEngine` is runtime-agnostic in design (takes `codeBundle: ByteArray`)
- `BulletproofSandbox` similar (no runtime-specific logic)
- BUT: No executor implementations exist to prepare runtime-specific code bundles
- Container isolation (process, namespaces, syscall filtering) is runtime-agnostic
- BUT: Each runtime may need different syscall whitelists (Python needs more than native C)

**Questions**:
1. Should we have **runtime-specific sandbox configurations**?
   - Python sandbox: Allow file I/O for .pyc imports, more syscalls
   - JVM sandbox: Allow class loading, reflection syscalls
   - Native sandbox: Minimal syscalls, no dynamic loading



2. Or keep **one universal sandbox** with maximum syscall whitelist?
   - Risk: Less secure (all runtimes get union of all syscalls)
   - Benefit: Simpler implementation

**Recommendation Requested**: Universal sandbox or per-runtime sandboxes?

**Answer: thee should be runtime specifc used based on teh task type/runtime engine

---

## COMPLETION NOTIFICATION

### 11. Task Completion Notification Protocol
**Question**: How should compute nodes notify client nodes of task completion?

**Context**:
- User requirement: "coordinate sending completion notification"
- Current `IntelligentDistributedComputeService.assignTaskToNode()` placeholder: marks COMPLETED but NO notification sent (lines 274-288)
- `TaskManager.completeTask()` publishes output to storage but NO gossip message sent
- NO `TASK_COMPLETED` or `TASK_RESULT_READY` message type found in GossipMessageType

**Options**:
- **Option A**: Compute node sends direct message to client node (requires storing client node address)
- **Option B**: Compute node broadcasts TASK_COMPLETED (inefficient, all nodes see)
- **Option C**: Client node polls compute node (bad UX, wastes bandwidth)
- **Option D**: Compute node publishes result to storage, client polls storage (leverages existing storage layer)

**Answer: Option a. user id is node pub key which is its address i believe (confirm that is true)


**Required Details**:
1. What message type? (Define new TASK_COMPLETED in GossipMessageType?)

**Answer: create new TASK_COMPLETED message time in MeshEcosystemMessage.kt
2. What payload? (taskId, resultFileId, executionStats, error if failed?)

**Answer: at a minimim , taskId, resultManifest (remember can be more than one file), resultMessage (task can define a result message in additon to the manifest), exectution stats, error if failed

3. Should notification include result data or just reference?

**Answer: just a manifest with references to the output file(S)

4. What if client node offline when task completes? (Store notification? Retry?)

**Answer: Retry for a period of time defined by a new vairable to be added to MeshrabiyaConstants.kt

**Recommendation Requested**: What's the completion notification strategy?

---

## SALVAGEABLE CODE FROM SCHEDULER

### 12. Scheduler Logic to Preserve
**Question**: Which specific parts of scheduler implementation should be salvaged?

**Context**:
- User directive: "use the design in the Scheduler implementation as sampled reference for useful logic to refactor"
- From previous analysis, salvageable components:
  - ✅ **Node selection logic**: findSuitableNodes(), selectOptimalNode() with multi-factor scoring
  - ✅ **Resource filtering**: RAM, battery, thermal, GPU/NPU, storage, network latency checks
  - ✅ **DependencyGraph**: Complete DAG implementation (for future workflow orchestration, not immediate need)
  - ❌ **Decomposition logic**: User explicitly wants REMOVED (single-node execution only)
  - ❌ **Quorum coordination**: Not applicable for single-node execution

**Specific Salvage Questions**:
1. Should node selection be part of **client-side** (IntelligentDistributedComputeService already does this) or **compute-side** (verify client's selection)?

**Answer: the Scheduler code can be examined for ideas on compute node selection that doesnt require quorum logic or refactored not to use it. It would be integrated into the client_side node selection logic. This should only be done if there is cleaer benefit to the Scheduler implmentaiton over the current implementaiton

2. Should resource filtering be used for:
   - Deciding whether to respond to broadcast? (already done in handleIncomingComputeTaskRequest)
   - Deciding whether to execute accepted task? (additional check before execution?)

**Answer; the compute_node should use resourc filtering to decide if it should respond and client _node uses the most capable and fit compute_node for the task_type in question

3. Should we extract node selection into reusable utility class or keep inline in IntelligentDistributedComputeService?

**Answer: if the size of IntelligentDistributedComputeService.kt is getting too large for analysis breakout the node selection logic for both the compute_side and client_side furhter breakout common logic for both logic processes if applicable

**Recommendation Requested**: Be specific about which scheduler files/methods to salvage and where to integrate.

---

## IMPLEMENTATION SCOPE

### 13. Phased Implementation or All-at-Once?
**Question**: Should we implement task execution layer in phases or as single comprehensive update?

**Answer: implement single comprehensive fully fuunctional complete update

**Context**:
- User requested "comprehensive plan" but actual implementation may be complex
- Multiple components needed: execution coordinator, resource monitoring, result storage, sandboxing integration, runtime management

**Options**:
- **Phase 1**: Core execution (PythonExecutor implementation, basic sandboxing)
- **Phase 2**: Resource monitoring and load tracking
- **Phase 3**: Result storage coordination and completion notification
- **Phase 4**: Multi-runtime support (JVM, Native executors)
- **Phase 5**: Enhanced security (StrangersTrustEngine proofs, reputation)

**OR**:
- **Single implementation**: All components together (risk of incomplete testing)

**Recommendation Requested**: Preferred implementation strategy?

---

## DATA STRUCTURE ENHANCEMENTS

### 14. Required Data Structure Changes
**Question**: What enhancements to existing data structures are needed?

**Identified Needs**:
1. **TaskStatus**: Add execution-related fields?
   - `executionStartedAt: Long?`
   - `executorNodeAddress: Int?`
   - `containerId: String?`
   - `resourceUsage: ResourceMetrics?`

**Answer: modify structues as resquired
2. **ComputeNodeResponse**: Already has mlKitFeatures, mlKitCustomSupport, currentLoad, estimatedLatencyMs. Sufficient or needs more?

3. **TaskRequest**: Add execution constraints?
   - `maxExecutionTimeMs: Long?`
   - `maxMemoryBytes: Long?`
   - `requiredRuntime: Runtime?`

**Answer: implement as MeshrabiyaConstant settings where 0 means unlimited and set default to 0

4. **New Classes Needed**:
   - `TaskExecutionContext`: Bundles task + input + limits + callbacks for executor
   - `ResourceMetrics`: CPU, RAM, disk usage snapshot
   - `ExecutionResult`: Extends TaskExecutionResult with resourcesUsed, executionProof

**Answer: Add as needed. they should all be placed in a common file like Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshComputeDataDefinitions.kt

**Recommendation Requested**: Review and suggest additional data structure needs.

---

## SUMMARY OF QUESTIONS

**Total**: 14 questions across 6 categories
- **Architecture**: 3 questions (coordinator, message handling, service persistence)
- **Resource Monitoring**: 2 questions (location/granularity, container integration)
- **Result Storage**: 1 question (format, permissions)
- **Task Types**: 1 question (preservation, mapping)
- **Runtime Management**: 1 question (installation strategy)
- **Sandboxing**: 2 questions (lifecycle, multi-runtime)
- **Completion**: 1 question (notification protocol)
- **Salvage**: 1 question (specific scheduler code to preserve)
- **Scope**: 1 question (phased vs all-at-once)
- **Data Structures**: 1 question (enhancements needed)

**Next Step**: User answers questions → Create comprehensive implementation plan

---

**Note**: These questions emerged from systematic analysis of:
- TaskManager.kt (task lifecycle tracking)
- Executors.kt (execution interfaces, NO implementations)
- LocalDeviceServiceLibrary.kt (service registry, runtime validation)
- ServicePackageManager.kt (package management)
- StrangersSafeComputeEngine.kt (sandboxing infrastructure)
- BulletproofSandbox.kt (alternative sandboxing approach)
- SandboxStorageProxy.kt (storage access control)
- IntelligentDistributedComputeService.kt (request/response handling, NO execution)
- UnifiedMLServiceManager.kt (ML service tiers)
- ResourceManager.kt (simple interface, no implementation)
- EnhancedGossipMessage.kt (message types, task types)
- JobTypes.kt (task types, capabilities)

All questions are grounded in actual code gaps and architectural decisions needed for complete task execution layer implementation.
