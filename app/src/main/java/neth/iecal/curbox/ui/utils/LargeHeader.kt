package neth.iecal.curbox.ui.utils

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * Large bold screen title that sits at the top of a scrolling screen and
 * collapses away (fades, shrinks and drifts up) as the content scrolls.
 * Pure view code: it only inserts one TextView and listens to scrolling, so
 * it cannot affect the screen's own logic.
 */
object LargeHeader {

    fun install(root: View, title: CharSequence) {
        val scroller = findScroller(root) ?: return
        val content = scroller.getChildAt(0) as? ViewGroup ?: return
        if (content !is android.widget.LinearLayout) return
        if (content.findViewWithTag<View>(TAG) != null) return
        val d = root.resources.displayMetrics.density

        val tv = TextView(root.context).apply {
            tag = TAG
            text = title
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.01f
            setTextColor(MaterialColors.getColor(root, MaterialR.attr.colorOnSurface))
            setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (14 * d).toInt())
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            pivotX = 0f
        }
        content.addView(tv, 0)

        val range = 72f * d
        scroller.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, y, _, _ ->
                val p = (y / range).coerceIn(0f, 1f)
                tv.alpha = 1f - p
                tv.translationY = y * 0.4f
                val s = 1f - 0.12f * p
                tv.scaleX = s
                tv.scaleY = s
            }
        )
    }

    private fun findScroller(v: View): NestedScrollView? {
        if (v is NestedScrollView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findScroller(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private const val TAG = "horus_large_header"
}
