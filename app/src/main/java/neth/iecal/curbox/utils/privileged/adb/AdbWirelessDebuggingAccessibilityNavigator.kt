package neth.iecal.curbox.utils.privileged.adb

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Navigates Android Settings toward the Wireless Debugging pairing dialog
 * during an explicit CurBox Direct Access setup session.
 *
 * The navigator is process-local to CurBox's Accessibility service.
 *
 * It never operates unless explicitly armed and it only inspects nodes from
 * Android Settings.
 */
object AdbWirelessDebuggingAccessibilityNavigator {

    private const val MAX_TREE_DEPTH =
        18

    private val armed =
        AtomicBoolean(false)

    private val allowedSettingsPackages =
        setOf(
            "com.android.settings"
        )

    /*
     * These labels describe the standard AOSP Settings UI.
     *
     * Matching is intentionally case-insensitive and tolerant of surrounding
     * text, but confined to Android Settings and an explicitly armed session.
     */
    private val wirelessDebuggingLabels =
        listOf(
            "wireless debugging"
        )

    private val pairByCodeLabels =
        listOf(
            "pair device with pairing code"
        )

    fun arm() {
        armed.set(
            true
        )
    }

    fun disarm() {
        armed.set(
            false
        )
    }

    fun isArmed(): Boolean {
        return armed.get()
    }

    /**
     * Attempts one bounded navigation pass.
     *
     * Returns true when CurBox performed a click during this pass.
     */
    fun navigate(
        packageName: CharSequence?,
        root: AccessibilityNodeInfo?
    ): Boolean {
        if (!armed.get()) {
            return false
        }

        val currentPackage =
            packageName
                ?.toString()
                ?: return false

        if (
            currentPackage !in
            allowedSettingsPackages
        ) {
            return false
        }

        val currentRoot =
            root
                ?: return false

        /*
         * Prefer the pairing-code action when it is already visible.
         *
         * If it is not visible, look for Wireless Debugging itself.
         */
        if (
            clickNodeMatching(
                root =
                    currentRoot,
                labels =
                    pairByCodeLabels
            )
        ) {
            /*
             * The pairing dialog should now be handled by
             * AdbPairingAccessibilityExtractor.
             *
             * Stop navigation so repeated Accessibility events cannot click
             * the pairing action again.
             */
            disarm()

            return true
        }

        return clickNodeMatching(
            root =
                currentRoot,
            labels =
                wirelessDebuggingLabels
        )
    }

    private fun clickNodeMatching(
        root: AccessibilityNodeInfo,
        labels: List<String>
    ): Boolean {
        val node =
            findMatchingNode(
                node =
                    root,
                depth =
                    0,
                labels =
                    labels
            )
                ?: return false

        return performClick(
            node
        )
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        if (
            node == null ||
            depth >
            MAX_TREE_DEPTH ||
            !armed.get()
        ) {
            return null
        }

        val text =
            node.text
                ?.toString()
                ?.trim()

        val contentDescription =
            node.contentDescription
                ?.toString()
                ?.trim()

        if (
            matchesAny(
                text =
                    text,
                labels =
                    labels
            ) ||
            matchesAny(
                text =
                    contentDescription,
                labels =
                    labels
            )
        ) {
            return node
        }

        for (
            index in
            0 until node.childCount
        ) {
            val result =
                findMatchingNode(
                    node =
                        node.getChild(
                            index
                        ),
                    depth =
                        depth + 1,
                    labels =
                        labels
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun matchesAny(
        text: String?,
        labels: List<String>
    ): Boolean {
        if (text.isNullOrBlank()) {
            return false
        }

        return labels.any {
            label ->
            text.contains(
                other =
                    label,
                ignoreCase =
                    true
            )
        }
    }

    /**
     * Some Settings rows expose ACTION_CLICK on the parent container instead
     * of the TextView containing the label, so walk upward until a clickable
     * node is found.
     */
    private fun performClick(
        startingNode: AccessibilityNodeInfo
    ): Boolean {
        var current:
            AccessibilityNodeInfo? =
            startingNode

        var depth =
            0

        while (
            current != null &&
            depth <=
            MAX_TREE_DEPTH
        ) {
            if (
                current.isClickable &&
                current.isEnabled
            ) {
                return current.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            }

            current =
                current.parent

            depth +=
                1
        }

        return false
    }
}
