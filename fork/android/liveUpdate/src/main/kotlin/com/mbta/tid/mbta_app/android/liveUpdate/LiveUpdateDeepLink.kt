package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mbta.tid.mbta_app.android.MainActivity
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.routes.DeepLinkState

/**
 * Links back to the tracked trip with the app's stop deep link.
 *
 * While the rider waits at the boarding station, the link opens stop details there with the trip
 * selected. Once the vehicle leaves the boarding station, the rider is on it, so the link also
 * carries `/v/<vehicle>/follow/<destination station>`. The fork's edit to upstream DeepLinkState
 * and MapAndSheetPage then opens the trip page on top of stop details, as a tap on Follow does.
 *
 * The trip page targets the destination station. Upstream then pins it and keeps both the vehicle
 * and the destination in view while it follows the vehicle. Stop details below stays at the
 * boarding station, so Back goes there. A terminal destination has no departures in the arriving
 * direction, and its stop details would never finish loading.
 */
object LiveUpdateDeepLink {
    private const val BASE_URL = "https://go.mbta.com"

    /** Stop details groups some routes (Green Line branches) under their line. */
    fun routeOrLineId(routeId: String, global: GlobalResponse?): String =
        global?.getLineOrRoute(Route.Id(routeId))?.id?.idText ?: routeId

    /** The parent station of [stopId], or [stopId] if it has no parent or isn't known. */
    fun stationId(stopId: String, global: GlobalResponse?): String =
        global?.getStop(stopId)?.resolveParent(global)?.id ?: stopId

    /** True when the link should open the trip page: the rider is on the vehicle. */
    fun opensTripPage(phase: LiveUpdatePhase?): Boolean = phase is LiveUpdatePhase.Riding

    /**
     * The vehicle the trip page follows. The latest vehicle on the trip, so the map still follows
     * the train if its vehicle ID changed after tracking started.
     */
    fun vehicleId(request: LiveUpdateRequest, snapshot: TrackerSnapshot?): String? =
        snapshot?.vehicleOnTrip?.id ?: request.vehicleId

    /** The trip page's target: the destination station, if there is a destination. */
    fun followStopId(request: LiveUpdateRequest, global: GlobalResponse?): String? =
        request.destinationStopId?.let { stationId(it, global) }

    fun state(
        request: LiveUpdateRequest,
        phase: LiveUpdatePhase?,
        vehicleId: String?,
        global: GlobalResponse?,
    ): DeepLinkState.Stop {
        val follow = opensTripPage(phase)
        return DeepLinkState.Stop(
            stopId = request.stopId,
            routeId = routeOrLineId(request.routeId, global),
            directionId = request.directionId,
            tripId = request.tripId,
            vehicleId = vehicleId.takeIf { follow },
            follow = follow,
            followStopId = followStopId(request, global).takeIf { follow },
        )
    }

    /** The same link as [state], as a URL. */
    fun path(
        request: LiveUpdateRequest,
        phase: LiveUpdatePhase?,
        vehicleId: String?,
        global: GlobalResponse?,
    ): String {
        val stop =
            "$BASE_URL/s/${request.stopId}/r/${routeOrLineId(request.routeId, global)}" +
                "/d/${request.directionId}/t/${request.tripId}"
        if (!opensTripPage(phase)) return stop
        return stop +
            (vehicleId?.let { "/v/$it" } ?: "") +
            "/follow" +
            (followStopId(request, global)?.let { "/$it" } ?: "")
    }

    /**
     * Opens [path] in this app's MainActivity. MainActivity.handleIntent reads the data of an
     * ACTION_VIEW intent. The explicit component keeps the official app from claiming the link.
     */
    fun intent(context: Context, path: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(path)).setClass(context, MainActivity::class.java)
}
