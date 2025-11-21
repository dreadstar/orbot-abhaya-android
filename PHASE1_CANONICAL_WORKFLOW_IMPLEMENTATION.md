# Phase 1 Canonical Workflow Implementation - Complete

**Date**: January 9, 2025  
**Status**: ✅ COMPLETE  
**Files Modified**: 3 files  
**Lines Changed**: ~250 lines across all changes

---

## Overview

Phase 1 of the canonical workflow refactoring focused on updating data structures and adding encryption infrastructure to support the new storage, retrieval, and permission workflows. All ecosystem messages have been consolidated into a single file for consistency, and service keypair generation has been added for storage node encryption.

---

## Phase 1.1: Ecosystem Message Consolidation ✅

### Objective
Consolidate all ecosystem message data structures into `MeshEcosystemMessage.kt` for consistency and maintainability.

### Files Modified
- **MeshEcosystemMessage.kt** (746 lines)
  - Added 6 data class definitions inline (lines 15-85)
  - Updated imports to remove separate file references
  - All message types now in single file

### Data Structures Added/Updated

#### 1. StorageNodeRequest (10 fields)
```kotlin
data class StorageNodeRequest(
    val requestId: String,           // NEW: For request correlation
    val chunkId: String,
    val chunkIndex: Int,             // NEW: Which chunk in file
    val fileId: String,              // NEW: Parent file ID
    val desiredReplicas: Int,        // NEW: Target replica count
    val chunkSizeBytes: Long,        // NEW: Actual chunk size
    val replicaCount: Int,           // NEW: Current replica count
    val requiredSpace: Long,         // LEGACY: For backward compat
    val fileName: String,            // LEGACY: For backward compat
    val senderId: String             // LEGACY: For backward compat
)
```

**Serialization**: All 10 fields packed with MessagePack in order above

**Usage**: Broadcast by requester to discover storage nodes for chunk placement

#### 2. StorageNodeResponse (9 fields)
```kotlin
data class StorageNodeResponse(
    val nodeId: String,
    val availableSpace: Long,
    val totalStorageAllocated: Long, // NEW: Total storage this node allocated
    val systemState: Int,
    val url: String,
    val latency: Long,
    val fitnessScore: Float,
    val fileId: String,              // NEW: Correlation
    val servicePublicKey: ByteArray  // NEW: For chunk encryption
)
```

**Serialization**: Binary data (servicePublicKey) uses `packBinaryHeader() + writePayload()`

**Usage**: Response to StorageNodeRequest, includes public key for chunk encryption

#### 3. ChunkRetrievalQuery (2 fields)
```kotlin
data class ChunkRetrievalQuery(
    val fileId: String,
    val chunkIndexes: List<Int>?     // NEW: Optional targeted chunk list for retry
)
```

**Serialization**: Uses boolean flag pattern for optional field:
```kotlin
// Pack
if (chunkIndexes != null) {
    packBoolean(true)
    packArrayHeader(chunkIndexes.size)
    chunkIndexes.forEach { packInt(it) }
} else {
    packBoolean(false)
}

// Unpack
val hasChunkIndexes = unpackBoolean()
val chunkIndexes = if (hasChunkIndexes) {
    val size = unpackArrayHeader()
    (0 until size).map { unpackInt() }
} else null
```

**Usage**: Request chunks from storage nodes, optionally specify which chunks for retry

#### 4. ChunkRetrievalResponse, ReplicaQuery, ReplicaResponse
Kept existing structure - no changes needed.

### Files Cleaned Up
Moved to `.md` extension (preserved but not compiled):
- `StorageNodeRequest.kt.md`
- `StorageNodeResponse.kt.md`
- `ChunkRetrievalQuery.kt.md`
- `ChunkRetrievalResponse.kt.md`
- `ReplicaQuery.kt.md`
- `ReplicaResponse.kt.md`

---

## Phase 1.2: MeshChunk Verification ✅

### Objective
Verify `MeshChunk` has `replicaCount` field for tracking replica counts during daisy-chain replication.

### Files Verified
- **MeshStorageDataDefinitions.kt** (no changes needed)

