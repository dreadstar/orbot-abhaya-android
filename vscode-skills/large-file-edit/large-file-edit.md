---
name: large-file-edit
description: >
  Use this skill to safely edit files exceeding 800 lines where
  replace_string_in_file is unreliable due to whitespace/indentation
  matching issues. Triggers: "this file is over 800 lines", "large file
  edit", "edit large file", "safe file edit", "edit using /tmp copy",
  "apply changes to big file". Workflow: creates a /tmp working copy, runs
  all replace_string_in_file calls on the copy, verifies with a unified
  diff, then atomically swaps the copy back over the original only after
  all changes are confirmed correct. Enforces: package-line placement,
  pattern uniqueness checks, brace/paren balance validation, and a size-
  reduction safety guard on commit. Never edits the original file directly.
argument-hint: 'Absolute path to the large file to edit (e.g. /home/.../MyFile.kt)'
---

# Large-File Edit Skill

Safe editing workflow for files > 800 lines.  
All replacements are made on a `/tmp` copy. The original is only overwritten
after the unified diff confirms every change is correct.

---

## When to Use

- File exceeds 800 lines (mandatory per AGENTS.md Large File Manual Edit Rule)
- `replace_string_in_file` has been failing on a file due to whitespace or
  indentation mismatches
- Multiple related edits need to be applied to the same file atomically
- Post-edit verification is required before changes are persisted

---

## Required Tools

| Tool | Used for |
|------|----------|
| `run_in_terminal` | Running the three helper scripts |
| `read_file` | Reading sections of TMP_EDIT for pattern verification |
| `replace_string_in_file` | Making changes — on TMP_EDIT only, never the original |
| `grep_search` | Pattern uniqueness checks (must confirm exactly 1 match) |

Helper scripts live in [./scripts/](./scripts/):
- [preflight.sh](./scripts/preflight.sh) — create /tmp copy + backup
- [diff_check.sh](./scripts/diff_check.sh) — validate changes before commit
- [commit_edit.sh](./scripts/commit_edit.sh) — atomically overwrite original

---

## Step-by-Step Procedure

### Step 1 — Run preflight

```bash
bash vscode-skills/large-file-edit/scripts/preflight.sh /absolute/path/to/file
```

Parse the output. Record:
- `TMP_EDIT` path — **all edits go here, never to ORIGINAL**
- `TMP_BACKUP` path — restore point; do not modify
- `LINE_COUNT` — total lines in original
- `PACKAGE_LINE` — line number of `package`/`module` declaration (0 = not found)

### Step 2 — Read the sections to be changed

For each change, use `read_file` on **TMP_EDIT** (not the original):

```
read_file(filePath=<TMP_EDIT>, startLine=X, endLine=Y)
```

Read enough context: minimum 5 lines before and 5 lines after each target.

### Step 3 — Verify pattern uniqueness for every oldString

For each planned replacement, run `grep_search` on TMP_EDIT with the most
distinctive 3–5 contiguous lines of the intended `oldString`:

```
grep_search(query="<distinctive lines>", includePattern="<TMP_EDIT filename>", isRegexp=false)
```

**Required result: exactly 1 match.**

- 0 matches → whitespace mismatch; re-read and copy exact text from Step 2
- 2+ matches → add more context lines to `oldString` until unique

Document: `"UNIQUENESS CHECK: <abbreviated pattern> → 1 match at line X — PASS"`

### Step 4 — Import placement check (Kotlin / Java only)

If any edit adds an import:
- Confirm `PACKAGE_LINE` from Step 1
- Verify the import will be inserted **after** line `PACKAGE_LINE` and **before**
  any class/object/function declarations
- Do NOT insert any line before the package declaration

### Step 5 — Apply all edits to TMP_EDIT

Run `replace_string_in_file` (or `multi_replace_string_in_file` for multiple
independent edits) with `filePath` = `TMP_EDIT`:

```
replace_string_in_file(
    filePath=<TMP_EDIT>,
    oldString=<exact verbatim text from Step 2, verified unique in Step 3>,
    newString=<replacement>
)
```

