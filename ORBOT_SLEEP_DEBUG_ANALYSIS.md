=== PHASE 1: Manifest Service Declaration ===

<service
    android:name=".service.OrbotService"
    android:enabled="true"
    android:exported="true"
    android:foregroundServiceType="systemExempted"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:stopWithTask="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>

<receiver
    android:name=".service.StartTorReceiver"
    android:exported="true"
    tools:ignore="ExportedReceiver">
    <intent-filter>
        <action android:name="org.torproject.android.intent.action.START" />
    </intent-filter>
</receiver>

<receiver
    android:name=".receivers.OnBootReceiver"
    android:directBootAware="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>

<receiver
    android:name="com.ustadmobile.orbotmeshrabiyaintegration.routing.NetworkStateReceiver"
    android:enabled="true"
    android:exported="false">
    <intent-filter android:priority="1000">
        <action android:name="android.net.conn.CONNECTIVITY_CHANGE" />
        <action android:name="android.net.wifi.STATE_CHANGE" />
        <action android:name="android.net.wifi.WIFI_STATE_CHANGED" />
    </intent-filter>
</receiver>

---

=== PHASE 2: WakeLock Acquisition and Release ===

Filename: Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt

Method: acquireWakeLock() (lines ~58-71)
```
private fun acquireWakeLock() {
    context?.let {
        try {
            val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "meshrabiya:broadcast_wakelock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minute timeout
            }
            logger(Log.INFO, "$TAG CPU WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }
}
```

Method: releaseWakeLock() (lines ~76-86)
```
private fun releaseWakeLock() {
    wakeLock?.let {
        try {
            if (it.isHeld) {
                it.release()
                logger(Log.INFO, "$TAG CPU WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
        wakeLock = null
    }
}
```

---

=== PHASE 3: onRevoke() Handler ===

Filename: orbotservice/src/main/java/org/torproject/android/service/OrbotService.java

Method onRevoke() (lines ~749-756)
```
@Override
public void onRevoke() {
    Prefs.putUseVpn(false);
    mVpnManager.handleIntent(new Builder(), new Intent(ACTION_STOP_VPN));
    // tell UI, if it's open, to update immediately (don't wait for onResume() in Activity...)
    LocalBroadcastHelper.sendLocalBroadcast(this, new Intent(ACTION_STOP_VPN));
}
```

- Helper method: mVpnManager.handleIntent() inside onRevoke()
- Side effect: sets Prefs.useVpn false and sends STOP_VPN intent (deactivate path)

---

=== PHASE 4: Status State Machine — Deactivated Transitions ===

Enum/Constants definition:
Filename: orbotservice/src/main/java/org/torproject/android/service/OrbotConstants.kt
- STATUS_OFF = TorService.STATUS_OFF
- STATUS_ON = TorService.STATUS_ON
- STATUS_STARTING = TorService.STATUS_STARTING
- STATUS_STOPPING = TorService.STATUS_STOPPING
- STATUS_STARTS_DISABLED = "STARTS_DISABLED"

Transition sites in OrbotService.java:

1. mCurrentStatus initialization (line 83)
```
protected String mCurrentStatus = STATUS_OFF;
```

2. in ActionBroadcastReceiver.onReceive for ACTION_STATUS (lines ~877-892)
```
case ACTION_STATUS -> {
    var newStatus = intent.getStringExtra(EXTRA_STATUS);
    if (STATUS_OFF.equals(mCurrentStatus) && STATUS_STOPPING.equals(newStatus))
        break;
    mCurrentStatus = newStatus;
    if (STATUS_OFF.equals(mCurrentStatus)) {
        showToolbarNotification(getString(R.string.open_orbot_to_connect_to_tor), NOTIFY_ID, R.drawable.ic_stat_tor);
    }
    ...
}
```

3. startTorService() and stopTor() may post statuses via callbacks, but direct assignment is primarily in the receiver above.

---

=== PHASE 5: Screen Unlock Receiver ===

- No receiver for ACTION_SCREEN_ON / ACTION_USER_PRESENT is present in the codebase (grep returned no matches).
- The only relevant receiver patterns are BOOT_COMPLETED and connectivity state, not screen unlock.

---

=== PHASE 6: Battery Optimization Check ===

Filename: app/src/main/java/org/torproject/android/ui/v3onionservice/PermissionManager.kt

Method requestBatteryPermissions(...)
```
if (pm.isIgnoringBatteryOptimizations(packageName)) {
    return
}

Snackbar.make(...) .setAction(...) { 
    val intent = Intent().apply {
        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        data = "package:$packageName".toUri()
    }
    activity.startActivity(intent)
}.show()
```

Method requestDropBatteryPermissions(...)
```
val pm = ...
if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
    return
}
...
```

---

=== SUMMARY OF GAPS FOUND ===

Phase 1: OrbotService is foreground with stopWithTask=false; service declaration is correct for background keep-alive. No SCREEN_ON receiver present.
Phase 2: WakeLock is acquired with 10-minute timeout in BroadcastMessageHandler; this auto-release could cause sleep-related disconnect if used for VPN-relevant path.
Phase 3: onRevoke() deactivates VPN by toggling Prefs.useVpn false and sending ACTION_STOP_VPN without user/system distinction.
Phase 4: Status transitions use TorService constant strings; OFF state can be set from ACTION_STATUS broadcast regardless of user intent. No explicit system/user distinction in onRevoke path.
Phase 5: No screen-unlock receiver to re-assert status on unlock; if service dies due to OS, there is no reconnect trigger here.
Phase 6: Battery optimization check exists only in v3 onion service permission manager, not in Orbot core service startup.
