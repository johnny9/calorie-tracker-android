package com.johnny9.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnny9.calorietracker.UsdaFoodSearchUiState
import com.johnny9.calorietracker.data.usda.UsdaFoodSummary
import java.util.Locale

@Composable
fun UsdaFoodSearchPanel(
    query: String,
    state: UsdaFoodSearchUiState,
    savedFdcIds: Set<Long>,
    onSelect: (Long) -> Unit,
    actionLabel: String,
    disableSaved: Boolean,
    actionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val normalizedQuery = query.trim().replace(Regex("\\s+"), " ").take(80)
    if (state.query != normalizedQuery || (state.source == null && !state.isSearching && state.error == null)) return

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline USDA catalog", style = MaterialTheme.typography.titleSmall)
            if (state.isSearching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("Searching on-device…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val source = state.source
                if (source != null) {
                    Text(
                        "${source.releaseId} · ${source.releaseDate} · ${source.license}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.error == null && state.results.isEmpty()) {
                    Text("No offline USDA matches found.", style = MaterialTheme.typography.bodySmall)
                }
                state.results.forEachIndexed { index, food ->
                    if (index > 0) HorizontalDivider()
                    UsdaCandidateRow(
                        food = food,
                        saved = food.fdcId in savedFdcIds,
                        importing = state.importingFdcId == food.fdcId,
                        importInProgress = state.importingFdcId != null,
                        actionLabel = actionLabel,
                        disableSaved = disableSaved,
                        actionEnabled = actionEnabled,
                        onSelect = { onSelect(food.fdcId) },
                    )
                }
                source?.let {
                    Text(
                        it.attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsdaCandidateRow(
    food: UsdaFoodSummary,
    saved: Boolean,
    importing: Boolean,
    importInProgress: Boolean,
    actionLabel: String,
    disableSaved: Boolean,
    actionEnabled: Boolean,
    onSelect: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(food.name, style = MaterialTheme.typography.bodyMedium)
        food.brand?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(food.previewText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("FDC ${food.fdcId}", style = MaterialTheme.typography.labelSmall)
        if (!food.hasCompleteNutrition && food.hasRequiredNutrition) {
            Text("Fiber is unavailable and will be saved as 0 g.", style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onSelect,
                enabled = actionEnabled && food.hasImportableNutrition && !importInProgress && !(disableSaved && saved),
            ) {
                Text(
                    when {
                        !food.hasImportableNutrition -> "Nutrition invalid"
                        importing -> "Loading…"
                        disableSaved && saved -> "Saved offline"
                        else -> actionLabel
                    },
                )
            }
        }
    }
}

private fun UsdaFoodSummary.previewText(): String {
    val values = mutableListOf(servingLabel)
    calories?.let { values += String.format(Locale.US, "%.0f kcal", it) }
    protein?.let { values += String.format(Locale.US, "P %.1f", it) }
    carbs?.let { values += String.format(Locale.US, "C %.1f", it) }
    fat?.let { values += String.format(Locale.US, "F %.1f", it) }
    return values.joinToString(" · ")
}
