package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Prediction
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.InTransitTo
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.StoppedAt
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveUpdateStateCalculatorTest {
    private val f = LiveUpdateFixtures()

    /** The anchor from the first snapshot: the vehicle is on the way to B. */
    private val anchor =
        checkNotNull(TrackingAnchor.from(f.snapshot(f.vehicle(InTransitTo, 20)), f.request))

    private fun compute(
        snapshot: TrackerSnapshot,
        request: LiveUpdateRequest = f.request,
        wasOnTrip: Boolean = true,
        rememberedBoardingTime: EasternTimeInstant? = null,
    ) =
        LiveUpdateStateCalculator.compute(
            snapshot,
            request,
            anchor,
            wasOnTrip,
            f.now,
            rememberedBoardingTime,
        )

    @Test
    fun `anchor uses the boarding stop and the remaining sequences`() {
        assertEquals(30, anchor.boardingStopSequence)
        assertEquals("C", anchor.boardingStopName)
        assertEquals(listOf(20, 30, 40, 50), anchor.sequences)
    }

    @Test
    fun `anchor is null when the vehicle already passed the boarding stop`() {
        assertNull(TrackingAnchor.from(f.snapshot(f.vehicle(InTransitTo, 40)), f.request))
    }

    @Test
    fun `waiting shows the countdown to the boarding stop`() {
        val state = compute(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes)))

        assertEquals(LiveUpdatePhase.Waiting, state.phase)
        assertEquals(LiveUpdateLabel.Minutes(4), state.label)
        assertEquals("C", state.targetStopName)
        assertEquals(1, state.stopsAway)
        assertEquals(0, state.progress)
        assertEquals(2, state.boardingPosition)
        assertEquals(2, state.progressMax)
        assertNull(state.destinationPosition)
    }

    @Test
    fun `without a destination, the ETA is the arrival at the boarding stop`() {
        val state = compute(f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes)))

        assertEquals(f.now + 4.minutes, state.eta)
        assertEquals(f.now + 4.minutes, state.boardingTime)
        assertNull(state.destinationArrival)
        assertNull(state.rideDuration)
    }

    @Test
    fun `with a destination, the ETA is the destination arrival and the ride time is known`() {
        val state =
            compute(
                f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes, 50 to 12.minutes)),
                f.requestWithDestination,
            )

        assertEquals(f.now + 12.minutes, state.eta)
        assertEquals(f.now + 12.minutes, state.destinationArrival)
        assertEquals(f.now + 4.minutes, state.boardingTime)
        assertEquals(8.minutes, state.rideDuration)
    }

    @Test
    fun `while riding, the remembered boarding time gives the ride time`() {
        val state =
            compute(
                f.snapshot(f.vehicle(InTransitTo, 40), mapOf(50 to 3.minutes)),
                f.requestWithDestination,
                rememberedBoardingTime = f.now - 2.minutes,
            )

        assertEquals(LiveUpdatePhase.Riding, state.phase)
        assertEquals(f.now - 2.minutes, state.boardingTime)
        assertEquals(f.now + 3.minutes, state.eta)
        assertEquals(5.minutes, state.rideDuration)
    }

    @Test
    fun `waiting without predictions has an unknown label`() {
        val state = compute(f.snapshot(f.vehicle(InTransitTo, 20)))

        assertEquals(LiveUpdatePhase.Waiting, state.phase)
        assertEquals(LiveUpdateLabel.Unknown, state.label)
    }

    @Test
    fun `vehicle stopped at the boarding stop is boarding`() {
        val state = compute(f.snapshot(f.vehicle(StoppedAt, 30), mapOf(50 to 5.minutes)))

        assertEquals(LiveUpdatePhase.Waiting, state.phase)
        assertEquals(LiveUpdateLabel.Boarding, state.label)
        assertEquals(0, state.stopsAway)
        assertEquals(2, state.progress)
    }

    @Test
    fun `without a destination, tracking ends when the vehicle leaves the boarding stop`() {
        val state = compute(f.snapshot(f.vehicle(InTransitTo, 40)))

        assertEquals(LiveUpdatePhase.Finished(FinishReason.DepartedBoarding), state.phase)
    }

    @Test
    fun `with a destination, the ride continues to the destination`() {
        val state =
            compute(
                f.snapshot(f.vehicle(InTransitTo, 40), mapOf(50 to 3.minutes)),
                f.requestWithDestination,
            )

        assertEquals(LiveUpdatePhase.Riding, state.phase)
        assertEquals(LiveUpdateLabel.Minutes(3), state.label)
        assertEquals("E", state.targetStopName)
        assertEquals("E", state.destinationStopName)
        assertEquals(1, state.stopsAway)
        assertEquals(3, state.progress)
        assertEquals(2, state.boardingPosition)
        assertEquals(6, state.destinationPosition)
        assertEquals(6, state.progressMax)
    }

    @Test
    fun `tracking ends at the destination`() {
        val state = compute(f.snapshot(f.vehicle(StoppedAt, 50)), f.requestWithDestination)

        assertEquals(LiveUpdatePhase.Finished(FinishReason.ArrivedDestination), state.phase)
    }

    @Test
    fun `a destination before the boarding stop is ignored`() {
        val state =
            compute(
                f.snapshot(f.vehicle(InTransitTo, 40)),
                f.request.withDestination(f.stopB.id, 20),
            )

        assertEquals(LiveUpdatePhase.Finished(FinishReason.DepartedBoarding), state.phase)
    }

    @Test
    fun `vehicle on another trip after this trip ends the tracking`() {
        val otherTrip = f.snapshot(f.vehicle(InTransitTo, 20, tripId = "trip2"))

        assertEquals(
            LiveUpdatePhase.Finished(FinishReason.TripEnded),
            compute(otherTrip).phase,
        )
        assertEquals(
            LiveUpdatePhase.Finished(FinishReason.ArrivedDestination),
            compute(otherTrip, f.requestWithDestination).phase,
        )
    }

    @Test
    fun `vehicle still finishing its previous trip keeps waiting`() {
        val state =
            compute(
                f.snapshot(f.vehicle(InTransitTo, 20, tripId = "trip0"), mapOf(30 to 12.minutes)),
                wasOnTrip = false,
            )

        assertEquals(LiveUpdatePhase.Waiting, state.phase)
        assertEquals(LiveUpdateLabel.Minutes(12), state.label)
        assertNull(state.stopsAway)
        assertEquals(0, state.progress)
    }

    @Test
    fun `cancelled boarding stop ends the tracking`() {
        val state =
            compute(
                f.snapshot(
                    f.vehicle(InTransitTo, 20),
                    mapOf(30 to 4.minutes),
                    Prediction.ScheduleRelationship.Cancelled,
                ),
            )

        assertEquals(LiveUpdatePhase.Finished(FinishReason.Cancelled), state.phase)
    }

    @Test
    fun `labels follow the time thresholds`() {
        val target = f.now
        fun label(seconds: Long) = LiveUpdateStateCalculator.labelFor(seconds.seconds, target)

        assertEquals(LiveUpdateLabel.Arriving, label(-10))
        assertEquals(LiveUpdateLabel.Arriving, label(30))
        assertEquals(LiveUpdateLabel.Approaching, label(75))
        assertEquals(LiveUpdateLabel.Minutes(2), label(100))
        assertEquals(LiveUpdateLabel.Minutes(59), label(59 * 60))
        assertEquals(LiveUpdateLabel.ClockTime(target), label(2.hours.inWholeSeconds))
    }

    @Test
    fun `progress moves forward as the vehicle moves`() {
        val positions =
            listOf(
                    f.vehicle(InTransitTo, 20),
                    f.vehicle(StoppedAt, 20),
                    f.vehicle(InTransitTo, 30),
                    f.vehicle(StoppedAt, 30),
                    f.vehicle(InTransitTo, 40),
                    f.vehicle(StoppedAt, 40),
                    f.vehicle(InTransitTo, 50),
                )
                .map { compute(f.snapshot(it), f.requestWithDestination).progress }

        assertEquals(listOf(0, 0, 1, 2, 3, 4, 5), positions)
        assertNotNull(anchor)
    }
}
