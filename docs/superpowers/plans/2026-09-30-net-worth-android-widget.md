# Net Worth Android Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kotlin/Jetpack Glance home-screen widget (plus a minimal settings app) that shows the net-worth API's current net worth and time-to-milestone projections on the user's Oppo tablet, released as a stably-signed APK.

**Architecture:** A pure, JVM-tested core (`domain/` projection maths, money formatting and widget view-model; `data/` API client, cache, sync) sits under thin Android shells (Glance widget, Compose settings and config screens, WorkManager scheduling). The widget render path never touches the network: it is a pure function of (cache file, per-widget config, sync status, now). The project is adapted from `countdown/android` (same author, same toolchain), not shared with it.

**Tech Stack:** Kotlin 2.0, Gradle 8.10 + AGP 8.7, Jetpack Glance 1.1, WorkManager 2.9, OkHttp 4.12, kotlinx.serialization, Jetpack Compose Material3 (settings screens only), JUnit4 + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-09-30-net-worth-android-widget-design.md`

## Global Constraints

- Project lives in `net-worth-android/` at the repo root; `applicationId` and `namespace` are `com.isym444.networth`; minSdk 31, targetSdk/compileSdk 35.
- Native Kotlin; Jetpack Glance widget; WorkManager refresh (60 min periodic) plus a 6 h network-free re-render. **No midnight alarm. No backend or frontend changes.**
- The widget render path performs **no network I/O**; it reads the cache file only. A failed refresh keeps the cache. With a cache present the widget never shows an error state, only a hint line.
- Endpoint: `GET {baseUrl}/api/v1/history`, header `X-Api-Key: <DASHBOARD_ACCESS_KEY>`. **Not** `Authorization: Bearer`. The backend's 401 body is `{"error": "..."}` (not problem+json).
- The key is stored only in Keystore-backed `EncryptedSharedPreferences`, **never logged** (no OkHttp logging interceptor), excluded from backup (`allowBackup=false`). HTTPS required except `localhost`, `127.0.0.1`, `10.0.2.2`.
- Milestone maths mirrors `timeToReachPortfolio` in `net-worth-frontend/src/app/dashboard.component.ts`: annual return `0.07` compounded monthly (`1.07^(1/12)`), cap `1200` months, milestones `100000, 250000, 500000, 750000, 1000000`, birth date `1996-06-14`. Constants live only in `ProjectionConfig`.
- Currency symbol and code come from the latest snapshot's `currency`; there is no currency picker.
- Release APK signed with one stable keystore kept **outside the repo**; `minify` off. No secrets, keystores, `keystore.properties` or `local.properties` committed. Nothing is read from the repo's `.env`.
- Every commit message ends with these two lines (blank line before them):
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp`
- Commands run from the `net-worth-tracker` repo root; Gradle commands are written `(cd net-worth-android && ./gradlew …)`.

## Deviations from the spec (decided while planning; all small)

1. **No time-zone setting.** "Today" (for ages) and "updated Xh ago" use the device zone. Countdown needed a configured zone for day roll-over; a net-worth figure does not.
2. **The cache stores only the last two snapshots** (plus the projection fields). The widget needs only the latest and the one before it, and `/history` returns every daily snapshot since the start.
3. **The `HistorySource` interface** sits between `Sync` and `ApiClient` so `Sync` is tested without HTTP.
4. **Tapping the widget without a web URL launches the app's launcher activity** via an implicit `MAIN/LAUNCHER` intent restricted to this package, so the widget code does not depend on `SettingsActivity`.
5. **No ETag / `If-None-Match`.** `/api/v1/history` sends no ETag, and the payload is small once trimmed.
6. **A 401/403 never triggers a WorkManager retry**, since retrying a rejected key is pointless.

## Review Focus

Inputs and conditions the spec implies but doesn't spell out. Each has a pinning test in the task named.

1. **Access key pasted with a trailing newline or spaces** → trimmed and still authenticates; a key with inner whitespace is rejected with a clear message; a key with control characters never crashes the worker (Tasks 3, 5).
2. **A brand-new database: `snapshots: []`, a single snapshot, or every projection field `null`** → no crash; "No snapshots yet"; no change line for one snapshot; projection rows simply omitted (Tasks 1, 6).
3. **Zero or negative net worth (debts exceed assets)** → `-£1,500` formatting, every milestone shows `100y+` rather than a nonsense time, no crash (Tasks 2, 6).
4. **A currency the symbol table doesn't know (`CHF`, blank)** → `CHF 1,000`, or no symbol; never a crash or `null` text (Task 6).
5. **A rejected key or an old cache** → the last good data stays on screen with a "Key rejected" or "Out of date" hint; a corrupt or missing cache file loads as empty; a 200 that is really an HTML page (wrong URL) is a decoding error that leaves the cache alone (Tasks 3, 4, 6).

---

### Task 1: Gradle scaffold and API models

**Files:**
- Create: `net-worth-android/settings.gradle.kts`, `net-worth-android/build.gradle.kts`, `net-worth-android/gradle.properties`, `net-worth-android/gradle/libs.versions.toml`, `net-worth-android/.gitignore`
- Create: `net-worth-android/app/build.gradle.kts`, `net-worth-android/app/src/main/AndroidManifest.xml`, `net-worth-android/app/src/main/res/values/strings.xml`
- Create (copied): `net-worth-android/gradlew`, `net-worth-android/gradlew.bat`, `net-worth-android/gradle/wrapper/*`
- Create (git-ignored): `net-worth-android/local.properties`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/Models.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/ModelsTest.kt`
- Commit also: the spec and this plan under `docs/superpowers/`

**Interfaces:**
- Produces (`package com.isym444.networth.data`):
  - `val ApiJson: Json`
  - `@Serializable data class Snapshot(id: Long = 0, timestamp: String = "", currency: String = "GBP", grandTotal: Double = 0.0)`
  - `@Serializable data class HistoryResponse(snapshots: List<Snapshot> = emptyList(), expectedNetWorthAt40/50/60/65: Double? = null, expectedNetWorthWithContributionsAt40/50/60/65: Double? = null, estimatedNetWorth1y: Double? = null, monthlyContribution: Double? = null)`
  - `@Serializable data class CacheSnapshot(fetchedAt: Long = 0, history: HistoryResponse? = null)`; `fetchedAt` is epoch milliseconds, 0 = never.
- Produces: a Gradle project where `(cd net-worth-android && ./gradlew testDebugUnitTest assembleDebug)` succeeds; version-catalog aliases `libs.*` used by every later task.

- [ ] **Step 1: Confirm the Android SDK and JDK**

```bash
SDK=/opt/homebrew/share/android-commandlinetools
ls "$SDK/platforms" "$SDK/build-tools"
java -version 2>&1 | head -1
```
Expected: `platforms` lists `android-35` (or run `"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-35" "build-tools;35.0.0"` to add it); `java` reports 17 or newer. If `brew --prefix` is not `/opt/homebrew`, substitute it for `SDK` everywhere in this plan.

- [ ] **Step 2: Create the project skeleton and copy the Gradle wrapper**

```bash
CD="/Users/isym444/Desktop/Desktop - IanのMacBook Air/programming/countdown/android"
mkdir -p net-worth-android/gradle net-worth-android/app/src/main/res/values \
  net-worth-android/app/src/main/kotlin/com/isym444/networth/data \
  net-worth-android/app/src/test/kotlin/com/isym444/networth/data
cp "$CD/gradlew" "$CD/gradlew.bat" net-worth-android/
cp -R "$CD/gradle/wrapper" net-worth-android/gradle/
chmod +x net-worth-android/gradlew
printf 'sdk.dir=/opt/homebrew/share/android-commandlinetools\n' > net-worth-android/local.properties
```

- [ ] **Step 3: Write the Gradle and manifest files**

`net-worth-android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "net-worth-android"
include(":app")
```

`net-worth-android/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`net-worth-android/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`net-worth-android/gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
glance = "1.1.1"
work = "2.9.1"
okhttp = "4.12.0"
serialization = "1.7.3"
coroutines = "1.9.0"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
securityCrypto = "1.1.0-alpha06"
junit = "4.13.2"

[libraries]
glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

`net-worth-android/.gitignore`:
```
local.properties
keystore.properties
.gradle/
build/
app/build/
.idea/
captures/
*.jks
*.keystore
*.apk
```

`net-worth-android/app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.isym444.networth"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.isym444.networth"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.security.crypto)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
```

`net-worth-android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="@string/app_name"
        android:allowBackup="false"
        android:theme="@android:style/Theme.DeviceDefault.DayNight">
    </application>
</manifest>
```

`net-worth-android/app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Net Worth</string>
    <string name="widget_description">Net worth and time to milestones</string>
</resources>
```

