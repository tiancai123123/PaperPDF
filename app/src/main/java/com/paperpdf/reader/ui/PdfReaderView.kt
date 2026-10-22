package com.paperpdf.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import com.paperpdf.reader.data.AnnotationRecord
import com.paperpdf.reader.data.AnnoType
import com.paperpdf.reader.data.ColorMode
import com.paperpdf.reader.data.LayoutMode
import com.paperpdf.reader.data.RectN
import com.paperpdf.reader.engine.PageGeometry
import com.paperpdf.reader.engine.PdfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ReaderTool { NONE, PEN, ERASER, TEXTBOX }

data class SelectionInfo(
    val pageIndex: Int,
    val start: Int,
    val endExclusive: Int,
    val quote: String,
    val rects: List<RectN>,
)

data class ReaderPosition(
    val pageIndex: Int,
    val offset: Float,
    val zoom: Float,
    val mode: LayoutMode,
)

private data class TextSelection(
    val pageIndex: Int,
    val start: Int,
    val endExclusive: Int,
    val quote: String,
    val rects: List<RectN>,
) {
    fun info() = SelectionInfo(pageIndex, start, endExclusive, quote, rects)
}

private data class SearchMark(val pageIndex: Int, val start: Int, val endExclusive: Int, val rects: List<RectN>)

class PdfReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(pageIndex: Int)
        fun onPositionChanged(pageIndex: Int, offset: Float, zoom: Float, mode: LayoutMode)
        fun onChromeToggle()
        fun onSelectionChanged(info: SelectionInfo?)
        fun onRequestTextBox(pageIndex: Int, rect: RectN)
        fun onCreateInk(pageIndex: Int, extra: String, bounds: RectN)
        fun onAnnotationTap(record: AnnotationRecord)
        fun onDeleteAnnotation(record: AnnotationRecord)
        fun onMessage(message: String)
        fun onZoomChanged(zoom: Float)
    }

    var listener: Listener? = null

    private var engine: PdfEngine? = null
    private var geometries: List<PageGeometry> = emptyList()
    private var annotations: List<AnnotationRecord> = emptyList()
    private var annotationsByPage: Map<Int, List<AnnotationRecord>> = emptyMap()
    private var inkCache: MutableMap<Long, List<List<PointF>>> = mutableMapOf()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val pageGap = 10f * density
    private val touchSlop = 8f * density
    private val handleRadius = 11f * density
    private val noteIconRadius = 9f * density

    private var marginPx = 0f
    private var mode = LayoutMode.VERTICAL
    private var colorMode = ColorMode.DAY
    private var zoom = 1f
    private var pinchEnabled = true
    private val isTablet = resources.configuration.smallestScreenWidthDp >= 600
    private var fitPageActive = false

    private var pageTops = FloatArray(0)
    private var pageHeights = FloatArray(0)
    private var totalHeight = 0f
    private var scrollY = 0f
    private var panX = 0f

    private var currentPage = 0
    private var hOffset = 0f
    private var hPanX = 0f
    private var hPanY = 0f
    private var pendingPage = 0
    private var pendingFraction = 0f
    private var layoutReady = false

    private var selection: TextSelection? = null
    private var selectionDrag = 0
    private var selectionAnchorStart = 0
    private var selectionAnchorEnd = 0
    private var selectionJob: Job? = null

    private var searchMark: SearchMark? = null
    private var activeTool = ReaderTool.NONE
    private var penDrawing = false
    private var penPage = 0
    private var penPoints = mutableListOf<PointF>()

    private var panning = false
    private var pinching = false
    private var twoFingerPan = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var scroller = OverScroller(context)
    private var lastNotifyAt = 0L

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderJobs = HashMap<String, Job>()
    private val bitmapBudgetBytes = bitmapBudget()
    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapBudgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun bitmapBudget(): Int {
        val thirdOfHeap = (Runtime.getRuntime().maxMemory() / 3).toInt()
        return thirdOfHeap.coerceIn(96 * 1024 * 1024, 192 * 1024 * 1024)
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val annotationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 12f * scaledDensity
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 132, 255)
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 38, 132, 255)
        style = Paint.Style.FILL
    }
    private val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 196, 0)
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val nightFilter by lazy {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 250f,
                    0f, -1f, 0f, 0f, 250f,
                    0f, 0f, -1f, 0f, 250f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    private val sepiaFilter by lazy {
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scroller.forceFinished(true)
            panning = false
            pinching = true
            hOffset = 0f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!pinchEnabled) return false
            applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            listener?.onZoomChanged(zoom)
            notifyPosition(force = true)
            requestVisibleRenders()
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            if (activeTool != ReaderTool.NONE) return
            beginSelection(e.x, e.y)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (activeTool != ReaderTool.NONE) return false
            val fit = minZoomFor()
            val target = if (fit < 0.999f) {
                if (zoom > fit + 0.05f) fit else 1f
            } else {
                if (zoom > 1.05f) 1f else 2.2f
            }
            applyZoom(target, e.x, e.y)
            listener?.onZoomChanged(zoom)
            notifyPosition(force = true)
            return true
        }
    })

    init {
        isFocusable = true
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setDocument(
        engine: PdfEngine,
        geometries: List<PageGeometry>,
        page: Int,
        fraction: Float,
        initialZoom: Float,
        layoutMode: LayoutMode,
    ) {
        this.engine = engine
        this.geometries = geometries
        mode = layoutMode
        pendingPage = page
        pendingFraction = fraction
        currentPage = page
        val fit = pageFitZoom(page)
        // Tablets in landscape start in full-page mode regardless of the
        // portrait zoom that was saved for this document.
        fitPageActive = isTabletLandscape()
        zoom = if (fitPageActive) fit else initialZoom.coerceIn(minZoomFor(page), 6f)
        bitmapCache.evictAll()
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        layoutReady = false
        post {
            recomputeLayout(requestRenders = false)
            goToPage(pendingPage, pendingFraction)
        }
    }

    fun setAnnotations(items: List<AnnotationRecord>) {
        annotations = items
        annotationsByPage = items.groupBy { it.pageIndex }
        inkCache = mutableMapOf()
        items.filter { it.type == AnnoType.INK }.forEach { record ->
            inkCache[record.id] = parseInk(record.extra)
        }
        invalidate()
    }

    fun setColorMode(mode: ColorMode) {
        colorMode = mode
        invalidate()
    }

    fun setLayoutMode(next: LayoutMode) {
        if (mode == next) return
        val page = currentPage
        val frac = currentOffsetFraction()
        mode = next
        hOffset = 0f
        hPanX = 0f
        hPanY = 0f
        recomputeLayout(requestRenders = false)
        goToPage(page, frac)
        invalidate()
    }

    fun setTool(tool: ReaderTool) {
        activeTool = tool
        if (tool != ReaderTool.NONE) clearSelection()
        invalidate()
    }

    fun setPinchEnabled(enabled: Boolean) {
        pinchEnabled = enabled
    }

    fun currentTool(): ReaderTool = activeTool

    fun currentPage(): Int = currentPage

    fun currentZoom(): Float = zoom

    fun positionSnapshot(): ReaderPosition {
        val page = if (mode == LayoutMode.VERTICAL && layoutReady) {
            pageAtContentY(scrollY + height * 0.35f)
        } else {
            currentPage
        }
        return ReaderPosition(page, currentOffsetFraction(), zoom, mode)
    }

    fun currentOffsetFraction(): Float {
        if (!layoutReady || pageHeights.isEmpty()) return 0f
        return when (mode) {
            LayoutMode.VERTICAL -> {
                val top = pageTops.getOrNull(currentPage) ?: 0f
                ((scrollY - top) / pageHeights[currentPage].coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            LayoutMode.HORIZONTAL -> 0f
        }
    }

    fun goToPage(page: Int, fraction: Float = 0f) {
        if (geometries.isEmpty()) return
        val target = page.coerceIn(0, geometries.lastIndex)
        currentPage = target
        pendingPage = target
        pendingFraction = fraction.coerceIn(0f, 1f)
        if (!layoutReady) return
        if (mode == LayoutMode.VERTICAL) {
            val top = pageTops.getOrNull(target) ?: 0f
            scrollY = top + pendingFraction * pageHeights.getOrElse(target) { 0f }
            clampVertical()
        } else {
            hOffset = 0f
            hPanX = 0f
            hPanY = 0f
        }
        invalidate()
        notifyPosition(force = true)
        requestVisibleRenders()
    }

    fun showSearchHit(pageIndex: Int, start: Int, endExclusive: Int) {
        goToPage(pageIndex)
        selectionJob?.cancel()
        selectionJob = renderScope.launch {
            val eng = engine ?: return@launch
            val rects = eng.rangeRects(pageIndex, start, endExclusive)
            val geometry = geometries.getOrNull(pageIndex) ?: return@launch
            searchMark = SearchMark(
                pageIndex,
                start,
                endExclusive,
                rects.map { normalize(it, geometry) },
            )
            invalidate()
        }
    }

    fun clearSearchMark() {
        searchMark = null
        invalidate()
    }

    fun selectionInfo(): SelectionInfo? = selection?.info()

    fun clearSelection() {
        selection = null
        selectionDrag = 0
        listener?.onSelectionChanged(null)
        invalidate()
    }

    fun refresh() {
        invalidate()
        requestVisibleRenders()
    }

    fun resetZoom() {
        val page = currentPage
        val frac = currentOffsetFraction()
        val fit = pageFitZoom(page)
        fitPageActive = isTabletLandscape()
        zoom = if (fitPageActive) fit else 1f
        panX = 0f
        hPanX = 0f
        hPanY = 0f
        recomputeLayout(requestRenders = false)
        goToPage(page, frac)
        listener?.onZoomChanged(zoom)
    }

    fun maxPage(): Int = geometries.lastIndex.coerceAtLeast(0)

    private fun recomputeLayout(preserveScroll: Boolean = false, requestRenders: Boolean = true) {
        if (width == 0 || height == 0 || geometries.isEmpty()) return
        val anchorPage = currentPage.coerceIn(0, geometries.lastIndex)
        if (fitPageActive) zoom = pageFitZoom(anchorPage)
        val anchorFraction = if (layoutReady) currentOffsetFraction() else pendingFraction
        if (mode == LayoutMode.VERTICAL) {
            pageTops = FloatArray(geometries.size)
            pageHeights = FloatArray(geometries.size)
            var y = 0f
            geometries.forEachIndexed { index, geometry ->
                pageTops[index] = y
                val h = displayWidth(geometry) * (geometry.displayHeight / geometry.displayWidth)
                pageHeights[index] = h
                y += h + pageGap
            }
            totalHeight = (y - pageGap).coerceAtLeast(0f)
            layoutReady = true
            if (!preserveScroll) {
                scrollY = (pageTops.getOrElse(anchorPage) { 0f } + anchorFraction * pageHeights.getOrElse(anchorPage) { 0f })
            }
            clampVertical()
        } else {
            totalHeight = 0f
            pageTops = FloatArray(0)
            pageHeights = FloatArray(0)
            layoutReady = true
            clampHorizontal()
        }
        if (requestRenders) requestVisibleRenders()
    }

    private fun displayWidth(geometry: PageGeometry): Float {
        val usable = (width - 2 * marginPx).coerceAtLeast(32f)
        return usable * zoom
    }

    private fun isTabletLandscape(w: Int = width, h: Int = height): Boolean =
        isTablet && w > h

    private fun pageFitZoom(page: Int = currentPage, w: Int = width, h: Int = height): Float {
        if (!isTablet || w <= h) return 1f
        val geometry = geometries.getOrNull(page) ?: return 1f
        val usableW = (w - 2 * marginPx).coerceAtLeast(32f)
        val usableH = (h - 2 * marginPx).coerceAtLeast(32f)
        val heightAtOne = usableW * geometry.displayHeight / geometry.displayWidth
        if (heightAtOne <= 0f) return 1f
        return (usableH / heightAtOne).coerceIn(0.2f, 1f)
    }

    private fun minZoomFor(page: Int = currentPage): Float =
        if (isTabletLandscape()) pageFitZoom(page) else 1f

    private fun displayHeight(geometry: PageGeometry): Float =
        displayWidth(geometry) * (geometry.displayHeight / geometry.displayWidth)

    private fun pageRect(pageIndex: Int): RectF {
        val geometry = geometries.getOrNull(pageIndex) ?: return RectF()
        val w = displayWidth(geometry)
        val h = displayHeight(geometry)
        return if (mode == LayoutMode.VERTICAL) {
            val left = (width - w) / 2f + panX
            val top = pageTops.getOrElse(pageIndex) { 0f } - scrollY
            RectF(left, top, left + w, top + h)
        } else {
            val left = (width - w) / 2f + hPanX + hOffset
            val top = (height - h) / 2f + hPanY
            RectF(left, top, left + w, top + h)
        }
    }

    private fun clampVertical() {
        val maxScroll = max(0f, totalHeight - height)
        scrollY = scrollY.coerceIn(0f, maxScroll)
        if (zoom <= 1.01f) {
            panX = 0f
        } else {
            val geometry = geometries.getOrNull(currentPage) ?: return
            val w = displayWidth(geometry)
            val limit = max(0f, (w - width) / 2f + marginPx)
            panX = panX.coerceIn(-limit, limit)
        }
    }

    private fun clampHorizontal() {
        val geometry = geometries.getOrNull(currentPage) ?: return
        val w = displayWidth(geometry)
        val h = displayHeight(geometry)
        val baseLeft = (width - w) / 2f
        val baseTop = (height - h) / 2f
        if (w <= width - 2 * marginPx) {
            hPanX = 0f
        } else {
            val lower = width - marginPx - baseLeft - w
            val upper = marginPx - baseLeft
            hPanX = hPanX.coerceIn(min(lower, upper), max(lower, upper))
        }
        if (h <= height - 2 * marginPx) {
            hPanY = 0f
        } else {
            val lower = height - marginPx - baseTop - h
            val upper = marginPx - baseTop
            hPanY = hPanY.coerceIn(min(lower, upper), max(lower, upper))
        }
    }

    private fun applyZoom(next: Float, focusX: Float, focusY: Float) {
        if (!layoutReady) return
        val old = zoom
        val minZoom = minZoomFor()
        val target = next.coerceIn(minZoom, 6f)
        if (abs(target - old) < 0.001f) return
        fitPageActive = isTabletLandscape() && abs(target - minZoom) < 0.005f
        if (mode == LayoutMode.VERTICAL) {
            val contentY = scrollY + focusY
            val page = pageAtContentY(contentY)
            val top = pageTops.getOrElse(page) { 0f }
            val frac = ((contentY - top) / pageHeights.getOrElse(page) { 1f }).coerceIn(0f, 1f)
            val oldRect = pageRect(page)
            val nx = if (oldRect.width() > 0) (focusX - oldRect.left) / oldRect.width() else 0.5f
            zoom = target
            recomputeLayout(preserveScroll = true, requestRenders = false)
            val newRect = pageRect(page)
            currentPage = page
            scrollY = pageTops[page] + frac * pageHeights[page] - focusY
            panX += (focusX - nx * newRect.width()) - newRect.left
            clampVertical()
        } else {
            val page = currentPage
            val oldRect = pageRect(page)
            val nx = if (oldRect.width() > 0) (focusX - oldRect.left) / oldRect.width() else 0.5f
            val ny = if (oldRect.height() > 0) (focusY - oldRect.top) / oldRect.height() else 0.5f
            zoom = target
            val newRect = pageRect(page)
            hPanX += focusX - (newRect.left + nx * newRect.width())
            hPanY += focusY - (newRect.top + ny * newRect.height())
            clampHorizontal()
        }
        if (old != target) invalidate()
        // Rendering every intermediate scale step would queue a series of large
        // bitmaps on the single PDF thread. Use the cached page while pinching and
        // request the final sharp bitmap once the gesture ends.
        if (!pinching) requestVisibleRenders()
    }

    private fun pageAtContentY(y: Float): Int {
        if (pageTops.isEmpty()) return 0
        var result = 0
        for (i in pageTops.indices) {
            if (pageTops[i] <= y) result = i else break
        }
        return result
    }

    private fun pageAt(x: Float, y: Float): Int? {
        if (mode == LayoutMode.HORIZONTAL) return currentPage
        for (i in geometries.indices) {
            val rect = pageRect(i)
            if (y >= rect.top && y <= rect.bottom) return i
        }
        return null
    }

    private fun visiblePages(): List<Int> {
        if (!layoutReady || geometries.isEmpty()) return emptyList()
        if (mode == LayoutMode.HORIZONTAL) return listOf(currentPage)
        return geometries.indices.filter { index ->
            val top = pageTops[index] - scrollY
            val bottom = top + pageHeights[index]
            bottom >= 0f && top <= height
        }
    }

    private fun requestRender(pageIndex: Int, targetWidth: Int) {
        val eng = engine ?: return
        val geometry = geometries.getOrNull(pageIndex) ?: return
        val bucket = renderBucket(targetWidth, geometry)
        val targetHeight = (bucket * geometry.displayHeight / geometry.displayWidth).roundToInt().coerceAtLeast(64)
        val key = "$pageIndex@$bucket"
        if (bitmapCache.get(key) != null || renderJobs.containsKey(key)) return
        val job = renderScope.launch {
            try {
                val bitmap = eng.renderPage(pageIndex, bucket, targetHeight, renderAnnotations = true)
                if (bitmap != null && isActive) {
                    bitmapCache.put(key, bitmap)
                    invalidate()
                } else {
                    bitmap?.recycle()
                }
            } finally {
                renderJobs.remove(key)
            }
        }
        renderJobs[key] = job
    }

    private fun renderCap(geometry: PageGeometry): Int {
        // Keep both dimensions inside PDFium's 4096 px limit without changing
        // the page aspect ratio. A4 portrait therefore tops out around 2880 px,
        // which is enough for a 2880 px-wide tablet in landscape.
        val byHeight = (4096f * geometry.displayWidth / geometry.displayHeight).toInt()
        return min(4096, byHeight).coerceAtLeast(320)
    }

    private fun renderBucket(targetWidth: Int, geometry: PageGeometry): Int {
        val capped = targetWidth.coerceIn(320, renderCap(geometry))
        return ((capped / 96).coerceAtLeast(1)) * 96
    }

    private fun requestedWidth(rectWidth: Float, geometry: PageGeometry): Int =
        (rectWidth * 1.2f).roundToInt().coerceIn(320, renderCap(geometry))

    private fun previewWidth(needed: Int): Int =
        min(needed, max(1080, (width * 0.5f).roundToInt()))

    private fun cachedBitmap(pageIndex: Int, bucket: Int): Bitmap? {
        bitmapCache.get("$pageIndex@$bucket")?.let { return it }
        // A lower-resolution bitmap is still much better than an empty page while
        // the sharper tile is rendering after a zoom change.
        return bitmapCache.snapshot().entries
            .asSequence()
            .filter { it.key.startsWith("$pageIndex@") }
            .maxByOrNull { it.key.substringAfter('@').toIntOrNull() ?: 0 }
            ?.value
    }

    private fun requestVisibleRenders() {
        if (!layoutReady) return
        val visible = visiblePages()
        val anchor = visible.lastOrNull() ?: currentPage
        val keepFrom = (anchor - 2).coerceAtLeast(0)
        val keepTo = (anchor + 3).coerceAtMost(geometries.lastIndex)
        cancelStaleRenders((visible + (keepFrom..keepTo)).toSet())
        visible.forEach { page ->
            val rect = pageRect(page)
            val geometry = geometries.getOrNull(page) ?: return@forEach
            val needed = requestedWidth(rect.width(), geometry)
            val preview = previewWidth(needed)
            if (needed > 1600 && renderBucket(preview, geometry) != renderBucket(needed, geometry)) {
                requestRender(page, preview)
            }
            requestRender(page, needed)
        }
        prefetchAround(anchor, visible.toSet())
    }

    private fun cancelStaleRenders(keepPages: Set<Int>) {
        val iterator = renderJobs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val page = entry.key.substringBefore('@').toIntOrNull()
            val bucket = entry.key.substringAfter('@', "").toIntOrNull()
            val geometry = page?.let { geometries.getOrNull(it) }
            val currentBucket = if (geometry != null) {
                val needed = requestedWidth(displayWidth(geometry), geometry)
                val preview = previewWidth(needed)
                renderBucket(needed, geometry) to renderBucket(preview, geometry)
            } else {
                null
            }
            val obsoleteBucket = currentBucket != null && bucket != null &&
                bucket != currentBucket.first && bucket != currentBucket.second
            if (page != null && (page !in keepPages || obsoleteBucket)) {
                entry.value.cancel()
                iterator.remove()
            }
        }
    }

    private fun bytesFor(bucket: Int, geometry: PageGeometry): Int {
        val h = (bucket * geometry.displayHeight / geometry.displayWidth).roundToInt().coerceAtLeast(1)
        return bucket * h * 4
    }

    private fun prefetchAround(anchor: Int, visible: Set<Int>) {
        // One PDFium thread renders in order. The current page is queued first
        // by requestVisibleRenders; these pages are the next ready-to-show
        // candidates. Two full pages ahead are normally enough to make a page
        // turn instantaneous, while the memory check prevents evicting the
        // visible page on a high-resolution tablet.
        val candidates = listOf(anchor + 1, anchor + 2, anchor - 1, anchor + 3)
        var reservedBytes = 0
        candidates.forEach { page ->
            if (page !in geometries.indices || page in visible) return@forEach
            val geometry = geometries[page]
            val needed = requestedWidth(displayWidth(geometry), geometry)
            val target = renderBucket(needed, geometry)
            if (bitmapCache.get("$page@$target") != null) return@forEach
            val preview = previewWidth(needed)
            val previewBucket = renderBucket(preview, geometry)
            val available = bitmapBudgetBytes - bitmapCache.size() - reservedBytes
            val targetBytes = bytesFor(target, geometry)
            if (targetBytes <= available * 0.88f) {
                requestRender(page, needed)
                reservedBytes += targetBytes
            } else if (previewBucket != target) {
                val previewBytes = bytesFor(previewBucket, geometry)
                if (previewBytes <= available * 0.88f) {
                    requestRender(page, preview)
                    reservedBytes += previewBytes
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val page = if (layoutReady) currentPage else pendingPage
        val frac = if (layoutReady) currentOffsetFraction() else pendingFraction
        val newFit = pageFitZoom(page, w, h)
        val enteringLandscape = oldw <= oldh && w > h
        if (isTablet && w > h && (fitPageActive || enteringLandscape)) {
            zoom = newFit
            fitPageActive = true
        } else if (w <= h && zoom < 1f) {
            zoom = 1f
            fitPageActive = false
        }
        recomputeLayout(requestRenders = false)
        if (layoutReady) goToPage(page, frac)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (colorMode == ColorMode.NIGHT) Color.rgb(18, 18, 18) else Color.rgb(46, 47, 45))
        if (geometries.isEmpty() || !layoutReady) {
            drawCenteredHint(canvas, "正在打开 PDF…")
            return
        }
        val visible = visiblePages().ifEmpty { listOf(currentPage) }
        bitmapPaint.colorFilter = when (colorMode) {
            ColorMode.NIGHT -> nightFilter
            ColorMode.SEPIA -> sepiaFilter
            ColorMode.DAY -> null
        }
        visible.forEach { pageIndex ->
            val rect = pageRect(pageIndex)
            if (rect.width() <= 0f) return@forEach
            canvas.drawRect(
                RectF(rect.left + 3f * density, rect.top + 5f * density, rect.right + 3f * density, rect.bottom + 5f * density),
                shadowPaint,
            )
            val geometry = geometries.getOrNull(pageIndex) ?: return@forEach
            val needed = requestedWidth(rect.width(), geometry)
            val target = renderBucket(needed, geometry)
            val fitBucket = renderBucket(width.coerceAtLeast(1), geometry)
            val bitmap = cachedBitmap(pageIndex, target)
                ?: bitmapCache.get("$pageIndex@$fitBucket")
            if (bitmap == null || bitmap.width < target) {
                // The page may already have a low-resolution preview (for example
                // from prefetch or the previous fling). Always ask for the exact
                // bucket so text becomes sharp without waiting for a tap.
                val preview = previewWidth(needed)
                if (needed > 1600 && renderBucket(preview, geometry) != target) {
                    requestRender(pageIndex, preview)
                }
                requestRender(pageIndex, needed)
            }
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
            } else {
                pagePaint.color = Color.WHITE
                canvas.drawRect(rect, pagePaint)
                drawCenteredHint(canvas, "载入中…", rect)
            }
            drawPageAnnotations(canvas, pageIndex, rect)
            drawSelection(canvas, pageIndex, rect)
            drawSearchMark(canvas, pageIndex, rect)
            if (penDrawing && penPage == pageIndex && penPoints.isNotEmpty()) {
                drawPenPreview(canvas, rect)
            }
        }
        val first = visible.firstOrNull() ?: currentPage
        val last = visible.lastOrNull() ?: currentPage
        requestRender((first - 1).coerceAtLeast(0), (width * 0.9f).roundToInt().coerceIn(320, 1200))
        requestRender((last + 1).coerceAtMost(geometries.lastIndex), (width * 0.9f).roundToInt().coerceIn(320, 1200))
    }

    private fun drawCenteredHint(canvas: Canvas, text: String, rect: RectF? = null) {
        textPaint.color = if (colorMode == ColorMode.NIGHT) Color.LTGRAY else Color.DKGRAY
        val cx = rect?.centerX() ?: (width / 2f)
        val cy = rect?.centerY() ?: (height / 2f)
        canvas.drawText(text, cx - textPaint.measureText(text) / 2f, cy, textPaint)
    }

    private fun drawPageAnnotations(canvas: Canvas, pageIndex: Int, pageRect: RectF) {
        val items = annotationsByPage[pageIndex] ?: return
        items.forEach { record ->
            when (record.type) {
                AnnoType.HIGHLIGHT, AnnoType.NOTE -> {
                    annotationPaint.style = Paint.Style.FILL
                    annotationPaint.color = withAlpha(record.colorArgb, 0x66)
                    record.rects.forEach { n ->
                        val r = toViewRect(n, pageRect)
                        canvas.drawRoundRect(r, 3f * density, 3f * density, annotationPaint)
                    }
                    if (record.type == AnnoType.NOTE) drawNoteIcon(canvas, record, pageRect)
                }

                AnnoType.UNDERLINE -> {
                    annotationPaint.style = Paint.Style.STROKE
                    annotationPaint.strokeWidth = 2f * density
                    annotationPaint.color = record.colorArgb
                    record.rects.forEach { n ->
                        val r = toViewRect(n, pageRect)
                        canvas.drawLine(r.left, r.bottom, r.right, r.bottom, annotationPaint)
                    }
                }

                AnnoType.STRIKEOUT -> {
                    annotationPaint.style = Paint.Style.STROKE
                    annotationPaint.strokeWidth = 2f * density
                    annotationPaint.color = record.colorArgb
                    record.rects.forEach { n ->
                        val r = toViewRect(n, pageRect)
                        canvas.drawLine(r.left, r.centerY(), r.right, r.centerY(), annotationPaint)
                    }
                }

                AnnoType.SQUARE -> {
                    annotationPaint.style = Paint.Style.STROKE
                    annotationPaint.strokeWidth = 2f * density
                    annotationPaint.color = record.colorArgb
                    record.rects.forEach { n -> canvas.drawRect(toViewRect(n, pageRect), annotationPaint) }
                }

                AnnoType.TEXTBOX -> {
                    val r = record.rects.firstOrNull()?.let { toViewRect(it, pageRect) } ?: return@forEach
                    annotationPaint.style = Paint.Style.FILL
                    annotationPaint.color = withAlpha(record.colorArgb, 0x2E)
                    canvas.drawRoundRect(r, 4f * density, 4f * density, annotationPaint)
                    annotationPaint.style = Paint.Style.STROKE
                    annotationPaint.strokeWidth = 1.4f * density
                    annotationPaint.color = withAlpha(record.colorArgb, 0xCC)
                    canvas.drawRoundRect(r, 4f * density, 4f * density, annotationPaint)
                    drawWrappedText(
                        canvas,
                        record.note,
                        r.left + 4f * density,
                        r.top + 4f * density,
                        r.width() - 8f * density,
                        max(9f, min(16f * scaledDensity, r.height() * 0.32f)),
                        if (colorMode == ColorMode.NIGHT) Color.WHITE else Color.BLACK,
                    )
                }

                AnnoType.INK -> {
                    val strokes = inkCache[record.id] ?: parseInk(record.extra)
                    val widthFraction = runCatching { JSONObject(record.extra).optDouble("width", 0.003).toFloat() }
                        .getOrDefault(0.003f)
                    inkPaint.color = record.colorArgb
                    inkPaint.strokeWidth = (widthFraction * pageRect.width()).coerceAtLeast(1f)
                    strokes.forEach { stroke ->
                        if (stroke.size < 2) return@forEach
                        val path = Path()
                        stroke.forEachIndexed { i, p ->
                            val x = pageRect.left + p.x * pageRect.width()
                            val y = pageRect.top + p.y * pageRect.height()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        canvas.drawPath(path, inkPaint)
                    }
                }
            }
        }
    }

    private fun drawNoteIcon(canvas: Canvas, record: AnnotationRecord, pageRect: RectF) {
        val last = record.rects.lastOrNull() ?: return
        val r = toViewRect(last, pageRect)
        val cx = r.right - noteIconRadius * 0.2f
        val cy = r.top + noteIconRadius * 0.2f
        annotationPaint.style = Paint.Style.FILL
        annotationPaint.color = record.colorArgb
        canvas.drawCircle(cx, cy, noteIconRadius, annotationPaint)
        annotationPaint.color = Color.WHITE
        val w = noteIconRadius * 0.9f
        canvas.drawRoundRect(
            RectF(cx - w * 0.45f, cy - w * 0.4f, cx + w * 0.45f, cy + w * 0.45f),
            1.5f * density,
            1.5f * density,
            annotationPaint,
        )
        annotationPaint.color = record.colorArgb
        canvas.drawRect(cx - w * 0.25f, cy - w * 0.12f, cx + w * 0.25f, cy - w * 0.02f, annotationPaint)
        canvas.drawRect(cx - w * 0.25f, cy + w * 0.08f, cx + w * 0.05f, cy + w * 0.18f, annotationPaint)
    }

    private fun drawSelection(canvas: Canvas, pageIndex: Int, pageRect: RectF) {
        val sel = selection ?: return
        if (sel.pageIndex != pageIndex) return
        sel.rects.forEach { canvas.drawRect(toViewRect(it, pageRect), selectionPaint) }
        val first = sel.rects.firstOrNull() ?: return
        val last = sel.rects.lastOrNull() ?: return
        drawHandle(canvas, toViewRect(first, pageRect).left, toViewRect(first, pageRect).bottom)
        drawHandle(canvas, toViewRect(last, pageRect).right, toViewRect(last, pageRect).bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
        canvas.drawCircle(x, y, handleRadius - 1.5f * density, handleStroke)
    }

    private fun drawSearchMark(canvas: Canvas, pageIndex: Int, pageRect: RectF) {
        val mark = searchMark ?: return
        if (mark.pageIndex != pageIndex) return
        mark.rects.forEach { canvas.drawRect(toViewRect(it, pageRect), searchPaint) }
    }

    private fun drawPenPreview(canvas: Canvas, pageRect: RectF) {
        inkPaint.color = Color.rgb(210, 60, 60)
        inkPaint.strokeWidth = 3f * density
        val path = Path()
        penPoints.forEachIndexed { i, p ->
            val x = pageRect.left + p.x * pageRect.width()
            val y = pageRect.top + p.y * pageRect.height()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, inkPaint)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        size: Float,
        color: Int,
    ) {
        if (text.isBlank() || maxWidth <= 0f) return
        textPaint.color = color
        textPaint.textSize = size
        val lineHeight = size * 1.32f
        var cursorY = y + size
        text.split('\n').forEach { paragraph ->
            var line = StringBuilder()
            paragraph.forEach { ch ->
                val candidate = line.toString() + ch
                if (textPaint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    canvas.drawText(line.toString(), x, cursorY, textPaint)
                    cursorY += lineHeight
                    line = StringBuilder().append(ch)
                } else {
                    line.append(ch)
                }
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, cursorY, textPaint)
                cursorY += lineHeight
            }
        }
    }

    private fun toViewRect(n: RectN, pageRect: RectF): RectF = RectF(
        pageRect.left + n.left * pageRect.width(),
        pageRect.top + n.top * pageRect.height(),
        pageRect.left + n.right * pageRect.width(),
        pageRect.top + n.bottom * pageRect.height(),
    )

    private fun normalize(rect: RectF, geometry: PageGeometry): RectN = RectN(
        (rect.left / geometry.displayWidth).coerceIn(0f, 1f),
        (rect.top / geometry.displayHeight).coerceIn(0f, 1f),
        (rect.right / geometry.displayWidth).coerceIn(0f, 1f),
        (rect.bottom / geometry.displayHeight).coerceIn(0f, 1f),
    ).normalized()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress && !pinching) gestureDetector.onTouchEvent(event)
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                pinching = false
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                panning = false
                if (selection != null) {
                    val handle = hitSelectionHandle(event.x, event.y)
                    if (handle != 0) {
                        selectionDrag = handle
                        return true
                    }
                }
                if (activeTool == ReaderTool.PEN) {
                    val page = pageAt(event.x, event.y) ?: return true
                    val rect = pageRect(page)
                    penPage = page
                    penPoints = mutableListOf(normalizePoint(event.x, event.y, rect))
                    penDrawing = true
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeTool == ReaderTool.PEN) {
                    // A second finger switches from ink to canvas navigation.
                    // Discard the unfinished stroke rather than committing a dot.
                    penDrawing = false
                    penPoints.clear()
                    twoFingerPan = true
                    invalidate()
                }
                pinching = true
                panning = false
                scroller.forceFinished(true)
                velocityTracker?.clear()
                lastX = centroidX(event)
                lastY = centroidY(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingerPan && event.pointerCount >= 2) {
                    val cx = centroidX(event)
                    val cy = centroidY(event)
                    handlePan(cx - lastX, cy - lastY)
                    lastX = cx
                    lastY = cy
                    return true
                }
                if (pinching || scaleDetector.isInProgress || event.pointerCount > 1) return true
                if (selectionDrag != 0) {
                    updateSelectionDrag(event.x, event.y)
                    return true
                }
                if (penDrawing) {
                    val rect = pageRect(penPage)
                    val p = normalizePoint(event.x, event.y, rect)
                    if (penPoints.isEmpty() || hypot(p.x - penPoints.last().x, p.y - penPoints.last().y) > 0.001f) {
                        penPoints.add(p)
                        invalidate()
                    }
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!panning && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    panning = true
                }
                if (panning) {
                    handlePan(dx, dy)
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_UP -> {
                pinching = false
                twoFingerPan = false
                if (selectionDrag != 0) {
                    selectionDrag = 0
                    return true
                }
                if (penDrawing) {
                    finishPen()
                    return true
                }
                if (panning) {
                    finishDrag(event)
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Keep panning disabled until every finger is lifted. Otherwise the
                // remaining finger would suddenly become the pan anchor and jerk the page.
                pinching = true
                panning = false
                twoFingerPan = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                penDrawing = false
                penPoints.clear()
                panning = false
                pinching = false
                twoFingerPan = false
                selectionDrag = 0
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }
        }
        return true
    }

    private fun centroidX(event: MotionEvent): Float {
        if (event.pointerCount <= 0) return event.x
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun centroidY(event: MotionEvent): Float {
        if (event.pointerCount <= 0) return event.y
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun normalizePoint(x: Float, y: Float, rect: RectF): PointF = PointF(
        ((x - rect.left) / rect.width()).coerceIn(0f, 1f),
        ((y - rect.top) / rect.height()).coerceIn(0f, 1f),
    )

    private fun handlePan(dx: Float, dy: Float) {
        if (mode == LayoutMode.VERTICAL) {
            scrollY -= dy
            if (zoom > 1.01f) panX += dx
            clampVertical()
        } else {
            if (zoom > 1.01f) {
                hPanX += dx
                hPanY += dy
                clampHorizontal()
            } else {
                hOffset += dx
                val maxOffset = width * 0.9f
                val atFirst = currentPage == 0
                val atLast = currentPage == geometries.lastIndex
                hOffset = when {
                    hOffset > 0 && atFirst -> hOffset * 0.25f
                    hOffset < 0 && atLast -> hOffset * 0.25f
                    else -> hOffset.coerceIn(-maxOffset, maxOffset)
                }
            }
        }
        invalidate()
        notifyPosition()
    }

    private fun finishDrag(event: MotionEvent) {
        val velocity = velocityTracker?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity to it.yVelocity
        } ?: (0f to 0f)
        if (mode == LayoutMode.VERTICAL) {
            if (abs(velocity.second) > 600f) {
                val maxScroll = max(0f, totalHeight - height).toInt()
                scroller.fling(0, scrollY.toInt(), 0, -velocity.second.toInt(), 0, 0, 0, maxScroll)
                postInvalidateOnAnimation()
            }
            requestVisibleRenders()
        } else if (zoom <= 1.01f) {
            val threshold = width * 0.22f
            val fling = abs(velocity.first) > 700f
            if (hOffset < -threshold || (fling && velocity.first < 0)) {
                if (currentPage < geometries.lastIndex) {
                    currentPage++
                    hOffset = 0f
                } else {
                    hOffset = 0f
                }
            } else if (hOffset > threshold || (fling && velocity.first > 0)) {
                if (currentPage > 0) {
                    currentPage--
                    hOffset = 0f
                } else {
                    hOffset = 0f
                }
            } else {
                hOffset = 0f
            }
            invalidate()
            notifyPosition(force = true)
            requestVisibleRenders()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            clampVertical()
            invalidate()
            notifyPosition()
            postInvalidateOnAnimation()
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (selection != null) {
            clearSelection()
            return
        }
        val page = pageAt(x, y)
        if (page != null && activeTool == ReaderTool.ERASER) {
            val hit = hitAnnotation(page, x, y)
            if (hit != null) listener?.onDeleteAnnotation(hit)
            return
        }
        if (page != null && activeTool == ReaderTool.TEXTBOX) {
            val rect = pageRect(page)
            val left = ((x - rect.left) / rect.width()).coerceIn(0.02f, 0.55f)
            val top = ((y - rect.top) / rect.height()).coerceIn(0.02f, 0.9f)
            val normalized = RectN(left, top, (left + 0.42f).coerceAtMost(0.98f), (top + 0.075f).coerceAtMost(0.98f))
            activeTool = ReaderTool.NONE
            listener?.onRequestTextBox(page, normalized)
            return
        }
        if (page != null) {
            val hit = hitAnnotation(page, x, y)
            if (hit != null) {
                listener?.onAnnotationTap(hit)
                return
            }
        }
        val ratio = x / width.coerceAtLeast(1)
        when {
            ratio < 0.16f -> {
                if (mode == LayoutMode.HORIZONTAL) {
                    if (currentPage > 0) goToPage(currentPage - 1)
                } else {
                    goToPage((currentPage - 1).coerceAtLeast(0))
                }
            }
            ratio > 0.84f -> {
                if (mode == LayoutMode.HORIZONTAL) {
                    if (currentPage < geometries.lastIndex) goToPage(currentPage + 1)
                } else {
                    goToPage((currentPage + 1).coerceAtMost(geometries.lastIndex))
                }
            }
            else -> listener?.onChromeToggle()
        }
    }

    private fun hitSelectionHandle(x: Float, y: Float): Int {
        val sel = selection ?: return 0
        val rect = pageRect(sel.pageIndex)
        val first = sel.rects.firstOrNull()?.let { toViewRect(it, rect) } ?: return 0
        val last = sel.rects.lastOrNull()?.let { toViewRect(it, rect) } ?: return 0
        if (hypot(x - first.left, y - first.bottom) <= handleRadius * 1.8f) return 1
        if (hypot(x - last.right, y - last.bottom) <= handleRadius * 1.8f) return 2
        return 0
    }

    private fun beginSelection(x: Float, y: Float) {
        val page = pageAt(x, y) ?: return
        val eng = engine ?: return
        val rect = pageRect(page)
        val gx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f) * geometries[page].displayWidth
        val gy = ((y - rect.top) / rect.height()).coerceIn(0f, 1f) * geometries[page].displayHeight
        selectionJob?.cancel()
        selectionJob = renderScope.launch {
            val text = eng.pageText(page)
            if (text.isBlank()) {
                listener?.onMessage("该页面没有可搜索的文本层，仅支持视觉标注")
                return@launch
            }
            val char = eng.charIndexAt(page, gx, gy)
            if (char < 0) {
                listener?.onMessage("这里没有可选择的文字")
                return@launch
            }
            val range = eng.wordRangeAt(page, char)
            if (range.isEmpty()) return@launch
            val rects = eng.rangeRects(page, range.first, range.last + 1)
            if (rects.isEmpty()) return@launch
            val geometry = geometries[page]
            val normalized = rects.map { normalize(it, geometry) }
            val quote = text.substring(range.first, (range.last + 1).coerceAtMost(text.length))
            selection = TextSelection(page, range.first, range.last + 1, quote, normalized)
            selectionAnchorStart = range.first
            selectionAnchorEnd = range.last + 1
            listener?.onSelectionChanged(selection?.info())
            invalidate()
        }
    }

    private fun updateSelectionDrag(x: Float, y: Float) {
        val sel = selection ?: return
        val eng = engine ?: return
        val rect = pageRect(sel.pageIndex)
        if (x < rect.left - width || x > rect.right + width) return
        val gx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f) * geometries[sel.pageIndex].displayWidth
        val gy = ((y - rect.top) / rect.height()).coerceIn(0f, 1f) * geometries[sel.pageIndex].displayHeight
        selectionJob?.cancel()
        selectionJob = renderScope.launch {
            val char = eng.charIndexAt(sel.pageIndex, gx, gy)
            if (char < 0) return@launch
            val start: Int
            val end: Int
            if (selectionDrag == 1) {
                start = min(char, sel.endExclusive - 1)
                end = sel.endExclusive
            } else {
                start = sel.start
                end = max(char + 1, sel.start + 1)
            }
            val rects = eng.rangeRects(sel.pageIndex, start, end)
            if (rects.isEmpty()) return@launch
            val text = eng.pageText(sel.pageIndex)
            val geometry = geometries[sel.pageIndex]
            val newSelection = TextSelection(
                sel.pageIndex,
                start,
                end,
                text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length)),
                rects.map { normalize(it, geometry) },
            )
            selection = newSelection
            listener?.onSelectionChanged(newSelection.info())
            invalidate()
        }
    }

    private fun finishPen() {
        penDrawing = false
        if (penPoints.size < 2) {
            penPoints.clear()
            invalidate()
            return
        }
        val geometry = geometries.getOrNull(penPage) ?: return
        val widthFraction = (3f * density) / displayWidth(geometry).coerceAtLeast(1f)
        val strokes = JSONArray().apply {
            put(JSONArray().apply {
                penPoints.forEach { p ->
                    put(JSONArray().apply {
                        put(p.x.toDouble())
                        put(p.y.toDouble())
                    })
                }
            })
        }
        val extra = JSONObject().apply {
            put("strokes", strokes)
            put("width", widthFraction.toDouble())
        }.toString()
        val minX = penPoints.minOf { it.x }
        val maxX = penPoints.maxOf { it.x }
        val minY = penPoints.minOf { it.y }
        val maxY = penPoints.maxOf { it.y }
        listener?.onCreateInk(
            penPage,
            extra,
            RectN(minX, minY, maxX, maxY).normalized(),
        )
        penPoints.clear()
        invalidate()
    }

    private fun hitAnnotation(pageIndex: Int, x: Float, y: Float): AnnotationRecord? {
        val page = pageRect(pageIndex)
        val items = annotationsByPage[pageIndex] ?: return null
        return items.lastOrNull { record ->
            record.rects.any { n ->
                val r = toViewRect(n, page)
                if (record.type == AnnoType.NOTE) {
                    val cx = r.right
                    val cy = r.top
                    hypot(x - cx, y - cy) <= noteIconRadius * 1.9f
                } else {
                    val hit = RectF(r)
                    hit.inset(-6f * density, -6f * density)
                    hit.contains(x, y)
                }
            }
        }
    }

    private fun notifyPosition(force: Boolean = false) {
        if (!layoutReady) return
        val page = if (mode == LayoutMode.VERTICAL) {
            pageAtContentY(scrollY + height * 0.35f)
        } else {
            currentPage
        }
        val fraction = currentOffsetFraction()
        val now = System.currentTimeMillis()
        if (page != currentPage) {
            currentPage = page
            listener?.onPageChanged(page)
        }
        if (force || now - lastNotifyAt > 180) {
            lastNotifyAt = now
            listener?.onPositionChanged(page, fraction, zoom, mode)
        }
    }

    private fun parseInk(extra: String): List<List<PointF>> {
        if (extra.isBlank()) return emptyList()
        val root = runCatching { JSONObject(extra) }.getOrNull() ?: return emptyList()
        val strokes = root.optJSONArray("strokes") ?: return emptyList()
        val result = mutableListOf<List<PointF>>()
        for (i in 0 until strokes.length()) {
            val points = strokes.optJSONArray(i) ?: continue
            val list = mutableListOf<PointF>()
            for (j in 0 until points.length()) {
                val p = points.optJSONArray(j) ?: continue
                list.add(PointF(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
            }
            if (list.isNotEmpty()) result.add(list)
        }
        return result
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderScope.cancel()
        bitmapCache.evictAll()
    }
}
