# Fork feature: Android release build

This feature builds, signs and publishes the fork's own prod APK. It is a fork feature like any
other (see `fork/README.md`), so upstream files stay unchanged.

## What the fork build changes
The feature changes the `:androidApp` release build type:

- The application ID is `com.mbta.tid.mbta_app.mbestavros`. The app installs next to the official app.
- The app label is "MBTA Go (mbestavros)".
- Firebase uses placeholder values and analytics is deactivated. No data goes to MBTA's Firebase project.
- `BuildConfig.SENTRY_DSN` is empty, so the app never starts Sentry, even when `SENTRY_DSN_ANDROID`
  is set in `.envrc` or the environment.
- Sentry source bundles, upload tasks and Sentry plugin telemetry are off.

Files in `fork/android/release/`:

| File | Purpose |
|---|---|
| `feature.gradle` | Application ID suffix, empty Sentry DSN, Sentry plugin settings |
| `src/release/AndroidManifest.xml` | App label, analytics and messaging auto-init off |
| `src/release/res/values/fork-firebase.xml` | Placeholder Firebase values |
| `check-apk.sh` | Checks an APK's package, label, version name, analytics flag and that it has no Sentry DSN |

To use a different identity, change `applicationIdSuffix` in `feature.gradle`, `android:label` in
`src/release/AndroidManifest.xml`, and the expected values at the top of `check-apk.sh`.

If upstream renames something the fork scripts use, the build fails with a message that starts with
`fork:`. Fix the fork script that the message names.

Do not add a version name suffix. `AppVersion.parse()` accepts only `X.Y.Z`,
and the app crashes at launch if the version name has a suffix.

The workflow `.github/workflows/fork-android-release.yaml` has three jobs:

| Job | Runs repository code | Access |
|---|---|---|
| `build` | Yes | Read-only token, no secrets |
| `sign` | No | Signing secrets from the `android-release` environment |
| `release` | No | Token that can create releases, no secrets |

The `build` job gives `--init-script fork/android/fork.init.gradle` to Gradle, so CI does not need
the loader (see "Local setup"). Before the release build, the job checks the format of the fork
Kotlin files and runs the Live Update tests.

The app uses MBTA's production backend (`mobile-app-backend.mbtace.com`). The backend supplies the
Mapbox token and all transit data. The fork contains no API keys.

## Local setup
You need these tools:

- JDK 17. `.tool-versions` has the exact version that upstream uses.
- The Android SDK command-line tools. Gradle downloads the SDK platform and build tools.
- `rsvg-convert`, from the librsvg package.
- The GitHub CLI (`gh`). Log in with `gh auth login`.

On macOS, Homebrew can install them:
```
brew install --cask temurin@17 android-commandlinetools
brew install librsvg gh
```

Set `ANDROID_HOME` to the SDK folder and add `platform-tools` to `PATH` in your shell profile:
```
export ANDROID_HOME=<path to the Android SDK>
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```
With the Homebrew package, the SDK folder is `$(brew --prefix)/share/android-commandlinetools`.

Accept the SDK licenses and install `adb`:
```
sdkmanager --licenses
sdkmanager platform-tools
```

Set your git identity before you commit. If you do not, git can use your machine's hostname,
and a push makes that hostname public:
```
git config user.name "<your name>"
git config user.email "<your GitHub noreply address>"
```

Do not install the `pre-commit` hooks unless you also install `xcodegen`.
The hooks run `xcodegen` after each checkout, merge and rebase.

Install the fork loader, so that Android Studio and every Gradle command apply the fork changes.
Without it, Android Studio builds do not include fork features. Create
`~/.gradle/init.d/mbta-fork.gradle` with this content:
```groovy
// Applies the MBTA Go fork build changes to any checkout that has fork/android/fork.init.gradle.
gradle.settingsEvaluated { settings ->
    def forkScript = new File(settings.rootDir, 'fork/android/fork.init.gradle')
    if (forkScript.exists()) gradle.apply(from: forkScript)
}
```
The loader has no effect on other projects. `fork.init.gradle` applies only one time, so you can
also give `--init-script` when the loader is installed. After you install the loader, sync the
project in Android Studio.

## Build locally
```
./gradlew --init-script fork/android/fork.init.gradle :androidApp:assembleProdRelease
```
The output is `androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk`.
Check it with the same script that CI runs:
```
fork/android/release/check-apk.sh androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk
```
Check: the last line starts with `check-apk: OK`.

## One-time GitHub setup
Do these steps in order. Set `REPO` first, and use it in each command:
```
REPO=<owner>/<repo>
```
Always give `--repo`. When a clone has an `upstream` remote, `gh` can select the upstream repository.

### 1. Disable upstream workflows
1. Open the Actions tab of the fork. If GitHub asks, enable workflows.
2. Disable the upstream workflows that have their own triggers. They need MBTA secrets or MBTA runners:
   ```
   for wf in android-dev-orange-deploy android-prod-deploy android-staging-deploy \
     ios-dev-orange-deploy ios-prod-deploy ios-staging-deploy test-older-ios ci cache-cleanup; do
     gh workflow disable "$wf.yaml" --repo "$REPO"
   done
   ```
