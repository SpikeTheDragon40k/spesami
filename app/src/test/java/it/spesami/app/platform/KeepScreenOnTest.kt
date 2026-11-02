package it.spesami.app.platform

import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import it.spesami.app.ui.SpesamiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Host mirroring how SpesamiApp shows "Modalità Spesa": a rememberSaveable flag (survives recreation, never a
 * fresh start) decides whether the mode, and so [KeepScreenOn], is in the composition.
 */
class ModeHostActivity : ComponentActivity() {
    var setMode: (Boolean) -> Unit = {}
    var setTheme: (Boolean) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keepOn = intent.getBooleanExtra(KEEP_ON, true)
        setContent {
            var mode by rememberSaveable { mutableStateOf(false) }
            var dark by rememberSaveable { mutableStateOf(false) }
            setMode = { mode = it }
            setTheme = { dark = it }
            SpesamiTheme(dark) {
                if (mode) {
                    KeepScreenOn(enabled = keepOn)
                    Text("Modalità Spesa")
                } else {
                    Text("Lista della spesa")
                }
            }
        }
    }

    companion object {
        const val KEEP_ON = "keep_on"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepScreenOnTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(keepOn: Boolean = true): ActivityController<ModeHostActivity> {
        val intent = android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), ModeHostActivity::class.java)
            .putExtra(ModeHostActivity.KEEP_ON, keepOn)
        return Robolectric.buildActivity(ModeHostActivity::class.java, intent).setup().also { idle() }
    }

    private fun ActivityController<ModeHostActivity>.keepOn() = get().window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0

    private fun ActivityController<ModeHostActivity>.mode(on: Boolean) {
        get().setMode(on)
        idle()
    }

    @Test
    fun enteringAndExitingTheModeSetsAndClearsTheFlag() {
        val c = launch()
        assertFalse(c.keepOn())
        c.mode(true)
        assertTrue(c.keepOn())
        c.mode(false) // exit / navigate away: back to normal timeout
        assertFalse(c.keepOn())
        c.mode(true) // re-entering works again
        assertTrue(c.keepOn())
    }

    @Test
    fun recompositionDoesNotReapplyOrLeak() {
        val c = launch()
        c.mode(true)
        repeat(3) { c.get().setTheme(it % 2 == 0); idle() } // Light <-> Dracula while in the mode
        assertTrue(c.keepOn())
        c.mode(false)
        assertFalse(c.keepOn())
    }

    @Test
    fun backgroundingRestoresNormalBehaviour() {
        val c = launch()
        c.mode(true)
        c.pause().stop()
        idle()
        assertFalse(c.keepOn())
        c.restart().start().resume()
        idle()
        assertTrue(c.keepOn())
    }

    @Test
    fun activityRecreationKeepsTheModeAndTheFlag() {
        val c = launch()
        c.mode(true)
        val oldWindow = c.get().window
        c.recreate()
        idle()
        assertTrue(c.get().window !== oldWindow)
        assertTrue(c.keepOn()) // mode restored, flag applied to the new window
        c.mode(false)
        assertFalse(c.keepOn())
    }

    @Test
    fun destroyingTheActivityCleansUp() {
        val c = launch()
        c.mode(true)
        val window = c.get().window
        c.pause().stop().destroy()
        idle()
        assertFalse(window.attributes.flags and FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun disabledSettingKeepsNormalTimeout() {
        val c = launch(keepOn = false)
        c.mode(true)
        assertFalse(c.keepOn())
        c.pause().stop().restart().start().resume()
        idle()
        assertFalse(c.keepOn())
    }
}
