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
            try {
                waitForView(withId(R.id.rootLayout), timeoutMs = 7000)
                onView(withId(R.id.kindnessFragment)).perform(click())
                // wait until target fragment content is visible (panel id in fragment_kindness.xml)
                waitForView(withId(R.id.panel_kindness_activate))
                if (!safeScreengrab("C-kindness_mode_screen")) {
                    Screengrab.screenshot("C-kindness_mode_screen-fallback")
                }
            } catch (e: Exception) {
                Screengrab.screenshot("C-kindness_mode_screen-fallback")
            }
        }
    }
}