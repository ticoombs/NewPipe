/*
 * SPDX-FileCopyrightText: 2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.SubscriptionUpdateInfoEntity
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.feed.service.FeedLoadManager
import org.schabi.newpipe.util.Localization

/**
 * Dialog showing the smart-scheduler state for a single subscription:
 * detected upload pattern, predicted next check time, recent uploads, and a
 * "Refresh now" button that forces an immediate refresh.
 */
class NextCheckInfoDialog : DialogFragment() {

    private val disposables = CompositeDisposable()
    private var subscriptionId: Long = -1L
    private var rootView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subscriptionId = arguments?.getLong(ARG_SUBSCRIPTION_ID, -1L) ?: -1L
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_next_check_info, null, false)
        rootView = view

        // Initial bind with placeholders; real data is loaded asynchronously.
        bindLoading(view)
        loadAndBind()

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.next_check_info_title)
            .setView(view)
            .setPositiveButton(R.string.next_check_refresh_now, null)
            .setNegativeButton(R.string.next_check_close, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    button?.setOnClickListener { doRefresh(button) }
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposables.clear()
        rootView = null
    }

    private fun bindLoading(view: View) {
        view.findViewById<TextView>(R.id.nextCheckInfoSummary).text = LOADING_LABEL
        view.findViewById<TextView>(R.id.nextCheckInfoPattern).text = ""
        view.findViewById<TextView>(R.id.nextCheckInfoInterval).text = ""
        view.findViewById<TextView>(R.id.nextCheckInfoConfidence).text = ""
        view.findViewById<TextView>(R.id.nextCheckInfoUploadsList).text = EM_DASH
        view.findViewById<TextView>(R.id.nextCheckInfoLastRefresh).text = ""
    }

    private fun loadAndBind() {
        val ctx = requireContext().applicationContext
        disposables.add(
            Single.fromCallable {
                val dao = FeedDatabaseManager(ctx).database().feedDAO()
                val info = dao.getUpdateInfo(subscriptionId)
                val uploads = dao.getRecentUploadDates(
                    subscriptionId,
                    RECENT_UPLOADS_LIMIT,
                    OffsetDateTime.now(ZoneOffset.UTC)
                )
                LoadedState(info, uploads)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { state -> rootView?.let { bindViews(it, state.info, state.uploads) } },
                    { /* leave the loading placeholders; silent failure is fine here */ }
                )
        )
    }

    private fun bindViews(
        view: View,
        info: SubscriptionUpdateInfoEntity?,
        uploads: List<OffsetDateTime>
    ) {
        val summaryView = view.findViewById<TextView>(R.id.nextCheckInfoSummary)
        val patternView = view.findViewById<TextView>(R.id.nextCheckInfoPattern)
        val intervalView = view.findViewById<TextView>(R.id.nextCheckInfoInterval)
        val confidenceView = view.findViewById<TextView>(R.id.nextCheckInfoConfidence)
        val uploadsView = view.findViewById<TextView>(R.id.nextCheckInfoUploadsList)
        val lastRefreshView = view.findViewById<TextView>(R.id.nextCheckInfoLastRefresh)

        // Summary: next check time
        val nextUpdate = info?.nextUpdate
        summaryView.text = if (nextUpdate != null) {
            getString(R.string.next_check_info_summary, Localization.relativeTime(nextUpdate))
        } else {
            getString(R.string.next_check_info_pattern_new)
        }

        // Detected pattern
        patternView.text = patternLabel(requireContext(), info)

        // Average interval (TODO: replace with a proper duration_days plural when available)
        val intervalDays = info?.fetchInterval ?: SubscriptionUpdateInfoEntity.DEFAULT_FETCH_INTERVAL
        val intervalText = resources.getQuantityString(R.plurals.days, intervalDays, intervalDays)
        intervalView.text = getString(R.string.next_check_info_interval, intervalText)

        // Confidence bucket
        val confidence = info?.confidence ?: 0f
        confidenceView.text = confidenceLabel(requireContext(), confidence)

        // Recent uploads (bulleted, newline-separated)
        uploadsView.text = if (uploads.isEmpty()) {
            EM_DASH
        } else {
            uploads.joinToString(separator = "\n") { "• ${Localization.relativeTime(it)}" }
        }

        // Last refresh
        val lastUpdated = info?.lastUpdated
        lastRefreshView.text = if (lastUpdated != null) {
            getString(R.string.next_check_info_last_refresh, Localization.relativeTime(lastUpdated))
        } else {
            EM_DASH
        }
    }

    private fun doRefresh(button: Button) {
        button.isEnabled = false
        val originalLabel = button.text
        button.text = LOADING_LABEL

        val ctx = requireContext().applicationContext
        disposables.add(
            FeedLoadManager(ctx).forceRefreshOne(subscriptionId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        loadAndBind()
                        if (isAdded) {
                            button.isEnabled = true
                            button.text = originalLabel
                        }
                    },
                    { error ->
                        if (isAdded) {
                            Toast.makeText(
                                context,
                                error.message ?: getString(R.string.general_error),
                                Toast.LENGTH_SHORT
                            ).show()
                            button.isEnabled = true
                            button.text = originalLabel
                        }
                    }
                )
        )
    }

    private data class LoadedState(
        val info: SubscriptionUpdateInfoEntity?,
        val uploads: List<OffsetDateTime>
    )

    companion object {
        private const val ARG_SUBSCRIPTION_ID = "subscription_id"
        private const val LOADING_LABEL = "…"
        private const val RECENT_UPLOADS_LIMIT = 4
        private const val CONFIDENCE_HIGH = 0.7f
        private const val CONFIDENCE_MEDIUM = 0.4f
        private const val EM_DASH = "—"

        @VisibleForTesting
        @JvmStatic
        fun confidenceLabel(context: android.content.Context, confidence: Float): String = when {
            confidence >= CONFIDENCE_HIGH ->
                context.getString(R.string.next_check_info_confidence_high)

            confidence >= CONFIDENCE_MEDIUM ->
                context.getString(R.string.next_check_info_confidence_medium)

            else -> context.getString(R.string.next_check_info_confidence_low)
        }

        @VisibleForTesting
        @JvmStatic
        fun patternLabel(
            context: android.content.Context,
            info: SubscriptionUpdateInfoEntity?
        ): String = when (info?.detectedPattern) {
            SubscriptionUpdateInfoEntity.PATTERN_WEEKDAY_PERIODIC -> {
                val weekdayValue = (info.detectedWeekday ?: DayOfWeek.MONDAY.value)
                    .coerceIn(1, 7)
                val weekdayName = DayOfWeek.of(weekdayValue)
                    .getDisplayName(TextStyle.FULL, Locale.getDefault())
                context.getString(R.string.next_check_info_pattern_weekday, weekdayName)
            }

            SubscriptionUpdateInfoEntity.PATTERN_ACTIVE_EWMA ->
                context.getString(R.string.next_check_info_pattern_active)

            SubscriptionUpdateInfoEntity.PATTERN_DORMANT ->
                context.getString(R.string.next_check_info_pattern_dormant, info.backoffMultiplier)

            else -> context.getString(R.string.next_check_info_pattern_new)
        }

        @JvmStatic
        fun newInstance(subscriptionId: Long): NextCheckInfoDialog = NextCheckInfoDialog().apply {
            arguments = Bundle().apply { putLong(ARG_SUBSCRIPTION_ID, subscriptionId) }
        }
    }
}
