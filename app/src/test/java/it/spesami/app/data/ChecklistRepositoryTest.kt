package it.spesami.app.data

import it.spesami.core.AppData
import it.spesami.core.Backup
import it.spesami.core.BackupReadResult
import it.spesami.core.DefaultCategories
import it.spesami.core.MealType
import it.spesami.core.ShoppingItem
import it.spesami.core.ShoppingList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Persistence of the weekly shopping checklist (purchased marks and hand-added products). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecklistRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val week = LocalDate.of(2026, 11, 2)
    private val dbName = "checklist-test.db"
    private lateinit var helper: SpesamiDatabase
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        helper = SpesamiDatabase(context, dbName)
        repo = Repository(helper)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(dbName)
    }

    private fun list(w: LocalDate = week, data: AppData = repo.load()): List<ShoppingItem> = ShoppingList.build(data, w)
    private fun item(name: String, w: LocalDate = week) = list(w).single { it.name == name }
    private fun id(name: String) = repo.load().ingredients.single { it.name == name }.id

    /** Frittata (patate, uova) on Monday and Tuesday, Purè (patate, latte) on Wednesday. */
    private fun planWeek(w: LocalDate = week): Pair<Long, Long> {
        val frittata = repo.saveRecipe(null, "Frittata", "", listOf("Patate", "Uova"))
        val pure = repo.saveRecipe(null, "Purè", "", listOf("Patate", "Latte"))
        repo.setMeal(w, 0, MealType.LUNCH, frittata)
        repo.setMeal(w, 1, MealType.LUNCH, frittata)
        repo.setMeal(w, 2, MealType.DINNER, pure)
        return frittata to pure
    }

    @Test
    fun checkAndUncheck() {
        planWeek()
        repo.setPurchased(week, id("Patate"), true)
        assertTrue(item("Patate").purchased)
        assertFalse(item("Uova").purchased)
        repo.setPurchased(week, id("Patate"), true) // idempotent
        assertEquals(1, repo.load().purchases.size)
        repo.setPurchased(week, id("Patate"), false)
        assertFalse(item("Patate").purchased)
        assertTrue(repo.load().purchases.isEmpty())
    }

    @Test
    fun stateSurvivesRestart() {
        planWeek()
        repo.setPurchased(week, id("Uova"), true)
        repo.addProduct(week, "Detersivo")
        helper.close()

        helper = SpesamiDatabase(context, dbName)
        repo = Repository(helper)
        assertTrue(item("Uova").purchased)
        assertFalse(item("Patate").purchased)
        assertTrue(item("Detersivo").manual)
    }

    @Test
    fun weeksHaveSeparateStates() {
        val (frittata, _) = planWeek()
        val next = week.plusWeeks(1)
        repo.setMeal(next, 4, MealType.DINNER, frittata)
        repo.setPurchased(week, id("Patate"), true)
        repo.addProduct(next, "Sapone")
        assertTrue(item("Patate").purchased)
        assertFalse(item("Patate", next).purchased)
        assertTrue(list().none { it.name == "Sapone" })
        assertTrue(item("Sapone", next).manual)
    }

    @Test
    fun editingMealsKeepsPurchasesAndCounts() {
        val (frittata, pure) = planWeek()
        repo.setPurchased(week, id("Patate"), true)
        repo.setPurchased(week, id("Uova"), true)

        // New ingredient in a planned recipe: shows up unchecked, the others keep their state.
        repo.saveRecipe(frittata, "Frittata", "", listOf("Patate", "Uova", "Zucchine"))
        assertFalse(item("Zucchine").purchased)
        assertTrue(item("Patate").purchased && item("Uova").purchased)

        // Count changes do not reset it.
        assertEquals(3, item("Patate").count)
        repo.setMeal(week, 5, MealType.LUNCH, pure)
        assertEquals(4, item("Patate").count)
        repo.setMeal(week, 0, MealType.LUNCH, null)
        repo.setMeal(week, 1, MealType.LUNCH, null)
        assertEquals(2, item("Patate").count)
        assertTrue(item("Patate").purchased)
    }

    @Test
    fun removedAndReintroducedIngredientRestoresState() {
        val (frittata, _) = planWeek()
        repo.setPurchased(week, id("Uova"), true)
        repo.saveRecipe(frittata, "Frittata", "", listOf("Patate")) // Uova no longer in any meal
        assertTrue(list().none { it.name == "Uova" })
        repo.saveRecipe(frittata, "Frittata", "", listOf("Patate", "uova"))
        assertTrue(item("Uova").purchased)

        // Same through the plan: empty the week, plan again.
        repo.clearWeek(week)
        assertTrue(list().isEmpty())
        repo.setMeal(week, 3, MealType.DINNER, frittata)
        assertTrue(item("Uova").purchased)
    }

    @Test
    fun renameRecategoriseAndMergeKeepState() {
        planWeek()
        val latte = id("Latte")
        repo.setPurchased(week, latte, true)
        repo.renameIngredient(latte, "Latte intero")
        assertTrue(item("Latte intero").purchased)
        repo.setIngredientCategory(latte, null)
        assertTrue(item("Latte intero").purchased)
        assertNull(item("Latte intero").categoryId)

        repo.saveRecipe(null, "Cappuccino", "", listOf("Latte parzialmente scremato"))
        repo.mergeIngredient(latte, id("Latte parzialmente scremato"))
        repo.setMeal(week, 6, MealType.LUNCH, repo.load().recipes.single { it.name == "Cappuccino" }.id)
        assertTrue(item("Latte parzialmente scremato").purchased)
    }

    @Test
    fun manualProductsAddAndRemoveWithoutTouchingThePlan() {
        planWeek()
        val before = repo.load()
        assertTrue(repo.addProduct(week, " detersivo ") is Repository.ProductChange.Added)
        assertTrue(repo.addProduct(week, "Detersivo") is Repository.ProductChange.AlreadyListed)
        assertTrue(repo.addProduct(week, "  ") is Repository.ProductChange.Empty)
        repo.addProduct(week, "Cibo per animali", DefaultCategories.seed().single { it.builtinKey == DefaultCategories.OTHER }.id, setCategory = true)

        val detersivo = item("Detersivo")
        assertEquals(0, detersivo.count)
        assertTrue(detersivo.manual)
        val cibo = repo.load().ingredients.single { it.name == "Cibo per animali" }
        assertTrue(cibo.categoryManual)
        assertEquals("altro", repo.load().categoryOf(cibo)?.builtinKey)

        val after = repo.load()
        assertEquals(before.recipes, after.recipes)
        assertEquals(before.slots, after.slots)

        repo.setPurchased(week, detersivo.ingredientId, true)
        repo.removeProduct(week, detersivo.ingredientId)
        assertTrue(list().none { it.name == "Detersivo" })
        assertEquals(listOf("Cibo per animali", "Latte", "Patate", "Uova"), list().map { it.name }.sorted())
    }

    @Test
    fun manualProductMatchingAMealIngredientIsOneEntry() {
        planWeek()
        repo.setPurchased(week, id("Patate"), true)
        repo.addProduct(week, "patate")
        val patate = list().filter { it.name == "Patate" }
        assertEquals(1, patate.size)
        assertTrue(patate.single().manual)
        assertEquals(3, patate.single().count)
        // Asking for it again by hand means it's needed: back to "da comprare".
        assertFalse(patate.single().purchased)

        // Removing the manual request leaves the planned item.
        repo.removeProduct(week, patate.single().ingredientId)
        assertFalse(item("Patate").manual)
        assertEquals(3, item("Patate").count)

        // An alias is matched too.
        repo.addAlias(id("Uova"), "Uovo")
        repo.addProduct(week, "uovo")
        assertEquals(1, list().count { it.name == "Uova" })
    }

    @Test
    fun resetUnchecksOnlyThisWeek() {
        val (frittata, _) = planWeek()
        val next = week.plusWeeks(1)
        repo.setMeal(next, 0, MealType.LUNCH, frittata)
        repo.addProduct(week, "Detersivo")
        listOf("Patate", "Uova", "Detersivo").forEach { repo.setPurchased(week, id(it), true) }
        repo.setPurchased(next, id("Patate"), true)
        repo.createCategory("Casa")
        val before = repo.load()

        repo.resetPurchases(week)
        val after = repo.load()
        assertTrue(list(week, after).none { it.purchased })
        assertTrue(item("Patate", next).purchased)
        assertTrue(item("Detersivo").manual)
        assertEquals(before.slots, after.slots)
        assertEquals(before.recipes, after.recipes)
        assertEquals(before.categories, after.categories)
        assertEquals(before.ingredients, after.ingredients)
    }

    @Test
    fun categoriesArePreservedAndGroupTheChecklist() {
        planWeek()
        repo.addProduct(week, "Detersivo")
        repo.setPurchased(week, id("Patate"), true)
        val data = repo.load()
        assertEquals(12, data.categories.size)
        val groups = ShoppingList.grouped(data, list(data = data))
        assertEquals(listOf("Frutta e verdura", "Latte e latticini", "Altro"), groups.map { it.label })
        assertEquals("Patate", groups.first().items.single().name)
        assertTrue(groups.first().items.single().purchased)
    }

    @Test
    fun backupRestoresChecklist() {
        planWeek()
        repo.addProduct(week, "Detersivo")
        repo.setPurchased(week, id("Uova"), true)
        val saved = repo.load()
        val restored = (Backup.decode(Backup.encode(saved, "2026-11-02")) as BackupReadResult.Valid).data

        repo.resetPurchases(week)
        repo.removeProduct(week, id("Detersivo"))
        repo.replaceAll(restored)
        assertTrue(item("Uova").purchased)
        assertTrue(item("Detersivo").manual)
    }

    @Test
    fun deletingAnIngredientDropsItsChecklistRows() {
        repo.addProduct(week, "Detersivo")
        repo.setPurchased(week, id("Detersivo"), true)
        repo.deleteIngredient(id("Detersivo"))
        val data = repo.load()
        assertTrue(data.purchases.isEmpty() && data.manualItems.isEmpty())
    }

    @Test
    fun shoppingModeUndoRevertsThePersistedState() {
        planWeek()
        val patate = item("Patate")
        val check = it.spesami.core.PurchaseToggle(patate.ingredientId, patate.name, purchased = true)
        repo.setPurchased(week, check.ingredientId, check.purchased)
        assertTrue(item("Patate").purchased)
        check.undo().let { repo.setPurchased(week, it.ingredientId, it.purchased) }
        assertFalse(item("Patate").purchased)
        assertTrue(repo.load().purchases.isEmpty())

        // Unchecking from "Già acquistati" and undoing that puts the tick back.
        repo.setPurchased(week, patate.ingredientId, true)
        val uncheck = it.spesami.core.PurchaseToggle(patate.ingredientId, patate.name, purchased = false)
        repo.setPurchased(week, uncheck.ingredientId, uncheck.purchased)
        uncheck.undo().let { repo.setPurchased(week, it.ingredientId, it.purchased) }
        assertTrue(item("Patate").purchased)
    }

    @Test
    fun productAddedDuringShoppingIsInTheModeAndTheNormalList() {
        planWeek()
        repo.addProduct(week, "Carta igienica")
        val data = repo.load()
        val view = it.spesami.core.ShoppingMode.view(data, week)
        val inMode = view.pending.flatMap { s -> s.group.items }.single { it.name == "Carta igienica" }
        assertFalse(inMode.purchased)
        assertEquals("Altro — 1 da comprare", view.pending.last().label)
        assertEquals(inMode, list(data = data).single { it.name == "Carta igienica" })
        assertEquals(4, view.progress.total)
    }
}
