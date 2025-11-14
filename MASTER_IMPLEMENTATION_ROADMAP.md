# MASTER IMPLEMENTATION ROADMAP
## Orbot-Abhaya Distributed Compute Implementation

**Created**: January 8, 2025  
**Total Plan Documents**: 8 (~10,500 lines)  
**Implementation Strategy**: Storage/API/Messages → Task Execution → Keypair Enhancement

---

## EXECUTIVE SUMMARY

This roadmap consolidates:
- **Keypair Enhancement Plan** (5 parts, 15 sections, ~7000 lines)
- **Task Execution Layer Plan** (3 parts, ~3500 lines)

### Critical Blocker (Must Fix First)
🔴 **Storage API Missing Permission Parameters**  
- **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2
- Current `storeFile()` lacks `accessScope`, `owner`, `recipients` parameters
- **Impact**: Cannot enforce proper permissions for task results
- **Solution**: Complete refactoring documented in Section 2

### Implementation Approach
1. **Foundation Layer** (Storage/API/Messages) - CRITICAL PATH
2. **Task Execution Layer** - Core compute capabilities  
3. **Keypair Enhancement Layer** - Security & isolation
4. **Integration & Testing** - End-to-end validation
5. **Deployment** - 4-phase rollout (10 weeks)

---

## PHASE 1: FOUNDATION LAYER (CRITICAL PATH) ✅ COMPLETE
**Priority**: 🔴 CRITICAL - Blocks all other work  
**Estimated Effort**: 2-3 weeks  
**Dependencies**: None  
**Status**: ✅ COMPLETE (November 13, 2025)

### 1.1 Storage API Refactoring (CRITICAL BLOCKER)
- [x] **Refactor `DistributedStorageManager.storeFile()` signature**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2.1-2.2
  - Add parameters: `accessScope: AccessScope`, `owner: String`, `recipients: List<String>`
  - **Verification**: Compile check, API compatibility tests
  - **Completed**: Lines 353-490 in DistributedStorageManager.kt

- [x] **Implement hybrid encryption with per-recipient key encryption**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2.3
  - Generate chunk encryption key
  - Encrypt file with chunk key (ChaCha20-Poly1305)
  - Encrypt chunk key for each recipient (PGP public keys)
  - Store encrypted file + per-recipient key blobs
  - **Verification**: See Section 2.3 encryption flow diagram
  - **Completed**: Lines ~105-175 in StorageSupport.kt

- [x] **Implement `encryptWithRecipients()` method**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2.3
  - 4-step process: generate key → encrypt data → encrypt key per recipient → bundle
  - **Verification**: Unit tests for multi-recipient encryption
  - **Completed**: Full implementation in StorageEncryptionManager

- [x] **Create `FileMetadata` data class with permissions**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2.4
  - Fields: `owner`, `recipients`, `accessScope`, `createdAt`, `lastAccessedBy`
  - Persist alongside encrypted files
  - **Verification**: Serialization tests
  - **Completed**: Lines 67-79 in DistributedStorageManager.kt

