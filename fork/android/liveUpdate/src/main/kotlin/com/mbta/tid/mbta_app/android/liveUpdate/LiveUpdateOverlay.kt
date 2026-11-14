package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.mbta.tid.mbta_app.android.MainActivity
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.component.RoutePill
import com.mbta.tid.mbta_app.android.component.RoutePillType
import com.mbta.tid.mbta_app.android.util.Typography
import com.mbta.tid.mbta_app.android.util.formattedTime
import com.mbta.tid.mbta_app.android.util.modifiers.haloContainer
import com.mbta.tid.mbta_app.android.util.notificationPermissionState
import com.mbta.tid.mbta_app.map.ColorPalette
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.IStopDetailsViewModel
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
 * the only way to start a Live Update. On the trip page of the tracked trip, and on the stop
 * details page of its boarding or destination station, it opens the destination sheet. On other
 * screens, it opens the tracked trip again.
 */
@Composable
fun LiveUpdateOverlay(
    controller: LiveUpdateController = koinInject(),
    mapViewModel: IMapViewModel = koinInject(),
    tripDetailsViewModel: ITripDetailsViewModel = koinInject(),
    stopDetailsViewModel: IStopDetailsViewModel = koinInject(),
    globalRepository: IGlobalRepository = koinInject(),
) {
    val session by controller.session.collectAsState()
    val repositoryGlobal by globalRepository.state.collectAsState()
    val pendingPermission by controller.pendingPermission.collectAsState()
    val mapState by mapViewModel.models.collectAsState()
    val tripState by tripDetailsViewModel.models.collectAsState()
    val stopDetailsState by stopDetailsViewModel.models.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (pendingPermission != null) PermissionRequest(controller)

    val tripPageOpen = TripPage.isOpen(mapState, tripState)
    val pageFilter = TripPage.trackableFilter(tripPageOpen, tripState)
    // An untracked trip page offers "Track this trip", even while another trip is tracked.
    val trackFilter = pageFilter?.takeIf { it.tripId != session?.request?.tripId }
    val current = session.takeIf { trackFilter == null }
    val onTrackedTripPage =
        current != null &&
            tripPageOpen &&
            tripState.tripData?.tripFilter?.tripId == current.request.tripId
    val global = current?.snapshot?.global
    val onStationPage =
        current != null &&
            StationPage.isOpen(
                StationPage.isStopDetails(mapState, tripState),
                stopDetailsState,
                StationPage.trackedStationIds(current.request, global),
                global,
            )
    // Aboard, the trip page is the only page that matters, so the station page offers the way back.
    val riding = current?.state?.phase is LiveUpdatePhase.Riding
    val showsPicker = onTrackedTripPage || (onStationPage && !riding)
    val onEntryScreen = mapState.layerState is MapViewModel.LayerState.Overview

    // Upstream pins the trip page's target stop. A trip page opened from the notification targets
    // the destination already, so only pin it when the target is another stop.
    val destinationStationId =
        current?.request?.destinationStopId?.let { LiveUpdateDeepLink.stationId(it, global) }
    val pageTargetStationId =
        tripState.tripData?.tripFilter?.stopId?.let { LiveUpdateDeepLink.stationId(it, global) }
    DestinationMapPin(
        destinationStationId?.takeIf { onTrackedTripPage && it != pageTargetStationId },
    )

    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showsPicker) { if (!showsPicker) sheetOpen = false }

    // One pill for every state, so it animates from one state to the next instead of popping.
    val pillContent =
        when {
            trackFilter != null ->
                LiveUpdatePillModel.trackRoute(tripState.tripData, repositoryGlobal)?.let {
                    PillContent(
                        PillKind.Track,
                        it,
                        starting = false,
                        title = stringResource(R.string.live_update_overlay_track),
                        subtitle = null,
                    )
                }
            current != null ->
                LiveUpdatePillModel.trackedRoute(current, repositoryGlobal)?.let {
                    trackedPillContent(current, it, showsPicker, context)
                }
            else -> null
        }
    AnimatedLiveUpdatePill(
        pillContent,
        bottomPadding = if (onEntryScreen) TAB_BAR_HEIGHT + 12.dp else 16.dp,
    ) {
        when {
            trackFilter != null -> controller.track(trackFilter)
            current == null -> Unit
            showsPicker -> {
                sheetOpen = true
            }
            else -> {
                val activity = context.findMainActivity() ?: return@AnimatedLiveUpdatePill
                returnToTrip(activity, current, scope, mayBeOnMorePage = onEntryScreen)
            }
        }
    }
    if (sheetOpen && current != null) {
        DestinationSheet(
            current,
            onDismiss = { sheetOpen = false },
            onChoose = { choice ->
                controller.setDestination(choice?.stopId, choice?.stopSequence)
                sheetOpen = false
            },
            onStop = {
                controller.stop()
                sheetOpen = false
            },
        )
    }
}