### MeshChunk Structure (Already Correct)
```kotlin
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long = System.currentTimeMillis(),
    val recipientKeyIds: List<Long> = emptyList(),    // ✅ Already present
    val sessionKeys: Map<Long, ByteArray> = emptyMap(), // ✅ Already present
    var replicaCount: Int = 0                           // ✅ Already present
)
```

All required fields for canonical workflow were already present - no changes needed.

---

## Phase 1.3: ChunkTransferMessage Update ✅

### Objective
Add replica tracking and encryption metadata to `ChunkTransferMessage` for canonical workflow.

### Files Modified
- **MeshEcosystemMessage.kt** (lines ~207-250)

### Updated Structure (12 fields total)
```kotlin
data class ChunkTransferMessage(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val fileName: String,
    val relativePath: String,
    val chunkBytes: ByteArray,
    val hash: String,
    val replicaCount: Int = 0,                          // NEW: Current replica count
    val recipientKeyIds: List<Long> = emptyList(),      // NEW: Encryption recipients
    val sessionKeys: Map<Long, ByteArray> = emptyMap(), // NEW: Per-recipient keys
    val desiredReplicas: Int? = null                    // NEW: Target replica count
)
```

### Serialization Updates

**toBytes() - 40+ lines added**:
```kotlin
// Pack replica count
packInt(replicaCount)

// Pack recipient key IDs
packArrayHeader(recipientKeyIds.size)
recipientKeyIds.forEach { packLong(it) }

// Pack session keys map
packMapHeader(sessionKeys.size)
sessionKeys.forEach { (keyId, keyBytes) ->
    packLong(keyId)
    packBinaryHeader(keyBytes.size)
    writePayload(keyBytes)
}

// Pack optional desiredReplicas
if (desiredReplicas != null) {
    packBoolean(true)
    packInt(desiredReplicas)
} else {
    packBoolean(false)
}
```

**fromBytes() deserialization**:
```kotlin
val replicaCount = unpackInt()

val recipientKeyIdCount = unpackArrayHeader()
val recipientKeyIds = (0 until recipientKeyIdCount).map { unpackLong() }

val sessionKeyCount = unpackMapHeader()
val sessionKeys = (0 until sessionKeyCount).associate {
    val keyId = unpackLong()
    val keyBytesSize = unpackBinaryHeader()
    val keyBytes = readPayload(keyBytesSize)
    keyId to keyBytes
}

val hasDesiredReplicas = unpackBoolean()
val desiredReplicas = if (hasDesiredReplicas) unpackInt() else null
```

### Usage in Canonical Workflow
- **Step 1 (Store)**: replicaCount=0, sessionKeys populated, desiredReplicas set
- **Step N (Replication)**: replicaCount incremented each hop, sessionKeys unchanged
- **Termination**: When replicaCount >= desiredReplicas, stop replicating

---

## Phase 1.4: FilePermissionUpdateMessage Update ✅

### Objective
Update permission messages to use `RecipientEntry` for USER/TASK distinction and support chunk retry.

### Files Modified
- **MeshEcosystemMessage.kt** (lines ~305-330, ~335-350)

### RecipientEntry Integration
```kotlin
data class RecipientEntry(
    val publicKey: String,
    val recipientType: RecipientType,  // USER or TASK
    val expiresAt: Long? = null,       // For TASK recipients
    val taskId: String? = null         // For TASK recipients
)

enum class RecipientType {
    USER,   // Long-lived user access
    TASK    // Ephemeral task-scoped access
}
```

### Updated FilePermissionUpdateMessage (5 fields)
```kotlin
data class FilePermissionUpdateMessage(
    val fileId: String,
    val addedRecipients: List<RecipientEntry>,     // NEW: Recipients to add
    val removedRecipients: List<RecipientEntry>,   // NEW: Recipients to remove
    val chunkIndexes: List<Int>? = null,           // NEW: For targeted retry
    val senderNodeId: String
)
```

**Before**: `newRecipientKeyIds: List<Long>`  
**After**: `addedRecipients: List<RecipientEntry>`, `removedRecipients: List<RecipientEntry>`

