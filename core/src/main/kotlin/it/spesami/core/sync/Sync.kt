package it.spesami.core.sync

/** A device seen in Drive, for the "Dispositivi" list. */
data class SyncDevice(val id: String, val name: String, val lastSync: Long, val isThisDevice: Boolean)

/** This device's sync data between runs (stored locally, never in backups). */
data class LocalSync(
    val deviceId: String,
    val state: SyncState = SyncState(),
    /** The data as last recorded or applied; null before the first sync, so everything local counts as new. */
    val last: Snapshot? = null,
)

data class SyncResult(
    val local: LocalSync,
    /** What the app's data must become. Contains everything local plus everything from the other devices. */
    val data: Snapshot,
    /** This device's file, to upload as [SyncFile.fileName]. */
    val ownFile: String,
    val devices: List<SyncDevice>,
    /** Plain-language problems with other devices' files (skipped), in Italian for the UI. */
    val problems: List<String>,
)

/**
 * The whole sync, with no network and no storage: the Drive layer downloads the other devices' files, calls
 * [run], uploads [SyncResult.ownFile] and applies [SyncResult.data].
 */
object Sync {
    /** Stamps local edits made since the last call; used after every change, also offline. */
    fun record(local: LocalSync, current: Snapshot, now: Long): LocalSync {
        val clock = Hlc.Clock(local.deviceId) { now }.observe(local.state.newestStamp())
        val state = SyncChanges.record(local.state, local.last ?: Snapshot.EMPTY, current, clock.next())
        return local.copy(state = state, last = current.copy(redirects = emptyMap()))
    }

    /**
     * Records local edits, merges the other devices' files, and returns the merged data. The first sync of a
     * phone that already has data merges it: every local record is new to the others, nothing is replaced.
     *
     * [peerFiles] are the texts of the other devices' files (this device's own is ignored). [serverTime] is
     * Google's clock from Drive's responses, used only to forget old deletions; null skips that.
     */
    fun run(
        local: LocalSync,
        current: Snapshot,
        peerFiles: List<String>,
        deviceName: String,
        appVersion: String,
        now: Long,
        serverTime: Long?,
    ): SyncResult {
        var recorded = record(local, current, now)
        val problems = mutableListOf<String>()
        val devices = mutableListOf(SyncDevice(local.deviceId, deviceName, now, isThisDevice = true))
        var state = recorded.state
        for (text in peerFiles) {
            when (val read = SyncFiles.decode(text)) {
                is SyncFileRead.Valid -> {
                    if (read.file.deviceId == local.deviceId) continue
                    state = SyncMerge.merge(state, read.file.state)
                    devices += SyncDevice(read.file.deviceId, read.file.deviceName, read.file.writtenAt, isThisDevice = false)
                }
                is SyncFileRead.TooNew -> problems +=
                    "«${read.deviceName}» usa una versione più recente di Spesami: aggiorna l'app su questo telefono per ricevere le sue modifiche."
                is SyncFileRead.Invalid -> problems += "Un file di sincronizzazione è stato ignorato: ${read.reason}."
            }
        }
        state = SyncMerge.advance(state, serverTime)
        val data = SyncMaterialize.snapshot(state)
        recorded = recorded.copy(state = state, last = data)
        val ownFile = SyncFiles.encode(
            SyncFile(
                deviceId = local.deviceId,
                deviceName = deviceName,
                appVersion = appVersion,
                writtenAt = now,
                prunedBefore = state.prunedBefore,
                records = state.records.toSortedMap(),
            )
        )
        return SyncResult(recorded, data, ownFile, devices.sortedWith(compareBy({ !it.isThisDevice }, { it.name }, { it.id })), problems)
    }
}
