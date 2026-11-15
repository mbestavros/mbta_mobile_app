package com.mbta.tid.mbta_app.android.liveUpdate

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.Typography
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter

/** Only one trip is tracked at a time, so tracking another trip ends the current one. */
object LiveUpdateSwitch {
    /**
     * True when tracking [filter] would end an ongoing Live Update, so the user should confirm it.
     * The service stops publishing a session once it finishes, so a session here is ongoing.
     *
     * The overlay only offers trips other than the tracked one, so the trip check never fails
     * there. It is a guard: confirming a switch to the tracked trip would be meaningless.
     */
    fun needsConfirmation(session: LiveUpdateSession?, filter: TripDetailsPageFilter): Boolean =
        session != null && session.request.tripId != filter.tripId

    /** "47 to Central Square", or null while the tracked trip is still loading. */
    fun currentTripName(session: LiveUpdateSession?): Pair<String, String>? {
        val state = session?.state ?: return null
        return state.routeName to state.headsign
    }
}

/**
 * Asks before replacing the tracked trip. Styled like upstream's location settings prompt
 * (LocationAuthButton): a fill3 container, a bold headline and text buttons.
 */
@Composable
internal fun SwitchTripDialog(
    currentTrip: Pair<String, String>?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.live_update_switch_confirm), style = Typography.body)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.live_update_switch_keep), style = Typography.body)
            }
        },
        title = {
            Text(
                stringResource(R.string.live_update_switch_title),
                style = Typography.headlineBold,
            )
        },
        text = {
            Text(
                if (currentTrip != null) {
                    stringResource(
                        R.string.live_update_switch_text,
                        stringResource(
                            R.string.live_update_title,
                            currentTrip.first,
                            currentTrip.second,
                        ),
                    )
                } else {
                    stringResource(R.string.live_update_switch_text_unknown)
                },
            )
        },
        containerColor = colorResource(R.color.fill3),
    )
}
