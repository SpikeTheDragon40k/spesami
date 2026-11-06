package it.spesami.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.spesami.core.DefaultCategories
import it.spesami.core.RecipeColor
import it.spesami.core.Uids
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Opens a database written by app version 1.4.0 (schema version 4) with the current code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTo5Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val week = LocalDate.of(2026, 11, 2)
    private val name = "migration-v4-test.db"

    /** The exact version 4 schema, as created by app 1.4.0. */
    private class V4(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 4) {
        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE shopping_category (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, position INTEGER NOT NULL, builtin_key TEXT UNIQUE, uid TEXT)")
            DefaultCategories.seed().forEach {
                db.execSQL("INSERT INTO shopping_category (id, name, name_key, position, builtin_key, uid) VALUES (?, ?, ?, ?, ?, ?)", arrayOf(it.id, it.name, it.name.lowercase(), it.position, it.builtinKey, Uids.builtinCategory(it.builtinKey!!)))
            }
            db.execSQL("CREATE TABLE ingredient (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, category_id INTEGER REFERENCES shopping_category(id) ON DELETE SET NULL, category_manual INTEGER NOT NULL DEFAULT 0, uid TEXT)")
            db.execSQL("CREATE TABLE ingredient_alias (id INTEGER PRIMARY KEY AUTOINCREMENT, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, alias TEXT NOT NULL, alias_key TEXT NOT NULL UNIQUE, uid TEXT)")
            db.execSQL("CREATE TABLE recipe (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', uid TEXT)")
            db.execSQL("CREATE TABLE recipe_ingredient (recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, position INTEGER NOT NULL, PRIMARY KEY (recipe_id, ingredient_id))")
            db.execSQL("CREATE TABLE meal_slot (week_start TEXT NOT NULL, day INTEGER NOT NULL, meal TEXT NOT NULL, recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, PRIMARY KEY (week_start, day, meal))")
            db.execSQL("CREATE TABLE preference (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            for (table in listOf("shopping_purchased", "shopping_extra")) {
                db.execSQL("CREATE TABLE $table (week_start TEXT NOT NULL, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, PRIMARY KEY (week_start, ingredient_id))")
            }
            for (table in listOf("shopping_category", "ingredient", "ingredient_alias", "recipe")) db.execSQL("CREATE UNIQUE INDEX ${table}_uid ON $table(uid)")
            db.execSQL("CREATE TABLE ingredient_redirect (from_uid TEXT PRIMARY KEY, to_uid TEXT NOT NULL)")
            db.execSQL("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        V4(context, name).writableDatabase.use { db ->
            db.execSQL("INSERT INTO ingredient (id, name, name_key, uid) VALUES (1, 'Uova', 'uova', 'ing-1')")
            db.execSQL("INSERT INTO recipe (id, name, notes, uid) VALUES (3, 'Frittata', 'Padella', 'rec-3')")
            db.execSQL("INSERT INTO recipe_ingredient VALUES (3, 1, 0)")
            db.execSQL("INSERT INTO meal_slot VALUES ('$week', 0, 'LUNCH', 3)")
            db.execSQL("INSERT INTO sync_state VALUES ('device_id', 'dev-1')")
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun existingRecipesHaveNoColourAndNothingElseChanges() {
        val helper = SpesamiDatabase(context, name)
        val repo = Repository(helper)
        val recipe = repo.load().recipesById.getValue(3)
        assertNull(recipe.color)
        assertEquals("Frittata" to "Padella", recipe.name to recipe.notes)
        assertEquals("rec-3", recipe.uid)
        assertEquals(listOf(1L), recipe.ingredientIds)
        assertEquals(1, repo.load().slotsOfWeek(week).size)
        assertEquals("dev-1", repo.syncValue("device_id"))
        // The new column works.
        repo.saveRecipe(3, "Frittata", "Padella", listOf("Uova"), RecipeColor.BLUE)
        assertEquals(RecipeColor.BLUE, repo.load().recipesById.getValue(3).color)
        helper.close()
    }
}
