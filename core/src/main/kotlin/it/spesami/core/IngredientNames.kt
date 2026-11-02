package it.spesami.core

import java.util.Locale

object IngredientNames {
    private val spaces = Regex("\\s+")

    /** Comparison key: "  Patate  Novelle " and "patate novelle" are the same ingredient. */
    fun key(name: String): String = clean(name).lowercase(Locale.ITALIAN)

    /** Display form: trimmed, single spaces, first letter upper case ("patate" -> "Patate"). */
    fun clean(name: String): String =
        name.replace(spaces, " ").trim().replaceFirstChar { it.titlecase(Locale.ITALIAN) }
}

/** Maps any typed name (canonical or alias, any case or spacing) to the canonical ingredient. */
class IngredientResolver(ingredients: List<Ingredient>, aliases: List<IngredientAlias>) {
    private val byKey: Map<String, Long> = buildMap {
        aliases.forEach { put(IngredientNames.key(it.alias), it.ingredientId) }
        // Canonical names win over aliases if data were ever inconsistent.
        ingredients.forEach { put(IngredientNames.key(it.name), it.id) }
    }

    fun resolve(name: String): Long? = byKey[IngredientNames.key(name)]
}
