# Decisions — smart-subscription-scheduling

## [2026-05-15] Key Architectural Decisions

### DB Migration: 13 → 14
- New version constant: `DB_VER_14 = 14`
- New migration object: `MIGRATION_13_14`
- 4 new columns on `subscription_update_info`:
  - `backoff_multiplier REAL NOT NULL DEFAULT 1.0`
  - `detected_pattern INTEGER NOT NULL DEFAULT 0`
  - `detected_weekday INTEGER` (nullable)
  - `confidence REAL NOT NULL DEFAULT 0.0`

### Preference Key Decision
- Plan specifies adding `smart_scheduling_enabled_key` → `pref_smart_scheduling_enabled`
- This is SEPARATE from existing `feed_smart_update_scheduling_key`
- New preference: default `true`, controls subtitle visibility and analyzer behavior
- SmartSchedulingPrefs.isEnabled(context) helper in a new Kotlin file

### Algorithm Design (for T3)
- State machine: NEW (< 3 samples), WEEKDAY_PERIODIC (≥5 samples, ≥70% top-2 weekday), ACTIVE_EWMA, DORMANT
- α = 0.3 for EWMA
- Bounds: [1, 14] days
- No OffsetDateTime.now() inside analyzer — injected clock `() -> OffsetDateTime`
- Single class, no helper files

### FetchInterval Deletion (T6)
- DELETE FetchInterval.kt and FetchIntervalTest.kt in same commit as FeedLoadManager refactor
- ALL references must be removed before commit