### Serialization with JSON
Complex objects serialized as JSON strings:
```kotlin
// Pack
val addedJson = Json.encodeToString(addedRecipients)
packString(addedJson)
val removedJson = Json.encodeToString(removedRecipients)
packString(removedJson)

// Unpack
val addedJson = unpackString()
val addedRecipients = Json.decodeFromString<List<RecipientEntry>>(addedJson)
val removedJson = unpackString()
val removedRecipients = Json.decodeFromString<List<RecipientEntry>>(removedJson)
```

### Updated FilePermissionUpdateConfirmationMessage (4 fields)
```kotlin
data class FilePermissionUpdateConfirmationMessage(
    val nodeId: String,              // CHANGED: was storageNodeId (3rd param)
    val fileId: String,
    val chunkIndexes: List<Int>,     // CHANGED: was chunkId (singular)
    val status: String
)
```

**Before**: `(fileId, chunkId, storageNodeId, status)`  
**After**: `(nodeId, fileId, chunkIndexes, status)` - reports multiple chunks at once

### Usage in Canonical Workflow
- **Phase 4 (Update Access)**: Storage nodes receive permission updates with RecipientEntry lists
- **Re-encryption**: addedRecipients triggers session key re-encryption for new recipients
- **Task Cleanup**: removedRecipients with TASK type triggers cleanup when task completes
- **Retry**: chunkIndexes targets specific chunks that failed re-encryption

---

## Phase 1.5: Service Keypair Infrastructure ✅

### Objective
Add service keypair generation for storage nodes to support canonical workflow Step 0 and encryption in Step 1.5.

### Files Modified

#### 1. StorageSupport.kt
**Location**: Lines 77-101  
**Changes**: Added `generateServiceKeypair()` function

```kotlin
private val RSA_KEY_SIZE = 2048

/**
 * Generate service keypair for storage node encryption.
 * 
 * Canonical workflow Step 0: Each storage node has a service keypair.
 * The public key is included in StorageNodeResponse and used by requesters
 * to encrypt chunks so the storage node can decrypt them.
 * 
 * @return Pair of (publicKey, privateKey) as ByteArray
 */
fun generateServiceKeypair(): Pair<ByteArray, ByteArray> {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(RSA_KEY_SIZE, SecureRandom())
    val keyPair = keyPairGenerator.generateKeyPair()
    
    return Pair(
        keyPair.public.encoded,
        keyPair.private.encoded
    )
}
```

**Imports Added**: `import java.security.KeyPairGenerator`

#### 2. DistributedStorageManager.kt
**Location**: Lines 177-189  
**Changes**: Added lazy service keypair initialization

```kotlin
private val encryptionManager = StorageEncryptionManager()

// Service keypair for canonical workflow Step 0
// Storage node's service keypair - public key sent in StorageNodeResponse,
// private key used to decrypt incoming chunks
private val serviceKeypair: Pair<ByteArray, ByteArray> by lazy {
    encryptionManager.generateServiceKeypair()
}
val servicePublicKey: ByteArray
    get() = serviceKeypair.first
private val servicePrivateKey: ByteArray
    get() = serviceKeypair.second
```

### Usage in Canonical Workflow

**Step 0 (Initialization)**:
- Storage node generates RSA-2048 keypair on first access (lazy)
- Keypair persists for node lifetime (could be persisted to disk in production)

**Step 1.5 (Store - Encryption)**:
```kotlin
// Requester receives StorageNodeResponse with servicePublicKey
val storageNode = selectBestStorageNode(responses)
val encryptedChunk = encryptionManager.encryptWithRecipients(
    data = chunkBytes,
    recipients = recipientEntries + storageNode.servicePublicKey  // Include storage node
)
```

**Step 2 (Storage Node Receives)**:
```kotlin
// Storage node decrypts using its servicePrivateKey
val decryptedChunk = encryptionManager.decryptWithPrivateKey(
    encryptedData = chunkTransfer.chunkBytes,
    privateKey = servicePrivateKey
)
```

