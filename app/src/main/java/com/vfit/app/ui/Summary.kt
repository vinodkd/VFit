package com.vfit.app.ui

import com.vfit.app.data.AppSettings
import com.vfit.model.ModelResult
import java.util.Locale

data class SummarySection(val title: String, val rows: List<Pair<String, String>>)

/** Port of write_summary() in tdee_tracker.py: status text for the latest day. */
fun summarize(r: ModelResult, s: AppSettings): List<SummarySection> {
    val u = Units(s)
    val o = r.latest ?: return emptyList()
    val outs = r.outputs
    fun f(fmt: String, vararg a: Any?) = String.format(Locale.US, fmt, *a)

    val status = SummarySection(
        "Current status as of ${o.date}",
        listOf(
            "Trend weight" to u.weight(o.trendWeight),
            "Weekly rate" to (o.weeklyRate?.let { f("%+.2f %s/week", u.wOut(it), u.w) } ?: "-"),
            "Estimated TDEE" to (o.tdeeEst?.let {
                "${kcal(it)} kcal/day (80% range ${kcal(o.tdeeLow)}-${kcal(o.tdeeHigh)}), confidence ${o.tdeeConfidence}"
            } ?: if (o.intakeAvg14d != null || outs.size < 21) "not enough data yet (needs ~3 weeks)"
            else "can't estimate: too few days with calories logged in the last 4 weeks"),
            "Average intake (14 d)" to "${kcal(o.intakeAvg14d)} kcal/day",
            "Deficit" to (o.deficit?.let { "${kcal(it)} kcal/day" + if (it < 0) " (surplus)" else "" } ?: "-"),
            "Data coverage (14 d)" to (o.dataCoverage?.let { f("%.0f%%", it * 100) } ?: "-"),
            "Adaptation" to (o.adaptation?.let { a ->
                val ref = outs.first { it.adaptation != null }.date
                f("%+,.0f kcal/day since %s beyond what weight lost explains", a, ref) +
                    if (a < 0) " (burning less than expected)" else ""
            } ?: "-"),
        ),
    )

    val forecast = SummarySection(
        "Forecast (if intake stays at the 14-day average)",
        if (o.settlingWeight == null) listOf("" to "Forecast needs ~4 weeks of data with calories logged on most days.")
        else {
            val bDisp = s.b * if (s.imperial) 0.45359237 else 1.0
            listOf(
                "Settling weight" to "${u.weight(o.settlingWeight)} (80% range ${f("%.1f", u.wOut(o.settlingLow!!))}-${f("%.1f", u.wOut(o.settlingHigh!!))})",
                "Projected" to "4 wk: ${u.weight(o.proj4w)}   12 wk: ${u.weight(o.proj12w)}   26 wk: ${u.weight(o.proj26w)}",
                "Goal date" to (o.goalDate?.toString() ?: o.goalNote ?: "-"),
                "Target intake" to (o.targetIntake?.let {
                    "${kcal(it)} kcal/day for your goal rate of ${f("%.2f", u.wOut(s.goalRateKg))} ${u.w}/week. " +
                        "It steps down about ${f("%,.0f", bDisp * 4)} kcal for every 4 ${u.w} lost, because a lighter body burns less."
                } ?: "-"),
            )
        },
    )

    val plateauRows = ArrayList<Pair<String, String>>()
    if (o.plateauFlag != null) {
        plateauRows += "Status" to (if (o.plateauFlag == "PLATEAU") "PLATEAU: trend has been roughly flat" else "REGAINING: trend has been rising") + " for 3+ weeks."
        val cause = o.plateauCause.orEmpty()
        plateauRows += "Likely cause" to cause
        plateauRows += "Suggested next step" to when {
            cause.startsWith("can't tell") -> "Log intake for 2-3 weeks so the model can separate eating from expenditure."
            cause.startsWith("deficit") -> "Options: trim intake 100-200 kcal, add ~2,000 steps/day, or hold for 1-2 weeks and re-check."
            cause.startsWith("possible water") -> "Fat loss is probably continuing; give it 2-3 more weeks before changing anything."
            else -> "Tighten logging for 2 weeks (weigh everything, include oils/bites) before changing the plan."
        }
        plateauRows += "Caveat" to "Logging drift (portions creeping up) looks identical to a falling TDEE in this data."
    } else {
        plateauRows += "Status" to if (outs.size >= 35) "No plateau detected." else "Plateau checks start after ~5 weeks of data."
    }

    val lastFlag = outs.dropLast(1).lastOrNull { it.strengthFlag != null }?.date
    val compRows = arrayListOf(
        "Waist trend" to (o.waistTrend?.let { f("%+.2f %s/week", u.lenOut(it), u.len) } ?: "-"),
        "Waist lost per ${u.w} lost" to (o.waistPerWtLost?.let { f("%.2f %s/%s", u.lenOut(u.wIn(it)), u.len, u.w) } ?: "-"),
        "Strength index" to (o.strengthIndex?.let { f("%+.1f%% vs baseline", it * 100) } ?: "-"),
        "Strength flag" to (o.strengthFlag ?: ("none now" + (lastFlag?.let { " (last flagged $it)" } ?: ""))),
    )
    if (o.strengthFlag != null || lastFlag != null)
        compRows += "Suggested next step" to "Before cutting calories further, review deficit size, protein intake, and training volume."

    return listOf(status, forecast, SummarySection("Plateau check", plateauRows), SummarySection("Composition", compRows))
}

