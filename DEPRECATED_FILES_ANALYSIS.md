# DEPRECATED FILES ANALYSIS
**Date:** December 4, 2025  
**Analysis Scope:** Assess if files are utilized in canonical workflows or can be deprecated

---

## ANALYSIS RESULTS

### 1. **ComputeSideTaskHandler.kt** - ❌ DEPRECATED (NOT USED)

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/ComputeSideTaskHandler.kt`

**Stated Purpose:**
- Compute-side task assignment handler with keypair generation
- Receives TASK_ASSIGNMENT message from scheduler
- Generates per-task keypair
- Sends TASK_SCHEDULED message with task public key back to scheduler

**Canonical Workflow Analysis:**
- **NOT CALLED** by any active code (grep found ZERO usages)
- References `TaskScheduledMessage` which doesn't exist in current codebase
- References deprecated scheduler-based workflow
- Has compilation errors: `Unresolved reference 'generateTaskKeypair'`, `Unresolved reference 'getTaskPrivateKey'`

**Actual Canonical Flow:**
- TaskManager handles task lifecycle (no scheduler)
- TaskManager.addTask() generates RSA keypair directly
- No TASK_SCHEDULED message in current architecture

**Verdict:** ✅ **SAFE TO DEPRECATE**
- Belongs to old scheduler-based architecture
- Replaced by TaskManager direct workflow
- Zero active references

---

### 2. **ContainerExecution.kt** - ❌ DEPRECATED (NOT USED)

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/ContainerExecution.kt`

**Stated Purpose:**
- Container execution, monitoring, and resource usage extraction
- Executes code in `MicroContainer` isolation
- Extracts resource metrics

**Canonical Workflow Analysis:**
- **NOT CALLED** by any active code (grep found ZERO usages outside self)
- References `MicroContainer` which is not used in canonical flow
- Has compilation errors: Wrong argument types for ExecutionResult
- Contains commented-out code and stub implementations

**Actual Canonical Flow:**
- JVMExecutor uses URLClassLoader + filesystem directories (no containers)
- JSExecutor uses Rhino engine (no containers)
- MLNativeExecutor uses TensorFlow Lite Interpreter (no containers)

**Verdict:** ✅ **SAFE TO DEPRECATE**
- No container-based execution in canonical workflow
- All executors use language-specific isolation
- Zero active references

---

### 3. **MLKitCustomWrapper.kt** - ⚠️ PARTIALLY DEPRECATED

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/ml/MLKitCustomWrapper.kt`

**Stated Purpose:**
- Wrapper for Google ML Kit custom model inference
- Provides API for loading and running custom TensorFlow Lite models

**Canonical Workflow Analysis:**
- **NOT CALLED** by active compute execution flow
- MLNativeExecutor uses TensorFlow Lite Interpreter directly
- Has compilation errors: `Unresolved reference 'FileChannel'`, wrong argument types

**BUT:**
- May be used for ML capability detection in EmergentRoleManager
- May be referenced by fitness score calculation
- Need to check if used for service announcements

**Verdict:** ⚠️ **NEEDS VERIFICATION**
- Check if EmergentRoleManager uses it for ML capabilities
- If only for capability detection, keep but fix
- If unused, deprecate

---

### 4. **MobileServiceSecurity.kt** - ⚠️ NEEDS VERIFICATION

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/security/MobileServiceSecurity.kt`

**Canonical Workflow Analysis:**
- **NO IMPORTS FOUND** in other files (grep returned zero)
- Has compilation errors: Missing required parameters (minRAMMB, preferredRAMMB, cpuIntensity)
- May contain security utilities or service capability definitions

**BUT:**
- Security-related code should be carefully reviewed before deprecation
- May define ServiceEntry or capability constants used elsewhere
- File name suggests it might be infrastructure

**Verdict:** ⚠️ **NEEDS VERIFICATION**
- Review file contents to check for:
  - Service capability definitions
  - Security utilities used by reflection/dynamic loading
  - Constants referenced by string literals
- If truly unused, deprecate
- If contains needed definitions, extract to active file

---

### 5. **StagedSyncManager.kt** - ⚠️ ACTIVE BUT HAS ERRORS

**Location:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/StagedSyncManager.kt`

**Error:**
```
'when' expression must be exhaustive. Add the 'is Conflict' branch or an 'else' branch.
```

**Canonical Workflow Analysis:**
- Part of distributed storage subsystem
- Likely used by DistributedStorageClient/Server for sync operations
- NOT directly called by compute workflow, but supports storage

**Verdict:** ⚠️ **ACTIVE - NEEDS FIX, NOT DEPRECATED**
- Part of active distributed storage system
- Fix the `when` expression exhaustiveness
- Keep file, fix compilation error

---

## SUMMARY

| File | Status | Action |
|------|--------|--------|
| ComputeSideTaskHandler.kt | ❌ DEPRECATED | Delete - zero usages, old scheduler architecture |
| ContainerExecution.kt | ❌ DEPRECATED | Delete - zero usages, no container execution |
| MLKitCustomWrapper.kt | ⚠️ VERIFY | Check EmergentRoleManager usage, then decide |
| MobileServiceSecurity.kt | ⚠️ VERIFY | Review for constants/definitions, may extract or delete |
| StagedSyncManager.kt | ✅ ACTIVE | Fix `when` exhaustiveness, keep file |

---

## RECOMMENDED ACTIONS

### Immediate (Safe Deletions):
1. Delete `ComputeSideTaskHandler.kt` - old scheduler workflow
2. Delete `ContainerExecution.kt` - no container execution in use

### Verification Needed:
3. Check `MLKitCustomWrapper.kt` usage in EmergentRoleManager for ML capability detection
4. Review `MobileServiceSecurity.kt` for service capability definitions or constants

### Fix Required:
5. Fix `StagedSyncManager.kt` when expression exhaustiveness error

---

## CANONICAL WORKFLOW REFERENCE

**Task Execution Flow (Verified):**
```
DistributedComputeClient.processTaskRequest()
  ↓ broadcast
DistributedComputeServer.handleIncomingComputeTaskRequest()
  ↓
TaskManager.addTask() [generates RSA keypair]
  ↓
TaskExecutionCoordinator.executeTask()
  ↓
JVMExecutor.execute() [URLClassLoader + SecurityManager]
JSExecutor.execute() [Rhino engine]
MLNativeExecutor.execute() [TensorFlow Lite Interpreter]
```

**No Usage Of:**
- TaskScheduler (deprecated)
- ComputeSideTaskHandler (deprecated)
- MicroContainer (deprecated)
- ContainerExecution (deprecated)

---

**Generated:** December 4, 2025  
**Method:** Grep analysis + TASK_EXECUTION_FLOW_ANALYSIS.md review
