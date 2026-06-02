package org.schabi.newpipe.local.subscription

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.xwray.groupie.Group
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionWithUpdateInfo
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.item.ChannelItem
import org.schabi.newpipe.local.subscription.item.FeedGroupCardGridItem
import org.schabi.newpipe.local.subscription.item.FeedGroupCardItem
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {
    private var feedDatabaseManager: FeedDatabaseManager = FeedDatabaseManager(application)
    private var subscriptionManager = SubscriptionManager(application)
    private val sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(application)
    private val smartSchedulingPrefKey: String =
        application.getString(R.string.smart_scheduling_enabled_key)
    private val smartSchedulingEnabledProcessor: BehaviorProcessor<Boolean> =
        BehaviorProcessor.createDefault(
            sharedPreferences.getBoolean(smartSchedulingPrefKey, true)
        )
    private val smartSchedulingPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == smartSchedulingPrefKey) {
                smartSchedulingEnabledProcessor.onNext(prefs.getBoolean(key, true))
            }
        }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(smartSchedulingPrefListener)
    }

    // true -> list view, false -> grid view
    private val listViewMode = BehaviorProcessor.createDefault(
        !shouldUseGridForSubscription(application)
    )
    private val listViewModeFlowable = listViewMode.distinctUntilChanged()

    private val mutableStateLiveData = MutableLiveData<SubscriptionState>()
    private val mutableFeedGroupsLiveData = MutableLiveData<Pair<List<Group>, Boolean>>()
    val stateLiveData: LiveData<SubscriptionState> = mutableStateLiveData
    val feedGroupsLiveData: LiveData<Pair<List<Group>, Boolean>> = mutableFeedGroupsLiveData

    private var feedGroupItemsDisposable = Flowable
        .combineLatest(
            feedDatabaseManager.groups(),
            listViewModeFlowable,
            ::Pair
        )
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .map { (feedGroups, listViewMode) ->
            Pair(
                feedGroups.map(if (listViewMode) ::FeedGroupCardItem else ::FeedGroupCardGridItem),
                listViewMode
            )
        }
        .subscribeOn(Schedulers.io())
        .subscribe(
            { mutableFeedGroupsLiveData.postValue(it) },
            { mutableStateLiveData.postValue(SubscriptionState.ErrorState(it)) }
        )

    private var stateItemsDisposable = gateNextUpdateBySmartScheduling(
        subscriptionManager.subscriptionsWithUpdateInfo(),
        smartSchedulingEnabledProcessor
    )
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .map {
            it.map { withInfo ->
                ChannelItem(
                    withInfo.subscription.toChannelInfoItem(),
                    withInfo.subscription.uid,
                    ChannelItem.ItemVersion.MINI,
                    nextUpdate = withInfo.nextUpdate
                )
            }
        }
        .subscribeOn(Schedulers.io())
        .subscribe(
            { mutableStateLiveData.postValue(SubscriptionState.LoadedState(it)) },
            { mutableStateLiveData.postValue(SubscriptionState.ErrorState(it)) }
        )

    override fun onCleared() {
        super.onCleared()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(smartSchedulingPrefListener)
        stateItemsDisposable.dispose()
        feedGroupItemsDisposable.dispose()
    }

    fun setListViewMode(newListViewMode: Boolean) {
        listViewMode.onNext(newListViewMode)
    }

    fun getListViewMode(): Boolean {
        return listViewMode.value ?: true
    }

    sealed class SubscriptionState {
        data class LoadedState(val subscriptions: List<Group>) : SubscriptionState()
        data class ErrorState(val error: Throwable? = null) : SubscriptionState()
    }

    companion object {

        /**
         * Returns whether to use GridLayout mode for Subscription Fragment.
         *
         * ### Current mapping:
         *
         *  | ItemViewMode | ItemVersion | Span count |
         *  |---|---|---|
         *  | AUTO | MINI | 1 |
         *  | LIST | MINI | 1 |
         *  | CARD | GRID | > 1 (ThemeHelper defined) |
         *  | GRID | GRID | > 1 (ThemeHelper defined) |
         *
         *  @see [SubscriptionViewModel.shouldUseGridForSubscription] to modify Layout Manager
         */
        fun shouldUseGridForSubscription(context: Context): Boolean {
            val itemViewMode = getItemViewMode(context)
            return itemViewMode == ItemViewMode.GRID || itemViewMode == ItemViewMode.CARD
        }

        /**
         * Combines the subscription list with the smart-scheduling-enabled preference flag.
         * When the preference is disabled, [SubscriptionWithUpdateInfo.nextUpdate] is forced
         * to `null` so the UI hides the "next check" line. When enabled, values pass through
         * unchanged. Re-emits whenever either upstream emits.
         */
        @VisibleForTesting
        @JvmStatic
        fun gateNextUpdateBySmartScheduling(
            subscriptions: Flowable<List<SubscriptionWithUpdateInfo>>,
            smartSchedulingEnabled: Flowable<Boolean>
        ): Flowable<List<SubscriptionWithUpdateInfo>> = Flowable.combineLatest(
            subscriptions,
            smartSchedulingEnabled.distinctUntilChanged(),
            ::Pair
        ).map { (items, enabled) ->
            if (enabled) {
                items
            } else {
                items.map { it.copy(nextUpdate = null) }
            }
        }
    }
}
