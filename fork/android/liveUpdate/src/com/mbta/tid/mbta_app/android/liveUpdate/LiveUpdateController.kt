package com.mbta.tid.mbta_app.android.liveUpdate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The current Live Update, as the service last computed it. */
data class LiveUpdateSession(
    val request: LiveUpdateRequest,
    val snapshot: TrackerSnapshot? = null,
    val anchor: TrackingAnchor? = null,
    val state: LiveUpdateState? = null,
)

/** Starts and stops the Live Update service, and shares its state with the overlay. */
class LiveUpdateController(private val context: Context) {
    private val _session = MutableStateFlow<LiveUpdateSession?>(null)
    val session: StateFlow<LiveUpdateSession?> = _session.asStateFlow()

    /** A Follow that waits for the notification permission. */
    private val _pendingPermission = MutableStateFlow<LiveUpdateRequest?>(null)
    val pendingPermission: StateFlow<LiveUpdateRequest?> = _pendingPermission.asStateFlow()

    /**
     * Trips that the user dismissed or that finished in this process. A Live Update must not be
     * posted again after the user dismissed it.
     */
    private val doneTripIds = mutableSetOf<String>()

    fun onFollow(filter: TripDetailsPageFilter) {
        val tripId = filter.tripId
        if (_session.value?.request?.tripId == tripId || tripId in doneTripIds) return
        val request = LiveUpdateRequest.from(filter)
        if (!canPostNotifications()) {
            _pendingPermission.value = request
            return
        }
        start(request)
    }

    fun onPermissionResult(granted: Boolean) {
        val request = _pendingPermission.value ?: return
        _pendingPermission.value = null
        if (granted) start(request)
    }

    fun setDestination(stopId: String?, stopSequence: Int?) {
        val current = _session.value ?: return
        val request = current.request.withDestination(stopId, stopSequence)
        _session.value = current.copy(request = request)
        send(LiveUpdateService.ACTION_UPDATE, request)
    }

    fun stop() {
        _session.value?.request?.tripId?.let { doneTripIds += it }
        send(LiveUpdateService.ACTION_STOP, null)
    }

    internal fun publish(session: LiveUpdateSession) {
        _session.value = session
    }

    internal fun markDone(tripId: String) {
        doneTripIds += tripId
    }

    internal fun clear() {
        _session.value = null
    }

    private fun start(request: LiveUpdateRequest) {
        _session.value = LiveUpdateSession(request)
        val intent =
            request.writeTo(
                Intent(context, LiveUpdateService::class.java)
                    .setAction(LiveUpdateService.ACTION_START),
            )
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Starting a foreground service from the background is not allowed. Follow is a
            // foreground action, so this is not expected.
            Log.w("LiveUpdate", "Could not start the Live Update service", e)
            _session.value = null
        }
    }

    private fun send(action: String, request: LiveUpdateRequest?) {
        val intent = Intent(context, LiveUpdateService::class.java).setAction(action)
        request?.writeTo(intent)
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w("LiveUpdate", "Could not send $action to the Live Update service", e)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
}
