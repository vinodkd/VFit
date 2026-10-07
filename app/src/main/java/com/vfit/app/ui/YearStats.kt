package com.vfit.app.ui

import com.vfit.app.data.DayEntry
import com.vfit.app.data.ExerciseEntry
import com.vfit.app.data.LiftSet
import com.vfit.app.data.MealEntry
import com.vfit.app.data.Repository
import com.vfit.model.ModelResult
import java.time.LocalDate

/** What a day counted as in the year-to-date calendar. */
enum class DayKind { FASTED, LIVED, EXERCISE, DIET }

/**
 * [start]..[end] is the period so far (end = today). [periodDays] is the whole period the targets apply to:
 * a year from [start] (or the days so far, once more than a year has passed).
 */
data class YearStats(val start: LocalDate, val end: LocalDate, val kinds: Map<LocalDate, DayKind>, val periodDays: Int) {
    val daysSoFar get() = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
    val daysLeft get() = periodDays - daysSoFar
    fun count(k: DayKind) = kinds.values.count { it == k }
    /** Days of this kind still "available" under a target share (percent) of the period; negative = over. */
    fun left(k: DayKind, targetPercent: Double) = Math.round(targetPercent / 100 * periodDays).toInt() - count(k)
}

/** Start of the period: the configured day if it's not in the future, else 1 January of [today]'s year. */
fun periodStart(today: LocalDate, configured: LocalDate?) =
    configured?.takeIf { !it.isAfter(today) } ?: today.withDayOfYear(1)

/** Minutes assumed for a strength session logged without an activity duration. */
private const val STRENGTH_MINUTES = 30
/** Net METs above rest for logged exercise (moderate effort; the app doesn't record intensity). */
private const val EXERCISE_NET_METS = 4.0

/**
 * Classifies each day from [start] (default 1 January) to [today] by what "won":
 *  1. fasted  - fasting day
 *  2. lived   - ate more than that day's TDEE estimate
 *  3. a deficit day: exercise if estimated exercise calories cover more of it than eating less did, else diet
 * Days with nothing to go on are left out. Uses logged calories only (not assumed ones).
 */
fun yearStats(
    today: LocalDate, days: List<DayEntry>, meals: List<MealEntry>, exercise: List<ExerciseEntry>,
    lifts: List<LiftSet>, model: ModelResult?, start: LocalDate = today.withDayOfYear(1),
): YearStats {
    val dayMap = days.associateBy { it.date }
    val mealMap = meals.groupBy { it.date }
    val minutes = exercise.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.minutes ?: 0 } }
    val strengthDays = lifts.map { it.date }.toSet()
    val out = model?.outputs?.associateBy { it.date }.orEmpty()
    val kinds = HashMap<LocalDate, DayKind>()
    var d = start
    while (!d.isAfter(today)) {
        val e = d.toEpochDay()
        val day = dayMap[e]
        val cal = Repository.dayCalories(day, mealMap[e].orEmpty())
        val o = out[d]
        val tdee = o?.tdeeEst
        val exMin = (minutes[e] ?: 0) + if (e in strengthDays && (minutes[e] ?: 0) == 0) STRENGTH_MINUTES else 0
        val moved = e in minutes || e in strengthDays
        val exKcal = exMin * EXERCISE_NET_METS * 3.5 * (o?.trendWeight ?: 80.0) / 200
        val kind = when {
            day?.fasting == true -> DayKind.FASTED
            cal != null && tdee != null && cal > tdee -> DayKind.LIVED
            cal != null && tdee != null -> if (moved && exKcal >= tdee - cal) DayKind.EXERCISE else DayKind.DIET
            moved -> DayKind.EXERCISE
            else -> null
        }
        kind?.let { kinds[d] = it }
        d = d.plusDays(1)
    }
    val yearDays = (start.plusYears(1).toEpochDay() - start.toEpochDay()).toInt()
    val soFar = (today.toEpochDay() - start.toEpochDay() + 1).toInt()
    return YearStats(start, today, kinds, maxOf(yearDays, soFar))
}
