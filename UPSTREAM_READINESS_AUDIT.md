# Upstream Readiness Audit: Smart Feed Scheduling Feature

**Date**: 2026-03-04  
**Branch**: `feature/smart-feed-scheduling`  
**Goal**: Align implementation with NewPipe contribution guidelines for eventual upstream merge

---

## Executive Summary

| Category | Status | Notes |
|----------|--------|-------|
| Code Style (ktlint) | ✅ PASS | No violations |
| Code Style (checkstyle) | ✅ PASS | No violations |
| SPDX Headers | ⚠️ MISSING | Not required yet, but trending |
| F-Droid Compliance | ✅ PASS | No proprietary dependencies |
| Architecture Patterns | ✅ GOOD | Follows existing patterns |
| Testing | ❌ MISSING | No unit tests |
| Documentation | ⚠️ PARTIAL | Code comments exist, but lacking |
| AI Policy Compliance | ⚠️ UNCERTAIN | Heavy AI generation |

---

## 1. Code Style Compliance

### ✅ Automated Linters

```bash
./gradlew runKtlint    # PASS
./gradlew runCheckstyle # PASS
```

All Kotlin and Java code passes linting without warnings.

### ✅ Naming Conventions

| Pattern | Our Code | NewPipe Standard | Status |
|---------|----------|------------------|--------|
| Entities | `SubscriptionUpdateInfoEntity` | `*Entity.kt` | ✅ Match |
| DAOs | `SubscriptionDAO`, `FeedDAO` | `*DAO.kt` | ✅ Match |
| Workers | `FeedAutoUpdateWorker` | `*Worker.kt` | ✅ Match |
| Constants | `TABLE_NAME`, `SUBSCRIPTION_ID` | `UPPER_SNAKE_CASE` | ✅ Match |
| Package structure | `database/feed/model/` | Follows hierarchy | ✅ Match |

### ⚠️ SPDX Headers

**Current state**: None of our new files have SPDX headers.

**Example from recent NewPipe files**:
```kotlin
/*
 * SPDX-FileCopyrightText: 2017-2024 NewPipe contributors <https://newpipe.net>
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
```

**Observation**: Many existing files (including `NewVersionWorker.kt`) lack these headers. This appears to be a recent addition (Dec 2025), not yet enforced project-wide.

**Recommendation**: Add SPDX headers to all new files before upstream PR.

---

## 2. Architecture Compliance

### ✅ Database Layer

Our entities follow NewPipe patterns exactly:

```kotlin
// Our code matches existing style
@Entity(
    tableName = SubscriptionUpdateInfoEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = [SubscriptionEntity.SUBSCRIPTION_UID],
            childColumns = [SubscriptionUpdateInfoEntity.SUBSCRIPTION_ID],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
            deferred = true  // ✅ Matches FeedEntity pattern
        )
    ]
)
```

**Comparison with `FeedEntity.kt`**: Identical structure.

### ✅ DAO Patterns

```kotlin
// Matches existing DAO style
@Query(
    """
    SELECT s.*, sui.next_update
    FROM subscriptions s
    LEFT JOIN subscription_update_info sui ON s.uid = sui.subscription_id
    ORDER BY s.name COLLATE NOCASE ASC
    """
)
abstract fun getAllWithUpdateInfo(): Flowable<List<SubscriptionWithUpdateInfo>>
```

Uses:
- `@RewriteQueriesToDropUnusedColumns` (where applicable) ✅
- Multi-line string literals for complex queries ✅
- `Flowable<List<T>>` for reactive streams ✅
- `COLLATE NOCASE` for sorting ✅

### ✅ Worker Implementation

`FeedAutoUpdateWorker` extends `RxWorker` like `NotificationWorker`:

```kotlin
class FeedAutoUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : RxWorker(appContext, workerParams) {
    
    override fun createWork(): Single<Result> {
        // RxJava pattern ✅
    }
    
    override fun getForegroundInfo(): Single<ForegroundInfo> {
        // Foreground notification ✅
    }
}
```

**Matches**:
- Constructor signature ✅
- `RxWorker` base class ✅
- Foreground service notification ✅
- Error handling with `ErrorUtil.createNotification` ✅

### ✅ ViewModel Integration

```kotlin
// SubscriptionViewModel.kt
stateItemsDisposable = subscriptionManager.subscriptionsWithUpdateInfo()
    .throttleLatest(...)
    .map { it.map { withInfo -> 
        ChannelItem(
            withInfo.subscription.toChannelInfoItem(), 
            withInfo.subscription.uid, 
            ChannelItem.ItemVersion.MINI, 
            nextUpdate = withInfo.nextUpdate
        ) 
    }}
```

**Follows**:
- RxJava `.map` chains ✅
- `throttleLatest` for UI updates ✅
- Proper `Disposable` lifecycle management ✅

---

## 3. F-Droid Compliance

✅ **No proprietary dependencies introduced**

