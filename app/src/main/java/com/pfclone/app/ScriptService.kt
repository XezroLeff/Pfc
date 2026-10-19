package com.pfclone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class CrossView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun onDraw(c: Canvas) {
        val w = width / 2f
        val h = height / 2f
        val r = width / 2f - 4f
        val gap = 10f
        for ((color, sw) in listOf(Color.BLACK to 6f, Color.WHITE to 3f)) {
            p.color = color
            p.strokeWidth = sw
            c.drawCircle(w, h, r, p)
            c.drawLine(w, 2f, w, h - gap, p)
            c.drawLine(w, h + gap, w, height - 2f, p)
            c.drawLine(2f, h, w - gap, h, p)
            c.drawLine(w + gap, h, width - 2f, h, p)
        }
    }
}

class ScriptService : Service() {

    companion object {
        const val ACTION_STOP = "com.pfclone.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false
    }

    @Volatile var stopped = false
    @Volatile var capture: ScreenCapture? = null
    @Volatile private var scriptActive = false
    @Volatile private var scope: Scriptable? = null
    private var projection: MediaProjection? = null
    private var scriptThread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var density = 1f

    private var panel: View? = null
    private var playBtn: TextView? = null

    private var cross: View? = null
    private var card: View? = null
    private var cardText: TextView? = null
    private var swatch: View? = null
    private var px = 0
    private var py = 0
    private val tick = object : Runnable {
        override fun run() { updateInfo(); ui.postDelayed(this, 250) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        startAsForeground()
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        running = true

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything() }
        }, ui)
        projection = proj

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        capture = ScreenCapture(proj, dm.widthPixels, dm.heightPixels, dm.densityDpi).also { it.start() }

        if (!Settings.canDrawOverlays(this)) {
            LogBus.log("⚠ Нет разрешения «Поверх окон»")
            stopEverything()
            return START_NOT_STICKY
        }
        showPanel()
        LogBus.log("✅ Панель включена: ▶ запускает сохранённый скрипт, 🎯 — пипетка")
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pfc", "PFC", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ScriptService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "pfc")
            .setContentTitle("Панель PFC активна")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "ВЫКЛЮЧИТЬ", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun bg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun overlayLp(w: Int = WindowManager.LayoutParams.WRAP_CONTENT,
                          h: Int = WindowManager.LayoutParams.WRAP_CONTENT) =
        WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun label(t: String, color: Int, size: Float = 22f) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun attachDrag(handle: View, target: View, lp: WindowManager.LayoutParams) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(target, lp)
                }
            }
            true
        }
    }

    private fun showPanel() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(0xE6202124.toInt(), 24)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val lp = overlayLp().apply { x = dp(8); y = dp(120) }

        val handle = label("≡", 0xFFAAAAAA.toInt())
        val play = label("▶", 0xFF4CAF50.toInt())
        val pick = label("🎯", Color.WHITE)
        val close = label("✕", 0xFFEF5350.toInt())
        playBtn = play

        play.setOnClickListener { toggleScript() }
        pick.setOnClickListener { if (cross == null) showPicker() else hidePicker() }
        close.setOnClickListener { stopEverything() }

        row.addView(handle); row.addView(play); row.addView(pick); row.addView(close)
        attachDrag(handle, row, lp)
        wm.addView(row, lp)
        panel = row
    }

    private fun updatePlayButton() {
        playBtn?.apply {
            if (scriptActive) { text = "■"; setTextColor(0xFFEF5350.toInt()) }
            else { text = "▶"; setTextColor(0xFF4CAF50.toInt()) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun toggleScript() {
        if (scriptActive) {
            stopped = true
            try { scope?.let { ScriptableObject.putProperty(it, "EXIT", true) } } catch (_: Throwable) {}
            scriptThread?.interrupt()
            return
        }
        if (scriptThread?.isAlive == true) { toast("Подожди секунду…"); return }
        val src = getSharedPreferences("pfc", Context.MODE_PRIVATE).getString("script", "") ?: ""
        if (src.isBlank()) { toast("Скрипт пуст: открой приложение и нажми «Сохранить»"); return }
        startScript(src)
    }

    private fun startScript(src: String) {
        stopped = false
        scriptActive = true
        updatePlayButton()
        scriptThread = Thread {
            val cx = JsContext.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = JsContext.VERSION_ES6
                cx.wrapFactory.isJavaPrimitiveWrap = false
                val sc = cx.initStandardObjects()
                scope = sc
                ScriptableObject.putProperty(sc, "pfc", JsContext.javaToJS(Pfc(this), sc))
                ScriptableObject.putProperty(sc, "Time", JsContext.javaToJS(TimeApi(), sc))
                ScriptableObject.putProperty(sc, "EXIT", false)
                LogBus.log("▶ Скрипт запущен")
                cx.evaluateString(sc, src, "script", 1, null)
                LogBus.log("■ Скрипт завершён")
            } catch (e: Throwable) {
                if (!stopped) LogBus.log("⚠ Ошибка: " + (e.message ?: e.toString()))
                else LogBus.log("■ Остановлено")
            } finally {
                JsContext.exit()
                scriptActive = false
                ui.post { updatePlayButton() }
            }
        }.also { it.start() }
    }

    private fun showPicker() {
        val size = dp(72)
        val dm = resources.displayMetrics

        val c = CrossView(this)
        val clp = overlayLp(size, size).apply {
            x = (dm.widthPixels - size) / 2
            y = (dm.heightPixels - size) / 2
        }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var bx = 0; var by = 0
        c.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = clp.x; oy = clp.y; bx = px; by = py }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    clp.x = ox + dx; clp.y = oy + dy
                    px = bx + dx; py = by + dy
                    wm.updateViewLayout(c, clp)
                    updateInfo()
                }
            }
            true
        }
        wm.addView(c, clp)
        cross = c
        c.postDelayed({
            val loc = IntArray(2)
            c.getLocationOnScreen(loc)
            px = loc[0] + size / 2
            py = loc[1] + size / 2
            updateInfo()
        }, 150)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xEE202124.toInt(), 16)
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        val sw = View(this)
        val txt = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.MONOSPACE
            text = "…"
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
        top.addView(txt)
        box.addView(top)

        fun nudge(dx: Int, dy: Int) {
            px += dx; py += dy
            clp.x += dx; clp.y += dy
            wm.updateViewLayout(c, clp)
            updateInfo()
        }
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for ((t, d) in listOf("◀" to (-1 to 0), "▲" to (0 to -1), "▼" to (0 to 1), "▶" to (1 to 0))) {
            nav.addView(label(t, Color.WHITE, 18f).apply { setOnClickListener { nudge(d.first, d.second) } })
        }
        box.addView(nav)

        val acts = LinearLayout(this).apply { gravity = Gravity.CENTER }
        acts.addView(label("Копировать", 0xFF4CAF50.toInt(), 15f).apply {
            setOnClickListener {
                val color = capture?.pixel(px, py) ?: 0
                val s = "$px, $py, $color"
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("pfc", s))
                LogBus.log("📍 $s")
                toast("Скопировано: $s")
            }
        })
        acts.addView(label("Закрыть", 0xFFEF5350.toInt(), 15f).apply { setOnClickListener { hidePicker() } })
        box.addView(acts)

        val blp = overlayLp().apply { x = dp(16); y = dp(60) }
        attachDrag(txt, box, blp)
        wm.addView(box, blp)
        card = box
        cardText = txt
        swatch = sw
        ui.post(tick)
    }

    private fun updateInfo() {
        val color = capture?.pixel(px, py) ?: 0
        cardText?.text = "X: $px   Y: $py\nЦвет: $color\n" + String.format("#%06X", color)
        swatch?.setBackgroundColor(0xFF000000.toInt() or color)
    }

    private fun hidePicker() {
        ui.removeCallbacks(tick)
        for (v in listOf(cross, card)) {
            if (v != null) try { wm.removeView(v) } catch (_: Throwable) {}
        }
        cross = null; card = null; cardText = null; swatch = null
    }

    private fun stopEverything() {
        if (!running) { stopSelf(); return }
        running = false
        stopped = true
        try { scope?.let { ScriptableObject.putProperty(it, "EXIT", true) } } catch (_: Throwable) {}
        scriptThread?.interrupt()
        hidePicker()
        panel?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        panel = null; playBtn = null
        capture?.stop(); capture = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        LogBus.log("Панель выключена")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopEverything()
        super.onDestroy()
    }
}
