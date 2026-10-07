package com.vfit.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vfit.app.data.DayEntry
import com.vfit.app.data.ExerciseEntry
import com.vfit.app.data.GLUCOSE_FASTING
import com.vfit.app.data.GLUCOSE_POST_MEAL
import com.vfit.app.data.GlucoseReading
import com.vfit.app.data.LiftSet
import com.vfit.app.data.MealEntry
import com.vfit.app.data.MealTemplate
import com.vfit.app.data.Repository
import com.vfit.app.data.Slot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
    val allExercise by vm.exercise.collectAsState()
    val settings by vm.settings.collectAsState()
    val model by vm.model.collectAsState()
    val u = Units(settings)
    val ac = LocalAccents.current
    val e = date.toEpochDay()
    val entry = days?.firstOrNull { it.date == e }
    val meals = allMeals.filter { it.date == e }
    val glucose = allGlucose.filter { it.date == e }
    val exercise = allExercise.filter { it.date == e }
    val lifts = allLifts.filter { it.date == e }
    val activeTemplates = templates.orEmpty().filter { !it.archived }
    val total = Repository.dayCalories(entry, meals)
    val out = model?.outputs?.firstOrNull { it.date == date }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        DateBar(date, vm::selectDate)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            StatPill("Eaten", total?.let { "${it.roundToInt()} kcal" } ?: "—")
            out?.let { StatPill("Trend", u.weight(it.trendWeight)) }
            out?.tdeeEst?.let { StatPill("TDEE", "${kcal(it)} kcal") }
        }

        val bySlot = meals.groupBy { Slot.ofMeal(it) }
        // meals the timeline can't place (no slot, no time) go in a catch-all item before End of day
        val unplaced = bySlot[null].orEmpty()
        val items = Slot.entries.filter { it != Slot.END_OF_DAY }
        for (slot in items) {
            when (slot.kind) {
                Slot.Kind.BODY -> TimelineItem(slot.label, ac.body, done = entry?.weight != null, first = true,
                    summary = entry?.weight?.let { u.weight(it) }) {
                    key(date, days != null) { MorningContent(entry, glucose, u, vm, date) }
                }
                Slot.Kind.MEAL, Slot.Kind.SNACK -> {
                    val list = bySlot[slot].orEmpty()
                    TimelineItem(slot.label, if (slot.kind == Slot.Kind.MEAL) ac.meal else ac.snack, done = list.isNotEmpty(),
                        summary = list.takeIf { it.isNotEmpty() }?.let { "${it.sumOf { m -> m.kcal * m.quantity }.roundToInt()} kcal" }) {
                        FoodContent(slot, list, rankTemplates(activeTemplates, allMeals, slot), vm, date, onManageMeals)
                    }
                    // post-meal blood sugar is its own step, shown once something was eaten in this slot
                    if (list.isNotEmpty()) {
                        val reading = glucose.firstOrNull { g -> g.kind == GLUCOSE_POST_MEAL && list.any { it.id == g.mealEntryId } }
                        TimelineItem("Blood sugar 2 h after ${slot.label.lowercase()}", ac.glucose, done = reading != null,
                            summary = reading?.let { u.glucose(it.value) }) {
                            PostMealGlucose(list, reading, u, vm, date)
                        }
                    }
                }
                Slot.Kind.EXERCISE -> {
                    val list = exercise.filter { it.slot == slot.id }
                    val sets = lifts.filter { it.slot == slot.id }
                    TimelineItem(slot.label, ac.exercise, done = list.isNotEmpty() || sets.isNotEmpty(),
                        summary = list.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.let { "${it.sum()} min" }) {
                        ExerciseContent(slot, list, sets, allExercise, allLifts, e, vm)
                    }
                }
                Slot.Kind.DAY -> {}
            }
        }
        if (unplaced.isNotEmpty()) TimelineItem("Other meals", ac.meal, done = true,
            summary = "${unplaced.sumOf { it.kcal * it.quantity }.roundToInt()} kcal") {
            for (m in unplaced) MealRow(m, vm)
        }
        TimelineItem(Slot.END_OF_DAY.label, ac.day, done = entry?.activity != null, last = true,
            summary = entry?.activity?.let { ACTIVITIES[it] }) {
            EndOfDayContent(entry, vm, date)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Saved meals ordered by how often they were logged in this slot, then by recency. */
private fun rankTemplates(templates: List<MealTemplate>, meals: List<MealEntry>, slot: Slot): List<MealTemplate> {
    val counts = meals.filter { Slot.ofMeal(it) == slot && it.templateId != null }.groupingBy { it.templateId!! }.eachCount()
    return templates.sortedWith(compareByDescending<MealTemplate> { counts[it.id] ?: 0 }.thenByDescending { it.lastUsed })
}

private fun nowOr(date: LocalDate, fallback: Int) =
    if (date == LocalDate.now()) LocalTime.now().let { it.hour * 60 + it.minute } else fallback

// ------------------------------------------------------------------ building blocks

@Composable
private fun StatPill(label: String, value: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A step on the day's timeline: a dot (filled when done) on a vertical line, and a tinted card. */
@Composable
private fun TimelineItem(
    title: String, accent: Color, done: Boolean, summary: String? = null,
    first: Boolean = false, last: Boolean = false, content: @Composable () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().drawBehind {
            val x = 11.dp.toPx()
            val dotY = 26.dp.toPx()
            val r = 7.dp.toPx()
            if (!first) drawLine(lineColor, Offset(x, 0f), Offset(x, dotY - r), 2.dp.toPx())
            if (!last) drawLine(lineColor, Offset(x, dotY + r), Offset(x, size.height), 2.dp.toPx())
            if (done) drawCircle(accent, r, Offset(x, dotY))
            else drawCircle(accent, r - 1.dp.toPx(), Offset(x, dotY), style = Stroke(2.dp.toPx()))
        },
    ) {
        Spacer(Modifier.width(30.dp))
        Card(
            Modifier.weight(1f).padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = accent, modifier = Modifier.weight(1f))
                    summary?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                }
                content()
            }
        }
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier = Modifier, suffix: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, maxLines = 1) }, singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun RemovableRow(text: String, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Delete", Modifier.size(18.dp)) }
    }
}

