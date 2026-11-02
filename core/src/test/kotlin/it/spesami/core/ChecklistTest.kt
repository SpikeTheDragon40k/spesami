package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The weekly checklist computed from the plan, purchased marks and hand-added products. */
class ChecklistTest {
    private val week = LocalDate.of(2026, 11, 2)
    private val categories = DefaultCategories.seed()
    private fun cat(key: String) = categories.single { it.builtinKey == key }.id

    private val base = AppData(
        ingredients = listOf(
            Ingredient(1, "Patate", cat("frutta_verdura")),
            Ingredient(2, "Uova", cat("latte_latticini")),
            Ingredient(3, "Spinaci", cat("frutta_verdura")),
            Ingredient(4, "Detersivo", cat("altro"), categoryManual = true),
        ),
        recipes = listOf(Recipe(10, "Frittata", "", listOf(1, 2)), Recipe(11, "Contorno", "", listOf(1, 3))),
        categories = categories,
        slots = listOf(
            MealSlot(week, 0, MealType.LUNCH, 10),
            MealSlot(week, 1, MealType.LUNCH, 10),
            MealSlot(week, 2, MealType.DINNER, 11),
            MealSlot(week, 3, MealType.DINNER, 11),
        ),
    )

    private fun AppData.buy(vararg ids: Long, w: LocalDate = week) = copy(purchases = purchases + ids.map { WeekItem(w, it) })
    private fun AppData.add(vararg ids: Long, w: LocalDate = week) = copy(manualItems = manualItems + ids.map { WeekItem(w, it) })
    private fun AppData.item(name: String) = ShoppingList.build(this, week).single { it.name == name }

    @Test
    fun checkAndUncheck() {
        assertFalse(base.item("Patate").purchased)
        val bought = base.buy(1)
        assertTrue(bought.item("Patate").purchased)
        assertFalse(bought.item("Uova").purchased)
        val unchecked = bought.copy(purchases = bought.purchases - WeekItem(week, 1))
        assertFalse(unchecked.item("Patate").purchased)
    }

    @Test
    fun weeksAreIndependent() {
        val next = week.plusWeeks(1)
        val data = base.copy(slots = base.slots + base.slots.map { it.copy(weekStart = next) }).buy(1)
        assertTrue(ShoppingList.build(data, week).single { it.name == "Patate" }.purchased)
        assertFalse(ShoppingList.build(data, next).single { it.name == "Patate" }.purchased)
    }

    @Test
    fun progressCountsDistinctProducts() {
        val data = base.buy(1) // Patate ×4 is one product
        assertEquals(4, data.item("Patate").count)
        val progress = ShoppingList.progress(ShoppingList.build(data, week))
        assertEquals(ShoppingProgress(1, 3), progress)
        assertEquals("1 di 3 prodotti acquistati", progress.label)
        assertEquals(33, progress.percent)
        assertEquals(2, progress.pending)
        assertEquals(ShoppingProgress(0, 0).fraction, 0f)
        assertEquals("8 di 15 prodotti acquistati", ShoppingProgress(8, 15).label)
    }

    @Test
    fun countChangesKeepPurchasedState() {
        val data = base.buy(1).copy(slots = base.slots.drop(1)) // one meal less: Patate ×3
        assertEquals(3, data.item("Patate").count)
        assertTrue(data.item("Patate").purchased)
    }

    @Test
    fun removedThenReintroducedIngredientRestoresState() {
        val bought = base.buy(3)
        val withoutSpinach = bought.copy(slots = bought.slots.filter { it.recipeId != 11L })
        assertTrue(ShoppingList.build(withoutSpinach, week).none { it.name == "Spinaci" })
        assertEquals(bought.purchases, withoutSpinach.purchases) // mark kept while absent
        assertTrue(bought.item("Spinaci").purchased)
    }

    @Test
    fun newMealIngredientIsUnchecked() {
        val data = base.buy(1, 2, 3).copy(
            ingredients = base.ingredients + Ingredient(5, "Zucchine", cat("frutta_verdura")),
            recipes = base.recipes.map { if (it.id == 10L) it.copy(ingredientIds = it.ingredientIds + 5) else it },
        )
        assertFalse(data.item("Zucchine").purchased)
        assertTrue(listOf("Patate", "Uova", "Spinaci").all { data.item(it).purchased })
    }

    @Test
    fun manualProductsAreListedWithoutDuplicates() {
        val data = base.add(4).add(1) // Detersivo, and Patate which meals need anyway
        val items = ShoppingList.build(data, week)
        assertEquals(4, items.size)
        assertEquals(1, items.count { it.name == "Patate" })
        val patate = data.item("Patate")
        assertTrue(patate.manual)
        assertEquals(4, patate.count)
        val detersivo = data.item("Detersivo")
        assertEquals(0, detersivo.count)
        assertTrue(detersivo.manual)
        assertEquals(cat("altro"), detersivo.categoryId)
        // Other weeks don't see it.
        assertTrue(ShoppingList.build(data, week.plusWeeks(1)).isEmpty())
    }

    @Test
    fun pendingFirstInsideEachCategory() {
        val data = base.buy(1)
        val group = ShoppingList.grouped(data, ShoppingList.build(data, week)).first()
        assertEquals("Frutta e verdura", group.label)
        assertEquals(listOf("Spinaci", "Patate"), group.items.map { it.name })
    }

    @Test
    fun exportOnlyPending() {
        val data = base.buy(1).add(4)
        val sections = ShoppingExport.sections(data, week, ExportOptions(scope = ExportScope.PENDING))
        assertEquals(
            "FRUTTA E VERDURA\nSpinaci\n\nLATTE E LATTICINI\nUova\n\nALTRO\nDetersivo",
            ShoppingExport.toPlainText(sections, includeCounts = false),
        )
        assertEquals("Spinaci ×2\nUova ×2\nDetersivo", ShoppingExport.toPlainText(sections.map { it.copy(title = null) }, includeCounts = true))
        assertEquals("3 prodotti da comprare", ShoppingExport.summary(sections, ExportScope.PENDING))
    }

    @Test
    fun exportCompleteList() {
        val data = base.buy(1).add(4)
        val sections = ShoppingExport.sections(data, week, ExportOptions(scope = ExportScope.ALL))
        assertEquals(
            "FRUTTA E VERDURA\nSpinaci\nPatate\n\nLATTE E LATTICINI\nUova\n\nALTRO\nDetersivo",
            ShoppingExport.toPlainText(sections, includeCounts = false),
        )
        assertTrue(sections.flatMap { it.items }.single { it.name == "Patate" }.purchased)
        assertEquals("4 prodotti, 1 già acquistato", ShoppingExport.summary(sections, ExportScope.ALL))
    }

    @Test
    fun everythingPurchasedLeavesNothingPending() {
        val data = base.buy(1, 2, 3)
        assertTrue(ShoppingExport.sections(data, week, ExportOptions()).isEmpty())
        assertEquals(3, ShoppingExport.itemCount(ShoppingExport.sections(data, week, ExportOptions(scope = ExportScope.ALL))))
    }

    @Test
    fun recategorisingKeepsStateAndCategories() {
        val data = base.buy(2)
        val moved = data.copy(ingredients = data.ingredients.map { if (it.id == 2L) it.copy(categoryId = cat("altro"), categoryManual = true) else it })
        assertTrue(moved.item("Uova").purchased)
        assertEquals(cat("altro"), moved.item("Uova").categoryId)
        assertEquals(categories, moved.categories)
    }
}
