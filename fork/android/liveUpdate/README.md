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
  - On the trip page, it shows "Choose destination" or "To <stop>". Tap it to open a list of the
    next stops. Select the stop where you get off. After you board, the Live Update shows the time
    until you arrive.
  - On other screens, it shows "Return to trip tracking". Tap it to open stop details with the
    trip selected. Tap Follow to open the trip page.
- "Track this trip" shows only after the trip data loads. Some trips show "not available" on
  the trip page because the app can't load them. These trips can't be tracked. If no trip
  data comes in 2 minutes, the Live Update ends with "No data for this trip".
- Tap the notification to open stop details with the trip selected.
- Without a destination, the Live Update ends when the vehicle leaves your stop. With a destination,
  it ends when the vehicle arrives there.
- Use "Stop" in the notification, or "Stop tracking" in the list, to end it. If you swipe it
  away, it ends.
- The service posts the notification again only when its content changes. Each post also goes to
  connected watches.

## How it connects to the app
The feature does not change upstream files:

| Part | How it connects to the app |
|---|---|
| `src/main/AndroidManifest.xml` | The loader adds it to each variant with the variant API |
| `feature.gradle` | Adds `androidx.core` for `ProgressStyle`, and Robolectric and Compose UI test for the unit tests |
| `LiveUpdateInitializer` | A content provider that starts the feature after `Application.onCreate` |
| `TripPage` | Reads the map and trip details view models to find the open trip page |
| `LiveUpdateOverlayInstaller` | Adds the pill above `MainActivity`'s content |
| `LiveUpdateDeepLink` | Opens the trip with the app's stop deep link, as an `ACTION_VIEW` intent to `MainActivity` |
| `LiveUpdateService` | A foreground service with its own websocket. The app closes its socket in the background. |

The feature uses these upstream APIs. If upstream changes one, the fork does not compile:

- `ITripDetailsViewModel.models` (`context`, `tripData.tripFilter`, `tripData.trip`) and
  `IMapViewModel.models` (`LayerState.TripSelected.following`, `LayerState.Overview`).
- `makeNativeModule`, `ErrorBannerStateRepository`, `DebugRepository`, and the repository
  interfaces for trips, trip predictions, vehicles and global data.
- `TripDetailsStopList.fromPieces`, `TripDetailsStopList.splitForTarget` and `Stop.resolveParent`.
- `DeepLinkState.Stop`, `DeepLinkState.from` and `GlobalResponse.getLineOrRoute`.
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
   Tap it. Check that stop details opens with the trip selected. From More, the app restarts its
   screen first. It must not close.
5. Tap a different vehicle on the map. Check that "Track this trip" shows. Tap it. Check that the
   Live Update changes to the new trip.
6. Lock the phone for 5 to 10 minutes. Check that the times and the progress bar still change.
7. Tap the notification. Check that stop details opens with the trip selected, in the fork and
   not in the official app if both are installed.
8. Tap "Stop". Check that the notification goes away.

## Known limitations
- The Live Update does not show service alerts. The trip page still shows them.
- The Live Update strings are in English only.
