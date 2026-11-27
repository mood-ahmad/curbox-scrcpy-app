package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts Wireless ADB pairing information from Android Settings'
 * Accessibility tree.
 *
 * This object runs inside the Accessibility-service process.
 *
 * Extraction is disabled by default. AppBlockerService explicitly arms it
 * after receiving an ARM_CAPTURE request for a specific pairing session.
 *
 * Every captured result is forwarded together with that session ID so the
 * main process can reject stale results from an older pairing attempt.
 */
object AdbPairingAccessibilityExtractor {

    private const val MAX_TREE_DEPTH =
        15

    private val allowedSettingsPackages =
        setOf(
            "com.android.settings"
        )

    /**
     * Accept:
     *
     * 123456
     * 123 456
     */
    private val pairingCodeRegex =
        Regex(
            """^\d{3}\s?\d{3}$"""
        )

    private val ipv4AndPortRegex =
        Regex(
            """(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})"""
        )

    /**
     * Process-local capture state.
     */
    private val armed =
        AtomicBoolean(
            false
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
     * Enables extraction for the current explicit automatic-pairing session.
     *
     * Session ownership itself lives in AppBlockerService. The extractor only
     * controls whether tree inspection is currently allowed.
     */
    fun arm() {
        synchronized(deliveryLock) {
            lastDeliveredFingerprint =
                null

            armed.set(
                true
            )
        }
    }

    /**
     * Stops extraction.
     */
    fun disarm() {
        synchronized(deliveryLock) {
            armed.set(
                false
            )

            lastDeliveredFingerprint =
                null
        }
    }

    fun isArmed(): Boolean {
        return armed.get()
    }

    /**
     * Processes one Accessibility event belonging to [sessionId].
     *
     * Events are ignored unless extraction is armed. Even while armed, only
     * Android Settings is inspected.
     */
    fun onAccessibilityEvent(
        context: Context,
        sessionId: String,
        event: AccessibilityEvent,
        activeWindowRoot: AccessibilityNodeInfo?
    ) {
        if (
            !armed.get() ||
            sessionId.isBlank()
        ) {
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
            context =
                context.applicationContext,
            sessionId =
                sessionId,
            root =
                root
        )
    }

    private fun scan(
        context: Context,
        sessionId: String,
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
            node =
                root,
            depth =
                0
        ) { text ->

            if (!armed.get()) {
                return@walk true
            }

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

        if (!armed.get()) {
            return
        }

        val detectedCode =
            code
                ?: return

        deliver(
            context =
                context,
            sessionId =
                sessionId,
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
     * before forwarding the result.
     *
     * The session ID is included in the fingerprint so a legitimate new
     * pairing attempt can never be suppressed by the previous attempt.
     */
    private fun deliver(
        context: Context,
        sessionId: String,
        info: PairingInfo
    ) {
        if (
            !armed.get() ||
            sessionId.isBlank()
        ) {
            return
        }

        val fingerprint =
            buildString {
                append(
                    sessionId
                )

                append(
                    ':'
                )

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
            if (!armed.get()) {
                return
            }

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
            sessionId =
                sessionId,
            info =
                info
        )

        try {
            onCodeDelivered?.invoke()
        } catch (_: Throwable) {
        }
    }

    /** Called after a pairing code has been forwarded to the main process. */
    @Volatile
    var onCodeDelivered: (() -> Unit)? = null

    /**
     * Clears duplicate tracking without changing capture state.
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

        if (!armed.get()) {
            return true
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
