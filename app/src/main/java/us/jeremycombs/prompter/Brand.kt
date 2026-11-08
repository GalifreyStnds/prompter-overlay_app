package us.jeremycombs.prompter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.SeekBar
import kotlin.math.roundToInt

/**
 * Shared look for the settings screen and the floating overlay.
 * Colors live in res/values/colors.xml. Yellow marks the primary action and the active element,
 * the same way the launcher icon marks the current reading line.
 */
object Brand {
    private const val RIPPLE_ALPHA = 0x33
    private const val BUTTON_TEXT_SP = 14f
    private const val BUTTON_RADIUS_DP = 9
    private const val FOCUS_RING_DP = 2

    fun bg(ctx: Context): Int = ctx.getColor(R.color.brand_bg)
    fun panel(ctx: Context): Int = ctx.getColor(R.color.brand_panel)
    fun text(ctx: Context): Int = ctx.getColor(R.color.brand_text)
    fun muted(ctx: Context): Int = ctx.getColor(R.color.brand_muted)
    fun accent(ctx: Context): Int = ctx.getColor(R.color.brand_accent)
    fun onAccent(ctx: Context): Int = ctx.getColor(R.color.brand_on_accent)

    @Suppress("DEPRECATION")
    fun applyPageInsets(view: View, onKeyboard: ((Boolean) -> Unit)? = null) {
        view.setOnApplyWindowInsetsListener { page, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val system = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val bars = insets.getInsets(if (onKeyboard == null) system else system or WindowInsets.Type.ime())
                page.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                onKeyboard?.invoke(insets.isVisible(WindowInsets.Type.ime()))
            } else {
                page.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(fill: Int, radiusPx: Float, strokeColor: Int = Color.TRANSPARENT, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /**
     * Rounded button background with a press ripple and a visible focus ring for keyboard
     * and switch-access users.
     */
    private fun buttonBackground(ctx: Context, fill: Int, ink: Int, primary: Boolean): Drawable {
        val density = ctx.resources.displayMetrics.density
        val radius = BUTTON_RADIUS_DP * density
        val focusRing = (FOCUS_RING_DP * density).roundToInt()
        val outline = ctx.getColor(R.color.brand_outline)
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(fill, radius, ink, focusRing))
            addState(intArrayOf(), rounded(fill, radius, outline, if (primary) 0 else density.roundToInt()))
        }
        val ripple = ColorStateList.valueOf(withAlpha(ink, RIPPLE_ALPHA))
        return RippleDrawable(ripple, states, rounded(Color.WHITE, radius))
    }

    /** Primary buttons are yellow with dark text. Others are charcoal with light text. */
    fun styleButton(ctx: Context, button: Button, primary: Boolean) {
        val fill = if (primary) accent(ctx) else panel(ctx)
        val ink = if (primary) onAccent(ctx) else text(ctx)
        button.background = buttonBackground(ctx, fill, ink, primary)
        button.setTextColor(ink)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
        button.isAllCaps = false
        button.stateListAnimator = null
    }

    fun styleSeekBar(ctx: Context, seek: SeekBar) {
        seek.progressTintList = ColorStateList.valueOf(accent(ctx))
        seek.thumbTintList = ColorStateList.valueOf(accent(ctx))
        seek.progressBackgroundTintList = ColorStateList.valueOf(panel(ctx))
    }
}
