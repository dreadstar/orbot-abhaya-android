# Discovery Protocol v2

Full detail for **Phase 1 — Codebase Discovery** of the Abhaya Planning v2 skill.

Extends the v1 protocol with two mandatory new steps: Constructor Injection Audit
and Variable Liveness Audit. These two steps were absent in v1 and were the root
cause of PT3 (meshInternetCheckJob never launched) and PT7 (MeshInternetRelayServer
never instantiated).

---

## Tool Priority Order

| Priority | Tool | When to use |
|----------|------|-------------|
| 1 | `grep_search` | Find declarations, call sites, instantiation sites by exact name |
| 2 | `read_file` | Read declaration + ≥10 lines context once path+line confirmed |
| 3 | `file_search` | Locate file when only name/pattern known |
| 4 | `semantic_search` | Cross-cutting concerns with no obvious text signature |

---

## Discovery Sequence

### Step 1 — Identify Entry Points
Find the UI event handler first:
- `grep_search("setOnClickListener")` or `grep_search("onClick = {")`
- Read the handler (minimum 30 lines context)

### Step 2 — Trace Downward
From UI handler → ViewModel → API interface → implementation → data structures.
Grep each symbol's declaration, not its usage.

### Step 3 — Trace Upward (data sources)
For any field being removed or changed, find where it is *set*:
- `grep_search("fieldName =")` — find all writes
- Read each write site

### Step 4 — Map All Call Sites
For every method being renamed or deleted:
- `grep_search("methodName(")` — all callers
- `grep_search(".methodName")` — property-style access

### Step 5 — Identify DTOs
- `grep_search("data class DtoName")` — declaration
- `grep_search("DtoName()")` — construction sites
- `grep_search(".toDto()")` / `grep_search(".fromDto()")` — conversion functions

### Step 6 — Dead Code Inventory
After mapping all call sites, identify every symbol with zero remaining callers
after the change. This feeds Phase 6.

---

## Step 7 (NEW v2) — Constructor Injection Audit

**Trigger:** Every constructor that will receive a new injected parameter.

**Protocol:**
1. `grep_search("ClassName(")` — find ALL instantiation sites
2. For each site, `read_file` (minimum 5 lines before + after)
3. For each optional constructor parameter (has `= null` or `= default` value):
   - Determine if the plan expects it to be non-default
   - If yes and the instantiation site does not supply it: **TYPE-A WIRING RISK**
4. Record:

```
CONSTRUCTOR AUDIT: EmergentRoleManager
  grep_search("EmergentRoleManager(") → 1 match
  Site: VirtualNode.kt:286
  Parameters supplied: virtualNode, context, getTopologyMap, getCurrentNodeCapabilities
  Parameters ABSENT (take defaults):
    meshTrafficRouter         = null  [intentional]
    distributedStorageManager = null  [intentional]
    deviceCapabilityManager   = null  [intentional]
    meshInternetRelayServer   = null  [UNINTENTIONAL — plan requires relay server active]
  → ⚠️ TYPE-A-WIRING: meshInternetRelayServer must be instantiated and passed here
```

**Note:** "Optional" does not mean "irrelevant." Every optional parameter with a
consequential default (like `null` that disables a feature) must be explicitly
decided: supply or intentionally omit?

---

## Step 8 (NEW v2) — Variable Liveness Audit

**Trigger:** Every variable the plan declares for deferred use (Jobs, Servers,
Managers, etc. that hold state and must be started).

**Protocol:**
1. `grep_search("variableName")` — find ALL matches in codebase
2. Count matches:
   - **1 match** = declaration only → SUSPENSION FLAG
   - **2+ matches** = declaration + assignments → record all assignment contexts
3. For **launch-style** variables (Job, CoroutineScope), verify a `launch {` or `.start()` call exists
4. Record:

```
LIVENESS AUDIT: meshInternetCheckJob
  grep_search("meshInternetCheckJob") → 1 match
  File: MeshrabiyaApiImpl.kt:225
  Context: private var meshInternetCheckJob: Job? = null
  Assignment sites beyond declaration: NONE
  → ⚠️ SUSPENSION: Job declared, never launched. Plan must add launch site.
  Suggested fix: launch in startEventMonitoring() with delay(0) for immediate first probe
```

---

## Parallelization Strategy

Batch independent searches in one tool call block:

```
BATCH 1 — declarations:
  grep_search("ClassName")
  grep_search("methodName")
  grep_search("fieldName")

BATCH 2 — instantiation and call sites (after batch 1 confirms paths):
  grep_search("ClassName(")       ← constructor instantiation sites
  grep_search("methodName(")      ← call sites
  grep_search("fieldName =")      ← write sites

BATCH 3 — reads (after batch 2 confirms file:line):
  read_file File1.kt lines A-B
  read_file File2.kt lines C-D
  read_file File3.kt lines E-F
```

Never `read_file` before `grep_search` confirms the exact path and line.
