package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.AutoDnd
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.anti_stimulants.MindfulMessage
import neth.iecal.curbox.blockers.AntiUninstallBlocker
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.ReelScriptRunner
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.trackers.AppUsageTracker
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.trackers.ReelUsageTracker
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.trackers.WebsiteUsageTracker
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager
import neth.iecal.curbox.utils.privileged.adb.AdbPairingAccessibilityExtractor
import neth.iecal.curbox.utils.privileged.adb.AdbPairingCaptureBridge

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val appBlocker: AppBlocker =
        AppBlocker()

    private val focusModeBlocker =
        FocusModeBlocker()

    private val autoDnd =
        AutoDnd()

    private val reelBlocker =
        ReelBlocker()

    private val reelScriptRunner =
        ReelScriptRunner()

    private var keywordBlocker =
        KeywordBlocker()

    private val uiHider =
        UiHider()

    private val nodePicker =
        NodePicker()

    private val antiUninstallBlocker =
        AntiUninstallBlocker()

    private var grayScaleFilter =
        GrayScaleFilter()

    private val reelsOverlayManager by lazy {
        ReelsOverlayManager(
            this
        )
    }

    private val reelsCountTracker =
        ReelsCountTracker()

    private val reelUsageTracker =
        ReelUsageTracker()

    private val mindfulMessage =
        MindfulMessage()

    private val websiteUsageTracker =
        WebsiteUsageTracker()

    private val appUsageTracker =
        AppUsageTracker()

    private val serviceScope =
        CoroutineScope(
            Dispatchers.Default +
                SupervisorJob()
        )

    private val eventChannel =
        Channel<AccessibilityEvent>(
            Channel.CONFLATED
        ) { droppedEvent ->
            droppedEvent.recycle()
        }

    private val websiteObservationChannel =
        Channel<WebsiteObservation?>(
            Channel.CONFLATED
        )

    private lateinit var crashLogger:
        CrashLogger

    private var pairingCaptureStateReceiver:
        BroadcastReceiver? = null

    fun syncDndState() {
        val autoDndActive =
            autoDnd.isDndRequested()

        val manualFocusDndActive =
            focusModeBlocker
                .isDndRequested()

        neth.iecal.curbox.utils.DndHelper
            .applyDndState(
                this,
                autoDndActive ||
                    manualFocusDndActive
            )
    }

    override fun onCreate() {
        super.onCreate()

        crashLogger =
            CrashLogger(
                this
            )

        AdbPairingAccessibilityExtractor
            .disarm()

        try {
            pairingCaptureStateReceiver =
                AdbPairingCaptureBridge
                    .registerCaptureStateReceiver(
                        context =
                            this,
                        listener =
                            object :
                                AdbPairingCaptureBridge
                                    .CaptureStateListener {

                                override fun onCaptureArmed() {
                                    AdbPairingAccessibilityExtractor
                                        .arm()
                                }

                                override fun onCaptureDisarmed() {
                                    AdbPairingAccessibilityExtractor
                                        .disarm()
                                }
                            }
                    )
        } catch (t: Throwable) {
            Log.e(
                "DirectADB",
                "Failed to register pairing capture control receiver",
                t
            )

            crashLogger
                .logNonFatalError(
                    Exception(t)
                )
        }

        try {
            rikka.shizuku.ShizukuProvider
                .requestBinderForNonProviderProcess(
                    this
                )
        } catch (e: Exception) {
            Log.e(
                "Shizuku",
                "Failed to bind Shizuku in non-provider process",
                e
            )
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        event ?: return

        super.onAccessibilityEvent(
            event
        )

        /*
         * Extraction immediately returns unless an explicit Direct Access
         * pairing session has armed it through the cross-process bridge.
         */
        try {
            AdbPairingAccessibilityExtractor
                .onAccessibilityEvent(
                    context =
                        this,
                    event =
                        event,
                    activeWindowRoot =
                        rootInActiveWindow
                )
        } catch (t: Throwable) {
            Log.e(
                "DirectADB",
                "Pairing accessibility extraction failed",
                t
            )

            if (
                ::crashLogger
                    .isInitialized
            ) {
                crashLogger
                    .logNonFatalError(
                        Exception(t)
                    )
            }
        }

        try {
            if (
                BuildConfig
                    .SUPPORTS_ANTI_UNINSTALL
            ) {
                antiUninstallBlocker
                    .doAntiUninstallCheck(
                        event
                    )
            }

            appBlocker
                .doAppBlockerCheck(
                    event
                )

            grayScaleFilter
                .doGrayscaleCheck(
                    event
                )

            focusModeBlocker
                .doFocusModeCheck(
                    event
                )
        } catch (t: Throwable) {
            Log.e(
                "error",
                t.message
                    ?: "Unknown error"
            )

            crashLogger
                .logNonFatalError(
                    Exception(t)
                )
        }

        try {
            appUsageTracker
                .onEvent(
                    event
                )

            mindfulMessage
                .onEvent(
                    event
                )
        } catch (t: Throwable) {
            if (
                t is
                CancellationException
            ) {
                throw t
            }

            crashLogger
                .logNonFatalError(
                    Exception(t)
                )

            Log.e(
                "Usage Tracking",
                "Usage tracking or mindful message error",
                t
            )
        }

        val eventCopy =
            AccessibilityEvent
                .obtain(
                    event
                )

        val result =
            eventChannel
                .trySend(
                    eventCopy
                )

        if (
            result.isFailure
        ) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (
                event in
                eventChannel
            ) {
                try {
                    websiteUsageTracker
                        .onEvent(
                            event
                        )

                    val reelDetection =
                        reelScriptRunner
                            .detect(
                                event
                            )

                    val reelComparator =
                        reelDetection
                            ?.comparator

                    reelUsageTracker
                        .onEvent(
                            event,
                            reelComparator
                        )

                    reelsCountTracker
                        .onEvent(
                            event,
                            reelComparator
                        )

                    reelBlocker
                        .doViewBlockerCheck(
                            event,
                            reelComparator,
                            reelDetection
                                ?.isInstagramReelOpenedFromDmInbox ==
                                true
                        )

                    keywordBlocker
                        .checkIfUnsupportedBrowser(
                            event
                        )

                    if (
                        BuildConfig
                            .SUPPORTS_UI_HIDER
                    ) {
                        uiHider
                            .doUiHiderCheck(
                                event
                            )
                    }
                } catch (t: Throwable) {
                    if (
                        t is
                        CancellationException
                    ) {
                        throw t
                    }

                    crashLogger
                        .logNonFatalError(
                            Exception(t)
                        )

                    Log.e(
                        "Blocker",
                        "Background worker error",
                        t
                    )
                } finally {
                    event.recycle()
                }
            }
        }

        serviceScope.launch(
            Dispatchers.IO
        ) {
            for (
                observation in
                    websiteObservationChannel
            ) {
                try {
                    keywordBlocker
                        .onWebsiteObserved(
                            observation
                        )
                } catch (t: Throwable) {
                    if (
                        t is
                        CancellationException
                    ) {
                        throw t
                    }

                    crashLogger
                        .logNonFatalError(
                            Exception(t)
                        )

                    Log.e(
                        "KeywordBlocker",
                        "Website observation check failed",
                        t
                    )
                }
            }
        }
    }

    @SuppressLint(
        "UnspecifiedRegisterReceiverFlag"
    )
    override fun onServiceConnected() {
        super.onServiceConnected()

        appBlocker
            .setupAppBlocker(
                this
            )

        focusModeBlocker
            .setupFocusMode(
                this
            )

        autoDnd
            .setup(
                this
            )

        reelBlocker
            .setupBlocker(
                this
            )

        reelScriptRunner
            .setup(
                this
            )

        keywordBlocker
            .setupBlocker(
                this
            )

        grayScaleFilter
            .setup(
                this
            )

        if (
            BuildConfig
                .SUPPORTS_UI_HIDER
        ) {
            uiHider
                .setupBlocker(
                    this
                )

            nodePicker
                .setupBlocker(
                    this
                )
        }

        if (
            BuildConfig
                .SUPPORTS_ANTI_UNINSTALL
        ) {
            antiUninstallBlocker
                .setupBlocker(
                    this
                )
        }

        reelUsageTracker
            .setup(
                this
            )

        reelsCountTracker
            .setup(
                this,
                reelsOverlayManager,
                reelUsageTracker::onReelCounted
            )

        mindfulMessage
            .setup(
                this
            )

        websiteUsageTracker
            .setup(
                this
            ) { observation ->
                websiteObservationChannel
                    .trySend(
                        observation
                    )
            }

        appUsageTracker
            .setup(
                this
            )

        neth.iecal.curbox.utils
            .UsageStatsCleaner
            .watch(
                this
            )

        focusModeBlocker
            .setupReceivers()

        appBlocker
            .setupReceivers()

        reelBlocker
            .setupReceivers()

        keywordBlocker
            .setupReceivers()

        grayScaleFilter
            .setupReceivers()

        if (
            BuildConfig
                .SUPPORTS_UI_HIDER
        ) {
            uiHider
                .setupReceivers()

            nodePicker
                .setupReceivers()
        }

        reelsCountTracker
            .setupReceivers()

        if (
            !Settings.canDrawOverlays(
                this
            )
        ) {
            Toast.makeText(
                this,
                getString(
                    R.string
                        .please_provide_draw_over_other_apps
                ),
                Toast.LENGTH_LONG
            ).show()

            val intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName"
                        .toUri()
                ).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK
                }

            startActivity(
                intent
            )
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        /*
         * Stop pairing capture before tearing down the Accessibility service.
         */
        AdbPairingAccessibilityExtractor
            .disarm()

        AdbPairingCaptureBridge.unregister(
            context =
                this,
            receiver =
                pairingCaptureStateReceiver
        )

        pairingCaptureStateReceiver =
            null

        super.onDestroy()

        try {
            focusModeBlocker
                .removeReceivers()

            focusModeBlocker
                .onDestroy()

            autoDnd
                .stop()

            reelBlocker
                .removeReceivers()

            appBlocker
                .onDestroy()

            keywordBlocker
                .removeReceivers()

            grayScaleFilter
                .unregisterReceivers()

            if (
                BuildConfig
                    .SUPPORTS_UI_HIDER
            ) {
                uiHider
                    .removeReceivers()

                nodePicker
                    .removeReceivers()
            }

            if (
                BuildConfig
                    .SUPPORTS_ANTI_UNINSTALL
            ) {
                antiUninstallBlocker
                    .onDestroy()
            }

            mindfulMessage
                .onDestroy()

            reelsCountTracker
                .onDestroy()

            reelUsageTracker
                .onDestroy()

            websiteUsageTracker
                .onDestroy()

            appUsageTracker
                .onDestroy()

            eventChannel.close()
            websiteObservationChannel.close()
            serviceScope.cancel()
        } catch (_: Exception) {
        }
    }
}
