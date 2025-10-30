
# KNOWLEDGE-10302025.md

## Comprehensive Onboarding: Meshrabiya Distributed Storage, Compute, and Architecture (October 30, 2025)

This document provides a deep-dive onboarding for agents and developers working on the Meshrabiya mesh, distributed storage, and distributed compute subsystems. It covers strategic design decisions, architectural patterns, refactor rationale, and integration details to bring new contributors to full working knowledge.

---

## 1. Distributed Storage Architecture & Refactor

### 1.1. Storage Node Participation Toggle
- **Purpose:** Controls whether a node acts as a storage node (i.e., stores/replicates chunks for others, responds to storage/replica requests).
- **UI Integration:** Only the "Participate in Distributed Storage" toggle affects this role. Other toggles (mesh, gateway, compute) do not impact storage node responsibilities.
- **API Usage:**
	- `setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)` is the canonical method for toggling participation.
	- Internally, this may call `enableDistributedStorage()` or `disableDistributedStorage()`.
- **Event Routing:**
	- All mesh ecosystem events relevant to storage node responsibilities are routed through `MeshEcosystemListener`.
	- The listener checks participation state before invoking any storage node handler in `DistributedStorageManager`.
- **Separation of Concerns:**
	- Client capabilities (retrieval, download) remain available regardless of participation state.
	- Storage node logic is strictly gated by the toggle, ensuring no accidental role leakage.

### 1.2. Event Routing and Manager Responsibilities
- **MeshEcosystemListener:**
	- Centralizes all event routing for mesh ecosystem events (chunk transfer, storage node responses, replica responses, permission updates).
	- Checks participation state before calling storage node handlers.
	- Example: `routeStorageNodeResponse`, `routeChunkRetrievalResponse`, `onChunkTransfer` all require participation enabled.
- **DistributedStorageManager:**
	- Handles actual storage node logic (storing chunks, responding to requests, managing replication).
	- No redundant participation checks needed; only called when participation is enabled.
	- Event-driven design: manager reacts to events routed by the listener.

### 1.3. API and Helper Modules
- **MeshrabiyaApi:**
	- Unified interface for all mesh/gateway/storage/service actions.
	- Exposes participation toggles, status queries, and event registration.
- **Helper Modules:**
	- `MeshManagers`, `MeshUIBindings`, `MeshListeners`, `MeshStorageUI`, `MeshServiceLayerUI`, `MeshUtils` support modularity and separation of UI, logic, and event handling.

### 1.4. Strategic Improvements
- **Modularity:** All mesh/storage/service logic is routed through the API and helpers, supporting future extensibility and maintainability.
- **Role Clarity:** Strict separation between client and storage node roles, enforced at the event routing layer.
- **Testing:** Participation toggling is validated to ensure correct role behavior; client features remain unaffected when storage node responsibilities are disabled.

---

## 2. Distributed Compute Architecture & Refactor

### 2.1. Service Layer Participation
- **Purpose:** Controls whether a node contributes compute resources to the distributed service layer (e.g., task scheduling, ML inference, Python execution).
- **UI Integration:** Managed by its own toggle (`serviceLayerParticipationSwitch`), independent of storage participation.
- **Coordinator:**
	- `ServiceLayerCoordinator` manages service startup/shutdown, statistics, and error handling.
	- Periodic updates and health checks are performed to monitor service status.
- **Event Routing:**
	- Compute-related events are routed through dedicated listeners and coordinators, not mixed with storage node logic.

### 2.2. UI and Preferences
- **Preferences:** Participation state is persisted in shared preferences and reflected in UI status indicators.
- **Feedback:** UI provides clear feedback on service activation, errors, and statistics.
- **Separation:** Gateway and mesh toggles do not affect compute participation or storage node responsibilities.

---

## 3. Meshrabiya API, Event Routing, and Architecture

### 3.1. Unified API Design
- **MeshrabiyaApi:**
	- Centralizes all control and query operations for mesh, gateway, storage, and service layers.
	- Exposes event registration for UI/control layers.
	- Ensures all logic is accessible via a single, maintainable interface.

### 3.2. Event-Driven Logic
- **Listeners:**
	- `MeshEcosystemListener` and other listeners route events to appropriate managers based on participation state and role.
- **Decoupling:**
	- Event routing is decoupled from business logic, supporting easier testing and future refactoring.

### 3.3. Helper Modules and UI Bindings
- **Purpose:**
	- Support clean separation of UI, event handling, and business logic.
	- Make onboarding and future changes easier by isolating concerns.

---

## 4. UI Integration and Wiring

### 4.1. Storage Participation Toggle
- **Integration Points:**
	- On initialization and toggle change, the UI should call `MeshrabiyaApi.setStorageParticipationEnabled` to update participation state.
	- UI updates preferences and state, but only the storage participation toggle affects storage node responsibilities.
- **Other Toggles:**
	- Gateway, mesh, and compute toggles are correctly separated and do not affect storage node logic.

### 4.2. Error Handling and Feedback
- **UI provides feedback** for service activation, errors, and statistics, supporting user understanding and debugging.

---

## 5. Testing, Validation, and Best Practices

### 5.1. Role Behavior Validation
- **Test Cases:**
	- Toggling distributed storage participation enables/disables storage node responsibilities.
	- Client features (retrieval, download) remain available regardless of participation state.
- **No Redundant Logic:**
	- Event routing and API usage are streamlined to avoid duplicate checks and logic.

### 5.2. Maintainability and Extensibility
- **Modular Design:**
	- Helper modules and unified API support future refactoring and onboarding.
- **Documentation:**
	- Inline documentation and knowledge docs (like this one) are maintained to support new contributors.

---

## 6. Strategic Design Decisions (Summary)

- **Centralized Event Routing:** All mesh ecosystem events are routed through listeners that enforce participation and role logic.
- **Strict Role Separation:** Only the storage participation toggle affects storage node responsibilities; other toggles are independent.
- **Unified API:** All control and query operations are exposed via `MeshrabiyaApi` for maintainability.
- **Modular Helper Files:** UI, event handling, and business logic are separated for clarity and extensibility.
- **Testing and Validation:** Role behavior and event routing are validated to ensure correct operation and future-proofing.

---

**Onboarding Guidance:**
Any new agent or developer should:
1. Review this document and the inline documentation in key files (`MeshEcosystemListener`, `DistributedStorageManager`, `MeshrabiyaApi`, UI fragments).
2. Understand the separation of roles and the event-driven architecture.
3. Use the unified API and helper modules for all new features and refactors.
4. Validate changes with role toggling and event routing tests.
5. Update this knowledge doc and inline documentation with any new strategic decisions or patterns.
