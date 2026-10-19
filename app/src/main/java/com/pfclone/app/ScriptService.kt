package com.pfclone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class ScriptService : Service() {

    companion object {
        const val ACTION_STOP = "com.pfclone.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SCRIPT = "script"
        @Volatile var running = false
    }

    @Volatile var stopped = false
    @Volatile var capture: ScreenCapture? = null
    private var projection: MediaProjection? = null
    private var thread: Thread? = null
    private var overlay: Button? = null
    @Volatile private var scope: Scriptable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true

        startAsForeground()

        val code = intent!!.getIntExtra(EXTRA_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)!!
        val script = intent.getStringExtra(EXTRA_SCRIPT) ?: ""

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        capture = ScreenCapture(proj, dm.widthPixels, dm.heightPixels, dm.densityDpi)

        showOverlay()
        startScript(script)
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
            .setContentTitle("Скрипт работает")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "СТОП", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "■ СТОП"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCCD32F2F.toInt())
            setOnClickListener { stopEverything() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        wm.addView(b, lp)
        overlay = b
    }

    private fun startScript(src: String) {
        thread = Thread {
            val cx = JsContext.enter()
            try {
                cx.optimizationLevel = -1               // Android: только интерпретатор
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
                Handler(Looper.getMainLooper()).post { stopEverything() }
            }
        }.also { it.start() }
    }

    private fun stopEverything() {
        if (!running) return
        running = false
        stopped = true
        try { scope?.let { ScriptableObject.putProperty(it, "EXIT", true) } } catch (_: Throwable) {}
        thread?.interrupt()
        capture?.stop(); capture = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        overlay?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Throwable) {}
        }
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }
}
