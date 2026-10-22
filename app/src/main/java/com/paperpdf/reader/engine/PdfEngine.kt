package com.paperpdf.reader.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.PdfPasswordException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

data class PageSizePt(val width: Float, val height: Float)

data class PageGeometry(
    val displayWidth: Float,
    val displayHeight: Float,
    val rotation: Int,
) {
    val unrotatedWidth: Float = if (rotation % 2 == 0) displayWidth else displayHeight
    val unrotatedHeight: Float = if (rotation % 2 == 0) displayHeight else displayWidth

    fun boxBottomLeftToDisplay(box: RectF): RectF {
        val left = box.left
        val top = unrotatedHeight - box.top
        val right = box.right
        val bottom = unrotatedHeight - box.bottom
        val corners = listOf(
            unrotatedToDisplay(left, top),
            unrotatedToDisplay(right, top),
            unrotatedToDisplay(left, bottom),
            unrotatedToDisplay(right, bottom),
        )
        return RectF(
            corners.minOf { it.first },
            corners.minOf { it.second },
            corners.maxOf { it.first },
            corners.maxOf { it.second },
        )
    }

    fun unrotatedToDisplay(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        1 -> (displayWidth - y) to x
        2 -> (displayWidth - x) to (displayHeight - y)
        3 -> y to (displayHeight - x)
        else -> x to y
    }

    fun displayToUnrotated(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        1 -> y to (displayWidth - x)
        2 -> (displayWidth - x) to (displayHeight - y)
        3 -> (displayHeight - y) to x
        else -> x to y
    }
}

open class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PasswordRequiredException : PdfOpenException("该 PDF 需要密码")
class WrongPasswordException : PdfOpenException("密码错误，请重试")

data class OpenedPdfInfo(
    val pageCount: Int,
    val pageSizes: List<PageSizePt>,
    val title: String?,
    val author: String?,
    val outline: List<OutlineEntry>,
)

data class OutlineEntry(
    val title: String,
    val pageIndex: Int,
    val depth: Int,
)

/**
 * Thin, single-threaded adapter over PDFium. Every native call is serialized on one
 * dedicated thread; UI code only sees immutable copies of geometry and bitmaps.
 *
 * Keeping this adapter behind a small interface is deliberate: MuPDF can replace it
 * without touching the reader view or repositories.
 */
