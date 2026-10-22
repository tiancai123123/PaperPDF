package com.paperpdf.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.paperpdf.reader.ui.ReaderRequest

/**
 * Creates one Android document task for each PDF reader window.
 *
 * NEW_DOCUMENT gives each PDF a task entry in Recents. MULTIPLE_TASK keeps even
 * repeated opens of the same URI independent, which allows two windows of the
 * same document to hold different reading positions.
 */
object ReaderWindowLauncher {
    const val EXTRA_DOC_ID = "doc_id"
    const val EXTRA_URI = "uri"
    const val EXTRA_COPY_IF_NEEDED = "copy_if_needed"
    const val EXTRA_TITLE = "title"

    fun intent(
        context: Context,
        request: ReaderRequest,
        launchAdjacent: Boolean = false,
    ): Intent = Intent(context, ReaderActivity::class.java).apply {
        var launchFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        if (launchAdjacent) launchFlags = launchFlags or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        addFlags(launchFlags)
        if (request.uri != null) {
            setDataAndType(Uri.parse(request.uri), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        request.docId?.let { putExtra(EXTRA_DOC_ID, it) }
        putExtra(EXTRA_URI, request.uri)
        putExtra(EXTRA_COPY_IF_NEEDED, request.copyIfNeeded)
    }

    fun open(
        context: Context,
        request: ReaderRequest,
        title: String? = null,
        launchAdjacent: Boolean = false,
    ) {
        context.startActivity(
            intent(context, request, launchAdjacent).apply {
                title?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_TITLE, it) }
            },
        )
    }

    fun requestFrom(context: Context, intent: Intent): ReaderRequest? {
        val dataUri = intent.data?.toString()
        val extraUri = intent.getStringExtra(EXTRA_URI)
        val uri = extraUri?.takeIf { it.isNotBlank() } ?: dataUri
        val docId = intent.getLongExtra(EXTRA_DOC_ID, -1L).takeIf { it >= 0L }
        if (docId == null && uri.isNullOrBlank()) return null

        val persistable = dataUri != null &&
            (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        if (persistable) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(dataUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        val hasCopyFlag = intent.hasExtra(EXTRA_COPY_IF_NEEDED)
        val copyIfNeeded = if (hasCopyFlag) {
            intent.getBooleanExtra(EXTRA_COPY_IF_NEEDED, false)
        } else {
            dataUri != null && !persistable
        }
        return ReaderRequest(
            docId = docId,
            uri = uri?.takeIf { it.isNotBlank() },
            copyIfNeeded = copyIfNeeded,
        )
    }
}
