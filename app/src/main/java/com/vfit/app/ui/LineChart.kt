package com.vfit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** x = epoch day (a fractional part is the time of day). NaN y values are gaps. */
sealed class Series(val label: String, val color: Color) {
    class Line(label: String, color: Color, val x: List<Double>, val y: List<Double>, val dashed: Boolean = false, val width: Float = 2f) : Series(label, color)
    class Dots(label: String, color: Color, val x: List<Double>, val y: List<Double>) : Series(label, color)
    class Band(label: String, color: Color, val x: List<Double>, val lo: List<Double>, val hi: List<Double>) : Series(label, color)
    class HLine(label: String, color: Color, val y: Double) : Series(label, color)
}

private val LEFT = 46.dp
private val RIGHT = 8.dp
private val TOP = 8.dp
private val BOTTOM = 20.dp
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.US)

private fun xsOf(s: Series) = when (s) { is Series.Line -> s.x; is Series.Dots -> s.x; is Series.Band -> s.x; is Series.HLine -> emptyList() }

/** Value(s) of a series at x (nearest point within half a day); null if none. */
private fun valueAt(s: Series, x: Double, decimals: Int): String? {
    fun f(v: Double) = String.format(Locale.US, "%,.${decimals}f", v)
    fun near(xs: List<Double>): Int? = xs.indices.minByOrNull { abs(xs[it] - x) }?.takeIf { abs(xs[it] - x) < 0.5 }
    return when (s) {
        is Series.Line -> near(s.x)?.let { s.y[it] }?.takeUnless { it.isNaN() }?.let(::f)
        is Series.Dots -> near(s.x)?.let { s.y[it] }?.takeUnless { it.isNaN() }?.let(::f)
        is Series.Band -> near(s.x)?.let { i -> if (s.lo[i].isNaN()) null else "${f(s.lo[i])}–${f(s.hi[i])}" }
        is Series.HLine -> null
    }
}

