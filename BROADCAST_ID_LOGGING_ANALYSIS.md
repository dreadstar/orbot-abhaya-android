# Broadcast ID Logging Analysis - Scope, Risk, & Effort Assessment

**Date:** February 19, 2026  
**Purpose:** Evaluate adding broadcast ID as sub-tag to all broadcast workflow logs  
**Objective:** Solve UNKNOWN broadcast categorization problem in log analysis

---

## Executive Summary

**Proposal:** Add broadcast ID to all logging statements in the broadcast workflow lifecycle once the ID has been defined.

**Scope:** 5 core files, 4 of which are LARGE FILES (>800 lines)  
**Estimated Logging Points:** 60-80 log statements across entire workflow  
**Risk Level:** MEDIUM-HIGH (large file manual edits required)  
**Total Effort:** 6-10 hours (analysis + implementation + testing)

**Recommendation:** **PROCEED WITH CAUTION** - High value for debugging but significant implementation complexity due to large file rule.

---

## 1. SCOPE ANALYSIS

### 1.1 Core Broadcast Workflow Files

| File | Lines | Size Category | Broadcast Logs | Edit Method |
|------|-------|---------------|----------------|-------------|
| BroadcastMessageHandler.kt | 844 | **LARGE (>800)** | ~40 | **MANUAL** |
| VirtualNode.kt | 1,483 | **LARGE (>800)** | ~10 | **MANUAL** |
| EnhancedMeshFragment.kt | 1,931 | **LARGE (>800)** | ~15 | **MANUAL** |
| MeshrabiyaApiImpl.kt | 1,965 | **LARGE (>800)** | ~5 | **MANUAL** |
| VirtualNodeDatagramSocket.kt | 112 | Small | ~3 | Automated |

**Total:** 5 files, **4 require manual editing** per AGENTS.md LARGE FILE RULE.

---

### 1.2 Broadcast Workflow Lifecycle (11 Steps)

**Broadcast ID Availability by Step:**

| Step | Location | File | Broadcast ID Available? | Current Logging |
|------|----------|------|------------------------|-----------------|
| 1. Initiation | sendBroadcast() | BroadcastMessageHandler.kt:113 | ✅ YES (line 138) | Has ID in some logs |
| 2. Chunking | sendBroadcast() | BroadcastMessageHandler.kt:148 | ✅ YES | Has ID |
| 3. Transmission | sendBroadcast() | BroadcastMessageHandler.kt:248-340 | ✅ YES | Has ID |
| 4. Network Reception | route() | VirtualNode.kt:649 | ❌ NO (packet parsing) | **Missing ID** |
| 5. Routing to Handler | route() | VirtualNode.kt:908-920 | ⚠️ PARTIAL (computed) | Has ID in some logs |
| 6. Chunk Processing | handleBroadcastChunk() | BroadcastMessageHandler.kt:425-500 | ✅ YES (extracted) | **Inconsistent** |
| 7. Completion Check | handleBroadcastChunk() | BroadcastMessageHandler.kt:480-490 | ✅ YES | Has ID |
| 8. File Reassembly | handleBroadcastChunk() | BroadcastMessageHandler.kt:479 | ✅ YES | Has ID |
| 9. Folder Creation | writeBroadcastFile() | BroadcastMessageHandler.kt:732-740 | ❌ NO (static method) | **Missing ID** |
| 10. File Writing | writeBroadcastFile() | BroadcastMessageHandler.kt:745-760 | ❌ NO (static method) | **Missing ID** |
| 11. Notification | onTextOnlyBroadcastComplete() | BroadcastMessageHandler.kt:793-822 | ✅ YES | **Missing ID** |
| 11b. UI Callback | broadcastListener | EnhancedMeshFragment.kt:297-390 | ✅ YES (in DTO) | Has ID |

**Critical Findings:**

1. **Steps 4, 9, 10:** Broadcast ID NOT currently available in code context
2. **Step 6:** Broadcast ID inconsistently logged
3. **Step 11:** Broadcast ID available but NOT logged
4. **Steps 1-3, 7-8:** Already have broadcast ID in most logs

---

### 1.3 Detailed File Analysis

#### 1.3.1 BroadcastMessageHandler.kt (844 lines) 🔴 LARGE FILE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`

**Existing Broadcast Logs (sample from grep):**

