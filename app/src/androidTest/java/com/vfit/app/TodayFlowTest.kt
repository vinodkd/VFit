package com.vfit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vfit.app.ui.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real UI against a fresh database. */
@RunWith(AndroidJUnit4::class)
class TodayFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("vfit.db")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() = scenario.close()

    @Test
    fun logWeightAndMealThenSeeSummary() {
        rule.onNode(hasText("Weight (kg)") and hasSetTextAction()).performTextInput("84.2")
        rule.onNodeWithText("Moderate").performClick()
        rule.onAllNodesWithText("Save").onFirst().performClick()   // Body card comes first
        rule.onNodeWithText("Saved").assertIsDisplayed()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Trend 84.2 kg", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("One-off meal").performScrollTo().performClick()
        rule.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Lunch")
        rule.onNode(hasText("kcal") and hasSetTextAction()).performTextInput("800")
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("800 kcal")).fetchSemanticsNodes().isNotEmpty() }
        // saved as a template chip for next time
        rule.onNodeWithText("Lunch · 800").assertIsDisplayed()

        rule.onNodeWithText("Summary").performClick()
        rule.onNodeWithText("Trend weight").assertIsDisplayed()
        rule.onNodeWithText("84.2 kg").assertIsDisplayed()
    }
}
