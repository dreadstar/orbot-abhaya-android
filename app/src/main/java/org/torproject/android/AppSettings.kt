package org.torproject.android

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    fun getReplicaCount(): Int {
        // Read from SharedPreferences or config file
        // Default to 3 if not set
        return prefs.getInt("replica_count", 3)
    }
}