```kotlin
Line 113: logger(Log.INFO, "$TAG Starting broadcast: message='$messageText', file='$filePath'")
Line 148: logger(Log.DEBUG, "$TAG Broadcast $broadcastId: file size=${fileBytes.size}, chunks=$totalChunks, hasFile=$hasFile")
Line 164: logger(Log.INFO, "$TAG Text-only broadcast $broadcastId: sending metadata packet only")
Line 248: logger(Log.INFO, "$TAG Broadcast $broadcastId: Starting batch ${batchNum + 1}/$totalBatches (chunks $batchStart-${batchEnd - 1})")
Line 317: logger(Log.DEBUG, "$TAG Broadcast $broadcastId chunk $chunkIndex: sending to ${neighbors.size} neighbor(s)")
Line 340: logger(Log.INFO, "$TAG Broadcast $broadcastId: $percentComplete% complete ($chunkIndex/$totalChunks chunks)")
Line 358: logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, all $totalChunks chunks sent")
```

**Patterns:**
- **GOOD:** Most logs already include `$broadcastId` in message
- **INCONSISTENT:** Some logs use `"BroadcastMessageHandler Broadcast $broadcastId: ..."` format
- **MISSING:** Initial log (line 113) does NOT have broadcast ID (not yet defined)

**Proposed Enhancement:**
Change `TAG` constant to support dynamic sub-tag:

**BEFORE:**
```kotlin
private const val TAG = "BroadcastMessageHandler"
logger(Log.INFO, "$TAG Broadcast $broadcastId: Starting batch...")
```

**AFTER:**
```kotlin
private const val TAG = "BroadcastMessageHandler"
private fun tag(broadcastId: String?) = if (broadcastId != null) "$TAG[$broadcastId]" else TAG

logger(Log.INFO, "${tag(broadcastId)} Starting batch...")
```

**Changes Required:**
- Add `tag()` helper function (1 new line)
- Update ~40 logger calls to use `tag(broadcastId)` instead of `TAG`
- Pass broadcast ID to helper methods (writeBroadcastFile, etc.)

**Complexity:** HIGH - Manual editing of large file required

---

#### 1.3.2 VirtualNode.kt (1,483 lines) 🔴 LARGE FILE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**Existing Broadcast Logs:**

```kotlin
Line 649: logger(Log.INFO, "$logPrefix: [PKT_CHECK] ✅ BROADCAST PACKET DETECTED (type=$packetTypeHex) - routing to BroadcastMessageHandler")
Line 908: logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=$roleType, hops remaining: ${packet.header.maxHops})")
Line 912: logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
Line 920: logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER or MESH_HUB, not forwarding")
```

**Issues:**
1. Line 649: Packet detected **BEFORE** broadcast ID extracted - ID not available
2. Line 908, 920: ID available (from `computeBroadcastId()`) but not in sub-tag
3. Line 912: Forwarding loop - ID available from parent scope but not logged

**Proposed Enhancement:**

**For logs WITH broadcastId available:**
```kotlin
// BEFORE
logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors...")

// AFTER
logger(Log.VERBOSE, "$logPrefix[broadcast:${broadcastId.take(8)}]: Packet not seen before, forwarding to neighbors...")
```

**For logs WITHOUT broadcastId (line 649):**
- Extract broadcast ID early from packet before logging
- OR: Keep current log and add follow-up log with ID

**Changes Required:**
- Extract broadcast ID earlier in route() flow (1-2 lines)
- Update ~10 logger calls to include broadcast ID sub-tag
- Update `computeBroadcastId()` calls to store result in variable

**Complexity:** HIGH - Manual editing of large file + logic refactoring

---

#### 1.3.3 EnhancedMeshFragment.kt (1,931 lines) 🔴 LARGE FILE

**File:** `app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt`

**Existing Broadcast Logs:**

```kotlin
Line 299: android.util.Log.d("EnhancedMeshFragment", 
    "[BROADCAST_LISTENER] ⚡ Callback invoked: id=${broadcast.broadcastId}, sender=${broadcast.senderNodeId}, ...")
Line 314: android.util.Log.d("EnhancedMeshFragment", 
    "[BROADCAST_LISTENER] Comprehensive diagnostics - Thread=${Thread.currentThread().name}, ...")
Line 351: android.util.Log.d("EnhancedMeshFragment", 
    "[BROADCAST_LISTENER] Constructing message - fileName='${broadcast.fileName}', filePath='${broadcast.filePath}'")
```

