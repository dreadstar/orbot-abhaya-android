package org.torproject.android.service;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import android.util.Log;

/**
 * Java helper to centralize LocalBroadcastManager usage and suppress deprecation warnings in one place.
 */
@SuppressWarnings("deprecation")
public final class LocalBroadcastHelper {

    private LocalBroadcastHelper() { }

    public static void sendLocalBroadcast(@NonNull Context ctx, @NonNull Intent intent) {
        try {
            intent.setPackage(ctx.getPackageName());
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w("LocalBroadcastHelper", "sendLocalBroadcast failed", e);
        }
    }

    public static void registerReceiver(@NonNull Context ctx, @NonNull android.content.BroadcastReceiver receiver, @NonNull android.content.IntentFilter filter) {
        try {
            ctx.getApplicationContext().registerReceiver(receiver, filter);
        } catch (Exception e) {
            Log.w("LocalBroadcastHelper", "registerReceiver failed", e);
        }
    }

    public static void unregisterReceiver(@NonNull Context ctx, @NonNull android.content.BroadcastReceiver receiver) {
        try {
            ctx.getApplicationContext().unregisterReceiver(receiver);
        } catch (Exception e) {
            Log.w("LocalBroadcastHelper", "unregisterReceiver failed", e);
        }
    }
}
