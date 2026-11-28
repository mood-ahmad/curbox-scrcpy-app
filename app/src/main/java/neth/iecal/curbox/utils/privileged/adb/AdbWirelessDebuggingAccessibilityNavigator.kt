package neth.iecal.curbox.utils.privileged.adb

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Navigates Android Settings toward the Wireless Debugging pairing dialog
 * during an explicit CurBox Direct Access setup session.
 *
 * Navigation is disabled by default, restricted to Android Settings, and
 * enabled only during an explicitly armed Direct Access pairing session.
 *
 * Passes are driven both by Accessibility events and by a timer owned by the
 * service, because a static Settings page may not emit another event.
 */
object AdbWirelessDebuggingAccessibilityNavigator {

    private const val TAG = "DirectADB-Nav"

    private const val MIN_STEP_INTERVAL_MS = 450L
    private const val MAX_SCROLLS = 40
    private const val STALL_RESET_MS = 12_000L
    private const val MAX_RECOVERIES = 6
    private val DEVELOPER_OPTIONS_LABELS = listOf(
        "developer options", "خيارات المطور", "خيارات المطوّر", "options pour les développeurs",
        "opciones de desarrollador", "entwickleroptionen", "opções do desenvolvedor",
        "для разработчиков", "geliştirici seçenekleri", "opzioni sviluppatore",
        "开发者选项", "開發人員選項", "開発者向けオプション", "개발자 옵션", "opsi developer",
        "ontwikkelaarsopties", "opcje programisty", "डेवलपर विकल्प", "параметри розробника",
        "tùy chọn nhà phát triển", "گزینه‌های برنامه‌نویس"
    )
    private const val HINT_AFTER_MS = 12_000L

    private val armed = AtomicBoolean(false)

    private val allowedSettingsPackages = setOf("com.android.settings")

    /** Gestures, toasts, highlight and logging supplied by the service. */
    interface Host {
        fun tap(x: Int, y: Int): Boolean
        fun swipeUp(): Boolean
        fun toast(message: String)
        fun highlight(left: Int, top: Int, right: Int, bottom: Int)
        fun log(message: String)

        /** Reopens Android's Developer options when the user wandered off. */
        fun openDeveloperOptions(): Boolean = false

        /** Direct jump to the Wireless debugging page (no labels needed). */
        fun openWirelessDebugging(): Boolean = false

        /** Enable-only mode: Wireless debugging is confirmed on. */
        fun wirelessEnabled() {}
    }

    @Volatile
    var host: Host? = null

    @Volatile private var armedAt = 0L
    @Volatile private var lastStepAt = 0L
    @Volatile private var scrollCount = 0
    @Volatile private var stallCount = 0
    @Volatile private var lastSignature = 0
    @Volatile private var switchClicks = 0
    @Volatile private var hintCount = 0
    @Volatile private var lastHintAt = 0L
    @Volatile private var lastLoggedState = ""
    @Volatile private var passCount = 0
    @Volatile private var reversed = false
    @Volatile private var subPageClicks = 0
    @Volatile private var lastSubClickAt = 0L
    @Volatile private var screenClass = ""
    @Volatile private var lastProgressAt = 0L
    @Volatile private var recoveries = 0
    @Volatile private var lastRecoveryAt = 0L
    @Volatile private var pairClickedAt = 0L
    @Volatile var enableOnly = false
        private set
    private val codeRegex = Regex("^\\d{3}\\s?\\d{3}$")

    /** Forget per-screen attempt limits so no screen is ever a dead end. */
    private fun resetAttempts(reason: String) {
        scrollCount = 0
        stallCount = 0
        lastSignature = 0
        rowAttempts = 0
        structuralPairClicks = 0
        subPageClicks = 0
        reversed = false
        lastSubClickAt = 0L
        note("attempts reset: $reason")
    }

    /**
     * The screen the user is on now, decided fresh on every pass so out of
     * order user actions (switching things on, opening other pages) are just
     * another starting point.
     */
    private fun classify(root: UiNode): String {
        val matcher = WirelessDebuggingNodeMatcher
        if (matcher.findByLabels(root, matcher.pairByCodeLabels, matcher.PAIR_BY_CODE_KEY) != null) return "PAIR"
        if (matcher.findAllowButton(root) != null) return "ALLOW"
        if (matcher.allTexts(root).any { codeRegex.matches(matcher.normalizeDigits(it).trim()) }) return "CODE"
        val wd = matcher.findByLabels(root, matcher.wirelessDebuggingLabels)
        if (wd != null) return if (matcher.isDeveloperList(root)) "LIST_ROW" else "SUB"
        // Language independent: a page with exactly one switch that is not
        // the Developer options list is the Wireless debugging page.
        if (!matcher.isDeveloperList(root) && matcher.countCheckable(root) == 1 &&
            matcher.countClickableRows(root) >= 1 && matcher.hasSettingsListId(root)
        ) return "SUB"
        return "OTHER"
    }

