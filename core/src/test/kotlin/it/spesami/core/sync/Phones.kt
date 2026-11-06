package it.spesami.core.sync

import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.IngredientNames
import it.spesami.core.MealType
import it.spesami.core.Uids
import java.time.LocalDate

/** A shared Drive folder and a clock for simulated phones. */
class World(var now: Long = 1_793_700_000_000L /* 2026-11-02 */) {
    val drive = LinkedHashMap<String, String>()
    var serverTime: Long? = null

    fun phone(name: String, skewMs: Long = 0) = Phone(this, name, skewMs)

    fun later(ms: Long = 60_000) {
        now += ms
    }
}

/**
 * One simulated phone: its data ([db]) changed through the same operations as the app's repository (same
 * cascades, merges and category rules), its sync data, and its own clock ([skewMs] off from real time).
 */
class Phone(private val world: World, val name: String, private val skewMs: Long = 0) {
    val id = name
    var db: Snapshot = Snapshot.from(AppData(categories = DefaultCategories.seed()))
    var local = LocalSync(id)
    private var counter = 0

    fun now() = world.now + skewMs

    private fun newUid() = "$name-${++counter}"

    /** A user edit: applied, then stamped (as the app does a few seconds after each change). */
    fun edit(change: Phone.() -> Snapshot) {
        db = change()
        local = Sync.record(local, db, now())
        db = db.copy(redirects = emptyMap()) // recorded, as the app clears its ingredient_redirect table
        world.later(1_000)
    }

    fun sync(): SyncResult {
        val own = SyncFile.fileName(id)
        val peers = world.drive.filterKeys { it != own }.values.toList()
        val result = Sync.run(local, db, peers, name, "1.4.0", now(), world.serverTime)
        world.drive[own] = result.ownFile
        db = result.data
        local = result.local
        world.later(1_000)
        return result
    }

    // --- Lookups

    fun ingredient(name: String): String {
        val key = IngredientNames.key(name)
        return db.ingredients.firstOrNull { IngredientNames.key(it.name) == key }?.uid
            ?: db.aliases.firstOrNull { IngredientNames.key(it.alias) == key }?.ingredientUid
            ?: error("$name not found on ${this.name}: ${db.ingredients.map { it.name }}")
    }

    fun recipe(name: String) = db.recipes.single { it.name == name }.uid

    fun category(name: String) = db.categories.single { it.name == name }.uid

    fun names(): List<String> = db.ingredients.map { it.name }.sorted()

    fun recipeIngredients(recipe: String) = db.recipes.single { it.name == recipe }.ingredientUids.map { u -> db.ingredients.single { it.uid == u }.name }

    fun categoryOf(ingredient: String) = db.ingredients.single { it.uid == ingredient(ingredient) }.categoryUid
        ?.let { c -> db.categories.single { it.uid == c }.name }

    // --- Operations, mirroring Repository

    private fun findOrCreate(s: Snapshot, name: String): Pair<Snapshot, String> {
        val key = IngredientNames.key(name)
        s.ingredients.firstOrNull { IngredientNames.key(it.name) == key }?.let { return s to it.uid }
        s.aliases.firstOrNull { IngredientNames.key(it.alias) == key }?.let { return s to it.ingredientUid }
        val uid = newUid()
        return s.copy(ingredients = s.ingredients + SnapIngredient(uid, IngredientNames.clean(name))) to uid
    }

    fun saveRecipe(name: String, ingredients: List<String>, notes: String = "", uid: String? = null): Snapshot {
        var s = db
        val ids = ingredients.map { n -> findOrCreate(s, n).also { s = it.first }.second }.distinct()
        val recipeUid = uid ?: newUid()
        val recipe = SnapRecipe(recipeUid, name, notes, ids, db.recipes.firstOrNull { it.uid == recipeUid }?.color)
        return s.copy(recipes = s.recipes.filter { it.uid != recipeUid } + recipe)
    }

    fun setColor(recipe: String, color: String?) = db.copy(recipes = db.recipes.map { if (it.uid == recipe) it.copy(color = color) else it })

    fun deleteRecipe(uid: String) = db.copy(recipes = db.recipes.filter { it.uid != uid }, slots = db.slots.filter { it.recipeUid != uid })

    fun setMeal(week: LocalDate, day: Int, meal: MealType, recipe: String?): Snapshot {
        val others = db.slots.filterNot { it.weekStart == week && it.day == day && it.meal == meal }
        return db.copy(slots = if (recipe == null) others else others + SnapSlot(week, day, meal, recipe))
    }

