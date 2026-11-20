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
import neth.iecal.curbox.utils.privileged.adb.DirectAccessLifecycleManager
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

    private var manualFallbackVisible =
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

        val appContext =
            requireContext()
                .applicationContext

        orchestrator =
            DirectAccessOrchestrator(
                appContext
            )

        binding
            .openWirelessDebuggingButton
            .setOnClickListener {
                /*
                 * Arm automatic pairing before leaving CurBox.
                 *
                 * The main process starts listening for the capture and for
                 * the mDNS pairing endpoint. The user can then open Android's
                 * pairing-code dialog without having to return to CurBox to
                 * type the code.
                 */
                startAutomaticPairing(
                    openSettingsAfterStart = true
                )
            }

        binding
            .pairButton
            .setOnClickListener {
                if (manualFallbackVisible) {
                    startManualPairing()
                } else {
                    startAutomaticPairing(
                        openSettingsAfterStart = true
                    )
                }
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

        when {
            orchestrator
                ?.isConnected() == true -> {
                showConnectedState()
            }

            DirectAccessLifecycleManager
                .isRecoveryEnabled(
                    appContext
                ) -> {
                startExistingConnectionRecovery()
            }

            else -> {
                showAutomaticPairingState()
            }
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
        orchestrator =
            null

        _binding =
            null

        super.onDestroyView()
    }

    /**
     * Attempts normal reconnect using an identity that completed pairing
     * previously.
     */
    private fun startExistingConnectionRecovery() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        setOperationInProgress(
            true
        )

        hidePairingCodeInput()

        binding
            .statusText
            .text =
            "Restoring Direct Access…"

        currentOrchestrator.ensureConnected(
            createListener(
                onConnected =
                    ::applyPersistentPrivilegesAndFinish,
                onPairingRequired = {
                    showAutomaticPairingState()

                    binding
                        .statusText
                        .text =
                        "The existing Direct Access connection could not be restored. Start automatic pairing to repair it."
                }
            )
        )
    }

    /**
     * Preferred pairing path.
     *
     * CurBox listens for two independent pieces of information:
     *
     * - the pairing code captured from Android Settings by Accessibility;
     * - the pairing endpoint advertised through mDNS.
     *
     * The user does not need to copy or type the six-digit code.
     */
    private fun startAutomaticPairing(
        openSettingsAfterStart: Boolean
    ) {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        manualFallbackVisible =
            false

        setOperationInProgress(
            true
        )

        hidePairingCodeInput()

        binding
            .pairButton
            .text =
            "Automatic pairing"

        binding
            .statusText
            .text =
            "Preparing automatic pairing…"

        currentOrchestrator.startAutoPairing(
            createListener(
                onConnected =
                    ::applyPersistentPrivilegesAndFinish,
                onFailure = { error ->
                    showManualFallback(
                        error
                    )
                },
                onWirelessRequired = {
                    setOperationInProgress(
                        false
                    )

                    hidePairingCodeInput()

                    binding
                        .statusText
                        .text =
                        "Enable Wireless Debugging, then choose “Pair device with pairing code”. CurBox will read the pairing code automatically."

                    updateWirelessDebuggingStatus()

                    if (openSettingsAfterStart) {
                        openDeveloperOptions()
                    }
                }
            )
        )

        /*
         * Once the coordinator has been armed, leave CurBox and let the user
         * open Android's Wireless Debugging pairing-code dialog.
         *
         * The Accessibility process can capture the dialog while CurBox's
         * main process remains ready to receive the result.
         */
        if (
            openSettingsAfterStart &&
            currentOrchestrator
                .isWirelessDebuggingEnabled()
        ) {
            openDeveloperOptions()
        }
    }

    /**
     * Manual six-digit pairing remains available only as a fallback for a
     * Settings implementation whose pairing dialog cannot be interpreted
     * through Accessibility.
     */
    private fun startManualPairing() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

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
            .error =
            null

        setOperationInProgress(
            true
        )

        binding
            .statusText
            .text =
            "Pairing manually…"

        currentOrchestrator.pairAndConnect(
            pairingCode =
                pairingCode,
            listener =
                createListener(
                    onConnected =
                        ::applyPersistentPrivilegesAndFinish,
                    onFailure = { error ->
                        showManualFallback(
                            error
                        )
                    }
                )
        )
    }

    /**
     * A verified ADB connection exists at this point.
     *
     * Apply the privileges CurBox wants to persist beyond the ADB command
     * session before enabling Continue.
     */
    private fun applyPersistentPrivilegesAndFinish() {
        val appContext =
            context
                ?.applicationContext
                ?: return

        PersistentPrivilegesManager.apply(
            context =
                appContext,
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

                            if (result.success) {
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

    /**
     * Shared adapter around DirectAccessOrchestrator.Listener.
     */
    private fun createListener(
        onConnected: () -> Unit,
        onFailure: ((Throwable?) -> Unit)? = null,
        onWirelessRequired: (() -> Unit)? = null,
        onPairingRequired: (() -> Unit)? = null
    ): DirectAccessOrchestrator.Listener {

        return object :
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
                postToUi {
                    onConnected()
                }
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

                    if (onWirelessRequired != null) {
                        onWirelessRequired()
                    } else {
                        showManualFallback(
                            cause
                        )
                    }
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

                    if (onPairingRequired != null) {
                        onPairingRequired()
                    } else {
                        onFailure
                            ?.invoke(
                                cause
                            )
                            ?: showManualFallback(
                                cause
                            )
                    }
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

                    onFailure
                        ?.invoke(
                            error
                        )
                        ?: showManualFallback(
                            error
                        )
                }
            }
        }
    }

    private fun showState(
        state: DirectAccessOrchestrator.State
    ) {
        binding
            .statusText
            .text =
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

                DirectAccessOrchestrator.State.WAITING_FOR_PAIRING_DIALOG ->
                    "Waiting for Android's pairing-code window. CurBox will capture the code automatically."

                DirectAccessOrchestrator.State.DISCOVERING_PAIRING ->
                    "Pairing code captured. Waiting for the pairing service…"

                DirectAccessOrchestrator.State.PAIRING ->
                    "Pairing CurBox automatically…"

                DirectAccessOrchestrator.State.DISCOVERING_CONNECTION ->
                    "Pairing succeeded. Looking for the ADB connection…"

                DirectAccessOrchestrator.State.CONNECTING ->
                    "Connecting to Direct ADB…"

                DirectAccessOrchestrator.State.CONNECTED ->
                    "Direct ADB connected."

                DirectAccessOrchestrator.State.ERROR ->
                    "Automatic Direct Access setup failed."
            }
    }

    private fun showConnectedState() {
        directAccessConnected =
            true

        manualFallbackVisible =
            false

        binding
            .pairingCodeLayout
            .error =
            null

        hidePairingCodeInput()

        binding
            .openWirelessDebuggingButton
            .visibility =
            View.GONE

        binding
            .pairButton
            .visibility =
            View.GONE

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

    private fun showAutomaticPairingState() {
        directAccessConnected =
            false

        manualFallbackVisible =
            false

        setOperationInProgress(
            false
        )

        hidePairingCodeInput()

        binding
            .openWirelessDebuggingButton
            .visibility =
            View.VISIBLE

        binding
            .pairButton
            .visibility =
            View.VISIBLE

        binding
            .pairButton
            .text =
            "Start automatic pairing"

        binding
            .continueButton
            .isEnabled =
            false

        binding
            .statusText
            .text =
            "Start automatic pairing, then open Android's “Pair device with pairing code” window. CurBox will capture the code automatically."

        updateWirelessDebuggingStatus()
    }

    private fun showManualFallback(
        cause: Throwable?
    ) {
        manualFallbackVisible =
            true

        setOperationInProgress(
            false
        )

        binding
            .pairingCodeLayout
            .visibility =
            View.VISIBLE

        binding
            .pairButton
            .visibility =
            View.VISIBLE

        binding
            .pairButton
            .text =
            "Pair manually"

        binding
            .openWirelessDebuggingButton
            .visibility =
            View.VISIBLE

        binding
            .continueButton
            .isEnabled =
            false

        binding
            .statusText
            .text =
            buildString {
                append(
                    "Automatic pairing could not complete. Manual pairing is available as a fallback."
                )

                cause
                    ?.message
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

    private fun hidePairingCodeInput() {
        binding
            .pairingCodeLayout
            .visibility =
            View.GONE

        binding
            .pairingCodeLayout
            .error =
            null
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
        val enabled =
            orchestrator
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
        val currentActivity =
            activity
                ?: return

        currentActivity.runOnUiThread {
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
