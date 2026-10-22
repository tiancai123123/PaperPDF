package com.paperpdf.reader.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DevicePdf(
    val uri: String,
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
)

enum class DeviceScanMode {
    ALL_FILES,
    FOLDERS,
    MEDIA_STORE,
    NONE,
}

data class DeviceScanResult(
    val files: List<DevicePdf>,
    val mode: DeviceScanMode,
    val note: String = "",
)

data class CachedDeviceScan(
    val files: List<DevicePdf>,
    val mode: DeviceScanMode,
    val note: String,
    val scannedAt: Long,
)

class DevicePdfScanner(private val context: Context) {
    private val prefs = context.getSharedPreferences("device_pdf_scan", Context.MODE_PRIVATE)

    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED

    fun allFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun savedFolders(): List<Uri> =
        prefs.getStringSet("folders", emptySet())
            .orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { it != Uri.EMPTY }

    fun addFolder(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val next = savedFolders().toMutableSet().apply { add(uri) }
        prefs.edit().putStringSet("folders", next.map { it.toString() }.toSet()).apply()
    }

    fun removeFolder(uri: Uri) {
        val next = savedFolders().toMutableSet().apply { remove(uri) }
        prefs.edit().putStringSet("folders", next.map { it.toString() }.toSet()).apply()
    }

    fun cachedResult(): CachedDeviceScan? {
        val raw = prefs.getString("cache_files", null) ?: return null
        val scannedAt = prefs.getLong("cache_time", 0L)
        if (scannedAt <= 0L) return null
        return runCatching {
            val array = JSONArray(raw)
            val files = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        DevicePdf(
                            uri = item.getString("uri"),
                            name = item.getString("name"),
                            path = item.optString("path"),
                            size = item.optLong("size"),
                            modified = item.optLong("modified"),
                        ),
                    )
                }
            }
            CachedDeviceScan(
                files = files,
                mode = runCatching {
                    DeviceScanMode.valueOf(prefs.getString("cache_mode", DeviceScanMode.NONE.name)!!)
                }.getOrDefault(DeviceScanMode.NONE),
                note = prefs.getString("cache_note", "").orEmpty(),
                scannedAt = scannedAt,
            )
        }.getOrNull()
    }

    fun saveCachedResult(result: DeviceScanResult) {
        val array = JSONArray().apply {
            result.files.forEach { file ->
                put(
                    JSONObject()
                        .put("uri", file.uri)
                        .put("name", file.name)
                        .put("path", file.path)
                        .put("size", file.size)
                        .put("modified", file.modified),
                )
            }
        }
        prefs.edit()
            .putString("cache_files", array.toString())
            .putString("cache_mode", result.mode.name)
            .putString("cache_note", result.note)
            .putLong("cache_time", System.currentTimeMillis())
            .apply()
    }

    suspend fun scan(): DeviceScanResult = withContext(Dispatchers.IO) {
        if (hasAllFilesAccess()) {
            val files = scanSharedStorage()
            return@withContext DeviceScanResult(
                files = files,
                mode = DeviceScanMode.ALL_FILES,
                note = "已扫描内部存储",
            )
        }
        val folders = savedFolders()
        if (folders.isNotEmpty()) {
            val files = folders.flatMap { scanTree(it) }
                .distinctBy { it.uri }
                .sortedByDescending { it.modified }
            return@withContext DeviceScanResult(
                files = files,
                mode = DeviceScanMode.FOLDERS,
                note = "已扫描 ${folders.size} 个授权文件夹",
            )
        }
        val files = scanMediaStore()
        DeviceScanResult(
            files = files,
            mode = DeviceScanMode.MEDIA_STORE,
            note = "当前使用系统媒体库索引，可能不完整。授权“所有文件访问”后可扫描完整存储。",
        )
    }

    private fun scanSharedStorage(): List<DevicePdf> {
        val root = Environment.getExternalStorageDirectory()
        val out = ArrayList<DevicePdf>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        val skipped = setOf(
            "android",
            "lost.dir",
            "system volume information",
            "\$recycle.bin",
            ".thumbnails",
        )
        while (stack.isNotEmpty() && out.size < 6000) {
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name.startsWith(".") || child.name.lowercase() in skipped) continue
                    stack.add(child)
                } else if (child.isFile &&
                    child.length() > 0 &&
                    child.name.endsWith(".pdf", ignoreCase = true)
                ) {
                    out.add(
                        DevicePdf(
                            uri = Uri.fromFile(child).toString(),
                            name = child.name,
                            path = child.absolutePath.removePrefix(root.absolutePath).trimStart('/'),
                            size = child.length(),
                            modified = child.lastModified(),
                        ),
                    )
                }
            }
        }
        return out.sortedByDescending { it.modified }
    }

    private fun scanTree(treeUri: Uri): List<DevicePdf> {
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return emptyList()
        val out = ArrayList<DevicePdf>()
        fun walk(dir: DocumentFile, depth: Int, prefix: String) {
            if (depth > 10 || out.size >= 3000) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                val name = child.name.orEmpty()
                if (child.isDirectory) {
                    if (name.startsWith(".") || name.equals("Android", ignoreCase = true)) continue
                    walk(child, depth + 1, if (prefix.isEmpty()) name else "$prefix/$name")
                } else if (child.isFile &&
                    (child.type == "application/pdf" || name.endsWith(".pdf", ignoreCase = true))
                ) {
                    out.add(
                        DevicePdf(
                            uri = child.uri.toString(),
                            name = name.ifBlank { "document.pdf" },
                            path = if (prefix.isEmpty()) name else "$prefix/$name",
                            size = child.length(),
                            modified = child.lastModified(),
                        ),
                    )
                }
            }
        }
        walk(root, 0, root.name.orEmpty())
        return out
    }

    private fun scanMediaStore(): List<DevicePdf> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val selection =
            "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("application/pdf", "%.pdf")
        return runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val name = cursor.getString(nameIndex) ?: continue
                        if (!name.endsWith(".pdf", ignoreCase = true)) continue
                        add(
                            DevicePdf(
                                uri = ContentUris.withAppendedId(collection, id).toString(),
                                name = name,
                                path = "系统文件索引",
                                size = cursor.getLong(sizeIndex),
                                modified = cursor.getLong(dateIndex) * 1000L,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
