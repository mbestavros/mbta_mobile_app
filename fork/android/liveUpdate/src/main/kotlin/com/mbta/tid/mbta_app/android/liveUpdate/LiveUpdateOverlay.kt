package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.mbta.tid.mbta_app.android.MainActivity
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.Typography
import com.mbta.tid.mbta_app.android.util.formattedTime
import com.mbta.tid.mbta_app.android.util.modifiers.haloContainer
import com.mbta.tid.mbta_app.android.util.notificationPermissionState
import com.mbta.tid.mbta_app.model.TripDetailsStopList
import com.mbta.tid.mbta_app.routes.DeepLinkState
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Height of the upstream bottom tab row (a Material 3 tab with an icon and a label). */
private val TAB_BAR_HEIGHT = 72.dp

/**
 * The Live Update pill. On a trip page that is not tracked, it offers to track the trip. This is
 * the only way to start a Live Update. On the trip page of the tracked trip, it opens the
 * destination sheet. On other screens, it opens the tracked trip again.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (pendingPermission != null) PermissionRequest(controller)

    val current = session
    val pageFilter = TripPage.trackableFilter(TripPage.isOpen(mapState), tripState)
    if (pageFilter != null && pageFilter.tripId != current?.request?.tripId) {
        Pill(
            colorResource(R.color.key),
            colorResource(R.color.fill3),
            bottomPadding = 16.dp,
            onClick = { controller.track(pageFilter) },
        ) {
            Text(
                stringResource(R.string.live_update_overlay_track),
                style = Typography.calloutSemibold,
                maxLines = 1,
            )
        }
        return
    }
    if (current == null) return
    val onTrackedTripPage =
        TripPage.isOpen(mapState) &&
            tripState.tripData?.tripFilter?.tripId == current.request.tripId
    val onEntryScreen = mapState.layerState is MapViewModel.LayerState.Overview

    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(onTrackedTripPage) { if (!onTrackedTripPage) sheetOpen = false }

    LiveUpdatePill(
        current,
        onTrackedTripPage = onTrackedTripPage,
        bottomPadding = if (onEntryScreen) TAB_BAR_HEIGHT + 12.dp else 16.dp,
    ) {
        if (onTrackedTripPage) {
            sheetOpen = true
        } else {
            val activity = context.findMainActivity() ?: return@LiveUpdatePill
            val target = LiveUpdateDeepLink.state(current.request, current.snapshot?.global)
            returnToTrip(activity, target, scope, canLeavePage = onEntryScreen)
        }
    }
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

/**
 * Opens the tracked trip with the stop deep link. The map and sheet page handles the link and
 * clears it. The More page does not handle deep links, so if the link is still there after a short
 * time, go back to the map and sheet page, which then handles it.
 */
private fun returnToTrip(
    activity: MainActivity,
    target: DeepLinkState.Stop,
    scope: CoroutineScope,
    canLeavePage: Boolean,
) {
    activity.deepLinkStateFlow.value = target
    if (!canLeavePage) return
    scope.launch {
        repeat(3) {
            delay(750)
            if (activity.deepLinkStateFlow.value != target) return@launch
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
}

private fun Context.findMainActivity(): MainActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is MainActivity) return context
        context = context.baseContext
    }
    return null
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
private fun LiveUpdatePill(
    session: LiveUpdateSession,
    onTrackedTripPage: Boolean,
    bottomPadding: Dp,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val state = session.state
    val background = state?.routeColor?.let(::composeColor) ?: colorResource(R.color.key)
    val foreground = state?.routeTextColor?.let(::composeColor) ?: colorResource(R.color.fill3)
    val destination = state?.destinationStopName
    Pill(background, foreground, bottomPadding, onClick) {
        if (onTrackedTripPage) {
            Text(
                if (destination != null) {
                    stringResource(R.string.live_update_overlay_destination, destination)
                } else {
                    stringResource(R.string.live_update_overlay_choose_destination)
                },
                style = Typography.calloutSemibold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                stringResource(R.string.live_update_overlay_return),
                style = Typography.calloutSemibold,
                maxLines = 1,
            )
            if (state != null) {
                Text(
                    stringResource(
                        R.string.live_update_overlay_status,
                        state.routeName,
                        LiveUpdateTextFormatter.statusText(state, ContextStringLookup(context)),
                    ),
                    style = Typography.footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Pill(
    background: Color,
    foreground: Color,
    bottomPadding: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier =
            Modifier.navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)
                .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = foreground,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
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
    val state = session.state
    val choices =
        if (anchor != null && snapshot != null) {
            snapshot.stopList.stops.filter { it.stopSequence > anchor.boardingStopSequence }
        } else {
            null
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorResource(R.color.fill2),
        contentColor = colorResource(R.color.text),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(state, context)

            if (!canPostPromoted(context)) {
                Row(
                    Modifier.fillMaxWidth()
                        .haloContainer(1.dp, backgroundColor = colorResource(R.color.fill3))
                        .clickable(role = Role.Button) { openPromotedSettings(context) }
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
                    .haloContainer(1.dp, backgroundColor = colorResource(R.color.fill3)),
            ) {
                item(key = "none") {
                    StopRow(
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
                }
                if (choices == null) {
                    item(key = "loading") {
                        HorizontalDivider()
                        StopRow(stringResource(R.string.live_update_sheet_loading), onClick = null)
                    }
                } else {
                    itemsIndexed(choices, key = { _, entry -> entry.stopSequence }) { _, entry ->
                        HorizontalDivider()
                        val name = snapshot?.global?.let { entry.stop.resolveParent(it).name }
                        val time =
                            (entry.prediction?.arrivalTime ?: entry.schedule?.arrivalTime)
                                ?.formattedTime()
                        StopRow(
                            name ?: entry.stop.name,
                            time = time,
                            selected =
                                session.request.destinationStopSequence == entry.stopSequence,
                            onClick = { onChoose(entry) },
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
}

@Composable
private fun SheetHeader(state: LiveUpdateState?, context: Context) {
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
            LiveUpdateTextFormatter.subText(state, ContextStringLookup(context))?.let {
                Text(it, style = Typography.footnoteSemibold)
            }
        }
    }
}

@Composable
private fun StopRow(
    text: String,
    detail: String? = null,
    time: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier,
            )
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