class PdfEngine private constructor(
    private val context: Context,
    private val pfd: ParcelFileDescriptor,
    private val document: PdfDocument,
) : AutoCloseable {

    private val core = PdfiumCore(context)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfium-engine").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()

    private var cachedPageIndex = -1
    private var cachedPage: PdfPage? = null
    private var cachedTextPage: PdfTextPage? = null
    private val textCache = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > 24
    }

    @Volatile
    private var closed = false

    val pageCount: Int = document.getPageCount()

    val geometries: List<PageGeometry> = buildList {
        if (pageCount <= 0) return@buildList
        val sampleCount = if (pageCount <= 120) pageCount else 16
        val samples = ArrayList<PageGeometry>(sampleCount)
        for (index in 0 until sampleCount) {
            readGeometry(index)?.let { samples.add(it) }
        }
        if (samples.isEmpty()) {
            repeat(pageCount) { add(PageGeometry(595f, 842f, 0)) }
            return@buildList
        }
        val first = samples.first()
        val uniform = samples.all { it == first }
        if (pageCount <= 120 || uniform) {
            if (uniform) {
                repeat(pageCount) { add(first) }
            } else {
                for (index in 0 until pageCount) {
                    add(readGeometry(index) ?: first)
                }
            }
        } else {
            // Large mixed-size documents: read every page once. This is slower,
            // but avoids wrong scroll geometry for pages with a different size.
            for (index in 0 until pageCount) {
                add(readGeometry(index) ?: first)
            }
        }
    }

    private fun readGeometry(index: Int): PageGeometry? {
        val page = document.openPage(index) ?: return null
        return try {
            val rotation = page.getPageRotation().coerceIn(0, 3)
            val width = page.getPageWidthPoint().toFloat()
            val height = page.getPageHeightPoint().toFloat()
            PageGeometry(width, height, rotation)
        } finally {
            page.close()
        }
    }

    val title: String? = runCatching { document.getDocumentMeta().title }.getOrNull()
    val author: String? = runCatching { document.getDocumentMeta().author }.getOrNull()

    suspend fun outline(): List<OutlineEntry> = withContext(dispatcher) {
        mutex.withLock {
            if (closed) return@withLock emptyList()
            val roots = runCatching { document.getTableOfContents() }.getOrDefault(emptyList())
            buildList {
                fun walk(nodes: List<Bookmark>, depth: Int) {
                    nodes.forEach { node ->
                        val title = node.title?.trim().orEmpty()
                        val page = node.pageIdx.toInt()
                        if (title.isNotEmpty()) {
                            add(
                                OutlineEntry(
                                    title = title,
                                    pageIndex = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                                    depth = depth,
                                ),
                            )
                        }
                        walk(node.children, depth + 1)
                    }
                }
                walk(roots, 0)
            }
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int,
        renderAnnotations: Boolean = false,
    ): Bitmap? = withContext(dispatcher) {
        mutex.withLock {
            if (closed || pageIndex !in 0 until pageCount) return@withLock null
            val width = targetWidth.coerceIn(16, 4096)
            val height = targetHeight.coerceIn(16, 4096)
            val result = runCatching {
                val page = page(pageIndex)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.renderPageBitmap(
                    bitmap = bitmap,
                    startX = 0,
                    startY = 0,
                    drawSizeX = width,
                    drawSizeY = height,
                    renderAnnot = renderAnnotations,
                    textMask = false,
                    canvasColor = Color.WHITE,
                    pageBackgroundColor = Color.WHITE,
                )
                bitmap
            }
            result.exceptionOrNull()?.let {
                Log.w("PdfEngine", "renderPage $pageIndex ${width}x$height failed", it)
            }
            result.getOrNull()
        }
    }

    suspend fun pageText(pageIndex: Int): String = withContext(dispatcher) {
        mutex.withLock {
            if (closed || pageIndex !in 0 until pageCount) return@withLock ""
            textCache[pageIndex]?.let { return@withLock it }
            runCatching {
                val textPage = textPage(pageIndex)
                val count = textPage.textPageCountChars()
                val text = if (count <= 0) {
                    ""
                } else {
                    textPage.textPageGetText(0, count).orEmpty()
                }
                textCache[pageIndex] = text
                text
            }.getOrDefault("")
        }
    }

    suspend fun charIndexAt(pageIndex: Int, xDisplay: Float, yDisplay: Float): Int =
        withContext(dispatcher) {
            mutex.withLock {
                if (closed) return@withLock -1
                val geometry = geometries.getOrNull(pageIndex) ?: return@withLock -1
                val (ux, uyTop) = geometry.displayToUnrotated(xDisplay, yDisplay)
                val uyBottom = geometry.unrotatedHeight - uyTop
                runCatching {
                    textPage(pageIndex).textPageGetCharIndexAtPos(
                        ux.toDouble(),
                        uyBottom.toDouble(),
                        24.0,
                        24.0,
                    )
                }.getOrDefault(-1)
            }
        }

    suspend fun wordRangeAt(pageIndex: Int, charIndex: Int): IntRange =
        withContext(dispatcher) {
            mutex.withLock {
                if (closed || charIndex < 0) return@withLock IntRange.EMPTY
                val text = textForLocked(pageIndex)
                if (text.isEmpty()) return@withLock IntRange.EMPTY
                val index = charIndex.coerceIn(0, text.length - 1)
                if (text[index].isWhitespace()) return@withLock index until (index + 1)
                var start = index
                while (start > 0 && isWordChar(text[start - 1]) && isWordChar(text[start])) {
                    start--
                }
                var end = index + 1
                while (end < text.length && isWordChar(text[end]) && isWordChar(text[end - 1])) {
                    end++
                }
                start until end
            }
        }

    suspend fun charBox(pageIndex: Int, charIndex: Int): RectF? = withContext(dispatcher) {
        mutex.withLock {
            if (closed || charIndex < 0) return@withLock null
            val geometry = geometries.getOrNull(pageIndex) ?: return@withLock null
            runCatching {
                val box = textPage(pageIndex).textPageGetCharBox(charIndex) ?: return@runCatching null
                geometry.boxBottomLeftToDisplay(box)
            }.getOrNull()
        }
    }

    suspend fun rangeRects(pageIndex: Int, start: Int, endExclusive: Int): List<RectF> =
        withContext(dispatcher) {
            mutex.withLock {
                if (closed || start < 0 || endExclusive <= start) return@withLock emptyList()
                val geometry = geometries.getOrNull(pageIndex) ?: return@withLock emptyList()
                runCatching {
                    val textPage = textPage(pageIndex)
                    val count = textPage.textPageCountRects(start, endExclusive - start)
                    buildList {
                        for (i in 0 until count) {
                            val rect = textPage.textPageGetRect(i) ?: continue
                            add(geometry.boxBottomLeftToDisplay(rect))
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }

    suspend fun hasText(pageIndex: Int): Boolean = pageText(pageIndex).isNotBlank()

    private fun textForLocked(pageIndex: Int): String {
        textCache[pageIndex]?.let { return it }
        return runCatching {
            val count = textPage(pageIndex).textPageCountChars()
            val text = if (count <= 0) "" else textPage(pageIndex).textPageGetText(0, count).orEmpty()
            textCache[pageIndex] = text
            text
        }.getOrDefault("")
    }

    private fun page(pageIndex: Int): PdfPage {
        val existing = cachedPage
        if (existing != null && cachedPageIndex == pageIndex) return existing
        closeCachedPage()
        val opened = document.openPage(pageIndex) ?: throw IOException("无法打开第 ${pageIndex + 1} 页")
        cachedPage = opened
        cachedPageIndex = pageIndex
        return opened
    }

    private fun textPage(pageIndex: Int): PdfTextPage {
        val existing = cachedTextPage
        if (existing != null && cachedPageIndex == pageIndex) return existing
        closeCachedTextPage()
        val page = page(pageIndex)
        val opened = page.openTextPage()
        cachedTextPage = opened
        return opened
    }

    private fun closeCachedTextPage() {
        runCatching { cachedTextPage?.close() }
        cachedTextPage = null
    }

    private fun closeCachedPage() {
        closeCachedTextPage()
        runCatching { cachedPage?.close() }
        cachedPage = null
        cachedPageIndex = -1
    }

    override fun close() {
        if (closed) return
        closed = true
        if (mutex.tryLock()) {
            runCatching { closeCachedPage() }
            mutex.unlock()
        }
        runCatching { document.close() }
        runCatching { pfd.close() }
        executor.shutdown()
    }

    companion object {
        fun open(context: Context, uriString: String, password: String?): PdfEngine {
            val uri = Uri.parse(uriString)
            val pfd = try {
                if (uri.scheme == "file") {
                    ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw PdfOpenException("无法读取该文件")
                }
            } catch (e: Exception) {
                throw PdfOpenException("无法打开文件：${e.message}", e)
            }
            val core = PdfiumCore(context)
            val document = try {
                core.newDocument(pfd, password)
            } catch (e: PdfPasswordException) {
                runCatching { pfd.close() }
                if (password.isNullOrEmpty()) throw PasswordRequiredException() else throw WrongPasswordException()
            } catch (e: Exception) {
                runCatching { pfd.close() }
                throw PdfOpenException("PDF 解析失败：${e.message}", e)
            }
            return try {
                PdfEngine(context, pfd, document)
            } catch (e: Exception) {
                runCatching { document.close() }
                runCatching { pfd.close() }
                throw PdfOpenException("PDF 解析失败：${e.message}", e)
            }
        }

        private fun isWordChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch == '_' || ch.code > 0x2E80
    }
}
