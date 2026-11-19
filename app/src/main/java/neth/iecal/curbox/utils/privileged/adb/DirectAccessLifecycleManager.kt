package neth.iecal.curbox.utils.privileged.adb

import android.content.Context

/**
 * Owns Direct Access lifecycle and recovery behaviour.
 *
 * This manager deliberately performs reconnect only.
 *
 * It never:
 * - starts pairing automatically,
 * - asks for a pairing code,
 * - deletes the stored ADB identity after a transient failure,
 * - enables recovery before Direct Access has explicitly completed setup.
 *
 * Recovery becomes active only after [setRecoveryEnabled] is called with true.
 */
object DirectAccessLifecycleManager {

    private const val PREFS_NAME =
        "direct_access_lifecycle"

    private const val KEY_RECOVERY_ENABLED =
        "recovery_enabled"

    private val lock =
        Any()

    private var orchestrator:
        DirectAccessOrchestrator? = null

    private var appContext:
        Context? = null

    private var started =
        false

    private var recoveryInProgress =
        false

    /**
     * Initialises lifecycle recovery for the main CurBox process.
     *
     * Calling this repeatedly is safe.
     *
     * If Direct Access has previously completed setup, an asynchronous
     * reconnect attempt is started. Otherwise this method only records the
     * application context and performs no ADB work.
     */
    fun start(
        context: Context
    ) {
        val contextToUse =
            context.applicationContext

        synchronized(lock) {
            if (started) {
                return
            }

            appContext =
                contextToUse

            orchestrator =
                DirectAccessOrchestrator(
                    contextToUse
                )

            started =
                true
        }

        if (isRecoveryEnabled(contextToUse)) {
            requestRecovery()
        }
    }

    /**
     * Enables or disables automatic Direct Access recovery.
     *
     * This preference represents successful Direct Access setup. It is
     * intentionally separate from whether an AndroidKeyStore identity exists,
     * because an identity may have been created during an incomplete setup.
     */
    fun setRecoveryEnabled(
        context: Context,
        enabled: Boolean
    ) {
        context.applicationContext
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_RECOVERY_ENABLED,
                enabled
            )
            .apply()

        if (!enabled) {
            synchronized(lock) {
                recoveryInProgress =
                    false
            }

            orchestrator?.cancel()
        }
    }

    /**
     * Returns whether CurBox should automatically restore Direct Access.
     */
    fun isRecoveryEnabled(
        context: Context
    ): Boolean {
        return context.applicationContext
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getBoolean(
                KEY_RECOVERY_ENABLED,
                false
            )
    }

    /**
     * Called when the active network disappears.
     *
     * The existing transport is disconnected so it cannot continue reporting
     * a stale ADB connection as usable.
     *
     * The persistent identity is preserved for the next reconnect.
     */
    fun onNetworkLost() {
        val shouldHandle =
            synchronized(lock) {
                started &&
                    appContext
                        ?.let(
                            ::isRecoveryEnabled
                        ) == true
            }

        if (!shouldHandle) {
            return
        }

        synchronized(lock) {
            recoveryInProgress =
                false
        }

        orchestrator?.cancel()

        AdbChannelManager.disconnect()
    }

    /**
     * Called when a usable network becomes available or changes.
     *
     * Discovery is performed again instead of reusing an old host or port,
     * because Wireless ADB connection endpoints are discovered dynamically.
     */
    fun onNetworkAvailable() {
        requestRecovery()
    }

    /**
     * Requests normal Direct ADB recovery.
     *
     * Only one lifecycle-owned reconnect attempt is allowed at a time.
     */
    fun requestRecovery() {
        val context =
            synchronized(lock) {
                appContext
            } ?: return

        if (!isRecoveryEnabled(context)) {
            return
        }

        if (AdbChannelManager.isConnected()) {
            synchronized(lock) {
                recoveryInProgress =
                    false
            }

            return
        }

        val currentOrchestrator =
            synchronized(lock) {
                if (
                    !started ||
                    recoveryInProgress
                ) {
                    return
                }

                recoveryInProgress =
                    true

                orchestrator
            } ?: run {
                synchronized(lock) {
                    recoveryInProgress =
                        false
                }

                return
            }

        currentOrchestrator.ensureConnected(
            object :
                DirectAccessOrchestrator.Listener {

                override fun onConnected() {
                    finishRecovery()
                }

                override fun onWirelessDebuggingRequired(
                    cause: Throwable?
                ) {
                    /*
                     * Lifecycle recovery stops here.
                     *
                     * If Wireless Debugging requires user action, background
                     * recovery must never start pairing or repeatedly prompt.
                     */
                    finishRecovery()
                }

                override fun onPairingRequired(
                    cause: Throwable?
                ) {
                    /*
                     * Recovery does not pair automatically.
                     *
                     * Keep the existing identity and let an explicit user flow
                     * decide whether repairing/re-pairing is appropriate.
                     */
                    finishRecovery()
                }

                override fun onError(
                    error: Throwable
                ) {
                    finishRecovery()
                }
            }
        )
    }

    /**
     * Stops lifecycle-owned work without deleting preferences or the stored
     * ADB identity.
     */
    fun stop() {
        val current =
            synchronized(lock) {
                if (!started) {
                    return
                }

                started =
                    false

                recoveryInProgress =
                    false

                val value =
                    orchestrator

                orchestrator =
                    null

                appContext =
                    null

                value
            }

        current?.cancel()
    }

    private fun finishRecovery() {
        synchronized(lock) {
            recoveryInProgress =
                false
        }
    }
}
