package com.vfit.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Dates are stored as epoch days, times as minutes after midnight. Weights in kg, lengths in cm, glucose in mg/dL.

@Entity(tableName = "day_entry")
data class DayEntry(
    @PrimaryKey val date: Long,
    val weight: Double? = null,
    val waist: Double? = null,
    val activity: Int? = null,
    val grip: Double? = null,
    /** Used instead of the sum of meal entries when set (imported totals, quick totals). */
    val caloriesOverride: Int? = null,
    val note: String? = null,
)

@Entity(tableName = "meal_template")
data class MealTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kcal: Int,
    val archived: Boolean = false,
    /** Epoch millis of last use, for "recent first" ordering. */
    val lastUsed: Long = 0,
)

@Entity(tableName = "meal_entry", indices = [Index("date")])
data class MealEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val templateId: Long? = null,
    /** Name and kcal are copied at log time so editing a template doesn't rewrite history. */
    val name: String,
    val kcal: Int,
    val quantity: Double = 1.0,
    val time: Int? = null,
)

@Entity(tableName = "lift_set", primaryKeys = ["date", "lift"])
data class LiftSet(
    val date: Long,
    val lift: String,
    val load: Double,
    val reps: Int,
) {
    /** Epley estimated 1-rep max, as in tdee_tracker.py. */
    val e1rm: Double get() = if (reps == 1) load else load * (1 + reps / 30.0)
}

const val GLUCOSE_FASTING = 0
const val GLUCOSE_POST_MEAL = 1

@Entity(tableName = "glucose_reading", indices = [Index("date")])
data class GlucoseReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val time: Int,
    val kind: Int,
    val mealEntryId: Long? = null,
    val value: Double,
)

@Entity(tableName = "setting")
data class SettingRow(@PrimaryKey val key: String, val value: String)

@Dao
interface VFitDao {
    @Query("SELECT * FROM day_entry ORDER BY date") fun days(): Flow<List<DayEntry>>
    @Query("SELECT * FROM meal_entry ORDER BY date, id") fun meals(): Flow<List<MealEntry>>
    @Query("SELECT * FROM meal_template ORDER BY lastUsed DESC, name") fun templates(): Flow<List<MealTemplate>>
    @Query("SELECT * FROM lift_set ORDER BY date, lift") fun lifts(): Flow<List<LiftSet>>
    @Query("SELECT * FROM glucose_reading ORDER BY date, time") fun glucose(): Flow<List<GlucoseReading>>
    @Query("SELECT * FROM setting") fun settings(): Flow<List<SettingRow>>

    @Query("SELECT * FROM day_entry WHERE date = :date") suspend fun day(date: Long): DayEntry?
    @Query("SELECT * FROM day_entry ORDER BY date") suspend fun allDays(): List<DayEntry>
    @Query("SELECT * FROM meal_entry ORDER BY date, id") suspend fun allMeals(): List<MealEntry>
    @Query("SELECT * FROM lift_set ORDER BY date, lift") suspend fun allLifts(): List<LiftSet>
    @Query("SELECT * FROM glucose_reading ORDER BY date, time") suspend fun allGlucose(): List<GlucoseReading>
    @Query("SELECT * FROM meal_template") suspend fun allTemplates(): List<MealTemplate>
    @Query("SELECT * FROM setting") suspend fun allSettings(): List<SettingRow>

    @Upsert suspend fun upsertDay(d: DayEntry)
    @Upsert suspend fun upsertDays(d: List<DayEntry>)
    @Upsert suspend fun upsertMeal(m: MealEntry): Long
    @Upsert suspend fun upsertTemplate(t: MealTemplate): Long
    @Upsert suspend fun upsertLift(l: LiftSet)
    @Upsert suspend fun upsertLifts(l: List<LiftSet>)
    @Upsert suspend fun upsertGlucose(g: GlucoseReading): Long
    @Upsert suspend fun upsertSettings(s: List<SettingRow>)

    @Query("DELETE FROM meal_entry WHERE id = :id") suspend fun deleteMeal(id: Long)
    @Query("DELETE FROM glucose_reading WHERE mealEntryId = :mealId") suspend fun deleteGlucoseForMeal(mealId: Long)
    @Query("DELETE FROM glucose_reading WHERE id = :id") suspend fun deleteGlucose(id: Long)
    @Query("DELETE FROM lift_set WHERE date = :date AND lift = :lift") suspend fun deleteLift(date: Long, lift: String)
    @Query("DELETE FROM meal_template WHERE id = :id") suspend fun deleteTemplate(id: Long)

    @Transaction
    suspend fun deleteMealWithGlucose(id: Long) {
        deleteGlucoseForMeal(id)
        deleteMeal(id)
    }
}

@Database(
    entities = [DayEntry::class, MealTemplate::class, MealEntry::class, LiftSet::class, GlucoseReading::class, SettingRow::class],
    version = 1,
    exportSchema = false,
)
abstract class VFitDb : RoomDatabase() {
    abstract fun dao(): VFitDao

    companion object {
        fun open(context: Context): VFitDb =
            Room.databaseBuilder(context, VFitDb::class.java, "vfit.db").build()
    }
}
