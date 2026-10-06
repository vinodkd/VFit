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
