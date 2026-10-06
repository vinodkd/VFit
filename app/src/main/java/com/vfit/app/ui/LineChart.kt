package com.vfit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** x = epoch day. NaN y values are gaps. */
sealed class Series(val label: String, val color: Color) {
    class Line(label: String, color: Color, val x: List<Double>, val y: List<Double>, val dashed: Boolean = false, val width: Float = 2f) : Series(label, color)
    class Dots(label: String, color: Color, val x: List<Double>, val y: List<Double>) : Series(label, color)
    class Band(label: String, color: Color, val x: List<Double>, val lo: List<Double>, val hi: List<Double>) : Series(label, color)
    class HLine(label: String, color: Color, val y: Double) : Series(label, color)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(title: String, unit: String, series: List<Series>, modifier: Modifier = Modifier, decimals: Int = 0) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)

    val xs = series.flatMap {
        when (it) { is Series.Line -> it.x; is Series.Dots -> it.x; is Series.Band -> it.x; is Series.HLine -> emptyList() }
    }
    val ys = series.flatMap {
        when (it) {
            is Series.Line -> it.y; is Series.Dots -> it.y; is Series.Band -> it.lo + it.hi; is Series.HLine -> listOf(it.y)
        }
    }.filter { !it.isNaN() }

    Column(modifier.padding(vertical = 8.dp)) {
        Text("$title ($unit)", style = MaterialTheme.typography.titleSmall)
        if (xs.isEmpty() || ys.isEmpty()) {
            Text("No data yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        val (yMin, yMax, yStep) = niceRange(ys.min(), ys.max())
        val xMin = xs.min()
        val xMax = xs.max().let { if (it == xMin) xMin + 1 else it }
        Canvas(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp)) {
            val left = 44.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val top = 4.dp.toPx()
            fun px(x: Double) = (left + (x - xMin) / (xMax - xMin) * (right - left)).toFloat()
            fun py(y: Double) = (bottom - (y - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            var t = yMin
            while (t <= yMax + yStep / 2) {
                drawLine(gridColor, Offset(left, py(t)), Offset(right, py(t)), 1f)
                val lbl = measurer.measure("%.${decimals}f".format(t), labelStyle)
                drawText(lbl, topLeft = Offset(left - lbl.size.width - 4.dp.toPx(), py(t) - lbl.size.height / 2))
                t += yStep
            }
            val fmt = DateTimeFormatter.ofPattern("MMM d")
            for (k in 0..3) {
                val x = xMin + (xMax - xMin) * k / 3
                val lbl = measurer.measure(LocalDate.ofEpochDay(Math.round(x)).format(fmt), labelStyle)
                val lx = (px(x) - lbl.size.width / 2).coerceIn(left, right - lbl.size.width)
                drawText(lbl, topLeft = Offset(lx, bottom + 2.dp.toPx()))
            }

            for (s in series) when (s) {
                is Series.Band -> {
                    val pts = s.x.indices.filter { !s.lo[it].isNaN() && !s.hi[it].isNaN() }
                    if (pts.size >= 2) {
                        val p = Path()
                        pts.forEachIndexed { k, i -> if (k == 0) p.moveTo(px(s.x[i]), py(s.hi[i])) else p.lineTo(px(s.x[i]), py(s.hi[i])) }
                        pts.reversed().forEach { i -> p.lineTo(px(s.x[i]), py(s.lo[i])) }
                        p.close()
                        drawPath(p, s.color.copy(alpha = 0.18f))
                    }
                }
                is Series.Dots -> s.x.indices.filter { !s.y[it].isNaN() }.forEach {
                    drawCircle(s.color, 2.dp.toPx(), Offset(px(s.x[it]), py(s.y[it])))
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
        }
        FlowRow(Modifier.padding(top = 4.dp)) {
            for (s in series) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Spacer(Modifier.size(10.dp).background(if (s is Series.Band) s.color.copy(alpha = 0.3f) else s.color))
                Spacer(Modifier.width(4.dp))
                Text(s.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Rounded axis bounds and step with about 4 gridlines. */
private fun niceRange(lo: Double, hi: Double): Triple<Double, Double, Double> {
    val span = (hi - lo).takeIf { it > 0 } ?: maxOf(1.0, kotlin.math.abs(hi) * 0.1)
    val raw = span / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val min = floor(lo / step) * step
    var max = ceil(hi / step) * step
    if (max == min) max = min + step
    return Triple(min, max, step)
}
