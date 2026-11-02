package it.spesami.core

/** One line of the shopping-list PDF. */
sealed interface PdfLine {
    /** [continued] marks a heading repeated at the top of a column because its category did not fit before. */
    data class Heading(val text: String, val continued: Boolean = false) : PdfLine
    data class Item(val item: ShoppingItem) : PdfLine
}

/** The lines of each column of one page, top to bottom. */
data class PdfPage(val columns: List<List<PdfLine>>)

/**
 * Splits the shopping list into pages and columns, platform independent so it can be tested and reused by the
 * desktop builds. A heading is never the last line of a column: it moves to the next column with its first
 * item, and a category that continues in a new column repeats its heading there.
 */
class PdfListLayout(
    val columns: Int,
    val firstPageHeight: Float,
    val pageHeight: Float,
    val headingHeight: Float,
    val itemHeight: Float,
    /** Extra space above a heading that is not at the top of a column. */
    val sectionGap: Float,
) {
    fun paginate(sections: List<ExportSection>): List<PdfPage> {
        val pages = mutableListOf<PdfPage>()
        var page = mutableListOf<MutableList<PdfLine>>(mutableListOf())
        var used = 0f
        var height = firstPageHeight

        fun nextColumn() {
            if (page.size == columns) {
                pages += PdfPage(page)
                page = mutableListOf(mutableListOf())
                height = pageHeight
            } else {
                page += mutableListOf<PdfLine>()
            }
            used = 0f
        }

        fun headingCost() = headingHeight + if (used > 0f) sectionGap else 0f

        for (section in sections) {
            if (section.title != null) {
                // Heading plus at least one item must fit, otherwise start a new column.
                if (used > 0f && used + headingCost() + itemHeight > height) nextColumn()
                used += headingCost()
                page.last() += PdfLine.Heading(section.title)
            }
            for (item in section.items) {
                if (used + itemHeight > height && used > 0f) {
                    nextColumn()
                    if (section.title != null) {
                        page.last() += PdfLine.Heading(section.title, continued = true)
                        used += headingHeight
                    }
                }
                page.last() += PdfLine.Item(item)
                used += itemHeight
            }
        }
        pages += PdfPage(page)
        return pages
    }
}