/** How long the map and sheet page may take to handle a deep link before we assume it can't. */
private const val DEEP_LINK_HANDLED_TIMEOUT_MS = 1_500L

/**
 * Opens the tracked trip with the stop deep link. The map and sheet page handles the link and
 * clears it on its next frame.
 *
 * The More page does not handle deep links. The overlay can't see upstream's navigation, so it
 * can't tell the More page from the map entry screen. If the link is still pending after a timeout,
 * restart MainActivity with the link as a new task. That drops upstream's saved navigation state,
 * so the new activity starts on the map and sheet page and opens the link there. If the guess is
 * wrong, the cost is a UI restart, never closing the app.
 */
private fun returnToTrip(
    activity: MainActivity,
    session: LiveUpdateSession,
    scope: CoroutineScope,
    mayBeOnMorePage: Boolean,
) {
    val global = session.snapshot?.global
    val phase = session.state?.phase
    val vehicleId = LiveUpdateDeepLink.vehicleId(session.request, session.snapshot)
    val target = LiveUpdateDeepLink.state(session.request, phase, vehicleId, global)
    activity.deepLinkStateFlow.value = target
    if (!mayBeOnMorePage) return
    scope.launch {
        delay(DEEP_LINK_HANDLED_TIMEOUT_MS)
        if (activity.deepLinkStateFlow.value != target || activity.isFinishing) return@launch
        activity.clearDeepLink()
        activity.startActivity(
            LiveUpdateDeepLink.intent(
                    activity,
                    LiveUpdateDeepLink.path(session.request, phase, vehicleId, global),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }
}

/**
 * Pins [stationId] on the map while it is not null. See [LiveUpdateMapPin].
 *
 * The composition stays alive when MainActivity is stopped, so a plain LaunchedEffect would keep
 * the pin's one-second check running for the whole ride with the phone locked. Run it only while
 * the activity is started.
 */
@Composable
private fun DestinationMapPin(stationId: String?) {
    val activity = LocalContext.current.findMainActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val colorPalette = if (isSystemInDarkTheme()) ColorPalette.dark else ColorPalette.light
    LaunchedEffect(stationId, colorPalette, lifecycleOwner) {
        if (stationId == null) {
            LiveUpdateMapPin.hide(activity)
        } else {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveUpdateMapPin.keepShown(activity, stationId, colorPalette)
            }
        }
    }
    DisposableEffect(activity) { onDispose { LiveUpdateMapPin.hide(activity) } }
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

/** What the pill shows. A new [title] crossfades; [subtitle] updates in place. */
internal data class PillContent(
    val kind: PillKind,
    val route: PillRoute,
    /** True until the tracker computes its first state. The badge shows a spinner meanwhile. */
    val starting: Boolean,
    val title: String,
    val subtitle: String?,
)

@Composable
private fun trackedPillContent(
    session: LiveUpdateSession,
    route: PillRoute,
    showsPicker: Boolean,
    context: Context,
): PillContent {
    val state = session.state
    val starting = LiveUpdatePillModel.isStarting(session)
    if (showsPicker) {
        val destination = state?.destinationStopName
        return PillContent(
            PillKind.Picker,
            route,
            starting,
            title =
                if (destination != null) {
                    stringResource(R.string.live_update_overlay_destination, destination)
                } else {
                    stringResource(R.string.live_update_overlay_choose_destination)
                },
            subtitle = null,
        )
    }
    return PillContent(
        PillKind.Return,
        route,
        starting,
        title = stringResource(R.string.live_update_overlay_return),
        subtitle =
            state?.let { LiveUpdateTextFormatter.statusText(it, ContextStringLookup(context)) },
    )
}

private const val COLOR_ANIMATION_MS = 300

/**
 * Slides the pill in once [content] is ready, and out when it turns null. The pill keeps its last
 * content while it slides out, and does not take taps then.
 */
@Composable
private fun AnimatedLiveUpdatePill(content: PillContent?, bottomPadding: Dp, onClick: () -> Unit) {
    var lastContent by remember { mutableStateOf(content) }
    if (content != null) SideEffect { lastContent = content }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = content != null
    val padding by animateDpAsState(bottomPadding, label = "pillPadding")
    val currentOnClick by rememberUpdatedState(onClick)
    AnimatedVisibility(
        visibleState = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val shown = content ?: lastContent ?: return@AnimatedVisibility
        LiveUpdatePill(shown, padding, enabled = content != null) { currentOnClick() }
    }
}

/** The pill in the route colors, with the route badge on the left. */
@Composable
internal fun LiveUpdatePill(
    content: PillContent,
    bottomPadding: Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val route = content.route.route
    val background by
        animateColorAsState(
            composeColor(route.color) ?: colorResource(R.color.key),
            tween(COLOR_ANIMATION_MS),
            label = "pillBackground",
        )
    val foreground by
        animateColorAsState(
            composeColor(route.textColor) ?: colorResource(R.color.fill3),
            tween(COLOR_ANIMATION_MS),
            label = "pillForeground",
        )
    var lastSubtitle by remember { mutableStateOf(content.subtitle) }
    content.subtitle?.let { SideEffect { lastSubtitle = it } }

    Pill(
        background,
        foreground,
        bottomPadding,
        onClick,
        enabled = enabled,
        badge = { PillBadge(content, foreground) },
    ) {
        // The pill animates its own size, so the title only fades.
        AnimatedContent(
            content.title,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90))).using(null)
            },
            label = "pillTitle",
        ) { title ->
            Text(
                title,
                style = Typography.calloutSemibold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = content.subtitle != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                content.subtitle ?: lastSubtitle.orEmpty(),
                style = Typography.footnote,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The route badge, or a spinner while tracking starts. The badge has the pill's color, so an
 * outline in the text color sets it apart. Its content description names the route, which the pill
 * text no longer does.
 */
@Composable
private fun PillBadge(content: PillContent, foreground: Color) {
    val startingLabel = stringResource(R.string.live_update_overlay_starting)
    Crossfade(content.starting, label = "pillBadge") { starting ->
        Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
            if (starting) {
                // The indicator is its own semantics node. Clear it so the label merges into the
                // pill and TalkBack reads one button.
                CircularProgressIndicator(
                    Modifier.size(20.dp).clearAndSetSemantics {
                        contentDescription = startingLabel
                    },
                    color = foreground,
                    strokeWidth = 2.dp,
                )
            } else {
                RoutePill(
                    content.route.route,
                    content.route.line,
                    type = RoutePillType.FlexCompact,
                    border = BorderStroke(1.5.dp, foreground),
                )
            }
        }
    }
}

@Composable
internal fun Pill(
    background: Color,
    foreground: Color,
    bottomPadding: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier =
            Modifier.navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = foreground,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.animateContentSize()
                .padding(start = if (badge != null) 12.dp else 20.dp, end = 20.dp)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            badge?.invoke()
            Column(
                horizontalAlignment =
                    if (badge != null) Alignment.Start else Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

/** A stop after the boarding stop that the user can pick as the destination. */
internal data class DestinationChoice(
    val stopId: String,
    val stopSequence: Int,
    val name: String,
    val time: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationSheet(
    session: LiveUpdateSession,
    onDismiss: () -> Unit,
    onChoose: (DestinationChoice?) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val anchor = session.anchor
    val snapshot = session.snapshot
    val choices =
        if (anchor != null && snapshot != null) {
            snapshot.stopList.stops
                .filter { it.stopSequence > anchor.boardingStopSequence }
                .map { entry ->
                    DestinationChoice(
                        stopId = entry.stop.id,
                        stopSequence = entry.stopSequence,
                        name = entry.stop.resolveParent(snapshot.global).name,
                        time =
                            (entry.prediction?.arrivalTime ?: entry.schedule?.arrivalTime)
                                ?.formattedTime(),
                    )
                }
        } else {
            null
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorResource(R.color.fill2),
        contentColor = colorResource(R.color.text),
    ) {
        DestinationSheetContent(
            state = session.state,
            canPostPromoted = canPostPromoted(context),
            boardingStopName = anchor?.boardingStopName,
            choices = choices,
            selectedStopSequence = session.request.destinationStopSequence,
            onChoose = onChoose,
            onStop = onStop,
            onOpenSettings = { openPromotedSettings(context) },
        )
    }
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
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetHeader(state, context)

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
