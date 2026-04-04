---
name: abhaya-planning
description: >
  Use this skill to plan and specify large refactors or feature implementations
  in the Abhaya/Orbot-Meshrabiya Android codebase. Triggers include: any request
  to "create a plan", "write a refactor plan", "spec out an implementation",
  "plan the changes for X", or "write the implementation document for X".
  This skill defines a five-phase methodology: codebase discovery → uncertainty
  identification → iterative research rounds (with subagent swap files) →
  plan document creation → implementation specification. Always produces two
  output documents: a *plan* document (architecture decisions, risk table) and
  an *implementation* document (verified BEFORE/AFTER code snippets per file,
  bottom-to-top ordering). Use in combination with the AGENTS.md verification
  protocols and the debug-strategy skill for any embedded error resolution.
argument-hint: 'Describe the feature or refactor to plan (e.g. "VPN gateway refactor removing nonMeshWifi")'
---

# Abhaya Planning Skill

A five-phase methodology for producing verified, implementation-ready plans for
the Abhaya/Orbot-Meshrabiya codebase. Derived from the VPN Gateway Refactor
session (April 2026) which produced `VPN_GATEWAY_REFACTOR_PLAN.md` and
`VPN_GATEWAY_REFACTOR_IMPLEMENTATION.md` through 5+ iterative research rounds.

---

## Phases at a Glance

| Phase | Name | Output |
|-------|------|--------|
| 1 | Codebase Discovery | Verified symbol map (file:line for every touchpoint) |
| 2 | Uncertainty Identification | Risk table + approximate-line markers |
| 3 | Iterative Research Rounds | Resolved risks; swap files per round |
| 4 | Plan Document | Architecture decisions, data flow, risk table |
| 5 | Implementation Specification | BEFORE/AFTER snippets, bottom-to-top, per-file |

---

## Phase 1 — Codebase Discovery

**Goal:** Produce a complete, verified map of every file, class, method, and field
that will be touched by the change — before writing a single word of plan prose.

**Never write plan text until Phase 1 is complete. Never guess file paths or line numbers.**

### Discovery Tool Stack (in order)

1. `file_search` — locate files by name pattern when path is uncertain
2. `grep_search` — find method/field declarations by exact name (`isRegexp: false`)
3. `read_file` — read the declaration + ≥10 lines context to verify signature
4. `semantic_search` — cross-cutting concerns (e.g. "all places that read nonMeshHasInternet")
5. `grep_search` with regex — find all call sites of a method being removed

### Discovery Sequence

1. **Identify user-facing entry points** (Activities, Fragments, button handlers)
2. **Trace data flow downward**: UI → ViewModel/Fragment → API interface → implementation → data structures
3. **Trace data flow upward**: identify every field that feeds the removed/changed functionality
4. **Map all call sites** of every method/field to be deleted or renamed
5. **Identify all DTOs** passed across module boundaries that carry the affected data
6. **Record dead code candidates** — classes/files with zero remaining callers after the change

### Discovery Record Format

For each touchpoint, record:
```
FILE: absolute/path/to/File.kt
ITEM: [class/fun/val name]
LINE: [exact line number from grep_search or read_file output]
SIGNATURE: [verbatim declaration as it appears on disk]
CALL_SITES: [file:line for each caller — from grep_search]
```

**Store in session memory** via `memory(create, /memories/session/[plan-name]-discovery.md)`.
This becomes the "swap file" consumed by later phases.

### Parallel Tool Calls

Launch independent searches in one block:
```
grep_search("connectToNonMeshWifi")      ← All in one <tool_calls> block
grep_search("disconnectFromNonMeshWifi")
grep_search("getNonMeshWifiStateFlow")
grep_search("nonMeshHasInternet")
```
Then read all matching files in one parallel block.

---

## Phase 2 — Uncertainty Identification

**Goal:** Produce an explicit list of every fact that was *assumed* rather than
verified, and every line number that is *approximate* rather than exact.

### Uncertainty Markers

Use these inline markers in plan drafts:
- `~line 847` — approximate line number, needs pinning in later round
- `⚠️ Open: [question]` — unresolved architectural question
- `UNVERIFIED: [fact]` — claim not yet backed by a tool read

