package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.Trip
import com.mbta.tid.mbta_app.model.TripDetailsStopList
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration

/** The latest data for a tracked trip, as the trip page would show it. */
data class TrackerSnapshot(
    val trip: Trip,
    val route: Route,
    val stopList: TripDetailsStopList,
    val vehicle: Vehicle?,
    val global: GlobalResponse,
) {
    val vehicleOnTrip: Vehicle?
        get() = vehicle?.takeIf { it.tripId == trip.id }
}

sealed class LiveUpdatePhase {
    /** The vehicle has not left the boarding stop yet. */
    data object Waiting : LiveUpdatePhase()

    /** The vehicle has left the boarding stop and goes to the destination. */
    data object Riding : LiveUpdatePhase()

    data class Finished(val reason: FinishReason) : LiveUpdatePhase()
}

enum class FinishReason {
    /** No destination was set, and the vehicle left the boarding stop. */
    DepartedBoarding,
    /** The vehicle arrived at the destination, or went past it. */
    ArrivedDestination,
    /** The vehicle finished this trip and started another one. */
    TripEnded,
    /** The target stop is cancelled or skipped for this trip. */
    Cancelled,
    /** The vehicle was already past the boarding stop when tracking started. */
    BoardingAlreadyPassed,
}

sealed class LiveUpdateLabel {
    data object Boarding : LiveUpdateLabel()

    data object Arriving : LiveUpdateLabel()

    /** Less than a minute and a half away. Shown as "1 min". */
    data object Approaching : LiveUpdateLabel()

    data class Minutes(val minutes: Int) : LiveUpdateLabel()

    data class ClockTime(val time: EasternTimeInstant) : LiveUpdateLabel()

    /** No prediction or schedule yet. */
    data object Unknown : LiveUpdateLabel()
}

data class LiveUpdateState(
    val phase: LiveUpdatePhase,
    val routeName: String,
    val headsign: String,
    val routeColor: String,
    val routeTextColor: String,
    val boardingStopName: String,
    val destinationStopName: String?,
    /** The boarding stop while waiting, the destination while riding. */
    val targetStopName: String,
    val label: LiveUpdateLabel,
    val targetTime: EasternTimeInstant?,
    /** When the train leaves the boarding stop. */
    val boardingTime: EasternTimeInstant?,
    /** When the train arrives at the destination, or null without a destination. */
    val destinationArrival: EasternTimeInstant?,
    /** The destination arrival, or the arrival at the boarding stop without a destination. */
    val eta: EasternTimeInstant?,
    /** Time on the train from the boarding stop to the destination. */
    val rideDuration: Duration?,
    /** Stops between the vehicle and the target, or null if the vehicle position is unknown. */
    val stopsAway: Int?,
    val progress: Int,
    val progressMax: Int,
    val boardingPosition: Int,
    val destinationPosition: Int?,
)
