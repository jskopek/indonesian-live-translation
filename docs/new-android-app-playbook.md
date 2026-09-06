# Playbook: bootstrapping a new Android app with one-tap updates

This is how `jskopek/android-calculator` is set up, distilled so a fresh project (and a
fresh agent) can reach "installable on the phone, updates in one tap" in a single session.
Reference implementation: https://github.com/jskopek/android-calculator

## The shape of the pipeline

1. Every push to the development branch runs GitHub Actions.
2. CI runs unit tests, builds a **signed release APK**, and publishes a **GitHub Release**
   tagged `v0.1.<run number>` with the APK attached.
3. The phone runs [Obtainium](https://github.com/ImranR98/Obtainium), pointed at the repo.
   It notices each new release and installs it with one tap.

That is the whole distribution story. No Play Console, no zip files, no ADB.

## Environment facts the agent must know

- **The remote sandbox cannot build Android.** `dl.google.com` (the Android SDK and the
  Android Gradle Plugin) is blocked by the network policy. Do not spend time trying to
  install the SDK; CI is the compiler. Write code carefully, push, read the CI result.
- **Pure Kotlin can be tested locally.** Put logic in a package with no Android imports,
  and run its JUnit tests with a throwaway Kotlin/JVM Gradle project in the scratchpad that
  points `srcDir` at those files (Gradle and a JDK are installed; Maven Central and the
  Gradle plugin portal are reachable). This caught real bugs before the first CI run.
- **Font files, icons, and fonts.googleapis.com** may or may not be reachable;
  `raw.githubusercontent.com` was, which is how fonts were fetched from `google/fonts`.
- GitHub is only reachable through the MCP tools (no `gh`). `actions_list`,
  `actions_get`, `actions_run_trigger`, and `get_job_logs` are enough to drive CI.

## Step by step (target: first installable build within the first session)

### 1. Project skeleton

Copy the shape of the reference repo, not its features:

- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `gradle/libs.versions.toml` (version catalog), `app/build.gradle.kts`.
- Gradle wrapper: run `gradle wrapper --gradle-version 8.11.1` in a scratch directory
  that contains an empty `settings.gradle.kts`, then copy `gradlew`, `gradlew.bat`, and
  `gradle/wrapper/` into the project. `git update-index --chmod=+x gradlew`.
- Known-good versions: AGP 8.7.3, Kotlin 2.0.21 with the Compose compiler plugin,
  Compose BOM 2024.12.01, compileSdk/targetSdk 35, minSdk 26, JDK 17 in CI.
- Icons: an adaptive icon made of a vector drawable plus a colour
  (`res/mipmap-anydpi-v26/ic_launcher.xml`). No PNGs needed at minSdk 26.
- Theme: `android:Theme.Material.Light.NoActionBar` (and a `values-night` variant);
  Compose draws everything else. No AppCompat dependency.
- `.gitignore` must exclude `*.jks`, `*.keystore`, `local.properties`, `build/`.

Pick a unique `applicationId` (reverse-domain, e.g. `ca.skopek.<appname>`). It cannot be
changed later without users reinstalling.

### 2. Signing key (do this before the first build, not after)

A debug-signed build from CI uses a throwaway key that differs on every runner, so the
next build cannot update over it and the user has to uninstall. Generate a real key once:

```sh
keytool -genkeypair -v -keystore release.jks -alias <appname> -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass "<password>" -keypass "<password>" -dname "CN=<App name>"
base64 -w0 release.jks > keystore-base64.txt
```

Send the user `release.jks`, `keystore-base64.txt`, and the password (SendUserFile), and
ask them to add four repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Tell them to back the keystore up: if it is lost, the app can
never be updated in place again.

In `app/build.gradle.kts`, read the key from environment variables and fall back to the
debug key when they are absent so local `assembleRelease` still works. Take `versionCode`
and `versionName` from `VERSION_CODE` / `VERSION_NAME` env vars. Copy the block from the
reference repo's `app/build.gradle.kts`.

### 3. Workflow

Copy `.github/workflows/android.yml` from the reference repo. It:

- triggers on every push to any branch (plus `workflow_dispatch`),
- sets `VERSION_NAME: 0.1.${{ github.run_number }}` and `VERSION_CODE: ${{ github.run_number }}`,
- runs `:app:testDebugUnitTest`, decodes the keystore secret to `$RUNNER_TEMP`,
  runs `:app:assembleRelease`, renames the APK to `<appname>-v<version>.apk`,
- uploads it as an artifact and publishes a GitHub Release with
  `softprops/action-gh-release@v2` (`permissions: contents: write`).

The keystore step fails loudly if the secret is missing. Expect the first run to be red
until the secrets exist; re-run it with `actions_run_trigger` (`run_workflow`) afterwards
rather than pushing an empty commit.

### 4. Repository visibility

Obtainium cannot see releases on a private repo without a personal access token. Either
make the repo public (simplest; the signing key is never in the repo) or have the user add
a fine-grained GitHub token in Obtainium's settings.

### 5. Phone setup (the user does this once)

1. Uninstall any earlier debug-signed install of the app.
2. Install Obtainium (Play Store, or `app-arm64-v8a-release.apk` from its GitHub releases).
3. In Obtainium: Add App, paste the repo URL, install. Grant "install from this source" once.
4. Play Protect will show an "unknown developer" note on installs. That is expected for
   apps signed outside Google Play.

### 6. Prove the loop

Push a trivial change, watch the run go green, confirm Obtainium offers the new version.
Only then start on features.

## Gotchas collected along the way

- Every push creates a release, so expect a build per commit. Batch tiny changes.
- `versionCode` must always increase: the workflow run number guarantees that. Never
  hard-code it.
- Keep the same keystore forever. Changing it means the user uninstalls.
- Release notes come from the head commit message, so write commit messages a person
  would want to read on the phone.
- CI builds take about 3 to 4 minutes; wait with a background `sleep`, then check the run.
  Do not poll aggressively.
- `material-icons-extended` is convenient but large; fine while R8 shrinks release builds.
- For foldables: use `material3-window-size-class` and keep state in a ViewModel so
  fold/unfold and rotation never lose input. Two-pane above Compact width works well.
- Write Compose code against stable, well-known APIs; there is no local compiler to catch
  a typo, so a wrong import costs a CI cycle.

## Files worth copying from the reference repo

- `.github/workflows/android.yml` — the whole pipeline
- `app/build.gradle.kts` — signing config, env-driven versioning
- `gradle/libs.versions.toml`, `settings.gradle.kts`, `gradle.properties`
- `app/src/main/res/mipmap-anydpi-v26/` and `res/drawable/ic_launcher_foreground.xml`
- `README.md` "Installing updates on a phone" section for the user-facing explanation
