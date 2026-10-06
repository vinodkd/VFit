package com.vfit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * Compares the Kotlin model with outputs from tdee_tracker.py.
 * Golden files come from:  python3 tdee_tracker.py export-csv <workbook> <dir>
 *
 * golden_sim (simulated data) is committed. To also check a private workbook, export it and run
 *   ./gradlew :model:test -Pgolden.extra=/path/to/export_dir
 */
class GoldenTest {

    @Test
    fun simulatedData() = check(resourceDir("golden_sim"))

    @Test
    fun extraDataIfGiven() {
        val dir = System.getProperty("golden.extra")?.takeIf { it.isNotBlank() } ?: return
        check(File(dir))
    }

    private fun resourceDir(name: String) = File(javaClass.classLoader.getResource("$name/inputs.csv")!!.toURI()).parentFile

    private fun check(dir: File) {
        val cfg = GoldenIo.readConfig(File(dir, "config.csv"))
        val inputs = GoldenIo.readInputs(File(dir, "inputs.csv"))
        val result = Model.compute(inputs, cfg)
        assertNotNull(result)
        val got = result!!.outputs.associateBy { it.date }
        val (header, rows) = GoldenIo.readCsv(File(dir, "outputs.csv"))
        var compared = 0
        val errors = ArrayList<String>()
        for (row in rows) {
            val date = LocalDate.parse(row[0])
            val o = got[date] ?: run { errors.add("$date: missing output"); null } ?: continue
            for ((k, name) in header.withIndex()) {
                if (k == 0) continue
                val exp = row.getOrElse(k) { "" }
                val act: Any? = field(o, name)
                compared++
                if (!same(exp, act)) errors.add("$date $name: expected '$exp', got '$act'")
            }
        }
        if (errors.isNotEmpty()) fail("${errors.size} mismatches of $compared values, first 20:\n" + errors.take(20).joinToString("\n"))
        assertEquals(rows.size, got.size)
    }

    private fun same(exp: String, act: Any?): Boolean {
        if (exp.isEmpty()) return act == null
        if (act == null) return false
        return when (act) {
            is Double -> exp.toDoubleOrNull()?.let { abs(it - act) <= 1e-6 * max(1.0, abs(it)) } ?: false
            else -> exp == act.toString()
        }
    }

    private fun field(o: DayOutput, name: String): Any? = when (name) {
        "trend_weight" -> o.trendWeight
        "weekly_rate" -> o.weeklyRate
        "tdee_est" -> o.tdeeEst
        "tdee_low" -> o.tdeeLow
        "tdee_high" -> o.tdeeHigh
        "tdee_confidence" -> o.tdeeConfidence
        "intake_avg_14d" -> o.intakeAvg14d
        "deficit" -> o.deficit
        "data_coverage" -> o.dataCoverage
        "settling_weight" -> o.settlingWeight
        "settling_low" -> o.settlingLow
        "settling_high" -> o.settlingHigh
        "proj_4w" -> o.proj4w
        "proj_12w" -> o.proj12w
        "proj_26w" -> o.proj26w
        "target_intake" -> o.targetIntake
        "goal_date" -> o.goalDate ?: o.goalNote
        "plateau_flag" -> o.plateauFlag
        "plateau_cause" -> o.plateauCause
        "waist_trend" -> o.waistTrend
        "waist_per_wt_lost" -> o.waistPerWtLost
        "strength_index" -> o.strengthIndex
        "strength_flag" -> o.strengthFlag
        "adaptation" -> o.adaptation
        else -> error("unknown column $name")
    }
}
