package it.spesami.core

import java.time.LocalDate

/**
 * Keeps the display on while "Modalità Spesa" is in the foreground. Android implements it with the window's
 * FLAG_KEEP_SCREEN_ON; platforms without such a concept (or desktop builds) use [NoOp].
 * Implementations must be idempotent and must never clear a keep-awake request they did not make themselves.
 */
interface ScreenAwakeController {
    fun enable()
    fun disable()

    object NoOp : ScreenAwakeController {
        override fun enable() = Unit
        override fun disable() = Unit
    }
}

/** A pending category section of the shopping mode, with how many of its products are still to buy. */
data class ShoppingModeSection(val group: ShoppingGroup) {
    val pending: Int get() = group.items.size
    val label: String get() = "${group.label} — $pending da comprare"
}

/**
 * What "Modalità Spesa" shows: the week's existing checklist ([ShoppingList.build], manual products included),
 * split into still-to-buy sections and the "Già acquistati" block. Progress always covers the whole week's list,
 * also while a search narrows the rows.
 */
data class ShoppingModeView(
    val progress: ShoppingProgress,
    val pending: List<ShoppingModeSection>,
    val purchased: List<ShoppingItem>,
    val query: String,
) {
    val isEmpty: Boolean get() = progress.total == 0
    val isComplete: Boolean get() = progress.total > 0 && progress.pending == 0
    val noMatches: Boolean get() = query.isNotBlank() && pending.isEmpty() && purchased.isEmpty()
    val remainingLabel: String get() = if (progress.pending == 1) "1 prodotto da comprare" else "${progress.pending} prodotti da comprare"

    companion object {
        const val COMPLETED = "Spesa completata!"
        const val PURCHASED_SECTION = "Già acquistati"
    }
}

object ShoppingMode {
    fun view(data: AppData, weekStart: LocalDate, query: String = ""): ShoppingModeView {
        val items = ShoppingList.build(data, weekStart, ShoppingSort.ALPHABETICAL)
        val needle = IngredientDictionary.normalize(query)
        val shown = if (needle.isEmpty()) items else items.filter { needle in IngredientDictionary.normalize(it.name) }
        return ShoppingModeView(
            progress = ShoppingList.progress(items),
            pending = ShoppingList.grouped(data, shown.filter { !it.purchased }).map(::ShoppingModeSection),
            purchased = ShoppingList.grouped(data, shown.filter { it.purchased }).flatMap { it.items },
            query = query,
        )
    }
}

/** A checkbox change, kept so the snackbar's "ANNULLA" can write the opposite state back to the database. */
data class PurchaseToggle(val ingredientId: Long, val name: String, val purchased: Boolean) {
    val message: String get() = if (purchased) "Segnato come acquistato: $name" else "Di nuovo da comprare: $name"
    fun undo(): PurchaseToggle = copy(purchased = !purchased)

    companion object {
        const val UNDO_LABEL = "ANNULLA"
    }
}
