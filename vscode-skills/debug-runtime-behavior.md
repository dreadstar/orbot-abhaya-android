---
name: debug-runtime-behavior
description: >
  Sub-skill B for wrong-behavior and crash debugging. Use when: (1) the app crashes or
  produces a FATAL EXCEPTION, (2) behavior is wrong but no compile error exists — UI does
  not update, state not propagated, callback not fired, feature incomplete, data not saved,
  intermittent failure. This skill uses victim-first analysis, ranked hypothesis formation
  with explicit falsification, and evidence-first root cause declaration. Never start with
  sender-side analysis — always find the last correct observed state on the affected device
  first. Invoked by debug-triage; can also be invoked directly for targeted analysis.
---

# Debug Runtime Behavior — Sub-skill B

Covers crashes, wrong behavior, incomplete state, and feature failures detected at runtime.  
All facts must come from live tool reads. Comments and markdown docs are never sources of truth.

---

## MANDATORY RESPONSE HEADER

Every response must open with:

```
═══════════════════════════════════════════════════════
ACTIVE MODE    : [INFORMATIONAL | PATCH]
FAILURE MODE   : [crash | behavior]
CURRENT PHASE  : [phase name and number]
TOOL CALLS MADE: [list of reads/searches this turn, or NONE]
FILE MUTATIONS : [NONE | list — PATCH mode only]
═══════════════════════════════════════════════════════
```

---

## PHASE B0 — Symptom Capture

**Goal:** Lock in the exact failure description before touching any log or code.

Capture the following before reading any file:

```
SYMPTOM RECORD
  Failure mode    : [crash | behavior]
  Affected device : [device name / role — e.g. "Phone 2 / STA / receiver"]
  Failure summary : [one sentence — what was supposed to happen, what happened instead]
  Transaction ID  : [broadcast ID, session ID, or other unique identifier — if known]
  Log files       : [Phone 1 log: name, Phone 2 log: name, sizes via read_file line 1 probe]
  Crash signal    : [FATAL EXCEPTION line verbatim, if crash mode]
```

**Phase gate:**
```
B0 COMPLETE — symptom locked, logs identified
```

---

## PHASE B1 — Victim-First Failure Window

**RULE: Analysis ALWAYS starts on the affected/victim device, never the sender.**

Definition of victim: the device that failed to reach the expected final state.

Steps:

1. **Identify the last correct log line** on the victim device: the last line that proves
   the transaction was still progressing normally toward completion.

2. **Identify the first wrong log line**: the first line that proves failure, or the last
   line in the log if the transaction simply stops (silent failure).

3. **Define the failure window**: all log lines between last correct and first wrong.

4. Record:
```
VICTIM FAILURE WINDOW
  Device          : [device name]
  Last correct    : [line number] — [verbatim log line]
  First wrong     : [line number] — [verbatim log line, or "SILENT — log ends normally"]
  Window size     : [N lines between]
  Key observations: [list of anything unusual in the window]
```

5. **Only after completing B1 on the victim** — read the sender log for the same time window
   if needed to understand what the sender was doing.

**Phase gate:**
```
B1 COMPLETE — failure window identified, victim-first rule applied
```

---

## PHASE B2 — Hypothesis Formation

**Goal:** Generate ranked hypotheses with explicit falsification tests.

Rules:
- Minimum 3 hypotheses. Maximum 6.
- Each must be specific and falsifiable — not "something went wrong" but "X failed because Y,
  which predicts we will see Z in the log."
- Rank by probability (most likely first) based only on B0 and B1 evidence.
- Each hypothesis must have an explicit falsification test that would **disprove** it.
- Do not include hypotheses that cannot be tested with available logs or code.

Format:
```
HYPOTHESIS #N (probability: high/medium/low)
  Claim         : [specific mechanism that would cause the observed failure]
  Predicts      : [what log evidence would confirm this hypothesis]
  Falsified by  : [what log evidence would definitively rule this out]
  Status        : [ ] UNTESTED
```

**Phase gate:**
```
B2 COMPLETE — [N] hypotheses formed, all with falsification tests
```

---

## PHASE B3 — Evidence Collection

**Goal:** Test each hypothesis with targeted evidence. No noise. No exploratory grep.

Rules:
- **Every grep must target a specific hypothesis.** State which hypothesis it tests.
- **No DEEP_INSPECT-style broad greps** (e.g., "show me everything in the log for this broadcast").
  Broad greps without a specific question waste context and obscure the actual evidence.
- **Read only the lines you need.** If a grep returns >10 matches, filter with a qualifying term.
- **Update each hypothesis status** after gathering evidence for it.
- **Stop collecting evidence when a hypothesis is confirmed or all are falsified.**