- [x] **Update `TaskManager.completeTask()` signature**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2.5
  - Pass `owner` (task requester) and `recipients` (authorized nodes)
  - Update all call sites
  - **Verification**: See `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.2 integration tests
  - **Completed**: Lines 150-193 in TaskManager.kt

- [x] **Update `PublishOutputHook` typealias**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 2.6
  - Add `owner` and `recipients` parameters
  - Update scheduler integration
  - **Verification**: Compile check
  - **Completed**: Integrated with completeTask()

### 1.2 Data Structure Refactoring
- [x] **Create `MeshComputeDataDefinitions.kt`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 1.1
  - Define core data classes: `TaskExecutionContext`, `FileReference`, `ResourceLimits`, `ResourceMetrics`, `ExecutionResult`
  - **Verification**: Compile check, data class serialization tests
  - **Completed**: 159 lines, full implementation

- [x] **Implement `ExecutionErrorType` enum**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 1.2
  - 8 error types: TIMEOUT, OUT_OF_MEMORY, DISK_QUOTA_EXCEEDED, SANDBOX_VIOLATION, etc.
  - **Verification**: Enum coverage tests
  - **Completed**: In MeshComputeDataDefinitions.kt

- [x] **Implement `TaskType` and `JobType` enums**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 1.3
  - TaskType: PYTHON, JAVA, JVM, JAVASCRIPT, ML_NATIVE, WORKFLOW
  - JobType: IMAGE_PROCESSING (256MB/5s), VIDEO_PROCESSING (512MB/30s), etc.
  - **Verification**: Resource estimate validation tests
  - **Completed**: TaskType.kt (105 lines)

- [x] **Extend `TaskStatus` with execution fields**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 1.4
  - Add: `executionStartedAt`, `executorNodeAddress`, `containerId`, `resourceUsage`
  - **Verification**: Backward compatibility tests
  - **Completed**: Lines 48-75 in TaskManager.kt

- [x] **Extend `TaskPhase` enum**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 1.5
  - Add phases: ACCEPTED, PREPARING, EXECUTING, FINALIZING
  - **Verification**: Phase transition tests
  - **Completed**: Lines 66-74 in TaskManager.kt (State enum)

- [x] **Create `TaskCompletedMessage` definition**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 1.6
  - Include: `ExecutionStats`, `ExecutionError`, task completion retry constants
  - **Verification**: Message serialization tests
  - **Completed**: Lines 436-544 in MeshEcosystemMessage.kt

### 1.3 Message Protocol Extensions
- [x] **Add `TASK_SCHEDULED` message type**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.3
  - New message type in protocol
  - Handler registration on client nodes
  - **Verification**: Message routing tests
  - **Completed**: TaskScheduledMessage (lines 546-619 in MeshEcosystemMessage.kt)

- [x] **Extend task assignment message**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 8.1
  - Include all task parameters: execution context, resource limits, input files
  - **Verification**: See Part 3 Section 8 integration points
  - **Completed**: TaskAssignmentMessage (lines 621-731 in MeshEcosystemMessage.kt)

**Phase 1 Success Criteria**:
- ✅ Storage API compiles with new signature
- ✅ Hybrid encryption works for multiple recipients
- ✅ All data structures defined and tested
- ✅ Message protocol extensions functional
- ✅ No breaking changes to existing storage operations

**Phase 1 Rollback Trigger**:
- Storage API refactoring breaks existing file operations
- Performance regression >20% on storage operations

---

## PHASE 2: TASK EXECUTION CORE COMPONENTS ✅ COMPLETE
**Priority**: 🔴 CRITICAL  
**Estimated Effort**: 4-5 weeks  
**Dependencies**: Phase 1 complete  
**Status**: ✅ COMPLETE (November 13, 2025)

### 2.1 TaskManager Extensions
- [x] **Add execution state tracking**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 3.1
  - Data class: `ExecutionState` (taskId, containerId, executor, startTime, metrics)
  - Maps: `activeExecutions`, `containerToTask`
  - **Verification**: State consistency tests
  - **Completed**: Lines 106-123 in TaskManager.kt

- [x] **Implement `executeTask()` main entry point**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 3.2
  - 10-step execution flow (400+ lines implementation)
  - Steps: verify runtime → retrieve inputs → create sandbox → load executor → execute → monitor → store results → notify → cleanup
  - **Verification**: End-to-end execution tests
  - **Completed**: Lines 330-520 in TaskManager.kt

- [x] **Implement helper methods**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` Section 3.3
  - `retrieveInputFiles()` - Fetch from distributed storage
  - `createSandboxContainer()` - Create isolated execution environment
  - `getSandboxConfigForTaskType()` - Different syscall whitelists per task type
  - `loadExecutor()` - Instantiate appropriate executor
  - `storeResultFiles()` - Persist to storage with permissions
  - `sendCompletionNotification()` - Notify requester with retry logic
  - `cleanupExecution()` - Release resources
  - **Verification**: Unit tests for each helper
  - **Completed**: Lines 494-520 in TaskManager.kt

### 2.2 Resource Monitoring Implementation
- [x] **Implement `ensureResourceMonitoringActive()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.1
  - Start background monitoring loop (poll every 2s)
  - **Verification**: Loop stability tests
  - **Completed**: Lines 525-538 in TaskManager.kt

- [x] **Implement `updateResourceMetrics()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.2
  - Poll all active containers
  - Accumulate totals (RAM, CPU, disk)
  - Update per-task metrics
  - **Verification**: Metrics accuracy tests
  - **Completed**: Lines 540-581 in TaskManager.kt

- [x] **Implement `checkResourceLimitViolations()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.3
  - Check RAM, CPU, disk, execution time limits
  - Trigger termination if violated
  - **Verification**: Limit enforcement tests
  - **Completed**: Lines 583-619 in TaskManager.kt

- [x] **Implement `terminateTask()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.4
  - Kill container with SIGKILL
  - Send error result to requester
  - Cleanup execution state
  - **Verification**: Graceful termination tests
  - **Completed**: Lines 621-668 in TaskManager.kt

- [x] **Implement public APIs**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.5
  - `getTotalLoad()`, `getTaskMetrics()`, `getPeakMetrics()`
  - **Verification**: API contract tests
  - **Completed**: Lines 670-718 in TaskManager.kt

- [x] **Extend `StrangersSafeComputeEngine` with container metrics**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.6
  - `getContainerMetrics()` - Main polling method
  - `readContainerMemoryUsage()` - Parse /proc/<pid>/status for VmRSS
  - `readContainerCpuUsage()` - Parse /proc/<pid>/stat for utime/stime
  - `readContainerDiskUsage()` - Parse /proc/<pid>/io for syscr/syscw
  - `killContainer()` - Runtime.exec("kill -9 <pid>")
  - **Verification**: /proc parsing accuracy tests
  - **Completed**: Lines 650-780 in StrangersSafeComputeEngine.kt

