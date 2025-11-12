# MESH_REFACTOR_PLAN.md

## Key Observations

- **Circular Dependencies:**  
  Mesh objects such as `AndroidVirtualNode`, `MeshGossipService`, `MeshRoleManager`, `EmergentRoleManager`, `MeshStorageManager`, and `ReplicationManager` are tightly coupled, often referencing each other directly or via singleton patterns. This leads to hidden state, unclear ownership, and potential for instance mismatches.

- **Singleton Overuse:**  
  Most mesh-related classes use singletons for themselves and their dependencies, making lifecycle management and testing difficult.

- **Signature Complexity:**  
  Constructors and `getInstance` methods require many parameters, often duplicating context, DataStore, and executor services, leading to error-prone and hard-to-maintain code.

- **Ambiguous Role Management:**  
  Both `MeshRoleManager` and `EmergentRoleManager` handle aspects of node role assignment, fitness, and mesh intelligence, resulting in redundancy and confusion.

- **DataStore Propagation:**  
  Both Jetpack and custom DataStore instances are passed throughout the mesh stack, increasing API complexity and risk of inconsistency.

---

## Recommendations for Simplification

1. **Adopt a Top-Down Ownership Model:**  
   `AndroidVirtualNode` should be the orchestrator, instantiating and owning all mesh services and managers. All mesh operations should be routed through this node.

2. **Remove Singleton Patterns:**  
   Eliminate singletons for mesh objects. Use explicit dependency injection, passing required instances via constructors.

3. **Unify Role Management:**  
   Refactor all role and fitness logic into a single, enhanced `EmergentRoleManager`. Remove `MeshRoleManager` unless unique, essential logic is identified and migrated.

4. **Centralize DataStore Management:**  
   Decide on a single DataStore abstraction and provide it from the top level. Pass it explicitly to all classes that need it.

5. **Clarify and Document Object Relationships:**  
   Make ownership and dependency relationships explicit in code and documentation.

6. **Incremental Refactor with Logging and Testing:**  
   Refactor in phases, updating INTERIM_COMMIT_LOG.md and KNOWLEDGE docs at each step. Use output logging and automated tests to verify correctness.

---

## 1. Relationship and Dependency Review

### Current State

- `AndroidVirtualNode` is the core mesh node abstraction, but other services (e.g., `MeshGossipService`, `MeshStorageManager`, `ReplicationManager`) instantiate their own mesh objects, leading to multiple instances and unclear mesh topology.
- `MeshGossipService` both requires and creates its own `AndroidVirtualNode`, causing possible instance mismatches.
- `MeshRoleManager` and `EmergentRoleManager` both handle aspects of role assignment, fitness, and mesh intelligence.
- DataStore instances are inconsistently managed and propagated.

### Desired State

- **Single Source of Truth:**  
  Only one instance of `AndroidVirtualNode` per mesh context, which owns all mesh services and managers.
- **Explicit Dependency Injection:**  
  All dependencies are passed via constructors, not created internally.
- **Unified Role Management:**  
  All role, fitness, and mesh intelligence logic is handled by a single `EmergentRoleManager`.
- **Centralized DataStore:**  
  DataStore is instantiated at the top level and passed down as needed.

---

## 2. Design Evaluation and Refactor Opportunities

### Problems

- **Tight Coupling:**  
  Each component is tightly coupled to concrete implementations, making refactoring, testing, and extension difficult.
- **Hidden State:**  
  Singleton usage hides state and makes it hard to reason about object lifecycles.
- **Circularity:**  
  The mesh of dependencies is nearly circular, a classic anti-pattern.
- **Signature Bloat:**  
  Passing both Jetpack and custom DataStore everywhere is error-prone and makes APIs harder to use.
- **Redundant Role Management:**  
  Having both `MeshRoleManager` and `EmergentRoleManager` is confusing and violates the single-responsibility principle.

### Opportunities

- **Adopt Dependency Injection:**  
  Use constructor injection for all dependencies, possibly with a DI framework.
- **Remove Singletons:**  
  Only use singletons for truly global, stateless services.
- **Unify Role Management:**  
  Refactor all role and fitness logic into `EmergentRoleManager`.