3. Check the result:
   ```
   gh workflow list --all --repo "$REPO"
   ```
   Check: the nine workflows are not `active`. Keep the `workflow_call` workflows enabled
   (`build-android`, `build-ios`, `test-android`, `test-ios`, `pre-commit`, `zizmor`).
   They run only when another workflow calls them, and the workflows that call them are disabled.

### 2. Restrict GitHub Actions
1. Allow only GitHub-owned actions and the Gradle wrapper check. Require full commit SHA pins:
   ```
   P="repos/$REPO/actions/permissions"
   gh api -X PUT "$P" -F enabled=true -f allowed_actions=selected -F sha_pinning_required=true
   gh api -X PUT "$P/selected-actions" -F github_owned_allowed=true -F verified_allowed=false \
     -f 'patterns_allowed[]=gradle/actions/wrapper-validation@*'
   gh api -X PUT "$P/fork-pr-contributor-approval" -f approval_policy=all_external_contributors
   ```
2. In Settings > Actions > General, under "Workflow permissions":
   1. Select "Read repository contents and packages permissions".
   2. Turn off "Allow GitHub Actions to create and approve pull requests".

### 3. Protect the repository
1. In Settings > Advanced Security, turn on secret scanning and push protection.
2. In Settings > General > Releases, turn on release immutability.
3. In Settings > Rules > Rulesets, create a tag ruleset:
   1. Set enforcement to "Active".
   2. Target the tags that match `fork-android-v*`.
   3. Restrict creations, updates and deletions.
   4. Add the `Repository admin` role to the bypass list. A personal repository accepts roles, not people.
4. Do not block force pushes on `main`. A rebase on upstream needs a force push.
5. Turn on 2FA or a passkey for your GitHub account.

