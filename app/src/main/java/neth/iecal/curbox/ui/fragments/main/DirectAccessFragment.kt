package neth.iecal.curbox.ui.fragments.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import neth.iecal.curbox.databinding.FragmentDirectAccessBinding
import neth.iecal.curbox.utils.privileged.PersistentPrivilegesManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessLifecycleManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessOrchestrator

class DirectAccessFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID =
            "direct_access_fragment"

        private const val PAIRING_CODE_LENGTH =
            6
    }

    private var _binding:
        FragmentDirectAccessBinding? = null

    private val binding
        get() = _binding!!

    private var orchestrator:
        DirectAccessOrchestrator? = null

    private var operationInProgress =
        false

    /**
     * True while the current automatic-pairing attempt intends to open
     * Android Settings after Accessibility acknowledges readiness.
     */
    private var openSettingsWhenAccessibilityReady =
        false

    /**
     * Prevents repeated state callbacks from launching Settings more than once
     * during the same automatic-pairing attempt.
     */
    private var settingsOpenedForCurrentAttempt =
        false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentDirectAccessBinding.inflate(
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
            .autoPairButton
            .setOnClickListener {
                startAutomaticPairing(
                    openSettingsAfterReady =
                        true
                )
            }

        binding
            .openWirelessDebuggingButton
            .setOnClickListener {
                startAutomaticPairing(
                    openSettingsAfterReady =
                        true
                )
            }

        binding
            .reconnectButton
            .setOnClickListener {
                reconnect()
            }

        binding
            .showManualPairing
            .setOnClickListener {
                showManualPairing()
            }

        binding
            .manualPairButton
            .setOnClickListener {
                startManualPairing()
            }

        binding
            .disconnectButton
            .setOnClickListener {
                disconnect()
            }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            renderStatus()
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

    /**
     * Starts automatic pairing without immediately leaving CurBox.
     *
     * Settings is opened only after WAITING_FOR_PAIRING_DIALOG is received,
     * which means the Accessibility process has acknowledged that its
     * extractor and navigator are armed for the current session.
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

        hideManualPairing()

        openSettingsWhenAccessibilityReady =
            openSettingsAfterReady

        settingsOpenedForCurrentAttempt =
            false

        setOperationInProgress(
            true
        )

        binding
            .operationStatus
            .text =
            "Preparing automatic pairing…"

        currentOrchestrator.startAutoPairing(
            createListener(
                onConnected =
                    ::applyPersistentPrivilegesAndFinish,
                onFailure = { error ->
                    openSettingsWhenAccessibilityReady =
                        false

                    showAutomaticPairingFailure(
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

                    binding
                        .operationStatus
                        .text =
                        "Wireless Debugging must be enabled. Enable it in Developer Options, then return to CurBox and start automatic pairing again."

                    renderStatus()

                    /*
                     * Automatic capture is not active on this path because
                     * Wireless Debugging could not be enabled yet. Settings is
                     * opened only so the user can enable it.
                     */
                    if (openSettingsAfterReady) {
                        openDeveloperOptions()
                    }
                }
            )
        )
    }

    private fun reconnect() {
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

        binding
            .operationStatus
            .text =
            "Restoring Direct Access…"

        currentOrchestrator.ensureConnected(
            createListener(
                onConnected = {
                    setOperationInProgress(
                        false
                    )

                    binding
                        .operationStatus
                        .text =
                        "Direct Access reconnected."

                    renderStatus()
                },
                onPairingRequired = {
                    setOperationInProgress(
                        false
                    )

                    binding
                        .operationStatus
                        .text =
                        "The stored Direct Access identity could not reconnect. Use automatic pairing to repair the connection."

                    renderStatus()
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
                .manualPairingCodeInput
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
                .manualPairingCodeLayout
                .error =
                "Enter the 6-digit pairing code."

            return
        }

        binding
            .manualPairingCodeLayout
            .error =
            null

        setOperationInProgress(
            true
        )

        binding
            .operationStatus
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
                        setOperationInProgress(
                            false
                        )

                        binding
                            .operationStatus
                            .text =
                            buildString {
                                append(
                                    "Manual pairing failed."
                                )

                                error
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

                        renderStatus()
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

        binding
            .operationStatus
            .text =
            "Direct ADB connected. Applying CurBox privileges…"

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
                                hideManualPairing()

                                settingsOpenedForCurrentAttempt =
                                    false

                                binding
                                    .operationStatus
                                    .text =
                                    "Direct Access is ready."
                            } else {
                                binding
                                    .operationStatus
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

                            renderStatus()
                        }
                    }
                }
        )
    }

    private fun disconnect() {
        if (operationInProgress) {
            return
        }

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        orchestrator
            ?.disconnect()

        binding
            .operationStatus
            .text =
            "Direct ADB disconnected. The stored pairing identity was preserved."

        renderStatus()
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

                    renderStatus()
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

                    if (onWirelessRequired != null) {
                        onWirelessRequired()
                    } else {
                        openSettingsWhenAccessibilityReady =
                            false

                        onFailure
                            ?.invoke(
                                cause
                            )
                            ?: showAutomaticPairingFailure(
                                cause
                            )
                    }

                    renderStatus()
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

                    if (onPairingRequired != null) {
                        onPairingRequired()
                    } else {
                        onFailure
                            ?.invoke(
                                cause
                            )
                            ?: showAutomaticPairingFailure(
                                cause
                            )
                    }

                    renderStatus()
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

                    onFailure
                        ?.invoke(
                            error
                        )
                        ?: showAutomaticPairingFailure(
                            error
                        )

                    renderStatus()
                }
            }
        }
    }

    /**
     * WAITING_FOR_PAIRING_DIALOG is emitted only after ARMED_ACK for the
     * current automatic-pairing session.
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
            .operationStatus
            .text =
            when (state) {
                DirectAccessOrchestrator.State.IDLE ->
                    "No Direct Access operation is running."

                DirectAccessOrchestrator.State.INITIALIZING ->
                    "Preparing Direct Access…"

                DirectAccessOrchestrator.State.ENABLING_WIRELESS_DEBUGGING ->
                    "Enabling Wireless Debugging…"

                DirectAccessOrchestrator.State.WIRELESS_DEBUGGING_REQUIRED ->
                    "Wireless Debugging requires your attention."

                DirectAccessOrchestrator.State.DISCOVERING ->
                    "Looking for the Wireless ADB connection…"

                DirectAccessOrchestrator.State.PAIRING_REQUIRED ->
                    "The stored identity needs pairing again."

                DirectAccessOrchestrator.State.WAITING_FOR_ACCESSIBILITY ->
                    "Preparing automatic pairing. Waiting for Accessibility to become ready."

                DirectAccessOrchestrator.State.WAITING_FOR_PAIRING_DIALOG ->
                    "Accessibility is ready. Opening Android's Wireless Debugging setup…"

                DirectAccessOrchestrator.State.DISCOVERING_PAIRING ->
                    "Pairing information captured. Waiting for the pairing service…"

                DirectAccessOrchestrator.State.PAIRING ->
                    "Pairing CurBox automatically…"

                DirectAccessOrchestrator.State.DISCOVERING_CONNECTION ->
                    "Pairing succeeded. Looking for the ADB connection…"

                DirectAccessOrchestrator.State.CONNECTING ->
                    "Connecting to Direct ADB…"

                DirectAccessOrchestrator.State.CONNECTED ->
                    "Direct ADB connected."

                DirectAccessOrchestrator.State.ERROR ->
                    "Direct Access operation failed."
            }
    }

    private fun showAutomaticPairingFailure(
        cause: Throwable?
    ) {
        openSettingsWhenAccessibilityReady =
            false

        showManualPairing()

        binding
            .operationStatus
            .text =
            buildString {
                append(
                    "Automatic pairing could not complete. You can retry automatic setup or use manual pairing."
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

    private fun showManualPairing() {
        binding
            .manualPairingCodeLayout
            .visibility =
            View.VISIBLE

        binding
            .manualPairButton
            .visibility =
            View.VISIBLE

        binding
            .showManualPairing
            .visibility =
            View.GONE
    }

    private fun hideManualPairing() {
        binding
            .manualPairingCodeLayout
            .visibility =
            View.GONE

        binding
            .manualPairingCodeLayout
            .error =
            null

        binding
            .manualPairButton
            .visibility =
            View.GONE

        binding
            .showManualPairing
            .visibility =
            View.VISIBLE
    }

    private fun renderStatus() {
        val currentOrchestrator =
            orchestrator
                ?: return

        val appContext =
            context
                ?.applicationContext
                ?: return

        val connected =
            currentOrchestrator.isConnected()

        val wirelessEnabled =
            currentOrchestrator
                .isWirelessDebuggingEnabled()

        val recoveryEnabled =
            DirectAccessLifecycleManager
                .isRecoveryEnabled(
                    appContext
                )

        binding
            .directAccessStatus
            .text =
            when {
                connected ->
                    "Connected"

                recoveryEnabled ->
                    "Configured, currently disconnected"

                else ->
                    "Not configured"
            }

        binding
            .wirelessDebuggingStatus
            .text =
            if (wirelessEnabled) {
                "Wireless Debugging: On"
            } else {
                "Wireless Debugging: Off"
            }

        binding
            .recoveryStatus
            .text =
            if (recoveryEnabled) {
                "Automatic recovery: Enabled"
            } else {
                "Automatic recovery: Not configured"
            }

        binding
            .reconnectButton
            .isEnabled =
            recoveryEnabled &&
                !connected &&
                !operationInProgress

        binding
            .disconnectButton
            .isEnabled =
            connected &&
                !operationInProgress

        binding
            .autoPairButton
            .isEnabled =
            !operationInProgress
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
            .autoPairButton
            .isEnabled =
            !inProgress

        binding
            .openWirelessDebuggingButton
            .isEnabled =
            !inProgress

        binding
            .manualPairingCodeInput
            .isEnabled =
            !inProgress

        binding
            .manualPairButton
            .isEnabled =
            !inProgress

        binding
            .showManualPairing
            .isEnabled =
            !inProgress

        renderStatus()
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
}

