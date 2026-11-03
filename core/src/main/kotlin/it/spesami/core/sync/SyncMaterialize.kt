package it.spesami.core.sync

import it.spesami.core.DefaultCategories
import it.spesami.core.IngredientNames
import it.spesami.core.MealType
import it.spesami.core.Uids
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Turns a merged [SyncState] into the app's data. A pure function of the state: every phone with the same
 * records ends up with exactly the same data. Records may disagree with each other (two phones edited offline),
 * so the rules that keep the data consistent are applied here, the same way everywhere:
 *
 * - Deleted (tombstoned) records are gone; anything referring to them is dropped (like `ON DELETE CASCADE`),
 *   except ingredients in a deleted category, which move to "Altro" (like deleting a category does). "Altro"
 *   itself always exists.
 * - Two categories with the same name become one: a seeded category wins, then the smallest uid.
 * - Merged ingredients ("into") are followed, through chains; a cycle (A into B on one phone, B into A on another)
 *   keeps its smallest uid. A merge into an ingredient that was deleted is ignored, so nothing more is lost.
 * - Two ingredients with the same name become one (smallest uid), and an ingredient whose name is another
 *   ingredient's alias merges into that one, as adding such an alias does in the app. Every reference to a merged
 *   ingredient (recipes, ticks, hand-added products, aliases) moves to the one that stays.
 * - Two recipes with the same name become one (smallest uid), with the ingredients of both: this is what the
 *   first sync of two phones that had entered the same recipes needs.
 * - A tick or hand-added product on two merged ingredients: the most recent write wins.
 */
object SyncMaterialize {
    private val OTHER = Uids.builtinCategory(DefaultCategories.OTHER)

