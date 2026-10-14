package us.jeremycombs.prompter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
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
    private lateinit var status: TextView
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
            .setSmallIcon(android.R.drawable.ic_media_play)
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
            setColor(Color.BLACK)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            this.background = this@OverlayService.background
            clipToOutline = true
        }

        status = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(GRIP_COLOR)
            contentDescription = "Drag to move the prompter"
            setOnTouchListener(dragListener())
        }
        container.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GRIP_HEIGHT_DP)))

        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = controlButton(ctx, "Play", "Play or pause scrolling") { togglePlay() }
        controls.addView(playButton, controlParams())
        controls.addView(controlButton(ctx, "Top", "Jump back to the start of the script") { jumpToTop() }, controlParams())
        controls.addView(controlButton(ctx, "Slow", "Scroll slower") { changeSpeed(-Limits.SPEED_STEP) }, controlParams())
        controls.addView(controlButton(ctx, "Fast", "Scroll faster") { changeSpeed(Limits.SPEED_STEP) }, controlParams())
        controls.addView(controlButton(ctx, "A-", "Smaller text") { changeSize(-Limits.SIZE_STEP) }, controlParams())
        controls.addView(controlButton(ctx, "A+", "Larger text") { changeSize(Limits.SIZE_STEP) }, controlParams())
        controls.addView(controlButton(ctx, "X", "Close the prompter") { stopSelf() }, controlParams())
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

        val marker = View(ctx).apply { setBackgroundColor(MARKER_COLOR) }
        area.addView(marker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MARKER_HEIGHT_DP)))

        val handle = TextView(ctx).apply {
            text = "◢"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(HANDLE_COLOR)
            contentDescription = "Drag to resize the prompter"
            setOnTouchListener(resizeListener())
        }
        area.addView(
            handle,
            FrameLayout.LayoutParams(dp(HANDLE_SIZE_DP), dp(HANDLE_SIZE_DP), Gravity.BOTTOM or Gravity.END)
        )

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

    private fun controlButton(ctx: ContextThemeWrapper, label: String, description: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { onClick() }
        }

    private fun controlParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    private fun applyStyle() {
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
        body.setTextColor(Options.colors[prefs.colorIndex].argb)
        body.typeface = Typeface.create(Options.fonts[prefs.fontIndex].family, Typeface.BOLD)
        body.setLineSpacing(0f, LINE_SPACING)
        scroll.scaleX = if (prefs.mirror) -1f else 1f
        background.setColor(Color.argb(prefs.opacityPercent * 255 / 100, 0, 0, 0))
        updateStatus()
    }

    private fun updateStatus() {
        status.text = "Drag to move   Speed ${prefs.speedDp}   Size ${prefs.textSizeSp}"
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
        updateStatus()
    }

    private fun changeSize(delta: Int) {
        prefs.textSizeSp = prefs.textSizeSp + delta
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
        updateStatus()
    }

    private fun jumpToTop() {
        scroll.scrollTo(0, 0)
        position = 0f
        lastSetY = 0
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
        var remaining = seconds
        val tick = object : Runnable {
            override fun run() {
                if (!counting) return
                if (remaining <= 0) {
                    counting = false
                    beginPlayback()
                    return
                }
                status.text = "Starting in $remaining"
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
        updateStatus()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun pausePlayback() {
        playing = false
        counting = false
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        playButton.text = "Play"
        updateStatus()
    }

    private companion object {
        const val TAG = "PrompterOverlay"
        const val CHANNEL_ID = "prompter_overlay"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "us.jeremycombs.prompter.STOP"

        const val DEFAULT_WIDTH_RATIO = 0.92f
        const val DEFAULT_HEIGHT_RATIO = 0.4f
        const val DEFAULT_TOP_DP = 48
        const val MIN_WIDTH_DP = 220
        const val MIN_HEIGHT_DP = 160
        const val CORNER_DP = 12
        const val GRIP_HEIGHT_DP = 32
        const val CONTROL_HEIGHT_DP = 48
        const val HANDLE_SIZE_DP = 44
        const val TEXT_PADDING_DP = 16
        const val MARKER_HEIGHT_DP = 2
        const val MARKER_POSITION_RATIO = 0.3f
        const val LINE_SPACING = 1.25f

        const val GRIP_COLOR = 0xFF2A2A31.toInt()
        const val MARKER_COLOR = 0x99FFD54A.toInt()
        const val HANDLE_COLOR = 0x66000000

        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MS_PER_SECOND = 1000L
    }
}
