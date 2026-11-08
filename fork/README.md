# Fork: Android release build

This folder holds fork-only build changes. Upstream files stay unchanged, so rebases on
`mbta/mobile_app` do not conflict.

## What the fork build changes
`fork/android/fork.init.gradle` changes the `:androidApp` release build type:

- The application ID is `com.mbta.tid.mbta_app.mbestavros`. The app installs next to the official app.
- The app label is "MBTA Go (mbestavros)".
- Firebase uses placeholder values and analytics is deactivated. No data goes to MBTA's Firebase project.
- Sentry upload tasks and Sentry plugin telemetry are off.

The init script does not change the version name. `AppVersion.parse()` accepts only `X.Y.Z`,
and the app crashes at launch if the version name has a suffix.

The workflow `.github/workflows/fork-android-release.yaml` builds, signs and publishes the APK.

The app uses MBTA's production backend (`mobile-app-backend.mbtace.com`). The backend supplies the
Mapbox token and all transit data. The fork contains no API keys.

## Local setup
Install the tools with Homebrew:
```
brew install --cask temurin@17 android-commandlinetools
brew install librsvg
```
Add this to `~/.zshrc`:
```
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```
Accept the SDK licenses once: `sdkmanager --licenses`.

## Build locally
```
./gradlew --init-script fork/android/fork.init.gradle :androidApp:assembleProdRelease
```
The output is `androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk`.

