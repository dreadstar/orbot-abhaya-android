# STORAGE_LIFECYCLE.md

## Purpose
This document defines the complete lifecycle for distributed file storage and replication in the orbot-abhaya-android project. It is intended for developers and AI agents to guide implementation, testing, and validation of all related features. All test cases and agent protocols must reference this document for correctness.

---

## 1. File Addition & Drop Folder Monitoring

- **User selects a drop folder** in the Mesh UI ([MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)).
- **Drop folder watcher** ([MeshStorageManager.kt](app/src/main/java/org/torproject/android/mesh/MeshStorageManager.kt)) is started only after selection.
- When a **new file is added** to the drop folder, the watcher triggers the storage lifecycle.

---

## 2. Storage Node Discovery (Broadcast Request)

- The client node **broadcasts a Storage Node Request** to the mesh network using gossip protocol ([MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)).
- The request includes:
  - File size
  - File name
  - Any relevant metadata for fitness evaluation

---

## 3. Candidate Node Reception & Response

- **All nodes** on the mesh receive the broadcast.
- Each candidate node:
  - Checks if it is a **storage node** ([EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt)).
  - Evaluates **system state** (thermal, battery, etc.) and **available disk space**.
  - If eligible, **responds** with:
    - Available space
    - System state
    - Node ID
    - Mesh URL for storage
    - Latency estimate
    - Fitness score

---

## 4. Client Node Response Handling & Candidate Selection

- The client node **waits for responses** within a timeout window.
- If **no responses** are received:
  - UI alerts the user.
  - The client retries after a delay.
- If **responses are received**:
  - The client selects the **best candidate** based on:
    - Sufficient available space
    - Healthy system state
    - Lowest latency
    - Highest fitness score
  - If **none are suitable** (e.g., insufficient space), UI alerts the user and retries after a delay.

---

## 5. File Transfer

- The client node **initiates file transfer** to the selected storage node using mesh protocols ([MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)).
- Transfer is chunked, reliable, and confirmed by completion notification.

---

## 6. Storage Node File Reception & Completion Notification

- The storage node **receives the file** and stores it in its shared storage area ([OrbotService.kt](orbotservice/src/main/java/org/torproject/android/service/OrbotService.kt)).
- The storage node **generates a file identifier** (anonymized, suitable for cloud-style sharing and replication).
- The storage node **sends a completion notification** to the client node, including the file identifier.

---

## 7. Client Node UI Update

- Upon receiving completion notification, the client node:
  - Updates the UI ([MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)) to indicate successful storage and display the file identifier.
  - Marks the file as replicated to distributed storage.

---

## 8. Replication Process

- The storage node **initiates replication** of the file to other storage nodes ([ReplicationManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/ReplicationManager.kt)).
- Replication uses a **broadcast request** similar to the initial storage process.
- Each candidate node:
  - Checks if it already stores the file (using the file identifier).
  - Evaluates eligibility as in the initial storage process.
- Replication continues until the **desired replica count (X)** is reached.
  - X is configurable ([AppSettings.kt](app/src/main/java/org/torproject/android/AppSettings.kt)).
  - Before replicating, each node queries the mesh for the current replica count.
  - Replication stops when X or more replicas are confirmed.

---

## 9. Replica Count Query

- Any node can **query the mesh** for the number of replicas of a given file identifier.
- The query is broadcast, and all nodes respond if they store the file.
- The client or storage node uses this to determine if further replication is needed.

---

## 10. Error Handling & Retries

- All steps include robust error handling:
  - Timeouts and retries for broadcasts and transfers.
  - UI alerts for user awareness.
  - Logging for diagnostics.
- All failures are surfaced to the user and retried according to protocol.

---

## 11. Security & Privacy

- File identifiers are anonymized and do not reveal user identity.
- Transfers and replication use encrypted mesh channels.
- Only eligible nodes participate in storage and replication.

---

## 12. Test Case Guidance

- Test cases must cover:
  - Drop folder selection and watcher activation/deactivation.
  - Storage node discovery and candidate selection logic.
  - File transfer success and failure scenarios.
  - Completion notification and UI update.
  - Replication initiation, progress, and completion.
  - Replica count query and enforcement.
  - Error handling, retries, and user alerts.
  - Security and privacy compliance.

---

## 13. References

- [MeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/MeshFragment.kt)
- [MainActivity.kt](app/src/main/java/org/torproject/android/MainActivity.kt)
- [GatewayCapabilitiesManager.kt](app/src/main/java/org/torproject/android/GatewayCapabilitiesManager.kt)
- [MeshStorageManager.kt](app/src/main/java/org/torproject/android/mesh/MeshStorageManager.kt)
- [EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt)
- [MeshGossipService.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/MeshGossipService.kt)
- [OrbotService.kt](orbotservice/src/main/java/org/torproject/android/service/OrbotService.kt)
- [ReplicationManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/storage/ReplicationManager.kt)
- [AppSettings.kt](app/src/main/java/org/torproject/android/AppSettings.kt)

---

**This document is the canonical reference for distributed storage and replication lifecycle in the orbot-abhaya-android project. All development, agent operations, and test cases must comply with these protocols.**