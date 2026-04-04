---
name: debug-patch-strategy
description: >
  Mandatory structured debug and patch workflow for Android/Kotlin codebases. Use this skill
  whenever the user asks to investigate a bug, trace a crash, fix a compile error, diagnose
  a runtime issue, or apply a code patch — especially in Android (Kotlin/Java) projects. Also
  triggers on phrases like "debug strategy", "INVESTIGATE", "PATCH", "root cause", "apply patch",
  "the bug is", "it still doesn't work", "trace the issue", or any request that involves reading
  logs, logcat, or build errors and then proposing or applying code changes. Always use this skill
  when the user provides logcat output, a stack trace, or a build error alongside source code.
---

# Debug & Patch Strategy

A rigorous, phase-gated workflow that takes the agent from raw error output to verified, 
applied code changes — with zero guessing and full auditability.

---

## INVOCATION SYNTAX

Every invocation must specify a mode:

```
"Use the debug strategy to INVESTIGATE X"  → MODE: INFORMATIONAL
"Use the debug strategy to PATCH X"        → MODE: PATCH
```

No mode keyword → default to **MODE: INFORMATIONAL** and state this explicitly.

Print the active mode at the top of every response:

```
ACTIVE MODE: INFORMATIONAL  [no file mutations permitted]
  — or —
ACTIVE MODE: PATCH  [file mutations permitted only on explicit BEFORE/AFTER delivery]
```

Mode cannot change mid-response. Changes take effect on the next response only.

---

## PRIME DIRECTIVE

**All understanding comes exclusively from live code reads.**

Never read, reference, or draw conclusions from:
- Any `.md` file (including this one)
- Inline code comments (`//`, `/* */`, `/** */`)
- File names or directory names as documentation
- README, CHANGELOG, or any documentation file

Valid sources of truth only:
- Literal content of `.kt`, `.java`, `.xml`, `.gradle`, `.json`, `.yaml` files read by tool
- Live tool output: build logs, grep results, compiler errors
- Error messages from authoritative log files supplied by the user

If a fact cannot be established from a live source-code tool read, write:

```
UNVERIFIED: [fact] — cannot proceed without live code read
```

…and perform the read before continuing. Never proceed past an unverified fact.

---

## MODE STATE MACHINE

```
┌─────────────────────────────────────────────────────────────────┐
│  INFORMATIONAL                                                  │
│  PERMITTED : read files, grep, produce phase analysis,          │
│              produce candidate BEFORE/AFTER text                │
│  PROHIBITED: any file write/edit/create/delete, asking          │
│              questions, offering choices, "shall I apply?"      │
│  → PATCH   : only on exact phrase "apply patch" from user       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  PATCH                                                          │
│  PERMITTED : everything in INFORMATIONAL, plus file mutations   │
│              ONLY for files/lines in a prior BEFORE/AFTER       │
│  PROHIBITED: editing files not in a prior snippet, asking       │
│              questions mid-patch, stopping to ask questions     │
│  → INFORMATIONAL: on any new "investigate" invocation           │
└─────────────────────────────────────────────────────────────────┘
```

Mode boundary violation → state it as `PROCESS VIOLATION: [what happened]`, undo if possible, continue correctly.

---

## MANDATORY RESPONSE HEADER

Every response must open with:

```
═══════════════════════════════════════════════════════
ACTIVE MODE    : [INFORMATIONAL | PATCH]
CURRENT PHASE  : [phase name and number]
ERRORS IN SCOPE: [N from Phase 0, or "Phase 0 not yet run"]
TOOL CALLS MADE: [list of read/search calls this turn, or NONE]
FILE MUTATIONS : [NONE | list of files mutated — PATCH mode only]
═══════════════════════════════════════════════════════
```

Each phase begins with a banner:

```
━━━ PHASE N — [PHASE NAME] ━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## PHASES

### Phase 0 — Error Enumeration

Read the authoritative error source in full via tool. Produce a numbered list:

```
ERROR #N
  Message : [verbatim message — no paraphrasing]
  File    : [absolute path]
  Line    : [line number]
  Symbol  : [type, method, or property implicated]
  Status  : [ ] UNRESOLVED
```

Every distinct error gets its own entry. No grouping. No merging.  
New errors found in later phases → append as `ERROR #N+1`, restart from Phase 1 for those only.

**Gate:** `PHASE 0 COMPLETE — [N] errors enumerated`

---

### Phase 1 — Symbol and Type Verification

For every symbol, type, method, property, or DTO from Phase 0:

1. Search for its declaration via tool.
2. Read the declaration file via tool. Record:

```
VERIFIED: [SymbolName]
  File      : absolute/path/to/File.kt
  Line      : N
  Kind      : [class | data class | interface | fun | property | enum]
  Signature : [full signature as written in source]
  Properties: [full list, if class or data class]
  Package   : [package declaration from top of file]
```

