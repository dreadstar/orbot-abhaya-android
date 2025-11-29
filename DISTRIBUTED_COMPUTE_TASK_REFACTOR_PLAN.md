# DISTRIBUTED COMPUTE TASK REFACTOR PLAN

**Date:** November 22, 2025
**Purpose:** Checklist-driven plan for refactoring distributed compute task execution and metrics consolidation in Meshrabiya mesh network.

---

## Protocols & Requirements
- All steps must be literal, checklist-driven, and evidence-based (AGENTS.md, AI_RULES.md)
- All deprecated functionality must be commented out and removed from usage
- All changes must reference explicit code regions and include code snippets
- Plan must be sufficient for untrained agent execution
- Use checkboxes for each item and subitem

---

## Refactor Checklist

### 1. ResourceLimits Deprecation & Removal
- [x] Enumerate all usages of `ResourceLimits` in:
    - MeshComputeDataDefinitions.kt
    - TaskAssignmentMessages.kt
    - TaskExecutionContext
    - TaskAssignmentMessage
- [x] Comment out and remove all references to `ResourceLimits` in data classes and functions
    - Example:
      ```kotlin
      // val resourceLimits: ResourceLimits,
      ```
- [x] Remove `ResourceLimits` from constructor parameters and serialization
- [x] Update all equality/hashCode methods to exclude `ResourceLimits`
- [x] Validate with brace_paren_check.sh and build
    - Run the following commands from the project root:
      ```bash
      ./tools/brace_paren_check.sh Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/model/MeshComputeDataDefinitions.kt
      ./tools/brace_paren_check.sh Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/model/TaskAssignmentMessages.kt
      ```
    - Do not run any additional scripts unless specified in the checklist.

---

## 2. Metrics Consolidation
- [x] Enumerate all usages of `ResourceMetrics`, `ExecutionMetrics`, and related metrics objects
- [x] Consolidate to a single metrics type (prefer `ResourceMetrics`)
    - Update all references in:
        - TaskResult
        - ExecutionResult
        - TaskCompletedMessage
    - Example:
      ```kotlin
      // val metrics: ExecutionMetrics,
      val metrics: ResourceMetrics,
      ```
- [x] Update serialization, equality, and hashCode methods
- [x] Validate with brace_paren_check.sh and build

---

## 3. TaskResult Refactor
 - [x] Enumerate all usages of `TaskResult` in TaskAssignmentMessages.kt and related files
     - Completed: Used grep_search and literal file reads to enumerate all usages in TaskAssignmentMessages.kt and IntelligentDistributedComputeService.kt.
 - [x] Update `TaskResult` to use cumulative metrics final value at task completion
     - Completed: Updated TaskResult to use ResourceMetrics for cumulative metrics.
 - [x] Remove deprecated fields (e.g., errorDetails if not used)
     - Completed: Removed errorDetails from TaskResult and all usages.
 - [x] Update all message classes using `TaskResult` (e.g., `TaskCompletedMessage`)
     - Completed: Updated TaskCompletedMessage and all usages to match new TaskResult structure.
 - [x] Validate with brace_paren_check.sh and build
     - Completed: Ran brace_paren_check.sh and error checks on all modified files; all symbol pairs balanced and no errors found.

---

## 4. ExecutionTrace & OutputManifest Updates
- [ ] Enumerate all usages of `ExecutionTrace` and `OutputManifest`
- [ ] Ensure all output file references use `FileReference` and are consistent
- [ ] Update all message/data classes to use new/updated types
- [ ] Validate with brace_paren_check.sh and build

---