- [ ] **Step 4: Write the failing test**

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/ModelsTest.kt`:
```kotlin
package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    // Shape of the real /api/v1/history response, including fields the widget ignores.
    private val realistic = """
        {"snapshots":[
          {"id":1,"timestamp":"2026-09-28T07:00:00Z","currency":"GBP","t212Total":100.5,"cryptoTotal":1.0,
           "chaseTotal":null,"amexDebtTotal":null,"amountLeft":null,"grandTotal":183000.5,
           "grandTotalEur":null,"aiCommentary":"Spending is up"},
          {"id":2,"timestamp":"2026-09-29T07:00:00Z","currency":"GBP","grandTotal":184320.25,
           "someFutureField":{"a":1}}],
         "expectedNetWorthAt40":900000.0,"expectedNetWorthAt50":1500000.0,
         "expectedNetWorthAt60":2500000.0,"expectedNetWorthAt65":3500000.0,
         "expectedNetWorthWithContributionsAt40":1000000.0,"expectedNetWorthWithContributionsAt50":1700000.0,
         "expectedNetWorthWithContributionsAt60":2900000.0,"expectedNetWorthWithContributionsAt65":4100000.0,
         "estimatedNetWorth1y":200000.0,"monthlyContribution":1000.0}
    """.trimIndent()

    @Test fun decodesRealisticResponseAndIgnoresUnknownKeys() {
        val h = ApiJson.decodeFromString<HistoryResponse>(realistic)
        assertEquals(2, h.snapshots.size)
        assertEquals(184320.25, h.snapshots.last().grandTotal, 0.0)
        assertEquals("GBP", h.snapshots.last().currency)
        assertEquals("2026-09-29T07:00:00Z", h.snapshots.last().timestamp)
        assertEquals(900000.0, h.expectedNetWorthAt40!!, 0.0)
        assertEquals(4100000.0, h.expectedNetWorthWithContributionsAt65!!, 0.0)
        assertEquals(200000.0, h.estimatedNetWorth1y!!, 0.0)
        assertEquals(1000.0, h.monthlyContribution!!, 0.0)
    }

    @Test fun nullOrMissingProjectionFieldsDecodeAsNull() {
        val h = ApiJson.decodeFromString<HistoryResponse>(
            """{"snapshots":[{"grandTotal":1.0}],"estimatedNetWorth1y":null,"monthlyContribution":null}""",
        )
        assertNull(h.expectedNetWorthAt40)
        assertNull(h.expectedNetWorthWithContributionsAt65)
        assertNull(h.estimatedNetWorth1y)
        assertNull(h.monthlyContribution)
        assertEquals("GBP", h.snapshots.single().currency)   // missing currency falls back to the default
    }

    @Test fun brandNewDatabaseDecodes() {
        assertTrue(ApiJson.decodeFromString<HistoryResponse>("""{"snapshots":[]}""").snapshots.isEmpty())
        assertTrue(ApiJson.decodeFromString<HistoryResponse>("{}").snapshots.isEmpty())
    }

    @Test fun cacheSnapshotRoundTrips() {
        val original = CacheSnapshot(
            fetchedAt = 1_790_000_000_000,
            history = HistoryResponse(
                snapshots = listOf(Snapshot(id = 2, timestamp = "t", currency = "EUR", grandTotal = 5.5)),
                estimatedNetWorth1y = 9.0,
            ),
        )
        val json = ApiJson.encodeToString(CacheSnapshot.serializer(), original)
        assertEquals(original, ApiJson.decodeFromString<CacheSnapshot>(json))
    }

    @Test fun emptyCacheHasNoHistory() {
        val c = CacheSnapshot()
        assertEquals(0L, c.fetchedAt)
        assertNull(c.history)
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.data.ModelsTest')`
Expected: FAIL: compilation error `Unresolved reference: ApiJson` (the first run also downloads Gradle and dependencies, which takes a few minutes).

- [ ] **Step 6: Write the implementation**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/Models.kt`:
```kotlin
package com.isym444.networth.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lenient on input (unknown keys, null for a defaulted field -> default), complete on output. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** The few fields the widget reads from a `NetWorthSnapshot`; everything else is ignored on decode. */
@Serializable
data class Snapshot(
    val id: Long = 0,
    val timestamp: String = "",
    val currency: String = "GBP",
    val grandTotal: Double = 0.0,
)

/** Mirrors `NetWorthHistoryResponse` (net-worth-service). Every projection is nullable. */
@Serializable
data class HistoryResponse(
    val snapshots: List<Snapshot> = emptyList(),          // oldest first
    val expectedNetWorthAt40: Double? = null,
    val expectedNetWorthAt50: Double? = null,
    val expectedNetWorthAt60: Double? = null,
    val expectedNetWorthAt65: Double? = null,
    val expectedNetWorthWithContributionsAt40: Double? = null,
    val expectedNetWorthWithContributionsAt50: Double? = null,
    val expectedNetWorthWithContributionsAt60: Double? = null,
    val expectedNetWorthWithContributionsAt65: Double? = null,
    val estimatedNetWorth1y: Double? = null,
    val monthlyContribution: Double? = null,
)

/** What the widget renders from; persisted by HistoryCache. `fetchedAt` is epoch milliseconds (0 = never). */
@Serializable
data class CacheSnapshot(
    val fetchedAt: Long = 0,
    val history: HistoryResponse? = null,
)
```

- [ ] **Step 7: Run the tests to verify they pass, and that the app assembles**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest assembleDebug)`
Expected: PASS (5 tests) and `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/specs/2026-09-30-net-worth-android-widget-design.md \
        docs/superpowers/plans/2026-09-30-net-worth-android-widget.md \
        net-worth-android
git status --short   # confirm local.properties and build/ are NOT listed
git commit -m "$(cat <<'EOF'
android: scaffold net-worth-android and add API models

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 2: Milestone projection maths

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/ProjectionConfig.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/Projection.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/ProjectionTest.kt`

**Interfaces:**
- Produces (`package com.isym444.networth.domain`):
  - `object ProjectionConfig { const val ANNUAL_RETURN: Double; const val MAX_MONTHS: Int; val MILESTONES: List<Double>; val BIRTH_DATE: LocalDate }`
  - `sealed interface Reach { data class Reached(val age: Int); data class In(val months: Int, val age: Int); data object Never; fun text(): String }`
  - `object Projection { fun monthsToReach(current: Double, target: Double, monthlyContribution: Double): Int?; fun reach(current: Double, target: Double, monthlyContribution: Double, today: LocalDate): Reach; fun ageAfterMonths(today: LocalDate, months: Int): Int }`
  - `monthsToReach` returns `0` if already reached, `null` if never within `MAX_MONTHS`.

The expected values below were produced by running the Angular function verbatim in Node with "now" fixed at 2026‑09‑30 (they are not guesses).

- [ ] **Step 1: Write the failing test**

`net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/ProjectionTest.kt`:
```kotlin
package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProjectionTest {
    private val today = LocalDate.of(2026, 9, 30)

    private fun reach(current: Double, target: Double, contribution: Double) =
        Projection.reach(current, target, contribution, today)

    @Test fun matchesDashboardWithoutContributions() {
        assertEquals(Reach.In(123, 40), reach(50_000.0, 100_000.0, 0.0))
        assertEquals("10y 3m (age 40)", reach(50_000.0, 100_000.0, 0.0).text())
        assertEquals("4y 7m (age 34)", reach(184_320.0, 250_000.0, 0.0).text())
        assertEquals("25y 0m (age 55)", reach(184_320.0, 1_000_000.0, 0.0).text())
    }

    @Test fun matchesDashboardWithContributions() {
        assertEquals(Reach.In(36, 33), reach(50_000.0, 100_000.0, 1_000.0))
        assertEquals("3y 0m (age 33)", reach(50_000.0, 100_000.0, 1_000.0).text())
        assertEquals("2y 6m (age 32)", reach(184_320.0, 250_000.0, 1_000.0).text())
        assertEquals("15y 4m (age 45)", reach(184_320.0, 1_000_000.0, 1_500.0).text())
    }

    @Test fun alreadyReachedIncludesEqualAndAbove() {
        assertEquals(Reach.Reached(30), reach(100_000.0, 100_000.0, 0.0))
        assertEquals("Reached (age 30)", reach(250_000.0, 100_000.0, 0.0).text())
    }

    @Test fun justUnderTheTargetTakesOneMonth() {
        assertEquals("0y 1m (age 30)", reach(99_999.99, 100_000.0, 0.0).text())
    }

    @Test fun neverWhenNotReachableWithinTheCap() {
        assertEquals(Reach.Never, reach(1_000.0, 1_000_000.0, 0.0))
        assertEquals("100y+", reach(1_000.0, 1_000_000.0, 0.0).text())
        assertEquals("100y+", reach(0.0, 100_000.0, 0.0).text())
        assertNull(Projection.monthsToReach(0.0, 100_000.0, 0.0))
    }

    @Test fun negativeNetWorthWithNoContributionNeverReaches() {
        assertEquals(Reach.Never, reach(-5_000.0, 100_000.0, 0.0))
    }

    @Test fun negativeNetWorthWithAContributionEventuallyReaches() {
        val r = reach(-5_000.0, 100_000.0, 1_000.0)
        assertTrue("expected In, was $r", r is Reach.In)
    }

    @Test fun monthsToReachIsZeroWhenAlreadyThere() {
        assertEquals(0, Projection.monthsToReach(100_000.0, 100_000.0, 0.0))
    }

    @Test fun ageRollsOverOnTheBirthday() {
        // Birthday is 14 June: the day before is still 29, the day itself is 30.
        assertEquals(29, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 0))
        assertEquals(30, Projection.ageAfterMonths(LocalDate.of(2026, 6, 14), 0))
        assertEquals(30, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 1))    // 13 Jul 2026
        assertEquals(30, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 12))   // 13 Jun 2027, day before the 31st birthday
        assertEquals(31, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 13))   // 13 Jul 2027
    }

    @Test fun monthEndClampDoesNotChangeTheAge() {
        // JS setMonth overflows (31 Jan + 1 month = 3 Mar); java.time clamps to 28 Feb. Both are age 29.
        assertEquals(29, Projection.ageAfterMonths(LocalDate.of(2026, 1, 31), 1))
    }

    @Test fun milestoneListMatchesTheDashboard() {
        assertEquals(listOf(100_000.0, 250_000.0, 500_000.0, 750_000.0, 1_000_000.0), ProjectionConfig.MILESTONES)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.domain.ProjectionTest')`
Expected: FAIL: `Unresolved reference: Projection`.

- [ ] **Step 3: Write the implementation**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/ProjectionConfig.kt`:
```kotlin
package com.isym444.networth.domain

import java.time.LocalDate

/**
 * Projection assumptions. These duplicate values that also live elsewhere; change all copies together:
 *  - net-worth-frontend/src/app/dashboard.component.ts: assumedAnnualReturn, netWorthMilestones, birthDate, maxProjectionMonths
 *  - net-worth-service PortfolioProjectionService.ANNUAL_GROWTH_RATE and NetWorthHistoryService.BIRTH_DATE
 */
object ProjectionConfig {
    const val ANNUAL_RETURN = 0.07
    const val MAX_MONTHS = 1200
    val MILESTONES: List<Double> = listOf(100_000.0, 250_000.0, 500_000.0, 750_000.0, 1_000_000.0)
    val BIRTH_DATE: LocalDate = LocalDate.of(1996, 6, 14)
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/Projection.kt`:
```kotlin
package com.isym444.networth.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

sealed interface Reach {
    data class Reached(val age: Int) : Reach
    data class In(val months: Int, val age: Int) : Reach
    data object Never : Reach

    /** Same wording as the dashboard: "Reached (age 30)", "4y 7m (age 34)", "100y+". */
    fun text(): String = when (this) {
        is Reached -> "Reached (age $age)"
        is In -> "${months / 12}y ${months % 12}m (age $age)"
        Never -> "100y+"
    }
}

/** Pure port of `timeToReachPortfolio` (dashboard.component.ts). */
object Projection {
    /** 0 if `current >= target`; null if not reached within [ProjectionConfig.MAX_MONTHS]. */
    fun monthsToReach(current: Double, target: Double, monthlyContribution: Double): Int? {
        if (current >= target) return 0
        val growth = (1 + ProjectionConfig.ANNUAL_RETURN).pow(1.0 / 12)
        var balance = current
        var months = 0
        while (balance < target && months < ProjectionConfig.MAX_MONTHS) {
            balance = balance * growth + monthlyContribution
            months++
        }
        return if (balance >= target) months else null
    }

    fun reach(current: Double, target: Double, monthlyContribution: Double, today: LocalDate): Reach {
        val months = monthsToReach(current, target, monthlyContribution) ?: return Reach.Never
        val age = ageAfterMonths(today, months)
        return if (months == 0) Reach.Reached(age) else Reach.In(months, age)
    }

    fun ageAfterMonths(today: LocalDate, months: Int): Int =
        ChronoUnit.YEARS.between(ProjectionConfig.BIRTH_DATE, today.plusMonths(months.toLong())).toInt()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.domain.ProjectionTest')`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add net-worth-android
git commit -m "$(cat <<'EOF'
android: port dashboard milestone projection maths

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 3: UrlPolicy and ApiClient

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/UrlPolicy.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/ApiClient.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/UrlPolicyTest.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/ApiClientTest.kt`

**Interfaces:**
- Consumes: `HistoryResponse`, `ApiJson` (Task 1).
- Produces (`package com.isym444.networth.data`):
  - `object UrlPolicy { fun parse(input: String): HttpUrl? }`
  - `interface HistorySource { suspend fun history(): HistoryResponse }`
  - `sealed class ApiError(message: String, val userMessage: String, cause: Throwable?) : Exception` with subclasses `NotConfigured()`, `Http(status: Int, detail: String)`, `Network(cause: Throwable)`, `Decoding()`, `Storage(cause: Throwable)`
  - `val ApiError.rejectsKey: Boolean` (true for `Http` 401/403)
  - `class ApiClient(baseUrl: HttpUrl, apiKey: String, http: OkHttpClient = DEFAULT_HTTP) : HistorySource`

- [ ] **Step 1: Write the failing tests**

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/UrlPolicyTest.kt`:
```kotlin
package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPolicyTest {
    @Test fun addsHttpsWhenSchemeIsMissing() =
        assertEquals("https://nwapi.isym.uk/", UrlPolicy.parse("nwapi.isym.uk")?.toString())

    @Test fun trimsWhitespace() =
        assertEquals("https://nwapi.isym.uk/", UrlPolicy.parse("  https://nwapi.isym.uk/\n")?.toString())

    @Test fun keepsAPathPrefix() =
        assertEquals("https://example.com/nw/", UrlPolicy.parse("https://example.com/nw/")?.toString())

    @Test fun rejectsPlainHttpToARealHost() = assertNull(UrlPolicy.parse("http://nwapi.isym.uk"))

    @Test fun allowsPlainHttpToLocalDevHosts() {
        assertEquals("http://10.0.2.2:8080/", UrlPolicy.parse("http://10.0.2.2:8080")?.toString())
        assertEquals("http://localhost:8080/", UrlPolicy.parse("http://localhost:8080")?.toString())
    }

    @Test fun rejectsBlankAndGarbage() {
        assertNull(UrlPolicy.parse(""))
        assertNull(UrlPolicy.parse("   "))
        assertNull(UrlPolicy.parse("https://"))
    }
}
```

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/ApiClientTest.kt`:
```kotlin
package com.isym444.networth.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(
        key: String = "secret",
        base: HttpUrl = server.url("/"),
        http: OkHttpClient = ApiClient.DEFAULT_HTTP,
    ) = ApiClient(base, key, http)

    private val body = """{"snapshots":[{"grandTotal":184320.25,"currency":"GBP"}],"monthlyContribution":1000.0}"""

    @Test fun sendsTheKeyInXApiKeyAndHitsTheHistoryPath() {
        server.enqueue(MockResponse().setBody(body))
        runBlocking { client(key = "  secret\n").history() }
        val req = server.takeRequest()
        assertEquals("/api/v1/history", req.path)
        assertEquals("secret", req.getHeader("X-Api-Key"))       // trimmed
        assertNull(req.getHeader("Authorization"))               // this backend does not use Bearer
    }

    @Test fun decodesA200() {
        server.enqueue(MockResponse().setBody(body))
        val h = runBlocking { client().history() }
        assertEquals(184320.25, h.snapshots.single().grandTotal, 0.0)
        assertEquals(1000.0, h.monthlyContribution!!, 0.0)
    }

    @Test fun keepsAPathPrefixOnTheBaseUrl() {
        server.enqueue(MockResponse().setBody(body))
        runBlocking { client(base = server.url("/nw/")).history() }
        assertEquals("/nw/api/v1/history", server.takeRequest().path)
    }

    @Test fun a401IsAKeyRejection() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Missing or invalid X-Api-Key header"}"""))
        val e = assertThrows(ApiError.Http::class.java) { runBlocking { client().history() } }
        assertEquals(401, e.status)
        assertEquals("Missing or invalid X-Api-Key header", e.detail)
        assertTrue(e.userMessage.contains("rejected"))
        assertTrue(e.rejectsKey)
    }

    @Test fun a500CarriesTheSpringMessageButDoesNotRejectTheKey() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        val e = assertThrows(ApiError.Http::class.java) { runBlocking { client().history() } }
        assertEquals("boom", e.detail)
        assertFalse(e.rejectsKey)
    }

    @Test fun anHtmlPageWithA200IsADecodingError() {
        server.enqueue(MockResponse().setBody("<html><body>Welcome</body></html>"))
        assertThrows(ApiError.Decoding::class.java) { runBlocking { client().history() } }
    }

    @Test fun aTimeoutIsANetworkError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        val e = assertThrows(ApiError.Network::class.java) { runBlocking { client(http = http).history() } }
        assertFalse(e.rejectsKey)
    }

    @Test fun aKeyWithControlCharactersIsNotConfiguredNotACrash() {
        assertThrows(ApiError.NotConfigured::class.java) { runBlocking { client(key = "ab\u0001cd").history() } }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.data.UrlPolicyTest' --tests 'com.isym444.networth.data.ApiClientTest')`
Expected: FAIL: `Unresolved reference: UrlPolicy` / `ApiClient`.

- [ ] **Step 3: Write the implementation**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/UrlPolicy.kt`:
```kotlin
package com.isym444.networth.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalises a user-typed URL. HTTPS is required except for local dev hosts. */
object UrlPolicy {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun parse(input: String): HttpUrl? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        if (url.scheme == "http" && url.host !in LOCAL_HOSTS) return null
        return url
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/ApiClient.kt`:
```kotlin
package com.isym444.networth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiError(message: String, val userMessage: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : ApiError("API URL or key not set", "Enter the API URL and access key first")

    class Http(val status: Int, val detail: String) : ApiError(
        if (detail.isEmpty()) "HTTP $status" else "HTTP $status: $detail",
        when (status) {
            401, 403 -> "Access key rejected (HTTP $status) — check the dashboard access key"
            else -> "Server error (HTTP $status)"
        },
    )

    class Network(cause: Throwable) : ApiError(
        cause.message ?: "Network error",
        "Can't reach the server — check the URL and your connection",
        cause,
    )

    class Decoding : ApiError("Unexpected response", "Unexpected response — is this the Net Worth API URL?")

    class Storage(cause: Throwable) : ApiError(
        cause.message ?: "Storage error",
        "Couldn't save data on this device — is storage full?",
        cause,
    )
}

/** True when the server said the key is wrong (as opposed to being down or unreachable). */
val ApiError.rejectsKey: Boolean get() = this is ApiError.Http && (status == 401 || status == 403)

/** What [Sync] needs from the network; lets tests substitute a fake. */
interface HistorySource {
    suspend fun history(): HistoryResponse
}

/**
 * Talks to net-worth-service. Deliberately has no logging interceptor: the
 * X-Api-Key header must never reach a log.
 */
class ApiClient(
    private val baseUrl: HttpUrl,
    apiKey: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
) : HistorySource {
    private val key = apiKey.trim()

    override suspend fun history(): HistoryResponse {
        val url = baseUrl.newBuilder().addPathSegments("api/v1/history").build()
        return call(request(url)) { decode(it) }
    }

    private fun request(url: HttpUrl): Request {
        val b = Request.Builder().url(url).get()
        try {
            b.header("X-Api-Key", key)
        } catch (e: IllegalArgumentException) {          // control characters in a pasted key
            throw ApiError.NotConfigured()
        }
        return b.build()
    }

    private suspend fun <T> call(req: Request, parse: (Response) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiError.Http(resp.code, errorDetail(resp))
                parse(resp)
            }
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
    }

    private fun decode(resp: Response): HistoryResponse =
        try {
            ApiJson.decodeFromString<HistoryResponse>(resp.body?.string().orEmpty())
        } catch (e: SerializationException) {
            throw ApiError.Decoding()
        } catch (e: IllegalArgumentException) {
            throw ApiError.Decoding()
        }

    /** The auth filter answers `{"error": ...}`; Spring's default error body uses `message`. */
    private fun errorDetail(resp: Response): String = runCatching {
        val obj = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
        (obj["message"] ?: obj["error"])?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")

    companion object {
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.data.UrlPolicyTest' --tests 'com.isym444.networth.data.ApiClientTest')`
Expected: PASS (6 + 8 tests).

- [ ] **Step 5: Commit**

```bash
git add net-worth-android
git commit -m "$(cat <<'EOF'
android: add UrlPolicy and X-Api-Key history client

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 4: HistoryCache and Sync

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/HistoryCache.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/Sync.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/HistoryCacheTest.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/SyncTest.kt`

**Interfaces:**
- Consumes: `CacheSnapshot`, `HistoryResponse`, `Snapshot`, `ApiJson` (Task 1); `HistorySource`, `ApiError` (Task 3).
- Produces:
  - `class HistoryCache(file: File) { fun load(): CacheSnapshot; fun save(snapshot: CacheSnapshot); companion fun forContext(context: Context): HistoryCache }`
  - `sealed interface SyncResult { data object Updated; data class Failed(val error: ApiError) }`
  - `class Sync(cache: HistoryCache, source: () -> HistorySource?, clock: () -> Long = System::currentTimeMillis, onResult: (SyncResult, Long) -> Unit = { _, _ -> }) { suspend fun refresh(): SyncResult; companion const val KEEP_SNAPSHOTS = 2 }`

- [ ] **Step 1: Write the failing tests**

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/HistoryCacheTest.kt`:
```kotlin
package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun snap(total: Double) = CacheSnapshot(
        fetchedAt = 42,
        history = HistoryResponse(snapshots = listOf(Snapshot(grandTotal = total))),
    )

    @Test fun missingFileLoadsAsEmpty() {
        val c = HistoryCache(File(tmp.root, "nope.json")).load()
        assertEquals(0L, c.fetchedAt)
        assertNull(c.history)
    }

    @Test fun corruptFileLoadsAsEmptyInsteadOfThrowing() {
        val f = tmp.newFile("cache.json").apply { writeText("{ this is not json") }
        assertNull(HistoryCache(f).load().history)
    }

    @Test fun roundTrips() {
        val cache = HistoryCache(File(tmp.root, "cache.json"))
        cache.save(snap(123.0))
        assertEquals(snap(123.0), cache.load())
    }

    @Test fun saveOverwritesAndLeavesNoTempFiles() {
        val cache = HistoryCache(File(tmp.root, "cache.json"))
        cache.save(snap(1.0))
        cache.save(snap(2.0))
        assertEquals(2.0, cache.load().history!!.snapshots.single().grandTotal, 0.0)
        assertEquals(listOf("cache.json"), tmp.root.list()!!.sorted())
    }

    @Test fun createsMissingParentDirectories() {
        val cache = HistoryCache(File(tmp.root, "a/b/cache.json"))
        cache.save(snap(5.0))
        assertTrue(File(tmp.root, "a/b/cache.json").exists())
    }
}
```

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/SyncTest.kt`:
```kotlin
package com.isym444.networth.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = HistoryCache(File(tmp.root, "cache.json"))

    private fun source(block: () -> HistoryResponse) = object : HistorySource {
        override suspend fun history() = block()
    }

    private fun history(vararg totals: Double) = HistoryResponse(
        snapshots = totals.mapIndexed { i, t -> Snapshot(id = i.toLong(), grandTotal = t) },
        estimatedNetWorth1y = 9.0,
        monthlyContribution = 1000.0,
    )

    @Test fun successKeepsOnlyTheLastTwoSnapshotsAndStampsFetchTime() {
        val cache = cache()
        val results = mutableListOf<Pair<SyncResult, Long>>()
        val sync = Sync(cache, { source { history(1.0, 2.0, 3.0, 4.0) } }, clock = { 777L }, onResult = { r, t -> results += r to t })

        assertEquals(SyncResult.Updated, runBlocking { sync.refresh() })

        val saved = cache.load()
        assertEquals(777L, saved.fetchedAt)
        assertEquals(listOf(3.0, 4.0), saved.history!!.snapshots.map { it.grandTotal })
        assertEquals(9.0, saved.history!!.estimatedNetWorth1y!!, 0.0)          // projections are preserved
        assertEquals(listOf<Pair<SyncResult, Long>>(SyncResult.Updated to 777L), results)
    }

    @Test fun anEmptyResponseReplacesTheCache() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        runBlocking { Sync(cache, { source { history() } }, clock = { 2L }).refresh() }
        assertTrue(cache.load().history!!.snapshots.isEmpty())
        assertEquals(2L, cache.load().fetchedAt)
    }

    @Test fun aRejectedKeyLeavesTheCacheExactlyAsItWas() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        val before = File(tmp.root, "cache.json").readText()
        val result = runBlocking { Sync(cache, { source { throw ApiError.Http(401, "no") } }).refresh() }
        assertTrue(result is SyncResult.Failed && result.error.rejectsKey)
        assertEquals(before, File(tmp.root, "cache.json").readText())
    }

    @Test fun aNetworkFailureLeavesTheCacheAlone() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        val result = runBlocking { Sync(cache, { source { throw ApiError.Network(java.io.IOException("offline")) } }).refresh() }
        assertTrue(result is SyncResult.Failed)
        assertEquals(1L, cache.load().fetchedAt)
    }

    @Test fun aDecodingFailureLeavesTheCacheAlone() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        runBlocking { Sync(cache, { source { throw ApiError.Decoding() } }).refresh() }
        assertEquals(5.0, cache.load().history!!.snapshots.single().grandTotal, 0.0)
    }

    @Test fun noSourceMeansNotConfigured() {
        val cache = cache()
        val result = runBlocking { Sync(cache, { null }).refresh() }
        assertTrue(result is SyncResult.Failed && result.error is ApiError.NotConfigured)
        assertNull(cache.load().history)
    }

    @Test fun aStorageFailureIsReportedNotThrown() {
        val blocker = tmp.newFile("blocker")                       // a file where a directory is needed
        val broken = HistoryCache(File(blocker, "cache.json"))
        val result = runBlocking { Sync(broken, { source { history(1.0) } }).refresh() }
        assertTrue(result is SyncResult.Failed && result.error is ApiError.Storage)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.data.HistoryCacheTest' --tests 'com.isym444.networth.data.SyncTest')`
Expected: FAIL: `Unresolved reference: HistoryCache` / `Sync`.

- [ ] **Step 3: Write the implementation**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/HistoryCache.kt`:
```kotlin
package com.isym444.networth.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON file holding the last good API response. Writes are atomic (temp file + rename) so the
 * widget never reads a half-written cache, and a missing or corrupt file loads as empty instead of throwing.
 */
class HistoryCache(private val file: File) {
    fun load(): CacheSnapshot =
        runCatching { ApiJson.decodeFromString<CacheSnapshot>(file.readText()) }.getOrDefault(CacheSnapshot())

    fun save(snapshot: CacheSnapshot) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("cache file has no parent directory")
        dir.mkdirs()
        // A unique temp file per call: the hourly worker and "Save & test" can save at the same moment.
        val tmp = File.createTempFile("cache", ".tmp", dir)
        try {
            tmp.writeText(ApiJson.encodeToString(CacheSnapshot.serializer(), snapshot))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()        // no-op after a successful move
        }
    }

    companion object {
        fun forContext(context: Context) = HistoryCache(File(context.filesDir, "cache.json"))
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/Sync.kt`:
```kotlin
package com.isym444.networth.data

import java.io.IOException

sealed interface SyncResult {
    data object Updated : SyncResult
    data class Failed(val error: ApiError) : SyncResult
}

/**
 * Refreshes the cache from the API. On *any* failure the cache is left exactly as it was,
 * so the widget keeps rendering the last good data.
 */
class Sync(
    private val cache: HistoryCache,
    private val source: () -> HistorySource?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onResult: (SyncResult, Long) -> Unit = { _, _ -> },
) {
    suspend fun refresh(): SyncResult {
        val result = attempt()
        onResult(result, clock())
        return result
    }

    private suspend fun attempt(): SyncResult {
        val api = source() ?: return SyncResult.Failed(ApiError.NotConfigured())
        return try {
            val fresh = api.history()
            // /history returns every daily snapshot; the widget only needs the latest two (headline + change).
            val trimmed = fresh.copy(snapshots = fresh.snapshots.takeLast(KEEP_SNAPSHOTS))
            cache.save(CacheSnapshot(fetchedAt = clock(), history = trimmed))
            SyncResult.Updated
        } catch (e: ApiError) {
            SyncResult.Failed(e)
        } catch (e: IOException) {          // only the cache write can throw this (ApiClient maps its own I/O errors)
            SyncResult.Failed(ApiError.Storage(e))
        }
    }

    companion object {
        const val KEEP_SNAPSHOTS = 2
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.data.HistoryCacheTest' --tests 'com.isym444.networth.data.SyncTest')`
Expected: PASS (5 + 7 tests).

- [ ] **Step 5: Commit**

```bash
git add net-worth-android
git commit -m "$(cat <<'EOF'
android: add atomic history cache and failure-safe sync

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 5: Settings model, validator, encrypted store and AppGraph

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/AppSettings.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/OpenWithRecovery.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/SettingsStore.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/data/AppGraph.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/SettingsValidatorTest.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/data/OpenWithRecoveryTest.kt`

**Interfaces:**
- Consumes: `UrlPolicy`, `ApiClient`, `HistorySource`, `ApiError`, `rejectsKey` (Task 3); `HistoryCache`, `Sync`, `SyncResult` (Task 4).
- Produces:
  - `data class AppSettings(baseUrl: String = "", apiKey: String = "", webUrl: String = "") { val isConfigured: Boolean; companion const val DEFAULT_BASE_URL = "https://nwapi.isym.uk" }`
  - `data class SyncStatus(lastSuccessAt: Long, lastError: String?, keyRejected: Boolean)`
  - `enum class Field { BASE_URL, API_KEY, WEB_URL }`; `sealed interface Validation { Valid(settings: AppSettings); Invalid(errors: Map<Field, String>) }`; `object SettingsValidator { fun validate(baseUrl: String, apiKey: String, webUrl: String): Validation }`
  - `fun <T : Any> openWithRecovery(create: () -> T, reset: () -> Unit, pause: () -> Unit = …): T?`
  - `class SettingsStore(context: Context) { fun load(): AppSettings; fun save(s: AppSettings); fun status(): SyncStatus; fun recordSync(result: SyncResult, at: Long) }`
  - `class AppGraph { val settings: SettingsStore; val cache: HistoryCache; fun sync(): Sync; companion fun get(context: Context): AppGraph; fun sourceFor(s: AppSettings): HistorySource? }`

- [ ] **Step 1: Write the failing tests**

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/SettingsValidatorTest.kt`:
```kotlin
package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {
    private fun valid(base: String = "https://nwapi.isym.uk", key: String = "abc123", web: String = ""): AppSettings {
        val v = SettingsValidator.validate(base, key, web)
        assertTrue("expected Valid, was $v", v is Validation.Valid)
        return (v as Validation.Valid).settings
    }

    private fun errors(base: String = "https://nwapi.isym.uk", key: String = "abc123", web: String = ""): Set<Field> {
        val v = SettingsValidator.validate(base, key, web)
        assertTrue("expected Invalid, was $v", v is Validation.Invalid)
        return (v as Validation.Invalid).errors.keys
    }

    @Test fun acceptsAValidTriple() {
        val s = valid(web = "https://net-worth.example.com")
        assertEquals("https://nwapi.isym.uk/", s.baseUrl)
        assertEquals("abc123", s.apiKey)
        assertEquals("https://net-worth.example.com/", s.webUrl)
        assertTrue(s.isConfigured)
    }

    @Test fun trimsAPastedKeyWithATrailingNewline() = assertEquals("abc123", valid(key = "  abc123\n").apiKey)

    @Test fun rejectsInnerWhitespaceInTheKey() = assertEquals(setOf(Field.API_KEY), errors(key = "abc 123"))

    @Test fun rejectsAnEmptyKey() = assertEquals(setOf(Field.API_KEY), errors(key = "  "))

    @Test fun acceptsKeysWithoutAPrefix() = assertEquals("plainsecret", valid(key = "plainsecret").apiKey)

    @Test fun normalisesAMissingScheme() = assertEquals("https://nwapi.isym.uk/", valid(base = "nwapi.isym.uk").baseUrl)

    @Test fun rejectsPlainHttpBaseUrl() = assertEquals(setOf(Field.BASE_URL), errors(base = "http://nwapi.isym.uk"))

    @Test fun rejectsABlankBaseUrl() = assertEquals(setOf(Field.BASE_URL), errors(base = ""))

    @Test fun webUrlIsOptional() = assertEquals("", valid(web = "  ").webUrl)

    @Test fun rejectsABadWebUrl() = assertEquals(setOf(Field.WEB_URL), errors(web = "http://insecure.example.com"))

    @Test fun reportsEveryBadFieldAtOnce() =
        assertEquals(setOf(Field.BASE_URL, Field.API_KEY, Field.WEB_URL), errors(base = "", key = "", web = "http://x.com"))

    @Test fun isConfiguredNeedsBothUrlAndKey() {
        assertFalse(AppSettings(baseUrl = "https://x/", apiKey = "").isConfigured)
        assertFalse(AppSettings(baseUrl = "", apiKey = "k").isConfigured)
        assertTrue(AppSettings(baseUrl = "https://x/", apiKey = "k").isConfigured)
    }
}
```

`net-worth-android/app/src/test/kotlin/com/isym444/networth/data/OpenWithRecoveryTest.kt`:
```kotlin
package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenWithRecoveryTest {
    @Test fun succeedsFirstTimeWithoutPausingOrResetting() {
        var pauses = 0
        var resets = 0
        assertEquals("ok", openWithRecovery(create = { "ok" }, reset = { resets++ }, pause = { pauses++ }))
        assertEquals(0, pauses)
        assertEquals(0, resets)
    }

    @Test fun aTransientFailureIsRetriedAfterAPauseWithoutResetting() {
        var calls = 0
        var pauses = 0
        var resets = 0
        val v = openWithRecovery(
            create = { if (calls++ == 0) throw IllegalStateException("keystore hiccup") else "ok" },
            reset = { resets++ },
            pause = { pauses++ },
        )
        assertEquals("ok", v)
        assertEquals(1, pauses)
        assertEquals(0, resets)
    }

    @Test fun aPersistentFailureResetsThenTriesOnceMore() {
        var calls = 0
        var resets = 0
        val v = openWithRecovery(
            create = { if (calls++ < 2) throw IllegalStateException("corrupt") else "fresh" },
            reset = { resets++ },
            pause = {},
        )
        assertEquals("fresh", v)
        assertEquals(1, resets)
    }

    @Test fun returnsNullWhenEvenTheResetAttemptFails() {
        var resets = 0
        val v = openWithRecovery<String>(create = { throw IllegalStateException("dead") }, reset = { resets++ }, pause = {})
        assertNull(v)
        assertEquals(1, resets)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.data.SettingsValidatorTest' --tests 'com.isym444.networth.data.OpenWithRecoveryTest')`
Expected: FAIL: `Unresolved reference: SettingsValidator` / `openWithRecovery`.

- [ ] **Step 3: Write the implementation**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/AppSettings.kt`:
```kotlin
package com.isym444.networth.data

data class AppSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val webUrl: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://nwapi.isym.uk"
    }
}

/** Outcome of the last sync attempt, for the widget's hint line and the settings screen. */
data class SyncStatus(val lastSuccessAt: Long, val lastError: String?, val keyRejected: Boolean)

enum class Field { BASE_URL, API_KEY, WEB_URL }

sealed interface Validation {
    data class Valid(val settings: AppSettings) : Validation
    data class Invalid(val errors: Map<Field, String>) : Validation
}

object SettingsValidator {
    fun validate(baseUrl: String, apiKey: String, webUrl: String): Validation {
        val errors = linkedMapOf<Field, String>()

        val base = UrlPolicy.parse(baseUrl)
        if (base == null) errors[Field.BASE_URL] = "Enter a valid address. https is required (for example https://nwapi.example.com)."

        val key = apiKey.trim()
        when {
            key.isEmpty() -> errors[Field.API_KEY] = "Paste the dashboard access key."
            key.any { it.isWhitespace() } -> errors[Field.API_KEY] = "The key must not contain spaces."
        }

        val web = webUrl.trim()
        val webParsed = if (web.isEmpty()) null else UrlPolicy.parse(web)
        if (web.isNotEmpty() && webParsed == null) errors[Field.WEB_URL] = "Enter a valid https address, or leave blank."

        if (errors.isNotEmpty() || base == null) return Validation.Invalid(errors)
        return Validation.Valid(AppSettings(base.toString(), key, webParsed?.toString().orEmpty()))
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/OpenWithRecovery.kt`:
```kotlin
package com.isym444.networth.data

/**
 * Opens a fragile store (Keystore-backed encrypted preferences). Keystore trouble on OEM ROMs is often
 * transient, so: try, pause and try once more, and only then reset (which deletes saved data) and try a
 * final time. If even that fails, return null so callers degrade instead of crashing the widget.
 */
fun <T : Any> openWithRecovery(create: () -> T, reset: () -> Unit, pause: () -> Unit = { Thread.sleep(150) }): T? {
    try { return create() } catch (e: Exception) { /* maybe transient */ }
    pause()
    try { return create() } catch (e: Exception) { /* persistent */ }
    reset()
    return try { create() } catch (e: Exception) { null }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/SettingsStore.kt`:
```kotlin
package com.isym444.networth.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings + last sync outcome in Keystore-backed encrypted preferences (the access key never sits in plain text). */
class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences? by lazy {
        openWithRecovery(create = ::create, reset = { context.deleteSharedPreferences(FILE) })
    }

    /** Only used if encrypted storage is unavailable: settings last for this process instead of crashing. */
    @Volatile private var inMemory = AppSettings()

    fun load(): AppSettings {
        val p = prefs ?: return inMemory
        return AppSettings(
            baseUrl = p.getString(K_URL, null).orEmpty(),
            apiKey = p.getString(K_KEY, null).orEmpty(),
            webUrl = p.getString(K_WEB, null).orEmpty(),
        )
    }

    fun save(s: AppSettings) {
        val p = prefs
        if (p == null) { inMemory = s; return }
        p.edit().putString(K_URL, s.baseUrl).putString(K_KEY, s.apiKey).putString(K_WEB, s.webUrl).apply()
    }

    fun status(): SyncStatus {
        val p = prefs ?: return SyncStatus(0, null, false)
        return SyncStatus(p.getLong(K_OK, 0), p.getString(K_ERR, null), p.getBoolean(K_REJECTED, false))
    }

    fun recordSync(result: SyncResult, at: Long) {
        val e = (prefs ?: return).edit()
        when (result) {
            is SyncResult.Failed -> e.putString(K_ERR, result.error.userMessage).putBoolean(K_REJECTED, result.error.rejectsKey)
            SyncResult.Updated -> e.putLong(K_OK, at).remove(K_ERR).remove(K_REJECTED)
        }
        e.apply()
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context, FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val FILE = "networth_settings"
        const val K_URL = "base_url"; const val K_KEY = "api_key"; const val K_WEB = "web_url"
        const val K_OK = "last_ok"; const val K_ERR = "last_err"; const val K_REJECTED = "key_rejected"
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/data/AppGraph.kt`:
```kotlin
package com.isym444.networth.data

import android.content.Context

/** Process-wide wiring shared by the widget, the workers and the screens. */
class AppGraph private constructor(context: Context) {
    val settings = SettingsStore(context)
    val cache = HistoryCache.forContext(context)

    fun sync(): Sync = Sync(cache, source = { sourceFor(settings.load()) }, onResult = settings::recordSync)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }

        fun sourceFor(s: AppSettings): HistorySource? {
            if (!s.isConfigured) return null
            val url = UrlPolicy.parse(s.baseUrl) ?: return null
            return ApiClient(url, s.apiKey)
        }
    }
}
```

- [ ] **Step 4: Run the whole unit-test suite and assemble**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest assembleDebug)`
Expected: PASS (all tests so far) and `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add net-worth-android
git commit -m "$(cat <<'EOF'
android: add settings model, validator, encrypted store and app graph

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 6: Money, WidgetConfig, WidgetSize and WidgetModel (the pure view-model)

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/Money.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/WidgetConfig.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/WidgetSize.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/WidgetModel.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/MoneyTest.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/WidgetConfigTest.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/WidgetSizeTest.kt`
- Test: `net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/WidgetModelTest.kt`

**Interfaces:**
- Consumes: `CacheSnapshot`, `HistoryResponse`, `Snapshot`, `ApiJson` (Task 1); `Projection`, `ProjectionConfig`, `Reach` (Task 2); `AppSettings`, `SyncStatus` (Task 5).
- Produces (`package com.isym444.networth.domain`):
  - `object Money { fun symbol(code: String): String; fun full(amount: Double, symbol: String): String; fun compact(amount: Double, symbol: String): String }`
  - `@Serializable data class WidgetConfig(withContributions: Boolean = true) { fun toJson(): String; companion fun fromJson(json: String?): WidgetConfig }`
  - `enum class WidgetSize { SMALL, MEDIUM, LARGE; companion fun forDp(widthDp: Float, heightDp: Float): WidgetSize }`
  - `data class MilestoneRow(label: String, value: String, reached: Boolean)`; `data class ProjectionRow(label: String, value: String)`
  - `sealed interface WidgetState { NotConfigured; Empty(message: String); Ready(headline: String, change: String?, changeUp: Boolean?, updated: String, hint: String?, oneYear: String?, milestones: List<MilestoneRow>, projections: List<ProjectionRow>) { fun nextMilestones(n: Int): List<MilestoneRow> } }`
  - `object WidgetModel { const val STALE_AFTER_MS: Long; fun build(cache: CacheSnapshot, config: WidgetConfig, settings: AppSettings, status: SyncStatus, now: Instant, zone: ZoneId): WidgetState }`

- [ ] **Step 1: Write the failing tests**

`net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/MoneyTest.kt`:
```kotlin
package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun symbols() {
        assertEquals("£", Money.symbol("GBP"))
        assertEquals("£", Money.symbol("gbp"))
        assertEquals("€", Money.symbol("EUR"))
        assertEquals("$", Money.symbol("USD"))
        assertEquals("¥", Money.symbol("JPY"))
        assertEquals("MX$", Money.symbol("MXN"))
    }

    @Test fun unknownCurrenciesUseTheirCodeAndBlankUsesNothing() {
        assertEquals("CHF ", Money.symbol("CHF"))
        assertEquals("", Money.symbol(""))
        assertEquals("", Money.symbol("  "))
    }

    @Test fun fullFormatsWholeUnitsWithGrouping() {
        assertEquals("£184,320", Money.full(184_320.25, "£"))
        assertEquals("£0", Money.full(0.0, "£"))
        assertEquals("$1,234,567", Money.full(1_234_567.0, "$"))
        assertEquals("CHF 1,234", Money.full(1_234.0, "CHF "))
    }

    @Test fun fullHandlesNegativesAndNeverPrintsMinusZero() {
        assertEquals("-£1,235", Money.full(-1_234.6, "£"))
        assertEquals("-£1,500", Money.full(-1_500.0, "£"))
        assertEquals("£0", Money.full(-0.2, "£"))
    }

    @Test fun compactAbbreviatesThousandsAndMillions() {
        assertEquals("£100k", Money.compact(100_000.0, "£"))
        assertEquals("£250k", Money.compact(250_000.0, "£"))
        assertEquals("£845k", Money.compact(845_300.0, "£"))
        assertEquals("£1m", Money.compact(1_000_000.0, "£"))
        assertEquals("£1.7m", Money.compact(1_700_000.0, "£"))
        assertEquals("£1.23m", Money.compact(1_234_567.0, "£"))
        assertEquals("£10m", Money.compact(10_000_000.0, "£"))
    }

    @Test fun compactSmallAndNegativeValues() {
        assertEquals("£999", Money.compact(999.0, "£"))
        assertEquals("£0", Money.compact(0.0, "£"))
        assertEquals("-£250k", Money.compact(-250_000.0, "£"))
        assertEquals("£0", Money.compact(-0.2, "£"))
    }
}
```

`net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/WidgetConfigTest.kt`:
```kotlin
package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigTest {
    @Test fun defaultsToWithContributions() = assertTrue(WidgetConfig().withContributions)

    @Test fun roundTrips() {
        val c = WidgetConfig(withContributions = false)
        assertEquals(c, WidgetConfig.fromJson(c.toJson()))
        assertFalse(WidgetConfig.fromJson(c.toJson()).withContributions)
    }

    @Test fun nullBlankAndCorruptJsonFallBackToDefaults() {
        assertEquals(WidgetConfig(), WidgetConfig.fromJson(null))
        assertEquals(WidgetConfig(), WidgetConfig.fromJson("  "))
        assertEquals(WidgetConfig(), WidgetConfig.fromJson("{ not json"))
    }

    @Test fun unknownKeysAreIgnored() =
        assertEquals(WidgetConfig(withContributions = false), WidgetConfig.fromJson("""{"withContributions":false,"future":1}"""))
}
```

`net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/WidgetSizeTest.kt`:
```kotlin
package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeTest {
    @Test fun smallIsNarrowOrShort() {
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(110f, 110f))
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(180f, 300f))   // narrow beats tall
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(300f, 90f))
    }

    @Test fun mediumIsWideAndShort() {
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(250f, 110f))
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(320f, 199f))
    }

    @Test fun largeIsWideAndTall() {
        assertEquals(WidgetSize.LARGE, WidgetSize.forDp(250f, 200f))
        assertEquals(WidgetSize.LARGE, WidgetSize.forDp(400f, 500f))
    }
}
```

`net-worth-android/app/src/test/kotlin/com/isym444/networth/domain/WidgetModelTest.kt`:
```kotlin
package com.isym444.networth.domain

import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.CacheSnapshot
import com.isym444.networth.data.HistoryResponse
import com.isym444.networth.data.Snapshot
import com.isym444.networth.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class WidgetModelTest {
    // "Today" is 2026-09-30 in UTC, matching the values in ProjectionTest.
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val nowMs = now.toEpochMilli()
    private val hour = 60L * 60 * 1000

    private val configured = AppSettings("https://nwapi.isym.uk/", "key", "")
    private val ok = SyncStatus(lastSuccessAt = nowMs, lastError = null, keyRejected = false)
    private val rejected = SyncStatus(lastSuccessAt = nowMs - hour, lastError = "Access key rejected", keyRejected = true)

    private fun history(
        vararg totals: Double,
        currency: String = "GBP",
        monthly: Double? = 1_000.0,
        oneYear: Double? = 210_000.0,
        at40: Double? = 900_000.0, at50: Double? = 1_500_000.0, at60: Double? = 2_500_000.0, at65: Double? = 3_500_000.0,
        wc40: Double? = 1_000_000.0, wc50: Double? = 1_700_000.0, wc60: Double? = 2_900_000.0, wc65: Double? = 4_100_000.0,
    ) = HistoryResponse(
        snapshots = totals.mapIndexed { i, t -> Snapshot(id = i.toLong(), timestamp = "2026-09-2${i}T07:00:00Z", currency = currency, grandTotal = t) },
        expectedNetWorthAt40 = at40, expectedNetWorthAt50 = at50, expectedNetWorthAt60 = at60, expectedNetWorthAt65 = at65,
        expectedNetWorthWithContributionsAt40 = wc40, expectedNetWorthWithContributionsAt50 = wc50,
        expectedNetWorthWithContributionsAt60 = wc60, expectedNetWorthWithContributionsAt65 = wc65,
        estimatedNetWorth1y = oneYear, monthlyContribution = monthly,
    )

    private fun build(
        h: HistoryResponse?,
        config: WidgetConfig = WidgetConfig(),
        settings: AppSettings = configured,
        status: SyncStatus = ok,
        fetchedAt: Long = nowMs,
    ): WidgetState = WidgetModel.build(CacheSnapshot(fetchedAt = fetchedAt, history = h), config, settings, status, now, ZoneOffset.UTC)

    private fun ready(
        h: HistoryResponse?,
        config: WidgetConfig = WidgetConfig(),
        status: SyncStatus = ok,
        fetchedAt: Long = nowMs,
    ) = build(h, config, configured, status, fetchedAt) as WidgetState.Ready

    // ---- empty and unconfigured states -----------------------------------------------------------------

    @Test fun notConfiguredWhenThereIsNoDataAndNoSettings() =
        assertEquals(WidgetState.NotConfigured, build(null, settings = AppSettings()))

    @Test fun waitingForFirstSyncWhenConfiguredButNothingCached() =
        assertEquals(WidgetState.Empty("Waiting for first sync"), build(null))

    @Test fun aRejectedKeyBeforeAnySyncSaysSo() {
        val s = build(null, status = rejected) as WidgetState.Empty
        assertTrue(s.message, s.message.contains("Key rejected"))
    }

    @Test fun aBrandNewDatabaseShowsNoSnapshotsYet() =
        assertEquals(WidgetState.Empty("No snapshots yet"), build(history()))

    // ---- headline and change ---------------------------------------------------------------------------

    @Test fun headlineUsesTheLatestSnapshotAndItsCurrency() {
        assertEquals("£184,320", ready(history(183_000.0, 184_320.25)).headline)
        assertEquals("€1,000", ready(history(1_000.0, currency = "EUR")).headline)
    }

    @Test fun changeUpAgainstThePreviousSnapshot() {
        val s = ready(history(184_320.0, 185_554.4))
        assertEquals("+£1,234 (+0.7%)", s.change)
        assertEquals(true, s.changeUp)
    }

    @Test fun changeDown() {
        val s = ready(history(100_000.0, 99_500.0))
        assertEquals("-£500 (-0.5%)", s.change)
        assertEquals(false, s.changeUp)
    }

    @Test fun noChange() {
        val s = ready(history(100_000.0, 100_000.0))
        assertEquals("No change", s.change)
        assertNull(s.changeUp)
    }

    @Test fun aSingleSnapshotHasNoChangeLine() {
        val s = ready(history(50_000.0))
        assertNull(s.change)
        assertNull(s.changeUp)
    }

    @Test fun changeFromAZeroBaselineOmitsThePercentage() =
        assertEquals("+£500", ready(history(0.0, 500.0)).change)

    // ---- milestones ------------------------------------------------------------------------------------

    @Test fun milestonesWithContributionsMatchTheDashboard() {
        val m = ready(history(183_000.0, 184_320.0)).milestones
        assertEquals(listOf("£100k", "£250k", "£500k", "£750k", "£1m"), m.map { it.label })
        assertEquals("Reached (age 30)", m[0].value)
        assertTrue(m[0].reached)
        assertEquals("2y 6m (age 32)", m[1].value)
        assertEquals(false, m[1].reached)
    }

    @Test fun milestonesWithoutContributionsMatchTheDashboard() {
        val m = ready(history(184_320.0), WidgetConfig(withContributions = false)).milestones
        assertEquals("4y 7m (age 34)", m[1].value)
        assertEquals("25y 0m (age 55)", m[4].value)
    }

    @Test fun aMissingMonthlyContributionActsLikeZero() {
        val m = ready(history(184_320.0, monthly = null)).milestones
        assertEquals("4y 7m (age 34)", m[1].value)
    }

    @Test fun nextMilestonesSkipsTheOnesAlreadyReached() {
        val s = ready(history(184_320.0))
        assertEquals(listOf("£250k", "£500k", "£750k"), s.nextMilestones(3).map { it.label })
    }

    @Test fun nextMilestonesIsEmptyWhenEverythingIsReached() =
        assertTrue(ready(history(2_000_000.0)).nextMilestones(3).isEmpty())

    // ---- projections and the one-year estimate ----------------------------------------------------------

    @Test fun projectionsFollowTheContributionSetting() {
        val h = history(184_320.0)
        assertEquals(
            listOf("Age 40" to "£1m", "Age 50" to "£1.7m", "Age 60" to "£2.9m", "Age 65" to "£4.1m"),
            ready(h).projections.map { it.label to it.value },
        )
        assertEquals(
            listOf("Age 40" to "£900k", "Age 50" to "£1.5m", "Age 60" to "£2.5m", "Age 65" to "£3.5m"),
            ready(h, WidgetConfig(withContributions = false)).projections.map { it.label to it.value },
        )
    }

    @Test fun nullProjectionsAreOmittedAndNeverCrash() {
        val s = ready(history(184_320.0, oneYear = null, wc40 = null, wc50 = null, wc60 = null, wc65 = null))
        assertTrue(s.projections.isEmpty())
        assertNull(s.oneYear)
        assertEquals(5, s.milestones.size)          // milestones still work without projections
    }

    @Test fun oneYearEstimateIsCompact() = assertEquals("£210k", ready(history(184_320.0)).oneYear)

    // ---- zero, negative, odd currencies ----------------------------------------------------------------

    @Test fun negativeNetWorthFormatsWithAMinusAndNeverReachesWithoutContributions() {
        val s = ready(history(-1_500.0), WidgetConfig(withContributions = false))
        assertEquals("-£1,500", s.headline)
        assertTrue(s.milestones.all { it.value == "100y+" && !it.reached })
    }

    @Test fun zeroNetWorthDoesNotCrash() {
        val s = ready(history(0.0), WidgetConfig(withContributions = false))
        assertEquals("£0", s.headline)
        assertEquals("100y+", s.milestones[0].value)
    }

    @Test fun anUnknownCurrencyUsesItsCode() = assertEquals("CHF 1,000", ready(history(1_000.0, currency = "CHF")).headline)

    @Test fun aBlankCurrencyHasNoSymbol() = assertEquals("1,000", ready(history(1_000.0, currency = "")).headline)

    // ---- freshness and hints ---------------------------------------------------------------------------

    @Test fun updatedTextIsRelative() {
        assertEquals("Updated just now", ready(history(1.0), fetchedAt = nowMs - 20_000).updated)
        assertEquals("Updated 5m ago", ready(history(1.0), fetchedAt = nowMs - 5 * 60_000).updated)
        assertEquals("Updated 3h ago", ready(history(1.0), fetchedAt = nowMs - 3 * hour).updated)
        assertEquals("Updated 2d ago", ready(history(1.0), fetchedAt = nowMs - 49 * hour).updated)
    }

    @Test fun aClockSkewedFutureFetchTimeReadsAsJustNow() =
        assertEquals("Updated just now", ready(history(1.0), fetchedAt = nowMs + hour).updated)

    @Test fun noHintWhenFresh() = assertNull(ready(history(1.0), fetchedAt = nowMs - 5 * hour).hint)

    @Test fun staleDataGetsAnOutOfDateHintButStaysOnScreen() {
        val s = ready(history(184_320.0), fetchedAt = nowMs - 7 * hour)
        assertEquals("Out of date — can't reach the server", s.hint)
        assertEquals("£184,320", s.headline)
    }

    @Test fun aRejectedKeyKeepsTheLastDataAndShowsAHint() {
        val s = ready(history(184_320.0), status = rejected)
        assertEquals("Key rejected — update it in the app", s.hint)
        assertEquals("£184,320", s.headline)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest --tests 'com.isym444.networth.domain.*')`
Expected: FAIL: `Unresolved reference: Money` / `WidgetModel` / `WidgetConfig` / `WidgetSize`.

- [ ] **Step 3: Write the implementation**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/Money.kt`:
```kotlin
package com.isym444.networth.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

object Money {
    /** Known symbols; anything else is shown as its code plus a space ("CHF 1,234"); blank gets no symbol. */
    fun symbol(code: String): String {
        val c = code.trim().uppercase(Locale.ROOT)
        return when (c) {
            "" -> ""
            "GBP" -> "£"
            "EUR" -> "€"
            "USD" -> "$"
            "JPY" -> "¥"
            "MXN" -> "MX$"
            else -> "$c "
        }
    }

    /** Whole units with grouping: "£184,320", "-£1,235". Never prints "-£0". */
    fun full(amount: Double, symbol: String): String {
        val r = amount.roundToLong()
        val sign = if (r < 0) "-" else ""
        return sign + symbol + String.format(Locale.UK, "%,d", r.absoluteValue)
    }

    /** Short form for tight spaces: "£100k", "£1.7m", "£999", "-£250k". */
    fun compact(amount: Double, symbol: String): String {
        val a = amount.absoluteValue
        val body = when {
            a >= 1_000_000 -> BigDecimal.valueOf(a / 1_000_000).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "m"
            a >= 1_000 -> (a / 1_000).roundToLong().toString() + "k"
            else -> a.roundToLong().toString()
        }
        val sign = if (amount < 0 && body != "0") "-" else ""
        return sign + symbol + body
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/WidgetConfig.kt`:
```kotlin
package com.isym444.networth.domain

import com.isym444.networth.data.ApiJson
import kotlinx.serialization.Serializable

/** Per-widget options, stored as JSON in Glance's per-instance state. */
@Serializable
data class WidgetConfig(
    val withContributions: Boolean = true,
) {
    fun toJson(): String = ApiJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String?): WidgetConfig =
            if (json.isNullOrBlank()) WidgetConfig()
            else runCatching { ApiJson.decodeFromString(serializer(), json) }.getOrDefault(WidgetConfig())
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/WidgetSize.kt`:
```kotlin
package com.isym444.networth.domain

/**
 * Which layout to draw for the size the launcher gives the widget. The thresholds are starting points;
 * tune them against the emulator/tablet in Task 8 (Oppo Pad cells are larger than a phone's).
 */
enum class WidgetSize {
    SMALL, MEDIUM, LARGE;

    companion object {
        fun forDp(widthDp: Float, heightDp: Float): WidgetSize = when {
            widthDp >= 250f && heightDp >= 200f -> LARGE
            widthDp >= 250f && heightDp >= 110f -> MEDIUM
            else -> SMALL
        }
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/domain/WidgetModel.kt`:
```kotlin
package com.isym444.networth.domain

import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.CacheSnapshot
import com.isym444.networth.data.HistoryResponse
import com.isym444.networth.data.SyncStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

data class MilestoneRow(val label: String, val value: String, val reached: Boolean)
data class ProjectionRow(val label: String, val value: String)

sealed interface WidgetState {
    data object NotConfigured : WidgetState
    data class Empty(val message: String) : WidgetState
    data class Ready(
        val headline: String,               // "£184,320"
        val change: String?,                // "+£1,234 (+0.7%)" / "No change" / null when there is no previous snapshot
        val changeUp: Boolean?,             // true = up, false = down, null = neutral
        val updated: String,                // "Updated 3h ago"
        val hint: String?,                  // "Key rejected — …" / "Out of date — …" / null
        val oneYear: String?,               // "£210k"
        val milestones: List<MilestoneRow>, // all five, in order
        val projections: List<ProjectionRow>,
    ) : WidgetState {
        /** The next `n` milestones that are not yet reached (for the medium layout). */
        fun nextMilestones(n: Int): List<MilestoneRow> = milestones.filter { !it.reached }.take(n)
    }
}

/** Pure: (cache, config, settings, sync status, now, zone) -> what to draw. No Android, no I/O. */
object WidgetModel {
    const val STALE_AFTER_MS: Long = 6L * 60 * 60 * 1000

    fun build(
        cache: CacheSnapshot,
        config: WidgetConfig,
        settings: AppSettings,
        status: SyncStatus,
        now: Instant,
        zone: ZoneId,
    ): WidgetState {
        val history = cache.history
        if (history == null) {
            return when {
                !settings.isConfigured -> WidgetState.NotConfigured
                status.keyRejected -> WidgetState.Empty("Key rejected — open Net Worth to fix it")
                else -> WidgetState.Empty("Waiting for first sync")
            }
        }
        val latest = history.snapshots.lastOrNull() ?: return WidgetState.Empty("No snapshots yet")
        val previous = history.snapshots.getOrNull(history.snapshots.size - 2)
        val symbol = Money.symbol(latest.currency)
        val today = now.atZone(zone).toLocalDate()

        val delta = previous?.let { latest.grandTotal - it.grandTotal }
        val (change, up) = changeLine(delta, previous?.grandTotal, symbol)

        val contribution = if (config.withContributions) history.monthlyContribution ?: 0.0 else 0.0
        val milestones = ProjectionConfig.MILESTONES.map { target ->
            val reach = Projection.reach(latest.grandTotal, target, contribution, today)
            MilestoneRow(Money.compact(target, symbol), reach.text(), reach is Reach.Reached)
        }

        val ageMs = now.toEpochMilli() - cache.fetchedAt
        val hint = when {
            status.keyRejected -> "Key rejected — update it in the app"
            cache.fetchedAt > 0 && ageMs > STALE_AFTER_MS -> "Out of date — can't reach the server"
            else -> null
        }

        return WidgetState.Ready(
            headline = Money.full(latest.grandTotal, symbol),
            change = change,
            changeUp = up,
            updated = "Updated " + ago(ageMs),
            hint = hint,
            oneYear = history.estimatedNetWorth1y?.let { Money.compact(it, symbol) },
            milestones = milestones,
            projections = projections(history, config.withContributions, symbol),
        )
    }

    private fun changeLine(delta: Double?, previousTotal: Double?, symbol: String): Pair<String?, Boolean?> {
        if (delta == null || previousTotal == null) return null to null
        if (delta.roundToLong() == 0L) return "No change" to null
        val pct = if (previousTotal != 0.0) {
            String.format(Locale.UK, " (%+.1f%%)", delta / previousTotal.absoluteValue * 100)
        } else ""
        val text = (if (delta > 0) "+" else "-") + Money.full(delta.absoluteValue, symbol) + pct
        return text to (delta > 0)
    }

    private fun projections(h: HistoryResponse, withContributions: Boolean, symbol: String): List<ProjectionRow> {
        val byAge = if (withContributions) {
            listOf(
                40 to h.expectedNetWorthWithContributionsAt40, 50 to h.expectedNetWorthWithContributionsAt50,
                60 to h.expectedNetWorthWithContributionsAt60, 65 to h.expectedNetWorthWithContributionsAt65,
            )
        } else {
            listOf(
                40 to h.expectedNetWorthAt40, 50 to h.expectedNetWorthAt50,
                60 to h.expectedNetWorthAt60, 65 to h.expectedNetWorthAt65,
            )
        }
        return byAge.mapNotNull { (age, value) -> value?.let { ProjectionRow("Age $age", Money.compact(it, symbol)) } }
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"                       // also covers a fetch time in the future (clock skew)
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else -> "${ms / 86_400_000}d ago"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest)`
Expected: PASS (every test in the project, including all the new `Money`, `WidgetConfig`, `WidgetSize` and `WidgetModel` tests).

- [ ] **Step 5: Commit**

```bash
git add net-worth-android
git commit -m "$(cat <<'EOF'
android: add money formatting, widget config/size and the widget view-model

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 7: Glance widget, background refresh and receiver

Android glue with no JVM unit tests; all logic it displays is covered by Task 6. Verification is a clean compile plus the emulator check in Task 8.

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/Scheduler.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/RefreshWorker.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/RerenderWorker.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetUpdater.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/NetWorthWidget.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetLayouts.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/NetWorthWidgetReceiver.kt`
- Create: `net-worth-android/app/src/main/res/xml/net_worth_widget_info.xml`
- Modify: `net-worth-android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AppGraph`, `UrlPolicy`, `SyncResult`, `rejectsKey` (Tasks 3-5); `WidgetModel`, `WidgetConfig`, `WidgetSize`, `WidgetState`, `MilestoneRow`, `ProjectionRow` (Task 6).
- Produces:
  - `object Scheduler { fun ensureScheduled(context: Context); fun refreshNow(context: Context); fun cancelAll(context: Context) }`
  - `object WidgetUpdater { suspend fun refreshAll(context: Context) }`
  - `class NetWorthWidget : GlanceAppWidget { companion val CONFIG_KEY: Preferences.Key<String>; val TICK: Preferences.Key<Long> }`
  - `class NetWorthWidgetReceiver : GlanceAppWidgetReceiver`

- [ ] **Step 1: Write the scheduler and the two workers**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/Scheduler.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** No midnight alarm: the data changes about once a day, so an hourly refresh plus a 6-hourly re-render is enough. */
object Scheduler {
    private const val PERIODIC = "networth-refresh"
    private const val ONCE = "networth-refresh-now"
    private const val RERENDER = "networth-rerender"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Idempotent: safe to call from every render and from the settings screen. */
    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(60, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
        // No constraints, no network: keeps "updated Xh ago", hints and ages current while offline.
        wm.enqueueUniquePeriodicWork(
            RERENDER, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RerenderWorker>(6, TimeUnit.HOURS).build(),
        )
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(RERENDER)
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/RefreshWorker.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.isym444.networth.data.AppGraph
import com.isym444.networth.data.SyncResult
import com.isym444.networth.data.rejectsKey

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        if (!graph.settings.load().isConfigured) return Result.success()
        val result = graph.sync().refresh()
        WidgetUpdater.refreshAll(applicationContext)        // also refreshes the hint line after a failure
        // Retrying a rejected key is pointless; the user has to fix it in the app.
        val retry = result is SyncResult.Failed && !result.error.rejectsKey && runAttemptCount < 3
        return if (retry) Result.retry() else Result.success()
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/RerenderWorker.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Network-free, unconstrained backstop: re-render from the cache so "updated Xh ago", hints and ages stay right offline. */
class RerenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.refreshAll(applicationContext)
        return Result.success()
    }
}
```

- [ ] **Step 2: Write the widget updater and the widget**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetUpdater.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

object WidgetUpdater {
    /** Re-render every placed widget from the cache (no network). */
    suspend fun refreshAll(context: Context) {
        val widget = NetWorthWidget()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(NetWorthWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(context, id) { it[NetWorthWidget.TICK] = System.currentTimeMillis() }
            widget.update(context, id)
        }
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/NetWorthWidget.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import com.isym444.networth.data.AppGraph
import com.isym444.networth.data.UrlPolicy
import com.isym444.networth.domain.WidgetConfig
import com.isym444.networth.domain.WidgetModel
import java.time.Instant
import java.time.ZoneId

/**
 * Renders from the cache only (never the network). Each update writes a new TICK into the widget's state,
 * which re-runs the `remember(tick)` blocks below: cache, settings and "now" are re-read, so fresh syncs and
 * the passage of time show up without restarting the session.
 */
class NetWorthWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = AppGraph.get(context)
        // Self-heal: a force-stop or ColorOS "clean up" drops the jobs; any render re-asserts them (idempotent).
        Scheduler.ensureScheduled(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val tick = prefs[TICK]
            val configJson = prefs[CONFIG_KEY]
            val config = remember(configJson) { WidgetConfig.fromJson(configJson) }
            val settings = remember(tick) { graph.settings.load() }
            val status = remember(tick) { graph.settings.status() }
            val cache = remember(tick) { graph.cache.load() }
            val now = remember(tick) { Instant.now() }
            val state = remember(tick, configJson) {
                WidgetModel.build(cache, config, settings, status, now, ZoneId.systemDefault())
            }
            val tap = remember(settings.webUrl) { tapIntent(context, settings.webUrl) }
            WidgetContent(state, tap)
        }
    }

    /** Tap: the web dashboard if configured, otherwise this app's launcher activity (the settings screen). */
    private fun tapIntent(context: Context, webUrl: String): Intent {
        val url = webUrl.takeIf { it.isNotBlank() }?.let { UrlPolicy.parse(it) }
        return if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
        else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
    }

    companion object {
        val CONFIG_KEY = stringPreferencesKey("config")
        val TICK = longPreferencesKey("tick")
    }
}
```

- [ ] **Step 3: Write the three layouts**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetLayouts.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.isym444.networth.domain.MilestoneRow
import com.isym444.networth.domain.ProjectionRow
import com.isym444.networth.domain.WidgetSize
import com.isym444.networth.domain.WidgetState

internal object Palette {
    private fun solid(c: Color): ColorProvider = ColorProvider(c)
    private fun dayNight(day: Color, night: Color): ColorProvider = androidx.glance.color.ColorProvider(day, night)

    val bg = dayNight(Color(0xFFFFFFFF), Color(0xFF1C1C1E))
    val primary = dayNight(Color(0xFF111111), Color(0xFFF2F2F7))
    val secondary = dayNight(Color(0xFF6B6B70), Color(0xFF9A9AA0))
    val up = dayNight(Color(0xFF2E7D32), Color(0xFF66BB6A))
    val down = dayNight(Color(0xFFC62828), Color(0xFFEF5350))
    val warn = solid(Color(0xFFFB8C00))
}

@Composable
internal fun WidgetContent(state: WidgetState, tap: Intent) {
    val open = actionStartActivity(tap)
    val size = LocalSize.current
    val layout = WidgetSize.forDp(size.width.value, size.height.value)
    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(Palette.bg).cornerRadius(24.dp).padding(12.dp).clickable(open),
    ) {
        when (state) {
            is WidgetState.Ready -> when (layout) {
                WidgetSize.SMALL -> SmallLayout(state)
                WidgetSize.MEDIUM -> MediumLayout(state)
                WidgetSize.LARGE -> LargeLayout(state)
            }
            is WidgetState.Empty -> Message(state.message)
            WidgetState.NotConfigured -> Message("Open Net Worth to set up")
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = Palette.secondary, fontSize = 13.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun SmallLayout(s: WidgetState.Ready) {
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Label("Net worth")
        Text(s.headline, style = TextStyle(color = Palette.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        ChangeLine(s)
        Spacer(GlanceModifier.height(4.dp))
        Footer(s)
    }
}

@Composable
private fun MediumLayout(s: WidgetState.Ready) {
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Label("Net worth")
            Text(s.headline, style = TextStyle(color = Palette.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            ChangeLine(s)
            Spacer(GlanceModifier.height(4.dp))
            Footer(s)
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            val next = s.nextMilestones(3)
            if (next.isEmpty()) {
                Text("All milestones reached", style = TextStyle(color = Palette.up, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            } else {
                Label("Next milestones")
                next.forEach { MilestoneLine(it) }
            }
        }
    }
}

@Composable
private fun LargeLayout(s: WidgetState.Ready) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Label("Net worth")
        Text(s.headline, style = TextStyle(color = Palette.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        ChangeLine(s)
        s.oneYear?.let { Text("In a year ≈ $it", style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1) }
        Spacer(GlanceModifier.height(8.dp))
        Label("Time to milestones")
        s.milestones.forEach { MilestoneLine(it) }
        if (s.projections.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Label("Projected net worth")
            s.projections.forEach { ProjectionLine(it) }
        }
        Spacer(GlanceModifier.height(6.dp))
        Footer(s)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = TextStyle(color = Palette.secondary, fontSize = 11.sp), maxLines = 1)

@Composable
private fun ChangeLine(s: WidgetState.Ready) {
    val text = s.change ?: return
    val color = when (s.changeUp) {
        true -> Palette.up
        false -> Palette.down
        null -> Palette.secondary
    }
    Text(text, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 1)
}

@Composable
private fun Footer(s: WidgetState.Ready) {
    val text = s.hint ?: s.updated
    val color = if (s.hint != null) Palette.warn else Palette.secondary
    Text(text, style = TextStyle(color = color, fontSize = 10.sp), maxLines = 1)
}

@Composable
private fun MilestoneLine(m: MilestoneRow) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            m.label,
            modifier = GlanceModifier.width(56.dp),
            style = TextStyle(color = Palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(
            m.value,
            style = TextStyle(color = if (m.reached) Palette.up else Palette.secondary, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProjectionLine(p: ProjectionRow) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            p.label,
            modifier = GlanceModifier.width(56.dp),
            style = TextStyle(color = Palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(p.value, style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1)
    }
}
```

- [ ] **Step 4: Write the receiver, the provider XML and the manifest entry**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/NetWorthWidgetReceiver.kt`:
```kotlin
package com.isym444.networth.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class NetWorthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetWorthWidget()

    override fun onEnabled(context: Context) {          // first widget placed
        super.onEnabled(context)
        Scheduler.ensureScheduled(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {         // last widget removed
        super.onDisabled(context)
        Scheduler.cancelAll(context)
    }
}
```

`net-worth-android/app/src/main/res/xml/net_worth_widget_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:minResizeWidth="110dp"
    android:minResizeHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen"
    android:description="@string/widget_description"
    android:initialLayout="@layout/glance_default_loading_layout" />
```
(Task 8 adds `android:configure` and `android:widgetFeatures="reconfigurable"` once the config activity exists.)

Replace the whole `<application>` element in `net-worth-android/app/src/main/AndroidManifest.xml` with:
```xml
    <application
        android:label="@string/app_name"
        android:allowBackup="false"
        android:theme="@android:style/Theme.DeviceDefault.DayNight">

        <receiver
            android:name=".widget.NetWorthWidgetReceiver"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/net_worth_widget_info" />
        </receiver>
    </application>
```

- [ ] **Step 5: Build and re-run the unit tests**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest assembleDebug)`
Expected: PASS and `BUILD SUCCESSFUL`. If a Glance import or signature differs from what is written here, fix the compile error in place (the versions in `libs.versions.toml` are the ones Countdown builds with); do not change any test.

- [ ] **Step 6: Commit**

```bash
git add net-worth-android
git commit -m "$(cat <<'EOF'
android: add Glance widget, hourly refresh and widget receiver

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 8: Settings screen, widget options screen, pinning and emulator check

**Files:**
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/ui/Theme.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/ui/SettingsActivity.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetConfigActivity.kt`
- Create: `net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetPinner.kt`
- Create: `net-worth-android/app/src/debug/AndroidManifest.xml`, `net-worth-android/app/src/debug/res/xml/network_security_config.xml`
- Create: `net-worth-android/dev/seed-cache.json`, `net-worth-android/dev/seed.sh`
- Modify: `net-worth-android/app/src/main/AndroidManifest.xml`, `net-worth-android/app/src/main/res/xml/net_worth_widget_info.xml`

**Interfaces:**
- Consumes: `AppGraph`, `SettingsValidator`, `Validation`, `Field`, `AppSettings.DEFAULT_BASE_URL`, `SyncResult` (Tasks 4-5); `Scheduler`, `WidgetUpdater`, `NetWorthWidget`, `NetWorthWidgetReceiver` (Task 7); `Money`, `WidgetConfig` (Task 6).
- Produces: `class SettingsActivity` (launcher), `class WidgetConfigActivity` (per-widget options), `object WidgetPinner { fun pin(context: Context): Boolean }`.

- [ ] **Step 1: Write the theme and the settings screen**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/ui/Theme.kt`:
```kotlin
package com.isym444.networth.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/ui/SettingsActivity.kt`:
```kotlin
package com.isym444.networth.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.isym444.networth.data.AppGraph
import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.Field
import com.isym444.networth.data.SettingsValidator
import com.isym444.networth.data.SyncResult
import com.isym444.networth.data.Validation
import com.isym444.networth.domain.Money
import com.isym444.networth.widget.Scheduler
import com.isym444.networth.widget.WidgetPinner
import com.isym444.networth.widget.WidgetUpdater
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureScheduled(this)
        setContent { AppTheme { SettingsScreen() } }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()
    val saved = remember { graph.settings.load() }

    var baseUrl by remember { mutableStateOf(saved.baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL }) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var webUrl by remember { mutableStateOf(saved.webUrl) }
    var errors by remember { mutableStateOf<Map<Field, String>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(syncSummary(graph)) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Net Worth", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API URL") },
            placeholder = { Text(AppSettings.DEFAULT_BASE_URL) }, singleLine = true,
            isError = Field.BASE_URL in errors, supportingText = { errors[Field.BASE_URL]?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, label = { Text("Dashboard access key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = Field.API_KEY in errors, supportingText = { errors[Field.API_KEY]?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = webUrl, onValueChange = { webUrl = it }, label = { Text("Dashboard URL (opened when you tap the widget)") },
            singleLine = true, isError = Field.WEB_URL in errors, supportingText = { errors[Field.WEB_URL]?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                when (val v = SettingsValidator.validate(baseUrl, apiKey, webUrl)) {
                    is Validation.Invalid -> { errors = v.errors; status = null }
                    is Validation.Valid -> {
                        errors = emptyMap(); busy = true; status = "Connecting…"
                        scope.launch {
                            graph.settings.save(v.settings)
                            Scheduler.ensureScheduled(context)
                            status = when (val result = graph.sync().refresh()) {
                                is SyncResult.Failed -> result.error.userMessage
                                SyncResult.Updated -> graph.cache.load().history?.snapshots?.lastOrNull()
                                    ?.let { "OK — latest ${Money.full(it.grandTotal, Money.symbol(it.currency))}" }
                                    ?: "OK — no snapshots yet"
                            }
                            WidgetUpdater.refreshAll(context)
                            summary = syncSummary(graph)
                            busy = false
                        }
                    }
                }
            },
        ) { Text("Save & test") }

        status?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp)) }
        Text(summary, style = MaterialTheme.typography.bodySmall)

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!WidgetPinner.pin(context)) {
                    status = "This launcher can't add widgets from here. Long-press the home screen → Widgets → Net Worth."
                }
            },
        ) { Text("Add widget to home screen") }

        Text("Keeping it fresh", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "The widget always shows the last synced data, even offline. " +
                "Some devices (Oppo/ColorOS) pause background refresh; allow Net Worth to run unrestricted to keep it syncing.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        ) { Text("Battery optimisation settings") }
    }
}

private fun syncSummary(graph: AppGraph): String {
    val snap = graph.cache.load()
    val st = graph.settings.status()
    val last = if (snap.fetchedAt <= 0) "Never synced"
    else "Last synced " + Instant.ofEpochMilli(snap.fetchedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH))
    return buildString {
        append(last)
        st.lastError?.let { append("\nLast error: $it") }
    }
}
```

- [ ] **Step 2: Write the widget options screen and the pinner**

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetConfigActivity.kt`:
```kotlin
package com.isym444.networth.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.isym444.networth.domain.WidgetConfig
import com.isym444.networth.ui.AppTheme
import kotlinx.coroutines.launch

/** Per-widget options. Launched by the launcher when the widget is added, and from its reconfigure action. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                var config by remember { mutableStateOf(WidgetConfig()) }
                LaunchedEffect(Unit) { config = loadConfig(appWidgetId) }
                ConfigScreen(config, onChange = { config = it }, onSave = { scope.launch { save(appWidgetId, config) } })
            }
        }
    }

    private suspend fun loadConfig(appWidgetId: Int): WidgetConfig = runCatching {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        val prefs: Preferences = getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
        WidgetConfig.fromJson(prefs[NetWorthWidget.CONFIG_KEY])
    }.getOrDefault(WidgetConfig())

    private suspend fun save(appWidgetId: Int, config: WidgetConfig) {
        runCatching {
            val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this, glanceId) {
                it[NetWorthWidget.CONFIG_KEY] = config.toJson()
                it[NetWorthWidget.TICK] = System.currentTimeMillis()
            }
            NetWorthWidget().update(this, glanceId)
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@Composable
private fun ConfigScreen(config: WidgetConfig, onChange: (WidgetConfig) -> Unit, onSave: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Widget options", style = MaterialTheme.typography.headlineSmall)

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Include monthly contributions in milestone times")
            Switch(checked = config.withContributions, onCheckedChange = { onChange(config.copy(withContributions = it)) })
        }
        Text(
            "On: uses the monthly contribution saved on the dashboard. Off: growth on today's net worth only.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
```

`net-worth-android/app/src/main/kotlin/com/isym444/networth/widget/WidgetPinner.kt`:
```kotlin
package com.isym444.networth.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

object WidgetPinner {
    /** Asks the launcher to add the widget. False if this launcher can't do it (add it by hand then). */
    fun pin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, NetWorthWidgetReceiver::class.java), null, null)
    }
}
```

- [ ] **Step 3: Register the activities and the config hook; add the debug-only cleartext config**

In `net-worth-android/app/src/main/AndroidManifest.xml`, add these two `<activity>` elements inside `<application>`, before the `<receiver>`:
```xml
        <activity
            android:name=".ui.SettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".widget.WidgetConfigActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>
```

In `net-worth-android/app/src/main/res/xml/net_worth_widget_info.xml`, add these two attributes to the `<appwidget-provider>` element (before `android:description`):
```xml
    android:configure="com.isym444.networth.widget.WidgetConfigActivity"
    android:widgetFeatures="reconfigurable"
```

`net-worth-android/app/src/debug/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

`net-worth-android/app/src/debug/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Debug builds only: lets the emulator reach a local API at http://10.0.2.2:8080. -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```
(`mkdir -p net-worth-android/app/src/debug/res/xml` first.)

- [ ] **Step 4: Add the emulator seed cache**

`net-worth-android/dev/seed-cache.json` (fictional numbers; `fetchedAt` is a fixed 2026‑09‑30 timestamp, so the widget will show an "Updated Nd ago" / "Out of date" hint once time passes, which is a handy check of that path):
```json
{
  "fetchedAt": 1790726400000,
  "history": {
    "snapshots": [
      {"id": 1, "timestamp": "2026-09-29T07:00:00Z", "currency": "GBP", "grandTotal": 183000.5},
      {"id": 2, "timestamp": "2026-09-30T07:00:00Z", "currency": "GBP", "grandTotal": 184320.25}
    ],
    "expectedNetWorthAt40": 900000.0,
    "expectedNetWorthAt50": 1500000.0,
    "expectedNetWorthAt60": 2500000.0,
    "expectedNetWorthAt65": 3500000.0,
    "expectedNetWorthWithContributionsAt40": 1000000.0,
    "expectedNetWorthWithContributionsAt50": 1700000.0,
    "expectedNetWorthWithContributionsAt60": 2900000.0,
    "expectedNetWorthWithContributionsAt65": 4100000.0,
    "estimatedNetWorth1y": 210000.0,
    "monthlyContribution": 1000.0
  }
}
```

`net-worth-android/dev/seed.sh` (then `chmod +x`):
```bash
#!/usr/bin/env bash
# Usage: net-worth-android/dev/seed.sh   (debug build installed on the running emulator/device)
set -euo pipefail
ADB="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb"
HERE="$(cd "$(dirname "$0")" && pwd)"
"$ADB" push "$HERE/seed-cache.json" /data/local/tmp/seed-cache.json
"$ADB" shell run-as com.isym444.networth mkdir -p files
"$ADB" shell run-as com.isym444.networth cp /data/local/tmp/seed-cache.json files/cache.json
echo "Seeded. Re-render by adding the widget or opening the app."
```

- [ ] **Step 5: Build, then run the unit tests**

Run: `(cd net-worth-android && ./gradlew testDebugUnitTest assembleDebug)`
Expected: PASS and `BUILD SUCCESSFUL`.

- [ ] **Step 6: Check on the emulator**

```bash
SDK=/opt/homebrew/share/android-commandlinetools
"$SDK/cmdline-tools/latest/bin/avdmanager" list avd          # if none: create one from an installed system image
"$SDK/emulator/emulator" -avd <name> &                       # wait for boot; a Pixel-Tablet-style AVD is closest to the Oppo Pad
"$SDK/platform-tools/adb" wait-for-device
(cd net-worth-android && ./gradlew installDebug)
net-worth-android/dev/seed.sh
"$SDK/platform-tools/adb" shell monkey -p com.isym444.networth -c android.intent.category.LAUNCHER 1
```
Then, in the emulator, check each of these and write down anything that fails:

1. The settings screen opens with the API URL pre-filled with `https://nwapi.isym.uk`. Tap **Add widget to home screen**, accept, and pick "Include monthly contributions" **on**.
2. **Medium (4×2):** headline `£184,320`, a green `+£1,320 (+0.7%)` line, and the next milestones `£250k 2y 6m (age 32)`, `£500k …`, `£750k …`. Footer reads "Updated …" or, because the seed timestamp is fixed, "Out of date — can't reach the server".
3. **Resize to small (≈2×2):** headline, change and footer only. **Resize to large (≈4×4+):** headline, "In a year ≈ £210k", all five milestones (`£100k` green with "Reached (age 30)"), and the four projections. If a layout switches at the wrong size, adjust the thresholds in `WidgetSize.forDp` and its test together.
4. Long-press the widget → reconfigure → switch contributions **off** → the `£250k` row changes to `4y 7m (age 34)`.
5. **Airplane mode on**, re-add or re-render the widget: it still renders from the cache.
6. With a wrong key (any text) and **Save & test**: the status line says "Access key rejected"; the widget keeps the seeded data and shows "Key rejected — update it in the app".
7. Tap the widget with no dashboard URL set: the settings screen opens. Set a dashboard URL, tap again: the browser opens it.

- [ ] **Step 7: Commit**

```bash
git add net-worth-android
git status --short   # confirm build/ and local.properties are NOT listed
git commit -m "$(cat <<'EOF'
android: add settings and widget options screens, pinning and emulator seed

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```

---

### Task 9: Release signing, README and tablet install

**Files:**
- Modify: `net-worth-android/app/build.gradle.kts`
- Create: `net-worth-android/README.md`
- Modify: `README.md` (repo root) only if it has a components list; otherwise leave it alone

**Interfaces:**
- Consumes: the whole app. Produces: `./gradlew assembleRelease` yields a signed `app-release.apk`.

- [ ] **Step 1: Have the user create the release keystore (the password must not pass through this session)**

Ask the user to run this themselves (in Claude Code they can type it with the `!` prefix), choosing their own passwords:
```bash
mkdir -p ~/.android/networth
keytool -genkeypair -v -keystore ~/.android/networth/networth-release.jks \
  -alias networth -keyalg RSA -keysize 2048 -validity 10000
```
Then have them create the git-ignored `net-worth-android/keystore.properties` with their values:
```properties
storeFile=/Users/<them>/.android/networth/networth-release.jks
storePassword=<their store password>
keyAlias=networth
keyPassword=<their key password>
```
Do not read, print or commit either file. Confirm only that `git check-ignore net-worth-android/keystore.properties` prints the path.

- [ ] **Step 2: Wire signing into the Gradle build**

In `net-worth-android/app/build.gradle.kts`, add at the very top:
```kotlin
import java.util.Properties
```
Add above the `android {` block:
```kotlin
// Release signing comes from net-worth-android/keystore.properties (git-ignored).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
```
Inside `android { … }`, before `buildTypes`, add:
```kotlin
    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
```
and replace the `buildTypes` block with:
```kotlin
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
```

- [ ] **Step 3: Write the README**

`net-worth-android/README.md`:
````markdown
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
3. Press **Add widget to home screen** (or long-press the home screen → Widgets → Net Worth). Resize it: small shows the headline,
   medium adds the next three milestones, large adds every milestone and the age 40/50/60/65 projections.
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
````

- [ ] **Step 4: Build the release APK and verify its signature**

```bash
(cd net-worth-android && ./gradlew testDebugUnitTest assembleRelease)
SDK=/opt/homebrew/share/android-commandlinetools
"$SDK/build-tools/35.0.0/apksigner" verify --print-certs net-worth-android/app/build/outputs/apk/release/app-release.apk
```
Expected: `BUILD SUCCESSFUL`; `apksigner` prints `Verifies` and a certificate line whose owner is the keystore's, not "Android Debug". If `35.0.0` isn't the installed build-tools version, use `ls "$SDK/build-tools"`.

- [ ] **Step 5: Live check against the real API (needs the user's key; do not paste it into this session)**

Have the user install the release APK on the tablet (or `adb install -r`), open the app, paste their key, and press **Save & test**. Expected: status reads `OK — latest £…` matching the dashboard's net worth, and the widget shows the same headline. Have them also compare one milestone row (for example £250k) with the dashboard's "Time to Net Worth Milestones" card: the two should agree exactly.

- [ ] **Step 6: Commit**

```bash
git add net-worth-android
git status --short   # confirm keystore.properties, *.jks and *.apk are NOT listed
git commit -m "$(cat <<'EOF'
android: add signed release build and README

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019b731GXHWuTNZPS6m7Ttyp
EOF
)"
```
