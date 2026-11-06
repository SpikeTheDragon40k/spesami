package it.spesami.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.spesami.core.DefaultCategories
import it.spesami.core.IngredientDictionary
import it.spesami.core.IngredientNames
import it.spesami.core.Uids

class SpesamiDatabase(context: Context, name: String? = "spesami.db") : SQLiteOpenHelper(context, name, null, VERSION) {
    companion object {
        const val VERSION = 5

        /** Tables whose rows are synced as entities with their own global id (the `uid` column). */
        val UID_TABLES = listOf("shopping_category", "ingredient", "ingredient_alias", "recipe")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCategoryTable(db)
        seedCategories(db)
        db.execSQL(
            """CREATE TABLE ingredient (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                category_id INTEGER REFERENCES shopping_category(id) ON DELETE SET NULL,
                category_manual INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE ingredient_alias (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE,
                alias TEXT NOT NULL,
                alias_key TEXT NOT NULL UNIQUE)"""
        )
        db.execSQL(
            """CREATE TABLE recipe (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE recipe_ingredient (
                recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
                ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                PRIMARY KEY (recipe_id, ingredient_id))"""
        )
        db.execSQL(
            """CREATE TABLE meal_slot (
                week_start TEXT NOT NULL,
                day INTEGER NOT NULL,
                meal TEXT NOT NULL,
                recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
                PRIMARY KEY (week_start, day, meal))"""
        )
        db.execSQL("CREATE TABLE preference (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createChecklistTables(db)
        // The same code paths as an upgrade, so new and migrated databases are identical.
        migrateTo4(db)
        migrateTo5(db)
    }

    /** Runs inside a transaction: if any step fails, the database stays at the old version, untouched. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateTo2(db)
        if (oldVersion < 3) createChecklistTables(db)
        if (oldVersion < 4) migrateTo4(db)
        if (oldVersion < 5) migrateTo5(db)
    }

    /**
     * Version 5: `recipe.color`, the colour picked by hand for a recipe (a [it.spesami.core.RecipeColor] key; NULL
     * = none). Additive only: existing recipes simply have no colour.
     */
    private fun migrateTo5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE recipe ADD COLUMN color TEXT")
    }

    /**
     * Version 4: groundwork for sync between devices. Additive only: no row is changed except to receive its id.
     *
     * - `uid` on every synced entity: a global id, unique across devices, next to the local integer id. The local
     *   ids and every foreign key stay as they are, so no relationship is touched. Existing rows get a random
     *   UUID; the seeded categories get `builtin:<key>`, the same on every phone. A unique index (not a column
     *   constraint, which ALTER TABLE cannot add) keeps them unique.
     * - `ingredient_redirect`: "this ingredient was merged into that one", by uid, so other devices can move their
     *   own references too instead of only seeing a deletion.
     * - `sync_state`: this device's sync data (its id, the version stamps and deletions it knows, status). Never
     *   part of JSON backups: a restored backup must not copy one phone's identity to another.
     */
    private fun migrateTo4(db: SQLiteDatabase) {
        for (table in UID_TABLES) {
            db.execSQL("ALTER TABLE $table ADD COLUMN uid TEXT")
            val builtinKey = if (table == "shopping_category") "builtin_key" else "NULL"
            val rows = db.rawQuery("SELECT id, $builtinKey FROM $table", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
            for ((id, key) in rows) {
                val uid = key?.let(Uids::builtinCategory) ?: Uids.random()
                db.update(table, ContentValues().apply { put("uid", uid) }, "id = ?", arrayOf(id.toString()))
            }
            db.execSQL("CREATE UNIQUE INDEX ${table}_uid ON $table(uid)")
        }
        db.execSQL("CREATE TABLE ingredient_redirect (from_uid TEXT PRIMARY KEY, to_uid TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    /**
     * Version 3: the weekly shopping checklist. The list itself is still computed from the meal plan; these tables
     * only hold what cannot be derived: which products were bought, and which were added by hand, per week and
     * stable ingredient id. Additive only, so existing data is untouched.
     */
    private fun createChecklistTables(db: SQLiteDatabase) {
        for (table in listOf("shopping_purchased", "shopping_extra")) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS $table (
                    week_start TEXT NOT NULL,
                    ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE,
                    PRIMARY KEY (week_start, ingredient_id))"""
            )
        }
    }

    /**
     * Version 2: user-editable shopping categories. Version 1 stored a fixed enum name in ingredient.category;
     * those were user choices, so they become manual categories. Unclassified ingredients get the dictionary's
     * suggestion. The old column is kept (unused) because dropping columns needs a table rebuild, which would
     * cascade-delete recipe links while foreign keys are on.
     */
    private fun migrateTo2(db: SQLiteDatabase) {
        createCategoryTable(db)
        seedCategories(db)
        db.execSQL("ALTER TABLE ingredient ADD COLUMN category_id INTEGER REFERENCES shopping_category(id) ON DELETE SET NULL")
        db.execSQL("ALTER TABLE ingredient ADD COLUMN category_manual INTEGER NOT NULL DEFAULT 0")

        val idByKey = db.rawQuery("SELECT builtin_key, id FROM shopping_category WHERE builtin_key IS NOT NULL", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }
        val rows = db.rawQuery("SELECT id, name, category FROM ingredient", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
        }
        for ((id, name, legacy) in rows) {
            val key = if (legacy != null) DefaultCategories.fromLegacy(legacy, name) else IngredientDictionary.suggest(name)
            db.update("ingredient", ContentValues().apply {
                put("category_id", key?.let { idByKey[it] })
                put("category_manual", if (legacy != null && key != null) 1 else 0)
                putNull("category")
            }, "id = ?", arrayOf(id.toString()))
        }
    }

    private fun createCategoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shopping_category (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                name_key TEXT NOT NULL UNIQUE,
                position INTEGER NOT NULL,
                builtin_key TEXT UNIQUE)"""
        )
    }

    /** Inserts the default categories only into an empty table, so nothing is ever duplicated or resurrected. */
    private fun seedCategories(db: SQLiteDatabase) {
        val empty = db.rawQuery("SELECT COUNT(*) FROM shopping_category", null).use { it.moveToFirst(); it.getInt(0) == 0 }
        if (!empty) return
        DefaultCategories.seed().forEach { c ->
            db.insertOrThrow("shopping_category", null, ContentValues().apply {
                put("id", c.id)
                put("name", c.name)
                put("name_key", IngredientNames.key(c.name))
                put("position", c.position)
                put("builtin_key", c.builtinKey)
            })
        }
    }
}
