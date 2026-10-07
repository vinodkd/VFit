package com.vfit.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vfit.app.data.MealEntry
import com.vfit.app.data.Repository
import com.vfit.app.data.VFitDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: VFitDb
    private lateinit var repo: Repository
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun memDb() = Room.inMemoryDatabaseBuilder(ctx, VFitDb::class.java).build()

    @Before fun setUp() { db = memDb(); repo = Repository(db) }
    @After fun tearDown() = db.close()

    private val inputsCsv = buildString {
        appendLine("date,weight_kg,waist_cm,calories,activity,grip,e1rm_squat")
        for (i in 0 until 40) {
            val d = LocalDate.of(2026, 3, 1).plusDays(i.toLong())
            appendLine("$d,${95.0 - i * 0.08},${if (i % 7 == 0) 104 - i * 0.1 else ""},1800,${if (i % 2 == 0) "light" else ""},,${if (i % 7 == 3) 110 else ""}")
        }
    }

    @Test
    fun vitalDataImportOverwritesItsDatesAndKeepsOthers() = runBlocking {
        // app data first: 3 Mar has an override and a waist; 10 Mar is outside the file
        repo.importCsv("date,weight_kg,waist_cm,calories\n2026-03-03,90,100,1500\n2026-03-10,88,,1800\n")
        // as written by `tdee_tracker.py vital-to-csv`
        repo.importCsv(
            "date,weight_kg,waist_cm,calories,activity,grip,fasting,bp_sys,bp_dia,pulse\n" +
                "2026-03-01,91,,1600.0,,,,127.0,85.0,67.0\n" +
                "2026-03-02,90.5,,0.0,,,1,,,\n" +
                "2026-03-03,90.2,,,,,,,,\n"
        )
        repo.importCsv("date,time,kind,slot,meal,mg_dl\n2026-03-01,07:00,fasting,0,,104.0\n2026-03-01,10:00,2h_post_meal,1,,196.0\n")
        suspend fun d(day: Int) = repo.day(LocalDate.of(2026, 3, day).toEpochDay())!!
        assertEquals(127.0, d(1).bpSys!!, 1e-9); assertEquals(85.0, d(1).bpDia!!, 1e-9); assertEquals(67.0, d(1).pulse!!, 1e-9)
        assertTrue(d(2).fasting); assertEquals(0, d(2).caloriesOverride)
        assertEquals(null, d(3).caloriesOverride)                  // blank in the source = not entered
        assertEquals(null, d(3).waist)                             // source overwrites its dates
        assertEquals(1800, d(10).caloriesOverride)                 // dates outside the file untouched
        val g = repo.glucose.first()
        assertEquals(2, g.size)
        assertEquals(com.vfit.app.data.Slot.BREAKFAST.id, g.first { it.kind == com.vfit.app.data.GLUCOSE_POST_MEAL }.slot)
        // 3 Mar has no calories -> assumed by the model, not stored
        assertTrue(LocalDate.of(2026, 3, 3) in repo.computed.first().assumed)
    }

    @Test
    fun importComputesModelAndExportRoundTrips() = runBlocking {
        assertEquals("Imported 40 days.", repo.importCsv(inputsCsv))
        assertEquals("Imported settings.", repo.importCsv("setting,value\ngoal_weight,85\nage,46\n"))
        val s = repo.settings.first()
        assertEquals(85.0, s.goalWeightKg!!, 1e-9)
        assertEquals(46.0, s.age, 1e-9)

        val r = repo.computed.first().result
        assertNotNull(r)
        assertEquals(40, r!!.outputs.size)
        assertNotNull(r.latest!!.tdeeEst)

        // a meal on a day with an imported total doesn't change that day's calories (override wins)
        repo.saveMeal(MealEntry(date = LocalDate.of(2026, 3, 1).toEpochDay(), name = "x", kcal = 500))
        assertEquals(1800.0, repo.computed.first().result!!.calories[0], 1e-9)

        repo.saveDay(repo.day(LocalDate.of(2026, 3, 5).toEpochDay())!!.copy(fasting = true))
        val files = repo.exportFiles()
        assertEquals(setOf("inputs.csv", "config.csv", "meals.csv", "glucose.csv", "exercise.csv"), files.keys)
        assertTrue(files["meals.csv"]!!.contains("2026-03-01,,,x,500,1.0"))

        // re-import into a fresh database gives the same model output
        val db2 = memDb()
        try {
            val repo2 = Repository(db2)
            repo2.importCsv(files["inputs.csv"]!!)
            repo2.importCsv(files["config.csv"]!!)
            val a = r.latest!!
            val b = repo2.computed.first().result!!.latest!!
            assertEquals(a.trendWeight, b.trendWeight, 1e-9)
            assertEquals(a.tdeeEst!!, b.tdeeEst!!, 1e-6)
            assertEquals(a.strengthIndex, b.strengthIndex)
            assertEquals(true, repo2.day(LocalDate.of(2026, 3, 5).toEpochDay())!!.fasting)
            assertEquals(false, repo2.day(LocalDate.of(2026, 3, 6).toEpochDay())!!.fasting)
        } finally {
            db2.close()
        }
    }
}