@Composable
private fun GlucoseInput(u: Units, label: String, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(label, text, Modifier.weight(1f), suffix = u.glu) { text = it }
        OutlinedButton(enabled = parseNum(text) != null, onClick = { parseNum(text)?.let { onSave(u.gluIn(it)) }; text = "" }) { Text("Save") }
    }
}

// ------------------------------------------------------------------ morning

@Composable
private fun MorningContent(entry: DayEntry?, glucose: List<GlucoseReading>, u: Units, vm: AppViewModel, date: LocalDate) {
    val ac = LocalAccents.current
    val fasting = glucose.firstOrNull { it.kind == GLUCOSE_FASTING }
    var weight by remember { mutableStateOf(u.field(entry?.weight?.let(u::wOut))) }
    var waist by remember { mutableStateOf(u.field(entry?.waist?.let(u::lenOut))) }
    var grip by remember { mutableStateOf(u.field(entry?.grip)) }
    var sugar by remember { mutableStateOf(u.field(fasting?.value?.let(u::gluOut), if (u.glu == "mg/dL") 0 else 1)) }
    var saved by remember { mutableStateOf(true) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField("Weight", weight, Modifier.weight(1f), suffix = u.w) { weight = it; saved = false }
        NumField("Fasting glucose", sugar, Modifier.weight(1f), suffix = u.glu) { sugar = it; saved = false }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField("Waist", waist, Modifier.weight(1f), suffix = u.len) { waist = it; saved = false }
        NumField("Grip (optional)", grip, Modifier.weight(1f)) { grip = it; saved = false }
    }
    Button(
        enabled = !saved, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = ac.body),
        onClick = {
            vm.saveMorning(date, parseNum(weight)?.let(u::wIn), parseNum(waist)?.let(u::lenIn), parseNum(grip),
                parseNum(sugar)?.let(u::gluIn), fasting)
            saved = true
        },
    ) { Text(if (saved) "Saved" else "Save check-in") }
}

