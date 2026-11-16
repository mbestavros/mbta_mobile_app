package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.mbta.tid.mbta_app.android.MainActivity
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.routes.DeepLinkState

/**
 * Opens the tracked trip.
 *
 * While the rider waits at the boarding station, it opens upstream's stop details there with the
 * trip selected, through the app's own stop deep link. Once the vehicle leaves the boarding
 * station, the rider is on it, so it opens [LiveTripActivity], the fork's trip page. Upstream has
 * no deep link to its trip page, and the fork does not edit upstream to add one.
 *
 * The trip page targets the destination station if there is one, else the boarding stop. Back from
 * it opens stop details at the boarding station, with the plain stop link. A terminal destination
 * has no departures in the arriving direction, and its stop details would never finish loading.
 */
object LiveUpdateDeepLink {
    private const val BASE_URL = "https://go.mbta.com"

    /** Stop details groups some routes (Green Line branches) under their line. */
    fun routeOrLineId(routeId: String, global: GlobalResponse?): String =
        global?.getLineOrRoute(Route.Id(routeId))?.id?.idText ?: routeId

    /** The parent station of [stopId], or [stopId] if it has no parent or isn't known. */
    fun stationId(stopId: String, global: GlobalResponse?): String =
        global?.getStop(stopId)?.resolveParent(global)?.id ?: stopId

    /** True when the trip opens in the trip page: the rider is on the vehicle. */
    fun opensTripPage(phase: LiveUpdatePhase?): Boolean = phase is LiveUpdatePhase.Riding

    /**
     * The vehicle the trip page follows. The latest vehicle on the trip, so the page still follows
     * the train if its vehicle ID changed after tracking started.
     */
    fun vehicleId(request: LiveUpdateRequest, snapshot: TrackerSnapshot?): String? =
        snapshot?.vehicleOnTrip?.id ?: request.vehicleId

    /**
     * The trip page of [request]. It targets the destination station if there is one, so upstream's
     * stop list marks it and splits the trip there. Else it targets the boarding stop.
     */
    fun tripPageFilter(
        request: LiveUpdateRequest,
        vehicleId: String?,
        global: GlobalResponse?,
    ): TripDetailsPageFilter {
        val destination = request.destinationStopId?.takeIf { request.hasDestination }
        return TripDetailsPageFilter(
            tripId = request.tripId,
            vehicleId = vehicleId,
            routeId = Route.Id(request.routeId),
            directionId = request.directionId,
            stopId = destination?.let { stationId(it, global) } ?: request.stopId,
            stopSequence =
                if (destination != null) request.destinationStopSequence else request.stopSequence,
        )
    }

    /** Stop details at the boarding stop with the trip selected, as upstream parses the link. */
    fun state(request: LiveUpdateRequest, global: GlobalResponse?): DeepLinkState.Stop =
        DeepLinkState.Stop(
            stopId = request.stopId,
            routeId = routeOrLineId(request.routeId, global),
            directionId = request.directionId,
            tripId = request.tripId,
        )

    /** The same link as [state], as a URL. */
    fun path(request: LiveUpdateRequest, global: GlobalResponse?): String =
        "$BASE_URL/s/${request.stopId}/r/${routeOrLineId(request.routeId, global)}" +
            "/d/${request.directionId}/t/${request.tripId}"

    /** Stop details of [stopId] with nothing selected. */
    fun stopPath(stopId: String): String = "$BASE_URL/s/$stopId"

    /**
     * Opens [path] in this app's MainActivity. MainActivity.handleIntent reads the data of an
     * ACTION_VIEW intent. The explicit component keeps the official app from claiming the link.
     */
    fun intent(context: Context, path: String): Intent =
        Intent(Intent.ACTION_VIEW, path.toUri()).setClass(context, MainActivity::class.java)

    /** Where the tracked trip opens in [phase]. See the class comment. */
    fun target(
        request: LiveUpdateRequest,
        phase: LiveUpdatePhase?,
        vehicleId: String?,
        global: GlobalResponse?,
    ): Target =
        if (opensTripPage(phase)) {
            Target.TripPage(request, vehicleId)
        } else {
            Target.StopDetails(path(request, global))
        }

    /** Where the tracked trip opens. Equal targets open the same screen. */
    sealed interface Target {
        fun intent(context: Context): Intent

        data class StopDetails(val path: String) : Target {
            override fun intent(context: Context): Intent = intent(context, path)
        }

        data class TripPage(val request: LiveUpdateRequest, val vehicleId: String?) : Target {
            override fun intent(context: Context): Intent =
                LiveTripActivity.intent(context, request, vehicleId)
        }
    }
}
