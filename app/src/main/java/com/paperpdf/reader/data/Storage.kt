package com.paperpdf.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ImportedFile(
    val uriString: String,
    val hash: String,
    val size: Long,
    val displayName: String,
)

class DocumentStorage(private val context: Context) {

    fun import(uri: Uri, copyIfNeeded: Boolean): ImportedFile {
        val name = displayName(uri)
        val hashAndSize = hash(uri)
        val resolvedUri = if (copyIfNeeded) {
            copyToInternal(uri, hashAndSize.first)
        } else {
            uri.toString()
        }
        return ImportedFile(
            uriString = resolvedUri,
            hash = hashAndSize.first,
            size = hashAndSize.second,
            displayName = name,
        )
    }

    fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.path?.let { File(it).name } ?: "document.pdf"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val value = cursor.getString(index)
                            if (!value.isNullOrBlank()) return value
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun hash(uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        } ?: throw IllegalStateException("无法读取文件")
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }

    private fun copyToInternal(uri: Uri, hash: String): String {
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val target = File(dir, "$hash.pdf")
        if (target.exists() && target.length() > 0) return Uri.fromFile(target).toString()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        } ?: throw IllegalStateException("无法复制文件")
        return Uri.fromFile(target).toString()
    }
}
