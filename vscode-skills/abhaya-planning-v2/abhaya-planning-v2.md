---
name: abhaya-planning-v2
description: >
  Use this skill to plan and specify large refactors or feature implementations
  in the Abhaya/Orbot-Meshrabiya Android codebase. Triggers include: any request
  to "create a plan", "write a refactor plan", "spec out an implementation",
  "plan the changes for X", or "write the implementation document for X".
  This v2 skill refines the original five-phase methodology with six hard-won
  additions derived from the VPN Gateway Refactor post-mortem (PT1–PT7): full
  constructor injection audit, variable liveness audit, observer lifecycle
  timeline, view ownership matrix, cross-role behavior matrix, and pre-
  implementation dead code audit. These additions are mandatory, not optional.
  Produces two output documents: a *plan* document (architecture decisions,
  risk table, role matrix) and an *implementation* document (verified
  BEFORE/AFTER code snippets per file, bottom-to-top ordering). Use in
  combination with AGENTS.md verification protocols and the debug-strategy
  skill for any embedded error resolution.
argument-hint: 'Describe the feature or refactor to plan (e.g. "VPN gateway refactor removing nonMeshWifi")'
---

# Abhaya Planning Skill v2

A six-phase methodology for producing verified, implementation-ready plans for
the Abhaya/Orbot-Meshrabiya codebase. Derived from the original v1 skill
(VPN Gateway Refactor, April 2026) and refined through its full post-mortem.

The v1 plan required 7 post-implementation fix rounds (PT1–PT7) to reach a
working state. This v2 skill encodes the root causes of every fix round as
mandatory discovery and verification steps that must complete **before** any
plan prose is written.

---

## Post-Mortem Summary (Why v2 Exists)

The VPN Gateway Refactor plan correctly identified the architectural goal and
the main data flow. It failed in five categories:

| Type | Root Cause | Introduced by | Fix round |
|------|-----------|---------------|-----------|
| A – Wiring gap | Constructor parameters assumed correct; instantiation sites not audited | meshInternetRelayServer never passed to EmergentRoleManager | PT7 |
| A – Liveness gap | Variable declared but never launched (meshInternetCheckJob) | No caller-count audit | PT3 |
| B – Lifecycle gap | Observer registered before views exist; StateFlow replay skipped by guard | ViewStub inflation timing not traced | PT5 |
| B – View conflict | Two observers writing same view property (meshChipAp) | Second observer not discovered | PT3, PT4 |
| C – Platform gap | BroadcastReceiver flag RECEIVER_NOT_EXPORTED blocks cross-app on Android 13+ | Broadcast reception not verified | PT3 |
| D – Role gap | Probe treats CLEARNET_GATEWAY and TOR_GATEWAY identically | Cross-role behavior matrix not created | PT6 |
| E – Dead code gap | DTO fields removed; stale references in UI not caught | No pre-impl dead code audit | PT2 |

---

## Phases at a Glance

| Phase | Name | Output | New in v2 |
|-------|------|--------|-----------|
| 1 | Codebase Discovery | Verified symbol map | + Injection audit + Liveness audit |
| 2 | Uncertainty Identification | Risk table + role matrix | + Observer timeline + View ownership + Platform audit |
| 3 | Iterative Research Rounds | Resolved risks; swap files | + Platform verification round |
| 4 | Plan Document | Architecture decisions, data flow | + Role behavior matrix section |
| 5 | Implementation Specification | BEFORE/AFTER snippets, bottom-to-top | + Pre-impl dead code audit gate |
| 6 | *(new)* Pre-Impl Dead Code Audit | Zero-caller verification for all deletions | NEW |
| 7 | *(new)* Separation-of-Concerns Audit | API/DTO boundary violations table | NEW |
| 8 | *(new)* Logging Strategy | Log call inventory per file, pattern conformance | NEW |

---

## Phase 1 — Codebase Discovery

**Goal:** Produce a 100% verified map of every file, class, method, field, and
constructor instantiation that will be touched by the change — before writing a
single word of plan prose.

**Never write plan text until Phase 1 is complete. Never guess file paths or line numbers.**

See [discovery-protocol-v2.md](./references/discovery-protocol-v2.md) for the
full tool reference.

### Discovery Sequence