    fun renameIngredient(uid: String, name: String) =
        db.copy(ingredients = db.ingredients.map { if (it.uid == uid) it.copy(name = IngredientNames.clean(name)) else it })

    fun setCategory(uid: String, category: String?) =
        db.copy(ingredients = db.ingredients.map { if (it.uid == uid) it.copy(categoryUid = category, categoryManual = true) else it })

    /** "Rename" of an automatically classified ingredient: the app re-classifies it for the new name. */
    fun renameAutomatic(uid: String, name: String, suggested: String?) =
        db.copy(ingredients = db.ingredients.map { if (it.uid == uid && !it.categoryManual) it.copy(name = name, categoryUid = suggested) else if (it.uid == uid) it.copy(name = name) else it })

    fun addAlias(owner: String, alias: String) = db.copy(aliases = db.aliases + SnapAlias(newUid(), owner, IngredientNames.clean(alias)))

    fun deleteIngredient(uid: String) = db.copy(
        ingredients = db.ingredients.filter { it.uid != uid },
        aliases = db.aliases.filter { it.ingredientUid != uid },
        recipes = db.recipes.map { r -> r.copy(ingredientUids = r.ingredientUids - uid) },
        purchases = db.purchases.filter { it.ingredientUid != uid }.toSet(),
        extras = db.extras.filter { it.ingredientUid != uid }.toSet(),
    )

    /** "Unisci" / adding an alias that is another ingredient's name: [source] into [target]. */
    fun merge(source: String, target: String): Snapshot {
        val sourceName = db.ingredients.single { it.uid == source }.name
        fun move(items: Set<SnapWeekItem>) = items.map { if (it.ingredientUid == source) it.copy(ingredientUid = target) else it }.toSet()
        val src = db.ingredients.single { it.uid == source }
        return db.copy(
            ingredients = db.ingredients.filter { it.uid != source }.map {
                if (it.uid == target && it.categoryUid == null && !it.categoryManual) it.copy(categoryUid = src.categoryUid, categoryManual = src.categoryManual) else it
            },
            aliases = db.aliases.map { if (it.ingredientUid == source) it.copy(ingredientUid = target) else it } + SnapAlias(newUid(), target, sourceName),
            recipes = db.recipes.map { r -> r.copy(ingredientUids = r.ingredientUids.map { if (it == source) target else it }.distinct()) },
            purchases = move(db.purchases),
            extras = move(db.extras),
            redirects = db.redirects + (source to target),
        )
    }

    fun createCategory(name: String): Snapshot {
        val ordered = db.categories
        val insertAt = if (ordered.lastOrNull()?.uid == OTHER) ordered.size - 1 else ordered.size
        val list = ordered.toMutableList().apply { add(insertAt, SnapCategory(newUid(), name, 0)) }
        return db.copy(categories = list.mapIndexed { i, c -> c.copy(position = i) })
    }

    fun renameCategory(uid: String, name: String) = db.copy(categories = db.categories.map { if (it.uid == uid) it.copy(name = name) else it })

    fun moveCategory(uid: String, delta: Int): Snapshot {
        val list = db.categories.toMutableList()
        val from = list.indexOfFirst { it.uid == uid }
        list.add((from + delta).coerceIn(0, list.lastIndex), list.removeAt(from))
        return db.copy(categories = list.mapIndexed { i, c -> c.copy(position = i) })
    }

    fun deleteCategory(uid: String) = db.copy(
        categories = db.categories.filter { it.uid != uid }.mapIndexed { i, c -> c.copy(position = i) },
        ingredients = db.ingredients.map { if (it.categoryUid == uid) it.copy(categoryUid = OTHER) else it },
    )

    fun tick(week: LocalDate, ingredient: String, on: Boolean = true) = db.copy(
        purchases = if (on) db.purchases + SnapWeekItem(week, ingredient) else db.purchases - SnapWeekItem(week, ingredient),
    )

    fun resetPurchases(week: LocalDate) = db.copy(purchases = db.purchases.filter { it.weekStart != week }.toSet())

    fun addProduct(week: LocalDate, name: String): Snapshot {
        val (s, uid) = findOrCreate(db, name)
        return s.copy(extras = s.extras + SnapWeekItem(week, uid), purchases = s.purchases - SnapWeekItem(week, uid))
    }

    companion object {
        val OTHER = Uids.builtinCategory(DefaultCategories.OTHER)
        val PRODUCE = Uids.builtinCategory("frutta_verdura")
    }
}