For every conversion function (`.toDto()`, `.toInternal()`, `.toHash()`, any mapping extension):
- Search for its declaration via tool
- Record input type, output type, file, line
- If not found: `MISSING: [functionName] — not found in codebase` → new Phase 0 ERROR

**Gate:** `PHASE 1 COMPLETE — [N] symbols verified, [M] missing`

---

### Phase 2 — Overload and Lambda Signature Verification

For every higher-order function call (`combine`, `map`, `flatMap`, `collect`, `collectLatest`, `onEach`, `filter`, `fold`, `zip`, `stateIn`, `shareIn`, `transform`, `runCatching`, `let`, `also`, `apply`, `run`, and any lambda-accepting function):

1. Search for and read the specific overload matched by arity via tool. Record:

```
OVERLOAD VERIFIED: [functionName]([arg types])
  Declaration file : absolute/path/to/File.kt
  Line             : N
  Lambda expects   : ([param1: Type1, ...]) -> ReturnType
  Vararg form      : [yes | no]
```

2. Compare lambda in existing/proposed code against the record. Any mismatch:

```
LAMBDA MISMATCH: [functionName] — expected ([types]) got ([types])
```

Rewrite the lambda to match before producing any snippet.

**Gate:** `PHASE 2 COMPLETE — [N] overloads verified, [M] lambda mismatches corrected`

---

### Phase 3 — Import and Reference Validation

1. Read the import block of each affected file via tool.
2. For every type/function used in the affected region: confirm import is present.
3. For every existing import: confirm it remains used after the proposed change.

```
IMPORTS TO ADD    : [list]
IMPORTS TO REMOVE : [list]
IMPORTS CONFIRMED : [list]
```

**Gate:** `PHASE 3 COMPLETE`

---

### Phase 4 — Extension Function Verification

*Skip if no extension functions in scope.*

1. Search the codebase for an existing extension on the same receiver type — record file:line.
2. Verify the proposed function:
   - Idiomatic Kotlin: `fun ReceiverType.functionName(): ReturnType { ... }`
   - Complete, non-empty body
   - No fully qualified receiver type
   - Receiver type is imported in the target file

Failure → `EXTENSION FUNCTION BLOCKED — [reason]` → find an alternative approach.

**Gate:** `PHASE 4 COMPLETE — [N] extension functions verified`  
or `PHASE 4 SKIPPED — no extension functions in scope`

---

### Phase 5 — Pattern Uniqueness Check

For every BEFORE snippet:

1. Take the most distinctive 3–5 contiguous lines.
2. Search the entire file for that exact sequence via tool — must return exactly 1 match.
3. 0 matches → re-read the file and correct. >1 match → extend anchor until unique.

```
UNIQUENESS CHECK: [first 3 lines, abbreviated]
  Search result : [1 match — PASS | 0 matches — FAIL | N matches — FAIL]
  Action taken  : [PASS | re-read file and corrected | extended anchor to N lines]
```

**Gate:** `PHASE 5 COMPLETE — all BEFORE snippets confirmed unique`

---

### Phase 6 — Before/After Snippet Delivery

**INFORMATIONAL mode:** candidate text only — no file mutation.  
**PATCH mode:** defines exact scope of every permitted file mutation.

Every change must be a complete pair:

```
FILE: absolute/path/to/File.kt
LINES: N–M

━━━ BEFORE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[min 5 lines verbatim context before]
[lines being changed, exactly as in file]
[min 5 lines verbatim context after]

━━━ AFTER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[same 5 lines verbatim context]
[replacement lines, fully written out]
[same 5 lines verbatim context]
```

Rules:
- Context lines verbatim — no paraphrasing, no `// ...`, no ellipses
- AFTER reflects all import changes from Phase 3
- No stubs, `...`, or placeholder TODOs (unless they existed in BEFORE and are outside changed lines)
- Every Phase 0 error has a snippet pair or explicit `NO SNIPPET REQUIRED — [reason]`
- Snippets delivered in the same response as analysis — never in a follow-up

**Gate:** `PHASE 6 COMPLETE — [N] snippet pairs delivered`

---

### Phase 7 — Upstream and Downstream Impact Tracing

1. **Downstream:** For every new call/property/type in any AFTER snippet — verify existence via Phase 1 rules.
2. **Upstream:** For every changed function/property signature — search for all call sites. Read each via tool. Confirm compatibility.  Incompatible → `ERROR #N+1`, produce snippet pair.

```
DOWNSTREAM VERIFIED : [callees confirmed to exist, file:line each]
UPSTREAM CALL SITES : [file:line — COMPATIBLE | NEW ERROR #N]
```

**Gate:** `PHASE 7 COMPLETE — [N] upstream callers checked, [M] new errors escalated`

