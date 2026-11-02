package it.spesami.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.Ingredient
import it.spesami.core.IngredientAlias
import it.spesami.core.IngredientDictionary
import it.spesami.core.IngredientNames
import it.spesami.core.MealSlot
import it.spesami.core.MealType
import it.spesami.core.Recipe
import it.spesami.core.ShoppingCategory
import java.time.LocalDate

/** All reads and writes of the local database. Call from a background thread. */
class Repository(private val helper: SpesamiDatabase) {
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun load(): AppData {
        val categories = db.rawQuery("SELECT id, name, position, builtin_key FROM shopping_category ORDER BY position, id", null).use { c ->
            buildList { while (c.moveToNext()) add(ShoppingCategory(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3))) }
        }
        val ingredients = db.rawQuery("SELECT id, name, category_id, category_manual FROM ingredient", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Ingredient(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3) != 0))
                }
            }
        }
        val aliases = db.rawQuery("SELECT id, ingredient_id, alias FROM ingredient_alias ORDER BY alias", null).use { c ->
            buildList { while (c.moveToNext()) add(IngredientAlias(c.getLong(0), c.getLong(1), c.getString(2))) }
        }
        val recipeIngredients = HashMap<Long, MutableList<Long>>()
        db.rawQuery("SELECT recipe_id, ingredient_id FROM recipe_ingredient ORDER BY recipe_id, position", null).use { c ->
            while (c.moveToNext()) recipeIngredients.getOrPut(c.getLong(0)) { mutableListOf() } += c.getLong(1)
        }
        val recipes = db.rawQuery("SELECT id, name, notes FROM recipe", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(Recipe(id, c.getString(1), c.getString(2), recipeIngredients[id].orEmpty()))
                }
            }
        }
        val slots = db.rawQuery("SELECT week_start, day, meal, recipe_id FROM meal_slot", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val meal = MealType.entries.firstOrNull { it.name == c.getString(2) } ?: continue
                    add(MealSlot(LocalDate.parse(c.getString(0)), c.getInt(1), meal, c.getLong(3)))
                }
            }
        }
        val preferences = db.rawQuery("SELECT key, value FROM preference", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        return AppData(ingredients, aliases, recipes, slots, categories, preferences)
    }

    // --- Recipes ---

    /** Creates or updates a recipe; each typed ingredient name is matched to an existing ingredient or alias, or created. */
    fun saveRecipe(id: Long?, name: String, notes: String, ingredientNames: List<String>): Long = inTransaction {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("notes", notes.trim())
        }
        val recipeId = if (id == null) {
            db.insertOrThrow("recipe", null, values)
        } else {
            db.update("recipe", values, "id = ?", arrayOf(id.toString()))
            db.delete("recipe_ingredient", "recipe_id = ?", arrayOf(id.toString()))
            id
        }
        ingredientNames.filter { it.isNotBlank() }
            .map { findOrCreateIngredient(it) }
            .distinct()
            .forEachIndexed { position, ingredientId ->
                db.insertOrThrow("recipe_ingredient", null, ContentValues().apply {
                    put("recipe_id", recipeId)
                    put("ingredient_id", ingredientId)
                    put("position", position)
                })
            }
        recipeId
    }

    fun deleteRecipe(id: Long) {
        db.delete("recipe", "id = ?", arrayOf(id.toString()))
    }

    // --- Meal plan ---

    fun setMeal(weekStart: LocalDate, day: Int, meal: MealType, recipeId: Long?) {
        val args = arrayOf(weekStart.toString(), day.toString(), meal.name)
        if (recipeId == null) {
            db.delete("meal_slot", "week_start = ? AND day = ? AND meal = ?", args)
        } else {
            db.insertWithOnConflict("meal_slot", null, ContentValues().apply {
                put("week_start", weekStart.toString())
                put("day", day)
                put("meal", meal.name)
                put("recipe_id", recipeId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun clearWeek(weekStart: LocalDate) {
        db.delete("meal_slot", "week_start = ?", arrayOf(weekStart.toString()))
    }

    /** Replaces the plan of [to] with a copy of the plan of [from]. */
    fun copyWeek(from: LocalDate, to: LocalDate) = inTransaction {
        clearWeek(to)
        db.execSQL(
            "INSERT INTO meal_slot (week_start, day, meal, recipe_id) SELECT ?, day, meal, recipe_id FROM meal_slot WHERE week_start = ?",
            arrayOf(to.toString(), from.toString()),
        )
    }

    // --- Ingredients and aliases ---

    sealed interface IngredientChange {
        data object Done : IngredientChange
        data class Merged(val into: String) : IngredientChange
        data class Conflict(val message: String) : IngredientChange
    }

    /** Renames an ingredient. An automatically classified one is re-classified for its new name. */
    fun renameIngredient(id: Long, name: String): IngredientChange = inTransaction {
        val key = IngredientNames.key(name)
        val other = ingredientIdByKey(key)
        val aliasOwner = aliasOwnerByKey(key)
        when {
            key.isEmpty() -> IngredientChange.Done
            other != null && other != id -> IngredientChange.Conflict("Esiste già un ingrediente \"${IngredientNames.clean(name)}\". Usa \"Unisci\".")
            aliasOwner != null && aliasOwner != id -> IngredientChange.Conflict("\"${IngredientNames.clean(name)}\" è già un alias di un altro ingrediente.")
            else -> {
                // The ingredient's new name must not stay as one of its own aliases.
                db.delete("ingredient_alias", "alias_key = ?", arrayOf(key))
                db.update("ingredient", ContentValues().apply {
                    put("name", IngredientNames.clean(name))
                    put("name_key", key)
                }, "id = ?", arrayOf(id.toString()))
                db.update("ingredient", ContentValues().apply { put("category_id", suggestedCategoryId(name)) },
                    "id = ? AND category_manual = 0", arrayOf(id.toString()))
                IngredientChange.Done
            }
        }
    }

    /** The user's choice ([categoryId] null = "Da classificare"): marked manual, never auto-changed again. */
    fun setIngredientCategory(id: Long, categoryId: Long?) {
        db.update("ingredient", ContentValues().apply {
            put("category_id", categoryId)
            put("category_manual", 1)
        }, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Adds [alias] to ingredient [id]. If the alias is the name of another ingredient, that ingredient is merged
     * into this one, so the shopping list counts both as the same item.
     */
    fun addAlias(id: Long, alias: String): IngredientChange = inTransaction {
        val key = IngredientNames.key(alias)
        val other = ingredientIdByKey(key)
        val aliasOwner = aliasOwnerByKey(key)
        when {
            key.isEmpty() || other == id || aliasOwner == id -> IngredientChange.Done
            aliasOwner != null -> IngredientChange.Conflict("\"${IngredientNames.clean(alias)}\" è già un alias di un altro ingrediente.")
            other != null -> {
                mergeIngredient(other, id)
                IngredientChange.Merged(ingredientName(id))
            }
            else -> {
                insertAlias(id, alias)
                IngredientChange.Done
            }
        }
    }

    fun removeAlias(aliasId: Long) {
        db.delete("ingredient_alias", "id = ?", arrayOf(aliasId.toString()))
    }

    /** Moves every use and alias of [sourceId] to [targetId]; the source name becomes an alias of the target. */
    fun mergeIngredient(sourceId: Long, targetId: Long) = inTransaction {
        if (sourceId == targetId) return@inTransaction
        val sourceName = ingredientName(sourceId)
        val src = arrayOf(sourceId.toString())
        db.execSQL(
            "INSERT OR IGNORE INTO recipe_ingredient (recipe_id, ingredient_id, position) " +
                "SELECT recipe_id, ?, position FROM recipe_ingredient WHERE ingredient_id = ?",
            arrayOf(targetId, sourceId),
        )
        db.execSQL("UPDATE ingredient_alias SET ingredient_id = ? WHERE ingredient_id = ?", arrayOf(targetId, sourceId))
        // A target without any category (and no explicit choice) takes the source's.
        db.execSQL(
            "UPDATE ingredient SET category_id = (SELECT category_id FROM ingredient WHERE id = ?), " +
                "category_manual = (SELECT category_manual FROM ingredient WHERE id = ?) " +
                "WHERE id = ? AND category_id IS NULL AND category_manual = 0",
            arrayOf(sourceId, sourceId, targetId),
        )
        db.delete("ingredient", "id = ?", src) // cascades the remaining recipe_ingredient rows
        insertAlias(targetId, sourceName)
    }

    fun deleteIngredient(id: Long) {
        db.delete("ingredient", "id = ?", arrayOf(id.toString()))
    }

    // --- Shopping categories ---

    sealed interface CategoryChange {
        data object Done : CategoryChange
        data class Conflict(val message: String) : CategoryChange
    }

    /** Adds a category just before "Altro" (or at the end if "Altro" was moved). */
    fun createCategory(name: String): CategoryChange = inTransaction {
        val clean = name.trim()
        if (clean.isEmpty()) return@inTransaction CategoryChange.Done
        if (categoryIdByKey(IngredientNames.key(clean)) != null) return@inTransaction CategoryChange.Conflict("Esiste già la categoria \"$clean\".")
        val ordered = orderedCategoryIds()
        val other = otherCategoryId()
        val insertAt = if (other != null && ordered.lastOrNull() == other) ordered.size - 1 else ordered.size
        val id = db.insertOrThrow("shopping_category", null, ContentValues().apply {
            put("name", clean)
            put("name_key", IngredientNames.key(clean))
            put("position", insertAt)
        })
        writePositions(ordered.toMutableList().apply { add(insertAt, id) })
        CategoryChange.Done
    }

    fun renameCategory(id: Long, name: String): CategoryChange = inTransaction {
        val clean = name.trim()
        val other = categoryIdByKey(IngredientNames.key(clean))
        when {
            clean.isEmpty() -> CategoryChange.Done
            other != null && other != id -> CategoryChange.Conflict("Esiste già la categoria \"$clean\".")
            else -> {
                db.update("shopping_category", ContentValues().apply {
                    put("name", clean)
                    put("name_key", IngredientNames.key(clean))
                }, "id = ?", arrayOf(id.toString()))
                CategoryChange.Done
            }
        }
    }

    /** Moves a category [delta] places up (negative) or down in the list order. */
    fun moveCategory(id: Long, delta: Int) = inTransaction {
        val ordered = orderedCategoryIds().toMutableList()
        val from = ordered.indexOf(id)
        if (from < 0) return@inTransaction
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        ordered.add(to, ordered.removeAt(from))
        writePositions(ordered)
    }

    /** Deletes a category; its ingredients move to "Altro", which itself cannot be deleted. */
    fun deleteCategory(id: Long): CategoryChange = inTransaction {
        val other = otherCategoryId()
        if (other == null || other == id) return@inTransaction CategoryChange.Conflict("La categoria \"Altro\" non può essere eliminata.")
        db.execSQL("UPDATE ingredient SET category_id = ? WHERE category_id = ?", arrayOf(other, id))
        db.delete("shopping_category", "id = ?", arrayOf(id.toString()))
        writePositions(orderedCategoryIds())
        CategoryChange.Done
    }

    // --- Preferences ---

    fun setPreference(key: String, value: String) {
        db.insertWithOnConflict("preference", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // --- Backup ---

    /** Replaces all local data with [data] (already validated) in one transaction. */
    fun replaceAll(data: AppData) = inTransaction {
        listOf("meal_slot", "recipe_ingredient", "recipe", "ingredient_alias", "ingredient", "shopping_category", "preference").forEach {
            db.delete(it, null, null)
        }
        db.execSQL("DELETE FROM sqlite_sequence")
        data.categories.forEach {
            db.insertOrThrow("shopping_category", null, ContentValues().apply {
                put("id", it.id)
                put("name", it.name)
                put("name_key", IngredientNames.key(it.name))
                put("position", it.position)
                put("builtin_key", it.builtinKey)
            })
        }
        data.ingredients.forEach {
            db.insertOrThrow("ingredient", null, ContentValues().apply {
                put("id", it.id)
                put("name", it.name)
                put("name_key", IngredientNames.key(it.name))
                put("category_id", it.categoryId)
                put("category_manual", if (it.categoryManual) 1 else 0)
            })
        }
        data.aliases.forEach { insertAlias(it.ingredientId, it.alias) }
        data.recipes.forEach { r ->
            db.insertOrThrow("recipe", null, ContentValues().apply {
                put("id", r.id)
                put("name", r.name)
                put("notes", r.notes)
            })
            r.ingredientIds.forEachIndexed { position, ingredientId ->
                db.insertOrThrow("recipe_ingredient", null, ContentValues().apply {
                    put("recipe_id", r.id)
                    put("ingredient_id", ingredientId)
                    put("position", position)
                })
            }
        }
        data.slots.forEach { setMeal(it.weekStart, it.day, it.meal, it.recipeId) }
        data.preferences.forEach { (k, v) -> setPreference(k, v) }
    }

    // --- Helpers ---

    private fun findOrCreateIngredient(name: String): Long {
        val key = IngredientNames.key(name)
        return ingredientIdByKey(key) ?: aliasOwnerByKey(key) ?: db.insertOrThrow("ingredient", null, ContentValues().apply {
            put("name", IngredientNames.clean(name))
            put("name_key", key)
            put("category_id", suggestedCategoryId(name)) // automatic, only for unambiguous dictionary matches
        })
    }

    /** The dictionary's category for [name], if that default category still exists. */
    private fun suggestedCategoryId(name: String): Long? =
        IngredientDictionary.suggest(name)?.let { key ->
            db.rawQuery("SELECT id FROM shopping_category WHERE builtin_key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }
        }

    private fun otherCategoryId(): Long? =
        db.rawQuery("SELECT id FROM shopping_category WHERE builtin_key = ?", arrayOf(DefaultCategories.OTHER)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun categoryIdByKey(key: String): Long? =
        db.rawQuery("SELECT id FROM shopping_category WHERE name_key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun orderedCategoryIds(): List<Long> =
        db.rawQuery("SELECT id FROM shopping_category ORDER BY position, id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    private fun writePositions(ordered: List<Long>) {
        ordered.forEachIndexed { position, id ->
            db.update("shopping_category", ContentValues().apply { put("position", position) }, "id = ?", arrayOf(id.toString()))
        }
    }

    private fun insertAlias(ingredientId: Long, alias: String) {
        db.insertWithOnConflict("ingredient_alias", null, ContentValues().apply {
            put("ingredient_id", ingredientId)
            put("alias", IngredientNames.clean(alias))
            put("alias_key", IngredientNames.key(alias))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun ingredientIdByKey(key: String): Long? =
        db.rawQuery("SELECT id FROM ingredient WHERE name_key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun aliasOwnerByKey(key: String): Long? =
        db.rawQuery("SELECT ingredient_id FROM ingredient_alias WHERE alias_key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun ingredientName(id: Long): String =
        db.rawQuery("SELECT name FROM ingredient WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }

    private fun <T> inTransaction(block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }
}
