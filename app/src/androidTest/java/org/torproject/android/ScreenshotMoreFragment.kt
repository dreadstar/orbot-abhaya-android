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
class ScreenshotMoreFragment {

    @Test
    fun openMoreFragment() {
        ActivityScenario.launch(OrbotActivity::class.java).use { scenario ->
            Thread.sleep(3000) // Wait for activity to fully initialize
            
            try {
                // Try to click on the More tab in bottom navigation
                onView(withId(R.id.moreFragment)).perform(click())
                Thread.sleep(2000) // Wait for navigation to complete
                Screengrab.screenshot("D-more_screen")
            } catch (e: Exception) {
                // If navigation fails, just take screenshot of current state
                Thread.sleep(1000)
                Screengrab.screenshot("D-more_screen-fallback")
            }
        }
    }
}