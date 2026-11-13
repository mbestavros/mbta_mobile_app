package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.routes.DeepLinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateDeepLinkTest {
    private val f = LiveUpdateFixtures()

    @Test
    fun `path opens stop details with the trip selected`() {
        assertEquals(
            "https://go.mbta.com/s/${f.stopC.id}/r/Red/d/0/t/trip1",
            LiveUpdateDeepLink.path(f.request, f.global),
        )
        assertEquals(
            DeepLinkState.Stop(f.stopC.id, "Red", 0, "trip1"),
            LiveUpdateDeepLink.state(f.request, f.global),
        )
    }

    @Test
    fun `parsed path gives the same state`() {
        val request = f.request.copy(stopId = "place-pktrm")

        assertEquals(
            LiveUpdateDeepLink.state(request, f.global),
            DeepLinkState.from(LiveUpdateDeepLink.path(request, f.global)),
        )
    }

    @Test
    fun `grouped line routes use the line ID`() {
        val objects = ObjectCollectionBuilder("LiveUpdateDeepLinkTest")
        objects.line { id = "line-Green" }
        objects.route {
            id = "Green-B"
            lineId = "line-Green"
        }
        objects.line { id = "line-Red" }
        objects.route {
            id = "Red"
            lineId = "line-Red"
        }
        val global = GlobalResponse(objects)

        assertEquals("line-Green", LiveUpdateDeepLink.routeOrLineId("Green-B", global))
        assertEquals("Red", LiveUpdateDeepLink.routeOrLineId("Red", global))
        assertEquals("Unknown", LiveUpdateDeepLink.routeOrLineId("Unknown", global))
        assertEquals("Green-B", LiveUpdateDeepLink.routeOrLineId("Green-B", null))
    }
}
