package com.vfit.app.data

import com.vfit.model.DayInput
import com.vfit.model.GoldenIo
import com.vfit.model.Model
import com.vfit.model.ModelResult
import com.vfit.model.UNKNOWN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import kotlin.math.roundToInt

class Repository(private val db: VFitDb) {
    private val dao = db.dao()

    val days = dao.days()
    val meals = dao.meals()
    val templates = dao.templates()
    val lifts = dao.lifts()
    val glucose = dao.glucose()
    val exercise = dao.exercise()
    val settings: Flow<AppSettings> = dao.settings().map { rows -> AppSettings.fromMap(rows.associate { it.key to it.value }) }

    /** Model output plus the days whose intake was assumed (not logged). */
    data class Computed(val result: ModelResult?, val assumed: Set<LocalDate>)

    /** Full recompute on every change; cheap (5x5 filter, one step per day). */
    val computed: Flow<Computed> = combine(days, meals, lifts, settings) { d, m, l, s ->
        val (inputs, assumed) = fillGaps(buildInputs(d, m, l), d.filter { it.fasting }.map { it.date }.toSet(), LocalDate.now(), s.assumedIntake)
        Computed(Model.compute(inputs, s.toModelConfig()), assumed)
    }.flowOn(Dispatchers.Default)

    suspend fun saveDay(d: DayEntry) = dao.upsertDay(d)
    suspend fun saveMeal(m: MealEntry) = dao.upsertMeal(m)
    suspend fun deleteMeal(id: Long) = dao.deleteMealWithGlucose(id)
    suspend fun saveTemplate(t: MealTemplate) = dao.upsertTemplate(t)
    suspend fun deleteTemplate(id: Long) = dao.deleteTemplate(id)
    suspend fun saveLift(l: LiftSet) = dao.upsertLift(l)
    suspend fun deleteLift(date: Long, lift: String) = dao.deleteLift(date, lift)
    suspend fun saveGlucose(g: GlucoseReading) = dao.upsertGlucose(g)
    suspend fun saveExercise(e: ExerciseEntry) = dao.upsertExercise(e)
    suspend fun deleteExercise(id: Long) = dao.deleteExercise(id)
    suspend fun deleteGlucose(id: Long) = dao.deleteGlucose(id)
    suspend fun saveSettings(s: AppSettings) = dao.upsertSettings(s.toMap().map { SettingRow(it.key, it.value) })
    suspend fun day(date: Long) = dao.day(date)

    // ------------------------------------------------------------------ import / export

