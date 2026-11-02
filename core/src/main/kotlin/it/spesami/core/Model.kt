package it.spesami.core

import java.time.LocalDate

enum class MealType(val label: String) {
    LUNCH("Pranzo"),
    DINNER("Cena"),
}

/**
 * A user-editable shopping-list category. [position] gives the order on the list; [builtinKey] identifies the
 * seeded defaults (see [DefaultCategories]) independently of their (renamable) name.
 */
data class ShoppingCategory(val id: Long, val name: String, val position: Int, val builtinKey: String? = null)

/**
 * [categoryId] null means "Da classificare". [categoryManual] is true once the user picked the category
 * (including picking "Da classificare"); automatic classification never touches those ingredients.
 */
data class Ingredient(val id: Long, val name: String, val categoryId: Long? = null, val categoryManual: Boolean = false)

data class IngredientAlias(val id: Long, val ingredientId: Long, val alias: String)

data class Recipe(val id: Long, val name: String, val notes: String, val ingredientIds: List<Long>)

/** One lunch or dinner of one day. [day] is 0 (Monday) .. 6 (Sunday) of the week starting on [weekStart]. */
data class MealSlot(val weekStart: LocalDate, val day: Int, val meal: MealType, val recipeId: Long)

/**
 * One ingredient in one week's shopping checklist. Used both for "purchased" marks and for products the user added
 * by hand; both are keyed by the stable ingredient id, so renaming or recategorising never loses them.
 */
data class WeekItem(val weekStart: LocalDate, val ingredientId: Long)

/** Everything the app stores. Small enough to be loaded and kept in memory as a whole. */
data class AppData(
    val ingredients: List<Ingredient> = emptyList(),
    val aliases: List<IngredientAlias> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val slots: List<MealSlot> = emptyList(),
    val categories: List<ShoppingCategory> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
    /** Purchased marks. Kept even while the ingredient is not on the list, so it comes back checked. */
    val purchases: List<WeekItem> = emptyList(),
    /** Products added by hand to a week's list ("Detersivo"), independent of recipes and the plan. */
    val manualItems: List<WeekItem> = emptyList(),
) {
    val ingredientsById: Map<Long, Ingredient> by lazy { ingredients.associateBy { it.id } }
    val recipesById: Map<Long, Recipe> by lazy { recipes.associateBy { it.id } }
    val categoriesById: Map<Long, ShoppingCategory> by lazy { categories.associateBy { it.id } }
    val sortedCategories: List<ShoppingCategory> by lazy { categories.sortedWith(compareBy({ it.position }, { it.id })) }

    private val purchaseSet: Set<WeekItem> by lazy { purchases.toSet() }
    private val manualSet: Set<WeekItem> by lazy { manualItems.toSet() }

    fun slotsOfWeek(weekStart: LocalDate): List<MealSlot> = slots.filter { it.weekStart == weekStart }

    fun isPurchased(weekStart: LocalDate, ingredientId: Long): Boolean = WeekItem(weekStart, ingredientId) in purchaseSet

    fun isManual(weekStart: LocalDate, ingredientId: Long): Boolean = WeekItem(weekStart, ingredientId) in manualSet

    fun manualOfWeek(weekStart: LocalDate): List<Long> = manualItems.filter { it.weekStart == weekStart }.map { it.ingredientId }

    /** The category an ingredient is shown under; null ("Da classificare") also for a dangling id. */
    fun categoryOf(ingredient: Ingredient): ShoppingCategory? = ingredient.categoryId?.let { categoriesById[it] }
}
