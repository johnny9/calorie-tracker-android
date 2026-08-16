package com.johnny9.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import com.johnny9.calorietracker.OnlineFoodSearchUiState
import com.johnny9.calorietracker.foodlookup.OnlineFoodCandidate
import java.util.Locale

@Composable
fun OnlineFoodSearchPanel(
    query: String,
    state: OnlineFoodSearchUiState,
    savedBarcodes: Set<String>,
    onSearch: (String) -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedQuery = query.trim().replace(Regex("\\s+"), " ")
    val showingThisQuery = state.query == normalizedQuery
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Packaged-food search", style = MaterialTheme.typography.titleSmall)
            Text(
                "Local foods stay available offline. Search Open Food Facts only when you tap the button.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { onSearch(normalizedQuery) },
                enabled = normalizedQuery.length in 2..80 && !(showingThisQuery && state.isSearching) && state.importingBarcode == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (showingThisQuery && state.isSearching) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching…")
                } else {
                    Text("Search Open Food Facts")
                }
            }

            if (showingThisQuery) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!state.isSearching && state.error == null && state.results.isEmpty()) {
                    Text("No online matches found.", style = MaterialTheme.typography.bodySmall)
                }
                state.results.forEachIndexed { index, result ->
                    if (index > 0) HorizontalDivider()
                    OnlineCandidateRow(
                        result = result,
                        saved = result.barcode in savedBarcodes,
                        importing = state.importingBarcode == result.barcode,
                        importInProgress = state.importingBarcode != null,
                        onSave = { onSave(result.barcode) },
                    )
                }
            }

            Text(
                "Community data from Open Food Facts (ODbL). Nutrition is validated before saving, but verify the package label.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnlineCandidateRow(
    result: OnlineFoodCandidate,
    saved: Boolean,
    importing: Boolean,
    importInProgress: Boolean,
    onSave: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(result.name, style = MaterialTheme.typography.bodyMedium)
        result.brand?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(result.previewText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Barcode ${result.barcode}", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSave, enabled = !saved && !importInProgress) {
                Text(
                    when {
                        saved -> "Saved offline"
                        importing -> "Validating…"
                        else -> "Save offline"
                    },
                )
            }
        }
    }
}

private fun OnlineFoodCandidate.previewText(): String {
    val energy = caloriesPer100g ?: return "Nutrition preview incomplete; the full label will be checked before saving."
    val pieces = mutableListOf(String.format(Locale.US, "%.0f kcal / 100 g or ml", energy))
    proteinPer100g?.let { pieces += String.format(Locale.US, "P %.1f", it) }
    carbsPer100g?.let { pieces += String.format(Locale.US, "C %.1f", it) }
    fatPer100g?.let { pieces += String.format(Locale.US, "F %.1f", it) }
    return pieces.joinToString(" · ")
}
