package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mbta.tid.mbta_app.android.MyApplicationTheme
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.GlobalContext

/**
 * The fork's trip page for the tracked trip. The notification and the pill open it once the rider
 * is aboard. Upstream has no deep link to its own trip page, so the fork hosts upstream's trip view
 * here instead of editing upstream (see [LiveTripScreen]).
 *
 * Back, and the header's back and close buttons, open stop details at the boarding station in
 * MainActivity with the plain stop link, as Back from upstream's trip page does. A tap on a stop in
 * the list opens that stop's details the same way. Either way this activity finishes, so the app
 * keeps one MainActivity.
 */
class LiveTripActivity : ComponentActivity() {
    private val opened = MutableStateFlow<Opened?>(null)

    /** The trip this activity was opened for. */
    internal data class Opened(val request: LiveUpdateRequest, val vehicleId: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = read(intent)
        // Koin starts in Application.onCreate, so it is there unless that failed.
        if (first == null || GlobalContext.getOrNull() == null) {
            Log.w(LIVE_UPDATE_TAG, "LiveTripActivity opened without a trip")
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            finish()
            return
        }
        opened.value = first
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { leave() }
        setContent {
            MyApplicationTheme {
                val opened by opened.collectAsState()
                opened?.let {
                    LiveTripRoute(
                        it.request,
                        it.vehicleId,
                        onBack = ::leave,
                        onOpenStop = ::openStop,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)?.let { opened.value = it }
    }

    private fun leave() {
        val request = opened.value?.request
        if (request == null) {
            finish()
            return
        }
        val global = GlobalContext.getOrNull()?.getOrNull<IGlobalRepository>()?.state?.value
        openInMainActivity(LiveUpdateDeepLink.path(request, global))
    }

    private fun openStop(stationId: String) {
        openInMainActivity(LiveUpdateDeepLink.stopPath(stationId))
    }

    /**
     * Opens [path] in the MainActivity below this one, or a new one if there is none. CLEAR_TOP
     * finishes this activity, and SINGLE_TOP hands the link to the existing MainActivity.
     */
    private fun openInMainActivity(path: String) {
        startActivity(
            LiveUpdateDeepLink.intent(this, path)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        private const val EXTRA_VEHICLE_ID = "liveTrip.vehicleId"

        /**
         * Opens the trip page of [request]. Also from a notification, so in a new task if needed.
         */
        fun intent(context: Context, request: LiveUpdateRequest, vehicleId: String?): Intent =
            request
                .writeTo(Intent(context, LiveTripActivity::class.java))
                .putExtra(EXTRA_VEHICLE_ID, vehicleId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        internal fun read(intent: Intent?): Opened? {
            val request = intent?.let { LiveUpdateRequest.readFrom(it) } ?: return null
            return Opened(request, intent.getStringExtra(EXTRA_VEHICLE_ID))
        }
    }
}
