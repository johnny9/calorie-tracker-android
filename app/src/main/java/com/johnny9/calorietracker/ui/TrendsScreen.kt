package com.johnny9.calorietracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.johnny9.calorietracker.AppViewModel
import com.johnny9.calorietracker.domain.DailyPoint
import com.johnny9.calorietracker.domain.DayCompleteness
import com.johnny9.calorietracker.domain.fromMilli
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TrendsScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val state by viewModel.trends.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Net calorie trends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Net = food intake − active calories. Gaps stay gaps; they are never silently changed to zero.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Latest 7 calendar days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    NetBarChart(state.lastSeven)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        state.lastSeven.forEach { Text(it.date.format(DateTimeFormatter.ofPattern("EE")), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rolling window", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${state.windowDays} days")
                    Slider(
                        value = state.windowDays.toFloat(),
                        onValueChange = { viewModel.setRollingWindow(it.roundToInt()) },
                        valueRange = 3f..30f,
                        steps = 26,
                    )
                    val average = state.rolling.averageNetMilliKcal
                    Text(
                        if (average == null) "No completed days yet" else String.format(Locale.US, "%.0f kcal average net", average.fromMilli()),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${state.rolling.eligibleDays} complete of ${state.rolling.elapsedDays} elapsed; ${state.rolling.requestedWindowDays}-day window",
                    )
                    Text(
                        String.format(Locale.US, "Known total: %.0f kcal", state.rolling.knownTotalNetMilliKcal.fromMilli()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { Text("Daily detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(state.lastSeven.reversed(), key = { it.date.toString() }) { point ->
            DailyTrendRow(point)
        }
    }
}

@Composable
private fun NetBarChart(points: List<DailyPoint>) {
    val eligible = points.filter(DailyPoint::isEligible)
    val minValue = min(0f, eligible.minOfOrNull { it.netMilliKcal.fromMilli().toFloat() } ?: 0f)
    val maxValue = max(0f, eligible.maxOfOrNull { it.netMilliKcal.fromMilli().toFloat() } ?: 0f)
    val range = maxValue - minValue
    val safeRange = if (range == 0f) 1f else range
    val completeColor = MaterialTheme.colorScheme.primary
    val zeroColor = MaterialTheme.colorScheme.secondary
    val gapColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val baseline = size.height * (maxValue / safeRange)
        drawLine(gapColor, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 2f)
        val slot = size.width / max(points.size, 1)
        points.forEachIndexed { index, point ->
            val centerX = slot * index + slot / 2f
            if (!point.isEligible) {
                drawLine(
                    color = gapColor,
                    start = Offset(centerX - slot * 0.18f, baseline),
                    end = Offset(centerX + slot * 0.18f, baseline),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round,
                )
            } else {
                val value = point.netMilliKcal.fromMilli().toFloat()
                val y = size.height * ((maxValue - value) / safeRange)
                val top = min(y, baseline)
                val barHeight = max(abs(y - baseline), 5f)
                drawRoundRect(
                    color = if (point.completeness == DayCompleteness.FASTED_ZERO) zeroColor else completeColor,
                    topLeft = Offset(centerX - slot * 0.22f, top),
                    size = Size(slot * 0.44f, barHeight),
                )
            }
        }
    }
}

@Composable
private fun DailyTrendRow(point: DailyPoint) {
    val statusColor = when (point.completeness) {
        DayCompleteness.COMPLETE -> MaterialTheme.colorScheme.primary
        DayCompleteness.FASTED_ZERO -> MaterialTheme.colorScheme.secondary
        DayCompleteness.PARTIAL -> MaterialTheme.colorScheme.tertiary
        DayCompleteness.MISSING -> MaterialTheme.colorScheme.outline
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(point.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontWeight = FontWeight.Medium)
                Text(point.completeness.name.lowercase().replace('_', ' '), color = statusColor, style = MaterialTheme.typography.bodySmall)
            }
            Column {
                Text(String.format(Locale.US, "Net %.0f kcal", point.netMilliKcal.fromMilli()), fontWeight = FontWeight.Bold)
                Text(
                    String.format(Locale.US, "%.0f in − %.0f active", point.intakeMilliKcal.fromMilli(), point.activeMilliKcal.fromMilli()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
