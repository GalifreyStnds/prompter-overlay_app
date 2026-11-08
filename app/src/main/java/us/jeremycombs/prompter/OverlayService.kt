package us.jeremycombs.prompter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Foreground service that owns the floating teleprompter window.
 * The window is draggable by its top bar, resizable by its bottom-right corner,
 * and scrolls the saved script at the saved speed.
 */
class OverlayService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var background: GradientDrawable
    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var playButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var counting = false
    private var position = 0f
    private var lastSetY = 0
    private var lastFrameNanos = 0L

    private val density: Float get() = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).roundToInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Overlay permission missing, stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            showOverlay()
        } else {
            val script = prefs.script
            if (!script.contentEquals(body.text)) {
                pausePlayback()
                body.text = script
                jumpTo()
                // Cancel any fling that was still scrolling the previous script.
                scroll.fling(0)
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWindow()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay view was already removed")
            }
        }
        root = null
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Prompter overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_prompter)
            .setColor(Brand.accent(this))
            .setContentTitle("Prompter is running")
            .setContentText("Tap to open settings")
            .setContentIntent(open)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    // ---------- window ----------

    private fun showOverlay() {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val width = if (prefs.winW > 0) prefs.winW else (screenW * DEFAULT_WIDTH_RATIO).toInt()
        val height = if (prefs.winH > 0) prefs.winH else (screenH * DEFAULT_HEIGHT_RATIO).toInt()

        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = if (prefs.winX >= 0) prefs.winX else (screenW - width) / 2
        lp.y = if (prefs.winY >= 0) prefs.winY else dp(DEFAULT_TOP_DP)

        val view = buildView()
        params = lp
        clampWindow(lp)
        try {
            wm.addView(view, lp)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not add overlay window", e)
            params = null
            stopSelf()
            return
        }
        root = view
        applyStyle()
    }

    private fun clampWindow(lp: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        lp.width = ScrollMath.clamp(lp.width, dp(MIN_WIDTH_DP), metrics.widthPixels)
        lp.height = ScrollMath.clamp(lp.height, dp(MIN_HEIGHT_DP), metrics.heightPixels)
        lp.x = ScrollMath.clamp(lp.x, 0, metrics.widthPixels - lp.width)
        lp.y = ScrollMath.clamp(lp.y, 0, metrics.heightPixels - lp.height)
    }

    private fun applyWindow() {
        val view = root ?: return
        val lp = params ?: return
        clampWindow(lp)
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay view is not attached")
        }
    }

    private fun persistGeometry() {
        val lp = params ?: return
        prefs.winX = lp.x
        prefs.winY = lp.y
        prefs.winW = lp.width
        prefs.winH = lp.height
    }

    // ---------- view construction ----------

    private fun buildView(): View {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)

        background = GradientDrawable().apply {
            cornerRadius = dp(CORNER_DP).toFloat()
            setColor(Brand.bg(this@OverlayService))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            this.background = this@OverlayService.background
            clipToOutline = true
        }

        val grip = FrameLayout(ctx).apply {
            setBackgroundColor(Brand.panel(this@OverlayService))
            contentDescription = "Drag to move the prompter"
            setOnTouchListener(dragListener())
        }
        val gripMark = View(ctx).apply {
            background = Brand.rounded(Brand.muted(this@OverlayService), dp(GRIP_MARK_HEIGHT_DP).toFloat())
        }
        grip.addView(gripMark, FrameLayout.LayoutParams(dp(GRIP_MARK_WIDTH_DP), dp(GRIP_MARK_HEIGHT_DP), Gravity.CENTER))
        countdownLabel = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Brand.text(this@OverlayService))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Brand.panel(this@OverlayService))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.INVISIBLE
        }
        grip.addView(countdownLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GRIP_HEIGHT_DP)))

        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val speedControls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val moreControls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = controlButton(ctx, "Play", "Play scrolling", primary = true) { togglePlay() }
        controls.addView(playButton, controlParams(PLAY_WIDTH_DP))
        controls.addView(controlButton(ctx, "↶", "Rewind paragraph") {
            speedControls.visibility = View.GONE
            moreControls.visibility = View.GONE
            rewindParagraph()
        }, controlParams())
        controls.addView(controlButton(ctx, "Speed", "Scroll speed controls") { toggleMenu(speedControls, moreControls) }, controlParams())
        controls.addView(controlButton(ctx, "More", "Top, text size and close controls") { toggleMenu(moreControls, speedControls) }, controlParams())
        speedControls.addView(controlButton(ctx, "Slower", "Scroll slower") { changeSpeed(-Limits.SPEED_STEP) }, controlParams())
        speedControls.addView(controlButton(ctx, "Faster", "Scroll faster") { changeSpeed(Limits.SPEED_STEP) }, controlParams())
        moreControls.addView(controlButton(ctx, "Top", "Jump back to the start of the script") {
            moreControls.visibility = View.GONE
            jumpTo()
        }, controlParams())
        moreControls.addView(controlButton(ctx, "A-", "Smaller text") { changeSize(-Limits.SIZE_STEP) }, controlParams())
        moreControls.addView(controlButton(ctx, "A+", "Larger text") { changeSize(Limits.SIZE_STEP) }, controlParams())
        moreControls.addView(controlButton(ctx, "Close", "Close the prompter") { stopSelf() }, controlParams())
        container.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL_HEIGHT_DP)))

        val area = FrameLayout(ctx)
        scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        body = TextView(ctx).apply {
            text = prefs.script
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(TEXT_PADDING_DP), dp(TEXT_PADDING_DP), dp(TEXT_PADDING_DP), 0)
        }
        scroll.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        area.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val marker = FrameLayout(ctx).apply {
            for (edge in listOf(Gravity.LEFT, Gravity.RIGHT)) {
                val tick = View(ctx).apply {
                    setBackgroundColor(Brand.withAlpha(Brand.accent(this@OverlayService), MARKER_ALPHA))
                }
                addView(tick, FrameLayout.LayoutParams(dp(MARKER_WIDTH_DP), dp(MARKER_HEIGHT_DP), edge))
            }
        }
        area.addView(marker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MARKER_HEIGHT_DP)))

        val handle = TextView(ctx).apply {
            text = "◢"
            gravity = Gravity.CENTER
            setTextColor(Brand.accent(this@OverlayService))
            setBackgroundColor(Brand.withAlpha(Brand.bg(this@OverlayService), HANDLE_ALPHA))
            contentDescription = "Drag to resize the prompter"
            setOnTouchListener(resizeListener())
        }
        area.addView(
            handle,
            FrameLayout.LayoutParams(dp(HANDLE_SIZE_DP), dp(HANDLE_SIZE_DP), Gravity.BOTTOM or Gravity.END)
        )
        // Layer menus over the script so opening one preserves the window and scroll viewport.
        for (menu in listOf(speedControls, moreControls)) {
            menu.setBackgroundColor(Brand.bg(this))
            menu.visibility = View.GONE
            area.addView(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL_HEIGHT_DP)))
        }

        area.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            area.post {
                val markerParams = marker.layoutParams as FrameLayout.LayoutParams
                val target = (h * MARKER_POSITION_RATIO).roundToInt()
                if (markerParams.topMargin != target) {
                    markerParams.topMargin = target
                    marker.layoutParams = markerParams
                }
                // Bottom padding equals the visible height so the last line can reach the top.
                if (body.paddingBottom != h) {
                    body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, h)
                }
            }
        }

        container.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return container
    }

    private fun controlButton(
        ctx: ContextThemeWrapper,
        label: String,
        description: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ): Button =
        Button(ctx).apply {
            text = label
            contentDescription = description
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            Brand.styleButton(this@OverlayService, this, primary, dp(BUTTON_RADIUS_DP).toFloat(), dp(FOCUS_RING_DP))
            setOnClickListener { onClick() }
        }

    private fun controlParams(widthDp: Int = CONTROL_WIDTH_DP) =
        LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(CONTROL_MARGIN_DP), 0, dp(CONTROL_MARGIN_DP), 0)
        }

    private fun toggleMenu(menu: View, other: View) {
        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        other.visibility = View.GONE
    }

    private fun applyStyle() {
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
        body.setTextColor(Options.colors[prefs.colorIndex].argb)
        body.typeface = Typeface.create(Options.fonts[prefs.fontIndex].family, Typeface.BOLD)
        body.setLineSpacing(0f, LINE_SPACING)
        scroll.scaleX = if (prefs.mirror) -1f else 1f
        background.setColor(Brand.withAlpha(Brand.bg(this), prefs.opacityPercent * ALPHA_MAX / PERCENT_MAX))
    }

    // ---------- touch handling ----------

    private fun dragListener(): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        return View.OnTouchListener { v, event ->
            val lp = params ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).roundToInt()
                    lp.y = startY + (event.rawY - touchY).roundToInt()
                    applyWindow()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    persistGeometry()
                    true
                }
                else -> false
            }
        }
    }

    private fun resizeListener(): View.OnTouchListener {
        var startW = 0
        var startH = 0
        var touchX = 0f
        var touchY = 0f
        return View.OnTouchListener { v, event ->
            val lp = params ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = lp.width
                    startH = lp.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = startW + (event.rawX - touchX).roundToInt()
                    lp.height = startH + (event.rawY - touchY).roundToInt()
                    applyWindow()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    persistGeometry()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- controls ----------

    private fun changeSpeed(delta: Int) {
        prefs.speedDp = prefs.speedDp + delta
    }

    private fun changeSize(delta: Int) {
        prefs.textSizeSp = prefs.textSizeSp + delta
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
    }

    private fun rewindParagraph() {
        pausePlayback()
        val layout = body.layout ?: return
        val markerY = (scroll.height * MARKER_POSITION_RATIO).roundToInt()
        val target = ScrollMath.rewindTarget(layout.text, scroll.scrollY) { offset ->
            val line = layout.getLineForOffset(offset)
            (layout.getLineTop(line) + body.totalPaddingTop - markerY).coerceAtLeast(0)
        }
        jumpTo(target)
        // Reset any manual fling at the new reading position.
        scroll.fling(0)
    }

    private fun jumpTo(y: Int = 0) {
        scroll.scrollTo(0, y)
        lastSetY = scroll.scrollY
        position = lastSetY.toFloat()
    }

    // ---------- playback ----------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val dt = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND
            lastFrameNanos = frameTimeNanos
            // If the user dragged the text by hand, continue from where they left it.
            if (scroll.scrollY != lastSetY) position = scroll.scrollY.toFloat()
            val max = (body.height - scroll.height).coerceAtLeast(0).toFloat()
            position = ScrollMath.advance(position, prefs.speedDp * density, dt, max)
            scroll.scrollTo(0, position.roundToInt())
            lastSetY = scroll.scrollY
            if (ScrollMath.atEnd(position, max)) {
                pausePlayback()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun togglePlay() {
        if (playing || counting) pausePlayback() else startWithCountdown()
    }

    private fun startWithCountdown() {
        val seconds = prefs.countdownSec
        if (seconds <= 0) {
            beginPlayback()
            return
        }
        counting = true
        playButton.text = "Stop"
        playButton.contentDescription = "Cancel countdown"
        var remaining = seconds
        val tick = object : Runnable {
            override fun run() {
                if (!counting) return
                if (remaining <= 0) {
                    counting = false
                    beginPlayback()
                    return
                }
                countdownLabel.text = "Starting in $remaining"
                countdownLabel.visibility = View.VISIBLE
                remaining -= 1
                handler.postDelayed(this, MS_PER_SECOND)
            }
        }
        handler.post(tick)
    }

    private fun beginPlayback() {
        position = scroll.scrollY.toFloat()
        lastSetY = scroll.scrollY
        lastFrameNanos = 0L
        playing = true
        playButton.text = "Pause"
        playButton.contentDescription = "Pause scrolling"
        countdownLabel.visibility = View.INVISIBLE
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun pausePlayback() {
        playing = false
        counting = false
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        playButton.text = "Play"
        playButton.contentDescription = "Play scrolling"
        countdownLabel.visibility = View.INVISIBLE
    }

    private companion object {
        const val TAG = "PrompterOverlay"
        const val CHANNEL_ID = "prompter_overlay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "us.jeremycombs.prompter.STOP"

        const val DEFAULT_WIDTH_RATIO = 0.92f
        const val DEFAULT_HEIGHT_RATIO = 0.4f
        const val DEFAULT_TOP_DP = 96
        const val MIN_WIDTH_DP = 220
        const val MIN_HEIGHT_DP = 160
        const val CORNER_DP = 12
        const val GRIP_HEIGHT_DP = 24
        const val GRIP_MARK_WIDTH_DP = 40
        const val GRIP_MARK_HEIGHT_DP = 4
        const val PLAY_WIDTH_DP = 64
        const val CONTROL_WIDTH_DP = 48
        const val CONTROL_HEIGHT_DP = 48
        const val HANDLE_SIZE_DP = 48
        const val TEXT_PADDING_DP = 16
        const val MARKER_WIDTH_DP = 8
        const val MARKER_HEIGHT_DP = 2
        const val MARKER_POSITION_RATIO = 0.3f
        const val LINE_SPACING = 1.25f

        const val BUTTON_RADIUS_DP = 6
        const val FOCUS_RING_DP = 2
        const val CONTROL_MARGIN_DP = 1
        const val MARKER_ALPHA = 0xCC
        const val HANDLE_ALPHA = 0xAA
        const val ALPHA_MAX = 255
        const val PERCENT_MAX = 100

        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MS_PER_SECOND = 1000L
    }
}