1. **Identify user-facing entry points** (Activities, Fragments, Composables, button handlers)
2. **Trace data flow downward**: UI → ViewModel → API interface → implementation → data structures
3. **Trace data flow upward**: find every field that *sets* the functionality being changed
4. **Map all call sites** of every method/field to be renamed or deleted
5. **Identify all DTOs** passed across module boundaries
6. **Record dead code candidates** — symbols with zero remaining callers after the change
7. *(NEW v2)* **Constructor injection audit** — for every constructor found, audit every instantiation site
8. *(NEW v2)* **Variable liveness audit** — for every new variable the plan adds, verify a launch/assignment site exists

### v2-MANDATORY: Constructor Injection Audit

For every class constructor discovered that will receive injected parameters:

```
grep_search("ClassName(")   ← finds ALL instantiation sites
```

For each instantiation site:
- Read the call site (≥3 lines before + after)
- List every parameter — including optional ones with defaults
- For each optional parameter the plan expects to be non-default, verify it will be supplied
- If any instantiation omits a required parameter: add `⚠️ Open` risk

**Record:**
```
INSTANTIATION AUDIT: ClassName
  Site 1: File.kt:286 — params: virtualNode, context, getTopologyMap, getCurrentNodeCapabilities
    MISSING: meshInternetRelayServer (defaults to null — will be dead wiring unless supplied here)
  → RISK: TYPE-A-WIRING ⚠️ Open
```

### v2-MANDATORY: Variable Liveness Audit

For every variable the plan declares or uses:

```
grep_search("variableName")   ← find ALL assignments, not just declaration
```

Count total matches:
- **1 match** (declaration only) → `⚠️ SUSPENSION: variable declared but never assigned — plan must add launch/assignment site`
- **2+ matches** (declaration + assignments) → record each assignment context

**Record:**
```
LIVENESS AUDIT: meshInternetCheckJob
  Declaration: MeshrabiyaApiImpl.kt:225
  Assignments: 1 (declaration only)
  → SUSPENSION: no caller launches this job — plan must add launch site in startEventMonitoring()
```

### Discovery Record Format

```
FILE: absolute/path/to/File.kt
ITEM: [class/fun/val name]
LINE: [exact line number from grep_search or read_file output]
SIGNATURE: [verbatim declaration as it appears on disk]
CALL_SITES: [file:line for each caller — from grep_search]
INSTANTIATION_SITES: [file:line for each constructor call — from grep_search]
LIVENESS: [declaration-only | assigned at: file:line]
```

Store in session memory: `memory(create, /memories/session/[plan-name]-discovery.md)`

---

## Phase 2 — Uncertainty Identification

**Goal:** Produce an explicit, exhaustive list of every assumption (not just
architectural assumptions, but also platform, lifecycle, and cross-type ones).

See [uncertainty-v2.md](./references/uncertainty-v2.md) for the full format.

### Standard Uncertainty Markers

- `~line 847` — approximate line number
- `⚠️ Open: [question]` — unresolved question; blocks Phase 4 until resolved
- `UNVERIFIED: [fact]` — claim not backed by a tool read

### v2-MANDATORY: Observer Lifecycle Timeline

For every new or changed observer/listener, create an ASCII timeline:

```
TIMELINE: [observer name]
  t=0  onViewCreated() fires
  t=0  setupVpnStatusObserver() registered — StateFlow replays current value
       ├─ !deferredViewsInitialized → guard SKIPS emission
       └─ internetWifiRow stays GONE
  t=X  ViewStub.inflate callback fires
       ├─ bindDeferredViews() → new views bound
       ├─ deferredViewsInitialized = true
       └─ StateFlow does NOT re-emit same value [StateFlow deduplication]
  → RESULT: internet row stays GONE permanently
  → FIX: read .value snapshot directly after bindDeferredViews()
```

Any timeline with a gap between "observer registered" and "views ready" is a
TYPE-B risk: `⚠️ Open: StateFlow replay race — must read .value snapshot at bind time`.

### v2-MANDATORY: View Ownership Matrix

For every view whose visibility or content can be written by more than one source:

