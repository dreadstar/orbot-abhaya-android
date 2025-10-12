package org.torproject.android

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab

@RunWith(AndroidJUnit4::class)
@LargeTest
class ScreenshotChooseHowToConnect {

    @Test
    fun screenshotChooseHowToConnect() {
        ActivityScenario.launch(OrbotActivity::class.java).use { scenario ->
            Thread.sleep(3000) // Wait for activity to fully initialize
            
            try {
                // Navigate to connect fragment first (it's the default)
                Thread.sleep(2000) // Wait for UI to settle
                Screengrab.screenshot("B-choose-how")
            } catch (e: Exception) {
                // If specific UI elements don't exist, just take screenshot
                Screengrab.screenshot("B-choose-how-fallback")
            }
        }
    }
}
