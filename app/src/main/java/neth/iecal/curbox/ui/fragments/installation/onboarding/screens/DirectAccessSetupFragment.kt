package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import neth.iecal.curbox.databinding.FragmentDirectAccessSetupBinding
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.utils.privileged.PersistentPrivilegesManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessOrchestrator

class DirectAccessSetupFragment : Fragment() {

    private var _binding:
        FragmentDirectAccessSetupBinding? = null

    private val binding
        get() = _binding!!

    private var orchestrator:
        DirectAccessOrchestrator? = null

    private var operationInProgress =
        false

    private var directAccessConnected =
        false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentDirectAccessSetupBinding.inflate(
                inflater,
                container,
                false
            )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        orchestrator =
            DirectAccessOrchestrator(
                requireContext()
                    .applicationContext
            )

        binding
            .openWirelessDebuggingButton
            .setOnClickListener {
                openDeveloperOptions()
            }

        binding
            .pairButton
            .setOnClickListener {
                startPairing()
            }

        binding
            .continueButton
            .setOnClickListener {
                if (!directAccessConnected) {
                    return@setOnClickListener
                }

                val parent =
                    parentFragment
                        as? OnboardingFragment

                parent?.goToNextPage()
            }

        updateWirelessDebuggingStatus()

        /*
         * If Direct ADB is already connected, do not force the user through
         * pairing again.
         */
        if (
            orchestrator
                ?.isConnected() == true
        ) {
            showConnectedState()
        }
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            updateWirelessDebuggingStatus()
        }
    }

    override fun onDestroyView() {
        orchestrator?.cancel()
        orchestrator = null

        _binding = null

        super.onDestroyView()
    }

    private fun startPairing() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator ?: return

        val pairingCode =
            binding
                .pairingCodeInput
                .text
                ?.toString()
                ?.trim()
                .orEmpty()

        if (
            pairingCode.length !=
            PAIRING_CODE_LENGTH ||
            pairingCode.any {
                !it.isDigit()
            }
        ) {
            binding
                .pairingCodeLayout
                .error =
                "Enter the 6-digit pairing code."

            return
        }

        binding
            .pairingCodeLayout
            .error = null

        setOperationInProgress(
            true
        )

        binding.statusText.text =
            "Preparing Direct Access…"

        currentOrchestrator.pairAndConnect(
            pairingCode =
                pairingCode,
            listener =
                object :
                    DirectAccessOrchestrator.Listener {

                    override fun onStateChanged(
                        state:
                            DirectAccessOrchestrator.State
                    ) {
                        postToUi {
                            showState(
                                state
                            )
                        }
                    }

                    override fun onConnected() {
                        /*
                         * DirectAccessOrchestrator reaches this callback only
                         * after pairing and the verified ADB connection have
                         * succeeded.
                         *
                         * Apply CurBox's persistent privileged grants while
                         * DirectAdbBackend is now available.
                         */
                        PersistentPrivilegesManager.apply(
                            context =
                                requireContext()
                                    .applicationContext,
                            listener =
                                object :
                                    PersistentPrivilegesManager.Listener {

                                    override fun onComplete(
                                        result:
                                            PersistentPrivilegesManager.Result
                                    ) {
                                        postToUi {
                                            setOperationInProgress(
                                                false
                                            )

                                            if (
                                                result.success
                                            ) {
                                                showConnectedState()
                                            } else {
                                                directAccessConnected =
                                                    false

                                                binding
                                                    .continueButton
                                                    .isEnabled =
                                                    false

                                                binding
                                                    .statusText
                                                    .text =
                                                    buildString {
                                                        append(
                                                            "Direct ADB connected, but CurBox could not apply persistent privileges."
                                                        )

                                                        result.error
                                                            ?.takeIf {
                                                                it.isNotBlank()
                                                            }
                                                            ?.let {
                                                                append(
                                                                    "\n"
                                                                )

                                                                append(
                                                                    it
                                                                )
                                                            }
                                                    }
                                            }
                                        }
                                    }
                                }
                        )
                    }

                    override fun onWirelessDebuggingRequired(
                        cause: Throwable?
                    ) {
                        postToUi {
                            setOperationInProgress(
                                false
                            )

                            directAccessConnected =
                                false

                            binding
                                .continueButton
                                .isEnabled =
                                false

                            binding
                                .statusText
                                .text =
                                "Wireless Debugging must be enabled in Developer Options."

                            updateWirelessDebuggingStatus()
                        }
                    }

                    override fun onPairingRequired(
                        cause: Throwable?
                    ) {
                        postToUi {
                            setOperationInProgress(
                                false
                            )

                            directAccessConnected =
                                false

                            binding
                                .continueButton
                                .isEnabled =
                                false

                            binding
                                .statusText
                                .text =
                                "Pairing is required. Open Wireless Debugging, choose pairing by code, then try again."
                        }
                    }

                    override fun onError(
                        error: Throwable
                    ) {
                        postToUi {
                            setOperationInProgress(
                                false
                            )

                            directAccessConnected =
                                false

                            binding
                                .continueButton
                                .isEnabled =
                                false

                            binding
                                .statusText
                                .text =
                                buildString {
                                    append(
                                        "Direct Access setup failed."
                                    )

                                    error.message
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let {
                                            append(
                                                "\n"
                                            )

                                            append(
                                                it
                                            )
                                        }
                                }
                        }
                    }
                }
        )
    }

    private fun showState(
        state: DirectAccessOrchestrator.State
    ) {
        binding.statusText.text =
            when (state) {
                DirectAccessOrchestrator.State.IDLE ->
                    "Direct Access is not connected yet."

                DirectAccessOrchestrator.State.INITIALIZING ->
                    "Preparing Direct Access…"

                DirectAccessOrchestrator.State.ENABLING_WIRELESS_DEBUGGING ->
                    "Enabling Wireless Debugging…"

                DirectAccessOrchestrator.State.WIRELESS_DEBUGGING_REQUIRED ->
                    "Wireless Debugging requires your attention."

                DirectAccessOrchestrator.State.DISCOVERING ->
                    "Looking for the Wireless ADB connection…"

                DirectAccessOrchestrator.State.PAIRING_REQUIRED ->
                    "Pairing is required."

                DirectAccessOrchestrator.State.DISCOVERING_PAIRING ->
                    "Looking for the pairing service…"

                DirectAccessOrchestrator.State.PAIRING ->
                    "Pairing CurBox with this device…"

                DirectAccessOrchestrator.State.DISCOVERING_CONNECTION ->
                    "Pairing succeeded. Looking for the ADB connection…"

                DirectAccessOrchestrator.State.CONNECTING ->
                    "Connecting to Direct ADB…"

                DirectAccessOrchestrator.State.CONNECTED ->
                    "Direct ADB connected. Applying CurBox privileges…"

                DirectAccessOrchestrator.State.ERROR ->
                    "Direct Access setup failed."
            }
    }

    private fun showConnectedState() {
        directAccessConnected =
            true

        binding
            .pairingCodeLayout
            .error = null

        binding
            .statusText
            .text =
            "Direct Access is ready."

        binding
            .continueButton
            .isEnabled =
            true

        updateWirelessDebuggingStatus()
    }

    private fun setOperationInProgress(
        inProgress: Boolean
    ) {
        operationInProgress =
            inProgress

        binding
            .progressIndicator
            .visibility =
            if (inProgress) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding
            .pairButton
            .isEnabled =
            !inProgress

        binding
            .pairingCodeInput
            .isEnabled =
            !inProgress

        binding
            .openWirelessDebuggingButton
            .isEnabled =
            !inProgress
    }

    private fun updateWirelessDebuggingStatus() {
        val currentOrchestrator =
            orchestrator

        val enabled =
            currentOrchestrator
                ?.isWirelessDebuggingEnabled() ==
                true

        binding
            .wirelessDebuggingStatus
            .text =
            if (enabled) {
                "Wireless Debugging is enabled."
            } else {
                "Wireless Debugging is disabled."
            }
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
            )
        } catch (
            _: ActivityNotFoundException
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_SETTINGS
                )
            )
        }
    }

    private fun postToUi(
        action: () -> Unit
    ) {
        val activity =
            activity ?: return

        activity.runOnUiThread {
            if (_binding == null) {
                return@runOnUiThread
            }

            action()
        }
    }

    companion object {
        private const val PAIRING_CODE_LENGTH =
            6
    }
}