**Phase 4 (Re-encryption)**:
- Storage node uses servicePrivateKey to decrypt chunk
- Re-encrypts with new recipient list
- No need to request chunk from network

---

## Canonical Workflow File & Function Flow

### Storage Workflow (Phases 1-2)

```
┌─────────────────────────────────────────────────────────────────┐
│ REQUESTER NODE (User initiates file storage)                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ storeFile(file, recipients)
                              │  File: DistributedStorageManager.kt
                              │  - Split file into chunks
                              │  - For each chunk:
                              │
                              ├─ broadcastStorageNodeRequest(chunk)
                              │  - Create StorageNodeRequest (10 fields)
                              │  - Send via MeshEcosystemListener
                              │
┌─────────────────────────────────────────────────────────────────┐
│ STORAGE NODES (Respond to broadcast)                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleStorageNodeRequest()
                              │  File: DistributedStorageManager.kt
                              │  - Check quota: storageQuotaManager.canStoreFile()
                              │  - Calculate fitnessScore
                              │  - Create StorageNodeResponse (9 fields)
                              │  - Include servicePublicKey (from Phase 1.5)
                              │  - Send response to requester
                              │
┌─────────────────────────────────────────────────────────────────┐
│ REQUESTER NODE (Evaluates responses)                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleStorageNodeResponse()
                              │  - Collect responses
                              │  - Select single best node (fitness + latency)
                              │
                              ├─ encryptChunkForStorageNode()
                              │  File: StorageEncryptionManager (StorageSupport.kt)
                              │  - encryptWithRecipients(chunkBytes, recipients + storageNode.servicePublicKey)
                              │  - Returns encrypted chunk with session keys
                              │
                              ├─ createChunkTransferMessage()
                              │  - ChunkTransferMessage (12 fields from Phase 1.3)
                              │  - replicaCount = 0
                              │  - recipientKeyIds populated
                              │  - sessionKeys populated
                              │  - desiredReplicas = target count
                              │
                              ├─ sendChunkToStorageNode()
                              │  - Send ChunkTransferMessage to selected node
                              │
┌─────────────────────────────────────────────────────────────────┐
│ STORAGE NODE 1 (Receives and stores chunk)                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleIncomingChunkStorage() [PHASE 2.2]
                              │  File: DistributedStorageManager.kt
                              │  - Decrypt chunk using servicePrivateKey
                              │  - replicaCount = received.replicaCount + 1
                              │  - Write chunk to filesystem
                              │  - Index in StorageDataStore
                              │  - Send confirmation to requester
                              │
                              ├─ initiateChunkReplication() [PHASE 2.3]
                              │  if (replicaCount < desiredReplicas):
                              │    - Broadcast StorageNodeRequest (replicaCount=1)
                              │    - Select single best node
                              │    - Transfer ChunkTransferMessage (replicaCount=1)
                              │
┌─────────────────────────────────────────────────────────────────┐
│ STORAGE NODE 2 (Receives replicated chunk)                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleIncomingChunkStorage()
                              │  - replicaCount = 1 + 1 = 2
                              │  - Store chunk
                              │
                              ├─ initiateChunkReplication()
                              │  if (replicaCount < desiredReplicas):
                              │    - Continue daisy-chain...
                              │
                           [Continues until replicaCount >= desiredReplicas]
```

### Retrieval Workflow (Phase 3)

```
┌─────────────────────────────────────────────────────────────────┐
│ REQUESTER NODE (User retrieves file)                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ retrieveFile(fileId)
                              │  File: DistributedStorageManager.kt
                              │
                              ├─ broadcastChunkRetrievalQuery()
                              │  - ChunkRetrievalQuery (2 fields from Phase 1.1)
                              │  - chunkIndexes = null (all chunks) or [specific list]
                              │
┌─────────────────────────────────────────────────────────────────┐
│ STORAGE NODES (Respond with chunks)                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleChunkRetrievalQuery()
                              │  - Look up chunks in StorageDataStore
                              │  - Create ChunkRetrievalResponse
                              │  - Send encrypted chunks to requester
                              │
┌─────────────────────────────────────────────────────────────────┐
│ REQUESTER NODE (Reassembles file)                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleChunkRetrievalResponse()
                              │  - Decrypt chunks using user's private key
                              │  - Verify chunk hashes
                              │  - Reassemble file from chunks
                              │  - Return file to user
```

