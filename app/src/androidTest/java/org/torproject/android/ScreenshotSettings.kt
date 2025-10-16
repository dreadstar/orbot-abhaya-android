package org.torproject.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith
import org.torproject.android.ui.more.SettingsActivity
import tools.fastlane.screengrab.Screengrab
import androidx.test.espresso.matcher.ViewMatchers.withId

@RunWith(AndroidJUnit4::class)
@LargeTest
class ScreenshotSettings {

    @Test
    fun screenshotSettings() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            try {
                // root container id in settings_fragment.xml is 'settings_container'
                waitForView(withId(R.id.settings_container), timeoutMs = 7000)
                if (!safeScreengrab("E-settings_screen")) {
                    Screengrab.screenshot("E-settings_screen-fallback")
                }
            } catch (e: Exception) {
                Screengrab.screenshot("E-settings_screen-fallback")
            }
        }
    }
}