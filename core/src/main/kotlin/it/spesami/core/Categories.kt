package it.spesami.core

object DefaultCategories {
    /** Fallback category: never deletable, receives the ingredients of deleted categories. */
    const val OTHER = "altro"

    /** Label for ingredients without a category. Not a real category: it cannot be renamed or deleted. */
    const val UNCLASSIFIED_LABEL = "Da classificare"

    /** Seeded categories as (builtin key, Italian name), in their default order. */
    val all: List<Pair<String, String>> = listOf(
        "frutta_verdura" to "Frutta e verdura",
        "carne_salumi" to "Carne e salumi",
        "pesce_surgelati" to "Pesce e surgelati",
        "formaggi" to "Formaggi",
        "latte_latticini" to "Latte e latticini",
        "scatolame_conserve" to "Scatolame e conserve",
        "pasta_riso_cereali" to "Pasta, riso e cereali",
        "pane_forno" to "Pane e prodotti da forno",
        "colazione_merende" to "Colazione e merende",
        "condimenti_spezie_salse" to "Condimenti, spezie e salse",
        "bevande" to "Bevande",
        OTHER to "Altro",
    )

    val keys: Set<String> = all.map { it.first }.toSet()

    /** Stable ids 1..12 in default order, and global ids that are the same on every device. */
    fun seed(): List<ShoppingCategory> = all.mapIndexed { i, (key, name) -> ShoppingCategory(i + 1L, name, i, key, Uids.builtinCategory(key)) }

    /**
     * Maps a category of the first app version (an enum name) to a default category. The old groups were
     * broader, so the dictionary splits them when it agrees (e.g. old "Carne e pesce" + "Salmone" -> pesce).
     */
    fun fromLegacy(legacy: String, ingredientName: String): String? {
        val (allowed, fallback) = when (legacy) {
            "PRODUCE" -> setOf("frutta_verdura") to "frutta_verdura"
            "MEAT_FISH" -> setOf("carne_salumi", "pesce_surgelati") to "carne_salumi"
            "DAIRY_EGGS" -> setOf("latte_latticini", "formaggi") to "latte_latticini"
            "BREAD_CEREALS" -> setOf("pasta_riso_cereali", "pane_forno", "colazione_merende") to "pasta_riso_cereali"
            "PANTRY" -> setOf("scatolame_conserve", "condimenti_spezie_salse", "colazione_merende", "pasta_riso_cereali") to "scatolame_conserve"
            "FROZEN" -> setOf("pesce_surgelati") to "pesce_surgelati"
            "DRINKS" -> setOf("bevande") to "bevande"
            "OTHER" -> setOf(OTHER) to OTHER
            else -> return null
        }
        return IngredientDictionary.suggest(ingredientName)?.takeIf { it in allowed } ?: fallback
    }
}
