package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.io.File

/**
 * Best-effort automation of Samsung One UI's App info > Battery > Unrestricted
 * choice, which has no shell equivalent. The main process asks by writing a
 * small request file (the accessibility service lives in another process) and
 * opening CurBox's App info page; the service then taps through it.
 *
 * Only exact label matches are clicked, nothing is scrolled blindly more than
 * a few times, and the whole attempt stops after a short time.
 */
object BatteryUnrestrictedNavigator {

    private const val FILE_NAME = "battery_unrestricted_request"
    private const val MAX_AGE_MS = 2 * 60 * 1000L
    private const val MAX_PASSES = 40
    private const val MIN_STEP_MS = 600L

    val unrestrictedLabels = listOf(
        "unrestricted", "غير مقيد", "غير مقيّد", "sans restriction",
        "sin restricciones", "uneingeschränkt", "sem restrições", "неограниченно"
    )

    val batteryRowLabels = listOf(
        "battery", "البطارية", "batterie", "batería", "akku", "bateria", "батарея"
    )

    @Volatile private var passes = 0
    @Volatile private var lastStepAt = 0L
    @Volatile private var swipes = 0

    fun request(context: Context, nowMs: Long = System.currentTimeMillis()) {
        passes = 0
        swipes = 0
        try {
            File(context.applicationContext.filesDir, FILE_NAME).writeText(nowMs.toString())
        } catch (_: Throwable) {
        }
    }

    fun pending(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) return false
            val at = file.readText().trim().toLongOrNull() ?: 0L
            if (nowMs - at in 0..MAX_AGE_MS) true else {
                file.delete()
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun finish(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    /** True once the "Unrestricted" choice was selected or was already on. */
    fun step(
        root: UiNode?,
        host: AdbWirelessDebuggingAccessibilityNavigator.Host?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (root == null) return false
        if (nowMs - lastStepAt < MIN_STEP_MS) return false
        lastStepAt = nowMs
        passes++
        val matcher = WirelessDebuggingNodeMatcher

        val unrestricted = exact(root, unrestrictedLabels)
        if (unrestricted != null) {
            var node: UiNode? = unrestricted
            var depth = 0
            var alreadyOn = false
            while (node != null && depth < 6) {
                if (node.isCheckable && node.isChecked) alreadyOn = true
                node = node.parent
                depth++
            }
            if (alreadyOn) {
                host?.log("battery: Unrestricted already selected")
                return true
            }
            val clicked = click(unrestricted, host)
            host?.log("battery: tapped Unrestricted ok=$clicked")
            return clicked
        }

        val batteryRow = exact(root, batteryRowLabels)
        if (batteryRow != null) {
            val clicked = click(batteryRow, host)
            host?.log("battery: tapped Battery row ok=$clicked")
            return false
        }

        if (swipes < 6) {
            swipes++
            val ok = host?.swipeUp() == true
            host?.log("battery: scrolling swipe=$swipes ok=$ok texts=${matcher.allTexts(root).take(12)}")
        }
        return false
    }

    fun exhausted(): Boolean = passes >= MAX_PASSES

    private fun exact(root: UiNode, labels: List<String>): UiNode? {
        val node = WirelessDebuggingNodeMatcher.findByLabels(root, labels) ?: return null
        val own = WirelessDebuggingNodeMatcher.normalize(node.text)
        val desc = WirelessDebuggingNodeMatcher.normalize(node.contentDescription)
        val normalized = labels.map { WirelessDebuggingNodeMatcher.normalize(it) }
        return if (own in normalized || desc in normalized) node else null
    }

    private fun click(
        node: UiNode,
        host: AdbWirelessDebuggingAccessibilityNavigator.Host?
    ): Boolean {
        if (node.click()) return true
        val ancestor = WirelessDebuggingNodeMatcher.clickableAncestor(node)
        if (ancestor != null && ancestor.click()) return true
        val b = (ancestor ?: node).bounds()
        return b[2] > b[0] && b[3] > b[1] &&
            host?.tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) == true
    }
}
