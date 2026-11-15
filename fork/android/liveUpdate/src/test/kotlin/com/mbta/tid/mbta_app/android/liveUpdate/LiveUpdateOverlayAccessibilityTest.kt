package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mbta.tid.mbta_app.android.MyApplicationTheme
import com.mbta.tid.mbta_app.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveUpdateOverlayAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun string(id: Int, vararg args: Any) =
        RuntimeEnvironment.getApplication().getString(id, *args)

    private val alewife = DestinationChoice("place-alfcl", 5, "Alewife", "10:05 AM")
    private val davis = DestinationChoice("place-davis", 6, "Davis", null)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private val hasNoRole = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)
    private val isNotSelectable = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)

    private fun setSheet(
        choices: List<DestinationChoice>?,
        selectedStopSequence: Int?,
        canPostPromoted: Boolean = true,
        onChoose: (DestinationChoice?) -> Unit = {},
        onStop: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        compose.setContent {
            MyApplicationTheme {
                DestinationSheetContent(
                    state = null,
                    canPostPromoted = canPostPromoted,
                    boardingStopName = "Park Street",
                    choices = choices,
                    selectedStopSequence = selectedStopSequence,
                    onChoose = onChoose,
                    onStop = onStop,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }

    @Test
    fun pillIsAButtonWithItsLabel() {
        var clicks = 0
        val label = string(R.string.live_update_overlay_track)
        compose.setContent {
            MyApplicationTheme {
                Pill(Color.Black, Color.White, 16.dp, onClick = { clicks++ }) { Text(label) }
            }
        }

        compose
            .onNodeWithText(label)
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, clicks)
    }

    private val red = LiveUpdateFixtures().route

    private fun setPill(starting: Boolean, onClick: () -> Unit = {}) {
        compose.setContent {
            MyApplicationTheme {
                LiveUpdatePill(
                    PillContent(
                        PillKind.Return,
                        PillRoute(red, null),
                        starting = starting,
                        title = string(R.string.live_update_overlay_return),
                        subtitle = "5 min",
                    ),
                    bottomPadding = 16.dp,
                    onClick = onClick,
                )
            }
        }
    }

    @Test
    fun routePillNamesTheRouteWithItsBadge() {
        var clicks = 0
        setPill(starting = false) { clicks++ }

        compose
            .onNodeWithText(string(R.string.live_update_overlay_return))
            .assert(hasRole(Role.Button))
            .assert(hasContentDescription("Red Line", substring = true))
            .assert(hasText("5 min"))
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun startingPillSaysSoInsteadOfTheBadge() {
        compose.mainClock.autoAdvance = false
        setPill(starting = true)
        compose.mainClock.advanceTimeBy(1_000)

        compose
            .onNodeWithText(string(R.string.live_update_overlay_return))
            .assert(hasRole(Role.Button))
            .assert(hasContentDescription(string(R.string.live_update_overlay_starting)))
            .assertHasClickAction()
    }

    @Test
    fun noDestinationIsSelectedByDefault() {
        setSheet(listOf(alewife, davis), selectedStopSequence = null)

        compose
            .onNodeWithText(string(R.string.live_update_sheet_no_destination), substring = true)
            .assert(hasRole(Role.RadioButton))
            .assertIsSelected()
        compose.onNodeWithText("Alewife", substring = true).assertIsNotSelected()
        compose.onNodeWithText("Davis", substring = true).assertIsNotSelected()
    }

    @Test
    fun chosenDestinationIsSelected() {
        setSheet(listOf(alewife, davis), selectedStopSequence = davis.stopSequence)

        compose
            .onNodeWithText("Davis", substring = true)
            .assert(hasRole(Role.RadioButton))
            .assertIsEnabled()
            .assertIsSelected()
        compose.onNodeWithText("Alewife", substring = true).assertIsNotSelected()
        compose
            .onNodeWithText(string(R.string.live_update_sheet_no_destination), substring = true)
            .assertIsNotSelected()
    }

    @Test
    fun noDestinationRowReadsItsDetail() {
        setSheet(listOf(alewife), selectedStopSequence = null)

        compose
            .onNodeWithText(
                string(R.string.live_update_sheet_no_destination_detail, "Park Street"),
                substring = true,
            )
            .assertIsSelected()
    }

    @Test
    fun tappingARowChoosesIt() {
        var chosen: DestinationChoice? = davis
        setSheet(
            listOf(alewife, davis),
            selectedStopSequence = davis.stopSequence,
            onChoose = { chosen = it },
        )

        compose.onNodeWithText("Alewife", substring = true).performClick()
        assertEquals(alewife, chosen)
        compose
            .onNodeWithText(string(R.string.live_update_sheet_no_destination), substring = true)
            .performClick()
        assertNull(chosen)
    }

    @Test
    fun loadingRowIsNotAChoice() {
        setSheet(choices = null, selectedStopSequence = null)

        compose
            .onNodeWithText(string(R.string.live_update_sheet_loading))
            .assert(hasNoRole)
            .assert(isNotSelectable)
            .assertHasNoClickAction()
    }

    @Test
    fun stopButtonIsAnEnabledButton() {
        var stops = 0
        setSheet(listOf(alewife), selectedStopSequence = null, onStop = { stops++ })

        compose
            .onNodeWithText(string(R.string.live_update_sheet_stop))
            .assert(hasRole(Role.Button))
            .assertIsEnabled()
            .performClick()
        assertEquals(1, stops)
    }

    @Test
    fun promotionSettingsRowIsAButton() {
        var opens = 0
        setSheet(
            listOf(alewife),
            selectedStopSequence = null,
            canPostPromoted = false,
            onOpenSettings = { opens++ },
        )

        compose
            .onNodeWithText(string(R.string.live_update_sheet_enable))
            .assert(hasRole(Role.Button))
            .performClick()
        assertEquals(1, opens)
    }

    private fun setSwitchDialog(
        currentTrip: Pair<String, String>?,
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            MyApplicationTheme { SwitchTripDialog(currentTrip, onConfirm, onDismiss) }
        }
    }

    @Test
    fun switchDialogNamesTheTrackedTrip() {
        setSwitchDialog("47" to "Central Square")

        compose.onNodeWithText(string(R.string.live_update_switch_title)).assertExists()
        compose
            .onNodeWithText(
                string(
                    R.string.live_update_switch_text,
                    string(R.string.live_update_title, "47", "Central Square"),
                ),
            )
            .assertExists()
    }

    @Test
    fun switchDialogWithoutTripDataStillExplains() {
        setSwitchDialog(currentTrip = null)

        compose.onNodeWithText(string(R.string.live_update_switch_text_unknown)).assertExists()
    }

    @Test
    fun switchDialogButtonsAreEnabledButtons() {
        var confirms = 0
        var dismissals = 0
        setSwitchDialog("47" to "Central Square", { confirms++ }, { dismissals++ })

        compose
            .onNodeWithText(string(R.string.live_update_switch_confirm))
            .assert(hasRole(Role.Button))
            .assertIsEnabled()
            .performClick()
        assertEquals(1, confirms)
        compose
            .onNodeWithText(string(R.string.live_update_switch_keep))
            .assert(hasRole(Role.Button))
            .assertIsEnabled()
            .performClick()
        assertEquals(1, dismissals)
    }
}
