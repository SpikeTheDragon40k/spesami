package it.spesami.app.sync

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import it.spesami.core.sync.DriveClient
import it.spesami.core.sync.DriveException
import it.spesami.core.sync.DriveSync
import it.spesami.core.sync.SyncExchange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the optional Google Drive sync while the app is open: when it starts, when it comes back to the foreground,
 * a few seconds after a change, and on "Sincronizza ora". No background service: offline, nothing happens and
 * the next sync catches up. Database steps run on [db] (the app's single writer), the network on IO.
 */
class SyncManager(
    private val app: Application,
    private val engine: SyncEngine,
    private val db: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val onApplied: () -> Unit,
    private val auth: GoogleAuth = GoogleAuth(app),
    private val exchange: () -> SyncExchange = { DriveSync(DriveClient(UrlConnectionTransport(), auth)) },
) {
    data class Status(val running: Boolean = false, val info: SyncEngine.Info? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private var pending: Job? = null

    init {
        scope.launch { refresh() }
    }

    private suspend fun refresh(running: Boolean = _status.value.running) {
        val info = withContext(db) { engine.info() }
        _status.value = Status(running, info)
    }

    /** Called on the database thread right after each write. */
    fun onLocalChange() {
        if (engine.recordLocalChange() && engine.enabled) schedule(CHANGE_DELAY_MS)
    }

    /** App opened or back in the foreground, or "Sincronizza ora". */
    fun requestSync() = schedule(0)

    private fun schedule(delayMs: Long) {
        pending?.cancel() // only the wait: a sync already talking to Drive is never interrupted
        pending = scope.launch {
            delay(delayMs)
            scope.launch { run() }
        }
    }

    private suspend fun run() = mutex.withLock {
        if (!withContext(db) { engine.enabled }) return@withLock
        refresh(running = true)
        try {
            val prepared = withContext(db) { engine.prepare() }
            val result = withContext(Dispatchers.IO) { exchange().sync(prepared.local, prepared.current, deviceName(), appVersion(), System.currentTimeMillis()) }
            val applied = withContext(db) { engine.finish(prepared, result) }
            if (applied) withContext(db) { onApplied() } else schedule(RETRY_AFTER_EDIT_MS)
        } catch (e: DriveException) {
            withContext(db) { engine.setError(describe(e), needsReconnect = e.kind == DriveException.Kind.UNAUTHORIZED) }
        } catch (e: Exception) {
            withContext(db) { engine.setError("Sincronizzazione non riuscita: ${e.message ?: e.javaClass.simpleName}") }
        }
        refresh(running = false)
    }

    // --- Connect / disconnect

    /** "Connetti": asks Google for access; [launchConsent] shows Google's account and consent screen if needed. */
    fun connect(launchConsent: (android.app.PendingIntent) -> Unit, onFailure: (String) -> Unit) {
        auth.start { start ->
            when (start) {
                GoogleAuth.Start.Granted -> enable()
                is GoogleAuth.Start.NeedsConsent -> launchConsent(start.intent)
                is GoogleAuth.Start.Failed -> onFailure(start.message)
            }
        }
    }

    /** The consent screen's result. */
    fun finishConnect(data: Intent?): Boolean = auth.finish(data).also { if (it) enable() }

    private fun enable() {
        scope.launch {
            withContext(db) {
                engine.setEnabled(true)
                engine.setError(null)
            }
            refresh()
            requestSync()
        }
    }

    /**
     * "Disconnetti": this phone stops syncing and keeps all its data. Google access is not revoked here, because
     * revoking would also cut off the other phones using the same Google account.
     */
    fun disconnect() {
        pending?.cancel()
        scope.launch {
            mutex.withLock { withContext(db) { engine.setEnabled(false) } }
            refresh()
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    private fun appVersion(): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    companion object {
        const val CHANGE_DELAY_MS = 5_000L
        private const val RETRY_AFTER_EDIT_MS = 2_000L

        fun describe(e: DriveException): String = when (e.kind) {
            DriveException.Kind.OFFLINE -> "Nessuna connessione: la sincronizzazione riprenderà appena possibile."
            DriveException.Kind.UNAUTHORIZED -> "L'accesso a Google Drive non è più valido: tocca «Connetti di nuovo»."
            DriveException.Kind.TEMPORARY -> "Google Drive non risponde in questo momento: riprovo più tardi."
            DriveException.Kind.FAILED -> "Sincronizzazione non riuscita: ${e.message}."
        }
    }
}
