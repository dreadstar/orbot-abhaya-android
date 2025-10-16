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
            try {
                    waitForView(withId(R.id.rootLayout), timeoutMs = 7000)
                    onView(withId(R.id.moreFragment)).perform(click())
                    waitForView(withId(R.id.rvMoreActions))
                if (!safeScreengrab("D-more_screen")) {
                    Screengrab.screenshot("D-more_screen-fallback")
                }
            } catch (e: Exception) {
                Screengrab.screenshot("D-more_screen-fallback")
            }
        }
    }
}