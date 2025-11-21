# StrangersSafeComputeEngine.kt Refactoring Plan (Interim)

**Date:** November 21, 2025
**Status:** Actionable, with explicit TODOs for distributed service logic

---

## Refactoring Breakdown

### 1. StrangersSafeComputeEngine.kt (Orchestrator)
- Main entry point for strangers-safe compute operations.
- Orchestrates container creation, code execution, and integrates all subcomponents.
- Singleton instance logic.
- High-level APIs for task execution and resource management.

---

### 2. MicroContainer.kt
- Ultra-lightweight container abstraction and communication mechanisms.
- `MicroContainer`, `ResourceLimits`, `CommunicationPipe`.

---

### 3. StrangersTrustEngine.kt
- Cryptographic verification, zero-knowledge proofs, and trust model logic.
- `StrangersTrustEngine`, `ExecutionProof`, `ExecutionTrace`.

---

### 4. ComputeEconomics.kt
- Economic incentives, reputation, and reward calculations for compute tasks.
- `ComputeEconomics`, `ComputeReward`.

---

### 5. ContainerExecution.kt
- Container execution, monitoring, and resource usage extraction.
- `executeInContainer`, `monitorContainerExecution`, `ExecutionResult`.

---

### 6. CodeVerification.kt
- Code bundle signature verification and hash calculation.
- `CodeVerification`, `verifyCodeBundle`, `calculateHash`.

---

### 7. DistributedServiceLibrary.kt
- Unified `ServiceLibraryEntry` model: incorporates all runtime and distribution fields.
- Subclasses for Python, Java, Hybrid, NDK, Workflow, etc.
- Fields for cryptographic verification, distribution metadata, audit reports, resource requirements, manifest, execution profile, inputs, outputs, capabilities.
- **TODO:** I2P registry, torrent distribution, and trust logic. (Leave explicit TODOs at relevant places in the code.)

---

### 8. ResourceMonitoring.kt
- Resource metrics and monitoring for running containers.
- Resource metrics extraction methods, container ID to PID mapping.

---

## Key Architectural Notes
- Unify all service entry logic into a single, comprehensive `ServiceLibraryEntry` class (in DistributedServiceLibrary.kt).
- Remove all legacy `LibraryEntry` definitions and update all references/imports to use the new model.
- Ensure all modules (compute, distribution, trust, economics, execution) reference the unified service entry for both runtime and distributed workflows.
- **TODOs:** For distributed service logic (I2P registry, torrent distribution, trust), leave clear TODO comments and document requirements for future implementation.

---

## Outstanding Questions / Uncertainties
- Are there any legacy usages of `LibraryEntry` that require special migration logic?

**Answer: There should not be but you should check.

- Are there additional distributed service protocols (besides I2P/torrent) that need to be supported?

##Anser: We will in a future phase want to allow the user to add an entry to their own device library in a developer mode. so developers can test their task code locally.

- Should the trust logic be extensible for future cryptographic schemes?

##Answer: it sounds like a good idea but what is the complexity,  the impact on development and size of the app

- Is there a canonical location for resource requirements and audit reports, or should these always be part of `ServiceLibraryEntry`?

##Answer: The resource Requirements  and audit reports should be part of `ServiceLibraryEntry`
---

## Next Steps
- Begin modular refactor, leaving TODOs for distributed service logic.
- Document all TODOs and requirements for future implementation.
- Validate with tests and workflow scenarios after refactor.

---

**This plan is actionable and ready for staged implementation. All distributed service logic will be marked with TODOs and revisited in future iterations.**
