package com.groqvoice.keyboard.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.color.MaterialColors
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.databinding.ActivitySettingsBinding
import com.groqvoice.keyboard.utils.AuditLogger
import com.groqvoice.keyboard.utils.SecurePrefs

/**
 * Settings screen for GroqVoice Keyboard.
 *
 * Hosts a [SettingsFragment] (PreferenceFragmentCompat) inside the activity's frame.
 * Provides a toolbar with up navigation back to [com.groqvoice.keyboard.ui.onboarding.WelcomeActivity].
 *
 * TSD Section 1.2 — SettingsActivity.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * PreferenceFragmentCompat that reads from [R.xml.preferences].
     *
     * NOTE: Sensitive values (API key) are bridged to/from [SecurePrefs] manually;
     * they are NOT stored directly in the standard SharedPreferences managed by
     * PreferenceFragmentCompat to maintain encryption.
     */
    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var securePrefs: SecurePrefs

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            securePrefs = SecurePrefs(requireContext())

            // Bridge API key preference ↔ SecurePrefs
            findPreference<androidx.preference.EditTextPreference>(SecurePrefs.KEY_API_KEY)
                ?.apply {
                    // Show masked value
                    summary = if (securePrefs.hasApiKey()) "●●●●●●●●●●●●" else
                        getString(R.string.pref_api_key_summary)

                    setOnPreferenceChangeListener { _, newValue ->
                        securePrefs.setApiKey(newValue as? String)
                        summary = if (securePrefs.hasApiKey()) "●●●●●●●●●●●●" else
                            getString(R.string.pref_api_key_summary)
                        true
                    }
                }

            // Bridge model preference ↔ SecurePrefs
            findPreference<androidx.preference.ListPreference>(SecurePrefs.KEY_SELECTED_MODEL)
                ?.apply {
                    value = securePrefs.getModel()
                    setOnPreferenceChangeListener { _, newValue ->
                        securePrefs.setModel(newValue as String)
                        true
                    }
                }

            // Bridge double-tap preference ↔ SecurePrefs
            findPreference<androidx.preference.SwitchPreferenceCompat>(SecurePrefs.KEY_DOUBLE_TAP_PERIOD)
                ?.apply {
                    isChecked = securePrefs.isDoubleTapPeriodEnabled()
                    setOnPreferenceChangeListener { _, newValue ->
                        securePrefs.setDoubleTapPeriodEnabled(newValue as Boolean)
                        true
                    }
                }

            // Bridge haptic preference ↔ SecurePrefs
            findPreference<androidx.preference.SwitchPreferenceCompat>(SecurePrefs.KEY_HAPTIC_FEEDBACK)
                ?.apply {
                    isChecked = securePrefs.isHapticFeedbackEnabled()
                    setOnPreferenceChangeListener { _, newValue ->
                        securePrefs.setHapticFeedbackEnabled(newValue as Boolean)
                        true
                    }
                }

            // Clear transcription log action
            findPreference<Preference>("clear_transcription_log")
                ?.setOnPreferenceClickListener {
                    AuditLogger(requireContext()).clearLog()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.pref_clear_log_done),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }

            // Privacy policy link
            findPreference<Preference>("privacy_policy")
                ?.setOnPreferenceClickListener {
                    val url = getString(R.string.privacy_policy_url)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    val opened = runCatching { startActivity(intent) }.isSuccess
                    if (!opened) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.pref_open_link_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setBackgroundColor(
                MaterialColors.getColor(
                    view,
                    com.google.android.material.R.attr.colorSurface
                )
            )
        }
    }
}