- **Centralize DataStore Management:**  
  Provide DataStore from the top level and pass it explicitly.
- **Clarify Ownership and Lifecycle:**  
  Make it clear which component owns which resource.

---

## 3. Critical Evaluation and Architectural Recommendation

### Top-Down Ownership

- `AndroidVirtualNode` should be the top-level coordinator, instantiating and owning all mesh services and managers.
- `MeshGossipService` should be instantiated by `AndroidVirtualNode` and should not create its own node.
- `EmergentRoleManager` should be instantiated and owned by `AndroidVirtualNode`, providing all role and fitness logic.

### Unified Role Management

- All role assignment, fitness calculation, and mesh intelligence should be handled by `EmergentRoleManager`.
- Any unique or superior logic in `MeshRoleManager` (e.g., centrality calculation, choke point detection, neighbor fitness tracking) should be migrated to `EmergentRoleManager`.
- Remove `MeshRoleManager` unless essential, unique logic is identified.

### Centralized DataStore

- DataStore should be instantiated at the top level and passed to all components that need it.
- Avoid passing both Jetpack and custom DataStore unless absolutely necessary.

### Explicit Wiring

- All dependencies should be passed via constructor injection.
- No internal singleton creation.

### Testability and Extensibility

- Each component can be tested in isolation.
- New services can be added as new components owned by the node.

---

## 4. Summary Table: Object Relationships

| Object                | Needs/Uses                | Should Accept As Parameter         | Should NOT Create Internally      |
|-----------------------|---------------------------|------------------------------------|-----------------------------------|
| AndroidVirtualNode    | Context, DataStore        | Context, DataStore                 | MeshRoleManager, EmergentRoleMgr  |
| MeshGossipService     | VirtualNode, DataStore    | VirtualNode, DataStore             | AndroidVirtualNode                |
| EmergentRoleManager   | VirtualNode, Context, UserConfig | VirtualNode, Context, UserConfig |                                   |
| MeshStorageManager    | MeshGossipService, DataStore | MeshGossipService, DataStore      | VirtualNode, MeshRoleManager      |
| ReplicationManager    | MeshGossipService, DataStore | MeshGossipService, DataStore      | VirtualNode, MeshRoleManager      |

---

## 5. Conclusion

- The current design is not best practice due to excessive singleton use, circular dependencies, and complex signatures.
- Refactoring to use dependency injection, removing singletons, and centralizing DataStore management will greatly improve maintainability, testability, and clarity.
- Node role management should be a single process, based on user config, system state, and mesh intelligence.
- `MeshRoleManager` is redundant if `EmergentRoleManager` is implemented as described.
- Migrate any unique logic from `MeshRoleManager` to `EmergentRoleManager` and remove the former.
- All mesh-related objects should be constructed at the top level and passed down, not created as singletons internally.

---

## 6. Detailed Evaluation: EmergentRoleManager and MeshRoleManager Refactor

### Analysis

- **MeshRoleManager** currently handles:
  - Fitness calculation (signal, battery, client count)
  - Static role assignment (`MESH_NODE`, `CLIENT`, `BRIDGE`)
  - Centrality and choke point detection
  - Neighbor fitness tracking (partially implemented)
- **EmergentRoleManager** should handle:
  - All of the above, plus:
    - User configuration (storage, compute, gateway participation)
    - Dynamic system state (fitness, mesh intelligence)
    - Role transitions and mesh-wide intelligence

### Plan for Refactoring

#### Phase 1: Audit and Migration

- **Files Involved:**
  - `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRoleManager.kt`
  - `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`
  - All files referencing `MeshRoleManager` (search for usages)

- **Steps:**
  1. Audit all methods and properties in `MeshRoleManager`.
  2. Identify unique or superior logic (e.g., centrality, choke point, neighbor fitness).
  3. Migrate this logic into `EmergentRoleManager`, ensuring all APIs are preserved or improved.
  4. Update all references to use `EmergentRoleManager` for role and fitness queries/updates.

#### Phase 2: Remove MeshRoleManager

- **Files Involved:**
  - All files referencing `MeshRoleManager`
  - Project build files (to remove from dependencies if needed)

