package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.InTransitTo
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.StoppedAt
import com.mbta.tid.mbta_app.model.Vehicle.OccupancyStatus
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateTextTest {
    /** English templates, copied from live_update_strings.xml and the app's strings.xml. */
    private class FakeStrings : StringLookup {
        private val strings =
            mapOf(
                R.string.live_update_title to "%1\$s to %2\$s",
                R.string.live_update_text_boarding to "Boarding at %1\$s",
                R.string.live_update_text_arriving to "Arriving at %1\$s",
                R.string.live_update_text_approaching to "1 min to %1\$s",
                R.string.live_update_text_minutes to "%1\$d min to %2\$s",
                R.string.live_update_text_clock_time to "Arrives at %2\$s at %1\$s",
                R.string.live_update_text_unknown to "Waiting for predictions for %1\$s",
                R.string.live_update_wait_boarding to "Boarding now",
                R.string.live_update_wait_arriving to "Arriving now",
                R.string.live_update_short_minutes to "%1\$d min",
                R.string.live_update_wait_unknown to "Waiting for predictions",
                R.string.live_update_next_stop_to to "Next stop: %1\$s",
                R.string.live_update_time_to to "%1\$s to %2\$s",
                R.string.live_update_next to "Next: %1\$s",
                R.string.live_update_at_stop_eta to "%1\$s · ETA %2\$s",
                R.string.live_update_chip_boarding to "Board",
                R.string.live_update_chip_arriving to "Now",
                R.string.live_update_chip_minutes to "%1\$d min",
                R.string.live_update_finished_arrived to "Arrived at %1\$s",
                R.string.live_update_finished_departed to "Left %1\$s",
                R.string.live_update_finished_trip_ended to "Trip complete",
                R.string.live_update_finished_cancelled to "Trip cancelled at %1\$s",
                R.string.live_update_finished_already_passed to "Already past %1\$s",
                R.string.live_update_sub_eta to "ETA %1\$s",
                R.string.live_update_sub_arrive to "Arrive %1\$s",
                R.string.live_update_ride_minutes to "%1\$d min ride",
                R.string.live_update_ride_hours to "%1\$d hr %2\$d min ride",
                R.string.crowding_not_crowded to "Not crowded",
                R.string.crowding_some_crowding to "Some crowding",
                R.string.crowding_crowded to "Crowded",
            )

        private val plurals =
            mapOf(
                R.plurals.live_update_stops_away to ("%1\$d stop away" to "%1\$d stops away"),
                R.plurals.live_update_stops_to to ("%1\$d stop to %2\$s" to "%1\$d stops to %2\$s"),
                R.plurals.live_update_chip_stops to ("%1\$d stop" to "%1\$d stops"),
            )

        override fun string(id: Int, vararg args: Any): String = strings.getValue(id).format(*args)

        override fun plural(id: Int, count: Int, vararg args: Any): String {
            val (one, other) = plurals.getValue(id)
            return (if (count == 1) one else other).format(*args)
        }

        override fun clockTime(time: EasternTimeInstant): String = "9:30 AM"
    }

    private val f = LiveUpdateFixtures()
    private val strings = FakeStrings()
    private val anchor =
        checkNotNull(TrackingAnchor.from(f.snapshot(f.vehicle(InTransitTo, 20)), f.request))

    private fun state(snapshot: TrackerSnapshot, request: LiveUpdateRequest = f.request) =
        LiveUpdateStateCalculator.compute(snapshot, request, anchor, true, f.now)

    private fun text(snapshot: TrackerSnapshot, request: LiveUpdateRequest = f.request) =
        LiveUpdateTextFormatter.format(state(snapshot, request), strings)

    private fun riding(
        stopSequence: Int,
        toDestination: kotlin.time.Duration,
        status: com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus = InTransitTo,
    ) =
        state(
            f.snapshot(f.vehicle(status, stopSequence), mapOf(50 to toDestination)),
            f.requestWithDestination,
        )

    // Waiting

    @Test
    fun `waiting puts the countdown and the stops away first`() {
        val text = text(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes)))

        assertEquals("4 min · 1 stop away", text.title)
        assertEquals("Red Line to Alewife", text.subText)
        assertEquals("C · ETA 9:30 AM", text.text)
        assertEquals("4 min", text.chip)
    }

    @Test
    fun `waiting with a destination ends with the arrival and the ride time`() {
        val text =
            text(
                f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes, 50 to 12.minutes)),
                f.requestWithDestination,
            )

        assertEquals("4 min · 1 stop away", text.title)
        assertEquals("Arrive 9:30 AM · 8 min ride", text.text)
    }

    @Test
    fun `waiting shows whole-vehicle crowding`() {
        val text =
            text(
                f.snapshot(
                    f.vehicle(InTransitTo, 20, occupancy = OccupancyStatus.ManySeatsAvailable),
                    mapOf(30 to 4.minutes),
                ),
            )

        assertEquals("Red Line to Alewife · Not crowded", text.subText)
    }

    @Test
    fun `waiting leaves out crowding with per-car data`() {
        val vehicle =
            f.vehicle(
                InTransitTo,
                20,
                occupancy = OccupancyStatus.ManySeatsAvailable,
                carOccupancy = listOf(OccupancyStatus.Full, OccupancyStatus.ManySeatsAvailable),
            )

        assertEquals("Red Line to Alewife", text(f.snapshot(vehicle)).subText)
    }

    @Test
    fun `boarding has no stop count`() {
        val text = text(f.snapshot(f.vehicle(StoppedAt, 30)))

        assertEquals("Boarding now", text.title)
        assertEquals("Board", text.chip)
    }

    @Test
    fun `clock time`() {
        val text = text(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 90.minutes)))

        assertEquals("9:30 AM · 1 stop away", text.title)
        assertNull(text.chip)
    }

    @Test
    fun `waiting without predictions`() {
        val text = text(f.snapshot(f.vehicle(InTransitTo, 20)))

        assertEquals("1 stop away", text.title)
        assertEquals("C", text.text)
    }

    @Test
    fun `waiting without predictions or a vehicle`() {
        assertEquals("Waiting for predictions", text(f.snapshot(null)).title)
    }

    // Riding

    @Test
    fun `riding title has the numbers then the destination`() {
        val text = LiveUpdateTextFormatter.format(riding(40, 3.minutes), strings)

        assertEquals("3 min · 2 stops to E", text.title)
        assertEquals("Next: D", text.subText)
        // The boarding stop left the stop list, so without a remembered time there's no ride time.
        assertEquals("Arrive 9:30 AM", text.text)
    }

    @Test
    fun `riding to the last stop names it as the next stop`() {
        val inTransit = LiveUpdateTextFormatter.format(riding(50, 2.minutes), strings)
        val stopped =
            LiveUpdateTextFormatter.format(riding(40, 2.minutes, status = StoppedAt), strings)

        for (text in listOf(inTransit, stopped)) {
            assertEquals("2 min · Next stop: E", text.title)
            assertNull(text.subText)
            assertEquals("1 stop", text.chip)
        }
    }

    @Test
    fun `riding never shows crowding or the route`() {
        val state =
            state(
                f.snapshot(
                    f.vehicle(InTransitTo, 40, occupancy = OccupancyStatus.Full),
                    mapOf(50 to 3.minutes),
                ),
                f.requestWithDestination,
            )

        assertNull(state.crowding)
        assertEquals("Next: D", LiveUpdateTextFormatter.format(state, strings).subText)
    }

    @Test
    fun `riding arriving`() {
        val text = LiveUpdateTextFormatter.format(riding(50, 10.seconds), strings)

        assertEquals("Arriving at E", text.title)
        assertNull(text.subText)
        assertEquals("Now", text.chip)
    }

    @Test
    fun `riding without a known next stop has no second line`() {
        val text =
            LiveUpdateTextFormatter.format(riding(40, 3.minutes).copy(nextStopName = null), strings)

        assertNull(text.subText)
    }

    @Test
    fun `riding with unknown parts`() {
        fun title(state: LiveUpdateState) = LiveUpdateTextFormatter.format(state, strings).title
        val base = riding(40, 3.minutes)

        assertEquals("3 min to E", title(base.copy(stopsAway = null)))
        assertEquals("2 stops to E", title(base.copy(label = LiveUpdateLabel.Unknown)))
        assertEquals(
            "Waiting for predictions for E",
            title(base.copy(label = LiveUpdateLabel.Unknown, stopsAway = null)),
        )
    }

    @Test
    fun `numbers come first in the title so they are never cut off`() {
        val labels =
            listOf(
                LiveUpdateLabel.Boarding,
                LiveUpdateLabel.Arriving,
                LiveUpdateLabel.Approaching,
                LiveUpdateLabel.Minutes(59),
                LiveUpdateLabel.ClockTime(f.now),
                LiveUpdateLabel.Unknown,
            )
        val destination = "Massachusetts Avenue"
        val base = riding(40, 3.minutes).copy(destinationStopName = destination)
        for (label in labels) {
            for (stopsAway in listOf(null, 0, 1, 9, 30)) {
                for (phase in listOf(LiveUpdatePhase.Waiting, LiveUpdatePhase.Riding)) {
                    // The calculator gives the Boarding label only while waiting.
                    if (phase == LiveUpdatePhase.Riding && label == LiveUpdateLabel.Boarding) {
                        continue
                    }
                    val state = base.copy(label = label, stopsAway = stopsAway, phase = phase)
                    val title = LiveUpdateTextFormatter.format(state, strings).title
                    val message = "$label $stopsAway $phase: $title"
                    if (phase == LiveUpdatePhase.Waiting) {
                        assertTrue(message, title.length <= MAX_TITLE)
                    } else {
                        // Only the destination, which comes last, may be cut off.
                        assertTrue(message, title.endsWith(destination))
                        val hasNumbers = label != LiveUpdateLabel.Unknown || stopsAway != null
                        if (hasNumbers) {
                            val prefix = title.removeSuffix(destination)
                            assertTrue(message, prefix.length <= MAX_TITLE)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** "12:45 PM · 30 stops away" is the longest real waiting title. */
        const val MAX_TITLE = 24
    }

    @Test
    fun `riding chip shows stops only near the end`() {
        fun chip(state: LiveUpdateState) = LiveUpdateTextFormatter.chip(state, strings)

        assertEquals("2 stops", chip(riding(40, 9.minutes)))
        assertEquals("10 min", chip(riding(40, 10.minutes)))
        assertEquals("3 min", chip(riding(40, 3.minutes).copy(stopsAway = 3)))
        assertEquals("3 stops", chip(riding(40, 3.minutes).copy(stopsAway = 2)))
        assertEquals("2 stops", chip(riding(40, 3.minutes).copy(label = LiveUpdateLabel.Unknown)))
        assertEquals(
            "2 stops",
            chip(riding(40, 3.minutes).copy(label = LiveUpdateLabel.Approaching)),
        )
        assertNull(chip(riding(40, 90.minutes)))
    }

    // Shared helpers and finished

    @Test
    fun `sub text leaves out unknown parts`() {
        val noPredictions = state(f.snapshot(f.vehicle(InTransitTo, 20)), f.requestWithDestination)
        val longRide =
            state(
                    f.snapshot(
                        f.vehicle(InTransitTo, 20),
                        mapOf(30 to 4.minutes, 50 to 12.minutes),
                    ),
                    f.requestWithDestination,
                )
                .copy(rideDuration = 75.minutes)

        assertNull(LiveUpdateTextFormatter.subText(noPredictions, strings))
        assertEquals(
            "Arrive 9:30 AM · 1 hr 15 min ride",
            LiveUpdateTextFormatter.subText(longRide, strings),
        )
    }

    @Test
    fun `status text has no stop count`() {
        val state = state(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes)))

        assertEquals("4 min to C", LiveUpdateTextFormatter.statusText(state, strings))
    }

    @Test
    fun `finished texts`() {
        assertEquals("Left C", text(f.snapshot(f.vehicle(InTransitTo, 40))).text)
        assertEquals(
            "Arrived at E",
            text(f.snapshot(f.vehicle(StoppedAt, 50)), f.requestWithDestination).text,
        )
        assertNull(text(f.snapshot(f.vehicle(InTransitTo, 40))).chip)
    }

    @Test
    fun `chip text fits the status chip or is left out`() {
        val labels =
            listOf(
                LiveUpdateLabel.Boarding,
                LiveUpdateLabel.Arriving,
                LiveUpdateLabel.Approaching,
                LiveUpdateLabel.Minutes(1),
                LiveUpdateLabel.Minutes(59),
                LiveUpdateLabel.ClockTime(f.now),
                LiveUpdateLabel.Unknown,
            )
        val base = riding(40, 3.minutes)
        for (label in labels) {
            for (stopsAway in listOf(null, 0, 1, 8, 9, 30)) {
                for (phase in listOf(LiveUpdatePhase.Waiting, LiveUpdatePhase.Riding)) {
                    val state = base.copy(label = label, stopsAway = stopsAway, phase = phase)
                    val chip = LiveUpdateTextFormatter.chip(state, strings)
                    assertTrue("$state: $chip", chip == null || chip.length <= 7)
                }
            }
        }
        assertNull(LiveUpdateTextFormatter.chip(LiveUpdateLabel.ClockTime(f.now), strings))
        assertEquals("1 min", LiveUpdateTextFormatter.chip(LiveUpdateLabel.Approaching, strings))
    }
}
