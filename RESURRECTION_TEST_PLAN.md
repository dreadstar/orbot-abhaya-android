# RESURRECTION TEST PLAN
**Orbot-Abhaya Manual Testing & Deployment Guide**  
**Created:** December 6, 2025  
**Last Updated:** December 6, 2025

---

## OVERVIEW

This document contains all manual testing procedures that must be executed when the Orbot-Abhaya application is ready for deployment to physical test devices. These tests validate functionality that cannot be fully verified through automated unit and integration tests, particularly those requiring real hardware, network conditions, and user interaction.

---

## PRE-DEPLOYMENT CHECKLIST

### Prerequisites

- [ ] All unit tests passing (`./gradlew :Meshrabiya:lib-meshrabiya:test`)
- [ ] All integration tests passing (`./gradlew :Meshrabiya:lib-meshrabiya:connectedAndroidTest`)
- [ ] Code review complete
- [ ] Documentation updated
- [ ] Build successful (debug and release)

### Device Requirements

**Minimum:** 2 Android devices for gateway routing tests  
**Recommended:** 3+ devices for multi-gateway scenarios

**Device Setup:**
- [ ] Android 8.0+ (API 26+)
- [ ] ADB debugging enabled
- [ ] Connected to same WiFi network
- [ ] Battery optimization disabled for Orbot

### Build Commands

```bash
# Clean build
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew clean

# Debug APK
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :app:assembleDebug --console=plain

# Release APK (for production deployment)
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :app:assembleRelease --console=plain
```

### Installation

