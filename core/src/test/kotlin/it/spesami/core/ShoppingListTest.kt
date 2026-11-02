package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ShoppingListTest {
    private val week = LocalDate.of(2026, 11, 2)
    private val categories = DefaultCategories.seed()
    private fun cat(key: String) = categories.single { it.builtinKey == key }.id

    private val ingredients = listOf(
        Ingredient(1, "Patate", cat("frutta_verdura")),
        Ingredient(2, "Spinaci", cat("frutta_verdura")),
        Ingredient(3, "Uova", cat("latte_latticini")),
        Ingredient(4, "Latte", null),
        Ingredient(5, "Tonno in scatola", cat("scatolame_conserve")),
    )
    private val recipes = listOf(
        Recipe(10, "Frittata", "", listOf(1, 3, 3)), // Uova listed twice: still one meal
        Recipe(11, "Purè", "", listOf(1, 4)),
        Recipe(12, "Spinaci saltati", "", listOf(2)),
        Recipe(13, "Insalata di tonno", "", listOf(5, 1)),
    )
    private val data = AppData(
        ingredients = ingredients,
        recipes = recipes,
        categories = categories,
        slots = listOf(
            MealSlot(week, 0, MealType.LUNCH, 10),
            MealSlot(week, 0, MealType.DINNER, 11),
            MealSlot(week, 3, MealType.DINNER, 10),
            MealSlot(week, 5, MealType.LUNCH, 12),
            MealSlot(week, 6, MealType.LUNCH, 13),
            MealSlot(week.plusWeeks(1), 0, MealType.LUNCH, 11), // other week: ignored
        ),
    )

    @Test
    fun countsOneOccurrencePerMeal() {
        val items = ShoppingList.build(data, week)
        assertEquals(
            listOf("Patate" to 4, "Uova" to 2, "Latte" to 1, "Spinaci" to 1, "Tonno in scatola" to 1),
            items.map { it.name to it.count },
        )
    }

    @Test
    fun alphabeticalSort() {
        val items = ShoppingList.build(data, week, ShoppingSort.ALPHABETICAL)
        assertEquals(listOf("Latte", "Patate", "Spinaci", "Tonno in scatola", "Uova"), items.map { it.name })
    }

    @Test
    fun groupsInCategoryOrderAlphabeticalInsideUnclassifiedLast() {
        val groups = ShoppingList.grouped(data, ShoppingList.build(data, week))
        assertEquals(listOf("Frutta e verdura", "Latte e latticini", "Scatolame e conserve", "Da classificare"), groups.map { it.label })
        assertEquals(listOf("Patate", "Spinaci"), groups[0].items.map { it.name })
    }

    @Test
    fun groupsFollowCustomOrder() {
        val reordered = data.copy(categories = categories.map { if (it.builtinKey == "scatolame_conserve") it.copy(position = -1) else it })
        val groups = ShoppingList.grouped(reordered, ShoppingList.build(reordered, week))
        assertEquals("Scatolame e conserve", groups.first().label)
    }

    @Test
    fun plainTextGroupedWithHeaders() {
        val text = ShoppingExport.toPlainText(ShoppingExport.sections(data, week, ExportOptions()), includeCounts = false)
        assertEquals(
            "FRUTTA E VERDURA\nPatate\nSpinaci\n\nLATTE E LATTICINI\nUova\n\nSCATOLAME E CONSERVE\nTonno in scatola\n\nDA CLASSIFICARE\nLatte",
            text,
        )
    }

    @Test
    fun plainTextVariants() {
        val flat = ShoppingExport.sections(data, week, ExportOptions(groupByCategory = false))
        assertEquals("Patate\nUova\nLatte\nSpinaci\nTonno in scatola", ShoppingExport.toPlainText(flat, false))
        assertEquals("Patate ×4\nUova ×2\nLatte ×1\nSpinaci ×1\nTonno in scatola ×1", ShoppingExport.toPlainText(flat, true))

        val groupedNoHeaders = ShoppingExport.sections(data, week, ExportOptions(showHeaders = false))
        assertEquals("Patate ×4\nSpinaci ×1\nUova ×2\nTonno in scatola ×1\nLatte ×1", ShoppingExport.toPlainText(groupedNoHeaders, true))
    }

    @Test
    fun categoryFilterLeavesDataUntouched() {
        val options = ExportOptions(excludedCategories = setOf(cat("frutta_verdura"), null))
        val sections = ShoppingExport.sections(data, week, options)
        assertEquals("LATTE E LATTICINI\nUova\n\nSCATOLAME E CONSERVE\nTonno in scatola", ShoppingExport.toPlainText(sections, false))
        assertEquals(5, ShoppingList.build(data, week).size)
        assertEquals(emptyList<ExportSection>(), ShoppingExport.sections(data, week, ExportOptions(excludedCategories = (categories.map { it.id } + null).toSet())))
    }

    @Test
    fun danglingCategoryIsUnclassified() {
        val broken = data.copy(ingredients = ingredients.map { if (it.id == 2L) it.copy(categoryId = 999) else it })
        val groups = ShoppingList.grouped(broken, ShoppingList.build(broken, week))
        assertEquals(listOf("Latte", "Spinaci"), groups.last().items.map { it.name })
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
