package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ShoppingModeTest {
    private val week = LocalDate.of(2026, 11, 2)
    private val categories = DefaultCategories.seed()
    private fun cat(key: String) = categories.single { it.builtinKey == key }.id

    private val base = AppData(
        ingredients = listOf(
            Ingredient(1, "Patate", cat("frutta_verdura")),
            Ingredient(2, "Uova", cat("latte_latticini")),
            Ingredient(3, "Spinaci", cat("frutta_verdura")),
            Ingredient(4, "Carta igienica", cat("altro")),
            Ingredient(5, "Caffè", null),
        ),
        recipes = listOf(Recipe(10, "Frittata", "", listOf(1, 2)), Recipe(11, "Contorno", "", listOf(1, 3))),
        categories = categories,
        slots = listOf(
            MealSlot(week, 0, MealType.LUNCH, 10),
            MealSlot(week, 1, MealType.LUNCH, 10),
            MealSlot(week, 2, MealType.DINNER, 11),
            MealSlot(week, 3, MealType.DINNER, 11),
        ),
        manualItems = listOf(WeekItem(week, 4)),
    )

    private fun AppData.buy(vararg ids: Long) = copy(purchases = purchases + ids.map { WeekItem(week, it) })
    private fun ShoppingModeView.pendingNames() = pending.flatMap { s -> s.group.items.map { it.name } }

    @Test
    fun reusesTheWeeksChecklist() {
        val view = ShoppingMode.view(base, week)
        val list = ShoppingList.build(base, week)
        assertEquals(list.map { it.ingredientId }.toSet(), view.pending.flatMap { s -> s.group.items.map { it.ingredientId } }.toSet())
        assertTrue("Carta igienica" in view.pendingNames()) // hand-added products included
        assertEquals(ShoppingList.progress(list), view.progress)
    }

    @Test
    fun checkingMovesToAlreadyPurchasedAndUncheckingBack() {
        val bought = ShoppingMode.view(base.buy(1), week)
        assertFalse("Patate" in bought.pendingNames())
        assertEquals(listOf("Patate"), bought.purchased.map { it.name })
        assertEquals(ShoppingProgress(1, 4), bought.progress)
        assertEquals("3 prodotti da comprare", bought.remainingLabel)

        val unchecked = ShoppingMode.view(base, week)
        assertTrue("Patate" in unchecked.pendingNames())
        assertTrue(unchecked.purchased.isEmpty())
    }

    @Test
    fun progressCountsDistinctProducts() {
        val view = ShoppingMode.view(base.buy(1), week)
        assertEquals(4, ShoppingList.build(base, week).single { it.name == "Patate" }.count)
        assertEquals("1 di 4 prodotti acquistati", view.progress.label)
        assertEquals(25, view.progress.percent)
        assertEquals("1 prodotto da comprare", ShoppingMode.view(base.buy(1, 2, 3), week).remainingLabel)
    }

    @Test
    fun emptyList() {
        val view = ShoppingMode.view(AppData(categories = categories), week)
        assertTrue(view.isEmpty)
        assertFalse(view.isComplete)
        assertEquals(0f, view.progress.fraction)
        assertEquals("0 prodotti da comprare", view.remainingLabel)
        assertTrue(view.pending.isEmpty() && view.purchased.isEmpty())
    }

    @Test
    fun completedListKeepsEverything() {
        val view = ShoppingMode.view(base.buy(1, 2, 3, 4), week)
        assertTrue(view.isComplete)
        assertTrue(view.pending.isEmpty())
        assertEquals(4, view.purchased.size) // nothing deleted or reset
        assertEquals(100, view.progress.percent)
        assertEquals("Spesa completata!", ShoppingModeView.COMPLETED)
    }

    @Test
    fun groupedByCategoryWithPendingCounts() {
        val view = ShoppingMode.view(base.buy(3), week)
        assertEquals(
            listOf("Frutta e verdura — 1 da comprare", "Latte e latticini — 1 da comprare", "Altro — 1 da comprare"),
            view.pending.map { it.label },
        )
        assertEquals(listOf("Patate", "Uova", "Carta igienica"), view.pendingNames())
        // Unclassified last.
        val withCoffee = base.copy(manualItems = base.manualItems + WeekItem(week, 5))
        assertEquals("Da classificare — 1 da comprare", ShoppingMode.view(withCoffee, week).pending.last().label)
    }

    @Test
    fun searchNarrowsRowsButNotProgress() {
        val data = base.buy(1)
        val view = ShoppingMode.view(data, week, "  PAT ")
        assertTrue(view.pending.isEmpty())
        assertEquals(listOf("Patate"), view.purchased.map { it.name })
        assertEquals(ShoppingProgress(1, 4), view.progress)

        val accents = ShoppingMode.view(data.copy(manualItems = data.manualItems + WeekItem(week, 5)), week, "caffe")
        assertEquals(listOf("Caffè"), accents.pendingNames())
        assertTrue(ShoppingMode.view(data, week, "detersivo").noMatches)
        assertFalse(ShoppingMode.view(data, week, "").noMatches)
    }

    @Test
    fun undoIsTheOppositeWrite() {
        val check = PurchaseToggle(1, "Patate", purchased = true)
        assertEquals("Segnato come acquistato: Patate", check.message)
        assertEquals(PurchaseToggle(1, "Patate", purchased = false), check.undo())
        assertEquals("Di nuovo da comprare: Patate", check.undo().message)
        assertEquals(check, check.undo().undo())

        val after = base.buy(1)
        val undone = after.copy(purchases = after.purchases - WeekItem(week, check.undo().ingredientId))
        assertEquals(ShoppingMode.view(base, week), ShoppingMode.view(undone, week))
    }

    @Test
    fun noOpControllerIsSafe() {
        ScreenAwakeController.NoOp.enable()
        ScreenAwakeController.NoOp.disable()
        ScreenAwakeController.NoOp.disable()
    }
}
