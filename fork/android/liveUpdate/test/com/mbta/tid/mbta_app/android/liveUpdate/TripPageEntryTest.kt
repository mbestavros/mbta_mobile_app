package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.analytics.Analytics
import com.mbta.tid.mbta_app.model.LineOrRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPageEntryTest {
    private var time = 0L
    private val entry = TripPageEntry { time }

    @Test
    fun `page opened without a vehicle tap is a follow`() {
        assertFalse(entry.consumeOpenedFromMap())
    }

    @Test
    fun `vehicle tap marks the next page only`() {
        entry.vehicleTapped()
        time += 5_000
        assertTrue(entry.consumeOpenedFromMap())
        assertFalse(entry.consumeOpenedFromMap())
    }

    @Test
    fun `closing the page clears an old tap`() {
        entry.vehicleTapped()
        time += 5_000
        entry.pageClosed()
        assertFalse(entry.consumeOpenedFromMap())
    }

    @Test
    fun `vehicle tap on the trip page survives the page change`() {
        entry.vehicleTapped()
        time += 100
        entry.pageClosed()
        assertTrue(entry.consumeOpenedFromMap())
    }

    private class RecordingAnalytics : Analytics() {
        val events = mutableListOf<String>()
        val properties = mutableListOf<Pair<String, String>>()

        override fun logEvent(name: String, parameters: Map<String, String>) {
            events += name
        }

        override fun setUserProperty(name: String, value: String) {
            properties += name to value
        }
    }

    @Test
    fun `observing analytics forwards events and reports them`() {
        val delegate = RecordingAnalytics()
        val seen = mutableListOf<String>()
        val analytics = ObservingAnalytics.wrap(delegate) { seen += it }
        assertNotNull(analytics)

        analytics!!.tappedVehicle(LineOrRoute.Id.fromString("Red"))
        analytics.recordSession(favoritesCount = 3)

        assertEquals(listOf(TripPageEntry.VEHICLE_TAP_EVENT), seen)
        assertEquals(listOf(TripPageEntry.VEHICLE_TAP_EVENT), delegate.events)
        assertEquals(listOf("favorites_count" to "3"), delegate.properties)
    }
}