```bash
# Set up environment
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

# Get device IDs
adb devices

# Install on each device
adb -s <device_id> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## TOR GATEWAY ROUTING TESTS

### TEST 1: Basic Gateway Routing (TOR_ONLY)

**Objective:** Verify client can route packets to internet via Tor gateway node.

**Setup:**
- Device 1: Client node (Tor NOT installed)
- Device 2: Gateway node (Tor installed and running)

**Steps:**

1. **Device 2 (Gateway):**
   - [ ] Open Orbot
   - [ ] Start Tor (wait for "Connected" status)
   - [ ] Open Meshrabiya settings
   - [ ] Enable "Act as Gateway"
   - [ ] Select "Tor Gateway" role
   - [ ] Verify status shows "Gateway: Tor"

2. **Device 1 (Client):**
   - [ ] Open Orbot
   - [ ] Open Meshrabiya settings
   - [ ] Set Gateway Preference = "TOR_ONLY"
   - [ ] Wait for gateway discovery (check network info)
   - [ ] Verify "Tor Gateways: 1" shown
   - [ ] Verify "Clearnet Gateways: 0"

3. **Send Test Packet:**
   - [ ] Device 1: Send ping to 8.8.8.8
   - [ ] Monitor logs: `adb -s <device1_id> logcat -s Meshrabiya:V`
   - [ ] Verify log: "Routing via gateway (type=TOR)"
   - [ ] Verify log: "Forwarding to gateway <device2_addr>"

4. **Verify on Gateway:**
   - [ ] Device 2: Check gateway statistics
   - [ ] Verify "Packets Routed: 1+"
   - [ ] Verify "Tor Packets: 1+"
   - [ ] Verify "Clearnet Packets: 0"

**Expected Result:**
✅ Packet routed from Device 1 → Device 2 → Internet via Tor

**Success Criteria:**
- Client discovers gateway within 5 seconds
- Packet routing latency < 500ms
- Gateway statistics increment correctly

---

### TEST 2: Per-App VPN Override

**Objective:** Verify Orbot VPN per-app rules supersede global gateway preference.

**Setup:**
- Device 1: Client with VPN enabled
- Device 2: Tor gateway (running)
- Device 3: Clearnet gateway (running)

**Steps:**

1. **Device 1 Setup:**
   - [ ] Open Orbot
   - [ ] Enable VPN mode
   - [ ] Open "Choose Apps"
   - [ ] Select Chrome for Tor routing (check checkbox)
   - [ ] Deselect Firefox (leave unchecked)
   - [ ] Save settings

2. **Meshrabiya Settings:**
   - [ ] Set Gateway Preference = "CLEARNET_ONLY"
   - [ ] Verify NetworkInfo shows:
     - Tor Gateways: 1
     - Clearnet Gateways: 1

3. **Test Chrome (should use Tor):**
   - [ ] Open Chrome
   - [ ] Navigate to https://check.torproject.org
   - [ ] Monitor logs: `adb logcat -s Meshrabiya:V | grep -i vpn`
   - [ ] Verify log: "VPN rule for com.android.chrome: gatewayType=TOR"
   - [ ] Expected webpage: "Congratulations. This browser is configured to use Tor."

4. **Test Firefox (should use clearnet):**
   - [ ] Open Firefox
   - [ ] Navigate to https://check.torproject.org
   - [ ] Monitor logs
   - [ ] Verify log: "Using global preference CLEARNET_ONLY"
   - [ ] Expected webpage: "Sorry. You are not using Tor."

**Expected Result:**
✅ Chrome uses Tor (VPN rule supersedes CLEARNET_ONLY preference)  
✅ Firefox uses clearnet (follows global preference)

**Success Criteria:**
- VPN per-app rules correctly detected
- Chrome routed via Tor gateway
- Firefox routed via clearnet gateway
- Logs show VPN rule precedence

---

### TEST 3: Gateway Failover (EITHER Preference)

**Objective:** Verify EITHER preference falls back when primary gateway unavailable.

**Setup:**
- Device 1: Client
- Device 2: Tor gateway
- Device 3: Clearnet gateway

**Steps:**

1. **Initial Setup:**
   - [ ] Ensure both gateways running
   - [ ] Device 1: Set preference = "EITHER"
   - [ ] Verify NetworkInfo shows:
     - Tor Gateways: 1
     - Clearnet Gateways: 1

2. **Send Packet (Tor Preferred):**
   - [ ] Device 1: Send packet to 1.1.1.1
   - [ ] Verify log: "Routing via gateway (type=TOR)"
   - [ ] Verify packet routed to Device 2

3. **Disable Tor Gateway:**
   - [ ] Device 2: Stop Tor service (or stop Meshrabiya)
   - [ ] Wait 35 seconds (stale timeout)
   - [ ] Device 1: Check NetworkInfo
   - [ ] Verify: Tor Gateways: 0, Clearnet: 1

4. **Send Packet (Fallback to Clearnet):**
   - [ ] Device 1: Send packet to 1.1.1.1
   - [ ] Monitor logs
   - [ ] Verify log: "No TOR gateway available"
   - [ ] Verify log: "Attempting fallback to CLEARNET"
   - [ ] Verify log: "Forwarding to gateway <device3_addr>"

**Expected Result:**
✅ Initial packets use Tor gateway  
✅ After Tor gateway stale, packets fallback to clearnet gateway  
✅ No packet loss during transition

**Success Criteria:**
- Stale gateway detection within 35 seconds
- Automatic failover to alternate gateway type
- Failover log messages present
- No crashes or ANR during failover

---

### TEST 4: No Gateway Available (Packet Drop)

**Objective:** Verify packets are properly dropped when no suitable gateway exists.

**Setup:**
- Device 1: Client only (no gateway capabilities)
- No other devices (no gateways on mesh)

**Steps:**

1. **Client Setup:**
   - [ ] Set preference = "TOR_ONLY"
   - [ ] Verify NetworkInfo: Tor Gateways: 0, Clearnet: 0

2. **Send Packet:**
   - [ ] Send packet to 8.8.8.8
   - [ ] Monitor logs
   - [ ] Verify warning: "No TOR gateway available"
   - [ ] Verify warning: "Dropping packet: no suitable gateway"

3. **Verify No Crash:**
   - [ ] App remains running
   - [ ] No ANR (Application Not Responding)
   - [ ] UI remains responsive

**Expected Result:**
✅ Packet dropped gracefully  
✅ Warning logged  
✅ No crash or hang

**Success Criteria:**
- Clear warning message logged
- No exception thrown
- App continues functioning normally

---

## PERFORMANCE TESTS

### PERF-1: Gateway Discovery Time

**Objective:** Measure time from mesh start to gateway discovery.

**Steps:**
1. [ ] Start gateway node (Device 2)
2. [ ] Wait for Tor connection
3. [ ] Note timestamp
4. [ ] Start client node (Device 1)
5. [ ] Monitor NetworkInfo until gateway appears
6. [ ] Note timestamp

**Success Criteria:**
- Gateway discovered within 5 seconds
- NetworkInfo updates automatically

**Log Command:**
```bash
adb logcat -s Meshrabiya:V | grep -E "(Discovered|gateway)"
```

---

### PERF-2: Packet Routing Latency

**Objective:** Measure end-to-end routing latency through gateway.

**Steps:**
1. [ ] Client and gateway on same WiFi
2. [ ] Send 10 ping packets to 8.8.8.8
3. [ ] Measure time from send to gateway receipt
4. [ ] Calculate average latency

**Success Criteria:**
- Average latency < 500ms (local network)
- No packet loss

**Measurement Command:**
```bash
adb shell "ping -c 10 8.8.8.8" | grep "time="
```

---

### PERF-3: Gateway Failover Time

**Objective:** Measure time to failover to alternate gateway.

**Steps:**
1. [ ] Client using Tor gateway (preference = EITHER)
2. [ ] Kill Tor gateway abruptly
3. [ ] Note timestamp
4. [ ] Monitor logs until clearnet gateway selected
5. [ ] Note timestamp

**Success Criteria:**
- Failover within 35 seconds (stale timeout + selection)
- Next packet routes successfully

---

### PERF-4: Memory Usage

**Objective:** Verify no memory leaks from gateway message tracking.

**Steps:**
1. [ ] Start app, note baseline memory
2. [ ] Route 1000 packets through gateway
3. [ ] Wait 5 minutes
4. [ ] Check memory usage again
5. [ ] Force GC and recheck

**Success Criteria:**
- Memory increase < 5MB after 1000 packets
- Memory returns to baseline after cleanup
- No OutOfMemoryError

**Monitoring Command:**
```bash
adb shell "dumpsys meminfo org.torproject.android"
```

---

## EDGE CASE TESTS

### EDGE-1: Stale Gateway Filtering

**Objective:** Verify stale gateways are not used for routing.

**Steps:**
1. [ ] Gateway running normally
2. [ ] Client sees gateway in NetworkInfo
3. [ ] Kill gateway Meshrabiya service (no clean shutdown)
4. [ ] Wait 35 seconds
5. [ ] Client sends packet
6. [ ] Verify gateway NOT selected

**Expected Result:**
✅ Stale gateway filtered out  
✅ Alternate gateway used OR packet dropped

---

### EDGE-2: Multiple Gateways (Load Distribution)

**Objective:** Verify load distribution across multiple gateways.

**Setup:**
- 3 Tor gateways at equal distance

**Steps:**
1. [ ] Client sees 3 Tor gateways
2. [ ] Send 30 packets
3. [ ] Monitor which gateway receives each packet
4. [ ] Calculate distribution

**Success Criteria:**
- Each gateway receives ~10 packets (±3)
- No single gateway receives all packets

---

### EDGE-3: Gateway Type Mismatch

**Objective:** Verify behavior when gateway advertises wrong type.

**Steps:**
1. [ ] Gateway advertises as TOR_GATEWAY
2. [ ] Gateway has Tor stopped
3. [ ] Client sends packet with gatewayType=TOR
4. [ ] Monitor what happens

**Expected Result:**
✅ Gateway attempts Tor routing  
✅ Fails gracefully if Tor unavailable  
✅ Error logged

---

## LOG MONITORING GUIDE

### Key Log Messages

**Gateway Discovery:**
```
D/Meshrabiya: Discovered Tor gateway: 10.0.0.2
D/Meshrabiya: Discovered Clearnet gateway: 10.0.0.3
```

**VPN Rule Precedence:**
```
D/Meshrabiya: VPN per-app rule for com.android.chrome: gatewayType=TOR
D/Meshrabiya: Using global gateway preference: TOR_ONLY → gatewayType=TOR
```

**Gateway Routing:**
```
D/Meshrabiya: Routing internet-bound packet via gateway (type=TOR)
D/Meshrabiya: Selected gateway: 10.0.0.2 (distance=1)
D/Meshrabiya: Forwarding packet to gateway 10.0.0.2 (hop 1)
```

**Failover:**
```
W/Meshrabiya: No gateway available for type=TOR
I/Meshrabiya: Attempting fallback to gateway type=CLEARNET
D/Meshrabiya: Forwarding to CLEARNET gateway 10.0.0.3
```

### Log Commands

```bash
# All Meshrabiya logs
adb logcat -s Meshrabiya:V

