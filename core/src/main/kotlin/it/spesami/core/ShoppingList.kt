package it.spesami.core

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

data class ShoppingItem(val ingredientId: Long, val name: String, val categoryId: Long?, val count: Int)

enum class ShoppingSort { COUNT, ALPHABETICAL }

/** [category] null = "Da classificare". */
data class ShoppingGroup(val category: ShoppingCategory?, val items: List<ShoppingItem>) {
    val label: String get() = category?.name ?: DefaultCategories.UNCLASSIFIED_LABEL
    val categoryId: Long? get() = category?.id
}

object ShoppingList {
    private val collator: Collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
    private val byName = Comparator<ShoppingItem> { a, b -> collator.compare(a.name, b.name) }

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
            data.ingredientsById[id]?.let { ShoppingItem(id, it.name, data.categoryOf(it)?.id, count) }
        }
        return sorted(items, sort)
    }

    fun sorted(items: List<ShoppingItem>, sort: ShoppingSort): List<ShoppingItem> = when (sort) {
        ShoppingSort.COUNT -> items.sortedWith(compareByDescending<ShoppingItem> { it.count }.then(byName))
        ShoppingSort.ALPHABETICAL -> items.sortedWith(byName)
    }

    /** Groups in the configured category order, "Da classificare" last; ingredients A–Z inside each group. */
    fun grouped(data: AppData, items: List<ShoppingItem>): List<ShoppingGroup> {
        val byCategory = items.groupBy { it.categoryId?.takeIf { id -> id in data.categoriesById } }
        val groups = data.sortedCategories.mapNotNull { c -> byCategory[c.id]?.let { ShoppingGroup(c, it.sortedWith(byName)) } }
        val unclassified = byCategory[null]?.let { ShoppingGroup(null, it.sortedWith(byName)) }
        return groups + listOfNotNull(unclassified)
    }
}

/**
 * Settings of the "Esporta lista della spesa" dialog. [excludedCategories] holds the category ids left out of
 * the export (null = "Da classificare"); storing exclusions means new categories are included by default.
 */
data class ExportOptions(
    val groupByCategory: Boolean = true,
    val includeCounts: Boolean = false,
    val showHeaders: Boolean = true,
    val excludedCategories: Set<Long?> = emptySet(),
    val sort: ShoppingSort = ShoppingSort.COUNT,
)

/** A block of the export; [title] is null when grouping or headers are off. */
data class ExportSection(val title: String?, val items: List<ShoppingItem>)

object ShoppingExport {
    /** What gets exported: filtered by category, grouped or flat. Never changes the plan or the ingredients. */
    fun sections(data: AppData, weekStart: LocalDate, options: ExportOptions): List<ExportSection> {
        val items = ShoppingList.build(data, weekStart, options.sort).filter { it.categoryId !in options.excludedCategories }
        if (items.isEmpty()) return emptyList()
        if (!options.groupByCategory) return listOf(ExportSection(null, items))
        return ShoppingList.grouped(data, items).map { ExportSection(if (options.showHeaders) it.label else null, it.items) }
    }

    /**
     * Plain text for the clipboard and the Android share sheet: one ingredient per line, "\n" line ends, no bullets
     * or numbering. Headers are upper case with a blank line between categories; counts look like "Patate ×4".
     */
    fun toPlainText(sections: List<ExportSection>, includeCounts: Boolean): String {
        fun line(item: ShoppingItem) = if (includeCounts) "${item.name} ×${item.count}" else item.name
        return if (sections.any { it.title != null }) {
            sections.joinToString("\n\n") { s ->
                (listOfNotNull(s.title?.uppercase(Locale.ITALIAN)) + s.items.map(::line)).joinToString("\n")
            }
        } else {
            sections.flatMap { it.items }.joinToString("\n", transform = ::line)
        }
    }

    fun itemCount(sections: List<ExportSection>): Int = sections.sumOf { it.items.size }
}
