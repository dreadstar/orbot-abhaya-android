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
    // Meshrabiya core types
    lateinit var virtualNode: AndroidVirtualNode
    lateinit var meshLogger: MNetLogger
    lateinit var meshJson: Json

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                if (!isAuthenticationPromptOpenLegacyFlag)
                    shouldRequestAuthentication = true
            }
        })

        Prefs.setContext(applicationContext)
        LocaleHelper.onAttach(applicationContext)
        Languages.setup(OrbotActivity::class.java, R.string.menu_settings)

        if (Prefs.defaultLocale != Locale.getDefault().language) {
            Languages.setLanguage(this, Prefs.defaultLocale, true)
        }

        // Meshrabiya integration
        meshLogger = MNetLoggerStdout() // Use concrete implementation
        meshJson = Json { encodeDefaults = true }
        
        // Create DataStore for mesh preferences  
        val meshDataStore = applicationContext.meshDataStore
        
        // Create executor service for mesh operations
        val meshExecutor = Executors.newScheduledThreadPool(2)
        
        virtualNode = AndroidVirtualNode(
            context = applicationContext,
            logger = meshLogger,
            json = meshJson,
            dataStore = meshDataStore,
            scheduledExecutorService = meshExecutor
        )

        // this code only runs on first install and app updates
        if (Prefs.currentVersionForUpdate < BuildConfig.VERSION_CODE) {
            Prefs.currentVersionForUpdate = BuildConfig.VERSION_CODE
            // don't do anything resource intensive here, instead set a flag to do the task later
            // tell OrbotService it needs to reinstall geoip
            Prefs.isGeoIpReinstallNeeded = true
        }
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

    companion object {
        var shouldRequestAuthentication: Boolean = true
        // see https://github.com/guardianproject/orbot-android/issues/1340
        var isAuthenticationPromptOpenLegacyFlag: Boolean = false
        fun resetLockFlags() {
            shouldRequestAuthentication = true
            isAuthenticationPromptOpenLegacyFlag = false
        }
    }
}
