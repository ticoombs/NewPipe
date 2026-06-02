/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription.dialog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.DayOfWeek
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.database.feed.model.SubscriptionUpdateInfoEntity

/**
 * Robolectric tests for [NextCheckInfoDialog]'s pure mapping helpers (T10).
 *
 * The dialog itself loads data asynchronously from Room, so we test the two
 * extracted `@VisibleForTesting` companion helpers directly. They cover the
 * non-trivial logic users will see: confidence buckets and detected-pattern
 * labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NextCheckInfoDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun newInstance_storesSubscriptionIdInArguments() {
        val dialog = NextCheckInfoDialog.newInstance(42L)
        assertThat(dialog.arguments).isNotNull
        assertThat(dialog.arguments!!.getLong("subscription_id", -1L)).isEqualTo(42L)
    }

    @Test
    fun confidenceLabel_highThreshold_returnsHigh() {
        val label = NextCheckInfoDialog.confidenceLabel(context, 0.8f)
        assertThat(label).isEqualTo("Confidence: High")

        // Boundary: exactly 0.7 should also be High
        val boundary = NextCheckInfoDialog.confidenceLabel(context, 0.7f)
        assertThat(boundary).isEqualTo("Confidence: High")
    }

    @Test
    fun confidenceLabel_mediumThreshold_returnsMedium() {
        val label = NextCheckInfoDialog.confidenceLabel(context, 0.5f)
        assertThat(label).isEqualTo("Confidence: Medium")

        // Boundary: exactly 0.4 should also be Medium
        val boundary = NextCheckInfoDialog.confidenceLabel(context, 0.4f)
        assertThat(boundary).isEqualTo("Confidence: Medium")

        // Just below high cutoff
        val justBelowHigh = NextCheckInfoDialog.confidenceLabel(context, 0.69f)
        assertThat(justBelowHigh).isEqualTo("Confidence: Medium")
    }

    @Test
    fun confidenceLabel_lowThreshold_returnsLow() {
        val label = NextCheckInfoDialog.confidenceLabel(context, 0.1f)
        assertThat(label).isEqualTo("Confidence: Low")

        // Zero
        assertThat(NextCheckInfoDialog.confidenceLabel(context, 0f))
            .isEqualTo("Confidence: Low")

        // Just below medium cutoff
        assertThat(NextCheckInfoDialog.confidenceLabel(context, 0.39f))
            .isEqualTo("Confidence: Low")
    }

    @Test
    fun patternLabel_weekdayPeriodic_displaysWeekday() {
        val info = SubscriptionUpdateInfoEntity(
            subscriptionId = 1L,
            lastUpdated = null,
            nextUpdate = null,
            detectedPattern = SubscriptionUpdateInfoEntity.PATTERN_WEEKDAY_PERIODIC,
            detectedWeekday = DayOfWeek.TUESDAY.value,
            confidence = 0.9f
        )

        val label = NextCheckInfoDialog.patternLabel(context, info)

        assertThat(label).contains("Tuesday")
        assertThat(label).startsWith("Detected pattern: ")

        // Active EWMA path: no weekday-specific text
        val active = info.copy(
            detectedPattern = SubscriptionUpdateInfoEntity.PATTERN_ACTIVE_EWMA
        )
        val activeLabel = NextCheckInfoDialog.patternLabel(context, active)
        assertThat(activeLabel).doesNotContain("Tuesday")
        assertThat(activeLabel).contains("averaging")

        // Null info → "new" learning label
        val newLabel = NextCheckInfoDialog.patternLabel(context, null)
        assertThat(newLabel).contains("Learning")
    }
}
