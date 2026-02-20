package com.groqvoice.keyboard.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.groqvoice.keyboard.databinding.FragmentWelcomeBinding
import com.groqvoice.keyboard.utils.PermissionManager

/**
 * Onboarding Step 1 — Welcome & Permissions.
 *
 * Displays the app value proposition and requests RECORD_AUDIO + POST_NOTIFICATIONS
 * when the user taps "Get Started".
 *
 * TSD Section 2.1 Step 1.
 */
class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var permissionManager: PermissionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        permissionManager = PermissionManager(requireContext())

        binding.btnGetStarted.setOnClickListener {
            if (permissionManager.hasAudioPermission()) {
                // Permission already granted — advance to next step
                (activity as? WelcomeActivity)?.goToNextStep()
            } else {
                permissionManager.requestAllPermissions(requireActivity())
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionManager.REQUEST_CODE_ALL) {
            if (permissionManager.hasAudioPermission()) {
                (activity as? WelcomeActivity)?.goToNextStep()
            }
            // If denied, the user can tap "Get Started" again; no auto-redirect.
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
