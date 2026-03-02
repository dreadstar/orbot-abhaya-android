# Implementation Directive: NETWORK_BROADCAST_v2.md

You are tasked with implementing the complete broadcast message+file feature according to [NETWORK_BROADCAST_v2.md](NETWORK_BROADCAST_v2.md). Follow these protocols with absolute precision.

## CRITICAL REQUIREMENTS

### 1. Use MeshrabiyaConstants.kt for ALL Constants
- **File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt)
- **DO NOT** hardcode any constants in implementation files
- **ALWAYS** reference constants as `MeshrabiyaConstants.CONSTANT_NAME`
- **Required constants to add**:
  ```kotlin
  const val BROADCAST_CHUNK_SIZE = 1024
  const val MMCP_TYPE_BROADCAST_MESSAGE = 6
  const val BROADCAST_TIMEOUT_MS = 30_000L
  const val MAX_BROADCAST_MESSAGE_LENGTH = 500
  ```

### 2. Track Progress in Plan Document
- **Update NETWORK_BROADCAST_v2.md** with completion markers as you proceed
- Mark each completed section with `✅ COMPLETED (YYYY-MM-DD HH:MM)` and timestamp
- Mark in-progress sections with `🔄 IN PROGRESS (YYYY-MM-DD HH:MM) - [status]`
- Mark blocked sections with `⚠️ BLOCKED (YYYY-MM-DD HH:MM) - [reason]`
- **Never move to next phase until current phase is 100% complete and verified**

### 3. Systematic Implementation Order
- Follow the **exact phase order** defined in the plan (Phases 1-7)
- Complete **all items** in a phase before proceeding to next phase
- Verify each phase with compilation and tests before continuing
- Document any deviations from plan with justification

### 4. Verification Protocol
- After **each file creation/modification**, run:
  ```bash
  : > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew :Meshrabiya:lib-meshrabiya:compileDebugKotlin --console=plain 2>&1 | tee build_output.log
  ```
- Fix **all compilation errors** before proceeding to next file
- Document any deviations from plan with justification in plan document
- Update Section 13 verification checklist as items are completed

---

## IMPLEMENTATION WORKFLOW

### Phase 1: Constants & Data Structures

#### Step 1.1: Add Constants to MeshrabiyaConstants.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaConstants.kt)

Add the following constants in an appropriate section (after existing broadcast constants or create new section):

```kotlin
// ========================================
// BROADCAST MESSAGE+FILE CONSTANTS
// ========================================

/**
 * Chunk size for broadcast file transfer (bytes).
 * Balances memory efficiency with packet overhead.
 * Must be smaller than VirtualPacket max payload (~1500 bytes).
 */
const val BROADCAST_CHUNK_SIZE = 1024

/**
 * MMCP message type for broadcast message+file packets.
 * Uses port 0 (MMCP port) with this type identifier.
 */
const val MMCP_TYPE_BROADCAST_MESSAGE = 6

/**
 * Timeout for incomplete broadcast reception (milliseconds).
 * After this time, incomplete broadcasts are cleaned up.
 */
const val BROADCAST_TIMEOUT_MS = 30_000L

/**
 * Maximum length for broadcast message text (characters).
 * Keeps total packet size within limits.
 */
const val MAX_BROADCAST_MESSAGE_LENGTH = 500
```

**Update Plan**: In NETWORK_BROADCAST_v2.md, add after Phase 1 heading:
```markdown
### Phase 1: Constants & Data Structures
**Status**: 🔄 IN PROGRESS (2026-02-01 XX:XX)

#### Step 1.1: Add Constants to MeshrabiyaConstants.kt
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**Verification**: Compiled successfully, no errors
```

**Verify**: Compile and check for errors.

---

#### Step 1.2: Create BroadcastDtos.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/ext/BroadcastDtos.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/ext/BroadcastDtos.kt)

**Action**: CREATE file exactly as specified in Section 5.1 of plan

**Critical**: Ensure you follow the exact code in Section 5.1, no modifications

**Update Plan**: Mark Section 5.1 as:
```markdown
### 5.1 New File: `BroadcastDtos.kt`
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**File**: Created at correct path
**Verification**: Compiled successfully, no errors
```

**Verify**: Compile and check for errors.

---

#### Step 1.3: Create BroadcastState.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastState.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastState.kt)

**Action**: CREATE file exactly as specified in Section 5.2 of plan

**Note**: Directory `broadcast/` may not exist - create it

**Update Plan**: Mark Section 5.2 as:
```markdown
### 5.2 New File: `BroadcastState.kt`
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**File**: Created at correct path (created broadcast/ directory)
**Verification**: Compiled successfully, no errors
```

**Verify**: Compile and check for errors.

---