**If a replacement fails:** return to Step 2 for that chunk. Do not retry the
same pattern — re-read and re-verify first.

### Step 6 — Run brace/paren balance check (code files only)

For `.kt`, `.java`, `.py`, `.ts` files run:

```bash
bash tools/brace_paren_check.sh <TMP_EDIT>
```

**Required output:** `All symbol pairs are balanced.`

If unbalanced: read the relevant function body in TMP_EDIT, find and fix the
mismatch, then rerun until balanced.

### Step 7 — Run diff_check

```bash
bash vscode-skills/large-file-edit/scripts/diff_check.sh <TMP_EDIT> /absolute/path/to/original
```

**Expected exit code: 0 (CHANGES_DETECTED)**

Review every hunk in the unified diff output:
- Each hunk must correspond to exactly one intended change
- No unexpected deletions or insertions

If exit code 2 (NO_CHANGES): edits did not land — return to Step 3.  
If unexpected hunks appear: restore from TMP_BACKUP, restart from Step 2.

### Step 8 — Commit

```bash
bash vscode-skills/large-file-edit/scripts/commit_edit.sh <TMP_EDIT> /absolute/path/to/original
```

**Expected output:** `COMMIT_OK`

If exit code 2 (size-reduction warning): review the diff again. If reduction is
intentional, rerun with `--force`:

```bash
bash vscode-skills/large-file-edit/scripts/commit_edit.sh <TMP_EDIT> /absolute/path/to/original --force
```

### Step 9 — Post-commit verification

Use `read_file` on the **original** (now updated) to verify key sections:

```
read_file(filePath=/absolute/path/to/original, startLine=X, endLine=Y)
```

Confirm each changed region reflects the intended `newString` content.

---

## Quick Reference Checklist

Before committing, every box must be checked:

```
[ ] preflight.sh ran — TMP_EDIT and TMP_BACKUP paths recorded
[ ] All edits target TMP_EDIT, never the original
[ ] Each oldString read from TMP_EDIT via read_file (not recalled from memory)
[ ] Each oldString confirmed to match exactly 1 location via grep_search
[ ] Import placements are after PACKAGE_LINE (Kotlin/Java)
[ ] No content inserted before package declaration
[ ] brace_paren_check.sh passes (code files only)
[ ] diff_check.sh exit 0, all hunks match intended changes
[ ] commit_edit.sh exits COMMIT_OK
[ ] Post-commit read_file confirms changes on original
```

---

## Pattern Uniqueness Failure Modes (Quick Reference)

| Symptom | Root cause | Fix |
|---------|-----------|-----|
| 0 matches | Whitespace/indentation differs from disk | Re-read exact bytes via `read_file`; copy verbatim |
| 0 matches | CRLF vs LF line endings | Use `grep_search` with shorter single-line anchor |
| 2+ matches | Pattern too generic | Add 3–5 more context lines until unique |
| Edit succeeds but wrong location changed | Pattern matched multiple places before uniqueness check was run | Restore from .bak; redo with uniqueness check |

---

## Restore from Backup

If anything goes wrong before commit:

```bash
cp <TMP_BACKUP> /absolute/path/to/original
echo "Restored from backup"
```

The original was never modified before `commit_edit.sh` runs, so the backup
is only needed if you accidentally ran commit_edit.sh prematurely.

---

## Safety Properties

| Property | Mechanism |
|----------|----------|
| Original never modified during editing | All `replace_string_in_file` calls target TMP_EDIT |
| Pristine restore point always available | `preflight.sh` creates `.bak` before any edits |
| No silent empty-file commit | `commit_edit.sh` refuses if TMP_EDIT is empty |
| No accidental mass-deletion | 40% size-reduction guard in `commit_edit.sh` |
| No edits before package line | Step 4 import placement check |
| Changes are exactly what was intended | Unified diff review in Step 7 |
| Post-edit integrity confirmed | Step 9 `read_file` on committed original |
