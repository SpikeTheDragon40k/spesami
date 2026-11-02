package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupTest {
    private val data = AppData(
        ingredients = listOf(Ingredient(1, "Patate", Category.PRODUCE), Ingredient(2, "Uova", null)),
        aliases = listOf(IngredientAlias(1, 1, "Patata")),
        recipes = listOf(Recipe(5, "Frittata di patate", "In forno 20 minuti", listOf(1, 2))),
        slots = listOf(MealSlot(LocalDate.of(2026, 11, 2), 2, MealType.DINNER, 5)),
        preferences = mapOf("theme" to "DRACULA"),
    )

    @Test
    fun roundTrip() {
        val result = Backup.decode(Backup.encode(data, "2026-11-02T10:00:00"))
        assertTrue(result is BackupReadResult.Valid)
        assertEquals(data, (result as BackupReadResult.Valid).data)
    }

    @Test
    fun rejectsGarbageAndOtherFormats() {
        assertTrue(Backup.decode("not json") is BackupReadResult.Invalid)
        assertTrue(Backup.decode("""{"format":"x","createdAt":"","ingredients":[],"aliases":[],"recipes":[],"mealPlan":[]}""") is BackupReadResult.Invalid)
    }

    @Test
    fun rejectsDanglingReferences() {
        val broken = Backup.encode(data.copy(recipes = listOf(Recipe(5, "Frittata", "", listOf(1, 99)))), "2026-11-02")
        val result = Backup.decode(broken)
        assertTrue(result is BackupReadResult.Invalid)
        assertTrue((result as BackupReadResult.Invalid).errors.single().contains("inesistente"))
    }

    @Test
    fun rejectsAliasCollidingWithIngredient() {
        val broken = Backup.encode(data.copy(aliases = listOf(IngredientAlias(1, 1, "uova"))), "2026-11-02")
        assertTrue(Backup.decode(broken) is BackupReadResult.Invalid)
    }
}