#### Step 1.4: Update Phase 1 Summary
**Update Plan**: Mark Phase 1 complete in Section 12.1:
```markdown
### Phase 1: Constants & Data Structures
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**Files Created**: 2 (BroadcastDtos.kt, BroadcastState.kt)
**Constants Added**: 4 (in MeshrabiyaConstants.kt)
**Verification**: All files compile successfully
```

---

### Phase 2: Serialization Logic

#### Step 2.1: Create BroadcastPacketSerializer.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastPacketSerializer.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastPacketSerializer.kt)

**Action**: CREATE file from Section 6.1 of plan

**CRITICAL MODIFICATION**: Replace hardcoded `MAX_MESSAGE_LENGTH` constant with reference to MeshrabiyaConstants:

```kotlin
import com.ustadmobile.meshrabiya.MeshrabiyaConstants

object BroadcastPacketSerializer {
    private const val VERSION = 1
    
    fun serialize(
        broadcastId: String,
        messageText: String,
        chunkMetadata: BroadcastChunkMetadata,
        chunkData: ByteArray
    ): ByteArray {
        require(messageText.length <= MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
            "Message exceeds max length: ${messageText.length} > ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH}"
        }
        // ... rest of implementation
    }
}
```

**Update Plan**: Mark Section 6.1 as:
```markdown
### 6.1 New File: `BroadcastPacketSerializer.kt`
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**File**: Created with MeshrabiyaConstants reference
**Modification**: Used MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH instead of local constant
**Verification**: Compiled successfully, no errors
```

**Verify**: Compile and check for errors.

---

#### Step 2.2: Create BroadcastPacketSerializerTest.kt
**File**: [Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastPacketSerializerTest.kt](Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastPacketSerializerTest.kt)

**Action**: CREATE file from Section 11.1.1 of plan

**Update Plan**: Mark Section 11.1.1 as:
```markdown
#### 11.1.1 `BroadcastPacketSerializerTest.kt`
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**File**: Created at correct path
**Verification**: Compiled successfully, tests pass
```

**Verify**: Run tests:
```bash
./gradlew :Meshrabiya:lib-meshrabiya:test --tests BroadcastPacketSerializerTest
```

---

#### Step 2.3: Create IncomingBroadcastStateTest.kt
**File**: [Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/broadcast/IncomingBroadcastStateTest.kt](Meshrabiya/lib-meshrabiya/src/test/java/com/ustadmobile/meshrabiya/vnet/broadcast/IncomingBroadcastStateTest.kt)

**Action**: CREATE file from Section 11.1.2 of plan

**Update Plan**: Mark Section 11.1.2 as completed

**Verify**: Run tests.

---

#### Step 2.4: Update Phase 2 Summary
**Update Plan**: Mark Phase 2 complete.

---

### Phase 3: Send Logic (Broadcast Origination)

#### Step 3.1: Create BroadcastMessageHandler.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt)

**Action**: CREATE file from Section 7.1 of plan

**CRITICAL MODIFICATIONS**: Replace ALL hardcoded constants with MeshrabiyaConstants references:

```kotlin
import com.ustadmobile.meshrabiya.MeshrabiyaConstants

class BroadcastMessageHandler(
    // ... parameters
) {
    // ... other code
    
    companion object {
        private const val TAG = "BroadcastMessageHandler"
        // Remove local constants, use MeshrabiyaConstants instead
    }
    
    fun sendBroadcast(...) {
        // ...
        val totalChunks = (fileBytes.size + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE - 1) / 
                         MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
        
        // In chunk loop:
        val startOffset = chunkIndex * MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
        val endOffset = minOf(startOffset + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE, fileBytes.size)
        // ...
    }
    
    fun cleanupStaleTransfers() {
        // ...
        if (now - state.startTime > MeshrabiyaConstants.BROADCAST_TIMEOUT_MS) {
            // ...
        }
    }
}
```

**Update Plan**: Mark Section 7.1 as:
```markdown
### 7.1 New File: `BroadcastMessageHandler.kt`
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**File**: Created with all MeshrabiyaConstants references
**Modifications**:
- Used MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
- Used MeshrabiyaConstants.MMCP_TYPE_BROADCAST_MESSAGE
- Used MeshrabiyaConstants.BROADCAST_TIMEOUT_MS
**Verification**: Compiled successfully, no errors
```

**Verify**: Compile and check for errors.

---

#### Step 3.2: Update Phase 3 Summary
**Update Plan**: Mark Phase 3 complete.

---

### Phase 4: Receive Logic (Broadcast Reception & Reassembly)

#### Step 4.1: Modify VirtualNode.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt)

**Action**: MODIFY file as specified in Section 8.1 of plan

