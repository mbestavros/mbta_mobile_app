package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.formattedTime
import com.mbta.tid.mbta_app.model.Vehicle
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

/**
 * The notification text. On the promoted template the lines show in this order: [title], [subText],
 * [text], then the progress bar. So [title] has the most important fact for the current stage, and
 * [text] has the ETA and the ride time.
 */
data class LiveUpdateText(
    val title: String,
    val text: String,
    val chip: String?,
    /** The secondary line: the route and crowding while waiting, the next stop while riding. */
    val subText: String? = null,
)

object LiveUpdateTextFormatter {
    /** The status chip shows its text in full only up to 7 characters. */
    const val MAX_CHIP_LENGTH = 7

    /** While riding, the chip shows the stops left only below both of these limits. */
    const val CHIP_STOPS_MAX = 9
    const val CHIP_STOPS_MAX_MINUTES = 14

    fun format(state: LiveUpdateState, strings: StringLookup): LiveUpdateText {
        val routeTitle = strings.string(R.string.live_update_title, state.routeName, state.headsign)
        val phase = state.phase
        if (phase is LiveUpdatePhase.Finished) {
            return LiveUpdateText(routeTitle, finishedText(phase.reason, state, strings), null)
        }
        return if (phase == LiveUpdatePhase.Riding) {
            ridingText(state, strings)
        } else {
            waitingText(state, routeTitle, strings)
        }
    }

    /** "Arrives in 4 min · 2 stops away", "Red Line to Ashmont · Not crowded", the ETA. */
    private fun waitingText(
        state: LiveUpdateState,
        routeTitle: String,
        strings: StringLookup,
    ): LiveUpdateText {
        val countdown =
            when (val label = state.label) {
                LiveUpdateLabel.Boarding -> strings.string(R.string.live_update_wait_boarding)
                LiveUpdateLabel.Arriving -> strings.string(R.string.live_update_wait_arriving)
                LiveUpdateLabel.Approaching -> strings.string(R.string.live_update_wait_minutes, 1)
                is LiveUpdateLabel.Minutes ->
                    strings.string(R.string.live_update_wait_minutes, label.minutes)
                is LiveUpdateLabel.ClockTime ->
                    strings.string(
                        R.string.live_update_wait_clock_time,
                        strings.clockTime(label.time),
                    )
                LiveUpdateLabel.Unknown -> strings.string(R.string.live_update_wait_unknown)
            }
        val stopsAway =
            state.stopsAway?.takeIf {
                it > 0 &&
                    state.label != LiveUpdateLabel.Boarding &&
                    state.label != LiveUpdateLabel.Arriving
            }
        val title =
            listOfNotNull(
                    countdown,
                    stopsAway?.let {
                        strings.plural(R.plurals.live_update_stops_away, it, it)
                    },
                )
                .joinToString(SEPARATOR)
        val sub =
            listOfNotNull(routeTitle, state.crowding?.let { crowdingText(it, strings) })
                .joinToString(SEPARATOR)
        val text =
            if (state.destinationStopName != null) {
                subText(state, strings) ?: state.boardingStopName
            } else {
                state.eta?.let {
                    strings.string(
                        R.string.live_update_at_stop_eta,
                        state.boardingStopName,
                        strings.clockTime(it),
                    )
                } ?: state.boardingStopName
            }
        return LiveUpdateText(title, text, chip(state, strings), sub)
    }

    /** "6 min to Park Street · 3 stops", "Next stop: Downtown Crossing", the ETA. */
    private fun ridingText(state: LiveUpdateState, strings: StringLookup): LiveUpdateText {
        val status = statusText(state, strings)
        val stopsLeft = state.stopsLeft?.takeIf { state.label != LiveUpdateLabel.Arriving }
        val title =
            when {
                stopsLeft == null -> status
                stopsLeft == 1 ->
                    status + SEPARATOR + strings.string(R.string.live_update_destination_next)
                else ->
                    status +
                        SEPARATOR +
                        strings.plural(R.plurals.live_update_stops_left, stopsLeft, stopsLeft)
            }
        val sub =
            state.nextStopName
                ?.takeIf { it != state.destinationStopName }
                ?.let { strings.string(R.string.live_update_next_stop, it) }
        val text = subText(state, strings) ?: state.targetStopName
        return LiveUpdateText(title, text, chip(state, strings), sub)
    }

    private fun crowdingText(level: Vehicle.CrowdingLevel, strings: StringLookup): String =
        strings.string(
            when (level) {
                Vehicle.CrowdingLevel.NotCrowded -> R.string.crowding_not_crowded
                Vehicle.CrowdingLevel.SomeCrowding -> R.string.crowding_some_crowding
                Vehicle.CrowdingLevel.Crowded -> R.string.crowding_crowded
            },
        )

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

    /**
     * While waiting, the countdown to the vehicle. While riding, the countdown to the destination,
     * until fewer than 10 stops and less than 15 minutes are left. Then the stops left.
     */
    fun chip(state: LiveUpdateState, strings: StringLookup): String? {
        val stopsLeft = state.stopsLeft
        val label = state.label
        val nearEnd =
            when (label) {
                LiveUpdateLabel.Approaching,
                LiveUpdateLabel.Unknown -> true
                is LiveUpdateLabel.Minutes -> label.minutes <= CHIP_STOPS_MAX_MINUTES
                else -> false
            }
        if (stopsLeft != null && stopsLeft <= CHIP_STOPS_MAX && nearEnd) {
            return strings.plural(R.plurals.live_update_chip_stops, stopsLeft, stopsLeft).takeIf {
                it.length <= MAX_CHIP_LENGTH
            } ?: chip(label, strings)
        }
        return chip(label, strings)
    }

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