## One-time GitHub setup
1. Create a keystore outside the repo:
   ```
   keytool -genkeypair -v -keystore ~/mbta-go-fork.jks -alias release \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Back up the keystore and its passwords offline. Without them, installed copies cannot update.
3. Get the certificate SHA-256:
   ```
   keytool -list -v -keystore ~/mbta-go-fork.jks -alias release | grep 'SHA256:'
   ```
4. Create the environment `android-release` in the GitHub repo settings.
   Add yourself as a required reviewer. Allow deployments only from tags that match `fork-android-v*`.
5. Add the environment secrets:
   ```
   base64 -i ~/mbta-go-fork.jks | gh secret set KEYSTORE_BASE64 --env android-release
   gh secret set KEYSTORE_PASSWORD --env android-release
   gh secret set KEY_ALIAS --env android-release
   gh secret set KEY_PASSWORD --env android-release
   ```
6. Add the repo variable `SIGNING_CERT_SHA256` with the value from step 3:
   ```
   gh variable set SIGNING_CERT_SHA256
   ```
7. Disable the upstream workflows that have their own triggers. They need MBTA secrets or MBTA runners:
   ```
   for wf in android-dev-orange-deploy android-prod-deploy android-staging-deploy \
     ios-dev-orange-deploy ios-prod-deploy ios-staging-deploy test-older-ios ci cache-cleanup; do
     gh workflow disable "$wf.yaml" --repo mbestavros/mbta_mobile_app
   done
   ```
   Keep the `workflow_call` workflows enabled (`build-android`, `build-ios`, `test-android`,
   `test-ios`, `pre-commit`, `zizmor`). They run only when a disabled workflow calls them.

## Update from upstream
Do these steps in order. Each step has a check. Stop if a check fails.

### 1. Prepare
1. Add the upstream remote. Do this one time only:
   ```
   git remote add upstream https://github.com/mbta/mobile_app.git
   ```
2. Go to `main` and make sure the working tree is clean:
   ```
   git switch main
   git status
   ```
   Check: `git status` shows "nothing to commit". If not, commit or stash your changes first.
3. Make sure local `main` is the same as GitHub:
   ```
   git fetch origin
   git status
   ```
   Check: `git status` shows "Your branch is up to date with 'origin/main'".

### 2. Look at upstream changes before you rebase
1. Get the upstream changes:
   ```
   git fetch upstream
   ```
2. Look for workflow changes:
   ```
   git diff --name-status HEAD...upstream/main -- .github/workflows/
   ```
   Check: write down each line that starts with `A` (added). You must disable new workflows in step 5.
3. Look for changes to files that the fork depends on:
   ```
   git diff --stat HEAD...upstream/main -- androidApp/build.gradle.kts \
     shared/src/commonMain/kotlin/com/mbta/tid/mbta_app/model/AppVersion.kt \
     androidApp/src/main/AndroidManifest.xml androidApp/src/main/res/values/google-services.xml
   ```
   Check: if a file is listed, read its diff. Look for changes to the release build type,
   source sets, the Sentry plugin, Firebase setup, or the version format.
4. Make sure upstream has no `release` source set:
   ```
   git ls-tree -d upstream/main androidApp/src/release
   ```
   Check: the output is empty. If not, the fork manifest and upstream's release manifest may conflict.

### 3. Rebase
1. Rebase the fork commits on upstream:
   ```
   git rebase upstream/main
   ```
2. If the rebase stops with a conflict, cancel it:
   ```
   git rebase --abort
   ```
   Then fix the fork files on a branch. Fork files are new paths, so a conflict means upstream
   added a file at one of those paths.
3. Check that only fork files differ from upstream:
   ```
   git diff --stat upstream/main
   ```
   Check: the list contains only `.github/workflows/fork-*.yaml` and files in `fork/`.

### 4. Test locally
1. Run the unit tests and lint. Do not run iOS tasks:
   ```
   ./gradlew spotlessCheck
   ./gradlew shared:check androidApp:check \
     -x shared:jvmTest -x shared:iosSimulatorArm64Test -x shared:iosX64Test
   ```
   Check: both commands show `BUILD SUCCESSFUL`. Run `spotlessCheck` before any build.
   A build generates `Dependency.android.kt`, and `spotlessCheck` fails on that generated file.
2. Build the fork release:
   ```
   ./gradlew --init-script fork/android/fork.init.gradle :androidApp:assembleProdRelease
   ```
   Check: `BUILD SUCCESSFUL`. The Sentry upload tasks show `SKIPPED`.
3. Check the APK:
   ```
   BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
   "$BT/aapt2" dump badging androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk | head -n 1
   ```
   Check: the package name is `com.mbta.tid.mbta_app.mbestavros`. The version name is `X.Y.Z` with no suffix.
4. Optional: sign the APK with a test keystore and install it on a phone. See "Test on a phone".

### 5. Push
1. Disable each new workflow from step 2.2. Do this before you push, or the push can start it:
   ```
   gh workflow disable <file>.yaml --repo mbestavros/mbta_mobile_app
   ```
   A workflow file must exist on GitHub before you can disable it. If the file is new, push first,
   then disable it at once and cancel any run it started (`gh run list`, `gh run cancel <id>`).
2. Push the rebased `main`. The rebase changed the fork commits, so a force push is necessary:
   ```
   git push --force-with-lease origin main
   ```
   `--force-with-lease` stops the push if GitHub has commits that you did not fetch.
3. Rebase your feature branches on the new `main`:
   ```
   git switch <branch>
   git rebase main
   ```

### 6. Release
1. Get the upstream version name:
   ```
   grep 'versionName =' androidApp/build.gradle.kts
   ```
2. Make a tag. Use the version name and a build number. Increase the build number for each release
   of the same version:
   ```
   git tag fork-android-v<version>-<n>
   git push origin fork-android-v<version>-<n>
   ```
   Example: `fork-android-v2.2.1-1`.
3. In the Actions run, approve the `android-release` deployment.
4. Download the APK from the GitHub Release. Install it on your phone as an update.

## Test on a phone
1. Make a test keystore outside the repo:
   ```
   keytool -genkeypair -keystore /tmp/test.jks -alias release -keyalg RSA -keysize 2048 \
     -validity 30 -dname "CN=Test"
   ```
2. Sign the APK:
   ```
   "$BT/apksigner" sign --ks /tmp/test.jks --ks-key-alias release --out /tmp/test.apk \
     androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk
   ```
3. Connect the phone with wireless debugging (`adb pair`, then `adb connect`).
   Keep the screen on. The connection can drop while the phone sleeps.
4. Install with `adb install -r --no-incremental /tmp/test.apk`.
   A test-signed APK cannot update the release APK. Uninstall one before you install the other.
5. Open the app. Check that the map and predictions load.
6. If the app crashes, read the crash log:
   ```
   adb logcat -d -b crash
   ```
   Firebase "API key not valid" errors are expected. They come from the placeholder values.
7. Delete `/tmp/test.jks` and `/tmp/test.apk`.

## Known limitations
- Push notifications do not work. MBTA's backend sends them through MBTA's Firebase project.
- There is no crash reporting.
- The Mapbox SDK can send its own telemetry. The fork does not change this.
- The fork depends on MBTA's backend. If MBTA adds app attestation or rate limits, the fork can stop working.
