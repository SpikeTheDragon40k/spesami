package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BackupTest {
    private val data = AppData(
        ingredients = listOf(Ingredient(1, "Patate", 1, categoryManual = true), Ingredient(2, "Uova", null), Ingredient(3, "Bucce", 13)),
        aliases = listOf(IngredientAlias(1, 1, "Patata")),
        recipes = listOf(Recipe(5, "Frittata di patate", "In forno 20 minuti", listOf(1, 2))),
        slots = listOf(MealSlot(LocalDate.of(2026, 11, 2), 2, MealType.DINNER, 5)),
        categories = DefaultCategories.seed() + ShoppingCategory(13, "Compostaggio", 12),
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

    @Test
    fun rejectsUnknownCategoryReference() {
        val broken = Backup.encode(data.copy(ingredients = data.ingredients + Ingredient(9, "Sale", 77)), "2026-11-02")
        val errors = (Backup.decode(broken) as BackupReadResult.Invalid).errors
        assertTrue(errors.single().contains("categoria inesistente"))
    }

    @Test
    fun rejectsMissingFallbackCategory() {
        val broken = Backup.encode(data.copy(categories = data.categories.filter { it.builtinKey != DefaultCategories.OTHER }), "2026-11-02")
        assertTrue(Backup.decode(broken) is BackupReadResult.Invalid)
    }

    @Test
    fun readsVersion1Backups() {
        val v1 = """
            {"format":"spesami-backup","version":1,"createdAt":"2026-11-02T10:00:00",
             "ingredients":[{"id":1,"name":"Salmone","category":"MEAT_FISH"},{"id":2,"name":"Parmigiano"},{"id":3,"name":"Boh"}],
             "aliases":[],"recipes":[{"id":1,"name":"Salmone al forno","ingredientIds":[1,2,3]}],"mealPlan":[],"preferences":{}}
        """.trimIndent()
        val restored = (Backup.decode(v1) as BackupReadResult.Valid).data
        assertEquals(DefaultCategories.seed(), restored.categories)
        val byName = restored.ingredients.associateBy { it.name }
        assertEquals("pesce_surgelati", restored.categoryOf(byName.getValue("Salmone"))?.builtinKey)
        assertTrue(byName.getValue("Salmone").categoryManual)
        assertEquals("formaggi", restored.categoryOf(byName.getValue("Parmigiano"))?.builtinKey)
        assertEquals(false, byName.getValue("Parmigiano").categoryManual)
        assertEquals(null, byName.getValue("Boh").categoryId)
    }
}
