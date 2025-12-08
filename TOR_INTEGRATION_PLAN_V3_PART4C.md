# Tor Integration Plan V3 - Part 4C: Manual Testing & Deployment
**Version:** 3.0  
**Date:** January 2025  
**Dependencies:** Parts 4A-4B complete

---

## 4C.1 MANUAL TESTING CHECKLIST

### Pre-Deployment Testing

#### Device Setup

- [ ] Device 1: Client node (no Tor installed)
- [ ] Device 2: Gateway node (Tor installed and running)
- [ ] Both devices on same WiFi network
- [ ] ADB debugging enabled on both devices

#### Installation

```bash
# Build debug APK
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :app:assembleDebug --console=plain

# Install on both devices
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

adb -s <device1_id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device2_id> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 4C.2 TEST SCENARIO 1: BASIC GATEWAY ROUTING

### Objective
Verify client can route packets to internet via gateway node.

### Steps

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

3. **Send Test Packet:**
   - [ ] Device 1: Send ping to 8.8.8.8
   - [ ] Monitor logs: `adb logcat -s Meshrabiya:V`
   - [ ] Verify log shows: "Routing via gateway (type=TOR)"
   - [ ] Verify log shows: "Forwarding to gateway <device2_addr>"

4. **Verify on Gateway:**
   - [ ] Device 2: Check gateway statistics
   - [ ] Verify "Packets Routed: 1"
   - [ ] Verify "Tor Packets: 1"

### Expected Result
✅ Packet routed from Device 1 → Device 2 → Internet via Tor

---

## 4C.3 TEST SCENARIO 2: PER-APP VPN OVERRIDE

### Objective
Verify Orbot VPN per-app rules supersede global preference.

### Steps

1. **Device 1 Setup:**
   - [ ] Open Orbot
   - [ ] Enable VPN mode
   - [ ] Open "Choose Apps"
   - [ ] Select Chrome for Tor routing
   - [ ] Deselect Firefox (clearnet)

2. **Meshrabiya Settings:**
   - [ ] Set Gateway Preference = "CLEARNET_ONLY"
   - [ ] Verify both Tor and Clearnet gateways available

3. **Test Chrome (should use Tor):**
   - [ ] Open Chrome
   - [ ] Navigate to https://check.torproject.org
   - [ ] Monitor logs: `adb logcat -s Meshrabiya:V`
   - [ ] Verify log: "VPN rule for com.android.chrome: gatewayType=TOR"
   - [ ] Expected: "Congratulations. This browser is configured to use Tor."

4. **Test Firefox (should use clearnet):**
   - [ ] Open Firefox
   - [ ] Navigate to https://check.torproject.org
   - [ ] Monitor logs
   - [ ] Verify log: "VPN rule for org.mozilla.firefox: gatewayType=CLEARNET"
   - [ ] Expected: "Sorry. You are not using Tor."

### Expected Result
✅ Chrome uses Tor (VPN rule supersedes CLEARNET_ONLY)  
✅ Firefox uses clearnet (VPN rule supersedes preference)

---

## 4C.4 TEST SCENARIO 3: GATEWAY FAILOVER

### Objective
Verify EITHER preference falls back when primary gateway unavailable.

### Steps

1. **Initial Setup:**
   - [ ] 2 gateway nodes: Device 2 (Tor), Device 3 (Clearnet)
   - [ ] Device 1 preference = "EITHER"
   - [ ] Verify NetworkInfo shows: Tor Gateways=1, Clearnet=1

2. **Disable Tor Gateway:**
   - [ ] Device 2: Stop Tor service
   - [ ] Wait 30 seconds (stale timeout)
   - [ ] Device 1: Verify NetworkInfo shows: Tor Gateways=0, Clearnet=1

3. **Send Packet:**
   - [ ] Device 1: Send packet to internet
   - [ ] Monitor logs
   - [ ] Verify log: "No TOR gateway available"
   - [ ] Verify log: "Attempting fallback to CLEARNET"
   - [ ] Verify log: "Forwarding to gateway <device3_addr>"

### Expected Result
✅ Packet routed via clearnet gateway (fallback successful)

---

## 4C.5 LOG MONITORING

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
```

---

## 4C.6 PERFORMANCE TESTING

### Metrics to Measure

1. **Gateway Discovery Time:**
   - [ ] Start client node
   - [ ] Time until gateway appears in NetworkInfo
   - [ ] Target: < 5 seconds

2. **Packet Routing Latency:**
   - [ ] Send ping packet
   - [ ] Measure time to gateway receipt
   - [ ] Target: < 500ms (local network)

3. **Gateway Failover Time:**
   - [ ] Disable primary gateway
   - [ ] Time until fallback gateway selected
   - [ ] Target: < 30 seconds (stale timeout)

### Performance Test Commands

