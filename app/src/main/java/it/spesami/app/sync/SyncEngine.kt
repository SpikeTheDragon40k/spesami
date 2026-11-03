package it.spesami.app.sync

import it.spesami.app.data.Repository
import it.spesami.core.Backup
import it.spesami.core.Uids
import it.spesami.core.sync.LocalSync
import it.spesami.core.sync.LocalSyncJson
import it.spesami.core.sync.Snapshot
import it.spesami.core.sync.Sync
import it.spesami.core.sync.SyncDevice
import it.spesami.core.sync.SyncResult
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The database side of the sync. Every method runs on the app's single database thread, so it never interleaves
 * with the user's edits; the network part ([it.spesami.core.sync.SyncExchange]) runs in between, elsewhere.
 * Everything here is stored in `sync_state`, which is never part of a JSON backup.
 */
class SyncEngine(
    private val repository: Repository,
    private val backupDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Prepared(val local: LocalSync, val current: Snapshot)

    data class Info(
        val enabled: Boolean,
        val lastSync: Long?,
        val error: String?,
        val needsReconnect: Boolean,
        val devices: List<SyncDevice>,
        val problems: List<String>,
        val firstBackup: String?,
    )

    val enabled: Boolean get() = repository.syncValue(ENABLED) == "1"

    fun setEnabled(on: Boolean) {
        repository.setSyncValue(ENABLED, if (on) "1" else null)
        if (!on) setError(null)
    }

    fun setError(message: String?, needsReconnect: Boolean = false) {
        repository.setSyncValue(ERROR, message)
        repository.setSyncValue(RECONNECT, if (needsReconnect) "1" else null)
    }

    fun info() = Info(
        enabled = enabled,
        lastSync = repository.syncValue(LAST_SYNC)?.toLongOrNull(),
        error = repository.syncValue(ERROR),
        needsReconnect = repository.syncValue(RECONNECT) == "1",
        devices = repository.syncValue(DEVICES).orEmpty().lines().filter { it.isNotBlank() }.mapNotNull(::decodeDevice),
        problems = repository.syncValue(PROBLEMS).orEmpty().lines().filter { it.isNotBlank() },
        firstBackup = repository.syncValue(FIRST_BACKUP),
    )

    /** This device's id: random, made once, kept even if the sync data must be rebuilt. */
    private fun deviceId(): String = repository.syncValue(DEVICE_ID) ?: Uids.random().also { repository.setSyncValue(DEVICE_ID, it) }

    fun local(): LocalSync = repository.syncValue(LOCAL)?.let(LocalSyncJson::decode)?.takeIf { it.deviceId == deviceId() } ?: LocalSync(deviceId())

    /**
     * After every write: stamps what changed, so each edit carries the time it was made, also offline. Before the
     * first sync there is nothing to compare with (everything is new then), so only stale merges are forgotten.
     * Returns true if something synced changed.
     */
    fun recordLocalChange(): Boolean {
        val local = local()
        if (local.last == null) {
            repository.clearRedirects()
            return false
        }
        val current = repository.syncSnapshot()
        val recorded = Sync.record(local, current, now())
        val changed = recorded.state.records != local.state.records
        if (changed) repository.setSyncValue(LOCAL, LocalSyncJson.encode(recorded))
        repository.clearRedirects(current.redirects.keys)
        return changed
    }

    /** Before going to Drive. The first sync of a phone with data saves a JSON backup of it first. */
    fun prepare(): Prepared {
        val local = local()
        if (local.last == null && repository.hasUserData()) {
            backupDir.mkdirs()
            val stamp = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val file = File(backupDir, "prima-sincronizzazione-${stamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))}.json")
            file.writeText(Backup.encode(repository.load(), stamp.toString()))
            repository.setSyncValue(FIRST_BACKUP, file.absolutePath)
        }
        return Prepared(local, repository.syncSnapshot())
    }

    /**
     * After Drive: applies the merged data, unless the user edited something meanwhile. Then nothing is applied, the
     * edits stay, and the next sync (scheduled by the caller) merges them. Returns whether the data was applied.
     */
    fun finish(prepared: Prepared, result: SyncResult): Boolean {
        val applied = repository.applySnapshot(prepared.current, result.data)
        val saved = if (applied) result.local else result.local.copy(last = prepared.current.copy(redirects = emptyMap()))
        repository.setSyncValue(LOCAL, LocalSyncJson.encode(saved))
        repository.clearRedirects(prepared.current.redirects.keys)
        repository.setSyncValue(LAST_SYNC, now().toString())
        repository.setSyncValue(DEVICES, result.devices.joinToString("\n", transform = ::encodeDevice))
        repository.setSyncValue(PROBLEMS, result.problems.joinToString("\n").ifEmpty { null })
        setError(null)
        return applied
    }

    private fun encodeDevice(d: SyncDevice) = listOf(d.id, d.name.replace('\t', ' ').replace('\n', ' '), d.lastSync, if (d.isThisDevice) 1 else 0).joinToString("\t")

    private fun decodeDevice(line: String): SyncDevice? {
        val p = line.split('\t').takeIf { it.size == 4 } ?: return null
        return SyncDevice(p[0], p[1], p[2].toLongOrNull() ?: return null, p[3] == "1")
    }

    companion object {
        private const val DEVICE_ID = "device_id"
        private const val LOCAL = "local"
        private const val ENABLED = "enabled"
        private const val LAST_SYNC = "last_sync"
        private const val ERROR = "error"
        private const val RECONNECT = "needs_reconnect"
        private const val DEVICES = "devices"
        private const val PROBLEMS = "problems"
        private const val FIRST_BACKUP = "first_backup"

        /** "oggi alle 14:32", "ieri alle 9:05", "3 novembre alle 18:00". */
        fun describeTime(epochMs: Long, today: java.time.LocalDate = java.time.LocalDate.now()): String {
            val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
            val time = t.format(DateTimeFormatter.ofPattern("H:mm"))
            val day = when (t.toLocalDate()) {
                today -> "oggi"
                today.minusDays(1) -> "ieri"
                else -> t.format(DateTimeFormatter.ofPattern(if (t.year == today.year) "d MMMM" else "d MMMM yyyy", java.util.Locale.ITALIAN))
            }
            return "$day alle $time"
        }
    }
}