    fun snapshot(state: SyncState): Snapshot {
        val records = state.records
        fun live(kind: String) = records.filter { (_, r) -> r.k == kind && r.d == null }.toSortedMap()

        // --- Categories
        val categoryRecords = live(Kinds.CATEGORY).mapNotNull { (uid, r) ->
            val name = r.value("name")?.takeIf { IngredientNames.key(it).isNotEmpty() } ?: return@mapNotNull null
            SnapCategory(uid, name.trim(), r.value("pos")?.toIntOrNull() ?: Int.MAX_VALUE, r.value("builtin"))
        }.toMutableList()
        if (categoryRecords.none { it.uid == OTHER }) {
            categoryRecords += SnapCategory(OTHER, DefaultCategories.all.last().second, Int.MAX_VALUE, DefaultCategories.OTHER)
        }
        val categoryWinner = HashMap<String, String>()
        val categories = categoryRecords.groupBy { IngredientNames.key(it.name) }.values.map { group ->
            val winner = group.minWith(compareBy({ it.builtinKey == null }, { it.uid }))
            group.forEach { categoryWinner[it.uid] = winner.uid }
            winner
        }
        /** The category an ingredient is shown in: the surviving duplicate, or "Altro" if it was deleted. */
        fun category(uid: String?): String? = if (uid.isNullOrEmpty()) null else categoryWinner[uid] ?: OTHER

        // --- Ingredients
        val ingredientRecords = records.filter { (_, r) -> r.k == Kinds.INGREDIENT }
        fun ingredientName(uid: String) = ingredientRecords[uid]?.value("name")?.takeIf { IngredientNames.key(it).isNotEmpty() }
        fun isLive(uid: String) = ingredientRecords[uid]?.d == null && ingredientName(uid) != null
        val implicit = HashMap<String, String>()

        /** The ingredient that [uid] stands for after every merge, or null if it is gone. */
        fun resolve(uid: String): String? {
            if (!isLive(uid)) return null
            val path = LinkedHashSet<String>()
            var current = uid
            while (true) {
                if (!path.add(current)) return path.dropWhile { it != current }.min()
                current = implicit[current] ?: ingredientRecords[current]?.value("into")?.takeIf { it != current && isLive(it) } ?: return current
            }
        }

        val liveIngredients = ingredientRecords.keys.filter(::isLive).sorted()
        val aliasRecords = live(Kinds.ALIAS)
        while (true) {
            val roots = liveIngredients.filter { resolve(it) == it }
            val sameName = roots.groupBy { IngredientNames.key(ingredientName(it)!!) }.values.filter { it.size > 1 }
            if (sameName.isNotEmpty()) {
                sameName.forEach { group -> group.drop(1).forEach { implicit[it] = group.first() } } // roots are sorted
                continue
            }
            val rootByName = roots.associateBy { IngredientNames.key(ingredientName(it)!!) }
            val claimed = aliasRecords.firstNotNullOfOrNull { (_, a) ->
                val owner = a.value("ing")?.let(::resolve) ?: return@firstNotNullOfOrNull null
                val named = rootByName[IngredientNames.key(a.value("name").orEmpty())]
                if (named != null && named != owner) named to owner else null
            } ?: break
            implicit[claimed.first] = claimed.second
        }

        val roots = liveIngredients.filter { resolve(it) == it }
        val absorbed = liveIngredients.filter { resolve(it) != it }.groupBy { resolve(it)!! }
        fun categoryField(uid: String): Pair<String?, Boolean>? {
            val value = ingredientRecords[uid]?.value("cat") ?: return null
            return category(value.substringAfter(':')) to value.startsWith("m:")
        }
        val ingredients = roots.map { uid ->
            var (categoryUid, manual) = categoryField(uid) ?: (null to false)
            if (categoryUid == null && !manual) {
                // As in the app's merge: a target without any category takes the first merged one's.
                absorbed[uid].orEmpty().firstNotNullOfOrNull { a -> categoryField(a)?.takeIf { it.first != null } }?.let {
                    categoryUid = it.first
                    manual = it.second
                }
            }
            SnapIngredient(uid, IngredientNames.clean(ingredientName(uid)!!), categoryUid, manual)
        }
        val names = ingredients.associate { IngredientNames.key(it.name) to it.uid }

        // --- Aliases: owned by the surviving ingredient; never an ingredient's name, never twice.
        val aliasKeys = HashSet<String>()
        val aliases = aliasRecords.mapNotNull { (uid, a) ->
            val owner = a.value("ing")?.let(::resolve) ?: return@mapNotNull null
            val text = a.value("name") ?: return@mapNotNull null
            val key = IngredientNames.key(text)
            if (key.isEmpty() || key in names || !aliasKeys.add(key)) return@mapNotNull null
            SnapAlias(uid, owner, IngredientNames.clean(text))
        }

        // --- Recipes
        val recipeRecords = live(Kinds.RECIPE).filterValues { it.value("name")?.isNotBlank() == true }
        val recipeWinner = HashMap<String, String>()
        recipeRecords.keys.groupBy { IngredientNames.key(recipeRecords.getValue(it).value("name")!!) }.values.forEach { group ->
            group.forEach { recipeWinner[it] = group.min() }
        }
        // Several links can lead to the same (recipe, ingredient) after merges: the most recent decides.
        val links = HashMap<Pair<String, String>, SyncRecord>()
        records.forEach { (uid, r) ->
            if (r.k != Kinds.RECIPE_INGREDIENT) return@forEach
            val parts = Keys.parts(uid).takeIf { it.size == 3 } ?: return@forEach
            val recipe = recipeWinner[parts[1]] ?: return@forEach
            val ingredient = resolve(parts[2]) ?: return@forEach
            val key = recipe to ingredient
            val current = links[key]
            if (current == null || newer(r.f["in"], current.f["in"])) links[key] = r
        }
        val recipeIngredients = HashMap<String, MutableMap<String, Int>>()
        links.forEach { (key, r) ->
            if (r.value("in") != "1") return@forEach
            recipeIngredients.getOrPut(key.first) { HashMap() }[key.second] = r.value("pos")?.toIntOrNull() ?: 0
        }
        val recipes = recipeWinner.values.toSortedSet().map { uid ->
            val r = recipeRecords.getValue(uid)
            val ordered = recipeIngredients[uid].orEmpty().entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
            SnapRecipe(uid, r.value("name")!!.trim(), r.value("notes").orEmpty(), ordered)
        }

        // --- Meal plan
        val slots = records.mapNotNull { (uid, r) ->
            if (r.k != Kinds.SLOT) return@mapNotNull null
            val parts = Keys.parts(uid).takeIf { it.size == 4 } ?: return@mapNotNull null
            val week = monday(parts[1]) ?: return@mapNotNull null
            val day = parts[2].toIntOrNull()?.takeIf { it in 0..6 } ?: return@mapNotNull null
            val meal = MealType.entries.firstOrNull { it.name == parts[3] } ?: return@mapNotNull null
            val recipe = r.value("rec")?.let(recipeWinner::get) ?: return@mapNotNull null
            SnapSlot(week, day, meal, recipe)
        }

        // --- Weekly checklist
        fun weekItems(kind: String): Set<SnapWeekItem> {
            val newest = HashMap<SnapWeekItem, Field>()
            records.forEach { (uid, r) ->
                if (r.k != kind) return@forEach
                val parts = Keys.parts(uid).takeIf { it.size == 3 } ?: return@forEach
                val week = monday(parts[1]) ?: return@forEach
                val ingredient = resolve(parts[2]) ?: return@forEach
                val field = r.f["on"] ?: return@forEach
                val item = SnapWeekItem(week, ingredient)
                if (newer(field, newest[item])) newest[item] = field
            }
            return newest.filterValues { it.v == "1" }.keys
        }

        return Snapshot(
            categories = categories,
            ingredients = ingredients.map { it.copy(categoryUid = it.categoryUid?.let { c -> categoryWinner[c] ?: OTHER }) },
            aliases = aliases,
            recipes = recipes,
            slots = slots,
            purchases = weekItems(Kinds.PURCHASED),
            extras = weekItems(Kinds.EXTRA),
        )
    }

    /** Whether [a] was written after [b]; equal stamps are ordered by value so the result never depends on order. */
    private fun newer(a: Field?, b: Field?): Boolean = when {
        a == null -> false
        b == null -> true
        a.s != b.s -> a.s > b.s
        else -> a.v.orEmpty() > b.v.orEmpty()
    }

    private fun monday(text: String): LocalDate? = try {
        LocalDate.parse(text).takeIf { it.dayOfWeek == DayOfWeek.MONDAY }
    } catch (e: DateTimeParseException) {
        null
    }
}
