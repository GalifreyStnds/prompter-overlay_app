package us.jeremycombs.prompter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
 * Ads, if added later, belong in [adContainer] only. Never in the overlay window.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scriptField: EditText
    private lateinit var permissionStatus: TextView
    private lateinit var adContainer: FrameLayout
    private var askedForNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onPause() {
        prefs.script = scriptField.text.toString()
        super.onPause()
    }

    // ---------- actions ----------

    private fun startOverlay() {
        prefs.script = scriptField.text.toString()
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

    private fun refreshPermissionStatus() {
        permissionStatus.text = if (Settings.canDrawOverlays(this)) {
            "Display over other apps: allowed"
        } else {
            "Display over other apps: not allowed yet. Tap Start overlay to open the setting."
        }
    }

    // ---------- UI ----------

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Brand.bg(this@MainActivity))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(left, top, right, bottom)
            insets
        }

        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP), dp(PAGE_PADDING_DP))
        }
        scroller.addView(column)
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        column.addView(buildHeader())
        column.addView(bodyText(
            "Start the overlay, open your camera app, then press Play on the floating window. " +
                "Drag the top bar to move it. Drag the bottom-right corner to resize it.",
            muted = true
        ).apply { setPadding(0, dp(12), 0, dp(16)) })

        val scriptLabel = fieldLabel("Script")
        scriptField = EditText(this).apply {
            id = View.generateViewId()
            setText(prefs.script)
            hint = "Paste or type your script"
            gravity = Gravity.TOP or Gravity.START
            minLines = SCRIPT_MIN_LINES
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Brand.text(this@MainActivity))
            setHintTextColor(Brand.muted(this@MainActivity))
            background = Brand.rounded(Brand.panel(this@MainActivity), dp(FIELD_RADIUS_DP).toFloat())
            setPadding(dp(FIELD_PADDING_DP), dp(FIELD_PADDING_DP), dp(FIELD_PADDING_DP), dp(FIELD_PADDING_DP))
        }
        scriptLabel.labelFor = scriptField.id
        column.addView(scriptLabel)
        column.addView(scriptField)

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

        permissionStatus = bodyText("", muted = true).apply { setPadding(0, dp(16), 0, dp(8)) }
        column.addView(permissionStatus)

        column.addView(actionButton("Start overlay", primary = true) { startOverlay() })
        column.addView(actionButton("Stop overlay", primary = false) { stopOverlay() })
        column.addView(bodyText(
            "Changes made here apply the next time you start the overlay. " +
                "Speed and text size can also be changed live on the overlay.",
            muted = true
        ).apply { setPadding(0, dp(12), 0, 0) })

        // Future ad banner goes in this container. Hidden until an ad SDK is added.
        adContainer = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(adContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return root
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

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "Prompter"
            setTextColor(Brand.text(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "Floating teleprompter"
            setTextColor(Brand.accent(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_SP)
        })
        row.addView(titles)
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
            Brand.styleButton(this@MainActivity, this, primary, dp(BUTTON_RADIUS_DP).toFloat(), dp(FOCUS_RING_DP))
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
        const val SCRIPT_MIN_LINES = 8
        const val FIELD_PADDING_DP = 12
        const val FIELD_RADIUS_DP = 10
        const val BUTTON_RADIUS_DP = 10
        const val FOCUS_RING_DP = 2
        const val MIN_TOUCH_DP = 48
        const val LOGO_SIZE_DP = 64
        const val LOGO_RADIUS_DP = 16
        const val TITLE_SP = 26f
        const val SUBTITLE_SP = 14f
    }
}
