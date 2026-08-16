package com.johnny9.calorietracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.johnny9.calorietracker.AppViewModel
import com.johnny9.calorietracker.MacroGoals
import com.johnny9.calorietracker.data.DiaryEntryEntity
import com.johnny9.calorietracker.data.FoodEntity
import com.johnny9.calorietracker.domain.RecipeSummary
import com.johnny9.calorietracker.domain.fromMilli
import java.time.format.DateTimeFormatter
import java.util.Locale

private val mealGroups = listOf("Breakfast", "Lunch", "Dinner", "Snacks")

@Composable
fun TodayScreen(viewModel: AppViewModel, padding: PaddingValues, onOpenSettings: () -> Unit) {
    val date by viewModel.selectedDate.collectAsState()
    val state by viewModel.today.collectAsState()
    val foods by viewModel.foods.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    var addToMeal by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { viewModel.moveDate(-1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Today", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")))
                }
                IconButton(onClick = { viewModel.moveDate(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
                }
            }
        }

        if (!state.targetConfigured) {
            item {
                Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Set up your targets", fontWeight = FontWeight.Bold)
                        Text("The starter 2,000 kcal target is only a placeholder. Add your body inputs or choose a fixed target.")
                        Text("Open settings", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryMetric("Intake", state.intakeMilliKcal, Modifier.weight(1f))
                    SummaryMetric("Active", state.activeMilliKcal, Modifier.weight(1f))
                    SummaryMetric("Net", state.netMilliKcal, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryMetric("Target", state.targetMilliKcal, Modifier.weight(1f))
                    SummaryMetric("Remaining", state.remainingMilliKcal, Modifier.weight(1f))
                }
                Text(
                    if (state.activeMilliKcal == null) {
                        "Active calories and Net are unavailable until this day is synced. Day status: ${state.completeness.name.lowercase().replace('_', ' ')}"
                    } else {
                        "Net = intake − active calories. Day status: ${state.completeness.name.lowercase().replace('_', ' ')}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { MacroSummary(state.nutrients.proteinMilliGram, state.nutrients.carbsMilliGram, state.nutrients.fatMilliGram, state.macroGoals) }

        mealGroups.forEach { meal ->
            item {
                MealSection(
                    name = meal,
                    entries = state.entries.filter { it.mealGroup == meal },
                    onAdd = { addToMeal = meal },
                    onDelete = viewModel::deleteEntry,
                )
            }
        }

        item {
            Button(onClick = viewModel::toggleDayComplete, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isDayComplete) "Reopen day" else "Mark day complete")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "A logged item does not complete a day. Rolling averages include only days you explicitly complete.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    addToMeal?.let { meal ->
        AddEntryDialog(
            meal = meal,
            foods = foods,
            recipes = recipes,
            onDismiss = { addToMeal = null },
            onFood = { id, quantity -> viewModel.logFood(id, meal, quantity); addToMeal = null },
            onRecipe = { id, quantity -> viewModel.logRecipe(id, meal, quantity); addToMeal = null },
        )
    }
}

@Composable
private fun SummaryMetric(label: String, milliKcal: Long?, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                milliKcal?.let { String.format(Locale.US, "%.0f", it.fromMilli()) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(if (milliKcal == null) "sync needed" else "kcal", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MacroSummary(protein: Long, carbs: Long, fat: Long, goals: MacroGoals) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Macros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            MacroBar("Protein", protein.fromMilli(), goals.proteinGram)
            MacroBar("Carbs", carbs.fromMilli(), goals.carbsGram)
            MacroBar("Fat", fat.fromMilli(), goals.fatGram)
        }
    }
}

@Composable
private fun MacroBar(label: String, value: Double, goal: Double) {
    val progress = if (goal > 0) (value / goal).toFloat().coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(String.format(Locale.US, "%.1f / %.1f g", value, goal))
    }
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MealSection(name: String, entries: List<DiaryEntryEntity>, onAdd: () -> Unit, onDelete: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add to $name") }
            }
            if (entries.isEmpty()) {
                Text("Nothing logged", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.nameSnapshot, fontWeight = FontWeight.Medium)
                            Text(
                                "${entry.quantity} × ${entry.servingLabelSnapshot} · ${entry.caloriesMilliKcal.fromMilli().roundToInt()} kcal",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onDelete(entry.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${entry.nameSnapshot}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddEntryDialog(
    meal: String,
    foods: List<FoodEntity>,
    recipes: List<RecipeSummary>,
    onDismiss: () -> Unit,
    onFood: (String, Double) -> Unit,
    onRecipe: (String, Double) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    val quantity = quantityText.toDoubleOrNull()?.takeIf { it > 0 }
    val normalized = query.trim()
    val visibleFoods = foods.filter { normalized.isEmpty() || it.name.contains(normalized, ignoreCase = true) }
    val visibleRecipes = recipes.filter { normalized.isEmpty() || it.name.contains(normalized, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to $meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search foods and recipes") }, singleLine = true)
                OutlinedTextField(quantityText, { quantityText = it }, label = { Text("Number of servings") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (visibleFoods.isNotEmpty()) {
                        item { Text("Foods", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        items(visibleFoods, key = FoodEntity::id) { food ->
                            EntryChoice(
                                title = food.name,
                                subtitle = "${food.servingLabel} · ${food.caloriesMilliKcal.fromMilli().roundToInt()} kcal · ${food.source.replace('_', ' ')}",
                                enabled = quantity != null,
                                onClick = { quantity?.let { onFood(food.id, it) } },
                            )
                        }
                    }
                    if (visibleRecipes.isNotEmpty()) {
                        item { Text("Recipes", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        items(visibleRecipes, key = RecipeSummary::id) { recipe ->
                            EntryChoice(
                                title = recipe.name,
                                subtitle = "1 serving · ${recipe.nutrientsPerServing.caloriesMilliKcal.fromMilli().roundToInt()} kcal",
                                enabled = quantity != null,
                                onClick = { quantity?.let { onRecipe(recipe.id, it) } },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EntryChoice(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Text("Add", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