```
VIEW OWNERSHIP MATRIX
  View: meshChipAp
  Writer 1: setupNetworkInfoObserver() — reads SNAPSHOT meshApActiveFlow.value
            trigger: any networkInfo emission
            writes: meshChipAp.visibility
  Writer 2: setupWifiStateObserver() — REACTIVE wifiStateFlow
            trigger: any wifiState emission
            writes: meshChipAp.visibility

  CONFLICT: Writer 1 reads stale snapshot; Writer 2 is reactive.
  → TYPE-B-CONFLICT ⚠️ Open: designate single owner; remove snapshot write from Writer 1
```

### v2-MANDATORY: Cross-Role Behavior Matrix

For every enum or role type involved in the change:

```
ROLE BEHAVIOR MATRIX
  Role             | Advertised when           | Internet probe  | Relay port | SOCKS
  CLEARNET_GATEWAY | VPN active + WiFi          | TCP:9080        | 9080       | No
  TOR_GATEWAY      | Orbot active               | Presence only   | N/A        | Yes (Orbot)
  TOR_RELAY        | Orbot active, no direct IP | N/A             | N/A        | Forwarding
  CLEARNET_RELAY   | VPN active, relay only     | N/A             | N/A        | No
```

Any shared code path that treats roles identically without checking this matrix
is a TYPE-D risk: `⚠️ Open: probe logic assumes homogeneous role behavior`.

### v2-MANDATORY: Android Platform Audit

For every Android API call introduced or modified:

```
PLATFORM AUDIT: ContextCompat.registerReceiver(flags)
  Min API required: 33 (RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED)
  Breaking change: Android 13+ silently blocks cross-app broadcasts when
                   RECEIVER_NOT_EXPORTED is set
  Required flag for Orbot broadcast: RECEIVER_EXPORTED
  → TYPE-C-PLATFORM ⚠️ Open: verify flag is EXPORTED for external package broadcasts
```

### Risk Table (complete format)

| # | Type | Risk | Status | Resolution |
|---|------|------|--------|------------|
| 1 | TYPE-A | meshInternetRelayServer not passed to EmergentRoleManager | ✅/⚠️ | Verified: VirtualNode.kt:286 — field is null |
| 2 | TYPE-B | StateFlow replay skipped before views bound | ✅/⚠️ | Timeline traced — needs .value snapshot |
| 3 | TYPE-C | Broadcast receiver exports flag on Android 13+ | ✅/⚠️ | Verified: must use RECEIVER_EXPORTED |
| 4 | TYPE-D | CLEARNET and TOR probe identically | ✅/⚠️ | Matrix created — needs split path |
| 5 | TYPE-E | nonMeshSsid references in Fragment not removed | ✅/⚠️ | grep 3 sites — must add to plan |

**Completeness Gate:** every row `✅ Resolved` before Phase 4. Zero `~line` markers.

---

## Phase 3 — Iterative Research Rounds

See [research-rounds.md](./references/research-rounds.md) for the full protocol.

**Summary:** Each round is one `runSubagent(Explore)` invocation. Subagent writes
results to a persistent `content.txt` swap file. Main agent reads at start of
next turn. Rounds continue until all `⚠️ Open` risks are resolved.

### Round Types (v2 additions)

**Round P — Platform Verification (NEW)**  
Scope: Every `⚠️ Open` of TYPE-C.  
Subagent task: read Android release notes, Javadoc headers for all platform APIs
in scope. Report minimum API level, any version-specific behavior, and exact
correct usage pattern.

**Round L — Lifecycle Verification (NEW)**  
Scope: Every `⚠️ Open` of TYPE-B.  
Subagent task: trace Fragment lifecycle events in the affected file (onViewCreated,
onDestroyView, ViewStub inflation), map them to the observer registration sequence,
and verify no state can be missed by timing.

**Round R — Role Behavior Verification (NEW)**  
Scope: Every `⚠️ Open` of TYPE-D.  
Subagent task: for each role enum value, read entire role handler/transition code
path and verify each path independently.

---

## Phase 4 — Plan Document

See [plan-structure.md](./references/plan-structure.md) for the full template.

**Output file naming:** `[FEATURE]_REFACTOR_PLAN.md`

**v2 additions to standard plan sections:**

### §X — Role Behavior Matrix (NEW, mandatory when roles/enums involved)

