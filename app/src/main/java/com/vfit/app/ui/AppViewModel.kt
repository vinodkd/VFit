package com.vfit.app.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vfit.app.VFitApp
import com.vfit.app.data.AppSettings
import com.vfit.app.data.DayEntry
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
    val templates: StateFlow<List<MealTemplate>> = repo.templates.state(emptyList())
    val lifts: StateFlow<List<LiftSet>> = repo.lifts.state(emptyList())
    val glucose: StateFlow<List<GlucoseReading>> = repo.glucose.state(emptyList())
    val settings: StateFlow<AppSettings> = repo.settings.state(AppSettings())
    val model: StateFlow<ModelResult?> = repo.model.state(null)

    private val _date = MutableStateFlow(LocalDate.now())
    val date = _date.asStateFlow()
    fun selectDate(d: LocalDate) { _date.value = d }

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch { block() }

    fun saveDay(d: DayEntry) = io { repo.saveDay(d) }

    private fun nowMinutes(date: LocalDate) =
        if (date == LocalDate.now()) LocalTime.now().let { it.hour * 60 + it.minute } else null

    fun addMeal(date: LocalDate, t: MealTemplate) = io {
        repo.saveMeal(MealEntry(date = date.toEpochDay(), templateId = t.id, name = t.name, kcal = t.kcal, time = nowMinutes(date)))
        repo.saveTemplate(t.copy(lastUsed = System.currentTimeMillis()))
    }

    fun addOneOffMeal(date: LocalDate, name: String, kcal: Int, saveAsTemplate: Boolean) = io {
        var templateId: Long? = null
        if (saveAsTemplate) templateId = repo.saveTemplate(MealTemplate(name = name, kcal = kcal, lastUsed = System.currentTimeMillis()))
        repo.saveMeal(MealEntry(date = date.toEpochDay(), templateId = templateId, name = name, kcal = kcal, time = nowMinutes(date)))
    }

    fun updateMeal(m: MealEntry) = io { repo.saveMeal(m) }
    fun deleteMeal(id: Long) = io { repo.deleteMeal(id) }
    fun saveTemplate(t: MealTemplate) = io { repo.saveTemplate(t) }
    fun deleteTemplate(id: Long) = io { repo.deleteTemplate(id) }
    fun saveLift(l: LiftSet) = io { repo.saveLift(l) }
    fun deleteLift(date: Long, lift: String) = io { repo.deleteLift(date, lift) }
    fun saveGlucose(g: GlucoseReading) = io { repo.saveGlucose(g) }
    fun deleteGlucose(id: Long) = io { repo.deleteGlucose(id) }
    fun saveSettings(s: AppSettings) = io { repo.saveSettings(s); _message.value = "Settings saved" }

    fun importCsv(uri: Uri) = io {
        val ctx = getApplication<Application>()
        _message.value = runCatching {
            val text = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }
            repo.importCsv(text)
        }.getOrElse { "Import failed: ${it.message}" }
    }

    fun exportCsv(tree: Uri) = io {
        val ctx = getApplication<Application>()
        _message.value = runCatching {
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
        }.getOrElse { "Export failed: ${it.message}" }
    }
}

