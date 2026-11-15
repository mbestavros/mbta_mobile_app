package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.mbta.tid.mbta_app.android.util.notificationPermissionState
import com.mbta.tid.mbta_app.map.ColorPalette
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.IStopDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Height of the upstream bottom tab row (a Material 3 tab with an icon and a label). */
private val TAB_BAR_HEIGHT = 72.dp

/**
 * The Live Update pill. On a trip page that is not tracked, it offers to track the trip. So does
 * stop details with a trip selected that is not tracked, whether or not a vehicle is on it yet.
 * These are the only ways to start a Live Update. On the trip page of the tracked trip, and on the
 * stop details page of its boarding or destination station, it opens the destination sheet. On
 * other screens, it opens the tracked trip again.
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
    // The trip and stop details models change on every prediction. Read them only through
    // derivedStateOf, so the overlay recomposes only when what it shows changes.
    val mapState by mapViewModel.models.collectAsState()
    val tripState by tripDetailsViewModel.models.collectAsState()
    val stopDetailsState by stopDetailsViewModel.models.collectAsState()
    val page by remember {
        derivedStateOf(structuralEqualityPolicy()) {
            OverlayPage.from(mapState, tripState, stopDetailsState)
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (pendingPermission != null) PermissionRequest(controller)

    // An untracked trip on the trip page or selected on stop details offers "Track this trip", even
    // while another trip is tracked.
    val trackFilter = page.trackableFilter?.takeIf { it.tripId != session?.request?.tripId }
    val current = session.takeIf { trackFilter == null }
    val onTrackedTripPage =
        current != null && page.tripPageOpen && page.pageTripId == current.request.tripId
    val global = current?.snapshot?.global
    val onStationPage =
        current != null &&
            StationPage.isOpen(
                page.isStopDetails,
                page.stopDetailsStopId,
                StationPage.trackedStationIds(current.request, global),
                global,
            )
    // Aboard, the trip page is the only page that matters, so the station page offers the way back.
    val riding = current?.state?.phase is LiveUpdatePhase.Riding
    val showsPicker = onTrackedTripPage || (onStationPage && !riding)
    val onEntryScreen = page.onEntryScreen

    // Upstream pins the trip page's target stop. A trip page opened from the notification targets
    // the destination already, so only pin it when the target is another stop.
    val destinationStationId =
        current?.request?.destinationStopId?.let { LiveUpdateDeepLink.stationId(it, global) }
    val pageTargetStationId =
        page.pageTargetStopId?.let { LiveUpdateDeepLink.stationId(it, global) }
    DestinationMapPin(
        destinationStationId?.takeIf { onTrackedTripPage && it != pageTargetStationId },
    )

    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showsPicker) { if (!showsPicker) sheetOpen = false }

    // Tracking another trip ends the tracked one, so ask first. Drop the question if the page no
    // longer offers that trip.
    var confirmSwitch by remember { mutableStateOf<TripDetailsPageFilter?>(null) }
    LaunchedEffect(trackFilter) { if (confirmSwitch != trackFilter) confirmSwitch = null }

    // One pill for every state, so it animates from one state to the next instead of popping.
    val pillContent =
        when {
            trackFilter != null ->
                LiveUpdatePillModel.trackRouteById(page.pageRouteId, repositoryGlobal)?.let {
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
                    trackedPillContent(current, it, showsPicker)
                }
            else -> null
        }
    AnimatedLiveUpdatePill(
        pillContent,
        bottomPadding = if (onEntryScreen) TAB_BAR_HEIGHT + 12.dp else 16.dp,
    ) {
        when {
            trackFilter != null ->
                if (LiveUpdateSwitch.needsConfirmation(session, trackFilter)) {
                    confirmSwitch = trackFilter
                } else {
                    controller.track(trackFilter)
                }
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
    confirmSwitch?.let { filter ->
        SwitchTripDialog(
            currentTrip = LiveUpdateSwitch.currentTripName(session),
            onConfirm = {
                confirmSwitch = null
                controller.track(filter)
            },
            onDismiss = { confirmSwitch = null },
        )
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
): PillContent {
    val context = LocalContext.current
    val strings = remember(context) { ContextStringLookup(context) }
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
        subtitle = state?.let { LiveUpdateTextFormatter.statusText(it, strings) },
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

private fun composeColor(hex: String): Color? = parseRouteColor(hex)?.let { Color(it) }
