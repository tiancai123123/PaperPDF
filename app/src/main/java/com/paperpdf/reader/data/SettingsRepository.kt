package com.paperpdf.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReaderSettings(
    val colorMode: ColorMode = ColorMode.DAY,
    val invertImages: Boolean = false,
    val keepScreenOn: Boolean = true,
    val brightness: Float = -1f,
    val defaultLayout: LayoutMode = LayoutMode.VERTICAL,
    val tapToTurn: Boolean = true,
    val zoomLocked: Boolean = false,
    val orientation: Int = -1,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val settings: StateFlow<ReaderSettings> = state.asStateFlow()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(state.value)
        state.value = next
        prefs.edit()
            .putString("color_mode", next.colorMode.name)
            .putBoolean("invert_images", next.invertImages)
            .putBoolean("keep_screen_on", next.keepScreenOn)
            .putFloat("brightness", next.brightness)
            .putString("layout", next.defaultLayout.name)
            .putBoolean("tap_to_turn", next.tapToTurn)
            .putBoolean("zoom_locked", next.zoomLocked)
            .putInt("orientation", next.orientation)
            .commit()
    }

    private fun load(): ReaderSettings = ReaderSettings(
        colorMode = runCatching {
            ColorMode.valueOf(prefs.getString("color_mode", ColorMode.DAY.name)!!)
        }.getOrDefault(ColorMode.DAY),
        invertImages = prefs.getBoolean("invert_images", false),
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        brightness = prefs.getFloat("brightness", -1f),
        defaultLayout = runCatching {
            LayoutMode.valueOf(prefs.getString("layout", LayoutMode.VERTICAL.name)!!)
        }.getOrDefault(LayoutMode.VERTICAL),
        tapToTurn = prefs.getBoolean("tap_to_turn", true),
        zoomLocked = prefs.getBoolean("zoom_locked", false),
        orientation = prefs.getInt("orientation", -1),
    )
}
