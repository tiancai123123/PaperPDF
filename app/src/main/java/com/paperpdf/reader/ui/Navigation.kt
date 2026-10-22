package com.paperpdf.reader.ui

data class ReaderRequest(
    val docId: Long?,
    val uri: String?,
    val copyIfNeeded: Boolean,
)
