# Plan Document Structure

Full detail for **Phase 4 — Plan Document** of the Abhaya Planning skill.

---

## Overview

The plan document captures **architecture decisions** and **change rationale**
at the file and method level. It is not a code patch document — that is the
implementation spec. The plan document answers: *What changes? Why? In what order?
What are the risks?*

---

## Output File

**Naming:** `[FEATURE]_REFACTOR_PLAN.md` (e.g. `VPN_GATEWAY_REFACTOR_PLAN.md`)  
**Location:** Project root  
**Size:** Typically 700–1100 lines depending on change scope

---

## Section Template

### §0 — Purpose

```markdown
## §0 Purpose

**Why this change:**
[1–3 sentences on the user-facing or architectural motivation]

**What changes:**
[Bullet list of the major removals / additions]

**What does NOT change:**
[Explicit scope boundary — prevents scope creep]
```

### §1 — Architecture Change Summary

Include an ASCII before/after flow diagram. Example:

```
BEFORE:
  Fragment → ViewModel.connect(ssid, passphrase)
           → MeshrabiyaApi.connectToNonMeshWifi()
           → VpnGatewayState.nonMeshConnected = true

AFTER:
  Fragment → ViewModel.connect()
           → MeshrabiyaApi.connectToBestAvailableNetwork()
           → VpnGatewayState.gatewayMode = AUTO
```

The diagram must be readable without knowing the code.

---

### §2–N — Per-File Change Sections

**One section per file to be modified.** Ordering:

1. **New standalone types** (data classes, enums) that nothing else depends on yet
2. **API interfaces** before their implementations
3. **Implementations** in dependency order (deepest service layer first)
4. **Fragment/Activity/Composable** UI changes last
5. **Dead code deletion** last of all (safest — done after all callers are removed)

**Section format:**

```markdown
## §N — [FileName.kt]

**File:** `absolute/path/to/FileName.kt`  
**Change type:** [MODIFY | DELETE | NEW]

### N.1 — [What is changing]

**Current state (line X):**
[verbatim declaration — from read_file during discovery]

**Proposed change:**
[description of change in prose]

**Rationale:**
[why this specific change is needed]

**Risk:** [LOW | MEDIUM | HIGH] — [brief reason]
```

For methods being **removed**, include a call-site table:

```markdown
### N.2 — Remove `methodName()`

**Current declaration:** `fun methodName(param: Type): ReturnType` at line 247

**All callers (must all be removed or migrated):**

| Caller file | Line | Notes |
|-------------|------|-------|
| Fragment.kt | 891 | Direct call — will be removed in §M |
| TestFile.kt | 45  | Unit test — will be deleted |
```

---

### §(N+1) — Implementation Order

A numbered sequence of steps that ensures the project compiles cleanly after
each step. Ordered by dependency (things with no dependents first):

```markdown
## §(N+1) — Implementation Order

1. Create `NewDtoClass.kt` with updated fields
2. Update `ApiInterface.kt` to add new method signatures
3. Update `ApiImpl.kt` to implement new methods (keep old ones for now)
4. Update `Fragment.kt` to call new methods
5. Remove old method bodies from `ApiImpl.kt`
6. Remove old method signatures from `ApiInterface.kt`
7. Delete dead `OldDtoClass.kt`
...
```

**Rule:** Never remove an interface method before removing all callers.
Never remove a caller before confirming its replacement is in place.

---

### §(N+2) — Verification Checklist

```markdown
## §(N+2) — Verification Checklist

Before submitting:
- [ ] Project compiles with zero errors
- [ ] All removed symbols return 0 results from grep_search
- [ ] All new methods have at least one caller
- [ ] No `~line` approximations remain in this document
- [ ] All ⚠️ Open risks resolved
- [ ] Dead code table has been audited
- [ ] Implementation spec BEFORE patterns were grep-confirmed as unique
```

---

### §(N+3) — Known Risks

This section tracks every architectural uncertainty from the whole planning process.
It starts populated from Phase 2 and ends with all rows showing `✅ Resolved`.

```markdown
## §(N+3) — Known Risks

| # | Risk | Status | Resolution |
|---|------|--------|------------|
| 1 | Will removing bindSocket break UDP routing? | ✅ Resolved | Read TunFd.kt L412: routing uses SO_MARK not bindSocket |
| 2 | Is connectToNonMeshWifi called in any test file? | ✅ Resolved | grep_search: 0 matches in test/ dirs |
| 3 | Does VpnGatewayState.nonMeshConnected have any direct observers? | ✅ Resolved | grep_search: 2 observers, both in Fragment.kt §4-migrated |
```

**Policy:** This section must contain zero `⚠️ Open` rows before the plan is
considered complete. If a risk cannot be resolved by research, escalate to the
user before writing the implementation spec.

---

### §(N+4) — Out of Scope

Explicit list of what is **not** being changed. Prevents reviewers from asking
"why isn't X covered?"

```markdown
## §(N+4) — Out of Scope

- Tor circuit management (separate subsystem, no API overlap)
- Battery optimisation logic in BatteryMonitorService.kt
- Unit test rewrites (follow-up task)
- Back-compat for pre-existing persisted state
```

---

## Research Citation Pattern

When a plan section was uncertain and resolved through a research round, cite it
inline:

```markdown
### 7.3 — Remove `nonMeshHasInternet` field

Research confirmed (round 2): This field is set only in `MeshrabiyaApiImpl.kt`
at L677 and L715:
- L677: `_nonMeshHasInternet.value = hasInternet`  (inside `onNonMeshNetworkConnected`)
- L715: `_nonMeshHasInternet.value = false`  (inside `onNonMeshNetworkDisconnected`)
There are no other set sites. Both of these will be removed in §7.
```

---

## Quality Gates Before Phase 5

Do not start the implementation spec until:

- [ ] All sections §0–§(N+4) are complete
- [ ] Every file path in the plan is absolute and confirmed to exist via `file_search`
- [ ] Every line number is exact (no `~line` markers remain)
- [ ] Every method signature is verbatim from disk (from read_file)
- [ ] Risk table has 0 rows with `⚠️ Open`
- [ ] Implementation order section is present and logically correct
