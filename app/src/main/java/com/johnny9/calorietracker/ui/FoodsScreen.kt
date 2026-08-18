package com.johnny9.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.johnny9.calorietracker.AppViewModel
import com.johnny9.calorietracker.data.FoodEntity
import com.johnny9.calorietracker.domain.RecipeSummary
import com.johnny9.calorietracker.domain.UnitConverter
import com.johnny9.calorietracker.domain.UnitSystem
import com.johnny9.calorietracker.domain.cleanFoodName
import com.johnny9.calorietracker.domain.fromMilli
import java.util.Locale
import kotlin.math.round

@Composable
fun FoodsScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val foods by viewModel.foods.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val onlineSearch by viewModel.onlineFoodSearch.collectAsState()
    val usdaSearch by viewModel.usdaFoodSearch.collectAsState()
    val target by viewModel.target.collectAsState()
    var search by remember { mutableStateOf("") }
    var showFoodDialog by remember { mutableStateOf(false) }
    var showRecipeDialog by remember { mutableStateOf(false) }
    var showRecipes by remember { mutableStateOf(false) }

    val query = search.trim()
    val filteredFoods = foods.filter { it.matchesQuery(query) }
    val filteredRecipes = recipes.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }

    LaunchedEffect(query, showRecipes) {
        viewModel.searchUsdaFoods(if (!showRecipes && query.length >= 2) query else "")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Foods & recipes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Local foods, saved packaged foods, and everything you create remain available offline.")
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
        }

        if (!showRecipes && query.length >= 2) {
            item {
                UsdaFoodSearchPanel(
                    query = query,
                    state = usdaSearch,
                    savedFdcIds = foods.asSequence()
                        .filter { it.source.startsWith("USDA_FDC_") }
                        .mapNotNull { it.sourceId?.toLongOrNull() }
                        .toSet(),
                    onSelect = viewModel::saveUsdaFood,
                    actionLabel = "Save offline",
                    disableSaved = true,
                )
            }
        }
        if (!showRecipes && query.length >= 2 && viewModel.onlineFoodLookupAvailable) {
            item {
                OnlineFoodSearchPanel(
                    query = query,
                    state = onlineSearch,
                    savedBarcodes = foods.filter { it.source == "OPEN_FOOD_FACTS" }.mapNotNull(FoodEntity::sourceId).toSet(),
                    onSearch = viewModel::searchOnlineFoods,
                    onSave = viewModel::saveOnlineFood,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showRecipes, onClick = { showRecipes = false }, label = { Text("Foods (${filteredFoods.size})") })
                FilterChip(selected = showRecipes, onClick = { showRecipes = true }, label = { Text("Recipes (${filteredRecipes.size})") })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showFoodDialog = true }, modifier = Modifier.weight(1f)) { Text("New custom food") }
                Button(onClick = { showRecipeDialog = true }, modifier = Modifier.weight(1f), enabled = foods.isNotEmpty()) { Text("New recipe") }
            }
        }

        if (showRecipes) {
            items(filteredRecipes, key = RecipeSummary::id) { recipe ->
                RecipeRow(recipe, onArchive = { viewModel.archiveRecipe(recipe.id) })
            }
        } else {
            items(filteredFoods, key = FoodEntity::id) { food ->
                FoodRow(food, onArchive = { viewModel.archiveFood(food.id) })
            }
        }
    }

    if (showFoodDialog) {
        CustomFoodDialog(
            unitSystem = UnitSystem.fromStorage(target?.unitSystem),
            onDismiss = { showFoodDialog = false },
            onSave = { name, serving, grams, calories, protein, carbs, fat, fiber ->
                viewModel.createFood(name, serving, grams, calories, protein, carbs, fat, fiber)
                showFoodDialog = false
            },
        )
    }

    if (showRecipeDialog) {
        RecipeDialog(
            foods = foods,
            onDismiss = { showRecipeDialog = false },
            onSave = { name, servings, quantities ->
                viewModel.createRecipe(name, servings, quantities)
                showRecipeDialog = false
            },
        )
    }
}

@Composable
private fun FoodRow(food: FoodEntity, onArchive: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(cleanFoodName(food.name, food.brand), fontWeight = FontWeight.Medium)
                food.brand?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "${food.servingLabel} · ${formatNumber(food.caloriesMilliKcal.fromMilli())} kcal · P ${formatNumber(food.proteinMilliGram.fromMilli())} · C ${formatNumber(food.carbsMilliGram.fromMilli())} · F ${formatNumber(food.fatMilliGram.fromMilli())}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(food.sourceLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (food.isUserCreated || food.source == "OPEN_FOOD_FACTS" || food.source.startsWith("USDA_FDC_")) {
                IconButton(onClick = onArchive) { Icon(Icons.Default.Archive, contentDescription = "Archive ${food.name}") }
            }
        }
    }
}

