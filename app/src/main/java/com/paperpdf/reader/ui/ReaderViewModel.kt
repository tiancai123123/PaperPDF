package com.paperpdf.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paperpdf.reader.PaperPdfApp
import com.paperpdf.reader.data.AnnotationRecord
import com.paperpdf.reader.data.AnnoType
import com.paperpdf.reader.data.BookmarkRecord
import com.paperpdf.reader.data.DocumentRecord
import com.paperpdf.reader.data.LayoutMode
import com.paperpdf.reader.data.RectN
import com.paperpdf.reader.data.ReadingStatus
import com.paperpdf.reader.data.SearchHit
import com.paperpdf.reader.data.toJson
import com.paperpdf.reader.engine.OutlineEntry
import com.paperpdf.reader.engine.PageGeometry
import com.paperpdf.reader.engine.PasswordRequiredException
import com.paperpdf.reader.engine.PdfEngine
import com.paperpdf.reader.engine.PdfOpenException
import com.paperpdf.reader.engine.WrongPasswordException
import com.paperpdf.reader.export.PdfExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface OpenState {
    data object Loading : OpenState
    data object NeedPassword : OpenState
    data class Ready(
        val doc: DocumentRecord,
        val pageCount: Int,
        val geometries: List<PageGeometry>,
        val outline: List<OutlineEntry>,
        val initialPage: Int,
        val initialOffset: Float,
        val initialZoom: Float,
    ) : OpenState
    data class Failed(val message: String) : OpenState
}

data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val running: Boolean = false,
    val progress: Float = 0f,
    val activeIndex: Int = -1,
)

data class SearchJump(val pageIndex: Int, val start: Int, val endExclusive: Int)

