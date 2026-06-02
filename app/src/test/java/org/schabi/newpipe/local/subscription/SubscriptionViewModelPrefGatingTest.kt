/*
 * SPDX-FileCopyrightText: 2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.database.subscription.SubscriptionWithUpdateInfo

/**
 * Pins down the behavior of
 * [SubscriptionViewModel.gateNextUpdateBySmartScheduling]: when the
 * smart-scheduling preference is disabled, the `nextUpdate` field on every
 * emitted [SubscriptionWithUpdateInfo] must be forced to `null` so that the
 * "next check" UI line stays hidden. When enabled, values pass through
 * unchanged. Toggling the upstream pref flag must re-emit immediately.
 */
class SubscriptionViewModelPrefGatingTest {

    private val nextUpdateA: OffsetDateTime = OffsetDateTime.parse("2030-01-01T00:00:00Z")
    private val nextUpdateB: OffsetDateTime = OffsetDateTime.parse("2030-02-01T00:00:00Z")

    private fun subscription(uid: Long, name: String): SubscriptionEntity {
        val sub = SubscriptionEntity()
        sub.uid = uid
        sub.serviceId = 0
        sub.url = "https://example.com/$name"
        sub.name = name
        return sub
    }

    private val sampleItems: List<SubscriptionWithUpdateInfo> = listOf(
        SubscriptionWithUpdateInfo(subscription(1L, "alpha"), nextUpdateA),
        SubscriptionWithUpdateInfo(subscription(2L, "beta"), nextUpdateB),
        SubscriptionWithUpdateInfo(subscription(3L, "gamma"), null)
    )

    @Test
    fun prefEnabled_emitsItemsWithNextUpdate() {
        val gated = SubscriptionViewModel.gateNextUpdateBySmartScheduling(
            Flowable.just(sampleItems),
            Flowable.just(true)
        )

        val emissions = gated.test().also { it.awaitCount(1) }.values()

        assertThat(emissions).hasSize(1)
        assertThat(emissions[0]).isEqualTo(sampleItems)
        assertThat(emissions[0].map { it.nextUpdate })
            .containsExactly(nextUpdateA, nextUpdateB, null)
    }

    @Test
    fun prefDisabled_emitsItemsWithNullNextUpdate() {
        val gated = SubscriptionViewModel.gateNextUpdateBySmartScheduling(
            Flowable.just(sampleItems),
            Flowable.just(false)
        )

        val emissions = gated.test().also { it.awaitCount(1) }.values()

        assertThat(emissions).hasSize(1)
        assertThat(emissions[0].map { it.nextUpdate }).containsOnlyNulls()
        // subscriptions themselves preserved
        assertThat(emissions[0].map { it.subscription.uid })
            .containsExactly(1L, 2L, 3L)
    }

    @Test
    fun prefToggled_reEmitsItems() {
        val itemsProcessor = BehaviorProcessor.createDefault(sampleItems)
        val prefProcessor = BehaviorProcessor.createDefault(true)

        val subscriber = SubscriptionViewModel.gateNextUpdateBySmartScheduling(
            itemsProcessor,
            prefProcessor
        ).test()

        // First emission: enabled → values preserved
        subscriber.awaitCount(1)
        assertThat(subscriber.values()[0].map { it.nextUpdate })
            .containsExactly(nextUpdateA, nextUpdateB, null)

        // Toggle pref to disabled → re-emit with all nulls
        prefProcessor.onNext(false)
        subscriber.awaitCount(2)
        assertThat(subscriber.values()[1].map { it.nextUpdate }).containsOnlyNulls()

        // Toggle back to enabled → re-emit with originals
        prefProcessor.onNext(true)
        subscriber.awaitCount(3)
        assertThat(subscriber.values()[2].map { it.nextUpdate })
            .containsExactly(nextUpdateA, nextUpdateB, null)

        subscriber.assertNoErrors()
        assertThat(subscriber.values()).hasSize(3)
    }
}
