package com.vfit.model

import java.time.LocalDate
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Port of compute() in tdee_tracker.py. Everything is metric (kg, cm); the UI converts for display.
 * Each day's outputs use only data up to that day.
 */

const val SEDENTARY = 0
const val LIGHT = 1
const val MODERATE = 2
const val HEAVY = 3
const val UNKNOWN = -1

data class ModelConfig(
    val male: Boolean = true,
    val age: Double = 45.0,
    val heightCm: Double = 178.0,
    val goalWeightKg: Double? = 80.0,
    val goalRateKg: Double = 0.5,
    val b: Double = 22.0,
    val energyDensity: Double = 7700.0,
    val weightNoiseKg: Double = 0.6,
    val offsetSedentary: Double = -150.0,
    val offsetModerate: Double = 200.0,
    val offsetHeavy: Double = 400.0,
    val adaptationRefDate: LocalDate? = null,
)

/** One calendar day of inputs. Calories or weight of 0 count as missing, as in the script. */
data class DayInput(
    val date: LocalDate,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val calories: Double? = null,
    val activity: Int = UNKNOWN,
    val grip: Double? = null,
    /** Estimated 1-rep max per lift name. */
    val e1rm: Map<String, Double> = emptyMap(),
)

data class DayOutput(
    val date: LocalDate,
    val trendWeight: Double,
    val weeklyRate: Double? = null,
    val tdeeEst: Double? = null,
    val tdeeLow: Double? = null,
    val tdeeHigh: Double? = null,
    val tdeeConfidence: String? = null,
    val intakeAvg14d: Double? = null,
    val deficit: Double? = null,
    val dataCoverage: Double? = null,
    val settlingWeight: Double? = null,
    val settlingLow: Double? = null,
    val settlingHigh: Double? = null,
    val proj4w: Double? = null,
    val proj12w: Double? = null,
    val proj26w: Double? = null,
    val targetIntake: Double? = null,
    /** Set when the goal is reachable at current intake. */
    val goalDate: LocalDate? = null,
    /** "not at current intake" or "reached" when goalDate is null but a goal is set. */
    val goalNote: String? = null,
    val plateauFlag: String? = null,
    val plateauCause: String? = null,
    val waistTrend: Double? = null,
    val waistPerWtLost: Double? = null,
    val strengthIndex: Double? = null,
    val strengthFlag: String? = null,
    val adaptation: Double? = null,
)

data class ModelResult(
    /** Every calendar day from the first input to the last. */
    val dates: List<LocalDate>,
    /** Outputs from the first day with a weight onward, in date order. */
    val outputs: List<DayOutput>,
    /** Raw daily inputs aligned with [dates] (NaN = missing), for charts. */
    val weight: DoubleArray,
    val waist: DoubleArray,
    val calories: DoubleArray,
    val strength: DoubleArray,
    val b: Double,
    val energyDensity: Double,
) {
    val latest: DayOutput? get() = outputs.lastOrNull()
}

private const val Z80 = 1.2816

object Model {

    fun mifflin(wKg: Double, hCm: Double, age: Double, male: Boolean) =
        10 * wKg + 6.25 * hCm - 5 * age + if (male) 5.0 else -161.0

