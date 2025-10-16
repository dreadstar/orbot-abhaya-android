package org.torproject.android

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.torproject.android.service.util.Prefs
import tools.fastlane.screengrab.locale.LocaleTestRule
import tools.fastlane.screengrab.Screengrab
import android.util.Log


@LargeTest
@RunWith(AndroidJUnit4::class)
abstract class BaseScreenshotTest {

    @Rule @JvmField
    val localeTestRule = LocaleTestRule()

    fun ViewInteraction.isGone() = getViewAssertion(ViewMatchers.Visibility.GONE)

    fun ViewInteraction.isVisible() = getViewAssertion(ViewMatchers.Visibility.VISIBLE)

    fun ViewInteraction.isInvisible() = getViewAssertion(ViewMatchers.Visibility.INVISIBLE)


    // Notification permissions only needed for Android 13+ (API 33+)
    // Skip permission grant to avoid errors on older devices
    // @get:Rule
    // var mGrantPermissionRule: GrantPermissionRule? =
    //     GrantPermissionRule.grant(
    //         "android.permission.POST_NOTIFICATIONS"
    //     )

    private fun getViewAssertion(visibility: ViewMatchers.Visibility): ViewAssertion? {
        return ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(visibility))
    }

    open fun childAtPosition(
        parentMatcher: Matcher<View>, position: Int
    ): Matcher<View> {

        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("Child at position $position in parent ")
                parentMatcher.describeTo(description)
            }

            public override fun matchesSafely(view: View): Boolean {
                val parent = view.parent
                return parent is ViewGroup && parentMatcher.matches(parent)
                        && view == parent.getChildAt(position)
            }
        }
    }

    @Before
    fun setPrefs(){
        Prefs.setContext(getContext())
        Prefs.isSecureWindow = false
    }

    @Before
    fun ensureScreengrabUsesUiAutomator() {
        // Try to explicitly configure Screengrab to use UiAutomatorScreenshotStrategy.
        // Use reflection so this is safe across library versions.
        try {
            val screengrabClass = Screengrab::class.java
            val strategyClass = Class.forName("tools.fastlane.screengrab.ScreenshotStrategy")
            val uiAutoClass = Class.forName("tools.fastlane.screengrab.UiAutomatorScreenshotStrategy")

            // look for a public static setter method: setScreenshotStrategy(ScreenshotStrategy)
            try {
                val setter = screengrabClass.getMethod("setScreenshotStrategy", strategyClass)
                val strategyInstance = uiAutoClass.getDeclaredConstructor().newInstance()
                setter.invoke(null, strategyInstance)
                Log.i("BaseScreenshotTest", "Screengrab: set UiAutomatorScreenshotStrategy via setter")
                return
            } catch (_: NoSuchMethodException) {
                // fallback: try to set a private/static field if present
            }

            // try to find a static field of type ScreenshotStrategy and replace it
            try {
                val fields = screengrabClass.declaredFields
                for (f in fields) {
                    if (f.type == strategyClass) {
                        f.isAccessible = true
                        val strategyInstance = uiAutoClass.getDeclaredConstructor().newInstance()
                        f.set(null, strategyInstance)
                        Log.i("BaseScreenshotTest", "Screengrab: set UiAutomatorScreenshotStrategy via field ${f.name}")
                        return
                    }
                }
            } catch (t: Throwable) {
                Log.w("BaseScreenshotTest", "Could not set Screengrab strategy via field: ${t.message}")
            }
        } catch (t: Throwable) {
            // If reflection fails, don't block tests — we'll fall back to whatever Screengrab picks.
            Log.w("BaseScreenshotTest", "Screengrab UiAutomator config unavailable: ${t.message}")
        }
    }

    open fun getContext(): Context? {
        return InstrumentationRegistry.getInstrumentation().targetContext
    }

}