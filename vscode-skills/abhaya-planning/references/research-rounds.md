# Research Rounds and Subagent Swap Files

Full detail for **Phase 3 — Iterative Research Rounds** of the Abhaya Planning skill.

---

## Overview

Research happens in **rounds**. Each round resolves a specific set of uncertainties
identified in the previous round. Rounds are implemented as `runSubagent(Explore)`
invocations. The subagent writes its results to a persistent file on disk — the
**swap file** — which survives token budget resets.

This technique was the key innovation discovered in the VPN Gateway Refactor
session. When the main agent's token budget approached exhaustion, a single
subagent invocation with a carefully scoped research task produced a 13KB verified
fact set that the main agent consumed in the next turn.

---

## When to Launch a Research Round

Launch a round when any of these conditions are true:

1. `grep_search("⚠️ Open", isRegexp:false)` returns non-zero matches in any plan draft file
2. `grep_search("~line", isRegexp:false)` returns non-zero matches in the discovery session memory
3. A new section of the plan requires knowing details not yet in the discovery record
4. Token budget is under ~30% and there are still >5 items to verify

**Trigger idiom:**
```
grep_search query="⚠️ Open|~line" isRegexp=true
→ 0 matches: no round needed
→ N matches: launch Explore subagent with those N items as scope
```

---

## Round Structure

### Round 1 — Architectural Mapping

**Scope:** First pass. All entry points, primary data flow, file inventory.
Everything in the discovery protocol that can be batched.

**Subagent prompt template:**
```
Research task for the [FEATURE] refactor. Read and report on each of these items:

For each item, output in this exact format:
FILE: [absolute path]
ITEM: [method/field/class name]
LINE: [exact line number — from grep_search or read_file output]
SIGNATURE: [verbatim declaration]
CALLERS: [file:line for each caller of this symbol]
CONTEXT: [5 lines before and after the declaration, verbatim]

Items to research:
1. [MethodA in FileX.kt — find its declaration and all callers]
2. [FieldB in FileY.kt — find where it is set and where it is read]
3. [DtoClass — find all fields and all construction sites]
...

Close with: "All code locations verified by direct read_file + grep_search tool calls."
```

**Completion signal:** Final line of swap file output is exactly:
`All code locations verified by direct read_file + grep_search tool calls.`

---

### Round N — Targeted Uncertainty Resolution

**Scope:** Specific unresolved items from the previous round's output or from
plan-draft `⚠️ Open` markers.

**Subagent prompt template:**
```
Targeted research round. Resolve only these specific uncertainties:

UNRESOLVED:
1. ~line for [MethodA] in [FileX.kt] — the plan has approximate line 450
2. ⚠️ Open: Does [ClassB] override [methodC]? Need to check inheritance chain.
3. ⚠️ Open: Where exactly is [fieldD] initialised in [FileZ.kt]?

For each:
- Use grep_search to find the exact location
- Use read_file to confirm with surrounding context
- Report in FILE/ITEM/LINE/SIGNATURE/CONTEXT format

Close with: "All code locations verified by direct read_file + grep_search tool calls."
```

---

## The Swap File Mechanism

### How It Works

VS Code writes large subagent outputs (typically >2KB) to a persistent file in
workspaceStorage rather than returning them inline. The subagent tool call returns
a pointer to this file:

```
Large tool result (13kb) written to file. Use the read_file tool to access the
content at: /home/[user]/.config/Code/User/workspaceStorage/[ws-id]/
GitHub.copilot-chat/chat-session-resources/[session-id]/
[toolu-id]/content.txt
```

### Path Pattern

```
/home/<user>/.config/Code/User/workspaceStorage/<workspace-hash>/
  GitHub.copilot-chat/
    chat-session-resources/
      <session-id>/
        <toolu_bdrk_XXXX>__vscode-<timestamp>/
          content.txt
```

**Do not reconstruct or guess this path.** The path is provided in the `runSubagent`
return value. If the content was delivered inline (small result), use it directly.

### Why Swap Files Survive Token Budget Resets

The content.txt file is written to the VS Code `workspaceStorage` directory on the
local filesystem. It is not stored in the LLM's context window. As long as:
- The session ID is the same (same chat window)
- The file has not been garbage-collected by VS Code

...the file persists. The conversation summary mechanism (which compacts prior
turns) preserves the file path in its "Active Work State" or "Recent Operations"
sections, allowing a subsequent turn to read the file with `read_file`.

### Consuming the Swap File

In the turn **after** launching the subagent:

1. Check if the result was inline or file-based
2. If file path was provided: `read_file(filePath=<that path>, startLine=1, endLine=999)`
3. Extract all `FILE:`, `LINE:`, `SIGNATURE:` values
4. Replace all `~line X` approximations in the plan draft with exact values
5. Move all `⚠️ Open` risks to `✅ Resolved` with the citation
6. **Don't try to reference the swap file in later turns** — copy the values into
   the plan document or session memory, then treat the swap file as consumed

---

## Round Lifecycle

```
START
  │
  ▼
Identify unresolved items
(grep_search for "⚠️ Open" and "~line")
  │
  ├─ 0 items → DONE (advance to Phase 4)
  │
  └─ N items → Launch Explore subagent
                    │
                    ▼
              Subagent runs all
              grep_search + read_file
              calls for N items
                    │
                    ▼
              Returns swap file path
              (or inline if small)
                    │
                    ▼
              NEXT TURN: read swap file
              → extract exact values
              → update plan draft
              → move risks to Resolved
                    │
                    ▼
              grep_search again for
              "⚠️ Open" and "~line"
                    │
                   (loop)
```

---

## Subagent Scope Guidelines

**Keep each subagent round tightly scoped:**
- ≤15 symbols per round (prevents output truncation)
- All symbols from the **same** 2-3 files (reduces context switching)
- One clear question per symbol — no open-ended exploration

**Avoid:**
- Asking the subagent to make decisions ("should we use X or Y?")
- Asking the subagent to write plan text (it should only report facts)
- Scope that spans all files in the project (too broad, result will be shallow)

**Good scope example:**
```
Research these 8 items in MeshrabiyaApiImpl.kt and VpnGatewayState.kt:
[list of 8 specific method/field lookups]
```

**Bad scope example:**
```
Research the entire VPN subsystem and tell me how it works
```

---

## Iteration Termination

Research is complete when all three conditions hold:
1. `grep_search("⚠️ Open")` returns 0 matches in all plan files
2. `grep_search("~line")` returns 0 matches in all plan files and session memory
3. `grep_search("UNVERIFIED")` returns 0 matches in all plan files

Only then may Phase 4 (plan document writing) begin.
