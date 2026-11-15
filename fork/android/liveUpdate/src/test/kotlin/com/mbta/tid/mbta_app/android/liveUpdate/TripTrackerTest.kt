package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.ApiResult
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for android.util.Log. SDK 35: CI uses Java 17.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TripTrackerTest {
    private fun error(code: Int? = null, message: String = "boom") =
        ApiResult.Error<String>(code, message)

    @Test
    fun `the focused vehicle is kept until it reports`() {
        val f = LiveUpdateFixtures()
        // The vehicle channel's first null, before the focused vehicle's first message.
        val beforeFirstMessage = f.snapshot(vehicle = null, arrivals = mapOf(30 to 4.minutes))
        assertFalse(TripTracker.shouldRepickVehicle(beforeFirstMessage, "v1", seenOnTrip = false))
        // Nothing to keep: pick from the predictions.
        assertTrue(TripTracker.shouldRepickVehicle(beforeFirstMessage, null, seenOnTrip = false))
    }

    @Test
    fun `a focused vehicle on the trip is never replaced`() {
        val f = LiveUpdateFixtures()
        val onTrip = f.snapshot(f.vehicle(Vehicle.CurrentStatus.InTransitTo, 20))
        assertFalse(TripTracker.shouldRepickVehicle(onTrip, "v1", seenOnTrip = false))
    }

    @Test
    fun `a vehicle elsewhere is replaced only before the trip starts`() {
        val f = LiveUpdateFixtures()
        val elsewhere =
            f.snapshot(f.vehicle(Vehicle.CurrentStatus.InTransitTo, 40, tripId = "previousTrip"))
        assertTrue(TripTracker.shouldRepickVehicle(elsewhere, "v1", seenOnTrip = false))
        assertFalse(TripTracker.shouldRepickVehicle(elsewhere, "v1", seenOnTrip = true))
    }

    @Test
    fun `a reassigned bus is picked up from the boarding stop's prediction`() {
        val f = LiveUpdateFixtures()
        // The request named v1, but the boarding stop (C, 30) now predicts v2.
        val snapshot =
            f.snapshot(
                vehicle = f.vehicle(Vehicle.CurrentStatus.InTransitTo, 20, tripId = "previousTrip"),
                arrivals = mapOf(20 to 2.minutes, 30 to 4.minutes),
                predictedVehicles = mapOf(20 to "v3", 30 to "v2"),
            )
        assertEquals("v2", TripTracker.predictedVehicleId(snapshot, f.request, "v1"))
    }

    @Test
    fun `without a boarding prediction the first predicted vehicle is used`() {
        val f = LiveUpdateFixtures()
        val snapshot =
            f.snapshot(
                vehicle = null,
                arrivals = mapOf(40 to 6.minutes),
                predictedVehicles = mapOf(40 to "v2"),
            )
        assertEquals("v2", TripTracker.predictedVehicleId(snapshot, f.request, "v1"))
    }

    @Test
    fun `without predictions the current vehicle is kept`() {
        val f = LiveUpdateFixtures()
        val snapshot = f.snapshot(vehicle = null)
        assertEquals("v1", TripTracker.predictedVehicleId(snapshot, f.request, "v1"))
        assertNull(TripTracker.predictedVehicleId(snapshot, f.request, null))
    }

    @Test
    fun `a burst of errors makes one reconnect after the delay`() = runTest {
        var connects = 0
        val reconnector = TripTracker.Reconnector(backgroundScope) { connects++ }

        repeat(5) { reconnector.onError("Trip predictions", error()) }
        advanceTimeBy(TripTracker.RECONNECT_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, connects)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, connects)

        // Once that reconnect ran, a new error may schedule the next one.
        reconnector.onError("Trip predictions", error())
        advanceTimeBy(TripTracker.RECONNECT_DELAY_MS)
        runCurrent()
        assertEquals(2, connects)
    }

    @Test
    fun `parse errors don't reconnect`() = runTest {
        var connects = 0
        val reconnector = TripTracker.Reconnector(backgroundScope) { connects++ }

        reconnector.onError(
            "Vehicle",
            error(message = "${TripTracker.PARSE_ERROR_PREFIX} - bad payload"),
        )
        advanceTimeBy(TripTracker.RECONNECT_DELAY_MS * 2)
        runCurrent()

        assertEquals(0, connects)
    }

    @Test
    fun `retrying backs off and caps the delay`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val result = async {
            TripTracker.retrying("trip") {
                attemptTimes += currentTime
                if (attemptTimes.size < 8) error() else ApiResult.Ok("done")
            }
        }

        assertEquals("done", result.await())
        val gaps = attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), gaps)
    }

    @Test
    fun `a 404 is not retried`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            TripTracker.retrying<String>("trip") {
                attempts++
                error(404)
            }
        }
            .exceptionOrNull()

        assertTrue(thrown is TripUnavailableException)
        assertEquals(404, (thrown as TripUnavailableException).code)
        assertEquals(1, attempts)
    }

    @Test
    fun `timeouts, rate limits and server errors are retried`() = runTest {
        for (code in listOf(408, 429, 500, 503)) {
            var attempts = 0
            val result =
                TripTracker.retrying("trip") {
                    attempts++
                    if (attempts == 1) error(code) else ApiResult.Ok("ok")
                }
            assertEquals("ok", result)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun `exceptions are retried`() = runTest {
        var attempts = 0
        val result =
            TripTracker.retrying("trip") {
                attempts++
                if (attempts == 1) throw IllegalStateException("socket") else ApiResult.Ok("ok")
            }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `optional data that fails for good is left out instead of ending tracking`() = runTest {
        // For example, schedules for an added trip.
        var attempts = 0
        val result =
            TripTracker.optional<String>("schedules") {
                attempts++
                error(404)
            }

        assertNull(result)
        assertEquals(1, attempts)
    }

    @Test
    fun `optional data still retries until it loads`() = runTest {
        var attempts = 0
        val result =
            TripTracker.optional("schedules") {
                attempts++
                if (attempts == 1) error(503) else ApiResult.Ok("ok")
            }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }
}
