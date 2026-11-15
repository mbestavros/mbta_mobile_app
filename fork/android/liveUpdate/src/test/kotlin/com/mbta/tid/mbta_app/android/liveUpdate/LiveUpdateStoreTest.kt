package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import android.content.Context
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Month
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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

    @Test
    fun `drops invalid JSON`() {
        assertDropped("{not json")
    }

    @Test
    fun `drops a non-numeric stop name key`() {
        val json =
            LiveUpdateStore.encode(SavedSession(request, TrackingProgress(anchor = anchor), 1L))
        json.getJSONObject("anchor").getJSONObject("stopNames").put("abc", "Bad")
        assertDropped(json.toString())
    }

    @Test
    fun `drops a session without a request`() {
        assertDropped("""{"startedAtMs": 1}""")
    }

    @Test
    fun `needsSave ignores small boarding time moves`() {
        val time = EasternTimeInstant(2026, Month.NOVEMBER, 12, 8, 5)
        val saved = TrackingProgress(anchor, wasOnTrip = false, boardingTime = time)

        assertFalse(saved.needsSave(saved))
        assertFalse(saved.copy(boardingTime = time + 59.seconds).needsSave(saved))
        assertFalse(saved.copy(boardingTime = time - 59.seconds).needsSave(saved))
        assertTrue(saved.copy(boardingTime = time + 1.minutes).needsSave(saved))
        assertTrue(saved.copy(boardingTime = time - 2.minutes).needsSave(saved))
    }

    @Test
    fun `needsSave always saves anchor, trip flag and boarding time presence`() {
        val time = EasternTimeInstant(2026, Month.NOVEMBER, 12, 8, 5)
        val saved = TrackingProgress(anchor = null, wasOnTrip = false, boardingTime = null)

        assertTrue(saved.copy(anchor = anchor).needsSave(saved))
        assertTrue(saved.copy(wasOnTrip = true).needsSave(saved))
        assertTrue(saved.copy(boardingTime = time).needsSave(saved))
        assertTrue(saved.needsSave(saved.copy(boardingTime = time)))
    }

    private fun assertDropped(raw: String) {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("live_update_session", Context.MODE_PRIVATE)
        prefs.edit().putString("session", raw).commit()

        assertNull(LiveUpdateStore(app).load())
        assertFalse(prefs.contains("session"))
    }
}