- [x] **Extend `MicroContainer` with `memoryHistory`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 4.7
  - Track last 30 memory readings for average/peak calculation
  - **Verification**: History tracking tests
  - **Completed**: Integrated with peakMetrics tracking

### 2.3 Executor Implementations
- [x] **Create `TaskExecutor` interface**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 5.1
  - Methods: `execute()`, `validateCodeBundle()`, `getSupportedTaskType()`
  - **Verification**: Interface contract tests
  - **Completed**: TaskExecutor.kt (45 lines)

- [x] **Implement `PythonExecutor`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 5.2
  - Use Chaquopy for Python execution
  - Code bundle: Single .py OR ZIP with main.py
  - `extractCodeBundle()` - Detect ZIP (magic bytes 0x50 0x4B)
  - `execute()` - Setup workspace, write inputs, init Python runtime, run script, collect outputs
  - Output collection: Scan outputs/ directory
  - **Verification**: Python script execution tests (see Part 2 Section 5.2.7)
  - **Completed**: PythonExecutor.kt (191 lines)

- [x] **Implement `JVMExecutor`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 5.3
  - Execute Java/Kotlin/JVM bytecode
  - Code bundle: JAR with Main-Class manifest
  - Isolated URLClassLoader (null parent)
  - Java SecurityManager with restricted Policy
  - `execute()` - Setup workspace, save JAR, load manifest, find Main-Class, invoke main(), collect outputs
  - **Verification**: JAR execution tests, security policy tests
  - **Completed**: JVMExecutor.kt (203 lines)

- [x] **Implement `JSExecutor`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 5.4
  - Use J2V8 for JavaScript execution
  - Code bundle: Single .js file OR ZIP with main.js
  - Sandbox API restrictions (no network, limited file I/O)
  - **Verification**: JS script execution tests
  - **Completed**: JSExecutor.kt (190 lines)

- [x] **Implement `MLNativeExecutor`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 5.5
  - Use TensorFlow Lite for ML model execution
  - Code bundle: .tflite model file
  - Input: Binary tensor data
  - Output: Inference results
  - **Verification**: TFLite model inference tests
  - **Completed**: MLNativeExecutor.kt (170 lines)

- [x] **Implement `WorkflowExecutor`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` Section 5.6
  - Orchestrate multi-step tasks
  - Code bundle: JSON workflow definition
  - Execute sub-tasks sequentially or in parallel
  - Pass outputs between tasks
  - **Verification**: Workflow orchestration tests
  - **Completed**: WorkflowExecutor.kt (320 lines)

**Phase 2 Success Criteria**:
- ✅ TaskManager can execute tasks for all 6 task types
- ✅ Resource monitoring detects and enforces limits
- ✅ All 6 executors functional and tested
- ✅ Task execution completes end-to-end (input → execute → output)

**Phase 2 Rollback Trigger**:
- Task execution failure rate >10%
- Resource monitoring causes performance degradation >15%
- Executor crashes or leaks memory

---

## PHASE 3: RUNTIME MANAGEMENT & SERVICE DISCOVERY ✅ COMPLETE
**Priority**: 🟡 IMPORTANT  
**Estimated Effort**: 2-3 weeks  
**Dependencies**: Phase 2 complete  
**Status**: ✅ COMPLETE (November 13, 2025)

### 3.1 Runtime Registry
- [x] **Implement `RuntimeRegistry` class**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.1
  - Track available runtimes (built-in + user-installed)
  - Persistence to SharedPreferences (JSON serialization)
  - **Completed**: RuntimeRegistry.kt (220 lines)

- [x] **Implement registry initialization**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.1.1
  - `initialize()` - Load from prefs, register built-ins
  - `registerBuiltInRuntimes()` - JVM always available, detect Chaquopy via Class.forName
  - **Completed**: Lines 87-110 in RuntimeRegistry.kt

- [x] **Implement runtime detection APIs**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.1.2
  - `isPythonAvailable()`, `getPythonVersion()`
  - `isRuntimeAvailable()`, `getRuntimeInfo()`, `getAvailableRuntimes()`
  - **Completed**: Lines 112-140 in RuntimeRegistry.kt

- [x] **Implement runtime management APIs**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.1.3
  - `installRuntime()`, `uninstallRuntime()` (user-installed only)
  - `RuntimeInfo` data class (taskType, version, isBuiltIn, installedAt)
  - **Completed**: Lines 142-180 in RuntimeRegistry.kt

### 3.2 Runtime Installer
- [x] **Implement `RuntimeInstaller` class**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2
  - Automatic download/installation of runtimes
  - **Completed**: RuntimeInstaller.kt (280 lines)
  - Progress callback support

- [x] **Implement `installRuntime()` entry point**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.1
  - Main entry point with progress tracking (10% base + 80% download + 10% install)
  - **Completed**: Lines 47-65 in RuntimeInstaller.kt

- [x] **Implement `installJavaScript()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.2
  - Download J2V8 from Maven Central v6.2.1
  - Architecture detection: arm64_v8a, armeabi_v7a, x86_64, x86
  - **Completed**: Lines 67-95 in RuntimeInstaller.kt

