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
class ScreenshotKindnessModeFragment {

    @Test
    fun openKindnessModeFragment() {
        ActivityScenario.launch(OrbotActivity::class.java).use { scenario ->
            Thread.sleep(3000) // Wait for activity to fully initialize
            
            try {
                onView(withId(R.id.kindnessFragment)).perform(click())
                Thread.sleep(1000) // Wait for navigation
                Screengrab.screenshot("C-kindness_mode_screen")
            } catch (e: Exception) {
                // If specific UI elements don't exist, just take screenshot
                Screengrab.screenshot("C-kindness_mode_screen-fallback")
            }
        }
    }
}