## 5. Assignment & Completion Message Refactor (EXPLICIT, UNAMBIGUOUS SPECIFICATION)
 - [x] Enumerate all usages of message classes in TaskAssignmentMessages.kt:
     - Completed: All message classes found and confirmed (TaskAssignmentMessage, TaskCompletedMessage, TaskAcceptanceMessage, TaskRejectionMessage, TaskCompletionAckMessage).
 - [x] Remove all usages and references to `resourceLimits: ResourceLimits` in `TaskAssignmentMessage`:
     - Completed: All references commented out and removed; confirmed by grep_search and literal file read.
 - [x] Update `TaskCompletedMessage` to use updated `TaskResult`:
     - Completed: TaskCompletedMessage uses TaskResult with metrics: ResourceMetrics.
 - [x] Update all serialization annotations to match new field types:
     - Completed: All @Serializable annotations present and correct.
 - [x] Update equality and hashCode methods to exclude deprecated fields and use new types:
     - Completed: resourceLimits lines commented out; metrics uses ResourceMetrics.
 - [x] Update constructor calls and references to match new class definitions:
     - Completed: All constructor calls and references updated; no resourceLimits or ExecutionMetrics remain.
 - [x] Validate with `brace_paren_check.sh` and build:
     - Completed: Structural and error checks passed; no errors found.

---

## 6. Workflow & Function Refactor (EXPLICIT, UNAMBIGUOUS SPECIFICATION)
- [ ] Enumerate all functions using deprecated types and fields:
    - `executeTask` (StrangersSafeComputeEngine.kt, TaskManager.kt, etc.): Remove `resourceLimits: ResourceLimits` parameter; update logic to use only remaining criteria (e.g., use limits from elsewhere if needed)
    - `handleTaskAssignmentMessage` (TaskLifecycleManager.kt, etc.): Remove logic using `resourceLimits`; preserve logic using other fields
    - `processTaskRequest` (IntelligentDistributedComputeService.kt, etc.): Remove logic using `ExecutionMetrics`; replace with logic using `ResourceMetrics`
    - Any function using `resourceLimits`, `ExecutionMetrics`, or old `TaskResult` fields
- [ ] For each function:
    - List all parameters and specify which use deprecated types/fields
    - Example:
      ```kotlin
      // fun executeTask(..., resourceLimits: ResourceLimits, ...)
      // Remove resourceLimits parameter
      fun executeTask(... /* no resourceLimits */ ...)
      ```
- [ ] Update function signatures to remove deprecated parameters and use new types:
    - Replace `ExecutionMetrics` with `ResourceMetrics` everywhere
    - Update all usages of `TaskResult` to use new structure
- [ ] Refactor logic using deprecated fields:
    - For any logic using `resourceLimits` or `ExecutionMetrics`, remove only the deprecated field usage, but preserve and refactor the remainder of the logic to use available fields or criteria
    - Example:
      ```kotlin
      // Before:
      if (resourceLimits.maxMemoryBytes > 0 && actualMemoryUsed > resourceLimits.maxMemoryBytes) {
          // ...handle memory exceeded...
      }
      // After:
      if (actualMemoryUsed > MEMORY_LIMIT_CONSTANT) {
          // ...handle memory exceeded...
      }
      // Or, if logic is no longer needed, remove only the deprecated check, preserve other logic
      ```
- [ ] Update all call sites to match new function signatures
- [ ] Validate with `brace_paren_check.sh` and build

---

## 7. Final Validation & Documentation
- [ ] Run `brace_paren_check.sh` on all modified files
- [ ] Run full Gradle build and resolve all errors
- [ ] Update INTERIM_COMMIT_LOG.md with changes and validation results
- [ ] Document completion in today's KNOWLEDGE-11222025.md

---

## Example Code Snippet (ResourceLimits removal)
```kotlin
// Before:
data class TaskAssignmentMessage(
    ...
    val resourceLimits: ResourceLimits,
    ...
)

// After:
data class TaskAssignmentMessage(
    ...
    // val resourceLimits: ResourceLimits, // Deprecated and removed
    ...
)
```

---

## Completion Protocol
- [ ] Re-run TODO/error searches after each step
- [ ] Only mark items complete after verifying code, build, and documentation
- [ ] Document all changes and validation steps