    fun compute(inputs: List<DayInput>, cfg: ModelConfig): ModelResult? {
        if (inputs.isEmpty()) return null
        val byDate = HashMap<LocalDate, DayInput>()
        for (r in inputs) byDate[r.date] = r                 // last row wins for duplicate dates
        val d0 = byDate.keys.min()
        val d1 = byDate.keys.max()
        val n = (d1.toEpochDay() - d0.toEpochDay()).toInt() + 1
        val dates = List(n) { d0.plusDays(it.toLong()) }

        val nan = Double.NaN
        val W = DoubleArray(n) { nan }
        val WA = DoubleArray(n) { nan }
        val CAL = DoubleArray(n) { nan }
        val ACT = IntArray(n) { UNKNOWN }
        val liftNames = inputs.flatMap { it.e1rm.keys }.distinct().sorted()
        val meas = LinkedHashMap<String, DoubleArray>()
        meas["grip"] = DoubleArray(n) { nan }
        for (l in liftNames) meas["lift:$l"] = DoubleArray(n) { nan }
        for (i in 0 until n) {
            val r = byDate[dates[i]] ?: continue
            r.weightKg?.let { if (it != 0.0) W[i] = it }
            r.waistCm?.let { if (it != 0.0) WA[i] = it }
            r.calories?.let { if (it != 0.0) CAL[i] = it }
            ACT[i] = r.activity
            r.grip?.let { if (it != 0.0) meas["grip"]!![i] = it }
            for ((l, v) in r.e1rm) meas["lift:$l"]!![i] = v
        }

        val start = W.indexOfFirst { !it.isNaN() }
        if (start < 0) return null

        val b = cfg.b
        val rho = cfg.energyDensity
        val R = cfg.weightNoiseKg * cfg.weightNoiseKg

        // --- Kalman filter.  x = [W, a, o_sed, o_mod, o_heavy]; 'light' offset is 0.
        val rmr = mifflin(W[start], cfg.heightCm, cfg.age, cfg.male)
        var x = doubleArrayOf(W[start], 1.4 * rmr - b * W[start], cfg.offsetSedentary, cfg.offsetModerate, cfg.offsetHeavy)
        var P = Mat.diag(R, 350.0 * 350.0, 150.0 * 150.0, 150.0 * 150.0, 200.0 * 200.0)
        val qBase = doubleArrayOf(0.03 * 0.03, 12.0 * 12.0, 1.0, 1.0, 1.0)
        val X = Array(n) { DoubleArray(5) { nan } }
        val PP = arrayOfNulls<Array<DoubleArray>>(n)

        for (i in start until n) {
            if (i > start) {
                val j = i - 1
                var intake = CAL[j]
                var extra = 0.0
                var noInfo = false
                if (intake.isNaN()) {                       // unlogged day: assume recent average, widen uncertainty
                    var sum = 0.0
                    var cnt = 0
                    for (k in max(0, j - 13)..j) if (!CAL[k].isNaN()) { sum += CAL[k]; cnt++ }
                    if (cnt >= 3) {
                        intake = sum / cnt
                        extra += (450.0 / rho).let { it * it }
                    } else {                                // nothing to go on: TDEE must not learn from this day
                        noInfo = true
                        extra += (600.0 / rho).let { it * it }
                    }
                }
                val e = DoubleArray(5)                     // offset selector
                when (ACT[j]) {
                    SEDENTARY -> e[2] = 1.0
                    MODERATE -> e[3] = 1.0
                    HEAVY -> e[4] = 1.0
                    UNKNOWN -> extra += (150.0 / rho).let { it * it }
                }
                val F = Mat.identity(5)
                F[0][0] = 1 - b / rho
                F[0][1] = -1 / rho
                for (k in 2 until 5) F[0][k] = -e[k] / rho
                if (noInfo) {                               // predict "no change", decoupled from TDEE
                    for (k in 0 until 5) F[0][k] = 0.0
                    F[0][0] = 1.0
                    intake = 0.0
                }
                x = Mat.mulVec(F, x)
                x[0] += intake / rho
                val Q = Mat.diag(*qBase)
                Q[0][0] += extra
                P = Mat.add(Mat.mul(Mat.mul(F, P), Mat.transpose(F)), Q)
            }
            if (!W[i].isNaN()) {                            // scalar update, H = [1,0,0,0,0]
                val S = P[0][0] + R
                val K = DoubleArray(5) { P[it][0] / S }
                val innov = W[i] - x[0]
                x = DoubleArray(5) { x[it] + K[it] * innov }
                val hp = P[0].copyOf()                       // H @ P = first row
                P = Array(5) { r -> DoubleArray(5) { c -> P[r][c] - K[r] * hp[c] } }
            }
            X[i] = x.copyOf()
            PP[i] = Mat.copy(P)
        }

        // --- strength composite (causal): each measure vs mean of its first 2 readings, 28-day window
        val comp = DoubleArray(n) { nan }
        for (i in 0 until n) {
            val idx = ArrayList<Double>()
            for (arr in meas.values) {
                val seen = (0..i).filter { !arr[it].isNaN() }
                if (seen.size < 2) continue
                val base = (arr[seen[0]] + arr[seen[1]]) / 2
                val win = (max(0, i - 27)..i).map { arr[it] }.filter { !it.isNaN() }
                if (win.isNotEmpty()) idx.add(win.average() / base - 1)
            }
            if (idx.isNotEmpty()) comp[i] = idx.average()
        }

        val goal = cfg.goalWeightKg?.takeIf { it != 0.0 }
        val goalRate = cfg.goalRateKg
        val outs = ArrayList<DayOutput>()
        var firstTdee: Double? = null
        var refI: Int? = null
        var nW = 0

        for (i in start until n) {
            val xi = X[i]
            val Pi = PP[i]!!
            val age = i - start
            if (!W[i].isNaN()) nW++
            val weeklyRate = if (age >= 14) (xi[0] - X[i - 14][0]) / 2.0 else null
            val lo14 = max(0, i - 13)
            val cal14 = (lo14..i).map { CAL[it] }.filter { !it.isNaN() }
            val both = (lo14..i).count { !CAL[it].isNaN() && !W[it].isNaN() }
            val coverage = if (age >= 13) both / 14.0 else null
            val winLen = (i - lo14 + 1).toDouble()
            val fr = doubleArrayOf(
                (lo14..i).count { ACT[it] == SEDENTARY } / winLen,
                (lo14..i).count { ACT[it] == MODERATE } / winLen,
                (lo14..i).count { ACT[it] == HEAVY } / winLen,
            )
            val h = doubleArrayOf(b, 1.0, fr[0], fr[1], fr[2])
            val tdee = Mat.dot(h, xi)
            val sd = sqrt(max(Mat.quad(h, Pi), 0.0))
            val intakeAvg = if (cal14.size >= 7) cal14.average() else null
            val calDays28 = (max(0, i - 27)..i).count { !CAL[it].isNaN() }
            val tdeeOk = age >= 20 && nW >= 10 && calDays28 >= 10

            var tdeeEst: Double? = null
            var tdeeLow: Double? = null
            var tdeeHigh: Double? = null
            var conf: String? = null
            var deficit: Double? = null
            var adaptation: Double? = null
            if (tdeeOk) {
                if (firstTdee == null) firstTdee = tdee
                tdeeEst = tdee; tdeeLow = tdee - Z80 * sd; tdeeHigh = tdee + Z80 * sd
                conf = if (sd < 100) "High" else if (sd < 200) "Medium" else "Low"
                if (intakeAvg != null) deficit = tdee - intakeAvg
                // adaptation: TDEE change at the same activity minus b * weight change = change in intercept a
                if (refI == null && (cfg.adaptationRefDate == null || !dates[i].isBefore(cfg.adaptationRefDate))) refI = i
                refI?.let { adaptation = xi[1] - X[it][1] }
            }

            // forecast
            var settling: Double? = null
            var sLow: Double? = null
            var sHigh: Double? = null
            var p4: Double? = null
            var p12: Double? = null
            var p26: Double? = null
            var target: Double? = null
            var goalDate: LocalDate? = null
            var goalNote: String? = null
            if (tdeeOk && age >= 27 && intakeAvg != null) {
                val hs = doubleArrayOf(0.0, 1.0, fr[0], fr[1], fr[2])
                val base = Mat.dot(hs, xi)
                val sdS = sqrt(max(Mat.quad(hs, Pi), 0.0)) / b
                val ws = (intakeAvg - base) / b
                settling = ws; sLow = ws - Z80 * sdS; sHigh = ws + Z80 * sdS
                val proj = { days: Int -> ws + (xi[0] - ws) * exp(-b * days / rho) }
                p4 = proj(28); p12 = proj(84); p26 = proj(182)
                if (goal != null) {
                    if (xi[0] > goal) {
                        target = if (goalRate > 0) tdee - goalRate * rho / 7.0 else null
                        if (ws < goal) {
                            val t = rho / b * ln((xi[0] - ws) / (goal - ws))
                            goalDate = dates[i].plusDays(pyRound(t))
                        } else goalNote = "not at current intake"
                    } else {
                        target = tdee
                        goalNote = "reached"
                    }
                }
            }

            // composition
            val lo28 = max(0, i - 27)
            val wIdx = (lo28..i).filter { !WA[it].isNaN() }
            val waistTrend = if (wIdx.size >= 3) slope(wIdx.map { it.toDouble() }, wIdx.map { WA[it] }) * 7 else null
            val waSeen = (0..i).filter { !WA[it].isNaN() }
            val lost = X[start][0] - xi[0]
            val waistPerWt = if (waSeen.size >= 4 && lost >= 1.5)
                ((WA[waSeen[0]] + WA[waSeen[1]]) / 2 - (WA[waSeen[waSeen.size - 2]] + WA[waSeen.last()]) / 2) / lost
            else null
            val strengthIndex = comp[i].takeUnless { it.isNaN() }
            var strengthFlag: String? = null
            if (i - 28 >= start && !comp[i].isNaN() && !comp[i - 28].isNaN()) {
                val drop = comp[i] - comp[i - 28]
                val wtChange = xi[0] - X[i - 28][0]
                if (drop < -0.05 && wtChange < -0.5)
                    strengthFlag = String.format(Locale.US, "strength %+.0f%% in 4 wks while losing weight", drop * 100)
            }

            // plateau
            var plateauFlag: String? = null
            var plateauCause: String? = null
            if (age >= 34 && goal != null && goalRate > 0 && xi[0] > goal + 0.5) {
                val rate21 = (xi[0] - X[i - 21][0]) / 3.0
                val thresh = max(0.1, 0.25 * goalRate)
                if (rate21 > -thresh) {
                    plateauFlag = if (rate21 > thresh) "REGAINING" else "PLATEAU"
                    val sUp = !comp[i].isNaN() && i - 21 >= start && !comp[i - 21].isNaN() && comp[i] - comp[i - 21] > 0.02
                    val waistDown = waistTrend != null && waistTrend < -0.25
                    plateauCause = when {
                        waistDown || sUp -> "possible water masking (" + (if (waistDown) "waist still falling" else "strength rising") + ")"
                        deficit == null -> "can't tell - not enough calories logged recently"
                        deficit < 200 -> String.format(Locale.US, "deficit closed (TDEE est. down %,.0f since first estimate)", firstTdee!! - tdee)
                        else -> "unclear - model still sees a deficit; recheck logging, wait 1-2 wks"
                    }
                }
            }

            outs.add(
                DayOutput(
                    date = dates[i], trendWeight = xi[0], weeklyRate = weeklyRate,
                    tdeeEst = tdeeEst, tdeeLow = tdeeLow, tdeeHigh = tdeeHigh, tdeeConfidence = conf,
                    intakeAvg14d = intakeAvg, deficit = deficit, dataCoverage = coverage,
                    settlingWeight = settling, settlingLow = sLow, settlingHigh = sHigh,
                    proj4w = p4, proj12w = p12, proj26w = p26, targetIntake = target,
                    goalDate = goalDate, goalNote = goalNote, plateauFlag = plateauFlag, plateauCause = plateauCause,
                    waistTrend = waistTrend, waistPerWtLost = waistPerWt, strengthIndex = strengthIndex,
                    strengthFlag = strengthFlag, adaptation = adaptation,
                )
            )
        }
        return ModelResult(dates, outs, W, WA, CAL, comp, b, rho)
    }