- **Steps:**
  1. Remove `MeshRoleManager` class and related files.
  2. Remove all references and update imports/usages.
  3. Run full build and tests to verify correctness.

#### Phase 3: Centralize and Simplify Construction

- **Files Involved:**
  - `AndroidVirtualNode.kt`
  - `MeshGossipService.kt`
  - `MeshStorageManager.kt`
  - `ReplicationManager.kt`
  - Any DI or top-level wiring code

- **Steps:**
  1. Refactor all mesh objects to be constructed at the top level and passed down.
  2. Remove all singleton patterns from mesh objects.
  3. Centralize DataStore instantiation and pass it explicitly.
  4. Update INTERIM_COMMIT_LOG.md and KNOWLEDGE docs at each step.

#### Phase 4: Documentation and Testing

- **Files Involved:**
  - `AGENTS.md`
  - `AI_RULES.md`
  - `INTERIM_COMMIT_LOG.md`
  - Today's `KNOWLEDGE-MMDDYYYY.md`

- **Steps:**
  1. Document all changes, reasoning, and new rules.
  2. Ensure all tests pass and update documentation as needed.

---

## 7. Next Steps

- Continue to add details to this plan as further investigation and review are completed.
- Once the plan is finalized, proceed with the refactoring in the phases outlined above, ensuring all changes are logged and tested according to project protocols.

---
---

## 8. Ownership and Integration of EmergentRoleManager

### Assessment: EmergentRoleManager Should Be Owned by AndroidVirtualNode

**Summary:**  
After reviewing the architecture and integration patterns, it is clear that `EmergentRoleManager` should be instantiated and owned exclusively by `AndroidVirtualNode`. This ensures a single source of truth for node role management, eliminates hidden state and singleton misuse, and clarifies the lifecycle and dependency graph for all mesh-related services.

**Rationale:**
- `AndroidVirtualNode` is the top-level mesh node abstraction and should coordinate all mesh services and managers.
- `EmergentRoleManager` is responsible for evaluating, planning, and tracking node roles and mesh intelligence, but should not perform side-effect actions (such as activating gateway routing or announcing capabilities) directly.
- All actions based on role changes should be handled by `AndroidVirtualNode` (or a dedicated coordinator), which observes role changes via `StateFlow` or event/callback mechanisms from `EmergentRoleManager`.
- This separation of concerns improves maintainability, testability, and extensibility, and prevents circular dependencies.

**Implementation Plan:**
- Refactor all code so that only `AndroidVirtualNode` instantiates and owns `EmergentRoleManager`.
- Remove all singleton patterns and direct instantiations of `EmergentRoleManager` from other classes.
- Expose role state and intelligence from `EmergentRoleManager` via `StateFlow` or events.
- Move all side-effect actions (e.g., gateway activation, announcements) out of `EmergentRoleManager` and into `AndroidVirtualNode` or a coordinator class that observes role changes.
- Update all usages to access `EmergentRoleManager` via the owning `AndroidVirtualNode` instance.