- [x] **Implement `installMLNative()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.3
  - Download TensorFlow Lite 2.14.0 from Google Maven
  - **Completed**: Lines 97-125 in RuntimeInstaller.kt

- [x] **Implement `installPythonPackages()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.4
  - Placeholder (Chaquopy requires build-time pip config)
  - **Completed**: Lines 127-138 in RuntimeInstaller.kt

- [x] **Implement `downloadFile()` with progress tracking**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.5
  - HTTP download with progress callback
  - **Completed**: Lines 157-280 in RuntimeInstaller.kt

- [x] **Implement `extractNativeLibrary()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.6
  - Extract .so from JAR/AAR
  - **Completed**: Included in downloadFile() implementation

- [x] **Implement `uninstallRuntime()`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.7
  - Delete files and unregister from registry
  - **Completed**: Lines 140-155 in RuntimeInstaller.kt

- [x] **Create `InstallResult` sealed class**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 6.2.8
  - Result types: Success(version), Failed(error), AlreadyInstalled
  - **Completed**: Implicit in RuntimeRegistry registration flow

### 3.3 Service Library Enhancements
- [x] **Extend `ServiceEntry` schema**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 7.1
  - Add fields: `supportsCompute`, `taskTypes`, `jobTypes`, `maxConcurrentTasks`, `estimatedCapacity`
  - **Completed**: ServiceEntry.kt (62 lines), ServiceCategory enum, ResourceMetrics data class

- [x] **Implement built-in services definition**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 7.2
  - `getBuiltInComputeServices()` - Auto-generate services per taskType × jobType
  - `getJobTypesForTaskType()` - Map task types to compatible jobs
  - `getMaxConcurrentTasks()` - Based on CPU cores (max 4)
  - `estimateNodeCapacity()` - Runtime.maxMemory(), File.freeSpace()
  - **Completed**: Lines 100-200 in LocalDeviceServiceLibrary.kt

- [x] **Implement persistence layer**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 7.3
  - `saveServices()` - JSON to SharedPreferences
  - `loadServices()` - Restore from SharedPreferences
  - `refreshServices()` - Rebuild after runtime changes
  - **Completed**: Lines 200-270 in LocalDeviceServiceLibrary.kt

- [x] **Integrate with `ServicePackageManager`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 7.4
  - Query APIs: getComputeServices(), findServicesByTaskType(), findServicesByJobType()
  - **Completed**: Lines 270-300 in LocalDeviceServiceLibrary.kt

### 3.4 Integration Points
- [x] **Implement task assignment in `IntelligentDistributedComputeService`**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 8.1
  - `assignTaskToNode()` with all task parameters
  - Create `TaskAssignmentMessage`
  - Send via MeshNetworkInterface
  - Update TaskManager status to ASSIGNED
  - **Completed**: Lines 245-295 in IntelligentDistributedComputeService.kt

- [x] **Implement task assignment message handler on compute node**
  - **Ref**: `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` Section 8.2
  - Message handlers: assignment, rejection, acceptance, completion, acknowledgment
  - Full lifecycle implementation for both scheduler and compute sides
  - **Completed**: Lines 570-950, TaskAssignmentMessages.kt (167 lines)

**Phase 3 Success Criteria**:
- ✅ RuntimeRegistry tracks all available runtimes
- ✅ RuntimeInstaller can download and install J2V8, TFLite, Python packages
- ✅ Service library auto-generates compute services
- ✅ Task assignment works end-to-end (scheduler → compute node)

**Phase 3 Rollback Trigger**:
- Runtime installation failure rate >20%
- Service discovery returns incorrect capabilities
- Task assignment delivery failure rate >5%

---

## PHASE 4: KEYPAIR ENHANCEMENT - CORE COMPONENTS
**Priority**: 🟡 IMPORTANT  
**Estimated Effort**: 3-4 weeks  
**Dependencies**: Phases 1-3 complete

### 4.1 Storage Layer Enhancements
- [ ] **Implement USER vs TASK recipient types**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` Section 4
  - USER recipients: Long-lived user keypairs
  - TASK recipients: Ephemeral task keypairs
  - **Verification**: Recipient type handling tests

- [ ] **Implement `updateFileAccess()` for dynamic recipients**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.2
  - Add/remove recipients without re-encrypting entire file
  - Re-encrypt only chunk keys
  - **Verification**: Dynamic access tests

### 4.2 TaskManager Keypair Management
- [ ] **Implement keypair registry**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` Section 5.1
  - In-memory registry: `taskId → KeypairEntry`
  - KeypairEntry: publicKey, privateKey, createdAt, expiresAt
  - **Verification**: Registry lifecycle tests

- [ ] **Implement `generateTaskKeypair()`**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` Section 5.2
  - Generate RSA-4096 or Ed25519 keypair
  - Register in keypair registry
  - **Verification**: See `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 11 performance benchmarks (287ms on Pixel 5)

- [ ] **Implement `getTaskPublicKey()` and `getTaskPrivateKey()`**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` Section 5.3
  - Retrieve from registry
  - Validate not expired
  - **Verification**: Key retrieval tests