// ------------------------------------------------------------------ meals & snacks

/** What was eaten in a slot, and a button to log more. Saved meals are offered only inside the log dialog. */
@Composable
private fun FoodContent(slot: Slot, list: List<MealEntry>, templates: List<MealTemplate>, vm: AppViewModel, date: LocalDate, onManage: () -> Unit) {
    var logging by remember { mutableStateOf(false) }
    if (list.isEmpty()) Text("Not logged yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (m in list) MealRow(m, vm)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { logging = true }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (list.isEmpty()) "Log ${slot.label.lowercase()}" else "Add more")
        }
        if (templates.isEmpty() && list.isEmpty()) TextButton(onClick = onManage) { Text("Saved meals") }
    }
    if (logging) LogMealDialog(slot, templates, defaultTime(date, slot), onDismiss = { logging = false }) { t, name, kcal, qty, time, save ->
        vm.logMeal(date, slot, t, name, kcal, qty, time, save); logging = false
    }
}

/** Now for today; otherwise a typical time for the slot. */
private fun defaultTime(date: LocalDate, slot: Slot): Int = if (date == LocalDate.now()) nowOr(date, 0) else when (slot) {
    Slot.BREAKFAST -> 8 * 60; Slot.MORNING_SNACK -> 10 * 60 + 30; Slot.LUNCH -> 13 * 60; Slot.AFTERNOON_SNACK -> 16 * 60
    Slot.DINNER -> 19 * 60; Slot.EVENING_SNACK -> 21 * 60; else -> 12 * 60
}

