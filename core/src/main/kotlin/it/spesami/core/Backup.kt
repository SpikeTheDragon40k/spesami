package it.spesami.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Versioned JSON file written and read by the manual backup feature. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: String,
    /** Since version 2. Version 1 files get the default categories. */
    val categories: List<BackupCategory> = emptyList(),
    val ingredients: List<BackupIngredient>,
    val aliases: List<BackupAlias>,
    val recipes: List<BackupRecipe>,
    val mealPlan: List<BackupMeal>,
    val preferences: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "spesami-backup"
        const val VERSION = 2
    }
}

@Serializable
data class BackupCategory(val id: Long, val name: String, val position: Int, val builtinKey: String? = null)

/** [category] is the version 1 enum name; version 2 uses [categoryId] and [categoryManual]. */
@Serializable
data class BackupIngredient(
    val id: Long,
    val name: String,
    val category: String? = null,
    val categoryId: Long? = null,
    val categoryManual: Boolean = false,
)

@Serializable
data class BackupAlias(val ingredientId: Long, val alias: String)

@Serializable
data class BackupRecipe(val id: Long, val name: String, val notes: String = "", val ingredientIds: List<Long>)

/** [weekStart] is an ISO date (a Monday), [day] 0..6, [meal] a [MealType] name. */
@Serializable
data class BackupMeal(val weekStart: String, val day: Int, val meal: String, val recipeId: Long)

sealed interface BackupReadResult {
    data class Valid(val data: AppData, val summary: String) : BackupReadResult
    data class Invalid(val errors: List<String>) : BackupReadResult
}

