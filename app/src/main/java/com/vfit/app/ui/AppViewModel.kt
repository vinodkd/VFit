package com.vfit.app.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vfit.app.VFitApp
import com.vfit.app.data.AppSettings
import com.vfit.app.data.DayEntry
import com.vfit.app.data.ExerciseEntry
import com.vfit.app.data.Slot
import com.vfit.app.data.GlucoseReading
import com.vfit.app.data.LiftSet
import com.vfit.app.data.MealEntry
import com.vfit.app.data.MealTemplate
import com.vfit.model.ModelResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as VFitApp).repository
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    /** null until the first load from the database completes. */
    val days: StateFlow<List<DayEntry>?> = repo.days.state(null)
    val meals: StateFlow<List<MealEntry>> = repo.meals.state(emptyList())
    /** null until loaded, so the UI doesn't flash "no saved meals". */
    val templates: StateFlow<List<MealTemplate>?> = repo.templates.state(null)
    val lifts: StateFlow<List<LiftSet>> = repo.lifts.state(emptyList())
    val glucose: StateFlow<List<GlucoseReading>> = repo.glucose.state(emptyList())
    val exercise: StateFlow<List<ExerciseEntry>> = repo.exercise.state(emptyList())
    val settings: StateFlow<AppSettings> = repo.settings.state(AppSettings())
    val model: StateFlow<ModelResult?> = repo.model.state(null)

    private val _date = MutableStateFlow(LocalDate.now())
    val date = _date.asStateFlow()
    fun selectDate(d: LocalDate) { _date.value = d }

    /** Snackbar text, optionally with an action (e.g. Undo). */
    data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }
    private fun say(text: String) { _message.value = UiMessage(text) }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch { block() }

    fun saveDay(d: DayEntry) = io { repo.saveDay(d) }

    private fun nowMinutes(date: LocalDate) =
        if (date == LocalDate.now()) LocalTime.now().let { it.hour * 60 + it.minute } else null

    /**
     * Logs a meal the user ate: either a saved meal ([t]) or a new one ([name]/[kcal], optionally saved for next time).
     * [time] is minutes after midnight.
     */
    fun logMeal(date: LocalDate, slot: Slot, t: MealTemplate?, name: String, kcal: Int, quantity: Double, time: Int?, saveAsTemplate: Boolean) = io {
        val now = System.currentTimeMillis()
        val templateId = when {
            t != null -> { repo.saveTemplate(t.copy(lastUsed = now)); t.id }
            saveAsTemplate -> repo.saveTemplate(MealTemplate(name = name, kcal = kcal, lastUsed = now))
            else -> null
        }
        repo.saveMeal(MealEntry(date = date.toEpochDay(), templateId = templateId, name = t?.name ?: name, kcal = t?.kcal ?: kcal,
            quantity = quantity, time = time, slot = slot.id))
    }

    fun updateMeal(m: MealEntry) = io { repo.saveMeal(m) }
    /** Removes a meal (and its 2 h glucose reading), with Undo. */
    fun deleteMeal(m: MealEntry) = io {
        val readings = glucose.value.filter { it.mealEntryId == m.id }
        repo.deleteMeal(m.id)
        _message.value = UiMessage("Removed ${m.name}", "Undo") {
            io { repo.saveMeal(m); readings.forEach { repo.saveGlucose(it) } }
        }
    }

    /** Saves the morning check-in: body fields, and the fasting glucose reading (blank removes it). */
    fun saveMorning(date: LocalDate, weight: Double?, waist: Double?, grip: Double?, fastingMgdl: Double?, existing: GlucoseReading?) = io {
        val e = date.toEpochDay()
        repo.saveDay((repo.day(e) ?: DayEntry(e)).copy(weight = weight, waist = waist, grip = grip))
        when {
            fastingMgdl != null -> repo.saveGlucose(
                existing?.copy(value = fastingMgdl)
                    ?: GlucoseReading(date = e, time = nowMinutes(date) ?: 7 * 60, kind = com.vfit.app.data.GLUCOSE_FASTING, value = fastingMgdl)
            )
            existing != null -> repo.deleteGlucose(existing.id)
        }
    }
    fun saveTemplate(t: MealTemplate) = io { repo.saveTemplate(t) }
    fun deleteTemplate(id: Long) = io { repo.deleteTemplate(id) }
    fun saveLift(l: LiftSet) = io { repo.saveLift(l) }
    fun deleteLift(date: Long, lift: String) = io { repo.deleteLift(date, lift) }
    fun saveGlucose(g: GlucoseReading) = io { repo.saveGlucose(g) }
    fun deleteGlucose(id: Long) = io { repo.deleteGlucose(id) }
    fun saveExercise(e: ExerciseEntry) = io { repo.saveExercise(e) }
    fun deleteExercise(id: Long) = io { repo.deleteExercise(id) }

    /** Saves activity without touching other fields of the day (so unsaved body edits aren't lost). */
    fun setActivity(date: LocalDate, activity: Int?) = io {
        val e = date.toEpochDay()
        repo.saveDay((repo.day(e) ?: DayEntry(e)).copy(activity = activity))
    }

    fun saveSettings(s: AppSettings) = io { repo.saveSettings(s); say("Settings saved") }

    fun importCsv(uri: Uri) = io {
        val ctx = getApplication<Application>()
        say(runCatching {
            val text = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
            repo.importCsv(text)
        }.getOrElse { "Import failed: ${it.message}" })
    }

    fun exportCsv(tree: Uri) = io {
        val ctx = getApplication<Application>()
        say(runCatching {
            val files = repo.exportFiles()
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(ctx, tree)!!
                val stamp = LocalDate.now().toString()
                for ((name, text) in files) {
                    val base = name.removeSuffix(".csv")
                    val f = dir.createFile("text/csv", "vfit_${stamp}_$base") ?: error("can't create $name")
                    ctx.contentResolver.openOutputStream(f.uri)!!.bufferedWriter().use { it.write(text) }
                }
            }
            "Exported ${files.size} files"
        }.getOrElse { "Export failed: ${it.message}" })
    }
}

