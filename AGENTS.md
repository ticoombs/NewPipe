# NewPipe Agent Notes

This document captures build commands, code style, and critical learnings for AI agents working on this codebase.

---

## Build & Test Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run all linting (checkstyle + ktlint)
./gradlew runCheckstyle runKtlint

# Run unit tests
./gradlew testDebugUnitTest

# Run a single test class (Java)
./gradlew testDebugUnitTest --tests "org.schabi.newpipe.util.ListHelperTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "org.schabi.newpipe.util.ListHelperTest.getSortedStreamVideosListTest"

# Run Android instrumentation tests (requires emulator/device)
./gradlew connectedCheck

# Full CI build (what CI runs)
./gradlew assembleDebug lintDebug testDebugUnitTest --stacktrace -DskipFormatKtlint

# Auto-format Kotlin files
./gradlew formatKtlint
```

### Install & Debug on Device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.schabi.newpipe.debug/org.schabi.newpipe.MainActivity
adb logcat -d | grep -i "schabi\|newpipe" | grep -viE "InputDispatcher|WindowManager"
```


## Publishing to the dev F-Droid repo (after every user-visible change)

We self-host an F-Droid repo at `/home/timc/repos/fdroid-repo` (sibling directory). After landing any user-visible feature or fix on this fork, publish a new build so the team's devices auto-prompt for the update. **Skipping this step keeps the device fleet on stale code and undoes the point of the dev repo.**

