package com.groqvoice.keyboard.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.databinding.ActivitySettingsBinding
import com.groqvoice.keyboard.utils.AuditLogger
import com.groqvoice.keyboard.utils.SecurePrefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var securePrefs: SecurePrefs
    private lateinit var auditLogger: AuditLogger
    private lateinit var modelValues: Array<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        securePrefs = SecurePrefs(this)
        setTheme(resolveSettingsTheme())
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auditLogger = AuditLogger(this)

        modelValues = resources.getStringArray(R.array.groq_model_values)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        applyInsets()
        bindCurrentValues()
        refreshAuditLog()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun bindCurrentValues() {
        binding.etApiKey.setText(securePrefs.getApiKey().orEmpty())
        binding.switchDoubleTap.isChecked = securePrefs.isDoubleTapPeriodEnabled()
        binding.switchHaptics.isChecked = securePrefs.isHapticFeedbackEnabled()

        val modelLabels = resources.getStringArray(R.array.groq_models)
        val modelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            modelLabels
        )
        binding.dropdownModel.setAdapter(modelAdapter)
        binding.dropdownModel.maxLines = 1
        binding.dropdownModel.isSingleLine = true

        val selectedModel = securePrefs.getModel()
        val currentIndex = modelValues.indexOf(selectedModel).coerceAtLeast(0)
        binding.dropdownModel.setText(modelLabels[currentIndex], false)

        val selectedTheme = securePrefs.getBrandTheme()
        val themeButtonId = if (selectedTheme == SecurePrefs.THEME_PASTEL_PINK) {
            R.id.btn_theme_pastel
        } else {
            R.id.btn_theme_obsidian
        }
        binding.themeToggleGroup.check(themeButtonId)

        val supportsSystemColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        binding.switchSystemColors.isEnabled = supportsSystemColors
        binding.switchSystemColors.isChecked =
            supportsSystemColors && securePrefs.isSystemColorsEnabled()
        binding.tvSystemColorsSummary.text = if (supportsSystemColors) {
            getString(R.string.pref_use_system_colors_summary)
        } else {
            getString(R.string.pref_use_system_colors_unavailable)
        }
    }

    private fun setupListeners() {
        binding.btnSaveApiKey.setOnClickListener {
            val value = binding.etApiKey.text?.toString()?.trim()
            securePrefs.setApiKey(value?.takeIf { it.isNotBlank() })
            Toast.makeText(
                this,
                getString(R.string.pref_api_key_saved),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.dropdownModel.setOnItemClickListener { _, _, position, _ ->
            if (position in modelValues.indices) {
                securePrefs.setModel(modelValues[position])
            }
        }
        binding.dropdownModel.setOnClickListener {
            binding.dropdownModel.showDropDown()
        }
        binding.dropdownModel.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.dropdownModel.showDropDown()
        }

        binding.themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selectedTheme = when (checkedId) {
                R.id.btn_theme_pastel -> SecurePrefs.THEME_PASTEL_PINK
                else -> SecurePrefs.THEME_OBSIDIAN_EMBER
            }
            if (selectedTheme != securePrefs.getBrandTheme()) {
                securePrefs.setBrandTheme(selectedTheme)
                Toast.makeText(
                    this,
                    getString(R.string.pref_theme_saved),
                    Toast.LENGTH_SHORT
                ).show()
                recreate()
            }
        }

        binding.switchDoubleTap.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.setDoubleTapPeriodEnabled(isChecked)
        }

        binding.switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.setHapticFeedbackEnabled(isChecked)
        }

        binding.switchSystemColors.setOnCheckedChangeListener { _, isChecked ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                binding.switchSystemColors.isChecked = false
                return@setOnCheckedChangeListener
            }
            securePrefs.setSystemColorsEnabled(isChecked)
            Toast.makeText(
                this,
                getString(R.string.pref_use_system_colors_restart_note),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnClearLog.setOnClickListener {
            auditLogger.clearLog()
            refreshAuditLog()
            Toast.makeText(
                this,
                getString(R.string.pref_clear_log_done),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnCopyLog.setOnClickListener {
            val logText = auditLogger.readLog()
            if (logText.isBlank()) {
                Toast.makeText(
                    this,
                    getString(R.string.pref_log_empty),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Sensum transcription log", logText))
            Toast.makeText(
                this,
                getString(R.string.pref_log_copied),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnRefreshLog.setOnClickListener {
            refreshAuditLog()
            Toast.makeText(
                this,
                getString(R.string.pref_log_refreshed),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnPrivacyPolicy.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url)))
            val opened = runCatching { startActivity(intent) }.isSuccess
            if (!opened) {
                Toast.makeText(
                    this,
                    getString(R.string.pref_open_link_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun refreshAuditLog() {
        val logText = auditLogger.readLog()
        binding.tvTranscriptionLog.text = if (logText.isBlank()) {
            getString(R.string.pref_log_empty)
        } else {
            logText
        }
    }

    private fun resolveSettingsTheme(): Int {
        return if (securePrefs.getBrandTheme() == SecurePrefs.THEME_PASTEL_PINK) {
            R.style.Theme_GroqVoiceKeyboard_Settings_Pastel
        } else {
            R.style.Theme_GroqVoiceKeyboard_Settings
        }
    }
}
