package org.torproject.android

import org.torproject.android.R
import org.torproject.android.BuildConfig
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.torproject.android.localization.Languages
import org.torproject.android.localization.LocaleHelper
import org.torproject.android.service.util.Prefs
import java.util.Locale

// Meshrabiya imports
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import kotlinx.serialization.json.Json
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors

// DataStore extension property
private val Context.meshDataStore: DataStore<Preferences> by preferencesDataStore(name = "mesh_settings")

class OrbotApp : Application() {
    companion object {
        @JvmStatic
        lateinit var instance: OrbotApp
            private set

        var shouldRequestAuthentication: Boolean = true
        // see https://github.com/guardianproject/orbot-android/issues/1340
        var isAuthenticationPromptOpenLegacyFlag: Boolean = false
        fun resetLockFlags() {
            shouldRequestAuthentication = true
            isAuthenticationPromptOpenLegacyFlag = false
        }
    }
    // Meshrabiya core types
    lateinit var virtualNode: AndroidVirtualNode
    lateinit var meshLogger: MNetLogger
    lateinit var meshJson: Json

    override fun onCreate() {
        android.util.Log.d("OrbotApp", "onCreate() - START")
        
        try {
            super.onCreate()
            android.util.Log.d("OrbotApp", "onCreate() - super.onCreate() completed")

            instance = this
            android.util.Log.d("OrbotApp", "onCreate() - instance set")

            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    super.onStop(owner)
                    if (!isAuthenticationPromptOpenLegacyFlag)
                        shouldRequestAuthentication = true
                }
            })
            android.util.Log.d("OrbotApp", "onCreate() - lifecycle observer added")

            Prefs.setContext(applicationContext)
            android.util.Log.d("OrbotApp", "onCreate() - Prefs context set")
            
            LocaleHelper.onAttach(applicationContext)
            android.util.Log.d("OrbotApp", "onCreate() - LocaleHelper attached")
            
            Languages.setup(OrbotActivity::class.java, R.string.menu_settings)
            android.util.Log.d("OrbotApp", "onCreate() - Languages setup completed")

            if (Prefs.defaultLocale != Locale.getDefault().language) {
                android.util.Log.d("OrbotApp", "onCreate() - Setting default locale")
                Languages.setLanguage(this, Prefs.defaultLocale, true)
                android.util.Log.d("OrbotApp", "onCreate() - Default locale set")
            }

            // Meshrabiya integration
            try {
                android.util.Log.d("OrbotApp", "onCreate() - Starting Meshrabiya integration")
                
                android.util.Log.d("OrbotApp", "onCreate() - Creating mesh logger")
                meshLogger = MNetLoggerStdout() // Use concrete implementation
                android.util.Log.d("OrbotApp", "onCreate() - Mesh logger created")
                
                android.util.Log.d("OrbotApp", "onCreate() - Creating mesh JSON")
                meshJson = Json { encodeDefaults = true }
                android.util.Log.d("OrbotApp", "onCreate() - Mesh JSON created")
                
                android.util.Log.d("OrbotApp", "onCreate() - Creating mesh DataStore")
                // Create DataStore for mesh preferences  
                val meshDataStore = applicationContext.meshDataStore
                android.util.Log.d("OrbotApp", "onCreate() - Mesh DataStore created")
                
                android.util.Log.d("OrbotApp", "onCreate() - Creating mesh executor")
                // Create executor service for mesh operations
                val meshExecutor = Executors.newScheduledThreadPool(2)
                android.util.Log.d("OrbotApp", "onCreate() - Mesh executor created")
                
                android.util.Log.d("OrbotApp", "onCreate() - Creating AndroidVirtualNode")
                virtualNode = AndroidVirtualNode(
                    context = applicationContext,
                    logger = meshLogger,
                    json = meshJson,
                    dataStore = meshDataStore,
                    scheduledExecutorService = meshExecutor
                )
                android.util.Log.d("OrbotApp", "onCreate() - AndroidVirtualNode created successfully")
            } catch (e: Exception) {
                android.util.Log.e("OrbotApp", "onCreate() - Exception in Meshrabiya integration", e)
                // Don't let mesh setup failure crash the app
            }

            // this code only runs on first install and app updates
            android.util.Log.d("OrbotApp", "onCreate() - Checking version for updates")
            if (Prefs.currentVersionForUpdate < BuildConfig.VERSION_CODE) {
                android.util.Log.d("OrbotApp", "onCreate() - Version update detected, setting flags")
                Prefs.currentVersionForUpdate = BuildConfig.VERSION_CODE
                // don't do anything resource intensive here, instead set a flag to do the task later
                // tell OrbotService it needs to reinstall geoip
                Prefs.isGeoIpReinstallNeeded = true
                android.util.Log.d("OrbotApp", "onCreate() - Version update flags set")
            }
            
        } catch (e: Exception) {
            android.util.Log.e("OrbotApp", "onCreate() - Exception in onCreate", e)
        }
        
        android.util.Log.d("OrbotApp", "onCreate() - END")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Prefs.defaultLocale != Locale.getDefault().language) {
            Languages.setLanguage(this, Prefs.defaultLocale, true)
        }
    }

    fun setLocale() {
        val appLocale = Prefs.defaultLocale
        val systemLoc = Locale.getDefault().language

        if (appLocale != systemLoc) {
            Languages.setLanguage(this, appLocale, true)
        }
    }

    
}
