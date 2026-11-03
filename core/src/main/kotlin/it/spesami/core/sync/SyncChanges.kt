package it.spesami.core.sync

import it.spesami.core.DefaultCategories

/**
 * Turns local edits into stamped record writes, by comparing the data as it was last recorded ([old]) with the
 * data now ([new]). Runs after every change, also offline, so each edit is stamped when it was made, not when it
 * is finally uploaded. Only what changed is written, so a later sync of unrelated data never overrides it.
 */
object SyncChanges {
    fun record(state: SyncState, old: Snapshot, new: Snapshot, stamp: String): SyncState {
        val out = HashMap(state.records)

        fun write(uid: String, kind: String, fields: Map<String, String?>, defaults: Map<String, String?> = emptyMap()) {
            if (fields.isEmpty()) return
            val record = out[uid] ?: SyncRecord(kind)
            if (record.d != null) return // already deleted elsewhere: a deletion wins over edits
            out[uid] = record.copy(f = record.f + fields.mapValues { (name, value) ->
                Field(value, if (name in defaults && defaults[name] == value) Hlc.base(stamp) else stamp)
            })
        }

        fun delete(uid: String, kind: String) {
            val record = out[uid]
            out[uid] = SyncRecord(kind, emptyMap(), listOfNotNull(record?.d, stamp).max())
        }

        /** Writes the fields of [now] that differ from [before] (all of them for a new record). */
        fun changed(before: Map<String, String?>?, now: Map<String, String?>) =
            now.filter { (k, v) -> before == null || !before.containsKey(k) || before[k] != v }

        // Categories
        val oldCategories = old.categories.associateBy { it.uid }
        for (c in new.categories) {
            val before = oldCategories[c.uid]
            // A seeded category first seen here with its default name or place was not edited by anyone here:
            // those values get the lowest stamp, so a phone joining the sync never undoes the family's renames
            // and reordering of the default categories.
            val defaults = if (before == null && c.builtinKey != null) defaultFields(c.builtinKey) else emptyMap()
            write(c.uid, Kinds.CATEGORY, changed(before?.let(::fields), fields(c)), defaults)
        }
        (oldCategories.keys - new.categories.map { it.uid }.toSet()).forEach { delete(it, Kinds.CATEGORY) }

        // Ingredients; one that disappeared because it was merged into another becomes a redirect, not a deletion.
        val oldIngredients = old.ingredients.associateBy { it.uid }
        for (i in new.ingredients) {
            write(i.uid, Kinds.INGREDIENT, changed(oldIngredients[i.uid]?.let(::fields), fields(i)))
        }
        val newIngredientUids = new.ingredients.map { it.uid }.toSet()
        for (uid in oldIngredients.keys - newIngredientUids) {
            val into = new.redirects[uid]
            if (into != null && into != uid) write(uid, Kinds.INGREDIENT, mapOf("into" to into)) else delete(uid, Kinds.INGREDIENT)
        }

        // Aliases
        val oldAliases = old.aliases.associateBy { it.uid }
        for (a in new.aliases) write(a.uid, Kinds.ALIAS, changed(oldAliases[a.uid]?.let(::fields), fields(a)))
        (oldAliases.keys - new.aliases.map { it.uid }.toSet()).forEach { delete(it, Kinds.ALIAS) }

        // Recipes and their ingredients
        val oldRecipes = old.recipes.associateBy { it.uid }
        for (r in new.recipes) {
            val before = oldRecipes[r.uid]
            write(r.uid, Kinds.RECIPE, changed(before?.let(::fields), fields(r)))
            val oldPositions = before?.ingredientUids?.withIndex()?.associate { it.value to it.index }.orEmpty()
            r.ingredientUids.forEachIndexed { position, ingredient ->
                if (oldPositions[ingredient] != position) {
                    write(Keys.recipeIngredient(r.uid, ingredient), Kinds.RECIPE_INGREDIENT, mapOf("in" to "1", "pos" to position.toString()))
                }
            }
            // A link that left only because its ingredient was merged stays: it now leads to the merge target, and
            // still to the ingredient itself if another phone deleted the target meanwhile.
            (oldPositions.keys - r.ingredientUids.toSet() - new.redirects.keys).forEach {
                write(Keys.recipeIngredient(r.uid, it), Kinds.RECIPE_INGREDIENT, mapOf("in" to "0"))
            }
        }
        (oldRecipes.keys - new.recipes.map { it.uid }.toSet()).forEach { delete(it, Kinds.RECIPE) }

        // Meal plan
        fun slotKey(s: SnapSlot) = Keys.slot(s.weekStart, s.day, s.meal.name)
        val oldSlots = old.slots.associate { slotKey(it) to it.recipeUid }
        val newSlots = new.slots.associate { slotKey(it) to it.recipeUid }
        for ((key, recipe) in newSlots) if (oldSlots[key] != recipe) write(key, Kinds.SLOT, mapOf("rec" to recipe))
        (oldSlots.keys - newSlots.keys).forEach { write(it, Kinds.SLOT, mapOf("rec" to null)) }

        // Weekly checklist: each (week, ingredient) is its own record, so two people shopping never collide.
        fun items(before: Set<SnapWeekItem>, now: Set<SnapWeekItem>, kind: String, key: (SnapWeekItem) -> String) {
            (now - before).forEach { write(key(it), kind, mapOf("on" to "1")) }
            (before - now).filter { it.ingredientUid !in new.redirects }.forEach { write(key(it), kind, mapOf("on" to "0")) }
        }
        items(old.purchases, new.purchases, Kinds.PURCHASED) { Keys.purchased(it.weekStart, it.ingredientUid) }
        items(old.extras, new.extras, Kinds.EXTRA) { Keys.extra(it.weekStart, it.ingredientUid) }

        return state.copy(records = out)
    }

    private fun defaultFields(builtinKey: String): Map<String, String?> {
        val index = DefaultCategories.all.indexOfFirst { it.first == builtinKey }
        if (index < 0) return emptyMap()
        return mapOf("name" to DefaultCategories.all[index].second, "pos" to index.toString(), "builtin" to builtinKey)
    }

    private fun fields(c: SnapCategory) = mapOf("name" to c.name, "pos" to c.position.toString(), "builtin" to c.builtinKey)

    private fun fields(i: SnapIngredient) = mapOf("name" to i.name, "cat" to encodeCategory(i.categoryUid, i.categoryManual))

    private fun fields(a: SnapAlias) = mapOf("ing" to a.ingredientUid, "name" to a.alias)

    private fun fields(r: SnapRecipe) = mapOf("name" to r.name, "notes" to r.notes)

    fun encodeCategory(uid: String?, manual: Boolean) = (if (manual) "m:" else "a:") + uid.orEmpty()
}
