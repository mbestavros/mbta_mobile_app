# Fork features

This fork of `mbta/mobile_app` adds features without editing upstream files, so rebases on upstream
do not conflict. If upstream changes something a feature depends on, the fork build fails with an
error that starts with `fork:`.

## Features
| Feature | Folder | Docs |
|---|---|---|
| Signed release build | `fork/android/release/` | [release/README.md](android/release/README.md) |
| Live Update for followed trips | `fork/android/liveUpdate/` | "Live Update" in [release/README.md](android/release/README.md) |

The release docs also cover local setup, one-time GitHub setup, and how to update from upstream.

## How features are built
Install the loader in `~/.gradle/init.d/` (see "Local setup" in
[release/README.md](android/release/README.md)) so that Android Studio and every Gradle command
build the fork features. Without it, pass the loader to each Gradle command:
```
./gradlew --init-script fork/android/fork.init.gradle <tasks>
```
Without either, Gradle builds plain upstream and no fork code. `fork.init.gradle` applies only once,
so both together are fine. CI uses `--init-script`.

`fork/android/fork.init.gradle` treats each folder `fork/android/<feature>/` as one feature,
laid out like an Android module:

| Path in the feature folder | What the loader does |
|---|---|
| `src/<sourceSet>/kotlin`, `java`, `res` | Adds the folder to `android.sourceSets.<sourceSet>` of `:androidApp` |
| `src/<sourceSet>/AndroidManifest.xml` | Merges it into matching variants (`addStaticManifestFile`) |
| `feature.gradle` | Applies it to `:androidApp`, for feature-only build settings |

`<sourceSet>` is `main`, `test`, `androidTest`, a build type (`debug`, `release`), a flavor
(`prod`, `staging`, `devOrange`, `local`) or a variant name such as `prodRelease`. Code in `main`
goes into every variant. An unknown source set name fails the build.

## Add a feature
1. Make `fork/android/<feature>/src/main/kotlin/...` (and `res/`, `AndroidManifest.xml`, `src/test/...`
   as needed).
2. Hook into the app at runtime, not by editing upstream: observe public Koin singletons and
   StateFlows, start up from a `ContentProvider` in the feature manifest, and use window overlays.
3. If the feature needs build settings, add `feature.gradle`. Register each hook with
   `forkExpect('<feature>: <hook>')` and mark it with `forkDone('<feature>: <hook>')` when it runs.
   The loader fails the build if a hook never ran. See `release/feature.gradle`.
4. Add the feature to the table above.