### 4. Create the signing key
1. Create a keystore outside the repo:
   ```
   keytool -genkeypair -v -keystore ~/mbta-go-fork.jks -alias release \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
   `keytool` can ask for one password only. A PKCS12 keystore uses one password for the keystore and the key.
2. Make the file private:
   ```
   chmod 600 ~/mbta-go-fork.jks
   ```
3. Back up the keystore and its password offline. Without them, installed copies cannot update.
4. Get the certificate fingerprint:
   ```
   keytool -list -v -keystore ~/mbta-go-fork.jks -alias release | grep 'SHA256:'
   ```

### 5. Create the environment and secrets
1. In Settings > Environments, create the environment `android-release`.
2. Under "Deployment branches and tags", select "Selected branches and tags".
   Add a rule of type "Tag" with the pattern `fork-android-v*`.
3. Add yourself as a required reviewer. If the search box finds nothing, use the API.
   This command keeps the tag rule:
   ```
   ID=$(gh api users/<your GitHub username> --jq .id)
   gh api -X PUT "repos/$REPO/environments/android-release" --input - <<EOF
   {
     "reviewers": [{ "type": "User", "id": $ID }],
     "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
   }
   EOF
   ```
4. Add the environment secrets. Do not add repository-level copies:
   ```
   base64 < ~/mbta-go-fork.jks | gh secret set KEYSTORE_BASE64 --env android-release --repo "$REPO"
   gh secret set KEYSTORE_PASSWORD --env android-release --repo "$REPO"
   gh secret set KEY_ALIAS --env android-release --repo "$REPO"
   gh secret set KEY_PASSWORD --env android-release --repo "$REPO"
   ```
   The value of `KEY_ALIAS` is `release`. If `keytool` asked for one password only, use that
   password for `KEYSTORE_PASSWORD` and for `KEY_PASSWORD`.
5. Add the fingerprint from step 4 as a repository variable:
   ```
   gh variable set SIGNING_CERT_SHA256 --repo "$REPO"
   ```
   Enter the hexadecimal value only. Do not include `SHA256:`. Colons are permitted.

### 6. Test the workflow
1. Push the fork files to `main`:
   ```
   git push origin main
   ```
   If you push over HTTPS, the token needs the `workflow` scope. Add it with `gh auth refresh -s workflow`.
2. Run the workflow manually. A manual run builds the unsigned APK only:
   ```
   gh workflow run fork-android-release.yaml --repo "$REPO" --ref main
   gh run watch --repo "$REPO"
   ```
   Check: `build` passes. `sign` and `release` show as skipped.
3. Make a release. See "Release".

## Release
1. Get the upstream version name:
   ```
   grep 'versionName =' androidApp/build.gradle.kts
   ```
2. Make a tag from the commit to release. Use the version name and a build number.
   Increase the build number for each tag of the same version:
   ```
   git tag fork-android-v<version>-<n>
   git push origin fork-android-v<version>-<n>
   ```
   Example: `fork-android-v2.2.1-1`.
3. Wait for `build` to finish. Then approve the `android-release` deployment in the Actions run.
4. Download the APK from the GitHub Release.
5. Install the APK on your phone.

The workflow sets the version code from the tag: `fork-android-vX.Y.Z-N` gives
`X*1000000 + Y*10000 + Z*100 + N`. A higher version or build number always gives a higher code,
so the phone accepts the update. Y, Z and N must each be under 100, or the build fails.
The `build` job also fails if the APK's version name is not the `X.Y.Z` from the tag.

If a release fails, make a new tag with a higher build number. Do not run the old tag again.
A tag uses the workflow file from its own commit. An immutable release cannot be replaced.

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
   Your own feature commits are also listed if they are on `main`.

### 4. Test locally
1. Check formatting. Delete the generated dependency file first, because it fails the format check.
   Git ignores this file and the build makes it again:
   ```
   rm -f shared/src/androidMain/kotlin/com/mbta/tid/mbta_app/model/Dependency.android.kt
   ./gradlew spotlessCheck
   ```
   Check: `BUILD SUCCESSFUL`. When the fork script applies, `spotlessCheck` also checks the Kotlin
   files in `fork/` (task `spotlessForkKotlinCheck`). To fix the format, run
   `./gradlew spotlessForkKotlinApply`.
2. Run the unit tests and lint. These flags skip the iOS tests and the format check that you did in step 1:
   ```
   ./gradlew shared:check androidApp:check -x shared:jvmTest -x shared:spotlessKotlinCheck \
     -x shared:iosSimulatorArm64Test -x shared:iosX64Test
   ```
   Check: `BUILD SUCCESSFUL`. If Gradle reports that an iOS task does not exist, remove that flag.
   With the loader installed, this also compiles and tests the fork features. Without the loader,
   add `--init-script fork/android/fork.init.gradle`.
   If the compiler reports errors in `fork/`, upstream changed an API that a fork feature uses.
   See the upstream API lists in each feature's README, such as
   [liveUpdate/README.md](../liveUpdate/README.md).
3. Build the fork release:
   ```
   ./gradlew --init-script fork/android/fork.init.gradle :androidApp:assembleProdRelease
   ```
   Check: `BUILD SUCCESSFUL`. A message that starts with `fork:` means upstream changed something
   the fork scripts use.
4. Check the APK:
   ```
   fork/android/release/check-apk.sh androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk
   ```
   Check: the last line starts with `check-apk: OK`.
5. Optional: test the APK on a phone. See "Test on a phone".

### 5. Push
1. Push the rebased `main`. The rebase changed the fork commits, so a force push is necessary:
   ```
   git push --force-with-lease origin main
   ```
   `--force-with-lease` stops the push if GitHub has commits that you did not fetch.
2. Disable each new workflow from step 2.2 immediately. A workflow file must be on GitHub before
   you can disable it. If the push started the workflow, cancel the run:
   ```
   REPO=<owner>/<repo>
   gh workflow disable <file>.yaml --repo "$REPO"
   gh run list --repo "$REPO"
   gh run cancel <run id> --repo "$REPO"
   ```
3. Rebase your feature branches on the new `main`:
   ```
   git switch <branch>
   git rebase main
   ```

### 6. Release
Make a new release. See "Release".

## Test on a phone
1. Make a test keystore outside the repo:
   ```
   keytool -genkeypair -keystore /tmp/test.jks -alias release -keyalg RSA -keysize 2048 \
     -validity 30 -dname "CN=Test"
   ```
2. Sign the APK:
   ```
   BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
   "$BT/apksigner" sign --ks /tmp/test.jks --ks-key-alias release --out /tmp/test.apk \
     androidApp/build/outputs/apk/prod/release/androidApp-prod-release-unsigned.apk
   ```
3. Connect the phone with USB debugging or wireless debugging (`adb pair`, then `adb connect`).
   For wireless debugging, keep the screen on. The connection can drop while the phone sleeps.
4. Install the APK:
   ```
   adb install -r --no-incremental /tmp/test.apk
   ```
   A test-signed APK cannot update the release APK. Uninstall one before you install the other.
   An uninstall deletes the app's data.
5. Open the app. Check that the map and predictions load.
6. Open a station, select a direction and a trip, and tap Follow. Tap "Track this trip". Check
   that a Live Update shows.
   See [liveUpdate/README.md](../liveUpdate/README.md) for the full test.
7. If the app crashes, read the crash log:
   ```
   adb logcat -d -b crash
   ```
   Firebase "API key not valid" errors are expected. They come from the placeholder values.
8. Delete `/tmp/test.jks` and `/tmp/test.apk`.

## Known limitations
- Push notifications do not work. MBTA's backend sends them through MBTA's Firebase project.
- There is no crash reporting. The fork blanks the Sentry DSN.
- Links to `go.mbta.com` and `mobile-app-backend.mbtace.com` do not open the fork by default.
  Upstream declares them as verified App Links, and MBTA's `assetlinks.json` does not list the fork's
  signing certificate. You can turn them on by hand in Android's "Open by default" settings for the app.
- The Mapbox SDK can send its own telemetry. The fork does not change this.
- The fork depends on MBTA's backend. If MBTA adds app attestation or rate limits, the fork can stop working.
- CI does not run upstream's unit tests or emulator tests. Run the unit tests locally.
- For Live Update limitations, see [liveUpdate/README.md](../liveUpdate/README.md).
