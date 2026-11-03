package it.spesami.core.sync

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** One field of a record: its value (null = none) and the stamp of the write that set it. */
@Serializable
data class Field(val v: String? = null, val s: String)

/**
 * One synced record: a kind ([Kinds]), its fields, and a tombstone [d] (the stamp of its deletion) for entities.
 * A deleted record keeps no fields: a deletion always wins over edits, so they no longer matter.
 */
@Serializable
data class SyncRecord(val k: String, val f: Map<String, Field> = emptyMap(), val d: String? = null) {
    fun value(name: String): String? = f[name]?.v

    fun newestStamp(): String? = (f.values.map { it.s } + listOfNotNull(d)).maxOrNull()
}

/**
 * Everything a device knows, keyed by record uid. [prunedBefore] (epoch ms, Google's server time) is the horizon
 * before which deletions have been forgotten (see [SyncMerge.prune]).
 */
data class SyncState(val records: Map<String, SyncRecord> = emptyMap(), val prunedBefore: Long = 0) {
    fun newestStamp(): String? = records.values.mapNotNull { it.newestStamp() }.maxOrNull()
}

/**
 * Record kinds and their fields. Entities (category, ingredient, alias, recipe) use their own uid and can be
 * deleted (tombstone). Relations use a uid built from what they relate (see [Keys]) and are switched on and off
 * by a field instead, so they can come back: a tick can be unticked and ticked again.
 */
object Kinds {
    /** Fields: name, pos (order), builtin (seeded category key). */
    const val CATEGORY = "cat"

    /** Fields: name, cat (`m:<categoryUid>` chosen by hand or `a:<categoryUid>` automatic; empty uid = none), into (merged into). */
    const val INGREDIENT = "ing"

    /** Fields: ing (owner ingredient uid), name. */
    const val ALIAS = "alias"

    /** Fields: name, notes. */
    const val RECIPE = "rec"

    /** One ingredient of one recipe. Fields: in ("1"/"0"), pos. */
    const val RECIPE_INGREDIENT = "ri"

    /** One lunch or dinner. Field: rec (recipe uid, null = empty). */
    const val SLOT = "slot"

    /** A purchased tick of one week's checklist. Field: on ("1"/"0"). */
    const val PURCHASED = "tick"

    /** A product added by hand to one week's list. Field: on ("1"/"0"). */
    const val EXTRA = "extra"

    val entities = setOf(CATEGORY, INGREDIENT, ALIAS, RECIPE)
    val relations = setOf(RECIPE_INGREDIENT, SLOT, PURCHASED, EXTRA)
    val all = entities + relations
}

/** Uids of relation records. Entity uids never contain [SEP]. */
object Keys {
    const val SEP = '|'

    fun recipeIngredient(recipe: String, ingredient: String) = "${Kinds.RECIPE_INGREDIENT}$SEP$recipe$SEP$ingredient"

    fun slot(week: LocalDate, day: Int, meal: String) = "${Kinds.SLOT}$SEP$week$SEP$day$SEP$meal"

    fun purchased(week: LocalDate, ingredient: String) = "${Kinds.PURCHASED}$SEP$week$SEP$ingredient"

    fun extra(week: LocalDate, ingredient: String) = "${Kinds.EXTRA}$SEP$week$SEP$ingredient"

    fun parts(uid: String): List<String> = uid.split(SEP)
}
