package com.paperpdf.reader.ui

import android.content.Intent
import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paperpdf.reader.PaperPdfApp
import com.paperpdf.reader.data.DevicePdf
import com.paperpdf.reader.data.DeviceScanMode
import com.paperpdf.reader.data.DocumentRecord
import com.paperpdf.reader.data.PdfShare
import com.paperpdf.reader.data.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryViewModel(private val app: PaperPdfApp) : ViewModel() {
    private val _documents = MutableStateFlow<List<DocumentRecord>>(emptyList())
    val documents: StateFlow<List<DocumentRecord>> = _documents.asStateFlow()
    private var sort = "last_opened_at DESC"
    private val scanner = app.container.deviceScanner
    private val _deviceFiles = MutableStateFlow<List<DevicePdf>>(emptyList())
    val deviceFiles: StateFlow<List<DevicePdf>> = _deviceFiles.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _scanMode = MutableStateFlow(DeviceScanMode.NONE)
    val scanMode: StateFlow<DeviceScanMode> = _scanMode.asStateFlow()
    private val _scanNote = MutableStateFlow("")
    val scanNote: StateFlow<String> = _scanNote.asStateFlow()
    private val _hasAllFilesAccess = MutableStateFlow(scanner.hasAllFilesAccess())
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()
    private var lastScanAt = 0L

    init {
        scanner.cachedResult()?.let { cached ->
            _deviceFiles.value = cached.files
            _scanMode.value = cached.mode
            _scanNote.value = cached.note.ifBlank { "已扫描内部存储" } + " · 已加载缓存"
            lastScanAt = cached.scannedAt
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _documents.value = app.container.documents.list(sort)
        }
    }

    fun setSort(next: String) {
        sort = next
        refresh()
    }

    fun updateInfo(doc: DocumentRecord, title: String, author: String, tags: String, status: Int) {
        viewModelScope.launch {
            app.container.documents.updateInfo(doc.id, title.ifBlank { null }, author.ifBlank { null }, tags, status)
            refresh()
        }
    }

    fun delete(doc: DocumentRecord) {
        viewModelScope.launch {
            app.container.documents.delete(doc.id)
            refresh()
        }
    }

    fun refreshDeviceFiles() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _hasAllFilesAccess.value = scanner.hasAllFilesAccess()
            if (_hasAllFilesAccess.value) _scanMode.value = DeviceScanMode.ALL_FILES
            runCatching { scanner.scan() }
                .onSuccess { result ->
                    _deviceFiles.value = result.files
                    _scanMode.value = result.mode
                    _scanNote.value = result.note
                    scanner.saveCachedResult(result)
                    lastScanAt = System.currentTimeMillis()
                }
                .onFailure {
                    _scanNote.value = "扫描失败：${it.message}"
                }
            _scanning.value = false
        }
    }

    fun onResume(filesTabVisible: Boolean) {
        refresh()
        val hadAccess = _hasAllFilesAccess.value
        _hasAllFilesAccess.value = scanner.hasAllFilesAccess()
        if (filesTabVisible && _hasAllFilesAccess.value && !hadAccess) {
            refreshDeviceFiles()
        }
    }

    fun ensureDeviceFilesScanned() {
        if (lastScanAt <= 0L && scanner.cachedResult() == null) {
            refreshDeviceFiles()
        }
    }

    fun addFolder(uri: Uri) {
        scanner.addFolder(uri)
        refreshDeviceFiles()
    }

    fun allFilesAccessIntent(): Intent? = scanner.allFilesAccessIntent()

    fun needsLegacyStoragePermission(): Boolean = scanner.needsLegacyStoragePermission()
}

