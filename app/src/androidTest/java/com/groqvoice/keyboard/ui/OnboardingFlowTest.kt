package com.groqvoice.keyboard.ui

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.ui.onboarding.WelcomeActivity
import com.groqvoice.keyboard.utils.SecurePrefs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the 3-step onboarding flow.
 *
 * TSD Section 8.2 — Onboarding Flow: Complete setup, verify keyboard appears in system list.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    @Before
    fun resetOnboardingState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurePrefs(context).apply {
            setOnboardingComplete(false)
            clearApiKey()
        }
    }

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

    @Test
    fun onboardingFlow_advancesToApiKeyAndKeyboardEnableSteps() {
        activityRule.scenario.onActivity { it.goToNextStep() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onView(withId(R.id.btn_validate_continue)).check(matches(isDisplayed()))

        activityRule.scenario.onActivity { it.goToNextStep() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        onView(withId(R.id.btn_open_keyboard_settings)).check(matches(isDisplayed()))
    }
}
