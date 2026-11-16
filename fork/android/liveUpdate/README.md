# Live Update

When you track a trip, the app shows an Android Live Update. On Android 16 and later, it
shows on the lock screen and as a chip in the status bar. On earlier versions, it is an ongoing
notification with a progress bar.

- The Live Update shows the time until the vehicle arrives at your stop. The notification header
  shows the ETA. With a destination, it shows the arrival time and the ride time.
- On a trip page, a "Track this trip" pill shows. Tap it to start the Live Update. This is the
  only way to start one. Follow and a tap on a vehicle on the map only open the trip page. If
  another trip is tracked, the new trip replaces it.
- While a trip is tracked, a pill shows on every screen of the app.
  - On the tracked trip's page, it shows "Choose destination" or "To <stop>". Tap it to open a
    list of the next stops. Select the stop where you get off. After you board, the Live Update
    shows the time until you arrive.
  - While you wait, it shows the same picker on stop details for the boarding or destination
    station.
  - On other screens, and on stop details once you are on the vehicle, it shows "Return to trip
    tracking".
- "Return to trip tracking" and the notification open the page for where you are:
  - While you wait for the vehicle, they open stop details for the boarding station with the trip
    selected, so you can also see other departures.
  - After the vehicle leaves the boarding station, you are on it. They open the fork's trip page
    (`LiveTripActivity`): upstream's map with upstream's trip sheet over it. It targets the
    destination, so upstream's list marks it and the map pins it and fits the vehicle and the
    destination. The map shows only the tracked vehicle, and not your location. With upstream's
    "Hide maps" setting, the list fills the screen. While the trip is tracked, the page follows a new
    destination or vehicle, and the pill on it opens the destination list. Back opens stop details
    at the boarding station. A tap on a stop opens that stop's details.
- If you open the tracked trip's page yourself (it targets the boarding station), the map also
  pins the destination station. The pin stays while you move the map.
- The pill has the route colors and the route badge on its left, like the notification. It waits
  until the route is known, then slides in, so it never flashes the default blue first. Right
  after "Track this trip", a spinner replaces the badge until the first trip data arrives.
  Color, text and size changes animate.
- "Track this trip" shows only after the trip data loads. Some trips show "not available" on
  the trip page because the app can't load them. These trips can't be tracked. If no trip
  data comes in 2 minutes, the Live Update ends with "No data for this trip".
- Without a destination, the Live Update ends when the vehicle leaves your stop. With a destination,
  it ends when the vehicle arrives there.
- Use "Stop" in the notification, or "Stop tracking" in the list, to end it. If you swipe it
  away by accident, it comes back and tracking goes on.
- A finished state (left the stop, trip ended, cancelled) must hold across two updates, and 20
  seconds unless it's an arrival, before the Live Update ends. One glitchy vehicle update doesn't
  end it.
- The service judges the connection by socket frames, not trip data. A healthy socket gets a
  heartbeat reply every 30 seconds, so a quiet channel (a trip that leaves in 20 minutes) is left
  alone. A socket silent for 90 seconds (it died while the phone slept, or in a tunnel) is rebuilt.
  The check runs every minute, and also at once when the network comes back and when the screen
  turns on. While nothing gets through, rebuilds back off from 90 seconds up to 10 minutes. The
  socket's own retries back off from 1 to 30 seconds, where upstream's retry every 2 seconds.
- It ends with "Lost connection to this trip" only after 45 minutes without a frame, or when data
  is stale 30 minutes after the trip's last known time. It ends after 3 hours in any case.
- The destination map pin checks its layer every second only while MainActivity is started.
- The session is saved (`LiveUpdateStore`) and the service is sticky. If Android kills the
  process, the service resumes the trip when it restarts. If Android won't let it start in the
  foreground, a "Tap to resume tracking" notification resumes it.
- `LiveUpdateDiagnostics` logs why each session ended or reconnected, and why the process died
  (`ApplicationExitInfo`). Each `finish` entry also has the session's traffic counts
  (`LiveUpdateStats`: minutes, socket frames, prediction and vehicle messages, reconnects,
  notification posts). Use them to judge the radio cost. Read it with `adb logcat -d -s LiveUpdate:*`, or on a debug build with
  `adb shell run-as com.mbta.tid.mbta_app.staging cat shared_prefs/live_update_diagnostics.xml`.
- The service posts the notification again only when its content changes. Each post also goes to
  connected watches.

