---
name: debug-network-protocol
description: >
  Sub-skill C for network, packet, and mesh protocol debugging. Extends debug-runtime-behavior
  with network-specific phases. Use when: packets don't arrive, broadcast is stuck at N/M
  chunks, NACK not sent or not received, unicast loopback, wrong socket binding,
  WiFi-Direct/hotspot routing failure, or any cross-device delivery failure in the
  mesh network. Triggered by debug-triage when failure type is "network". Runs C0 topology
  snapshot and C1 packet lifecycle inventory BEFORE entering the runtime-behavior B phases.
  CRITICAL: C4 Protocol Recovery Audit runs FIRST (before any log reading) — most network
  failures are protocol design gaps, not routing failures. Invoked by debug-triage; can also
  be invoked directly for targeted network analysis.
---

# Debug Network Protocol — Sub-skill C

Extends `debug-runtime-behavior` (Sub-skill B) with network-specific pre-phases.
Always run C0–C4 first, then hand off to B0–B6 phases.

---

## MANDATORY RESPONSE HEADER

Every response must open with:

```
═══════════════════════════════════════════════════════
ACTIVE MODE      : [INFORMATIONAL | PATCH]
FAILURE MODE     : network-protocol
CURRENT PHASE    : [phase name and number]
TOOL CALLS MADE  : [list of reads/searches this turn, or NONE]
FILE MUTATIONS   : [NONE | list — PATCH mode only]
CLOCK WARNING    : [if Phone 2 logs involved: "Phone 2 clock unreliable — correlate by EVENTS"]
═══════════════════════════════════════════════════════
```

---

## PHASE C0 — Topology Snapshot

**Goal:** Document the complete network topology BEFORE reading any transaction-specific logs.
Getting topology wrong causes misattribution of failures.

Read the log files to establish:

```
TOPOLOGY SNAPSHOT
  Phone 1:
    Role             : [AP / STA / both]
    Virtual IP       : [e.g. 169.254.25.95]
    Real UDP address : [IP:port from socket bind log lines]
    PID              : [from log prefix]
    Log file         : [name]
    Hotspot SSID     : [AndroidShare_XXXX, if AP]
    Non-mesh WiFi    : [SSID if connected, e.g. "obie " — IMPORTANT for routing]
    
  Phone 2:
    Role             : [AP / STA / both]
    Virtual IP       : [e.g. 169.254.42.194]
    Real UDP address : [IP:port]
    PID              : [from log prefix]
    Log file         : [name]
    Clock status     : ⚠️ UNRELIABLE — do NOT correlate by timestamp with Phone 1
    
  Active sockets:
    [socket name] boundNetwork=[network] local=[IP:port] remote=[IP:port or broadcast]
    ...
    
  Routing table (from logs):
    [destination IP] → [nexthop or direct] via [interface/socket]
```

**Clock warning (mandatory for all sessions involving Phone 2):**

> ⚠️ Phone 2 (LML211BL3f1c96e3) has an incorrect system clock. Log timestamps from Phone 2
> are unreliable and must NOT be correlated with Phone 1 timestamps. Correlate events by
> CONTENT: packet sequence numbers, chunk indices, broadcast IDs, or explicit message content.

**Phase gate:**
```
C0 COMPLETE — topology snapshot recorded, clock warning noted if applicable
```

---

## PHASE C1 — Packet Lifecycle Inventory

**Goal:** Before reading transaction logs, document what each packet type is SUPPOSED to do.
Never start debugging a network failure without first knowing the expected protocol.

For the transaction under investigation, enumerate every packet type involved:

```
PACKET TYPE: [name, e.g. BroadcastChunk, BroadcastNack, UnicastAck]
  Direction     : [Phone 1 → Phone 2 | Phone 2 → Phone 1 | bidirectional]
  Expected from : [sender role, e.g. "AP / Phone 1"]
  Expected to   : [receiver role or virtual IP]
  Socket        : [which socket carries this — e.g. hotspot broadcast socket]
  Transport     : [UDP unicast | UDP broadcast | WiFi-Direct]
  Log signature : [exact log tag and message fragment to look for on each device]
```

This phase is complete when every packet that should flow in this transaction is documented.

**Phase gate:**
```
C1 COMPLETE — [N] packet types inventoried
```

