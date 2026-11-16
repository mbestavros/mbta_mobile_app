package com.mbta.tid.mbta_app.android.liveUpdate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * Foreground service that keeps the Live Update notification current while the app is in the
 * background. It uses its own websocket (see [LiveUpdateConnection]).
 *
 * It is built to survive real use:
 * - A dead socket reconnects instead of ending tracking (see [LiveUpdateWatchdog]). It also checks
 *   when the network comes back and when the screen turns on. A quiet but live socket is left
 *   alone, so the radio can stay idle.
 * - Swiping the notification away doesn't stop tracking. Only the Stop action does.
 * - The session is saved ([LiveUpdateStore]) and the service is sticky, so it resumes after the
 *   system kills the process.
 * - A finished phase must hold across updates before tracking ends ([FinishDebouncer]).
 */
class LiveUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdogJob: Job? = null
    private var dataJob: Job? = null
    private var connection: LiveUpdateConnection? = null
    private val request = MutableStateFlow<LiveUpdateRequest?>(null)
    private val strings by lazy { ContextStringLookup(this) }
    private val store by lazy { LiveUpdateStore(this) }
    private val diagnostics by lazy { LiveUpdateDiagnostics(this) }
    private val finishDebouncer = FinishDebouncer()

    private var progress = TrackingProgress()
    /** The progress last written to [store]. See [TrackingProgress.needsSave]. */
    private var savedProgress = TrackingProgress()
    /** Wall-clock start of the session, kept across restores. */
    private var startedAtMs = 0L
    /**
     * True if this session was restored after the process died. The watchdog reads it only until
     * the first snapshot arrives (see [LiveUpdateWatchdog.decide]), so it is never reset.
     */
    private var restored = false
    // SystemClock.elapsedRealtime values. They keep counting while the phone sleeps.
    private var sessionStartElapsed = 0L
    private var lastDataElapsed: Long? = null
    private var lastConnectElapsed = 0L
    // Written from socket threads.
    @Volatile private var lastFrameElapsed: Long? = null
    @Volatile private var reconnectsSinceFrame = 0
    private var stats = LiveUpdateStats()
    private var lastTargetTime: EasternTimeInstant? = null
    private var lastPosted: LiveUpdateNotification.OngoingContent? = null
    private var lastNotification: Notification? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    private val controller: LiveUpdateController?
        get() = koinOrNull()?.getOrNull()

    /** The app's Koin, or null before MainApplication starts it. */
    private fun koinOrNull() = GlobalContext.getOrNull()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // After a process restart the service can run before the initializer's posted task. The
        // notification channel and the controller must exist before the first post or publish.
        LiveUpdateNotification.ensureChannel(this)
        LiveUpdateBootstrap.start(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // A service started with startForegroundService must call startForeground soon.
                startInForeground(LiveUpdateNotification.placeholder(this))
                val newRequest = LiveUpdateRequest.readFrom(intent)
                if (newRequest == null || koinOrNull() == null) {
                    finish(null, "start_invalid")
                } else {
                    startSession(
                        newRequest,
                        TrackingProgress(),
                        System.currentTimeMillis(),
                        isRestore = false,
                    )
                }
            }
            ACTION_RESUME -> {
                NotificationManagerCompat.from(this).cancel(LiveUpdateNotification.RESUME_ID)
                startInForeground(LiveUpdateNotification.placeholder(this))
                if (!resumeSaved("resume_tap")) finish(null, "resume_nothing_saved")
            }
            ACTION_UPDATE -> {
                val updated = LiveUpdateRequest.readFrom(intent)
                if (updated != null && updated.tripId == request.value?.tripId) {
                    request.value = updated
                    saveSession()
                }
            }
            ACTION_STOP -> finish(null, "user_stop")
            ACTION_DISMISSED -> onDismissed(startId)
            // A null intent is a sticky restart after the system killed the process.
            else -> if (request.value == null) restoreAfterRestart(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopTracking()
        controller?.clear()
        super.onDestroy()
    }

    /**
     * On Android 14 and later, the user can swipe away a foreground service notification. That is
     * easy to do by accident, so it doesn't stop tracking: the notification comes back. The Stop
     * action ends tracking.
     */
    private fun onDismissed(startId: Int) {
        if (request.value == null) {
            stopSelf(startId)
            return
        }
        diagnostics.record("dismissed_reposted trip=${request.value?.tripId}")
        post(
            LiveUpdateNotification.ONGOING_ID,
            lastNotification ?: LiveUpdateNotification.placeholder(this),
        )
    }

    private fun restoreAfterRestart(startId: Int) {
        val saved = loadFreshSession()
        if (saved == null || koinOrNull() == null) {
            stopSelf(startId)
            return
        }
        try {
            startInForeground(LiveUpdateNotification.placeholder(this))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app is in the background. Keep the
            // saved session and let the user resume it with a tap.
            diagnostics.record("restart_fgs_denied ${e.javaClass.simpleName}")
            post(LiveUpdateNotification.RESUME_ID, LiveUpdateNotification.resume(this))
            stopSelf(startId)
            return
        }
        diagnostics.record("restored_after_restart trip=${saved.request.tripId}")
        startSession(saved.request, saved.progress, saved.startedAtMs, isRestore = true)
    }

    private fun resumeSaved(event: String): Boolean {
        val saved = loadFreshSession() ?: return false
        if (koinOrNull() == null) return false
        diagnostics.record("$event trip=${saved.request.tripId}")
        startSession(saved.request, saved.progress, saved.startedAtMs, isRestore = true)
        return true
    }

    private fun loadFreshSession(): SavedSession? {
        val saved = store.load() ?: return null
        val age = (System.currentTimeMillis() - saved.startedAtMs).milliseconds
        if (age > LiveUpdateWatchdog.MAX_DURATION || age.isNegative()) {
            store.clear()
            return null
        }
        return saved
    }

    private fun startSession(
        newRequest: LiveUpdateRequest,
        initialProgress: TrackingProgress,
        sessionStartedAtMs: Long,
        isRestore: Boolean,
    ) {
        stopTracking()
        request.value = newRequest
        progress = initialProgress
        startedAtMs = sessionStartedAtMs
        restored = isRestore
        sessionStartElapsed = SystemClock.elapsedRealtime()
        lastDataElapsed = null
        lastFrameElapsed = null
        reconnectsSinceFrame = 0
        stats = LiveUpdateStats()
        lastTargetTime = null
        lastPosted = null
        lastNotification = null
        finishDebouncer.reset()
        saveSession()
        if (isRestore) {
            controller?.publish(LiveUpdateSession(newRequest, anchor = initialProgress.anchor))
        }
        registerWakeSignals()
        connect(null)
        watchdogJob = scope.launch {
            while (true) {
                delay(LiveUpdateWatchdog.CHECK_INTERVAL)
                checkWatchdog()
            }
        }
    }

    /** Opens a new socket and restarts the snapshot collection. [reason] is null on first open. */
    private fun connect(reason: String?) {
        val currentRequest = request.value ?: return
        val koin = koinOrNull()
        if (koin == null) {
            finish(null, "no_koin")
            return
        }
        // The cancelled flows disconnect their channels in awaitClose, after close() did. That is
        // safe: upstream's disconnect does nothing the second time.
        dataJob?.cancel()
        connection?.close()
        val sessionStats = stats
        val newConnection =
            LiveUpdateConnection.create(this, koin) {
                    lastFrameElapsed = SystemClock.elapsedRealtime()
                    reconnectsSinceFrame = 0
                    sessionStats.frames.incrementAndGet()
                }
                .also { it.open() }
        connection = newConnection
        lastConnectElapsed = SystemClock.elapsedRealtime()
        if (reason != null) {
            reconnectsSinceFrame++
            stats.reconnects.incrementAndGet()
            diagnostics.record(
                "reconnect reason=$reason trip=${currentRequest.tripId} " +
                    "sinceFrame=${sinceLastFrame()?.inWholeSeconds}s " +
                    "sinceData=${sinceLastData()?.inWholeSeconds}s",
            )
        }
        val tracker =
            TripTracker(
                newConnection.tripPredictions,
                newConnection.vehicle,
                koin.get(),
                koin.get(),
                stats,
            )
        dataJob = scope.launch {
            try {
                collectSnapshots(tracker, currentRequest)
            } catch (e: TripUnavailableException) {
                // The backend rejected the trip, for example because it no longer exists.
                finish(
                    plainText(R.string.live_update_finished_no_data),
                    "trip_unavailable:${e.name}:${e.code}",
                )
            }
        }
    }

    private suspend fun collectSnapshots(tracker: TripTracker, initialRequest: LiveUpdateRequest) {
        val snapshots =
            tracker.snapshots(initialRequest).onEach {
                lastDataElapsed = SystemClock.elapsedRealtime()
            }
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
                val before = progress
                val anchor =
                    before.anchor
                        ?: TrackingAnchor.from(snapshot, currentRequest)
                        ?: run {
                            finishBoardingPassed(snapshot, currentRequest)
                            return@collect
                        }
                // Compute off the main thread. Only posting and publishing stay on Main.
                val state =
                    withContext(Dispatchers.Default) {
                        LiveUpdateStateCalculator.compute(
                            snapshot,
                            currentRequest,
                            anchor,
                            before.wasOnTrip,
                            EasternTimeInstant.now(),
                            before.boardingTime,
                        )
                    }
                progress =
                    TrackingProgress(
                        anchor = anchor,
                        wasOnTrip = before.wasOnTrip || snapshot.vehicleOnTrip != null,
                        boardingTime = state.boardingTime,
                    )
                // The boarding time moves with every prediction. A slightly stale one only
                // affects the ride duration after a restore, so don't rewrite the file for it.
                if (progress.needsSave(savedProgress)) saveSession()
                lastTargetTime = state.eta ?: state.targetTime ?: lastTargetTime

                val phase = state.phase
                val finishNow =
                    finishDebouncer.shouldFinish(phase, snapshot, SystemClock.elapsedRealtime())
                if (phase is LiveUpdatePhase.Finished) {
                    // Until it's confirmed, keep showing the last ongoing state.
                    if (finishNow) {
                        finish(
                            LiveUpdateTextFormatter.format(state, strings),
                            "finished:${phase.reason}",
                        )
                    }
                    return@collect
                }

                val text = LiveUpdateTextFormatter.format(state, strings)
                controller?.publish(LiveUpdateSession(currentRequest, snapshot, anchor, state))
                val target =
                    LiveUpdateDeepLink.target(
                        currentRequest,
                        phase,
                        LiveUpdateDeepLink.vehicleId(currentRequest, snapshot),
                        snapshot.global,
                    )
                val content = LiveUpdateNotification.ongoingContent(state, text, target)
                if (content != lastPosted) {
                    lastPosted = content
                    val notification = LiveUpdateNotification.ongoing(this, state, text, target)
                    lastNotification = notification
                    stats.posts.incrementAndGet()
                    post(LiveUpdateNotification.ONGOING_ID, notification)
                }
            }
    }

    private fun checkWatchdog() {
        if (request.value == null) return
        val now = SystemClock.elapsedRealtime()
        val input =
            LiveUpdateWatchdog.Input(
                sinceStart = (now - sessionStartElapsed).milliseconds,
                sinceLastData = sinceLastData(),
                sinceLastFrame = sinceLastFrame(),
                sinceLastConnect = (now - lastConnectElapsed).milliseconds,
                reconnectsSinceFrame = reconnectsSinceFrame,
                totalDuration = (System.currentTimeMillis() - startedAtMs).milliseconds,
                pastTarget = lastTargetTime?.let { EasternTimeInstant.now() - it },
                restored = restored,
            )
        when (LiveUpdateWatchdog.decide(input)) {
            LiveUpdateWatchdog.Action.None -> Unit
            LiveUpdateWatchdog.Action.Reconnect -> connect("stale")
            LiveUpdateWatchdog.Action.FinishNoData ->
                finish(plainText(R.string.live_update_finished_no_data), "watchdog_no_data")
            LiveUpdateWatchdog.Action.FinishLostConnection ->
                finish(
                    plainText(R.string.live_update_finished_lost_connection),
                    "watchdog_lost_connection",
                )
            LiveUpdateWatchdog.Action.FinishMaxDuration ->
                finish(plainText(R.string.live_update_finished_max_duration), "max_duration")
        }
    }

    private fun sinceLastData(): Duration? = lastDataElapsed?.let {
        (SystemClock.elapsedRealtime() - it).milliseconds
    }

    private fun sinceLastFrame(): Duration? = lastFrameElapsed?.let {
        (SystemClock.elapsedRealtime() - it).milliseconds
    }

    private fun plainText(textId: Int) =
        LiveUpdateText(
            strings.string(R.string.live_update_placeholder_title),
            strings.string(textId),
            chip = null,
        )

    /**
     * Runs the watchdog at once when the network comes back or changes, and when the screen turns
     * on, instead of waiting for the next minute check. While the phone sleeps, Phoenix heartbeats
     * stop and the server can close the socket, and the socket's own reconnect timers stop too. The
     * watchdog rebuilds only a socket that has gone silent. A socket that Phoenix already
     * reconnected by itself is left alone.
     */
    private fun registerWakeSignals() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    private var current: Network? = null
                    private var lost = false

                    override fun onAvailable(network: Network) {
                        val changed = lost || (current != null && current != network)
                        current = network
                        lost = false
                        if (changed) scope.launch { checkWatchdog() }
                    }

                    override fun onLost(network: Network) {
                        if (network == current) {
                            current = null
                            lost = true
                        }
                    }
                }
            try {
                connectivity.registerDefaultNetworkCallback(callback)
                networkCallback = callback
            } catch (e: RuntimeException) {
                Log.w(LIVE_UPDATE_TAG, "Could not watch the network", e)
            }
        }

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    checkWatchdog()
                }
            }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOnReceiver = receiver
    }

    private fun unregisterWakeSignals() {
        networkCallback?.let { callback ->
            try {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
        }
        networkCallback = null
        screenOnReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
        }
        screenOnReceiver = null
    }

    private fun stopTracking() {
        watchdogJob?.cancel()
        watchdogJob = null
        dataJob?.cancel()
        dataJob = null
        connection?.close()
        connection = null
        unregisterWakeSignals()
    }

    private fun saveSession() {
        val current = request.value ?: return
        store.save(SavedSession(current, progress, startedAtMs))
        savedProgress = progress
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
        finish(LiveUpdateText(title, text, chip = null), "boarding_passed")
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

    /**
     * Ends tracking. If [finalText] is not null, shows it in a plain notification for a minute.
     * [reason] goes to [LiveUpdateDiagnostics].
     */
    private fun finish(finalText: LiveUpdateText?, reason: String) {
        val minutes = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 60_000
        diagnostics.record(
            "finish reason=$reason trip=${request.value?.tripId} " +
                "sinceData=${sinceLastData()?.inWholeSeconds}s restored=$restored " +
                stats.summary(minutes),
        )
        stopTracking()
        request.value = null
        store.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (finalText != null) {
            post(LiveUpdateNotification.FINAL_ID, LiveUpdateNotification.final(this, finalText))
        }
        controller?.clear()
        stopSelf()
    }

    // canPost checks POST_NOTIFICATIONS, but lint can't see through the helper.
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!LiveUpdateNotification.canPost(this)) return
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    companion object {
        const val ACTION_START = "com.mbta.tid.mbta_app.android.liveUpdate.START"
        const val ACTION_RESUME = "com.mbta.tid.mbta_app.android.liveUpdate.RESUME"
        const val ACTION_UPDATE = "com.mbta.tid.mbta_app.android.liveUpdate.UPDATE"
        const val ACTION_STOP = "com.mbta.tid.mbta_app.android.liveUpdate.STOP"
        const val ACTION_DISMISSED = "com.mbta.tid.mbta_app.android.liveUpdate.DISMISSED"

        private val TICK_INTERVAL = 15.seconds
    }
}
