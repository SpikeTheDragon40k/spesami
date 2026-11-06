package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class RecipeColorTest {
    @Test
    fun eightColoursWithStableUniqueKeys() {
        assertEquals(8, RecipeColor.entries.size)
        assertEquals(RecipeColor.entries.size, RecipeColor.entries.map { it.key }.toSet().size)
        RecipeColor.entries.forEach { assertEquals(it, RecipeColor.fromKey(it.key)) }
        assertEquals(RecipeColor.BLUE, RecipeColor.fromKey(" Blue "))
    }

    @Test
    fun unknownOrMissingKeysAreNoColour() {
        assertNull(RecipeColor.fromKey(null))
        assertNull(RecipeColor.fromKey(""))
        assertNull(RecipeColor.fromKey("ultraviolet"))
    }

    /** The text drawn on a tinted meal stays readable (WCAG AA: 4.5 for text, 3 for the coloured meal labels). */
    @Test
    fun textIsReadableOnEveryTint() {
        // Light: app onSurface / PDF text, app onSurfaceVariant, Pranzo and Cena labels. (On coloured cells the
        // PDF draws ingredients in its text colour, not its muted one, which is too faint there.)
        val lightText = listOf(0xFF1D1B20, 0xFF22212C, 0xFF49454F).map { it.toInt() }
        val lightLabels = listOf(0xFFB0306F, 0xFF2E7D4F).map { it.toInt() }
        // Dracula: foreground (app and PDF), app onSurfaceVariant, Pranzo and Cena labels.
        val darkText = listOf(0xFFF8F8F2, 0xFFC3C7DB).map { it.toInt() }
        val darkLabels = listOf(0xFFFF79C6, 0xFF50FA7B).map { it.toInt() }
        for (c in RecipeColor.entries) {
            lightText.forEach { assertReadable(c, "light", it, c.tintLight, 4.5) }
            lightLabels.forEach { assertReadable(c, "light label", it, c.tintLight, 3.0) }
            darkText.forEach { assertReadable(c, "dark", it, c.tintDark, 4.5) }
            darkLabels.forEach { assertReadable(c, "dark label", it, c.tintDark, 3.0) }
        }
    }

    @Test
    fun tintsAreDistinguishableFromEachOther() {
        for (shade in listOf(RecipeColor::tintLight, RecipeColor::tintDark, RecipeColor::swatchLight, RecipeColor::swatchDark)) {
            assertEquals(shade.name, RecipeColor.entries.size, RecipeColor.entries.map(shade).toSet().size)
        }
    }

    private fun assertReadable(c: RecipeColor, what: String, text: Int, background: Int, minimum: Double) {
        val ratio = contrast(text, background)
        assertTrue("${c.label} $what: %06X on %06X is %.2f".format(text and 0xFFFFFF, background and 0xFFFFFF, ratio), ratio >= minimum)
    }

    private fun luminance(argb: Int): Double {
        fun ch(shift: Int): Double {
            val v = (argb shr shift and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