    /** Navigate only far enough to make sure Wireless debugging is on. */
    fun armEnableOnly() {
        arm()
        enableOnly = true
        note("armed (enable only)")
    }

    fun arm() {
        enableOnly = false
        scrollCount = 0
        stallCount = 0
        lastSignature = 0
        switchClicks = 0
        rowAttempts = 0
        hintCount = 0
        lastHintAt = 0L
        lastLoggedState = ""
        passCount = 0
        reversed = false
        subPageClicks = 0
        lastSubClickAt = 0L
        lastStepAt = 0L
        screenClass = ""
        pairClickedAt = 0L
        recoveries = 0
        lastRecoveryAt = 0L
        armedAt = System.currentTimeMillis()
        lastProgressAt = armedAt
        armed.set(true)
        note("armed")
    }

    fun disarm() {
        if (armed.getAndSet(false)) {
            note("disarmed")
        }
    }

    fun isArmed(): Boolean = armed.get()

    private fun note(message: String) {
        Log.i(TAG, message)
        try {
            host?.log(message)
        } catch (_: Throwable) {
        }
    }

    /**
     * One bounded navigation pass. Returns true when an action was performed.
     */
    fun navigate(
        packageName: CharSequence?,
        root: AccessibilityNodeInfo?
    ): Boolean {
        if (!armed.get()) return false

        val currentPackage = packageName?.toString() ?: return false

        if (currentPackage !in allowedSettingsPackages) {
            logOnce("ignored package=$currentPackage")
            return false
        }

        val currentRoot = root ?: run {
            logOnce("no root window")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastStepAt < MIN_STEP_INTERVAL_MS) return false
        lastStepAt = now

        return try {
            step(AccessibilityUiNode(currentRoot), now)
        } catch (t: Throwable) {
            note("step failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun recoverToDeveloperOptions(root: UiNode, now: Long) {
        if (now - lastRecoveryAt < 6_000L || recoveries >= MAX_RECOVERIES) return
        lastRecoveryAt = now
        recoveries++
        val ok = try {
            (recoveries % 2 == 1 && host?.openWirelessDebugging() == true) ||
                host?.openDeveloperOptions() == true
        } catch (_: Throwable) { false }
        note("recovery #$recoveries: reopened Developer options ok=$ok")
        resetAttempts("recovery")
    }

    private fun logOnce(state: String) {
        if (state != lastLoggedState) {
            lastLoggedState = state
            note(state)
        }
    }

    private fun step(root: UiNode, now: Long): Boolean {
        val matcher = WirelessDebuggingNodeMatcher
        passCount++
        if (passCount <= 3 || passCount % 8 == 0) {
            note(
                "pass #$passCount list=${matcher.isDeveloperList(root)} " +
                    "switches=${matcher.countCheckable(root)} texts=" +
                    matcher.allTexts(root).take(25)
            )
        }

        // Every pass starts by re-detecting the screen. A new screen or a
        // long stall resets all attempt limits, so nothing stays stuck.
        val cls = classify(root)
        if (cls != screenClass) {
            note("screen $screenClass -> $cls")
            screenClass = cls
            lastProgressAt = now
            resetAttempts("screen changed")
        } else if (now - lastProgressAt > STALL_RESET_MS) {
            lastProgressAt = now
            resetAttempts("no progress for ${STALL_RESET_MS / 1000}s")
            if (cls == "OTHER" || cls == "PAIR") recoverToDeveloperOptions(root, now)
        }

        // 1. Pair with pairing code: the goal of navigation.
        val pairNode = matcher.findByLabels(
            root,
            matcher.pairByCodeLabels,
            matcher.PAIR_BY_CODE_KEY
        )
        if (enableOnly) {
            // Already on (pairing row, code dialog, or allow prompt visible)?
            if (cls == "PAIR" || cls == "CODE") {
                note("enable-only: Wireless debugging already on")
                host?.wirelessEnabled()
                return true
            }
        }
        if (cls == "CODE") {
            // The pairing dialog with its code is open: the extractor reads it.
            lastProgressAt = now
            return false
        }
        if (pairNode != null && now - pairClickedAt > 6_000L &&
            activate(pairNode, "pair-by-code")
        ) {
            // Stay armed until the code is delivered, so a dismissed dialog
            // is simply opened again.
            pairClickedAt = now
            return true
        }

        // 2. "Allow wireless debugging on this network?" prompt.
        val allow = matcher.findAllowButton(root)
        if (allow != null && allow.click()) {
            note("clicked Allow button")
            return true
        }

        // 3. The Wireless debugging row (Developer options). Once it is
        // visible, never scroll again: keep trying to open it.
        val wdNode = matcher.findByLabels(root, matcher.wirelessDebuggingLabels)
        if (wdNode == null && cls == "SUB") {
            if (enableOnSubPage(root, now)) return true
            if (structuralPairRow(root, now)) return true
        }
        if (wdNode != null) {
            if (matcher.isDeveloperList(root)) {
                return openWirelessDebugging(root, wdNode)
            }
            // Sub page with Wireless debugging off: switch it on, then wait
            // for the pairing row (or the Allow prompt) to appear.
            if (enableOnSubPage(root, now)) return true
        }

        // 4. Wrong page (Settings home, System, About...): go to Developer options.
        val devEntry = matcher.findByLabels(root, DEVELOPER_OPTIONS_LABELS)
        if (devEntry != null && !matcher.isDeveloperList(root)) {
            val own = matcher.normalize(devEntry.text)
            if (DEVELOPER_OPTIONS_LABELS.any { matcher.normalize(it) == own } &&
                activate(devEntry, "developer-options-entry")
            ) {
                return true
            }
        }

        // 5. Not visible yet: scroll the list.
        val scrolled = scrollStep(root, now)
        if (!scrolled && (scrollCount >= MAX_SCROLLS || stallCount >= 3)) {
            recoverToDeveloperOptions(root, now)
        }
        return scrolled
    }

    @Volatile private var rowAttempts = 0
    @Volatile private var structuralPairClicks = 0

    /**
     * Label-free fallback on the Wireless debugging page (switch on): after a
     * short stall, try the clickable text rows in order, each a bounded number
     * of times. Opening the wrong row is recovered by the stall logic.
     */
    private fun structuralPairRow(root: UiNode, now: Long): Boolean {
        if (now - lastProgressAt < 8_000L || structuralPairClicks >= 3) return false
        if (now - pairClickedAt < 6_000L) return false
        val rows = WirelessDebuggingNodeMatcher.clickableTextRows(root)
        val target = rows.getOrNull(structuralPairClicks) ?: return false
        structuralPairClicks++
        pairClickedAt = now
        note("structural pair row attempt #$structuralPairClicks")
        return target.click()
    }

    private fun enableOnSubPage(root: UiNode, now: Long): Boolean {
        val off = WirelessDebuggingNodeMatcher.uncheckedSwitches(root)
        if (off.isEmpty()) {
            logOnce("sub page: switch already on, waiting for pairing row")
            if (enableOnly) {
                host?.wirelessEnabled()
                return true
            }
            return false
        }
        if (subPageClicks >= 4 || now - lastSubClickAt < 2500L) return false
        subPageClicks++
        lastSubClickAt = now
        val target = off[0]
        val ok = target.forceClick() ||
            WirelessDebuggingNodeMatcher.clickableAncestor(target)?.click() == true ||
            run {
                val b = target.bounds()
                b[2] > b[0] && b[3] > b[1] &&
                    host?.tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) == true
            }
        note("sub page: switch was off, turning on attempt=$subPageClicks ok=$ok")
        return ok
    }

    private fun describe(node: UiNode): String {
        val b = node.bounds()
        return "text=${node.text} desc=${node.contentDescription} id=${node.viewId} " +
            "class=${node.className} clickable=${node.isClickable} " +
            "enabled=${node.isEnabled} bounds=${b.joinToString(",")}"
    }

    private fun openWirelessDebugging(root: UiNode, wdNode: UiNode): Boolean {
        val matcher = WirelessDebuggingNodeMatcher
        val attempt = rowAttempts++

        if (attempt == 0) {
            val chain = StringBuilder()
            var current: UiNode? = wdNode
            var depth = 0
            while (current != null && depth < 8) {
                chain.append("[").append(describe(current)).append("] ")
                current = current.parent
                depth++
            }
            note("Wireless debugging row found; chain: $chain")
            note("all texts: " + matcher.allTexts(root).take(60))
        }

        if (attempt >= 12) {
            logOnce("row attempts exhausted")
            val b = (matcher.clickableAncestor(wdNode) ?: wdNode).bounds()
            host?.highlight(b[0], b[1], b[2], b[3])
            return false
        }

        val clickable = matcher.clickableAncestor(wdNode)

        wdNode.showOnScreen()

        val ok = when (attempt % 3) {
            0 -> {
                // Every clickable ancestor, nearest first.
                var current: UiNode? = wdNode
                var clicked = false
                var depth = 0
                while (current != null && depth <= WirelessDebuggingNodeMatcher.MAX_DEPTH) {
                    if (current.click()) {
                        clicked = true
                        break
                    }
                    current = current.parent
                    depth++
                }
                note("row attempt $attempt ancestor-click ok=$clicked")
                clicked
            }
            1 -> {
                val b = (clickable ?: wdNode).bounds()
                val tapped =
                    b[2] > b[0] && b[3] > b[1] &&
                        host?.tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) == true
                note("row attempt $attempt gesture-tap ok=$tapped bounds=${b.joinToString(",")}")
                tapped
            }
            else -> {
                var current: UiNode? = wdNode
                var forced = false
                var depth = 0
                while (current != null && depth <= 6 && !forced) {
                    forced = current.forceClick()
                    current = current.parent
                    depth++
                }
                note("row attempt $attempt force-click ok=$forced")
                forced
            }
        }

        if (attempt == 5 || attempt == 11) {
            host?.toast("Tap \"Wireless debugging\"")
            val b = (clickable ?: wdNode).bounds()
            host?.highlight(b[0], b[1], b[2], b[3])
        }
        return ok
    }

