# Discovery Protocol

Full detail for **Phase 1 — Codebase Discovery** of the Abhaya Planning skill.

---

## Purpose

Produce a 100% verified map of every file, method, field, and call site that
will be affected by a refactor — before any plan prose is written. Zero guessing,
zero assumed paths.

---

## Tool Priority Order

| Priority | Tool | When to use |
|----------|------|-------------|
| 1 | `grep_search` | Find a method/field/class by exact name. Fastest. |
| 2 | `read_file` | Read a section once the path and line range are known. |
| 3 | `file_search` | Locate a file when only the name or pattern is known. |
| 4 | `semantic_search` | Cross-cutting concerns with no obvious text signature. |

**Do not use terminal commands** (`grep`, `find`, `rg`) when these tools are
available. Terminal output is slower and can't be cited with line numbers.

---

## Discovery Sequence

### Step 1 — Identify Entry Points

For each user-visible behaviour to change, find the UI event handler first:
- Android click handler: `grep_search("setOnClickListener", isRegexp:false)`
- Compose handler: `grep_search("onClick = {")` or `grep_search("onToggle")`
- Read the full handler with `read_file` (minimum 30 lines context)

### Step 2 — Trace Downward

From the UI handler, follow the call chain toward the service layer:
1. Find the ViewModel or Presenter call
2. Find the API interface declaration
3. Find the implementation of the API method
4. Find the data structures (DTOs) the method reads or returns

Use `grep_search` to find each symbol's declaration, not its usage.

### Step 3 — Trace Upward (data sources)

For any field or value being removed or changed, find where it is *set*:
- `grep_search("fieldName =")` and `grep_search("fieldName =")`
- Read each assignment site with `read_file`
- This reveals the full dependency chain upward to hardware/network sources

### Step 4 — Map All Call Sites

For every method being renamed or deleted:
- `grep_search("methodName(")` — finds all callers
- `grep_search("\.methodName")` — finds property-style access
- Record each: file path + line + how it calls the method

If a method has >10 call sites, consider whether a separate "call site migration"
section is needed in the plan.

### Step 5 — Identify DTOs

For each data class passed across module boundaries:
- `grep_search("data class DtoName")` — find declaration
- `read_file` 20 lines — record all field names and types verbatim
- `grep_search("DtoName()")` or `grep_search("DtoName =")` — find all construction sites
- `grep_search(".toDto()")` or `grep_search(".fromDto()")` — find all conversion functions

### Step 6 — Dead Code Inventory

After mapping all call sites, identify every symbol that will have **zero**
remaining callers after the change is applied:
- Classes: `grep_search("ClassName"`) — if returns only the class file itself, it's dead
- Methods: collect all callers; if all callers are in the change scope and will be removed, the method is dead
- DTOs: if all construction sites are removed, the DTO is dead

Record dead code in a table — it belongs in its own section of the plan.

---

## Discovery Record Format

Store in session memory at `/memories/session/[plan-name]-discovery.md`:

```markdown
## [FileName.kt]
**Path:** /absolute/path/to/FileName.kt

### [MethodName]
- **Line:** 247
- **Signature:** `suspend fun connectToNonMeshWifi(ssid: String, passphrase: String): Boolean`
- **Call sites:**
  - `/path/to/Fragment.kt:891` — calls via `viewModel.connect(ssid, passphrase)`
  - `/path/to/Service.kt:143` — assigns result to `_connectionState`
- **Verified:** read_file L247-260 on 2026-04-XX

### [FieldName]
- **Line:** 34
- **Declaration:** `private val _nonMeshConnected = MutableStateFlow(false)`
- **Collectors:** 2 sites (Fragment.kt:556, StatusWidget.kt:89)
- **Verified:** grep_search returned 1 declaration result
```

---

## Parallelization Strategy

Maximise tool call batching. In a single tool call block, launch:
- All `grep_search` calls for different method names simultaneously
- Then all `read_file` calls for the confirmed paths simultaneously

**Never make a `read_file` call you could have batched with other independent reads.**

```
BATCH 1 (all in one turn):
  grep_search("methodA")
  grep_search("methodB")
  grep_search("fieldC")
  grep_search("ClassName")

BATCH 2 (after batch 1 results are back, in one turn):
  read_file(FileA.kt, L200-260)    ← from grep_search result
  read_file(FileB.kt, L400-460)    ← from grep_search result
  read_file(FileC.kt, L88-130)     ← from grep_search result
```

---

## Completeness Gate

Phase 1 is complete when the session memory discovery file has:
- [ ] Every file to be modified with absolute path and line counts
- [ ] Every method to be renamed/deleted with exact line number and signature
- [ ] Every field to be removed with exact line number and declaration
- [ ] Every DTO to be changed with all field names and types
- [ ] Every call site with file:line reference
- [ ] Dead code candidates listed with caller counts

If any item is `~approximate` or `UNVERIFIED`, Phase 1 is **not complete**.
Launch another research round (see `research-rounds.md`) to pin it.
