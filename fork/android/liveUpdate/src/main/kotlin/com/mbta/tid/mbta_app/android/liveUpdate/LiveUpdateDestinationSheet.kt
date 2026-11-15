package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.Typography
import com.mbta.tid.mbta_app.android.util.formattedTime
import com.mbta.tid.mbta_app.android.util.modifiers.haloContainer

/** A stop after the boarding stop that the user can pick as the destination. */
internal data class DestinationChoice(
    val stopId: String,
    val stopSequence: Int,
    val name: String,
    val time: String?,
)

/**
 * The sheet that picks the destination of the tracked trip, and stops tracking. The overlay opens
 * it from the pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DestinationSheet(
    session: LiveUpdateSession,
    onDismiss: () -> Unit,
    onChoose: (DestinationChoice?) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val anchor = session.anchor
    val snapshot = session.snapshot
    // The service republishes on every 15-second tick with the same snapshot, so this only
    // recomputes when new data arrives.
    val choices =
        remember(snapshot, anchor) {
            if (anchor != null && snapshot != null) destinationChoices(snapshot, anchor) else null
        }
    // A binder call, so don't repeat it on every recomposition. Check again on resume: the banner
    // links to the settings screen where the user turns promotion on.
    var promoted by remember { mutableStateOf(canPostPromoted(context)) }
    LifecycleResumeEffect(context) {
        promoted = canPostPromoted(context)
        onPauseOrDispose {}
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorResource(R.color.fill2),
        contentColor = colorResource(R.color.text),
    ) {
        DestinationSheetContent(
            state = session.state,
            canPostPromoted = promoted,
            boardingStopName = anchor?.boardingStopName,
            choices = choices,
            selectedStopSequence = session.request.destinationStopSequence,
            onChoose = onChoose,
            onStop = onStop,
            onOpenSettings = { openPromotedSettings(context) },
        )
    }
}

private fun destinationChoices(
    snapshot: TrackerSnapshot,
    anchor: TrackingAnchor,
): List<DestinationChoice> =
    snapshot.stopList.stops
        .filter { it.stopSequence > anchor.boardingStopSequence }
        .map { entry ->
            DestinationChoice(
                stopId = entry.stop.id,
                stopSequence = entry.stopSequence,
                name = entry.stop.resolveParent(snapshot.global).name,
                time =
                    (entry.prediction?.arrivalTime ?: entry.schedule?.arrivalTime)?.formattedTime(),
            )
        }

/**
 * The destination sheet's content. [choices] is null while the stops load. [selectedStopSequence]
 * is null when there is no destination.
 */
@Composable
internal fun DestinationSheetContent(
    state: LiveUpdateState?,
    canPostPromoted: Boolean,
    boardingStopName: String?,
    choices: List<DestinationChoice>?,
    selectedStopSequence: Int?,
    onChoose: (DestinationChoice?) -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetHeader(state)

        if (!canPostPromoted) {
            Row(
                Modifier.fillMaxWidth()
                    .haloContainer(1.dp, backgroundColor = colorResource(R.color.fill3))
                    .clickable(role = Role.Button, onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.live_update_sheet_enable),
                    style = Typography.body,
                    color = colorResource(R.color.error),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.fa_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colorResource(R.color.deemphasized),
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f, fill = false)
                .fillMaxWidth()
                .haloContainer(1.dp, backgroundColor = colorResource(R.color.fill3))
                .selectableGroup(),
        ) {
            item(key = "none") {
                StopRow(
                    stringResource(R.string.live_update_sheet_no_destination),
                    detail =
                        boardingStopName?.let {
                            stringResource(
                                R.string.live_update_sheet_no_destination_detail,
                                it,
                            )
                        },
                    selected = selectedStopSequence == null,
                    onClick = { onChoose(null) },
                )
            }
            if (choices == null) {
                item(key = "loading") {
                    HorizontalDivider()
                    LoadingRow(stringResource(R.string.live_update_sheet_loading))
                }
            } else {
                items(choices, key = { it.stopSequence }) { choice ->
                    HorizontalDivider()
                    StopRow(
                        choice.name,
                        time = choice.time,
                        selected = selectedStopSequence == choice.stopSequence,
                        onClick = { onChoose(choice) },
                    )
                }
            }
        }

        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 45.dp),
            shape = RoundedCornerShape(8.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.delete),
                    contentColor = colorResource(R.color.delete_background),
                ),
        ) {
            Icon(
                painterResource(R.drawable.fa_xmark),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.live_update_sheet_stop),
                style = Typography.bodySemibold,
            )
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun SheetHeader(state: LiveUpdateState?) {
    val context = LocalContext.current
    val strings = remember(context) { ContextStringLookup(context) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.live_update_sheet_title), style = Typography.headlineSemibold)
        if (state != null) {
            Text(
                stringResource(
                    R.string.live_update_sheet_subtitle,
                    state.routeName,
                    state.headsign,
                    state.boardingStopName,
                ),
                style = Typography.footnote,
                color = colorResource(R.color.deemphasized),
            )
            LiveUpdateTextFormatter.subText(state, strings)?.let {
                Text(it, style = Typography.footnoteSemibold)
            }
        }
    }
}

/** A row that is not a choice. It is plain text to a screen reader. */
@Composable
private fun LoadingRow(text: String) {
    Text(
        text,
        style = Typography.body,
        color = colorResource(R.color.deemphasized),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** One choice of the destination list. A screen reader reads it as a radio button. */
@Composable
private fun StopRow(
    text: String,
    detail: String? = null,
    time: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = if (selected) Typography.bodySemibold else Typography.body)
            if (detail != null) {
                Text(
                    detail,
                    style = Typography.footnote,
                    color = colorResource(R.color.deemphasized),
                )
            }
        }
        if (time != null) {
            Text(time, style = Typography.footnote, color = colorResource(R.color.deemphasized))
        }
        if (selected) {
            Icon(
                painterResource(R.drawable.fa_check),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colorResource(R.color.key),
            )
        }
    }
}

private fun canPostPromoted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return true
    return context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
}

private fun openPromotedSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // The settings screen does not exist on every device.
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(LIVE_UPDATE_TAG, "No promoted notification settings screen", e)
    }
}
