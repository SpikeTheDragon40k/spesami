package it.spesami.core

import java.util.UUID

/**
 * Global ids for synced records. Local SQLite ids are per-device autoincrement integers, so two phones
 * would give different things the same id; a uid is unique across devices and never changes.
 */
object Uids {
    private const val BUILTIN_PREFIX = "builtin:"

    /** A new random id (UUID v4). */
    fun random(): String = UUID.randomUUID().toString()

    /**
     * The seeded categories exist on every phone from the start, so their uid is derived from the builtin key:
     * the "Frutta e verdura" of two phones is recognised as the same category instead of being duplicated.
     */
    fun builtinCategory(builtinKey: String): String = BUILTIN_PREFIX + builtinKey
}
