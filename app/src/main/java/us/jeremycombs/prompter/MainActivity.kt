package us.jeremycombs.prompter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Script editor and settings. Starts and stops the floating overlay.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scriptField: EditText
    private lateinit var loadedScript: String
    private var askedForNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        loadedScript = prefs.script
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshScript()
    }

    override fun onPause() {
        saveScript()
        super.onPause()
    }

    private fun refreshScript() {
        val saved = prefs.script
        if (saved != loadedScript) {
            loadedScript = saved
            scriptField.setText(saved)
        }
    }

    private fun saveScript() {
        // A share may have replaced the saved script while this editor was in the background.
        if (prefs.script != loadedScript) return
        val edited = scriptField.text.toString()
        if (edited != loadedScript) {
            prefs.script = edited
            loadedScript = prefs.script
        }
    }

    // ---------- actions ----------

    private fun startOverlay() {
        saveScript()
        refreshScript()
        if (prefs.script.isBlank()) {
            Toast.makeText(this, "Add a script first.", Toast.LENGTH_SHORT).show()
            scriptField.requestFocus()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\", then tap Start again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedForNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.editor_background))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP))
            isFocusableInTouchMode = true
        }
        root.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        column.addView(buildHeader())

        val scriptLabel = fieldLabel("Script")
        scriptField = object : EditText(this) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    // Apply multi-item pastes together so a later item cannot leave a partial paste.
                    if (clip != null && clip.itemCount > 1) {
                        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                        val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
                        val available = Limits.MAX_SCRIPT_CHARS - (length() - (end - start))
                        val paste = SpannableStringBuilder()
                        for (index in 0 until clip.itemCount) {
                            val item = clip.getItemAt(index)
                            val piece = if (id == android.R.id.paste) item.coerceToStyledText(context)
                                else item.coerceToText(context)
                            if (piece == null) continue
                            val separator = if (paste.isNotEmpty()) 1 else 0
                            if (piece.length > available - paste.length - separator) {
                                showScriptLimit()
                                return true
                            }
                            if (separator != 0) paste.append('\n')
                            paste.append(piece)
                        }
                        if (paste.isNotEmpty()) text.replace(start, end, paste)
                        return true
                    }
                }
                return super.onTextContextMenuItem(id)
            }
        }.apply {
            id = View.generateViewId()
            // The saved script is authoritative after a share, including after process recreation.
            isSaveEnabled = false
            setText(loadedScript)
            hint = "Paste or type your script"
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter { source, start, end, dest, destStart, destEnd ->
                if (end - start > Limits.MAX_SCRIPT_CHARS - (dest.length - (destEnd - destStart))) {
                    showScriptLimit()
                    dest.subSequence(destStart, destEnd)
                } else null
            })
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Brand.text(this@MainActivity))
            setHintTextColor(Brand.muted(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setLineSpacing(0f, 1.18f)
            isVerticalScrollBarEnabled = true
            background = Brand.rounded(
                getColor(R.color.editor_surface), dp(FIELD_RADIUS_DP).toFloat(),
                getColor(R.color.brand_outline), dp(1)
            )
            setPadding(dp(FIELD_PADDING_DP), dp(FIELD_PADDING_DP), dp(FIELD_PADDING_DP), dp(FIELD_PADDING_DP))
        }
        scriptLabel.labelFor = scriptField.id
        column.addView(scriptLabel)
        column.addView(scriptField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(bodyText("Blank lines mark rewind stops.", muted = true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        val start = actionButton("Start overlay", primary = true) { startOverlay() }
        column.addView(start)
        Brand.applyPageInsets(root) { keyboard ->
            start.visibility = if (keyboard) View.GONE else View.VISIBLE
        }
        if (Build.VERSION.SDK_INT < 30) {
            val visible = Rect()
            root.viewTreeObserver.addOnGlobalLayoutListener {
                root.getWindowVisibleDisplayFrame(visible)
                val keyboard = root.rootView.height - visible.height() > dp(100)
                start.visibility = if (keyboard) View.GONE else View.VISIBLE
            }
        }
        column.requestFocus()
        return root
    }

    private fun showScriptLimit() {
        Toast.makeText(this, "Script limit: 200,000 characters. Shorten the text and try again.", Toast.LENGTH_LONG).show()
    }

    private fun showOverlaySettings() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(scriptField.windowToken, 0)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_PADDING_DP), 0, dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP))
        }

        spinnerRow(column, "Font", Options.fonts.map { it.label }, prefs.fontIndex) { prefs.fontIndex = it }
        spinnerRow(column, "Text color", Options.colors.map { it.label }, prefs.colorIndex) { prefs.colorIndex = it }
        seekRow(column, "Text size", Limits.SIZE_MIN, Limits.SIZE_MAX, prefs.textSizeSp, { "$it sp" }) {
            prefs.textSizeSp = it
        }
        seekRow(column, "Scroll speed", Limits.SPEED_MIN, Limits.SPEED_MAX, prefs.speedDp, { "$it" }) {
            prefs.speedDp = it
        }
        seekRow(column, "Window opacity", Limits.OPACITY_MIN, Limits.OPACITY_MAX, prefs.opacityPercent, { "$it%" }) {
            prefs.opacityPercent = it
        }
        spinnerRow(
            column,
            "Countdown before scrolling",
            Options.countdowns.map { if (it == 0) "Off" else "$it seconds" },
            Options.countdowns.indexOf(prefs.countdownSec).coerceAtLeast(0)
        ) { prefs.countdownSec = Options.countdowns[it] }

        column.addView(CheckBox(this).apply {
            text = "Mirror text (for a glass teleprompter rig)"
            setTextColor(Brand.text(this@MainActivity))
            buttonTintList = ColorStateList.valueOf(Brand.accent(this@MainActivity))
            isChecked = prefs.mirror
            setOnCheckedChangeListener { _, checked -> prefs.mirror = checked }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        column.addView(bodyText(
            if (Settings.canDrawOverlays(this)) "Display over other apps: allowed"
            else "Tap Start overlay from the editor to allow display over other apps.",
            muted = true
        ).apply { setPadding(0, dp(16), 0, dp(8)) })
        column.addView(actionButton("Stop overlay", primary = false) { stopOverlay() })
        column.addView(bodyText(
            "Changes made here apply the next time you start the overlay. " +
                "Speed and text size can also be changed live on the overlay.",
            muted = true
        ).apply { setPadding(0, dp(12), 0, 0) })

        AlertDialog.Builder(this)
            .setTitle("Overlay settings")
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Decorative. The title next to it carries the meaning.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(LOGO_RADIUS_DP).toFloat())
                }
            }
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(LOGO_SIZE_DP), dp(LOGO_SIZE_DP)))

        val title = TextView(this).apply {
            text = "Prompter"
            setPadding(dp(12), 0, dp(8), 0)
            setTextColor(Brand.text(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(actionButton("Settings", primary = false) { showOverlaySettings() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun bodyText(value: String, muted: Boolean): TextView = TextView(this).apply {
        text = value
        setTextColor(if (muted) Brand.muted(this@MainActivity) else Brand.text(this@MainActivity))
    }

    private fun fieldLabel(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(Brand.text(this@MainActivity))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            minHeight = dp(MIN_TOUCH_DP)
            Brand.styleButton(this@MainActivity, this, primary)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }

    private fun spinnerRow(
        parent: LinearLayout,
        label: String,
        items: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val labelView = fieldLabel(label)
        val spinner = Spinner(this).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected)
            backgroundTintList = ColorStateList.valueOf(Brand.accent(this@MainActivity))
            minimumHeight = dp(MIN_TOUCH_DP)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = onPick(position)
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        labelView.labelFor = spinner.id
        parent.addView(labelView)
        parent.addView(spinner)
    }

    private fun seekRow(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val labelView = fieldLabel("$label: ${format(value)}")
        val seek = SeekBar(this).apply {
            id = View.generateViewId()
            this.min = min
            this.max = max
            progress = value
            Brand.styleSeekBar(this@MainActivity, this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    labelView.text = "$label: ${format(progress)}"
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        labelView.labelFor = seek.id
        parent.addView(labelView)
        parent.addView(seek)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val PAGE_PADDING_DP = 16
        const val FIELD_PADDING_DP = 12
        const val FIELD_RADIUS_DP = 13
        const val MIN_TOUCH_DP = 48
        const val LOGO_SIZE_DP = 32
        const val LOGO_RADIUS_DP = 8
        const val TITLE_SP = 23f
    }
}