Flow (from this repo's root):

```bash
# 1. Bump versionCode in app/build.gradle.kts (default value on the line
#    `versionCode = System.getProperty("versionCodeOverride")?.toInt() ?: 1008`).
#    Required — ingest.sh refuses to overwrite an existing
#    <appid>_<versionCode>.apk.
# 2. Commit the versionCode bump together with the feature.
# 3. Build, sign, and ingest in one go:
make fdroid-publish
```

What it does:
1. Sources `../fdroid-repo/keystore/newpipe.env` (passwords + key path).
2. Runs `./gradlew -DpackageSuffix=.dev assembleRelease`. The `.dev` suffix makes the APK install as `org.schabi.newpipe.dev`, so it sits alongside any upstream NewPipe a tester might have. Override via `FDROID_PACKAGE_SUFFIX=.featureX make fdroid-publish` for branch-specific builds — if you do, rename `fdroid-repo/metadata/org.schabi.newpipe.dev.yml` to match the new applicationId, otherwise the package will be missing from the index.
3. Calls `../fdroid-repo/scripts/ingest.sh org.schabi.newpipe.dev app/build/outputs/apk/release/app-release.apk`, which verifies the APK signature, copies it to `fdroid-repo/repo/org.schabi.newpipe.dev_<versionCode>.apk`, and regenerates the signed `index-v2.json`.

Pre-flight checks before running `make fdroid-publish`:
- `./gradlew testReleaseUnitTest` passes (or at minimum `testDebugUnitTest`).
- Release-mode R8 minification can break code that worked in debug — if the publish-built APK crashes on device but the debug APK doesn't, suspect `proguard-rules.pro` first.
- `versionCode` is strictly greater than the one in the previously published APK (`ls ../fdroid-repo/repo/org.schabi.newpipe.dev_*.apk`).

Post-publish:
- The repo's `repo/` directory is gitignored by design — push it to the server with `cd ../fdroid-repo && make rsync DEST=<host>:/srv/fdroid/`. If a deployment host is configured, run rsync as the final step; otherwise leave the regenerated index local and note the new versionCode in your report.
- Test devices already subscribed to the dev repo will surface the update within their F-Droid client's poll interval (default ~1h, or pull-to-refresh).

When to skip:
- Pure refactors with no behavior change, doc-only changes, or work-in-progress on a branch you haven't merged. `fdroid-publish` is for shippable revisions, not every commit.
---

## Deploying to Internal F-Droid Repo

After landing any user-visible feature or bugfix, publish a new build to our internal F-Droid repo at `../fdroid-repo/` so the devices on the network pick it up. **This is the default ship step — do it after every feature merge, not just at "release time".**

### One-shot command

```bash
# From NewPipe/ root
make fdroid-publish
```

This target:
1. Sources signing creds from `../fdroid-repo/keystore/newpipe.env` (gitignored).
2. Runs `./gradlew -DpackageSuffix=.dev assembleRelease` → produces a signed APK with applicationId `org.schabi.newpipe.dev` (installs side-by-side with upstream NewPipe).
3. Hands the APK to `../fdroid-repo/scripts/ingest.sh`, which verifies the signature, copies it to `repo/org.schabi.newpipe.dev_<versionCode>.apk`, and runs `fdroid update -c` to regenerate the signed index.

### Before publishing: bump versionCode

`ingest.sh` refuses to overwrite an existing APK with different contents. If you've already published the current `versionCode`, bump it in `app/build.gradle.kts` (`versionCode = …`) before running `make fdroid-publish`. Otherwise the ingest step fails with:

```
error: <path>/org.schabi.newpipe.dev_<vc>.apk exists with different contents.
       Bump versionCode in the app before re-publishing.
```

Rule of thumb: **one feature merge = one versionCode bump = one `make fdroid-publish`**.

### Serving / pushing to devices

After publish, from `../fdroid-repo/`:

```bash
# Local-network testing (devices point F-Droid at http://<this-host>:8080)
make serve

# Push to the always-on host serving via nginx/caddy
make rsync DEST=user@host:/srv/fdroid/
```

### Prerequisites (one-time)

- `../fdroid-repo/keystore/newpipe.env` and `newpipe.jks` must exist. Generate via `make init-keys` in `../fdroid-repo/` if missing.
- `fdroidserver` installed on the build host (`sudo apt install fdroidserver`).
- `ANDROID_HOME` set, or `NewPipe/local.properties` contains `sdk.dir=` (ingest infers from it).

### Common gotchas

- **`packageSuffix` is required.** Without `-DpackageSuffix=.dev` the APK ships with `org.schabi.newpipe`, which conflicts with upstream and fails ingest's applicationId check.
- **Signing is opt-in.** If `NEWPIPE_KEYSTORE` is unset at build time the release APK is unsigned and ingest will reject it. The Makefile sources the env file for you — just don't bypass it with a raw `./gradlew assembleRelease`.
- **Don't change the keystore between releases.** F-Droid clients refuse updates signed by a different key. If `newpipe.jks` is ever regenerated, every device must uninstall + reinstall.

---

## Code Style Guidelines

### General Rules (Checkstyle enforced)
- **Line length**: 100 characters max
- **No tabs**: Spaces only
- **No trailing whitespace**
- **Newline at end of file**
- **Braces required**: Always use `{}` for if/for/while, even single statements

### Java Conventions
- **Imports**: No star imports (`import java.util.*` forbidden)
- **Illegal imports**: Don't use JetBrains/javax/RxJava nullability annotations; use `androidx.annotation.Nullable`/`NonNull`
- **Final parameters**: All method parameters should be `final`
- **Final local variables**: Local variables should be `final` when possible
- **Member naming**: `camelCase`, with exceptions for `TAG` and `DEBUG` constants
- **Constants**: `UPPER_SNAKE_CASE`

### Kotlin Conventions (ktlint enforced)
- Standard Kotlin style guide
- Use `val` over `var` when possible
- Data classes for simple data holders
- Extension functions in `ktx/` package

### Nullability
- Use AndroidX annotations: `@NonNull`, `@Nullable`
- In Kotlin, prefer non-null types; use `?` only when null is a valid state

### Error Handling
- Never use empty catch blocks
- Log errors or rethrow with context
- Use ACRA for crash reporting

---

## Architecture Overview

| Layer | Location | Description |
|-------|----------|-------------|
| Database | `database/` | Room entities, DAOs, migrations |
| Local | `local/` | Local data management (feed, subscriptions, playlists) |
| Fragments | `fragments/` | UI fragments (View layer) |
| ViewModels | `viewmodels/` | MVVM ViewModels |
| UI Components | `ui/components/` | Jetpack Compose components |
| Player | `player/` | ExoPlayer integration |
| Extractor | External lib | NewPipeExtractor for service APIs |

---

## What's New Feed (FeedFragment)

### Critical: Discovery Date vs Upload Date

The What's New feed must show items ordered by **discovery date** (when NewPipe found the video), NOT upload date.

**Why**: When subscribing to a new channel, all historical videos get added. Without discovery_date ordering, old videos flood the feed.

### Correct ORDER BY

```sql
-- CORRECT: NULL values LAST, then newest discovery_date first
ORDER BY f.discovery_date IS NULL ASC, f.discovery_date DESC, s.upload_date DESC

-- WRONG: This puts NULL values FIRST!
ORDER BY f.discovery_date IS NULL DESC, f.discovery_date DESC, s.upload_date DESC
```

### Key Files

| File | Purpose |
|------|---------|
| `database/feed/dao/FeedDAO.kt` | Main query with ORDER BY |
| `database/feed/model/FeedEntity.kt` | Feed table entity with `discoveryDate` |
| `local/feed/FeedDatabaseManager.kt` | Sets `discoveryDate = now()` on insert |
| `database/Migrations.java` | Migration 10→11 added discovery_date |

---

## Smart Feed Scheduling

Reduces API calls by predicting when channels will upload.

| Component | Purpose |
|-----------|---------|
| `SubscriptionUpdateInfoEntity` | Tracks `last_updated`, `next_update`, `fetch_interval` |
| `FeedLoadManager.kt` | Scheduling logic |
| `FeedDAO.getAllDueForUpdate()` | Query for due subscriptions |

---

## Database Debugging

```bash
# Copy DB to local machine
adb shell "run-as org.schabi.newpipe.debug cat databases/newpipe.db" > /tmp/newpipe.db

# Common queries
sqlite3 /tmp/newpipe.db "SELECT COUNT(*), SUM(CASE WHEN discovery_date IS NULL THEN 1 ELSE 0 END) FROM feed;"
```

---

## Common Pitfalls

1. **NULL handling in ORDER BY**: `IS NULL` returns 1 (true) or 0 (false). With `DESC`, 1 comes first. Use `ASC` to push NULLs to end.

2. **OnConflictStrategy.IGNORE**: `FeedDAO.insertAll()` uses IGNORE—existing entries won't update.

3. **Timestamps are UTC**: Always use `OffsetDateTime.now(ZoneOffset.UTC)`.

4. **Room @Query changes require rebuild**: After modifying DAO SQL, full rebuild may be needed.

5. **NewPipeExtractor changes**: If modifying extractor behavior, edit `gradle/libs.versions.toml` to point to your fork/commit.

---

## Testing Patterns

### Java Tests
```java
public class SomeTest {
    @Test
    public void testSomething() {
        // Arrange
        final List<Thing> input = List.of(...);
        
        // Act
        final Result result = SomeClass.method(input);
        
        // Assert
        assertEquals(expected, result);
    }
}
```

### Kotlin Tests
```kotlin
class SomeTest {
    @Test
    fun testSomething() {
        val input = listOf(...)
        val result = SomeClass.method(input)
        assertEquals(expected, result)
    }
}
```

---

## TDD Red/Green for Tricky Logic

Subscription/feed logic has historically been a source of regressions (the "2-month bug", discovery_date semantics, first-refresh flooding). For any non-trivial change in this area, follow strict TDD red/green:

1. **Red**: Write a failing test that reproduces the bug or pins the desired behavior BEFORE touching production code. Run it and confirm it fails for the right reason (assertion mismatch, not compile/setup error).
2. **Green**: Make the smallest change that turns the test green. Resist the urge to refactor in the same step.
3. **Refactor** (optional): Only after green, with the test as a safety net.

### When TDD is mandatory

- Bugs in `FeedDatabaseManager`, `FeedDAO`, `FeedLoadManager`, or anything touching `discovery_date` / `upload_date` / subscription update scheduling.
- Any database migration that backfills or transforms data.
- Any change to the "What's New" feed ordering, filtering, or insertion logic.

### Preferred test infrastructure

- **Robolectric JVM tests** (`app/src/test/...`) for fast feedback. The repo is wired up with `robolectric`, `androidx.room.testing`, `assertj.core`, and `androidx.test.ext.junit`. `testOptions { unitTests.isIncludeAndroidResources = true }` is enabled.
- Use in-memory Room DBs (`Room.inMemoryDatabaseBuilder`) for DAO/Manager tests. See `FeedDAODiscoveryDateTest` and `FeedDatabaseManagerTest` for working patterns.
- For migration tests, build the prior schema from raw SQL and call `Migrations.MIGRATION_X_Y.migrate(db)` directly. `MigrationTestHelper` does NOT work in unit tests here because AGP does not merge unit-test assets (`mergeDebugUnitTestAssets` task does not exist). See `FeedDiscoveryDateMigrationTest` for the working pattern.
- Avoid androidTest/instrumentation for this kind of logic — too slow for red/green iteration.

### Anti-patterns

- Fixing a feed bug without a regression test (it WILL regress).
- Adding a test AFTER the fix that just asserts the current behavior — this proves nothing about the bug.
- Tests that depend on "today's date" without overriding the cutoff (e.g., `FEED_OLDEST_ALLOWED_DATE`). Use ancient dates like `2000-01-01` to bypass the 13-week filter.
- `MigrationTestHelper` in unit tests — it will fail with `FileNotFoundException` because schemas are not on the unit-test asset path.

---

## Important Notes

1. **Refactor branch**: New features should target `refactor` branch. Current codebase (`dev`/`master`) is maintenance-only (bugfixes).

2. **No Google Play Services**: This is a libre app. Never introduce closed-source dependencies.

3. **F-Droid compatibility**: Follow F-Droid inclusion policy. No tracking, no non-free components.

4. **Reproducible builds**: Don't enable `shrinkResources` in release builds.

5. **ALWAYS test on device**: Lint and build passing does NOT suffice as sufficient testing. UI changes and preference behavior must be verified on a real device or emulator via `adb install` and manual/automated UI testing.