### Permission Update Workflow (Phase 4)

```
┌─────────────────────────────────────────────────────────────────┐
│ REQUESTER NODE (Owner updates file access)                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ updateFilePermissions(fileId, addedRecipients, removedRecipients)
                              │  File: DistributedStorageManager.kt
                              │
                              ├─ broadcastFilePermissionUpdate()
                              │  - FilePermissionUpdateMessage (5 fields from Phase 1.4)
                              │  - addedRecipients: List<RecipientEntry> (USER or TASK)
                              │  - removedRecipients: List<RecipientEntry>
                              │  - chunkIndexes: null or [specific chunks for retry]
                              │
┌─────────────────────────────────────────────────────────────────┐
│ STORAGE NODES (Re-encrypt chunks for new recipients)            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleFilePermissionUpdate()
                              │  File: DistributedStorageManager.kt
                              │
                              ├─ reencryptChunksForRecipients()
                              │  File: StorageEncryptionManager (StorageSupport.kt)
                              │  - Decrypt chunk using servicePrivateKey
                              │  - Add new recipient session keys
                              │  - Remove old recipient session keys
                              │  - Re-encrypt chunk with updated recipients
                              │  - Update StorageDataStore
                              │
                              ├─ sendPermissionUpdateConfirmation()
                              │  - FilePermissionUpdateConfirmationMessage (4 fields)
                              │  - chunkIndexes: List<Int> (all updated chunks)
                              │  - status: "success" or "failed"
                              │
┌─────────────────────────────────────────────────────────────────┐
│ REQUESTER NODE (Tracks confirmation)                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ├─ handleFilePermissionUpdateConfirmation()
                              │  - Verify all chunks updated successfully
                              │  - If failed chunks, retry with chunkIndexes specified
```

---

## Data Structure Reference

### Core Message Types (MeshEcosystemMessage.kt)

All ecosystem messages defined in single file with MessagePack serialization:

1. **StorageNodeRequest** (10 fields) - Discovery broadcast
2. **StorageNodeResponse** (9 fields) - Storage node capabilities + servicePublicKey
3. **ChunkTransferMessage** (12 fields) - Chunk transmission with encryption metadata
4. **ChunkRetrievalQuery** (2 fields) - Request chunks (all or specific indexes)
5. **ChunkRetrievalResponse** (8 fields) - Encrypted chunks response
6. **FilePermissionUpdateMessage** (5 fields) - Add/remove recipients with RecipientEntry
7. **FilePermissionUpdateConfirmationMessage** (4 fields) - Confirmation for chunk list
8. **ReplicaQuery** (1 field) - Check replica count
9. **ReplicaResponse** (1 field) - Return replica count

### Supporting Types

**RecipientEntry** (RecipientType.kt):
```kotlin
data class RecipientEntry(
    val publicKey: String,
    val recipientType: RecipientType,  // USER or TASK
    val expiresAt: Long? = null,
    val taskId: String? = null
)
```

**MeshChunk** (MeshStorageDataDefinitions.kt):
```kotlin
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long,
    val recipientKeyIds: List<Long>,
    val sessionKeys: Map<Long, ByteArray>,
    var replicaCount: Int = 0
)
```

---

## Key Implementation Patterns

### 1. MessagePack Serialization Patterns

**Nullable/Optional Fields**:
```kotlin
// Boolean flag pattern
if (value != null) {
    packBoolean(true)
    packValue(value)
} else {
    packBoolean(false)
}
```

**ByteArray Fields**:
```kotlin
packBinaryHeader(bytes.size)
writePayload(bytes)
```

**List Fields**:
```kotlin
packArrayHeader(list.size)
list.forEach { packItem(it) }
```

**Map Fields**:
```kotlin
packMapHeader(map.size)
map.forEach { (key, value) ->
    packKey(key)
    packValue(value)
}
```

