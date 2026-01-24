# Mesh Networking Testing Plan

## Overview
This document describes a practical, device-based strategy to verify that the Meshrabiya mesh networking functionality is fully operational, using two real Android devices and logcat monitoring.

---

## Setup Steps

### 1. Build and Install APK on Both Devices

```bash
# From orbot-android root
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug

# Install on connected device
export ANDROID_HOME="/Users/dreadstar/Library/Android/sdk" && export PATH="$PATH:$ANDROID_HOME/platform-tools" && truncate -s 0 ready_state_fix_deploy.log && adb  -s 30870044490006E   install -r app/build/outputs/apk/fullperm/debug/app-fullperm-universal-debug.apk

# Install on second device (connect it, then run again)
export ANDROID_HOME="/Users/dreadstar/Library/Android/sdk" && export PATH="$PATH:$ANDROID_HOME/platform-tools" && truncate -s 0 ready_state_fix_deploy.log && adb  -s LML211BL3f1c96e3   install -r app/build/outputs/apk/fullperm/debug/app-fullperm-universal-debug.apk

# Phone 2 - Line-buffered tee (flushes every line)
truncate -s 0 phone_test2.log && adb -s LML211BL3f1c96e3 logcat -v time *:V | stdbuf -oL tee phone_test2.log

# OR truncate first, then run
truncate -s 0 phone_test2.log
adb -s LML211BL3f1c96e3 logcat -c
adb -s LML211BL3f1c96e3 logcat -v time *:V 2>&1 | tee -a phone_test2.log
```

### 2. Keep One Device Connected for Monitoring

```bash
# Check connected devices
adb devices

# If multiple devices, specify which one to monitor
adb -s <DEVICE_ID> logcat | grep -E "(Meshrabiya|VirtualNode|EmergentRole|MMCP|OriginatingMessage)"
```

Your device ID: `30870044490006E`

```bash
# Monitor mesh-specific logs from your connected device
adb -s 30870044490006E logcat | grep -E "(Meshrabiya|VirtualNode|EmergentRole|MMCP|OriginatingMessage|AndroidVirtualNode)"
```
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew assembleFullpermDebug  --console=plain 2>&1 | tee build_output.log
 export ANDROID_HOME="/Users/dreadstar/Library/Android/sdk" && export PATH="$PATH:$ANDROID_HOME/platform-tools" && truncate -s 0 ready_state_fix_deploy.log && adb install -r app/build/outputs/apk/fullperm/debug/app-fullperm-universal-debug.apk | tee ready_state_fix_deploy.log
