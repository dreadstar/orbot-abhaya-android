package org.torproject.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simple test to verify OrbotActivity can launch without issues
 * Bypasses screenshot framework to isolate activity launch problems
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SimpleOrbotActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(OrbotActivity::class.java)

    @Test
    fun testActivityCanLaunch() {
        // Just test that the activity can launch without crashing
        activityRule.scenario.onActivity { activity ->
            // Activity should be non-null if it launched successfully
            assert(activity != null)
        }
    }
}