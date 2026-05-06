package org.schabi.newpipe.local.feed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trivial smoke test verifying Robolectric + AndroidX test dependencies resolve and
 * a Context can be obtained on the JVM. Establishes that the rest of the feed-related
 * unit tests can rely on this infrastructure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RobolectricSmokeTest {
    @Test
    fun robolectricProvidesApplicationContext() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertThat(context).isNotNull
        assertThat(context.packageName).isNotBlank
    }
}
