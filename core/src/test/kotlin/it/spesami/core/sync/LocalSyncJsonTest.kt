package it.spesami.core.sync

import it.spesami.core.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class LocalSyncJsonTest {
    @Test
    fun roundTrip() {
        val world = World()
        val a = world.phone("a")
        val week = LocalDate.of(2026, 11, 2)
        a.edit { saveRecipe("Frittata", listOf("Uova", "Patate"), "Padella") }
        a.edit { setMeal(week, 1, MealType.DINNER, recipe("Frittata")) }
        a.edit { tick(week, ingredient("Uova")) }
        a.edit { addProduct(week, "Sapone") }
        a.edit { deleteRecipe(recipe("Frittata")) }
        a.sync()
        val back = LocalSyncJson.decode(LocalSyncJson.encode(a.local))
        assertEquals(a.local.deviceId, back!!.deviceId)
        assertEquals(a.local.state, back.state)
        assertEquals(a.local.last, back.last)
    }

    @Test
    fun beforeTheFirstSyncThereIsNoLastSnapshot() {
        val back = LocalSyncJson.decode(LocalSyncJson.encode(LocalSync("dev-1")))
        assertNull(back!!.last)
    }

    @Test
    fun unreadableTextIsNull() {
        assertNull(LocalSyncJson.decode("{oops"))
    }
}
