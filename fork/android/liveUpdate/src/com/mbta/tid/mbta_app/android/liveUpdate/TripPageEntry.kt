package com.mbta.tid.mbta_app.android.liveUpdate

import android.os.SystemClock
import android.util.Log
import com.mbta.tid.mbta_app.analytics.Analytics
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Tells a trip page opened with Follow from one opened with a vehicle tap on the map.
 *
 * Both open the same SheetRoutes.TripDetails, so the view model states are the same. The map tap
 * logs the tapped_vehicle analytics event just before it opens the page. Follow logs no event.
 */
class TripPageEntry(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var vehicleTapAt: Long? = null

    fun vehicleTapped() {
        vehicleTapAt = now()
    }

    /**
     * The trip page closed. A vehicle tap on the trip page closes the page and opens a new one, so
     * keep a tap from just before.
     */
    fun pageClosed() {
        val tapAt = vehicleTapAt ?: return
        if (now() - tapAt > NAVIGATION_GRACE_MS) vehicleTapAt = null
    }

    /** Returns true if the trip page that just loaded was opened from the map. */
    fun consumeOpenedFromMap(): Boolean = (vehicleTapAt != null).also { vehicleTapAt = null }

    companion object {
        const val VEHICLE_TAP_EVENT = "tapped_vehicle"
        private const val NAVIGATION_GRACE_MS = 1_000L
    }
}

/**
 * Wraps the app analytics to see events. The protected methods of the wrapped instance are called
 * by reflection. They are found by signature, because R8 renames them.
 */
internal class ObservingAnalytics
private constructor(
    private val delegate: Analytics,
    private val logEventMethod: Method,
    private val setUserPropertyMethod: Method,
    private val onEvent: (String) -> Unit,
) : Analytics() {
    override fun logEvent(name: String, parameters: Map<String, String>) {
        onEvent(name)
        logEventMethod.invoke(delegate, name, parameters)
    }

    override fun setUserProperty(name: String, value: String) {
        setUserPropertyMethod.invoke(delegate, name, value)
    }

    companion object {
        /** Returns null if the Analytics methods can't be found. */
        fun wrap(delegate: Analytics, onEvent: (String) -> Unit): Analytics? {
            val logEvent = abstractMethod(String::class.java, Map::class.java)
            val setUserProperty = abstractMethod(String::class.java, String::class.java)
            if (logEvent == null || setUserProperty == null) {
                Log.w("LiveUpdate", "Analytics methods not found; map taps start Live Updates")
                return null
            }
            return ObservingAnalytics(delegate, logEvent, setUserProperty, onEvent)
        }

        private fun abstractMethod(vararg parameterTypes: Class<*>): Method? =
            Analytics::class
                .java
                .declaredMethods
                .singleOrNull {
                    Modifier.isAbstract(it.modifiers) &&
                        it.parameterTypes.contentEquals(parameterTypes)
                }
                ?.apply { isAccessible = true }
    }
}
