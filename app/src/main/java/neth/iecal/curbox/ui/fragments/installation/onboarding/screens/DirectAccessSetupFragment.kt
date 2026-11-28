package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import neth.iecal.curbox.databinding.FragmentDirectAccessSetupBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.privileged.PersistentPrivilegesManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessLifecycleManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessOrchestrator

class DirectAccessSetupFragment : Fragment() {

    companion object {
        private const val PAIRING_CODE_LENGTH =
            6

        private const val SHIZUKU_PERMISSION_REQUEST_CODE =
            1001
    }

    private var _binding:
        FragmentDirectAccessSetupBinding? = null

    private val binding
        get() = _binding!!

    private var orchestrator:
        DirectAccessOrchestrator? = null

    private var operationInProgress =
        false

    /**
     * True after either Direct ADB or Shizuku has successfully applied the
     * persistent privileges required by CurBox.
     */
    private var privilegedAccessReady =
        false

    private var manualFallbackVisible =
        false

    /**
     * Android accepted CurBox's persistent ADB identity, but the normal
     * connection after pairing did not complete.
     */
    private var retryConnectionVisible =
        false

    /**
     * Developer Options is opened only after the Accessibility process
     * acknowledges that capture and navigation are armed.
     */
    private var openSettingsWhenAccessibilityReady =
        false

    private var settingsOpenedForCurrentAttempt =
        false

    /**
     * Set when CurBox sends the user to Accessibility Settings.
     *
     * When the user returns and AppBlockerService is enabled, automatic
     * pairing resumes without requiring another button press.
     */
    private var waitingForAccessibilityEnable =
        false

    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                requestCode,
                grantResult ->

            if (
                requestCode !=
                SHIZUKU_PERMISSION_REQUEST_CODE
            ) {
                return@OnRequestPermissionResultListener
            }

            postToUi {
                if (
                    grantResult ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    applyPersistentPrivilegesUsingShizuku()
                } else {
                    setOperationInProgress(
                        false
                    )

                    updateShizukuState()

                    binding
                        .statusText
                        .text =
                        "Shizuku permission was not granted. You can retry Shizuku or use Direct ADB."
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        try {
            rikka.shizuku.Shizuku
                .addRequestPermissionResultListener(
                    shizukuPermissionListener
                )
        } catch (_: Throwable) {
        }
    }

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
                waitingForAccessibilityEnable =
                    false

                startAutomaticPairing(
                    openSettingsAfterReady =
                        true
                )
            }

        binding
            .pairButton
            .setOnClickListener {
                waitingForAccessibilityEnable =
                    false

                when {
                    retryConnectionVisible -> {
                        retryPairedConnection()
                    }

                    manualFallbackVisible -> {
                        startManualPairing()
                    }

                    else -> {
                        startAutomaticPairing(
                            openSettingsAfterReady =
                                true
                        )
                    }
                }
            }

        binding
            .shizukuButton
            .setOnClickListener {
                waitingForAccessibilityEnable =
                    false

                startShizukuSetup()
            }

        binding
            .continueButton
            .setOnClickListener {
                if (!privilegedAccessReady) {
                    return@setOnClickListener
                }

                val parent =
                    parentFragment
                        as? OnboardingFragment

                parent?.goToNextPage()
            }

        updateWirelessDebuggingStatus()
        updateShizukuState()

        when {
            orchestrator
                ?.isConnected() == true -> {
                applyPersistentPrivilegesAfterDirectAdb()
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

        if (_binding == null) {
            return
        }

        updateWirelessDebuggingStatus()
        updateShizukuState()

        resumeAfterAccessibilityEnable()
    }

    override fun onDestroyView() {
        waitingForAccessibilityEnable =
            false

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

    override fun onDestroy() {
        try {
            rikka.shizuku.Shizuku
                .removeRequestPermissionResultListener(
                    shizukuPermissionListener
                )
        } catch (_: Throwable) {
        }

        super.onDestroy()
    }

    /**
     * Resumes Direct ADB setup after the user returns from Accessibility
     * Settings.
     */
    private fun resumeAfterAccessibilityEnable() {
        if (
            !waitingForAccessibilityEnable ||
            operationInProgress ||
            privilegedAccessReady
        ) {
            return
        }

        val currentContext =
            context
                ?: return

        val enabled =
            PermissionUtils
                .isAccessibilityServiceEnabled(
                    currentContext,
                    AppBlockerService::class.java
                )

        if (!enabled) {
            binding
                .statusText
                .text =
                "Enable Horus in Accessibility settings, then return here."

            return
        }

        waitingForAccessibilityEnable =
            false

        /*
         * Post the restart until the resumed view has finished its current
         * lifecycle callback.
         */
        binding.root.post {
            if (
                _binding == null ||
                operationInProgress ||
                privilegedAccessReady
            ) {
                return@post
            }

            startAutomaticPairing(
                openSettingsAfterReady =
                    true
            )
        }
    }

    private fun startExistingConnectionRecovery() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        waitingForAccessibilityEnable =
            false

        manualFallbackVisible =
            false

        retryConnectionVisible =
            false

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
                    ::applyPersistentPrivilegesAfterDirectAdb,

                onPairingRequired = {
                    showAutomaticPairingState()

                    binding
                        .statusText
                        .text =
                        "The stored Direct ADB identity could not be restored. Start automatic pairing to repair it."
                }
            )
        )
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

