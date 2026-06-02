# Plan: Channel page viewing should populate the What's New feed

## 1. Problem statement

When a user opens a channel page, NewPipe fetches `ChannelInfo` and `ChannelTabInfo` (streams) via the extractor and caches them in memory/disk for the page. The fetched `StreamInfoItem`s are rendered in the UI but are **never written to the `feed` table**. Consequence: even though the latest videos for a subscribed channel were just retrieved, the What's New feed (`FeedFragment`) does not reflect them until the next scheduled / pull-to-refresh feed load runs `FeedLoadManager`.

The user's mental model is: "Viewing a channel I'm subscribed to should add any new videos I see there to What's New." That is currently not implemented.

## 2. Evidence

Search of `app/src/main/java/org/schabi/newpipe/` for callers of `feedDatabaseManager.upsertAll(subscriptionId, items)`:

- `local/feed/service/FeedLoadManager.kt:362` — scheduled / on-demand feed update
- `local/subscription/SubscriptionManager.kt:62` — initial subscribe / subscription import

Search of `fragments/list/channel/` (ChannelFragment, ChannelTabFragment, ChannelAboutFragment) for any reference to `FeedDatabaseManager`, `upsertAll`, `feedDAO`, `FeedDAO`:

- **Zero matches.**

Conclusion: channel viewing has never been wired into the feed table. The bug is a missing integration, not a regression.

## 3. Fix design

### 3.1 Hook point

`ChannelTabFragment.handleResult(ChannelTabInfo result)` (file: `app/src/main/java/org/schabi/newpipe/fragments/list/channel/ChannelTabFragment.java`).

Why this point:

- It is invoked once per tab load with the first page of `relatedItems` (which contain `StreamInfoItem`s for the streams tab).
- Pagination ("load more") goes through `loadMoreItemsLogic` and is appended by the parent `BaseListInfoFragment` outside `handleResult`. Restricting the feed insert to `handleResult` naturally limits writes to **the first page only** — i.e. the freshest, most-likely-new items. This matches the user's expectation and avoids backfilling discovery dates for older paginated content.
- Existing date cap in `FeedDatabaseManager.upsertAll` (`FEED_OLDEST_ALLOWED_DATE = now − 13 weeks`) provides defense in depth even for the first page.

### 3.2 Subscription resolution

We must only insert into `feed` when the channel is currently a subscription, because `FeedEntity.subscriptionId` is a non-null FK to `subscriptions.uid`.

Lookup options considered:

- **A. Look up in `ChannelTabFragment`** — needs the *channel* URL. `ChannelTabFragment.tabHandler.getUrl()` is the *tab* URL, not the channel URL, and is not guaranteed to match `subscriptions.url` for all services. **Requires plumbing the channel URL into `ChannelTabFragment.getInstance(...)`.**
- **B. Resolve in `ChannelFragment` and pass `subscriptionId` (Long) to children** — `ChannelFragment` already monitors subscription state via `monitorSubscription` / `channelSubscription`. But subscription resolution there is asynchronous (RxJava `Flowable`), and `ChannelTabFragment`s are constructed in `updateTabs()` *before* the subscription lookup completes.
- **C. ChannelTabFragment performs its own lookup** using channel URL passed in.

**Decision: Option A/C hybrid.** Pass channel URL into `ChannelTabFragment.getInstance(...)` (alongside existing `serviceId`, `tabHandler`, `channelName`), and have the fragment perform a `SubscriptionDAO.getSubscription(serviceId, url): Maybe<SubscriptionEntity>` lookup on `Schedulers.io()` inside `handleResult`. This:

- Keeps the feed insert self-contained in `ChannelTabFragment` (no cross-fragment plumbing of state).
- Reuses the existing `getSubscription(serviceId, url)` DAO method (`SubscriptionDAO.kt:85`).
- Avoids the timing problem in option B (the `Maybe` simply emits empty if not subscribed → no-op).

### 3.3 Tab gating

Only act when the tab is the streams tab. There is already a helper: `ChannelTabHelper.isStreamsTab(tabHandler)` (used in `getListHeaderSupplier`). Reuse it.

Other tabs (Playlists, Channels, Albums) must be no-ops — their `relatedItems` are not `StreamInfoItem` and would not match the feed schema.

### 3.4 Threading

All DB work on `Schedulers.io()`. No UI updates from this code path. Disposable must be tracked so it is cleaned up on `onDestroyView` to avoid leaks.

`ChannelTabFragment` does not currently hold a `CompositeDisposable`. We will add one (mirrors the pattern in `ChannelFragment`).

### 3.5 Idempotency

`feedTable.insertAll` uses `OnConflictStrategy.IGNORE` (per `AGENTS.md` notes). Re-opening the same channel page repeatedly is safe — existing `(stream_id, subscription_id)` rows are not duplicated, and `streamTable.upsertAll` updates stream metadata. `discovery_date` is set on first insert via `FeedEntity.<init>(streamId, subscriptionId)` and not overwritten thereafter.

### 3.6 Subscription update info

`feedDatabaseManager.upsertAll(...)` also writes a `SubscriptionUpdateInfoEntity` (`upsertUpdateInfo`) with `lastUpdated = now`. This will mark the subscription as freshly fetched, so the smart scheduler (`FeedLoadManager` / `SubscriptionUpdateInfoEntity`) will not redundantly re-fetch this channel for a while. **This is desirable** — viewing the channel page is functionally equivalent to a feed update for that one subscription.

Side effect to flag: `upsertAll` also resets `fetch_interval` to `7` and `0` (the default). If this channel had a smart-scheduled fetch interval computed previously, it will be reset. Acceptable for v1 since the next scheduled fetch will recompute.

