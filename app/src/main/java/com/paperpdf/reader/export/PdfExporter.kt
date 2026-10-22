package com.paperpdf.reader.export

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.paperpdf.reader.data.AnnotationRecord
import com.paperpdf.reader.data.AnnoType
import com.paperpdf.reader.data.RectN
import com.paperpdf.reader.engine.PageGeometry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

object PdfExporter {

    fun exportAnnotated(
        context: Context,
        sourceUri: String,
        password: String?,
        annotations: List<AnnotationRecord>,
        geometries: List<PageGeometry>,
        outputUri: Uri,
    ) {
        val source = Uri.parse(sourceUri)
        val input: InputStream = if (source.scheme == "file") {
            FileInputStream(File(source.path!!))
        } else {
            context.contentResolver.openInputStream(source)
                ?: error("无法读取原 PDF")
        }
        val document = input.use { PDDocument.load(it, password) }
        try {
            annotations.groupBy { it.pageIndex }.forEach { (pageIndex, items) ->
                val page = document.getPage(pageIndex) ?: return@forEach
                val geometry = geometries.getOrNull(pageIndex) ?: return@forEach
                items.forEach { annotation ->
                    if (annotation.type == AnnoType.INK) {
                        runCatching { drawInk(document, page, geometry, annotation) }
                    } else {
                        runCatching { addAnnotation(document, page, geometry, annotation) }
                    }
                }
            }
            context.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                document.save(output)
            } ?: error("无法写入导出文件")
        } finally {
            document.close()
        }
    }

    private fun addAnnotation(
        document: PDDocument,
        page: PDPage,
        geometry: PageGeometry,
        record: AnnotationRecord,
    ) {
        val first = record.rects.firstOrNull() ?: return
        val bounds = pdfBounds(geometry, record.rects)
        val contents = buildString {
            if (record.quote.isNotBlank()) append(record.quote)
            if (record.note.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(record.note)
            }
        }
        val color = pdColor(record.colorArgb)
        val annotation = when (record.type) {
            AnnoType.HIGHLIGHT,
            AnnoType.UNDERLINE,
            AnnoType.STRIKEOUT,
            AnnoType.NOTE,
            -> {
                val subtype = when (record.type) {
                    AnnoType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                    AnnoType.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                    else -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                }
                PDAnnotationTextMarkup(subtype).apply {
                    setQuadPoints(quadPoints(geometry, record.rects))
                    this.color = color
                    setContents(contents)
                    setPrinted(true)
                }
            }

            AnnoType.SQUARE -> PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE).apply {
                this.color = color
                borderStyle = PDBorderStyleDictionary().apply {
                    width = 2f
                    style = PDBorderStyleDictionary.STYLE_SOLID
                }
                setContents(contents)
            }

            else -> PDAnnotationText().apply {
                name = PDAnnotationText.NAME_COMMENT
                this.color = color
                setContents(contents)
            }
        }
        annotation.rectangle = bounds
        annotation.isPrinted = true
        annotation.contents = contents.ifBlank { record.type }
        annotation.setModifiedDate(Calendar.getInstance())
        page.annotations.add(annotation)

        if (record.type == AnnoType.TEXTBOX) {
            val box = PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE).apply {
                this.color = color
                rectangle = bounds
                borderStyle = PDBorderStyleDictionary().apply {
                    width = 1.5f
                    style = PDBorderStyleDictionary.STYLE_SOLID
                }
                setContents(record.note)
                isPrinted = true
            }
            page.annotations.add(box)
        }

        if (record.type == AnnoType.NOTE && record.note.isNotBlank()) {
            val note = PDAnnotationText().apply {
                name = PDAnnotationText.NAME_COMMENT
                this.color = color
                setContents(record.note)
                rectangle = PDRectangle(
                    bounds.upperRightX - 12f,
                    bounds.upperRightY - 12f,
                    20f,
                    20f,
                )
                isPrinted = true
            }
            page.annotations.add(note)
        }
    }

    private fun drawInk(
        document: PDDocument,
        page: PDPage,
        geometry: PageGeometry,
        record: AnnotationRecord,
    ) {
        val strokes = inkStrokes(geometry, record.extra)
        if (strokes.isEmpty()) return
        val color = Color.red(record.colorArgb) / 255f to
            (Color.green(record.colorArgb) / 255f to Color.blue(record.colorArgb) / 255f)
        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true,
        ).use { stream ->
            stream.setStrokingColor(color.first, color.second.first, color.second.second)
            stream.setLineWidth(inkWidth(record.extra, geometry))
            stream.setLineCapStyle(1)
            stream.setLineJoinStyle(1)
            strokes.forEach { points ->
                if (points.size < 4) return@forEach
                stream.moveTo(points[0], points[1])
                var i = 2
                while (i + 1 < points.size) {
                    stream.lineTo(points[i], points[i + 1])
                    i += 2
                }
                stream.stroke()
            }
        }
    }

    private fun pdfPoint(geometry: PageGeometry, nx: Float, ny: Float): Pair<Float, Float> {
        val dx = (nx.coerceIn(0f, 1f) * geometry.displayWidth)
        val dy = (ny.coerceIn(0f, 1f) * geometry.displayHeight)
        val (ux, uyTop) = geometry.displayToUnrotated(dx, dy)
        return ux to (geometry.unrotatedHeight - uyTop)
    }

    private fun pdfBounds(geometry: PageGeometry, rects: List<RectN>): PDRectangle {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        rects.forEach { rect ->
            listOf(
                rect.left to rect.top,
                rect.right to rect.top,
                rect.left to rect.bottom,
                rect.right to rect.bottom,
            ).forEach { (nx, ny) ->
                val (x, y) = pdfPoint(geometry, nx, ny)
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        return PDRectangle(minX, minY, (maxX - minX).coerceAtLeast(1f), (maxY - minY).coerceAtLeast(1f))
    }

    private fun quadPoints(geometry: PageGeometry, rects: List<RectN>): FloatArray {
        val values = FloatArray(rects.size * 8)
        var offset = 0
        rects.forEach { rect ->
            val corners = listOf(
                pdfPoint(geometry, rect.left, rect.top),
                pdfPoint(geometry, rect.right, rect.top),
                pdfPoint(geometry, rect.left, rect.bottom),
                pdfPoint(geometry, rect.right, rect.bottom),
            )
            corners.forEach { (x, y) ->
                values[offset++] = x
                values[offset++] = y
            }
        }
        return values
    }

    private fun inkStrokes(geometry: PageGeometry, extra: String): List<FloatArray> {
        if (extra.isBlank()) return emptyList()
        val root = runCatching { JSONObject(extra) }.getOrNull() ?: return emptyList()
        val strokes = root.optJSONArray("strokes") ?: return emptyList()
        val result = mutableListOf<FloatArray>()
        for (s in 0 until strokes.length()) {
            val points = strokes.optJSONArray(s) ?: continue
            val values = FloatArray(points.length() * 2)
            var offset = 0
            for (i in 0 until points.length()) {
                val point = points.optJSONArray(i) ?: continue
                val (x, y) = pdfPoint(geometry, point.optDouble(0).toFloat(), point.optDouble(1).toFloat())
                values[offset++] = x
                values[offset++] = y
            }
            if (offset >= 4) result.add(values.copyOf(offset))
        }
        return result
    }

    private fun inkWidth(extra: String, geometry: PageGeometry): Float {
        val root = runCatching { JSONObject(extra) }.getOrNull() ?: return 2f
        val fraction = root.optDouble("width", 0.003).toFloat()
        return (fraction * geometry.displayWidth).coerceIn(0.5f, 12f)
    }

    private fun pdColor(argb: Int): PDColor = PDColor(
        floatArrayOf(
            Color.red(argb) / 255f,
            Color.green(argb) / 255f,
            Color.blue(argb) / 255f,
        ),
        PDDeviceRGB.INSTANCE,
    )
}
