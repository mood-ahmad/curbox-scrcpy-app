package neth.iecal.curbox.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

class WeeklyBarGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ZoomLevel {
        DAYS,
        WEEKS
    }

    data class BarData(
        val label: String,
        val value: Float,
        val dateMillis: Long
    )

    private var entries: List<BarData> = emptyList()
    private var selectedIndex: Int = -1
    private var onBarSelected: ((BarData) -> Unit)? = null
    private var onZoomLevelChanged: ((ZoomLevel) -> Unit)? = null
    private var zoomLevel = ZoomLevel.DAYS
    private var zoomEnabled = false
    private var awaitingZoomData = false
    private var accumulatedScale = 1f
    private var hadMultiplePointers = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulatedScale = 1f
                hadMultiplePointers = true
                return zoomEnabled
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled || awaitingZoomData) return false
                accumulatedScale *= detector.scaleFactor
                val nextLevel = when {
                    accumulatedScale <= ZOOM_OUT_THRESHOLD -> ZoomLevel.WEEKS
                    accumulatedScale >= ZOOM_IN_THRESHOLD -> ZoomLevel.DAYS
                    else -> return true
                }
                if (nextLevel != zoomLevel) {
                    zoomLevel = nextLevel
                    awaitingZoomData = true
                    onZoomLevelChanged?.invoke(nextLevel)
                }
                return true
            }
        }
    )

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val barRadius = 8f
    private val barRect = RectF()
    private val minBarHeight = 6f // dp

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(data: List<BarData>, selected: Int = -1) {
        entries = data
        awaitingZoomData = false
        selectedIndex = selected
        resolveColors()
        invalidate()
    }

    fun setOnBarSelectedListener(listener: (BarData) -> Unit) {
        onBarSelected = listener
    }

    fun enableZoom(
        initialLevel: ZoomLevel = ZoomLevel.DAYS,
        listener: (ZoomLevel) -> Unit
    ) {
        zoomEnabled = true
        zoomLevel = initialLevel
        onZoomLevelChanged = listener
    }

    fun setZoomLevel(level: ZoomLevel) {
        zoomLevel = level
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index
        invalidate()
    }

    private fun resolveColors() {
        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

        barPaint.color = ColorUtils.setAlphaComponent(colorPrimary, 192)
        selectedBarPaint.color = colorPrimary
        labelPaint.color = colorOnSurfaceVariant
        labelPaint.textSize = 11f * resources.displayMetrics.density
        selectedLabelPaint.color = colorPrimary
        selectedLabelPaint.textSize = labelPaint.textSize
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (210 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun niceMax(v: Float): Float {
        val steps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 16f, 24f, 48f, 96f, 168f)
        for (st in steps) if (v <= st) return st
        return kotlin.math.ceil(v / 24f) * 24f
    }

    private fun fmtHours(h: Float): String {
        if (h <= 0f) return "0"
        if (h < 1f) return "${kotlin.math.round(h * 60f).toInt()}m"
        val rounded = kotlin.math.round(h * 10f) / 10f
        return if (rounded % 1f == 0f) "${rounded.toInt()}h" else "${rounded}h"
    }

    private fun fmtAvg(h: Float): String {
        val totalMin = kotlin.math.round(h * 60f).toInt()
        val hh = totalMin / 60
        val mm = totalMin % 60
        return when {
            hh == 0 -> "${mm}m"
            mm == 0 -> "${hh}h"
            else -> "${hh}h ${mm}m"
        }
    }

    private fun axisWidth() = 44f * resources.displayMetrics.density
    private fun areaLeft() = paddingStart + 12f * resources.displayMetrics.density
    private fun areaRight() = width.toFloat() - paddingEnd - axisWidth()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return

        val d = resources.displayMetrics.density
        val count = entries.size
        val onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
        val container = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh)

        gridPaint.color = ColorUtils.setAlphaComponent(outline, 150)
        gridPaint.strokeWidth = 1f * d
        avgPaint.color = onSurfaceVariant
        avgPaint.strokeWidth = 1.2f * d
        avgPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
        axisPaint.color = onSurfaceVariant
        axisPaint.textSize = 11f * d
        chipPaint.color = container
        chipTextPaint.color = onSurface
        chipTextPaint.textSize = 10f * d
        pillPaint.color = primary
        trackPaint.color = ColorUtils.setAlphaComponent(outline, 70)

        val labelArea = 30f * d
        val chartTop = 12f * d
        val chartBottom = height - labelArea
        val chartHeight = chartBottom - chartTop
        val left = areaLeft()
        val right = areaRight()
        val gap = (right - left) / count
        val barWidth = minOf(gap * 0.5f, 22f * d)

        val maxValue = entries.maxOfOrNull { it.value } ?: 0f
        val axisMax = niceMax(if (maxValue <= 0f) 1f else maxValue)

        // grid: bottom, middle, top
        val levels = floatArrayOf(0f, axisMax / 2f, axisMax)
        for (lv in levels) {
            val y = chartBottom - chartHeight * (lv / axisMax)
            canvas.drawLine(left - 6f * d, y, right + 6f * d, y, gridPaint)
            canvas.drawText(fmtHours(lv), width - paddingEnd - 6f * d, y - 4f * d, axisPaint)
        }

        // soft track behind each bar
        for (i in entries.indices) {
            val cx = left + gap * i + gap / 2f
            barRect.set(cx - barWidth / 2f, chartTop, cx + barWidth / 2f, chartBottom)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, trackPaint)
        }

        val minBarPx = minBarHeight * d
        for (i in entries.indices) {
            val cx = left + gap * i + gap / 2f
            var h = chartHeight * (entries[i].value / axisMax)
            if (entries[i].value > 0f && h < minBarPx) h = minBarPx
            if (h <= 0f) continue
            barRect.set(cx - barWidth / 2f, chartBottom - h, cx + barWidth / 2f, chartBottom)
            val paint = if (i == selectedIndex) selectedBarPaint else barPaint
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, paint)
        }

        // average line (days that have usage)
        val used = entries.filter { it.value > 0f }
        if (used.isNotEmpty()) {
            val avg = used.map { it.value }.average().toFloat()
            val y = chartBottom - chartHeight * (minOf(avg, axisMax) / axisMax)
            val p = android.graphics.Path().apply { moveTo(left - 6f * d, y); lineTo(right + 6f * d, y) }
            canvas.drawPath(p, avgPaint)
            val text = "Avg " + fmtAvg(avg)
            val tw = chipTextPaint.measureText(text) + 12f * d
            val cx = width - paddingEnd - 4f * d - tw / 2f
            barRect.set(cx - tw / 2f, y - 10f * d, cx + tw / 2f, y + 10f * d)
            canvas.drawRoundRect(barRect, 10f * d, 10f * d, chipPaint)
            canvas.drawText(text, cx, y + 3.5f * d, chipTextPaint)
        }

        // day labels, selected one gets a filled pill
        val labelY = chartBottom + labelArea * 0.62f
        for (i in entries.indices) {
            val cx = left + gap * i + gap / 2f
            if (i == selectedIndex) {
                canvas.drawCircle(cx, labelY - 4f * d, 12f * d, pillPaint)
                selectedLabelPaint.color = onPrimary
                canvas.drawText(entries[i].label, cx, labelY, selectedLabelPaint)
            } else {
                canvas.drawText(entries[i].label, cx, labelY, labelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zoomEnabled) scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                hadMultiplePointers = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hadMultiplePointers = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val moved = kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop
                if (!hadMultiplePointers && !moved && !awaitingZoomData) {
                    selectBarAt(event.x)
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun selectBarAt(touchX: Float) {
        if (entries.isEmpty()) return
        val leftOffset = areaLeft()
        val gapWidth = (areaRight() - leftOffset) / entries.size

        for (i in entries.indices) {
            val centerX = leftOffset + gapWidth * i + gapWidth / 2f
            if (touchX in centerX - gapWidth / 2f..centerX + gapWidth / 2f) {
                selectedIndex = i
                invalidate()
                onBarSelected?.invoke(entries[i])
                return
            }
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private companion object {
        const val ZOOM_OUT_THRESHOLD = 0.8f
        const val ZOOM_IN_THRESHOLD = 1.2f
    }
}