class ReaderViewModel(
    private val app: PaperPdfApp,
    private val initialDocId: Long?,
    private val initialUri: String?,
    private val copyIfNeeded: Boolean,
) : ViewModel() {

    private val container = app.container
    private val _openState = MutableStateFlow<OpenState>(OpenState.Loading)
    val openState: StateFlow<OpenState> = _openState.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationRecord>>(emptyList())
    val annotations: StateFlow<List<AnnotationRecord>> = _annotations.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<BookmarkRecord>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkRecord>> = _bookmarks.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _currentZoom = MutableStateFlow(1f)
    val currentZoom: StateFlow<Float> = _currentZoom.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _searchJump = MutableStateFlow<SearchJump?>(null)
    val searchJump: StateFlow<SearchJump?> = _searchJump.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var engine: PdfEngine? = null
    private var record: DocumentRecord? = null
    private var importedUri: String? = initialUri
    private var importedHash: String? = null
    private var importedSize: Long = 0
    private var importedName: String? = null
    private var sessionPassword: String? = null
    private var pendingPage = 0
    private var pendingOffset = 0f
    private var pendingZoom = 1f
    private var pendingLayout = LayoutMode.VERTICAL
    private var saveJob: Job? = null
    private var searchJob: Job? = null

    fun currentEngine(): PdfEngine? = engine

    fun geometries(): List<PageGeometry> = (_openState.value as? OpenState.Ready)?.geometries.orEmpty()

    init {
        begin()
    }

    private fun begin() {
        viewModelScope.launch {
            try {
                val existing = initialDocId?.let { container.documents.get(it) }
                if (existing != null) {
                    record = existing
                    importedUri = existing.uri
                    importedHash = existing.hash
                    importedSize = existing.size
                    importedName = existing.displayName
                    pendingPage = existing.lastPage
                    pendingOffset = existing.pageOffset
                    pendingZoom = existing.zoom.coerceIn(0.2f, 6f)
                    pendingLayout = LayoutMode.from(existing.layoutMode)
                } else {
                    val uri = initialUri ?: error("缺少文件地址")
                    val file = container.documents.importPdf(Uri.parse(uri), copyIfNeeded)
                    importedUri = file.uriString
                    importedHash = file.hash
                    importedSize = file.size
                    importedName = file.displayName
                }
                openWithPassword(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _openState.value = OpenState.Failed(e.message ?: "打开失败")
            }
        }
    }

    fun retryWithPassword(password: String) {
        viewModelScope.launch {
            _passwordError.value = null
            _openState.value = OpenState.Loading
            openWithPassword(password)
        }
    }

    private suspend fun openWithPassword(password: String?) {
        val uri = importedUri ?: run {
            _openState.value = OpenState.Failed("文件地址丢失")
            return
        }
        try {
            val opened = withContext(Dispatchers.IO) {
                PdfEngine.open(app, uri, password)
            }
            engine?.close()
            engine = opened
            sessionPassword = password

            val doc = if (record == null) {
                val file = com.paperpdf.reader.data.ImportedFile(
                    uriString = uri,
                    hash = importedHash!!,
                    size = importedSize,
                    displayName = importedName ?: "document.pdf",
                )
                container.documents.saveDocument(
                    file,
                    opened.pageCount,
                    opened.title,
                    opened.author,
                )
            } else {
                record!!
            }
            record = doc
            if (doc.status == ReadingStatus.UNREAD.value) {
                container.documents.updateInfo(doc.id, doc.title, doc.author, doc.tags, ReadingStatus.READING.value)
                record = doc.copy(status = ReadingStatus.READING.value)
            }

            _annotations.value = container.annotations.list(doc.id)
            _bookmarks.value = container.bookmarks.list(doc.id)
            val outline = opened.outline()
            val initialPage = pendingPage.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
            _currentPage.value = initialPage
            _currentZoom.value = pendingZoom
            _openState.value = OpenState.Ready(
                doc = record!!,
                pageCount = opened.pageCount,
                geometries = opened.geometries,
                outline = outline,
                initialPage = initialPage,
                initialOffset = pendingOffset,
                initialZoom = pendingZoom,
            )
            generateCoverThumbnail()
        } catch (e: PasswordRequiredException) {
            _openState.value = OpenState.NeedPassword
        } catch (e: WrongPasswordException) {
            _openState.value = OpenState.NeedPassword
            _passwordError.value = "密码错误，请重试"
        } catch (e: PdfOpenException) {
            _openState.value = OpenState.Failed(e.message ?: "无法打开 PDF")
        } catch (e: Exception) {
            _openState.value = OpenState.Failed(e.message ?: "无法打开 PDF")
        }
    }

    fun onPositionChanged(page: Int, offset: Float, zoom: Float, layoutMode: LayoutMode) {
        pendingPage = page
        pendingOffset = offset
        pendingZoom = zoom
        pendingLayout = layoutMode
        if (_currentPage.value != page) _currentPage.value = page
        if (kotlin.math.abs(_currentZoom.value - zoom) > 0.01f) _currentZoom.value = zoom
        val id = record?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(650)
            container.documents.saveReadingState(id, page, offset, zoom, layoutMode.value)
        }
    }

    fun flushState() {
        val id = record?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            container.documents.saveReadingState(id, livePage, liveOffset, liveZoom, liveLayout.value)
        }
    }

    private var livePage = 0
    private var liveOffset = 0f
    private var liveZoom = 1f
    private var liveLayout = LayoutMode.VERTICAL

    fun rememberPosition(page: Int, offset: Float, zoom: Float, layout: LayoutMode) {
        livePage = page
        liveOffset = offset
        liveZoom = zoom
        liveLayout = layout
        onPositionChanged(page, offset, zoom, layout)
    }

    private fun generateCoverThumbnail() {
        val doc = record ?: return
        val eng = engine ?: return
        if (thumbnailFile(doc.hash, 0).exists()) return
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = runCatching { eng.renderPage(0, 320, 440) }.getOrNull() ?: return@launch
            runCatching {
                val file = thumbnailFile(doc.hash, 0)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            }
            bitmap.recycle()
        }
    }

    private fun thumbnailFile(hash: String, page: Int): File {
        val dir = File(app.filesDir, "thumbs").apply { mkdirs() }
        return File(dir, "${hash.take(24)}_$page.jpg")
    }

    suspend fun thumbnail(pageIndex: Int, widthPx: Int = 180): Bitmap? {
        val doc = record ?: return null
        val cached = thumbnailFile(doc.hash, pageIndex)
        if (cached.exists()) {
            return withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(cached.absolutePath)
            }
        }
        val eng = engine ?: return null
        val geometry = geometries().getOrNull(pageIndex) ?: return null
        val height = (widthPx * geometry.displayHeight / geometry.displayWidth).toInt().coerceAtLeast(32)
        val bitmap = eng.renderPage(pageIndex, widthPx, height) ?: return null
        withContext(Dispatchers.IO) {
            runCatching {
                FileOutputStream(cached).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            }
        }
        return bitmap
    }

    fun setCurrentPage(page: Int) {
        _currentPage.value = page
    }

    fun addAnnotation(
        type: String,
        page: Int,
        rects: List<RectN>,
        quote: String,
        startChar: Int,
        endChar: Int,
        color: Int,
        note: String = "",
        extra: String = "",
    ) {
        val doc = record ?: return
        viewModelScope.launch {
            val inserted = container.annotations.insert(
                AnnotationRecord(
                    docId = doc.id,
                    pageIndex = page,
                    type = type,
                    colorArgb = color,
                    rects = rects.map { it.normalized() },
                    quote = quote,
                    note = note,
                    startChar = startChar,
                    endChar = endChar,
                    extra = extra,
                ),
            )
            var current = inserted
            pushUndo(
                UndoEntry(
                    undo = {
                        container.annotations.delete(current.id)
                        refreshAnnotations()
                    },
                    redo = {
                        current = container.annotations.insert(current.copy(id = 0))
                        refreshAnnotations()
                    },
                ),
            )
            refreshAnnotations()
        }
    }

    fun updateAnnotation(record: AnnotationRecord, note: String? = null, color: Int? = null, tag: String? = null) {
        val before = _annotations.value.firstOrNull { it.id == record.id } ?: record
        val after = record.copy(
            note = note ?: record.note,
            colorArgb = color ?: record.colorArgb,
            tag = tag ?: record.tag,
            updatedAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            container.annotations.update(after)
            pushUndo(
                UndoEntry(
                    undo = {
                        container.annotations.update(before)
                        refreshAnnotations()
                    },
                    redo = {
                        container.annotations.update(after)
                        refreshAnnotations()
                    },
                ),
            )
            refreshAnnotations()
        }
    }

    fun deleteAnnotation(record: AnnotationRecord) {
        viewModelScope.launch {
            var current = record
            container.annotations.delete(record.id)
            _annotations.value = _annotations.value.filterNot { it.id == record.id }
            pushUndo(
                UndoEntry(
                    undo = {
                        current = container.annotations.insert(current.copy(id = 0))
                        refreshAnnotations()
                    },
                    redo = {
                        container.annotations.delete(current.id)
                        refreshAnnotations()
                    },
                ),
            )
        }
    }

    fun toggleBookmark(page: Int) {
        val existing = _bookmarks.value.firstOrNull { it.pageIndex == page }
        if (existing != null) deleteBookmark(existing) else addBookmark(page)
    }

    fun addBookmark(page: Int, label: String? = null) {
        val doc = record ?: return
        viewModelScope.launch {
            val inserted = container.bookmarks.insert(
                BookmarkRecord(
                    docId = doc.id,
                    pageIndex = page,
                    label = label?.takeIf { it.isNotBlank() } ?: "第 ${page + 1} 页",
                ),
            )
            var current = inserted
            pushUndo(
                UndoEntry(
                    undo = {
                        container.bookmarks.delete(current.id)
                        refreshBookmarks()
                    },
                    redo = {
                        current = container.bookmarks.insert(current.copy(id = 0))
                        refreshBookmarks()
                    },
                ),
            )
            refreshBookmarks()
        }
    }

    fun renameBookmark(record: BookmarkRecord, label: String) {
        val before = record
        val after = record.copy(label = label.ifBlank { "第 ${record.pageIndex + 1} 页" })
        viewModelScope.launch {
            container.bookmarks.update(after)
            pushUndo(
                UndoEntry(
                    undo = { container.bookmarks.update(before); refreshBookmarks() },
                    redo = { container.bookmarks.update(after); refreshBookmarks() },
                ),
            )
            refreshBookmarks()
        }
    }

    fun deleteBookmark(record: BookmarkRecord) {
        viewModelScope.launch {
            var current = record
            container.bookmarks.delete(record.id)
            refreshBookmarks()
            pushUndo(
                UndoEntry(
                    undo = {
                        current = container.bookmarks.insert(current.copy(id = 0))
                        refreshBookmarks()
                    },
                    redo = {
                        container.bookmarks.delete(current.id)
                        refreshBookmarks()
                    },
                ),
            )
        }
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            runCatching { entry.undo() }
            redoStack.addLast(entry)
            updateUndoFlags()
        }
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            runCatching { entry.redo() }
            undoStack.addLast(entry)
            updateUndoFlags()
        }
    }

    private fun pushUndo(entry: UndoEntry) {
        undoStack.addLast(entry)
        if (undoStack.size > 120) undoStack.removeFirst()
        redoStack.clear()
        updateUndoFlags()
    }

    private fun updateUndoFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private suspend fun refreshAnnotations() {
        val doc = record ?: return
        _annotations.value = container.annotations.list(doc.id)
    }

    private suspend fun refreshBookmarks() {
        val doc = record ?: return
        _bookmarks.value = container.bookmarks.list(doc.id)
    }

    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _search.value = SearchState()
            return
        }
        val eng = engine ?: return
        val total = engine?.pageCount ?: 0
        searchJob = viewModelScope.launch {
            val hits = mutableListOf<SearchHit>()
            _search.value = SearchState(query = trimmed, running = true)
            for (page in 0 until total) {
                if (!isActive) break
                val text = eng.pageText(page)
                if (text.isNotBlank()) {
                    var from = 0
                    while (from <= text.length - trimmed.length) {
                        val idx = text.indexOf(trimmed, from, ignoreCase = true)
                        if (idx < 0) break
                        val snippetStart = (idx - 34).coerceAtLeast(0)
                        val snippetEnd = (idx + trimmed.length + 46).coerceAtMost(text.length)
                        val snippet = text.substring(snippetStart, snippetEnd)
                            .replace('\n', ' ')
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        hits.add(SearchHit(page, idx, idx + trimmed.length, snippet))
                        if (hits.size >= 500) break
                        from = idx + trimmed.length.coerceAtLeast(1)
                    }
                }
                if (page % 3 == 0 || hits.size >= 500) {
                    _search.value = SearchState(
                        query = trimmed,
                        hits = hits.toList(),
                        running = true,
                        progress = (page + 1f) / total.coerceAtLeast(1),
                    )
                }
                if (hits.size >= 500) break
            }
            _search.value = SearchState(
                query = trimmed,
                hits = hits,
                running = false,
                progress = 1f,
                activeIndex = if (hits.isEmpty()) -1 else 0,
            )
            if (hits.isNotEmpty()) {
                _searchJump.value = SearchJump(hits[0].pageIndex, hits[0].charStart, hits[0].charEnd)
                _currentPage.value = hits[0].pageIndex
            }
        }
    }

    fun selectSearchHit(index: Int) {
        val state = _search.value
        val hit = state.hits.getOrNull(index) ?: return
        _search.value = state.copy(activeIndex = index)
        _searchJump.value = SearchJump(hit.pageIndex, hit.charStart, hit.charEnd)
        _currentPage.value = hit.pageIndex
    }

    fun nextSearchHit(delta: Int) {
        val state = _search.value
        if (state.hits.isEmpty()) return
        val next = (state.activeIndex + delta).coerceIn(0, state.hits.lastIndex)
        selectSearchHit(next)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    fun setToast(message: String?) {
        _toast.value = message
    }

    fun updateDocumentInfo(title: String, author: String, tags: String, status: Int) {
        val current = _openState.value as? OpenState.Ready ?: return
        viewModelScope.launch {
            container.documents.updateInfo(current.doc.id, title.ifBlank { null }, author.ifBlank { null }, tags, status)
            val updated = current.doc.copy(
                title = title.ifBlank { null },
                author = author.ifBlank { null },
                tags = tags,
                status = status,
            )
            record = updated
            _openState.value = current.copy(doc = updated)
            _toast.value = "文档信息已保存"
        }
    }

    suspend fun exportSidecar(context: Context, target: Uri): String = withContext(Dispatchers.IO) {
        val doc = record ?: error("文档尚未打开")
        val json = JSONObject().apply {
            put("schema", "paperpdf.sidecar.v1")
            put("documentHash", doc.hash)
            put("documentName", doc.effectiveTitle)
            put("pageCount", engine?.pageCount ?: doc.pageCount)
            put("exportedAt", System.currentTimeMillis())
            put("readingState", JSONObject().apply {
                put("page", livePage)
                put("offset", liveOffset.toDouble())
                put("zoom", liveZoom.toDouble())
                put("layout", liveLayout.name)
            })
            put("bookmarks", JSONArray().apply {
                _bookmarks.value.forEach { bm ->
                    put(JSONObject().apply {
                        put("pageIndex", bm.pageIndex)
                        put("label", bm.label)
                        put("createdAt", bm.createdAt)
                    })
                }
            })
            put("annotations", JSONArray().apply {
                _annotations.value.forEach { put(it.toJson()) }
            })
        }
        context.contentResolver.openOutputStream(target)?.use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("无法写入导出文件")
        target.toString()
    }

    suspend fun exportAnnotatedPdf(context: Context, target: Uri): String = withContext(Dispatchers.IO) {
        val doc = record ?: error("文档尚未打开")
        val source = doc.uri
        PdfExporter.exportAnnotated(
            context = context,
            sourceUri = source,
            password = sessionPassword,
            annotations = _annotations.value,
            geometries = geometries(),
            outputUri = target,
        )
        target.toString()
    }

    suspend fun exportMarkdown(context: Context, target: Uri): String = withContext(Dispatchers.IO) {
        val doc = record ?: error("文档尚未打开")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val sb = StringBuilder()
        sb.append("# ").append(doc.effectiveTitle).append("\n\n")
        sb.append("- 文件哈希：`").append(doc.hash).append("`\n")
        sb.append("- 页数：").append(engine?.pageCount ?: doc.pageCount).append("\n")
        sb.append("- 导出时间：").append(date).append("\n\n")
        _annotations.value.groupBy { it.pageIndex }.toSortedMap().forEach { (page, items) ->
            sb.append("## 第 ").append(page + 1).append(" 页\n\n")
            items.forEach { a ->
                sb.append("### ").append(AnnoType.label(a.type))
                if (a.quote.isNotBlank()) sb.append("：").append(a.quote.replace('\n', ' '))
                sb.append("\n\n")
                if (a.note.isNotBlank()) sb.append(a.note).append("\n\n")
                if (a.tag.isNotBlank()) sb.append("标签：").append(a.tag).append("\n\n")
            }
        }
        context.contentResolver.openOutputStream(target)?.use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        } ?: error("无法写入导出文件")
        target.toString()
    }

    override fun onCleared() {
        flushState()
        engine?.close()
        engine = null
        super.onCleared()
    }
}

private class UndoEntry(
    val undo: suspend () -> Unit,
    val redo: suspend () -> Unit,
)

class ReaderViewModelFactory(
    private val app: PaperPdfApp,
    private val docId: Long?,
    private val uri: String?,
    private val copyIfNeeded: Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderViewModel(app, docId, uri, copyIfNeeded) as T
}
