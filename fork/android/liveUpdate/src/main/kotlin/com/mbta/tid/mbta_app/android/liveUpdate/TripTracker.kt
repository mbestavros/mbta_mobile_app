package com.mbta.tid.mbta_app.android.liveUpdate

import android.util.Log
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsStopList
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.AlertsStreamDataResponse
import com.mbta.tid.mbta_app.model.response.ApiResult
import com.mbta.tid.mbta_app.model.response.PredictionsStreamDataResponse
import com.mbta.tid.mbta_app.model.response.TripSchedulesResponse
import com.mbta.tid.mbta_app.repositories.ErrorKey
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.repositories.ITripPredictionsRepository
import com.mbta.tid.mbta_app.repositories.ITripRepository
import com.mbta.tid.mbta_app.repositories.IVehicleRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thrown by [TripTracker.snapshots] when the backend says the trip can't load (a 4xx other than 408
 * or 429), for example because the trip no longer exists. Retrying would not help.
 */
internal class TripUnavailableException(val name: String, val code: Int) :
    RuntimeException("Loading $name failed with $code")

/**
 * Loads a trip the same way as the trip page (see getTripData and getTripDetailsStopList in
 * shared), but without Compose or Molecule, so that it keeps running while the screen is off.
 *
 * The channel repositories come from the service's own socket (see [LiveUpdateConnection]).
 */