**Pattern:**
- Uses `android.util.Log.d()` instead of logger
- Already includes `id=${broadcast.broadcastId}` in messages
- Tag is always `"EnhancedMeshFragment"`

**Proposed Enhancement:**
```kotlin
// BEFORE
android.util.Log.d("EnhancedMeshFragment", "[BROADCAST_LISTENER] ⚡ Callback invoked: id=${broadcast.broadcastId}, ...")

// AFTER
android.util.Log.d("EnhancedMeshFragment[${broadcast.broadcastId.take(8)}]", "[BROADCAST_LISTENER] ⚡ Callback invoked, sender=${broadcast.senderNodeId}, ...")
```

**Changes Required:**
- Update ~15 Log.d() calls to use dynamic tag
- Remove `id=` from message body (now in tag)

**Complexity:** HIGH - Manual editing of large file

---

#### 1.3.4 MeshrabiyaApiImpl.kt (1,965 lines) 🔴 LARGE FILE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/api/MeshrabiyaApiImpl.kt`

**Location:** Lines 1862-1880 (broadcastMessageAndFile method)

**Current Logging:** Minimal - mostly delegates to BroadcastMessageHandler

**Proposed Enhancement:**
```kotlin
// Add logging at API boundary
betaLogger.log(LogLevel.DEBUG, "MeshrabiyaApi[broadcast:init]", 
    "broadcastMessageAndFile called: messageText='$messageText', file=${filePath ?: "none"}")
```

**Changes Required:**
- Add 2-3 new log statements at API boundaries
- Use broadcast ID from callback result

**Complexity:** MEDIUM - Manual editing of large file but minimal changes

---

#### 1.3.5 VirtualNodeDatagramSocket.kt (112 lines) ✅ SMALL FILE

**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNodeDatagramSocket.kt`

**Broadcast Logs:** Minimal - packet I/O layer

**Proposed Enhancement:**
- Add broadcast ID to packet send/receive logs if available in packet metadata

**Changes Required:**
- 2-3 log statement updates

**Complexity:** LOW - Small file, automated edits allowed

---

## 2. BROADCAST ID PROPAGATION REQUIREMENTS

### 2.1 Where Broadcast ID Must Be Added

**Missing Broadcast ID in these contexts:**

1. **writeBroadcastFile() static method (BroadcastMessageHandler.kt:732-760)**
   - **Current:** `private fun writeBroadcastFile(fileName: String, fileBytes: ByteArray): String`
   - **Required:** Add `broadcastId: String` parameter
   - **Callers:** Line 486 (handleBroadcastChunk)
   - **Impact:** 1 method signature change, 1 call site update

2. **VirtualNode.route() packet detection (VirtualNode.kt:649)**
   - **Current:** Logs BEFORE broadcast ID computed
   - **Required:** Compute broadcast ID earlier or add follow-up log
   - **Impact:** Refactor route() packet parsing logic

3. **Notification creation (BroadcastMessageHandler.kt:793-822)**
   - **Current:** `onTextOnlyBroadcastComplete()` has broadcast ID but doesn't log it in tag
   - **Required:** Update logger calls to use dynamic tag
   - **Impact:** 5-8 log statement updates

---

### 2.2 Propagation Strategy

**Option A: Sub-Tag Approach (Recommended)**

Use bracket notation for sub-tags:
```kotlin
"BroadcastMessageHandler[abc123]"
"VirtualNode[broadcast:abc123]"
"EnhancedMeshFragment[abc123]"
```

**Advantages:**
- Easy to parse in log analysis scripts
- Doesn't break existing log parsing
- Visually clear in logcat
- Consistent with structured logging best practices

**Disadvantages:**
- Requires manual editing of large files
- Must update ~60-80 log statements

**Option B: Message Body Only**

Keep tag constant, always include broadcast ID in message:
```kotlin
logger(Log.INFO, "BroadcastMessageHandler", "[$broadcastId] Starting broadcast...")
```

**Advantages:**
- Simpler implementation
- Works with current large file rule (message-only changes)

**Disadvantages:**
- Harder to filter in logcat by tag
- Requires parsing message body in all analysis tools
- Less structured

---

## 3. RISK ASSESSMENT

### 3.1 Technical Risks

| Risk | Severity | Probability | Mitigation |
|------|----------|-------------|------------|
| **Manual edit errors in large files** | HIGH | MEDIUM | Use BEFORE/AFTER code blocks with 10+ lines context |
| **Broadcast ID not available in context** | MEDIUM | LOW | Refactor to pass ID to helper methods |
| **Log statement syntax errors** | LOW | LOW | Automated build validation |
| **Performance impact (string concatenation)** | LOW | VERY LOW | Kotlin string templates are optimized |
| **Breaking existing log parsers** | MEDIUM | LOW | Use additive sub-tag format (backward compatible) |

### 3.2 AGENTS.md Compliance

**Large File Manual Edit Rule (AGENTS.md):**
> For any file exceeding 800 lines, agents must NOT attempt direct edits using replace_string_in_file or multi_replace_string_in_file tools.

**Implications:**
- BroadcastMessageHandler.kt (844 lines): **MANUAL**
- VirtualNode.kt (1,483 lines): **MANUAL**
- EnhancedMeshFragment.kt (1,931 lines): **MANUAL**
- MeshrabiyaApiImpl.kt (1,965 lines): **MANUAL**

**Required Format:**
```markdown
**File:** [absolute path]
**Location:** Lines X-Y

