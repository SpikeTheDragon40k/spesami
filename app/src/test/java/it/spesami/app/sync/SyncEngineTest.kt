package it.spesami.app.sync

import android.content.Context
import it.spesami.app.data.Repository
import it.spesami.app.data.SpesamiDatabase
import it.spesami.core.Backup
import it.spesami.core.BackupReadResult
import it.spesami.core.DefaultCategories
import it.spesami.core.MealType
import it.spesami.core.ShoppingList
import it.spesami.core.sync.LocalSync
import it.spesami.core.sync.Snapshot
import it.spesami.core.sync.Sync
import it.spesami.core.sync.SyncExchange
import it.spesami.core.sync.SyncFile
import it.spesami.core.sync.SyncResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

private fun Repository.ingredientId(name: String) = load().ingredients.single { it.name == name }.id

/** Device files kept in a map instead of Drive. */
private class MapExchange(val files: MutableMap<String, String> = LinkedHashMap()) : SyncExchange {
    override fun sync(local: LocalSync, current: Snapshot, deviceName: String, appVersion: String, now: Long): SyncResult {
        val own = SyncFile.fileName(local.deviceId)
        return Sync.run(local, current, files.filterKeys { it != own }.values.toList(), deviceName, appVersion, now, null)
            .also { files[own] = it.ownFile }
    }
}

