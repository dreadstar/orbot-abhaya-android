# Implementation Specification Structure

Full detail for **Phase 5 — Implementation Specification** of the Abhaya Planning skill.

---

## Overview

The implementation spec is a separate document from the plan. It contains the
actual BEFORE/AFTER code for every change — verified against disk — organized so
that an agent (or developer) can apply changes mechanically without any ambiguity.

---

## Output File

**Naming:** `[FEATURE]_REFACTOR_IMPLEMENTATION.md`  
**Location:** Project root  
**Size:** Typically 800–1500 lines depending on change count

---

## Mandatory Header

Every implementation spec must open with this header:

```markdown
# [Feature] Implementation Spec

**Rule:** Changes listed **bottom-to-top** within each file (highest line number
first) so that edits do not shift line numbers for subsequent changes in the same
file.

All BEFORE/AFTER snippets are verified against disk. Each shows ≥5 lines of
verbatim surrounding context on both sides of the changed lines.

**Applies AGENTS.md rule:** Large File Manual Edit Rule applies to files >800 lines
(present BEFORE/AFTER, do not call replace_string_in_file directly).
```

---

## Table of Contents

Include a table of contents with file section anchors:

```markdown
## Contents

1. [NewType.kt — new file](#1-newtypekt--new-file)
2. [ApiInterface.kt — method signatures](#2-apiinterfacekt--method-signatures)
3. [ApiImpl.kt — implementation changes](#3-apiimplkt--implementation-changes)
4. [Fragment.kt — UI wiring](#4-fragmentkt--ui-wiring)
5. [Dead code to delete](#5-dead-code-to-delete)
6. [Implementation order](#6-implementation-order)
```

---

## File Ordering (Across Files)

Files are ordered by **dependency** — lowest-level first, UI layer last:

1. **New standalone files** (no incoming dependencies yet)
2. **DTOs and data classes** used by multiple other files
3. **API interface files** (before their implementations)
4. **API implementation files**
5. **Manager / service layer** that calls the API
6. **Fragment / Activity / Composable** (UI layer, highest level)
7. **Dead code deletions** (last — done after all callers are removed)

This ordering means that after completing each file section, the codebase
compiles (or would compile with only unused-import warnings).

---

## Change Ordering Within a File (Bottom-to-Top)

Within a single file section, list changes with the **highest line number first**.

**Why:** When applying changes sequentially, editing line 900 first does not shift
the context of the edit at line 450. If you edited line 450 first, line 900 would
become approximately line N±delta, making the BEFORE text stale.

```markdown
## 3. ApiImpl.kt

### Change 3-1 — Remove `onNonMeshNetworkDisconnected` callback (lines 715–730)
[highest in file — do this first]

### Change 3-2 — Update `onNonMeshNetworkConnected` callback (lines 677–695)
[next highest]

### Change 3-3 — Remove `_nonMeshHasInternet` field declaration (lines 34–35)
[lowest — do this last within this file]
```

---

## Per-Change Section Format

```markdown
### Change N-M — [verb] [symbol] (lines P–Q)

**File:** `/absolute/path/to/File.kt`  
**Lines:** P–Q  
**Type:** [ADD | MODIFY | DELETE]

**BEFORE (lines P–Q):**
```kotlin
[≥5 lines verbatim before the changed code]
[the lines being replaced — exactly as they appear on disk]
[≥5 lines verbatim after the changed code]
```

**AFTER:**
```kotlin
[same ≥5 lines verbatim before (unchanged)]
[the replacement code — complete, no ellipsis, no TODO stubs]
[same ≥5 lines verbatim after (unchanged)]
```

**Import to add:** `import com.example.NewType` *(if applicable)*  
**Import to remove:** `import com.example.OldType` *(if applicable)*  
**Pattern uniqueness:** Verified — grep_search returned 1 match at line P
```

---

## BEFORE/AFTER Quality Requirements

### Verbatim Context

Both BEFORE and AFTER must include **≥5 lines of verbatim surrounding context**
on each side — lines that are NOT being changed but uniquely identify the location.

```kotlin
// ❌ BAD — insufficient context, ambiguous location
/**BEFORE (lines 247-248):**
fun connectToNonMeshWifi(ssid: String, passphrase: String)

// ✅ GOOD — 5 lines context each side, unambiguous
**BEFORE (lines 243-255):**
    private val connectionPool = ConnectionPool()

    @Throws(IOException::class)
    override suspend fun connectToNonMeshWifi(
        ssid: String,
        passphrase: String
    ): Boolean {
        return connectionPool.connect(ssid)
    }

    override suspend fun disconnect() {
```