@Composable
private fun MealRow(m: MealEntry, vm: AppViewModel) {
    val ac = LocalAccents.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, "Eaten", tint = ac.exercise, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                (m.time?.let { Repository.hhmm(it) + " · " } ?: "") + "${(m.kcal * m.quantity).roundToInt()} kcal",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // quantity stepper, kept well apart from delete so taps don't land on the wrong one
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.updateMeal(m.copy(quantity = (m.quantity - 0.5).coerceAtLeast(0.5))) }, Modifier.size(40.dp)) {
                    Text("−", style = MaterialTheme.typography.titleMedium)
                }
                Text("×" + "%.1f".format(Locale.US, m.quantity).removeSuffix(".0"), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { vm.updateMeal(m.copy(quantity = m.quantity + 0.5)) }, Modifier.size(40.dp)) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = { vm.deleteMeal(m) }) {
            Icon(Icons.Filled.Delete, "Remove meal", tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Post-meal reading for a slot; linked to the slot's first meal, timed 2 h after it by default. */
@Composable
private fun PostMealGlucose(list: List<MealEntry>, reading: GlucoseReading?, u: Units, vm: AppViewModel, date: LocalDate) {
    if (reading != null) {
        RemovableRow("${u.glucose(reading.value)} at ${Repository.hhmm(reading.time)}") { vm.deleteGlucose(reading.id) }
        return
    }
    val first = list.minByOrNull { it.time ?: Int.MAX_VALUE }!!
    val due = first.time?.let { it + 120 }
    due?.let { Text("Due around ${Repository.hhmm(it.coerceAtMost(23 * 60 + 59))}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
    GlucoseInput(u, "Blood sugar") { v ->
        vm.saveGlucose(GlucoseReading(date = date.toEpochDay(), time = (due ?: nowOr(date, 13 * 60)).coerceAtMost(23 * 60 + 59),
            kind = GLUCOSE_POST_MEAL, mealEntryId = first.id, value = v))
    }
}

/** "Yes, I ate this": search saved meals (or type a new one), then amount and time. */
@Composable
private fun LogMealDialog(
    slot: Slot, templates: List<MealTemplate>, initialTime: Int, onDismiss: () -> Unit,
    onLog: (MealTemplate?, String, Int, Double, Int?, Boolean) -> Unit,
) {
    var picked by remember { mutableStateOf<MealTemplate?>(null) }
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(true) }
    var qty by remember { mutableStateOf(1.0) }
    var time by remember { mutableStateOf(Repository.hhmm(initialTime)) }
    val parsedTime = parseTime(time)
    val isNew = picked == null && name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log ${slot.label.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ComboField(
                    label = "Meal", text = name,
                    placeholder = if (templates.isEmpty()) "Name of the meal" else "Search saved meals or type a new one",
                    options = templates.map { ComboOption(it, it.name, "${it.kcal} kcal") },
                    onTextChange = { name = it; if (picked?.name != it) picked = null },
                    onPick = { picked = it.value; name = it.value.name },
                )
                when {
                    picked != null -> Text("Saved meal · ${picked!!.kcal} kcal", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    isNew -> {
                        NumField("Calories", kcal, suffix = "kcal") { kcal = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(save, { save = it }); Text("Save as a meal for next time")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Amount", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { qty = (qty - 0.5).coerceAtLeast(0.5) }) { Text("−", style = MaterialTheme.typography.titleMedium) }
                    Text("×" + "%.1f".format(Locale.US, qty).removeSuffix(".0"))
                    IconButton(onClick = { qty += 0.5 }) { Text("+", style = MaterialTheme.typography.titleMedium) }
                    OutlinedTextField(time, { time = it }, label = { Text("Time") }, singleLine = true, isError = parsedTime == null,
                        modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (picked != null || (isNew && parseNum(kcal) != null)) && parsedTime != null,
                onClick = { onLog(picked, name.trim(), parseNum(kcal)?.roundToInt() ?: 0, qty, parsedTime, save) },
            ) { Text("Log it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "8:05" / "08:05" / "805" -> minutes after midnight, or null. */
private fun parseTime(s: String): Int? {
    val t = s.trim()
    val (h, m) = when {
        ":" in t -> t.split(":").let { it[0] to it.getOrElse(1) { "" } }
        t.length in 3..4 -> t.dropLast(2) to t.takeLast(2)
        else -> return null
    }
    val hh = h.toIntOrNull() ?: return null
    val mm = m.toIntOrNull() ?: return null
    return if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
}

// ------------------------------------------------------------------ exercise

/** What was done in an exercise slot: activities (walk, gym...) and strength top sets. */
@Composable
private fun ExerciseContent(
    slot: Slot, list: List<ExerciseEntry>, sets: List<LiftSet>, history: List<ExerciseEntry>, liftHistory: List<LiftSet>,
    epochDay: Long, vm: AppViewModel,
) {
    // most recent entry per exercise / lift, newest first, for the dropdowns and prefills
    val pastExercise = history.sortedWith(compareByDescending<ExerciseEntry> { it.date }.thenByDescending { it.id })
        .distinctBy { it.description.lowercase() }
    val pastLifts = liftHistory.sortedByDescending { it.date }.distinctBy { it.lift }
    val ac = LocalAccents.current
    var logging by remember { mutableStateOf(false) }
    var addingSet by remember { mutableStateOf(false) }
    if (list.isEmpty() && sets.isEmpty())
        Text("Not logged yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (x in list) DoneRow(x.description, x.minutes?.let { "$it min" }, ac.exercise) { vm.deleteExercise(x.id) }
    for (l in sets) DoneRow("${l.lift} ${fmt(l.load)} × ${l.reps}", "top set · e1RM ${fmt(l.e1rm)}", ac.exercise) { vm.deleteLift(l.date, l.lift) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { logging = true }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Activity")
        }
        OutlinedButton(onClick = { addingSet = true }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Strength set")
        }
    }
    if (logging) {
        var desc by remember { mutableStateOf("") }
        var minutes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { logging = false },
            title = { Text("Log ${slot.label.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComboField(
                        label = "Exercise", text = desc,
                        placeholder = if (pastExercise.isEmpty()) "e.g. walk, gym" else "Pick one you've done or type a new one",
                        options = pastExercise.map { ComboOption(it, it.description, it.minutes?.let { m -> "$m min last time" }) },
                        onTextChange = { desc = it },
                        onPick = { desc = it.value.description; it.value.minutes?.let { m -> minutes = m.toString() } },
                    )
                    NumField("Duration", minutes, suffix = "min") { minutes = it }
                }
            },
            confirmButton = {
                TextButton(enabled = desc.isNotBlank(), onClick = {
                    vm.saveExercise(ExerciseEntry(date = epochDay, slot = slot.id, description = desc.trim(), minutes = parseNum(minutes)?.roundToInt()))
                    logging = false
                }) { Text("Log it") }
            },
            dismissButton = { TextButton(onClick = { logging = false }) { Text("Cancel") } },
        )
    }
    if (addingSet) {
        var lift by remember { mutableStateOf("") }
        var load by remember { mutableStateOf("") }
        var reps by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingSet = false },
            title = { Text("Strength: top working set") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComboField(
                        label = "Lift", text = lift,
                        placeholder = if (pastLifts.isEmpty()) "e.g. squat" else "Pick a lift or type a new one",
                        options = pastLifts.map { ComboOption(it, it.lift, "${fmt(it.load)} × ${it.reps} last time") },
                        onTextChange = { lift = it },
                        onPick = { lift = it.value.lift; load = fmt(it.value.load); reps = it.value.reps.toString() },
                    )
                    NumField("Load", load) { load = it }
                    NumField("Reps", reps) { reps = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = lift.isNotBlank() && parseNum(load) != null && (parseNum(reps) ?: 0.0) >= 1,
                    onClick = {
                        vm.saveLift(LiftSet(epochDay, lift.trim().lowercase(), parseNum(load)!!, parseNum(reps)!!.roundToInt(), slot.id))
                        addingSet = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { addingSet = false }) { Text("Cancel") } },
        )
    }
}

/** A logged item: check mark, title, detail, delete. */
@Composable
private fun DoneRow(title: String, detail: String?, tint: Color, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, "Done", tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }
}

// ------------------------------------------------------------------ end of day

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndOfDayContent(entry: DayEntry?, vm: AppViewModel, date: LocalDate) {
    Text("Overall activity today (used by the TDEE model)", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ACTIVITIES.forEachIndexed { i, label ->
            SegmentedButton(
                selected = entry?.activity == i,
                onClick = { vm.setActivity(date, if (entry?.activity == i) null else i) },
                shape = SegmentedButtonDefaults.itemShape(i, ACTIVITIES.size),
                icon = {},
            ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
    entry?.caloriesOverride?.let { o ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Calories for this day come from an imported total ($o kcal).", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.saveDay(entry.copy(caloriesOverride = null)) }) { Text("Use meals") }
        }
    }
}

private fun fmt(v: Double) = "%.1f".format(Locale.US, v).removeSuffix(".0")

// ------------------------------------------------------------------ date header

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateBar(date: LocalDate, onChange: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
        TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.DateRange, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (date == LocalDate.now()) "Today, " + date.format(DateTimeFormatter.ofPattern("d MMM"))
                else date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        IconButton(onClick = { onChange(date.plusDays(1)) }, enabled = date < LocalDate.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day")
        }
    }
    if (picking) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Go") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(today); picking = false }) { Text("Today") }
                    TextButton(onClick = { picking = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state) }
    }
}