---

## PHASE C2 — Cross-Device Event Correlation

**Goal:** Build a unified event timeline correlated by CONTENT, not timestamps.

Rules:
- **NEVER correlate by timestamp across devices** (Phone 2 clock is unreliable)
- Correlate by: broadcast ID, chunk index, sequence number, explicit log content
- Build a table: event on Phone 1 ↔ corresponding event on Phone 2

Format:
```
CROSS-DEVICE EVENT TABLE (correlated by content)

Seq | Phone 1 event (line#) | Phone 2 event (line#) | Match | Gap / anomaly
----|------------------------|------------------------|-------|---------------
1   | Sent chunk 0 (L1234)  | Received chunk 0 (L567)| ✓     | —
2   | Sent chunk 16 (L1238) | [NOT FOUND]            | ✗     | MISSING
...
```

Anomalies to flag:
- Packet sent on Phone 1 but no receive on Phone 2 → MISSING
- Packet received on Phone 2 but no send on Phone 1 → GHOST
- NACK sent on Phone 2 but no resend triggered on Phone 1 → NACK DROP
- Resend seen on Phone 1 but chunk still missing on Phone 2 → RESEND LOST

**Phase gate:**
```
C2 COMPLETE — cross-device event table built, [N] anomalies identified
```

---

## PHASE C3 — Socket and Network Layer Analysis

**Trigger:** C2 shows packets LEAVE the sender but DO NOT arrive on the receiver.

If anomalies in C2 are MISSING or RESEND LOST — this phase investigates why.

Steps:

1. **Check boundNetwork for all relevant sockets** on both devices.
   ```
   SOCKET ANALYSIS
     Socket [name] on Phone 1: boundNetwork=[X], sends to [IP:port]
     Socket [name] on Phone 2: boundNetwork=[X], listens on [port]
   ```

2. **Check for unicast loopback**: Does the sender receive its own packets?
   Look for log lines where Phone 1 is both sender and receiver of the same packet.
   If yes → this is a unicast loopback bug; the packet never left the device.

3. **Check network binding mismatch**: If sender's socket is bound to network A but
   receiver's socket listens on network B — packets are dropped silently.

4. **Check for non-mesh WiFi interference**: If Phone 1 has a non-mesh WiFi active
   (e.g., "obie "), the OS may route packets via that interface instead of the hotspot.
   Evidence: packets sent but `boundNetwork=none` or `boundNetwork=[non-mesh network]`.

Record findings:
```
SOCKET LAYER FINDINGS
  Unicast loopback : [YES — line# | NO — evidence]
  Network mismatch : [YES — detail | NO]
  Non-mesh routing : [YES — interface detail | NO]
  Conclusion       : [packets routed correctly | packets routing to wrong interface]
```

**Skip this phase if C2 shows packets NEVER LEFT the sender.** In that case, the issue
is in the sender's send logic, protocol state, or timer — proceed to B2 hypotheses.

**Phase gate:**
```
C3 COMPLETE — socket/network layer analyzed
  — or —
C3 SKIPPED — packets never left sender, proceeding to hypothesis phase
```

---

## PHASE C4 — Protocol Recovery Audit

**⚠️ CRITICAL: This phase runs FIRST, before reading transaction logs, before forming
hypotheses, before any grep. Skipping this phase is the single most common cause of
misdiagnosed network failures.**

**Rationale:** Most network failures in mesh protocols are not caused by packets being
lost (the network can lose packets). They are caused by the protocol failing to RECOVER
when packets are lost. Auditing recovery before log analysis focuses the entire session
on the right question.

For the transaction under investigation, answer each question with a code reference:

```
PROTOCOL RECOVERY AUDIT

Q1: If a DATA PACKET is lost in transit, what does the receiver do?
    Code path : [file:line — method that detects missing data]
    Mechanism : [e.g. timeout + NACK, sequence gap detection, ACK+retransmit]
    Is it one-shot or retry loop? : [one-shot | retry N times | unlimited retry]
    Max attempts: [N | unlimited | N/A]
    Gap found   : [YES — describe | NO]

Q2: If a NACK or recovery request is lost in transit, what does the sender do?
    Code path : [file:line]
    Mechanism : [e.g. sender timeout, re-broadcast, nothing]
    Gap found : [YES — describe | NO]

Q3: If a RESENT PACKET is also lost, what happens?
    Code path : [file:line]
    Mechanism : [e.g. second NACK window fires | no retry after first NACK | nothing]
    Gap found : [YES — describe | NO]

Q4: Is there a maximum duration after which the receiver gives up?
    Code path : [file:line or "none found"]
    Result    : [receiver gives up after Xs | receiver waits forever | N/A]
    Gap found : [YES — describe | NO]

PROTOCOL GAPS IDENTIFIED: [N]
  #1: [description of gap — e.g. "startTimeoutMonitor is one-shot: no retry after first NACK"]
```

If gaps are found → these are the leading hypotheses for B2. Do not spend time testing
network-layer hypotheses (C3) when a protocol gap explains the failure.

**Phase gate:**
```
C4 COMPLETE — protocol recovery audit done, [N] gaps identified
```

---

## PHASE C5 — Fix Classification Matrix

After root cause is declared (B4), classify the fix:

```
FIX CLASSIFICATION
  Category  : [protocol-gap | routing-bug | socket-binding | unicast-loopback | state-machine]
  Scope     : [single file | cross-file — list affected files]
  Risk      : [low — isolated timer change | medium — state machine change | high — protocol change]
  File size : [N lines → Large File Rule applies YES/NO]
  Regression: [which prior fixes could be affected — check Fixes 1–N]
```

Then proceed to B5 (Fix Scoping) and B6 (Verification Protocol) from `debug-runtime-behavior`.

**Phase gate:**
```
C5 COMPLETE — fix classified, handing off to B5 →
```

---

## EXECUTION ORDER

When invoked via `debug-triage` for a network failure, execute phases in this order:

```
C4 → C0 → C1 → C2 → C3 (if needed) → B0 → B1 → B2 → B3 → B4 → C5 → B5 → B6
```

Rationale for C4-first: Protocol gaps (design bugs) are more common than routing failures.
Auditing recovery early prevents spending a full session on socket analysis when the real
issue is a missing retry loop.

---

## ABSOLUTE PROHIBITIONS (NETWORK ADDITIONS)

These extend the prohibitions in `debug-runtime-behavior`:

| # | Prohibited |
|---|---|
| N1 | Correlating Phone 1 and Phone 2 log events by timestamp |
| N2 | Skipping C4 Protocol Recovery Audit before analyzing logs |
| N3 | Concluding "network routing failure" (C3) before ruling out protocol gaps (C4) |
| N4 | Blaming packet loss without tracing what the recovery protocol does when a packet is lost |
| N5 | Attributing failure to "non-mesh WiFi" or "boundNetwork" without checking if same-config original packets arrived successfully |
| N6 | Questioning the user's test procedure (they force-quit both apps, scanned correct QR, verified WiFi settings — assume this is always correct) |

---

## GATE CHECKLIST

```
NETWORK PROTOCOL GATE
  [✓/✗] C4 — Protocol recovery audit done FIRST (before any logs), gaps identified
  [✓/✗] C0 — Topology snapshot recorded (IPs, PIDs, sockets, non-mesh WiFi)
  [✓/✗] C1 — All packet types inventoried with expected direction and socket
  [✓/✗] C2 — Cross-device event table built, correlated by content not timestamps
  [✓/✗] C3 — Socket/network layer analyzed (or skipped with reason)
  [✓/✗] B0 — Symptom locked (via runtime-behavior sub-skill)
  [✓/✗] B1 — Victim-first failure window identified
  [✓/✗] B2 — Hypotheses ranked, protocol gaps from C4 listed as leading hypotheses
  [✓/✗] B3 — Targeted evidence collection (no DEEP_INSPECT noise)
  [✓/✗] B4 — Root cause declared
  [✓/✗] C5 — Fix classified by category, scope, risk
  [✓/✗] B5 — Fix scoped (file size checked, edit method determined)
  [✓/✗] B6 — Verification protocol with success/failure log signatures
  [✓/✗] CLOCK — Phone 2 timestamp correlation warning applied
  [✓/✗] MODE — No prohibited actions taken for active mode
  [✓/✗] HEADER — Response opened with mandatory header block
```