Include the completed matrix from Phase 2, with each role's behaviors confirmed
from disk reads. Any shared code path that must be split is described here.

### §Y — Observer Lifecycle Inventory (NEW, mandatory when UI observers involved)

List every observer added or changed with its setup location, trigger source,
views it writes, and the verified timing of when those views exist. Include the
ASCII timeline for any with a timing risk.

---

## Phase 5 — Implementation Specification

See [implementation-spec.md](./references/implementation-spec.md) for the full
template.

**v2 additions:**

### Mandatory pre-spec gate: Dead Code Audit (Phase 6)

**Do not finalize IMPLEMENTATION.md until Phase 6 is complete.**

Changes within each file listed bottom-to-top (highest line number first).

---

## Phase 6 — Pre-Implementation Dead Code Audit (NEW)

**Goal:** Before a single BEFORE/AFTER snippet is written for any deletion,
verify that every symbol to be removed has zero remaining callers.

This phase did not exist in v1 and was the root cause of PT2 build errors
(~70 compiler errors from stale nonMeshSsid references).

### Audit Procedure

For every symbol marked for deletion in the plan:

```
1. grep_search("symbolName")   ← find ALL references, not just declaration
2. List every match with file:line
3. Categorize each:
   (a) DELETED — covered by another plan section; will be removed
   (b) UPDATED — being changed to use new replacement symbol
   (c) STALE — not yet covered; must add to plan before proceeding
4. Count category (c): must be 0 before IMPLEMENTATION.md is finalized
```

**Record:**

```
DEAD CODE AUDIT: NonMeshWifiConnectionStateDto
  Declaration: DtoModels.kt:743 → (a) DELETED per §5
  Reference 1: MeshrabiyaApiImpl.kt:517 → (a) DELETED per §4.A-6
  Reference 2: EnhancedMeshFragment.kt:772 → (c) STALE — NOT COVERED
  Reference 3: EnhancedMeshFragment.kt:1248 → (c) STALE — NOT COVERED
  → 2 STALE references — plan incomplete; must add Fragment changes to §11
```

### Field Removal Cascade

For every field removed from a DTO:

```
grep_search("fieldName")   ← find all consumers of the field
```

For each consumer:
- Verify it is either deleted or updated to use the replacement field
- If neither: `⚠️ STALE` — add to plan

### Dead Code Report Table (required in IMPLEMENTATION.md)

| Symbol | Declaration | References | Disposition |
|--------|-------------|------------|-------------|
| NonMeshWifiConnectionStateDto | DtoModels.kt:743 | 5 | All covered ✅ |
| nonMeshSsid field | NetworkInfoDto:30 | 3 | Fragment:772 STALE ⚠️ |
| connectToNonMeshWifi() | MeshrabiyaApi.kt:41 | 2 | All covered ✅ |

**Gate:** No STALE rows allowed before IMPLEMENTATION.md is finalized.

---

## Phase 7 — Separation-of-Concerns Audit (NEW)

**Goal:** Guarantee that every interaction between `app/` or `orbotservice/` and
`Meshrabiya/lib-meshrabiya/` flows exclusively through `MeshrabiyaApi` (the
interface) and its DTO types. No direct instantiation of Meshrabiya internal
classes, no direct field access on `AndroidVirtualNode`, `VirtualNode`, or any
Meshrabiya-internal type is permitted from the main app modules.

**This is an absolute project rule, not a style preference.**

### Audit Procedure

For every file in `app/` and `orbotservice/` that the plan touches:

```
1. grep_search("import com.ustadmobile.meshrabiya", includePattern="app/**")
   grep_search("import com.ustadmobile.meshrabiya", includePattern="orbotservice/**")
   ← finds ALL Meshrabiya imports in app-side modules

2. For each import found, classify:
   (a) PERMITTED  — imports from com.ustadmobile.meshrabiya.api.*
                    or com.ustadmobile.meshrabiya.api.model.* (DTOs)
   (b) VIOLATION  — imports from any other com.ustadmobile.meshrabiya.* package
                    (vnet.*, service.*, storage.*, vnet.datagram.*, etc.)

3. For each VIOLATION: add TYPE-F risk to risk table
```

### New Code Review

For every new code snippet the plan introduces in `app/` or `orbotservice/`:

