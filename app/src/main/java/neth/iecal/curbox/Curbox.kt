package neth.iecal.curbox

import android.app.Application
import android.content.Context.android.material.color.DynamicColors
import neth.iecal.curbox.data.sync.SyncGateway
import neth.iecal.curbox.remotecontrol.EventLogger
import neth.iecal.curbox.remotecontrol.RemoteControlGuardian
import neth.iecal.curbox.remotecontrol.RemoteControlManager
import neth.iecal.curbox.remotecontrol.RemoteControlState

class Curbox : Application() {

    private val networkLock =
        Any()

    private var activeNetwork: Network? =
        null

    override fun onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this)

        Thread.setDefaultUncaughtExceptionHandler(
            CrashLogger(this)
        )

        super.onCreate()

        registerActivityLifecycleCallbacks(
            WatermarkCallbacks()
        )

        /*
         * Remote control must run only inside the main application
         * process.
         *
         * CurBox also has processes such as:
         *
         * :crash_handler
         * :app_blocker_service
         *
         * Each Android process creates its own Application instance.
         * Starting Remote Control outside the main process would create
         * independent Manager, Guardian and process-launching instances.
         */
        if (!isMainProcess()) {
            return
        }

        initialiseRemoteControl()

        try {
            SyncGateway.init(this)

            if (SyncGateway.provider.isAvailable) {
                SyncGateway.provider.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        neth.iecal.curbox.utils.UsageStatsCleaner
            .cleanNowIfDue(this)

        neth.iecal.curbox.utils.SettingsChangeDelayUtils
            .applyDuePendingChangesAsync(this)
    }

    private fun initialiseRemoteControl() {
        RemoteControlManager.init(this)

        /*
         * Capture the currently active network before registering the
         * callback.
         *
         * Android normally reports the already-active network when the
         * callback is first registered. That initial callback must not
         * be treated as a network change or it would immediately restart
         * the engine started below.
         */
        registerNetworkMonitor()

        RemoteControlManager.autoStartIfEnabled(this)

        RemoteControlGuardian.start(this)
    }

    private fun registerNetworkMonitor() {
        try {
            val connectivityManager =
                getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            synchronized(networkLock) {
                activeNetwork =
                    connectivityManager.activeNetwork
            }

            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {

                    override fun onAvailable(
                        network: Network
                    ) {
                        val networkChanged =
                            synchronized(networkLock) {
                                val previousNetwork =
                                    activeNetwork

                                if (previousNetwork == network) {
                                    false
                                } else {
                                    activeNetwork = network
                                    true
                                }
                            }

                        if (!networkChanged) {
                            EventLogger.log(
                                "NETWORK",
                                "Initial/current network reported; restart skipped"
                            )

                            return
                        }

                        /*
                         * Restart only an engine that has already
                         * completed startup.
                         *
                         * Starting or stopping engines must be allowed
                         * to finish without a competing network restart.
                         * Idle/Error recovery remains the responsibility
                         * of Manager/Guardian.
                         */
                        when (RemoteControlManager.state.value) {
                            is RemoteControlState.Ready -> {
                                EventLogger.log(
                                    "NETWORK",
                                    "Active network changed; restarting service"
                                )

                                RemoteControlManager.restart(
                                    this@Curbox
                                )
                            }

                            is RemoteControlState.Starting -> {
                                EventLogger.log(
                                    "NETWORK",
                                    "Network changed while engine is starting; restart skipped"
                                )
                            }

                            is RemoteControlState.Stopping -> {
                                EventLogger.log(
                                    "NETWORK",
                                    "Network changed while engine is stopping; restart skipped"
                                )
                            }

                            is RemoteControlState.Idle -> {
                                EventLogger.log(
                                    "NETWORK",
                                    "Network became available while engine is idle; restart skipped"
                                )
                            }

                            is RemoteControlState.Error -> {
                                EventLogger.log(
                                    "NETWORK",
                                    "Network changed while engine is in error state; Guardian will handle recovery"
                                )
                            }
                        }
                    }

                    override fun onLost(
                        network: Network
                    ) {
                        synchronized(networkLock) {
                            if (activeNetwork == network) {
                                activeNetwork = null
                            }
                        }

                        EventLogger.log(
                            "NETWORK",
                            "Active network lost"
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            EventLogger.log(
                "NETWORK",
                "Failed to register monitor: " +
                    (t.message ?: t.javaClass.simpleName)
            )
        }
    }

    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName() == packageName
        }

        val processName =
            try {
                java.io.File(
                    "/proc/self/cmdline"
                )
                    .readText()
                    .trim {
                        it <= ' ' ||
                            it == '\u0000'
                    }
            } catch (e: Exception) {
                packageName
            }

        return processName == packageName
    }
}
