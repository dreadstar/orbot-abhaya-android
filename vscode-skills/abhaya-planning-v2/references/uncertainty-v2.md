# Uncertainty Identification v2

Full detail for **Phase 2 — Uncertainty Identification** of the Abhaya Planning v2 skill.

Extends the v1 uncertainty protocol with three mandatory new matrices: Observer
Lifecycle Timeline, View Ownership Matrix, and Cross-Role Behavior Matrix.
These were absent in v1 and caused PT3/PT4/PT5 (lifecycle timing bugs) and
PT6 (CLEARNET/TOR probe conflation).

---

## Standard Uncertainty Markers

- `~line 847` — approximate line number, needs pinning
- `⚠️ Open: [question]` — unresolved; blocks Phase 4
- `UNVERIFIED: [fact]` — not backed by a tool read
- `TYPE-A-WIRING ⚠️` — constructor injection gap
- `TYPE-A-LIVENESS ⚠️` — variable declared but never assigned/launched
- `TYPE-B-LIFECYCLE ⚠️` — observer/view timing race
- `TYPE-B-CONFLICT ⚠️` — two observers writing the same view
- `TYPE-C-PLATFORM ⚠️` — Android API behavior not verified
- `TYPE-D-ROLE ⚠️` — role/enum value treated as homogeneous
- `TYPE-E-STALE ⚠️` — symbol deleted but consumers not covered

---

## Section 2.1 — Standard Risk Table

| # | Type | Risk | Status | Resolution |
|---|------|------|--------|------------|
| 1 | TYPE-A | [Constructor param omitted?] | ⚠️ | Read instantiation site |
| 2 | TYPE-B | [Observer fires before views bound?] | ⚠️ | Trace lifecycle timeline |
| 3 | TYPE-C | [Platform API version-specific?] | ⚠️ | Read Javadoc + release notes |
| 4 | TYPE-D | [Shared code path for multiple roles?] | ⚠️ | Build role matrix |
| 5 | TYPE-E | [Deleted symbol has stale consumers?] | ⚠️ | grep all references |

---

## Section 2.2 — Observer Lifecycle Timeline (NEW v2, mandatory)

**Required for:** every new or modified observer/listener that depends on views
being bound, or on external data sources that may arrive before views exist.

**Template:**

```
TIMELINE: [observer name] in [file:approximate-line]

  [Fragment lifecycle event: onViewCreated / onDestroyView / etc.]
       │
       ├─ [observer setup call]
       │    ├─ StateFlow replays current value immediately
       │    ├─ [Any guard condition? — list it]
       │    └─ [Result: view updated / emission skipped]
       │
  [ViewStub inflate callback / viewLifecycleOwner.lifecycleScope.launch / etc.]
       │
       ├─ [bindDeferredViews() or equivalent]
       │    ├─ deferredViewsInitialized = true
       │    └─ [Does data reach these views? How?]
       │
  [External event: VPN broadcast / mesh join / etc.]
       │
       └─ [What reaches the view? When?]

  TIMING RISKS IDENTIFIED:
    - [Risk 1: observer fires before views exist → emission skipped]
    - [Risk 2: StateFlow deduplication — same value not re-emitted after views bind]
  
  FIX STRATEGY:
    - Read .value snapshot directly after views are bound (force-apply current state)
```

**Completeness requirement:** Every observer in the change must have a timeline.
Any timing risk → `TYPE-B-LIFECYCLE ⚠️ Open`.

---

## Section 2.3 — View Ownership Matrix (NEW v2, mandatory)

**Required for:** every view whose `.visibility` or content can be written by
more than one observer or code path.

**How to find conflicts:**
```
grep_search("viewName.visibility")
grep_search("viewName.text")
grep_search("viewName.setImageResource")
```

If result count > 1 across different observers: CONFLICT.

**Template:**