- Confirm every Meshrabiya type it references is from `MeshrabiyaApi` or a DTO
- Confirm every method call goes through the `MeshrabiyaApi` interface, not the impl
- Confirm no `MeshrabiyaApiImpl.getInstance()` call is added anywhere other than
  `OrbotApp.onCreate()` (the designated single wiring point)

### Boundary Violation Table (required in PLAN.md)

```
SOC AUDIT: [file path]
  Import: com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
  Status: VIOLATION — vnet.* is an internal Meshrabiya package
  Required replacement: access via MeshrabiyaApi.getLocalNodeState() returning LocalNodeStateDto
  → TYPE-F ⚠️ Open: direct VirtualNode access must be removed
```

| File | Import / Access | Classification | Replacement |
|------|----------------|----------------|-------------|
| EnhancedMeshFragment.kt | vnet.AndroidVirtualNode | VIOLATION ⚠️ | MeshrabiyaApi.getLocalNodeState() → LocalNodeStateDto |
| OrbotApp.kt | api.MeshrabiyaApiImpl | PERMITTED (single wiring point) | — |

**Gate:** Zero VIOLATION rows with `⚠️ Open` status before PLAN.md is finalized.

---

## Phase 8 — Logging Strategy (NEW)

**Goal:** Every BEFORE/AFTER snippet that adds or modifies logic must include
log statements at critical decision points and control-flow branches from the
start. Logging must not be retrofitted post-implementation.

### What Requires a Log Statement

For every new or changed function in any AFTER snippet, add log statements at:

| Trigger | Log level | What to include |
|---------|-----------|----------------|
| Entry of any public/override fun | `Log.d` | function name + key input parameter values |
| Every `if`/`when` branch that changes state | `Log.d` | branch taken + the value that determined it |
| Successful completion of async work (coroutines, callbacks) | `Log.i` | result summary |
| Error paths (`catch`, null guards, empty-list guards) | `Log.w` or `Log.e` | condition + relevant state |
| StateFlow / LiveData emissions that drive UI | `Log.d` | emitted value |
| Any `return` that exits early (guard clauses) | `Log.d` | reason for early return |

### Logging Pattern Conformance

**Before writing any log statement in an existing file, the agent MUST:**

```
1. grep_search("Log.d\|Log.i\|Log.w\|Log.e", includePattern="[target file]")
   ← read the first 3 matching lines to extract the file's logging pattern

2. Identify the tag convention used in the file:
   - Companion object TAG constant?  → use TAG
   - Inline string literal?           → match the exact string format
   - Prefixed with class name?        → match the prefix style
   - android.util.Log vs Log import?  → match the import style

3. All new log calls in that file MUST use the same tag and import style
```

**Record:**

```
LOGGING PATTERN: OrbotVpnManager.java
  Tag style    : private static final String TAG = "OrbotVpnManager";
  Import style : android.util.Log (no import — fully qualified not used; import Log at top)
  Call style   : Log.d(TAG, "message")
  → All new log calls in this file use: Log.d(TAG, "...")

LOGGING PATTERN: MeshrabiyaApiImpl.kt
  Tag style    : private const val TAG = "MeshrabiyaApiImpl" in companion object
  Import style : android.util.Log (import android.util.Log)
  Call style   : Log.d(TAG, "[PREFIX] message")
  Prefix convention: bracketed prefix per subsystem e.g. [MESH_PROBE], [MESH_PROXY]
  → All new log calls in this file use: Log.d(TAG, "[SUBSYSTEM] message")
```

### Logging Inventory Table (required in IMPLEMENTATION.md)

For each file with new log statements:

| File | Log location (fun name) | Level | Message pattern | Captures |
|------|------------------------|-------|----------------|----------|
| MeshProxyController.kt | start() collect block | `Log.d` | `"Mesh proxy state: gatewayAvailable=$g packages=$n active=$a"` | gateway flag, package count, activation decision |
| OrbotApp.kt | onCreate() mesh block | `Log.d` | `"onCreate() - Mesh proxy controllers started"` | controller startup confirmation |

**Gate:** No new function with control-flow branches may be marked complete
until its log inventory row is filled and its pattern is verified against the
target file's existing style.

---

## Output Quality Standards