**Files Involved:**
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`
- All files referencing or instantiating `EmergentRoleManager`

**Summary Table: Ownership and Integration**

| Component              | Instantiates EmergentRoleManager | Should Own | Should Observe/React to Role Changes |
|------------------------|:-------------------------------:|:----------:|:------------------------------------:|
| AndroidVirtualNode     | Yes                             | Yes        | Yes                                 |
| MeshGossipService      | No (should not)                 | No         | No                                  |
| MeshStorageManager     | No (should not)                 | No         | No                                  |
| ReplicationManager     | No (should not)                 | No         | No                                  |

**Conclusion:**  
This approach ensures clear separation of concerns, explicit ownership, and robust, maintainable mesh architecture. All role management logic is centralized in `EmergentRoleManager`, while all side-effect actions are coordinated by `AndroidVirtualNode`.

---
---

## 9. Actionable Plan: EmergentRoleManager Ownership and Integration Refactor

### Objective
Ensure `EmergentRoleManager` is instantiated and owned exclusively by `AndroidVirtualNode`, and that all side-effect actions based on role changes are handled outside of `EmergentRoleManager`. All other mesh components must access role management and intelligence only via the `AndroidVirtualNode`'s reference to `EmergentRoleManager`.

---

### Step-by-Step Refactor Plan

#### **Phase 1: Audit and Preparation**

1. **Identify All Instantiations and Usages**
   - Search the codebase for all instantiations of `EmergentRoleManager` (including singleton `getInstance` calls).
   - List all files and classes that reference or use `EmergentRoleManager` directly.

2. **Review Side-Effect Logic**
   - In `EmergentRoleManager`, identify all methods that perform side-effect actions (e.g., activating gateway routing, announcing capabilities, updating mesh services).
   - Document these methods and their triggers.

---

#### **Phase 2: Refactor Ownership**

1. **AndroidVirtualNode Construction**
   - Refactor `AndroidVirtualNode` to instantiate and own a single instance of `EmergentRoleManager` as a property.
   - Pass all required dependencies (context, user config, virtual node reference, etc.) via constructor.

2. **Remove Singleton Patterns**
   - Remove all singleton `getInstance` patterns and static accessors for `EmergentRoleManager`.
   - Ensure no other class instantiates or owns `EmergentRoleManager`.

3. **Update All References**
   - Update all mesh components (e.g., `MeshGossipService`, `MeshStorageManager`, `ReplicationManager`) to access `EmergentRoleManager` only via their reference to `AndroidVirtualNode`.
   - If a component needs role information, require a reference to `AndroidVirtualNode` or pass the relevant data as needed.

---

#### **Phase 3: Decouple Side-Effect Actions**

1. **Move Side-Effect Logic**
   - Refactor all side-effect actions (e.g., gateway activation, mesh announcements) out of `EmergentRoleManager`.
   - Implement these actions in `AndroidVirtualNode` or a dedicated coordinator class.

2. **Signal Role Changes**
   - Ensure `EmergentRoleManager` exposes role changes and mesh intelligence updates via `StateFlow`, callback, or event mechanism.
   - In `AndroidVirtualNode`, observe these signals and trigger the appropriate side-effect actions.

---

#### **Phase 4: Testing and Validation**

1. **Unit and Integration Tests**
   - Update or add tests to verify that:
     - Only `AndroidVirtualNode` owns and instantiates `EmergentRoleManager`.
     - All side-effect actions are triggered correctly in response to role changes.
     - No other component directly instantiates or manipulates `EmergentRoleManager`.

2. **Manual Verification**
   - Manually inspect all files previously referencing `EmergentRoleManager` to ensure compliance.
   - Run full build and test suite, using output logging as per AGENTS.md protocols.

---

#### **Phase 5: Documentation and Logging**

1. **Update Documentation**
   - Update `AGENTS.md`, `AI_RULES.md`, and today's `KNOWLEDGE-MMDDYYYY.md` to reflect the new ownership and integration pattern.
   - Document the rationale, implementation details, and usage examples.

2. **Interim Commit Logging**
   - Log all changes in `INTERIM_COMMIT_LOG.md` with details of files changed, objectives accomplished, and any TODOs generated or satisfied.

---

### Files Involved

- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/AndroidVirtualNode.kt`
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`
- All files referencing or instantiating `EmergentRoleManager`
- `AGENTS.md`, `AI_RULES.md`, `INTERIM_COMMIT_LOG.md`, `KNOWLEDGE-MMDDYYYY.md`

---

### Summary

This plan ensures a single, explicit ownership model for `EmergentRoleManager`, clear separation of concerns, and robust, maintainable mesh architecture. All role management logic is centralized, and all side-effect actions are coordinated by `AndroidVirtualNode` or a dedicated coordinator, in accordance with project protocols.

---
Elements Worth Preserving from the Less Robust (ReplicationManager) Implementation
Direct Replica Count Query:
The explicit method to query the replica count for a chunk (queryReplicaCount) is simple and useful for diagnostics or manual enforcement.

Manual Replication Trigger:
The ability to manually trigger replication for a specific file or chunk can be useful for admin tools or recovery scenarios.

Candidate Node Selection by Latency:
Sorting candidate nodes by latency (in addition to available space) could improve performance and is a detail worth integrating.

Separation of Concerns:
The focused nature of ReplicationManager makes it easier to reason about and test replication logic in isolation.