/**
 * Line chart with a scrubber: press or slide a finger across the plot to pin a date; the readout above the
 * plot lists every series' value on that date, and a guide line + dots mark it on the plot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(title: String, unit: String, series: List<Series>, modifier: Modifier = Modifier, decimals: Int = 0) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val guideColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)
    val density = LocalDensity.current
    var scrubX by remember(series) { mutableStateOf<Double?>(null) }

    val xs = series.flatMap { s -> xsOf(s).filterIndexed { i, _ -> hasY(s, i) } }
    val ys = series.flatMap {
        when (it) {
            is Series.Line -> it.y; is Series.Dots -> it.y; is Series.Band -> it.lo + it.hi; is Series.HLine -> listOf(it.y)
        }
    }.filter { !it.isNaN() }

    Column(modifier.padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = axisColor)
        if (xs.isEmpty() || ys.isEmpty()) {
            Text("No data yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        val (yMin, yMax, yStep) = niceRange(ys.min(), ys.max())
        val xMin = xs.min()
        val xMax = xs.max().let { if (it == xMin) xMin + 1 else it }
        val allPoints = xs.distinct().sorted()
        val leftPx = with(density) { LEFT.toPx() }
        val rightPx = with(density) { RIGHT.toPx() }
        /** Finger position -> x of the nearest data point. */
        fun toX(px: Float, width: Int): Double {
            val v = xMin + (px - leftPx) / (width - leftPx - rightPx) * (xMax - xMin)
            return allPoints.minByOrNull { abs(it - v) } ?: v
        }

        // readout: always takes the same space so the plot doesn't jump while scrubbing
        Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 4.dp)) {
            val x = scrubX
            if (x == null) {
                Text("Touch and slide on the chart to read values", style = MaterialTheme.typography.bodySmall, color = axisColor)
            } else {
                val whole = abs(x - Math.rint(x)) < 1e-6
                val dt = LocalDate.ofEpochDay(floor(x).toLong())
                val label = if (whole) dt.format(DAY) else {
                    val mins = ((x - floor(x)) * 1440).roundToInt()
                    dt.atTime(mins / 60 % 24, mins % 60).format(DAY_TIME)
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (s in series) valueAt(s, x, decimals)?.let { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                            Spacer(Modifier.width(4.dp))
                            Text("${s.label}: $v", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Canvas(
            Modifier.fillMaxWidth().height(220.dp)
                .pointerInput(series) {
                    detectTapGestures(onPress = { scrubX = toX(it.x, size.width) })
                }
                .pointerInput(series) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubX = toX(it.x, size.width) },
                        onHorizontalDrag = { change, _ -> change.consume(); scrubX = toX(change.position.x, size.width) },
                    )
                },
        ) {
            val left = LEFT.toPx()
            val bottom = size.height - BOTTOM.toPx()
            val right = size.width - RIGHT.toPx()
            val top = TOP.toPx()
            fun px(x: Double) = (left + (x - xMin) / (xMax - xMin) * (right - left)).toFloat()
            fun py(y: Double) = (bottom - (y - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            var t = yMin
            while (t <= yMax + yStep / 2) {
                drawLine(gridColor, Offset(left, py(t)), Offset(right, py(t)), 1f)
                val lbl = measurer.measure(String.format(Locale.US, "%,.${decimals}f", t), labelStyle)
                drawText(lbl, topLeft = Offset(left - lbl.size.width - 6.dp.toPx(), py(t) - lbl.size.height / 2))
                t += yStep
            }
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)
            for (k in 0..3) {
                val x = xMin + (xMax - xMin) * k / 3
                val lbl = measurer.measure(LocalDate.ofEpochDay(floor(x).toLong()).format(fmt), labelStyle)
                val lx = (px(x) - lbl.size.width / 2).coerceIn(left, right - lbl.size.width)
                drawText(lbl, topLeft = Offset(lx, bottom + 4.dp.toPx()))
            }

            for (s in series) when (s) {
                is Series.Band -> {
                    val pts = s.x.indices.filter { !s.lo[it].isNaN() && !s.hi[it].isNaN() }
                    if (pts.size >= 2) {
                        val p = Path()
                        pts.forEachIndexed { k, i -> if (k == 0) p.moveTo(px(s.x[i]), py(s.hi[i])) else p.lineTo(px(s.x[i]), py(s.hi[i])) }
                        pts.reversed().forEach { i -> p.lineTo(px(s.x[i]), py(s.lo[i])) }
                        p.close()
                        drawPath(p, s.color.copy(alpha = 0.16f))
                    }
                }
                is Series.Dots -> s.x.indices.filter { !s.y[it].isNaN() }.forEach {
                    drawCircle(s.color.copy(alpha = 0.75f), 2.5.dp.toPx(), Offset(px(s.x[it]), py(s.y[it])))
                }
                is Series.Line -> {
                    val p = Path()
                    var pen = false
                    for (i in s.x.indices) {
                        if (s.y[i].isNaN()) { pen = false; continue }
                        if (pen) p.lineTo(px(s.x[i]), py(s.y[i])) else p.moveTo(px(s.x[i]), py(s.y[i]))
                        pen = true
                    }
                    drawPath(p, s.color, style = Stroke(
                        width = s.width.dp.toPx(),
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null,
                    ))
                }
                is Series.HLine -> drawLine(
                    s.color, Offset(left, py(s.y)), Offset(right, py(s.y)), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), 1f)
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), 1f)

            // scrubber: guide line + a ringed dot on every series that has a value there
            scrubX?.let { x ->
                drawLine(guideColor.copy(alpha = 0.6f), Offset(px(x), top), Offset(px(x), bottom), 1.5.dp.toPx())
                for (s in series) {
                    val y = when (s) {
                        is Series.Line -> s.x.indexOfFirst { abs(it - x) < 0.5 }.takeIf { it >= 0 }?.let { s.y[it] }
                        is Series.Dots -> s.x.indices.filter { abs(s.x[it] - x) < 0.5 }.minByOrNull { abs(s.x[it] - x) }?.let { s.y[it] }
                        else -> null
                    }
                    if (y != null && !y.isNaN()) {
                        drawCircle(Color.White, 6.dp.toPx(), Offset(px(x), py(y)))
                        drawCircle(s.color, 4.5.dp.toPx(), Offset(px(x), py(y)))
                    }
                }
            }
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in series) Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(10.dp).background(if (s is Series.Band) s.color.copy(alpha = 0.3f) else s.color))
                Spacer(Modifier.width(4.dp))
                Text(s.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun hasY(s: Series, i: Int) = when (s) {
    is Series.Line -> !s.y[i].isNaN(); is Series.Dots -> !s.y[i].isNaN(); is Series.Band -> !s.lo[i].isNaN(); is Series.HLine -> false
}

/** Small trend line for dashboard tiles. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    val v = values.filter { !it.isNaN() }
    if (v.size < 2) return
    Canvas(modifier.fillMaxWidth().height(32.dp)) {
        val lo = v.min()
        val hi = v.max().let { if (it == lo) lo + 1 else it }
        val p = Path()
        v.forEachIndexed { i, y ->
            val px = i / (v.size - 1f) * size.width
            val py = (size.height - (y - lo) / (hi - lo) * size.height).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, color, style = Stroke(2.dp.toPx()))
        drawCircle(color, 3.dp.toPx(), Offset(size.width, (size.height - (v.last() - lo) / (hi - lo) * size.height).toFloat()))
    }
}

/** Rounded axis bounds and step with about 4 gridlines. */
private fun niceRange(lo: Double, hi: Double): Triple<Double, Double, Double> {
    val span = (hi - lo).takeIf { it > 0 } ?: maxOf(1.0, abs(hi) * 0.1)
    val raw = span / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val min = floor(lo / step) * step
    var max = ceil(hi / step) * step
    if (max == min) max = min + step
    return Triple(min, max, step)
}
