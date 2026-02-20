package com.groqvoice.keyboard.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.databinding.FragmentKeyboardEnableBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Onboarding Step 3 — Keyboard Activation.
 *
 * Guides the user to enable GroqVoice in Android's keyboard settings.
 * Polls [InputMethodManager.getEnabledInputMethodList] every second to detect activation
 * and updates the status label accordingly.
 *
 * TSD Section 2.1 Step 3.
 */
class KeyboardEnableFragment : Fragment() {

    private var _binding: FragmentKeyboardEnableBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeyboardEnableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnOpenKeyboardSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        // Poll keyboard enabled state while fragment is at least STARTED
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val enabled = isGroqVoiceEnabled()
                    updateEnabledStatus(enabled)
                    if (enabled) {
                        delay(1_000) // Give user time to see the success state
                        (activity as? WelcomeActivity)?.goToNextStep()
                        break
                    }
                    delay(1_000)
                }
            }
        }
    }

    private fun isGroqVoiceEnabled(): Boolean {
        val imm = requireContext().getSystemService<InputMethodManager>() ?: return false
        return imm.enabledInputMethodList.any {
            it.packageName == requireContext().packageName
        }
    }

    private fun updateEnabledStatus(enabled: Boolean) {
        if (enabled) {
            binding.tvEnabledStatus.text = getString(R.string.keyboard_enabled_status)
            binding.tvEnabledStatus.setTextColor(requireContext().getColor(R.color.success))
        } else {
            binding.tvEnabledStatus.text = getString(R.string.keyboard_not_enabled_status)
            binding.tvEnabledStatus.setTextColor(requireContext().getColor(R.color.disabled))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
