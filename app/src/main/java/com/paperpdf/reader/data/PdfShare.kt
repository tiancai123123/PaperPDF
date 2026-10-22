package com.paperpdf.reader.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object PdfShare {
    fun share(context: Context, uriString: String, title: String) {
        val source = Uri.parse(uriString)
        val shareUri = when (source.scheme) {
            "content" -> source
            "file" -> {
                val file = source.path?.let(::File)
                    ?: throw IllegalStateException("文件路径无效")
                if (!file.exists()) throw IllegalStateException("文件不存在")
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            else -> throw IllegalStateException("不支持分享该文件地址")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newUri(context.contentResolver, title, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 PDF"))
    }
}
