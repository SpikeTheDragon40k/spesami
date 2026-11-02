package it.spesami.app.data

import it.spesami.core.Backup
import it.spesami.core.BackupReadResult
import it.spesami.core.Category
import it.spesami.core.MealType
import it.spesami.core.ShoppingList
import org.junit.After
import org.junit.Assert.assertEquals
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
class RepositoryTest {
    private val week = LocalDate.of(2026, 11, 2)
    private lateinit var helper: SpesamiDatabase
    private lateinit var repo: Repository

    @Before
    fun setUp() {
        helper = SpesamiDatabase(RuntimeEnvironment.getApplication())
        repo = Repository(helper)
    }

    @After
    fun tearDown() = helper.close()

    @Test
    fun recipesShareCanonicalIngredients() {
        val frittata = repo.saveRecipe(null, "Frittata", "", listOf("uova", " Patate ", "patate"))
        val pure = repo.saveRecipe(null, "Purè", "", listOf("PATATE", "Latte"))
        repo.setMeal(week, 0, MealType.LUNCH, frittata)
        repo.setMeal(week, 0, MealType.DINNER, pure)
        repo.setMeal(week, 2, MealType.DINNER, frittata)

        val data = repo.load()
        assertEquals(listOf("Latte", "Patate", "Uova"), data.ingredients.map { it.name }.sorted())
        val list = ShoppingList.build(data, week)
        assertEquals(listOf("Patate" to 3, "Uova" to 2, "Latte" to 1), list.map { it.name to it.count })
    }

    @Test
    fun aliasMatchingAnotherIngredientMergesThem() {
        val a = repo.saveRecipe(null, "Insalata", "", listOf("Patata"))
        val b = repo.saveRecipe(null, "Purè", "", listOf("Patate"))
        repo.setMeal(week, 0, MealType.LUNCH, a)
        repo.setMeal(week, 1, MealType.LUNCH, b)
        val patate = repo.load().ingredients.single { it.name == "Patate" }

        val change = repo.addAlias(patate.id, "patata")
        assertTrue(change is Repository.IngredientChange.Merged)

        val data = repo.load()
        assertEquals(listOf("Patate"), data.ingredients.map { it.name })
        assertEquals(listOf("Patata"), data.aliases.map { it.alias })
        assertEquals(listOf("Patate" to 2), ShoppingList.build(data, week).map { it.name to it.count })

        // New recipes typed with the alias resolve to the canonical ingredient.
        repo.saveRecipe(null, "Gnocchi", "", listOf("patata"))
        assertEquals(1, repo.load().ingredients.size)
    }

    @Test
    fun renameRejectsCollisions() {
        repo.saveRecipe(null, "X", "", listOf("Carote", "Zucchine"))
        val carote = repo.load().ingredients.single { it.name == "Carote" }
        assertTrue(repo.updateIngredient(carote.id, "zucchine", null) is Repository.IngredientChange.Conflict)
        assertEquals(Repository.IngredientChange.Done, repo.updateIngredient(carote.id, "Carote", Category.PRODUCE))
        assertEquals(Category.PRODUCE, repo.load().ingredientsById[carote.id]?.category)
    }

    @Test
    fun copyAndClearWeek() {
        val r = repo.saveRecipe(null, "Pasta al pomodoro", "", listOf("Pasta", "Pomodori"))
        repo.setMeal(week, 3, MealType.DINNER, r)
        repo.copyWeek(week, week.plusWeeks(1))
        assertEquals(1, repo.load().slotsOfWeek(week.plusWeeks(1)).size)
        repo.clearWeek(week)
        assertEquals(0, repo.load().slotsOfWeek(week).size)
        repo.deleteRecipe(r)
        assertEquals(0, repo.load().slots.size) // cascades
    }

    @Test
    fun backupRestoreReplacesEverything() {
        val r = repo.saveRecipe(null, "Frittata di spinaci", "Note", listOf("Uova", "Spinaci"))
        repo.setMeal(week, 4, MealType.LUNCH, r)
        val spinaci = repo.load().ingredients.single { it.name == "Spinaci" }
        repo.addAlias(spinaci.id, "Spinacio")
        repo.setPreference(Preferences.THEME, AppTheme.DRACULA.name)
        val original = repo.load()
        val json = Backup.encode(original, "2026-11-02T10:00:00")

        repo.saveRecipe(null, "Altro", "", listOf("Latte"))
        val restored = (Backup.decode(json) as BackupReadResult.Valid).data
        repo.replaceAll(restored)

        val after = repo.load()
        assertEquals(original.recipes, after.recipes)
        assertEquals(original.ingredients.sortedBy { it.id }, after.ingredients.sortedBy { it.id })
        assertEquals(original.slots, after.slots)
        assertEquals(listOf("Spinacio"), after.aliases.map { it.alias })
        assertEquals(AppTheme.DRACULA, Preferences.from(after.preferences).theme)
        // Ids continue after the restored ones.
        val next = repo.saveRecipe(null, "Nuova", "", listOf("Uova"))
        assertTrue(next > r)
    }
}
