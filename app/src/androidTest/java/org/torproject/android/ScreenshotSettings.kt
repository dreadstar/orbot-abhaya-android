package org.torproject.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith
import org.torproject.android.ui.more.SettingsActivity
import tools.fastlane.screengrab.Screengrab

@RunWith(AndroidJUnit4::class)
@LargeTest
class ScreenshotSettings {

    @Test
    fun screenshotSettings() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            Thread.sleep(3000) // Wait for activity to fully initialize
            
            try {
                Thread.sleep(1000) // Wait for UI to settle
                Screengrab.screenshot("E-settings_screen")
            } catch (e: Exception) {
                // If specific UI elements don't exist, just take screenshot
                Screengrab.screenshot("E-settings_screen-fallback")
            }
        }
    }
}