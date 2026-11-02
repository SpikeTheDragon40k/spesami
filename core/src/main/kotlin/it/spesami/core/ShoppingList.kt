package it.spesami.core

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

/**
 * One line of the weekly checklist. [count] is the number of planned meals that need it (0 for a product that is
 * only on the list because the user added it); [manual] records that it was also requested by hand.
 */
data class ShoppingItem(
    val ingredientId: Long,
    val name: String,
    val categoryId: Long?,
    val count: Int,
    val manual: Boolean = false,
    val purchased: Boolean = false,
)

/** Progress counts distinct products: "Patate ×4" is one product. */
data class ShoppingProgress(val purchased: Int, val total: Int) {
    val pending: Int get() = total - purchased
    val fraction: Float get() = if (total == 0) 0f else purchased.toFloat() / total
    val percent: Int get() = if (total == 0) 0 else purchased * 100 / total
    val label: String get() = "$purchased di $total ${if (total == 1) "prodotto acquistato" else "prodotti acquistati"}"
}

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
     * The week's checklist: every ingredient used by its lunches and dinners, with the number of meals that contain
     * it (once per meal, even if a recipe lists it twice), plus the products added by hand. A product that is both
     * is a single item. The meal plan stays the only source of the automatic items; purchased marks are looked up
     * by ingredient id, so they survive count changes, renames and new categories.
     */
    fun build(data: AppData, weekStart: LocalDate, sort: ShoppingSort = ShoppingSort.COUNT): List<ShoppingItem> {
        val counts = LinkedHashMap<Long, Int>()
        for (slot in data.slotsOfWeek(weekStart)) {
            val recipe = data.recipesById[slot.recipeId] ?: continue
            for (id in recipe.ingredientIds.toSet()) counts[id] = (counts[id] ?: 0) + 1
        }
        for (id in data.manualOfWeek(weekStart)) counts.putIfAbsent(id, 0)
        val items = counts.mapNotNull { (id, count) ->
            data.ingredientsById[id]?.let {
                ShoppingItem(
                    ingredientId = id,
                    name = it.name,
                    categoryId = data.categoryOf(it)?.id,
                    count = count,
                    manual = data.isManual(weekStart, id),
                    purchased = data.isPurchased(weekStart, id),
                )
            }
        }
        return sorted(items, sort)
    }

    fun progress(items: List<ShoppingItem>): ShoppingProgress = ShoppingProgress(items.count { it.purchased }, items.size)

    fun sorted(items: List<ShoppingItem>, sort: ShoppingSort): List<ShoppingItem> = when (sort) {
        ShoppingSort.COUNT -> items.sortedWith(compareByDescending<ShoppingItem> { it.count }.then(byName))
        ShoppingSort.ALPHABETICAL -> items.sortedWith(byName)
    }

    private val inGroup = compareBy<ShoppingItem> { it.purchased }.then(byName)

    /**
     * Groups in the configured category order, "Da classificare" last. Inside each group, products still to buy
     * come first, then the purchased ones, each A–Z.
     */
    fun grouped(data: AppData, items: List<ShoppingItem>): List<ShoppingGroup> {
        val byCategory = items.groupBy { it.categoryId?.takeIf { id -> id in data.categoriesById } }
        val groups = data.sortedCategories.mapNotNull { c -> byCategory[c.id]?.let { ShoppingGroup(c, it.sortedWith(inGroup)) } }
        val unclassified = byCategory[null]?.let { ShoppingGroup(null, it.sortedWith(inGroup)) }
        return groups + listOfNotNull(unclassified)
    }
}

/** Which products an export contains. */
enum class ExportScope(val label: String) {
    PENDING("Solo da comprare"),
    ALL("Lista completa"),
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
    val scope: ExportScope = ExportScope.PENDING,
    /** Full-list PDF only: draw purchased products with a ticked box. */
    val markPurchased: Boolean = true,
)

/** A block of the export; [title] is null when grouping or headers are off. */
data class ExportSection(val title: String?, val items: List<ShoppingItem>)

object ShoppingExport {
    /**
     * What gets exported: filtered by scope ("Solo da comprare" drops purchased products) and category, grouped or
     * flat. Never changes the plan, the ingredients or the purchased marks.
     */
    fun sections(data: AppData, weekStart: LocalDate, options: ExportOptions): List<ExportSection> {
        val items = ShoppingList.build(data, weekStart, options.sort)
            .filter { it.categoryId !in options.excludedCategories }
            .filter { options.scope == ExportScope.ALL || !it.purchased }
        if (items.isEmpty()) return emptyList()
        if (!options.groupByCategory) return listOf(ExportSection(null, items))
        return ShoppingList.grouped(data, items).map { ExportSection(if (options.showHeaders) it.label else null, it.items) }
    }

    /**
     * Plain text for the clipboard and the Android share sheet: one ingredient per line, "\n" line ends, no bullets
     * or numbering. Headers are upper case with a blank line between categories; counts look like "Patate ×4"
     * (a product only added by hand has no meal count, so it is just its name).
     */
    fun toPlainText(sections: List<ExportSection>, includeCounts: Boolean): String {
        fun line(item: ShoppingItem) = if (includeCounts && item.count > 0) "${item.name} ×${item.count}" else item.name
        return if (sections.any { it.title != null }) {
            sections.joinToString("\n\n") { s ->
                (listOfNotNull(s.title?.uppercase(Locale.ITALIAN)) + s.items.map(::line)).joinToString("\n")
            }
        } else {
            sections.flatMap { it.items }.joinToString("\n", transform = ::line)
        }
    }

    fun itemCount(sections: List<ExportSection>): Int = sections.sumOf { it.items.size }

    /** The PDF's summary line, e.g. "12 prodotti da comprare" or "15 prodotti, 8 già acquistati". */
    fun summary(sections: List<ExportSection>, scope: ExportScope): String {
        val total = itemCount(sections)
        val products = if (total == 1) "1 prodotto" else "$total prodotti"
        return when (scope) {
            ExportScope.PENDING -> "$products da comprare"
            ExportScope.ALL -> {
                val done = sections.sumOf { s -> s.items.count { it.purchased } }
                if (done == 0) products else "$products, $done già acquistat${if (done == 1) "o" else "i"}"
            }
        }
    }
}
