package com.groqvoice.keyboard.ui

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.ui.onboarding.WelcomeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the 3-step onboarding flow.
 *
 * TSD Section 8.2 — Onboarding Flow: Complete setup, verify keyboard appears in system list.
 * NOTE: These are skeleton tests. Full implementation belongs to Phase 5 (TSD 9.1).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(WelcomeActivity::class.java)

    @Test
    fun welcomeScreen_isDisplayed() {
        onView(withId(R.id.onboarding_pager)).check(matches(isDisplayed()))
    }

    @Test
    fun getStartedButton_isDisplayed() {
        onView(withId(R.id.btn_get_started)).check(matches(isDisplayed()))
    }
}
