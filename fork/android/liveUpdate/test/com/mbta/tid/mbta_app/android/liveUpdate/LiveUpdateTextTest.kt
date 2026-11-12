package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.InTransitTo
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.StoppedAt
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateTextTest {
    /** English templates, copied from live_update_strings.xml. */
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
                R.string.live_update_chip_boarding to "Board",
                R.string.live_update_chip_arriving to "Now",
                R.string.live_update_chip_minutes to "%1\$d min",
                R.string.live_update_finished_arrived to "Arrived at %1\$s",
                R.string.live_update_finished_departed to "Left %1\$s",
                R.string.live_update_finished_trip_ended to "Trip complete",
                R.string.live_update_finished_cancelled to "Trip cancelled at %1\$s",
                R.string.live_update_finished_already_passed to "Already past %1\$s",
            )

        override fun string(id: Int, vararg args: Any): String = strings.getValue(id).format(*args)

        override fun plural(id: Int, count: Int, vararg args: Any): String {
            check(id == R.plurals.live_update_stops_away)
            val template = if (count == 1) "%1\$s · %2\$d stop away" else "%1\$s · %2\$d stops away"
            return template.format(*args)
        }

        override fun clockTime(time: EasternTimeInstant): String = "9:30 AM"
    }

    private val f = LiveUpdateFixtures()
    private val strings = FakeStrings()
    private val anchor =
        checkNotNull(TrackingAnchor.from(f.snapshot(f.vehicle(InTransitTo, 20)), f.request))

    private fun text(snapshot: TrackerSnapshot, request: LiveUpdateRequest = f.request) =
        LiveUpdateTextFormatter.format(
            LiveUpdateStateCalculator.compute(snapshot, request, anchor, true, f.now),
            strings,
        )

    @Test
    fun `waiting text has the countdown and the stops away`() {
        val text = text(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes)))

        assertEquals("Red Line to Alewife", text.title)
        assertEquals("4 min to C · 1 stop away", text.text)
        assertEquals("4 min", text.chip)
    }

    @Test
    fun `boarding text has no stop count`() {
        val text = text(f.snapshot(f.vehicle(StoppedAt, 30)))

        assertEquals("Boarding at C", text.text)
        assertEquals("Board", text.chip)
    }

    @Test
    fun `riding text names the destination`() {
        val text =
            text(
                f.snapshot(f.vehicle(InTransitTo, 40), mapOf(50 to 3.minutes)),
                f.requestWithDestination,
            )

        assertEquals("3 min to E · 1 stop away", text.text)
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
        for (label in labels) {
            val chip = LiveUpdateTextFormatter.chip(label, strings)
            assertTrue("$label: $chip", chip == null || chip.length <= 7)
        }
        assertNull(LiveUpdateTextFormatter.chip(LiveUpdateLabel.ClockTime(f.now), strings))
        assertEquals("1 min", LiveUpdateTextFormatter.chip(LiveUpdateLabel.Approaching, strings))
    }

    @Test
    fun `clock time text`() {
        val text = text(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 90.minutes)))

        assertEquals("Arrives at C at 9:30 AM · 1 stop away", text.text)
        assertNull(text.chip)
    }
}
