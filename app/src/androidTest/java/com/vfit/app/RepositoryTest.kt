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
    fun importComputesModelAndExportRoundTrips() = runBlocking {
        assertEquals("Imported 40 days.", repo.importCsv(inputsCsv))
        assertEquals("Imported settings.", repo.importCsv("setting,value\ngoal_weight,85\nage,46\n"))
        val s = repo.settings.first()
        assertEquals(85.0, s.goalWeightKg!!, 1e-9)
        assertEquals(46.0, s.age, 1e-9)

        val r = repo.model.first()
        assertNotNull(r)
        assertEquals(40, r!!.outputs.size)
        assertNotNull(r.latest!!.tdeeEst)

        // a meal on a day with an imported total doesn't change that day's calories (override wins)
        repo.saveMeal(MealEntry(date = LocalDate.of(2026, 3, 1).toEpochDay(), name = "x", kcal = 500))
        assertEquals(1800.0, repo.model.first()!!.calories[0], 1e-9)

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
            val b = repo2.model.first()!!.latest!!
            assertEquals(a.trendWeight, b.trendWeight, 1e-9)
            assertEquals(a.tdeeEst!!, b.tdeeEst!!, 1e-6)
            assertEquals(a.strengthIndex, b.strengthIndex)
        } finally {
            db2.close()
        }
    }
}
