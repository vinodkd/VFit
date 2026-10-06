package com.vfit.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vfit.app.data.AppSettings
import com.vfit.app.data.GLUCOSE_FASTING
import com.vfit.app.data.GLUCOSE_POST_MEAL
import com.vfit.app.data.MealTemplate
import com.vfit.app.data.Repository
import com.vfit.model.Model
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Summary

@Composable
fun SummaryScreen(vm: AppViewModel) {
    val model by vm.model.collectAsState()
    val settings by vm.settings.collectAsState()
    val glucose by vm.glucose.collectAsState()
    val u = Units(settings)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val r = model
        if (r == null) {
            Text("No weights entered yet. Add today's weight on the Today tab, or import your data in Settings.")
        } else for (sec in summarize(r, settings)) SectionCard(sec)

        val since = LocalDate.now().minusDays(6).toEpochDay()
        val recent = glucose.filter { it.date >= since }
        fun avg(kind: Int) = recent.filter { it.kind == kind }.map { it.value }.takeIf { it.isNotEmpty() }?.average()
        SectionCard(
            SummarySection(
                "Glucose (last 7 days)",
                listOf(
                    "Fasting average" to (avg(GLUCOSE_FASTING)?.let { u.glucose(it) + "  (${recent.count { g -> g.kind == GLUCOSE_FASTING }} readings)" } ?: "-"),
                    "2 h after meals" to (avg(GLUCOSE_POST_MEAL)?.let { u.glucose(it) + "  (${recent.count { g -> g.kind == GLUCOSE_POST_MEAL }} readings)" } ?: "-"),
                ),
            )
        )
    }
}

