package us.jeremycombs.prompter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.SeekBar

/**
 * Shared look for the settings screen and the floating overlay.
 * Colors live in res/values/colors.xml. Yellow marks the primary action and the active element,
 * the same way the launcher icon marks the current reading line.
 */
object Brand {
    private const val RIPPLE_ALPHA = 0x33
    private const val BUTTON_TEXT_SP = 14f

    fun bg(ctx: Context): Int = ctx.getColor(R.color.brand_bg)
    fun panel(ctx: Context): Int = ctx.getColor(R.color.brand_panel)
    fun text(ctx: Context): Int = ctx.getColor(R.color.brand_text)
    fun muted(ctx: Context): Int = ctx.getColor(R.color.brand_muted)
    fun accent(ctx: Context): Int = ctx.getColor(R.color.brand_accent)
    fun onAccent(ctx: Context): Int = ctx.getColor(R.color.brand_on_accent)

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
    fun buttonBackground(ctx: Context, fill: Int, radiusPx: Float, focusRingPx: Int): Drawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(fill, radiusPx, text(ctx), focusRingPx))
            addState(intArrayOf(), rounded(fill, radiusPx))
        }
        val ripple = ColorStateList.valueOf(withAlpha(text(ctx), RIPPLE_ALPHA))
        return RippleDrawable(ripple, states, rounded(Color.WHITE, radiusPx))
    }

    /** Primary buttons are yellow with dark text. Others are charcoal with light text. */
    fun styleButton(ctx: Context, button: Button, primary: Boolean, radiusPx: Float, focusRingPx: Int) {
        val fill = if (primary) accent(ctx) else panel(ctx)
        button.background = buttonBackground(ctx, fill, radiusPx, focusRingPx)
        button.setTextColor(if (primary) onAccent(ctx) else text(ctx))
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
