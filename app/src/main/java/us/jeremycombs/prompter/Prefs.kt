package us.jeremycombs.prompter

import android.content.Context
import android.graphics.Color

data class FontOption(val label: String, val family: String)

data class ColorOption(val label: String, val argb: Int)

/** Allowed values for every user setting. Single source of truth for UI and storage. */
object Limits {
    const val MAX_SCRIPT_CHARS = 200_000

    const val SIZE_MIN = 16
    const val SIZE_MAX = 120
    const val SIZE_DEFAULT = 36
    const val SIZE_STEP = 2

    const val SPEED_MIN = 5
    const val SPEED_MAX = 300
    const val SPEED_DEFAULT = 40
    const val SPEED_STEP = 5

    const val OPACITY_MIN = 20
    const val OPACITY_MAX = 100
    const val OPACITY_DEFAULT = 70

    const val COUNTDOWN_DEFAULT = 3

    const val WINDOW_UNSET = -1
}

object Options {
    val fonts = listOf(
        FontOption("Sans", "sans-serif"),
        FontOption("Serif", "serif"),
        FontOption("Mono", "monospace"),
        FontOption("Condensed", "sans-serif-condensed"),
        FontOption("Casual", "casual"),
    )

    val colors = listOf(
        ColorOption("White", Color.WHITE),
        ColorOption("Yellow", 0xFFFFE066.toInt()),
        ColorOption("Green", 0xFF7CFC9A.toInt()),
        ColorOption("Cyan", 0xFF7FE9FF.toInt()),
    )

    val countdowns = listOf(0, 3, 5, 10)
}

/** Settings and script storage. Values are clamped on read so bad data can never crash the overlay. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var script: String
        get() = sp.getString(K_SCRIPT, "") ?: ""
        set(value) = sp.edit().putString(K_SCRIPT, value.take(Limits.MAX_SCRIPT_CHARS)).apply()

    var textSizeSp: Int
        get() = sp.getInt(K_SIZE, Limits.SIZE_DEFAULT).coerceIn(Limits.SIZE_MIN, Limits.SIZE_MAX)
        set(value) = putInt(K_SIZE, value.coerceIn(Limits.SIZE_MIN, Limits.SIZE_MAX))

    var speedDp: Int
        get() = sp.getInt(K_SPEED, Limits.SPEED_DEFAULT).coerceIn(Limits.SPEED_MIN, Limits.SPEED_MAX)
        set(value) = putInt(K_SPEED, value.coerceIn(Limits.SPEED_MIN, Limits.SPEED_MAX))

    var opacityPercent: Int
        get() = sp.getInt(K_OPACITY, Limits.OPACITY_DEFAULT).coerceIn(Limits.OPACITY_MIN, Limits.OPACITY_MAX)
        set(value) = putInt(K_OPACITY, value.coerceIn(Limits.OPACITY_MIN, Limits.OPACITY_MAX))

    var fontIndex: Int
        get() = sp.getInt(K_FONT, 0).coerceIn(0, Options.fonts.lastIndex)
        set(value) = putInt(K_FONT, value.coerceIn(0, Options.fonts.lastIndex))

    var colorIndex: Int
        get() = sp.getInt(K_COLOR, 0).coerceIn(0, Options.colors.lastIndex)
        set(value) = putInt(K_COLOR, value.coerceIn(0, Options.colors.lastIndex))

    var countdownSec: Int
        get() = sp.getInt(K_COUNTDOWN, Limits.COUNTDOWN_DEFAULT).takeIf { it in Options.countdowns }
            ?: Limits.COUNTDOWN_DEFAULT
        set(value) = putInt(K_COUNTDOWN, value)

    var mirror: Boolean
        get() = sp.getBoolean(K_MIRROR, false)
        set(value) = sp.edit().putBoolean(K_MIRROR, value).apply()

    var winX: Int
        get() = sp.getInt(K_WIN_X, Limits.WINDOW_UNSET)
        set(value) = putInt(K_WIN_X, value)

    var winY: Int
        get() = sp.getInt(K_WIN_Y, Limits.WINDOW_UNSET)
        set(value) = putInt(K_WIN_Y, value)

    var winW: Int
        get() = sp.getInt(K_WIN_W, Limits.WINDOW_UNSET)
        set(value) = putInt(K_WIN_W, value)

    var winH: Int
        get() = sp.getInt(K_WIN_H, Limits.WINDOW_UNSET)
        set(value) = putInt(K_WIN_H, value)

    private fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()

    private companion object {
        const val FILE = "prompter"
        const val K_SCRIPT = "script"
        const val K_SIZE = "size"
        const val K_SPEED = "speed"
        const val K_OPACITY = "opacity"
        const val K_FONT = "font"
        const val K_COLOR = "color"
        const val K_COUNTDOWN = "countdown"
        const val K_MIRROR = "mirror"
        const val K_WIN_X = "win_x"
        const val K_WIN_Y = "win_y"
        const val K_WIN_W = "win_w"
        const val K_WIN_H = "win_h"
    }
}