**BEFORE (Lines X-Y):**
[exact code with 5+ lines context]

**AFTER (Lines X-Y):**
[exact code with modification and context]

**Purpose:** [explanation]
```

**Total Manual Edit Blocks Required:** ~20-30 (across 4 large files)

---

### 3.3 Testing Requirements

**After implementation, must verify:**

1. **Log Filtering:**
   - Can filter by `BroadcastMessageHandler[abc123]` in logcat
   - Script correctly parses broadcast ID from tag

2. **Broadcast Workflow:**
   - Text-only broadcast completes (no broadcast ID in file write logs)
   - File broadcast completes (broadcast ID in all logs)
   - Multi-broadcast scenario (IDs don't collide)

3. **Error Scenarios:**
   - Broadcast ID null handling (fallback to base tag)
   - Exception logs include broadcast ID if available

---

## 4. EFFORT ESTIMATE

### 4.1 Breakdown by Phase

| Phase | Task | Estimated Hours | Notes |
|-------|------|-----------------|-------|
| **Phase 1: Analysis** | Review all log statements | 2 hours | ✅ Completed (this document) |
| **Phase 2: Design** | Finalize tag format & propagation strategy | 1 hour | Sub-tag approach recommended |
| **Phase 3: BroadcastMessageHandler.kt** | Update 40 log statements | 2 hours | Manual edits with BEFORE/AFTER |
| **Phase 4: VirtualNode.kt** | Refactor route() + update 10 logs | 1.5 hours | Extract broadcast ID earlier |
| **Phase 5: EnhancedMeshFragment.kt** | Update 15 log statements | 1 hour | Manual edits with BEFORE/AFTER |
| **Phase 6: MeshrabiyaApiImpl.kt** | Add 3 new log statements | 0.5 hours | Minimal changes |
| **Phase 7: VirtualNodeDatagramSocket.kt** | Update 3 log statements | 0.25 hours | Small file, automated |
| **Phase 8: Testing** | Build, deploy, test workflows | 1.5 hours | Text + file broadcasts |
| **Phase 9: Validation** | Update log filter script | 0.5 hours | Add broadcast ID extraction from tags |
| **Total** | | **10.25 hours** | Conservative estimate |

**Optimistic:** 6 hours (if no issues)  
**Realistic:** 10 hours  
**Pessimistic:** 16 hours (if major refactoring needed)

---

### 4.2 Effort Comparison vs. Value

**Value Delivered:**
- ✅ **Eliminates UNKNOWN broadcast categorization** - can group all logs by broadcast ID
- ✅ **Improves debugging** - filter logcat by specific broadcast
- ✅ **Enables per-broadcast analysis** - workflow completion tracking
- ✅ **Reduces false positives** - no more Camera2/Facebook UUID confusion

**Effort Required:**
- ❌ **10 hours total implementation time**
- ❌ **20-30 manual edit blocks** (large file rule)
- ❌ **High risk of typos/errors** in manual edits

**Recommendation:**
**PROCEED** - Value justifies effort, BUT:
1. Complete in focused session (avoid context switching)
2. Use automated testing for validation
3. Consider incremental approach (BroadcastMessageHandler.kt first, validate, then others)

---

## 5. ALTERNATIVE APPROACHES

### 5.1 Alternative: Extract Broadcast ID from Message Body

**Instead of changing tags, parse broadcast ID from existing messages:**

```python
# In filter_broadcast_logs.py
def extract_broadcast_id_from_message(message):
    # Pattern 1: "Broadcast abc123: ..."
    match = re.search(r'Broadcast ([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})', message)
    if match:
        return match.group(1)
    
    # Pattern 2: "id=abc123"
    match = re.search(r'id=([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})', message)
    if match:
        return match.group(1)
    
    return None
