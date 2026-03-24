---
name: remote-debug
description: >
  Use this skill whenever the user wants to debug an Android/Kotlin (or any
  mobile/backend) codebase where Claude does NOT have direct file access and
  must delegate source-code collection to a remote agent (Raptor Mini or
  similar). Triggers include: "create a prompt for Raptor Mini", "write a
  collection prompt", "gather files for debug", "remote agent data collection",
  "debug strategy prompt", or any situation where the user provides logs/crash
  output and asks Claude to prepare instructions for a separate agent to fetch
  the relevant source files. Also triggers when the user says "turn this into
  a skill" after going through a remote debug session. Always use this skill
  in combination with the debug-patch-strategy skill — this skill handles the
  REMOTE COLLECTION phase; debug-patch-strategy handles the ANALYSIS phase
  once files are returned.
---

# Remote Debug — Collection Prompt Generator

A two-phase workflow for debugging codebases where Claude lacks direct file
access. Phase 1 (this skill): analyse available evidence and generate a
targeted collection prompt for a remote agent. Phase 2: run the full
debug-patch-strategy once files are returned.

---

## PRIME DIRECTIVE

**Every prompt produced by this skill must be delivered as a downloadable
markdown file** via `present_files`. Never paste the prompt inline as plain
text — the user needs to copy it with formatting intact to paste into the
remote agent.

**Every collection prompt must instruct the remote agent to write all
results to a named markdown file** before doing anything else. The agent
writes continuously to that file as it collects; Claude reads that file in
the next session.

**Every collection prompt must open with an AGENT OPERATING RULES block**
that enforces autonomous, non-interactive execution and per-file disk
flushing. See the rules template in PHASE 2 below — include it verbatim
at the top of every generated prompt.

---

## PHASE 1 — Evidence Analysis (before writing the prompt)

Before generating any collection prompt, extract maximum signal from whatever
the user has provided (logs, prior session notes, crash traces, prior `.md`
reports). Run these checks silently using available tools:

### 1A — Log Triage
If a logcat or crash log is provided:
- Grep for app-specific tag names (the component classes involved)
- Grep for the symptom keywords (the missing state, the failing condition)
- Grep for any **data that IS arriving** vs **data that is NOT acting on it**
  — this gap is usually where the bug lives
- Note the exact first timestamp the symptom should have appeared vs when
  it actually does or doesn't

### 1B — Prior Session Deduplication
If a prior session `.md` report is provided (e.g. `GATEWAY_ROUTING_DEBUG_PT3.md`):
- Extract the list of already-studied files → add to DO NOT RE-COLLECT
- Extract confirmed facts → add to CONFIRMED EVIDENCE section
- Extract the current hypotheses → sharpen the investigation focus
- Increment the session counter for the output filename (PT3 → PT4, etc.)

### 1C — Gap Identification
State the gap explicitly before writing the prompt:
```
GAP: [DataProducer class] emits [X] correctly.
     [DataConsumer class] never reacts to [X].
     Missing link: the code that connects them.
```
The collection prompt targets exactly this gap — nothing broader.

---

## PHASE 2 — Writing the Collection Prompt

Every generated prompt must contain these sections in this exact order:

### SECTION A — Agent Operating Rules (include verbatim at top of every prompt)

```
## AGENT OPERATING RULES — READ BEFORE DOING ANYTHING ELSE

1. Do not pause, ask questions, or request confirmation at any point.
   Execute every step autonomously from start to finish.
2. Do not announce what you are about to do. Just do it.
3. Use IDE file-search and file-read tools as the primary means of
   locating and reading files. Only fall back to shell `find` / `grep`
   if the IDE tool returns no result.
4. Write every file's contents to disk immediately after reading it.
   Do not accumulate content in memory and write at the end — the write
   must happen file-by-file as you go.
5. After writing each file section, call the IDE's save/flush tool (or
   equivalent) to confirm the write is persisted before moving to the
   next file. If the IDE tool does not have an explicit flush, perform
   a read-back of the last 5 lines of the output file to verify the
   write landed.
6. If a file cannot be located after exhausting IDE search and shell
   fallback, write a `## FILE NOT FOUND: <name>` section with the
   search terms tried, then continue to the next file immediately.
7. Do not truncate, summarise, paraphrase, or omit any line of any
   file. Write every character verbatim.
