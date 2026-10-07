package com.vfit.app

import com.vfit.app.data.AppSettings
import com.vfit.app.data.DayEntry
import com.vfit.app.data.LiftSet
import com.vfit.app.data.MealEntry
import com.vfit.app.data.Repository
import com.vfit.app.ui.Units
import com.vfit.app.ui.summarize
import com.vfit.model.DayInput
import com.vfit.model.MODERATE
import com.vfit.model.Model
import com.vfit.model.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AppLogicTest {
    private val d0 = LocalDate.of(2026, 3, 1).toEpochDay()

    @Test
    fun dayCaloriesUsesOverrideThenMealsThenNull() {
        val meals = listOf(MealEntry(date = d0, name = "a", kcal = 800), MealEntry(date = d0, name = "b", kcal = 600, quantity = 0.5))
        assertEquals(1100.0, Repository.dayCalories(null, meals)!!, 1e-9)
        assertEquals(1500.0, Repository.dayCalories(DayEntry(d0, caloriesOverride = 1500), meals)!!, 1e-9)
        assertNull(Repository.dayCalories(DayEntry(d0, weight = 80.0), emptyList()))
    }

    @Test
    fun buildInputsMergesDaysMealsAndLifts() {
        val days = listOf(DayEntry(d0, weight = 90.0, activity = MODERATE), DayEntry(d0 + 2, waist = 100.0))
        val meals = listOf(MealEntry(date = d0 + 1, name = "x", kcal = 700))
        val lifts = listOf(LiftSet(d0 + 3, "squat", 100.0, 6), LiftSet(d0 + 3, "bench", 80.0, 1))
        val inp = Repository.buildInputs(days, meals, lifts)
        assertEquals((0..3).map { LocalDate.ofEpochDay(d0 + it) }, inp.map { it.date })
        assertEquals(MODERATE, inp[0].activity)
        assertEquals(UNKNOWN, inp[1].activity)
        assertEquals(700.0, inp[1].calories!!, 1e-9)
        assertNull(inp[0].calories)
        assertEquals(120.0, inp[3].e1rm["squat"]!!, 1e-9)   // Epley: 100 * (1 + 6/30)
        assertEquals(80.0, inp[3].e1rm["bench"]!!, 1e-9)    // 1 rep = load
    }

    @Test
    fun settingsRoundTripThroughMap() {
        val s = AppSettings(imperial = true, male = false, age = 51.0, heightCm = 165.5, goalWeightKg = null,
            goalRateKg = 0.25, adaptationRefDate = LocalDate.of(2026, 5, 1), glucoseMmol = true)
        assertEquals(s, AppSettings.fromMap(s.toMap()))
        assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))
        assertNull(AppSettings.fromMap(mapOf("goal_weight" to "")).goalWeightKg)
    }

    @Test
    fun unitConversionsRoundTrip() {
        val u = Units(AppSettings(imperial = true, glucoseMmol = true))
        assertEquals(220.462, u.wOut(100.0), 1e-3)
        assertEquals(100.0, u.wIn(u.wOut(100.0)), 1e-9)
        assertEquals(39.370, u.lenOut(100.0), 1e-3)
        assertEquals(5.55, u.gluOut(100.0), 1e-2)
        assertEquals(100.0, u.gluIn(u.gluOut(100.0)), 1e-9)
        val m = Units(AppSettings())
        assertEquals("82.5 kg", m.weight(82.5))
        assertEquals("110 mg/dL", m.glucose(110.0))
    }

    @Test
    fun summaryHasAllSectionsAndAdaptation() {
        // 60 days: 1,900 kcal logged, weight falling ~0.5 kg/week
        val start = LocalDate.of(2026, 3, 1)
        val inputs = (0 until 60).map { DayInput(start.plusDays(it.toLong()), weightKg = 95.0 - it * 0.07, calories = 1900.0) }
        val r = Model.compute(inputs, AppSettings().toModelConfig())!!
        val sec = summarize(r, AppSettings())
        assertEquals(listOf("Current status as of 2026-04-29", "Forecast (if intake stays at the 14-day average)", "Plateau check", "Composition"),
            sec.map { it.title })
        val rows = sec[0].rows.toMap()
        assertTrue(rows["Estimated TDEE"]!!.contains("kcal/day"))
        assertTrue(rows["Adaptation"]!!.contains("beyond what weight lost explains"))
        assertTrue(sec[1].rows.toMap()["Settling weight"]!!.endsWith(")"))
    }
}

class SlotTest {
    @Test
    fun mealsWithoutSlotArePlacedByTime() {
        assertEquals(com.vfit.app.data.Slot.BREAKFAST, com.vfit.app.data.Slot.forMealTime(8 * 60))
        assertEquals(com.vfit.app.data.Slot.LUNCH, com.vfit.app.data.Slot.forMealTime(13 * 60))
        assertEquals(com.vfit.app.data.Slot.DINNER, com.vfit.app.data.Slot.forMealTime(19 * 60 + 30))
        assertEquals(com.vfit.app.data.Slot.EVENING_SNACK, com.vfit.app.data.Slot.forMealTime(22 * 60))
        assertNull(com.vfit.app.data.Slot.forMealTime(null))
        // an explicit slot wins over the time
        val m = MealEntry(date = 0, name = "x", kcal = 1, time = 8 * 60, slot = com.vfit.app.data.Slot.LUNCH.id)
        assertEquals(com.vfit.app.data.Slot.LUNCH, com.vfit.app.data.Slot.ofMeal(m))
    }
}

