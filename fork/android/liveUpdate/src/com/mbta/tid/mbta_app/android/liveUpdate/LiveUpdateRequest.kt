package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Intent
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter

/** What the user asked to track: a trip, the stop they board at, and an optional destination. */
data class LiveUpdateRequest(
    val tripId: String,
    val vehicleId: String?,
    val routeId: String,
    val directionId: Int,
    val stopId: String,
    val stopSequence: Int?,
    val destinationStopId: String? = null,
    val destinationStopSequence: Int? = null,
) {
    val hasDestination: Boolean
        get() = destinationStopId != null && destinationStopSequence != null

    fun withDestination(stopId: String?, stopSequence: Int?): LiveUpdateRequest =
        if (stopId == null || stopSequence == null) {
            copy(destinationStopId = null, destinationStopSequence = null)
        } else {
            copy(destinationStopId = stopId, destinationStopSequence = stopSequence)
        }

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_TRIP_ID, tripId)
        putExtra(EXTRA_VEHICLE_ID, vehicleId)
        putExtra(EXTRA_ROUTE_ID, routeId)
        putExtra(EXTRA_DIRECTION_ID, directionId)
        putExtra(EXTRA_STOP_ID, stopId)
        stopSequence?.let { putExtra(EXTRA_STOP_SEQUENCE, it) }
        putExtra(EXTRA_DESTINATION_STOP_ID, destinationStopId)
        destinationStopSequence?.let { putExtra(EXTRA_DESTINATION_STOP_SEQUENCE, it) }
    }

    companion object {
        private const val EXTRA_TRIP_ID = "liveUpdate.tripId"
        private const val EXTRA_VEHICLE_ID = "liveUpdate.vehicleId"
        private const val EXTRA_ROUTE_ID = "liveUpdate.routeId"
        private const val EXTRA_DIRECTION_ID = "liveUpdate.directionId"
        private const val EXTRA_STOP_ID = "liveUpdate.stopId"
        private const val EXTRA_STOP_SEQUENCE = "liveUpdate.stopSequence"
        private const val EXTRA_DESTINATION_STOP_ID = "liveUpdate.destinationStopId"
        private const val EXTRA_DESTINATION_STOP_SEQUENCE = "liveUpdate.destinationStopSequence"

        fun from(filter: TripDetailsPageFilter): LiveUpdateRequest =
            LiveUpdateRequest(
                tripId = filter.tripId,
                vehicleId = filter.vehicleId,
                routeId = filter.routeId.idText,
                directionId = filter.directionId,
                stopId = filter.stopId,
                stopSequence = filter.stopSequence,
            )

        fun readFrom(intent: Intent): LiveUpdateRequest? {
            val tripId = intent.getStringExtra(EXTRA_TRIP_ID) ?: return null
            val routeId = intent.getStringExtra(EXTRA_ROUTE_ID) ?: return null
            val stopId = intent.getStringExtra(EXTRA_STOP_ID) ?: return null
            return LiveUpdateRequest(
                tripId = tripId,
                vehicleId = intent.getStringExtra(EXTRA_VEHICLE_ID),
                routeId = routeId,
                directionId = intent.getIntExtra(EXTRA_DIRECTION_ID, 0),
                stopId = stopId,
                stopSequence = intent.optionalInt(EXTRA_STOP_SEQUENCE),
                destinationStopId = intent.getStringExtra(EXTRA_DESTINATION_STOP_ID),
                destinationStopSequence = intent.optionalInt(EXTRA_DESTINATION_STOP_SEQUENCE),
            )
        }

        private fun Intent.optionalInt(key: String): Int? =
            if (hasExtra(key)) getIntExtra(key, 0) else null
    }
}
