# BROADCAST INVESTIGATION PROTOCOL

**Date:** February 16, 2026  
**Purpose:** Mandatory protocol for investigating broadcast/file transfer failures in mesh networking systems  
**Applies To:** All agents investigating broadcast, file transfer, or message delivery issues

---

## CORE PRINCIPLE

**ALWAYS trace the COMPLETE workflow from initiation to completion, step-by-step, with verification at each stage.**

Never assume intermediate steps work. Never skip steps. Always use log evidence and code verification.

---

## MANDATORY WORKFLOW TRACE (11 STEPS)

When investigating ANY broadcast or file transfer failure, agents MUST trace the following complete workflow:

### Step 1: Broadcast Initiation (Sender Device)
**Objective:** Verify broadcast was actually initiated with correct parameters

**Required Actions:**
1. ✅ Find broadcast dialog/UI submission in sender logs
2. ✅ Extract exact parameters:
   - Message text (if any)
   - File selected: Yes/No
   - File size (bytes)
   - File path (if applicable)
3. ✅ Locate API call: `broadcastMessageAndFile()` or equivalent
4. ✅ Verify parameters passed to API match user input
5. ✅ Extract broadcast ID assigned

**Log Keywords to Search:**
- "broadcastMessageAndFile", "sendBroadcast", "Broadcast dialog"
- File picker, file selection, URI resolution

**Code Files to Verify:**
- UI layer (e.g., EnhancedMeshFragment.kt)
- API implementation (e.g., MeshrabiyaApiImpl.kt)

**Evidence Required:**
- Exact log line showing broadcast initiation
- Broadcast ID
- Parameters (message text, file size)

---

### Step 2: File Chunking (Sender Device)
**Objective:** Verify file was correctly split into chunks with metadata

**Required Actions:**
1. ✅ Find chunking operation in sender logs
2. ✅ Extract total chunks calculated: `totalChunks = ceil(fileSize / chunkSize)`
3. ✅ Verify chunk size (typically 1024 bytes)
4. ✅ Find metadata creation with:
   - Broadcast ID
   - Total chunks
   - File name
   - Message text
   - Sender node ID
5. ✅ Verify metadata packet created and stored

**Log Keywords to Search:**
- "totalChunks", "chunkSize", "BroadcastMetadata"
- "Chunking", "split file"

**Code Files to Verify:**
- BroadcastMessageHandler.kt `sendBroadcast()` method
- Metadata creation logic

**Evidence Required:**
- Total chunks calculation
- Metadata packet contents
- Confirmation metadata stored in outgoingBroadcasts map

---

### Step 3: Transmission Path (Sender → Network)
**Objective:** Verify chunks transmitted from app layer through system to network, and check for sender loopback behavior

**Required Actions:**
1. ✅ App layer: Find broadcast send logs showing chunk-by-chunk transmission
2. ✅ Extract chunk indices sent (0, 1, 2, ..., totalChunks-1)
3. ✅ Find VirtualNode direct send to neighbors (broadcasts bypass route() for outgoing)
4. ✅ Find actual network transmission logs (UDP send, socket write)
5. ✅ Count total chunks transmitted
6. ✅ Verify destination address and port
7. ✅ Calculate transmission rate (chunks/second)
8. ✅ Check for transmission errors, retries, or failures
9. ✅ **Sender loopback check:** Verify sender does NOT process own broadcast when it loops back
   - Check if sender receives own broadcast via route() (expected in mesh)
   - Verify deduplication prevents local delivery (seenBroadcasts check)
   - Verify sender check prevents notification (fromAddr != addressAsInt)

**Log Keywords to Search:**
- "Sending chunk", "sent to neighbor", "BROADCAST packet"
- "UDP send", "DatagramSocket", "sendto"
- Destination IP, destination port
- "Broadcast already seen, ignoring" (sender dedup)
- "Skipping local delivery" (sender check)

**Code Files to Verify:**
- BroadcastMessageHandler.kt `sendBroadcast()` transmission loop
- VirtualNode.kt `route()` method (for loopback handling)
- VirtualNodeDatagramSocket.kt or equivalent