object Backup {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(data: AppData, createdAt: String): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            createdAt = createdAt,
            categories = data.sortedCategories.map { BackupCategory(it.id, it.name, it.position, it.builtinKey) },
            ingredients = data.ingredients.map { BackupIngredient(it.id, it.name, categoryId = it.categoryId, categoryManual = it.categoryManual) },
            aliases = data.aliases.map { BackupAlias(it.ingredientId, it.alias) },
            recipes = data.recipes.map { BackupRecipe(it.id, it.name, it.notes, it.ingredientIds) },
            mealPlan = data.slots.map { BackupMeal(it.weekStart.toString(), it.day, it.meal.name, it.recipeId) },
            preferences = data.preferences,
        ),
    )

    /** Parses and fully validates a backup; nothing is restored unless this returns [BackupReadResult.Valid]. */
    fun decode(text: String): BackupReadResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            return BackupReadResult.Invalid(listOf("Il file non è un backup di Spesami valido."))
        } catch (e: IllegalArgumentException) {
            return BackupReadResult.Invalid(listOf("Il file non è un backup di Spesami valido."))
        }
        if (file.format != BackupFile.FORMAT) {
            return BackupReadResult.Invalid(listOf("Il file non è un backup di Spesami."))
        }
        if (file.version > BackupFile.VERSION) {
            return BackupReadResult.Invalid(
                listOf("Il backup è stato creato da una versione più recente dell'app (formato ${file.version}).")
            )
        }

        val errors = mutableListOf<String>()

        val categories = if (file.version < 2) DefaultCategories.seed() else validateCategories(file.categories, errors)
        val categoryIds = categories.map { it.id }.toSet()
        val idByKey = categories.mapNotNull { c -> c.builtinKey?.let { it to c.id } }.toMap()

        val ingredientIds = HashSet<Long>()
        val nameKeys = HashSet<String>()
        val ingredients = file.ingredients.map { i ->
            if (!ingredientIds.add(i.id)) errors += "Ingrediente duplicato con id ${i.id}."
            if (i.name.isBlank()) errors += "Ingrediente ${i.id} senza nome."
            if (!nameKeys.add(IngredientNames.key(i.name))) errors += "Ingrediente ripetuto: \"${i.name}\"."
            if (file.version < 2) {
                // Version 1: map the old enum; unclassified ones get an automatic suggestion.
                val legacyKey = i.category?.let { DefaultCategories.fromLegacy(it, i.name) }
                if (i.category != null && legacyKey == null) errors += "Categoria sconosciuta per \"${i.name}\"."
                val key = legacyKey ?: IngredientDictionary.suggest(i.name)
                Ingredient(i.id, IngredientNames.clean(i.name), key?.let { idByKey[it] }, categoryManual = i.category != null)
            } else {
                if (i.categoryId != null && i.categoryId !in categoryIds) {
                    errors += "L'ingrediente \"${i.name}\" usa una categoria inesistente (${i.categoryId})."
                }
                Ingredient(i.id, IngredientNames.clean(i.name), i.categoryId, i.categoryManual)
            }
        }
        val aliases = file.aliases.mapIndexed { index, a ->
            if (a.ingredientId !in ingredientIds) errors += "L'alias \"${a.alias}\" punta a un ingrediente inesistente."
            if (a.alias.isBlank()) errors += "Alias vuoto."
            if (!nameKeys.add(IngredientNames.key(a.alias))) errors += "Alias ripetuto o uguale a un ingrediente: \"${a.alias}\"."
            IngredientAlias(index + 1L, a.ingredientId, IngredientNames.clean(a.alias))
        }
        val recipeIds = HashSet<Long>()
        val recipes = file.recipes.map { r ->
            if (!recipeIds.add(r.id)) errors += "Ricetta duplicata con id ${r.id}."
            if (r.name.isBlank()) errors += "Ricetta ${r.id} senza nome."
            r.ingredientIds.filter { it !in ingredientIds }.forEach {
                errors += "La ricetta \"${r.name}\" usa un ingrediente inesistente ($it)."
            }
            Recipe(r.id, r.name.trim(), r.notes, r.ingredientIds.distinct())
        }
        val slotKeys = HashSet<Triple<LocalDate, Int, MealType>>()
        val slots = file.mealPlan.mapNotNull { m ->
            val week = try {
                LocalDate.parse(m.weekStart)
            } catch (e: DateTimeParseException) {
                errors += "Data non valida nel piano: \"${m.weekStart}\"."
                return@mapNotNull null
            }
            val meal = MealType.entries.firstOrNull { it.name == m.meal }
            when {
                week != Weeks.startOf(week) -> errors += "La settimana ${m.weekStart} non inizia di lunedì."
                m.day !in 0..6 -> errors += "Giorno non valido nel piano: ${m.day}."
                meal == null -> errors += "Pasto non valido nel piano: \"${m.meal}\"."
                m.recipeId !in recipeIds -> errors += "Il piano usa una ricetta inesistente (${m.recipeId})."
                !slotKeys.add(Triple(week, m.day, meal)) -> errors += "Pasto ripetuto nel piano (${m.weekStart}, giorno ${m.day})."
                else -> return@mapNotNull MealSlot(week, m.day, meal, m.recipeId)
            }
            null
        }
        if (errors.isNotEmpty()) return BackupReadResult.Invalid(errors.distinct())

        val data = AppData(ingredients, aliases, recipes, slots, categories, file.preferences)
        val weeks = slots.map { it.weekStart }.distinct().size
        val summary = "${recipes.size} ricette, ${ingredients.size} ingredienti, ${aliases.size} alias, " +
            "${categories.size} categorie, ${slots.size} pasti in $weeks settimane (backup del ${file.createdAt.take(10)})"
        return BackupReadResult.Valid(data, summary)
    }

    private fun validateCategories(list: List<BackupCategory>, errors: MutableList<String>): List<ShoppingCategory> {
        val ids = HashSet<Long>()
        val names = HashSet<String>()
        val keys = HashSet<String>()
        list.forEach { c ->
            if (!ids.add(c.id)) errors += "Categoria duplicata con id ${c.id}."
            if (c.name.isBlank()) errors += "Categoria ${c.id} senza nome."
            if (!names.add(IngredientNames.key(c.name))) errors += "Categoria ripetuta: \"${c.name}\"."
            if (c.builtinKey != null && (c.builtinKey !in DefaultCategories.keys || !keys.add(c.builtinKey))) {
                errors += "Categoria predefinita non valida: \"${c.name}\"."
            }
        }
        if (DefaultCategories.OTHER !in keys) errors += "Manca la categoria \"Altro\"."
        // Positions are normalised to 0..n-1 in their saved order.
        return list.sortedWith(compareBy({ it.position }, { it.id }))
            .mapIndexed { i, c -> ShoppingCategory(c.id, c.name.trim(), i, c.builtinKey) }
    }
}