A plan is **complete** when:
- [ ] All file paths are absolute and exist on disk
- [ ] All line numbers are exact (no `~`)
- [ ] All method signatures are verbatim from disk
- [ ] Risk table has zero `⚠️ Open` rows
- [ ] Dead code table has zero STALE rows (Phase 6 gate)
- [ ] Role behavior matrix complete for all enums involved
- [ ] Observer lifecycle timelines complete for all observers involved
- [ ] Constructor instantiation audit complete for all injected constructors
- [ ] Variable liveness audit complete for all declared-but-not-launched variables

An implementation doc is **complete** when:
- [ ] Every changed file has at least one BEFORE/AFTER pair
- [ ] Every BEFORE pattern has been `grep_search`-verified as unique
- [ ] AFTER code compiles (brace/paren balanced, imports present)
- [ ] Changes within each file are ordered bottom-to-top
- [ ] Implementation order section correctly maps dependencies
- [ ] Dead Code Report table included, all rows `✅ covered`
- [ ] Separation-of-Concerns audit complete, zero VIOLATION rows with `⚠️ Open` (Phase 7)
- [ ] Logging inventory table complete; every new control-flow function has log coverage (Phase 8)
- [ ] All new log statements in existing files match the file's established tag and import style

---

## What Worked Well in v1 (Preserved)

- **Bottom-to-top edit ordering within files** — prevents line number drift; keep as-is
- **Pattern uniqueness testing** before every edit — prevents multi-location corruption; keep as-is
- **Parallel tool call batching** — grep multiple symbols in one block; keep as-is
- **Swap file pattern** for large subagent results — avoids token budget exhaustion; keep as-is
- **Systematic dead code inventory** for removed APIs — correct in design, just needed Phase 6 gate

---

## Lessons Encoded as Rules

### Rule L1: Never Trust Assumed Wiring

Constructor injection chains must be traced to every instantiation site with
actual parameter verification, not design specification alone. Discovering the
only call site of `EmergentRoleManager(...)` at VirtualNode.kt:286 with
`meshInternetRelayServer` absent would have caught PT7 in the plan phase.

### Rule L2: Runtime Behaviour Requires Dynamic Validation

Static code inspection finds architecture; it does not find runtime timing.
Create explicit timelines for observer setup vs view binding for every
observer added. StateFlow deduplication (won't re-emit same value) combined
with deferred view binding is the most common timing trap in this codebase.

### Rule L3: Enumerated Types Require Independent Path Verification

Different role values (CLEARNET_GATEWAY vs TOR_GATEWAY) have different network
behaviors. Any shared code path must be verified for each role independently
via the role behavior matrix before writing plan prose.

### Rule L4: Platform Edge Cases Are Silent Killers

Android version-specific behavior requires external Javadoc/release-notes
verification. `RECEIVER_NOT_EXPORTED` silently blocking cross-app broadcasts
on Android 13+ cannot be caught by codebase inspection alone.

### Rule L5: Dead Code Audit Must Precede Implementation

Finding stale references during build (70 compiler errors in PT2) is avoidable.
Phase 6 audit prevents it by requiring zero STALE rows before IMPLEMENTATION.md
is written.

### Rule L6: All App↔Meshrabiya Interaction Must Cross the API Boundary

The main app (`app/`, `orbotservice/`) must NEVER import from
`com.ustadmobile.meshrabiya.*` except `api.*` (the interface) and `api.model.*`
(DTOs). Any direct use of `vnet.*`, `service.*`, or `storage.*` types in app
code is an architectural violation. Every plan must enumerate all Meshrabiya
imports in app-side files and classify each as PERMITTED or VIOLATION before
plan prose is written. Violations must be resolved as part of the plan, not
deferred to implementation.

### Rule L7: Logging Must Be Designed In, Not Retrofitted

Debugging a feature that has no logs requires adding logging under time
pressure, which produces inconsistent and incomplete coverage. Every
BEFORE/AFTER snippet that introduces a new function with branching logic must
include log statements at entry, at every state-changing branch, and at every
error/early-return path. Log statements in an existing file must match the
file's existing tag constant, log call style (fully qualified vs import), and
message prefix convention — verified by reading three existing log calls in the
target file before writing the new ones.
