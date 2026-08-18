package com.johnny9.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.johnny9.calorietracker.AppViewModel
import com.johnny9.calorietracker.domain.TargetCalculator
import com.johnny9.calorietracker.domain.TargetInput
import com.johnny9.calorietracker.domain.UnitConverter
import com.johnny9.calorietracker.domain.UnitSystem
import com.johnny9.calorietracker.domain.fromMilli
import com.johnny9.calorietracker.health.HealthAvailability
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onRequestHealthPermission: () -> Unit,
    onExport: () -> Unit,
) {
    val plan by viewModel.target.collectAsState()
    val health by viewModel.healthState.collectAsState()

    var age by remember { mutableStateOf("35") }
    var unitSystem by remember { mutableStateOf(UnitSystem.METRIC) }
    var heightCm by remember { mutableStateOf("170") }
    var heightFeet by remember { mutableStateOf("5") }
    var heightInches by remember { mutableStateOf("6.93") }
    var weightKg by remember { mutableStateOf("70") }
    var weightPounds by remember { mutableStateOf("154.32") }
    var coefficient by remember { mutableDoubleStateOf(-161.0) }
    var customBmr by remember { mutableStateOf("") }
    var fixedMode by remember { mutableStateOf(true) }
    var fixedCalories by remember { mutableStateOf("2000") }
    var activityFactor by remember { mutableStateOf("1.2") }
    var goalAdjustment by remember { mutableStateOf("0") }
    var macroMode by remember { mutableStateOf("PERCENT") }
    var protein by remember { mutableStateOf("30") }
    var carbs by remember { mutableStateOf("40") }
    var fat by remember { mutableStateOf("30") }
    var useHealth by remember { mutableStateOf(false) }

    LaunchedEffect(plan?.updatedAtEpochMs) {
        plan?.let {
            age = it.ageYears.toString()
            unitSystem = UnitSystem.fromStorage(it.unitSystem)
            val canonicalHeightCm = it.heightMilliCm.fromMilli()
            val canonicalWeightKg = it.weightMilliKg.fromMilli()
            val usHeight = UnitConverter.centimetersToUsHeight(canonicalHeightCm)
            heightCm = cleanNumber(canonicalHeightCm)
            heightFeet = usHeight.feet.toString()
            heightInches = cleanNumber(usHeight.inches)
            weightKg = cleanNumber(canonicalWeightKg)
            weightPounds = cleanNumber(UnitConverter.kilogramsToPounds(canonicalWeightKg))
            coefficient = it.equationCoefficient
            customBmr = it.customBmrMilliKcal?.fromMilli()?.let(::cleanNumber).orEmpty()
            fixedMode = it.targetMode == "FIXED"
            fixedCalories = it.fixedTargetMilliKcal?.fromMilli()?.let(::cleanNumber) ?: cleanNumber(it.targetCaloriesMilliKcal.fromMilli())
            activityFactor = cleanNumber(it.activityFactor)
            goalAdjustment = cleanNumber(it.goalAdjustmentMilliKcal.fromMilli())
            macroMode = it.macroMode
            protein = cleanNumber(it.proteinTarget)
            carbs = cleanNumber(it.carbsTarget)
            fat = cleanNumber(it.fatTarget)
            useHealth = it.useHealthConnect
        }
    }

    fun selectUnitSystem(next: UnitSystem) {
        if (unitSystem == next) return
        when (next) {
            UnitSystem.METRIC -> {
                val feet = heightFeet.toIntOrNull()
                val inches = heightInches.toDoubleOrNull()
                if (feet != null && inches != null) {
                    heightCm = cleanNumber(UnitConverter.feetAndInchesToCentimeters(feet, inches))
                }
                weightPounds.toDoubleOrNull()?.let {
                    weightKg = cleanNumber(UnitConverter.poundsToKilograms(it))
                }
            }
            UnitSystem.US -> {
                heightCm.toDoubleOrNull()?.let {
                    val usHeight = UnitConverter.centimetersToUsHeight(it)
                    heightFeet = usHeight.feet.toString()
                    heightInches = cleanNumber(usHeight.inches)
                }
                weightKg.toDoubleOrNull()?.let {
                    weightPounds = cleanNumber(UnitConverter.kilogramsToPounds(it))
                }
            }
        }
        unitSystem = next
    }

    val canonicalBody = runCatching {
        when (unitSystem) {
            UnitSystem.METRIC -> heightCm.toDouble() to weightKg.toDouble()
            UnitSystem.US -> {
                val feet = heightFeet.toInt()
                val inches = heightInches.toDouble()
                require(feet >= 0 && inches >= 0.0 && inches < 12.0)
                UnitConverter.feetAndInchesToCentimeters(feet, inches) to
                    UnitConverter.poundsToKilograms(weightPounds.toDouble())
            }
        }
    }.getOrNull()
    val input = canonicalBody?.let { (canonicalHeightCm, canonicalWeightKg) ->
        runCatching {
            TargetInput(
                ageYears = age.toInt(),
                heightCm = canonicalHeightCm,
                weightKg = canonicalWeightKg,
                equationCoefficient = coefficient,
                customBmrKcal = customBmr.toDoubleOrNull(),
                fixedTargetKcal = if (fixedMode) fixedCalories.toDouble() else null,
                activityFactor = activityFactor.toDouble(),
                goalAdjustmentKcal = goalAdjustment.toDouble(),
            )
        }.getOrNull()
    }
    val preview = input?.let { runCatching { TargetCalculator.calculate(it) }.getOrNull() }
    val macros = listOf(protein, carbs, fat).map { it.toDoubleOrNull() }
    val macroValid = macros.all { it != null && it >= 0 } && (macroMode == "GRAMS" || kotlin.math.abs(macros.filterNotNull().sum() - 100.0) < 0.01)
    val canSave = preview != null && macroValid

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Profile & targets", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("BMI is context only. Calorie estimates use your selected Mifflin–St Jeor coefficient and activity factor.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Body inputs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = unitSystem == UnitSystem.METRIC,
                            onClick = { selectUnitSystem(UnitSystem.METRIC) },
                            label = { Text("Metric") },
                        )
                        FilterChip(
                            selected = unitSystem == UnitSystem.US,
                            onClick = { selectUnitSystem(UnitSystem.US) },
                            label = { Text("US") },
                        )
                    }
                    SettingsNumberField("Age (years)", age) { age = it }
                    when (unitSystem) {
                        UnitSystem.METRIC -> {
                            SettingsNumberField("Height (cm)", heightCm) { heightCm = it }
                            SettingsNumberField("Weight (kg)", weightKg) { weightKg = it }
                        }
                        UnitSystem.US -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsNumberField("Height (ft)", heightFeet, Modifier.weight(1f)) { heightFeet = it }
                                SettingsNumberField("Height (in)", heightInches, Modifier.weight(1f)) { heightInches = it }
                            }
                            SettingsNumberField("Weight (lb)", weightPounds) { weightPounds = it }
                        }
                    }
                    Text("Mifflin–St Jeor formula")
                    Text(
                        "This estimate uses a sex-specific equation term. Choose the formula matching the sex used for the calculation, or enter a measured BMR below to bypass it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = coefficient == 5.0, onClick = { coefficient = 5.0 }, label = { Text("Male formula (+5)") })
                        FilterChip(selected = coefficient == -161.0, onClick = { coefficient = -161.0 }, label = { Text("Female formula (−161)") })
                    }
                    SettingsNumberField("Measured or clinician-provided BMR (optional)", customBmr) { customBmr = it }
                    preview?.let {
                        Text(String.format(Locale.US, "BMI %.1f · BMR %.0f kcal/day", it.bmi, it.bmrKcal), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Calorie target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = fixedMode, onClick = { fixedMode = true }, label = { Text("Fixed") })
                        FilterChip(selected = !fixedMode, onClick = { fixedMode = false }, label = { Text("Calculated") })
                    }
                    if (fixedMode) {
                        SettingsNumberField("Target calories", fixedCalories) { fixedCalories = it }
                    } else {
                        SettingsNumberField("Activity factor", activityFactor) { activityFactor = it }
                        SettingsNumberField("Goal adjustment (kcal/day)", goalAdjustment) { goalAdjustment = it }
                    }
                    preview?.let { Text(String.format(Locale.US, "Saved target will be %.0f kcal/day", it.targetKcal), fontWeight = FontWeight.Bold) }
                    Text("Health Connect active calories affect Net; active and resting calories affect Total burn. Neither changes this saved target during the day.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Macro targets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = macroMode == "PERCENT", onClick = { macroMode = "PERCENT" }, label = { Text("Percent") })
                        FilterChip(selected = macroMode == "GRAMS", onClick = { macroMode = "GRAMS" }, label = { Text("Fixed grams") })
                    }
                    SettingsNumberField(if (macroMode == "PERCENT") "Protein (%)" else "Protein (g)", protein) { protein = it }
                    SettingsNumberField(if (macroMode == "PERCENT") "Carbohydrate (%)" else "Carbohydrate (g)", carbs) { carbs = it }
                    SettingsNumberField(if (macroMode == "PERCENT") "Fat (%)" else "Fat (g)", fat) { fat = it }
                    if (macroMode == "PERCENT" && !macroValid) Text("Percentages must total 100", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Energy from Health Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Use imported active and resting calories")
                            Text("Read-only and optional", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = useHealth, onCheckedChange = { useHealth = it })
                    }
                    Text(health.message, style = MaterialTheme.typography.bodySmall)
                    when {
                        health.availability != HealthAvailability.AVAILABLE -> OutlinedButton(onClick = {}, enabled = false) { Text("Health Connect unavailable") }
                        !health.hasPermission -> OutlinedButton(onClick = onRequestHealthPermission) { Text("Grant energy access") }
                        else -> {
                            OutlinedButton(onClick = viewModel::syncHealth) { Text("Sync last 30 days") }
                            if (!health.hasRestingPermission) {
                                OutlinedButton(onClick = onRequestHealthPermission) { Text("Grant resting-calorie access") }
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.saveTarget(
                        input = requireNotNull(input),
                        fixedMode = fixedMode,
                        macroMode = macroMode,
                        protein = requireNotNull(macros[0]),
                        carbs = requireNotNull(macros[1]),
                        fat = requireNotNull(macros[2]),
                        useHealthConnect = useHealth,
                        unitSystem = unitSystem,
                    )
                },
            ) { Text("Save profile and targets") }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Create a local ZIP of schema-versioned CSV files. It includes foods, recipes, diary snapshots, targets, activity, fasts, and daily summaries.")
                    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export all data") }
                    Text("Exports can contain sensitive diet and health information. Store them securely.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("Offline by default · no account · no ads · no analytics", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(value, onValueChange, modifier = modifier, label = { Text(label) }, singleLine = true)
}

private fun cleanNumber(value: Double): String = if (value == kotlin.math.round(value)) {
    String.format(Locale.US, "%.0f", value)
} else {
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