```
VIEW OWNERSHIP MATRIX

  View: [viewId]

  Writer 1: [observerName] in [file:line]
    Trigger: [what causes this observer to fire]
    Source:  [SNAPSHOT of stateFlow.value | REACTIVE collector of stateFlow]
    Writes:  [visibility VISIBLE/GONE | text | etc.]

  Writer 2: [observerName] in [file:line]
    Trigger: [what causes this observer to fire]
    Source:  [SNAPSHOT | REACTIVE]
    Writes:  [visibility VISIBLE/GONE | text | etc.]

  CONFLICT TYPE: [Snapshot-vs-Reactive | Dual-Reactive | Race-condition]
  
  RESOLUTION:
    Designate Writer [1 or 2] as sole owner.
    Remove write from Writer [other].
    Reason: [which source is more authoritative / less stale]
```

**Rule:** One view, one writer. Any conflict is `TYPE-B-CONFLICT ⚠️ Open`.

---

## Section 2.4 — Cross-Role Behavior Matrix (NEW v2, mandatory)

**Required for:** any change that involves an enum or role type with multiple
values (e.g., MeshRole, GatewayType, NodeCapabilityType).

**How to find role values:**
```
grep_search("enum class RoleName")         ← declaration
grep_search("RoleName.")                   ← all usages
grep_search("when (role)")                 ← branching logic
```

Read each value's handler/branch in full.

**Template:**

```
CROSS-ROLE BEHAVIOR MATRIX

  Enum: [EnumName]

  | Value            | Advertised when     | Network path    | Probe method   | Special |
  |------------------|---------------------|-----------------|----------------|---------|
  | CLEARNET_GATEWAY | VPN active + WiFi   | Direct TCP      | TCP:9080       | MeshInternetRelayServer must be running |
  | TOR_GATEWAY      | Orbot STATUS_ON     | SOCKS (Orbot)   | Presence only  | No relay server; probe != TCP |
  | TOR_RELAY        | Orbot active        | Forwarding      | N/A            | |
  | CLEARNET_RELAY   | VPN active          | Forwarding      | N/A            | |

  SHARED CODE PATHS THAT TOUCH MULTIPLE VALUES:
    - [method name]: handles all values identically → VERIFY each path works
    - getAvailableGatewayAddresses(): returns CLEARNET + TOR mixed
      → RISK: any consumer that assumes all returned addresses support the
        same probe will fail for one role type → TYPE-D-ROLE ⚠️ Open

  SPLIT REQUIRED:
    - checkInternetViaMeshGateway(): must branch on role type
      TOR_GATEWAY → presence check (role advertisement = proof of internet)
      CLEARNET_GATEWAY → TCP probe to port 9080
```

**Rule:** Never treat all values of an enum as equivalent in a shared code path
without verifying each value's specific requirements.

---

## Section 2.5 — Android Platform Audit (NEW v2, mandatory)

**Required for:** every Android framework API call introduced or modified.

**APIs that MUST be audited in this codebase:**
- `ContextCompat.registerReceiver` (flag semantics changed in Android 13+)
- `ConnectivityManager.getNetworkCapabilities` (min API 21)
- `NetworkCapabilities.hasTransport` (value constants available from API 21)
- `WifiManager.*` (several methods deprecated in API 29+)
- `VpnService.protect()` (API 14+, but behaviour varies across manufacturers)

**Template:**

```
PLATFORM AUDIT: [API method]

  Min API required: [N] (source: Javadoc @apiLevel or @RequiresApi)
  App minSdkVersion: [from build.gradle.kts]
  Compatible: [YES / NO — explain if NO]

  Version-specific breaking changes:
    Android [version]: [what changed and when]
    Relevant to this codebase: [YES / NO]

  Correct usage pattern:
    [Short code snippet from Javadoc example or verified usage in codebase]

  Cross-app broadcast requirement:
    Sender package: [external / internal]
    Required flag: [RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED / none]

  → [PASS / TYPE-C-PLATFORM ⚠️ Open: [specific issue]]
```

---

## Completeness Gate

Before advancing to Phase 4, verify:

- [ ] Risk table: zero `⚠️ Open` rows
- [ ] Zero `~line` approximate markers
- [ ] Observer lifecycle timeline completed for every new/modified observer
- [ ] View ownership matrix completed for every view with multiple potential writers
- [ ] Cross-role behavior matrix completed for every enum involved in the change
- [ ] Android platform audit completed for every new framework API call
- [ ] Constructor injection audit findings all resolved (no TYPE-A-WIRING open)
- [ ] Variable liveness audit findings all resolved (no TYPE-A-LIVENESS open)
