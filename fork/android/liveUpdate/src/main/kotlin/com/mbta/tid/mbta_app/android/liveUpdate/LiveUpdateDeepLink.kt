package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.routes.DeepLinkState

/**
 * Links back to the tracked trip with the app's existing deep links. A stop deep link opens stop
 * details with the trip selected. Upstream has no deep link to the trip page itself.
 */
object LiveUpdateDeepLink {
    private const val BASE_URL = "https://go.mbta.com"

    /** Stop details groups some routes (Green Line branches) under their line. */
    fun routeOrLineId(routeId: String, global: GlobalResponse?): String =
        global?.getLineOrRoute(Route.Id(routeId))?.id?.idText ?: routeId

    fun state(request: LiveUpdateRequest, global: GlobalResponse?): DeepLinkState.Stop =
        DeepLinkState.Stop(
            stopId = request.stopId,
            routeId = routeOrLineId(request.routeId, global),
            directionId = request.directionId,
            tripId = request.tripId,
        )

    /** A path for the deep_link_path extra that MainActivity reads. */
    fun path(request: LiveUpdateRequest, global: GlobalResponse?): String =
        "$BASE_URL/s/${request.stopId}/r/${routeOrLineId(request.routeId, global)}" +
            "/d/${request.directionId}/t/${request.tripId}"
}
