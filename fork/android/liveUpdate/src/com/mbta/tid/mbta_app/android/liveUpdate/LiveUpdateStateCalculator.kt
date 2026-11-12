package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Prediction
import com.mbta.tid.mbta_app.model.RouteType
import com.mbta.tid.mbta_app.model.TripDetailsStopList
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Facts that are fixed when tracking starts.
 *
 * [TripDetailsStopList.fromPieces] drops the stops that the vehicle has already passed, so the stop
 * list gets shorter as the vehicle moves. Progress uses the stop sequences that were left when
 * tracking started, so it does not move back.
 */
data class TrackingAnchor(
    val boardingStopSequence: Int,
    val boardingStopName: String,
    /** Stop sequences from the vehicle's start position to the end of the trip, in order. */
    val sequences: List<Int>,
    val stopNames: Map<Int, String>,
) {
    /** Two progress units per stop, so a vehicle between two stops can be shown halfway. */
    fun positionOf(stopSequence: Int): Int {
        val index = sequences.indexOfLast { it <= stopSequence }
        return if (index < 0) 0 else index * UNITS_PER_STOP
    }

    fun vehiclePosition(vehicle: Vehicle?): Int {
        val current = vehicle?.currentStopSequence ?: return 0
        return when (vehicle.currentStatus) {
            Vehicle.CurrentStatus.StoppedAt -> positionOf(current)
            Vehicle.CurrentStatus.InTransitTo,
            Vehicle.CurrentStatus.IncomingAt -> {
                val nextIndex = sequences.indexOfFirst { it >= current }
                if (nextIndex <= 0) 0 else nextIndex * UNITS_PER_STOP - 1
            }
        }
    }

    companion object {
        const val UNITS_PER_STOP = 2

        /** Returns null if the boarding stop is not in the remaining stops. */
        fun from(snapshot: TrackerSnapshot, request: LiveUpdateRequest): TrackingAnchor? {
            val stops = snapshot.stopList.stops
            val boarding =
                request.stopSequence?.let { sequence ->
                    stops.firstOrNull { it.stopSequence == sequence }
                }
                    ?: snapshot.stopList
                        .splitForTarget(request.stopId, request.stopSequence, snapshot.global)
                        .targetStop
                    ?: return null

            val vehicle = snapshot.vehicleOnTrip
            val startSequence =
                vehicle?.currentStopSequence?.takeIf {
                    vehicle.currentStatus == Vehicle.CurrentStatus.StoppedAt
                }
            val sequences =
                (listOfNotNull(startSequence) + stops.map { it.stopSequence }).distinct().sorted()
            val names = stops.associate {
                it.stopSequence to it.stop.resolveParent(snapshot.global).name
            }
            return TrackingAnchor(
                boardingStopSequence = boarding.stopSequence,
                boardingStopName = boarding.stop.resolveParent(snapshot.global).name,
                sequences = sequences,
                stopNames = names,
            )
        }
    }
}

object LiveUpdateStateCalculator {
    private val ARRIVING_CUTOFF = 30.seconds
    private val APPROACHING_CUTOFF = 90.seconds
    private val CLOCK_TIME_CUTOFF = 60.minutes

