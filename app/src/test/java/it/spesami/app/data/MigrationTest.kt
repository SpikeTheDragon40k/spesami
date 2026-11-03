package it.spesami.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.spesami.core.DefaultCategories
import it.spesami.core.MealType
import it.spesami.core.ShoppingList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Opens a database written by app version 1.0.0 (schema version 1) with the current code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val week = LocalDate.of(2026, 11, 2)

    /** The exact version 1 schema. */
    private class V1(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE ingredient (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, category TEXT)")
            db.execSQL("CREATE TABLE ingredient_alias (id INTEGER PRIMARY KEY AUTOINCREMENT, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, alias TEXT NOT NULL, alias_key TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE recipe (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE recipe_ingredient (recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, ingredient_id INTEGER NOT NULL REFERENCES ingredient(id) ON DELETE CASCADE, position INTEGER NOT NULL, PRIMARY KEY (recipe_id, ingredient_id))")
            db.execSQL("CREATE TABLE meal_slot (week_start TEXT NOT NULL, day INTEGER NOT NULL, meal TEXT NOT NULL, recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, PRIMARY KEY (week_start, day, meal))")
            db.execSQL("CREATE TABLE preference (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun version1DataSurvivesAndGetsCategories() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        V1(context, name).writableDatabase.use { db ->
            db.execSQL("INSERT INTO ingredient (id, name, name_key, category) VALUES (1, 'Salmone', 'salmone', 'MEAT_FISH'), (2, 'Patate', 'patate', NULL), (3, 'Boh', 'boh', NULL), (4, 'Parmigiano', 'parmigiano', 'PRODUCE')")
            db.execSQL("INSERT INTO ingredient_alias (ingredient_id, alias, alias_key) VALUES (2, 'Patata', 'patata')")
            db.execSQL("INSERT INTO recipe (id, name, notes) VALUES (1, 'Salmone e patate', 'Forno 200°')")
            db.execSQL("INSERT INTO recipe_ingredient VALUES (1, 1, 0), (1, 2, 1), (1, 3, 2), (1, 4, 3)")
            db.execSQL("INSERT INTO meal_slot VALUES ('$week', 0, 'LUNCH', 1), ('$week', 2, 'DINNER', 1)")
            db.execSQL("INSERT INTO preference VALUES ('theme', 'DRACULA')")
        }

        val helper = SpesamiDatabase(context, name)
        val data = Repository(helper).load()
        helper.close()

        assertEquals(DefaultCategories.seed(), data.categories)
        assertEquals(listOf(1L, 2L, 3L, 4L), data.recipes.single().ingredientIds)
        assertEquals(2, data.slots.size)
        assertEquals("Patata", data.aliases.single().alias)
        assertEquals("DRACULA", data.preferences["theme"])
        assertEquals(listOf(2, 2, 2, 2), ShoppingList.build(data, week).map { it.count })
        assertEquals(MealType.DINNER, data.slots.single { it.day == 2 }.meal)

        val byName = data.ingredients.associateBy { it.name }
        // Old user choice, split by the dictionary: "Carne e pesce" + Salmone -> pesce.
        assertEquals("pesce_surgelati", data.categoryOf(byName.getValue("Salmone"))?.builtinKey)
        assertTrue(byName.getValue("Salmone").categoryManual)
        // Old user choice that disagrees with the dictionary is kept.
        assertEquals("frutta_verdura", data.categoryOf(byName.getValue("Parmigiano"))?.builtinKey)
        // Unclassified: automatic suggestion, or "Da classificare".
        assertEquals("frutta_verdura", data.categoryOf(byName.getValue("Patate"))?.builtinKey)
        assertFalse(byName.getValue("Patate").categoryManual)
        assertNull(byName.getValue("Boh").categoryId)

        // Version 1 -> 4: every entity got a global id, the seeded categories their fixed one.
        assertTrue(data.ingredients.all { it.uid.isNotBlank() } && data.recipes.all { it.uid.isNotBlank() } && data.aliases.all { it.uid.isNotBlank() })
        assertEquals("builtin:altro", data.categories.single { it.builtinKey == DefaultCategories.OTHER }.uid)

        // Re-opening does not seed again.
        val again = SpesamiDatabase(context, name)
        assertEquals(12, Repository(again).load().categories.size)
        again.close()
    }

    /** The exact version 2 schema, as created by a fresh install of app 1.1.0. */
    private class V2(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
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
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun version2DatabaseGetsTheChecklist() {
        val name = "migration-v2-test.db"
        context.deleteDatabase(name)
        V2(context, name).writableDatabase.use { db ->
            db.execSQL("INSERT INTO ingredient (id, name, name_key, category_id) VALUES (1, 'Patate', 'patate', 1), (2, 'Uova', 'uova', NULL)")
            db.execSQL("INSERT INTO recipe (id, name) VALUES (1, 'Frittata')")
            db.execSQL("INSERT INTO recipe_ingredient VALUES (1, 1, 0), (1, 2, 1)")
            db.execSQL("INSERT INTO meal_slot VALUES ('$week', 0, 'LUNCH', 1)")
        }

        val helper = SpesamiDatabase(context, name)
        val repo = Repository(helper)
        val before = repo.load()
        assertEquals(listOf("Patate", "Uova"), ShoppingList.build(before, week).map { it.name }.sorted())
        assertTrue(before.purchases.isEmpty() && before.manualItems.isEmpty())

        val patate = before.ingredients.single { it.name == "Patate" }.id
        repo.setPurchased(week, patate, true)
        repo.addProduct(week, "Detersivo")
        val after = repo.load()
        assertTrue(after.isPurchased(week, patate))
        assertEquals(3, ShoppingList.build(after, week).size)
        // Upgraded all the way: every entity has its global id.
        assertTrue(after.ingredients.all { it.uid.isNotBlank() } && after.recipes.all { it.uid.isNotBlank() })
        helper.close()
        context.deleteDatabase(name)
    }
}
