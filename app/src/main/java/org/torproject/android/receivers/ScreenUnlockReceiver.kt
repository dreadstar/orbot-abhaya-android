package org.torproject.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.torproject.android.service.OrbotConstants.ACTION_START_VPN
import org.torproject.android.service.OrbotService
import org.torproject.android.service.util.Prefs

class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_USER_PRESENT != intent.action) return

        // Only restart if the user's stored preference is still to use VPN.
        // If Prefs.useVpn() is false, the user explicitly stopped Orbot — do not restart.
        if (!Prefs.useVpn()) {
            Log.d(TAG, "Screen unlocked but useVpn=false — not restarting")
            return
        }

        Log.d(TAG, "Screen unlocked and useVpn=true — checking OrbotService state")
        val startIntent = Intent(context, OrbotService::class.java).apply {
            action = ACTION_START_VPN
        }
        context.startService(startIntent)
    }

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }
}