@Composable
private fun RecipeRow(recipe: RecipeSummary, onArchive: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(recipe.name, fontWeight = FontWeight.Medium)
                Text(
                    "${recipe.ingredientCount} ingredients · ${formatNumber(recipe.nutrientsPerServing.caloriesMilliKcal.fromMilli())} kcal per serving",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onArchive) { Icon(Icons.Default.Archive, contentDescription = "Archive ${recipe.name}") }
        }
    }
}

@Composable
private fun CustomFoodDialog(
    unitSystem: UnitSystem,
    onDismiss: () -> Unit,
    onSave: (String, String, Double?, Double, Double, Double, Double, Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var serving by remember { mutableStateOf("1 serving") }
    var servingWeight by remember(unitSystem) { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var fiber by remember { mutableStateOf("") }
    val parsed = listOf(calories, protein, carbs, fat, fiber).map { it.toDoubleOrNull() }
    val parsedServingWeight = servingWeight.toDoubleOrNull()
    val servingWeightValid = servingWeight.isBlank() || (parsedServingWeight != null && parsedServingWeight > 0.0)
    val valid = name.isNotBlank() && serving.isNotBlank() && servingWeightValid && parsed.all { it != null && it >= 0.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom food") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) }
                item { OutlinedTextField(serving, { serving = it }, label = { Text("Serving label") }, singleLine = true) }
                item {
                    NumberField(
                        if (unitSystem == UnitSystem.US) "Serving weight (oz, optional)" else "Serving weight (g, optional)",
                        servingWeight,
                    ) { servingWeight = it }
                }
                item { NumberField("Calories (kcal)", calories) { calories = it } }
                item { NumberField("Protein (g)", protein) { protein = it } }
                item { NumberField("Carbohydrate (g)", carbs) { carbs = it } }
                item { NumberField("Fat (g)", fat) { fat = it } }
                item { NumberField("Fiber (g)", fiber) { fiber = it } }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val servingGrams = parsedServingWeight?.let {
                        if (unitSystem == UnitSystem.US) UnitConverter.ouncesToGrams(it) else it
                    }
                    onSave(name, serving, servingGrams, parsed[0]!!, parsed[1]!!, parsed[2]!!, parsed[3]!!, parsed[4]!!)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecipeDialog(
    foods: List<FoodEntity>,
    onDismiss: () -> Unit,
    onSave: (String, Double, Map<String, Double>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var servingsText by remember { mutableStateOf("1") }
    val quantities = remember { mutableStateMapOf<String, String>() }
    val parsed = quantities.mapNotNull { (id, value) -> value.toDoubleOrNull()?.takeIf { it > 0 }?.let { id to it } }.toMap()
    val servings = servingsText.toDoubleOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Recipe name") }, singleLine = true)
                NumberField("Number of finished servings", servingsText) { servingsText = it }
                Text("Ingredients", fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(foods, key = FoodEntity::id) { food ->
                        val selected = quantities.containsKey(food.id)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked -> if (checked) quantities[food.id] = "1" else quantities.remove(food.id) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(cleanFoodName(food.name, food.brand))
                                Text(food.servingLabel, style = MaterialTheme.typography.bodySmall)
                            }
                            if (selected) {
                                OutlinedTextField(
                                    value = quantities[food.id].orEmpty(),
                                    onValueChange = { quantities[food.id] = it },
                                    modifier = Modifier.width(80.dp),
                                    label = { Text("Qty") },
                                    singleLine = true,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && servings != null && parsed.isNotEmpty(), onClick = { onSave(name, servings!!, parsed) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true)
}

private fun formatNumber(value: Double): String = if (value == round(value)) {
    String.format(Locale.US, "%.0f", value)
} else {
    String.format(Locale.US, "%.1f", value)
}

internal fun FoodEntity.sourceLabel(): String = when (source) {
    "USDA_REFERENCE" -> "USDA reference"
    "BRAND_LABEL" -> "Manufacturer label"
    "OPEN_FOOD_FACTS" -> "Open Food Facts · community data"
    "USER_CUSTOM" -> "Custom food"
    else -> if (source.startsWith("USDA_FDC_")) {
        "USDA FoodData Central · ${source.removePrefix("USDA_FDC_").replace('_', ' ').lowercase()}"
    } else {
        source.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
    }
}

internal fun FoodEntity.matchesQuery(query: String): Boolean {
    val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.isEmpty()) return true
    val searchable = listOfNotNull(brand, name).joinToString(" ")
    return tokens.all { searchable.contains(it, ignoreCase = true) }
}
