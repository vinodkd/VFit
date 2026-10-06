package com.vfit.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vfit.app.data.DayEntry
import com.vfit.app.data.GLUCOSE_FASTING
import com.vfit.app.data.GLUCOSE_POST_MEAL
import com.vfit.app.data.GlucoseReading
import com.vfit.app.data.LiftSet
import com.vfit.app.data.MealEntry
import com.vfit.app.data.Repository
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val ACTIVITIES = listOf("Sedentary", "Light", "Moderate", "Heavy")

@Composable
fun TodayScreen(vm: AppViewModel, onManageMeals: () -> Unit) {
    val date by vm.date.collectAsState()
    val days by vm.days.collectAsState()
    val allMeals by vm.meals.collectAsState()
    val templates by vm.templates.collectAsState()
    val allGlucose by vm.glucose.collectAsState()
    val allLifts by vm.lifts.collectAsState()
    val settings by vm.settings.collectAsState()
    val model by vm.model.collectAsState()
    val u = Units(settings)
    val e = date.toEpochDay()
    val entry = days?.firstOrNull { it.date == e }
    val meals = allMeals.filter { it.date == e }
    val glucose = allGlucose.filter { it.date == e }
    val lifts = allLifts.filter { it.date == e }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateBar(date, vm::selectDate)

        model?.outputs?.firstOrNull { it.date == date }?.let { o ->
            Text(
                "Trend ${u.weight(o.trendWeight)}" + (o.tdeeEst?.let { "  ·  TDEE ${kcal(it)}" } ?: "") +
                    (o.adaptation?.let { "  ·  adaptation %+,.0f".format(Locale.US, it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Body measurements: reload the editor when the date changes or the database first loads.
        key(date, days != null) {
            BodyCard(entry, e, u, onSave = vm::saveDay)
        }

        MealsCard(
            meals = meals, templates = templates.filter { !it.archived }, glucose = glucose, u = u,
            total = Repository.dayCalories(entry, meals),
            override = entry?.caloriesOverride,
            onAdd = { vm.addMeal(date, it) },
            onOneOff = { name, kcal, save -> vm.addOneOffMeal(date, name, kcal, save) },
            onQty = { m, q -> vm.updateMeal(m.copy(quantity = q)) },
            onDelete = { vm.deleteMeal(it.id) },
            onGlucose = { m, v ->
                val t = m.time?.let { it + 120 } ?: nowOr(date, 13 * 60)
                vm.saveGlucose(GlucoseReading(date = e, time = t.coerceAtMost(23 * 60 + 59), kind = GLUCOSE_POST_MEAL, mealEntryId = m.id, value = v))
            },
            onDeleteGlucose = { vm.deleteGlucose(it.id) },
            onClearOverride = { entry?.let { vm.saveDay(it.copy(caloriesOverride = null)) } },
            onManage = onManageMeals,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fasting glucose", style = MaterialTheme.typography.titleMedium)
                val fasting = glucose.filter { it.kind == GLUCOSE_FASTING }
                for (g in fasting) ReadingRow("${Repository.hhmm(g.time)}  ${u.glucose(g.value)}") { vm.deleteGlucose(g.id) }
                if (fasting.isEmpty()) GlucoseInput(u) { v ->
                    vm.saveGlucose(GlucoseReading(date = e, time = nowOr(date, 7 * 60), kind = GLUCOSE_FASTING, value = v))
                }
            }
        }

        LiftsCard(lifts, allLifts.map { it.lift }.distinct().sorted(), e, vm::saveLift) { vm.deleteLift(it.date, it.lift) }
        Spacer(Modifier.padding(8.dp))
    }
}

private fun nowOr(date: LocalDate, fallback: Int) =
    if (date == LocalDate.now()) LocalTime.now().let { it.hour * 60 + it.minute } else fallback

@Composable
private fun DateBar(date: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
        Text(
            date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
        )
        if (date != LocalDate.now()) TextButton(onClick = { onChange(LocalDate.now()) }) { Text("Today") }
        IconButton(onClick = { onChange(date.plusDays(1)) }, enabled = date < LocalDate.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BodyCard(entry: DayEntry?, epochDay: Long, u: Units, onSave: (DayEntry) -> Unit) {
    var weight by remember { mutableStateOf(u.field(entry?.weight?.let(u::wOut))) }
    var waist by remember { mutableStateOf(u.field(entry?.waist?.let(u::lenOut))) }
    var grip by remember { mutableStateOf(u.field(entry?.grip)) }
    var activity by remember { mutableStateOf(entry?.activity) }
    var saved by remember { mutableStateOf(true) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Body", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Weight (${u.w})", weight, Modifier.weight(1f)) { weight = it; saved = false }
                NumField("Waist (${u.len})", waist, Modifier.weight(1f)) { waist = it; saved = false }
                NumField("Grip", grip, Modifier.weight(0.8f)) { grip = it; saved = false }
            }
            Text("Activity", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ACTIVITIES.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = activity == i,
                        onClick = { activity = if (activity == i) null else i; saved = false },
                        shape = SegmentedButtonDefaults.itemShape(i, ACTIVITIES.size),
                    ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
            }
            Button(
                enabled = !saved,
                onClick = {
                    onSave(
                        (entry ?: DayEntry(epochDay)).copy(
                            weight = parseNum(weight)?.let(u::wIn),
                            waist = parseNum(waist)?.let(u::lenIn),
                            grip = parseNum(grip),
                            activity = activity,
                        )
                    )
                    saved = true
                },
            ) { Text(if (saved) "Saved" else "Save") }
        }
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, maxLines = 1) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun ReadingRow(text: String, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "Delete") }
    }
}

