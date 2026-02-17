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
**Objective:** Verify chunks transmitted from app layer through system to network

**Required Actions:**
1. ✅ App layer: Find broadcast send logs showing chunk-by-chunk transmission
2. ✅ Extract chunk indices sent (0, 1, 2, ..., totalChunks-1)
3. ✅ Find VirtualNode.route() calls for BROADCAST packet type
4. ✅ Find actual network transmission logs (UDP send, socket write)
5. ✅ Count total chunks transmitted
6. ✅ Verify destination address and port
7. ✅ Calculate transmission rate (chunks/second)
8. ✅ Check for transmission errors, retries, or failures

**Log Keywords to Search:**
- "Sending chunk", "BROADCAST packet", "route()"
- "UDP send", "DatagramSocket", "sendto"
- Destination IP, destination port

**Code Files to Verify:**
- BroadcastMessageHandler.kt `sendBroadcast()` transmission loop
- VirtualNode.kt `route()` method
- VirtualNodeDatagramSocket.kt or equivalent

**Evidence Required:**
- Count of chunks transmitted (should equal totalChunks)
- Transmission rate
- Destination address
- Any transmission errors

**Critical Verification:**
- ❓ Did ALL chunks get transmitted? (count == totalChunks)
- ❓ Any network errors or socket failures?

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

**Required Actions:**
1. ✅ Find VirtualNode.route() or equivalent routing logic for BROADCAST packets
2. ✅ Count packets routed to BroadcastMessageHandler
3. ✅ Verify packet type detection (BROADCAST vs other types)
4. ✅ Find BroadcastMessageHandler.handlePacket() invocations
5. ✅ Calculate routing loss: `receivedAtNode - routedToHandler`
6. ✅ Check for:
   - Routing errors
   - Unknown packet types
   - Handler not registered

**Log Keywords to Search:**
- "route()", "BROADCAST packet", "routing to handler"
- "handlePacket", "BroadcastMessageHandler"
- "Unknown packet type", "handler not found"

**Code Files to Verify:**
- VirtualNode.kt routing logic
- BroadcastMessageHandler.kt `handlePacket()` method

**Evidence Required:**
- Count of packets routed to handler
- Any routing errors or failures

**Critical Verification:**
- ❓ Does routed count match received count?
- ❓ If not, THIS IS THE BUG - packets received but not routed to handler
- ❓ This was the PREVIOUS bug fixed by connectionExecutor migration

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
**Objective:** Verify user notified of broadcast reception

**Required Actions:**
1. ✅ Find BroadcastReceivedDto creation in logs
2. ✅ Verify DTO contents:
   - Broadcast ID
   - Sender node ID
   - Message text
   - File name
   - File path (or empty if text-only)
   - Error status
   - Error message
3. ✅ Find listener callback invocations
4. ✅ Count listeners notified
5. ✅ Find UI layer notification handling
6. ✅ Verify notification added to receivedBroadcasts list
7. ✅ Verify badge count updated
8. ✅ Verify notification appears in dropdown
9. ✅ Check for:
   - Listener not registered
   - UI update failures
   - Notification lost

**Log Keywords to Search:**
- "BroadcastReceivedDto", "notification"
- "receiveListeners", "listener callback"
- "receivedBroadcasts.add", "updateNotificationBadge"
- "notification badge", "dropdown"

**Code Files to Verify:**
- BroadcastMessageHandler.kt listener notification logic
- EnhancedMeshFragment.kt or equivalent UI notification handling

**Evidence Required:**
- BroadcastReceivedDto contents
- Listener callback count
- UI update logs
- Badge count change

**Critical Verification:**
- ❓ Were listeners notified?
- ❓ If not, are listeners registered?
- ❓ Did UI receive and display notification?
- ❓ If not, THIS IS THE BUG - notification delivery failure

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

3. **❓ Routing failure (VirtualNode → Handler)?**
   - Check Step 5 evidence
   - Calculate: Step 4 received - Step 5 routed
   - If routing loss > 0: CONFIRMED - THIS IS THE BUG
   - Otherwise: DISPROVEN

4. **❓ Chunk processing bug?**
   - Check Step 6 evidence
   - Calculate: Step 5 routed - Step 6 processed
   - If processing loss > 0: CONFIRMED - THIS IS THE BUG
   - Otherwise: DISPROVEN

5. **❓ Completion logic bug?**
   - Check Step 7 evidence
   - If receivedChunks.size == totalChunks AND isComplete() returns false: CONFIRMED
   - Otherwise: DISPROVEN

6. **❓ File reassembly bug?**
   - Check Step 8 evidence
   - If reassembled size != original size: CONFIRMED
   - Otherwise: DISPROVEN

7. **❓ Folder configuration error?**
   - Check Step 9 evidence
   - If drop folder == null OR folder creation failed: CONFIRMED
   - Otherwise: DISPROVEN

8. **❓ File write failure?**
   - Check Step 10 evidence
   - If write operation failed with error: CONFIRMED
   - Otherwise: DISPROVEN

9. **❓ Notification delivery failure?**
   - Check Step 11 evidence
   - If listeners not notified OR UI not updated: CONFIRMED
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
