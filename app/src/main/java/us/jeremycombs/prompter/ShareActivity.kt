package us.jeremycombs.prompter

import android.app.Activity
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Shows incoming text separately from the editor until the user chooses to replace the script. */
class ShareActivity : Activity() {
    private var previewText: String? = null
    private var previewError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            readPreview()
        } else {
            previewText = savedInstanceState.getString(STATE_TEXT)
            previewError = savedInstanceState.getString(STATE_ERROR)
        }
        showPreview()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPreview()
        showPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TEXT, previewText)
        outState.putString(STATE_ERROR, previewError)
    }

    private fun sharedText(): CharSequence? {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return try {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        } catch (e: BadParcelableException) {
            null
        }
    }

    private fun readPreview() {
        val incoming = sharedText()
        previewError = when {
            incoming == null -> "Share plain text to Prompter."
            incoming.length > Limits.MAX_SCRIPT_CHARS ->
                "Shared text is too long. The limit is ${Limits.MAX_SCRIPT_CHARS} characters."
            incoming.isBlank() -> "The shared text is blank."
            else -> null
        }
        previewText = if (previewError == null) incoming?.toString() else null
    }

    private fun showPreview() {
        val script = previewText
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.bg(this@ShareActivity))
        }
        Brand.applyPageInsets(root)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(label("Shared text", 24f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(label(previewError ?: "Your current script will be replaced only if you tap Replace Script", 16f).apply {
            setPadding(0, dp(8), 0, dp(12))
        })
        if (script != null) {
            val preview = ScrollView(this).apply {
                background = Brand.rounded(Brand.panel(this@ShareActivity), dp(10).toFloat())
                addView(label(script, 16f).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) })
            }
            content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(action("Keep Current Script") { finish() })
            content.addView(action("Replace Script", primary = true) { replaceScript(script) })
        } else {
            content.addView(action("Close") { finish() })
        }
        setContentView(root)
    }

    private fun replaceScript(script: String) {
        if (isFinishing) return
        Prefs(this).script = script
        // Reuse the editor task so an old editor cannot resume with a stale draft.
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
        finish()
    }

    private fun label(value: String, sizeSp: Float) = TextView(this).apply {
        text = value
        setTextColor(Brand.text(this@ShareActivity))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun action(title: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = title
        minHeight = dp(48)
        Brand.styleButton(this@ShareActivity, this, primary, dp(10).toFloat(), dp(2))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val STATE_TEXT = "previewText"
        const val STATE_ERROR = "previewError"
    }
}
