package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts Wireless ADB pairing information from Android Settings'
 * Accessibility tree.
 *
 * This object runs inside the Accessibility-service process.
 *
 * Through [AdbPairingCaptureBridge], detected pairing information is forwarded
 * to CurBox's main process where [AdbAutoPairingCoordinator] owns the actual
 * pairing operation.
 *
 * This component never performs network pairing itself.
 */
object AdbPairingAccessibilityExtractor {

    private const val MAX_TREE_DEPTH =
        15

    /**
     * Restrict inspection to Android Settings instead of scanning arbitrary
     * application windows for six-digit values.
     */
    private val allowedSettingsPackages =
        setOf(
            "com.android.settings"
        )

    /**
     * Android Wireless Debugging uses a six-digit pairing code.
     *
     * Accept both:
     *
     * 123456
     * 123 456
     */
    private val pairingCodeRegex =
        Regex(
            """^\d{3}\s?\d{3}$"""
        )

    /**
     * Matches the IPv4 address and temporary pairing port when Settings exposes
     * both values in the Accessibility tree.
     *
     * mDNS remains authoritative for the pairing endpoint. The captured port is
     * forwarded as supplementary information only.
     */
    private val ipv4AndPortRegex =
        Regex(
            """(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})"""
        )

    private val deliveryLock =
        Any()

    private var lastDeliveredFingerprint:
        String? = null

    data class PairingInfo(
        val code: String,
        val port: Int?,
        val rawAddress: String?
    )

    /**
     * Processes one Accessibility event from AppBlockerService.
     *
     * Only events belonging to Android Settings are inspected.
     */
    fun onAccessibilityEvent(
        context: Context,
        event: AccessibilityEvent,
        activeWindowRoot: AccessibilityNodeInfo?
    ) {
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
            context =
                context.applicationContext,
            root =
                root
        )
    }

    private fun scan(
        context: Context,
        root: AccessibilityNodeInfo
    ) {
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

            code != null &&
                port != null
        }

        val detectedCode =
            code ?: return

        deliver(
            context =
                context,
            info =
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

    /**
     * Deduplicates repeated Accessibility events from the same pairing dialog
     * before forwarding the capture across processes.
     */
    private fun deliver(
        context: Context,
        info: PairingInfo
    ) {
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

        synchronized(deliveryLock) {
            if (
                fingerprint ==
                lastDeliveredFingerprint
            ) {
                return
            }

            lastDeliveredFingerprint =
                fingerprint
        }

        AdbPairingCaptureBridge.publish(
            context =
                context,
            info =
                info
        )
    }

    /**
     * Clears duplicate-detection state before a new explicit pairing attempt.
     *
     * This does not arm or disarm Accessibility. The main-process coordinator
     * controls whether a broadcast receiver exists to consume captures.
     */
    fun reset() {
        synchronized(deliveryLock) {
            lastDeliveredFingerprint =
                null
        }
    }

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
