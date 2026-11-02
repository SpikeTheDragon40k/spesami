package it.spesami.core

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

data class ShoppingItem(val ingredientId: Long, val name: String, val category: Category?, val count: Int)

enum class ShoppingSort { COUNT, ALPHABETICAL }

data class ShoppingGroup(val category: Category?, val items: List<ShoppingItem>) {
    val label: String get() = category?.label ?: "Senza categoria"
}

object ShoppingList {
    private val collator: Collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }

    /**
     * Every ingredient used by the week's lunches and dinners, with the number of meals that contain it.
     * An ingredient counts once per meal, even if a recipe lists it twice.
     */
    fun build(data: AppData, weekStart: LocalDate, sort: ShoppingSort = ShoppingSort.COUNT): List<ShoppingItem> {
        val counts = HashMap<Long, Int>()
        for (slot in data.slotsOfWeek(weekStart)) {
            val recipe = data.recipesById[slot.recipeId] ?: continue
            for (id in recipe.ingredientIds.toSet()) counts[id] = (counts[id] ?: 0) + 1
        }
        val items = counts.mapNotNull { (id, count) ->
            data.ingredientsById[id]?.let { ShoppingItem(id, it.name, it.category, count) }
        }
        return sorted(items, sort)
    }

    fun sorted(items: List<ShoppingItem>, sort: ShoppingSort): List<ShoppingItem> {
        val byName = Comparator<ShoppingItem> { a, b -> collator.compare(a.name, b.name) }
        return when (sort) {
            ShoppingSort.COUNT -> items.sortedWith(compareByDescending<ShoppingItem> { it.count }.then(byName))
            ShoppingSort.ALPHABETICAL -> items.sortedWith(byName)
        }
    }

    /** Groups in [Category] order, uncategorised last; the order inside each group is kept. */
    fun grouped(items: List<ShoppingItem>): List<ShoppingGroup> =
        items.groupBy { it.category }
            .map { (category, list) -> ShoppingGroup(category, list) }
            .sortedBy { it.category?.ordinal ?: Int.MAX_VALUE }

    /**
     * Plain text for the clipboard and the Android share sheet: one ingredient per line, "\n" line ends,
     * no bullets or numbering. With counts: "Patate ×4".
     */
    fun toPlainText(items: List<ShoppingItem>, withCounts: Boolean): String =
        items.joinToString("\n") { if (withCounts) "${it.name} ×${it.count}" else it.name }
}
