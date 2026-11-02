package it.spesami.app.data

import it.spesami.core.AppData
import it.spesami.core.Backup
import it.spesami.core.BackupReadResult
import it.spesami.core.DefaultCategories
import it.spesami.core.ExportOptions
import it.spesami.core.MealType
import it.spesami.core.ShoppingExport
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryRepositoryTest {
    private val week = LocalDate.of(2026, 11, 2)
    private lateinit var helper: SpesamiDatabase
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        helper = SpesamiDatabase(RuntimeEnvironment.getApplication(), null) // in-memory
        repo = Repository(helper)
    }

    @After
    fun tearDown() = helper.close()

    private fun AppData.ingredient(name: String) = ingredients.single { it.name == name }
    private fun AppData.categoryKey(name: String) = categoryOf(ingredient(name))?.builtinKey
    private fun AppData.categoryId(key: String) = categories.single { it.builtinKey == key }.id

    @Test
    fun freshDatabaseHasDefaultCategories() {
        val data = repo.load()
        assertEquals(DefaultCategories.seed(), data.categories)
    }

    @Test
    fun newIngredientsAreClassifiedAutomatically() {
        repo.saveRecipe(null, "Pasta al tonno", "", listOf("Spaghetti", "Tonno in scatola", "Parmigiano", "Ingrediente segreto"))
        val data = repo.load()
        assertEquals("pasta_riso_cereali", data.categoryKey("Spaghetti"))
        assertEquals("scatolame_conserve", data.categoryKey("Tonno in scatola"))
        assertEquals("formaggi", data.categoryKey("Parmigiano"))
        assertNull(data.categoryKey("Ingrediente segreto")) // "Da classificare"
        assertFalse(data.ingredient("Parmigiano").categoryManual)
    }

    @Test
    fun manualCategoryIsNeverOverwritten() {
        repo.saveRecipe(null, "Insalata", "", listOf("Patate"))
        var data = repo.load()
        val patate = data.ingredient("Patate")
        repo.setIngredientCategory(patate.id, data.categoryId("altro"))
        repo.renameIngredient(patate.id, "Patate novelle") // would be frutta_verdura automatically
        repo.saveRecipe(null, "Purè", "", listOf("patate novelle"))
        data = repo.load()
        assertEquals("altro", data.categoryKey("Patate novelle"))
        assertTrue(data.ingredient("Patate novelle").categoryManual)

        // An automatic one follows its new name.
        repo.saveRecipe(null, "X", "", listOf("Ingrediente strano"))
        val strano = repo.load().ingredient("Ingrediente strano")
        repo.renameIngredient(strano.id, "Mozzarella")
        assertEquals("formaggi", repo.load().categoryKey("Mozzarella"))
    }

    @Test
    fun categoryChangeDoesNotTouchRecipesOrPlan() {
        val r = repo.saveRecipe(null, "Frittata", "", listOf("Uova", "Patate"))
        repo.setMeal(week, 0, MealType.LUNCH, r)
        val before = repo.load()
        repo.setIngredientCategory(before.ingredient("Uova").id, before.categoryId("altro"))
        val after = repo.load()
        assertEquals(before.recipes, after.recipes)
        assertEquals(before.slots, after.slots)
    }

    @Test
    fun createRenameReorderDelete() {
        repo.createCategory("Detersivi")
        var data = repo.load()
        val names = data.sortedCategories.map { it.name }
        assertEquals(listOf("Bevande", "Detersivi", "Altro"), names.takeLast(3)) // inserted before "Altro"
        assertTrue(repo.createCategory("detersivi") is Repository.CategoryChange.Conflict)

        val detersivi = data.sortedCategories.single { it.name == "Detersivi" }
        repo.renameCategory(detersivi.id, "Casa e pulizia")
        repo.moveCategory(detersivi.id, -100)
        data = repo.load()
        assertEquals("Casa e pulizia", data.sortedCategories.first().name)
        assertEquals((0 until data.categories.size).toList(), data.sortedCategories.map { it.position })

        // Deleting moves the ingredients to "Altro"; "Altro" itself cannot be deleted.
        repo.saveRecipe(null, "Pulizie", "", listOf("Spugne magiche"))
        val spugne = repo.load().ingredient("Spugne magiche")
        repo.setIngredientCategory(spugne.id, detersivi.id)
        repo.deleteCategory(detersivi.id)
        data = repo.load()
        assertEquals("altro", data.categoryKey("Spugne magiche"))
        assertTrue(repo.deleteCategory(data.categoryId("altro")) is Repository.CategoryChange.Conflict)
        assertEquals(12, repo.load().categories.size)
    }

    @Test
    fun groupedExportAcrossMealsWithFilter() {
        val a = repo.saveRecipe(null, "Pasta al tonno", "", listOf("Spaghetti", "Tonno in scatola", "Parmigiano"))
        val b = repo.saveRecipe(null, "Spaghetti al pomodoro", "", listOf("spaghetti", "Passata di pomodoro", "parmigiano"))
        val c = repo.saveRecipe(null, "Patate al forno", "", listOf("Patate", "Rosmarino"))
        repo.setMeal(week, 0, MealType.LUNCH, a)
        repo.setMeal(week, 1, MealType.DINNER, b)
        repo.setMeal(week, 2, MealType.LUNCH, c)
        repo.setMeal(week, 3, MealType.DINNER, a)
        val data = repo.load()

        assertEquals(3, ShoppingList.build(data, week).single { it.name == "Spaghetti" }.count)
        val text = ShoppingExport.toPlainText(ShoppingExport.sections(data, week, ExportOptions(includeCounts = true)), includeCounts = true)
        assertEquals(
            "FRUTTA E VERDURA\nPatate ×1\nRosmarino ×1\n\nFORMAGGI\nParmigiano ×3\n\n" +
                "SCATOLAME E CONSERVE\nPassata di pomodoro ×1\nTonno in scatola ×2\n\nPASTA, RISO E CEREALI\nSpaghetti ×3",
            text,
        )
        val onlyPantry = ExportOptions(excludedCategories = (data.categories.map { it.id } - data.categoryId("scatolame_conserve")).toSet<Long?>() + null)
        assertEquals(
            "SCATOLAME E CONSERVE\nPassata di pomodoro\nTonno in scatola",
            ShoppingExport.toPlainText(ShoppingExport.sections(data, week, onlyPantry), false),
        )
    }

    @Test
    fun backupRestoresCategoriesAndClassifications() {
        repo.createCategory("Casa")
        repo.saveRecipe(null, "X", "", listOf("Sapone di Marsiglia", "Latte"))
        var data = repo.load()
        val casa = data.categories.single { it.name == "Casa" }
        repo.setIngredientCategory(data.ingredient("Sapone di Marsiglia").id, casa.id)
        repo.moveCategory(casa.id, -3)
        val original = repo.load()
        val json = Backup.encode(original, "2026-11-02T10:00:00")

        repo.deleteCategory(casa.id)
        repo.renameCategory(original.categoryId("bevande"), "Bibite")
        repo.replaceAll((Backup.decode(json) as BackupReadResult.Valid).data)

        data = repo.load()
        assertEquals(original.sortedCategories, data.sortedCategories)
        assertEquals(original.ingredients.sortedBy { it.id }, data.ingredients.sortedBy { it.id })
        assertEquals("Casa", data.categoryOf(data.ingredient("Sapone di Marsiglia"))?.name)
    }
}