### Risk Table Template

Include in plan document as §17 (or equivalent):

| # | Risk | Status | Resolution |
|---|------|--------|------------|
| 1 | Does X class override Y method? | ✅ Resolved | Read file: No override at L256 |
| 2 | Will socket routing work without bindSocket? | ⚠️ Open | Need to check TUN behaviour |

Any `⚠️ Open` row means Phase 3 must run another research round.

### Completeness Gate

Before advancing to Phase 4, every risk row must show `✅ Resolved`.
Count of `~line` approximate markers should be zero.

---

## Phase 3 — Iterative Research Rounds

See [research-rounds.md](./references/research-rounds.md) for the full protocol.

**Summary:** Each round is one `runSubagent(Explore)` invocation with a targeted
scope. The subagent writes its results to a persistent content.txt file (the
"swap file"). The main agent reads that file with `read_file` at the start of
the next turn. Rounds continue until all `⚠️ Open` risks are `✅ Resolved` and
all `~line` markers are replaced with exact numbers.

### Swap File Pattern (key technique)

When a research task is too large for the current token budget:

1. Launch `runSubagent(Explore)` with the list of files and symbols to pin
2. Subagent writes comprehensive results to a file path provided in its return value
3. **Do not read the file in the same turn** — the subagent return message includes the path
4. In the **next turn**, start by calling `read_file` on that path
5. Extract exact line numbers, replace all `~line X` markers in the plan
6. Discard the swap file after extraction (it is temporary)

The swap file path follows this pattern:
```
/home/[user]/.config/Code/User/workspaceStorage/[ws-id]/GitHub.copilot-chat/
  chat-session-resources/[session-id]/[tool-call-id]/content.txt
```
It is returned by `runSubagent` as the value of the tool call — read it, do not guess the path.

---

## Phase 4 — Plan Document

See [plan-structure.md](./references/plan-structure.md) for the full template.

**Output file naming:** `[FEATURE]_REFACTOR_PLAN.md` or `[FEATURE]_IMPLEMENTATION_PLAN.md`

**Key sections:**
- §0 Purpose (user story / goal)
- §1 Architecture change (before/after ASCII flow)
- §2–N One section per file being changed (add new code first, delete last)
- §(N+1) Implementation order (sequential steps, dependency-ordered)
- §(N+2) Verification checklist
- §(N+3) Known risks (the risk table from Phase 2)
- §(N+4) Out of scope

**Ordering principle for file sections:**
1. New files / new types first (no dependencies)
2. Interface / API changes before implementations
3. Implementation changes
4. UI last
5. Dead code removal last (safest)

---

## Phase 5 — Implementation Specification

See [implementation-spec.md](./references/implementation-spec.md) for the full template.

**Output file naming:** `[FEATURE]_REFACTOR_IMPLEMENTATION.md`

**Key rules:**
- Changes within each file listed **bottom-to-top** (highest line number first) so
  earlier edits in the same file do not shift line numbers for later edits
- Every BEFORE/AFTER shows ≥5 lines of verbatim surrounding context
- Every snippet is verified against disk before inclusion (use `grep_search` to
  confirm the BEFORE text appears exactly once)
- Import additions and removals listed explicitly for every changed file
- A "Dead code to delete" table covers all symbols with no remaining callers
- A 14-step (or equivalent) "Implementation order" section closes the document

---

## Output Quality Standards

A plan is complete when:
- [ ] All file paths are absolute and exist on disk
- [ ] All line numbers are exact (no `~`)
- [ ] All method signatures are verbatim from disk
- [ ] Risk table has zero `⚠️ Open` rows
- [ ] Dead code table covers every symbol made orphan by the change

An implementation doc is complete when:
- [ ] Every changed file has at least one BEFORE/AFTER pair
- [ ] Every BEFORE pattern has been `grep_search`-verified as unique
- [ ] AFTER code compiles (brace/paren balanced, imports present)
- [ ] Changes within each file are ordered bottom-to-top
- [ ] Implementation order section correctly maps dependencies
