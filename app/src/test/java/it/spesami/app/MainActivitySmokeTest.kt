package it.spesami.app

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real activity and view model start without crashing (guards view-model initialisation order). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySmokeTest {
    @Test
    fun launches() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        ViewModelProvider(activity)[AppViewModel::class.java] // created by the activity, must not throw
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