For each grep or file read:
```
EVIDENCE SEARCH #N
  Tests hypothesis: #[N]
  Search          : [exact grep pattern and file]
  Result          : [N matches, key excerpts]
  Interpretation  : [how this confirms or falsifies hypothesis #N]
  Hypothesis #N   : [CONFIRMED | FALSIFIED | INCONCLUSIVE — reason]
```

**Phase gate:**
```
B3 COMPLETE — all hypotheses tested, [N] confirmed, [M] falsified, [K] inconclusive
```

---

## PHASE B4 — Root Cause Declaration

**Rule:** Root cause must be declared from CONFIRMED hypotheses, with all alternatives
explicitly eliminated.

Format:
```
ROOT CAUSE
  Mechanism  : [one sentence — the precise code path and why it fails]
  Evidence   : [log line and file:line that proves it]
  
ELIMINATED HYPOTHESES
  #N : [why this was ruled out — specific log evidence]
  ...
  
REMAINING UNCERTAINTY: [any aspect of root cause not fully proven, or NONE]
```

If no hypothesis was confirmed, state:
```
ROOT CAUSE UNDETERMINED
  Reason     : [why all hypotheses were inconclusive]
  Next steps : [what additional evidence would resolve it]
```

**Phase gate:**
```
B4 COMPLETE — root cause declared (or explicitly undetermined)
```

---

## PHASE B5 — Fix Scoping

**RULE: Check file size BEFORE writing any fix code.**

Steps:

1. Read line 1 of the target file → output shows `(N lines total)`.
2. If N > 800: **Large File Rule applies** — present BEFORE/AFTER snippet with line numbers
   and 5 lines of context on each side. Do NOT use `replace_string_in_file`. Wait for user.
3. If N ≤ 800: proceed with Pre-Edit Verification Protocol:
   a. Read the exact region to be changed (`read_file`)
   b. Run `grep_search` with the proposed `oldString` — must return exactly 1 match
   c. If 0 matches: whitespace error — re-read and correct
   d. If 2+ matches: add more context lines until unique

Record:
```
FIX SCOPE
  File       : [absolute path]
  Lines      : [N total]
  Rule       : [Large File Rule — BEFORE/AFTER only | Pre-Edit Verification — direct edit]
  Region     : [lines X–Y to be changed]
  Uniqueness : [grep_search result: N match(es) at line X]
```

**Phase gate:**
```
B5 COMPLETE — fix scoping verified, edit method determined
```

---

## PHASE B6 — Verification Protocol

**Goal:** Define what success looks like in logs BEFORE deploying the fix.

Specify:

```
VERIFICATION PROTOCOL
  Build step    : [gradle command]
  Deploy step   : [adb install command, if applicable]
  Test scenario : [exact steps to reproduce the original failure]
  
SUCCESS SIGNATURES (must appear in log after fix):
  Phone 1 log: [exact log line pattern that proves senders side is correct]
  Phone 2 log: [exact log line pattern that proves receiver completed successfully]
  
FAILURE SIGNATURES (must NOT appear):
  [log patterns that would indicate the fix didn't work]
  
REGRESSION CHECK:
  [any prior working behavior that must still work after this fix]
```

**Phase gate:**
```
B6 COMPLETE — verification protocol defined
```

---

## ABSOLUTE PROHIBITIONS

| # | Prohibited |
|---|---|
| P1 | Starting analysis on the sender before completing B1 on the victim |
| P2 | Forming a hypothesis without an explicit falsification test |
| P3 | Running a grep "to see what's there" — every grep must test a specific hypothesis |
| P4 | Declaring root cause when zero hypotheses are confirmed |
| P5 | Writing fix code before checking file size (B5) |
| P6 | Proposing an edit without grep_search confirming exactly 1 pattern match |
| P7 | Reading .md files or code comments as sources of truth |
| P8 | Assuming the user made a testing error (never attribute failure to user procedure) |
| P9 | Mutating any file in INFORMATIONAL mode |
| P10 | Asking the user for permission to proceed in INFORMATIONAL mode |

---

## GATE CHECKLIST

```
RUNTIME BEHAVIOR GATE
  [✓/✗] B0 — Symptom locked, transaction ID captured, logs identified
  [✓/✗] B1 — Failure window identified on VICTIM device first
  [✓/✗] B2 — ≥3 hypotheses with probability ranking and falsification tests
  [✓/✗] B3 — All hypotheses tested with targeted (not exploratory) greps
  [✓/✗] B4 — Root cause declared with eliminated alternatives listed
  [✓/✗] B5 — File size checked, edit method determined (Large File or Pre-Edit Verify)
  [✓/✗] B6 — Verification protocol defined with success/failure log signatures
  [✓/✗] MODE — No prohibited actions taken for active mode
  [✓/✗] HEADER — Response opened with mandatory mode/phase/tool header block
```
