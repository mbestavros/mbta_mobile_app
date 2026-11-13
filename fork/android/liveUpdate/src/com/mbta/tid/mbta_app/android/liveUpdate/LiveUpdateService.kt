package com.mbta.tid.mbta_app.android.liveUpdate

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Foreground service that keeps the Live Update notification current while the app is in the
 * background. It uses its own websocket (see [LiveUpdateConnection]).
 */
class LiveUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null
    private var connection: LiveUpdateConnection? = null
    private val request = MutableStateFlow<LiveUpdateRequest?>(null)
    private val strings by lazy { ContextStringLookup(this) }

    private val controller: LiveUpdateController?
        get() = GlobalContext.getOrNull()?.getOrNull()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // A service started with startForegroundService must call startForeground soon.
                startInForeground(LiveUpdateNotification.placeholder(this))
                val newRequest = LiveUpdateRequest.readFrom(intent)
                if (newRequest == null || GlobalContext.getOrNull() == null) {
                    finish(null)
                } else {
                    startTracking(newRequest)
                }
            }
            ACTION_UPDATE -> {
                val updated = LiveUpdateRequest.readFrom(intent)
                if (updated != null && updated.tripId == request.value?.tripId) {
                    request.value = updated
                }
            }
            ACTION_STOP -> finish(null)
            ACTION_DISMISSED -> {
                request.value?.tripId?.let { controller?.markDone(it) }
                finish(null)
            }
            else -> if (request.value == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        connection?.close()
        connection = null
        controller?.clear()
        super.onDestroy()
    }

    private fun startTracking(newRequest: LiveUpdateRequest) {
        trackingJob?.cancel()
        connection?.close()

        val koin = GlobalContext.get()
        val newConnection = LiveUpdateConnection.create(this, koin).also { it.open() }
        connection = newConnection
        request.value = newRequest
        val tracker = TripTracker(newConnection, koin.get(), koin.get())

        trackingJob = scope.launch {
            val startedAt = EasternTimeInstant.now()
            var lastSnapshotAt: EasternTimeInstant? = null
            var anchor: TrackingAnchor? = null
            var wasOnTrip = false
            var boardingTime: EasternTimeInstant? = null

            launch {
                while (true) {
                    delay(WATCHDOG_INTERVAL)
                    val now = EasternTimeInstant.now()
                    val lastData = lastSnapshotAt
                    if (lastData == null && now - startedAt > FIRST_DATA_TIMEOUT) {
                        Log.i(TAG, "Stopping: no trip data for ${newRequest.tripId}")
                        finish(
                            LiveUpdateText(
                                strings.string(R.string.live_update_placeholder_title),
                                strings.string(R.string.live_update_finished_no_data),
                                chip = null,
                            ),
                        )
                    } else if (
                        (lastData != null && now - lastData > NO_DATA_TIMEOUT) ||
                            now - startedAt > MAX_DURATION
                    ) {
                        Log.i(TAG, "Stopping: no data or too long")
                        finish(null)
                    }
                }
            }

            val snapshots =
                tracker.snapshots(newRequest).onEach { lastSnapshotAt = EasternTimeInstant.now() }
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(TICK_INTERVAL)
                }
            }

            combine(snapshots, request.filterNotNull(), ticker) { snapshot, currentRequest, _ ->
                    snapshot to currentRequest
                }
                .collect { (snapshot, currentRequest) ->
                    val currentAnchor =
                        anchor
                            ?: TrackingAnchor.from(snapshot, currentRequest)
                            ?: run {
                                finishBoardingPassed(snapshot, currentRequest)
                                return@collect
                            }
                    anchor = currentAnchor
                    val state =
                        LiveUpdateStateCalculator.compute(
                            snapshot,
                            currentRequest,
                            currentAnchor,
                            wasOnTrip,
                            EasternTimeInstant.now(),
                            boardingTime,
                        )
                    if (snapshot.vehicleOnTrip != null) wasOnTrip = true
                    boardingTime = state.boardingTime
                    val text = LiveUpdateTextFormatter.format(state, strings)
                    controller?.publish(
                        LiveUpdateSession(currentRequest, snapshot, currentAnchor, state),
                    )
                    if (state.phase is LiveUpdatePhase.Finished) {
                        controller?.markDone(currentRequest.tripId)
                        finish(text)
                    } else {
                        post(
                            LiveUpdateNotification.ONGOING_ID,
                            LiveUpdateNotification.ongoing(
                                this@LiveUpdateService,
                                state,
                                text,
                                LiveUpdateDeepLink.path(currentRequest, snapshot.global),
                            ),
                        )
                    }
                }
        }
    }

    private fun finishBoardingPassed(snapshot: TrackerSnapshot, request: LiveUpdateRequest) {
        val stopName =
            snapshot.global.getStop(request.stopId)?.resolveParent(snapshot.global)?.name
                ?: request.stopId
        val title =
            strings.string(
                R.string.live_update_title,
                snapshot.route.longName.ifEmpty { snapshot.route.shortName },
                snapshot.trip.headsign,
            )
        val text =
            strings.string(
                R.string.live_update_finished_already_passed,
                stopName,
            )
        controller?.markDone(request.tripId)
        finish(LiveUpdateText(title, text, chip = null))
    }

    private fun startInForeground(notification: Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, LiveUpdateNotification.ONGOING_ID, notification, type)
    }

    /** Ends tracking. If [finalText] is not null, shows it in a plain notification for a minute. */
    private fun finish(finalText: LiveUpdateText?) {
        trackingJob?.cancel()
        trackingJob = null
        connection?.close()
        connection = null
        request.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (finalText != null) {
            post(LiveUpdateNotification.FINAL_ID, LiveUpdateNotification.final(this, finalText))
        }
        controller?.clear()
        stopSelf()
    }

    private fun post(id: Int, notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    companion object {
        const val ACTION_START = "com.mbta.tid.mbta_app.android.liveUpdate.START"
        const val ACTION_UPDATE = "com.mbta.tid.mbta_app.android.liveUpdate.UPDATE"
        const val ACTION_STOP = "com.mbta.tid.mbta_app.android.liveUpdate.STOP"
        const val ACTION_DISMISSED = "com.mbta.tid.mbta_app.android.liveUpdate.DISMISSED"

        private const val TAG = "LiveUpdate"
        private val TICK_INTERVAL = 15.seconds
        private val WATCHDOG_INTERVAL = 1.minutes
        private val FIRST_DATA_TIMEOUT = 2.minutes
        private val NO_DATA_TIMEOUT = 10.minutes
        private val MAX_DURATION = 3.hours
    }
}
