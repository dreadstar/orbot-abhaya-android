# Meshrabiya Architecture: Current State (as of 2025-10-24)

## 1. Core Architectural Layers and Major Files

### A. Node & Network Layer
- **VirtualNode.kt** (abstract): Core mesh node logic, defines node state, message routing, and network operations.
- **AndroidVirtualNode.kt**: Android-specific implementation, orchestrates all mesh services, owns managers, and provides platform integration.
- **OriginatingMessageManager.kt**: Maintains mesh topology, neighbor discovery, message routing, and node health via originating messages and pings.

### B. Role & Intelligence Layer
- **EmergentRoleManager.kt**: Dynamic, event-driven role management (e.g., gateway, relay, participant) based on mesh state.
- **MeshRoleManager.kt**: Static or policy-based role management (to be merged with EmergentRoleManager).

### C. Gossip & Messaging Layer
- **MeshGossipService.kt**: Handles mesh-wide message dissemination, chunk transfer, and service discovery using gossip protocols.
- **MmcpMessageFactory.kt**, **MmcpMessage.kt**, **MmcpNodeAnnouncement.kt**: Message construction and parsing for mesh control and data messages.

### D. Storage & Replication Layer
- **DataStore.kt**: Local SQLite-backed storage for mesh files and chunks.
- **ReplicationManager.kt**: Ensures file/chunk replication across the mesh, enforces replica count, and manages redundancy.
- **MeshChunk.kt**, **MeshFile.kt**: Data models for storage objects.

### E. Service & Model Layer
- **ServiceAnnouncement.kt**, **ExecutionProfile.kt**, **DeviceCapabilities.kt**, **ResourceRequirements.kt**: Models for mesh services, node capabilities, and resource requirements.

### F. Utility & Support Layer
- **MeshrabiyaConstants.kt**: Centralized constants and settings (now includes migrated settings logic).
- **BetaTestLogger.kt**, **MNetLogger.kt**, **MNetLoggerStdout.kt**, **LogLine.kt**: Logging infrastructure.
- **ConnectivityMonitor.kt**: Monitors network state and connectivity.
- **Util/Ext**: Utility and extension functions for networking, serialization, and platform integration.

---

## 2. Key Interdependencies

- **AndroidVirtualNode** is the orchestrator: it owns or instantiates all managers and services, including `OriginatingMessageManager`, `EmergentRoleManager`, `MeshRoleManager`, `ReplicationManager`, and `DataStore`.
- **OriginatingMessageManager** is tightly coupled to the node’s network address, logger, and role managers. It maintains mesh topology, neighbor health, and is critical for routing and resilience.
- **MeshGossipService** depends on the node, role managers, and storage for message dissemination and chunk transfer.
- **ReplicationManager** depends on `DataStore`, `MeshGossipService`, and the node for enforcing replication policies.
- **EmergentRoleManager** and **MeshRoleManager** are both referenced by the node and gossip service for role assignment and mesh intelligence.
- **DataStore** is used by all services needing persistent state (replication, gossip, node).
- **Model and utility classes** are used throughout for data representation, logging, and support.

---

## 3. Comprehensive Architecture Diagram (Textual)

```
[MeshrabiyaConstants]      [Util/Ext]      [Logger/Monitor]
         |                     |                 |
         v                     v                 v
   [VirtualNode] <--- [AndroidVirtualNode] <--- [ConnectivityMonitor]
         |                |         |                |
         |                |         +--> [EmergentRoleManager]
         |                |         +--> [MeshRoleManager]
         |                |         +--> [OriginatingMessageManager]
         |                |         +--> [ReplicationManager]
         |                |         +--> [DataStore]
         |                |         +--> [MeshGossipService]
         |                |         |
         v                v         v
   [MeshGossipService] <----------> [ReplicationManager]
         |                |         |
         v                v         v
      [DataStore] <------ +-------> [MeshChunk/MeshFile]
         |
         v
   [Service/Model Layer]
```

**Legend:**
- **Arrows** indicate dependency or ownership.
- **AndroidVirtualNode** is the orchestrator, wiring all core services and managers.
- **OriginatingMessageManager** is central for mesh topology, neighbor health, and routing.
- **MeshGossipService** and **ReplicationManager** are tightly coupled to the node and storage.
- **DataStore** is the persistence layer, used by all services needing local state.
- **Logger/Monitor/Util/Ext** are used throughout for support.

---

## 4. File Purpose and Placement Summary

- **VirtualNode.kt**: Abstract base for all mesh nodes.
- **AndroidVirtualNode.kt**: Android-specific node, owns all managers/services.
- **OriginatingMessageManager.kt**: Maintains mesh topology, neighbor health, and routing.
- **EmergentRoleManager.kt**: Dynamic role assignment.
- **MeshRoleManager.kt**: Static role assignment.
- **MeshGossipService.kt**: Mesh-wide message dissemination and chunk transfer.
- **ReplicationManager.kt**: File/chunk replication and redundancy.
- **DataStore.kt**: Local persistent storage.
- **MeshChunk.kt**, **MeshFile.kt**: Data models for storage.
- **ServiceAnnouncement.kt**, **ExecutionProfile.kt**, **DeviceCapabilities.kt**, **ResourceRequirements.kt**: Service and capability models.
- **MeshrabiyaConstants.kt**: Centralized configuration and settings.
- **BetaTestLogger.kt**, **MNetLogger.kt**, **MNetLoggerStdout.kt**, **LogLine.kt**: Logging.
- **ConnectivityMonitor.kt**: Network state monitoring.
- **Util/Ext**: Support and extension functions.

---

**This document reflects the current architecture of the Meshrabiya library, including all non-test Java and Kotlin files and their interdependencies.**