**Steps**:
1. Add property: `var broadcastMessageHandler: BroadcastMessageHandler? = null`
2. Add broadcast detection logic in `route()` method

**Update Plan**: Mark Section 8.1 as:
```markdown
### 8.1 Modify: `VirtualNode.kt` - Add Broadcast Detection
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**Changes**:
- Added broadcastMessageHandler property
- Added broadcast packet detection in route() method
**Verification**: Compiled successfully, existing tests pass
```

**Verify**: Compile and run existing tests to ensure no regressions.

---

#### Step 4.2: Update Phase 4 Summary
**Update Plan**: Mark Phase 4 complete.

---

### Phase 5: Integration & Wiring

#### Step 5.1: Modify MeshrabiyaApi.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApi.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApi.kt)

**Action**: MODIFY file as specified in Section 9.1 of plan

**Update Plan**: Mark Section 9.1 as completed.

**Verify**: Compile.

---

#### Step 5.2: Modify MeshrabiyaApiImpl.kt
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt)

**Action**: MODIFY file as specified in Section 9.2 of plan

**CRITICAL MODIFICATION**: When checking message length, use MeshrabiyaConstants:

```kotlin
override fun broadcastMessageAndFile(...) {
    if (messageText.length > MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH) {
        callback(Result.failure(IllegalArgumentException(
            "Message exceeds ${MeshrabiyaConstants.MAX_BROADCAST_MESSAGE_LENGTH} character limit"
        )))
        return
    }
    // ...
}
```

**Update Plan**: Mark Section 9.2 as:
```markdown
### 9.2 Modify: `MeshrabiyaApiImpl.kt` - Implement Methods
**Status**: ✅ COMPLETED (2026-02-01 XX:XX)
**Changes**:
- Added broadcastHandler property
- Implemented broadcastMessageAndFile with MeshrabiyaConstants reference
- Implemented registerBroadcastListener
- Implemented unregisterBroadcastListener
- Wired handler in startMesh/stopMesh
**Verification**: Compiled successfully, no errors
```

**Verify**: Compile.

---

#### Step 5.3: Implement getDropFolder/setDropFolder
**File**: [Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/MeshrabiyaApiImpl.kt)

**Action**: MODIFY file as specified in Section 9.3 of plan

**Note**: Use MeshrabiyaConstants.getDropFolderPath() instead of direct SharedPreferences access, as this already exists in MeshrabiyaConstants.kt

**Update Plan**: Mark Section 9.3 as completed.

**Verify**: Full compilation succeeds.

---

#### Step 5.4: Update Phase 5 Summary
**Update Plan**: Mark Phase 5 complete.

---

### Phase 6: Error Handling

#### Step 6.1: Review Error Scenarios
**Action**: Review Section 10.1 of plan

**Checklist**:
- [ ] Drop folder not selected error handling verified
- [ ] File does not exist error handling verified
- [ ] Message too long error handling verified
- [ ] Chunk hash mismatch error handling verified
- [ ] Broadcast timeout error handling verified
- [ ] Drop folder full error handling verified
- [ ] Mesh not running error handling verified
- [ ] Serialization errors error handling verified

**Update Plan**: Mark Section 10 as reviewed.

---

#### Step 6.2: Verify Logging Matrix
**Action**: Review Section 10.2 of plan

**Checklist**: Verify all log events from the matrix are implemented.

**Update Plan**: Mark Phase 6 complete.

---

### Phase 7: Testing

#### Step 7.1: Create Integration Tests
**File**: [Meshrabiya/lib-meshrabiya/src/androidTest/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandlerTest.kt](Meshrabiya/lib-meshrabiya/src/androidTest/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandlerTest.kt)

**Action**: CREATE file from Section 11.1.3 of plan

**Update Plan**: Mark Section 11.1.3 as completed.

**Verify**: Run integration tests.

---

#### Step 7.2: Run All Tests
**Action**: Run full test suite:

```bash
./gradlew :Meshrabiya:lib-meshrabiya:test
./gradlew :Meshrabiya:lib-meshrabiya:connectedAndroidTest
```

**Update Plan**: Mark Phase 7 complete.

---

### Phase 8: Final Verification

#### Step 8.1: Complete Section 13 Checklist
**Action**: Go through each item in Section 13 of plan and verify.

**Update Plan**: Check off items in Section 13.

---

#### Step 8.2: Update Summary Table (Section 12)
**Action**: Verify all files in Section 12.1 and 12.2 are completed.

**Update Plan**: Mark all files with ✅ in Section 12.

---

## PROGRESS TRACKING FORMAT

In [NETWORK_BROADCAST_v2.md](NETWORK_BROADCAST_v2.md), update sections using this format:

