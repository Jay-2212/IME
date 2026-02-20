package com.groqvoice.keyboard.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.groqvoice.keyboard.R
import com.groqvoice.keyboard.api.GroqRepository
import com.groqvoice.keyboard.databinding.FragmentApiKeySetupBinding
import com.groqvoice.keyboard.utils.FileCacheManager
import com.groqvoice.keyboard.utils.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * Onboarding Step 2 — API Key Configuration.
 *
 * Validates the Groq API key locally (regex) and then via a live /models test call.
 * Stores the key in [EncryptedSharedPreferences] via [SecurePrefs].
 *
 * TSD Section 2.1 Step 2.
 */
class ApiKeySetupFragment : Fragment() {

    private var _binding: FragmentApiKeySetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var securePrefs: SecurePrefs
    private lateinit var groqRepository: GroqRepository

    companion object {
        /** Groq API key format: gsk_ followed by 40+ alphanumeric characters. TSD Section 2.1 */
        private val API_KEY_PATTERN = Pattern.compile("^gsk_[a-zA-Z0-9]{40,}$")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApiKeySetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        securePrefs = SecurePrefs(requireContext())
        groqRepository = GroqRepository(
            apiKeyProvider = { binding.etApiKey.text?.toString() },
            fileCacheManager = FileCacheManager(requireContext())
        )

        // Restore previously saved key (masked)
        securePrefs.getApiKey()?.let { binding.etApiKey.setText(it) }

        // Live format validation on each keystroke
        binding.etApiKey.doAfterTextChanged { editable ->
            val key = editable?.toString() ?: ""
            if (key.isNotEmpty() && !API_KEY_PATTERN.matcher(key).matches()) {
                binding.tilApiKey.error = getString(R.string.api_key_error_format)
            } else {
                binding.tilApiKey.error = null
            }
        }

        binding.tvHelpLink.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
            )
        }

        binding.btnValidateContinue.setOnClickListener { validateAndContinue() }
    }

    private fun validateAndContinue() {
        val key = binding.etApiKey.text?.toString() ?: return

        if (!API_KEY_PATTERN.matcher(key).matches()) {
            binding.tvValidationStatus.apply {
                text = getString(R.string.api_key_error_format)
                setTextColor(requireContext().getColor(R.color.error))
                visibility = View.VISIBLE
            }
            return
        }

        binding.tvValidationStatus.apply {
            text = getString(R.string.api_key_validating)
            setTextColor(requireContext().getColor(R.color.disabled))
            visibility = View.VISIBLE
        }
        binding.btnValidateContinue.isEnabled = false

        lifecycleScope.launch {
            val isValid = withContext(Dispatchers.IO) { groqRepository.validateApiKey() }

            if (isValid) {
                securePrefs.setApiKey(key)
                binding.tvValidationStatus.apply {
                    text = getString(R.string.api_key_valid)
                    setTextColor(requireContext().getColor(R.color.success))
                }
                (activity as? WelcomeActivity)?.goToNextStep()
            } else {
                binding.tvValidationStatus.apply {
                    text = getString(R.string.api_key_error_unauthorized)
                    setTextColor(requireContext().getColor(R.color.error))
                }
                binding.btnValidateContinue.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