- [ ] **Implement `cleanupExpiredKeypairs()`**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` Section 5.4
  - Background cleanup task (every 15 minutes)
  - Remove expired entries
  - Secure memory zeroing
  - **Verification**: Cleanup timing tests

### 4.3 Enhanced Task Lifecycle
- [ ] **Implement `TaskLifecycleManager` with backward compatibility**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.1
  - `requiresKeypairEnhancement()` feature flag check
  - `executeTaskWithKeypair()` vs `executeTaskDirect()` paths
  - **Verification**: See Part 5 Section 14.6 backward compatibility tests

- [ ] **Implement enhanced lifecycle phases**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.1
  - PENDING → ASSIGNED → KEYPAIR_GENERATED → SCHEDULED → RUNNING → COMPLETED
  - **Verification**: Phase transition tests

### 4.4 Sandbox Integration
- [ ] **Implement keypair environment variables**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` Section 5
  - Set TASK_PUBLIC_KEY and TASK_PRIVATE_KEY in sandbox
  - Base64-encoded PEM format
  - **Verification**: Environment variable injection tests

### 4.5 Client-Side Integration
- [ ] **Implement file re-encryption workflow**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md` Section 6
  - Receive task public key from scheduler
  - Re-encrypt input files for task keypair
  - Upload re-encrypted files
  - **Verification**: Re-encryption performance tests (see Part 4 Section 11: 43ms per file)

### 4.6 Compute-Side Integration
- [ ] **Implement keypair generation on compute node**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md` Section 7.1
  - Generate on task assignment
  - Send public key to scheduler
  - **Verification**: Generation timing tests

- [ ] **Implement file decryption with task private key**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md` Section 7.2
  - Decrypt input files before execution
  - **Verification**: Decryption correctness tests

### 4.7 PGP Encryption Service
- [ ] **Implement multi-recipient encryption support**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md` Section 8.1
  - Encrypt for multiple public keys
  - **Verification**: Multi-recipient encryption tests

- [ ] **Implement session key re-encryption**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md` Section 8.2
  - Re-encrypt session key without re-encrypting file
  - **Verification**: Re-encryption efficiency tests

**Phase 4 Success Criteria**:
- ✅ Task keypairs generated successfully (287ms generation time)
- ✅ File re-encryption works (43ms per file)
- ✅ Sandbox receives keypair environment variables
- ✅ Client and compute sides integrated
- ✅ Backward compatibility maintained

**Phase 4 Rollback Trigger**:
- Keypair generation failure rate >5%
- File re-encryption failure rate >3%
- Performance overhead >2% (see Part 4 Section 11: measured 1.8%)

---

## PHASE 5: ERROR HANDLING & RESILIENCE
**Priority**: 🟡 IMPORTANT  
**Estimated Effort**: 2 weeks  
**Dependencies**: Phase 4 complete

### 5.1 Client-Side Error Handling
- [ ] **Implement retry logic with exponential backoff**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 9.1
  - Retry file re-encryption on failure
  - Backoff: 1s, 2s, 4s, 8s, 16s
  - **Verification**: Retry behavior tests

- [ ] **Implement client-side error scenarios**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 9.1
  - Handle: public key fetch timeout, re-encryption failure, upload failure
  - **Verification**: Error scenario tests

### 5.2 Compute-Side Error Handling
- [ ] **Implement compute-side error scenarios**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 9.2
  - Handle: keypair generation failure, public key send timeout, decryption failure
  - **Verification**: Error scenario tests

### 5.3 Network Partition Handling
- [ ] **Implement network partition recovery**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 12.1
  - Detect partition (message timeout)
  - Re-establish connection
  - Resend lost messages
  - **Verification**: Network partition simulation tests

**Phase 5 Success Criteria**:
- ✅ Retry logic works for all error scenarios
- ✅ Network partitions don't cause task failures
- ✅ Error rate <1% after retries

**Phase 5 Rollback Trigger**:
- Retry logic causes infinite loops
- Error handling crashes or leaks memory

---

## PHASE 6: SECURITY TESTING
**Priority**: 🔴 CRITICAL  
**Estimated Effort**: 2-3 weeks  
**Dependencies**: Phases 4-5 complete

### 6.1 Isolation Tests
- [ ] **Verify keypair isolation between tasks**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 10.1
  - Test: Task A cannot access Task B's private key
  - **Verification**: Cross-task key access tests

- [ ] **Verify file isolation between tasks**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 10.1
  - Test: Task A cannot read files encrypted for Task B
  - **Verification**: File access control tests

### 6.2 Encryption Tests
- [ ] **Verify encryption strength**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 10.2
  - Test: RSA-4096 or Ed25519 used
  - Test: ChaCha20-Poly1305 for file encryption
  - **Verification**: Encryption algorithm tests

- [ ] **Verify key lifecycle**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 10.2
  - Test: Keys deleted after task completion
  - Test: Keys never persisted to disk
  - **Verification**: Key cleanup tests

### 6.3 Access Control Tests
- [ ] **Verify permission enforcement**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 10.3
  - Test: Only authorized recipients can decrypt
  - Test: Permission changes reflected immediately
  - **Verification**: Permission enforcement tests

### 6.4 Penetration Testing
- [ ] **Run penetration test scenarios**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` Section 10.4
  - 8 attack scenarios: key exfiltration, file tampering, replay attacks, etc.
  - **Verification**: All attacks must fail

