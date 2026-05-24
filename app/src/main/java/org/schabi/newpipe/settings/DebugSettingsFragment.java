/*
 * SPDX-FileCopyrightText: 2017-2025 NewPipe contributors <https://newpipe.net>
 * SPDX-FileCopyrightText: 2025-2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings;

import android.os.Bundle;

import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.local.feed.notifications.NotificationWorker;

import java.util.Optional;

public class DebugSettingsFragment extends BasePreferenceFragment {
    private static final String DUMMY = "Dummy";

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        final Preference allowHeapDumpingPreference =
                requirePreference(R.string.allow_heap_dumping_key);
        final Preference showMemoryLeaksPreference =
                requirePreference(R.string.show_memory_leaks_key);
        final Preference checkNewStreamsPreference =
                findPreference(getString(R.string.check_new_streams_key));
        final Preference runFeedAutoUpdatePreference =
                findPreference(getString(R.string.run_feed_auto_update_now_key));
        final Preference crashTheAppPreference =
                requirePreference(R.string.crash_the_app_key);
        final Preference showErrorSnackbarPreference =
                requirePreference(R.string.show_error_snackbar_key);
        final Preference createErrorNotificationPreference =
                requirePreference(R.string.create_error_notification_key);

        assert allowHeapDumpingPreference != null;
        assert showMemoryLeaksPreference != null;
        assert checkNewStreamsPreference != null;
        assert runFeedAutoUpdatePreference != null;
        assert crashTheAppPreference != null;
        assert showErrorSnackbarPreference != null;
        assert createErrorNotificationPreference != null;


        final Optional<DebugSettingsBVDLeakCanaryAPI> optBVLeakCanary = getBVDLeakCanary();

        allowHeapDumpingPreference.setEnabled(optBVLeakCanary.isPresent());
        showMemoryLeaksPreference.setEnabled(optBVLeakCanary.isPresent());

        if (optBVLeakCanary.isPresent()) {
            final DebugSettingsBVDLeakCanaryAPI pdLeakCanary = optBVLeakCanary.get();

            showMemoryLeaksPreference.setOnPreferenceClickListener(preference -> {
                startActivity(pdLeakCanary.getNewLeakDisplayActivityIntent());
                return true;
            });
        } else {
            allowHeapDumpingPreference.setSummary(R.string.leak_canary_not_available);
            showMemoryLeaksPreference.setSummary(R.string.leak_canary_not_available);
        }

        checkNewStreamsPreference.setOnPreferenceClickListener(preference -> {
            NotificationWorker.runNow(preference.getContext());
            return true;
        });

        runFeedAutoUpdatePreference.setOnPreferenceClickListener(preference -> {
            org.schabi.newpipe.local.feed.notifications.FeedAutoUpdateWorker
                    .runNow(preference.getContext());
            return true;
        });

        crashTheAppPreference.setOnPreferenceClickListener(preference -> {
            throw new RuntimeException(DUMMY);
        });

        showErrorSnackbarPreference.setOnPreferenceClickListener(preference -> {
            ErrorUtil.showUiErrorSnackbar(DebugSettingsFragment.this,
                    DUMMY, new RuntimeException(DUMMY));
            return true;
        });

        createErrorNotificationPreference.setOnPreferenceClickListener(preference -> {
            ErrorUtil.createNotification(requireContext(),
                    new ErrorInfo(new RuntimeException(DUMMY), UserAction.UI_ERROR, DUMMY));
            return true;
        });
    }

    /**
     * Tries to find the {@link DebugSettingsBVDLeakCanaryAPI#IMPL_CLASS} and loads it if available.
     * @return An {@link Optional} which is empty if the implementation class couldn't be loaded.
     */
    private Optional<DebugSettingsBVDLeakCanaryAPI> getBVDLeakCanary() {
        try {
            // Try to find the implementation of the LeakCanary API
            return Optional.of((DebugSettingsBVDLeakCanaryAPI)
                    Class.forName(DebugSettingsBVDLeakCanaryAPI.IMPL_CLASS)
                            .getDeclaredConstructor()
                            .newInstance());
        } catch (final Exception e) {
            return Optional.empty();
        }
    }
}