# Gateway routing only
adb logcat -s Meshrabiya:V | grep -E "(gateway|Gateway|GATEWAY)"

# VPN rules only
adb logcat -s Meshrabiya:V | grep -E "(VPN|per-app)"

# Errors and warnings
adb logcat -s Meshrabiya:W Meshrabiya:E

# Follow logs in real-time
adb logcat -s Meshrabiya:V | grep --line-buffered -E "(gateway|VPN|error)"
```

---

## DEPLOYMENT PROCEDURE

### 1. Pre-Release Validation

- [ ] All automated tests passing
- [ ] All manual tests from this document complete
- [ ] No critical bugs
- [ ] Performance metrics acceptable
- [ ] Code review approved
- [ ] Documentation updated

### 2. Build Release APK

```bash
# Clean workspace
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew clean

# Build release
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :app:assembleRelease --console=plain

# Verify APK location
ls -lh app/build/outputs/apk/release/
```

### 3. Sign APK (Production)

```bash
# Sign with release keystore
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore /path/to/release.keystore \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  orbot_release_key

# Verify signature
jarsigner -verify -verbose -certs \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

### 4. Create GitHub Release

```bash
# Tag release
git tag -a v3.0.0-tor-gateway -m "Tor Gateway Routing V3"

# Push tag
git push origin v3.0.0-tor-gateway

# Create release on GitHub
# Upload APK to release assets
```

