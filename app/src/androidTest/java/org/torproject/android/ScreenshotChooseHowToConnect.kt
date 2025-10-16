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
            // Wait for the main content to be visible
            try {
                // wait for activity root to be ready
                waitForView(withId(R.id.rootLayout), timeoutMs = 7000)
                if (!safeScreengrab("B-choose-how")) {
                    Screengrab.screenshot("B-choose-how-fallback")
                }
            } catch (e: Exception) {
                Screengrab.screenshot("B-choose-how-fallback")
            }
        }
    }
}
