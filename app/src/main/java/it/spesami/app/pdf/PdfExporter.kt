package it.spesami.app.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import it.spesami.core.AppData
import it.spesami.core.MealType
import it.spesami.core.ExportSection
import it.spesami.core.PdfLine
import it.spesami.core.PdfListLayout
import it.spesami.core.ShoppingExport
import it.spesami.core.Weeks
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colours of an exported PDF: the app's Light and Dracula themes, plus an ink-saving print style. */
class PdfPalette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val highlight: Int,
    val line: Int,
    /** False draws the title band as an outline instead of a filled block (ink saver). */
    val solidHeader: Boolean = true,
) {
    companion object {
        val Light = PdfPalette(
            background = 0xFFFFFFFF.toInt(),
            surface = 0xFFF5F1FC.toInt(),
            text = 0xFF22212C.toInt(),
            muted = 0xFF6B6880.toInt(),
            accent = 0xFF6D4BC3.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            highlight = 0xFFB0306F.toInt(),
            line = 0xFFDCD6EA.toInt(),
        )
        val Dracula = PdfPalette(
            background = 0xFF282A36.toInt(),
            surface = 0xFF343746.toInt(),
            text = 0xFFF8F8F2.toInt(),
            muted = 0xFF8C96C0.toInt(),
            accent = 0xFFBD93F9.toInt(),
            onAccent = 0xFF282A36.toInt(),
            highlight = 0xFFFF79C6.toInt(),
            line = 0xFF44475A.toInt(),
        )
        val InkSaver = PdfPalette(
            background = 0xFFFFFFFF.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            text = 0xFF000000.toInt(),
            muted = 0xFF555555.toInt(),
            accent = 0xFF333333.toInt(),
            onAccent = 0xFF000000.toInt(),
            highlight = 0xFF000000.toInt(),
            line = 0xFFBBBBBB.toInt(),
            solidHeader = false,
        )
    }
}

