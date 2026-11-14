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
  - After the vehicle leaves the boarding station, you are on it. They open the trip page, on top
    of stop details, so Back returns to the station. The trip page targets the destination, so the
    map fits the vehicle and the destination and upstream pins the destination.
- If you open the tracked trip's page yourself (it targets the boarding station), the map also
  pins the destination station. The pin stays while you move the map.
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
The feature changes upstream in one place, approved as an exception to the fork rule. Each line is
marked `// fork(liveUpdate)`:

- `shared/.../routes/DeepLinkState.kt`: `DeepLinkState.Stop` has the optional fields `vehicleId`,
  `follow` and `followStopId`, read from `/v/<vehicle>` and `/follow` or `/follow/<stop>` URL
  segments, and a `followRoute` for the trip page. The trip page targets `followStopId`, else the
  stop. Stop details underneath always opens the boarding station, so a terminal destination with
  no departures can't hang it. The fields have defaults, so other callers don't change. iOS reads only `sheetRoute`, so it
  still builds and opens stop details for these links.
- `androidApp/.../pages/MapAndSheetPage.kt`: one line opens `followRoute` after `sheetRoute`.

`DeepLinkFollowTest` in this feature tests the parser edit. On a rebase conflict in these files,
keep upstream's version and add the marked lines back.

Everything else is in this folder:

| Part | How it connects to the app |
|---|---|
| `src/main/AndroidManifest.xml` | The loader adds it to each variant with the variant API |
| `feature.gradle` | Adds `androidx.core` for `ProgressStyle`, and Robolectric and Compose UI test for the unit tests |
| `LiveUpdateInitializer` | A content provider that starts the feature after `Application.onCreate` |
| `TripPage` | Reads the map and trip details view models to find the open trip page. It uses the trip details `context`, not `following`, which turns off when you move the map. |
| `LiveUpdateOverlayInstaller` | Adds the pill above `MainActivity`'s content |
| `LiveUpdateDeepLink` | Opens the trip with the app's stop deep link, as an `ACTION_VIEW` intent to `MainActivity` |
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
- `StopLayerGenerator.createStopLayers`, `StopLayerGenerator.State.StopDetails`,
  `StopFeaturesBuilder.stopSourceId`, the `SymbolLayer` style model, `ColorPalette`, and in
  `androidApp`, `SymbolLayer.toMapbox()` and `MapLayerManager.puckAnchorLayerId`.
- In `androidApp`: `appVariant`, `decodeMessage`, `Socket.wrapped()`, `MainActivity`
  (`deepLinkStateFlow`, `clearDeepLink`), `MyApplicationTheme`, `Typography`,
  `haloContainer`, `notificationPermissionState`, `formattedTime()`, `R.drawable.app_icon_monochrome`,
  `R.drawable.fa_check`, `R.drawable.fa_xmark`, `R.drawable.fa_chevron_right`, the `R.color` values
  `fill2`, `fill3`, `text`, `deemphasized`, `key`, `delete`, `delete_background` and `error`, and
  `R.string.current_locale`.

These upstream behaviors are not checked by the compiler. If upstream changes one, the feature
breaks at runtime. Check them after each rebase:

- `MainActivity.handleIntent` opens the `data` URL of an `ACTION_VIEW` intent as a deep link. The
  notification and the "Return to trip tracking" fallback rely on it.
- Only the map and sheet page handles deep links, on its next frame. The More page ignores them.
  From a screen where the map shows its overview, "Return to trip tracking" sets the deep link.
  If the link is still pending after 1.5 seconds, the overlay assumes the More page is open. It
  then restarts `MainActivity` with the link as a new task, which drops the saved navigation state
  and opens the map and sheet page. If upstream adds more pages outside the map and sheet page,
  check this.
- `TAB_BAR_HEIGHT` in `LiveUpdateOverlay.kt` (72 dp) is a guess at the height of upstream's bottom
  tab row. The pill sits above it on the map overview. If upstream changes the tab row, the pill
  can cover it or float too high.
- `LiveUpdateOverlayInstaller` adds the overlay in a task posted from `onActivityCreated`. It
  relies on `MainActivity.onCreate` calling `setContent` before that task runs. `setContent`
  reuses a `ComposeView` that is already the first child of the content view, so if the order
  changes, the overlay replaces upstream's UI.
- `LiveUpdateMapPin` finds the Mapbox `MapView` in the activity's view tree. Compose's `MapboxMap`
  hosts it through `AndroidView`. It copies upstream's selected-pin layer, which it finds by the
  `-selected-pin` ending of the layer ID. It adds its layer below the puck anchor slot, which puts
  it above upstream's stop layers. While shown, it checks every second and adds the layer back if
  the style dropped it. If upstream changes any of this, the pin is missing or drawn below the
  stops. The rest of the feature still works.
- `TripTracker` skips reconnects on parse errors by the start of upstream's message,
  `SocketError.FAILED_TO_PARSE` ("channel failed to parse"). That constant is internal to `shared`.
  If it changes, parse errors also reconnect, at most once every 10 seconds.

## Tests
Run the feature tests (the loader must be active, see [fork/README.md](../../README.md)):
```
./gradlew -I fork/android/fork.init.gradle :androidApp:testStagingDebugUnitTest --tests '*liveUpdate*'
```
The Compose UI tests (`LiveUpdateOverlayAccessibilityTest`) run on Robolectric. The first run
downloads Robolectric's Android runtime jar.

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
   "Return to trip tracking". Check that both open the trip page with the map showing both the
   vehicle and the destination, and that Back returns to the station. There, check that the pill
   shows "Return to trip tracking". Use a terminal destination, such as Alewife, at least once.
9. Open the tracked trip from the boarding station with a destination. Check that the map pins
   both stations. Pan and zoom the map. Switch dark mode. Check that the destination pin stays.
10. Tap "Stop". Check that the notification goes away.

## Known limitations
- The Live Update does not show service alerts. The trip page still shows them.
- The Live Update strings are in English only.