## How it connects to the app
The feature does not change upstream. Everything is in this folder:

| Part | How it connects to the app |
|---|---|
| `src/main/AndroidManifest.xml` | The loader adds it to each variant with the variant API |
| `feature.gradle` | Adds `androidx.core` for `ProgressStyle`, and Robolectric and Compose UI test for the unit tests |
| `LiveUpdateInitializer` | A content provider that starts the feature after `Application.onCreate` |
| `TripPage` | Reads the map and trip details view models to find the open trip page. It uses the trip details `context`, not `following`, which turns off when you move the map. |
| `LiveUpdateOverlayInstaller` | Adds the pill above `MainActivity`'s content |
| `LiveUpdateDeepLink` | Opens the trip: stop details with the app's stop deep link, as an `ACTION_VIEW` intent to `MainActivity`, or the trip page in `LiveTripActivity` |
| `LiveTripActivity`, `LiveTripScreen` | The fork's trip page, declared in the feature manifest (`exported=false`). It hosts upstream's `TripDetailsView` with its own `TripDetailsViewModel` and `TripDetailsPageViewModel`, not the Koin singletons, so upstream's trip page keeps its own trip. It opens the app's socket while it is resumed, as `ContentView` does for `MainActivity`. |
| `LiveTripMap` | Upstream's `HomeMapView` with the page's own `MapViewModel`, so MainActivity's map keeps its state. It copies upstream's glue from `ContentView` and `MapAndSheetPage`: the viewport, and the trip details view model's map updates. |
| `LiveUpdateMapPin` | Adds a Mapbox layer to the map's style for the destination pin |
| `LiveUpdateService` | A foreground service with its own websocket. The app closes its socket in the background. |

The feature uses these upstream APIs. If upstream changes one, the fork does not compile:

- `ITripDetailsViewModel.models` (`context`, `tripData.tripFilter`, `tripData.trip`),
  `IMapViewModel.models` (`LayerState.StopSelected`, `LayerState.TripSelected`,
  `LayerState.Overview`) and `IStopDetailsViewModel.models` (`routeData.filters.stopId`).
- `makeNativeModule`, `ErrorBannerStateRepository`, `DebugRepository`, and the repository
  interfaces for trips, trip predictions, vehicles and global data.
- `TripDetailsStopList.fromPieces`, `TripDetailsStopList.splitForTarget` and `Stop.resolveParent`.
- `DeepLinkState.Stop`, `DeepLinkState.from` and `GlobalResponse.getLineOrRoute`.
- For the trip page: the `TripDetailsViewModel` and `TripDetailsPageViewModel` constructors, the
  `coroutineDispatcherIO` Koin name, `TripDetailsPageFilter`, `IErrorBannerViewModel.setSheetRoute`,
  `PhoenixSocket.attach`/`detach`, and in `androidApp`: `TripDetailsView`, `TripDetailsPageHeader`,
  `TripRouteAccents`, `ErrorBanner`, `ChannelDebugView`, `ScrollSeparatorColumn`,
  `AlertDetailsPage`, `ExplainerPage`, `ModalRoutes`, `getGlobalData`, `subscribeToAlerts`,
  `timer`, `IsLoadingSheetContents` and `R.string.loading`.
- For its map: the `MapViewModel` constructor, the `coroutineDispatcherDefault` Koin name,
  `IMapViewModel` (`setViewportManager`, `alertsChanged`, `selectedVehicleUpdated`,
  `tripStopsUpdated`), `TripDetailsViewModel.MapUpdate`, and in `androidApp`: `HomeMapView`,
  `ViewportProvider`, `LocationDataManager`, `ManageMapboxConfig`, the `component.sheet`
  `BottomSheetScaffold` and `SheetValue`, `DragHandle`, and `SettingsCache` with `Settings.HideMaps`.
- `StopLayerGenerator.createStopLayers`, `StopLayerGenerator.State.StopDetails`,
  `StopFeaturesBuilder.stopSourceId`, the `SymbolLayer` style model, `ColorPalette`, and in
  `androidApp`, `SymbolLayer.toMapbox()` and `MapLayerManager.puckAnchorLayerId`.
- In `androidApp`: `appVariant`, `decodeMessage`, `Socket.wrapped()`, `MainActivity`
  (`deepLinkStateFlow`, `clearDeepLink`), `MyApplicationTheme`, `Typography`,
  `haloContainer`, `notificationPermissionState`, `formattedTime()`, `R.drawable.app_icon_monochrome`,
  `R.drawable.fa_check`, `R.drawable.fa_xmark`, `R.drawable.fa_chevron_right`, the `R.color` values
  `fill2`, `fill3`, `text`, `deemphasized`, `key`, `delete`, `delete_background` and `error`, and
  `R.string.current_locale`.