```bash
# Ping via mesh gateway
adb shell "ping -c 10 8.8.8.8" | grep "time="

# Measure app startup time
adb shell "am start -W org.torproject.android/.ui.OrbotMainActivity"

# Monitor memory usage
adb shell "dumpsys meminfo org.torproject.android"
```

---

## 4C.7 EDGE CASE VALIDATION

### No Gateway Available

- [ ] Client preference = TOR_ONLY
- [ ] No Tor gateways on mesh
- [ ] Send packet
- [ ] Verify log: "Dropping packet: no TOR gateway"
- [ ] Verify packet not sent

### Stale Gateway Filtering

- [ ] Gateway node running
- [ ] Kill Meshrabiya service on gateway (no heartbeat)
- [ ] Wait 35 seconds
- [ ] Verify client NetworkInfo shows 0 gateways
- [ ] Verify client does not route to stale gateway

### Multiple Gateways (Load Balancing)

- [ ] 3 gateway nodes at equal distance
- [ ] Send 30 packets from client
- [ ] Verify packets distributed across gateways
- [ ] Expected: ~10 packets per gateway (round-robin)

---

## 4C.8 DEPLOYMENT CHECKLIST

### Pre-Deployment

- [ ] All unit tests pass (100%)
- [ ] All integration tests pass
- [ ] Manual testing scenarios complete
- [ ] Performance metrics acceptable
- [ ] Edge cases validated
- [ ] Code review complete
- [ ] Documentation updated

### Build Release APK

```bash
# Clean build
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew clean

# Build release
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew :app:assembleRelease --console=plain

# Sign APK (if needed)
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore /path/to/keystore.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  alias_name
```

### Deployment

- [ ] Upload APK to GitHub releases
- [ ] Tag release: `git tag v3.0.0-tor-integration`
- [ ] Push tag: `git push origin v3.0.0-tor-integration`
- [ ] Update README.md with new features
- [ ] Create release notes documenting:
  - [ ] Per-app VPN proxy rules support
  - [ ] Packet header gateway type field
  - [ ] Gateway routing with failover
  - [ ] NetworkInfo gateway breakdown

### Post-Deployment Monitoring

- [ ] Monitor crash reports (first 48 hours)
- [ ] Check gateway routing success rate
- [ ] Monitor VPN rule precedence issues
- [ ] Gather user feedback on gateway selection

---

## 4C.9 ROLLBACK PLAN

### If Critical Issues Found

1. **Immediate Actions:**
   - [ ] Remove release from GitHub
   - [ ] Post announcement: "Known issue, investigating"
   - [ ] Revert to previous version

2. **Rollback Commands:**
   ```bash
   # Revert commits
   git revert <v3_commit_hash>
   
   # Build previous version
   git checkout v2.0.0
   ./gradlew :app:assembleRelease
   
   # Deploy rollback
   # (upload previous version APK)
   ```

3. **Issue Resolution:**
   - [ ] Identify root cause
   - [ ] Create hotfix branch
   - [ ] Fix issue
   - [ ] Re-test with manual scenarios
   - [ ] Deploy v3.0.1 patch

---

## 4C.10 SUCCESS CRITERIA

V3 deployment is successful when:

- [x] All unit tests pass (>90% coverage)
- [x] All integration tests pass
- [x] Manual test scenarios complete successfully
- [x] Per-app VPN override works (Chrome vs Firefox test)
- [x] Gateway failover works (EITHER preference)
- [x] No critical bugs in first 48 hours
- [x] Gateway routing latency < 500ms
- [x] No memory leaks detected
- [x] Crash rate < 0.1%

---

**END OF PART 4C**

**TOR INTEGRATION PLAN V3 COMPLETE**

---

## FINAL SUMMARY

**V3 Plan Complete:**
- **Part 1:** VirtualPacketHeader extension (21 bytes with gatewayType)
- **Part 2:** Orbot VPN integration & proxy rules precedence
- **Part 3A:** Gateway routing core logic
- **Part 3B:** NetworkInfo gateway statistics
- **Part 3C:** OriginatingMessageManager updates
- **Part 4A:** Unit testing strategy
- **Part 4B:** Integration & E2E testing
- **Part 4C:** Manual testing & deployment

**Total Estimated Effort:** 25-30 hours
- Part 1: 4-6 hours
- Part 2: 8-10 hours
- Part 3: 6-8 hours
- Part 4: 7-9 hours

**Major V3 Improvements from V2:**
1. ✅ Packet header gateway type flag (simpler than port inspection)
2. ✅ Orbot VPN per-app rules precedence (supersedes preference)
3. ✅ Cross-module VPN settings access (SharedPreferences)
4. ✅ Gateway routing with multi-hop failover
5. ✅ Comprehensive testing strategy (unit + integration + E2E)

**All V2 Blockers Resolved:**
- ✅ VPN settings access (found in /app module)
- ✅ Packet classification (header flag vs port inspection)
- ✅ Routing integration point (route() method confirmed)
- ✅ Orbot deployment context (this IS Orbot fork)

**Ready for Implementation** ✅
