package neth.iecal.curbox.utils.privileged.adb

/**
 * Minimal view of an accessibility node, so the matching logic can run in
 * plain JVM tests without Android classes.
 */
interface UiNode {
    val text: String?
    val contentDescription: String?
    val viewId: String?
    val className: String?
    val isClickable: Boolean
    val isEnabled: Boolean
    val isScrollable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val childCount: Int
    val parent: UiNode?

    fun child(index: Int): UiNode?

    /** left, top, right, bottom in screen pixels. */
    fun bounds(): IntArray

    fun click(): Boolean
    fun scrollForward(): Boolean
    fun scrollDown(): Boolean
    fun scrollBackward(): Boolean

    /** ACTION_CLICK even when the node does not report itself clickable. */
    fun forceClick(): Boolean

    /** ACTION_SHOW_ON_SCREEN: scroll the node fully into view. */
    fun showOnScreen(): Boolean
}

/**
 * Pure matching helpers used by the Wireless Debugging navigator.
 */
object WirelessDebuggingNodeMatcher {

    const val MAX_DEPTH = 24

    val wirelessDebuggingLabels = listOf(
        "wireless debugging",
        "wireless adb debugging",
        "wireless debug",
        "wi-fi debugging",
        "wifi debugging",
        "تصحيح أخطاء لاسلكي",
        "تصحيح الأخطاء لاسلكيًا",
        "تصحيح الأخطاء لاسلكيا",
        "تصحيح الأخطاء اللاسلكي",
        "التصحيح اللاسلكي",
        "débogage sans fil",
        "depuración inalámbrica",
        "drahtloses debugging",
        "wlan-debugging",
        "depuração sem fio",
        "беспроводная отладка",
        "kablosuz hata ayıklama",
        "无线调试", "無線偵錯", "ワイヤレス デバッグ", "ワイヤレスデバッグ", "무선 디버깅",
        "debug wireless", "debugging nirkabel", "draadloze foutopsporing",
        "debugowanie bezprzewodowe", "वायरलेस डीबगिंग", "бездротове налагодження",
        "gỡ lỗi không dây", "اشکال‌زدایی بی‌سیم"
    )

    val pairByCodeLabels = listOf(
        "pair device with pairing code",
        "pair with pairing code",
        "إقران الجهاز باستخدام رمز الإقران",
        "إقران جهاز باستخدام رمز الإقران",
        "الإقران باستخدام رمز الإقران",
        "associer l'appareil avec un code",
        "vincular dispositivo con código",
        "gerät mit kopplungscode koppeln"
    )

    val allowLabels = listOf(
        "allow",
        "سماح",
        "السماح",
        "autoriser",
        "permitir",
        "zulassen"
    )

    /** AOSP preference key for "Pair device with pairing code". */
    const val PAIR_BY_CODE_KEY = "adb_pair_method_code_pref"

    /**
     * Converts Arabic-Indic and Persian digits to ASCII and removes direction
     * marks and non-breaking spaces, so a pairing code or IP:port shown in an
     * Arabic locale ("١٢٣ ٤٥٦") is still recognised.
     */
    fun normalizeDigits(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                '\u00A0', '\u202F', '\u2009' -> sb.append(' ')
                '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u2066', '\u2067', '\u2068', '\u2069', '\u061C' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun normalize(value: String?): String {
        if (value == null) return ""
        return value
            .replace('\u00A0', ' ')
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private fun labelsOf(node: UiNode): List<String> =
        listOf(
            normalize(node.text),
            normalize(node.contentDescription)
        ).filter { it.isNotEmpty() }

    /**
     * Finds a node by label. An exact label match wins over a node that only
     * contains the label, so a summary sentence never beats the row title.
     */
    fun findByLabels(
        root: UiNode?,
        labels: List<String>,
        resourceIdHint: String? = null
    ): UiNode? {
        if (root == null) return null
        val normalizedLabels = labels.map { normalize(it) }

        var contains: UiNode? = null
        var exact: UiNode? = null

        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || exact != null) return

            if (
                !resourceIdHint.isNullOrBlank() &&
                node.viewId?.contains(resourceIdHint, ignoreCase = true) == true
            ) {
                exact = node
                return
            }

            for (value in labelsOf(node)) {
                if (normalizedLabels.any { it == value }) {
                    exact = node
                    return
                }
                if (
                    contains == null &&
                    value.length <= 120 &&
                    normalizedLabels.any { value.contains(it) }
                ) {
                    contains = node
                }
            }

            for (i in 0 until node.childCount) {
                visit(node.child(i), depth + 1)
            }
        }

        visit(root, 0)
        return exact ?: contains
    }

