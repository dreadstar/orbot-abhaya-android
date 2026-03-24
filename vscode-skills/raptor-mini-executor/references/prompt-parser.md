# Prompt Parser — Section Detection Rules

Reference for Phase 0 of the Raptor Mini Executor skill.
Read this before parsing any incoming collection prompt.

---

## How Remote-Debug Prompts Are Structured

Every valid collection prompt produced by the `remote-debug` skill contains
exactly these sections in this order:

```
1. OUTPUT INSTRUCTION
2. DO NOT RE-COLLECT
3. CONFIRMED EVIDENCE
4. INVESTIGATION FOCUS
5. COLLECT THESE FILES
6. [final line]
```

---

## Section Detection

### 1 — OUTPUT INSTRUCTION

**Header pattern** (any of these):
```
## OUTPUT INSTRUCTION
# OUTPUT INSTRUCTION
**OUTPUT INSTRUCTION**
OUTPUT INSTRUCTION:
```

**Content to extract:**
The output filename. It will be on a line that looks like one of:
```
Write all results to: GATEWAY_ROUTING_DEBUG_PT4.md
Output file: PAYMENT_SERVICE_DEBUG_PT1.md
Save to: AUTH_FLOW_DEBUG_PT2.md
```

Extract the `.md` filename exactly as written. This becomes `OUTPUT_FILE`.

**Validation:** Must have a `.md` extension. Must contain only alphanumeric
characters, underscores, and hyphens. Reject if it contains spaces or
path separators (user may have introduced a path — strip it and use the
basename only, unless the path is absolute, in which case preserve it).

---

### 2 — DO NOT RE-COLLECT

**Header pattern** (any of these):
```
## DO NOT RE-COLLECT
# DO NOT RE-COLLECT
**DO NOT RE-COLLECT**
DO NOT RE-COLLECT:
```

**Content to extract:**
A list of file or class names already studied in prior sessions. Each item
is on its own line, typically prefixed with `-` or `*` or a number.

Extract the raw name from each line (strip the list marker). Store as
`SKIP_LIST[]`. Example:

```
- GatewayViewModel.kt       →  SKIP_LIST += "GatewayViewModel.kt"
- PaymentRepository         →  SKIP_LIST += "PaymentRepository"
- com.example.RouteManager  →  SKIP_LIST += "RouteManager" (use class name only)
```

For skip matching in Phase 2: match on the basename or class name only
(ignore package prefix). Case-sensitive match.

If this section is empty or absent, `SKIP_LIST` is empty — do not error.

---

### 3 — CONFIRMED EVIDENCE

**Header pattern** (any of these):
```
## CONFIRMED EVIDENCE
# CONFIRMED EVIDENCE
**CONFIRMED EVIDENCE**
CONFIRMED EVIDENCE:
```

**Content to extract:**
Everything between this header and the next section header. Copy verbatim —
including bullet points, sub-bullets, and indentation. This block is pasted
as-is into the output file header. Do not parse or interpret it.

If this section is present but empty: write `(none)` in the output header.
If this section is absent entirely: write `(not provided)` and log a warning
— the prompt is still valid, but note it is incomplete.

---

### 4 — INVESTIGATION FOCUS

**Header pattern** (any of these):
```
## INVESTIGATION FOCUS
# INVESTIGATION FOCUS
**INVESTIGATION FOCUS**
INVESTIGATION FOCUS:
```

**Content to extract:**
Everything between this header and the next section header. Copy verbatim.
Pasted as-is into the output file header. Do not interpret.

This section typically describes two things:
- The data or event that IS arriving/occurring
- The reaction or downstream effect that is NOT happening

You do not need to parse these — just copy the block.

If absent: `PARSE FAILURE — INVESTIGATION FOCUS section not found`. Stop.

---

### 5 — COLLECT THESE FILES

**Header pattern** (any of these):
```
## COLLECT THESE FILES
# COLLECT THESE FILES
**COLLECT THESE FILES**
COLLECT THESE FILES:
```

**Content to extract:**
A list of items to collect. Each item occupies one or more lines.

**Single-line format:**
```
- ClassName.kt
- path/to/File.kt
- SomeClass (search: "fun processPayment")
```

**Multi-line format (item with search terms):**
```
- PaymentProcessor.kt
  Why: emits the payment event
  Search: "fun emit" OR "PaymentEvent"
  Path hint: data/payment/
```

For each item, extract:
- `name`: the class or filename (first token after the list marker)
- `path_hint`: if an explicit path or path hint is given
- `search_terms[]`: any quoted strings after "Search:" or "search:"
- `why`: (ignored for execution — present for context only)

Store the full list as `COLLECT_LIST[]` with these fields per item.

**Validation:**
- Minimum 1 item required. Fail if empty.
- Maximum 8 items. If more than 8 are present, collect the first 8 and log:
  `WARNING: prompt contains [N] items; collecting first 8 per skill limit`

---

### 6 — Final Line Validation

The final non-empty line of every valid collection prompt must be exactly:

```
No analysis. No summaries. No commentary. Raw file contents only.
```

Check this after stripping trailing whitespace. Case-sensitive.

If absent: `PARSE FAILURE — final directive line missing`. Stop.
If present: validation passes.

---

## Detecting Section Boundaries

Section N ends where section N+1's header begins. Use the known header
patterns above as delimiters. If a section header is found mid-paragraph
(unlikely but possible), treat the first occurrence as the header.

If two sections are in unexpected order, parse them by header name regardless
of position, but log: `WARNING: sections out of expected order — parsed by name`

---

## Malformed Prompt Recovery

| Condition | Action |
|---|---|
| OUTPUT INSTRUCTION missing | `PARSE FAILURE` — stop |
| INVESTIGATION FOCUS missing | `PARSE FAILURE` — stop |
| COLLECT THESE FILES missing or empty | `PARSE FAILURE` — stop |
| Final directive line missing | `PARSE FAILURE` — stop |
| DO NOT RE-COLLECT missing | Continue with empty SKIP_LIST, log warning |
| CONFIRMED EVIDENCE missing | Continue with "(not provided)", log warning |
| Sections out of order | Parse by name, log warning, continue |
| More than 8 collect items | Collect first 8, log warning, continue |
