package com.pvd.scwcontrolroom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pvd.scwcontrolroom.kit.MetricSeries
import com.pvd.scwcontrolroom.kit.formatMetric
import com.pvd.scwcontrolroom.kit.instanceLabel
import com.pvd.scwcontrolroom.kit.isInstanceCount
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val axisTime = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * A line per instance; a single line also gets a shaded area. Values on the
 * right as the iPhone's charts have them, the period's start and end below.
 */
@Composable
fun MetricChart(series: List<MetricSeries>, name: String, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val palette = listOf(primary, Color(0xFF3B82F6), Color(0xFFE08A00), Color(0xFFD946EF), Color(0xFF14B8A6), Color(0xFFEF4444))
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    // A count of instances jumps between whole numbers.
    val steps = isInstanceCount(name)

    val points = series.flatMap { it.points }.filter { !it.value.isNaN() }
    if (points.isEmpty()) return
    val start = points.minOf { it.time }
    val end = points.maxOf { it.time }
    val low = minOf(0.0, points.minOf { it.value })
    val high = points.maxOf { it.value }.let { if (it <= low) low + 1 else it + (it - low) * 0.1 }
    val ticks = (0..3).map { low + (high - low) * it / 3 }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            val labelWidth = ticks.maxOf { measurer.measure(formatMetric(it, name), labelStyle).size.width } + 8.dp.toPx()
            val timeHeight = measurer.measure("00:00", labelStyle).size.height + 4.dp.toPx()
            val width = size.width - labelWidth
            val height = size.height - timeHeight
            val span = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1)
            fun x(time: java.time.Instant) = (time.toEpochMilli() - start.toEpochMilli()).toFloat() / span * width
            fun y(value: Double) = (height - (value - low) / (high - low) * height).toFloat()

            for (tick in ticks) {
                drawLine(grid, Offset(0f, y(tick)), Offset(width, y(tick)), 1.dp.toPx(),
                         pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                val text = measurer.measure(formatMetric(tick, name), labelStyle)
                drawText(text, topLeft = Offset(width + 6.dp.toPx(), (y(tick) - text.size.height / 2f).coerceIn(0f, height - text.size.height)))
            }
            drawText(measurer.measure(axisTime.format(start), labelStyle), topLeft = Offset(0f, height + 4.dp.toPx()))
            val endLabel = measurer.measure(axisTime.format(end), labelStyle)
            drawText(endLabel, topLeft = Offset(width - endLabel.size.width, height + 4.dp.toPx()))

            series.forEachIndexed { index, line ->
                val color = palette[index % palette.size]
                val samples = line.points.filter { !it.value.isNaN() }
                if (samples.isEmpty()) return@forEachIndexed
                val path = Path()
                samples.forEachIndexed { i, point ->
                    if (i == 0) path.moveTo(x(point.time), y(point.value))
                    else {
                        if (steps) path.lineTo(x(point.time), y(samples[i - 1].value))
                        path.lineTo(x(point.time), y(point.value))
                    }
                }
                if (series.size == 1) {
                    val area = Path().apply {
                        addPath(path)
                        lineTo(x(samples.last().time), y(low))
                        lineTo(x(samples.first().time), y(low))
                        close()
                    }
                    drawPath(area, color.copy(alpha = 0.15f))
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (series.size > 1) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                series.forEachIndexed { index, line ->
                    Box(Modifier.size(8.dp).background(palette[index % palette.size], CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(instanceLabel(line.instance), style = labelStyle)
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
