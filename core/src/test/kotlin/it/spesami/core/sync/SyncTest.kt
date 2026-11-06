package it.spesami.core.sync

import it.spesami.core.MealType
import it.spesami.core.sync.Phone.Companion.OTHER
import it.spesami.core.sync.Phone.Companion.PRODUCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SyncTest {
    private val week = LocalDate.of(2026, 11, 2)
    private val world = World()
    private val a = world.phone("a")
    private val b = world.phone("b")
    private val c = world.phone("c")

    /** Syncs every phone twice, so all have seen every file; then all must hold the same data. */
    private fun syncAll(vararg phones: Phone = arrayOf(a, b, c)) {
        repeat(2) { phones.forEach { it.sync() } }
        phones.drop(1).forEach { assertEquals("${it.name} differs from ${phones[0].name}", phones[0].db, it.db) }
    }

    // --- Basics

    @Test
    fun recordingThenReadingBackGivesTheSameData() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate", "Cipolla"), "Padella") }
        a.edit { setMeal(week, 0, MealType.LUNCH, recipe("Frittata")) }
        a.edit { addAlias(ingredient("Patate"), "Patata") }
        a.edit { setCategory(ingredient("Uova"), OTHER) }
        a.edit { tick(week, ingredient("Uova")) }
        a.edit { addProduct(week, "Detersivo") }
        a.edit { createCategory("Animali") }
        assertEquals(a.db, SyncMaterialize.snapshot(a.local.state))
    }

    @Test
    fun aSecondPhoneReceivesEverything() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        a.edit { setMeal(week, 2, MealType.DINNER, recipe("Frittata")) }
        a.edit { tick(week, ingredient("Uova")) }
        a.sync()
        b.sync()
        assertEquals(a.db, b.db)
        assertEquals(listOf("Uova", "Patate"), b.recipeIngredients("Frittata"))
        assertTrue(SnapWeekItem(week, b.ingredient("Uova")) in b.db.purchases)
    }

    @Test
    fun firstSyncOfTwoPhonesWithDataMergesNeverReplaces() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        a.edit { tick(week, ingredient("Patate")) }
        b.edit { saveRecipe("Pasta al pomodoro", listOf("Pasta", "Pomodoro")) }
        b.edit { saveRecipe("Frittata", listOf("Uova", "Zucchine")) } // the same recipe, entered separately
        b.edit { addProduct(week, "Detersivo") }
        syncAll(a, b)
        assertEquals(listOf("Frittata", "Pasta al pomodoro"), a.db.recipes.map { it.name }.sorted())
        // The two "Frittata" became one with the ingredients of both, and "Uova" is one ingredient.
        assertEquals(setOf("Uova", "Patate", "Zucchine"), a.recipeIngredients("Frittata").toSet())
        assertEquals(listOf("Detersivo", "Pasta", "Patate", "Pomodoro", "Uova", "Zucchine"), a.names())
        assertTrue(SnapWeekItem(week, a.ingredient("Patate")) in a.db.purchases)
        assertTrue(SnapWeekItem(week, a.ingredient("Detersivo")) in a.db.extras)
    }

    @Test
    fun threePhonesEditOfflineThenConverge() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll()
        a.edit { saveRecipe("Frittata", listOf("Uova", "Cipolla"), uid = recipe("Frittata")) }
        b.edit { setMeal(week, 0, MealType.LUNCH, recipe("Frittata")) }
        c.edit { addProduct(week, "Sapone") }
        c.edit { saveRecipe("Insalata", listOf("Lattuga")) }
        syncAll()
        assertEquals(listOf("Uova", "Cipolla"), c.recipeIngredients("Frittata"))
        assertEquals(1, c.db.slots.size)
        assertEquals(listOf("Frittata", "Insalata"), a.db.recipes.map { it.name }.sorted())
        assertTrue(SnapWeekItem(week, b.ingredient("Sapone")) in b.db.extras)
    }

    @Test
    fun syncingTwiceChangesNothing() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        b.edit { saveRecipe("Purè", listOf("Patate", "Latte")) }
        syncAll(a, b)
        val data = a.db
        val records = a.local.state.records
        val file = world.drive.getValue(SyncFile.fileName("a"))
        a.sync()
        a.sync()
        assertEquals(data, a.db)
        assertEquals(records, a.local.state.records)
        assertEquals(
            (SyncFiles.decode(file) as SyncFileRead.Valid).file.records,
            (SyncFiles.decode(world.drive.getValue(SyncFile.fileName("a"))) as SyncFileRead.Valid).file.records,
        )
    }

    // --- Merge properties

    private fun threeOfflineStates(): List<SyncState> {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        syncAll()
        a.edit { renameIngredient(ingredient("Patate"), "Patate novelle") }
        a.edit { tick(week, ingredient("Uova")) }
        b.edit { setCategory(ingredient("Patate"), OTHER) }
        b.edit { deleteRecipe(recipe("Frittata")) }
        b.edit { saveRecipe("Purè", listOf("Patate", "Latte")) }
        c.edit { merge(ingredient("Uova"), ingredient("Patate")) }
        c.edit { createCategory("Animali") }
        c.edit { tick(week, ingredient("Patate"), on = false) }
        return listOf(a, b, c).map { it.local.state }
    }

    @Test
    fun filesAppliedInAnyOrderGiveTheSameData() {
        val states = threeOfflineStates()
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        val results = orders.map { order -> order.map(states::get).reduce(SyncMerge::merge) }
        results.forEach { assertEquals(results[0], it) }
        val data = results.map(SyncMaterialize::snapshot)
        data.forEach { assertEquals(data[0], it) }
    }

    @Test
    fun mergeIsCommutativeAssociativeAndIdempotent() {
        val (x, y, z) = threeOfflineStates()
        assertEquals(SyncMerge.merge(x, y), SyncMerge.merge(y, x))
        assertEquals(SyncMerge.merge(SyncMerge.merge(x, y), z), SyncMerge.merge(x, SyncMerge.merge(y, z)))
        val xy = SyncMerge.merge(x, y)
        assertEquals(xy, SyncMerge.merge(xy, xy))
        assertEquals(xy, SyncMerge.merge(xy, x))
    }

    @Test
    fun phonesConvergeWhateverOrderTheySyncIn() {
        threeOfflineStates()
        // The same offline edits, synced c, b, a instead of a, b, c, end in the same data as merging the states.
        val expected = SyncMaterialize.snapshot(listOf(a, b, c).map { it.local.state }.reduce(SyncMerge::merge))
        repeat(2) { listOf(c, b, a).forEach { it.sync() } }
        assertEquals(expected, a.db)
        assertEquals(a.db, b.db)
        assertEquals(b.db, c.db)
    }

    // --- Edit vs edit

    @Test
    fun renameAndRecategorizeOfTheSameIngredientBothSurvive() {
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, b)
        a.edit { renameIngredient(ingredient("Patate"), "Patate novelle") }
        b.edit { setCategory(ingredient("Patate"), OTHER) }
        syncAll(a, b)
        assertEquals(listOf("Patate novelle"), a.names())
        assertEquals("Altro", a.categoryOf("Patate novelle"))
        assertTrue(a.db.ingredients.single().categoryManual)
    }

    @Test
    fun aCategoryPickedByHandBeatsAnAutomaticReclassification() {
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, b)
        b.edit { setCategory(ingredient("Patate"), OTHER) }
        // Later, a renames it; the app re-classifies automatically because a has not seen b's choice yet.
        a.edit { renameAutomatic(ingredient("Patate"), "Patate dolci", PRODUCE) }
        syncAll(a, b)
        assertEquals("Altro", a.categoryOf("Patate dolci"))
    }

    @Test
    fun sameFieldEditedTwiceTheLaterWins() {
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, b)
        a.edit { renameIngredient(ingredient("Patate"), "Patate gialle") }
        b.edit { renameIngredient(ingredient("Patate"), "Patate rosse") }
        syncAll(a, b)
        assertEquals(listOf("Patate rosse"), a.names())
    }

    @Test
    fun concurrentMealPlanEditsTheLaterWins() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, b)
        a.edit { setMeal(week, 0, MealType.LUNCH, recipe("Frittata")) }
        b.edit { setMeal(week, 0, MealType.LUNCH, recipe("Purè")) }
        b.edit { setMeal(week, 1, MealType.DINNER, recipe("Frittata")) }
        syncAll(a, b)
        assertEquals(listOf(a.recipe("Purè"), a.recipe("Frittata")), a.db.slots.map { it.recipeUid })
    }

    // --- Edit vs delete, delete vs delete

    @Test
    fun aDeletionBeatsAConcurrentEditInBothOrders() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, b)
        // Edit before the deletion, and edit after it: the recipe stays deleted either way.
        a.edit { saveRecipe("Frittata alta", listOf("Uova", "Latte"), uid = recipe("Frittata")) }
        b.edit { deleteRecipe(recipe("Frittata")) }
        b.edit { deleteRecipe(recipe("Purè")) }
        a.edit { saveRecipe("Purè di patate", listOf("Patate"), uid = recipe("Purè")) }
        syncAll(a, b)
        assertTrue(a.db.recipes.isEmpty())
    }

    @Test
    fun deletingTheSameThingTwiceIsOneDeletion() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll(a, b)
        a.edit { deleteRecipe(recipe("Frittata")) }
        b.edit { deleteRecipe(recipe("Frittata")) }
        syncAll(a, b)
        assertTrue(a.db.recipes.isEmpty())
        assertEquals(1, a.local.state.records.values.count { it.k == Kinds.RECIPE && it.d != null })
    }

    @Test
    fun usesOfADeletedIngredientDisappearEverywhere() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        syncAll(a, b)
        a.edit { deleteIngredient(ingredient("Patate")) }
        b.edit { saveRecipe("Purè", listOf("Patate", "Latte")) } // b still uses it
        b.edit { tick(week, ingredient("Patate")) }
        syncAll(a, b)
        assertEquals(listOf("Latte", "Uova"), a.names())
        assertEquals(listOf("Latte"), a.recipeIngredients("Purè"))
        assertTrue(a.db.purchases.isEmpty())
    }

    @Test
    fun aDeletedRecipeLeavesTheMealPlan() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll(a, b)
        a.edit { deleteRecipe(recipe("Frittata")) }
        b.edit { setMeal(week, 3, MealType.LUNCH, recipe("Frittata")) }
        syncAll(a, b)
        assertTrue(a.db.slots.isEmpty())
    }

    // --- Categories

    @Test
    fun ingredientsOfADeletedCategoryMoveToAltroOnEveryPhone() {
        a.edit { createCategory("Animali") }
        a.edit { saveRecipe("Pappa", listOf("Croccantini")) }
        a.edit { setCategory(ingredient("Croccantini"), category("Animali")) }
        syncAll()
        a.edit { deleteCategory(category("Animali")) }
        // Meanwhile b puts another ingredient in it.
        b.edit { addProduct(week, "Lettiera") }
        b.edit { setCategory(ingredient("Lettiera"), category("Animali")) }
        syncAll()
        assertFalse(c.db.categories.any { it.name == "Animali" })
        assertEquals("Altro", c.categoryOf("Croccantini"))
        assertEquals("Altro", c.categoryOf("Lettiera"))
    }

    @Test
    fun altroCannotDisappear() {
        val state = a.local.state.copy(records = mapOf(OTHER to SyncRecord(Kinds.CATEGORY, d = Hlc.encode(1, 0, "x"))))
        assertTrue(SyncMaterialize.snapshot(state).categories.any { it.uid == OTHER })
    }

    @Test
    fun aJoiningPhoneDoesNotUndoRenamesOfTheDefaultCategories() {
        a.edit { renameCategory(PRODUCE, "Verdure") }
        a.edit { moveCategory(OTHER, -11) }
        a.sync()
        b.sync() // b never touched its categories
        assertEquals(a.db.categories, b.db.categories)
        assertEquals("Verdure", b.db.categories.single { it.uid == PRODUCE }.name)
        assertEquals(OTHER, b.db.categories.first().uid)
    }

    @Test
    fun twoCategoriesWithTheSameNameBecomeOne() {
        a.edit { createCategory("Animali") }
        a.edit { saveRecipe("Pappa", listOf("Croccantini")) }
        a.edit { setCategory(ingredient("Croccantini"), category("Animali")) }
        b.edit { createCategory("Animali") }
        b.edit { addProduct(week, "Lettiera") }
        b.edit { setCategory(ingredient("Lettiera"), category("Animali")) }
        syncAll(a, b)
        assertEquals(1, a.db.categories.count { it.name == "Animali" })
        assertEquals("Animali", a.categoryOf("Croccantini"))
        assertEquals("Animali", a.categoryOf("Lettiera"))
    }

    @Test
    fun concurrentReorderingConverges() {
        a.edit { createCategory("Animali") }
        syncAll()
        a.edit { moveCategory(category("Animali"), -12) }
        b.edit { moveCategory(OTHER, -5) }
        c.edit { moveCategory(PRODUCE, 3) }
        syncAll()
        assertEquals(13, a.db.categories.size)
        assertEquals((0 until 13).toList(), a.db.categories.map { it.position })
    }

    // --- Aliases and merges

    @Test
    fun anAliasMergeReachesReferencesAddedElsewhere() {
        a.edit { saveRecipe("Purè", listOf("Patate", "Patata")) }
        syncAll(a, b)
        a.edit { merge(ingredient("Patata"), ingredient("Patate")) }
        // b, offline, still uses "Patata" in a new recipe, ticks it and adds it by hand.
        b.edit { saveRecipe("Gnocchi", listOf("Patata", "Farina")) }
        b.edit { tick(week, ingredient("Patata")) }
        syncAll(a, b)
        assertEquals(listOf("Farina", "Patate"), a.names())
        assertEquals(listOf("Patate", "Farina"), a.recipeIngredients("Gnocchi"))
        assertEquals(listOf("Patata"), a.db.aliases.map { it.alias })
        assertTrue(SnapWeekItem(week, a.ingredient("Patate")) in a.db.purchases)
    }

    @Test
    fun removingAMergedIngredientFromARecipeSticks() {
        a.edit { saveRecipe("Purè", listOf("Patate", "Patata", "Latte")) }
        syncAll(a, b)
        a.edit { merge(ingredient("Patata"), ingredient("Patate")) }
        syncAll(a, b)
        b.edit { saveRecipe("Purè", listOf("Latte"), uid = recipe("Purè")) }
        syncAll(a, b)
        assertEquals(listOf("Latte"), a.recipeIngredients("Purè"))
    }

    @Test
    fun opposingMergesOnTwoPhonesConverge() {
        a.edit { saveRecipe("Purè", listOf("Patate", "Patata")) }
        syncAll(a, b)
        a.edit { merge(ingredient("Patata"), ingredient("Patate")) }
        b.edit { merge(ingredient("Patate"), ingredient("Patata")) }
        syncAll(a, b)
        assertEquals(1, a.db.ingredients.size)
        assertEquals(1, a.recipeIngredients("Purè").size)
    }

    @Test
    fun aMergeIntoADeletedIngredientKeepsTheMergedOne() {
        a.edit { saveRecipe("Purè", listOf("Patate", "Patata")) }
        syncAll(a, b)
        a.edit { deleteIngredient(ingredient("Patate")) }
        b.edit { merge(ingredient("Patata"), ingredient("Patate")) }
        syncAll(a, b)
        assertEquals(listOf("Patata"), a.names())
        assertEquals(listOf("Patata"), a.recipeIngredients("Purè"))
    }

    @Test
    fun theSameNewIngredientTypedOnTwoPhonesBecomesOne() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Zucchine")) }
        b.edit { saveRecipe("Risotto", listOf("Riso", "zucchine ")) }
        a.edit { tick(week, ingredient("Zucchine")) }
        syncAll(a, b)
        assertEquals(1, a.db.ingredients.count { it.name == "Zucchine" })
        assertEquals(a.ingredient("Zucchine"), a.db.recipes.single { it.name == "Risotto" }.ingredientUids.last())
        assertTrue(SnapWeekItem(week, a.ingredient("Zucchine")) in a.db.purchases)
    }

    @Test
    fun anAliasOnOnePhoneAndAnIngredientOfThatNameOnAnotherMerge() {
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, b)
        a.edit { addAlias(ingredient("Patate"), "Patata") }
        b.edit { saveRecipe("Gnocchi", listOf("Patata", "Farina")) }
        syncAll(a, b)
        assertEquals(listOf("Farina", "Patate"), a.names())
        assertEquals(listOf("Patate", "Farina"), a.recipeIngredients("Gnocchi"))
        assertEquals(listOf("Patata"), a.db.aliases.map { it.alias })
    }

    // --- Shopping checklist

    @Test
    fun twoPeopleShoppingAtOnceKeepEachOthersTicks() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate", "Cipolla", "Latte")) }
        syncAll(a, b)
        a.edit { tick(week, ingredient("Uova")) }
        b.edit { tick(week, ingredient("Patate")) }
        a.edit { tick(week, ingredient("Cipolla")) }
        b.edit { tick(week, ingredient("Latte")) }
        b.edit { tick(week, ingredient("Latte"), on = false) } // a mistake, undone
        syncAll(a, b)
        assertEquals(setOf("Uova", "Patate", "Cipolla"), a.db.purchases.map { p -> a.db.ingredients.single { it.uid == p.ingredientUid }.name }.toSet())
    }

    @Test
    fun aResetDoesNotUndoALaterTickElsewhere() {
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        a.edit { tick(week, ingredient("Uova")) }
        syncAll(a, b)
        a.edit { resetPurchases(week) }
        b.edit { tick(week, ingredient("Patate")) }
        syncAll(a, b)
        assertEquals(setOf(SnapWeekItem(week, a.ingredient("Patate"))), a.db.purchases)
    }

    @Test
    fun weeksAreIndependent() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll(a, b)
        a.edit { tick(week, ingredient("Uova")) }
        b.edit { tick(week.plusWeeks(1), ingredient("Uova")) }
        b.edit { tick(week.plusWeeks(1), ingredient("Uova"), on = false) }
        syncAll(a, b)
        assertEquals(setOf(SnapWeekItem(week, a.ingredient("Uova"))), a.db.purchases)
    }

    // --- Clocks

    @Test
    fun aPhoneWithAClockAYearAheadCannotWinForever() {
        val year = 365L * 24 * 60 * 60 * 1000
        val fast = world.phone("fast", skewMs = year)
        a.edit { saveRecipe("Purè", listOf("Patate")) }
        syncAll(a, fast)
        fast.edit { renameIngredient(ingredient("Patate"), "Patate del futuro") }
        a.edit { renameIngredient(ingredient("Patate"), "Patate di oggi") }
        syncAll(a, fast)
        assertEquals(listOf("Patate del futuro"), a.names()) // concurrent: the higher stamp wins
        // But a, having seen fast's stamps, now stamps after them: its next edit wins.
        a.edit { renameIngredient(ingredient("Patate del futuro"), "Patate") }
        syncAll(a, fast)
        assertEquals(listOf("Patate"), fast.names())
    }

    @Test
    fun stampsOrderByTimeThenCounterThenDevice() {
        val clock = Hlc.Clock("a") { 5 }
        val first = clock.next()
        val second = clock.next()
        assertTrue(second > first)
        clock.observe(Hlc.encode(9_000, 3, "z"))
        val third = clock.next()
        assertTrue(third > Hlc.encode(9_000, 3, "z"))
        assertEquals(9_000, Hlc.wall(third))
        assertNotEquals(Hlc.encode(1, 0, "a"), Hlc.encode(1, 0, "b"))
    }

    // --- Files

    @Test
    fun aFileFromANewerAppIsSkippedWithAMessageAndNothingApplied() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        val future = SyncFiles.encode(
            SyncFile(minReaderVersion = SyncFile.READER_VERSION + 1, deviceId = "z", deviceName = "Pixel 12", writtenAt = 1,
                records = mapOf("x" to SyncRecord(Kinds.RECIPE, mapOf("name" to Field("Torta", Hlc.encode(1, 0, "z")))))),
        )
        world.drive[SyncFile.fileName("z")] = future
        val result = a.sync()
        assertEquals(listOf("Frittata"), a.db.recipes.map { it.name })
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single().contains("Pixel 12"))
        assertTrue(result.problems.single().contains("versione più recente"))
    }

    @Test
    fun aDamagedFileIsSkipped() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        world.drive[SyncFile.fileName("broken")] = "{\"format\":\"spesami-sync\",\"deviceId\":\"x\""
        world.drive[SyncFile.fileName("badstamp")] = SyncFiles.encode(
            SyncFile(deviceId = "y", deviceName = "Y", writtenAt = 1, records = mapOf("x" to SyncRecord(Kinds.RECIPE, mapOf("name" to Field("Torta", "yesterday"))))),
        )
        val result = a.sync()
        assertEquals(listOf("Frittata"), a.db.recipes.map { it.name })
        assertEquals(2, result.problems.size)
    }

    @Test
    fun unknownKindsFromANewerCompatibleAppAreIgnored() {
        val file = SyncFile(deviceId = "z", deviceName = "Z", writtenAt = 1, records = mapOf(
            "r1" to SyncRecord(Kinds.RECIPE, mapOf("name" to Field("Torta", Hlc.encode(1, 0, "z")))),
            "n1" to SyncRecord("shopping-note", mapOf("text" to Field("Ciao", Hlc.encode(1, 0, "z")))),
        ))
        val read = SyncFiles.decode(SyncFiles.encode(file)) as SyncFileRead.Valid
        assertEquals(setOf("r1"), read.file.records.keys)
    }

    @Test
    fun devicesSeenAreListed() {
        a.sync()
        b.sync()
        val result = a.sync()
        assertEquals(listOf("a", "b"), result.devices.map { it.name })
        assertTrue(result.devices.first().isThisDevice)
    }

    @Test
    fun ownFileIsNeverReadAsAnotherDevice() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.sync()
        val result = a.sync()
        assertEquals(1, result.devices.size)
    }

    // --- Forgetting deletions

    @Test
    fun deletionsAreForgottenAfter90DaysOfServerTime() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll(a, b)
        a.edit { deleteRecipe(recipe("Frittata")) }
        syncAll(a, b)
        assertTrue(a.local.state.records.values.any { it.d != null })
        world.later(91L * 24 * 60 * 60 * 1000)
        world.serverTime = world.now
        syncAll(a, b)
        assertFalse(a.local.state.records.values.any { it.d != null })
        assertTrue(a.db.recipes.isEmpty())
    }

    @Test
    fun aPhoneBackAfterMonthsDoesNotBringDeletedThingsBack() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll()
        // c goes away with the old state; a deletes the recipe, and much later the deletion is forgotten.
        a.edit { deleteRecipe(recipe("Frittata")) }
        syncAll(a, b)
        world.later(100L * 24 * 60 * 60 * 1000)
        world.serverTime = world.now
        syncAll(a, b)
        c.sync()
        syncAll()
        assertTrue(c.db.recipes.isEmpty())
        assertTrue(a.db.recipes.isEmpty())
    }

    @Test
    fun aWrongPhoneClockNeverPrunes() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.edit { deleteRecipe(recipe("Frittata")) }
        a.sync() // no server time known
        assertTrue(a.local.state.records.values.any { it.d != null })
        assertEquals(0, a.local.state.prunedBefore)
    }

    // --- Recipe colours

    @Test
    fun aColourReachesTheOtherPhonesAndCanBeRemoved() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.edit { setColor(recipe("Frittata"), "green") }
        syncAll(a, b)
        assertEquals("green", b.db.recipes.single().color)
        b.edit { setColor(recipe("Frittata"), null) }
        syncAll(a, b)
        assertEquals(null, a.db.recipes.single().color)
    }

    @Test
    fun aColourAndARenameOnTwoPhonesBothSurvive() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        syncAll(a, b)
        a.edit { setColor(recipe("Frittata"), "blue") }
        b.edit { saveRecipe("Frittata alta", listOf("Uova"), uid = recipe("Frittata")) }
        syncAll(a, b)
        assertEquals("Frittata alta" to "blue", a.db.recipes.single().let { it.name to it.color })
    }

    @Test
    fun aPhoneThatNeverSetsColoursDoesNotEraseThem() {
        // What a 1.4.0 phone does: it writes only the fields it knows (name, notes) and keeps the others on merge.
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.edit { setColor(recipe("Frittata"), "pink") }
        syncAll(a, b)
        val uid = a.recipe("Frittata")
        val oldPhone = b.local.state.records.getValue(uid)
        val renamedByOldApp = oldPhone.copy(f = oldPhone.f + ("name" to Field("Frittata di b", Hlc.Clock("b") { world.now + 60_000 }.next())))
        val merged = SyncMerge.mergeRecord(a.local.state.records.getValue(uid), renamedByOldApp)
        assertEquals("pink", merged.value("color"))
        assertEquals("Frittata di b", merged.value("name"))
    }

    @Test
    fun anUnknownColourShowsAsNoneAndStaysInTheRecords() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.edit { setColor(recipe("Frittata"), "ultraviolet") } // as written by a newer app
        a.sync()
        b.sync()
        assertEquals(null, b.db.recipes.single().color)
        assertEquals("ultraviolet", b.local.state.records.getValue(b.recipe("Frittata")).value("color"))
    }

    @Test
    fun sameNamedRecipesMergedKeepAColour() {
        a.edit { saveRecipe("Frittata", listOf("Uova")) }
        b.edit { saveRecipe("Frittata", listOf("Patate")) }
        val coloured = if (a.recipe("Frittata") > b.recipe("Frittata")) a else b // the one that will be absorbed
        coloured.edit { setColor(recipe("Frittata"), "orange") }
        syncAll(a, b)
        assertEquals("orange", a.db.recipes.single().color)
    }
}
