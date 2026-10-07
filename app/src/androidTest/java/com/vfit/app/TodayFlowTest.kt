package com.vfit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun timelineLogsMorningMealGlucoseAndExercise() {
        // morning check-in
        rule.onNode(hasText("Weight") and hasSetTextAction()).performTextInput("84.2")
        rule.onNode(hasText("Fasting glucose") and hasSetTextAction()).performTextInput("98")
        rule.onNodeWithText("More: blood pressure, pulse").performClick()
        rule.onNode(hasText("BP upper") and hasSetTextAction()).performTextInput("125")
        rule.onNode(hasText("BP lower") and hasSetTextAction()).performTextInput("82")
        rule.onNodeWithText("Save check-in").performClick()      // one Save for weight, glucose, waist, grip
        rule.onNodeWithText("Saved").assertIsDisplayed()
        waitFor("84.2 kg")

        // fasting marks the day but meals stay available
        rule.onNode(hasText("Fasting today") , useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(isToggleable()).performClick()
        waitFor("Fasting")

        // breakfast starts empty; nothing counts as eaten until it's logged
        rule.onAllNodesWithText("Not logged yet").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Log breakfast").performScrollTo().performClick()
        rule.onNode(hasText("Meal") and hasSetTextAction()).performTextInput("Oats")
        rule.onNode(hasText("Calories") and hasSetTextAction()).performTextInput("450")
        rule.onNodeWithText("Log it").performClick()
        waitFor("450 kcal", substring = true)

        // post-meal blood sugar is a separate timeline step that appears after logging
        rule.onNodeWithText("Blood sugar 2 h after breakfast").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Blood sugar") and hasSetTextAction()).performTextInput("135")
        rule.onNodeWithText("Save").performClick()
        waitFor("135 mg/dL at", substring = true)

        // the saved meal is offered in another slot's dropdown (typing filters it), not shown as eaten there
        rule.onNodeWithText("Log morning snack").performScrollTo().performClick()
        rule.onNode(hasText("Meal") and hasSetTextAction()).performTextInput("oa")
        rule.onNode(hasText("Oats") and hasText("450 kcal") and hasClickAction()).performClick()   // the dropdown item
        rule.onNodeWithText("Saved meal · 450 kcal").assertIsDisplayed()
        rule.onNodeWithText("Log it").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Remove meal")).fetchSemanticsNodes().size == 2 }

        // delete is separate from the quantity stepper, and can be undone
        rule.onAllNodes(hasContentDescription("Remove meal"))[1].performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Remove meal")).fetchSemanticsNodes().size == 1 }
        waitFor("Undo")
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Remove meal")).fetchSemanticsNodes().size == 2 }

        // morning exercise: an activity and a strength set, both in the exercise step
        rule.onAllNodesWithText("Activity").onFirst().performScrollTo().performClick()
        rule.onNode(hasText("Exercise") and hasSetTextAction()).performTextInput("Walk")
        rule.onNode(hasText("Duration") and hasSetTextAction()).performTextInput("30")
        rule.onNodeWithText("Log it").performClick()
        waitFor("Walk")
        rule.onAllNodesWithText("Strength set").onFirst().performScrollTo().performClick()
        rule.onNode(hasText("Lift") and hasSetTextAction()).performTextInput("squat")
        rule.onNode(hasText("Load") and hasSetTextAction()).performTextInput("100")
        rule.onNode(hasText("Reps") and hasSetTextAction()).performTextInput("6")
        rule.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        waitFor("squat 100 × 6")
        rule.onNodeWithText("top set · e1RM 120").assertIsDisplayed()

        // an exercise done before is offered in the next slot's dropdown, with its last duration
        rule.onAllNodesWithText("Activity")[1].performScrollTo().performClick()
        rule.onNode(hasText("Exercise") and hasSetTextAction()).performClick()
        rule.onNodeWithText("30 min last time").performClick()
        rule.onNodeWithText("Log it").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Walk")).fetchSemanticsNodes().size == 2 }

        // activity for the model
        rule.onNodeWithText("Moderate").performScrollTo().performClick()

        // Do next: the year at a glance and the next step
        rule.onNodeWithText("Do next").performClick()
        rule.onNode(hasText(" so far · ", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Fasted").assertIsDisplayed()
        rule.onNodeWithText("NEXT STEP").performScrollTo().assertIsDisplayed()

        // targets set in Settings show how many days are left
        rule.onNodeWithText("Settings").performClick()
        rule.onNode(hasText("Lived") and hasSetTextAction()).performScrollTo().performTextInput("20")
        rule.onNodeWithText("Save settings").performScrollTo().performClick()
        rule.onNodeWithText("Do next").performClick()
        waitFor(" left", substring = true)

        // Trends: tiles, then charts with a scrubber readout
        rule.onNodeWithText("Trends").performClick()
        rule.onNodeWithText("Trend weight").assertIsDisplayed()
        rule.onNodeWithText("84.2 kg").assertIsDisplayed()
        rule.onNodeWithText("125/82").performScrollTo().assertIsDisplayed()      // blood pressure tile
        rule.onAllNodesWithText("Touch and slide on the chart to read values").onFirst().performScrollTo().assertIsDisplayed()
    }
}
