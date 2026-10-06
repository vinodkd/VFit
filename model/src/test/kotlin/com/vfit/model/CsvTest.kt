package com.vfit.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CsvTest {
    @Test
    fun parsesQuotedFieldsAndCrLf() {
        val (h, rows) = GoldenIo.parseCsv("a,b,c\r\n1,\"x, y\",\"say \"\"hi\"\"\"\r\n\r\n2,,\n")
        assertEquals(listOf("a", "b", "c"), h)
        assertEquals(listOf(listOf("1", "x, y", "say \"hi\""), listOf("2", "", "")), rows)
    }

    @Test
    fun parsesInputsWithLiftsAndActivity() {
        val inp = GoldenIo.parseInputs(
            "date,weight_kg,waist_cm,calories,activity,grip,e1rm_squat\n" +
                "2026-03-01,90.5,,1600,mod,,\n2026-03-02,,101,,,45,120\nbad,1,,,,,\n"
        )
        assertEquals(2, inp.size)
        assertEquals(DayInput(LocalDate.of(2026, 3, 1), weightKg = 90.5, calories = 1600.0, activity = MODERATE), inp[0])
        assertEquals(mapOf("squat" to 120.0), inp[1].e1rm)
        assertEquals(UNKNOWN, inp[1].activity)
    }
}