**Evidence Required:**
- Count of chunks transmitted (should equal totalChunks)
- Transmission rate
- Destination address
- Any transmission errors
- Sender loopback handling (see Step 3.5 for detailed verification)

**Critical Verification:**
- ❓ Did ALL chunks get transmitted? (count == totalChunks)
- ❓ Any network errors or socket failures?
- ❓ Does sender receive own broadcast via loopback? (see Step 3.5)

---

### Step 3.5: Sender Loopback Check (Sender Device) ⚠️ CRITICAL
**Objective:** Verify sender does NOT receive own broadcast via network loopback

**Issue #1 Context:**
Broadcasts transmitted by a node can loop back through the mesh network and be received by the originating node. The routing layer MUST prevent the sender from processing their own broadcasts locally, as this causes unwanted notifications.

**Required Actions:**
1. ✅ Find VirtualNode.route() logs on SENDER for broadcast packets received
2. ✅ Check if sender's own broadcast appears in "Received broadcast chunk" logs
3. ✅ Verify seenBroadcasts deduplication mechanism:
   - Find "seenBroadcasts.putIfAbsent" logs
   - Extract return value (null = first time, timestamp = already seen)
4. ✅ Verify local delivery ONLY happens when prev == null (first time)
5. ✅ Verify sender check: `packet.header.fromAddr != addressAsInt`
6. ✅ Find "Broadcast already seen, ignoring" or "Skipping local delivery" logs
7. ✅ Verify sender does NOT create notification for own broadcast

