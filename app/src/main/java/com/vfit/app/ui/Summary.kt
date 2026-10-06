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
