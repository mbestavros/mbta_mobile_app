package com.mbta.tid.mbta_app.routes

import com.mbta.tid.mbta_app.model.LineOrRoute
import com.mbta.tid.mbta_app.model.StopDetailsFilter
import com.mbta.tid.mbta_app.model.TripDetailsFilter
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import io.ktor.http.Url

public sealed class DeepLinkState {
    public data object None : DeepLinkState()

    public data class Alert(val alertId: String, val routeId: String?, val stopId: String?) :
        DeepLinkState()

    public data class Stop(
        val stopId: String,
        val routeId: String?,
        val directionId: Int?,
        val tripId: String?,
        // fork(liveUpdate): open the trip page on top of stop details, like tapping Follow. The
        // trip page targets followStopId if set, else stopId.
        val vehicleId: String? = null,
        val follow: Boolean = false,
        val followStopId: String? = null,
    ) : DeepLinkState() {
        val sheetRoute: SheetRoutes.StopDetails =
            SheetRoutes.StopDetails(
                stopId,
                directionId?.let { direction ->
                    routeId?.let { route ->
                        StopDetailsFilter(LineOrRoute.Id.fromString(route), direction, false)
                    }
                },
                tripId?.let { TripDetailsFilter(it, null, null, false) },
            )

        // fork(liveUpdate): the trip page to open after sheetRoute, or null.
        val followRoute: SheetRoutes.TripDetails? =
            if (follow && tripId != null && routeId != null && directionId != null) {
                SheetRoutes.TripDetails(
                    TripDetailsPageFilter(
                        tripId,
                        vehicleId,
                        LineOrRoute.Id.fromString(routeId),
                        directionId,
                        followStopId ?: stopId,
                        null,
                    ),
                )
            } else null
    }

    public companion object {
        private val alertKeys = listOf("a", "alert")
        private val directionKeys = listOf("d", "direction")
        private val routeKeys = listOf("r", "route")
        private val stopKeys = listOf("s", "stop")
        private val tripKeys = listOf("t", "trip")

        public fun from(url: String): DeepLinkState? {
            val url = Url(url)
            val firstSegment = url.segments.firstOrNull()
            return when (firstSegment) {
                in alertKeys -> parseAlertSegments(url)
                in stopKeys -> parseStopSegments(url)
                null -> None
                else -> {
                    println("Unhandled deep link URI $url")
                    null
                }
            }
        }

        private fun parseAlertSegments(url: Url): Alert? {
            val alertId = segmentAfter(url, alertKeys)
            val routeId = segmentAfter(url, routeKeys)
            val stopId = segmentAfter(url, stopKeys)
            return alertId?.let { Alert(it, routeId, stopId) }
        }

        private fun parseStopSegments(url: Url): Stop? {
            val stopId = segmentAfter(url, stopKeys)
            val routeId = segmentAfter(url, routeKeys)
            val directionId = segmentAfter(url, directionKeys)?.toIntOrNull()?.coerceIn(0, 1)
            val tripId = segmentAfter(url, tripKeys)
            // fork(liveUpdate): optional vehicle and follow segments, as /follow or /follow/<stop>.
            val vehicleId = segmentAfter(url, listOf("v", "vehicle"))
            val follow = url.segments.any { it.lowercase() == "follow" }
            val followStopId = segmentAfter(url, listOf("follow"))
            return stopId?.let {
                Stop(it, routeId, directionId, tripId, vehicleId, follow, followStopId)
            }
        }

        private fun segmentAfter(url: Url, keys: List<String>): String? {
            for (key in keys) {
                val index = url.segments.indexOfFirst { it.lowercase() == key }
                if (index >= 0) {
                    return url.segments.getOrNull(index + 1)
                }
            }
            return null
        }
    }
}
