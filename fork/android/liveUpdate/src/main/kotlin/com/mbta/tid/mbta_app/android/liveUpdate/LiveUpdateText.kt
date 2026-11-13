package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.formattedTime
import com.mbta.tid.mbta_app.utils.EasternTimeInstant

/** Resource lookup, so that the text logic can be tested without Android. */
interface StringLookup {
    fun string(@StringRes id: Int, vararg args: Any): String

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String

    fun clockTime(time: EasternTimeInstant): String
}

class ContextStringLookup(private val context: Context) : StringLookup {
    override fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    override fun clockTime(time: EasternTimeInstant): String = time.formattedTime()
}

data class LiveUpdateText(
    val title: String,
    val text: String,
    val chip: String?,
    /** The ETA and the ride time, for the notification header. */
    val subText: String? = null,
)

object LiveUpdateTextFormatter {
    /** The status chip shows its text in full only up to 7 characters. */
    const val MAX_CHIP_LENGTH = 7

    fun format(state: LiveUpdateState, strings: StringLookup): LiveUpdateText {
        val title = strings.string(R.string.live_update_title, state.routeName, state.headsign)
        val phase = state.phase
        if (phase is LiveUpdatePhase.Finished) {
            return LiveUpdateText(title, finishedText(phase.reason, state, strings), chip = null)
        }

        val main = statusText(state, strings)
        val stopsAway = state.stopsAway
        val text =
            if (stopsAway != null && stopsAway > 0 && state.label != LiveUpdateLabel.Boarding) {
                strings.plural(R.plurals.live_update_stops_away, stopsAway, main, stopsAway)
            } else {
                main
            }
        return LiveUpdateText(title, text, chip(state.label, strings), subText(state, strings))
    }

    /** "4 min to Park Street", or the finished text. */
    fun statusText(state: LiveUpdateState, strings: StringLookup): String {
        val phase = state.phase
        if (phase is LiveUpdatePhase.Finished) return finishedText(phase.reason, state, strings)
        val stop = state.targetStopName
        return when (val label = state.label) {
            LiveUpdateLabel.Boarding -> strings.string(R.string.live_update_text_boarding, stop)
            LiveUpdateLabel.Arriving -> strings.string(R.string.live_update_text_arriving, stop)
            LiveUpdateLabel.Approaching ->
                strings.string(R.string.live_update_text_approaching, stop)
            is LiveUpdateLabel.Minutes ->
                strings.string(R.string.live_update_text_minutes, label.minutes, stop)
            is LiveUpdateLabel.ClockTime ->
                strings.string(
                    R.string.live_update_text_clock_time,
                    strings.clockTime(label.time),
                    stop,
                )
            LiveUpdateLabel.Unknown -> strings.string(R.string.live_update_text_unknown, stop)
        }
    }

    /**
     * "ETA 5:42 PM" without a destination. "Arrive 6:01 PM · 19 min ride" with a destination.
     * Unknown parts are left out.
     */
    fun subText(state: LiveUpdateState, strings: StringLookup): String? {
        if (state.phase is LiveUpdatePhase.Finished) return null
        val eta = state.eta?.let { strings.clockTime(it) }
        if (state.destinationStopName == null) {
            return eta?.let { strings.string(R.string.live_update_sub_eta, it) }
        }
        val parts =
            listOfNotNull(
                eta?.let { strings.string(R.string.live_update_sub_arrive, it) },
                state.rideDuration?.let { duration ->
                    val totalMinutes = duration.inWholeMinutes.toInt().coerceAtLeast(1)
                    if (totalMinutes < 60) {
                        strings.string(R.string.live_update_ride_minutes, totalMinutes)
                    } else {
                        strings.string(
                            R.string.live_update_ride_hours,
                            totalMinutes / 60,
                            totalMinutes % 60,
                        )
                    }
                },
            )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    private const val SEPARATOR = " · "

    fun chip(label: LiveUpdateLabel, strings: StringLookup): String? =
        when (label) {
            LiveUpdateLabel.Boarding -> strings.string(R.string.live_update_chip_boarding)
            LiveUpdateLabel.Arriving -> strings.string(R.string.live_update_chip_arriving)
            LiveUpdateLabel.Approaching -> strings.string(R.string.live_update_chip_minutes, 1)
            is LiveUpdateLabel.Minutes ->
                strings.string(R.string.live_update_chip_minutes, label.minutes)
            // A clock time like "10:45 PM" is too long for the chip. The notification "when" time
            // shows a countdown instead.
            is LiveUpdateLabel.ClockTime -> null
            LiveUpdateLabel.Unknown -> null
        }?.takeIf { it.length <= MAX_CHIP_LENGTH }

    private fun finishedText(
        reason: FinishReason,
        state: LiveUpdateState,
        strings: StringLookup,
    ): String =
        when (reason) {
            FinishReason.ArrivedDestination ->
                strings.string(
                    R.string.live_update_finished_arrived,
                    state.destinationStopName ?: state.targetStopName,
                )
            FinishReason.DepartedBoarding ->
                strings.string(R.string.live_update_finished_departed, state.boardingStopName)
            FinishReason.TripEnded -> strings.string(R.string.live_update_finished_trip_ended)
            FinishReason.Cancelled ->
                strings.string(R.string.live_update_finished_cancelled, state.targetStopName)
            FinishReason.BoardingAlreadyPassed ->
                strings.string(R.string.live_update_finished_already_passed, state.boardingStopName)
        }
}
