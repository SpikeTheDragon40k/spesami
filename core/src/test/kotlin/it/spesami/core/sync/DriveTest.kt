package it.spesami.core.sync

import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class DriveTest {
    private val drive = FakeDrive()
    private val tokens = FakeTokens(drive)
    private val client = DriveClient(drive, tokens)
    private val week = LocalDate.of(2026, 11, 2)

    /** A phone syncing through the fake Drive, with data edited like the app does. */
    private inner class DrivePhone(val name: String) {
        val phone = World().phone(name)
        val sync = DriveSync(DriveClient(drive, FakeTokens(drive)))

        fun run(): SyncResult = sync.sync(phone.local, phone.db, name, "1.4.0", drive.now).also {
            phone.db = it.data
            phone.local = it.local
            drive.now += 60_000
        }
    }

    private fun expectFailure(kind: DriveException.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: DriveException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun listsEveryPage() {
        drive.pageSize = 2
        repeat(5) { drive.put("device-$it.json", "{}") }
        assertEquals((0 until 5).map { "device-$it.json" }, client.list().map { it.name })
        assertEquals(3, drive.requests.size)
    }

    @Test
    fun createsUpdatesAndDownloadsInTheAppFolder() {
        val id = client.create("device-a.json", "{\"x\":1}")
        assertEquals("{\"x\":1}", client.download(id))
        client.update(id, "{\"x\":2}")
        assertEquals("{\"x\":2}", client.download(id))
        assertEquals(listOf("appDataFolder"), drive.files.getValue(id).parents)
        assertNull(client.download("missing"))
    }

    @Test
    fun anExpiredTokenIsRenewedOnce() {
        drive.validTokens.clear()
        drive.validTokens += "token-2"
        assertTrue(client.list().isEmpty())
        assertEquals(listOf("token-1"), tokens.invalidated)
    }

    @Test
    fun aTokenRejectedTwiceMeansReconnect() {
        drive.validTokens.clear()
        tokens.renewalAccepted = false
        expectFailure(DriveException.Kind.UNAUTHORIZED) { client.list() }
    }

    @Test
    fun revokedAccessMeansReconnect() {
        tokens.revoked = true
        expectFailure(DriveException.Kind.UNAUTHORIZED) { client.list() }
    }

    @Test
    fun noConnectionAndServerErrorsAreTemporary() {
        drive.offline = true
        expectFailure(DriveException.Kind.OFFLINE) { client.list() }
        drive.offline = false
        drive.failNext += 503
        expectFailure(DriveException.Kind.TEMPORARY) { client.list() }
        drive.failNext += 429
        expectFailure(DriveException.Kind.TEMPORARY) { client.list() }
        assertTrue(client.list().isEmpty()) // and then it works again
    }

    @Test
    fun readsGooglesClockFromTheResponses() {
        client.list()
        assertEquals(drive.now, client.serverTime)
    }

    @Test
    fun onlyTalksToGoogleApis() {
        val a = DrivePhone("a")
        a.phone.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.run()
        assertTrue(drive.requests.isNotEmpty())
        assertTrue(drive.requests.all { it.url.startsWith("https://www.googleapis.com/") })
    }

    @Test
    fun twoPhonesSyncThroughDriveAndEachWritesOnlyItsOwnFile() {
        val a = DrivePhone("a")
        val b = DrivePhone("b")
        a.phone.edit { saveRecipe("Frittata", listOf("Uova", "Patate")) }
        b.phone.edit { saveRecipe("Purè", listOf("Patate", "Latte")) }
        a.run()
        b.run()
        a.run()
        assertEquals(a.phone.db, b.phone.db)
        assertEquals(setOf("device-a.json", "device-b.json"), drive.files.values.map { it.name }.toSet())
        // Every write by a went to a's file, every write by b to b's.
        val fileOwner = drive.files.values.associate { it.id to it.name }
        drive.requests.filter { it.method == "PATCH" }.forEach { request ->
            val id = request.url.substringAfter("/files/").substringBefore('?')
            val writer = String(request.body!!).let { (SyncFiles.decode(it) as SyncFileRead.Valid).file.deviceId }
            assertEquals("device-$writer.json", fileOwner[id])
        }
    }

    @Test
    fun ownFileIsCreatedOnceThenUpdated() {
        val a = DrivePhone("a")
        a.run()
        a.run()
        a.run()
        assertEquals(1, drive.files.size)
        assertEquals(1, drive.requests.count { it.method == "POST" })
        assertEquals(2, drive.requests.count { it.method == "PATCH" })
    }

    @Test
    fun aFailedUploadLosesNothingAndTheNextSyncCatchesUp() {
        val a = DrivePhone("a")
        val b = DrivePhone("b")
        a.run()
        a.phone.edit { saveRecipe("Frittata", listOf("Uova")) }
        a.phone.edit { tick(week, ingredient("Uova")) }
        drive.offline = true
        try {
            a.run()
            fail()
        } catch (e: DriveException) {
            assertEquals(DriveException.Kind.OFFLINE, e.kind)
        }
        drive.offline = false
        b.run()
        assertTrue(b.phone.db.recipes.isEmpty()) // nothing arrived yet
        a.run()
        b.run()
        assertEquals(listOf("Frittata"), b.phone.db.recipes.map { it.name })
        assertTrue(SnapWeekItem(week, b.phone.ingredient("Uova")) in b.phone.db.purchases)
    }

    @Test
    fun aFileFromANewerAppIsReportedNotApplied() {
        drive.put("device-z.json", SyncFiles.encode(SyncFile(minReaderVersion = 99, deviceId = "z", deviceName = "Telefono di Anna", writtenAt = 1)))
        val a = DrivePhone("a")
        val result = a.run()
        assertEquals(1, result.problems.size)
        assertTrue("Telefono di Anna" in result.problems.single())
        assertNotNull(drive.content("device-a.json"))
    }

    @Test
    fun otherFilesInTheFolderAreIgnored() {
        drive.put("notes.txt", "hello")
        val a = DrivePhone("a")
        val result = a.run()
        assertTrue(result.problems.isEmpty())
        assertFalse(drive.requests.any { it.url.contains(drive.files.values.first { f -> f.name == "notes.txt" }.id + "?alt=media") })
    }

    @Test
    fun threePhonesOverDriveConverge() {
        val phones = listOf("a", "b", "c").map { DrivePhone(it) }
        phones[0].phone.edit { saveRecipe("Frittata", listOf("Uova")) }
        phones[1].phone.edit { saveRecipe("Pasta", listOf("Pasta", "Pomodoro")) }
        phones[2].phone.edit { addProduct(week, "Sapone") }
        repeat(2) { phones.forEach { it.run() } }
        phones[1].phone.edit { setMeal(week, 0, MealType.LUNCH, recipe("Frittata")) }
        repeat(2) { phones.forEach { it.run() } }
        assertEquals(phones[0].phone.db, phones[1].phone.db)
        assertEquals(phones[1].phone.db, phones[2].phone.db)
        assertEquals(1, phones[2].phone.db.slots.size)
        assertEquals(3, phones[0].run().devices.size)
        assertEquals(Snapshot.from(AppData(categories = DefaultCategories.seed())).categories, phones[0].phone.db.categories)
    }
}