---

### Phase 8 — Structural and Syntax Validation

For every AFTER snippet:

1. `{` / `}` balance within snippet and enclosing function
2. `(` / `)` balance on every expression line
3. Every coroutine builder (`launch`, `async`, `withContext`) closed
4. Every `collect`, `combine`, `onEach`, `map` lambda closed
5. Every `if`, `when`, `for`, `while` block closed
6. Every `override fun` signature matches interface/superclass — read declaration via tool
7. No dangling binary operators (`&&`, `||`, `+`, `.`, `->`) without continuation

Any failure → rewrite AFTER and repeat this phase for that snippet.

```
SYNTAX VALIDATION: File.kt LINES N–M
  Brace balance  : [PASS | FAIL — detail]
  Paren balance  : [PASS | FAIL — detail]
  Lambda closures: [PASS | FAIL — detail]
  Override check : [PASS | SKIPPED | FAIL — detail]
  Dangling ops   : [PASS | FAIL — detail]
```

**Gate:** `PHASE 8 COMPLETE — all snippets pass structural validation`

---

### Phase 9 — Change Log and Resolution

For every Phase 0 error:

```
CHANGE LOG ENTRY
  Error               : #N — [verbatim message]
  File                : absolute/path/to/File.kt
  Lines               : N–M
  Root cause          : [one sentence, from code reads only]
  Fix                 : [one sentence describing the code change]
  Symbols verified    : [from Phase 1]
  Overloads verified  : [from Phase 2]
  Imports added       : [from Phase 3]
  Imports removed     : [from Phase 3]
  Upstream callers    : [from Phase 7]
  Syntax validated    : [PASS]
  Status              : [RESOLVED | DISMISSED — reason | DEFERRED — reason]
```

Final summary:

```
═══════════════════════════════════════════
STRATEGY COMPLETE
  Total errors Phase 0 : N
  Resolved             : N
  Dismissed            : N
  Deferred             : N
  File mutations       : [NONE | list — PATCH mode only]
═══════════════════════════════════════════
```

---

## ABSOLUTE PROHIBITIONS

| # | Prohibited Action |
|---|---|
| P1 | Reading or citing any `.md` file for code understanding |
| P2 | Reading or citing inline comments for code understanding |
| P3 | Assuming a method, property, type, or overload exists without a live tool search |
| P4 | Proposing any conversion function without verifying its declaration exists |
| P5 | Producing a snippet without absolute file path and line numbers |
| P6 | Producing a snippet without minimum 5 lines of verbatim context on each side |
| P7 | Delivering snippets in a follow-up — must be in the same response as the analysis |
| P8 | Proposing an extension function without a verified codebase reference at file:line |
| P9 | Proposing an extension function with an empty or stub body |
| P10 | Using a fully qualified receiver type in any extension function |
| P11 | Grouping or merging two distinct Phase 0 errors into one entry |
| P12 | Skipping Phase 5 uniqueness check for any BEFORE snippet |
| P13 | Mutating any file while in INFORMATIONAL mode |
| P14 | Asking the user any question while in INFORMATIONAL mode |
| P15 | Offering choices or next-step options while in INFORMATIONAL mode |
| P16 | Writing "shall I apply this?", "would you like me to proceed?", or any equivalent |
| P17 | Changing the active mode within a single response |
| P18 | Mutating a file in PATCH mode not covered by a prior BEFORE/AFTER snippet |
| P19 | Proceeding past an UNVERIFIED fact without performing the required tool read |
| P20 | Omitting the mandatory response header block |

---

## GATE CHECKLIST

Print at the end of every response:

```
GATE CHECKLIST
  [✓/✗] Phase 0  — All errors enumerated verbatim, none merged
  [✓/✗] Phase 1  — Every implicated symbol verified by tool read, file+line recorded
  [✓/✗] Phase 2  — Every higher-order function overload verified, lambda signatures confirmed
  [✓/✗] Phase 3  — All imports validated; additions and removals listed
  [✓/✗] Phase 4  — Extension functions verified or explicitly blocked
  [✓/✗] Phase 5  — Every BEFORE snippet confirmed unique in its file by tool search
  [✓/✗] Phase 6  — Every change has a BEFORE/AFTER pair with path, lines, 5-line context
  [✓/✗] Phase 7  — Upstream callers read and confirmed compatible or escalated
  [✓/✗] Phase 8  — All AFTER snippets pass brace/paren/override/syntax validation
  [✓/✗] Phase 9  — Change log complete; every Phase 0 error RESOLVED/DISMISSED/DEFERRED
  [✓/✗] MODE     — No prohibited actions taken for active mode
  [✓/✗] HEADER   — Response opened with mandatory mode/phase/tool header block
```

Any `✗` means the response is incomplete — resolve before closing.
