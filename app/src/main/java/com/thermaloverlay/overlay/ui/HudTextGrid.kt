/**
 * Builds the detailed HUD text as rows with pixel-exact columns.
 *
 * Padding with spaces only lines up in a monospace font, and many ROMs replace
 * "monospace" with their (proportional) system font. So every column here is a
 * tab stop whose position is MEASURED with the font that is really in use, and
 * right-aligned cells (core load, swap %) get a tab stop computed from their
 * own measured width. The result is the same grid with any font:
 *
 *     #RAM    47%    1%
 *      DDR    6389
 *     #GMEM   539MB
 *     #0~5    2496MHz
 *        46%  671M        <- load ends under the end of "#0~5",
 *        35%  620M           MHz starts under the cluster frequency
 */
package com.thermaloverlay.overlay.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TabStopSpan
import kotlin.math.ceil
import kotlin.math.max

/** The text plus the width (px, without padding) its columns need. */
class HudDetails(val text: CharSequence, val width: Int)

/** Regular and bold paints copied from the TextView that will draw the text. */
class HudMetrics(base: TextPaint) {
    val regular = TextPaint(base).apply { typeface = base.typeface }
    val bold = TextPaint(base).apply { typeface = Typeface.create(base.typeface, Typeface.BOLD) }
}

/**
 * @param clusterLabels the "#0~5" style labels of the cluster headers.
 * @param rowLabels     the single-value labels that are really shown ("#RAM",
 *                      "#GMEM", ...): only they decide how wide the label
 *                      column is, so a row that isn't there can't widen it.
 */
class HudTextGrid(private val m: HudMetrics, clusterLabels: List<String>, rowLabels: List<String>) {
    private val sb = SpannableStringBuilder()
    private var rows = 0

    private val gap = m.bold.textSize * GAP_EM

    /** Where values (and the cluster frequency, and per-core MHz) start. */
    private val valueX: Int

    /** Where per-core load ends. */
    private val pctRight: Int

    /** Right edge of the value column; the swap % ends here. */
    private val valueRight: Int

    init {
        val clusterW = clusterLabels.maxOfOrNull { m.bold.measureText(it) } ?: m.bold.measureText("#0~7")
        // A 2-digit load must start to the right of the '#'.
        pctRight = ceil(max(clusterW, m.bold.measureText("#") + m.regular.measureText("99%"))).toInt()

        var labelW = m.regular.measureText(" DDR")
        for (l in rowLabels) labelW = max(labelW, m.bold.measureText(l))
        labelW = max(labelW, clusterW)
        valueX = ceil(max(labelW, pctRight.toFloat()) + gap).toInt()

        valueRight = valueX + ceil(m.bold.measureText(WIDEST_VALUE)).toInt()
    }

    fun build(): HudDetails = HudDetails(sb, valueRight)

    /** "#GMEM" + value, both bold white. */
    fun boldRow(label: String, value: String) = addRow("$label\t$value", bold = true, stops = intArrayOf(valueX))

    /** Gray, regular weight (DDR). */
    fun mutedRow(label: String, value: String) = addRow("$label\t$value", bold = false, stops = intArrayOf(valueX))

    /** "#RAM  47%  1%": RAM starts in the value column, swap ends at its right edge. */
    fun ramRow(ram: String, swap: String) {
        val swapStart = max(
            valueX + m.bold.measureText(ram) + gap,
            valueRight - m.bold.measureText(swap),
        ).toInt()
        addRow("#RAM\t$ram\t$swap", bold = true, stops = intArrayOf(valueX, swapStart))
    }

    /** "#0~5  2496MHz" */
    fun clusterHeader(label: String, freq: String) =
        addRow("$label\t$freq", bold = true, stops = intArrayOf(valueX))

    /** Load right-aligned under the end of the cluster label, MHz left-aligned under the frequency. */
    fun coreRow(load: String, mhz: String) {
        val loadStart = max(1, pctRight - ceil(m.regular.measureText(load)).toInt())
        addRow("\t$load\t$mhz", bold = false, stops = intArrayOf(loadStart, valueX))
    }

    private fun addRow(text: String, bold: Boolean, stops: IntArray) {
        if (rows > 0) sb.append('\n')
        rows++
        val start = sb.length
        sb.append(text)
        val end = sb.length
        if (bold) {
            sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (x in stops) sb.setSpan(TabStopSpan.Standard(x), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private companion object {
        // Gap between the label column and the value column, in em.
        const val GAP_EM = 0.4f

        // The widest value: sets the right edge of the whole text.
        const val WIDEST_VALUE = "8888MHz"
    }
}