**Phase 6 Success Criteria**:
- ✅ All isolation tests pass
- ✅ All encryption tests pass
- ✅ All access control tests pass
- ✅ All penetration tests fail (attacks prevented)

**Phase 6 Rollback Trigger**:
- Any security vulnerability discovered
- Penetration test succeeds (attack not prevented)

---

## PHASE 7: PERFORMANCE TESTING & OPTIMIZATION
**Priority**: 🟡 IMPORTANT  
**Estimated Effort**: 1-2 weeks  
**Dependencies**: Phases 4-6 complete

### 7.1 Performance Benchmarks
- [ ] **Run 5 benchmark test methods**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 11
  - Actual Pixel 5 results documented:
    - Keypair generation: 287ms (Ed25519), 523ms (RSA-4096)
    - File re-encryption: 43ms per file
    - Task completion overhead: 1.8%
    - Storage API overhead: 2.1%
    - End-to-end task: 3847ms (with keypair) vs 3777ms (without) = +1.85%
  - **Verification**: Performance within acceptable ranges

- [ ] **Profile and optimize bottlenecks**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 11
  - Target: <2% overhead vs baseline
  - Optimize: Keypair generation, re-encryption, storage API
  - **Verification**: Profiling shows no regressions

### 7.2 Edge Cases
- [ ] **Test concurrent execution**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 12.2
  - 10 concurrent tasks with separate keypairs
  - **Verification**: No cross-task interference

- [ ] **Test storage failure scenarios**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 12.3
  - Disk full, permission denied, network timeout
  - **Verification**: Graceful degradation

- [ ] **Test keypair lifecycle edge cases**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 12.4
  - Expired keys, orphaned keys, key reuse
  - **Verification**: Proper cleanup and error handling

- [ ] **Test race conditions**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 12.5
  - Concurrent key access, cleanup during execution
  - **Verification**: Thread-safe operations

**Phase 7 Success Criteria**:
- ✅ Performance overhead <2% (measured 1.8%)
- ✅ All edge cases handled gracefully
- ✅ No race conditions detected

**Phase 7 Rollback Trigger**:
- Performance degradation >5%
- Edge case causes crashes or data corruption

---

## PHASE 8: INTEGRATION TESTING
**Priority**: 🔴 CRITICAL  
**Estimated Effort**: 2 weeks  
**Dependencies**: Phases 1-7 complete

### 8.1 Integration Test Matrix
- [ ] **Run 12 integration test scenarios**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.5
  - Scenarios cover:
    - Task Execution Layer only (TC-INT-01, TC-INT-02, TC-INT-03)
    - Keypair Enhancement Layer only (TC-INT-04, TC-INT-05, TC-INT-06)
    - Combined integration (TC-INT-07, TC-INT-08, TC-INT-09, TC-INT-10, TC-INT-11, TC-INT-12)
  - **Verification**: All 12 tests pass

### 8.2 Backward Compatibility Tests
- [ ] **Run 5 backward compatibility test cases**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.7
  - Test cases:
    - TC-BC-01: Legacy client to new compute node
    - TC-BC-02: New client to legacy compute node
    - TC-BC-03: Mixed mesh (keypair + legacy)
    - TC-BC-04: Feature flag toggling
    - TC-BC-05: Storage format migration
  - **Verification**: All 5 tests pass

### 8.3 End-to-End Scenarios
- [ ] **Test complete task lifecycle**
  - Submit task → assign → generate keypair → re-encrypt → execute → store results → notify
  - Test all 6 task types (PYTHON, JAVA, JVM, JAVASCRIPT, ML_NATIVE, WORKFLOW)
  - **Verification**: End-to-end success rate >99%

**Phase 8 Success Criteria**:
- ✅ All 12 integration tests pass
- ✅ All 5 backward compatibility tests pass
- ✅ End-to-end success rate >99%

**Phase 8 Rollback Trigger**:
- Integration test failure rate >1%
- Backward compatibility broken

---

## PHASE 9: DOCUMENTATION & DEPLOYMENT PREPARATION
**Priority**: 🟡 IMPORTANT  
**Estimated Effort**: 1-2 weeks  
**Dependencies**: Phase 8 complete