    /**
     * @param wasOnTrip true if an earlier snapshot showed the vehicle on this trip. Then a vehicle
     *   on another trip means that this trip is complete. Before that, the vehicle can still be
     *   finishing its previous trip.
     */
    fun compute(
        snapshot: TrackerSnapshot,
        request: LiveUpdateRequest,
        anchor: TrackingAnchor,
        wasOnTrip: Boolean,
        now: EasternTimeInstant,
    ): LiveUpdateState {
        val vehicle = snapshot.vehicleOnTrip
        val current = vehicle?.currentStopSequence
        val stoppedAt = vehicle?.currentStatus == Vehicle.CurrentStatus.StoppedAt
        val boardingSequence = anchor.boardingStopSequence
        val destinationSequence =
            request.destinationStopSequence?.takeIf {
                it > boardingSequence && request.hasDestination
            }

        val departedBoarding = current != null && current > boardingSequence
        val arrivedDestination =
            destinationSequence != null &&
                current != null &&
                (current > destinationSequence || (current == destinationSequence && stoppedAt))
        val tripEnded = wasOnTrip && vehicle == null && snapshot.vehicle != null

        val boardingEntry = snapshot.stopList.entryFor(boardingSequence)
        val destinationEntry = destinationSequence?.let { snapshot.stopList.entryFor(it) }

        val phase =
            when {
                tripEnded ->
                    LiveUpdatePhase.Finished(
                        if (destinationSequence != null) FinishReason.ArrivedDestination
                        else FinishReason.TripEnded,
                    )
                !departedBoarding ->
                    if (boardingEntry?.prediction.isCancelledOrSkipped()) {
                        LiveUpdatePhase.Finished(FinishReason.Cancelled)
                    } else {
                        LiveUpdatePhase.Waiting
                    }
                destinationSequence == null ->
                    LiveUpdatePhase.Finished(FinishReason.DepartedBoarding)
                arrivedDestination -> LiveUpdatePhase.Finished(FinishReason.ArrivedDestination)
                destinationEntry?.prediction.isCancelledOrSkipped() ->
                    LiveUpdatePhase.Finished(FinishReason.Cancelled)
                else -> LiveUpdatePhase.Riding
            }

        val riding = phase == LiveUpdatePhase.Riding
        val targetSequence =
            if (riding && destinationSequence != null) destinationSequence else boardingSequence
        val targetEntry = if (riding) destinationEntry else boardingEntry
        val targetTime = targetEntry?.targetTime()

        val label =
            when {
                !riding && stoppedAt && current == boardingSequence -> LiveUpdateLabel.Boarding
                targetTime == null -> LiveUpdateLabel.Unknown
                else -> labelFor(targetTime - now, targetTime)
            }

        val stopsAway =
            if (vehicle == null) null
            else snapshot.stopList.stops.count { it.stopSequence < targetSequence }

        val boardingPosition = anchor.positionOf(boardingSequence)
        val destinationPosition = destinationSequence?.let { anchor.positionOf(it) }
        val progressMax = maxOf(destinationPosition ?: boardingPosition, 1)

        return LiveUpdateState(
            phase = phase,
            routeName = snapshot.route.displayName(),
            headsign = snapshot.trip.headsign,
            routeColor = snapshot.route.color,
            routeTextColor = snapshot.route.textColor,
            boardingStopName = anchor.boardingStopName,
            destinationStopName =
                destinationSequence?.let { anchor.stopNames[it] ?: request.destinationStopId },
            targetStopName =
                if (riding) anchor.stopNames[targetSequence] ?: anchor.boardingStopName
                else anchor.boardingStopName,
            label = label,
            targetTime = targetTime,
            stopsAway = stopsAway,
            progress = anchor.vehiclePosition(vehicle).coerceIn(0, progressMax),
            progressMax = progressMax,
            boardingPosition = boardingPosition,
            destinationPosition = destinationPosition,
        )
    }

    internal fun labelFor(
        remaining: kotlin.time.Duration,
        targetTime: EasternTimeInstant,
    ): LiveUpdateLabel =
        when {
            remaining <= ARRIVING_CUTOFF -> LiveUpdateLabel.Arriving
            remaining <= APPROACHING_CUTOFF -> LiveUpdateLabel.Approaching
            remaining < CLOCK_TIME_CUTOFF ->
                LiveUpdateLabel.Minutes(remaining.inWholeSeconds.div(60.0).roundToInt())
            else -> LiveUpdateLabel.ClockTime(targetTime)
        }

    private fun TripDetailsStopList.entryFor(stopSequence: Int): TripDetailsStopList.Entry? =
        stops.firstOrNull {
            it.stopSequence == stopSequence
        }

    private fun TripDetailsStopList.Entry.targetTime(): EasternTimeInstant? =
        prediction?.arrivalTime
            ?: prediction?.departureTime
            ?: schedule?.arrivalTime
            ?: schedule?.departureTime

    private fun Prediction?.isCancelledOrSkipped(): Boolean =
        this?.scheduleRelationship == Prediction.ScheduleRelationship.Cancelled ||
            this?.scheduleRelationship == Prediction.ScheduleRelationship.Skipped

    private fun com.mbta.tid.mbta_app.model.Route.displayName(): String =
        when (type) {
            RouteType.BUS -> shortName.ifEmpty { longName }
            else -> longName.ifEmpty { shortName }
        }
}
