package it.spesami.core.sync

import it.spesami.core.MealType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Stores [LocalSync] as one JSON text on the device (never in backups, never uploaded as such). */
object LocalSyncJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Serializable
    private data class Stored(
        val deviceId: String,
        val prunedBefore: Long = 0,
        val records: Map<String, SyncRecord> = emptyMap(),
        val last: StoredSnapshot? = null,
    )

    @Serializable
    private data class StoredSnapshot(
        val categories: List<C> = emptyList(),
        val ingredients: List<I> = emptyList(),
        val aliases: List<A> = emptyList(),
        val recipes: List<R> = emptyList(),
        val slots: List<S> = emptyList(),
        val purchases: List<W> = emptyList(),
        val extras: List<W> = emptyList(),
    )

    @Serializable private data class C(val uid: String, val name: String, val position: Int, val builtinKey: String? = null)
    @Serializable private data class I(val uid: String, val name: String, val categoryUid: String? = null, val categoryManual: Boolean = false)
    @Serializable private data class A(val uid: String, val ingredientUid: String, val alias: String)
    @Serializable private data class R(val uid: String, val name: String, val notes: String, val ingredientUids: List<String>)
    @Serializable private data class S(val weekStart: String, val day: Int, val meal: String, val recipeUid: String)
    @Serializable private data class W(val weekStart: String, val ingredientUid: String)

    fun encode(local: LocalSync): String = json.encodeToString(
        Stored.serializer(),
        Stored(local.deviceId, local.state.prunedBefore, local.state.records, local.last?.let(::store)),
    )

    /** Null if [text] is unreadable: the caller then starts over as on a first sync, which merges and loses nothing. */
    fun decode(text: String): LocalSync? = try {
        val s = json.decodeFromString(Stored.serializer(), text)
        LocalSync(s.deviceId, SyncState(s.records, s.prunedBefore), s.last?.let(::load))
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun store(s: Snapshot) = StoredSnapshot(
        s.categories.map { C(it.uid, it.name, it.position, it.builtinKey) },
        s.ingredients.map { I(it.uid, it.name, it.categoryUid, it.categoryManual) },
        s.aliases.map { A(it.uid, it.ingredientUid, it.alias) },
        s.recipes.map { R(it.uid, it.name, it.notes, it.ingredientUids) },
        s.slots.map { S(it.weekStart.toString(), it.day, it.meal.name, it.recipeUid) },
        s.purchases.map { W(it.weekStart.toString(), it.ingredientUid) },
        s.extras.map { W(it.weekStart.toString(), it.ingredientUid) },
    )

    private fun load(s: StoredSnapshot) = Snapshot(
        s.categories.map { SnapCategory(it.uid, it.name, it.position, it.builtinKey) },
        s.ingredients.map { SnapIngredient(it.uid, it.name, it.categoryUid, it.categoryManual) },
        s.aliases.map { SnapAlias(it.uid, it.ingredientUid, it.alias) },
        s.recipes.map { SnapRecipe(it.uid, it.name, it.notes, it.ingredientUids) },
        s.slots.map { SnapSlot(LocalDate.parse(it.weekStart), it.day, MealType.valueOf(it.meal), it.recipeUid) },
        s.purchases.map { SnapWeekItem(LocalDate.parse(it.weekStart), it.ingredientUid) }.toSet(),
        s.extras.map { SnapWeekItem(LocalDate.parse(it.weekStart), it.ingredientUid) }.toSet(),
    )
}
