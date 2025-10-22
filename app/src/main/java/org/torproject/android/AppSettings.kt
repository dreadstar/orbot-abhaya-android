package org.torproject.android

import android.content.Context
import android.content.SharedPreferences

object AppSettings {

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }
    
    fun getReplicaCount(): Int {
        // Read from SharedPreferences or config file
        // Default to 3 if not set
        return prefs.getInt("replica_count", 3)
    }

    fun setReplicaCount(count: Int) {
        prefs.edit().putInt("replica_count", count).apply()
    }
}