class TripTracker(
    private val tripPredictions: ITripPredictionsRepository,
    private val vehicleRepository: IVehicleRepository,
    private val tripRepository: ITripRepository,
    private val globalRepository: IGlobalRepository,
    private val stats: LiveUpdateStats? = null,
) {
    private val errorKey = ErrorKey(emptySet(), LIVE_UPDATE_TAG)

    // Alerts are not shown in the notification yet. fromPieces needs a non-null value.
    private val noAlerts = AlertsStreamDataResponse(emptyMap())

    /** Throws [TripUnavailableException] if the trip can't load. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(request: LiveUpdateRequest): Flow<TrackerSnapshot> = flow {
        val global =
            globalRepository.state.value ?: retrying("global") { globalRepository.getGlobalData() }
        val trip = retrying("trip") { tripRepository.getTrip(request.tripId) }.trip
        val route =
            global.getRoute(trip.routeId)
                ?: global.getRoute(Route.Id(request.routeId))
                ?: run {
                    Log.w(LIVE_UPDATE_TAG, "Trip ${trip.id} on unknown route ${trip.routeId}")
                    return@flow
                }

        // Schedules are optional: predictions alone are enough, for example on an added trip.
        val schedules: Flow<TripSchedulesResponse?> = flow {
            emit(null)
            optional("schedules") { tripRepository.getTripSchedules(request.tripId) }
                ?.let { emit(it) }
        }

        // The filter can have no vehicle yet, or name a bus that is reassigned before the trip
        // starts (common at a bus origin). Follow the predicted vehicle until one is on the trip,
        // but keep the vehicle the user focused on unless it reports that it is elsewhere.
        val vehicleId = MutableStateFlow(request.vehicleId)
        var seenOnTrip = false
        val vehicle: Flow<Vehicle?> = vehicleId.flatMapLatest { id ->
            if (id == null) flowOf(null) else vehicleUpdates(id)
        }

        val snapshots =
            combine(predictionUpdates(request.tripId), schedules, vehicle) {
                    predictions,
                    tripSchedules,
                    currentVehicle ->
                    // This runs on every prediction and vehicle update. Keep it off the main
                    // thread.
                    withContext(Dispatchers.Default) {
                        TripDetailsStopList.fromPieces(
                                trip,
                                tripSchedules,
                                predictions,
                                currentVehicle,
                                noAlerts,
                                global,
                            )
                            ?.let { TrackerSnapshot(trip, route, it, currentVehicle, global) }
                    }
                }
                .mapNotNull { it }
                .onEach { snapshot ->
                    if (snapshot.vehicleOnTrip != null) seenOnTrip = true
                    if (shouldRepickVehicle(snapshot, vehicleId.value, seenOnTrip)) {
                        val picked = predictedVehicleId(snapshot, request, vehicleId.value)
                        if (picked != vehicleId.value) {
                            Log.i(
                                LIVE_UPDATE_TAG,
                                "Following vehicle $picked, was ${vehicleId.value}",
                            )
                            vehicleId.value = picked
                        }
                    }
                }
        emitAll(snapshots)
    }

    // Both channels disconnect in awaitClose, after LiveUpdateConnection.close() may have done so
    // already. Upstream's disconnect detaches under a lock and nulls the channel, so a second call
    // does nothing.
    private fun predictionUpdates(tripId: String): Flow<PredictionsStreamDataResponse> =
        callbackFlow {
            lateinit var reconnector: Reconnector
            fun connect() {
                tripPredictions.connect(tripId, errorKey) { result ->
                    when (result) {
                        is ApiResult.Ok -> {
                            stats?.predictionMessages?.incrementAndGet()
                            trySend(result.data)
                        }
                        is ApiResult.Error -> reconnector.onError("Trip predictions", result)
                    }
                }
            }
            reconnector = Reconnector(this, ::connect)
            connect()
            awaitClose { tripPredictions.disconnect() }
        }

    private fun vehicleUpdates(vehicleId: String): Flow<Vehicle?> = callbackFlow {
        lateinit var reconnector: Reconnector
        fun connect() {
            vehicleRepository.connect(vehicleId, errorKey) { result ->
                when (result) {
                    is ApiResult.Ok -> {
                        stats?.vehicleMessages?.incrementAndGet()
                        trySend(result.data.vehicle)
                    }
                    is ApiResult.Error -> reconnector.onError("Vehicle", result)
                }
            }
        }
        reconnector = Reconnector(this, ::connect)
        // Don't block the snapshots while the vehicle channel joins.
        trySend(null)
        connect()
        awaitClose { vehicleRepository.disconnect() }
    }

    /**
     * Reconnects a channel after an error, with at most one reconnect pending. Each upstream
     * connect tears down the channel first, so a burst of errors must not stack up reconnects.
     * Errors come from socket callbacks, so this is synchronized.
     */
    internal class Reconnector(private val scope: CoroutineScope, private val connect: () -> Unit) {
        private var pending: Job? = null

        @Synchronized
        fun onError(channel: String, error: ApiResult.Error<*>) {
            Log.w(LIVE_UPDATE_TAG, "$channel error: $error")
            // One bad message does not break the channel. Rejoining would not fix it.
            if (error.message.startsWith(PARSE_ERROR_PREFIX)) return
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(RECONNECT_DELAY_MS)
                connect()
            }
        }
    }

    internal companion object {
        const val RECONNECT_DELAY_MS = 10_000L
        const val FIRST_RETRY_DELAY_MS = 2_000L
        const val MAX_RETRY_DELAY_MS = 60_000L

        /**
         * The start of upstream's parse error message (SocketError.FAILED_TO_PARSE in shared). It
         * is internal to shared, so it is copied here. If it changes, parse errors reconnect too,
         * at most once per [RECONNECT_DELAY_MS].
         */
        const val PARSE_ERROR_PREFIX = "channel failed to parse"

        /**
         * Runs [block] until it succeeds, backing off from [FIRST_RETRY_DELAY_MS] to
         * [MAX_RETRY_DELAY_MS]. A 4xx other than 408 (timeout) or 429 (rate limit) won't succeed
         * later, so it throws [TripUnavailableException] instead.
         */
        suspend fun <T : Any> retrying(name: String, block: suspend () -> ApiResult<T>): T {
            var delayMs = FIRST_RETRY_DELAY_MS
            while (true) {
                val result =
                    try {
                        block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ApiResult.Error(message = e.toString())
                    }
                when (result) {
                    is ApiResult.Ok -> return result.data
                    is ApiResult.Error -> {
                        val code = result.code
                        if (code != null && isPermanent(code)) {
                            Log.w(LIVE_UPDATE_TAG, "Loading $name failed for good: $result")
                            throw TripUnavailableException(name, code)
                        }
                        Log.w(
                            LIVE_UPDATE_TAG,
                            "Loading $name failed, retrying in $delayMs ms: $result",
                        )
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    }
                }
            }
        }

        /**
         * Like [retrying], for data the tracker can do without: a permanent failure returns null
         * instead of ending tracking.
         */
        suspend fun <T : Any> optional(name: String, block: suspend () -> ApiResult<T>): T? =
            try {
                retrying(name, block)
            } catch (e: TripUnavailableException) {
                Log.w(LIVE_UPDATE_TAG, "Going on without $name (${e.code})")
                null
            }

        private fun isPermanent(code: Int): Boolean = code in 400..499 && code != 408 && code != 429

        /**
         * True when the tracker should look for the trip's vehicle in the predictions: no vehicle
         * has been on the trip yet, and either there is no vehicle or [current] has reported and is
         * not on this trip. The vehicle channel sends null before its first message, so a null
         * vehicle alone does not replace the vehicle the user focused on.
         */
        fun shouldRepickVehicle(
            snapshot: TrackerSnapshot,
            current: String?,
            seenOnTrip: Boolean,
        ): Boolean {
            if (seenOnTrip || snapshot.vehicleOnTrip != null) return false
            return current == null || snapshot.vehicle != null
        }

        /**
         * The vehicle to follow while none has been seen on the trip: the one predicted at the
         * boarding stop, else the first predicted one, else [current]. Predictions follow the
         * MBTA's latest vehicle assignment, so a reassigned bus is picked up.
         */
        fun predictedVehicleId(
            snapshot: TrackerSnapshot,
            request: LiveUpdateRequest,
            current: String?,
        ): String? {
            val stops = snapshot.stopList.stops
            val boarding =
                request.stopSequence?.let { sequence ->
                    stops.firstOrNull { it.stopSequence == sequence }
                }
                    ?: stops.firstOrNull {
                        it.stop.id == request.stopId || it.stop.parentStationId == request.stopId
                    }
            return boarding?.prediction?.vehicleId
                ?: stops.firstNotNullOfOrNull { it.prediction?.vehicleId }
                ?: current
        }
    }
}
