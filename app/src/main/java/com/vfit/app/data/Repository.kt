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
    val settings: Flow<AppSettings> = dao.settings().map { rows -> AppSettings.fromMap(rows.associate { it.key to it.value }) }

    /** Full recompute on every change; cheap (5x5 filter, one step per day). */
    val model: Flow<ModelResult?> = combine(days, meals, lifts, settings) { d, m, l, s ->
        Model.compute(buildInputs(d, m, l), s.toModelConfig())
    }.flowOn(Dispatchers.Default)

    suspend fun saveDay(d: DayEntry) = dao.upsertDay(d)
    suspend fun saveMeal(m: MealEntry) = dao.upsertMeal(m)
    suspend fun deleteMeal(id: Long) = dao.deleteMealWithGlucose(id)
    suspend fun saveTemplate(t: MealTemplate) = dao.upsertTemplate(t)
    suspend fun deleteTemplate(id: Long) = dao.deleteTemplate(id)
    suspend fun saveLift(l: LiftSet) = dao.upsertLift(l)
    suspend fun deleteLift(date: Long, lift: String) = dao.deleteLift(date, lift)
    suspend fun saveGlucose(g: GlucoseReading) = dao.upsertGlucose(g)
    suspend fun deleteGlucose(id: Long) = dao.deleteGlucose(id)
    suspend fun saveSettings(s: AppSettings) = dao.upsertSettings(s.toMap().map { SettingRow(it.key, it.value) })
    suspend fun day(date: Long) = dao.day(date)

    // ------------------------------------------------------------------ import / export

    /** Imports inputs.csv or config.csv as written by `tdee_tracker.py export-csv` (or by [exportFiles]). */
    suspend fun importCsv(text: String): String {
        val (header, _) = GoldenIo.parseCsv(text)
        if (header.firstOrNull()?.trim() == "setting") {
            val current = AppSettings.fromMap(dao.allSettings().associate { it.key to it.value }).toMap()
            saveSettings(AppSettings.fromMap(current + GoldenIo.parseConfig(text)))
            return "Imported settings."
        }
        if ("date" !in header.map { it.trim() }) return "Not a VFit/tdee_tracker CSV (no 'date' column)."
        val rows = GoldenIo.parseInputs(text)
        val existing = dao.allDays().associateBy { it.date }
        val days = rows.map { r ->
            val e = r.date.toEpochDay()
            (existing[e] ?: DayEntry(e)).copy(
                weight = r.weightKg ?: existing[e]?.weight,
                waist = r.waistCm ?: existing[e]?.waist,
                activity = r.activity.takeIf { it != UNKNOWN } ?: existing[e]?.activity,
                grip = r.grip ?: existing[e]?.grip,
                caloriesOverride = r.calories?.roundToInt() ?: existing[e]?.caloriesOverride,
            )
        }
        dao.upsertDays(days)
        dao.upsertLifts(rows.flatMap { r -> r.e1rm.map { (l, v) -> LiftSet(r.date.toEpochDay(), l, v, 1) } })
        return "Imported ${days.size} days."
    }

    /** Returns file name -> CSV text: inputs.csv (re-importable), config.csv, meals.csv, glucose.csv. */
    suspend fun exportFiles(): Map<String, String> {
        val days = dao.allDays()
        val meals = dao.allMeals()
        val lifts = dao.allLifts()
        val inputs = buildInputs(days, meals, lifts)
        val liftNames = lifts.map { it.lift }.distinct().sorted()
        val act = listOf("sedentary", "light", "moderate", "heavy")
        val inputsCsv = buildString {
            appendLine((listOf("date", "weight_kg", "waist_cm", "calories", "activity", "grip") + liftNames.map { "e1rm_$it" }).joinToString(","))
            for (r in inputs) {
                appendLine((listOf(r.date.toString(), s(r.weightKg), s(r.waistCm), s(r.calories),
                    act.getOrElse(r.activity) { "" }, s(r.grip)) + liftNames.map { s(r.e1rm[it]) }).joinToString(","))
            }
        }
        val settings = AppSettings.fromMap(dao.allSettings().associate { it.key to it.value })
        val configCsv = buildString {
            appendLine("setting,value")
            settings.toMap().forEach { (k, v) -> appendLine("$k,$v") }
        }
        val mealsCsv = buildString {
            appendLine("date,time,name,kcal,quantity")
            for (m in meals) appendLine("${LocalDate.ofEpochDay(m.date)},${m.time?.let(::hhmm) ?: ""},${q(m.name)},${m.kcal},${m.quantity}")
        }
        val glucoseCsv = buildString {
            appendLine("date,time,kind,meal,mg_dl")
            val byId = meals.associateBy { it.id }
            for (g in dao.allGlucose()) appendLine(
                "${LocalDate.ofEpochDay(g.date)},${hhmm(g.time)},${if (g.kind == GLUCOSE_FASTING) "fasting" else "2h_post_meal"}," +
                    "${q(g.mealEntryId?.let { byId[it]?.name } ?: "")},${g.value}"
            )
        }
        return linkedMapOf("inputs.csv" to inputsCsv, "config.csv" to configCsv, "meals.csv" to mealsCsv, "glucose.csv" to glucoseCsv)
    }

    private fun s(v: Double?) = v?.toString() ?: ""
    private fun q(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    companion object {
        fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

        /** Daily calories = override if set, else sum of meal kcal x quantity; null if neither. */
        fun dayCalories(day: DayEntry?, meals: List<MealEntry>): Double? =
            day?.caloriesOverride?.toDouble() ?: meals.takeIf { it.isNotEmpty() }?.sumOf { it.kcal * it.quantity }

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
