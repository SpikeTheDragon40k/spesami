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
import it.spesami.core.Uids
import it.spesami.core.sync.Snapshot
import it.spesami.core.WeekItem
import java.time.LocalDate

/** All reads and writes of the local database. Call from a background thread. */
class Repository(private val helper: SpesamiDatabase) {
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun load(): AppData {
        val categories = db.rawQuery("SELECT id, name, position, builtin_key, uid FROM shopping_category ORDER BY position, id", null).use { c ->
            buildList { while (c.moveToNext()) add(ShoppingCategory(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4))) }
        }
        val ingredients = db.rawQuery("SELECT id, name, category_id, category_manual, uid FROM ingredient", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Ingredient(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3) != 0, c.getString(4)))
                }
            }
        }
        val aliases = db.rawQuery("SELECT id, ingredient_id, alias, uid FROM ingredient_alias ORDER BY alias", null).use { c ->
            buildList { while (c.moveToNext()) add(IngredientAlias(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) }
        }
        val recipeIngredients = HashMap<Long, MutableList<Long>>()
        db.rawQuery("SELECT recipe_id, ingredient_id FROM recipe_ingredient ORDER BY recipe_id, position", null).use { c ->
            while (c.moveToNext()) recipeIngredients.getOrPut(c.getLong(0)) { mutableListOf() } += c.getLong(1)
        }
        val recipes = db.rawQuery("SELECT id, name, notes, uid FROM recipe", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(Recipe(id, c.getString(1), c.getString(2), recipeIngredients[id].orEmpty(), c.getString(3)))
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
        fun weekItems(table: String) = db.rawQuery("SELECT week_start, ingredient_id FROM $table", null).use { c ->
            buildList { while (c.moveToNext()) add(WeekItem(LocalDate.parse(c.getString(0)), c.getLong(1))) }
        }
        return AppData(ingredients, aliases, recipes, slots, categories, preferences, weekItems("shopping_purchased"), weekItems("shopping_extra"))
    }

    // --- Recipes ---

    /** Creates or updates a recipe; each typed ingredient name is matched to an existing ingredient or alias, or created. */
    fun saveRecipe(id: Long?, name: String, notes: String, ingredientNames: List<String>): Long = inTransaction {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("notes", notes.trim())
        }
        val recipeId = if (id == null) {
            db.insertOrThrow("recipe", null, values.apply { put("uid", Uids.random()) })
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

    // --- Weekly shopping checklist ---

    /** Marks or unmarks one product of [weekStart]'s list as purchased. Other weeks are independent. */
    fun setPurchased(weekStart: LocalDate, ingredientId: Long, purchased: Boolean) {
        if (purchased) {
            db.insertWithOnConflict("shopping_purchased", null, weekItem(weekStart, ingredientId), SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            db.delete("shopping_purchased", "week_start = ? AND ingredient_id = ?", arrayOf(weekStart.toString(), ingredientId.toString()))
        }
    }

    /** "Reimposta acquisti": unchecks everything of one week; plan, recipes, categories and added products stay. */
    fun resetPurchases(weekStart: LocalDate) {
        db.delete("shopping_purchased", "week_start = ?", arrayOf(weekStart.toString()))
    }

    sealed interface ProductChange {
        data class Added(val name: String) : ProductChange
        data class AlreadyListed(val name: String) : ProductChange
        data object Empty : ProductChange
    }

    /**
     * "Aggiungi prodotto": puts [name] on [weekStart]'s list without touching recipes or the plan. The product is
     * matched to an existing ingredient or alias (so "Patate" never shows twice) or created, classified by the
     * dictionary unless [categoryId] is given; [categoryId] is applied as a manual choice. An explicitly added
     * product is something to buy, so it is (re)marked as still to buy.
     */
    fun addProduct(weekStart: LocalDate, name: String, categoryId: Long? = null, setCategory: Boolean = false): ProductChange = inTransaction {
        if (IngredientNames.key(name).isEmpty()) return@inTransaction ProductChange.Empty
        val id = findOrCreateIngredient(name)
        if (setCategory) setIngredientCategory(id, categoryId)
        val listed = db.rawQuery(
            "SELECT 1 FROM shopping_extra WHERE week_start = ? AND ingredient_id = ?", arrayOf(weekStart.toString(), id.toString()),
        ).use { it.moveToFirst() }
        if (!listed) db.insertOrThrow("shopping_extra", null, weekItem(weekStart, id))
        setPurchased(weekStart, id, false)
        if (!listed) ProductChange.Added(ingredientName(id)) else ProductChange.AlreadyListed(ingredientName(id))
    }

    /** Removes a hand-added product from one week; it stays only if a planned meal still needs it. */
    fun removeProduct(weekStart: LocalDate, ingredientId: Long) {
        db.delete("shopping_extra", "week_start = ? AND ingredient_id = ?", arrayOf(weekStart.toString(), ingredientId.toString()))
    }

    private fun weekItem(weekStart: LocalDate, ingredientId: Long) = ContentValues().apply {
        put("week_start", weekStart.toString())
        put("ingredient_id", ingredientId)
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
        // Remembered by global id, so the merge can be repeated on other devices (see SpesamiDatabase.migrateTo4).
        db.insertWithOnConflict("ingredient_redirect", null, ContentValues().apply {
            put("from_uid", ingredientUid(sourceId))
            put("to_uid", ingredientUid(targetId))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val src = arrayOf(sourceId.toString())
        db.execSQL(
            "INSERT OR IGNORE INTO recipe_ingredient (recipe_id, ingredient_id, position) " +
                "SELECT recipe_id, ?, position FROM recipe_ingredient WHERE ingredient_id = ?",
            arrayOf(targetId, sourceId),
        )
        db.execSQL("UPDATE ingredient_alias SET ingredient_id = ? WHERE ingredient_id = ?", arrayOf(targetId, sourceId))
        // The merged item keeps its checklist state in every week.
        for (table in listOf("shopping_purchased", "shopping_extra")) {
            db.execSQL(
                "INSERT OR IGNORE INTO $table (week_start, ingredient_id) SELECT week_start, ? FROM $table WHERE ingredient_id = ?",
                arrayOf(targetId, sourceId),
            )
        }
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
            put("uid", Uids.random())
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
        listOf("shopping_purchased", "shopping_extra", "meal_slot", "recipe_ingredient", "recipe", "ingredient_alias", "ingredient", "shopping_category", "preference").forEach {
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
                put("uid", it.uid.ifEmpty { it.builtinKey?.let(Uids::builtinCategory) ?: Uids.random() })
            })
        }
        data.ingredients.forEach {
            db.insertOrThrow("ingredient", null, ContentValues().apply {
                put("id", it.id)
                put("name", it.name)
                put("name_key", IngredientNames.key(it.name))
                put("category_id", it.categoryId)
                put("category_manual", if (it.categoryManual) 1 else 0)
                put("uid", it.uid.ifEmpty { Uids.random() })
            })
        }
        data.aliases.forEach { insertAlias(it.ingredientId, it.alias, it.uid) }
        data.recipes.forEach { r ->
            db.insertOrThrow("recipe", null, ContentValues().apply {
                put("id", r.id)
                put("name", r.name)
                put("notes", r.notes)
                put("uid", r.uid.ifEmpty { Uids.random() })
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
        data.manualItems.forEach { db.insertOrThrow("shopping_extra", null, weekItem(it.weekStart, it.ingredientId)) }
        data.purchases.forEach { db.insertOrThrow("shopping_purchased", null, weekItem(it.weekStart, it.ingredientId)) }
    }

    // --- Sync ---

    /** The synced part of the data, with the ingredient merges not yet recorded for sync. */
    fun syncSnapshot(): Snapshot {
        val redirects = db.rawQuery("SELECT from_uid, to_uid FROM ingredient_redirect", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        return Snapshot.from(load(), redirects)
    }

    /** Forgets merges once recorded (or when sync was never set up, where nobody needs them). */
    fun clearRedirects(fromUids: Collection<String>? = null) {
        if (fromUids == null) {
            db.delete("ingredient_redirect", null, null)
        } else {
            fromUids.forEach { db.delete("ingredient_redirect", "from_uid = ?", arrayOf(it)) }
        }
    }

    /**
     * Makes the synced data equal to [target], in one transaction, but only if it is still [expected] (nothing was
     * edited while the sync was talking to Drive); returns false otherwise and changes nothing. Rows are matched by
     * uid and keep their local ids, so anything referring to them by id (e.g. export settings) stays valid.
     * Preferences and sync data are not touched.
     */
    fun applySnapshot(expected: Snapshot, target: Snapshot): Boolean = inTransaction {
        if (syncSnapshot() != expected) return@inTransaction false
        // Fully rewritten below; deleting them first also lets ingredients and recipes go without cascades.
        listOf("shopping_purchased", "shopping_extra", "meal_slot", "recipe_ingredient", "ingredient_alias").forEach { db.delete(it, null, null) }

        val categoryIds = upsertByUid("shopping_category", target.categories.map { it.uid })
        target.categories.forEach { c ->
            db.update("shopping_category", ContentValues().apply {
                put("name", c.name)
                put("name_key", IngredientNames.key(c.name))
                put("position", c.position)
                put("builtin_key", c.builtinKey)
            }, "uid = ?", arrayOf(c.uid))
        }

        val ingredientIds = upsertByUid("ingredient", target.ingredients.map { it.uid })
        target.ingredients.forEach { i ->
            db.update("ingredient", ContentValues().apply {
                put("name", i.name)
                put("name_key", IngredientNames.key(i.name))
                put("category_id", i.categoryUid?.let(categoryIds::get))
                put("category_manual", if (i.categoryManual) 1 else 0)
            }, "uid = ?", arrayOf(i.uid))
        }

        val recipeIds = upsertByUid("recipe", target.recipes.map { it.uid })
        target.recipes.forEach { r ->
            db.update("recipe", ContentValues().apply {
                put("name", r.name)
                put("notes", r.notes)
            }, "uid = ?", arrayOf(r.uid))
            r.ingredientUids.mapNotNull(ingredientIds::get).distinct().forEachIndexed { position, ingredientId ->
                db.insertOrThrow("recipe_ingredient", null, ContentValues().apply {
                    put("recipe_id", recipeIds.getValue(r.uid))
                    put("ingredient_id", ingredientId)
                    put("position", position)
                })
            }
        }
        target.aliases.forEach { a -> ingredientIds[a.ingredientUid]?.let { insertAlias(it, a.alias, a.uid) } }
        target.slots.forEach { slot -> recipeIds[slot.recipeUid]?.let { setMeal(slot.weekStart, slot.day, slot.meal, it) } }
        target.extras.forEach { w -> ingredientIds[w.ingredientUid]?.let { db.insertOrThrow("shopping_extra", null, weekItem(w.weekStart, it)) } }
        target.purchases.forEach { w -> ingredientIds[w.ingredientUid]?.let { db.insertOrThrow("shopping_purchased", null, weekItem(w.weekStart, it)) } }
        true
    }

    /**
     * Deletes the rows of [table] whose uid is not in [uids], inserts placeholders for new ones, and gives every
     * row a temporary unique name key so the caller can rename freely (two rows may swap names). Returns
     * uid -> local id.
     */
    private fun upsertByUid(table: String, uids: List<String>): Map<String, Long> {
        val existing = db.rawQuery("SELECT uid, id FROM $table", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        val wanted = uids.toSet()
        existing.keys.filter { it !in wanted }.forEach { db.delete(table, "uid = ?", arrayOf(it)) }
        val hasNameKey = table != "recipe"
        // Real keys are trimmed, so they never start with a space: these temporary ones cannot collide.
        if (hasNameKey) db.execSQL("UPDATE $table SET name_key = ' ' || uid")
        val ids = HashMap<String, Long>()
        uids.forEach { uid ->
            ids[uid] = existing[uid] ?: db.insertOrThrow(table, null, ContentValues().apply {
                put("uid", uid)
                put("name", "")
                if (hasNameKey) put("name_key", " $uid")
                if (table == "shopping_category") put("position", 0)
            })
        }
        return ids
    }

    fun syncValue(key: String): String? =
        db.rawQuery("SELECT value FROM sync_state WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setSyncValue(key: String, value: String?) {
        if (value == null) {
            db.delete("sync_state", "key = ?", arrayOf(key))
        } else {
            db.insertWithOnConflict("sync_state", null, ContentValues().apply {
                put("key", key)
                put("value", value)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Whether there is anything worth backing up before a first sync (more than the seeded categories). */
    fun hasUserData(): Boolean = db.rawQuery(
        "SELECT (SELECT COUNT(*) FROM ingredient) + (SELECT COUNT(*) FROM recipe) + (SELECT COUNT(*) FROM shopping_category WHERE builtin_key IS NULL)", null,
    ).use { it.moveToFirst(); it.getInt(0) > 0 }

    // --- Helpers ---

    private fun findOrCreateIngredient(name: String): Long {
        val key = IngredientNames.key(name)
        return ingredientIdByKey(key) ?: aliasOwnerByKey(key) ?: db.insertOrThrow("ingredient", null, ContentValues().apply {
            put("name", IngredientNames.clean(name))
            put("name_key", key)
            put("category_id", suggestedCategoryId(name)) // automatic, only for unambiguous dictionary matches
            put("uid", Uids.random())
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

    private fun insertAlias(ingredientId: Long, alias: String, uid: String = "") {
        db.insertWithOnConflict("ingredient_alias", null, ContentValues().apply {
            put("ingredient_id", ingredientId)
            put("alias", IngredientNames.clean(alias))
            put("alias_key", IngredientNames.key(alias))
            put("uid", uid.ifEmpty { Uids.random() })
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun ingredientIdByKey(key: String): Long? =
        db.rawQuery("SELECT id FROM ingredient WHERE name_key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun aliasOwnerByKey(key: String): Long? =
        db.rawQuery("SELECT ingredient_id FROM ingredient_alias WHERE alias_key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun ingredientUid(id: Long): String =
        db.rawQuery("SELECT uid FROM ingredient WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }

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
