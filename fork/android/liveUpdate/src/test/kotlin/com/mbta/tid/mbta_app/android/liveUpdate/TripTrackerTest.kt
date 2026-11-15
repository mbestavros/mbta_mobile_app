package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import com.mbta.tid.mbta_app.model.response.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