/** The app side of the sync with real databases: record, prepare, exchange, apply. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val week = LocalDate.of(2026, 11, 2)
    private val drive = MapExchange()
    private var clock = 1_793_700_000_000L

    private inner class Device(val name: String) {
        val helper = SpesamiDatabase(context, "sync-$name.db")
        val repo = Repository(helper)
        val engine = SyncEngine(repo, File(context.cacheDir, "backups-$name")) { clock }

        /** A user edit, followed by what the app does after every write. */
        fun edit(block: Repository.() -> Unit) {
            repo.block()
            engine.recordLocalChange()
            clock += 1_000
        }

        fun sync(): Boolean {
            val prepared = engine.prepare()
            val result = drive.sync(prepared.local, prepared.current, name, "1.4.0", clock)
            clock += 1_000
            return engine.finish(prepared, result)
        }

        fun snapshot() = repo.syncSnapshot()

        fun ingredientId(name: String) = repo.load().ingredients.single { it.name == name }.id

        fun count(table: String) = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private lateinit var a: Device
    private lateinit var b: Device

    @Before
    fun setUp() {
        listOf("a", "b").forEach {
            context.deleteDatabase("sync-$it.db")
            File(context.cacheDir, "backups-$it").deleteRecursively()
        }
        a = Device("a")
        b = Device("b")
    }

    @After
    fun tearDown() {
        a.helper.close()
        b.helper.close()
    }

    private fun syncBoth() {
        repeat(2) {
            a.sync()
            b.sync()
        }
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun twoPhonesWithDataMergeAndKeepEverything() {
        a.edit { saveRecipe(null, "Frittata", "Padella", listOf("Uova", "Patate")) }
        a.edit { setMeal(week, 0, MealType.LUNCH, load().recipes.single().id) }
        a.edit { setPurchased(week, load().ingredients.single { it.name == "Uova" }.id, true) }
        b.edit { saveRecipe(null, "Purè", "", listOf("Patate", "Latte")) }
        b.edit { addProduct(week, "Detersivo") }
        b.edit { createCategory("Animali") }
        syncBoth()
        val data = b.repo.load()
        assertEquals(setOf("Frittata", "Purè"), data.recipes.map { it.name }.toSet())
        assertEquals(setOf("Uova", "Patate", "Latte", "Detersivo"), data.ingredients.map { it.name }.toSet())
        assertEquals(listOf("Detersivo", "Patate", "Uova"), ShoppingList.build(data, week).map { it.name }.sorted())
        assertTrue(data.isPurchased(week, b.ingredientId("Uova")))
        assertTrue(data.categories.any { it.name == "Animali" })
        assertEquals("Padella", data.recipes.single { it.name == "Frittata" }.notes)
        assertEquals(1, data.slots.size)
    }

    @Test
    fun syncingAgainChangesNothing() {
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova", "Patate")) }
        syncBoth()
        val before = a.repo.load()
        a.sync()
        a.sync()
        assertEquals(before, a.repo.load())
    }

    @Test
    fun localIdsAndPreferencesStayOnEachPhone() {
        b.edit { createCategory("Animali") }
        val animali = b.repo.load().categories.single { it.name == "Animali" }.id
        b.edit { setPreference("export_excluded_categories", animali.toString()) }
        b.edit { setPreference("theme", "LIGHT") }
        a.edit { setPreference("theme", "DRACULA") }
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova")) }
        b.edit { saveRecipe(null, "Purè", "", listOf("Patate")) }
        val patate = b.ingredientId("Patate")
        syncBoth()
        assertEquals(animali, b.repo.load().categories.single { it.name == "Animali" }.id)
        assertEquals(patate, b.ingredientId("Patate"))
        assertEquals("LIGHT", b.repo.load().preferences["theme"])
        assertEquals("DRACULA", a.repo.load().preferences["theme"])
        assertEquals(animali.toString(), b.repo.load().preferences["export_excluded_categories"])
    }

    @Test
    fun namesCanBeSwapped() {
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova", "Patate")) }
        syncBoth()
        a.edit { renameIngredient(ingredientId("Uova"), "Tmp") }
        a.edit { renameIngredient(ingredientId("Patate"), "Uova") }
        a.edit { renameIngredient(ingredientId("Tmp"), "Patate") }
        val uidOfNewUova = a.repo.load().ingredients.single { it.name == "Uova" }.uid
        syncBoth()
        assertEquals(uidOfNewUova, b.repo.load().ingredients.single { it.name == "Uova" }.uid)
    }

    @Test
    fun anEditDuringTheNetworkPartIsKeptAndSyncedNextTime() {
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova")) }
        a.sync()
        val prepared = b.engine.prepare()
        val result = drive.sync(prepared.local, prepared.current, "b", "1.4.0", clock)
        b.edit { saveRecipe(null, "Purè", "", listOf("Patate")) } // typed while the sync was talking to Drive
        assertFalse(b.engine.finish(prepared, result))
        assertEquals(listOf("Purè"), b.repo.load().recipes.map { it.name })
        syncBoth()
        assertEquals(setOf("Frittata", "Purè"), a.repo.load().recipes.map { it.name }.toSet())
    }

    @Test
    fun theFirstSyncOfAPhoneWithDataSavesABackupFirst() {
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova")) }
        a.edit { createCategory("Animali") }
        val before = a.repo.load()
        a.sync()
        val path = a.engine.info().firstBackup
        assertNotNull(path)
        val restored = Backup.decode(File(path!!).readText()) as BackupReadResult.Valid
        assertEquals(before.recipes.map { it.name }, restored.data.recipes.map { it.name })
        assertEquals(before.categories.map { it.uid }, restored.data.categories.map { it.uid })
    }

    @Test
    fun anEmptyPhoneNeedsNoBackup() {
        b.sync()
        assertNull(b.engine.info().firstBackup)
    }

    @Test
    fun anAliasMergeReachesTheOtherPhone() {
        a.edit { saveRecipe(null, "Purè", "", listOf("Patate", "Patata")) }
        syncBoth()
        a.edit { addAlias(ingredientId("Patate"), "Patata") } // merges "Patata" into "Patate"
        b.edit { saveRecipe(null, "Gnocchi", "", listOf("Patata", "Farina")) }
        b.edit { setPurchased(week, ingredientId("Patata"), true) }
        syncBoth()
        val data = b.repo.load()
        assertEquals(setOf("Patate", "Farina"), data.ingredients.map { it.name }.toSet())
        assertEquals(listOf("Patata"), data.aliases.map { it.alias })
        assertTrue(data.isPurchased(week, b.ingredientId("Patate")))
        assertEquals(0, a.count("ingredient_redirect")) // recorded, then forgotten locally
    }

    @Test
    fun deletingACategoryMovesItsIngredientsToAltroOnTheOtherPhone() {
        a.edit { createCategory("Animali") }
        a.edit { addProduct(week, "Croccantini", load().categories.single { it.name == "Animali" }.id, setCategory = true) }
        syncBoth()
        b.edit { deleteCategory(load().categories.single { it.name == "Animali" }.id) }
        syncBoth()
        val data = a.repo.load()
        assertEquals(DefaultCategories.OTHER, data.categoryOf(data.ingredients.single())?.builtinKey)
        assertFalse(data.categories.any { it.name == "Animali" })
    }

    @Test
    fun restoringABackupWhileConnectedReachesTheOtherPhone() {
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova")) }
        val backup = a.repo.load()
        a.edit { saveRecipe(null, "Purè", "", listOf("Patate")) }
        syncBoth()
        a.edit { replaceAll(backup) }
        syncBoth()
        assertEquals(listOf("Frittata"), b.repo.load().recipes.map { it.name })
    }

    @Test
    fun beforeTheFirstSyncNothingIsRecorded() {
        a.edit { saveRecipe(null, "Purè", "", listOf("Patate", "Patata")) }
        a.edit { addAlias(ingredientId("Patate"), "Patata") }
        assertEquals(0, a.count("ingredient_redirect"))
        assertNull(a.repo.syncValue("local"))
    }

    @Test
    fun disconnectingKeepsAllData() {
        a.engine.setEnabled(true)
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova")) }
        a.sync()
        val before = a.repo.load()
        a.engine.setEnabled(false)
        assertFalse(a.engine.enabled)
        assertEquals(before, a.repo.load())
    }

    @Test
    fun syncDataIsNeverInABackup() {
        a.edit { saveRecipe(null, "Frittata", "", listOf("Uova")) }
        a.sync()
        val text = Backup.encode(a.repo.load(), "2026-11-03")
        assertFalse(text.contains(a.engine.local().deviceId))
        assertFalse(text.contains("spesami-sync"))
    }

    @Test
    fun describesTimesInItalian() {
        val today = LocalDate.of(2026, 11, 3)
        val zone = java.time.ZoneId.systemDefault()
        fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        assertEquals("oggi alle 14:32", SyncEngine.describeTime(at(today, 14, 32), today))
        assertEquals("ieri alle 9:05", SyncEngine.describeTime(at(today.minusDays(1), 9, 5), today))
        assertEquals("1 novembre alle 18:00", SyncEngine.describeTime(at(today.minusDays(2), 18, 0), today))
    }
}