**Complex Objects (RecipientEntry)**:
```kotlin
val json = Json.encodeToString(list)
packString(json)
```

### 2. Lazy Initialization Pattern

Service keypair generated on first access:
```kotlin
private val serviceKeypair: Pair<ByteArray, ByteArray> by lazy {
    encryptionManager.generateServiceKeypair()
}
```

### 3. Encryption Architecture

**Hybrid Encryption**:
1. Generate random AES-256 chunk key
2. Encrypt chunk data with chunk key
3. Encrypt chunk key for each recipient (including storage node)
4. Bundle: encrypted data + per-recipient encrypted keys

**Storage Node Encryption**:
- Storage node included as recipient with RSA public key
- Can decrypt chunk for re-encryption without network request
- Enables Phase 4 permission updates without data transfer

---

## Testing Validation Points

Before moving to Phase 2, verify:

1. ✅ All ecosystem messages in MeshEcosystemMessage.kt
2. ✅ MeshChunk has replicaCount, recipientKeyIds, sessionKeys
3. ✅ ChunkTransferMessage serialization includes all 12 fields
4. ✅ FilePermissionUpdateMessage uses RecipientEntry lists
5. ✅ Service keypair generated on DistributedStorageManager initialization
6. ✅ StorageEncryptionManager has generateServiceKeypair() function
7. ⏳ Message serialization/deserialization round-trip tests (Phase 2)
8. ⏳ Service keypair encryption/decryption tests (Phase 2)

---

## Next Steps: Phase 2 - Storage Workflow Refactoring

### Phase 2.1: Refactor storeFile() Encryption Timing
**File**: DistributedStorageManager.kt  
**Changes**:
1. Move encryption from line ~463 to after storage node selection
2. Change `findBestStorageNodesForChunk()` to `findBestStorageNodeForChunk()` (singular)
3. Include storage node's `servicePublicKey` in encryption recipients
4. Remove `AccessScope` parameter and all related logic
5. Set `replicaCount = 0` in ChunkTransferMessage
6. Pass `desiredReplicas` to ChunkTransferMessage

### Phase 2.2: Implement handleIncomingChunkStorage()
**File**: DistributedStorageManager.kt  
**New Function**:
```kotlin
suspend fun handleIncomingChunkStorage(chunkTransfer: ChunkTransferMessage) {
    // 1. Decrypt chunk using servicePrivateKey
    // 2. Increment replicaCount before storing
    // 3. Write chunk to filesystem
    // 4. Index in StorageDataStore
    // 5. Send completion notification
    // 6. Initiate replication if replicaCount < desiredReplicas
}
```

### Phase 2.3: Implement Daisy-Chain Replication
**File**: DistributedStorageManager.kt  
**New Function**:
```kotlin
suspend fun initiateChunkReplication(chunk: MeshChunk) {
    if (chunk.replicaCount >= desiredReplicas) return
    
    // 1. Broadcast StorageNodeRequest with current replicaCount
    // 2. Select single best node
    // 3. Transfer chunk with same replicaCount value
    // 4. Natural termination when stored count >= target
}
```

---

## Summary Statistics

**Total Files Modified**: 3
- MeshEcosystemMessage.kt: ~180 lines changed (data structures + serialization)
- StorageSupport.kt: ~28 lines added (keypair generation)
- DistributedStorageManager.kt: ~13 lines added (keypair initialization)

**Total Lines Changed**: ~221 lines

**Data Structures Updated**: 6 message types
- StorageNodeRequest: 10 fields (7 new)
- StorageNodeResponse: 9 fields (3 new)
- ChunkTransferMessage: 12 fields (5 new)
- ChunkRetrievalQuery: 2 fields (1 new)
- FilePermissionUpdateMessage: 5 fields (4 new)
- FilePermissionUpdateConfirmationMessage: 4 fields (3 changed)

**Infrastructure Added**:
- Service keypair generation (RSA-2048)
- Lazy keypair initialization
- RecipientEntry integration
- MessagePack serialization for all new fields

**Phase 1 Status**: ✅ COMPLETE - Ready for Phase 2 implementation

