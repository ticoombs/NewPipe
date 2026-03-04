/*
 * SPDX-FileCopyrightText: 2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.database.subscription

import androidx.room.ColumnInfo
import androidx.room.Embedded
import java.time.OffsetDateTime
import org.schabi.newpipe.database.feed.model.SubscriptionUpdateInfoEntity

data class SubscriptionWithUpdateInfo(
    @Embedded
    val subscription: SubscriptionEntity,

    @ColumnInfo(name = SubscriptionUpdateInfoEntity.NEXT_UPDATE)
    val nextUpdate: OffsetDateTime?
)