    /**
     * Imports a CSV by its header: config.csv (settings), glucose.csv (has mg_dl), or inputs.csv
     * (from `tdee_tracker.py export-csv` / `vital-to-csv`, or a VFit export).
     * The file is treated as the source for the dates it covers: every column it has overwrites those days
     * (a blank cell clears the value); columns it doesn't have are left alone.
     */
    suspend fun importCsv(text: String): String {
        val (header, body) = GoldenIo.parseCsv(text)
        val h = header.map { it.trim() }
        if (h.firstOrNull() == "setting") {
            val current = AppSettings.fromMap(dao.allSettings().associate { it.key to it.value }).toMap()
            saveSettings(AppSettings.fromMap(current + GoldenIo.parseConfig(text)))
            return "Imported settings."
        }
        if ("date" !in h) return "Not a VFit/tdee_tracker CSV (no 'date' column)."
        val col = h.withIndex().associate { it.value to it.index }
        fun date(r: List<String>) = runCatching { LocalDate.parse(r[col["date"]!!].trim().take(10)).toEpochDay() }.getOrNull()
        /** null = column absent (keep existing); "" = blank cell (clear). */
        fun cell(r: List<String>, name: String): String? = col[name]?.let { r.getOrNull(it)?.trim() ?: "" }
        fun num(r: List<String>, name: String, old: Double?) = cell(r, name)?.toDoubleOrNull() ?: if (cell(r, name) == null) old else null

        if ("mg_dl" in col) {
            val readings = body.mapNotNull { r ->
                val e = date(r) ?: return@mapNotNull null
                val v = cell(r, "mg_dl")?.toDoubleOrNull() ?: return@mapNotNull null
                val t = cell(r, "time")?.split(":")?.let { (it[0].toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: 0
                val kind = if (cell(r, "kind")?.startsWith("fast") == true) GLUCOSE_FASTING else GLUCOSE_POST_MEAL
                GlucoseReading(date = e, time = t, kind = kind, value = v, slot = cell(r, "slot")?.toIntOrNull())
            }
            dao.deleteGlucoseOn(readings.map { it.date }.distinct())
            dao.upsertGlucoseList(readings)
            return "Imported ${readings.size} glucose readings."
        }

        val existing = dao.allDays().associateBy { it.date }
        val act = mapOf("sedentary" to 0, "light" to 1, "moderate" to 2, "heavy" to 3)
        val days = body.mapNotNull { r ->
            val e = date(r) ?: return@mapNotNull null
            val old = existing[e] ?: DayEntry(e)
            val cal = num(r, "calories", old.caloriesOverride?.toDouble())
            old.copy(
                weight = num(r, "weight_kg", old.weight),
                waist = num(r, "waist_cm", old.waist),
                activity = cell(r, "activity")?.let { a -> act.entries.firstOrNull { a.lowercase().startsWith(it.key.take(3)) }?.value } ?: old.activity.takeIf { cell(r, "activity") == null },
                grip = num(r, "grip", old.grip),
                caloriesOverride = cal?.roundToInt(),
                // a 0 kcal day is a fasting day
                fasting = (cell(r, "fasting")?.let { it == "1" } ?: old.fasting) || cal == 0.0,
                bpSys = num(r, "bp_sys", old.bpSys),
                bpDia = num(r, "bp_dia", old.bpDia),
                pulse = num(r, "pulse", old.pulse),
            )
        }
        dao.upsertDays(days)
        val lifts = h.filter { it.startsWith("e1rm_") }
        dao.upsertLifts(body.flatMap { r ->
            val e = date(r) ?: return@flatMap emptyList()
            lifts.mapNotNull { l -> cell(r, l)?.toDoubleOrNull()?.let { LiftSet(e, l.removePrefix("e1rm_"), it, 1) } }
        })
        return "Imported ${days.size} days."
    }

    /** Returns file name -> CSV text: inputs.csv (re-importable), config.csv, meals.csv, glucose.csv, exercise.csv. */
    suspend fun exportFiles(): Map<String, String> {
        val days = dao.allDays()
        val meals = dao.allMeals()
        val lifts = dao.allLifts()
        val inputs = buildInputs(days, meals, lifts)
        val liftNames = lifts.map { it.lift }.distinct().sorted()
        val act = listOf("sedentary", "light", "moderate", "heavy")
        val inputsCsv = buildString {
            appendLine((listOf("date", "weight_kg", "waist_cm", "calories", "activity", "grip") + liftNames.map { "e1rm_$it" } +
                listOf("fasting", "bp_sys", "bp_dia", "pulse")).joinToString(","))
            val dayMap = days.associateBy { it.date }
            for (r in inputs) {
                val d = dayMap[r.date.toEpochDay()]
                appendLine((listOf(r.date.toString(), s(r.weightKg), s(r.waistCm), s(r.calories),
                    act.getOrElse(r.activity) { "" }, s(r.grip)) + liftNames.map { s(r.e1rm[it]) } +
                    listOf(if (d?.fasting == true) "1" else "", s(d?.bpSys), s(d?.bpDia), s(d?.pulse))).joinToString(","))
            }
        }
        val settings = AppSettings.fromMap(dao.allSettings().associate { it.key to it.value })
        val configCsv = buildString {
            appendLine("setting,value")
            settings.toMap().forEach { (k, v) -> appendLine("$k,$v") }
        }
        val mealsCsv = buildString {
            appendLine("date,time,slot,name,kcal,quantity")
            for (m in meals) appendLine("${LocalDate.ofEpochDay(m.date)},${m.time?.let(::hhmm) ?: ""},${Slot.ofMeal(m)?.label ?: ""},${q(m.name)},${m.kcal},${m.quantity}")
        }
        val exerciseCsv = buildString {
            appendLine("date,slot,description,minutes")
            for (e in dao.allExercise()) appendLine("${LocalDate.ofEpochDay(e.date)},${Slot.of(e.slot)?.label ?: ""},${q(e.description)},${e.minutes ?: ""}")
        }
        val glucoseCsv = buildString {
            appendLine("date,time,kind,slot,meal,mg_dl")
            val byId = meals.associateBy { it.id }
            for (g in dao.allGlucose()) appendLine(
                "${LocalDate.ofEpochDay(g.date)},${hhmm(g.time)},${if (g.kind == GLUCOSE_FASTING) "fasting" else "2h_post_meal"}," +
                    "${g.slot ?: g.mealEntryId?.let { byId[it]?.slot } ?: ""},${q(g.mealEntryId?.let { byId[it]?.name } ?: "")},${g.value}"
            )
        }
        return linkedMapOf("inputs.csv" to inputsCsv, "config.csv" to configCsv, "meals.csv" to mealsCsv, "glucose.csv" to glucoseCsv,
            "exercise.csv" to exerciseCsv)
    }

    private fun s(v: Double?) = v?.toString() ?: ""
    private fun q(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    companion object {
        fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

        /** Daily calories = override if set, else sum of meal kcal x quantity; 0 on a fasting day with nothing logged; else null. */
        fun dayCalories(day: DayEntry?, meals: List<MealEntry>): Double? =
            day?.caloriesOverride?.toDouble() ?: meals.takeIf { it.isNotEmpty() }?.sumOf { it.kcal * it.quantity }
                ?: if (day?.fasting == true) 0.0 else null

        /**
         * Fills intake on past days with nothing logged (not today, not fasting days): interpolates between the nearest
         * logged non-fasting days if both are within a week, otherwise assumes [assumed] kcal. Returns the filled inputs
         * and the dates that were filled.
         */
        fun fillGaps(inputs: List<DayInput>, fasting: Set<Long>, today: LocalDate, assumed: Double): Pair<List<DayInput>, Set<LocalDate>> {
            if (inputs.isEmpty()) return inputs to emptySet()
            val byDate = inputs.associateBy { it.date }
            val logged = inputs.filter { it.calories != null && it.date.toEpochDay() !in fasting }
                .associate { it.date.toEpochDay() to it.calories!! }.toSortedMap()
            val first = inputs.first().date
            val last = minOf(inputs.last().date, today.minusDays(1))
            val filled = ArrayList<DayInput>()
            val assumedDays = HashSet<LocalDate>()
            var d = first
            while (!d.isAfter(inputs.last().date)) {
                val e = d.toEpochDay()
                val r = byDate[d]
                if ((r == null || r.calories == null) && !d.isAfter(last) && e !in fasting) {
                    val prev = logged.headMap(e).let { if (it.isEmpty()) null else it.lastKey() }?.takeIf { e - it <= 7 }
                    val next = logged.tailMap(e + 1).let { if (it.isEmpty()) null else it.firstKey() }?.takeIf { it - e <= 7 }
                    val kcal = if (prev != null && next != null)
                        logged[prev]!! + (logged[next]!! - logged[prev]!!) * (e - prev).toDouble() / (next - prev) else assumed
                    filled += (r ?: DayInput(d)).copy(calories = kcal)
                    assumedDays += d
                } else if (r != null) filled += r
                d = d.plusDays(1)
            }
            return filled to assumedDays
        }

        fun buildInputs(days: List<DayEntry>, meals: List<MealEntry>, lifts: List<LiftSet>): List<DayInput> {
            val dayMap = days.associateBy { it.date }
            val mealMap = meals.groupBy { it.date }
            val liftMap = lifts.groupBy { it.date }
            val dates = (dayMap.keys + mealMap.keys + liftMap.keys).sorted()
            return dates.map { e ->
                val d = dayMap[e]
                DayInput(
                    date = LocalDate.ofEpochDay(e),
                    weightKg = d?.weight,
                    waistCm = d?.waist,
                    calories = dayCalories(d, mealMap[e].orEmpty()),
                    activity = d?.activity ?: UNKNOWN,
                    grip = d?.grip,
                    e1rm = liftMap[e].orEmpty().associate { it.lift to it.e1rm },
                )
            }
        }
    }
}
