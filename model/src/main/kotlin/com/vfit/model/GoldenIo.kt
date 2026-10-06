package com.vfit.model

import java.io.File
import java.time.LocalDate

/**
 * Reads the CSV files written by `tdee_tracker.py export-csv` (inputs.csv, config.csv, outputs.csv).
 * Used by the golden tests and by the app's import.
 */
object GoldenIo {

    fun parseCsv(text: String): Pair<List<String>, List<List<String>>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(field.toString()); field = StringBuilder() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field = StringBuilder(); rows.add(row); row = ArrayList() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        val nonEmpty = rows.filter { r -> r.any { it.isNotBlank() } }
        return (nonEmpty.firstOrNull() ?: emptyList()) to nonEmpty.drop(1)
    }

    fun readCsv(f: File) = parseCsv(f.readText())

    fun activityCode(s: String?): Int {
        val v = s?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return UNKNOWN
        return when {
            v.startsWith("sed") -> SEDENTARY
            v.startsWith("lig") -> LIGHT
            v.startsWith("mod") -> MODERATE
            v.startsWith("hea") || v.startsWith("hard") -> HEAVY
            else -> UNKNOWN
        }
    }

    fun parseInputs(text: String): List<DayInput> {
        val (h, rows) = parseCsv(text)
        val col = h.withIndex().associate { it.value.trim() to it.index }
        fun num(r: List<String>, name: String) = col[name]?.let { r.getOrNull(it) }?.trim()?.toDoubleOrNull()
        val lifts = h.filter { it.startsWith("e1rm_") }
        return rows.mapNotNull { r ->
            val date = col["date"]?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
            DayInput(
                date = date,
                weightKg = num(r, "weight_kg"),
                waistCm = num(r, "waist_cm"),
                calories = num(r, "calories"),
                activity = activityCode(col["activity"]?.let { r.getOrNull(it) }),
                grip = num(r, "grip"),
                e1rm = lifts.mapNotNull { l -> num(r, l)?.let { l.removePrefix("e1rm_") to it } }.toMap(),
            )
        }
    }

    fun readInputs(f: File) = parseInputs(f.readText())

    fun parseConfig(text: String): Map<String, String> =
        parseCsv(text).second.filter { it.size >= 2 }.associate { it[0].trim() to it[1].trim() }

    fun toModelConfig(m: Map<String, String>): ModelConfig {
        val d = ModelConfig()
        fun n(k: String, def: Double) = m[k]?.toDoubleOrNull() ?: def
        return ModelConfig(
            male = m["sex"]?.lowercase()?.startsWith("m") ?: d.male,
            age = n("age", d.age),
            heightCm = n("height", d.heightCm),
            goalWeightKg = m["goal_weight"]?.let { it.toDoubleOrNull() } ?: if (m.containsKey("goal_weight")) null else d.goalWeightKg,
            goalRateKg = n("goal_rate", d.goalRateKg),
            b = n("b_kcal_per_kg", d.b),
            energyDensity = n("energy_density", d.energyDensity),
            weightNoiseKg = n("weight_noise_kg", d.weightNoiseKg),
            offsetSedentary = n("offset_sedentary", d.offsetSedentary),
            offsetModerate = n("offset_moderate", d.offsetModerate),
            offsetHeavy = n("offset_heavy", d.offsetHeavy),
            adaptationRefDate = m["adaptation_ref_date"]?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) },
        )
    }

    fun readConfig(f: File) = toModelConfig(parseConfig(f.readText()))
}