```

**Advantages:**
- ✅ **Zero code changes required**
- ✅ Works with existing logs immediately
- ✅ No large file edit risk

**Disadvantages:**
- ❌ Only works for logs that already have broadcast ID in message (60% coverage)
- ❌ Cannot solve Steps 4, 9, 10, 11 (broadcast ID not in message)
- ❌ More complex parsing logic in analysis scripts
- ❌ Doesn't help with logcat filtering

**Verdict:** **Good short-term solution, not comprehensive**

---

### 5.2 Alternative: Structured Logging Library

**Use structured logging with key-value pairs:**

```kotlin
// Using structured logging library
structuredLogger.info {
    tag = "BroadcastMessageHandler"
    broadcastId = broadcastId
    message = "Starting broadcast"
    fields = mapOf(
        "messageText" to messageText,
        "fileSize" to fileBytes.size
    )
}
```

**Advantages:**
- ✅ Machine-parseable logs
- ✅ Easy to filter/query
- ✅ Industry best practice

**Disadvantages:**
- ❌ Requires new dependency
- ❌ Large refactoring (100+ log statements across entire codebase)
- ❌ Learning curve for team
- ❌ Overkill for this specific problem

**Verdict:** **Future enhancement, not for immediate issue**

---

## 6. RECOMMENDED IMPLEMENTATION PLAN

### 6.1 Phased Approach (Incremental Value)

**Phase 1: High-Impact, Low-Risk** (3 hours)
1. BroadcastMessageHandler.kt: Add `tag()` helper function
2. Update 10 most critical logs (initiation, completion, errors)
3. Test with single broadcast
4. **Outcome:** 50% of UNKNOWN entries now categorized

**Phase 2: Network Layer** (2 hours)
1. VirtualNode.kt: Refactor route() to extract broadcast ID earlier
2. Update 10 broadcast routing logs
3. Test with multi-hop scenario
4. **Outcome:** Packet routing logs now have broadcast ID

**Phase 3: UI Layer** (1.5 hours)
1. EnhancedMeshFragment.kt: Update 15 UI callback logs
2. Test notification display
3. **Outcome:** Complete end-to-end tracing

**Phase 4: API Layer** (0.5 hours)
1. MeshrabiyaApiImpl.kt: Add 3 boundary logs
2. VirtualNodeDatagramSocket.kt: Update 3 I/O logs
3. **Outcome:** 100% coverage

**Phase 5: Validation & Documentation** (2 hours)
1. Update filter script to parse broadcast ID from tags
2. Re-run log analysis on test logs
3. Document new tag format in KNOWLEDGE.md
4. **Outcome:** Production-ready

**Total Incremental Effort:** 9 hours (vs. 10 hours for big-bang approach)  
**Advantage:** Can stop after Phase 1 if value proves insufficient

---

### 6.2 Implementation Checklist

**Pre-Implementation:**
- [ ] Read AGENTS.md LARGE FILE RULE (lines 1-50)
- [ ] Choose tag format (recommend: `TAG[broadcastId.take(8)]`)
- [ ] Create test broadcast scenario (text + file)

**BroadcastMessageHandler.kt:**
- [ ] Add `tag(broadcastId: String?)` helper function
- [ ] Update 40 logger() calls to use `tag(broadcastId)`
- [ ] Add broadcast ID parameter to `writeBroadcastFile()`
- [ ] Build & verify no compilation errors

**VirtualNode.kt:**
- [ ] Extract broadcast ID earlier in route() (before line 649)
- [ ] Update 10 logger() calls to include broadcast ID sub-tag
- [ ] Build & verify no compilation errors

**EnhancedMeshFragment.kt:**
- [ ] Update 15 Log.d() calls to use dynamic tag
- [ ] Remove redundant `id=` from message bodies
- [ ] Build & verify UI works

**MeshrabiyaApiImpl.kt:**
- [ ] Add 3 logger() calls at API boundaries
- [ ] Build & verify no compilation errors

**VirtualNodeDatagramSocket.kt:**
- [ ] Update 3 logger() calls (automated edits allowed)
- [ ] Build & verify no compilation errors

**Testing:**
- [ ] Deploy to Phone 1 + Phone 2
- [ ] Send text-only broadcast → verify logs have broadcast ID
- [ ] Send file broadcast → verify logs have broadcast ID through all 11 steps
- [ ] Check logcat filtering: `adb logcat | grep "BroadcastMessageHandler\["`
- [ ] Run filter script → verify UNKNOWN entries reduced to <100

**Documentation:**
- [ ] Update KNOWLEDGE.md with new log tag format
- [ ] Update BROADCAST_INVESTIGATION_PROTOCOL.md with tag parsing instructions
- [ ] Commit changes with detailed message

---

## 7. CONCLUSION

### 7.1 Summary

**Question:** Should we add broadcast ID as sub-tag to all broadcast workflow logs?

**Answer:** **YES, with incremental approach**

**Scope:**
- 5 files (4 large files requiring manual edits)
- 60-80 log statements to update
- 2-4 method signatures to change (add broadcast ID parameter)

**Risk:**
- MEDIUM-HIGH (manual edits in large files)
- Mitigatable with BEFORE/AFTER code blocks and incremental testing

**Effort:**
- 10 hours total (conservative estimate)
- Can be done incrementally (stop after Phase 1 if desired)

**Value:**
- Eliminates UNKNOWN categorization problem
- Enables per-broadcast workflow tracking
- Improves debugging capability significantly

### 7.2 Next Steps

**Immediate:**
1. User approval to proceed
2. Choose tag format: `TAG[broadcastId.take(8)]` vs. message-body-only
3. Start with Phase 1 (BroadcastMessageHandler.kt critical logs)

**Deferred:**
1. Group UNKNOWN entries by broadcast context (manual analysis with current logs)
2. After implementation: Re-run log analysis to validate improvement

---

## 8. APPENDIX: Log Tag Format Examples

### 8.1 Proposed Tag Format

**Base Tag:**
```
BroadcastMessageHandler
```

**With Broadcast ID Sub-Tag:**
```
BroadcastMessageHandler[abc12345]
```

**Logcat Filter:**
```bash
adb logcat | grep "BroadcastMessageHandler\[abc12345\]"
```

**Python Parsing:**
```python
import re