### No Ellipsis, No Stubs

```kotlin
// ❌ BAD
**AFTER:**
    override suspend fun connectToBestNetwork(): Boolean {
        // TODO: implement
        ...
    }

// ✅ GOOD — complete replacement code
**AFTER:**
    override suspend fun connectToBestNetwork(): Boolean {
        return gatewayManager.selectAndConnect()
    }
```

### Exact Line Numbers

Line numbers in section headings must match the BEFORE text exactly. Verify with:
```
read_file(filePath=<file>, startLine=P-5, endLine=Q+5)
```

---

## Pattern Uniqueness Verification

Before including any BEFORE/AFTER pair in the spec, verify the BEFORE text
appears exactly once in its file:

```
grep_search(query="[3+ distinctive lines from BEFORE text]", isRegexp=false, includePattern="FileName.kt")
→ Expected: exactly 1 match
→ 0 matches: BEFORE text doesn't match disk — re-read the file
→ 2+ matches: add more context lines until unique
```

Document the verification result in the section:
```
**Pattern uniqueness:** Verified — grep_search on "override suspend fun connectToNonMeshWifi" returned 1 match at line 247
```

---

## New File Sections

For entirely new files, provide the complete file content:

```markdown
## 1. NewType.kt — new file

**File:** `/absolute/path/to/NewType.kt`

**Complete content:**
```kotlin
package com.example.feature

data class NewType(
    val id: String,
    val value: Int,
    val enabled: Boolean = false
)
```

**Place in:** same package as `RelatedType.kt`
```

---

## Dead Code to Delete

Include a table of every symbol made orphan by the change:

```markdown
## 5. Dead Code to Delete

| Symbol | File | Lines | Reason | Delete in step |
|--------|------|-------|--------|----------------|
| `connectToNonMeshWifi()` | ApiImpl.kt | 247–260 | All callers removed in §3 | Step 9 |
| `_nonMeshHasInternet` field | ApiImpl.kt | 34 | No remaining collectors | Step 10 |
| `NonMeshWifiConnectionDto` | NonMeshWifiConnectionDto.kt | entire file | No remaining usages | Step 11 |
| `onNonMeshNetworkConnected()` | ApiImpl.kt | 677–695 | Event source removed | Step 9 |
```

For file-level deletions, note the command:
```markdown
**To delete `NonMeshWifiConnectionDto.kt`:** Remove file from project tree and
remove its import from all files listed above.
```

---

## Implementation Order Section

Close the document with a numbered implementation sequence:

```markdown
## 6. Implementation Order

Apply changes in this order to maintain a compilable project at each step:

1. Create `NewType.kt` (Change 1-1) — no dependencies, nothing breaks
2. Add new method signatures to `ApiInterface.kt` (Change 2-1) — interface change, compile
3. Add new method implementations to `ApiImpl.kt` (Change 3-4) — satisfies interface
4. Update `Fragment.kt` to call new methods (Change 4-1, 4-2) — callers now present
5. Remove old method bodies from `ApiImpl.kt` (Change 3-1, 3-2) — implementations gone
6. Remove old method signatures from `ApiInterface.kt` (Change 2-2) — contracts gone
7. Delete `OldType.kt` — no remaining callers
8. Clean up dead imports across all modified files
9. Build and verify zero compilation errors
```

**Rule:** Never remove an interface member before removing all callers.
Never remove a caller before confirming its replacement exists.

---

## Large File Rule

For files >800 lines, apply the **AGENTS.md Large File Manual Edit Rule**:
- Do NOT call `replace_string_in_file` directly
- Present BEFORE/AFTER as a snippet with line numbers
- User applies the change manually

Check file length before any edit:
```
read_file(filePath=<file>, startLine=1, endLine=1)
→ Output shows: "Lines 1 to 1 (XXXX lines total)"
→ If XXXX > 800: apply large-file rule
```

---

## Quality Gate

The implementation spec is complete when:

- [ ] Every file in the plan's §2–N has a corresponding section
- [ ] All changes within each file are ordered bottom-to-top
- [ ] Every BEFORE snippet is verified unique via grep_search
- [ ] Every AFTER snippet is complete code (no ellipsis, no TODO stubs)
- [ ] All new imports are listed explicitly
- [ ] All removed imports are listed explicitly
- [ ] Dead code table covers every symbol with zero remaining callers
- [ ] Implementation order section has no circular dependencies
- [ ] All line numbers match the current state of disk (no stale line references)
