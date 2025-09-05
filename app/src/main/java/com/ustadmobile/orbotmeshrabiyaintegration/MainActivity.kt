package com.ustadmobile.orbotmeshrabiyaintegration

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ustadmobile.orbotmeshrabiyaintegration.GatewayCapabilitiesManager

/**
 * Main activity for mesh integration demo. Handles UI and gateway capability toggling.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var gatewayManager: GatewayCapabilitiesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gatewayManager = GatewayCapabilitiesManager.getInstance(this)
        // TODO: Setup UI listeners and bind to gatewayManager
    }
}
