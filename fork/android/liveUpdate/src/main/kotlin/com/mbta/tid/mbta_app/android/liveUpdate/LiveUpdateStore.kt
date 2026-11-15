package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.util.Log
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/** What the service learned while tracking. It survives a reconnect and a process restart. */
data class TrackingProgress(
    val anchor: TrackingAnchor? = null,
    val wasOnTrip: Boolean = false,
    val boardingTime: EasternTimeInstant? = null,
)

/**
 * True if this progress differs enough from [saved] to write it again. The anchor and the trip flag
 * always count. The boarding time moves with nearly every prediction for the boarding stop, so it
 * counts only when it appears, disappears, or moves by [BOARDING_TIME_SAVE_STEP] or more.
 */
internal fun TrackingProgress.needsSave(saved: TrackingProgress): Boolean {
    if (anchor != saved.anchor || wasOnTrip != saved.wasOnTrip) return true
    val time = boardingTime
    val savedTime = saved.boardingTime
    if (time == null || savedTime == null) return (time == null) != (savedTime == null)
    return (time - savedTime).absoluteValue >= BOARDING_TIME_SAVE_STEP
}

private val BOARDING_TIME_SAVE_STEP = 1.minutes

/** A tracking session as saved to disk. */
data class SavedSession(
    val request: LiveUpdateRequest,
    val progress: TrackingProgress,
    /** Wall-clock time when the user started tracking, from System.currentTimeMillis. */
    val startedAtMs: Long,
)

/**
 * Saves the current session, so that the service can resume it after the system kills the process.
 * The service returns START_STICKY and reads the session back on restart.
 */
class LiveUpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(session: SavedSession) {
        prefs.edit().putString(KEY_SESSION, encode(session).toString()).apply()
    }

    fun load(): SavedSession? {
        val json = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            decode(JSONObject(json))
        } catch (e: Exception) {
            // JSONException (a checked exception on Android), or a NumberFormatException from a
            // bad stop-name key. A sticky restart reads this on every start, so a bad file must
            // not crash.
            Log.w(LIVE_UPDATE_TAG, "Dropping an unreadable saved session", e)
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    companion object {
        private const val PREFS_NAME = "live_update_session"
        private const val KEY_SESSION = "session"

        internal fun encode(session: SavedSession): JSONObject {
            val request = session.request
            val progress = session.progress
            return JSONObject().apply {
                put("startedAtMs", session.startedAtMs)
                put(
                    "request",
                    JSONObject().apply {
                        put("tripId", request.tripId)
                        putOpt("vehicleId", request.vehicleId)
                        put("routeId", request.routeId)
                        put("directionId", request.directionId)
                        put("stopId", request.stopId)
                        putOpt("stopSequence", request.stopSequence)
                        putOpt("destinationStopId", request.destinationStopId)
                        putOpt("destinationStopSequence", request.destinationStopSequence)
                    },
                )
                put("wasOnTrip", progress.wasOnTrip)
                putOpt("boardingTime", progress.boardingTime?.local?.toString())
                progress.anchor?.let { anchor ->
                    put(
                        "anchor",
                        JSONObject().apply {
                            put("boardingStopSequence", anchor.boardingStopSequence)
                            put("boardingStopName", anchor.boardingStopName)
                            put("sequences", JSONArray(anchor.sequences))
                            put(
                                "stopNames",
                                JSONObject().apply {
                                    anchor.stopNames.forEach { (sequence, name) ->
                                        put(sequence.toString(), name)
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        internal fun decode(json: JSONObject): SavedSession {
            val r = json.getJSONObject("request")
            val request =
                LiveUpdateRequest(
                    tripId = r.getString("tripId"),
                    vehicleId = r.optStringOrNull("vehicleId"),
                    routeId = r.getString("routeId"),
                    directionId = r.getInt("directionId"),
                    stopId = r.getString("stopId"),
                    stopSequence = r.optIntOrNull("stopSequence"),
                    destinationStopId = r.optStringOrNull("destinationStopId"),
                    destinationStopSequence = r.optIntOrNull("destinationStopSequence"),
                )
            val anchor =
                json.optJSONObject("anchor")?.let { a ->
                    val sequences = a.getJSONArray("sequences")
                    val names = a.getJSONObject("stopNames")
                    TrackingAnchor(
                        boardingStopSequence = a.getInt("boardingStopSequence"),
                        boardingStopName = a.getString("boardingStopName"),
                        sequences = List(sequences.length()) { sequences.getInt(it) },
                        stopNames =
                            names.keys().asSequence().associate {
                                it.toInt() to names.getString(it)
                            },
                    )
                }
            val boardingTime =
                json.optStringOrNull("boardingTime")?.let {
                    try {
                        EasternTimeInstant(LocalDateTime.parse(it))
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
            return SavedSession(
                request = request,
                progress =
                    TrackingProgress(
                        anchor = anchor,
                        wasOnTrip = json.optBoolean("wasOnTrip", false),
                        boardingTime = boardingTime,
                    ),
                startedAtMs = json.getLong("startedAtMs"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) getInt(key) else null
    }
}
