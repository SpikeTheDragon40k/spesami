package it.spesami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    private fun items(prefix: String, n: Int) = (1..n).map { ShoppingItem(it.toLong(), "$prefix $it", null, 1) }

    // Each column holds 5 items (or 1 heading + 4 items) exactly.
    private val layout = PdfListLayout(columns = 2, firstPageHeight = 100f, pageHeight = 100f, headingHeight = 20f, itemHeight = 20f, sectionGap = 0f)

    @Test
    fun headingIsNeverLastInColumn() {
        val sections = listOf(ExportSection("A", items("a", 4)), ExportSection("B", items("b", 2)))
        val pages = layout.paginate(sections)
        val first = pages[0].columns[0]
        assertEquals(5, first.size)
        assertTrue(first.last() is PdfLine.Item) // "B" did not fit with an item: moved on
        assertEquals(PdfLine.Heading("B"), pages[0].columns[1].first())
    }

    @Test
    fun continuedCategoryRepeatsHeadingAndBreaksPages() {
        val pages = layout.paginate(listOf(ExportSection("A", items("a", 12))))
        assertEquals(2, pages.size)
        assertEquals(PdfLine.Heading("A", continued = true), pages[0].columns[1].first())
        assertEquals(PdfLine.Heading("A", continued = true), pages[1].columns[0].first())
        val placed = pages.flatMap { p -> p.columns.flatten() }.filterIsInstance<PdfLine.Item>()
        assertEquals(12, placed.size)
        pages.flatMap { it.columns }.forEach { assertTrue(it.last() !is PdfLine.Heading) }
    }

    @Test
    fun flatListHasNoHeadings() {
        val pages = layout.paginate(listOf(ExportSection(null, items("x", 7))))
        assertEquals(listOf(5, 2), pages[0].columns.map { it.size })
        assertTrue(pages.flatMap { it.columns.flatten() }.none { it is PdfLine.Heading })
    }
}