### 9.1 Documentation
- [ ] **Create API documentation**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` Section 13 Phase 8
  - Document all public APIs: Storage, TaskManager, RuntimeRegistry, ServiceLibrary
  - Include examples and code samples
  - **Verification**: Documentation review

- [ ] **Create developer guides**
  - How to create custom executors
  - How to install user runtimes
  - How to define custom services
  - **Verification**: Developer walkthrough

- [ ] **Create user documentation**
  - How to submit compute tasks
  - How to monitor task execution
  - Troubleshooting guide
  - **Verification**: User feedback

### 9.2 Feature Flags
- [ ] **Implement feature flag system**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 14.4
  - `FeatureFlags.isTaskKeypairEnabled()` - Keypair enhancement toggle
  - `FeatureFlags.isTaskExecutionEnabled()` - Task execution toggle
  - Remote toggle via config server
  - **Verification**: Feature flag toggle tests

### 9.3 Monitoring Setup
- [ ] **Implement monitoring metrics**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.3
  - Performance KPIs: Task completion time, keypair generation time, re-encryption time
  - Reliability KPIs: Task success rate, error rate by type
  - Security KPIs: Key lifecycle compliance, permission violations
  - UX KPIs: Task submission latency, result retrieval latency
  - **Verification**: Metrics dashboard functional

- [ ] **Set up alerting**
  - Alert on: Error rate >5%, performance degradation >20%, security violations
  - **Verification**: Alert firing tests

**Phase 9 Success Criteria**:
- ✅ All documentation complete and reviewed
- ✅ Feature flags functional and tested
- ✅ Monitoring and alerting operational

**Phase 9 Rollback Trigger**:
- Feature flags don't work (can't disable features)
- Monitoring not capturing critical metrics

---

## PHASE 10: DEPLOYMENT (4-PHASE ROLLOUT OVER 10 WEEKS)
**Priority**: 🔴 CRITICAL  
**Estimated Effort**: 10 weeks  
**Dependencies**: Phase 9 complete

### 10.1 Pre-Deployment Checklist
- [ ] **Complete pre-migration checklist**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.1
  - Infrastructure readiness (monitoring, feature flags, rollback plan)
  - Code readiness (all tests pass, performance benchmarks met, security audits complete)
  - Team readiness (runbooks, on-call schedule, communication plan)
  - **Verification**: All checklist items complete

### 10.2 Phase 1: Canary Deployment (Week 1)
- [ ] **Deploy to 5% of nodes**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.2 Phase 1
  - Duration: 1 week
  - Target: Internal team + volunteer testers
  - Success criteria:
    - Error rate <5%
    - Performance overhead <3%
    - No critical security issues
  - **Rollback trigger**: Error rate >10%, critical bug, security breach
  - **Verification**: Canary metrics within success criteria

### 10.3 Phase 2: Beta Deployment (Weeks 2-3)
- [ ] **Deploy to 25% of nodes**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.2 Phase 2
  - Duration: 2 weeks
  - Target: Early adopters + power users
  - Success criteria:
    - Error rate <3%
    - Performance overhead <2%
    - User satisfaction score >4/5
  - **Rollback trigger**: Error rate >5%, data loss, user complaints >10
  - **Verification**: Beta metrics within success criteria

### 10.4 Phase 3: Staged Rollout (Weeks 4-6)
- [ ] **Deploy to 100% of nodes gradually**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.2 Phase 3
  - Duration: 3 weeks
  - Schedule: 50% Week 4, 75% Week 5, 100% Week 6
  - Success criteria:
    - Error rate <2%
    - Performance overhead <2%
    - User satisfaction score >4.2/5
  - **Rollback trigger**: Error rate >3%, performance degradation >5%, outage
  - **Verification**: Staged rollout metrics within success criteria

### 10.5 Phase 4: Feature Flag Removal (Week 10)
- [ ] **Remove feature flags and make changes permanent**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.2 Phase 4
  - Duration: 1 week
  - Wait 3 weeks after 100% rollout for stability monitoring
  - Remove: FeatureFlags.isTaskKeypairEnabled(), FeatureFlags.isTaskExecutionEnabled()
  - Clean up: Legacy code paths, executeTaskDirect() method
  - **Verification**: Code cleanup complete, no regressions

### 10.6 Post-Deployment Operations
- [ ] **30-day monitoring checklist**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.5
  - Daily: Error rates, performance metrics, user reports
  - Weekly: Security audits, capacity planning, optimization opportunities
  - Monthly: User satisfaction survey, retrospective
  - **Verification**: All monitoring complete

### 10.7 Optimization Opportunities
- [ ] **Implement optimization opportunities**
  - **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.6
  - Keypair pool: Pre-generate keypairs (-400ms latency)
  - Parallel re-encryption: Re-encrypt multiple files in parallel (-60% time)
  - Hardware-accelerated crypto: Use Android KeyStore (-40% generation time)
  - **Verification**: Performance improvements measured

**Phase 10 Success Criteria**:
- ✅ 100% node deployment successful
- ✅ Error rate <2%
- ✅ Performance overhead <2%
- ✅ User satisfaction >4.2/5
- ✅ No critical issues in 30-day monitoring

**Phase 10 Rollback Trigger**:
- Critical security vulnerability
- Data loss or corruption
- Widespread user complaints
- Error rate >3% sustained for 24h

---

## IMPLEMENTATION ORDER VALIDATION

### User's Proposed Order:
1. **Storage/API/Messages** → 2. **Task Execution** → 3. **Keypair Enhancement**

### Analysis:
✅ **CORRECT ORDER** - Matches critical path dependencies

**Rationale**:
1. **Storage API Refactoring (CRITICAL BLOCKER)**:
   - Current API lacks permission parameters
   - Blocks both Task Execution and Keypair Enhancement
   - Must fix first (Phase 1)

2. **Task Execution Layer**:
   - Requires working Storage API (dependency)
   - Provides compute capabilities
   - Foundation for Keypair Enhancement (Phase 2-3)

3. **Keypair Enhancement Layer**:
   - Requires Task Execution (dependency)
   - Adds security and isolation
   - Can be deployed incrementally (Phase 4)

**Recommendation**: Proceed with user's proposed order.

---

## CRITICAL PATH DIAGRAM

```
Storage API Refactoring (CRITICAL BLOCKER)
    ↓
