# Issues — smart-subscription-scheduling

## [2026-05-15] Pre-existing Overlap on T1

### Issue: Existing smart scheduling preference in content_settings.xml
- `content_settings.xml` already has a SwitchPreferenceCompat with `feed_smart_update_scheduling_key`
- This is NOT the same as the plan-specified `smart_scheduling_enabled_key` / `pref_smart_scheduling_enabled`
- The existing one has `defaultValue="false"` and seems to control feed auto-update, not subtitle visibility
- Plan requires a NEW separate switch for the subtitle/analyzer gating (defaultValue="true")
- T1 agent should add the NEW preference as a SEPARATE entry in the feed category

### Issue: Already-existing strings
- `strings.xml` has `feed_smart_update_scheduling_title` and `feed_smart_update_scheduling_summary` — these are for the existing pref
- T1 must add 5 NEW keys: `smart_scheduling_title`, `smart_scheduling_summary`, `next_check_info_title`, `next_check_refresh_now`, `next_check_close`
- Do NOT rename or touch existing keys

### Issue: T7 mentions 9+ string keys but some are body template keys not in T1
- T1 adds: `smart_scheduling_title`, `smart_scheduling_summary`, `next_check_info_title`, `next_check_refresh_now`, `next_check_close` (5 keys)
- T7 adds body templates: `next_check_info_summary`, `next_check_info_pattern_*`, `next_check_info_interval`, etc. (9 more keys)
- These are separate — don't conflate T1 and T7 string additions

## [2026-05-15] F3 Device QA — BLOCKED

### Blocker: No Pixel 1 attached
- `adb devices` returns empty list at Final Wave time
- F3 requires physical device for: APK install, uiautomator dump, sqlite query, logcat capture
- All automated checks (F1, F2, F4) APPROVED
- Evidence dir `.sisyphus/evidence/final-qa/` not populated (device required)
- **Action**: F3 skipped per "document blocker and move to next task" policy
- **Mitigation**: T11 user acceptance explicitly covers the device QA gap — user can run the scenarios manually or re-attach device to re-run F3

## 2026-05-19 — F3 Device QA: NextCheckInfoDialog CRASH on inflate

**Severity: CRITICAL — blocks feature usage**

Tapping "Next check info" long-press menu item crashes the app every time
on a Pixel 1 running LineageOS (Android 15-equivalent) with the
DarkTheme.YouTube app theme.

Stack:
- `android.view.InflateException: Binary XML file line #20 in layout/dialog_next_check_info: Error inflating class TextView`
- Caused by `java.lang.UnsupportedOperationException: Failed to resolve attribute at index 5: TypedValue{t=0x2/d=0x7f040123 a=-1}` (a `?attr/...` ref to a colour-state-list)
- At `org.schabi.newpipe.local.subscription.dialog.NextCheckInfoDialog.onCreateDialog(NextCheckInfoDialog.kt:51)`
- TypedArray.getColorStateList(TypedArray.java:608)

Root cause:
- `app/src/main/res/layout/dialog_next_check_info.xml` uses Material3 theme attrs:
  - `?attr/textAppearanceTitleMedium`
  - `?attr/textAppearanceCaption`
  - `?attr/colorOnSurface`
  - `?attr/colorOutline`
- App theme is AppCompat-derived (DarkTheme.YouTube extends Base.DarkTheme → Theme.AppCompat.DayNight.NoActionBar). These Material3 attrs are NOT exposed by AppCompat, so resolution fails.

Fix options:
1. Wrap dialog inflation context with a Material3 theme overlay (e.g.
   `ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_Material3_Dialog_Alert)`)
   or use `MaterialAlertDialogBuilder` with a Material3-overlaid context.
2. Replace the offending `?attr/` refs with AppCompat-safe equivalents:
   - `?attr/textAppearanceListItem` instead of `?attr/textAppearanceTitleMedium`
   - `?attr/textAppearanceListItemSmall` instead of `?attr/textAppearanceCaption`
   - `?android:attr/textColorPrimary` instead of `?attr/colorOnSurface`
   - `?android:attr/dividerHorizontal` or `?attr/colorControlHighlight` instead of `?attr/colorOutline`

Consequence: dialog crash also blocks any test of `forceRefreshOne` because the "Refresh now" button is inside the dialog.

Evidence: `.sisyphus/evidence/final-qa/s6_crash_logcat.txt`,
`s6_crash_summary.txt`.