### 5. Release Notes Template

```markdown
## Orbot-Abhaya v3.0.0 - Tor Gateway Routing

### New Features
- ✅ Per-App VPN Proxy Rules Support
  - VPN per-app settings now supersede global gateway preference
  - Chrome can use Tor while Firefox uses clearnet
  
- ✅ Gateway Routing with Failover
  - Automatic gateway discovery (Tor & clearnet)
  - Smart gateway selection based on distance/load
  - Automatic failover to alternate gateway type
  
- ✅ Enhanced NetworkInfo
  - Gateway breakdown (Tor vs clearnet counts)
  - Real-time gateway availability updates

### Technical Changes
- Packet header extended to 21 bytes (added gatewayType field)
- Gateway message tracking for return path routing
- Stale gateway filtering (30-second timeout)

### Testing
- 90%+ unit test coverage
- Integration tests for all gateway scenarios
- Manual testing on 3+ devices

### Installation
Download `app-debug.apk` or `app-release.apk` from release assets.

### Known Issues
(List any known limitations or issues)
```

### 6. Post-Deployment Monitoring

**First 24 Hours:**
- [ ] Monitor crash reports
- [ ] Check gateway routing success rate
- [ ] Monitor VPN rule precedence issues
- [ ] Gather user feedback

**Monitoring Commands:**
```bash
# Check crash rate
adb shell "dumpsys dropbox --print | grep crash"

# Monitor ANRs
adb shell "dumpsys dropbox --print | grep anr"
```

---

## ROLLBACK PLAN

### If Critical Issues Found

**Immediate Actions:**
1. [ ] Remove release from GitHub
2. [ ] Post announcement: "Known issue, investigating"
3. [ ] Provide previous stable version link