class LibraryViewModelFactory(private val app: PaperPdfApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(app) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenDocument: (Long, String) -> Unit,
    onOpenUri: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PaperPdfApp
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(app))
    val documents by vm.documents.collectAsState()
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var sortName by remember { mutableStateOf("最近打开") }
    var editing by remember { mutableStateOf<DocumentRecord?>(null) }
    var deleting by remember { mutableStateOf<DocumentRecord?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var allFilesPrompt by remember { mutableStateOf(false) }
    val deviceFiles by vm.deviceFiles.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanMode by vm.scanMode.collectAsState()
    val scanNote by vm.scanNote.collectAsState()
    val hasAllFiles by vm.hasAllFilesAccess.collectAsState()
    val sharePdf: (String, String) -> Unit = { uri, name ->
        runCatching { PdfShare.share(context, uri, name) }
            .onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onOpenUri(uri.toString(), false)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    val legacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        vm.refreshDeviceFiles()
    }
    val allFilesSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.onResume(tab == 1)
    }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(tab) {
        if (tab == 1) {
            if (vm.needsLegacyStoragePermission()) {
                legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                vm.ensureDeviceFilesScanned()
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tab) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume(tab == 1)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text(
                            "纸间",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "本地 PDF 阅读与标注 · 每份文档独立窗口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text(sortName, style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                "last_opened_at DESC" to "最近打开",
                                "added_at DESC" to "最近加入",
                                "title" to "标题",
                                "size" to "文件大小",
                            ).forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        sortMenu = false
                                        sortName = label
                                        vm.setSort(key)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf", "application/octet-stream")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("打开 PDF") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("历史 ${documents.size}") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("手机文件 ${deviceFiles.size}") },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                placeholder = { Text(if (tab == 0) "搜索历史文档" else "搜索手机里的 PDF") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
            val filteredDocs = documents.filter {
                query.isBlank() ||
                    it.effectiveTitle.contains(query, ignoreCase = true) ||
                    it.displayName.contains(query, ignoreCase = true)
            }
            val filteredFiles = deviceFiles.filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.path.contains(query, ignoreCase = true)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (tab == 0) "${documents.size} 份历史文档" else "${deviceFiles.size} 个手机 PDF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatBytes(
                        if (tab == 0) documents.sumOf { it.size }
                        else deviceFiles.sumOf { it.size },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tab == 0) {
                if (filteredDocs.isEmpty()) {
                    EmptyLibrary(
                        searching = query.isNotBlank(),
                        onOpen = { picker.launch(arrayOf("application/pdf", "application/octet-stream")) },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 100.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filteredDocs, key = { it.id }) { doc ->
                            DocumentCard(
                                doc = doc,
                                onClick = { onOpenDocument(doc.id, doc.uri) },
                                onShare = { sharePdf(doc.uri, doc.effectiveTitle) },
                                onEdit = { editing = doc },
                                onDelete = { deleting = doc },
                                onStatus = { status ->
                                    vm.updateInfo(
                                        doc,
                                        doc.title.orEmpty(),
                                        doc.author.orEmpty(),
                                        doc.tags.orEmpty(),
                                        status,
                                    )
                                },
                            )
                        }
                    }
                }
            } else {
                DeviceFilesPane(
                    files = filteredFiles,
                    totalCount = deviceFiles.size,
                    scanning = scanning,
                    scanMode = scanMode,
                    scanNote = scanNote,
                    hasAllFilesAccess = hasAllFiles,
                    searching = query.isNotBlank(),
                    onRefresh = vm::refreshDeviceFiles,
                    onChooseFolder = { folderPicker.launch(null) },
                    onRequestAllFiles = { allFilesPrompt = true },
                    onOpen = { onOpenUri(it.uri, false) },
                    onShare = { sharePdf(it.uri, it.name) },
                )
            }
        }
    }

    editing?.let { doc ->
        DocumentInfoDialog(
            doc = doc,
            onDismiss = { editing = null },
            onSave = { title, author, tags, status ->
                vm.updateInfo(doc, title, author, tags, status)
                editing = null
            },
        )
    }
    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移除文档记录？") },
            text = {
                Text("将删除《${doc.effectiveTitle}》的阅读进度、书签和标注记录。原 PDF 文件不会被删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(doc)
                    deleting = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
    if (allFilesPrompt) {
        AlertDialog(
            onDismissRequest = { allFilesPrompt = false },
            title = { Text("扫描手机里的全部 PDF") },
            text = {
                Text(
                    "需要授予“所有文件访问”权限，应用才能遍历内部存储并列出所有 PDF。" +
                        "应用只读取 PDF 文件，不会修改或删除原文件；也可以改为只授权某个文件夹。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    allFilesPrompt = false
                    vm.allFilesAccessIntent()?.let(allFilesSettings::launch)
                }) { Text("去授权") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        allFilesPrompt = false
                        folderPicker.launch(null)
                    }) { Text("选择文件夹") }
                    TextButton(onClick = { allFilesPrompt = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentRecord,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStatus: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            DocumentCover(doc)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    doc.effectiveTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(doc.pageCount).append(" 页")
                        append(" · ")
                        append(relativeTime(doc.lastOpenedAt))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val progress = if (doc.pageCount > 0) {
                    ((doc.lastPage + 1f) / doc.pageCount).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "读到第 ${doc.lastPage + 1} 页",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (doc.status == ReadingStatus.FINISHED.value) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = { Text("已读") })
                    } else if (doc.status == ReadingStatus.READING.value) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "在读",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("分享 PDF") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑文档信息") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("标记为未读") },
                        onClick = { menu = false; onStatus(ReadingStatus.UNREAD.value) },
                    )
                    DropdownMenuItem(
                        text = { Text("标记为已读") },
                        onClick = { menu = false; onStatus(ReadingStatus.FINISHED.value) },
                    )
                    DropdownMenuItem(
                        text = { Text("移除记录") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceFilesPane(
    files: List<DevicePdf>,
    totalCount: Int,
    scanning: Boolean,
    scanMode: DeviceScanMode,
    scanNote: String,
    hasAllFilesAccess: Boolean,
    searching: Boolean,
    onRefresh: () -> Unit,
    onChooseFolder: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onOpen: (DevicePdf) -> Unit,
    onShare: (DevicePdf) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRefresh, enabled = !scanning) {
                Text("刷新")
            }
            OutlinedButton(onClick = onChooseFolder) {
                Text("选择文件夹")
            }
            if (!hasAllFilesAccess) {
                Button(onClick = onRequestAllFiles) {
                    Text("授权全部文件")
                }
            }
        }
        Text(
            "${scanModeLabel(scanMode)} · 显示 ${files.size} / $totalCount 个 PDF",
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            )
            Text(
                "正在扫描手机存储，第一次扫描可能需要几秒…",
                modifier = Modifier.padding(horizontal = 22.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (scanNote.isNotBlank()) {
            Text(
                scanNote,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (files.isEmpty() && !scanning) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(96.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "PDF",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (searching) "没有匹配的手机文件" else "还没有扫描到 PDF",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hasAllFilesAccess) {
                            "内部存储中没有找到 PDF 文件。"
                        } else {
                            "可以授权“所有文件访问”扫描完整存储，或只选择一个文件夹。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 100.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.uri }) { file ->
                    DeviceFileCard(
                        file = file,
                        onOpen = { onOpen(file) },
                        onShare = { onShare(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceFileCard(file: DevicePdf, onOpen: () -> Unit, onShare: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(width = 46.dp, height = 60.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "PDF",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    file.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatBytes(file.size)} · ${relativeTime(file.modified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("打开") },
                        onClick = { menu = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text("分享 PDF") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menu = false; onShare() },
                    )
                }
            }
        }
    }
}

private fun scanModeLabel(mode: DeviceScanMode): String = when (mode) {
    DeviceScanMode.ALL_FILES -> "全盘扫描"
    DeviceScanMode.FOLDERS -> "授权文件夹"
    DeviceScanMode.MEDIA_STORE -> "系统媒体库"
    DeviceScanMode.NONE -> "尚未扫描"
}

@Composable
private fun DocumentCover(doc: DocumentRecord) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, doc.hash) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "thumbs/${doc.hash.take(24)}_0.jpg")
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(width = 64.dp, height = 88.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PDF",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(searching: Boolean, onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(120.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PDF", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                if (searching) "没有匹配的文档" else "把 PDF 放进来",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (searching) "换个关键词试试" else "本地打开，不上传；位置、书签和标注都会保存",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!searching) {
                Spacer(Modifier.height(18.dp))
                TextButton(onClick = onOpen) { Text("选择一份 PDF") }
            }
        }
    }
}

@Composable
fun DocumentInfoDialog(
    doc: DocumentRecord,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit,
) {
    var title by remember(doc.id) { mutableStateOf(doc.title.orEmpty()) }
    var author by remember(doc.id) { mutableStateOf(doc.author.orEmpty()) }
    var tags by remember(doc.id) { mutableStateOf(doc.tags.orEmpty()) }
    var status by remember(doc.id) { mutableStateOf(doc.status) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文档信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("标签（逗号分隔）") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("未读", status == 0) { status = 0 }
                    StatusChip("在读", status == 1) { status = 1 }
                    StatusChip("已读", status == 2) { status = 2 }
                }
                Text(
                    doc.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, author, tags, status) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun relativeTime(epoch: Long): String {
    val diff = System.currentTimeMillis() - epoch
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epoch))
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
