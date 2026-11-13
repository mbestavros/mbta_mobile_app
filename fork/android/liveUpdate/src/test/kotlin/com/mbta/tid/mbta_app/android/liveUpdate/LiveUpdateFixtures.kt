package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Prediction
import com.mbta.tid.mbta_app.model.RouteType
import com.mbta.tid.mbta_app.model.Stop
import com.mbta.tid.mbta_app.model.TripDetailsStopList
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration
import kotlinx.datetime.Month

/**
 * A Red Line trip with five stops: A (10), B (20), C (30), D (40), E (50). The user boards at C.
 */
class LiveUpdateFixtures {
    val now = EasternTimeInstant(2026, Month.NOVEMBER, 12, 8, 0)
    val objects = ObjectCollectionBuilder("LiveUpdateFixtures")
    val route = objects.route {
        id = "Red"
        type = RouteType.HEAVY_RAIL
        longName = "Red Line"
        color = "DA291C"
        textColor = "FFFFFF"
    }
    val trip = objects.trip {
        id = "trip1"
        routeId = "Red"
        headsign = "Alewife"
    }
    val stopA = objects.stop { name = "A" }
    val stopB = objects.stop { name = "B" }
    val stopC = objects.stop { name = "C" }
    val stopD = objects.stop { name = "D" }
    val stopE = objects.stop { name = "E" }
    private val stopsBySequence =
        mapOf(10 to stopA, 20 to stopB, 30 to stopC, 40 to stopD, 50 to stopE)

    val global by lazy { GlobalResponse(objects) }

    val request =
        LiveUpdateRequest(
            tripId = trip.id,
            vehicleId = "v1",
            routeId = "Red",
            directionId = 0,
            stopId = stopC.id,
            stopSequence = 30,
        )

    val requestWithDestination = request.withDestination(stopE.id, 50)

    fun vehicle(
        status: Vehicle.CurrentStatus,
        stopSequence: Int,
        tripId: String = trip.id,
    ): Vehicle = objects.vehicle {
        id = "v1"
        currentStatus = status
        currentStopSequence = stopSequence
        stopId = stopsBySequence.getValue(stopSequence).id
        this.tripId = tripId
    }

    /**
     * Builds a snapshot like TripDetailsStopList.fromPieces does: stops that the vehicle passed are
     * dropped.
     *
     * @param arrivals minutes from [now] for each stop sequence that has a prediction.
     */
    fun snapshot(
        vehicle: Vehicle?,
        arrivals: Map<Int, Duration> = emptyMap(),
        relationship: Prediction.ScheduleRelationship = Prediction.ScheduleRelationship.Scheduled,
    ): TrackerSnapshot {
        val onTrip = vehicle?.takeIf { it.tripId == trip.id }
        val entries =
            stopsBySequence
                .filterKeys { sequence ->
                    val current = onTrip?.currentStopSequence ?: return@filterKeys true
                    sequence > current ||
                        (sequence == current &&
                            onTrip.currentStatus != Vehicle.CurrentStatus.StoppedAt)
                }
                .map { (sequence, stop) ->
                    entry(stop, sequence, arrivals[sequence], relationship, vehicle)
                }
        return TrackerSnapshot(trip, route, TripDetailsStopList(trip, entries), vehicle, global)
    }

    private fun entry(
        stop: Stop,
        sequence: Int,
        arrival: Duration?,
        relationship: Prediction.ScheduleRelationship,
        vehicle: Vehicle?,
    ): TripDetailsStopList.Entry {
        val prediction = arrival?.let {
            objects.prediction {
                trip = this@LiveUpdateFixtures.trip
                stopId = stop.id
                stopSequence = sequence
                arrivalTime = now + it
                scheduleRelationship = relationship
                vehicleId = "v1"
            }
        }
        return TripDetailsStopList.Entry(
            stop = stop,
            stopSequence = sequence,
            disruption = null,
            schedule = null,
            prediction = prediction,
            vehicle = vehicle,
            routes = emptyList(),
        )
    }
}