class NextStepTest {
    private val start = LocalDate.of(2026, 3, 1)
    private fun run(days: Int, weight: (Int) -> Double, kcal: Double, s: AppSettings) =
        com.vfit.app.ui.nextStep(Model.compute((0 until days).map { DayInput(start.plusDays(it.toLong()), weightKg = weight(it), calories = kcal) }, s.toModelConfig())!!, s)

    @Test
    fun tooLittleDataSaysKeepLogging() {
        assertEquals("Keep logging", run(10, { 90.0 }, 2000.0, AppSettings()).headline)
    }

    @Test
    fun stalledAboveGoalSaysEatLessOrMoveMore() {
        val step = run(70, { 90.0 }, 2500.0, AppSettings(goalWeightKg = 80.0))
        assertTrue(step.headline, step.headline.startsWith("Eat about") && step.headline.endsWith("or move more"))
        assertTrue(step.detail, step.detail.contains("more walking a day"))
    }

    @Test
    fun noGoalSaysMaintain() {
        assertTrue(run(70, { 90.0 }, 2500.0, AppSettings(goalWeightKg = null)).headline.startsWith("Maintain at about"))
    }
}

class YearStatsTest {
    @Test
    fun eachDayGetsTheKindThatWon() {
        val today = LocalDate.of(2026, 3, 10)            // 69 days into the year
        val start = LocalDate.of(2026, 1, 1)
        // 60 days, steady weight on 2,000 kcal (so TDEE ~2,000); then:
        //   days 40-44 eat 3,000 -> lived; day 50 fasting -> fasted; days 45-46 eat 1,900 + 60 min walk -> exercise;
        //   days 47-48 eat 1,000 + 30 min walk -> diet (shortfall bigger than the walk)
        val days = (0 until 60).map { i ->
            val kcal = when (i) { in 40..44 -> 3000; 45, 46 -> 1900; 47, 48 -> 1000; else -> 2000 }
            DayEntry(start.plusDays(i.toLong()).toEpochDay(), weight = 85.0, caloriesOverride = if (i == 50) null else kcal, fasting = i == 50)
        }
        val exercise = listOf(45 to 60, 46 to 60, 47 to 30, 48 to 30).map { (i, m) ->
            com.vfit.app.data.ExerciseEntry(date = start.plusDays(i.toLong()).toEpochDay(), slot = 3, description = "walk", minutes = m)
        }
        val model = Model.compute(Repository.buildInputs(days, emptyList(), emptyList()), AppSettings().toModelConfig())
        val y = com.vfit.app.ui.yearStats(today, days, emptyList(), exercise, emptyList(), model)
        fun kind(i: Int) = y.kinds[start.plusDays(i.toLong())]
        assertEquals(69, y.daysSoFar)
        for (i in 40..44) assertEquals("day $i", com.vfit.app.ui.DayKind.LIVED, kind(i))
        assertEquals(com.vfit.app.ui.DayKind.FASTED, kind(50))
        assertEquals(com.vfit.app.ui.DayKind.EXERCISE, kind(45))
        assertEquals(com.vfit.app.ui.DayKind.DIET, kind(47))
        assertNull(kind(65))                             // no data
        assertEquals(1, y.count(com.vfit.app.ui.DayKind.FASTED))
    }
}

class GapFillTest {
    private val d0 = LocalDate.of(2026, 5, 1)
    private fun day(i: Int, kcal: Double?) = DayInput(d0.plusDays(i.toLong()), weightKg = 80.0, calories = kcal)

    @Test
    fun interpolatesShortGapsAssumesLongOnesSkipsFastingAndToday() {
        val inputs = listOf(day(0, 1000.0), day(1, null), day(2, null), day(3, 2500.0), day(4, 0.0), day(5, null),
            day(20, 1200.0), day(21, null))
        val today = d0.plusDays(21)
        val (out, assumed) = Repository.fillGaps(inputs, setOf(d0.plusDays(4).toEpochDay()), today, 1600.0)
        val cal = out.associate { it.date to it.calories }
        assertEquals(1500.0, cal[d0.plusDays(1)]!!, 1e-9)          // 1000 -> 2500 over 3 days
        assertEquals(2000.0, cal[d0.plusDays(2)]!!, 1e-9)
        assertEquals(0.0, cal[d0.plusDays(4)]!!, 1e-9)             // fasting day kept at 0, never an endpoint
        assertEquals(1600.0, cal[d0.plusDays(5)]!!, 1e-9)          // next logged day is 15 days away -> assumed
        assertEquals(1600.0, cal[d0.plusDays(10)]!!, 1e-9)         // missing rows inside the range are filled too
        assertNull(cal[today])                                     // today isn't filled
        assertTrue(d0.plusDays(1) in assumed && d0.plusDays(4) !in assumed && today !in assumed)
    }

    @Test
    fun fastingDayWithNothingLoggedIsZero() {
        assertEquals(0.0, Repository.dayCalories(DayEntry(1, fasting = true), emptyList())!!, 1e-9)
        assertEquals(300.0, Repository.dayCalories(DayEntry(1, fasting = true), listOf(MealEntry(date = 1, name = "soup", kcal = 300)))!!, 1e-9)
        assertNull(Repository.dayCalories(DayEntry(1), emptyList()))
    }
}
