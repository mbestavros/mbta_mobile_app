package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlinx.datetime.Month
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// A plain Application: MainApplication needs Firebase, which unit tests don't have.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveUpdateStoreTest {
    private val request =
        LiveUpdateRequest(
            tripId = "trip1",
            vehicleId = null,
            routeId = "Red",
            directionId = 1,
            stopId = "place-pktrm",
            stopSequence = 30,
            destinationStopId = "place-alfcl",
            destinationStopSequence = 50,
        )

    private val anchor =
        TrackingAnchor(
            boardingStopSequence = 30,
            boardingStopName = "Park Street",
            sequences = listOf(20, 30, 40, 50),
            stopNames = mapOf(20 to "A", 30 to "Park Street", 40 to "D", 50 to "Alewife"),
        )

    @Test
    fun `round-trips a session with progress`() {
        val store = LiveUpdateStore(RuntimeEnvironment.getApplication())
        val session =
            SavedSession(
                request,
                TrackingProgress(
                    anchor = anchor,
                    wasOnTrip = true,
                    boardingTime = EasternTimeInstant(2026, Month.NOVEMBER, 12, 8, 5),
                ),
                startedAtMs = 1_234L,
            )

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun `round-trips a session without progress or optional fields`() {
        val store = LiveUpdateStore(RuntimeEnvironment.getApplication())
        val session =
            SavedSession(
                request.copy(stopSequence = null).withDestination(null, null),
                TrackingProgress(),
                startedAtMs = 5L,
            )

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun `clear removes the session`() {
        val store = LiveUpdateStore(RuntimeEnvironment.getApplication())
        store.save(SavedSession(request, TrackingProgress(), 1L))

        store.clear()

        assertNull(store.load())
    }
}
