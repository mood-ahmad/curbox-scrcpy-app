package neth.iecal.curbox.utils.privileged.adb

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts Wireless ADB pairing information from Android Settings'
 * accessibility tree while an explicit CurBox auto-pairing session is active.
 *
 * This component does not perform pairing itself.
 *
 * Responsibilities:
 * - Observe only while CurBox has explicitly armed auto-pairing.
 * - Ignore unrelated applications.
 * - Walk the active Settings window using a bounded traversal.
 * - Detect the six-digit Wireless Debugging pairing code.
 * - Detect the on-screen IP address and pairing port when available.
 * - Deliver each detected pairing dialog only once.
 *
 * Network pairing remains owned by the Direct ADB pairing layer.
 */
object AdbPairingAccessibilityExtractor {

    private const val MAX_TREE_DEPTH =
        15

    /**
     * Android's Settings package on AOSP and the Android variants CurBox
     * currently targets.
     *
     * Keeping this strict prevents CurBox from scanning arbitrary application
     * windows for six-digit numbers.
     */
    private val allowedSettingsPackages =
        setOf(
            "com.android.settings"
        )

    /**
     * Android shows the pairing code as six digits. Some Settings variants
     * may visually separate it into two groups.
     */
    private val pairingCodeRegex =
        Regex(
            """^\d{3}\s?\d{3}$"""
        )

    /**
     * The Wireless Debugging pairing dialog can display an IPv4 address and
     * temporary pairing port together.
     *
     * mDNS remains the preferred authority for the actual pairing endpoint;
     * this value is primarily useful as a fallback and for correlating the
     * dialog discovered through Accessibility.
     */
    private val ipv4AndPortRegex =
        Regex(
            """(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})"""
        )

    private val armed =
        AtomicBoolean(false)

    private val deliveryLock =
        Any()

    @Volatile
    private var listener:
        Listener? = null

    private var lastDeliveredFingerprint:
        String? = null

    data class PairingInfo(
        val code: String,
        val port: Int?,
        val rawAddress: String?
    )

    interface Listener {

        /**
         * Called after CurBox finds a valid six-digit pairing code in Android
         * Settings.
         *
         * [PairingInfo.port] can be null. The pairing layer should prefer the
         * port discovered through _adb-tls-pairing._tcp and use this
         * on-screen value only as a fallback.
         */
        fun onPairingInfoDetected(
            info: PairingInfo
        )
    }

    /**
     * Enables pairing-dialog extraction for an explicit CurBox setup attempt.
     *
     * Accessibility events are ignored completely until this method is called.
     */
    fun start(
        listener: Listener
    ) {
        synchronized(deliveryLock) {
            this.listener =
                listener

            lastDeliveredFingerprint =
                null

            armed.set(
                true
            )
        }
    }

    /**
     * Stops extraction and forgets the listener.
     *
     * Calling stop repeatedly is safe.
     */
    fun stop() {
        synchronized(deliveryLock) {
            armed.set(
                false
            )

            listener =
                null

            lastDeliveredFingerprint =
                null
        }
    }

    /**
     * Processes one Accessibility event.
     *
     * [activeWindowRoot] should normally be the AccessibilityService's
     * rootInActiveWindow. event.source is preferred when it is available
     * because the code and IP/port can be separate nodes inside the same
     * Settings dialog.
     */
    fun onAccessibilityEvent(
        event: AccessibilityEvent,
        activeWindowRoot: AccessibilityNodeInfo?
    ) {
        if (!armed.get()) {
            return
        }

        val packageName =
            event.packageName
                ?.toString()
                ?: return

        if (
            packageName !in
            allowedSettingsPackages
        ) {
            return
        }

        val root =
            event.source
                ?.let(
                    ::findRoot
                )
                ?: activeWindowRoot
                ?: return

        scan(
            root
        )
    }

    private fun scan(
        root: AccessibilityNodeInfo
    ) {
        if (!armed.get()) {
            return
        }

        var code:
            String? = null

        var port:
            Int? = null

        var rawAddress:
            String? = null

        walk(
            node = root,
            depth = 0
        ) { text ->

            if (code == null) {
                val candidate =
                    text.trim()

                if (
                    pairingCodeRegex.matches(
                        candidate
                    )
                ) {
                    code =
                        candidate.replace(
                            " ",
                            ""
                        )
                }
            }

            if (port == null) {
                val match =
                    ipv4AndPortRegex.find(
                        text
                    )

                if (match != null) {
                    val detectedPort =
                        match
                            .groupValues
                            .getOrNull(
                                1
                            )
                            ?.toIntOrNull()

                    if (
                        detectedPort != null &&
                        detectedPort in 1..65535
                    ) {
                        port =
                            detectedPort

                        rawAddress =
                            match.value
                    }
                }
            }

            /*
             * Once both values exist there is no reason to inspect the rest of
             * the potentially large accessibility tree.
             */
            code != null &&
                port != null
        }

        val detectedCode =
            code ?: return

        deliver(
            PairingInfo(
                code =
                    detectedCode,
                port =
                    port,
                rawAddress =
                    rawAddress
            )
        )
    }

    private fun deliver(
        info: PairingInfo
    ) {
        if (!armed.get()) {
            return
        }

        val currentListener:
            Listener

        synchronized(deliveryLock) {
            if (!armed.get()) {
                return
            }

            val activeListener =
                listener ?: return

            val fingerprint =
                buildString {
                    append(
                        info.code
                    )

                    append(
                        ':'
                    )

                    append(
                        info.port
                            ?: -1
                    )
                }

            if (
                fingerprint ==
                lastDeliveredFingerprint
            ) {
                return
            }

            lastDeliveredFingerprint =
                fingerprint

            currentListener =
                activeListener
        }

        currentListener
            .onPairingInfoDetected(
                info
            )
    }

    /**
     * Finds the topmost node belonging to the event's window.
     *
     * The walk is bounded so a malformed or unusual Accessibility tree cannot
     * cause unbounded traversal.
     */
    private fun findRoot(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        var current =
            node

        var depth =
            0

        while (
            depth <
            MAX_TREE_DEPTH
        ) {
            val parent =
                current.parent
                    ?: break

            current =
                parent

            depth +=
                1
        }

        return current
    }

    /**
     * Depth-first traversal of node text.
     *
     * Returning true from [visit] stops traversal immediately.
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        visit: (String) -> Boolean
    ): Boolean {
        if (
            node == null ||
            depth >
            MAX_TREE_DEPTH
        ) {
            return false
        }

        val text =
            node.text
                ?.toString()

        if (
            !text.isNullOrBlank() &&
            visit(
                text
            )
        ) {
            return true
        }

        for (
            index in
            0 until node.childCount
        ) {
            if (
                walk(
                    node =
                        node.getChild(
                            index
                        ),
                    depth =
                        depth + 1,
                    visit =
                        visit
                )
            ) {
                return true
            }
        }

        return false
    }
}
