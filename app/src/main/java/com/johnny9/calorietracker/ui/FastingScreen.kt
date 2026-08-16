package com.johnny9.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.johnny9.calorietracker.AppViewModel
import com.johnny9.calorietracker.data.FastingPeriodEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
fun FastingScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val fasts by viewModel.fasts.collectAsState()
    val active = fasts.firstOrNull { it.endEpochMs == null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(active?.id) {
        while (active != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Fasting", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("A simple local timer. Fasting never changes calorie or macro targets automatically.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (active == null) {
                        Text("No active fast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Start now with a planned duration, or leave the end open.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.startFast(16) }) { Text("16 hours") }
                            Button(onClick = { viewModel.startFast(24) }) { Text("24 hours") }
                        }
                        OutlinedButton(onClick = { viewModel.startFast(null) }) { Text("Open-ended") }
                    } else {
                        Text("Fast in progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(formatDuration(now - active.startEpochMs), style = MaterialTheme.typography.displaySmall)
                        active.plannedEndEpochMs?.let { end ->
                            val remaining = end - now
                            Text(if (remaining >= 0) "${formatDuration(remaining)} remaining" else "Planned end passed ${formatDuration(-remaining)} ago")
                        }
                        Button(onClick = { viewModel.endFast(active.id) }, modifier = Modifier.fillMaxWidth()) { Text("End fast") }
                    }
                }
            }
        }
        item {
            Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Ending a fast does not mark a day complete or assume zero intake.", style = MaterialTheme.typography.bodySmall)
        }
        if (fasts.none { it.endEpochMs != null }) {
            item { Text("No completed fasts yet") }
        } else {
            items(fasts.filter { it.endEpochMs != null }, key = FastingPeriodEntity::id) { fast ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(formatInstant(fast.startEpochMs), fontWeight = FontWeight.Medium)
                        Text(formatDuration(requireNotNull(fast.endEpochMs) - fast.startEpochMs))
                    }
                }
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = max(milliseconds, 0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatInstant(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"))
