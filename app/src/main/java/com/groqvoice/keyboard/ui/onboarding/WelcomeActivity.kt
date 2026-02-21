package com.groqvoice.keyboard.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.groqvoice.keyboard.databinding.ActivityWelcomeBinding
import com.groqvoice.keyboard.utils.SecurePrefs
import com.groqvoice.keyboard.ui.settings.SettingsActivity

/**
 * Entry-point activity for the 3-step onboarding flow.
 *
 * Hosts a [ViewPager2] with the following fragments:
 *  1. [WelcomeFragment]         — Step 1: Welcome & Permissions
 *  2. [ApiKeySetupFragment]     — Step 2: API Key Configuration
 *  3. [KeyboardEnableFragment]  — Step 3: Keyboard Activation
 *
 * Skips onboarding and goes directly to [SettingsActivity] if the user
 * has already completed setup.
 *
 * TSD Section 2.1.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var securePrefs: SecurePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        securePrefs = SecurePrefs(this)

        // Skip onboarding if already completed
        if (securePrefs.isOnboardingComplete()) {
            // User already set up — route directly to settings.
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
    }

    private fun setupViewPager() {
        val adapter = OnboardingPagerAdapter(this)
        binding.onboardingPager.adapter = adapter
        binding.onboardingPager.isUserInputEnabled = false // Controlled programmatically

        binding.onboardingPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDotIndicator(position, adapter.itemCount)
                }
            }
        )
        updateDotIndicator(0, adapter.itemCount)
    }

    /** Navigates to the next onboarding step. Called by child fragments. */
    fun goToNextStep() {
        val current = binding.onboardingPager.currentItem
        val total = binding.onboardingPager.adapter?.itemCount ?: 1
        if (current < total - 1) {
            binding.onboardingPager.currentItem = current + 1
        } else {
            completeOnboarding()
        }
    }

    private fun completeOnboarding() {
        securePrefs.setOnboardingComplete(true)
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun updateDotIndicator(currentPage: Int, totalPages: Int) {
        val container = binding.dotIndicatorContainer
        container.removeAllViews()

        for (i in 0 until totalPages) {
            val dot = android.view.View(this).apply {
                val size = resources.getDimensionPixelSize(
                    com.groqvoice.keyboard.R.dimen.key_corner_radius
                ) / 2
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).also {
                    it.setMargins(8, 0, 8, 0)
                }
                background = getDrawable(
                    if (i == currentPage) com.groqvoice.keyboard.R.drawable.dot_active
                    else com.groqvoice.keyboard.R.drawable.dot_inactive
                )
            }
            container.addView(dot)
        }
    }
}
