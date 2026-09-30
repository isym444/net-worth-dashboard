# Net Worth — Android widget

Kotlin + Jetpack Glance widget that shows your net worth and time-to-milestone projections from `net-worth-service`.
Reads from a local cache only; WorkManager refreshes hourly and a 6-hourly re-render keeps the "updated" text right offline.
Spec and plan: `docs/superpowers/`.

## Build
    cd net-worth-android
    ./gradlew testDebugUnitTest        # unit tests (JVM, no device)
    ./gradlew assembleDebug            # debug APK (accepts http://10.0.2.2 for the emulator)
    ./gradlew assembleRelease          # signed release APK (needs net-worth-android/keystore.properties)

Needs the Android command-line SDK (`brew install --cask android-commandlinetools`) and a JDK 17+;
point Gradle at it with `net-worth-android/local.properties` (`sdk.dir=…`, git-ignored).

## Install on the tablet
1. Copy `app/build/outputs/apk/release/app-release.apk` to the tablet and open it
   (allow "install unknown apps" for the file manager once; "Install anyway" on the Play Protect prompt).
   Or: `adb install -r app-release.apk`.
2. Open **Net Worth**. The API URL defaults to `https://nwapi.isym.uk`; paste the dashboard access key
   (`DASHBOARD_ACCESS_KEY` on the backend), optionally the dashboard URL, then press **Save & test**.
3. Press **Add widget to home screen** (or long-press the home screen → Widgets → Net Worth). Resize it: small shows the headline, medium adds the next three milestones,
   large adds every milestone and the one-year estimate, extra-large adds the assumptions and the age 40/50/60/65 projections, and wide (≥380dp wide and ≥300dp tall) switches to two columns
   with progress to the next goal, 7/30-day and since-start changes, the all-time high, projections with and without contributions,
   and (when tall enough) a trend chart.
   The widget text is Japanese and amounts are in 万/億 with ポンド instead of £ (see `domain/Money.kt`).
4. Oppo/ColorOS: use **Battery optimisation settings** in the app to allow unrestricted background use.

## What the widget shows
- **Headline:** the latest snapshot's `grandTotal`, and the change since the previous snapshot.
- **Milestones:** time to £100k / £250k / £500k / £750k / £1m at 7% a year compounded monthly, with or without your monthly
  contribution (a per-widget option). This is the same maths as the dashboard, ported in `domain/Projection.kt`;
  the assumptions live in `domain/ProjectionConfig.kt`. If you change the return, milestones or birth date on the dashboard or
  backend, change them there too.

## Security
The widget uses the shared `DASHBOARD_ACCESS_KEY`, which has no read-only scope: a leaked key could also trigger
`POST /api/v1/snapshot` and `POST /api/v1/amex/insights`. Rotate it by changing the env var on the VPS and pasting the new
key in the app. The key is stored only in Keystore-backed encrypted preferences and is never logged.

## Signing — back this up
Releases are signed with `~/.android/networth/networth-release.jks` (path and passwords in git-ignored
`net-worth-android/keystore.properties`). Every update must use the same key or Android will refuse to update in place.
**Copy the keystore and its password to a safe place; it cannot be recovered.** Bump `versionCode` in `app/build.gradle.kts`
for each new release.

## Emulator check
`dev/seed.sh` copies `dev/seed-cache.json` into a debug build's cache so the widget renders without the API.
