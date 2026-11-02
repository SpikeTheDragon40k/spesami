package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    @Test
    fun defaultsHaveStableIdsAndOrder() {
        val seed = DefaultCategories.seed()
        assertEquals(12, seed.size)
        assertEquals((1L..12L).toList(), seed.map { it.id })
        assertEquals((0..11).toList(), seed.map { it.position })
        assertEquals("Frutta e verdura", seed.first().name)
        assertEquals(DefaultCategories.OTHER, seed.last().builtinKey)
    }

    @Test
    fun dictionaryExamples() {
        val expected = mapOf(
            "Patate" to "frutta_verdura",
            "Parmigiano" to "formaggi",
            "Latte" to "latte_latticini",
            "Tonno in scatola" to "scatolame_conserve",
            "Petto di pollo" to "carne_salumi",
            "Fette biscottate" to "colazione_merende",
            "Caffè" to "colazione_merende",
            "Ceci in scatola" to "scatolame_conserve",
            "Piselli surgelati" to "pesce_surgelati",
            "Pomodori pelati" to "scatolame_conserve",
            "Fiocchi di latte" to "formaggi",
            "Pasta sfoglia" to "pane_forno",
            "Spaghetti" to "pasta_riso_cereali",
            "Olio extravergine di oliva" to "condimenti_spezie_salse",
            "Filetto di salmone" to "pesce_surgelati",
            "Zucchine grigliate" to "frutta_verdura",
            "Mozzarella di bufala" to "formaggi",
            "Acqua frizzante" to "bevande",
        )
        expected.forEach { (name, key) -> assertEquals(name, key, IngredientDictionary.suggest(name)) }
        assertTrue(IngredientDictionary.size > 300)
    }

    @Test
    fun unknownOrAmbiguousNamesAreNotClassified() {
        assertNull(IngredientDictionary.suggest("Quinotto misterioso"))
        assertNull(IngredientDictionary.suggest("Latte di cocco")) // latte vs cocco
        assertNull(IngredientDictionary.suggest(""))
    }

    @Test
    fun legacyCategoriesMapToNewOnes() {
        assertEquals("pesce_surgelati", DefaultCategories.fromLegacy("MEAT_FISH", "Salmone"))
        assertEquals("carne_salumi", DefaultCategories.fromLegacy("MEAT_FISH", "Qualcosa"))
        assertEquals("formaggi", DefaultCategories.fromLegacy("DAIRY_EGGS", "Parmigiano"))
        assertEquals("latte_latticini", DefaultCategories.fromLegacy("DAIRY_EGGS", "Uova"))
        // The user's old choice wins over the dictionary when they disagree.
        assertEquals("frutta_verdura", DefaultCategories.fromLegacy("PRODUCE", "Parmigiano"))
        assertNull(DefaultCategories.fromLegacy("NOPE", "Patate"))
    }
}
