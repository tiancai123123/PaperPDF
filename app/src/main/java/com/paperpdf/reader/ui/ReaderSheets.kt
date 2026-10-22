package com.paperpdf.reader.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperpdf.reader.data.AnnotationRecord
import com.paperpdf.reader.data.AnnoType
import com.paperpdf.reader.data.BookmarkRecord
import com.paperpdf.reader.data.ColorMode
import com.paperpdf.reader.data.LayoutMode
import com.paperpdf.reader.data.ReaderSettings
import com.paperpdf.reader.data.RectN
import com.paperpdf.reader.engine.OutlineEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    outline: List<OutlineEntry>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame("目录", onDismiss) {
        if (outline.isEmpty()) {
            Text(
                "这份 PDF 没有内嵌目录。可以改用书签记录阅读位置。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(outline.size) { index ->
                    val item = outline[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(item.pageIndex) }
                            .padding(start = (12 + item.depth * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "第 ${item.pageIndex + 1} 页",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    search: SearchState,
    initialQuery: String,
    onSearch: (String) -> Unit,
    onSelect: (Int, com.paperpdf.reader.data.SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(if (search.query.isNotBlank()) search.query else initialQuery) }
    SheetFrame("全文搜索", onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入中文或英文关键词") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSearch(query) }, enabled = query.isNotBlank()) { Text("搜索") }
        }
        if (search.running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { search.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "正在检索第 ${(search.progress * 100).roundToInt()}% 的页面…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (search.query.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (search.hits.isEmpty()) "没有找到匹配的文字" else "找到 ${search.hits.size} 处，点按定位",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.heightIn(max = 430.dp)) {
            items(search.hits.size) { index ->
                val hit = search.hits[index]
                Surface(
                    onClick = { onSelect(index, hit) },
                    color = if (index == search.activeIndex) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            "第 ${hit.pageIndex + 1} 页",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            hit.snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationSheet(
    annotations: List<AnnotationRecord>,
    pageCount: Int,
    onJump: (AnnotationRecord) -> Unit,
    onEdit: (AnnotationRecord) -> Unit,
    onDelete: (AnnotationRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf<String?>(null) }
    val filtered = annotations.filter { filter == null || it.type == filter }.sortedBy { it.pageIndex }
    SheetFrame("标注与笔记", onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("全部") })
            listOf(
                AnnoType.HIGHLIGHT,
                AnnoType.UNDERLINE,
                AnnoType.STRIKEOUT,
                AnnoType.NOTE,
                AnnoType.TEXTBOX,
                AnnoType.INK,
            ).forEach { type ->
                FilterChip(
                    selected = filter == type,
                    onClick = { filter = if (filter == type) null else type },
                    label = { Text(AnnoType.label(type)) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (filtered.isEmpty()) {
            Text(
                "还没有标注。长按页面文字即可高亮、划线或写笔记。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 500.dp)) {
                items(filtered, key = { it.id }) { record ->
                    Surface(
                        onClick = { onJump(record) },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(record.colorArgb)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${AnnoType.label(record.type)} · 第 ${record.pageIndex + 1} 页",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (record.quote.isNotBlank()) {
                                    Text(
                                        record.quote.replace('\n', ' '),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (record.note.isNotBlank()) {
                                    Text(
                                        record.note,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onEdit(record) }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑")
                            }
                            IconButton(onClick = { onDelete(record) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    bookmarks: List<BookmarkRecord>,
    onJump: (BookmarkRecord) -> Unit,
    onRename: (BookmarkRecord, String) -> Unit,
    onDelete: (BookmarkRecord) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<BookmarkRecord?>(null) }
    SheetFrame("书签", onDismiss) {
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("把当前页加入书签")
        }
        if (bookmarks.isEmpty()) {
            Text(
                "还没有书签。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(bookmarks, key = { it.id }) { bm ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onJump(bm) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bm.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "第 ${bm.pageIndex + 1} 页",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { renaming = bm }) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = { onDelete(bm) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }
    renaming?.let { bm ->
        var label by remember(bm.id) { mutableStateOf(bm.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名书签") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(bm, label)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailSheet(
    vm: ReaderViewModel,
    pageCount: Int,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame("页面缩略图", onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.heightIn(max = 560.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pageCount) { page ->
                ThumbnailCell(
                    vm = vm,
                    page = page,
                    selected = page == currentPage,
                    onClick = { onSelect(page) },
                )
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    vm: ReaderViewModel,
    page: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, page) {
        value = vm.thumbnail(page, 200)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = if (selected) {
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        Text(
            "${page + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    canUndo: Boolean,
    canRedo: Boolean,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetZoom: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame("阅读设置", onDismiss) {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            item {
                SectionTitle("配色")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.colorMode == mode,
                            onClick = { onSettings { it.copy(colorMode = mode) } },
                            label = {
                                Text(
                                    when (mode) {
                                        ColorMode.DAY -> "日间"
                                        ColorMode.SEPIA -> "护眼"
                                        ColorMode.NIGHT -> "夜间"
                                    },
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                SectionTitle("翻页方式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.defaultLayout == LayoutMode.VERTICAL,
                        onClick = { onLayout(LayoutMode.VERTICAL) },
                        label = { Text("纵向滚动") },
                    )
                    FilterChip(
                        selected = settings.defaultLayout == LayoutMode.HORIZONTAL,
                        onClick = { onLayout(LayoutMode.HORIZONTAL) },
                        label = { Text("横向翻页") },
                    )
                }
                SectionTitle("屏幕亮度")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = if (settings.brightness < 0f) 0.6f else settings.brightness,
                        onValueChange = { onSettings { s -> s.copy(brightness = it.coerceAtLeast(0.05f)) } },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSettings { it.copy(brightness = -1f) } }) { Text("自动") }
                }
                SwitchRow("保持屏幕常亮", settings.keepScreenOn) { value ->
                    onSettings { it.copy(keepScreenOn = value) }
                }
                SwitchRow("缩放锁定（禁用双指缩放）", settings.zoomLocked) { value ->
                    onSettings { it.copy(zoomLocked = value) }
                }
                Spacer(Modifier.height(10.dp))
                SectionTitle("屏幕方向")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "自动" to -1,
                        "竖屏" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        "横屏" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = settings.orientation == value,
                            onClick = { onSettings { it.copy(orientation = value) } },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销") }
                    TextButton(onClick = onRedo, enabled = canRedo) { Text("重做") }
                    TextButton(onClick = onResetZoom) { Text("重置缩放") }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "扫描版 PDF 没有文本层时，只支持视觉标注；第一版不接入 OCR 和 AI。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onSidecar: () -> Unit,
    onAnnotatedPdf: () -> Unit,
    onMarkdown: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame("导出", onDismiss) {
        Text(
            "原 PDF 始终保持只读；导出会生成新文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        ExportRow(
            title = "带批注 PDF",
            description = "高亮、下划线、删除线、便签和矩形写成标准注释；手写会烧录到副本",
            onClick = onAnnotatedPdf,
        )
        HorizontalDivider()
        ExportRow(
            title = "标注 JSON 备份",
            description = "包含文档指纹、阅读位置、书签、标注和文本锚点，可迁移",
            onClick = onSidecar,
        )
        HorizontalDivider()
        ExportRow(
            title = "Markdown 笔记",
            description = "按页导出引用原文、笔记和标签，适合放进 Obsidian",
            onClick = onMarkdown,
        )
    }
}

@Composable
private fun ExportRow(title: String, description: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun NoteEditorDialog(
    target: NoteTarget,
    onSave: (String, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var note by remember(target) { mutableStateOf(target.existing?.note.orEmpty()) }
    var color by remember(target) { mutableStateOf(target.existing?.colorArgb ?: highlightColors[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (target.existing == null) "添加笔记" else "编辑${AnnoType.label(target.existing.type)}标注",
            )
        },
        text = {
            Column {
                if (target.quote.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            target.quote.replace('\n', ' '),
                            modifier = Modifier
                                .heightIn(max = 110.dp)
                                .padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("笔记") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    highlightColors.forEach { candidate ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(candidate))
                                .border(
                                    if (color == candidate) 2.dp else 1.dp,
                                    if (color == candidate) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    CircleShape,
                                )
                                .clickable { color = candidate },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note, color) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除标注", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun TextBoxDialog(
    target: TextBoxTarget,
    onSave: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(highlightColors[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文本框") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文字") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    highlightColors.forEach { candidate ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(candidate))
                                .border(
                                    if (color == candidate) 2.dp else 1.dp,
                                    if (color == candidate) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    CircleShape,
                                )
                                .clickable { color = candidate },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, color) }, enabled = text.isNotBlank()) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