```

### SECTION B — Step 1: Create Output File

The prompt must instruct the agent to create the named output file
**before any search begins**, write a header line to it, and verify
the file exists on disk before proceeding.

### SECTION C — Step 2: Collect Files

For each file entry, the prompt must specify in order:

1. **IDE search term** — filename first, then class/symbol if filename fails
2. **Shell fallback** — `find` or `grep -r` command, used only if IDE yields nothing
3. **Why needed** — one sentence maximum
4. **Critical symbols** — 3–5 specific method names or field names that
   must be present in the collected output (agent self-checks completeness)

Search instructions in every file entry must follow this pattern:
```
**IDE search:** filename `FileName.kt`
**Shell fallback:** `find <repo_root> -name "FileName.kt"`
```

### SECTION D — Step 3: Verification and Close

The prompt must end with a verification step that:
1. Counts `## FILE:` headers in the output file (IDE or `grep -c`)
2. Lists every header found
3. If count < N required, instructs the agent to find and collect
   the missing files before writing `## COLLECTION COMPLETE`
4. Requires a final flush and non-zero byte-size confirmation before
   the agent session ends

### Rules for the COLLECT list
- **Minimum necessary files only.** Do not ask for files already studied.
- Each item must name: the class/file, why it is needed, and specific
  method names or search terms to locate it if the path is unknown.
- Order items by dependency: the emitter first, the missing link second,
  the receiver third, then supporting DTOs and layouts.
- Cap at 8 files maximum. If more are needed, plan a PT+1 session.
- For files that may not exist, include a `## FILE NOT FOUND:` fallback
  instruction so the agent records the absence and moves on without pausing.

Fill in the variable sections based on Phase 1 analysis:

| Section | Source |
|---|---|
| Output filename | Increment from prior session, or derive from bug topic |
| DO NOT RE-COLLECT list | Prior session report or user-provided file list |
| CONFIRMED EVIDENCE facts | Log grep results + prior session findings |
| INVESTIGATION FOCUS | The gap identified in Phase 1C |
| COLLECT THESE FILES list | Minimum set to close the gap — no extras |

---

## PHASE 3 — Delivering the Prompt

1. Write the completed prompt to a `.md` file:
   - Filename: `<SESSION_NAME>_prompt.md`
     e.g. `GATEWAY_ROUTING_DEBUG_PT4_prompt.md`
   - Path: `/mnt/user-data/outputs/`

2. Call `present_files` with that path so the user can download it.

3. Tell the user in one sentence what the prompt will collect and why,
   then stop. Do not repeat the prompt contents inline.

---

## PHASE 4 — After Files Are Returned

When the user pastes or uploads the collected `.md` file from the remote
agent, switch immediately to `debug-patch-strategy` (INFORMATIONAL mode)
and begin Phase 0 — Error Enumeration against the collected sources.

The gap identified in Phase 1C becomes the first `ERROR #1` entry.

---

## ABSOLUTE RULES FOR EVERY GENERATED PROMPT

| # | Rule |
|---|---|
| R1 | AGENT OPERATING RULES block comes first — before output file creation, before any search |
| R2 | Step 1 creates the output file and verifies it on disk before any search begins |
| R3 | Output filename is specific and session-numbered |
| R4 | DO NOT RE-COLLECT section lists every previously studied file |
| R5 | CONFIRMED EVIDENCE section is populated from real log/report data — never left blank |
| R6 | INVESTIGATION FOCUS names exactly two things: the data that IS present and the reaction that is NOT happening |
| R7 | Every file entry leads with IDE search, shell fallback is secondary |
| R8 | Every file entry lists 3–5 critical symbols the agent must confirm are present in the output |
| R9 | Every file entry requires a per-file flush/verify before the agent moves to the next file |
| R10 | Files that may not exist include a `## FILE NOT FOUND:` fallback so the agent never pauses |
| R11 | Step 3 verification counts headers, lists them, and blocks `## COLLECTION COMPLETE` until count = N |
| R12 | Final line of prompt: "No analysis. No summaries. No commentary. No `// ...` truncation. Every line of every file, verbatim." |
| R13 | Prompt is delivered as a `.md` file via `present_files` — never pasted inline |

---

## NAMING CONVENTION

Output files follow this pattern:

```
<PROJECT_TOPIC>_DEBUG_PT<N>.md        ← collected sources (written by remote agent)
<PROJECT_TOPIC>_DEBUG_PT<N>_prompt.md ← the prompt delivered to user (written by Claude)
```

Examples:
```
GATEWAY_ROUTING_DEBUG_PT4.md
GATEWAY_ROUTING_DEBUG_PT4_prompt.md
PAYMENT_SERVICE_DEBUG_PT1.md
PAYMENT_SERVICE_DEBUG_PT1_prompt.md
```

If no prior session exists, derive the topic from the bug description and
start at PT1.

---

## REFERENCE FILES

The canonical template is now fully embedded in PHASE 2 above.
No external template file is required.