@Composable
private fun SectionCard(sec: SummarySection) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sec.title, style = MaterialTheme.typography.titleMedium)
            for ((k, v) in sec.rows) {
                if (k.isEmpty()) Text(v, style = MaterialTheme.typography.bodyMedium)
                else Column {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Charts

private val BLUE = Color(0xFF1F4E79)
private val GREY = Color(0xFF9AA7B8)
private val ORANGE = Color(0xFFC55A11)
private val GREEN = Color(0xFF548235)
private val RED = Color(0xFFC00000)
private val PURPLE = Color(0xFF7030A0)
private val TEAL = Color(0xFF2E75B6)

@Composable
fun ChartsScreen(vm: AppViewModel) {
    val model by vm.model.collectAsState()
    val settings by vm.settings.collectAsState()
    val glucose by vm.glucose.collectAsState()
    val u = Units(settings)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val r = model
        if (r == null) {
            Text("No data yet.")
        } else {
            val xDays = r.dates.map { it.toEpochDay().toDouble() }
            val outX = r.outputs.map { it.date.toEpochDay().toDouble() }
            val nan = Double.NaN

            // 1. weight + forecast
            val weightSeries = arrayListOf<Series>(
                Series.Dots("Daily weight", GREY, xDays, r.weight.map { if (it.isNaN()) nan else u.wOut(it) }),
                Series.Line("Trend", BLUE, outX, r.outputs.map { u.wOut(it.trendWeight) }),
            )
            val o = r.latest!!
            if (o.settlingWeight != null) {
                val x0 = o.date.toEpochDay()
                val fx = (0..182 step 7).map { (x0 + it).toDouble() }
                fun curve(ws: Double) = (0..182 step 7).map { u.wOut(Model.forecast(o.trendWeight, ws, it, r.b, r.energyDensity)) }
                weightSeries += Series.Band("80% range", ORANGE, fx, curve(o.settlingLow!!), curve(o.settlingHigh!!))
                weightSeries += Series.Line("Forecast at current intake", ORANGE, fx, curve(o.settlingWeight!!), dashed = true)
            }
            settings.goalWeightKg?.let { weightSeries += Series.HLine("Goal", GREEN, u.wOut(it)) }
            LineChart("Weight: trend and forecast", u.w, weightSeries, decimals = 0)
            HorizontalDivider()

            // 2. TDEE vs intake
            val tdeeX = r.outputs.filter { it.tdeeEst != null }
            LineChart(
                "Estimated TDEE vs intake", "kcal/day",
                listOf(
                    Series.Dots("Daily intake", GREY, xDays, r.calories.toList()),
                    Series.Line("Intake, 14-day avg", Color(0xFF7F7F7F), outX, r.outputs.map { it.intakeAvg14d ?: nan }),
                    Series.Band("TDEE 80% range", RED, tdeeX.map { it.date.toEpochDay().toDouble() }, tdeeX.map { it.tdeeLow!! }, tdeeX.map { it.tdeeHigh!! }),
                    Series.Line("Estimated TDEE", RED, tdeeX.map { it.date.toEpochDay().toDouble() }, tdeeX.map { it.tdeeEst!! }),
                ),
            )
            HorizontalDivider()

            // 3. adaptation
            val ad = r.outputs.filter { it.adaptation != null }
            LineChart(
                "Adaptation (TDEE change beyond weight lost)", "kcal/day",
                listOf(
                    Series.HLine("No adaptation", GREY, 0.0),
                    Series.Line("Adaptation", PURPLE, ad.map { it.date.toEpochDay().toDouble() }, ad.map { it.adaptation!! }),
                ),
            )
            HorizontalDivider()

            // 4. waist + strength
            LineChart("Waist", u.len, listOf(Series.Dots("Waist", TEAL, xDays, r.waist.map { if (it.isNaN()) nan else u.lenOut(it) }),
                Series.Line("Waist trend", TEAL, xDays, rolling(r.waist.map { if (it.isNaN()) nan else u.lenOut(it) }, 7))), decimals = 0)
            if (r.strength.any { !it.isNaN() }) {
                LineChart("Strength index (vs baseline, 28-day window)", "%", listOf(
                    Series.HLine("Baseline", GREY, 0.0),
                    Series.Line("Strength index", PURPLE, xDays, r.strength.map { it * 100 }),
                ))
            }
        }
        HorizontalDivider()

        // 5. glucose
        if (glucose.isNotEmpty()) {
            fun pts(kind: Int) = glucose.filter { it.kind == kind }
            val f = pts(GLUCOSE_FASTING)
            val p = pts(GLUCOSE_POST_MEAL)
            LineChart(
                "Blood glucose", u.glu,
                listOf(
                    Series.Dots("Fasting", BLUE, f.map { it.date + it.time / 1440.0 }, f.map { u.gluOut(it.value) }),
                    Series.Dots("2 h after meal", ORANGE, p.map { it.date + it.time / 1440.0 }, p.map { u.gluOut(it.value) }),
                ),
                decimals = if (settings.glucoseMmol) 1 else 0,
            )
        }
    }
}

/** Trailing mean over the last `n` days, ignoring gaps; NaN when no readings in the window. */
private fun rolling(v: List<Double>, n: Int) = v.indices.map { i ->
    v.subList(maxOf(0, i - n + 1), i + 1).filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN
}

// ------------------------------------------------------------------ History

@Composable
fun HistoryScreen(vm: AppViewModel, onOpenDay: (LocalDate) -> Unit) {
    val model by vm.model.collectAsState()
    val days by vm.days.collectAsState()
    val meals by vm.meals.collectAsState()
    val settings by vm.settings.collectAsState()
    val u = Units(settings)
    val outs = model?.outputs?.associateBy { it.date } ?: emptyMap()
    val dayMap = days.orEmpty().associateBy { it.date }
    val mealMap = meals.groupBy { it.date }
    val dates = (dayMap.keys + mealMap.keys).sortedDescending()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Row(Modifier.padding(bottom = 8.dp)) {
                Text("Date", Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium)
                Text("Weight", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Trend", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("kcal", Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium)
                Text("TDEE", Modifier.weight(0.9f), style = MaterialTheme.typography.labelMedium)
            }
        }
        items(dates, key = { it }) { e ->
            val d = LocalDate.ofEpochDay(e)
            val o = outs[d]
            val cal = Repository.dayCalories(dayMap[e], mealMap[e].orEmpty())
            Row(Modifier.fillMaxWidth().clickable { onOpenDay(d) }.padding(vertical = 8.dp)) {
                Text(d.toString(), Modifier.weight(1.3f))
                Text(dayMap[e]?.weight?.let { "%.1f".format(Locale.US, u.wOut(it)) } ?: "", Modifier.weight(1f))
                Text(o?.let { "%.1f".format(Locale.US, u.wOut(it.trendWeight)) } ?: "", Modifier.weight(1f))
                Text(cal?.roundToInt()?.toString() ?: "", Modifier.weight(0.9f))
                Text(o?.tdeeEst?.let { kcal(it) } ?: "", Modifier.weight(0.9f))
            }
            HorizontalDivider()
        }
    }
}

// ------------------------------------------------------------------ Meals

