package com.paperpdf.reader.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class LayoutMode(val value: Int) {
    VERTICAL(0),
    HORIZONTAL(1);

    companion object {
        fun from(value: Int) = entries.firstOrNull { it.value == value } ?: VERTICAL
    }
}

enum class ReadingStatus(val value: Int) {
    UNREAD(0),
    READING(1),
    FINISHED(2);

    companion object {
        fun from(value: Int) = entries.firstOrNull { it.value == value } ?: UNREAD
    }
}

enum class ColorMode {
    DAY,
    SEPIA,
    NIGHT,
}

data class DocumentRecord(
    val id: Long = 0L,
    val hash: String,
    val uri: String,
    val displayName: String,
    val size: Long,
    val pageCount: Int,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val lastPage: Int = 0,
    val pageOffset: Float = 0f,
    val zoom: Float = 1f,
    val layoutMode: Int = LayoutMode.VERTICAL.value,
    val status: Int = ReadingStatus.UNREAD.value,
    val title: String? = null,
    val author: String? = null,
    val tags: String? = null,
) {
    val effectiveTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: displayName
}

data class RectN(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun normalized(): RectN = RectN(
        min(left, right).coerceIn(0f, 1f),
        min(top, bottom).coerceIn(0f, 1f),
        max(left, right).coerceIn(0f, 1f),
        max(top, bottom).coerceIn(0f, 1f),
    )

    fun toJson(): JSONArray = JSONArray().apply {
        put(left)
        put(top)
        put(right)
        put(bottom)
    }

    companion object {
        fun fromJson(array: JSONArray): RectN = RectN(
            array.getDouble(0).toFloat(),
            array.getDouble(1).toFloat(),
            array.getDouble(2).toFloat(),
            array.getDouble(3).toFloat(),
        )

        fun listToJson(rects: List<RectN>): String = JSONArray().apply {
            rects.forEach { put(it.toJson()) }
        }.toString()

        fun listFromJson(raw: String?): List<RectN> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) add(fromJson(array.getJSONArray(i)))
                }
            }.getOrDefault(emptyList())
        }
    }
}

object AnnoType {
    const val HIGHLIGHT = "HIGHLIGHT"
    const val UNDERLINE = "UNDERLINE"
    const val STRIKEOUT = "STRIKEOUT"
    const val NOTE = "NOTE"
    const val TEXTBOX = "TEXTBOX"
    const val INK = "INK"
    const val SQUARE = "SQUARE"

    val all = listOf(HIGHLIGHT, UNDERLINE, STRIKEOUT, NOTE, TEXTBOX, INK, SQUARE)

    fun label(type: String): String = when (type) {
        HIGHLIGHT -> "高亮"
        UNDERLINE -> "下划线"
        STRIKEOUT -> "删除线"
        NOTE -> "笔记"
        TEXTBOX -> "文本框"
        INK -> "手写"
        SQUARE -> "矩形"
        else -> type
    }
}

data class AnnotationRecord(
    val id: Long = 0L,
    val docId: Long,
    val pageIndex: Int,
    val type: String,
    val colorArgb: Int,
    val rects: List<RectN>,
    val quote: String = "",
    val note: String = "",
    val startChar: Int = -1,
    val endChar: Int = -1,
    val tag: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val extra: String = "",
)

data class BookmarkRecord(
    val id: Long = 0L,
    val docId: Long,
    val pageIndex: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class OutlineNode(
    val title: String,
    val pageIndex: Int,
    val depth: Int,
    val children: List<OutlineNode> = emptyList(),
)

data class SearchHit(
    val pageIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val snippet: String,
)

data class ExportResult(
    val uri: String,
    val displayName: String,
)

fun AnnotationRecord.toJson(): JSONObject = JSONObject().apply {
    put("pageIndex", pageIndex)
    put("type", type)
    put("color", String.format("#%08X", colorArgb))
    put("rects", JSONArray(RectN.listToJson(rects)))
    put("quote", quote)
    put("note", note)
    put("startChar", startChar)
    put("endChar", endChar)
    put("tag", tag)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    if (extra.isNotBlank()) put("extra", JSONObject(extra))
}