object PdfExporter {
    private const val MARGIN = 32f
    private val generatedFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
    private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private fun paint(color: Int, size: Float, typeface: Typeface = regular) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        this.typeface = typeface
    }

    /** Weekly plan: A4 landscape, one row per day, lunch and dinner columns with each meal's ingredients. */
    fun writeWeekPlan(data: AppData, weekStart: LocalDate, palette: PdfPalette, out: OutputStream) {
        val width = 842
        val height = 595
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
        val c = page.canvas
        c.drawColor(palette.background)

        var y = header(c, width, palette, "Piano settimanale", Weeks.rangeLabel(weekStart))
        val slots = data.slotsOfWeek(weekStart).associateBy { it.day to it.meal }

        val dayWidth = 118f
        val mealWidth = (width - 2 * MARGIN - dayWidth) / 2
        val columnLabel = paint(palette.muted, 9f, bold).apply { letterSpacing = 0.08f }
        c.drawText("GIORNO", MARGIN + 10, y + 12, columnLabel)
        MealType.entries.forEachIndexed { i, meal ->
            c.drawText(meal.label.uppercase(Locale.ITALIAN), MARGIN + dayWidth + i * mealWidth + 10, y + 12, columnLabel)
        }
        y += 20

        val footerTop = height - MARGIN - 10
        val rowHeight = (footerTop - 8 - y) / 7
        val dayPaint = paint(palette.text, 12f, bold)
        val datePaint = paint(palette.muted, 9.5f)
        val namePaint = paint(palette.text, 10.5f, bold)
        val ingredientsPaint = paint(palette.muted, 8.5f)
        val emptyPaint = paint(palette.line, 10.5f, bold)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = palette.line }

        for (day in 0..6) {
            val top = y + day * rowHeight
            val rect = RectF(MARGIN, top + 2, width - MARGIN, top + rowHeight - 2)
            fill.color = if (day % 2 == 0) palette.surface else palette.background
            c.drawRoundRect(rect, 8f, 8f, fill)
            c.drawRoundRect(rect, 8f, 8f, stroke)
            fill.color = palette.accent
            c.drawRoundRect(RectF(MARGIN, top + 2, MARGIN + 4, top + rowHeight - 2), 2f, 2f, fill)

            val label = Weeks.dayLabel(weekStart, day)
            c.drawText(label.substringBefore(' '), MARGIN + 14, top + 22, dayPaint)
            c.drawText(label.substringAfter(' '), MARGIN + 14, top + 36, datePaint)

            MealType.entries.forEachIndexed { i, meal ->
                val left = MARGIN + dayWidth + i * mealWidth + 10
                val maxWidth = mealWidth - 20
                if (i > 0) c.drawLine(left - 10, top + 10, left - 10, top + rowHeight - 10, stroke)
                val recipe = slots[day to meal]?.let { data.recipesById[it.recipeId] }
                if (recipe == null) {
                    c.drawText("—", left, top + 22, emptyPaint)
                    return@forEachIndexed
                }
                var lineY = top + 20
                val nameLines = wrap(recipe.name, namePaint, maxWidth, 2)
                nameLines.forEach { c.drawText(it, left, lineY, namePaint); lineY += 13 }
                val ingredients = recipe.ingredientIds.mapNotNull { data.ingredientsById[it]?.name }.joinToString(", ")
                val room = ((top + rowHeight - 8 - lineY) / 11).toInt() + 1
                if (ingredients.isNotEmpty() && room > 0) {
                    lineY += 1
                    wrap(ingredients, ingredientsPaint, maxWidth, room).forEach { c.drawText(it, left, lineY, ingredientsPaint); lineY += 11 }
                }
            }
        }

        footer(c, width, height, palette, "${slots.size} pasti pianificati su 14")
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    /**
     * Shopping list: A4 portrait, two columns with a tick box per product. [sections] come from
     * [ShoppingExport.sections], so scope, grouping, headings, counts and the category filter match the text export.
     * With [markPurchased] (full list) purchased products get a ticked box and struck-through name; otherwise
     * every box is empty, ready to be ticked by hand.
     */
    fun writeShoppingList(
        sections: List<ExportSection>,
        includeCounts: Boolean,
        markPurchased: Boolean,
        weekStart: LocalDate,
        summary: String,
        palette: PdfPalette,
        out: OutputStream,
    ) {
        val width = 595
        val height = 842
        val doc = PdfDocument()
        val itemCount = ShoppingExport.itemCount(sections)
        val subtitle = Weeks.rangeLabel(weekStart)

        val namePaint = paint(palette.text, 11f)
        val donePaint = paint(palette.muted, 11f).apply { isStrikeThruText = true }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f; color = palette.accent
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val countPaint = paint(palette.highlight, 10f, bold).apply { textAlign = Paint.Align.RIGHT }
        val headingPaint = paint(palette.accent, 9.5f, bold).apply { letterSpacing = 0.1f }
        val continuedPaint = paint(palette.muted, 8.5f)
        val summaryPaint = paint(palette.muted, 10f)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = palette.accent }
        val rule = Paint().apply { color = palette.line; strokeWidth = 0.8f }
        val headingRule = Paint().apply { color = palette.accent; strokeWidth = 1.2f }

        val columnGap = 24f
        val columnWidth = (width - 2 * MARGIN - columnGap) / 2
        val contentTop = MARGIN + 54 + 18 // below the title band, see header()
        val summaryHeight = 22f
        val bottom = height - MARGIN - 24
        val itemHeight = 21f
        val headingHeight = 26f
        val sectionGap = 12f

        val layout = PdfListLayout(
            columns = 2,
            firstPageHeight = bottom - contentTop - summaryHeight,
            pageHeight = bottom - contentTop,
            headingHeight = headingHeight,
            itemHeight = itemHeight,
            sectionGap = sectionGap,
        )
        val pages = layout.paginate(sections)

        pages.forEachIndexed { pageIndex, layoutPage ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageIndex + 1).create())
            val c = page.canvas
            c.drawColor(palette.background)
            var top = header(c, width, palette, "Lista della spesa", subtitle)
            if (pageIndex == 0) {
                c.drawText(summary, MARGIN, top + 4, summaryPaint)
                top += summaryHeight
                if (itemCount == 0) c.drawText("Nessun prodotto da esportare per questa settimana.", MARGIN, top + 20, namePaint)
            }
            layoutPage.columns.forEachIndexed { column, lines ->
                val left = MARGIN + column * (columnWidth + columnGap)
                var y = top
                lines.forEach { line ->
                    when (line) {
                        is PdfLine.Heading -> {
                            if (y > top) y += sectionGap
                            val text = line.text.uppercase(Locale.ITALIAN)
                            c.drawText(text, left, y + 16, headingPaint)
                            if (line.continued) {
                                c.drawText("(segue)", left + headingPaint.measureText(text) + 6, y + 16, continuedPaint)
                            }
                            c.drawLine(left, y + 21, left + columnWidth, y + 21, headingRule)
                            y += headingHeight
                        }
                        is PdfLine.Item -> {
                            val done = markPurchased && line.item.purchased
                            c.drawRoundRect(RectF(left + 1, y + 4, left + 12, y + 15), 2.5f, 2.5f, box)
                            if (done) {
                                c.drawPath(Path().apply { moveTo(left + 3.5f, y + 9.5f); lineTo(left + 6f, y + 12.5f); lineTo(left + 10.5f, y + 6.5f) }, tick)
                            }
                            val countText = if (includeCounts && line.item.count > 0) "×${line.item.count}" else ""
                            val nameWidth = columnWidth - 22 - (if (countText.isNotEmpty()) countPaint.measureText(countText) + 8 else 0f)
                            val itemPaint = if (done) donePaint else namePaint
                            c.drawText(wrap(line.item.name, itemPaint, nameWidth, 1).first(), left + 20, y + 14, itemPaint)
                            if (countText.isNotEmpty()) c.drawText(countText, left + columnWidth, y + 14, countPaint)
                            c.drawLine(left + 20, y + itemHeight - 1, left + columnWidth, y + itemHeight - 1, rule)
                            y += itemHeight
                        }
                    }
                }
            }
            footer(c, width, height, palette, if (pages.size > 1) "Pagina ${pageIndex + 1} di ${pages.size}" else "")
            doc.finishPage(page)
        }

        doc.writeTo(out)
        doc.close()
    }

    /** Coloured title band; returns the y where content starts. */
    private fun header(c: Canvas, width: Int, palette: PdfPalette, title: String, subtitle: String): Float {
        val band = RectF(MARGIN, MARGIN, width - MARGIN, MARGIN + 54)
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            if (!palette.solidHeader) { style = Paint.Style.STROKE; strokeWidth = 1.5f }
        }
        c.drawRoundRect(band, 12f, 12f, bandPaint)
        c.drawText(title, band.left + 18, band.top + 34, paint(palette.onAccent, 20f, bold))
        val sub = paint(palette.onAccent, 11.5f).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(subtitle, band.right - 18, band.top + 33, sub)
        return band.bottom + 18
    }

    private fun footer(c: Canvas, width: Int, height: Int, palette: PdfPalette, right: String) {
        val p = paint(palette.muted, 8f)
        val y = height - MARGIN + 6
        c.drawText("Spesami · generato il ${LocalDate.now().format(generatedFormat)}", MARGIN, y, p)
        if (right.isNotEmpty()) c.drawText(right, width - MARGIN, y, p.apply { textAlign = Paint.Align.RIGHT })
    }

    /** Greedy word wrap into at most [maxLines] lines; the last line gets an ellipsis if text remains. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        val words = text.split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size && lines.size < maxLines) {
            val candidate = if (current.isEmpty()) words[i] else "$current ${words[i]}"
            when {
                paint.measureText(candidate) <= maxWidth -> { current = candidate; i++ }
                current.isEmpty() -> { lines += ellipsize(words[i], paint, maxWidth); i++ }
                else -> { lines += current; current = "" }
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current
        if (i < words.size && lines.isNotEmpty()) lines[lines.lastIndex] = ellipsize(lines.last() + "…", paint, maxWidth)
        return lines.ifEmpty { listOf("") }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end).trimEnd(' ', ',') + "…"
    }
}
