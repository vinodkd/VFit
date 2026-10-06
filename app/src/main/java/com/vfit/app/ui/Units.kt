package com.vfit.app.ui

import com.vfit.app.data.AppSettings
import java.util.Locale

private const val KG_PER_LB = 0.45359237
private const val CM_PER_IN = 2.54
private const val MGDL_PER_MMOL = 18.016

/** Converts between stored metric values and what the user sees/enters. */
class Units(s: AppSettings) {
    private val imperial = s.imperial
    private val mmol = s.glucoseMmol

    val w = if (imperial) "lb" else "kg"
    val len = if (imperial) "in" else "cm"
    val glu = if (mmol) "mmol/L" else "mg/dL"

    fun wOut(kg: Double) = if (imperial) kg / KG_PER_LB else kg
    fun wIn(v: Double) = if (imperial) v * KG_PER_LB else v
    fun lenOut(cm: Double) = if (imperial) cm / CM_PER_IN else cm
    fun lenIn(v: Double) = if (imperial) v * CM_PER_IN else v
    fun gluOut(mgdl: Double) = if (mmol) mgdl / MGDL_PER_MMOL else mgdl
    fun gluIn(v: Double) = if (mmol) v * MGDL_PER_MMOL else v

    fun weight(kg: Double?) = kg?.let { "%.1f %s".format(Locale.US, wOut(it), w) } ?: "-"
    fun glucose(mgdl: Double?) = mgdl?.let { if (mmol) "%.1f %s".format(Locale.US, gluOut(it), glu) else "%.0f %s".format(Locale.US, it, glu) } ?: "-"

    /** Text for an editable field. */
    fun field(v: Double?, decimals: Int = 1) = v?.let { "%.${decimals}f".format(Locale.US, it) } ?: ""
}

fun kcal(v: Double?) = v?.let { "%,d".format(Locale.US, (Math.round(it / 10.0) * 10)) } ?: "-"
fun parseNum(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
