# Fork features

This fork of `mbta/mobile_app` adds features without editing upstream files, so rebases on upstream
do not conflict. If upstream changes something a feature depends on, the fork build fails with an
error that starts with `fork:`.

## Features
| Feature | Folder | Docs |
|---|---|---|
| Signed release build | `fork/android/release/` | [release/README.md](android/release/README.md) |
| Live Update for followed trips | `fork/android/liveUpdate/` | [liveUpdate/README.md](android/liveUpdate/README.md) |

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
   Approved exceptions mark each upstream line with `// fork(<feature>)` and are listed in the
   feature's README. Today there are none. To open a screen upstream has no deep link for, host
   upstream's composables in a fork activity, as `liveUpdate`'s `LiveTripActivity` does.
3. If the feature needs build settings, add `feature.gradle`. Register each hook with
   `forkExpect('<feature>: <hook>')` and mark it with `forkDone('<feature>: <hook>')` when it runs.
   The loader fails the build if a hook never ran. See `release/feature.gradle`.
4. Depend on `shared/` types and view models where you can, not on `androidApp` internals. Fork
   code compiles inside `:androidApp`, so even its `internal` symbols are reachable, but upstream
   changes its UI files most often. List each upstream API the feature uses in its README.
5. Keep what the feature assumes about upstream behavior, beyond what the compiler checks, in one
   file, such as `liveUpdate/.../UpstreamContract.kt`. Guard each assumption with a test that runs
   real upstream code or with a runtime check, so a change in upstream fails a test or shows in
   diagnostics instead of breaking quietly.
6. Add the feature to the table above.

## Upstream canary
`.github/workflows/fork-upstream-canary.yaml` runs on Mondays and Thursdays, and by hand from the
Actions tab. In a scratch checkout, it rebases the fork onto `mbta/mobile_app` main, then runs the
fork format check, the fork tests and the prod release build with the APK check. It pushes
nothing. On a failure it opens an issue labelled `upstream-canary`, or comments on the open one,
unless the latest note already reports the same stage on the same upstream commit. The next run
that passes closes it. The issue says which stage failed: the rebase, the fork
checks or the release build. So a break in upstream shows up a few days after upstream merges it,
not at the next rebase.

GitHub runs scheduled workflows on a fork only once Actions is enabled for it, and turns them off
after 60 days without activity in the repository. The fork also needs issues turned on. Re-enable
the workflow in the Actions tab if it stops.
