package neth.iecal.curbox.services

import neth.iecal.curbox.utils.privileged.adb.WirelessDebuggingNodeMatcher
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
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
import neth.iecal.curbox.utils.privileged.adb.AdbWirelessDebuggingAccessibilityNavigator

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

    /**
     * Session currently owned by this Accessibility process.
     *
     * A DISARM belonging to an older session must never disable a newer
     * automatic-pairing attempt.
     */
    @Volatile
    private var activePairingSessionId:
        String? = null

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

        activePairingSessionId =
            null

        AdbPairingAccessibilityExtractor
            .disarm()

        AdbWirelessDebuggingAccessibilityNavigator
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

                                override fun onCaptureArmRequested(
                                    sessionId: String
                                ) {
                                    handlePairingCaptureArm(
                                        sessionId
                                    )
                                }

                                override fun onCaptureDisarmRequested(
                                    sessionId: String
                                ) {
                                    handlePairingCaptureDisarm(
                                        sessionId
                                    )
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

    private val navigatorHandler =
        android.os.Handler(
            android.os.Looper.getMainLooper()
        )

    private val navigatorTick =
        object : Runnable {
            override fun run() {
                if (
                    !AdbWirelessDebuggingAccessibilityNavigator
                        .isArmed()
                ) {
                    return
                }

                if (AdbWirelessDebuggingAccessibilityNavigator.enableOnly &&
                    !neth.iecal.curbox.utils.privileged.adb.WirelessEnableRequest
                        .pending(this@AppBlockerService)
                ) {
                    AdbWirelessDebuggingAccessibilityNavigator.disarm()
                    return
                }

                try {
                    val root =
                        rootInActiveWindow

                    AdbWirelessDebuggingAccessibilityNavigator
                        .navigate(
                            root?.packageName,
                            root
                        )
                } catch (t: Throwable) {
                    Log.e(
                        "DirectADB",
                        "Navigator tick failed",
                        t
                    )
                }

                navigatorHandler.postDelayed(
                    this,
                    700L
                )
            }
        }

    private val batteryTick =
        object : Runnable {
            override fun run() {
                try {
                    val ctx = this@AppBlockerService
                    if (!neth.iecal.curbox.utils.privileged.adb
                            .BatteryUnrestrictedNavigator.pending(ctx)
                    ) {
                        return
                    }
                    val root = rootInActiveWindow
                    val pkg = root?.packageName?.toString()
                    if (pkg == "com.android.settings" || pkg == "com.samsung.android.settings") {
                        val node = neth.iecal.curbox.utils.privileged.adb
                            .AdbWirelessDebuggingAccessibilityNavigator
                            .AccessibilityUiNode(root!!)
                        val done = neth.iecal.curbox.utils.privileged.adb
                            .BatteryUnrestrictedNavigator.step(node, navigatorHost)
                        if (done) {
                            neth.iecal.curbox.utils.privileged.adb
                                .BatteryUnrestrictedNavigator.finish(ctx)
                            scheduleReturnToCurbox(800L)
                            return
                        }
                    }
                    if (neth.iecal.curbox.utils.privileged.adb
                            .BatteryUnrestrictedNavigator.exhausted()
                    ) {
                        navigatorHost.log("battery: gave up after too many passes")
                        neth.iecal.curbox.utils.privileged.adb
                            .BatteryUnrestrictedNavigator.finish(ctx)
                        scheduleReturnToCurbox(800L)
                        return
                    }
                } catch (t: Throwable) {
                    Log.e("DirectADB", "Battery tick failed", t)
                }
                navigatorHandler.postDelayed(this, 800L)
            }
        }

    /** Starts enable-only navigation when the app asked for Wireless debugging. */
    private fun maybeStartWirelessEnable() {
        if (AdbWirelessDebuggingAccessibilityNavigator.isArmed()) return
        if (neth.iecal.curbox.utils.privileged.adb.WirelessEnableRequest.pending(this)) {
            AdbWirelessDebuggingAccessibilityNavigator.host = navigatorHost
            AdbWirelessDebuggingAccessibilityNavigator.armEnableOnly()
            startNavigatorTicker()
        }
    }

    private fun maybeStartBatteryTick() {
        if (neth.iecal.curbox.utils.privileged.adb
                .BatteryUnrestrictedNavigator.pending(this)
        ) {
            navigatorHandler.removeCallbacks(batteryTick)
            navigatorHandler.postDelayed(batteryTick, 800L)
        }
    }

    private fun startNavigatorTicker() {
        navigatorHandler.removeCallbacks(
            navigatorTick
        )

        navigatorHandler.postDelayed(
            navigatorTick,
            700L
        )
    }

    private val navigatorHost =
        object :
            AdbWirelessDebuggingAccessibilityNavigator.Host {

            override fun log(
                message: String
            ) {
                try {
                    neth.iecal.curbox.remotecontrol.EventLogger
                        .log(
                            "DIRECT_ADB_NAV",
                            message
                        )
                } catch (_: Throwable) {
                }
            }

            override fun openDeveloperOptions(): Boolean =
                neth.iecal.curbox.utils.privileged.adb.SettingsLauncher
                    .openDeveloperOptions(this@AppBlockerService)

            override fun openWirelessDebugging(): Boolean =
                neth.iecal.curbox.utils.privileged.adb.SettingsLauncher
                    .openWirelessDebugging(this@AppBlockerService)

            override fun wirelessEnabled() {
                try {
                    neth.iecal.curbox.utils.privileged.adb.WirelessEnableRequest.markEnabled(
                        this@AppBlockerService
                    )
                    AdbWirelessDebuggingAccessibilityNavigator.disarm()
                    scheduleReturnToCurbox(700L)
                } catch (_: Throwable) {
                }
            }

            override fun toast(
                message: String
            ) {
                navigatorHandler.post {
                    android.widget.Toast
                        .makeText(
                            this@AppBlockerService,
                            message,
                            android.widget.Toast.LENGTH_LONG
                        )
                        .show()
                }
            }

            override fun tap(
                x: Int,
                y: Int
            ): Boolean {
                val path =
                    android.graphics.Path().apply {
                        moveTo(
                            x.toFloat(),
                            y.toFloat()
                        )
                    }

                return dispatchGesture(
                    android.accessibilityservice.GestureDescription
                        .Builder()
                        .addStroke(
                            android.accessibilityservice
                                .GestureDescription
                                .StrokeDescription(
                                    path,
                                    0L,
                                    80L
                                )
                        )
                        .build(),
                    null,
                    null
                )
            }

            override fun swipeUp(): Boolean {
                val m =
                    resources.displayMetrics

                val x =
                    m.widthPixels / 2f

                val path =
                    android.graphics.Path().apply {
                        moveTo(
                            x,
                            m.heightPixels * 0.75f
                        )

                        lineTo(
                            x,
                            m.heightPixels * 0.35f
                        )
                    }

                return dispatchGesture(
                    android.accessibilityservice.GestureDescription
                        .Builder()
                        .addStroke(
                            android.accessibilityservice
                                .GestureDescription
                                .StrokeDescription(
                                    path,
                                    0L,
                                    350L
                                )
                        )
                        .build(),
                    null,
                    null
                )
            }

            override fun highlight(
                left: Int,
                top: Int,
                right: Int,
                bottom: Int
            ) {
                navigatorHandler.post {
                    try {
                        val wm =
                            getSystemService(
                                WINDOW_SERVICE
                            ) as android.view.WindowManager

                        val box =
                            android.view.View(
                                this@AppBlockerService
                            ).apply {
                                background =
                                    android.graphics.drawable
                                        .GradientDrawable()
                                        .apply {
                                            setColor(
                                                0x22FF0000
                                            )

                                            setStroke(
                                                6,
                                                0xFFFF3B30.toInt()
                                            )

                                            cornerRadius =
                                                24f
                                        }
                            }

                        val lp =
                            android.view.WindowManager.LayoutParams(
                                (right - left).coerceAtLeast(1),
                                (bottom - top).coerceAtLeast(1),
                                android.view.WindowManager
                                    .LayoutParams
                                    .TYPE_ACCESSIBILITY_OVERLAY,
                                android.view.WindowManager
                                    .LayoutParams
                                    .FLAG_NOT_FOCUSABLE or
                                    android.view.WindowManager
                                        .LayoutParams
                                        .FLAG_NOT_TOUCHABLE or
                                    android.view.WindowManager
                                        .LayoutParams
                                        .FLAG_LAYOUT_IN_SCREEN,
                                android.graphics.PixelFormat
                                    .TRANSLUCENT
                            ).apply {
                                gravity =
                                    android.view.Gravity.TOP or
                                        android.view.Gravity.START

                                x =
                                    left

                                y =
                                    top
                            }

                        wm.addView(
                            box,
                            lp
                        )

                        navigatorHandler.postDelayed(
                            {
                                try {
                                    wm.removeView(
                                        box
                                    )
                                } catch (_: Throwable) {
                                }
                            },
                            6000L
                        )
                    } catch (t: Throwable) {
                        Log.e(
                            "DirectADB",
                            "Highlight failed",
                            t
                        )
                    }
                }
            }
        }

    /**
     * Activates exactly one explicit automatic-pairing session.
     *
     * ACK is sent only after both Accessibility components have been armed.
     */
    private fun handlePairingCaptureArm(
        sessionId: String
    ) {
        if (sessionId.isBlank()) {
            return
        }

        activePairingSessionId =
            sessionId

        AdbPairingAccessibilityExtractor
            .reset()

        AdbPairingAccessibilityExtractor
            .arm()

        try {
            neth.iecal.curbox.remotecontrol.EventLogger.init(this)
        } catch (_: Throwable) {
        }

        AdbWirelessDebuggingAccessibilityNavigator.host =
            navigatorHost

        AdbPairingAccessibilityExtractor.onCodeDelivered = {
            navigatorHost.log("pairing code delivered")
            AdbWirelessDebuggingAccessibilityNavigator.disarm()
        }

        AdbWirelessDebuggingAccessibilityNavigator
            .arm()

        startNavigatorTicker()

        try {
            AdbPairingCaptureBridge
                .acknowledgeArmed(
                    context =
                        this,
                    sessionId =
                        sessionId
                )
        } catch (t: Throwable) {
            /*
             * If ACK cannot be sent, leave this attempt dormant instead of
             * scanning Settings without a main-process owner.
             */
            activePairingSessionId =
                null

            AdbPairingAccessibilityExtractor
                .disarm()

            AdbWirelessDebuggingAccessibilityNavigator
                .disarm()

            Log.e(
                "DirectADB",
                "Failed to acknowledge pairing capture session",
                t
            )

            crashLogger
                .logNonFatalError(
                    Exception(t)
                )
        }
    }

    /**
     * Only the matching session may disarm the current Accessibility attempt.
     */
    private fun handlePairingCaptureDisarm(
        sessionId: String
    ) {
        if (
            activePairingSessionId !=
            sessionId
        ) {
            return
        }

        activePairingSessionId =
            null

        AdbPairingAccessibilityExtractor
            .disarm()

        AdbWirelessDebuggingAccessibilityNavigator
            .disarm()

        scheduleReturnToCurbox()
    }

    /**
     * After the pairing code has been read (or the attempt ended) the user is
     * still inside Settings. Give pairing a few seconds, then bring CurBox
     * back from the Accessibility service, which may start activities from the
     * background. Falls back to Back presses while Settings is on screen.
     */
    /** Last resort: a high-priority notification that opens Horus in one tap. */
    private fun postReturnNotification() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
            launch.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            val pi = android.app.PendingIntent.getActivity(
                this, 7421, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        "horus_return",
                        "Return to Horus",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val n = androidx.core.app.NotificationCompat.Builder(this, "horus_return")
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Horus is ready")
                .setContentText("Tap to return to Horus")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(7421, n)
            navigatorHost.log("return: posted fallback notification")
        } catch (t: Throwable) {
            Log.e("DirectADB", "Return notification failed", t)
        }
    }

    private val returnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            navigatorHost.log("return requested by app after pairing")
            scheduleReturnToCurbox(200L)
        }
    }

    private fun scheduleReturnToCurbox(initialDelayMs: Long = 3500L) {
        var tries = 0

        val returnTask =
            object : Runnable {
                override fun run() {
                    try {
                        val activePackage =
                            rootInActiveWindow
                                ?.packageName
                                ?.toString()

                        if (activePackage == packageName) {
                            navigatorHost.log(
                                "returned to Horus after $tries tries"
                            )
                            return
                        }

                        val launch =
                            packageManager
                                .getLaunchIntentForPackage(
                                    packageName
                                )

                        if (launch != null) {
                            launch.addFlags(
                                android.content.Intent
                                    .FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent
                                        .FLAG_ACTIVITY_REORDER_TO_FRONT
                            )

                            startActivity(
                                launch
                            )
                        }

                        if (
                            tries % 2 == 1 &&
                            activePackage == "com.android.settings"
                        ) {
                            performGlobalAction(
                                GLOBAL_ACTION_BACK
                            )
                        }

                        navigatorHost.log(
                            "return attempt ${tries + 1} active=$activePackage"
                        )
                    } catch (t: Throwable) {
                        Log.e(
                            "DirectADB",
                            "Return to Horus failed",
                            t
                        )
                    }

                    tries += 1

                    if (tries < 10) {
                        navigatorHandler.postDelayed(
                            this,
                            1500L
                        )
                    } else {
                        postReturnNotification()
                    }
                }
            }

        navigatorHandler.postDelayed(
            returnTask,
            initialDelayMs
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        event ?: return

        if (event.packageName?.toString() == "com.android.settings") {
            maybeStartBatteryTick()
            maybeStartWirelessEnable()
        }

        try {
            if (neth.iecal.curbox.utils.privileged.adb.AdminActivationRequest.pending(this) &&
                event.className?.toString()?.contains("DeviceAdminAdd") == true
            ) {
                val r = rootInActiveWindow
                if (r != null) {
                    val node = neth.iecal.curbox.utils.privileged.adb.AdbWirelessDebuggingAccessibilityNavigator
                        .AccessibilityUiNode(r)
                    val target = WirelessDebuggingNodeMatcher.findByLabels(
                        node,
                        neth.iecal.curbox.utils.privileged.adb.AdminActivationRequest.activateLabels
                    )
                    if (target != null && (target.click() ||
                            WirelessDebuggingNodeMatcher.clickableAncestor(target)?.click() == true)
                    ) {
                        neth.iecal.curbox.utils.privileged.adb.AdminActivationRequest.finish(this)
                        navigatorHost.log("tamper: tapped Activate on device admin screen")
                        navigatorHandler.postDelayed({
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                if (neth.iecal.curbox.utils.AntiUninstallManager.isAdminActive(this@AppBlockerService)) {
                                    dataStoreManager.updateAntiUninstallConfig {
                                        it.copy(
                                            isEnabled = true,
                                            mode = neth.iecal.curbox.data.models.AntiUninstallMode.REMOTE,
                                            unlockRequestedAtMs = 0L
                                        )
                                    }
                                    navigatorHost.log("tamper: protection enabled after Activate")
                                }
                            }
                            scheduleReturnToCurbox(500L)
                        }, 1500L)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        super.onAccessibilityEvent(
            event
        )

        try {
            val sessionId =
                activePairingSessionId

            if (
                !sessionId.isNullOrBlank()
            ) {
                if (
                    AdbWirelessDebuggingAccessibilityNavigator
                        .isArmed()
                ) {
                    AdbWirelessDebuggingAccessibilityNavigator
                        .navigate(
                            packageName =
                                event.packageName,
                            root =
                                rootInActiveWindow
                                    ?: event.source
                        )
                }

                AdbPairingAccessibilityExtractor
                    .onAccessibilityEvent(
                        context =
                            this,
                        sessionId =
                            sessionId,
                        event =
                            event,
                        activeWindowRoot =
                            rootInActiveWindow
                    )
            }
        } catch (t: Throwable) {
            Log.e(
                "DirectADB",
                "Wireless Debugging accessibility automation failed",
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
        try {
            val filter = android.content.IntentFilter("neth.iecal.curbox.action.RETURN_TO_APP")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(returnReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(returnReceiver, filter)
            }
        } catch (_: Throwable) {
        }
        super.onServiceConnected()

        if (
            neth.iecal.curbox.utils.privileged.adb
                .AccessibilityReturnRequest.consume(this)
        ) {
            try {
                neth.iecal.curbox.remotecontrol.EventLogger.init(this)
            } catch (_: Throwable) {
            }
            navigatorHost.log(
                "accessibility enabled for pairing, returning to Horus"
            )
            scheduleReturnToCurbox(500L)
        }

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

        /*
         * Do not open Overlay Settings here.
         *
         * DirectAccessSetupFragment prepares a privileged backend first and
         * PersistentPrivilegesManager then configures SYSTEM_ALERT_WINDOW.
         *
         * Launching Overlay Settings from the Accessibility-service lifecycle
         * interrupts the automatic Direct Access setup flow immediately after
         * the user enables Accessibility.
         */

        startBackgroundWorker()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(returnReceiver)
        } catch (_: Throwable) {
        }
        activePairingSessionId =
            null

        AdbPairingAccessibilityExtractor
            .disarm()

        AdbWirelessDebuggingAccessibilityNavigator
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