All dependencies used:
- `androidx.work:work-runtime-ktx` - Open source ✅
- `androidx.room` - Open source ✅
- `io.reactivex.rxjava3` - Open source ✅
- `java.time` - JDK standard library ✅

No Google Play Services or closed-source libraries.

---

## 4. Testing

❌ **Critical Gap: No unit tests**

NewPipe has extensive test coverage:
- `app/src/test/java/org/schabi/newpipe/`
- Examples: `ListHelperTest.kt`, `DatabaseMigrationTest.kt`

### Missing Tests

1. **`FetchInterval` algorithm**:
   ```kotlin
   @Test
   fun testCalculateNextUpdate_weeklyPattern() {
       val history = listOf(/* uploads every 7 days */)
       val nextUpdate = FetchInterval.calculateNextUpdate(history, OffsetDateTime.now())
       // Assert within 7±1 days
   }
   ```

2. **Database migration 10→11**:
   ```kotlin
   @Test
   fun testMigration10to11() {
       // Test subscription_update_info table created
       // Test foreign key constraints
   }
   ```

3. **DAO queries**:
   ```kotlin
   @Test
   fun testGetAllWithUpdateInfo() {
       // Insert subscriptions with/without update info
       // Verify LEFT JOIN returns all subscriptions
       // Verify nextUpdate is null for missing records
   }
   ```

**Impact**: Without tests, upstream reviewers cannot verify correctness. This would likely block PR acceptance.

---

## 5. Documentation

### ⚠️ Code Comments

**Current**: Some inline comments exist.

**NewPipe Standard**: Extensive KDoc/JavaDoc on public methods.

**Example from `FeedDAO.kt`**:
```kotlin
/**
 * Returns a Flowable of all feed items from subscriptions in the given group,
 * along with the streams associated with each feed item.
 *
 * @param groupId the feed group id; use [FeedGroupEntity.GROUP_ALL_ID] for all groups
 * @param includePlayed whether to include streams that have been fully watched
 * @return Flowable of feed items with streams
 */
@Query(...)
abstract fun getStreams(
    groupId: Long,
    includePlayed: Boolean
): Flowable<List<StreamWithState>>
```

### ❌ Missing Documentation

Files needing KDoc:
- `SubscriptionUpdateInfoEntity` - Explain purpose of each field
- `FetchInterval` - Document algorithm, parameters, edge cases
- `FeedLoadManager.startLoading()` - Explain `useSmartScheduling` parameter
- `FeedAutoUpdateWorker` - Explain scheduling logic

---

## 6. Edge Cases & Error Handling

### ✅ Good Patterns

1. **Null safety**:
   ```kotlin
   val nextUpdate: OffsetDateTime? = null  // Nullable by design ✅
   ```

2. **Error propagation**:
   ```kotlin
   .onErrorReturn { throwable ->
       Log.e(TAG, "Error loading feed", throwable)
       Result.failure()
   }
   ```

### ⚠️ Potential Issues

1. **Time zone handling**:
   - Using `OffsetDateTime.now(ZoneOffset.UTC)` everywhere ✅
   - But: No explicit handling of user changing time zone
   - **Risk**: Low (server timestamps are UTC)

2. **Worker scheduling edge cases**:
   - Android 12+ exact alarm restrictions?
   - Battery optimization exemptions?
   - **Status**: Uses `PeriodicWorkRequest` which handles this ✅

3. **Database migration rollback**:
   - No down-migration strategy
   - **Risk**: Medium (user downgrades app, DB incompatible)
   - **Mitigation**: NewPipe typically doesn't support downgrades

---

## 7. AI Policy Compliance

### ⚠️ Uncertain Status

Per NewPipe's AI policy:

> Using generative AI to develop new features or making larger code changes is generally prohibited. Please refrain from contributions which are heavily depending on AI generated source code...

**Our situation**:
- ✅ Code follows project structure
- ✅ Passes all linters
- ✅ Matches architectural patterns
- ❌ Heavy AI generation without prior deep understanding
- ❌ No demonstration of "fundamental understanding of overall project structure"

### Path to Compliance

To meet AI policy for upstream:

1. **Study phase** (weeks):
   - Manually trace existing feed loading flow
   - Understand Worker lifecycle in NewPipe context
   - Study Room migration patterns in codebase

2. **Rewrite from scratch**:
   - Use current code as reference/specification
   - Write new implementation manually
   - Demonstrate understanding through documentation

3. **Justification**:
   - Document architectural decisions
   - Explain why this approach vs. alternatives
   - Show awareness of integration points

---

## 8. Comparison: Our Code vs. Existing Similar Features

### Feed Loading

| Aspect | Existing (`FeedLoadService`) | Our Addition |
|--------|------------------------------|--------------|
| Trigger | Manual refresh / notifications | + Background worker (periodic) |
| Strategy | Load all subscriptions | + Smart scheduling (selective) |
| UI feedback | Progress notification | ✅ Reuses same pattern |
| Error handling | `ErrorUtil.createNotification` | ✅ Reuses same pattern |
| Data source | `FeedLoadManager` | ✅ Reuses same class |

