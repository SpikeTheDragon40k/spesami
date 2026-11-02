package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ShoppingListTest {
    private val week = LocalDate.of(2026, 11, 2)
    private val ingredients = listOf(
        Ingredient(1, "Patate", Category.PRODUCE),
        Ingredient(2, "Spinaci", Category.PRODUCE),
        Ingredient(3, "Uova", Category.DAIRY_EGGS),
        Ingredient(4, "Latte", null),
    )
    private val recipes = listOf(
        Recipe(10, "Frittata", "", listOf(1, 3, 3)), // Uova listed twice: still one meal
        Recipe(11, "Purè", "", listOf(1, 4)),
        Recipe(12, "Spinaci saltati", "", listOf(2)),
    )
    private val data = AppData(
        ingredients = ingredients,
        recipes = recipes,
        slots = listOf(
            MealSlot(week, 0, MealType.LUNCH, 10),
            MealSlot(week, 0, MealType.DINNER, 11),
            MealSlot(week, 3, MealType.DINNER, 10),
            MealSlot(week, 5, MealType.LUNCH, 12),
            MealSlot(week.plusWeeks(1), 0, MealType.LUNCH, 11), // other week: ignored
        ),
    )

    @Test
    fun countsMealsPerIngredient() {
        val items = ShoppingList.build(data, week)
        assertEquals(listOf("Patate" to 3, "Uova" to 2, "Latte" to 1, "Spinaci" to 1), items.map { it.name to it.count })
    }

    @Test
    fun alphabeticalSort() {
        val items = ShoppingList.build(data, week, ShoppingSort.ALPHABETICAL)
        assertEquals(listOf("Latte", "Patate", "Spinaci", "Uova"), items.map { it.name })
    }

    @Test
    fun plainTextFormats() {
        val items = ShoppingList.build(data, week)
        assertEquals("Patate\nUova\nLatte\nSpinaci", ShoppingList.toPlainText(items, withCounts = false))
        assertEquals("Patate ×3\nUova ×2\nLatte ×1\nSpinaci ×1", ShoppingList.toPlainText(items, withCounts = true))
    }

    @Test
    fun groupsByCategoryUncategorisedLast() {
        val groups = ShoppingList.grouped(ShoppingList.build(data, week))
        assertEquals(listOf(Category.PRODUCE, Category.DAIRY_EGGS, null), groups.map { it.category })
        assertEquals(listOf("Patate", "Spinaci"), groups[0].items.map { it.name })
    }

    @Test
    fun resolverMatchesAliasesAndSpacing() {
        val resolver = IngredientResolver(ingredients, listOf(IngredientAlias(1, 1, "Patata")))
        assertEquals(1L, resolver.resolve("  patata "))
        assertEquals(1L, resolver.resolve("PATATE"))
        assertNull(resolver.resolve("Carote"))
        assertEquals("Patate novelle", IngredientNames.clean("  patate   novelle "))
    }

    @Test
    fun weekLabels() {
        assertEquals("Lunedì 2 novembre", Weeks.dayLabel(week, 0))
        assertEquals("2 – 8 novembre 2026", Weeks.rangeLabel(week))
        assertEquals("26 ottobre – 1 novembre 2026", Weeks.rangeLabel(LocalDate.of(2026, 10, 26)))
        assertEquals(week, Weeks.startOf(LocalDate.of(2026, 11, 8)))
    }
}
