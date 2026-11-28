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
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.PermissionUtils
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
     * Android accepted CurBox's persistent ADB identity, but the normal
     * Direct ADB connection failed afterwards.
     *
     * While true, the automatic-pairing button retries connection only.
     */
    private var retryConnectionVisible =
        false

    /**
     * True after automatic pairing encountered disabled Accessibility and
     * opened Android Accessibility Settings.
     *
     * When CurBox resumes and Accessibility is enabled, automatic pairing
     * continues without requiring another button press.
     */
    private var waitingForAccessibilityReturn =
        false

    /**
     * Developer Options is opened only after Accessibility acknowledges that
     * capture and navigation are armed for the current session.
     */
    private var openSettingsWhenAccessibilityReady =
        false

    /**
     * Prevents repeated state callbacks from launching Settings more than once
     * during one automatic-pairing attempt.
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

        neth.iecal.curbox.ui.utils.LargeHeader.install(
            view,
            getString(R.string.direct_access_tab)
        )

        orchestrator =
            DirectAccessOrchestrator(
                requireContext()
                    .applicationContext
            )

        binding
            .autoPairButton
            .setOnClickListener {
                if (retryConnectionVisible) {
                    retryPairedConnection()
                } else {
                    startAutomaticPairing(
                        openSettingsAfterReady =
                            true
                    )
                }
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

        if (_binding == null) {
            return
        }

        renderStatus()

        if (
            !operationInProgress &&
            neth.iecal.curbox.utils.privileged.adb.WirelessEnableRequest
                .consumeEnabled(requireContext())
        ) {
            startAutomaticPairing(
                openSettingsAfterReady =
                    true
            )
            return
        }

        if (
            waitingForAccessibilityReturn &&
            isCurBoxAccessibilityEnabled() &&
            !operationInProgress
        ) {
            waitingForAccessibilityReturn =
                false

            startAutomaticPairing(
                openSettingsAfterReady =
                    true
            )
        }
    }

    override fun onDestroyView() {
        waitingForAccessibilityReturn =
            false

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        retryConnectionVisible =
            false

        orchestrator?.cancel()

        orchestrator =
            null

        _binding =
            null

        super.onDestroyView()
    }

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

        retryConnectionVisible =
            false

        waitingForAccessibilityReturn =
            false

        openSettingsWhenAccessibilityReady =
            openSettingsAfterReady

        settingsOpenedForCurrentAttempt =
            false

        setOperationInProgress(
            true
        )

        binding
            .autoPairButton
            .text =
            "Automatic pairing"

        binding
            .operationStatus
            .text =
            "Preparing automatic pairing…"

        currentOrchestrator.startAutoPairing(
            createListener(
                onConnected =
                    ::applyPersistentPrivilegesAndFinish,

                onFailure = { error ->
                    handleAutomaticPairingFailure(
                        error
                    )
                },

                onWirelessRequired = {
                    openSettingsWhenAccessibilityReady =
                        false

                    settingsOpenedForCurrentAttempt =
                        false

                    retryConnectionVisible =
                        false

                    setOperationInProgress(
                        false
                    )

                    binding
                        .autoPairButton
                        .text =
                        "Start automatic pairing"

                    renderStatus()

                    if (isCurBoxAccessibilityEnabled()) {
                        // Switch it on for the user, then come back and resume.
                        binding
                            .operationStatus
                            .text =
                            "Turning on Wireless Debugging for you…"
                        neth.iecal.curbox.utils.privileged.adb.WirelessEnableRequest
                            .request(requireContext())
                        openDeveloperOptions()
                    } else {
                        binding
                            .operationStatus
                            .text =
                            "Wireless Debugging must be enabled. Enable it in Developer Options, then return to Horus and start automatic pairing again."
                        if (openSettingsAfterReady) {
                            openDeveloperOptions()
                        }
                    }
                },

                onPairedConnectionFailed = { error ->
                    showPairedConnectionFailure(
                        error
                    )
                }
            )
        )
    }

    /**
     * Retries the normal TLS connection without requesting another pairing
     * code. Android has already accepted CurBox's persistent ADB identity.
     */
    private fun retryPairedConnection() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        retryConnectionVisible =
            false

        waitingForAccessibilityReturn =
            false

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        hideManualPairing()

        setOperationInProgress(
            true
        )

        binding
            .autoPairButton
            .text =
            "Retry connection"

        binding
            .operationStatus
            .text =
            "Horus is already paired. Retrying the Direct ADB connection…"

        currentOrchestrator.ensureConnected(
            createListener(
                onConnected =
                    ::applyPersistentPrivilegesAndFinish,

                onPairingRequired = {
                    retryConnectionVisible =
                        false

                    setOperationInProgress(
                        false
                    )

                    binding
                        .autoPairButton
                        .text =
                        "Start automatic pairing"

                    binding
                        .operationStatus
                        .text =
                        "The saved Direct ADB pairing could not be restored. Start automatic pairing to repair it."

                    renderStatus()
                },

                onFailure = { error ->
                    showPairedConnectionFailure(
                        error
                    )
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

        retryConnectionVisible =
            false

        waitingForAccessibilityReturn =
            false

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
                onConnected =
                    ::applyPersistentPrivilegesAndFinish,

                onPairingRequired = {
                    setOperationInProgress(
                        false
                    )

                    binding
                        .autoPairButton
                        .text =
                        "Start automatic pairing"

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

        retryConnectionVisible =
            false

        waitingForAccessibilityReturn =
            false

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

        retryConnectionVisible =
            false

        waitingForAccessibilityReturn =
            false

        openSettingsWhenAccessibilityReady =
            false

        binding
            .operationStatus
            .text =
            "Direct ADB connected. Applying Horus privileges…"

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
                                    .autoPairButton
                                    .text =
                                    "Start automatic pairing"

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
                                            "Direct ADB connected, but Horus could not apply persistent privileges."
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

        retryConnectionVisible =
            false

        waitingForAccessibilityReturn =
            false

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        orchestrator
            ?.disconnect()

        binding
            .autoPairButton
            .text =
            "Start automatic pairing"

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
        onPairingRequired: (() -> Unit)? = null,
        onPairedConnectionFailed: ((Throwable) -> Unit)? = null
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

            override fun onPairedConnectionFailed(
                cause: Throwable
            ) {
                postToUi {
                    openSettingsWhenAccessibilityReady =
                        false

                    setOperationInProgress(
                        false
                    )

                    onPairedConnectionFailed
                        ?.invoke(
                            cause
                        )
                        ?: showPairedConnectionFailure(
                            cause
                        )

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

    private fun showPairedConnectionFailure(
        cause: Throwable?
    ) {
        retryConnectionVisible =
            true

        waitingForAccessibilityReturn =
            false

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        setOperationInProgress(
            false
        )

        hideManualPairing()

        binding
            .autoPairButton
            .text =
            "Retry connection"

        binding
            .operationStatus
            .text =
            buildString {
                append(
                    "Horus paired successfully, but the Direct ADB connection is not ready yet. Retry the connection without pairing again."
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

        renderStatus()
    }

    private fun handleAutomaticPairingFailure(
        error: Throwable?
    ) {
        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        retryConnectionVisible =
            false

        setOperationInProgress(
            false
        )

        if (
            isAccessibilityUnavailableError(
                error
            )
        ) {
            hideManualPairing()

            waitingForAccessibilityReturn =
                true

            binding
                .autoPairButton
                .text =
                "Start automatic pairing"

            binding
                .operationStatus
                .text =
                "Enable Horus Accessibility. When you return to Horus, automatic pairing will continue."

            openAccessibilitySettings()

            return
        }

        waitingForAccessibilityReturn =
            false

        showAutomaticPairingFailure(
            error
        )
    }

    private fun isAccessibilityUnavailableError(
        error: Throwable?
    ): Boolean {
        val message =
            error
                ?.message
                ?.lowercase()
                .orEmpty()

        return message.contains(
            "accessibility service must be enabled"
        ) ||
            message.contains(
                "accessibility service did not become ready"
            )
    }

    private fun isCurBoxAccessibilityEnabled(): Boolean {
        val currentContext =
            context
                ?: return false

        return PermissionUtils
            .isAccessibilityServiceEnabled(
                currentContext,
                AppBlockerService::class.java
            )
    }

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
                    "Pairing Horus automatically…"

                DirectAccessOrchestrator.State.PAIRED ->
                    "Horus is paired. Preparing the Direct ADB connection…"

                DirectAccessOrchestrator.State.DISCOVERING_CONNECTION ->
                    "Horus is paired. Looking for the Direct ADB connection…"

                DirectAccessOrchestrator.State.CONNECTING ->
                    "Horus is paired. Connecting to Direct ADB…"

                DirectAccessOrchestrator.State.CONNECTED ->
                    "Direct ADB connected."

                DirectAccessOrchestrator.State.ERROR ->
                    "Direct Access operation failed."
            }
    }

    private fun showAutomaticPairingFailure(
        cause: Throwable?
    ) {
        retryConnectionVisible =
            false

        openSettingsWhenAccessibilityReady =
            false

        binding
            .autoPairButton
            .text =
            "Start automatic pairing"

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

        // Re-pairing is only needed while not connected.
        binding.autoSetupCard.visibility =
            if (connected) android.view.View.GONE else android.view.View.VISIBLE

        binding
            .directAccessStatus
            .text =
            when {
                connected ->
                    "Connected"

                retryConnectionVisible ->
                    "Paired, connection required"

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

    private fun openAccessibilitySettings() {
        // Ask the service to bring Horus back once it is granted (step 2).
        neth.iecal.curbox.utils.privileged.adb.AccessibilityReturnRequest.request(requireContext())
        try {
            PermissionUtils.openAccessibilityServiceScreen(
                requireContext(),
                AppBlockerService::class.java
            )
            return
        } catch (_: Throwable) {
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
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

    private fun openDeveloperOptions() {
        neth.iecal.curbox.utils.privileged.adb.SettingsLauncher.openBest(requireContext())
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
