package com.paperpdf.reader

import android.content.Context
import com.paperpdf.reader.data.AnnotationRepository
import com.paperpdf.reader.data.BookmarkRepository
import com.paperpdf.reader.data.DocumentRepository
import com.paperpdf.reader.data.DevicePdfScanner
import com.paperpdf.reader.data.ReaderDb
import com.paperpdf.reader.data.SettingsRepository

class AppContainer(context: Context) {
    val db = ReaderDb(context)
    val settings = SettingsRepository(context)
    val documents = DocumentRepository(context, db)
    val annotations = AnnotationRepository(db)
    val bookmarks = BookmarkRepository(db)
    val deviceScanner = DevicePdfScanner(context)
}
