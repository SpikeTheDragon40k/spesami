package it.spesami.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.spesami.core.DefaultCategories
import it.spesami.core.MealType
import it.spesami.core.ShoppingList
import it.spesami.core.Uids
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Opens a database written by app version 1.3.0 (schema version 3) with the current code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTo4Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val week = LocalDate.of(2026, 11, 2)
    private val name = "migration-v3-test.db"

    /** The exact version 3 schema, as created by app 1.3.0. */
    private class V3(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 3) {
        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE shopping_category (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, position INTEGER NOT NULL, builtin_key TEXT UNIQUE)")
            DefaultCategories.seed().forEach {
                db.execSQL("INSERT INTO shopping_category (id, name, name_key, position, builtin_key) VALUES (?, ?, ?, ?, ?)", arrayOf(it.id, it.name, it.name.lowercase(), it.position, it.builtinKey))
            }
            db.execSQL("CREATE TABLE ingredient (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, category_id INTEGER REFERENCES shopping_category(id) ON DELETE SET NULL, category_manual INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE ingredient_alias (id INTEGER PRIMARY KEY AUTOINCREMENT, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, alias TEXT NOT NULL, alias_key TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE recipe (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE recipe_ingredient (recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, position INTEGER NOT NULL, PRIMARY KEY (recipe_id, ingredient_id))")
            db.execSQL("CREATE TABLE meal_slot (week_start TEXT NOT NULL, day INTEGER NOT NULL, meal TEXT NOT NULL, recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, PRIMARY KEY (week_start, day, meal))")
            db.execSQL("CREATE TABLE preference (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            for (table in listOf("shopping_purchased", "shopping_extra")) {
                db.execSQL("CREATE TABLE $table (week_start TEXT NOT NULL, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, PRIMARY KEY (week_start, ingredient_id))")
            }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        V3(context, name).writableDatabase.use { db ->
            // A user category between the defaults, a renamed default, and a deleted default (Bevande).
            db.execSQL("UPDATE shopping_category SET position = position + 1 WHERE position >= 3")
            db.execSQL("INSERT INTO shopping_category (id, name, name_key, position, builtin_key) VALUES (13, 'Animali', 'animali', 3, NULL)")
            db.execSQL("UPDATE shopping_category SET name = 'Verdure', name_key = 'verdure' WHERE builtin_key = 'frutta_verdura'")
            db.execSQL("DELETE FROM shopping_category WHERE builtin_key = 'bevande'")
            db.execSQL("INSERT INTO ingredient (id, name, name_key, category_id, category_manual) VALUES (1, 'Patate', 'patate', 1, 0), (2, 'Uova', 'uova', NULL, 1), (5, 'Croccantini', 'croccantini', 13, 1), (7, 'Detersivo', 'detersivo', 12, 1)")
            db.execSQL("INSERT INTO ingredient_alias (ingredient_id, alias, alias_key) VALUES (1, 'Patata', 'patata'), (1, 'Patatine', 'patatine')")
            db.execSQL("INSERT INTO recipe (id, name, notes) VALUES (3, 'Frittata', 'Padella'), (4, 'Purè', '')")
            db.execSQL("INSERT INTO recipe_ingredient VALUES (3, 2, 0), (3, 1, 1), (4, 1, 0)")
            db.execSQL("INSERT INTO meal_slot VALUES ('$week', 0, 'LUNCH', 3), ('$week', 4, 'DINNER', 4)")
            db.execSQL("INSERT INTO shopping_purchased VALUES ('$week', 1), ('${week.minusWeeks(1)}', 2)")
            db.execSQL("INSERT INTO shopping_extra VALUES ('$week', 7), ('$week', 5)")
            db.execSQL("INSERT INTO preference VALUES ('theme', 'DRACULA'), ('shopping_keep_screen_on', 'false')")
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun everyRowAndRelationshipSurvives() {
        val helper = SpesamiDatabase(context, name)
        val data = Repository(helper).load()
        helper.close()

        assertEquals(12, data.categories.size)
        assertEquals("Verdure", data.categories.single { it.builtinKey == "frutta_verdura" }.name)
        assertEquals(listOf(1L, 2L, 3L, 13L), data.sortedCategories.take(4).map { it.id })
        assertEquals(setOf(1L, 2L, 5L, 7L), data.ingredients.map { it.id }.toSet())
        assertEquals(1L, data.ingredientsById.getValue(1).categoryId)
        assertFalse(data.ingredientsById.getValue(1).categoryManual)
        assertEquals(13L, data.ingredientsById.getValue(5).categoryId)
        assertTrue(data.ingredientsById.getValue(2).categoryManual)
        assertEquals(listOf("Patata", "Patatine"), data.aliases.map { it.alias })
        assertTrue(data.aliases.all { it.ingredientId == 1L })
        assertEquals(listOf(2L, 1L), data.recipesById.getValue(3).ingredientIds)
        assertEquals("Padella", data.recipesById.getValue(3).notes)
        assertEquals(listOf(1L), data.recipesById.getValue(4).ingredientIds)
        assertEquals(setOf(3L, 4L), data.slots.map { it.recipeId }.toSet())
        assertTrue(data.isPurchased(week, 1))
        assertTrue(data.isPurchased(week.minusWeeks(1), 2))
        assertEquals(setOf(5L, 7L), data.manualOfWeek(week).toSet())
        assertEquals(mapOf("theme" to "DRACULA", "shopping_keep_screen_on" to "false"), data.preferences)
        // The week's list is unchanged: Patate (2 meals), Uova, and the two hand-added products.
        assertEquals(listOf("Croccantini", "Detersivo", "Patate", "Uova"), ShoppingList.build(data, week).map { it.name }.sorted())
    }

    @Test
    fun everyEntityGetsAUniqueGlobalId() {
        val helper = SpesamiDatabase(context, name)
        val data = Repository(helper).load()
        helper.close()

        val uids = data.categories.map { it.uid } + data.ingredients.map { it.uid } + data.aliases.map { it.uid } + data.recipes.map { it.uid }
        assertTrue(uids.all { it.isNotBlank() })
        assertEquals(uids.size, uids.toSet().size)
        // Seeded categories: the same uid on every phone, even when renamed.
        data.categories.filter { it.builtinKey != null }.forEach { assertEquals(Uids.builtinCategory(it.builtinKey!!), it.uid) }
        assertEquals("builtin:frutta_verdura", data.categories.single { it.name == "Verdure" }.uid)
        // A user category gets a random one.
        assertTrue(Regex("[0-9a-f-]{36}").matches(data.categories.single { it.id == 13L }.uid))
    }

    @Test
    fun idsAreAssignedOnceAndKeptOnReopen() {
        val first = SpesamiDatabase(context, name).let { h -> Repository(h).load().also { h.close() } }
        val second = SpesamiDatabase(context, name).let { h -> Repository(h).load().also { h.close() } }
        assertEquals(first.ingredients.map { it.id to it.uid }.toSet(), second.ingredients.map { it.id to it.uid }.toSet())
        assertEquals(first.recipes.map { it.uid }.toSet(), second.recipes.map { it.uid }.toSet())
    }

    @Test
    fun syncTablesStartEmptyAndUidsStayUnique() {
        val helper = SpesamiDatabase(context, name)
        val db = helper.writableDatabase
        for (table in listOf("ingredient_redirect", "sync_state")) {
            assertEquals(table, 0, db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) })
        }
        val existing = db.rawQuery("SELECT uid FROM recipe WHERE id = 3", null).use { it.moveToFirst(); it.getString(0) }
        val duplicate = runCatching { db.execSQL("UPDATE recipe SET uid = ? WHERE id = 4", arrayOf(existing)) }
        assertTrue("a duplicate uid must be rejected", duplicate.isFailure)
        helper.close()
    }

    @Test
    fun migratedAndNewDatabasesHaveTheSameSchema() {
        val fresh = "fresh-v4-test.db"
        context.deleteDatabase(fresh)
        fun schema(file: String): Map<String, List<String>> {
            val helper = SpesamiDatabase(context, file)
            val db = helper.writableDatabase
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            // Version 1 databases keep an unused legacy column (see migrateTo2), version 3 ones never had it.
            val columns = tables.associateWith { t ->
                db.rawQuery("PRAGMA table_info($t)", null).use { c -> buildList { while (c.moveToNext()) add("${c.getString(1)} ${c.getString(2)} notnull=${c.getInt(3)} pk=${c.getInt(5)}") } }
            }
            val indexes = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE '%_uid' ORDER BY name", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            helper.close()
            return columns + ("uid indexes" to indexes)
        }
        assertEquals(schema(fresh), schema(name))
        context.deleteDatabase(fresh)
    }
}
