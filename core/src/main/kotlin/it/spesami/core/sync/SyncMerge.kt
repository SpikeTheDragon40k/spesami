package it.spesami.core.sync

/**
 * Combining what two devices know. [merge] is commutative, associative and idempotent: the same files applied in
 * any order, any number of times, give the same state on every phone.
 */
object SyncMerge {
    /** Deletions are forgotten this long after they happened (by Google's server time, never a phone's clock). */
    const val TOMBSTONE_DAYS = 90L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun merge(a: SyncState, b: SyncState): SyncState {
        val records = HashMap<String, SyncRecord>()
        for (uid in a.records.keys + b.records.keys) {
            val ra = a.records[uid]
            val rb = b.records[uid]
            val merged = when {
                ra != null && rb != null -> mergeRecord(ra, rb)
                // Known to one side only, and older than the other side's horizon: the other side saw it and has
                // since forgotten its deletion. Bringing it back would undo that deletion.
                ra != null -> ra.takeUnless { forgotten(it, b.prunedBefore) }
                else -> rb!!.takeUnless { forgotten(it, a.prunedBefore) }
            } ?: continue
            records[uid] = merged
        }
        return prune(SyncState(records, maxOf(a.prunedBefore, b.prunedBefore)))
    }

    /**
     * Per field, the write with the higher stamp wins. A tombstone wins over every edit. The category of an
     * ingredient is special: a choice made by hand wins over an automatic classification, whatever the stamps,
     * so a rename on one phone (which re-classifies automatically) never undoes a category picked on another.
     */
    fun mergeRecord(a: SyncRecord, b: SyncRecord): SyncRecord {
        val deleted = listOfNotNull(a.d, b.d).maxOrNull()
        if (deleted != null) return SyncRecord(a.k, emptyMap(), deleted)
        val fields = HashMap<String, Field>(a.f)
        for ((name, fb) in b.f) {
            val fa = fields[name]
            fields[name] = if (fa == null) fb else pick(a.k, name, fa, fb)
        }
        return SyncRecord(a.k, fields, null)
    }

    private fun pick(kind: String, name: String, a: Field, b: Field): Field {
        val byPriority = if (kind == Kinds.INGREDIENT && name == "cat") manual(a).compareTo(manual(b)) else 0
        if (byPriority != 0) return if (byPriority > 0) a else b
        val byStamp = a.s.compareTo(b.s)
        if (byStamp != 0) return if (byStamp > 0) a else b
        // Equal stamps mean the same write; compare values anyway so the result never depends on the order.
        return if (a.v.orEmpty() >= b.v.orEmpty()) a else b
    }

    private fun manual(field: Field): Int = if (field.v?.startsWith("m:") == true) 1 else 0

    private fun forgotten(record: SyncRecord, prunedBefore: Long): Boolean {
        if (prunedBefore <= 0) return false
        val newest = record.newestStamp() ?: return true
        return Hlc.wall(newest) < prunedBefore
    }

    /** Drops deletions older than the state's horizon. */
    fun prune(state: SyncState): SyncState {
        if (state.prunedBefore <= 0) return state
        val kept = state.records.filterValues { r -> r.d == null || Hlc.wall(r.d) >= state.prunedBefore }
        return if (kept.size == state.records.size) state else state.copy(records = kept)
    }

    /**
     * Moves the horizon to [TOMBSTONE_DAYS] before [serverTime]: Google's clock (from Drive's responses), so a
     * phone with a wrong clock cannot make everyone forget deletions early. Without it, nothing is pruned.
     */
    fun advance(state: SyncState, serverTime: Long?): SyncState {
        if (serverTime == null) return state
        val horizon = serverTime - TOMBSTONE_DAYS * DAY_MS
        return if (horizon <= state.prunedBefore) state else prune(state.copy(prunedBefore = horizon))
    }
}
