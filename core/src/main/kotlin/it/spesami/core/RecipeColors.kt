package it.spesami.core

/**
 * The colour a recipe can be marked with, chosen by hand. It tints the recipe's meal cells in the weekly plan
 * (app and PDF). Stored, backed up and synced by [key], which never changes; unknown keys (from a newer app) read
 * as no colour.
 *
 * Every colour has a version for light backgrounds and one for dark (Dracula) backgrounds, as ARGB:
 * - [tintLight] / [tintDark]: the cell background. Pale on light, deep on dark, so the normal text colours stay
 *   readable on top (checked by `RecipeColorTest`).
 * - [swatchLight] / [swatchDark]: the saturated colour, for the picker dots and the accent bar.
 */
enum class RecipeColor(
    val key: String,
    val label: String,
    val swatchLight: Int,
    val tintLight: Int,
    val swatchDark: Int,
    val tintDark: Int,
) {
    RED("red", "Rosso", 0xFFD32F2F.toInt(), 0xFFFFDAD6.toInt(), 0xFFFF5555.toInt(), 0xFF5C2A33.toInt()),
    ORANGE("orange", "Arancione", 0xFFE65100.toInt(), 0xFFFFDDB8.toInt(), 0xFFFFB86C.toInt(), 0xFF5A3D27.toInt()),
    YELLOW("yellow", "Giallo", 0xFFB58900.toInt(), 0xFFFFF0A8.toInt(), 0xFFF1FA8C.toInt(), 0xFF4A4826.toInt()),
    GREEN("green", "Verde", 0xFF2E7D32.toInt(), 0xFFCDEFC8.toInt(), 0xFF50FA7B.toInt(), 0xFF27482F.toInt()),
    TEAL("teal", "Turchese", 0xFF00897B.toInt(), 0xFFBDEDE6.toInt(), 0xFF5FDDCB.toInt(), 0xFF1F4744.toInt()),
    BLUE("blue", "Blu", 0xFF1E66D0.toInt(), 0xFFD8E3FF.toInt(), 0xFF7AA2F7.toInt(), 0xFF2A3860.toInt()),
    PURPLE("purple", "Viola", 0xFF7B4CC2.toInt(), 0xFFE9DDFF.toInt(), 0xFFBD93F9.toInt(), 0xFF433668.toInt()),
    PINK("pink", "Rosa", 0xFFC2185B.toInt(), 0xFFFFD8E6.toInt(), 0xFFFF79C6.toInt(), 0xFF582C4B.toInt()),
    ;

    companion object {
        /** Null for null, blank or unknown keys. */
        fun fromKey(key: String?): RecipeColor? = key?.trim()?.lowercase()?.let { k -> entries.firstOrNull { it.key == k } }
    }
}
