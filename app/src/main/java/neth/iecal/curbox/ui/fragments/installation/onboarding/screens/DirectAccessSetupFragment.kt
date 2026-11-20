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

    /**
     * True only while the current automatic-pairing attempt intends to open
     * Android Settings after Accessibility has acknowledged readiness.
     */
    private var openSettingsWhenAccessibilityReady =
        false

    /**
     * Prevents repeated state callbacks from opening Settings more than once
     * for the same automatic-pairing attempt.
     */
    private var settingsOpenedForCurrentAttempt =
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
                startAutomaticPairing(
                    openSettingsAfterReady =
                        true
                )
            }

        binding
            .pairButton
            .setOnClickListener {
                if (manualFallbackVisible) {
                    startManualPairing()
                } else {
                    startAutomaticPairing(
                        openSettingsAfterReady =
                            true
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
        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        orchestrator?.cancel()

        orchestrator =
            null

        _binding =
            null

        super.onDestroyView()
    }

    private fun startExistingConnectionRecovery() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

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
     * Starts automatic pairing but deliberately does not leave CurBox yet.
     *
     * Developer Options is opened only after the orchestrator reaches
     * WAITING_FOR_PAIRING_DIALOG. That state is published after the
     * Accessibility process has returned ARMED_ACK for the current session.
     */
    private fun startAutomaticPairing(
        openSettingsAfterReady: Boolean
    ) {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        manualFallbackVisible =
            false

        openSettingsWhenAccessibilityReady =
            openSettingsAfterReady

        settingsOpenedForCurrentAttempt =
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
                    openSettingsWhenAccessibilityReady =
                        false

                    showManualFallback(
                        error
                    )
                },
                onWirelessRequired = {
                    openSettingsWhenAccessibilityReady =
                        false

                    settingsOpenedForCurrentAttempt =
                        false

                    setOperationInProgress(
                        false
                    )

                    hidePairingCodeInput()

                    binding
                        .statusText
                        .text =
                        "Enable Wireless Debugging, then return to CurBox and start automatic pairing again."

                    updateWirelessDebuggingStatus()

                    /*
                     * Automatic capture has not been armed in this path because
                     * Wireless Debugging is still unavailable. Settings can be
                     * opened to let the user enable it, but a new automatic
                     * pairing attempt must be started afterwards.
                     */
                    if (openSettingsAfterReady) {
                        openDeveloperOptions()
                    }
                }
            )
        )
    }

    private fun startManualPairing() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

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

    private fun applyPersistentPrivilegesAndFinish() {
        val appContext =
            context
                ?.applicationContext
                ?: return

        openSettingsWhenAccessibilityReady =
            false

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

                    maybeOpenSettingsAfterAccessibilityReady(
                        state
                    )
                }
            }

            override fun onConnected() {
                postToUi {
                    openSettingsWhenAccessibilityReady =
                        false

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
                    openSettingsWhenAccessibilityReady =
                        false

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
                    openSettingsWhenAccessibilityReady =
                        false

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

    /**
     * WAITING_FOR_PAIRING_DIALOG is our UI-level readiness signal:
     *
     * the current session has received ARMED_ACK and automatic capture is
     * actually ready before Android Settings is opened.
     */
    private fun maybeOpenSettingsAfterAccessibilityReady(
        state: DirectAccessOrchestrator.State
    ) {
        if (
            state !=
            DirectAccessOrchestrator.State.WAITING_FOR_PAIRING_DIALOG
        ) {
            return
        }

        if (
            !openSettingsWhenAccessibilityReady ||
            settingsOpenedForCurrentAttempt
        ) {
            return
        }

        settingsOpenedForCurrentAttempt =
            true

        openDeveloperOptions()
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

                DirectAccessOrchestrator.State.WAITING_FOR_ACCESSIBILITY ->
                    "Preparing automatic pairing. Waiting for Accessibility to become ready."

                DirectAccessOrchestrator.State.WAITING_FOR_PAIRING_DIALOG ->
                    "Accessibility is ready. Opening Android's Wireless Debugging setup…"

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

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
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

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
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
            "Start automatic pairing. CurBox will prepare Accessibility first, then open Android Settings and capture the pairing code automatically."

        updateWirelessDebuggingStatus()
    }

    private fun showManualFallback(
        cause: Throwable?
    ) {
        manualFallbackVisible =
            true

        openSettingsWhenAccessibilityReady =
            false

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
