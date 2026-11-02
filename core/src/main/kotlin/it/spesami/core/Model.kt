package it.spesami.core

import java.time.LocalDate

enum class MealType(val label: String) {
    LUNCH("Pranzo"),
    DINNER("Cena"),
}

/** Optional grouping for the shopping list, in the order a typical Italian supermarket walk goes. */
enum class Category(val label: String) {
    PRODUCE("Frutta e verdura"),
    MEAT_FISH("Carne e pesce"),
    DAIRY_EGGS("Latticini e uova"),
    BREAD_CEREALS("Pane, pasta e cereali"),
    PANTRY("Dispensa"),
    FROZEN("Surgelati"),
    DRINKS("Bevande"),
    OTHER("Altro"),
}

data class Ingredient(val id: Long, val name: String, val category: Category?)

data class IngredientAlias(val id: Long, val ingredientId: Long, val alias: String)

data class Recipe(val id: Long, val name: String, val notes: String, val ingredientIds: List<Long>)

/** One lunch or dinner of one day. [day] is 0 (Monday) .. 6 (Sunday) of the week starting on [weekStart]. */
data class MealSlot(val weekStart: LocalDate, val day: Int, val meal: MealType, val recipeId: Long)

/** Everything the app stores. Small enough to be loaded and kept in memory as a whole. */
data class AppData(
    val ingredients: List<Ingredient> = emptyList(),
    val aliases: List<IngredientAlias> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val slots: List<MealSlot> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
) {
    val ingredientsById: Map<Long, Ingredient> by lazy { ingredients.associateBy { it.id } }
    val recipesById: Map<Long, Recipe> by lazy { recipes.associateBy { it.id } }

    fun slotsOfWeek(weekStart: LocalDate): List<MealSlot> = slots.filter { it.weekStart == weekStart }
}