## 4. Concrete changes

### 4.1 `ChannelTabFragment.java`

1. Add a `String channelUrl` `@State` field.
2. Add a new `getInstance` overload (keep the existing one for binary compat; have it call the new one with `null` for `channelUrl`):

   ```java
   public static ChannelTabFragment getInstance(
       int serviceId, ListLinkHandler tabHandler,
       String channelUrl, String channelName) { ... }
   ```
3. Add `private final CompositeDisposable disposables = new CompositeDisposable();`.
4. In `onDestroyView()`, call `disposables.clear()`.
5. In `handleResult(ChannelTabInfo result)`, after the existing logic, if `ChannelTabHelper.isStreamsTab(tabHandler)` AND `channelUrl != null`:
    - Filter `result.getRelatedItems()` to `StreamInfoItem`.
    - If the list is non-empty, dispatch a single `Disposable` on `Schedulers.io()` that:
        - Calls `new SubscriptionManager(requireContext()).subscriptionTable().getSubscription(serviceId, channelUrl)` (`Maybe<SubscriptionEntity>`).
        - On `subscribe`, calls `new FeedDatabaseManager(requireContext()).upsertAll(entity.getUid(), streamItems)`.
        - On `onComplete` (no subscription) → no-op.
        - On error → log via `Log.w(TAG, ...)`. Do not surface to UI.
    - Add the `Disposable` to `disposables`.

### 4.2 `ChannelFragment.java`

In `updateTabs()` (line ~478), update the construction:

```java
ChannelTabFragment.getInstance(serviceId, linkHandler, url, name)
```

`url` is the channel URL field already on `ChannelFragment`.

### 4.3 No changes required to

- `FeedDatabaseManager.kt` (existing `upsertAll` is sufficient).
- `FeedDAO.kt` / database schema / migrations.
- `SubscriptionDAO.kt` (existing `getSubscription(serviceId, url)` is sufficient).
- `FeedLoadManager.kt` (scheduled flow remains untouched).

## 5. Verification

Each item must be checked off before completion.

- [ ] `./gradlew runCheckstyle runKtlint` passes.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] `./gradlew :app:assembleDebug` produces APK.
- [ ] `lsp_diagnostics` clean on changed files.
- [ ] Manual device test (per `AGENTS.md`: "ALWAYS test on device"):
    1. Subscribe to a channel.
    2. Pull DB: `adb shell "run-as org.schabi.newpipe.debug cat databases/newpipe.db" > /tmp/before.db`.
    3. Note count: `sqlite3 /tmp/before.db "SELECT COUNT(*) FROM feed WHERE subscription_id = (SELECT uid FROM subscriptions WHERE url = '<channel_url>');"`.
    4. Open the channel page in the app.
    5. Pull DB again as `/tmp/after.db`.
    6. Confirm feed row count for that subscription has increased (or stayed equal, if all videos were already known).
    7. Confirm new rows have non-null `discovery_date` ≈ now.
    8. Confirm What's New feed shows the channel's latest videos at top (sorted by discovery_date DESC).
- [ ] Negative test: open a channel page for a channel I'm **not** subscribed to. Confirm zero new feed rows. Confirm no error / crash.
- [ ] Negative test: navigate to non-streams tabs (Playlists, Channels). Confirm no feed rows added.
- [ ] Re-open the same channel page twice. Confirm no duplicate feed rows.

## 6. Risks and out-of-scope

### Risks
- **Regressing scheduled feed updates**: Mitigated — we only add a new write path; `FeedLoadManager` is untouched.
- **`fetch_interval` reset on every channel view**: Documented in §3.6. Acceptable for v1; revisit if smart scheduling regressions are observed.
- **Race conditions** on concurrent feed inserts (channel view while scheduled feed load is running): `upsertAll` is wrapped per-call but not transactionally cross-call. Existing `SubscriptionManager.upsertAll` and `FeedLoadManager.DatabaseConsumer` both use `database.runInTransaction`; our path does not need to because it is a single `upsertAll` invocation, which itself uses Room's batching. Worst case is a temporarily duplicated insert attempt → `OnConflictStrategy.IGNORE` swallows it.
- **Performance**: One extra `Maybe` DAO lookup + (if subscribed) one `upsertAll` per channel page open. Negligible.

### Out of scope
- Smart fetch interval recomputation after channel-view inserts.
- Doing the same for any other code path that fetches channel streams (e.g. notifications worker — already uses its own path).
- Refactoring `ChannelTabFragment`'s `getInstance` callers beyond `ChannelFragment` (search shows only one caller; if a second exists, it must be updated).
- Targeting `refactor` branch. Per `AGENTS.md`, new features go to `refactor`; this is arguably a bug fix to a never-implemented integration. **Branch decision deferred to user (see "How do you want to proceed?" answer captured at plan creation).**

## 7. Branch & commit policy

- Single commit (or two: one for plumbing channel URL, one for the feed insert). Conventional summary, e.g. `fix(channel): add fetched streams to What's New feed`.
- Do **not** commit unless explicitly requested by user.

## 8. Open questions for review

1. Should viewing **any** channel (not just subscribed) seed the feed? Current plan: subscribed only, because `feed.subscription_id` is required. Alternative: silently subscribe on view — rejected, would change semantics drastically.
2. Should pagination ("load more") also feed into What's New? Current plan: no, only first page. Confirm this matches user expectation.
3. Branch target: `dev`/`master` (treat as bugfix) or `refactor` (treat as new behavior)?