    /** Least-squares slope, equivalent to numpy.polyfit(x, y, 1)[0]. */
    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var den = 0.0
        for (k in x.indices) {
            num += (x[k] - mx) * (y[k] - my)
            den += (x[k] - mx) * (x[k] - mx)
        }
        return num / den
    }

    /** Python's round(): half to even. */
    private fun pyRound(v: Double): Long {
        val r = Math.rint(v)
        return r.roundToLong()
    }

    /** Settling forecast curve for the chart: weight after `days` days at current intake. */
    fun forecast(trend: Double, settling: Double, days: Int, b: Double, rho: Double) =
        settling + (trend - settling) * exp(-b * days / rho)
}

internal object Mat {
    fun identity(n: Int) = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
    fun diag(vararg d: Double) = Array(d.size) { r -> DoubleArray(d.size) { c -> if (r == c) d[r] else 0.0 } }
    fun copy(a: Array<DoubleArray>) = Array(a.size) { a[it].copyOf() }
    fun transpose(a: Array<DoubleArray>) = Array(a[0].size) { r -> DoubleArray(a.size) { c -> a[c][r] } }
    fun add(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(a.size) { r -> DoubleArray(a[0].size) { c -> a[r][c] + b[r][c] } }
    fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(a.size) { r ->
        DoubleArray(b[0].size) { c -> var s = 0.0; for (k in b.indices) s += a[r][k] * b[k][c]; s }
    }
    fun mulVec(a: Array<DoubleArray>, v: DoubleArray) = DoubleArray(a.size) { r -> var s = 0.0; for (k in v.indices) s += a[r][k] * v[k]; s }
    fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (k in a.indices) s += a[k] * b[k]; return s }
    fun quad(h: DoubleArray, p: Array<DoubleArray>) = dot(h, mulVec(p, h))
}