**Integration**: We extended existing systems cleanly. ✅

### Notifications

| Aspect | Existing (`NotificationWorker`) | Our Addition |
|--------|--------------------------------|--------------|
| Base class | `RxWorker` | ✅ Same |
| Foreground service | ✅ Uses ForegroundInfo | ✅ Same pattern |
| Notification channel | NOTIFICATION_CHANNEL_ID | ✅ Reuses |
| Scheduling | OneTimeWorkRequest | PeriodicWorkRequest (different but valid) |

**Integration**: Follows established patterns. ✅

---

## 9. Blocker Issues for Upstream

### High Priority (Must Fix Before PR)

1. ❌ **No unit tests**
   - Impact: Reviewers cannot verify correctness
   - Effort: 2-3 days to write comprehensive tests
   - Files: `FetchIntervalTest`, `SubscriptionDAOTest`, `FeedLoadManagerTest`

2. ⚠️ **AI policy compliance**
   - Impact: PR could be closed without review
   - Effort: Weeks of study + manual rewrite
   - Alternative: Open issue first, propose design, get feedback

3. ⚠️ **Missing documentation**
   - Impact: Code hard to review and maintain
   - Effort: 1 day to add KDoc comments
   - Focus: Public APIs and complex algorithms

### Medium Priority (Should Fix)

4. ⚠️ **SPDX headers**
   - Impact: May be required in future
   - Effort: 30 minutes (automated script)
   - Files: All new `.kt` files

5. ⚠️ **Edge case testing**
   - Impact: Bugs in production
   - Effort: 1 day manual testing
   - Focus: Time zone changes, app upgrades, worker lifecycle

### Low Priority (Nice to Have)

6. 📝 **Performance testing**
   - Impact: Unknown performance at scale
   - Effort: 1 day with profiler
   - Focus: Database query performance with 1000+ subscriptions

7. 📝 **Accessibility**
   - Impact: Screen reader users
   - Effort: 1 hour
   - Focus: "Next check" text in layouts

---

## 10. Recommended Path Forward

### Option A: Upstream Contribution (High Effort)

**Timeline**: 4-6 weeks

1. **Week 1-2: Study**
   - Trace feed loading flow manually
   - Study Worker patterns in Android 12+
   - Review Room migration best practices

2. **Week 3: Rewrite**
   - Manual reimplementation
   - Document architectural decisions
   - Open GitHub issue proposing feature

3. **Week 4: Testing**
   - Unit tests (target 80% coverage)
   - Manual testing on multiple Android versions
   - Performance profiling

4. **Week 5: Documentation**
   - KDoc comments on all public APIs
   - Update AGENTS.md with findings
   - Write migration guide for users

5. **Week 6: PR**
   - Submit PR with full justification
   - Respond to reviews
   - Iterate based on feedback

**Blockers**:
- NewPipe is in **maintenance mode** - only bugfixes accepted
- Feature may need to wait for refactor branch
- Team may reject smart scheduling concept entirely

### Option B: Personal Fork (Low Effort)

**Timeline**: Complete now ✅

1. Continue using in personal fork
2. Add tests for your own confidence
3. Monitor upstream for refactor completion
4. Revisit contribution later

**Advantages**:
- Immediate use and learning
- No pressure from upstream review
- Can iterate based on real usage

### Option C: Hybrid Approach (Recommended)

**Timeline**: 1-2 weeks

1. **Add SPDX headers** (30 min)
2. **Write unit tests** (2-3 days)
3. **Add KDoc comments** (1 day)
4. **Open issue upstream** proposing feature (30 min)
   - Get feedback before investing in rewrite
   - Understand if feature aligns with roadmap
5. **Continue using personal fork** while awaiting feedback

**Benefits**:
- Code quality improves (useful even for personal use)
- Demonstrates seriousness to upstream
- Low wasted effort if rejected

---

## 11. Conclusion

### Current Status

✅ **Production-ready for personal use**:
- Follows NewPipe architecture
- Passes all linters
- No F-Droid compliance issues
- Clean integration with existing code

⚠️ **Not upstream-ready**:
- Missing tests (blocker)
- AI policy concerns (potential blocker)
- Missing documentation (should fix)
- NewPipe in maintenance mode (timing issue)

### Next Steps

**Immediate** (this week):
1. Add SPDX headers to new files
2. Start writing unit tests

**Short-term** (this month):
1. Complete test suite (80% coverage target)
2. Add KDoc documentation
3. Open GitHub issue proposing feature

**Long-term** (this year):
1. Monitor NewPipe refactor progress
2. Revisit contribution when accepting new features
3. Consider manual rewrite to satisfy AI policy

### Key Insight

Our implementation is **architecturally sound** and **style-compliant**. The main barriers to upstream are:
1. **Procedural** - NewPipe maintenance mode
2. **Testing** - No unit tests
3. **Policy** - AI generation concerns

All three are addressable with time and effort.

---

**Generated**: 2026-03-04  
**Next Review**: After test suite completion
