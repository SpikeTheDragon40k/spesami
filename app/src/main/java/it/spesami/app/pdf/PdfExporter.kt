package it.spesami.app.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import it.spesami.core.AppData
import it.spesami.core.MealType
import it.spesami.core.ShoppingGroup
import it.spesami.core.Weeks
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colours of an exported PDF, matching the app's Light and Dracula Dark themes. */
class PdfPalette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val highlight: Int,
    val line: Int,
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

    /** Shopping list: A4 portrait, two columns with a tick box per ingredient, optionally grouped by category. */
    fun writeShoppingList(
        groups: List<ShoppingGroup>,
        showGroupTitles: Boolean,
        weekStart: LocalDate,
        mealCount: Int,
        palette: PdfPalette,
        out: OutputStream,
    ) {
        val width = 595
        val height = 842
        val doc = PdfDocument()
        val itemCount = groups.sumOf { it.items.size }
        val subtitle = Weeks.rangeLabel(weekStart)

        val namePaint = paint(palette.text, 11f)
        val countPaint = paint(palette.highlight, 10f, bold).apply { textAlign = Paint.Align.RIGHT }
        val groupPaint = paint(palette.accent, 9.5f, bold).apply { letterSpacing = 0.08f }
        val summaryPaint = paint(palette.muted, 10f)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = palette.accent }
        val rule = Paint().apply { color = palette.line; strokeWidth = 0.8f }

        val columnGap = 24f
        val columnWidth = (width - 2 * MARGIN - columnGap) / 2
        val bottom = height - MARGIN - 24
        val rowHeight = 21f

        // Flatten into entries that flow down the first column, then the second, then onto the next page.
        val entries = buildList {
            groups.forEach { g ->
                if (showGroupTitles) add(g.label to -1)
                g.items.forEach { add(it.name to it.count) }
            }
        }

        var pageNumber = 0
        var index = 0
        do {
            pageNumber++
            val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNumber).create())
            val c = page.canvas
            c.drawColor(palette.background)
            var top = header(c, width, palette, "Lista della spesa", subtitle)
            if (pageNumber == 1) {
                c.drawText("$itemCount ingredienti da $mealCount pasti", MARGIN, top + 4, summaryPaint)
                top += 22
            }
            if (entries.isEmpty()) {
                c.drawText("Nessun pasto pianificato per questa settimana.", MARGIN, top + 20, namePaint)
            }
            for (column in 0..1) {
                val left = MARGIN + column * (columnWidth + columnGap)
                var y = top
                while (index < entries.size) {
                    val (label, count) = entries[index]
                    val isGroup = count < 0
                    val needed = if (isGroup) rowHeight + 6 else rowHeight
                    // Never leave a group title alone at the bottom of a column.
                    if (y + needed + (if (isGroup) rowHeight else 0f) > bottom) break
                    if (isGroup) {
                        if (y > top) y += 6
                        c.drawText(label.uppercase(Locale.ITALIAN), left, y + 14, groupPaint)
                        c.drawLine(left, y + 19, left + columnWidth, y + 19, rule)
                        y += rowHeight
                    } else {
                        c.drawRoundRect(RectF(left + 1, y + 4, left + 12, y + 15), 2.5f, 2.5f, box)
                        val countText = "×$count"
                        val nameWidth = columnWidth - 22 - countPaint.measureText(countText) - 8
                        c.drawText(wrap(label, namePaint, nameWidth, 1).first(), left + 20, y + 14, namePaint)
                        c.drawText(countText, left + columnWidth, y + 14, countPaint)
                        c.drawLine(left + 20, y + rowHeight - 1, left + columnWidth, y + rowHeight - 1, rule)
                        y += rowHeight
                    }
                    index++
                }
            }
            footer(c, width, height, palette, if (pageNumber > 1 || index < entries.size) "Pagina $pageNumber" else "")
            doc.finishPage(page)
        } while (index < entries.size)

        doc.writeTo(out)
        doc.close()
    }

    /** Coloured title band; returns the y where content starts. */
    private fun header(c: Canvas, width: Int, palette: PdfPalette, title: String, subtitle: String): Float {
        val band = RectF(MARGIN, MARGIN, width - MARGIN, MARGIN + 54)
        c.drawRoundRect(band, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent })
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
