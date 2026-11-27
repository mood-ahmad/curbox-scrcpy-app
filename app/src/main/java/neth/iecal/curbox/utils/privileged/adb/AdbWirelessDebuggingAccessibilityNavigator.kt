package neth.iecal.curbox.utils.privileged.adb

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Navigates Android Settings toward the Wireless Debugging pairing dialog
 * during an explicit CurBox Direct Access setup session.
 *
 * The navigator is process-local to CurBox's Accessibility service.
 *
 * Navigation is disabled by default, restricted to Android Settings, and
 * enabled only during an explicitly armed Direct Access pairing session.
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

    /**
     * AOSP preference key used by WirelessDebuggingFragment for the
     * "Pair device with pairing code" preference.
     *
     * This is treated only as an additional signal. Accessibility does not
     * guarantee that a Preference key becomes the node's view resource ID.
     */
    private const val AOSP_PAIR_BY_CODE_KEY =
        "adb_pair_method_code_pref"

    /*
     * Text matching remains the compatibility fallback because Settings
     * implementations and Accessibility trees differ between Android builds.
     *
     * Include the common English and Arabic labels used for the two actions.
     */
    private val wirelessDebuggingLabels =
        listOf(
            "wireless debugging",
            "تصحيح الأخطاء لاسلكيًا",
            "تصحيح الأخطاء اللاسلكي",
            "التصحيح اللاسلكي"
        )

    private val pairByCodeLabels =
        listOf(
            "pair device with pairing code",
            "إقران الجهاز باستخدام رمز الإقران",
            "إقران جهاز باستخدام رمز الإقران",
            "الإقران باستخدام رمز الإقران"
        )

    private const val MAX_AUTO_SCROLLS =
        25

    private const val SCROLL_INTERVAL_MS =
        450L

    @Volatile
    private var scrollCount =
        0

    @Volatile
    private var lastScrollAt =
        0L

    fun arm() {
        scrollCount =
            0

        lastScrollAt =
            0L

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
     * Pair-by-code is preferred over the Wireless Debugging entry because the
     * user may already be inside Wireless Debugging when an event arrives.
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

        val pairByCodeNode =
            findNode(
                node =
                    currentRoot,
                depth =
                    0,
                labels =
                    pairByCodeLabels,
                resourceIdHint =
                    AOSP_PAIR_BY_CODE_KEY
            )

        if (
            pairByCodeNode != null &&
            performClick(
                pairByCodeNode
            )
        ) {
            /*
             * The pairing dialog is now expected to appear. Navigation has
             * finished; AdbPairingAccessibilityExtractor remains armed and
             * owns capture of the temporary code.
             */
            disarm()

            return true
        }

        val wirelessDebuggingNode =
            findNode(
                node =
                    currentRoot,
                depth =
                    0,
                labels =
                    wirelessDebuggingLabels,
                resourceIdHint =
                    null
            )
                ?: return scrollTowardWirelessDebugging(
                    currentRoot
                )

        return performClick(
            wirelessDebuggingNode
        )
    }

    /**
     * The Wireless debugging row is usually below the first screen of
     * Developer options. Scroll the list forward, a bounded number of times,
     * until the row becomes visible. Each scroll triggers a new Accessibility
     * event that runs another navigation pass.
     */
    private fun scrollTowardWirelessDebugging(
        root: AccessibilityNodeInfo
    ): Boolean {
        val now =
            System.currentTimeMillis()

        if (
            scrollCount >= MAX_AUTO_SCROLLS ||
            now - lastScrollAt < SCROLL_INTERVAL_MS
        ) {
            return false
        }

        val scrollable =
            findScrollable(
                root,
                0
            )
                ?: return false

        lastScrollAt =
            now

        scrollCount +=
            1

        return scrollable.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
    }

    private fun findScrollable(
        node: AccessibilityNodeInfo?,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (
            node == null ||
            depth > MAX_TREE_DEPTH
        ) {
            return null
        }

        if (
            node.isScrollable &&
            node.isEnabled
        ) {
            return node
        }

        for (
            index in
            0 until node.childCount
        ) {
            val result =
                findScrollable(
                    node.getChild(
                        index
                    ),
                    depth + 1
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        labels: List<String>,
        resourceIdHint: String?
    ): AccessibilityNodeInfo? {
        if (
            node == null ||
            depth > MAX_TREE_DEPTH ||
            !armed.get()
        ) {
            return null
        }

        val resourceId =
            node.viewIdResourceName
                ?.trim()

        if (
            !resourceIdHint.isNullOrBlank() &&
            resourceId
                ?.contains(
                    resourceIdHint,
                    ignoreCase = true
                ) == true
        ) {
            return node
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
                findNode(
                    node =
                        node.getChild(
                            index
                        ),
                    depth =
                        depth + 1,
                    labels =
                        labels,
                    resourceIdHint =
                        resourceIdHint
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

        return labels.any { label ->
            text.contains(
                other =
                    label,
                ignoreCase =
                    true
            )
        }
    }

    /**
     * Preference rows frequently expose ACTION_CLICK on an ancestor container
     * rather than on the TextView containing the label.
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
            depth <= MAX_TREE_DEPTH
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