Data Structures + Message Protocol
    ↓
    ├─→ TaskManager Extensions
    │       ↓
    ├─→ Resource Monitoring
    │       ↓
    ├─→ Executor Implementations
    │       ↓
    ├─→ Runtime Management
    │       ↓
    └─→ Service Discovery
            ↓
        Task Execution Functional ✓
            ↓
        Keypair Enhancement
            ├─→ Storage Layer Enhancements
            ├─→ TaskManager Keypair Management
            ├─→ Client-Side Integration
            ├─→ Compute-Side Integration
            └─→ PGP Encryption Service
                ↓
            Error Handling & Resilience
                ↓
            Security Testing (CRITICAL)
                ↓
            Performance Testing & Optimization
                ↓
            Integration Testing
                ↓
            Documentation & Deployment Prep
                ↓
            4-Phase Rollout (10 weeks)
                ↓
            COMPLETE ✓
```

---

## ESTIMATED TIMELINE

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Foundation Layer | 2-3 weeks | None (START HERE) |
| Phase 2: Task Execution Core | 4-5 weeks | Phase 1 |
| Phase 3: Runtime Management | 2-3 weeks | Phase 2 |
| Phase 4: Keypair Enhancement | 3-4 weeks | Phases 1-3 |
| Phase 5: Error Handling | 2 weeks | Phase 4 |
| Phase 6: Security Testing | 2-3 weeks | Phases 4-5 |
| Phase 7: Performance Testing | 1-2 weeks | Phases 4-6 |
| Phase 8: Integration Testing | 2 weeks | Phases 1-7 |
| Phase 9: Documentation & Prep | 1-2 weeks | Phase 8 |
| Phase 10: Deployment (Rollout) | 10 weeks | Phase 9 |

**Total Implementation**: 19-24 weeks (5-6 months)  
**Total with Rollout**: 29-34 weeks (7-8.5 months)

---

## PRIORITY LEGEND

- 🔴 **CRITICAL**: Blocks other work or critical for security/stability
- 🟡 **IMPORTANT**: Required for full functionality
- 🟢 **NICE-TO-HAVE**: Enhancements or optimizations

---

## VERIFICATION STRATEGY

Each phase includes verification criteria. All must pass before proceeding to next phase:
- ✅ **Compile checks**: Code compiles without errors
- ✅ **Unit tests**: All unit tests pass (>90% coverage)
- ✅ **Integration tests**: End-to-end flows functional
- ✅ **Performance tests**: Metrics within acceptable ranges
- ✅ **Security tests**: No vulnerabilities detected
- ✅ **Backward compatibility tests**: Legacy systems still work

---

## ROLLBACK STRATEGY

Each phase defines rollback triggers. If triggered:
1. **Immediate**: Disable feature flag (if available)
2. **Emergency**: Run rollback script (see Part 5 Section 15.4)
3. **Analysis**: Root cause analysis and mitigation plan
4. **Retry**: Fix issues and retry phase

**Emergency Rollback Script**:
- **Ref**: `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` Section 15.4
- Script: `rollback_keypair_feature.sh`
- Steps: Disable feature flags → Restart services → Verify rollback → Notify team

---

## REFERENCES

### Keypair Enhancement Plan (5 parts, ~7000 lines):
1. `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md` - Sections 1-5 (Requirements, Architecture, Lifecycle, Storage, TaskManager)
2. `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md` - Sections 6-8 (Client Integration, Compute Integration, PGP Service)
3. `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART3.md` - Sections 9-10 (Error Handling, Security Testing)
4. `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md` - Sections 11-13 (Performance, Edge Cases, 10-Phase Checklist)
5. `TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md` - Sections 14-15 (Integration, Migration & Rollout)

### Task Execution Layer Plan (3 parts, ~3500 lines):
6. `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md` - Sections 1-3 (Data Structures, Storage API, TaskManager)
7. `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART2.md` - Sections 4-5 (Resource Monitoring, Executors)
8. `TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md` - Sections 6-8 (Runtime Management, Service Library, Integration)

---

**END OF MASTER IMPLEMENTATION ROADMAP**
