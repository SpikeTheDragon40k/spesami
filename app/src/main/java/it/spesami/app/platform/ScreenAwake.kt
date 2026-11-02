package it.spesami.app.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.spesami.core.ScreenAwakeController

/**
 * [ScreenAwakeController] backed by the window's FLAG_KEEP_SCREEN_ON: no WakeLock, no permission, no service.
 * The system honours the flag only while the window is visible, the user can still lock the phone, and
 * timeouts and power saving are untouched. If someone else had already set the flag, it is left as found.
 */
class WindowScreenAwakeController(private val window: Window) : ScreenAwakeController {
    private var active = false
    private var setByUs = false

    override fun enable() {
        if (active) return
        active = true
        val alreadySet = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        setByUs = !alreadySet
        if (setByUs) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun disable() {
        if (!active) return
        active = false
        if (setByUs) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setByUs = false
    }
}

/**
 * Keeps the screen on while this composable is in the composition, [enabled] is true and the activity is started
 * (visible). Stopping the activity (app in background, screen locked) releases it; coming back re-applies it;
 * leaving the composition or a recreated activity always cleans up. Re-composition does nothing: the effect only
 * restarts when the activity, its lifecycle or [enabled] change.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle, enabled) {
        if (!enabled || activity == null) return@DisposableEffect onDispose { }
        val controller = WindowScreenAwakeController(activity.window)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.enable()
                Lifecycle.Event.ON_STOP -> controller.disable()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // replays ON_START if already started
        onDispose {
            lifecycle.removeObserver(observer)
            controller.disable()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