        manualFallbackVisible =
            false

        retryConnectionVisible =
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
                    ::applyPersistentPrivilegesAfterDirectAdb,

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

                    hidePairingCodeInput()

                    binding
                        .statusText
                        .text =
                        "Enable Wireless Debugging, then return to Horus and start automatic pairing again."

                    updateWirelessDebuggingStatus()

                    if (openSettingsAfterReady) {
                        openDeveloperOptions()
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
     * Retries the normal ADB connection without requesting another pairing
     * code.
     */
    private fun retryPairedConnection() {
        if (operationInProgress) {
            return
        }

        val currentOrchestrator =
            orchestrator
                ?: return

        waitingForAccessibilityEnable =
            false

        retryConnectionVisible =
            false

        manualFallbackVisible =
            false

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        hidePairingCodeInput()

        setOperationInProgress(
            true
        )

        binding
            .pairButton
            .text =
            "Retry connection"

        binding
            .statusText
            .text =
            "Horus is already paired. Retrying the Direct ADB connection…"

        currentOrchestrator.ensureConnected(
            createListener(
                onConnected =
                    ::applyPersistentPrivilegesAfterDirectAdb,

                onPairingRequired = {
                    showAutomaticPairingState()

                    binding
                        .statusText
                        .text =
                        "The saved Direct ADB pairing could not be restored. Start automatic pairing to repair it."
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

        waitingForAccessibilityEnable =
            false

        retryConnectionVisible =
            false

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
                        ::applyPersistentPrivilegesAfterDirectAdb,

                    onFailure = { error ->
                        showManualFallback(
                            error
                        )
                    }
                )
        )
    }

    private fun startShizukuSetup() {
        if (operationInProgress) {
            return
        }

        waitingForAccessibilityEnable =
            false

        if (
            !PermissionUtils
                .isShizukuAvailable()
        ) {
            updateShizukuState()

            binding
                .statusText
                .text =
                "Shizuku is not available. Start Shizuku first or use Direct ADB."

            return
        }

        manualFallbackVisible =
            false

        retryConnectionVisible =
            false

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        setOperationInProgress(
            true
        )

        binding
            .statusText
            .text =
            "Preparing Shizuku access…"

        if (
            PermissionUtils
                .hasShizukuPermission()
        ) {
            applyPersistentPrivilegesUsingShizuku()
            return
        }

        try {
            rikka.shizuku.Shizuku
                .requestPermission(
                    SHIZUKU_PERMISSION_REQUEST_CODE
                )
        } catch (error: Throwable) {
            setOperationInProgress(
                false
            )

            updateShizukuState()

            binding
                .statusText
                .text =
                buildErrorMessage(
                    prefix =
                        "Horus could not request Shizuku permission.",
                    cause =
                        error.message
                )
        }
    }

    private fun applyPersistentPrivilegesUsingShizuku() {
        val appContext =
            context
                ?.applicationContext

        if (appContext == null) {
            setOperationInProgress(
                false
            )

            return
        }

        binding
            .statusText
            .text =
            "Shizuku connected. Applying Horus privileges…"

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

                            updateShizukuState()

                            if (result.success) {
                                showPrivilegedAccessReady(
                                    message =
                                        "Shizuku access is ready."
                                )
                            } else {
                                privilegedAccessReady =
                                    false

                                binding
                                    .continueButton
                                    .isEnabled =
                                    false

                                binding
                                    .statusText
                                    .text =
                                    buildErrorMessage(
                                        prefix =
                                            "Shizuku is available, but Horus could not apply persistent privileges.",
                                        cause =
                                            result.error
                                    )
                            }
                        }
                    }
                }
        )
    }

    private fun applyPersistentPrivilegesAfterDirectAdb() {
        val appContext =
            context
                ?.applicationContext
                ?: return

        waitingForAccessibilityEnable =
            false

        openSettingsWhenAccessibilityReady =
            false

        retryConnectionVisible =
            false

        binding
            .statusText
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
                                showConnectedState()
                            } else {
                                privilegedAccessReady =
                                    false

                                binding
                                    .continueButton
                                    .isEnabled =
                                    false

                                binding
                                    .statusText
                                    .text =
                                    buildErrorMessage(
                                        prefix =
                                            "Direct ADB connected, but Horus could not apply persistent privileges.",
                                        cause =
                                            result.error
                                    )
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

                    privilegedAccessReady =
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

                    privilegedAccessReady =
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

            override fun onPairedConnectionFailed(
                cause: Throwable
            ) {
                postToUi {
                    openSettingsWhenAccessibilityReady =
                        false

                    setOperationInProgress(
                        false
                    )

                    privilegedAccessReady =
                        false

                    binding
                        .continueButton
                        .isEnabled =
                        false

                    onPairedConnectionFailed
                        ?.invoke(
                            cause
                        )
                        ?: showPairedConnectionFailure(
                            cause
                        )
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

                    privilegedAccessReady =
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

    private fun showPairedConnectionFailure(
        cause: Throwable?
    ) {
        waitingForAccessibilityEnable =
            false

        manualFallbackVisible =
            false

        retryConnectionVisible =
            true

        openSettingsWhenAccessibilityReady =
            false

        settingsOpenedForCurrentAttempt =
            false

        setOperationInProgress(
            false
        )

        hidePairingCodeInput()

        binding
            .pairButton
            .visibility =
            View.VISIBLE

        binding
            .pairButton
            .text =
            "Retry connection"

        binding
            .openWirelessDebuggingButton
            .visibility =
            View.GONE

        binding
            .continueButton
            .isEnabled =
            false

        binding
            .statusText
            .text =
            buildErrorMessage(
                prefix =
                    "Horus paired successfully, but the Direct ADB connection is not ready yet. Retry the connection without pairing again.",
                cause =
                    cause?.message
            )
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

        val message =
            error
                ?.message
                ?.lowercase()
                .orEmpty()

        if (
            message.contains(
                "accessibility service must be enabled"
            )
        ) {
            manualFallbackVisible =
                false

            waitingForAccessibilityEnable =
                true

            hidePairingCodeInput()

            binding
                .pairButton
                .visibility =
                View.VISIBLE

            binding
                .pairButton
                .text =
                "Start automatic pairing"

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
                "Enable Horus in Accessibility settings. Automatic pairing will continue when you return."

            openAccessibilitySettings()

            return
        }

        if (
            message.contains(
                "accessibility service did not become ready"
            )
        ) {
            manualFallbackVisible =
                false

            waitingForAccessibilityEnable =
                false

            showAutomaticPairingState()

            binding
                .statusText
                .text =
                "Horus Accessibility is enabled, but the service did not become ready. Tap Start automatic pairing to retry."

            return
        }

        waitingForAccessibilityEnable =
            false

        showManualFallback(
            error
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

    private fun showConnectedState() {
        showPrivilegedAccessReady(
            message =
                "Direct Access is ready."
        )

        binding
            .openWirelessDebuggingButton
            .visibility =
            View.GONE

        binding
            .pairButton
            .visibility =
            View.GONE

        updateWirelessDebuggingStatus()
    }

    private fun showPrivilegedAccessReady(
        message: String
    ) {
        waitingForAccessibilityEnable =
            false

        privilegedAccessReady =
            true

        manualFallbackVisible =
            false

        retryConnectionVisible =
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
            .statusText
            .text =
            message

        binding
            .continueButton
            .isEnabled =
            true

        updateShizukuState()
    }

    private fun showAutomaticPairingState() {
        privilegedAccessReady =
            false

        manualFallbackVisible =
            false

        retryConnectionVisible =
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
            "Choose Direct ADB or Shizuku to prepare privileged access."

        updateWirelessDebuggingStatus()
        updateShizukuState()
    }

    private fun showManualFallback(
        cause: Throwable?
    ) {
        waitingForAccessibilityEnable =
            false

        manualFallbackVisible =
            true

        retryConnectionVisible =
            false

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
            buildErrorMessage(
                prefix =
                    "Automatic pairing could not complete. Manual pairing is available as a fallback.",
                cause =
                    cause?.message
            )
    }

    private fun buildErrorMessage(
        prefix: String,
        cause: String?
    ): String {
        return buildString {
            append(
                prefix
            )

            cause
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

        binding
            .shizukuButton
            .isEnabled =
            !inProgress &&
                PermissionUtils
                    .isShizukuAvailable()
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

    private fun updateShizukuState() {
        val currentBinding =
            _binding
                ?: return

        val available =
            PermissionUtils
                .isShizukuAvailable()

        currentBinding
            .shizukuCard
            .visibility =
            if (available) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (!available) {
            return
        }

        val permissionGranted =
            PermissionUtils
                .hasShizukuPermission()

        currentBinding
            .shizukuStatus
            .text =
            if (permissionGranted) {
                "Shizuku is available and Horus has permission."
            } else {
                "Shizuku is available. Horus permission is required."
            }

        currentBinding
            .shizukuButton
            .text =
            if (permissionGranted) {
                "Use Shizuku"
            } else {
                "Allow Shizuku access"
            }

        currentBinding
            .shizukuButton
            .isEnabled =
            !operationInProgress
    }

    private fun openAccessibilitySettings() {
        neth.iecal.curbox.utils.privileged.adb.AccessibilityReturnRequest.request(requireContext())
        try {
            PermissionUtils
                .openAccessibilityServiceScreen(
                    requireContext(),
                    AppBlockerService::class.java
                )
        } catch (
            _: ActivityNotFoundException
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )
        } catch (
            _: Throwable
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
