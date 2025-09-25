package org.torproject.android.util

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * App-scoped local broadcast helper that avoids the deprecated LocalBroadcastManager.
 *
 * Implementation notes:
 * - sendLocalBroadcast sets the package on the Intent so only receivers in this app get it.
 * - register/unregister delegate to applicationContext.registerReceiver/unregisterReceiver.
 *   Callers must still unregister receivers when appropriate.
 */
object LocalBroadcast {

    private const val TAG = "LocalBroadcast"

    fun sendLocalBroadcast(context: Context, intent: Intent) {
        try {
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (ise: IllegalStateException) {
            Log.w(TAG, "Failed to send local broadcast", ise)
        }
    }

    fun registerReceiver(context: Context, receiver: android.content.BroadcastReceiver, filter: android.content.IntentFilter) {
        try {
            context.applicationContext.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed", e)
        }
    }

    fun unregisterReceiver(context: Context, receiver: android.content.BroadcastReceiver) {
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterReceiver failed", e)
        }
    }
}
