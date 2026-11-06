package it.spesami.core.sync

import it.spesami.core.AppData
import it.spesami.core.MealType
import java.time.LocalDate

data class SnapCategory(val uid: String, val name: String, val position: Int, val builtinKey: String? = null)

/** [categoryUid] null = "Da classificare". */
data class SnapIngredient(val uid: String, val name: String, val categoryUid: String? = null, val categoryManual: Boolean = false)

data class SnapAlias(val uid: String, val ingredientUid: String, val alias: String)

/** [color] is a [it.spesami.core.RecipeColor.key], null for none. */
data class SnapRecipe(val uid: String, val name: String, val notes: String, val ingredientUids: List<String>, val color: String? = null)

data class SnapSlot(val weekStart: LocalDate, val day: Int, val meal: MealType, val recipeUid: String)

data class SnapWeekItem(val weekStart: LocalDate, val ingredientUid: String)

/**
 * The synced part of the app's data, with relationships by global id instead of local SQLite id. Preferences
 * are not part of it: they belong to the device.
 *
 * [redirects] lists ingredients merged into another one ("Unisci", alias) since the last snapshot, by uid, so the
 * merge reaches the other devices as a merge rather than as a deletion. Not part of equality.
 */
class Snapshot(
    categories: List<SnapCategory> = emptyList(),
    ingredients: List<SnapIngredient> = emptyList(),
    aliases: List<SnapAlias> = emptyList(),
    recipes: List<SnapRecipe> = emptyList(),
    slots: List<SnapSlot> = emptyList(),
    purchases: Set<SnapWeekItem> = emptySet(),
    extras: Set<SnapWeekItem> = emptySet(),
    val redirects: Map<String, String> = emptyMap(),
) {
    /** In list order, positions 0..n-1. */
    val categories: List<SnapCategory> = categories.sortedWith(compareBy({ it.position }, { it.uid })).mapIndexed { i, c -> c.copy(position = i) }
    val ingredients: List<SnapIngredient> = ingredients.sortedBy { it.uid }
    val aliases: List<SnapAlias> = aliases.sortedBy { it.uid }
    val recipes: List<SnapRecipe> = recipes.sortedBy { it.uid }
    val slots: List<SnapSlot> = slots.sortedWith(compareBy({ it.weekStart }, { it.day }, { it.meal }))
    val purchases: Set<SnapWeekItem> = purchases
    val extras: Set<SnapWeekItem> = extras

    fun copy(
        categories: List<SnapCategory> = this.categories,
        ingredients: List<SnapIngredient> = this.ingredients,
        aliases: List<SnapAlias> = this.aliases,
        recipes: List<SnapRecipe> = this.recipes,
        slots: List<SnapSlot> = this.slots,
        purchases: Set<SnapWeekItem> = this.purchases,
        extras: Set<SnapWeekItem> = this.extras,
        redirects: Map<String, String> = this.redirects,
    ) = Snapshot(categories, ingredients, aliases, recipes, slots, purchases, extras, redirects)

    private fun content() = listOf(categories, ingredients, aliases, recipes, slots, purchases, extras)

    override fun equals(other: Any?) = other is Snapshot && content() == other.content()

    override fun hashCode() = content().hashCode()

    override fun toString() = "Snapshot(categories=$categories, ingredients=$ingredients, aliases=$aliases, recipes=$recipes, " +
        "slots=$slots, purchases=$purchases, extras=$extras, redirects=$redirects)"

    companion object {
        val EMPTY = Snapshot()

        /** The synced part of [data]. References to rows that do not exist are dropped. */
        fun from(data: AppData, redirects: Map<String, String> = emptyMap()): Snapshot {
            val categoryUid = data.categories.associate { it.id to it.uid }
            val ingredientUid = data.ingredients.associate { it.id to it.uid }
            val recipeUid = data.recipes.associate { it.id to it.uid }
            fun items(list: List<it.spesami.core.WeekItem>) =
                list.mapNotNull { w -> ingredientUid[w.ingredientId]?.let { SnapWeekItem(w.weekStart, it) } }.toSet()
            return Snapshot(
                categories = data.sortedCategories.map { SnapCategory(it.uid, it.name, it.position, it.builtinKey) },
                ingredients = data.ingredients.map { SnapIngredient(it.uid, it.name, it.categoryId?.let(categoryUid::get), it.categoryManual) },
                aliases = data.aliases.mapNotNull { a -> ingredientUid[a.ingredientId]?.let { SnapAlias(a.uid, it, a.alias) } },
                recipes = data.recipes.map { r -> SnapRecipe(r.uid, r.name, r.notes, r.ingredientIds.mapNotNull(ingredientUid::get).distinct(), r.color?.key) },
                slots = data.slots.mapNotNull { s -> recipeUid[s.recipeId]?.let { SnapSlot(s.weekStart, s.day, s.meal, it) } },
                purchases = items(data.purchases),
                extras = items(data.manualItems),
                redirects = redirects,
            )
        }
    }
}
