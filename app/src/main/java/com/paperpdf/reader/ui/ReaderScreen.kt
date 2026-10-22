package com.paperpdf.reader.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paperpdf.reader.PaperPdfApp
import com.paperpdf.reader.data.AnnotationRecord
import com.paperpdf.reader.data.AnnoType
import com.paperpdf.reader.data.BookmarkRecord
import com.paperpdf.reader.data.ColorMode
import com.paperpdf.reader.data.LayoutMode
import com.paperpdf.reader.data.PdfShare
import com.paperpdf.reader.data.ReaderSettings
import com.paperpdf.reader.data.RectN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private enum class ReaderSheet {
    NONE,
    TOC,
    SEARCH,
    ANNOTATIONS,
    BOOKMARKS,
    THUMBS,
    SETTINGS,
    EXPORT,
    INFO,
}

internal val highlightColors = listOf(
    0xFFFFD54F.toInt(),
    0xFFA5D6A7.toInt(),
    0xFF90CAF9.toInt(),
    0xFFF48FB1.toInt(),
    0xFFFFCC80.toInt(),
)

internal data class NoteTarget(
    val pageIndex: Int,
    val rects: List<RectN>,
    val quote: String,
    val start: Int,
    val endExclusive: Int,
    val existing: AnnotationRecord? = null,
)

internal data class TextBoxTarget(val pageIndex: Int, val rect: RectN)

@Composable
fun ReaderScreen(
    request: ReaderRequest,
    onBack: () -> Unit,
    onOpenAnotherWindow: () -> Unit,
    onDocumentTitleResolved: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperPdfApp
    val store = remember(request) { ViewModelStore() }
    val vm: ReaderViewModel = remember(request) {
        ViewModelProvider(
            store,
            ReaderViewModelFactory(app, request.docId, request.uri, request.copyIfNeeded),
        )[ReaderViewModel::class.java]
    }
    DisposableEffect(store) {
        onDispose {
            vm.flushState()
            store.clear()
        }
    }
    val openState by vm.openState.collectAsState()
    val passwordError by vm.passwordError.collectAsState()
    val settings by app.container.settings.settings.collectAsState()

    when (val state = openState) {
        OpenState.Loading -> OpeningScreen()
        OpenState.NeedPassword -> PasswordDialog(
            error = passwordError,
            onSubmit = vm::retryWithPassword,
            onBack = onBack,
        )

        is OpenState.Failed -> FailedScreen(state.message, onBack)
        is OpenState.Ready -> ReaderContent(
            vm = vm,
            ready = state,
            settings = settings,
            onSettingsChange = { transform -> app.container.settings.update(transform) },
            onBack = onBack,
            onOpenAnotherWindow = onOpenAnotherWindow,
            onDocumentTitleResolved = onDocumentTitleResolved,
        )
    }
}

@Composable
private fun OpeningScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text("正在解析 PDF…", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "首次打开会计算文件指纹，用于移动或重命名后继续匹配批注",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedScreen(message: String, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("打不开这份 PDF", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onBack) { Text("返回文档库") }
        }
    }
}

