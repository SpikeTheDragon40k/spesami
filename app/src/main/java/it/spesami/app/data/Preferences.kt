package it.spesami.app.data

import it.spesami.core.ExportOptions
import it.spesami.core.ShoppingSort

enum class AppTheme(val label: String) { SYSTEM("Automatico (sistema)"), LIGHT("Chiaro"), DRACULA("Dracula scuro") }

enum class PdfTheme(val label: String) {
    APP("Come l'app"),
    LIGHT("Chiaro"),
    DRACULA("Dracula"),
    INK_SAVER("Risparmio inchiostro"),
}

/** Typed view of the preference table. These are the "relevant app preferences" included in backups. */
data class Preferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val pdfTheme: PdfTheme = PdfTheme.APP,
    val export: ExportOptions = ExportOptions(),
) {
    companion object {
        const val THEME = "theme"
        const val PDF_THEME = "pdf_theme"
        const val SHARE_WITH_COUNTS = "share_with_counts"
        const val SHOPPING_SORT = "shopping_sort"
        const val GROUP_BY_CATEGORY = "group_by_category"
        const val SHOW_CATEGORY_HEADERS = "show_category_headers"
        const val EXCLUDED_CATEGORIES = "export_excluded_categories"

        /** "Da classificare" in [EXCLUDED_CATEGORIES], which otherwise lists category ids. */
        private const val UNCLASSIFIED = "none"

        fun from(map: Map<String, String>) = Preferences(
            theme = AppTheme.entries.firstOrNull { it.name == map[THEME] } ?: AppTheme.SYSTEM,
            pdfTheme = PdfTheme.entries.firstOrNull { it.name == map[PDF_THEME] } ?: PdfTheme.APP,
            export = ExportOptions(
                groupByCategory = map[GROUP_BY_CATEGORY] != "false",
                includeCounts = map[SHARE_WITH_COUNTS] == "true",
                showHeaders = map[SHOW_CATEGORY_HEADERS] != "false",
                excludedCategories = decodeExcluded(map[EXCLUDED_CATEGORIES]),
                sort = ShoppingSort.entries.firstOrNull { it.name == map[SHOPPING_SORT] } ?: ShoppingSort.COUNT,
            ),
        )

        fun encodeExcluded(ids: Set<Long?>): String = ids.joinToString(",") { it?.toString() ?: UNCLASSIFIED }

        private fun decodeExcluded(value: String?): Set<Long?> = buildSet {
            value.orEmpty().split(',').filter { it.isNotBlank() }.forEach {
                if (it == UNCLASSIFIED) add(null) else it.toLongOrNull()?.let(::add)
            }
        }
    }
}