@Composable
private fun GlucoseInput(u: Units, label: String = "Glucose (${u.glu})", onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(label, text, Modifier.weight(1f)) { text = it }
        OutlinedButton(enabled = parseNum(text) != null, onClick = { parseNum(text)?.let { onSave(u.gluIn(it)) }; text = "" }) {
            Text("Save")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MealsCard(
    meals: List<MealEntry>,
    templates: List<com.vfit.app.data.MealTemplate>,
    glucose: List<GlucoseReading>,
    u: Units,
    total: Double?,
    override: Int?,
    onAdd: (com.vfit.app.data.MealTemplate) -> Unit,
    onOneOff: (String, Int, Boolean) -> Unit,
    onQty: (MealEntry, Double) -> Unit,
    onDelete: (MealEntry) -> Unit,
    onGlucose: (MealEntry, Double) -> Unit,
    onDeleteGlucose: (GlucoseReading) -> Unit,
    onClearOverride: () -> Unit,
    onManage: () -> Unit,
) {
    var showOneOff by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Meals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(total?.let { "${it.roundToInt()} kcal" } ?: "no calories", style = MaterialTheme.typography.titleMedium)
            }
            if (override != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Using imported total of $override kcal for this day.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClearOverride) { Text("Use meals") }
            }
            if (templates.isEmpty()) Text("No saved meals yet. Add a one-off meal and tick \"save\", or use Manage.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in templates) AssistChip(onClick = { onAdd(t) }, label = { Text("${t.name} · ${t.kcal}") })
            }
            for (m in meals) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (m.time?.let { Repository.hhmm(it) + "  " } ?: "") + m.name +
                                "  ${(m.kcal * m.quantity).roundToInt()} kcal",
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { onQty(m, (m.quantity - 0.5).coerceAtLeast(0.5)) }) { Text("−") }
                        Text("×" + "%.1f".format(Locale.US, m.quantity).removeSuffix(".0"))
                        TextButton(onClick = { onQty(m, m.quantity + 0.5) }) { Text("+") }
                        IconButton(onClick = { onDelete(m) }) { Icon(Icons.Filled.Close, "Remove meal") }
                    }
                    val g = glucose.firstOrNull { it.kind == GLUCOSE_POST_MEAL && it.mealEntryId == m.id }
                    if (g != null) ReadingRow("   2 h after: ${u.glucose(g.value)} at ${Repository.hhmm(g.time)}") { onDeleteGlucose(g) }
                    else GlucoseInput(u, "2 h after (${u.glu})") { onGlucose(m, it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showOneOff = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("One-off meal") }
                TextButton(onClick = onManage) { Text("Manage meals") }
            }
        }
    }
    if (showOneOff) OneOffDialog(onDismiss = { showOneOff = false }) { name, kcal, save -> onOneOff(name, kcal, save); showOneOff = false }
}

@Composable
private fun OneOffDialog(onDismiss: () -> Unit, onAdd: (String, Int, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                NumField("kcal", kcal) { kcal = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it }); Text("Save as a meal for next time")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && parseNum(kcal) != null,
                onClick = { onAdd(name.trim(), parseNum(kcal)!!.roundToInt(), save) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LiftsCard(lifts: List<LiftSet>, known: List<String>, epochDay: Long, onSave: (LiftSet) -> Unit, onDelete: (LiftSet) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lifts (top set)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { show = true }) { Text("Add set") }
            }
            for (l in lifts) ReadingRow("${l.lift}: ${fmt(l.load)} × ${l.reps}  (e1RM ${fmt(l.e1rm)})") { onDelete(l) }
        }
    }
    if (show) {
        var lift by remember { mutableStateOf(known.firstOrNull() ?: "") }
        var load by remember { mutableStateOf("") }
        var reps by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Top working set") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lift, { lift = it }, label = { Text("Lift (e.g. squat)") }, singleLine = true)
                    if (known.isNotEmpty()) Text("Known: " + known.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    NumField("Load", load) { load = it }
                    NumField("Reps", reps) { reps = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = lift.isNotBlank() && parseNum(load) != null && (parseNum(reps) ?: 0.0) >= 1,
                    onClick = {
                        onSave(LiftSet(epochDay, lift.trim().lowercase(), parseNum(load)!!, parseNum(reps)!!.roundToInt()))
                        show = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

private fun fmt(v: Double) = "%.1f".format(Locale.US, v).removeSuffix(".0")
