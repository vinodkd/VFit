package com.vfit.app.ui

import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material3.CardDefaults
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

// ------------------------------------------------------------------ Dashboard

private data class Tile(val label: String, val value: String, val sub: String, val color: Color, val spark: List<Double> = emptyList())

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val model by vm.model.collectAsState()
    val settings by vm.settings.collectAsState()
    val glucose by vm.glucose.collectAsState()
    val days by vm.days.collectAsState()
    val meals by vm.meals.collectAsState()
    val exercise by vm.exercise.collectAsState()
    val lifts by vm.lifts.collectAsState()
    val u = Units(settings)
    val ac = LocalAccents.current
    var details by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val r = model
        if (r == null) {
            Text("No weights yet. Log one on Today, or import your data in Settings.")
            return@Column
        }
        val o = r.latest!!
        val recent = r.outputs.takeLast(90)
        Column {
            Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
            Text(
                "As of ${o.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))}" +
                    (o.dataCoverage?.let { " · data coverage %.0f%%".format(Locale.US, it * 100) } ?: "") +
                    (o.tdeeConfidence?.let { " · TDEE confidence $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        YearCard(yearStats(LocalDate.now(), days.orEmpty(), meals, exercise, lifts, r))

        val step = nextStep(r, settings)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("NEXT STEP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(step.headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        o.plateauFlag?.let { flag ->
            Card(colors = CardDefaults.cardColors(containerColor = ac.meal.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surface))) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(if (flag == "PLATEAU") "Plateau: trend flat for 3+ weeks" else "Regaining: trend rising for 3+ weeks",
                        style = MaterialTheme.typography.titleMedium, color = ac.meal)
                    o.plateauCause?.let { Text("Likely cause: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        val since = LocalDate.now().minusDays(6).toEpochDay()
        val g7 = glucose.filter { it.date >= since }
        fun gAvg(kind: Int) = g7.filter { it.kind == kind }.map { it.value }.takeIf { it.isNotEmpty() }?.average()
        val fasting = glucose.filter { it.kind == GLUCOSE_FASTING }

        val tiles = buildList {
            add(Tile("Trend weight", u.weight(o.trendWeight),
                o.weeklyRate?.let { "%+.2f %s/week".format(Locale.US, u.wOut(it), u.w) } ?: "rate after 2 weeks",
                ac.body, recent.map { u.wOut(it.trendWeight) }))
            add(Tile("TDEE", o.tdeeEst?.let { kcal(it) } ?: "—",
                o.tdeeEst?.let { "kcal/day · ${kcal(o.tdeeLow)}–${kcal(o.tdeeHigh)}" } ?: "needs ~3 weeks of data",
                ac.glucose, recent.map { it.tdeeEst ?: Double.NaN }))
            add(Tile("Intake (14 d)", o.intakeAvg14d?.let { kcal(it) } ?: "—",
                o.deficit?.let { if (it >= 0) "deficit ${kcal(it)} kcal/day" else "surplus ${kcal(-it)} kcal/day" } ?: "kcal/day",
                ac.meal, recent.map { it.intakeAvg14d ?: Double.NaN }))
            add(Tile("Adaptation", o.adaptation?.let { "%+,.0f".format(Locale.US, it) } ?: "—",
                o.adaptation?.let { "kcal/day beyond weight lost, since ${r.outputs.first { x -> x.adaptation != null }.date}" } ?: "after TDEE is estimated",
                ac.day, recent.map { it.adaptation ?: Double.NaN }))
            add(Tile("Settling weight", o.settlingWeight?.let { u.weight(it) } ?: "—",
                o.proj26w?.let { "in 26 weeks: ${u.weight(it)}" } ?: "needs ~4 weeks of data", ac.body))
            add(Tile("Goal", o.goalDate?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)) ?: o.goalNote ?: "—",
                o.targetIntake?.let { "target intake ${kcal(it)} kcal/day" } ?: settings.goalWeightKg?.let { "goal ${u.weight(it)}" } ?: "set a goal in Settings",
                ac.exercise))
            add(Tile("Waist", o.waistTrend?.let { "%+.2f".format(Locale.US, u.lenOut(it)) } ?: "—",
                (o.waistTrend?.let { "${u.len}/week" } ?: "needs 3 readings in 4 weeks") +
                    (o.waistPerWtLost?.let { " · %.2f %s per %s lost".format(Locale.US, u.lenOut(u.wIn(it)), u.len, u.w) } ?: ""),
                ac.body, r.waist.toList().takeLast(90)))
            val bySlot = g7.filter { it.kind == GLUCOSE_POST_MEAL }.groupBy { it.slot }
            fun slotAvg(slot: com.vfit.app.data.Slot) = bySlot[slot.id]?.map { it.value }?.average()?.let { "%.0f".format(Locale.US, u.gluOut(it)) }
            val post = listOfNotNull(
                slotAvg(com.vfit.app.data.Slot.BREAKFAST)?.let { "breakfast $it" },
                slotAvg(com.vfit.app.data.Slot.LUNCH)?.let { "lunch $it" },
                slotAvg(com.vfit.app.data.Slot.DINNER)?.let { "dinner $it" },
            )
            add(Tile("Fasting glucose (7 d)", gAvg(GLUCOSE_FASTING)?.let { u.glucose(it) } ?: "—",
                if (post.isEmpty()) "no post-meal readings this week" else "2 h after: " + post.joinToString(" · "),
                ac.glucose, fasting.takeLast(30).map { u.gluOut(it.value) }))
            val bp = days.orEmpty().filter { it.bpSys != null && it.bpDia != null }
            bp.lastOrNull()?.let { last ->
                val month = bp.filter { it.date >= LocalDate.now().minusDays(29).toEpochDay() }
                add(Tile("Blood pressure", "%.0f/%.0f".format(Locale.US, last.bpSys, last.bpDia),
                    "on ${LocalDate.ofEpochDay(last.date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.US))}" +
                        (last.pulse?.let { " · pulse %.0f".format(Locale.US, it) } ?: "") +
                        (if (month.size > 1) " · 30-day avg %.0f/%.0f".format(Locale.US, month.map { it.bpSys!! }.average(), month.map { it.bpDia!! }.average()) else ""),
                    ac.day, bp.takeLast(30).map { it.bpSys!! }))
            }
            o.strengthIndex?.let { si ->
                add(Tile("Strength", "%+.1f%%".format(Locale.US, si * 100), o.strengthFlag ?: "vs baseline, 28-day window",
                    ac.exercise, r.strength.toList().takeLast(90).map { it * 100 }))
            }
        }
        for (pair in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (t in pair) TileCard(t, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
        if (details) for (sec in summarize(r, settings)) SectionCard(sec)
    }
}

/** Year to date: each day coloured by what won (fasted / lived / exercise / diet), with counts. */
@Composable
private fun YearCard(y: YearStats) {
    val ac = LocalAccents.current
    val cats = listOf(
        Triple(DayKind.EXERCISE, "Exercise", ac.exercise),
        Triple(DayKind.DIET, "Diet", ac.body),
        Triple(DayKind.FASTED, "Fasted", ac.day),
        Triple(DayKind.LIVED, "Lived", ac.meal),
    )
    val color = cats.associate { it.first to it.third }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${y.start.year} so far · ${y.daysSoFar} days", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, label, c) in cats) Surface(
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = c.copy(alpha = 0.14f),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1)
                        Text("${y.count(k)}", style = MaterialTheme.typography.titleLarge)
                        Text("%.0f%%".format(Locale.US, 100.0 * y.count(k) / y.daysSoFar),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // calendar: one column per week (Mon-Sun), from the week of 1 January to this week
            val firstMonday = y.start.minusDays((y.start.dayOfWeek.value - 1).toLong())
            val weeks = ((y.end.toEpochDay() - firstMonday.toEpochDay()) / 7 + 1).toInt()
            Canvas(Modifier.fillMaxWidth().aspectRatio(weeks / 7f)) {
                val cell = size.width / weeks
                val gap = cell * 0.18f
                for (w in 0 until weeks) for (dow in 0 until 7) {
                    val d = firstMonday.plusDays((w * 7 + dow).toLong())
                    if (d.isBefore(y.start) || d.isAfter(y.end)) continue
                    drawRoundRect(
                        y.kinds[d]?.let { color[it] } ?: empty,
                        topLeft = Offset(w * cell + gap / 2, dow * cell + gap / 2),
                        size = Size(cell - gap, cell - gap),
                        cornerRadius = CornerRadius(cell * 0.2f),
                    )
                }
            }
            Text(
                "Each day shows what won: fasted; else lived (ate more than TDEE); else the deficit came mostly from " +
                    "exercise or from diet. Grey: not enough data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TileCard(t: Tile, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = t.color.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface))) {
        Column(Modifier.padding(12.dp).heightIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.label, style = MaterialTheme.typography.labelMedium, color = t.color)
            // numbers big; words ("not at current intake") smaller so they fit
            val numeric = t.value.firstOrNull()?.let { it.isDigit() || it == '+' || it == '-' } == true
            Text(t.value, style = if (numeric) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(t.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            Spacer(Modifier.weight(1f))
            Sparkline(t.spark, t.color, Modifier.padding(top = 6.dp))
        }
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


@Composable
fun ChartsScreen(vm: AppViewModel) {
    val model by vm.model.collectAsState()
    val settings by vm.settings.collectAsState()
    val glucose by vm.glucose.collectAsState()
    val assumed by vm.assumedDays.collectAsState()
    val days by vm.days.collectAsState()
    val u = Units(settings)
    val ac = LocalAccents.current
    val BLUE = ac.body; val ORANGE = ac.meal; val GREEN = ac.exercise; val RED = ac.glucose; val PURPLE = ac.day; val TEAL = ac.body
    val GREY = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Charts", style = MaterialTheme.typography.headlineMedium)
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
                    Series.Dots("Logged intake", ORANGE.copy(alpha = 0.55f), xDays,
                        r.calories.toList().mapIndexed { i, v -> if (r.dates[i] in assumed) Double.NaN else v }),
                    Series.Dots("Assumed (not logged)", GREY.copy(alpha = 0.6f), xDays,
                        r.calories.toList().mapIndexed { i, v -> if (r.dates[i] in assumed) v else Double.NaN }),
                    Series.Line("Intake, 14-day avg", ORANGE, outX, r.outputs.map { it.intakeAvg14d ?: nan }),
                    Series.Band("TDEE 80% range", BLUE, tdeeX.map { it.date.toEpochDay().toDouble() }, tdeeX.map { it.tdeeLow!! }, tdeeX.map { it.tdeeHigh!! }),
                    Series.Line("Estimated TDEE", BLUE, tdeeX.map { it.date.toEpochDay().toDouble() }, tdeeX.map { it.tdeeEst!! }),
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

        // blood pressure
        val bp = days.orEmpty().filter { it.bpSys != null || it.pulse != null }
        if (bp.isNotEmpty()) {
            val bx = bp.map { it.date.toDouble() }
            LineChart(
                "Blood pressure and pulse", "mmHg · bpm",
                listOf(
                    Series.Dots("Upper (systolic)", PURPLE, bx, bp.map { it.bpSys ?: Double.NaN }),
                    Series.Dots("Lower (diastolic)", BLUE, bx, bp.map { it.bpDia ?: Double.NaN }),
                    Series.Dots("Pulse", GREY, bx, bp.map { it.pulse ?: Double.NaN }),
                ),
            )
            HorizontalDivider()
        }

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

// ------------------------------------------------------------------ Meals

@Composable
fun MealsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val templates = vm.templates.collectAsState().value.orEmpty()
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
                "assumed" to u.field(s.assumedIntake, 0),
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
            NumField("Assumed intake on days with nothing logged", v("assumed"), Modifier.fillMaxWidth(), suffix = "kcal") { set("assumed", it) }
            Text("Used when there's no logged day within a week on both sides to interpolate from.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        assumedIntake = parseNum(v("assumed")) ?: s.assumedIntake,
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
