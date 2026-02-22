package com.groqvoice.keyboard.ui.onboarding

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.groqvoice.keyboard.R
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
        securePrefs = SecurePrefs(this)
        val onboardingTheme = if (securePrefs.getBrandTheme() == SecurePrefs.THEME_PASTEL_PINK) {
            R.style.Theme_GroqVoiceKeyboard_Onboarding_Pastel
        } else {
            R.style.Theme_GroqVoiceKeyboard_Onboarding
        }
        setTheme(onboardingTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Skip onboarding if already completed
        if (securePrefs.isOnboardingComplete()) {
            // User already set up — route directly to settings.
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        applyDynamicBackground()

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
        val surfaceVariant = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorSurfaceVariant,
            getColor(R.color.surface_variant)
        )
        val onSurface = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurface,
            getColor(R.color.text_primary)
        )
        val primary = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorPrimary,
            getColor(R.color.accent_primary)
        )
        val inactive = MaterialColors.layer(surfaceVariant, onSurface, 0.14f)

        for (i in 0 until totalPages) {
            val isActive = i == currentPage
            val dot = android.view.View(this).apply {
                val width = if (isActive) dp(30) else dp(8)
                val height = dp(8)
                layoutParams = android.widget.LinearLayout.LayoutParams(width, height).also {
                    it.setMargins(dp(5), 0, dp(5), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = height / 2f
                    setColor(if (isActive) primary else inactive)
                }
                alpha = if (isActive) 1f else 0.85f
            }
            container.addView(dot)
        }
    }

    private fun applyDynamicBackground() {
        val surface = MaterialColors.getColor(
            binding.onboardingRoot,
            com.google.android.material.R.attr.colorSurface,
            getColor(R.color.surface)
        )
        val primary = MaterialColors.getColor(
            binding.onboardingRoot,
            com.google.android.material.R.attr.colorPrimary,
            getColor(R.color.accent_primary)
        )
        val secondary = MaterialColors.getColor(
            binding.onboardingRoot,
            com.google.android.material.R.attr.colorSecondary,
            getColor(R.color.accent_secondary)
        )
        val start = MaterialColors.layer(surface, primary, 0.15f)
        val middle = MaterialColors.layer(surface, secondary, 0.08f)
        val end = MaterialColors.layer(surface, primary, 0.04f)

        binding.onboardingRoot.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, middle, end)
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