data class NextStep(val headline: String, val detail: String)

/**
 * The one thing to do next, from the latest day. Plateau advice follows write_summary() in tdee_tracker.py;
 * otherwise it compares 14-day intake with the intake that gives the goal rate.
 * Calories are in the app's logged units, so the advice holds even if logging is systematically off.
 */
fun nextStep(r: ModelResult, s: AppSettings): NextStep {
    val u = Units(s)
    val o = r.latest ?: return NextStep("Log your first weight", "Add this morning's weight on Today.")
    fun f(fmt: String, vararg a: Any?) = String.format(Locale.US, fmt, *a)
    // net kcal per minute of brisk walking (~2.5 METs above rest): 2.5 * 3.5 * kg / 200
    val walkPerMin = 2.5 * 3.5 * o.trendWeight / 200
    /** Exercise alternative to eating `kcal` less; walking is capped at an hour, the rest comes from food. */
    fun walk(kcal: Double): String {
        val mins = kcal / walkPerMin
        return if (mins <= 60) f("about %.0f min more walking a day", mins)
        else f("an hour more walking a day plus eating about %s kcal less", kcal(kcal - 60 * walkPerMin))
    }
    val intake = o.intakeAvg14d
    val target = o.targetIntake
    val strength = o.strengthFlag?.let { " Strength is dropping: check protein and training before cutting further." } ?: ""

    if (o.tdeeEst == null || intake == null) return NextStep(
        "Keep logging",
        "Log weight every morning and each meal. TDEE and forecasts appear after about 3 weeks with calories on most days.",
    )
    o.plateauFlag?.let {
        val cause = o.plateauCause.orEmpty()
        return when {
            cause.startsWith("possible water") -> NextStep("Hold steady for 2–3 weeks",
                "The scale is flat but ${cause.substringAfter("(").removeSuffix(")")}, so fat loss is probably continuing. Don't change anything yet.$strength")
            cause.startsWith("can't tell") -> NextStep("Log every meal for 2–3 weeks",
                "There aren't enough logged calories to tell eating apart from expenditure.$strength")
            cause.startsWith("deficit") -> {
                val gap = target?.let { intake - it }?.takeIf { it > 50 } ?: 150.0
                NextStep(f("Eat about %s kcal/day less, or move more", kcal(gap)),
                    f("Your deficit has closed. Aim for about %s kcal/day (now %s), or add %s. Or hold 1–2 weeks and re-check.",
                        kcal(target ?: (intake - gap)), kcal(intake), walk(gap)) + strength)
            }
            else -> NextStep("Tighten logging for 2 weeks",
                "The model still sees a deficit, so the stall is likely unlogged food. Log everything, including oils and bites, before changing the plan.$strength")
        }
    }
    val goal = s.goalWeightKg
    if (goal == null || o.trendWeight <= goal || target == null) return NextStep(
        f("Maintain at about %s kcal/day", kcal(o.tdeeEst)),
        (if (goal == null) "No goal weight is set (Settings). " else "You're at your goal. ") +
            "Eating near your TDEE keeps weight steady.$strength",
    )
    val gap = intake - target
    return when {
        gap > 100 -> NextStep(f("Eat about %s kcal/day less, or move more", kcal(gap)),
            f("To lose %s %s/week you need about %s kcal/day; your 14-day average is %s. Or add %s.",
                f("%.2f", u.wOut(s.goalRateKg)).trimEnd('0').trimEnd('.'), u.w, kcal(target), kcal(intake), walk(gap)) + strength)
        gap < -250 -> NextStep("You can eat a little more",
            f("You're about %s kcal/day under the intake for your goal rate (%s kcal/day). Losing faster than planned risks muscle.",
                kcal(-gap), kcal(target)) + strength)
        else -> NextStep(f("On track: keep intake near %s kcal/day", kcal(target)),
            "Your intake matches your goal rate. Keep logging.$strength")
    }
}