Prefer `shared/` types and view models over `androidApp` internals. Fork code compiles inside
`:androidApp`, so even its `internal` symbols are reachable, but `androidApp` UI files change most
often upstream. Add an `androidApp` dependency only when `shared/` has no equivalent, and list it
above.

## Upstream contracts
These upstream behaviors are not checked by the compiler. If upstream changes one, the build stays
green and the feature breaks at runtime. `UpstreamContract.kt` is the one place that holds them:
the values copied from upstream, the runtime checks, and an index of where each one is used and
what guards it. Start there when a contract breaks, and add a new contract there too.

| Contract | Guard |
|---|---|
| `MainActivity.handleIntent` opens the `data` URL of an `ACTION_VIEW` intent as a deep link. The notification and "Return to trip tracking" rely on it. | `UpstreamContractTest` runs the real `handleIntent` and compares the result with the state the fork built. |
| `TripPage` tells the trip page from stop details by the trip details view model's `context`, which upstream's `TripDetailsView` sets. | `TripDetailsViewContractTest` renders the real `TripDetailsView` both ways. |
| `TripTracker` skips reconnects on parse errors by the start of upstream's message, `SocketError.FAILED_TO_PARSE` ("channel failed to parse"), internal to `shared`. If it changes, parse errors also reconnect, at most once every 10 seconds. | `UpstreamContractTest` reads the constant by reflection. |
| `LiveUpdateMapPin` copies upstream's selected-pin layer, found by the `-selected-pin` ending of its ID. | `LiveUpdateMapPinTest` runs the real `StopLayerGenerator`. At runtime, check `map_pin_layer`. |
| `LiveUpdateMapPin` finds the Mapbox `MapView` in the view tree (Compose's `MapboxMap` hosts it through `AndroidView`), and adds its layer on upstream's stop source, below the puck anchor slot. It checks every second while shown and adds the layer back if the style dropped it. If this breaks, the pin is missing. The rest of the feature still works. | Runtime check `map_pin_not_shown`: 15 checks in a row (about 15 seconds) find no `MapView`, no stop source, no puck anchor layer, or fail to add the layer. |
| `LiveUpdateOverlayInstaller` adds the overlay in a task posted from `onActivityCreated`, and relies on `MainActivity.onCreate` calling `setContent` first. `setContent` reuses a `ComposeView` that is already the first child of the content view, so in the other order the overlay would replace upstream's UI. | Runtime check `overlay_install_order`: the first child of the content view is not upstream's `ComposeView`. If the content view is empty, the installer skips the pill. |
| Only the map and sheet page handles deep links, on its next frame. The More page ignores them. "Return to trip tracking" sets the link. | 1.5 seconds later, a link still pending is handled by `pendingLinkOutcome`. From a screen where the map shows its overview, the More page may be open, so the overlay restarts `MainActivity` with the link as a new task. This drops the saved navigation state and opens the map and sheet page. From any other screen, it is runtime check `deep_link_unhandled`. |
| `LiveTripScreen` rebuilds upstream's `TripDetailsPage` (its `LaunchedEffect`s and layout) around `TripDetailsView`. `TripDetailsView` must use the view model it is given. The app's socket must open only while an activity attaches it, and the error banner shows only errors for the sheet route set with `setSheetRoute`. | `LiveTripScreenTest` renders it with the real `TripDetailsView` and checks the context, the filters, the socket attach and the sheet route. If upstream's `TripDetailsPage` gains a new effect, the fork's page lacks it: compare the two after a rebase. |
| `LiveTripMap` connects `HomeMapView` to its view model as `ContentView` and `MapAndSheetPage` do: `setViewportManager` before the first `navChanged`, and the trip details `mapUpdates` forwarded to `selectedVehicleUpdated` and `tripStopsUpdated`. If upstream adds a call to that glue, the fork's map lacks it and still compiles. | `LiveTripMapTest` runs the real `HomeMapView` (without Mapbox) and checks the calls. Compare the glue in `ContentView` and `MapAndSheetPage` after a rebase. |
| What `MapViewModel.LayerState` (`StopSelected`, `TripSelected`, `Overview`) means on each page, read by `TripPage`, `StationPage` and the overlay. | Not checked against a real `MapViewModel`. The tests build the states by hand. Run phone steps 4, 5 and 8 after a rebase. |
| `TAB_BAR_HEIGHT` (72 dp) guesses the height of upstream's bottom tab row. The pill sits above it on the map overview. | Not checked. Look at the pill above the tabs after a rebase. |

A runtime check that fails logs an error, adds a `contract_violation check=<name> detail=<why>`
entry to `LiveUpdateDiagnostics`, and on a debuggable build shows a toast. Each check reports once
per process. Find them with `adb logcat -d -s LiveUpdate:E`.

The tests run on every release build and twice a week against upstream main (see "Upstream canary"
in [fork/README.md](../../README.md)).

## Tests
Run the feature tests (the loader must be active, see [fork/README.md](../../README.md)):
```
./gradlew -I fork/android/fork.init.gradle :androidApp:testStagingDebugUnitTest --tests '*liveUpdate*'
```
The Compose UI tests (`LiveUpdateOverlayAccessibilityTest`) run on Robolectric. The first run
downloads Robolectric's Android runtime jar. The Robolectric tests pin `sdk = [35]`: Robolectric's
SDK 36 runtime needs Java 21, and CI uses Java 17 from `.tool-versions`.

`UpstreamContractTest` and `TripDetailsViewContractTest` run real upstream code (see "Upstream
contracts"). If one fails after a rebase, upstream changed a behavior the feature relies on: fix
the feature, not the test. `UpstreamSelfCheckTest` tests the runtime checks.

Test on a phone:
1. Follow a trip. Check that no Live Update starts and that "Track this trip" shows. Tap it.
   Check that a status bar chip and a lock screen card show.
2. Check that the Live Update is promoted:
   ```
   adb shell dumpsys notification --noredact | grep -A20 live_updates | grep -i promot
   ```
3. On the trip page, tap "Choose destination" and select a destination. Check that the
   notification changes and that its header shows the arrival time and the ride time. With
   TalkBack on, check that the list reads the rows as radio buttons and the chosen one as selected.
4. Go to Favorites, Nearby and More. Check that "Return to trip tracking" shows above the tabs.
   Before the vehicle departs, tap it. Check that stop details opens at the boarding station with
   the trip selected. From More, the app restarts its screen first. It must not close. Check that
   the pill now shows "Choose destination" and that a tap opens the list.
5. Tap a different vehicle on the map. Check that "Track this trip" shows. Tap it. Check that the
   Live Update changes to the new trip.
6. Lock the phone for 5 to 10 minutes. Check that the times and the progress bar still change.
7. Tap the notification before the vehicle departs. Check that stop details opens with the trip
   selected, in the fork and not in the official app if both are installed.
8. With a destination, after the vehicle leaves the boarding station, tap the notification and
   "Return to trip tracking". Check that both open the fork's trip page with live times and the
   destination marked, the map showing the vehicle and the destination, and that Back opens
   stop details at the boarding station. There, check
   that the pill shows "Return to trip tracking". Use a terminal destination, such as Alewife, at
   least once. On the fork's trip page:
   - Tap the pill, pick another destination, and check that the page retargets.
   - Lock the phone for 5 minutes, unlock, and check that the times update.
   - Tap a stop in the list, and a stop pin on the map. Check that stop details for that stop
     opens.
   - Drag the sheet to full height and back. Rotate the phone. Check that the map still shows
     the trip.
   - Back in the app, open upstream's trip page for a different trip. Check that it shows that
     trip, not the tracked one.
9. Open the tracked trip from the boarding station with a destination. Check that the map pins
   both stations. Pan and zoom the map. Switch dark mode. Check that the destination pin stays.
10. Tap "Stop". Check that the notification goes away.

## Known limitations
- The Live Update does not show service alerts. The trip page still shows them.
- The Live Update strings are in English only.
- Back or a tapped stop leaves the fork's trip page for `MainActivity`. Back from there does not
  return to it.
- The fork's trip page has its own map, so a second Mapbox map is in memory while `MainActivity`
  is in the back stack. It loads the rail shapes again (the repository caches them).
- The error banner view model is upstream's singleton. Its sheet route is set by whichever
  screen resumed last, so a banner can lag one screen change behind.
- If `MainActivity` shows the More page when Back from the trip page opens stop details, the link
  waits until the map and sheet page shows (see "Upstream contracts").
