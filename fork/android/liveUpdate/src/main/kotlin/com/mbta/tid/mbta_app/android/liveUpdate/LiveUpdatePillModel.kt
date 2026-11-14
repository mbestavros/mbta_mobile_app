package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Line
import com.mbta.tid.mbta_app.model.LineOrRoute
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.model.stopDetailsPage.TripData

/** The route the pill shows: its colors and its badge. */
internal data class PillRoute(val route: Route, val line: Line?)

internal enum class PillKind {
    /** "Track this trip" on a trip page that is not tracked. */
    Track,
    /** Opens the destination sheet. */
    Picker,
    /** Opens the tracked trip again. */
    Return,
}

/**
 * Decides when the pill is ready to show and which route it shows. The pill waits for its route, so
 * it never shows the fallback color first and the route color a moment later.
 */
internal object LiveUpdatePillModel {
    /** The route of the open trip page. Null until both the trip and the global data load. */
    fun trackRoute(tripData: TripData?, global: GlobalResponse?): PillRoute? {
        val routeId = tripData?.trip?.routeId ?: return null
        return global?.getRoute(routeId)?.let { of(it, global) }
    }

    /**
     * The route of the tracked trip. The tracker's snapshot has it once the first fetch is done.
     * Before that, the request's route id and the global data resolve it, so the pill shows the
     * route color right after the user taps "Track this trip".
     */
    fun trackedRoute(session: LiveUpdateSession, global: GlobalResponse?): PillRoute? {
        session.snapshot?.let {
            return of(it.route, it.global)
        }
        return resolve(session.request.routeId, global)
    }

    /** True while the tracker has not computed the first state. */
    fun isStarting(session: LiveUpdateSession): Boolean = session.state == null

    /** The request's route id can be a line id, such as the Green Line's, on a trip page. */
    internal fun resolve(routeId: String, global: GlobalResponse?): PillRoute? {
        if (global == null) return null
        global.getRoute(Route.Id(routeId))?.let {
            return of(it, global)
        }
        val line =
            global.getLineOrRoute(LineOrRoute.Id.fromString(routeId)) as? LineOrRoute.Line
                ?: return null
        return PillRoute(line.sortRoute, line.line)
    }

    private fun of(route: Route, global: GlobalResponse): PillRoute =
        PillRoute(route, global.getLine(route.lineId))
}