    /** First enabled clickable node on the path from [node] to the root. */
    fun clickableAncestor(node: UiNode?): UiNode? {
        var current = node
        var depth = 0
        while (current != null && depth <= MAX_DEPTH) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Picks the list to scroll: the largest enabled scrollable node, with list
     * classes preferred over other scrollables of similar size.
     */
    fun bestScrollable(root: UiNode?): UiNode? {
        if (root == null) return null
        var best: UiNode? = null
        var bestScore = -1L

        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isScrollable && node.isEnabled) {
                val b = node.bounds()
                val area =
                    maxOf(0L, (b[2] - b[0]).toLong()) *
                        maxOf(0L, (b[3] - b[1]).toLong())
                val cls = node.className.orEmpty()
                val listBonus =
                    if (
                        cls.contains("RecyclerView") ||
                        cls.contains("ListView") ||
                        cls.contains("ScrollView")
                    ) 1L else 0L
                val score = area * 2 + listBonus
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }

        visit(root, 0)
        return best
    }

    /** Enabled switches that are currently off. */
    fun uncheckedSwitches(root: UiNode?): List<UiNode> {
        val result = mutableListOf<UiNode>()
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isCheckable && node.isEnabled && !node.isChecked) {
                result.add(node)
            }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return result
    }

    /** Every visible text, used for diagnostics and change detection. */
    fun allTexts(root: UiNode?): List<String> {
        val result = mutableListOf<String>()
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            node.text?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return result
    }

    fun signature(root: UiNode?): Int = allTexts(root).hashCode()

    private val developerListMarkers = listOf(
        "usb debugging",
        "stay awake",
        "bluetooth hci snoop",
        "oem unlocking",
        "تصحيح أخطاء usb",
        "تصحيح أخطاء الـusb",
        "تصحيح usb",
        "débogage usb",
        "depuración usb",
        "usb-debugging"
    )

    fun countCheckable(root: UiNode?): Int {
        var n = 0
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isCheckable) n++
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return n
    }

    /**
     * True on the Developer options list (many switches or a well-known
     * neighbour row), false on the Wireless debugging sub page that only has
     * its own switch and pairing rows.
     */
    fun isDeveloperList(root: UiNode?): Boolean {
        if (root == null) return false
        val texts = allTexts(root).map { normalize(it) }
        if (texts.any { t -> developerListMarkers.any { t == normalize(it) } }) return true
        return countCheckable(root) >= 3
    }

    /** True when the label sits on a node that is exactly an allow button. */
    fun findAllowButton(root: UiNode?): UiNode? {
        if (root == null) return null
        var found: UiNode? = null
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || found != null) return
            val cls = node.className.orEmpty()
            val value = normalize(node.text)
            if (
                cls.contains("Button") &&
                node.isEnabled &&
                allowLabels.any { normalize(it) == value }
            ) {
                found = node
                return
            }
            // Language independent: the positive button (android:id/button1)
            // of a dialog that carries the "always allow on this network"
            // checkbox.
            if (
                node.isEnabled &&
                node.viewId?.endsWith("id/button1") == true &&
                hasCheckBox(root)
            ) {
                found = node
                return
            }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return found
    }

    private fun hasCheckBox(root: UiNode?): Boolean {
        var any = false
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || any) return
            if (node.className.orEmpty().contains("CheckBox")) {
                any = true
                return
            }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return any
    }

    /** True when the window is a Settings preference list (has a list id). */
    fun hasSettingsListId(root: UiNode?): Boolean {
        var any = false
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || any) return
            val id = node.viewId.orEmpty()
            if (id.endsWith("id/recycler_view") || id.endsWith("id/list") || id.endsWith("id/main_content")) {
                any = true
                return
            }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return any
    }

    fun countClickableRows(root: UiNode?): Int = clickableTextRows(root).size

    /** Clickable, non-checkable rows that carry text but no IP:port, in screen order. */
    fun clickableTextRows(root: UiNode?): List<UiNode> {
        val rows = mutableListOf<UiNode>()
        fun visit(node: UiNode?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isClickable && node.isEnabled && !node.isCheckable) {
                val texts = mutableListOf<String>()
                fun collect(n: UiNode?, d: Int) {
                    if (n == null || d > 6) return
                    n.text?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                    for (i in 0 until n.childCount) collect(n.child(i), d + 1)
                }
                collect(node, 0)
                val hasPort = texts.any { Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(normalizeDigits(it)) }
                if (texts.isNotEmpty() && !hasPort) rows.add(node)
                return
            }
            for (i in 0 until node.childCount) visit(node.child(i), depth + 1)
        }
        visit(root, 0)
        return rows
    }
}
