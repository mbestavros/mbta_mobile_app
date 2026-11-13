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
import com.mbta.tid.mbta_app.repositories.ITripRepository
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
 * Loads a trip the same way as the trip page (see getTripData and getTripDetailsStopList in
 * shared), but without Compose or Molecule, so that it keeps running while the screen is off.
 */
class TripTracker(
    private val connection: LiveUpdateConnection,
    private val tripRepository: ITripRepository,
    private val globalRepository: IGlobalRepository,
) {
    private val errorKey = ErrorKey(emptySet(), "LiveUpdate")

    // Alerts are not shown in the notification yet. fromPieces needs a non-null value.
    private val noAlerts = AlertsStreamDataResponse(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(request: LiveUpdateRequest): Flow<TrackerSnapshot> = flow {
        val global =
            globalRepository.state.value ?: retrying("global") { globalRepository.getGlobalData() }
        val trip = retrying("trip") { tripRepository.getTrip(request.tripId) }.trip
        val route =
            global.getRoute(trip.routeId)
                ?: global.getRoute(Route.Id(request.routeId))
                ?: run {
                    Log.w(TAG, "Trip ${trip.id} on unknown route ${trip.routeId}")
                    return@flow
                }

        val schedules: Flow<TripSchedulesResponse?> = flow {
            emit(null)
            emit(retrying("schedules") { tripRepository.getTripSchedules(request.tripId) })
        }

        // The filter from the trip page can have no vehicle yet. Find it from the predictions.
        val vehicleId = MutableStateFlow(request.vehicleId)
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
                    if (vehicleId.value == null) {
                        vehicleId.value =
                            snapshot.stopList.stops.firstNotNullOfOrNull {
                                it.prediction?.vehicleId
                            }
                    }
                }
        emitAll(snapshots)
    }

    private fun predictionUpdates(tripId: String): Flow<PredictionsStreamDataResponse> =
        callbackFlow {
            lateinit var reconnector: Reconnector
            fun connect() {
                connection.tripPredictions.connect(tripId, errorKey) { result ->
                    when (result) {
                        is ApiResult.Ok -> trySend(result.data)
                        is ApiResult.Error -> reconnector.onError("Trip predictions", result)
                    }
                }
            }
            reconnector = Reconnector(this, ::connect)
            connect()
            awaitClose { connection.tripPredictions.disconnect() }
        }

    private fun vehicleUpdates(vehicleId: String): Flow<Vehicle?> = callbackFlow {
        lateinit var reconnector: Reconnector
        fun connect() {
            connection.vehicle.connect(vehicleId, errorKey) { result ->
                when (result) {
                    is ApiResult.Ok -> trySend(result.data.vehicle)
                    is ApiResult.Error -> reconnector.onError("Vehicle", result)
                }
            }
        }
        reconnector = Reconnector(this, ::connect)
        // Don't block the snapshots while the vehicle channel joins.
        trySend(null)
        connect()
        awaitClose { connection.vehicle.disconnect() }
    }

    /**
     * Reconnects a channel after an error, with at most one reconnect pending. Each upstream
     * connect tears down the channel first, so a burst of errors must not stack up reconnects.
     * Errors come from socket callbacks, so this is synchronized.
     */
    private class Reconnector(private val scope: CoroutineScope, private val connect: () -> Unit) {
        private var pending: Job? = null

        @Synchronized
        fun onError(channel: String, error: ApiResult.Error<*>) {
            Log.w(TAG, "$channel error: $error")
            // One bad message does not break the channel. Rejoining would not fix it.
            if (error.message.startsWith(PARSE_ERROR_PREFIX)) return
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(RECONNECT_DELAY_MS)
                connect()
            }
        }
    }

    private suspend fun <T : Any> retrying(name: String, block: suspend () -> ApiResult<T>): T {
        var delayMs = 2_000L
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
                    Log.w(TAG, "Loading $name failed, retrying in $delayMs ms: $result")
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    private companion object {
        const val TAG = "LiveUpdate"
        const val RECONNECT_DELAY_MS = 10_000L

        /**
         * The start of upstream's parse error message (SocketError.FAILED_TO_PARSE in shared). It
         * is internal to shared, so it is copied here. If it changes, parse errors reconnect too,
         * at most once per [RECONNECT_DELAY_MS].
         */
        const val PARSE_ERROR_PREFIX = "channel failed to parse"
    }
}
