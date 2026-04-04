---
name: debug-triage
description: >
  Master debug dispatcher. Use this skill FIRST for any debugging, investigation, or bug-fix
  request before invoking any other debug skill. Triggers on: "debug", "investigate",
  "root cause", "why doesn't", "it still fails", "trace", "diagnose", "fix the bug",
  "it's not working", "find the issue", or any request involving logs, crashes, build errors,
  wrong behavior, or network/protocol failures. This skill reads the prompt, classifies the
  failure type, then routes to the correct sub-skill: debug-patch-strategy (compile errors),
  debug-runtime-behavior (crashes / wrong behavior), debug-network-protocol (packet/routing
  failures), or remote-debug (no direct file access). Never skip triage and jump directly to
  a sub-skill — classification prevents wasted sessions caused by using the wrong methodology.
---

# Debug Triage — Master Dispatcher

This skill runs BEFORE any other debug skill. Its sole job is to classify the failure and
route to the correct methodology. Misclassification is the #1 cause of wasted sessions.

---

## MANDATORY RESPONSE HEADER

Every response must open with:

```
═══════════════════════════════════════════════════════
TRIAGE RESULT
  Failure type  : [compile | crash | behavior | network | remote]
  Routing to    : [skill name]
  Confidence    : [high | medium — reason if medium]
  Mode          : [INFORMATIONAL | PATCH]
═══════════════════════════════════════════════════════
```

---

## TRIAGE PHASE — Classification

Read the prompt and apply the classification rules below **in order**. Stop at the first match.

### T1 — Remote Codebase?
**Condition:** User says Claude does not have direct file access, asks for a collection prompt,
or mentions a remote agent (Raptor Mini, etc.).

→ **Route to: `remote-debug`**

---

### T2 — Build / Compile Error?
**Condition:** The failure is a Gradle build error, Kotlin/Java compiler error, unresolved
reference, type mismatch, missing import, or lint error. Key signals:
- Output contains `e: /path/to/File.kt:N:M: error:`
- Output contains `FAILED` with a Gradle task name
- Output contains `Unresolved reference`, `None of the following candidates`, `Type mismatch`
- User pastes a build log or mentions a compile error

→ **Route to: `debug-patch-strategy`**

Sub-skill invocation: follow the 9-phase protocol (Phase 0 Error Enumeration through Phase 9
Change Log). Use INFORMATIONAL or PATCH mode as specified by the user.

---

### T3 — Crash / Stack Trace?
**Condition:** The failure produces a `java.lang.Exception`, `NullPointerException`,
`IllegalStateException`, `FATAL EXCEPTION`, ANR, or any exception with a stack trace in
logcat. Key signals:
- Log contains `FATAL EXCEPTION` or `Process: ... PID:`
- Log contains `at com.` stack frames
- App crashes to home screen or forces-closes
- User says "the app crashed" or "force close"

→ **Route to: `debug-runtime-behavior`** (crash mode — set B0 mode="crash")

---

### T4 — Network / Protocol Failure?
**Condition:** The failure involves packets not arriving, broadcast incomplete, NACK not sent
or not received, chunks missing, wrong routing, socket binding, or any mesh/WiFi-Direct
connectivity issue. Key signals:
- Mentions: broadcast, chunk, NACK, route, socket, `boundNetwork`, hotspot, WiFi-Direct
- Log shows a device "stuck at N/M chunks" or never completing a transfer
- Log shows NACK sent but sender doesn't receive it (or vice versa)
- Any issue with packet delivery between two physical devices

→ **Route to: `debug-network-protocol`**

Note: `debug-network-protocol` EXTENDS `debug-runtime-behavior`. Run C0 topology snapshot
first, then fall through to B0–B6 phases. The network sub-skill overrides the log analysis
steps in B3 with protocol-specific grep rules.

---

### T5 — Wrong Behavior (no crash, no network, no compile error)?
**Condition:** The app runs but produces wrong output: UI doesn't update, state not reflected,
list not populated, callback not fired, observer not triggered, data not saved, feature
works sometimes but not always.

→ **Route to: `debug-runtime-behavior`** (behavior mode — set B0 mode="behavior")

---

### T6 — Ambiguous / Multi-type?
**Condition:** Prompt matches 2+ categories, or failure type cannot be determined from
available information.

Action:
1. List each candidate type and why it matches
2. Ask ONE clarifying question: "Is this a compile error, a crash, or wrong behavior?"
3. Once answered, re-run triage from T1

Do NOT start any sub-skill until classification is resolved.

---

## MODE DETERMINATION

After routing, determine the invocation mode:

| User says | Mode |
|-----------|------|
| "investigate", "why", "trace", "analyze", "root cause" | INFORMATIONAL |
| "fix", "patch", "apply", "change the code" | PATCH |
| Neither keyword present | Default INFORMATIONAL, state this explicitly |

Pass the resolved mode to the sub-skill. The sub-skill enforces mode boundaries.

---

## ROUTING TABLE (quick reference)

| Failure Type | Sub-skill | Notes |
|---|---|---|
| Compile / build error | `debug-patch-strategy` | 9-phase compiler protocol |
| Crash / ANR | `debug-runtime-behavior` | mode=crash |
| Wrong behavior | `debug-runtime-behavior` | mode=behavior |
| Packet/network/protocol | `debug-network-protocol` | extends runtime-behavior |
| No file access | `remote-debug` | Phase 1: collection prompt |

---

## ESCALATION RULES

During sub-skill execution, failure type may change. Escalation rules:

- **runtime-behavior → network-protocol**: If B3 evidence shows missing packets, wrong
  socket binding, or cross-device delivery failure — escalate to `debug-network-protocol`
  and run C0 topology snapshot before continuing B3 evidence collection.

- **compile-error → runtime-behavior**: If the build succeeds but the bug persists at
  runtime — escalate to `debug-runtime-behavior`.

- **Any sub-skill → remote-debug**: If sub-skill requires source files that are not
  accessible — pause, generate a remote collection prompt, wait for files.

When escalating, print:
```
ESCALATION: [from-skill] → [to-skill]
Reason: [why]
Continuing from phase [X] of [to-skill]
```

---

## GATE CHECKLIST

Print at end of triage phase:

```
TRIAGE GATE
  [✓/✗] Failure type classified (one of: compile/crash/behavior/network/remote)
  [✓/✗] Mode determined (INFORMATIONAL or PATCH)
  [✓/✗] Sub-skill identified
  [✓/✗] Header printed with TRIAGE RESULT block
  [✓/✗] Sub-skill execution beginning
```
