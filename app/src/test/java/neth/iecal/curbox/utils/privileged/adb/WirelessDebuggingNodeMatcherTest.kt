package neth.iecal.curbox.utils.privileged.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNode(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val viewId: String? = null,
    override val className: String? = null,
    override val isClickable: Boolean = false,
    override val isEnabled: Boolean = true,
    override val isScrollable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isChecked: Boolean = false,
    private val box: IntArray = intArrayOf(0, 0, 100, 100),
    val children: MutableList<FakeNode> = mutableListOf()
) : UiNode {
    override var parent: UiNode? = null
    override val childCount: Int get() = children.size
    var clicks = 0

    override fun child(index: Int): UiNode? = children.getOrNull(index)
    override fun bounds(): IntArray = box
    override fun click(): Boolean {
        clicks++
        return isClickable && isEnabled
    }
    override fun scrollForward(): Boolean = true
    override fun scrollDown(): Boolean = true

    fun add(vararg nodes: FakeNode): FakeNode {
        nodes.forEach {
            it.parent = this
            children.add(it)
        }
        return this
    }
}

class WirelessDebuggingNodeMatcherTest {

    private val labels = WirelessDebuggingNodeMatcher.wirelessDebuggingLabels

    @Test
    fun normalizeHandlesCaseSpacesAndDirectionMarks() {
        assertEquals(
            "wireless debugging",
            WirelessDebuggingNodeMatcher.normalize("  Wireless\u00A0  Debugging\u200F ")
        )
        assertEquals("", WirelessDebuggingNodeMatcher.normalize(null))
    }

    @Test
    fun exactTitleBeatsSummarySentence() {
        val summary = FakeNode(text = "Wireless debugging lets you connect over Wi-Fi")
        val title = FakeNode(text = "Wireless debugging")
        val root = FakeNode().add(summary, title)
        assertSame(title, WirelessDebuggingNodeMatcher.findByLabels(root, labels))
    }

    @Test
    fun containsMatchIsUsedWhenThereIsNoExactTitle() {
        val row = FakeNode(text = "Use Wireless Debugging")
        val root = FakeNode().add(row)
        assertSame(row, WirelessDebuggingNodeMatcher.findByLabels(root, labels))
    }

    @Test
    fun arabicLabelMatches() {
        val row = FakeNode(text = "تصحيح الأخطاء اللاسلكي")
        assertSame(row, WirelessDebuggingNodeMatcher.findByLabels(FakeNode().add(row), labels))
    }

    @Test
    fun contentDescriptionIsMatchedToo() {
        val row = FakeNode(contentDescription = "Wireless debugging")
        assertSame(row, WirelessDebuggingNodeMatcher.findByLabels(FakeNode().add(row), labels))
    }

    @Test
    fun noMatchReturnsNull() {
        val root = FakeNode().add(FakeNode(text = "USB debugging"), FakeNode(text = "Developer options"))
        assertNull(WirelessDebuggingNodeMatcher.findByLabels(root, labels))
    }

    @Test
    fun resourceIdHintMatchesPairByCodeRow() {
        val row = FakeNode(viewId = "com.android.settings:id/adb_pair_method_code_pref")
        val root = FakeNode().add(row)
        assertSame(
            row,
            WirelessDebuggingNodeMatcher.findByLabels(
                root,
                WirelessDebuggingNodeMatcher.pairByCodeLabels,
                WirelessDebuggingNodeMatcher.PAIR_BY_CODE_KEY
            )
        )
    }

    @Test
    fun clickableAncestorIsFoundAboveTheLabel() {
        val label = FakeNode(text = "Wireless debugging")
        val row = FakeNode(isClickable = true).add(label)
        FakeNode().add(row)
        assertSame(row, WirelessDebuggingNodeMatcher.clickableAncestor(label))
        assertNull(WirelessDebuggingNodeMatcher.clickableAncestor(FakeNode().add(FakeNode())))
    }

    @Test
    fun disabledAncestorIsSkipped() {
        val label = FakeNode(text = "x")
        val disabled = FakeNode(isClickable = true, isEnabled = false).add(label)
        assertNull(WirelessDebuggingNodeMatcher.clickableAncestor(label).takeIf { it !== disabled })
    }

    @Test
    fun bestScrollablePrefersTheLargestList() {
        val small = FakeNode(isScrollable = true, className = "android.widget.HorizontalScrollView", box = intArrayOf(0, 0, 200, 100))
        val list = FakeNode(isScrollable = true, className = "androidx.recyclerview.widget.RecyclerView", box = intArrayOf(0, 0, 1080, 2000))
        val root = FakeNode().add(small, list)
        assertSame(list, WirelessDebuggingNodeMatcher.bestScrollable(root))
        assertNull(WirelessDebuggingNodeMatcher.bestScrollable(FakeNode()))
    }

    @Test
    fun uncheckedSwitchesIgnoresOnAndDisabledSwitches() {
        val off = FakeNode(isCheckable = true, isChecked = false)
        val on = FakeNode(isCheckable = true, isChecked = true)
        val disabled = FakeNode(isCheckable = true, isChecked = false, isEnabled = false)
        val found = WirelessDebuggingNodeMatcher.uncheckedSwitches(FakeNode().add(off, on, disabled))
        assertEquals(1, found.size)
        assertSame(off, found[0])
    }

    @Test
    fun allowButtonRequiresAButtonWithExactLabel() {
        val notButton = FakeNode(text = "Allow", className = "android.widget.TextView")
        val button = FakeNode(text = "Allow", className = "android.widget.Button")
        val root = FakeNode().add(notButton, button)
        assertSame(button, WirelessDebuggingNodeMatcher.findAllowButton(root))
        assertNull(WirelessDebuggingNodeMatcher.findAllowButton(FakeNode().add(notButton)))
    }

    @Test
    fun signatureChangesWhenListContentChanges() {
        val a = FakeNode().add(FakeNode(text = "Developer options"), FakeNode(text = "USB debugging"))
        val b = FakeNode().add(FakeNode(text = "USB debugging"), FakeNode(text = "Wireless debugging"))
        assertTrue(WirelessDebuggingNodeMatcher.signature(a) != WirelessDebuggingNodeMatcher.signature(b))
        assertEquals(WirelessDebuggingNodeMatcher.signature(a), WirelessDebuggingNodeMatcher.signature(a))
        assertNotNull(WirelessDebuggingNodeMatcher.allTexts(a))
    }
}