@Composable
private fun PasswordDialog(error: String?, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("这份 PDF 需要密码") },
        text = {
            Column {
                Text(
                    "密码只用于本次打开，不会写入数据库或日志。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (password.isNotEmpty()) onSubmit(password) }) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("返回") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    vm: ReaderViewModel,
    ready: OpenState.Ready,
    settings: ReaderSettings,
    onSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onBack: () -> Unit,
    onOpenAnotherWindow: () -> Unit,
    onDocumentTitleResolved: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val annotations by vm.annotations.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val currentPage by vm.currentPage.collectAsState()
    val currentZoom by vm.currentZoom.collectAsState()
    val search by vm.search.collectAsState()
    val searchJump by vm.searchJump.collectAsState()
    val toast by vm.toast.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()

    var readerView by remember { mutableStateOf<PdfReaderView?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    var selection by remember { mutableStateOf<SelectionInfo?>(null) }
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }
    var activeTool by remember { mutableStateOf(ReaderTool.NONE) }
    var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }
    var textTarget by remember { mutableStateOf<TextBoxTarget?>(null) }
    var editingAnnotation by remember { mutableStateOf<AnnotationRecord?>(null) }
    var infoVisible by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }
    val lastPage = (ready.pageCount - 1).coerceAtLeast(0)

    val shareDocument = {
        runCatching {
            PdfShare.share(context, ready.doc.uri, ready.doc.effectiveTitle)
        }.onFailure {
            vm.setToast("分享失败：${it.message}")
        }
        Unit
    }

    LaunchedEffect(ready.doc.effectiveTitle) {
        onDocumentTitleResolved(ready.doc.effectiveTitle)
    }

    val listener = remember {
        object : PdfReaderView.Listener {
            override fun onPageChanged(pageIndex: Int) {
                vm.setCurrentPage(pageIndex)
            }

            override fun onPositionChanged(pageIndex: Int, offset: Float, zoom: Float, mode: LayoutMode) {
                vm.rememberPosition(pageIndex, offset, zoom, mode)
            }

            override fun onChromeToggle() {
                chromeVisible = !chromeVisible
            }

            override fun onSelectionChanged(info: SelectionInfo?) {
                selection = info
            }

            override fun onRequestTextBox(pageIndex: Int, rect: RectN) {
                activeTool = ReaderTool.NONE
                textTarget = TextBoxTarget(pageIndex, rect)
            }

            override fun onCreateInk(pageIndex: Int, extra: String, bounds: RectN) {
                vm.addAnnotation(
                    type = AnnoType.INK,
                    page = pageIndex,
                    rects = listOf(bounds),
                    quote = "",
                    startChar = -1,
                    endChar = -1,
                    color = 0xFF37474F.toInt(),
                    extra = extra,
                )
            }

            override fun onAnnotationTap(record: AnnotationRecord) {
                editingAnnotation = record
            }

            override fun onDeleteAnnotation(record: AnnotationRecord) {
                vm.deleteAnnotation(record)
                vm.setToast("已删除标注")
            }

            override fun onMessage(message: String) {
                vm.setToast(message)
            }

            override fun onZoomChanged(zoom: Float) = Unit
        }
    }

    LaunchedEffect(readerView, ready.doc.id) {
        val view = readerView ?: return@LaunchedEffect
        view.listener = listener
        view.setDocument(
            engine = vm.currentEngine() ?: return@LaunchedEffect,
            geometries = ready.geometries,
            page = ready.initialPage,
            fraction = ready.initialOffset,
            initialZoom = ready.initialZoom,
            layoutMode = settings.defaultLayout,
        )
        vm.rememberPosition(
            ready.initialPage,
            ready.initialOffset,
            ready.initialZoom,
            settings.defaultLayout,
        )
    }

    LaunchedEffect(annotations) {
        readerView?.setAnnotations(annotations)
    }

    LaunchedEffect(settings.colorMode, settings.zoomLocked) {
        readerView?.setColorMode(settings.colorMode)
        readerView?.setPinchEnabled(!settings.zoomLocked)
    }

    LaunchedEffect(settings.defaultLayout) {
        readerView?.setLayoutMode(settings.defaultLayout)
    }

    LaunchedEffect(currentPage) {
        sliderValue = currentPage.toFloat()
    }

    LaunchedEffect(searchJump) {
        val jump = searchJump ?: return@LaunchedEffect
        readerView?.showSearchHit(jump.pageIndex, jump.start, jump.endExclusive)
    }

    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.setToast(null)
    }

    DisposableEffect(Unit) {
        onDispose { vm.flushState() }
    }

    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(settings.brightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness ?: -1f
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = settings.brightness
            window.attributes = attrs
        }
        onDispose {
            if (window != null) {
                val attrs = window.attributes
                attrs.screenBrightness = previous
                window.attributes = attrs
            }
        }
    }

    LaunchedEffect(settings.orientation) {
        activity?.requestedOrientation = if (settings.orientation >= 0) {
            settings.orientation
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(chromeVisible) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (!chromeVisible && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler {
        when {
            noteTarget != null -> noteTarget = null
            textTarget != null -> textTarget = null
            editingAnnotation != null -> editingAnnotation = null
            sheet != ReaderSheet.NONE -> sheet = ReaderSheet.NONE
            selection != null -> readerView?.clearSelection()
            else -> onBack()
        }
    }

    val sidecarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            exportBusy = true
            scope.launch {
                readerView?.positionSnapshot()?.let {
                    vm.rememberPosition(it.pageIndex, it.offset, it.zoom, it.mode)
                }
                runCatching { vm.exportSidecar(context, uri) }
                    .onSuccess { vm.setToast("标注备份已导出") }
                    .onFailure { vm.setToast("导出失败：${it.message}") }
                exportBusy = false
                sheet = ReaderSheet.NONE
            }
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            exportBusy = true
            scope.launch {
                readerView?.positionSnapshot()?.let {
                    vm.rememberPosition(it.pageIndex, it.offset, it.zoom, it.mode)
                }
                runCatching { vm.exportAnnotatedPdf(context, uri) }
                    .onSuccess { vm.setToast("带批注 PDF 已导出，原文件未修改") }
                    .onFailure { vm.setToast("导出失败：${it.message}") }
                exportBusy = false
                sheet = ReaderSheet.NONE
            }
        }
    }
    val markdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) {
            exportBusy = true
            scope.launch {
                readerView?.positionSnapshot()?.let {
                    vm.rememberPosition(it.pageIndex, it.offset, it.zoom, it.mode)
                }
                runCatching { vm.exportMarkdown(context, uri) }
                    .onSuccess { vm.setToast("Markdown 笔记已导出") }
                    .onFailure { vm.setToast("导出失败：${it.message}") }
                exportBusy = false
                sheet = ReaderSheet.NONE
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (settings.colorMode == ColorMode.NIGHT) Color(0xFF121212) else Color(0xFF2E2F2D)),
    ) {
        AndroidView(
            factory = { ctx ->
                PdfReaderView(ctx).also {
                    it.listener = listener
                    readerView = it
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (chromeVisible) 80.dp else 0.dp,
                    bottom = if (chromeVisible) 116.dp else 0.dp,
                ),
            update = { view ->
                view.listener = listener
                readerView = view
            },
        )

        if (chromeVisible) {
            ReaderTopBar(
                title = ready.doc.effectiveTitle,
                bookmarked = bookmarks.any { it.pageIndex == currentPage },
                onBack = onBack,
                onToggleBookmark = {
                    val wasBookmarked = bookmarks.any { it.pageIndex == currentPage }
                    vm.toggleBookmark(currentPage)
                    vm.setToast(if (wasBookmarked) "已移除书签" else "已添加书签")
                },
                onSearch = { sheet = ReaderSheet.SEARCH },
                onToc = { sheet = ReaderSheet.TOC },
                onInfo = { infoVisible = true },
                onThumbnails = { sheet = ReaderSheet.THUMBS },
                onAnnotations = { sheet = ReaderSheet.ANNOTATIONS },
                onBookmarks = { sheet = ReaderSheet.BOOKMARKS },
                onExport = { sheet = ReaderSheet.EXPORT },
                onShare = shareDocument,
                onOpenAnotherWindow = onOpenAnotherWindow,
                onSettings = { sheet = ReaderSheet.SETTINGS },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (search.query.isNotBlank() && sheet != ReaderSheet.SEARCH) {
            SearchStatusBar(
                query = search.query,
                index = search.activeIndex,
                count = search.hits.size,
                onPrevious = { vm.nextSearchHit(-1) },
                onNext = { vm.nextSearchHit(1) },
                onClose = {
                    vm.clearSearch()
                    readerView?.clearSearchMark()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (chromeVisible) 148.dp else 20.dp),
            )
        }

        if (chromeVisible) {
            ReaderBottomBar(
                page = currentPage,
                lastPage = lastPage,
                zoom = currentZoom,
                sliderValue = sliderValue,
                onSliderChange = {
                    sliderValue = it
                    readerView?.goToPage(it.roundToInt())
                },
                onPrevious = { readerView?.goToPage((currentPage - 1).coerceAtLeast(0)) },
                onNext = { readerView?.goToPage((currentPage + 1).coerceAtMost(lastPage)) },
                annotationActive = activeTool != ReaderTool.NONE,
                onToggleAnnotation = {
                    if (activeTool == ReaderTool.NONE) {
                        activeTool = ReaderTool.PEN
                        readerView?.setTool(ReaderTool.PEN)
                    } else {
                        activeTool = ReaderTool.NONE
                        readerView?.setTool(ReaderTool.NONE)
                    }
                },
                onBookmarks = { sheet = ReaderSheet.BOOKMARKS },
                onSettings = { sheet = ReaderSheet.SETTINGS },
                onToc = { sheet = ReaderSheet.TOC },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (activeTool != ReaderTool.NONE) {
            AnnotationToolBar(
                activeTool = activeTool,
                canUndo = canUndo,
                canRedo = canRedo,
                onTool = { tool ->
                    activeTool = tool
                    readerView?.setTool(tool)
                },
                onUndo = vm::undo,
                onRedo = vm::redo,
                onClose = {
                    activeTool = ReaderTool.NONE
                    readerView?.setTool(ReaderTool.NONE)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (chromeVisible) 148.dp else 24.dp),
            )
        }

        selection?.let { info ->
            SelectionActionBar(
                info = info,
                onCopy = {
                    clipboard.setText(AnnotatedString(info.quote))
                    vm.setToast("已复制选中文字")
                    readerView?.clearSelection()
                },
                onAnnotate = { type, color, note ->
                    vm.addAnnotation(
                        type = type,
                        page = info.pageIndex,
                        rects = info.rects,
                        quote = info.quote,
                        startChar = info.start,
                        endChar = info.endExclusive,
                        color = color,
                        note = note,
                    )
                    readerView?.clearSelection()
                },
                onNote = {
                    noteTarget = NoteTarget(
                        info.pageIndex,
                        info.rects,
                        info.quote,
                        info.start,
                        info.endExclusive,
                    )
                },
                onSearch = {
                    val query = info.quote.take(80)
                    vm.search(query)
                    sheet = ReaderSheet.SEARCH
                },
                onClose = { readerView?.clearSelection() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (chromeVisible) 150.dp else 22.dp),
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (chromeVisible) 160.dp else 24.dp),
        )

        if (exportBusy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("正在导出…")
                    }
                }
            }
        }
    }

    when (sheet) {
        ReaderSheet.NONE -> Unit
        ReaderSheet.TOC -> TocSheet(
            outline = ready.outline,
            onSelect = { page ->
                readerView?.goToPage(page)
                sheet = ReaderSheet.NONE
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.SEARCH -> SearchSheet(
            search = search,
            initialQuery = selection?.quote?.take(80).orEmpty(),
            onSearch = { vm.search(it) },
            onSelect = { index, hit ->
                vm.selectSearchHit(index)
                readerView?.showSearchHit(hit.pageIndex, hit.charStart, hit.charEnd)
                readerView?.clearSelection()
                sheet = ReaderSheet.NONE
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.ANNOTATIONS -> AnnotationSheet(
            annotations = annotations,
            pageCount = ready.pageCount,
            onJump = { record ->
                readerView?.goToPage(record.pageIndex)
                sheet = ReaderSheet.NONE
            },
            onEdit = { editingAnnotation = it },
            onDelete = {
                vm.deleteAnnotation(it)
                vm.setToast("已删除标注")
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.BOOKMARKS -> BookmarkSheet(
            bookmarks = bookmarks,
            onJump = { bm ->
                readerView?.goToPage(bm.pageIndex)
                sheet = ReaderSheet.NONE
            },
            onRename = { bm, label -> vm.renameBookmark(bm, label) },
            onDelete = { vm.deleteBookmark(it) },
            onAdd = {
                vm.addBookmark(currentPage)
                vm.setToast("已添加书签")
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.THUMBS -> ThumbnailSheet(
            vm = vm,
            pageCount = ready.pageCount,
            currentPage = currentPage,
            onSelect = { page ->
                readerView?.goToPage(page)
                sheet = ReaderSheet.NONE
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.SETTINGS -> SettingsSheet(
            settings = settings,
            canUndo = canUndo,
            canRedo = canRedo,
            onSettings = onSettingsChange,
            onLayout = { layout ->
                onSettingsChange { it.copy(defaultLayout = layout) }
                readerView?.setLayoutMode(layout)
            },
            onUndo = vm::undo,
            onRedo = vm::redo,
            onResetZoom = {
                readerView?.resetZoom()
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.EXPORT -> ExportSheet(
            onSidecar = {
                sidecarLauncher.launch(exportFileName(ready.doc.effectiveTitle, "标注.json"))
            },
            onAnnotatedPdf = {
                pdfLauncher.launch(exportFileName(ready.doc.effectiveTitle, "批注.pdf"))
            },
            onMarkdown = {
                markdownLauncher.launch(exportFileName(ready.doc.effectiveTitle, "笔记.md"))
            },
            onDismiss = { sheet = ReaderSheet.NONE },
        )

        ReaderSheet.INFO -> Unit
    }

    if (infoVisible) {
        DocumentInfoDialog(
            doc = ready.doc,
            onDismiss = { infoVisible = false },
            onSave = { title, author, tags, status ->
                vm.updateDocumentInfo(title, author, tags, status)
                infoVisible = false
            },
        )
    }

    noteTarget?.let { target ->
        NoteEditorDialog(
            target = target,
            onSave = { note, color ->
                if (target.existing != null) {
                    vm.updateAnnotation(target.existing, note = note, color = color)
                } else {
                    vm.addAnnotation(
                        type = AnnoType.NOTE,
                        page = target.pageIndex,
                        rects = target.rects,
                        quote = target.quote,
                        startChar = target.start,
                        endChar = target.endExclusive,
                        color = color,
                        note = note,
                    )
                    readerView?.clearSelection()
                }
                noteTarget = null
            },
            onDelete = target.existing?.let { record ->
                {
                    vm.deleteAnnotation(record)
                    noteTarget = null
                }
            },
            onDismiss = { noteTarget = null },
        )
    }

    editingAnnotation?.let { record ->
        NoteEditorDialog(
            target = NoteTarget(
                pageIndex = record.pageIndex,
                rects = record.rects,
                quote = record.quote,
                start = record.startChar,
                endExclusive = record.endChar,
                existing = record,
            ),
            onSave = { note, color ->
                vm.updateAnnotation(record, note = note, color = color)
                editingAnnotation = null
            },
            onDelete = {
                vm.deleteAnnotation(record)
                editingAnnotation = null
            },
            onDismiss = { editingAnnotation = null },
        )
    }

    textTarget?.let { target ->
        TextBoxDialog(
            target = target,
            onSave = { text, color ->
                vm.addAnnotation(
                    type = AnnoType.TEXTBOX,
                    page = target.pageIndex,
                    rects = listOf(target.rect),
                    quote = "",
                    startChar = -1,
                    endChar = -1,
                    color = color,
                    note = text,
                )
                textTarget = null
            },
            onDismiss = { textTarget = null },
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSearch: () -> Unit,
    onToc: () -> Unit,
    onInfo: () -> Unit,
    onThumbnails: () -> Unit,
    onAnnotations: () -> Unit,
    onBookmarks: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onOpenAnotherWindow: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "书签",
                    tint = if (bookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onToc) {
                Icon(Icons.Default.List, contentDescription = "目录")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("文档信息") }, onClick = { menu = false; onInfo() })
                    DropdownMenuItem(text = { Text("缩略图") }, onClick = { menu = false; onThumbnails() })
                    DropdownMenuItem(text = { Text("全部标注") }, onClick = { menu = false; onAnnotations() })
                    DropdownMenuItem(text = { Text("全部书签") }, onClick = { menu = false; onBookmarks() })
                    DropdownMenuItem(text = { Text("导出") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("分享 PDF") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(
                        text = { Text("在新窗口打开另一个 PDF") },
                        onClick = { menu = false; onOpenAnotherWindow() },
                    )
                    DropdownMenuItem(text = { Text("阅读设置") }, onClick = { menu = false; onSettings() })
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    page: Int,
    lastPage: Int,
    zoom: Float,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    annotationActive: Boolean,
    onToggleAnnotation: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
    onToc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${page + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = sliderValue.coerceIn(0f, lastPage.toFloat()),
                    onValueChange = onSliderChange,
                    valueRange = 0f..lastPage.toFloat().coerceAtLeast(1f),
                    steps = max(0, lastPage - 1),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    "${lastPage + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (abs(zoom - 1f) > 0.05f) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${(zoom * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomAction("上一页", Icons.Default.ArrowBack, onPrevious)
                BottomAction(
                    if (annotationActive) "退出标注" else "标注",
                    Icons.Default.Edit,
                    onToggleAnnotation,
                )
                BottomAction("目录", Icons.Default.List, onToc)
                BottomAction("书签", Icons.Default.Star, onBookmarks)
                BottomAction("设置", Icons.Default.Settings, onSettings)
                BottomAction("下一页", Icons.Default.ArrowForward, onNext)
            }
        }
    }
}

@Composable
private fun BottomAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(21.dp))
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchStatusBar(
    query: String,
    index: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "“$query”",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (count == 0) "无结果" else "${index + 1}/$count",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onPrevious, enabled = count > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上一个")
            }
            IconButton(onClick = onNext, enabled = count > 0) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下一个")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "关闭搜索")
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    info: SelectionInfo,
    onCopy: () -> Unit,
    onAnnotate: (String, Int, String) -> Unit,
    onNote: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCopy) { Text("复制") }
                TextButton(onClick = { onAnnotate(AnnoType.HIGHLIGHT, highlightColors[0], "") }) { Text("高亮") }
                TextButton(onClick = { onAnnotate(AnnoType.UNDERLINE, highlightColors[0], "") }) { Text("下划线") }
                TextButton(onClick = { onAnnotate(AnnoType.STRIKEOUT, highlightColors[3], "") }) { Text("删除线") }
                TextButton(onClick = onNote) { Text("笔记") }
                TextButton(onClick = onSearch) { Text("搜索") }
                highlightColors.take(4).forEach { color ->
                    Box(
                        Modifier
                            .padding(horizontal = 5.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                            .clickable { onAnnotate(AnnoType.HIGHLIGHT, color, "") },
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            Text(
                info.quote.replace('\n', ' ').take(90),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnnotationToolBar(
    activeTool: ReaderTool,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (ReaderTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = activeTool == ReaderTool.PEN,
                        onClick = { onTool(ReaderTool.PEN) },
                        label = { Text("画笔") },
                    )
                    FilterChip(
                        selected = activeTool == ReaderTool.ERASER,
                        onClick = { onTool(ReaderTool.ERASER) },
                        label = { Text("橡皮擦") },
                    )
                    FilterChip(
                        selected = activeTool == ReaderTool.TEXTBOX,
                        onClick = { onTool(ReaderTool.TEXTBOX) },
                        label = { Text("文本框") },
                    )
                    TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销") }
                    TextButton(onClick = onRedo, enabled = canRedo) { Text("重做") }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "退出标注")
                }
            }
            Text(
                "单指书写 · 双指移动页面",
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun exportFileName(title: String, suffix: String): String {
    val base = title.removeSuffix(".pdf").removeSuffix(".PDF")
    val safe = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "document" }
    return "$safe-$suffix"
}
