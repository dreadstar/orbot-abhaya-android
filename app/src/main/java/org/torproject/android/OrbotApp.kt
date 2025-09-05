package org.torproject.android

import android.app.Application
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
import kotlinx.serialization.json.Json

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
        meshLogger = MNetLogger() // configure as needed
        meshJson = Json { encodeDefaults = true }
        virtualNode = AndroidVirtualNode(
            appContext = applicationContext,
            logger = meshLogger,
            json = meshJson
            // add other required parameters if needed
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