    /** Click strategies: node click, ancestor click, then a gesture tap. */
    private fun activate(node: UiNode, what: String): Boolean {
        if (node.click()) {
            note("clicked $what (node)")
            return true
        }

        val ancestor = WirelessDebuggingNodeMatcher.clickableAncestor(node)
        if (ancestor != null && ancestor.click()) {
            note("clicked $what (ancestor)")
            return true
        }

        val b = (ancestor ?: node).bounds()
        if (b[2] > b[0] && b[3] > b[1]) {
            val tapped = host?.tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) == true
            note("tapped $what by gesture ok=$tapped bounds=${b.joinToString(",")}")
            if (tapped) return true
            host?.highlight(b[0], b[1], b[2], b[3])
        }
        return false
    }

    private fun scrollStep(root: UiNode, now: Long): Boolean {
        val signature = WirelessDebuggingNodeMatcher.signature(root)
        stallCount = if (signature == lastSignature && scrollCount > 0) stallCount + 1 else 0
        lastSignature = signature

        if (now - armedAt > HINT_AFTER_MS && now - lastHintAt > HINT_AFTER_MS && hintCount < 3) {
            hintCount++
            lastHintAt = now
            host?.toast("Scroll down and tap \"Wireless debugging\"")
        }

        if (stallCount >= 3 && !reversed) {
            reversed = true
            stallCount = 0
            scrollCount = 0
            note("end of list reached without the row, scrolling back up")
        }
        if (stallCount >= 3 && reversed) {
            logOnce("list exhausted in both directions without the row")
            return false
        }

        if (scrollCount >= MAX_SCROLLS) {
            logOnce("scroll limit reached")
            return false
        }

        scrollCount++
        val scrollable = WirelessDebuggingNodeMatcher.bestScrollable(root)
        val strategy = if (scrollable == null) 2 else stallCount % 3

        val ok = if (reversed) {
            scrollable?.scrollBackward() == true
        } else when (strategy) {
            0 -> scrollable?.scrollForward() == true
            1 -> scrollable?.scrollDown() == true
            else -> host?.swipeUp() == true
        }

        note(
            "scroll #$scrollCount strategy=$strategy stall=$stallCount ok=$ok " +
                "scrollable=${scrollable?.className} texts=" +
                WirelessDebuggingNodeMatcher.allTexts(root).take(12)
        )
        return ok
    }

    /** Adapter from AccessibilityNodeInfo to the JVM-testable UiNode. */
    internal class AccessibilityUiNode(
        private val node: AccessibilityNodeInfo
    ) : UiNode {
        override val text: String? get() = node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val viewId: String? get() = node.viewIdResourceName
        override val className: String? get() = node.className?.toString()
        override val isClickable: Boolean get() = node.isClickable
        override val isEnabled: Boolean get() = node.isEnabled
        override val isScrollable: Boolean get() = node.isScrollable
        override val isCheckable: Boolean get() = node.isCheckable
        override val isChecked: Boolean get() = node.isChecked
        override val childCount: Int get() = node.childCount
        override val parent: UiNode?
            get() = node.parent?.let { AccessibilityUiNode(it) }

        override fun child(index: Int): UiNode? =
            node.getChild(index)?.let { AccessibilityUiNode(it) }

        override fun bounds(): IntArray {
            val r = Rect()
            node.getBoundsInScreen(r)
            return intArrayOf(r.left, r.top, r.right, r.bottom)
        }

        override fun click(): Boolean =
            node.isClickable && node.isEnabled &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        override fun scrollForward(): Boolean =
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

        override fun forceClick(): Boolean =
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        override fun showOnScreen(): Boolean =
            node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            )

        override fun scrollBackward(): Boolean =
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

        override fun scrollDown(): Boolean =
            node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            )
    }
}
