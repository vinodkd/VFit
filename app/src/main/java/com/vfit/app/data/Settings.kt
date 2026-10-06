package com.vfit.app.data

import com.vfit.model.ModelConfig
import java.time.LocalDate
import java.util.Locale

/** The workbook's Config sheet, stored metric. Keys match config.csv from `tdee_tracker.py export-csv`. */
data class AppSettings(
    val imperial: Boolean = false,
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
    val glucoseMmol: Boolean = false,
) {
    fun toModelConfig() = ModelConfig(
        male = male, age = age, heightCm = heightCm, goalWeightKg = goalWeightKg, goalRateKg = goalRateKg,
        b = b, energyDensity = energyDensity, weightNoiseKg = weightNoiseKg,
        offsetSedentary = offsetSedentary, offsetModerate = offsetModerate, offsetHeavy = offsetHeavy,
        adaptationRefDate = adaptationRefDate,
    )

    fun toMap(): Map<String, String> = linkedMapOf(
        "units" to if (imperial) "imperial" else "metric",
        "sex" to if (male) "male" else "female",
        "age" to num(age),
        "height" to num(heightCm),
        "goal_weight" to (goalWeightKg?.let(::num) ?: ""),
        "goal_rate" to num(goalRateKg),
        "b_kcal_per_kg" to num(b),
        "energy_density" to num(energyDensity),
        "weight_noise_kg" to num(weightNoiseKg),
        "offset_sedentary" to num(offsetSedentary),
        "offset_moderate" to num(offsetModerate),
        "offset_heavy" to num(offsetHeavy),
        "adaptation_ref_date" to (adaptationRefDate?.toString() ?: ""),
        "glucose_unit" to if (glucoseMmol) "mmol/L" else "mg/dL",
    )

    companion object {
        private fun num(v: Double) = if (v == Math.rint(v)) String.format(Locale.US, "%.0f", v) else v.toString()

        /** Missing keys keep their defaults; a blank goal_weight means "no goal". */
        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun n(k: String, def: Double) = m[k]?.trim()?.toDoubleOrNull() ?: def
            return AppSettings(
                imperial = m["units"]?.trim()?.lowercase()?.startsWith("imp") ?: d.imperial,
                male = m["sex"]?.trim()?.lowercase()?.startsWith("m") ?: d.male,
                age = n("age", d.age),
                heightCm = n("height", d.heightCm),
                goalWeightKg = if (m.containsKey("goal_weight")) m["goal_weight"]?.trim()?.toDoubleOrNull() else d.goalWeightKg,
                goalRateKg = n("goal_rate", d.goalRateKg),
                b = n("b_kcal_per_kg", d.b),
                energyDensity = n("energy_density", d.energyDensity),
                weightNoiseKg = n("weight_noise_kg", d.weightNoiseKg),
                offsetSedentary = n("offset_sedentary", d.offsetSedentary),
                offsetModerate = n("offset_moderate", d.offsetModerate),
                offsetHeavy = n("offset_heavy", d.offsetHeavy),
                adaptationRefDate = m["adaptation_ref_date"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
                glucoseMmol = m["glucose_unit"]?.trim()?.lowercase()?.startsWith("mmol") ?: d.glucoseMmol,
            )
        }
    }
}