**Log Keywords to Search:**
- "Broadcast already seen, ignoring"
- "Skipping local delivery for broadcast" (sender is THIS node)
- "seenBroadcasts.putIfAbsent"
- "Delivering broadcast locally" (should NOT appear for sender's own broadcasts)
- "fromAddr" comparisons

**Code Files to Verify:**
- VirtualNode.kt `route()` method (lines 894-950)
- Deduplication logic with seenBroadcasts map
- Local delivery conditional: `if (prev == null && fromAddr != addressAsInt)`

**Evidence Required:**
- Sender logs showing broadcast received from network
- Deduplication check result (already seen?)
- Whether local delivery was skipped
- Whether notification was created

**Critical Verification:**
- ❓ Did sender receive own broadcast via network loopback?
- ❓ If YES, was local delivery skipped due to dedup check?
- ❓ If local delivery happened, did sender create notification? ❌ BUG
- ❓ Does VirtualNode check `fromAddr != addressAsInt` before local delivery?

**Expected Behavior:**
- ✅ Sender broadcasts → seenBroadcasts[id] = timestamp (prev == null)
- ✅ Broadcast loops back → seenBroadcasts.putIfAbsent returns prev timestamp
- ✅ VirtualNode logs "Broadcast already seen, ignoring"
- ✅ Local delivery SKIPPED (notification count = 0 on sender)

**Bug Indicators:**
- ❌ Sender logs show "Delivering broadcast locally"
- ❌ Sender notification count > 0 after sending
- ❌ Toast/Snackbar appears on sender device
- ❌ Local delivery happens OUTSIDE `if (prev == null)` check

---

### Step 4: Network Reception (Network → Receiver)
**Objective:** Verify packets received at receiver device system layer

**Required Actions:**
1. ✅ Find UDP/datagram packet reception logs on receiver
2. ✅ Count packets received from sender's IP address
3. ✅ Find VirtualNode packet reception logs
4. ✅ Verify source address matches sender
5. ✅ Calculate packet loss rate: `(sent - received) / sent * 100%`
6. ✅ Check for:
   - Network errors
   - Malformed packets
   - Checksum failures

**Log Keywords to Search:**
- "received packet", "DatagramPacket received", "onReceive"
- Sender IP address, sender port
- "VirtualPacket", "packet validation"

**Code Files to Verify:**
- VirtualNodeDatagramSocket.kt reception logic
- VirtualNode.kt packet handling

**Evidence Required:**
- Count of packets received at system level
- Count of packets received at VirtualNode level
- Packet loss rate
- Any reception errors

**Critical Verification:**
- ❓ Does received count match transmitted count?
- ❓ If not, this is network loss (expected in some environments)

---

### Step 5: Routing to Handler (Receiver)
**Objective:** Verify packets correctly routed from VirtualNode to BroadcastMessageHandler

**VirtualNode Architecture Context:**
The VirtualNode routing layer handles broadcast packets differently than MMCP messages:
- **Broadcast packets** (toAddr == ADDR_BROADCAST): Forwarded to neighbors + delivered locally
- **MMCP messages** (toAddr == specific node): Routed through originatingMessageManager
- **Deduplication**: `seenBroadcasts` map prevents infinite forwarding loops
- **Local delivery**: Should ONLY happen for broadcasts from OTHER nodes, not own broadcasts

**Required Actions:**
1. ✅ Find VirtualNode.route() or onIncomingMmcpMessage() logs for BROADCAST packets
2. ✅ Verify broadcast packet type detection:
   - Find "BROADCAST PACKET DETECTED" logs
   - Extract packet type byte (should be 0x01 for broadcast chunks)
3. ✅ Count packets routed to BroadcastMessageHandler
4. ✅ Find BroadcastMessageHandler.onReceiveBroadcastPacket() invocations
5. ✅ Calculate routing loss: `receivedAtNode - routedToHandler`
6. ✅ Verify routing decision logic:
   - Check if packet bypasses MMCP routing (correct for broadcasts)
   - Verify broadcastMessageHandler?.onReceiveBroadcastPacket() called
7. ✅ Check for:
   - Routing errors
   - Unknown packet types
   - Handler not registered
   - Deduplication preventing local delivery (check if intended)

**Log Keywords to Search:**
- "✅ BROADCAST PACKET DETECTED", "routing to BroadcastMessageHandler"
- "onReceiveBroadcastPacket", "Received broadcast chunk"
- "Delivering broadcast locally" (should appear for receiver)
- "Forwarding broadcast to neighbor" (forwarding vs local delivery)
- "Unknown packet type", "handler not found"

**Code Files to Verify:**
- VirtualNode.kt routing logic (lines 894-950)
  - Broadcast detection: `if (toAddr == ADDR_BROADCAST)`
  - Deduplication: `seenBroadcasts.putIfAbsent(broadcastId, now)`
  - Forwarding: Router/Hub roles forward to neighbors
  - Local delivery: `broadcastMessageHandler?.onReceiveBroadcastPacket(packet)`
- BroadcastMessageHandler.kt `onReceiveBroadcastPacket()` method

**Evidence Required:**
- Count of packets routed to handler
- Deduplication check results (first seen vs already seen)
- Differentiation between forwarding and local delivery
- Any routing errors or failures

**Critical Verification:**
- ❓ Does routed count match received count?
- ❓ If not, is deduplication preventing delivery? (check if sender's own broadcast)
- ❓ Are broadcasts from OTHER nodes delivered locally? (correct)
- ❓ Are broadcasts from THIS node delivered locally? (incorrect - Issue #1)
- ❓ Is local delivery happening INSIDE or OUTSIDE dedup check?

---

### Step 6: Chunk Processing (Receiver)
**Objective:** Verify handler correctly processes and stores chunks

**Required Actions:**
1. ✅ Find handleBroadcastChunk() logs for each chunk
2. ✅ Extract chunk indices processed (0, 1, 2, ...)
3. ✅ Count total chunks processed
4. ✅ Find hash validation results for each chunk
5. ✅ Track receivedChunks.size progression over time
6. ✅ Find [BROADCAST_COMPLETE_CHECK] logs
7. ✅ Extract final receivedChunks.size value
8. ✅ Extract totalChunks value
9. ✅ Calculate gap: `totalChunks - receivedChunks.size`
10. ✅ Identify missing chunk indices (if any)
11. ✅ Check for:
    - Hash validation failures
    - Duplicate chunk handling
    - Storage errors

**Log Keywords to Search:**
- "handleBroadcastChunk", "chunk index", "hash validation"
- "[BROADCAST_COMPLETE_CHECK]", "receivedChunks", "totalChunks"
- "isComplete", "missing chunks"

**Code Files to Verify:**
- BroadcastMessageHandler.kt `handleBroadcastChunk()` method
- IncomingBroadcastState chunk storage logic

**Evidence Required:**
- Count of chunks processed (should match routed count)
- Hash validation pass/fail counts
- Final receivedChunks.size
- List of missing chunk indices (if gap > 0)

**Critical Verification:**
- ❓ Does processed count match routed count?
- ❓ If receivedChunks.size < totalChunks, which chunks are missing?
- ❓ Are missing chunks due to network loss or routing failure?

---

### Step 7: Completion Check (Receiver)
**Objective:** Verify completion detection logic works correctly

**Required Actions:**
1. ✅ Find isComplete() check logs
2. ✅ Verify logic: `receivedChunks.size == metadata.totalChunks`
3. ✅ Determine: Did isComplete() ever return true?
4. ✅ If NO:
   - Extract final receivedChunks.size vs totalChunks
   - Explain why incomplete (missing chunks)
5. ✅ If YES:
   - Find log line showing "isComplete=true"
   - Verify this triggers file reassembly

**Log Keywords to Search:**
- "isComplete", "[BROADCAST_COMPLETE_CHECK]"
- "all chunks received", "broadcast complete"

**Code Files to Verify:**
- BroadcastMessageHandler.kt completion check logic
- IncomingBroadcastState `isComplete()` method

**Evidence Required:**
- Final isComplete() return value
- Reason if false (receivedChunks vs totalChunks)

**Critical Verification:**
- ❓ If all chunks received, does isComplete() return true?
- ❓ If not, THIS IS THE BUG - completion logic is broken

---

### Step 8: File Reassembly (Receiver)
**Objective:** Verify chunks correctly reassembled into original file

**Required Actions:**
1. ✅ Find reassembleFile() call in logs
2. ✅ Extract reassembled byte count
3. ✅ Compare to original file size from sender
4. ✅ Verify chunks assembled in correct order (0, 1, 2, ...)
5. ✅ Check for:
   - Missing chunks causing gaps
   - Incorrect chunk ordering
   - ByteArray allocation failures

**Log Keywords to Search:**
- "reassembleFile", "reassembled", "bytes"
- "file reconstruction", "chunk assembly"

**Code Files to Verify:**
- BroadcastMessageHandler.kt `reassembleFile()` method

**Evidence Required:**
- Reassembled byte count
- Comparison to original size
- Any reassembly errors

**Critical Verification:**
- ❓ Does reassembled size match original file size?
- ❓ If not, which chunks are corrupted or missing?

---

### Step 9: Folder Creation (Receiver)
**Objective:** Verify SharedWithMe folder exists and is writable

**Required Actions:**
1. ✅ Find drop folder resolution logs
2. ✅ Extract drop folder path
3. ✅ Find SharedWithMe subfolder creation attempt
4. ✅ Verify folder exists after creation
5. ✅ Check for:
   - Drop folder not configured (callback returns null)
   - Permission errors
   - Storage unavailable
   - Path resolution failures

**Log Keywords to Search:**
- "drop folder", "getDropFolder", "SharedWithMe"
- "mkdirs", "folder creation", "storage"
- "Permission denied", "folder not configured"

**Code Files to Verify:**
- BroadcastMessageHandler.kt `writeBroadcastFile()` method
- MeshrabiyaApiImpl.kt or equivalent `getDropFolder()` method

**Evidence Required:**
- Drop folder path (or null if not configured)
- SharedWithMe folder creation success/failure
- Any permission or storage errors

**Critical Verification:**
- ❓ Is drop folder configured?
- ❓ If not, THIS IS THE BUG - user configuration error
- ❓ Does SharedWithMe folder exist and have write permissions?

---

### Step 10: File Writing (Receiver)
**Objective:** Verify file successfully written to storage

**Required Actions:**
1. ✅ Find writeBroadcastFile() call in logs
2. ✅ Extract target file path
3. ✅ Find file write operation (FileOutputStream, etc.)
4. ✅ Verify bytes written
5. ✅ Extract final file path returned
6. ✅ Check for:
   - File already exists (duplicate handling)
   - Write permission errors
   - Disk full errors
   - I/O exceptions

**Log Keywords to Search:**
- "writeBroadcastFile", "writing file", "file written"
- "FileOutputStream", "write bytes"
- "file already exists", "duplicate", "I/O error"

**Code Files to Verify:**
- BroadcastMessageHandler.kt `writeBroadcastFile()` method

**Evidence Required:**
- Target file path
- Bytes written
- Success/failure status
- Any I/O errors

**Critical Verification:**
- ❓ Was file successfully written to storage?
- ❓ If not, what error occurred? (permissions, disk space, etc.)

---

### Step 11: Notification Creation (Receiver)
**Objective:** Verify user notified of broadcast reception ONLY when file successfully received

**Required Actions:**
1. ✅ Find BroadcastReceivedDto creation in logs
2. ✅ Verify DTO contents:
   - Broadcast ID
   - Sender node ID
   - Message text
   - File name
   - File path (or empty if text-only)
   - **hasError flag** (CRITICAL - see Issue #2)
   - Error message (if hasError=true)
3. ✅ Find listener callback invocations
4. ✅ **Error handling check:** Verify notification ONLY created when hasError=false
   - If file write failed, check for "Skipping listener notification for failed file transfer"
   - Verify notification count does NOT increment on errors
5. ✅ Count listeners notified
6. ✅ Find UI layer notification handling
7. ✅ Verify notification added to receivedBroadcasts list
8. ✅ Verify badge count updated
9. ✅ Verify notification appears in dropdown
10. ✅ Check for:
    - Listener not registered
    - UI update failures
    - Notification lost
    - **Notification created despite hasError=true (Issue #2 bug)**

**Log Keywords to Search:**
- "BroadcastReceivedDto", "notification", "hasError"
- "Skipping listener notification for failed file transfer"
- "receiveListeners", "listener callback"
- "receivedBroadcasts.add", "updateNotificationBadge"
- "notification badge", "dropdown"
- "Creating notification DTO: hasError="

**Code Files to Verify:**
- BroadcastMessageHandler.kt listener notification logic
  - Lines 495-540: File broadcast notification (check hasError handling)
  - Lines 821-853: Text broadcast notification (check outgoingBroadcasts check)
- EnhancedMeshFragment.kt or equivalent UI notification handling

**Evidence Required:**
- BroadcastReceivedDto contents (especially hasError flag)
- Listener callback count
- UI update logs
- Badge count change
- Whether notification skipped on file write error

**Critical Verification:**
- ❓ Were listeners notified?
- ❓ If file write failed (hasError=true), were listeners SKIPPED? (correct)
- ❓ If file write failed, was notification created anyway? (Issue #2 bug)
- ❓ If not, are listeners registered?
- ❓ Did UI receive and display notification?
- ❓ Does badge count increment only for successful transfers?
- ❓ If not, THIS IS THE BUG - notification delivery failure or error handling bug

---

### Step 11.5: Error Notification Check (Receiver) ⚠️ CRITICAL
**Objective:** Verify notifications ONLY created for successful broadcasts, NOT for errors

**Issue #2 Context:**
When file broadcasts fail (e.g., file write error, no drop folder), the system should NOT create user-visible notifications or increment badge counts. Only successful broadcasts should appear in the notification dropdown.

**Required Actions:**
1. ✅ Find BroadcastReceivedDto creation logs
2. ✅ Extract hasError flag value
3. ✅ Verify listener notification logic:
   - If hasError=false: Listeners should be notified ✅
   - If hasError=true: Listeners should NOT be notified ❌
4. ✅ Find "Skipping listener notification for failed file transfer" logs
5. ✅ Verify badge count updates:
   - Only increment on hasError=false
   - Skip increment on hasError=true
6. ✅ Check UI receivedBroadcasts list:
   - Successful broadcasts should be added
   - Failed broadcasts should NOT be added
7. ✅ Verify error handling:
   - hasError=true set when file write fails
   - errorMessage populated with reason
   - Notification creation skipped (not just created with error flag)

**Log Keywords to Search:**
- "Creating notification DTO: hasError=true" (should NOT trigger listener)
- "Skipping listener notification for failed file transfer"
- "hasError=false" (normal success path)
- "Badge updated: count=" (correlate with hasError state)
- "FILE_WRITE Failed" (error that should prevent notification)

**Code Files to Verify:**
- BroadcastMessageHandler.kt notification creation logic (lines 495-540)
  - Check: `if (!hasError) { /* notify listeners */ }`
  - Error path should skip notification entirely
- EnhancedMeshFragment.kt broadcastListener callback
  - Should only receive callbacks for successful broadcasts
  - Badge count incremented only on successful broadcasts

**Evidence Required:**
- hasError flag value for each broadcast
- Whether listeners were notified when hasError=true
- Badge count changes correlated with hasError state
- Notification dropdown entries (should not include hasError=true)

**Critical Verification:**
- ❓ If file write failed (hasError=true), were listeners notified? ❌ BUG
- ❓ If hasError=true, was badge count incremented? ❌ BUG
- ❓ If hasError=true, does notification appear in dropdown? ❌ BUG
- ❓ Does notification creation happen INSIDE `if (!hasError)` check?

**Expected Behavior:**
- ✅ File write succeeds → hasError=false → notify listeners → badge++
- ✅ File write fails → hasError=true → skip notification → badge unchanged
- ✅ Error logged but user NOT shown notification

**Bug Indicators:**
- ❌ Logs show "hasError=true" AND "Notifying N listeners"
- ❌ Badge count incremented despite file write failure
- ❌ Notification dropdown shows entry with no file on disk
- ❌ Listener notification happens OUTSIDE `if (!hasError)` check

---

## VERIFICATION BY FALSIFICATION

After tracing all 11 steps, use falsification approach to identify root cause:

### Hypothesis Checklist

Test each hypothesis in order:

1. **❓ Chunks not transmitted?**
   - Check Step 3 evidence
   - If transmitted count < totalChunks: CONFIRMED
   - Otherwise: DISPROVEN

2. **❓ Network packet loss?**
   - Check Step 4 evidence
   - Calculate: (Step 3 sent - Step 4 received) / Step 3 sent
   - If loss rate > expected threshold: CONFIRMED
   - Otherwise: DISPROVEN

3. **❓ **⚠️ Sender loopback notification (Issue #1)?**
   - Check Step 3.5 evidence on SENDER device
   - If sender received own broadcast AND created notification: CONFIRMED - THIS IS BUG #1
   - If sender received own broadcast BUT skipped local delivery: DISPROVEN (working correctly)
   - Otherwise: DISPROVEN

4. **❓ Routing failure (VirtualNode → Handler)?**
   - Check Step 5 evidence
   - Calculate: Step 4 received - Step 5 routed
   - If routing loss > 0 on RECEIVER: CONFIRMED - THIS IS THE BUG
   - If routing loss > 0 on SENDER due to dedup: EXPECTED (not a bug)
   - Otherwise: DISPROVEN

5. **❓ Chunk processing bug?**
   - Check Step 6 evidence
   - Calculate: Step 5 routed - Step 6 processed
   - If processing loss > 0: CONFIRMED - THIS IS THE BUG
   - Otherwise: DISPROVEN

6. **❓ Completion logic bug?**
   - Check Step 7 evidence
   - If receivedChunks.size == totalChunks AND isComplete() returns false: CONFIRMED
   - Otherwise: DISPROVEN

7. **❓ File reassembly bug?**
   - Check Step 8 evidence
   - If reassembled size != original size: CONFIRMED
   - Otherwise: DISPROVEN

8. **❓ Folder configuration error?**
   - Check Step 9 evidence
   - If drop folder == null OR folder creation failed: CONFIRMED
   - Otherwise: DISPROVEN

9. **❓ File write failure?**
   - Check Step 10 evidence
   - If write operation failed with error: CONFIRMED
   - Otherwise: DISPROVEN

10. **❓ Notification delivery failure?**
    - Check Step 11 evidence
    - If listeners not notified OR UI not updated: CONFIRMED
    - Otherwise: DISPROVEN

11. **❓ **⚠️ Error notification created (Issue #2)?**
    - Check Step 11.5 evidence on RECEIVER device
    - If file write failed (hasError=true) AND listeners notified: CONFIRMED - THIS IS BUG #2
    - If hasError=true AND notification skipped: DISPROVEN (working correctly)
    - If badge count incremented despite hasError=true: CONFIRMED - THIS IS BUG #2
    - Otherwise: DISPROVEN

### Root Cause Determination

**The root cause is the FIRST hypothesis NOT DISPROVEN in the checklist.**

If all hypotheses are disproven, the system is working correctly and the issue is elsewhere (e.g., user expectations, test methodology).

---

## OUTPUT REQUIREMENTS

Agents MUST produce a document with the following structure:

```markdown
# Broadcast Workflow Analysis - [Broadcast ID]

## Test Scenario
- Device 1 (Sender): [ID]
- Device 2 (Receiver): [ID]
- Broadcast ID: [ID]
- File name: [name] (or "text-only")
- File size: [bytes]
- Total chunks: [count]

## Step-by-Step Trace

### Step 1: Initiation (Sender)
**Log Evidence:** [line numbers and exact log text]
**Findings:** [extracted data]
**Status:** ✅ PASS / ❌ FAIL

### Step 2: Chunking (Sender)
**Log Evidence:** [line numbers and exact log text]
**Findings:** [extracted data]
**Status:** ✅ PASS / ❌ FAIL

[... Continue for all 11 steps ...]

## Packet Count Summary

| Step | Description | Count | Loss |
|------|-------------|-------|------|
| 3 | Chunks transmitted | X | - |
| 4 | Packets received | Y | (X-Y) |
| 5 | Packets routed | Z | (Y-Z) |
| 6 | Chunks processed | W | (Z-W) |

## Verification by Falsification

- ❌ Chunks not transmitted? DISPROVEN [evidence]
- ❌ Network loss? DISPROVEN [evidence]
- ✅ Routing failure? CONFIRMED [evidence]
- ...

## Root Cause

**Location:** [Step number where workflow breaks]
**Issue:** [Description]
**Evidence:** [Log lines and code references]

## Proposed Fix

[Code changes with verified signatures]
```

---

## INTEGRATION WITH EXISTING RULES

This protocol is consistent with and extends the following existing AGENTS.md rules:

### MANDATORY CODE VERIFICATION BEFORE GENERATION (2026-02-13)
- This protocol enforces reading actual code at each step
- Verifies method signatures, properties, and data structures
- No assumptions based on error messages or guesses

### IMPLEMENTATION VERIFICATION BEFORE CODE GENERATION (2025-12-06)
- Step-by-step verification requires reading actual implementation
- Each step verified via literal file reads
- API signatures confirmed before proposing fixes

### VERIFICATION BY FALSIFICATION
- Hypothesis checklist explicitly uses falsification approach
- Each hypothesis tested with evidence
- Root cause is hypothesis NOT DISPROVEN

### NEVER ASSUME USER ERROR
- Protocol traces entire workflow before concluding
- User configuration errors identified only after verification
- System bugs separated from configuration issues

---

## WHEN TO USE THIS PROTOCOL

**ALWAYS use this protocol when:**
- User reports "broadcast not received"
- User reports "file not appearing in SharedWithMe"
- User reports "notification missing"
- User reports "incomplete file transfer"
- Investigating ANY broadcast or file transfer failure

**Do NOT skip steps or assume intermediate steps work.**

---

## EXAMPLE INVESTIGATION

See `BROADCAST_WORKFLOW_TRACE_02162026.md` for a complete example of this protocol in action, which revealed:

- ✅ All chunks transmitted (3,367/3,367)
- ✅ All packets received (0% loss)
- ✅ All packets routed correctly
- ✅ File reassembled perfectly
- ❌ Drop folder not configured (user configuration error)
- ❌ Exception handling bug prevented error notification

This would have been missed by only checking "are chunks being received?" and required tracing the COMPLETE workflow.

---

**END OF PROTOCOL**

**Date Added:** February 16, 2026  
**Trigger:** Agent incorrectly assumed broadcast completion failure without tracing complete workflow from initiation to notification.
