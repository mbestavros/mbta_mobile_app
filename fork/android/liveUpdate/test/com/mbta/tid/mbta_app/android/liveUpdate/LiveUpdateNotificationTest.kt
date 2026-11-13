package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveUpdateNotificationTest {
    private val now = EasternTimeInstant.now()
    private val text = LiveUpdateText("Red Line to Ashmont", "Arrives at 10:45 AM", null)

    private fun state(time: EasternTimeInstant = now, progress: Int = 2) =
        LiveUpdateState(
            phase = LiveUpdatePhase.Waiting,
            routeName = "Red Line",
            headsign = "Ashmont",
            routeColor = "DA291C",
            routeTextColor = "FFFFFF",
            boardingStopName = "Park Street",
            destinationStopName = null,
            targetStopName = "Park Street",
            label = LiveUpdateLabel.ClockTime(time),
            targetTime = time,
            boardingTime = time,
            destinationArrival = null,
            eta = time,
            rideDuration = null,
            stopsAway = 3,
            progress = progress,
            progressMax = 8,
            boardingPosition = 8,
            destinationPosition = null,
        )

    private fun content(state: LiveUpdateState, text: LiveUpdateText = this.text) =
        LiveUpdateNotification.ongoingContent(state, text, "/s/place-pktrm")

    @Test
    fun `content is the same when only the exact time changes`() {
        assertEquals(content(state()), content(state(time = now + 20.seconds)))
    }

    @Test
    fun `content changes with the progress or the text`() {
        assertNotEquals(content(state()), content(state(progress = 3)))
        assertNotEquals(content(state()), content(state(), text.copy(text = "Arrives at 10:46 AM")))
    }
}