### For Completed Sections:
```markdown
### 5.1 New File: `BroadcastDtos.kt`
**Status**: ✅ COMPLETED (2026-02-01 14:23)
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/ext/BroadcastDtos.kt`  
**Action**: CREATED
**Verification**: Compiled successfully, no errors
**Notes**: [Any relevant notes]
```

### For In-Progress Sections:
```markdown
### 7.1 New File: `BroadcastMessageHandler.kt`
**Status**: 🔄 IN PROGRESS (2026-02-01 14:45) - Implementing send logic
**File**: `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`  
**Action**: CREATE
**Progress**: 60% - Send logic complete, working on receive logic
```

### For Blocked Sections:
```markdown
### 9.2 Modify: `MeshrabiyaApiImpl.kt`
**Status**: ⚠️ BLOCKED (2026-02-01 15:10) - Compilation error in dependency
**File**: [path]
**Issue**: BroadcastMessageHandler not found - Phase 3 must be completed first
**Resolution**: Complete Step 3.1 first
```

---

## ERROR HANDLING PROTOCOL

### If Compilation Fails:
1. **Document** error in plan document under affected section
2. **Read** the full error output
3. **Fix** error (may require reading actual file to verify signature)
4. **Re-verify** compilation
5. **Update** status to ✅ only after success

### If Plan Requires Modification:
1. **Document** reason for deviation in plan under affected section
2. **Update** affected sections with new approach
3. **Ensure** overall objectives still met
4. **Add note** explaining why deviation was necessary

---

## COMPLETION CRITERIA

Implementation is complete when ALL of the following are true:

- [ ] All 7 phases marked ✅ in plan document
- [ ] All files in Section 12.1 created and marked ✅
- [ ] All files in Section 12.2 modified and marked ✅
- [ ] All compilation errors resolved
- [ ] All unit tests pass (Section 11.1)
- [ ] All integration tests pass (Section 11.2)
- [ ] All items in Section 13 verification checklist checked ✅
- [ ] All constants centralized in MeshrabiyaConstants.kt (no hardcoded values)
- [ ] Plan document updated with final status summary

---

## FINAL DELIVERABLE

Upon completion, add this section to end of [NETWORK_BROADCAST_v2.md](NETWORK_BROADCAST_v2.md):

```markdown
---

## IMPLEMENTATION SUMMARY

**Implementation Date**: 2026-02-01
**Status**: ✅ COMPLETE
**Implementation Time**: [X hours]
**Total Files Created**: 7
**Total Files Modified**: 4 (including MeshrabiyaConstants.kt)
**Total Lines Added**: ~1165
**Compilation Status**: ✅ SUCCESS
**Test Status**: ✅ ALL PASS

### Constants Added to MeshrabiyaConstants.kt
- `BROADCAST_CHUNK_SIZE = 1024`
- `MMCP_TYPE_BROADCAST_MESSAGE = 6`
- `BROADCAST_TIMEOUT_MS = 30_000L`
- `MAX_BROADCAST_MESSAGE_LENGTH = 500`

### Files Created
1. ✅ BroadcastDtos.kt
2. ✅ BroadcastState.kt
3. ✅ BroadcastPacketSerializer.kt
4. ✅ BroadcastMessageHandler.kt
5. ✅ BroadcastPacketSerializerTest.kt
6. ✅ IncomingBroadcastStateTest.kt
7. ✅ BroadcastMessageHandlerTest.kt

### Files Modified
1. ✅ MeshrabiyaConstants.kt - Added 4 broadcast constants
2. ✅ MeshrabiyaApi.kt - Added 3 interface methods
3. ✅ MeshrabiyaApiImpl.kt - Implemented broadcast functionality
4. ✅ VirtualNode.kt - Added broadcast packet detection

### Verification Checklist Results (Section 13)
[Paste completed checklist from Section 13]

### Deviations from Plan
[Document any deviations with justifications]

### Known Issues
[Any outstanding issues or future work]

### Next Steps
[Recommended next steps for testing on actual devices]
```

---

## CRITICAL REMINDERS

1. **NEVER** hardcode constants - always use MeshrabiyaConstants
2. **ALWAYS** update plan document before moving to next step
3. **VERIFY** compilation after each file creation/modification
4. **DOCUMENT** any deviations from plan with clear justification
5. **RUN TESTS** for each phase before proceeding
6. **READ** actual file contents when verification fails - don't assume
7. **FOLLOW** the exact implementation order - no skipping ahead

---

## BEGIN IMPLEMENTATION

**START NOW**: Begin with Phase 1, Step 1.1 - Adding constants to MeshrabiyaConstants.kt

**First Command to Run**:
```bash
# Read current MeshrabiyaConstants.kt to understand structure
```

Then add the broadcast constants in an appropriate location.

Good luck! Follow the plan systematically and track every step.
