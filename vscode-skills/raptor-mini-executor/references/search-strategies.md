# Search Strategies — File Location Guide

Reference for Phase 2 of the Raptor Mini Executor skill.
Read this before attempting to locate any file in a collection run.

---

## Strategy Priority Order

Always try strategies in this exact order. Stop at the first success.

```
1. Explicit path  →  2. Workspace search  →  3. Filesystem search  →  4. Alternate terms
```

Do not skip ahead. Do not try strategy 3 before exhausting strategy 2.

---

## Strategy 1 — Explicit Path

**When to use:** The COLLECT item contains a full or relative path.

Indicators:
- Contains `/` or `\` characters
- Ends in a recognised source extension (`.kt`, `.java`, `.xml`, etc.)
- Starts with a drive letter (`C:\`) or `/`

**Action:**
```
tool: read_file(path)
```
If the file exists → proceed to Phase 3 for this file.
If not → log `EXPLICIT PATH FAILED: [path] — not found` and try Strategy 2.

---

## Strategy 2 — Workspace Search

**When to use:** Always try this before filesystem search.

### 2A — Search by filename
```
tool: search_files(pattern="ClassName.kt", scope="workspace")
```
Use the exact filename from the COLLECT item, including extension.

### 2B — Search by class/symbol name (if no extension given)
```
tool: search_files(pattern="class ClassName", scope="workspace")
tool: search_files(pattern="object ClassName", scope="workspace")
tool: search_files(pattern="interface ClassName", scope="workspace")
```
Try `class` first, then `object`, then `interface`.

### 2C — Search by provided search terms
If the COLLECT item includes explicit search terms (from a `Search:` field):
```
tool: search_files(pattern="[search term]", scope="workspace")
```
Run one search per term. Stop at first non-empty result.

### 2D — Path-hint scoped search
If the COLLECT item includes a path hint:
```
tool: search_files(pattern="ClassName", scope="[path_hint]")
```

**Success condition:** One or more results returned.
If multiple results, pick the one whose path is most consistent with the
`path_hint` or with Android/Kotlin project conventions:
- Prefer `src/main/java/` or `src/main/kotlin/` over test directories
- Prefer `src/` over `build/` or `generated/`
- Prefer the result with the shortest path if no other signal

Log the chosen path:
```
RESOLVED: [class name] → [chosen path] (selected from [N] results)
```

---

## Strategy 3 — Filesystem Search

**When to use:** Workspace search returned no results.

Use the available shell/terminal tools to search the filesystem:

```bash
# Search by filename
find . -name "ClassName.kt" -not -path "*/build/*" -not -path "*/.gradle/*"

# Search by class declaration
grep -r "class ClassName" --include="*.kt" --include="*.java" -l

# Search by interface
grep -r "interface ClassName" --include="*.kt" --include="*.java" -l
```

**Exclude always:**
- `*/build/*`
- `*/.gradle/*`
- `*/.git/*`
- `*/generated/*`
- `*/node_modules/*`

**Scope:** Start from the workspace root. If unknown, use the current
working directory.

**Success condition:** At least one result that is not in an excluded path.

---

## Strategy 4 — Alternate Search Terms

**When to use:** All prior strategies returned nothing.

Derive alternate terms from the original class/file name:

### 4A — Strip package prefix
```
com.example.app.payment.PaymentProcessor  →  PaymentProcessor
```

### 4B — CamelCase variants
```
PaymentProcessor  →  payment_processor  (snake_case)
PaymentProcessor  →  PaymentProc        (truncated — only if 6+ chars remain)
```

### 4C — Common Android abbreviations
```
ViewModel  →  VM
Repository →  Repo
Manager    →  Mgr
Fragment   →  Frag
Activity   →  Act
```
Try the abbreviated form only if the full name failed.

### 4D — Partial name (prefix search)
Use the first 6+ characters of the class name:
```
grep -r "class Payment" --include="*.kt" -l
```

### 4E — Method-level search
If the COLLECT item specified a method or function name, search for it:
```
grep -r "fun processPayment" --include="*.kt" -l
```

Log every alternate attempt:
```
SEARCH ATTEMPT [N]: "[query]" via Strategy 4[letter] → [result or no results]
```

**If all alternate searches fail:** Log as MISSING (see Phase 2C in SKILL.md).

---

## File Extension Reference

Collect files with these extensions only:

| Extension | Type |
|---|---|
| `.kt` | Kotlin source |
| `.java` | Java source |
| `.xml` | Android layouts, manifests, resources |
| `.gradle` | Build scripts |
| `.gradle.kts` | Kotlin build scripts |
| `.json` | Config, API specs |
| `.yaml` / `.yml` | Config files |
| `.properties` | App properties |
| `.toml` | Version catalogs |

Do not collect `.md`, `.txt`, `.class`, `.dex`, `.aar`, `.jar`, or binary files.

---

## Multiple Results — Disambiguation Rules

When a search returns more than one candidate:

1. **Prefer non-test source:** `src/main/` over `src/test/` or `src/androidTest/`
2. **Prefer the module named in the INVESTIGATION FOCUS:** if the focus
   mentions a specific module name (e.g. `payment`, `gateway`), prefer
   files whose path contains that module name
3. **Prefer shorter path depth:** fewer directory levels = closer to project root
4. **When truly ambiguous:** collect ALL candidates and log:
   ```
   AMBIGUOUS: [class name] — collected [N] candidates:
     [path 1]
     [path 2]
   ```
   This is preferable to missing the correct file.