@Composable
fun MealsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val templates by vm.templates.collectAsState()
    var editing by remember { mutableStateOf<MealTemplate?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Saved meals", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { editing = MealTemplate(name = "", kcal = 0) }) { Text("New") }
        }
        Text("Editing a meal changes future logs only; days already logged keep their calories.", style = MaterialTheme.typography.bodySmall)
        LazyColumn {
            items(templates.sortedWith(compareBy({ it.archived }, { it.name.lowercase() })), key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name + if (t.archived) " (hidden)" else "", Modifier.weight(1f))
                    Text("${t.kcal} kcal")
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        var kcalText by remember(t.id) { mutableStateOf(if (t.kcal == 0) "" else t.kcal.toString()) }
        var archived by remember(t.id) { mutableStateOf(t.archived) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (t.id == 0L) "New meal" else "Edit meal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    NumField("kcal", kcalText) { kcalText = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(archived, { archived = it }); Text("  Hide from Today")
                    }
                    if (t.id != 0L) TextButton(onClick = { vm.deleteTemplate(t.id); editing = null }) {
                        Icon(Icons.Filled.Delete, null); Text(" Delete")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && parseNum(kcalText) != null,
                    onClick = {
                        vm.saveTemplate(t.copy(name = name.trim(), kcal = parseNum(kcalText)!!.roundToInt(), archived = archived))
                        editing = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(vm: AppViewModel, onManageMeals: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importCsv) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let(vm::exportCsv) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        key(settings) { SettingsForm(settings, vm::saveSettings) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Data", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onManageMeals) { Text("Manage saved meals") }
                Text(
                    "Import: inputs.csv or config.csv from `tdee_tracker.py export-csv`, or a VFit export. " +
                        "Imported calories are kept as daily totals.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }) { Text("Import CSV") }
                OutlinedButton(onClick = { exporter.launch(null) }) { Text("Export CSVs to a folder") }
            }
        }
    }
}

@Composable
private fun SettingsForm(s: AppSettings, onSave: (AppSettings) -> Unit) {
    val u = Units(s)
    var imperial by remember { mutableStateOf(s.imperial) }
    var male by remember { mutableStateOf(s.male) }
    var mmol by remember { mutableStateOf(s.glucoseMmol) }
    val f = remember {
        mutableStateOf(
            mapOf(
                "age" to u.field(s.age, 0),
                "height" to u.field(u.lenOut(s.heightCm), 1),
                "goal_weight" to u.field(s.goalWeightKg?.let(u::wOut), 1),
                "goal_rate" to u.field(u.wOut(s.goalRateKg), 2),
                "b" to u.field(s.b, 1),
                "rho" to u.field(s.energyDensity, 0),
                "noise" to u.field(s.weightNoiseKg, 2),
                "sed" to u.field(s.offsetSedentary, 0),
                "mod" to u.field(s.offsetModerate, 0),
                "heavy" to u.field(s.offsetHeavy, 0),
                "ref" to (s.adaptationRefDate?.toString() ?: ""),
            )
        )
    }
    fun v(k: String) = f.value[k].orEmpty()
    fun set(k: String, x: String) { f.value = f.value + (k to x) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Choice("Units", listOf("Metric (kg, cm)", "Imperial (lb, in)"), if (imperial) 1 else 0) { imperial = it == 1 }
            Choice("Sex", listOf("Male", "Female"), if (male) 0 else 1) { male = it == 0 }
            Choice("Glucose", listOf("mg/dL", "mmol/L"), if (mmol) 1 else 0) { mmol = it == 1 }
            Text("Entered in the units shown when this form was opened (${u.w}, ${u.len}). Save, then reopen to switch.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Age", v("age"), Modifier.weight(1f)) { set("age", it) }
                NumField("Height (${u.len})", v("height"), Modifier.weight(1f)) { set("height", it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Goal weight (${u.w})", v("goal_weight"), Modifier.weight(1f)) { set("goal_weight", it) }
                NumField("Goal rate (${u.w}/wk)", v("goal_rate"), Modifier.weight(1f)) { set("goal_rate", it) }
            }
            OutlinedTextField(v("ref"), { set("ref", it) }, label = { Text("Adaptation reference date (YYYY-MM-DD, blank = first TDEE day)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Model constants", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("b (kcal/kg)", v("b"), Modifier.weight(1f)) { set("b", it) }
                NumField("kcal per kg", v("rho"), Modifier.weight(1f)) { set("rho", it) }
                NumField("Scale noise kg", v("noise"), Modifier.weight(1f)) { set("noise", it) }
            }
            Text("Starting activity offsets vs a light day (kcal)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Sedentary", v("sed"), Modifier.weight(1f)) { set("sed", it) }
                NumField("Moderate", v("mod"), Modifier.weight(1f)) { set("mod", it) }
                NumField("Heavy", v("heavy"), Modifier.weight(1f)) { set("heavy", it) }
            }
            Button(onClick = {
                onSave(
                    s.copy(
                        imperial = imperial, male = male, glucoseMmol = mmol,
                        age = parseNum(v("age")) ?: s.age,
                        heightCm = parseNum(v("height"))?.let(u::lenIn) ?: s.heightCm,
                        goalWeightKg = parseNum(v("goal_weight"))?.let(u::wIn),
                        goalRateKg = parseNum(v("goal_rate"))?.let(u::wIn) ?: s.goalRateKg,
                        b = parseNum(v("b")) ?: s.b,
                        energyDensity = parseNum(v("rho")) ?: s.energyDensity,
                        weightNoiseKg = parseNum(v("noise")) ?: s.weightNoiseKg,
                        offsetSedentary = parseNum(v("sed")) ?: s.offsetSedentary,
                        offsetModerate = parseNum(v("mod")) ?: s.offsetModerate,
                        offsetHeavy = parseNum(v("heavy")) ?: s.offsetHeavy,
                        adaptationRefDate = v("ref").trim().takeIf { it.isNotEmpty() }
                            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    )
                )
            }) { Text("Save settings") }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, o ->
                RadioButton(selected == i, { onSelect(i) })
                Text(o, Modifier.clickable { onSelect(i) })
                if (i < options.lastIndex) androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            }
        }
    }
}
