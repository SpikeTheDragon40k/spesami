package it.spesami.app.platform

import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowScreenAwakeControllerTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val window = activity.window
    private fun keepOn() = window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun enableAndDisableAreIdempotent() {
        val controller = WindowScreenAwakeController(window)
        assertFalse(keepOn())
        controller.enable()
        controller.enable()
        assertTrue(keepOn())
        controller.disable()
        assertFalse(keepOn())
        controller.disable()
        assertFalse(keepOn())
    }

    @Test
    fun otherFlagsAreNotDisturbed() {
        window.addFlags(FLAG_SECURE)
        val before = window.attributes.flags
        WindowScreenAwakeController(window).apply { enable(); disable() }
        assertEquals(before, window.attributes.flags)
        assertTrue(window.attributes.flags and FLAG_SECURE != 0)
    }

    @Test
    fun aFlagSetByAnotherFeatureIsLeftOn() {
        window.addFlags(FLAG_KEEP_SCREEN_ON) // e.g. some other screen asked for it
        WindowScreenAwakeController(window).apply { enable(); disable() }
        assertTrue(keepOn())
    }
}
