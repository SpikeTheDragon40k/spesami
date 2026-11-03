package it.spesami.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One device's file in Drive (`device-<deviceId>.json`): the complete state it knows. Only that device writes it;
 * every other device only reads it, so no two devices ever write the same file.
 *
 * [version] grows with additive changes that older apps can safely ignore; [minReaderVersion] grows only when
 * an older app would misread the file. A reader older than [minReaderVersion] skips the whole file.
 */
@Serializable
data class SyncFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val minReaderVersion: Int = MIN_READER_VERSION,
    val deviceId: String,
    val deviceName: String,
    val appVersion: String = "",
    /** Epoch ms, by the writing phone's clock: shown as "last sync", never used to order edits. */
    val writtenAt: Long,
    val prunedBefore: Long = 0,
    val records: Map<String, SyncRecord> = emptyMap(),
) {
    val state: SyncState get() = SyncState(records, prunedBefore)

    companion object {
        const val FORMAT = "spesami-sync"
        const val VERSION = 1
        const val MIN_READER_VERSION = 1

        /** The newest [minReaderVersion] this app can read. */
        const val READER_VERSION = 1

        const val NAME_PREFIX = "device-"
        const val NAME_SUFFIX = ".json"

        fun fileName(deviceId: String) = "$NAME_PREFIX$deviceId$NAME_SUFFIX"

        fun deviceIdOf(fileName: String): String? =
            fileName.takeIf { it.startsWith(NAME_PREFIX) && it.endsWith(NAME_SUFFIX) }?.removePrefix(NAME_PREFIX)?.removeSuffix(NAME_SUFFIX)
    }
}

sealed interface SyncFileRead {
    data class Valid(val file: SyncFile) : SyncFileRead

    /** Written by a newer app this one cannot read: skipped entirely, never half-applied. */
    data class TooNew(val deviceName: String, val minReaderVersion: Int) : SyncFileRead

    data class Invalid(val reason: String) : SyncFileRead
}

object SyncFiles {
    private val json = Json {
        ignoreUnknownKeys = true // fields added by newer versions of the same format
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(file: SyncFile): String = json.encodeToString(SyncFile.serializer(), file)

    /** Parses and checks a whole file before any of it is used. */
    fun decode(text: String): SyncFileRead {
        val file = try {
            json.decodeFromString(SyncFile.serializer(), text)
        } catch (e: SerializationException) {
            return SyncFileRead.Invalid("non è un file di sincronizzazione leggibile")
        } catch (e: IllegalArgumentException) {
            return SyncFileRead.Invalid("non è un file di sincronizzazione leggibile")
        }
        if (file.format != SyncFile.FORMAT) return SyncFileRead.Invalid("non è un file di sincronizzazione di Spesami")
        if (file.minReaderVersion > SyncFile.READER_VERSION) return SyncFileRead.TooNew(file.deviceName, file.minReaderVersion)
        if (!Regex("[A-Za-z0-9_-]{1,64}").matches(file.deviceId)) return SyncFileRead.Invalid("identificativo del dispositivo non valido")
        for ((uid, r) in file.records) {
            val stamps = r.f.values.map { it.s } + listOfNotNull(r.d)
            if (stamps.any { !Hlc.isValid(it) }) return SyncFileRead.Invalid("contiene una modifica senza data valida")
            if (uid.isBlank() || uid.length > 300) return SyncFileRead.Invalid("contiene un identificativo non valido")
            if (r.k in Kinds.entities && Keys.SEP in uid) return SyncFileRead.Invalid("contiene un identificativo non valido")
        }
        // Kinds this version does not know come from a newer app that declared them safe to ignore.
        return SyncFileRead.Valid(file.copy(records = file.records.filterValues { it.k in Kinds.all }))
    }
}