def parse_broadcast_tag(tag):
    match = re.match(r'(\w+)\[([0-9a-f]{8})\]', tag)
    if match:
        return {
            'base_tag': match.group(1),
            'broadcast_id': match.group(2)
        }
    return {'base_tag': tag, 'broadcast_id': None}

# Example
parse_broadcast_tag("BroadcastMessageHandler[abc12345]")
# → {'base_tag': 'BroadcastMessageHandler', 'broadcast_id': 'abc12345'}
```

### 8.2 Example Log Output

**BEFORE:**
```
11:23:45.123 I BroadcastMessageHandler: Broadcast abc12345-1234-1234-1234-123456789012: Starting batch 1/10
11:23:45.456 D VirtualNode: Broadcast packet abc12345-1234-1234-1234-123456789012 not seen before, forwarding
11:23:46.789 D EnhancedMeshFragment: [BROADCAST_LISTENER] ⚡ Callback invoked: id=abc12345-1234-1234-1234-123456789012
```

**AFTER:**
```
11:23:45.123 I BroadcastMessageHandler[abc12345]: Starting batch 1/10
11:23:45.456 D VirtualNode[broadcast:abc12345]: Packet not seen before, forwarding
11:23:46.789 D EnhancedMeshFragment[abc12345]: [BROADCAST_LISTENER] ⚡ Callback invoked
```

**Benefits:**
- ✅ 40% shorter message text
- ✅ Easy to filter by broadcast ID  
- ✅ Clear visual grouping in logcat
- ✅ Machine-parseable tag format

---

**END OF ANALYSIS**
