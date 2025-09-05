package com.ustadmobile.orbotmeshrabiyaintegration

import android.app.Application
import android.util.Log

/**
 * Application class for mesh integration. Initializes gateway manager and logging.
 */
class MeshOrbotIntegrationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("MeshOrbotIntegrationApp", "App started. Initializing mesh integration.")
        GatewayCapabilitiesManager.getInstance(this)
        // TODO: Additional initialization if needed
    }
}
