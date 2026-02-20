package com.groqvoice.keyboard.ui.onboarding

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * [FragmentStateAdapter] for the 3-step onboarding [ViewPager2].
 *
 * Steps:
 *  0 → [WelcomeFragment]
 *  1 → [ApiKeySetupFragment]
 *  2 → [KeyboardEnableFragment]
 */
class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> WelcomeFragment()
        1 -> ApiKeySetupFragment()
        2 -> KeyboardEnableFragment()
        else -> throw IllegalArgumentException("Unknown onboarding position: $position")
    }
}