adb logcat -c && truncate -s 0 ./phone_test.log &&  adb logcat | tee ./phone_test.log
```
truncate -s 0 ./phone_test.log &&  adb  -s 30870044490006E logcat -c &&  adb  -s 30870044490006E logcat -v time *:V | tee phone_test.log

truncate -s 0 ./phone_test2.log &&  adb  -s LML211BL3f1c96e3 logcat -c &&  adb  -s LML211BL3f1c96e3 logcat -v time *:V | tee phone_test2.log
---

## Testing Procedure

### On Both Devices:
1. Open Orbot app
2. Navigate to the Mesh tab (EnhancedMeshFragment)
3. Enable mesh networking (toggle mesh button)
4. Wait 10-15 seconds

### What to Watch in Logcat (Connected Device):

```bash
# Successful peer discovery looks like:
D/Meshrabiya: Broadcasting originating message for node 169.254.x.x
D/VirtualNode: New neighbor connection established: 169.254.y.y
D/OriginatingMessage: Received originating message from node 123
D/EmergentRoleManager: Topology updated: 2 nodes visible
D/AndroidVirtualNode: Neighbor count: 1
```

---

## Verification Checklist

| Step | Device 1 (Connected) | Device 2 | Success Indicator |
|------|---------------------|----------|-------------------|
| 1. Mesh Init | Logcat: "AndroidVirtualNode initialized" | N/A | ✅ Node created |
| 2. WiFi Hotspot | Check WiFi settings shows hotspot active | N/A | ✅ Hotspot enabled |
| 3. Peer Discovery | Logcat: "New neighbor connection" | Check mesh UI shows "Connected" | ✅ Neighbors > 0 |
| 4. Originating Messages | Logcat: "Broadcasting originating message" every 3s | N/A | ✅ Broadcasting active |
| 5. Topology Building | Logcat: "Topology updated: 2 nodes" | N/A | ✅ Mesh formed |
| 6. Role Assignment | Logcat: "Current roles: [MESH_PARTICIPANT, ...]" | N/A | ✅ Roles assigned |

---

## What You'll See if Mesh Works

### In the UI (Both Devices):
- Mesh Status: `CONNECTED` (instead of `DISABLED` or `CONNECTING`)
- Peer Count: `1` (each device sees the other)
- Node Info: Shows local IP address (169.254.x.x)
- Network Stats: Shows neighbor count and topology size

### In Logcat (Connected Device):
```
D/Meshrabiya: Mesh initialization started
D/AndroidVirtualNode: Created virtual node with address 169.254.123.45
D/VirtualNode: Starting WiFi hotspot on band 2.4GHz
D/OriginatingMessage: Broadcasting originating message (TTL=60s)
D/VirtualNode: Received MMCP originating message from 169.254.67.89
D/OriginatingMessage: Added new neighbor: 169.254.67.89, hopCount=1
D/EmergentRoleManager: Topology map updated: 2 nodes
D/EmergentRoleManager: Calculating fitness score...
D/EmergentRoleManager: Current roles: [MESH_PARTICIPANT]
```

---

## Troubleshooting

### If No Peer Discovery After 30 Seconds:
1. **Check WiFi Permissions:**
   - Both devices need Location and Nearby Devices permissions
   - Check Android settings → Apps → Orbot → Permissions
2. **Verify WiFi Direct is Available:**
   ```bash
   adb -s 30870044490006E shell dumpsys wifi | grep "P2p"
   ```
3. **Check Hotspot Status:**
   ```bash
   adb -s 30870044490006E logcat | grep -i "hotspot"
   ```
4. **Ensure Both on Same WiFi Band:**
   - Both devices should use 2.4GHz (more compatible)
   - Check MeshrabiyaConstants.kt for WiFi config

### Expected Logcat Issues (Safe to Ignore):
```
W/Meshrabiya: No gateway available (expected if no Tor running)
D/VirtualNode: Originating message refresh (periodic, normal)
I/EmergentRoleManager: Fitness score low (expected with only 2 nodes)
```

---

## Quick Test Commands

```bash
# 1. Monitor mesh activity
adb -s 30870044490006E logcat -s Meshrabiya:* VirtualNode:* AndroidVirtualNode:*

# 2. Check current neighbor count
adb -s 30870044490006E logcat | grep "neighbor"

# 3. Watch for topology updates
adb -s 30870044490006E logcat | grep "Topology"

# 4. See role assignments
adb -s 30870044490006E logcat | grep "Current roles"
```

---

## Bottom Line

**This is the simplest, most direct way to verify mesh networking:**
- Build APK
- Install on 2 devices
- Connect 1 device via ADB
- Enable mesh on both
- Watch logcat for "neighbor" and "topology"

If you see "New neighbor connection" and "Topology updated: 2 nodes" in logcat within 15 seconds, **mesh networking is fully operational**. ✅

adb -s LML211BL3f1c96e3 logcat -c
adb -s 30870044490006E logcat -c

adb -s LML211BL3f1c96e3 logcat -d -v time '*:V' | grep -E "VirtualNodeDatagramSocket|OriginatingMessageManager|addNeighbor|MeshrabiyaApiImpl|JOIN|RECEIVED|SENDING|📦|⬇️|⬆️|🔗|📡|📥|🤝" > phone2_complete.log

adb -s 30870044490006E logcat -d -v time '*:V' | grep -E "VirtualNodeDatagramSocket|OriginatingMessageManager|addNeighbor|MeshrabiyaApiImpl|JOIN|RECEIVED|SENDING|📦|⬇️|⬆️|🔗|📡|📥|🤝" > phone1_complete.log