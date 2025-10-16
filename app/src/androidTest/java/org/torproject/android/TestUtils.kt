package org.torproject.android

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import tools.fastlane.screengrab.Screengrab
import androidx.test.uiautomator.UiDevice
import java.io.File
import android.os.Environment

/**
 * Polls until the given view matcher is present and displayed, or throws the last exception after timeout.
 */
fun waitForView(matcher: Matcher<View>, timeoutMs: Long = 5000L, pollIntervalMs: Long = 200L) {
    val start = System.currentTimeMillis()
    var lastEx: Exception? = null
    while (System.currentTimeMillis() - start < timeoutMs) {
        try {
            onView(matcher).check(matches(isDisplayed()))
            return
        } catch (e: Exception) {
            lastEx = e
            Thread.sleep(pollIntervalMs)
        }
    }
    // final attempt to show the real exception
    if (lastEx != null) throw lastEx
}

/**
 * Tries to run Screengrab.screenshot with simple retry logic to avoid transient flakiness.
 * Returns true if successful, false otherwise.
 */
fun safeScreengrab(name: String, attempts: Int = 3, delayMs: Long = 500L): Boolean {
    var lastEx: Exception? = null
    for (i in 1..attempts) {
        try {
            // Ensure main looper is idle
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Screengrab.screenshot(name)
            return true
        } catch (e: Exception) {
            lastEx = e
            Thread.sleep(delayMs)
        }
    }
    // If Screengrab repeatedly fails (e.g., null bitmap from UiAutomatorStrategy),
    // try a direct UiDevice-based screenshot as a best-effort fallback so tests can continue.
    try {
        val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val external = targetContext.getExternalFilesDir(null) ?: targetContext.filesDir
        val outFile = File(external, "fallback_screenshot_${name}.png")
        // attempt to take a screenshot via UiDevice
        if (uiDevice.takeScreenshot(outFile)) {
            return true
        }
    } catch (t: Throwable) {
        // swallow and fall through to return false
    }
    // last attempt failed
    if (lastEx != null) {
        // rethrow to let caller decide, but return false for convenience
        return false
    }
    return false
}
