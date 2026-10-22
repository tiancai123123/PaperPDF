package com.paperpdf.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentRepository(
    private val context: Context,
    private val db: ReaderDb,
) {
    private val storage = DocumentStorage(context)

    suspend fun importPdf(uri: Uri, copyIfNeeded: Boolean = false): ImportedFile =
        withContext(Dispatchers.IO) { storage.import(uri, copyIfNeeded) }

    suspend fun saveDocument(file: ImportedFile, pageCount: Int, title: String?, author: String?): DocumentRecord =
        withContext(Dispatchers.IO) {
            val record = db.upsertDocument(
                hash = file.hash,
                uri = file.uriString,
                displayName = file.displayName,
                size = file.size,
                pageCount = pageCount,
            )
            if (record.title.isNullOrBlank() && !title.isNullOrBlank()) {
                db.updateDocumentInfo(record.id, title, author, record.tags, record.status)
            }
            db.getDocument(record.id)!!
        }

    suspend fun list(sort: String = "last_opened_at DESC"): List<DocumentRecord> =
        withContext(Dispatchers.IO) { db.listDocuments(sort) }

    suspend fun get(id: Long): DocumentRecord? = withContext(Dispatchers.IO) { db.getDocument(id) }

    suspend fun saveReadingState(
        docId: Long,
        page: Int,
        offset: Float,
        zoom: Float,
        layoutMode: Int,
    ) = withContext(Dispatchers.IO) {
        db.updateReadingState(docId, page, offset, zoom, layoutMode)
    }

    suspend fun updateInfo(docId: Long, title: String?, author: String?, tags: String?, status: Int) =
        withContext(Dispatchers.IO) { db.updateDocumentInfo(docId, title, author, tags, status) }

    suspend fun delete(docId: Long) = withContext(Dispatchers.IO) { db.deleteDocument(docId) }
}

class AnnotationRepository(private val db: ReaderDb) {

    suspend fun list(docId: Long, page: Int? = null): List<AnnotationRecord> =
        withContext(Dispatchers.IO) { db.listAnnotations(docId, page) }

    suspend fun all(): List<AnnotationRecord> = withContext(Dispatchers.IO) { db.allAnnotations() }

    suspend fun insert(record: AnnotationRecord): AnnotationRecord =
        withContext(Dispatchers.IO) { db.insertAnnotation(record) }

    suspend fun update(record: AnnotationRecord) = withContext(Dispatchers.IO) {
        db.updateAnnotation(record)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { db.deleteAnnotation(id) }

    suspend fun get(id: Long): AnnotationRecord? = withContext(Dispatchers.IO) { db.getAnnotation(id) }
}

class BookmarkRepository(private val db: ReaderDb) {

    suspend fun list(docId: Long): List<BookmarkRecord> = withContext(Dispatchers.IO) {
        db.listBookmarks(docId)
    }

    suspend fun insert(record: BookmarkRecord): BookmarkRecord = withContext(Dispatchers.IO) {
        db.insertBookmark(record)
    }

    suspend fun update(record: BookmarkRecord) = withContext(Dispatchers.IO) { db.updateBookmark(record) }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { db.deleteBookmark(id) }

    suspend fun get(docId: Long, page: Int): BookmarkRecord? = withContext(Dispatchers.IO) {
        db.findBookmark(docId, page)
    }
}
