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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.formattedTime
import com.mbta.tid.mbta_app.android.util.notificationPermissionState
import com.mbta.tid.mbta_app.model.TripDetailsStopList
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import org.koin.compose.koinInject

/**
 * The Live Update control on the trip page: a pill that shows the destination and opens the
 * destination sheet. It shows only on the trip page of the tracked trip.
 */
@Composable
fun LiveUpdateOverlay(
    controller: LiveUpdateController = koinInject(),
    mapViewModel: IMapViewModel = koinInject(),
    tripDetailsViewModel: ITripDetailsViewModel = koinInject(),
) {
    val session by controller.session.collectAsState()
    val pendingPermission by controller.pendingPermission.collectAsState()
    val mapState by mapViewModel.models.collectAsState()
    val tripState by tripDetailsViewModel.models.collectAsState()

    if (pendingPermission != null) PermissionRequest(controller)

    val current = session ?: return
    val onTrackedTripPage =
        FollowDetector.isFollowingPage(mapState) &&
            tripState.tripData?.tripFilter?.tripId == current.request.tripId
    if (!onTrackedTripPage) return

    var sheetOpen by remember { mutableStateOf(false) }
    LiveUpdatePill(current) { sheetOpen = true }
    if (sheetOpen) {
        DestinationSheet(
            current,
            onDismiss = { sheetOpen = false },
            onChoose = { entry ->
                controller.setDestination(entry?.stop?.id, entry?.stopSequence)
                sheetOpen = false
            },
            onStop = {
                controller.stop()
                sheetOpen = false
            },
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRequest(controller: LiveUpdateController) {
    val permission = notificationPermissionState { granted ->
        controller.onPermissionResult(granted)
    }
    LaunchedEffect(Unit) {
        if (permission.status.isGranted) {
            controller.onPermissionResult(true)
        } else {
            permission.launchPermissionRequest()
        }
    }
}

@Composable
private fun LiveUpdatePill(session: LiveUpdateSession, onClick: () -> Unit) {
    val state = session.state
    val background = state?.routeColor?.let(::composeColor) ?: colorResource(R.color.key)
    val foreground = state?.routeTextColor?.let(::composeColor) ?: colorResource(R.color.fill3)
    val destination = state?.destinationStopName
    Surface(
        modifier =
            Modifier.navigationBarsPadding()
                .padding(bottom = 16.dp)
                .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = foreground,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.live_update_overlay_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (destination != null) {
                    stringResource(R.string.live_update_overlay_destination, destination)
                } else {
                    stringResource(R.string.live_update_overlay_choose_destination)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationSheet(
    session: LiveUpdateSession,
    onDismiss: () -> Unit,
    onChoose: (TripDetailsStopList.Entry?) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val anchor = session.anchor
    val snapshot = session.snapshot
    val choices =
        if (anchor != null && snapshot != null) {
            snapshot.stopList.stops.filter { it.stopSequence > anchor.boardingStopSequence }
        } else {
            null
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.live_update_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!canPostPromoted(context)) {
                SheetRow(
                    stringResource(R.string.live_update_sheet_enable),
                    onClick = { openPromotedSettings(context) },
                )
            }
            SheetRow(
                stringResource(R.string.live_update_sheet_no_destination),
                detail =
                    anchor?.let {
                        stringResource(
                            R.string.live_update_sheet_no_destination_detail,
                            it.boardingStopName,
                        )
                    },
                selected = !session.request.hasDestination,
                onClick = { onChoose(null) },
            )
            HorizontalDivider()
            if (choices == null) {
                SheetRow(stringResource(R.string.live_update_sheet_loading), onClick = null)
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(choices, key = { it.stopSequence }) { entry ->
                        val name = snapshot?.global?.let { entry.stop.resolveParent(it).name }
                        val time =
                            (entry.prediction?.arrivalTime ?: entry.schedule?.arrivalTime)
                                ?.formattedTime()
                        SheetRow(
                            name ?: entry.stop.name,
                            detail = time,
                            selected =
                                session.request.destinationStopSequence == entry.stopSequence,
                            onClick = { onChoose(entry) },
                        )
                    }
                }
            }
            HorizontalDivider()
            SheetRow(stringResource(R.string.live_update_sheet_stop), onClick = onStop)
        }
    }
}

@Composable
private fun SheetRow(
    text: String,
    detail: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorResource(R.color.deemphasized),
                )
            }
        }
        if (selected) Text("✓", fontWeight = FontWeight.Bold)
    }
}

private fun composeColor(hex: String): Color? =
    try {
        Color("#$hex".toColorInt())
    } catch (e: IllegalArgumentException) {
        null
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
        Log.w("LiveUpdate", "No promoted notification settings screen", e)
    }
}