**Rollback Commands:**
```bash
# Revert commits
git revert <v3_commit_hash>

# Build previous version
git checkout v2.0.0
./gradlew :app:assembleRelease

# Deploy rollback APK
# (upload to GitHub releases)
```

**Issue Resolution:**
1. [ ] Identify root cause
2. [ ] Create hotfix branch: `git checkout -b hotfix/v3.0.1`
3. [ ] Fix issue
4. [ ] Re-run all tests from this document
5. [ ] Deploy v3.0.1 patch

---

## SUCCESS CRITERIA

Deployment is successful when ALL criteria met:

**Testing:**
- [x] All unit tests pass (>90% coverage)
- [x] All integration tests pass
- [ ] All manual test scenarios complete successfully
- [ ] Per-app VPN override works (Chrome vs Firefox test)
- [ ] Gateway failover works (EITHER preference)
- [ ] No packet loss during failover

**Performance:**
- [ ] Gateway discovery < 5 seconds
- [ ] Packet routing latency < 500ms
- [ ] Failover time < 35 seconds
- [ ] Memory usage increase < 5MB per 1000 packets

**Stability:**
- [ ] No crashes during testing
- [ ] No ANR (Application Not Responding)
- [ ] No memory leaks detected
- [ ] Crash rate < 0.1% (post-deployment)

**Functionality:**
- [ ] All gateway routing scenarios work
- [ ] VPN per-app rules correctly applied
- [ ] Gateway statistics accurate
- [ ] Logs show expected messages

---

## REGRESSION TESTS

### Existing Functionality

After deploying gateway routing features, verify core functionality still works:

- [ ] Basic mesh connectivity (peer discovery)
- [ ] MMCP message routing
- [ ] VPN mode (without per-app rules)
- [ ] Tor connectivity (on gateway nodes)
- [ ] App settings persistence
- [ ] Orbot UI responsiveness

### Test Existing Features

```bash
# Run full test suite
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew test connectedAndroidTest
```

---

## FUTURE ENHANCEMENTS

**Items to test when implemented:**

1. **Multi-Hop Gateway Routing:**
   - [ ] Packet routed through 2+ gateways
   - [ ] Each hop verified in logs

2. **Gateway Load Balancing:**
   - [ ] Dynamic gateway selection based on load
   - [ ] Even distribution across gateways

3. **Return Path Routing:**
   - [ ] Return packets use same gateway as request
   - [ ] Symmetric routing verified

4. **Gateway Authentication:**
   - [ ] Only authorized nodes can act as gateways
   - [ ] Authentication challenges logged

---

## APPENDIX: DEVICE IDS

**Record device IDs for testing:**

| Device | Serial Number | Role | Notes |
|--------|---------------|------|-------|
| Device 1 | | Client | No Tor |
| Device 2 | | Tor Gateway | Tor installed |
| Device 3 | | Clearnet Gateway | No Tor |

**Get device IDs:**
```bash
adb devices -l
```

---

## APPENDIX: TEST MATRIX

| Test Scenario | Client Pref | VPN Rules | Gateways Available | Expected Behavior |
|---------------|-------------|-----------|-------------------|-------------------|
| Basic Tor | TOR_ONLY | None | Tor: 1, Clear: 0 | Route via Tor |
| Basic Clearnet | CLEARNET_ONLY | None | Tor: 0, Clear: 1 | Route via Clearnet |
| VPN Override | CLEARNET_ONLY | Chrome→Tor | Tor: 1, Clear: 1 | Chrome→Tor, others→Clear |
| Failover | EITHER | None | Tor: 0, Clear: 1 | Fallback to Clearnet |
| No Gateway | TOR_ONLY | None | Tor: 0, Clear: 0 | Drop packet |
| Multi-Gateway | EITHER | None | Tor: 2, Clear: 1 | Load distribute |

---

**END OF RESURRECTION TEST PLAN**

**Last Updated:** December 6, 2025  
**Next Review:** Before next major release
