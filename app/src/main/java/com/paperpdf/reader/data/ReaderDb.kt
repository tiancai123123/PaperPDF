package com.paperpdf.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ReaderDb(context: Context) : SQLiteOpenHelper(context, "paperpdf.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hash TEXT NOT NULL UNIQUE,
                uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                page_count INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL,
                last_page INTEGER NOT NULL DEFAULT 0,
                page_offset REAL NOT NULL DEFAULT 0,
                zoom REAL NOT NULL DEFAULT 1,
                layout_mode INTEGER NOT NULL DEFAULT 0,
                reading_status INTEGER NOT NULL DEFAULT 0,
                title TEXT,
                author TEXT,
                tags TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE annotations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL,
                page_index INTEGER NOT NULL,
                type TEXT NOT NULL,
                color INTEGER NOT NULL,
                rects TEXT NOT NULL,
                quote_text TEXT NOT NULL DEFAULT '',
                note_text TEXT NOT NULL DEFAULT '',
                start_char INTEGER NOT NULL DEFAULT -1,
                end_char INTEGER NOT NULL DEFAULT -1,
                tag TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                extra TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL,
                page_index INTEGER NOT NULL,
                label TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_annotations_doc_page ON annotations(doc_id, page_index)")
        db.execSQL("CREATE INDEX idx_bookmarks_doc_page ON bookmarks(doc_id, page_index)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertDocument(
        hash: String,
        uri: String,
        displayName: String,
        size: Long,
        pageCount: Int,
    ): DocumentRecord {
        val now = System.currentTimeMillis()
        val existing = findDocumentByHash(hash)
        if (existing == null) {
            val values = ContentValues().apply {
                put("hash", hash)
                put("uri", uri)
                put("display_name", displayName)
                put("size", size)
                put("page_count", pageCount)
                put("added_at", now)
                put("last_opened_at", now)
            }
            val id = writableDatabase.insertOrThrow("documents", null, values)
            return getDocument(id)!!
        }
        val values = ContentValues().apply {
            put("uri", uri)
            put("display_name", displayName)
            put("size", size)
            put("page_count", pageCount)
            put("last_opened_at", now)
        }
        writableDatabase.update("documents", values, "id = ?", arrayOf(existing.id.toString()))
        return getDocument(existing.id)!!
    }

    fun findDocumentByHash(hash: String): DocumentRecord? =
        readableDatabase.query(
            "documents", null, "hash = ?", arrayOf(hash), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDocument() else null }

    fun getDocument(id: Long): DocumentRecord? =
        readableDatabase.query(
            "documents", null, "id = ?", arrayOf(id.toString()), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDocument() else null }

    fun listDocuments(sort: String = "last_opened_at DESC"): List<DocumentRecord> {
        val safeSort = when (sort) {
            "title" -> "COALESCE(NULLIF(title, ''), display_name) COLLATE NOCASE ASC"
            "added_at" -> "added_at DESC"
            "size" -> "size DESC"
            else -> "last_opened_at DESC"
        }
        return readableDatabase.query("documents", null, null, null, null, null, safeSort)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toDocument())
                }
            }
    }

    fun updateReadingState(
        docId: Long,
        page: Int,
        offset: Float,
        zoom: Float,
        layoutMode: Int,
    ) {
        val values = ContentValues().apply {
            put("last_page", page)
            put("page_offset", offset)
            put("zoom", zoom)
            put("layout_mode", layoutMode)
            put("last_opened_at", System.currentTimeMillis())
        }
        writableDatabase.update("documents", values, "id = ?", arrayOf(docId.toString()))
    }

    fun touchDocument(docId: Long) {
        val values = ContentValues().apply { put("last_opened_at", System.currentTimeMillis()) }
        writableDatabase.update("documents", values, "id = ?", arrayOf(docId.toString()))
    }

    fun updateDocumentInfo(docId: Long, title: String?, author: String?, tags: String?, status: Int) {
        val values = ContentValues().apply {
            put("title", title)
            put("author", author)
            put("tags", tags)
            put("reading_status", status)
        }
        writableDatabase.update("documents", values, "id = ?", arrayOf(docId.toString()))
    }

    fun deleteDocument(docId: Long) {
        writableDatabase.delete("documents", "id = ?", arrayOf(docId.toString()))
    }

    fun insertAnnotation(record: AnnotationRecord): AnnotationRecord {
        val values = record.toValues()
        val id = writableDatabase.insertOrThrow("annotations", null, values)
        return record.copy(id = id)
    }

    fun updateAnnotation(record: AnnotationRecord) {
        writableDatabase.update(
            "annotations",
            record.toValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(record.id.toString()),
        )
    }

    fun deleteAnnotation(id: Long) {
        writableDatabase.delete("annotations", "id = ?", arrayOf(id.toString()))
    }

    fun getAnnotation(id: Long): AnnotationRecord? =
        readableDatabase.query("annotations", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.toAnnotation() else null }

    fun listAnnotations(docId: Long, pageIndex: Int? = null): List<AnnotationRecord> {
        val selection = if (pageIndex == null) "doc_id = ?" else "doc_id = ? AND page_index = ?"
        val args = if (pageIndex == null) {
            arrayOf(docId.toString())
        } else {
            arrayOf(docId.toString(), pageIndex.toString())
        }
        return readableDatabase.query(
            "annotations", null, selection, args, null, null, "page_index ASC, created_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toAnnotation())
            }
        }
    }

    fun allAnnotations(): List<AnnotationRecord> =
        readableDatabase.query("annotations", null, null, null, null, null, "updated_at DESC")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toAnnotation())
                }
            }

    fun insertBookmark(record: BookmarkRecord): BookmarkRecord {
        val values = ContentValues().apply {
            put("doc_id", record.docId)
            put("page_index", record.pageIndex)
            put("label", record.label)
            put("created_at", record.createdAt)
        }
        val id = writableDatabase.insertOrThrow("bookmarks", null, values)
        return record.copy(id = id)
    }

    fun updateBookmark(record: BookmarkRecord) {
        val values = ContentValues().apply {
            put("page_index", record.pageIndex)
            put("label", record.label)
        }
        writableDatabase.update("bookmarks", values, "id = ?", arrayOf(record.id.toString()))
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    fun listBookmarks(docId: Long): List<BookmarkRecord> =
        readableDatabase.query(
            "bookmarks", null, "doc_id = ?", arrayOf(docId.toString()), null, null, "page_index ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toBookmark())
            }
        }

    fun findBookmark(docId: Long, pageIndex: Int): BookmarkRecord? =
        readableDatabase.query(
            "bookmarks", null, "doc_id = ? AND page_index = ?",
            arrayOf(docId.toString(), pageIndex.toString()), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toBookmark() else null }

    private fun AnnotationRecord.toValues(): ContentValues = ContentValues().apply {
        put("doc_id", docId)
        put("page_index", pageIndex)
        put("type", type)
        put("color", colorArgb)
        put("rects", RectN.listToJson(rects))
        put("quote_text", quote)
        put("note_text", note)
        put("start_char", startChar)
        put("end_char", endChar)
        put("tag", tag)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("extra", extra)
    }

    private fun Cursor.toDocument(): DocumentRecord = DocumentRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        hash = getString(getColumnIndexOrThrow("hash")),
        uri = getString(getColumnIndexOrThrow("uri")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        size = getLong(getColumnIndexOrThrow("size")),
        pageCount = getInt(getColumnIndexOrThrow("page_count")),
        addedAt = getLong(getColumnIndexOrThrow("added_at")),
        lastOpenedAt = getLong(getColumnIndexOrThrow("last_opened_at")),
        lastPage = getInt(getColumnIndexOrThrow("last_page")),
        pageOffset = getFloat(getColumnIndexOrThrow("page_offset")),
        zoom = getFloat(getColumnIndexOrThrow("zoom")),
        layoutMode = getInt(getColumnIndexOrThrow("layout_mode")),
        status = getInt(getColumnIndexOrThrow("reading_status")),
        title = getString(getColumnIndexOrThrow("title")),
        author = getString(getColumnIndexOrThrow("author")),
        tags = getString(getColumnIndexOrThrow("tags")),
    )

    private fun Cursor.toAnnotation(): AnnotationRecord = AnnotationRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        docId = getLong(getColumnIndexOrThrow("doc_id")),
        pageIndex = getInt(getColumnIndexOrThrow("page_index")),
        type = getString(getColumnIndexOrThrow("type")),
        colorArgb = getInt(getColumnIndexOrThrow("color")),
        rects = RectN.listFromJson(getString(getColumnIndexOrThrow("rects"))),
        quote = getString(getColumnIndexOrThrow("quote_text")),
        note = getString(getColumnIndexOrThrow("note_text")),
        startChar = getInt(getColumnIndexOrThrow("start_char")),
        endChar = getInt(getColumnIndexOrThrow("end_char")),
        tag = getString(getColumnIndexOrThrow("tag")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        extra = getString(getColumnIndexOrThrow("extra")),
    )

    private fun Cursor.toBookmark(): BookmarkRecord = BookmarkRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        docId = getLong(getColumnIndexOrThrow("doc_id")),
        pageIndex = getInt(getColumnIndexOrThrow("page_index")),
        label = getString(getColumnIndexOrThrow("label")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
    )
}
