package com.pfclone.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val prefs by lazy { getSharedPreferences("pfc", Context.MODE_PRIVATE) }

    private val defaultScript = """pfc.log("» Спам-кликер + цвет + 6 нажатий «");

var spamX = 517,  spamY = 1264;
var colX  = 963,  colY  = 319;
var targetColor = 2471091;

var b1x = 945, b1y = 296;
var b2x = 829, b2y = 2345;
var b3x = 727, b3y = 1892;
var b4x = 544, b4y = 1414;
var b5x = 82,  b5y = 224;
var b6x = 532, b6y = 1263;

var spamDelay = 1;
var stepDelay = 150;
var afterDelay = 300;

pfc.startScreenCapture(1);

while (!EXIT) {
    if (pfc.getColor(colX, colY) == targetColor) {
        pfc.click(colX, colY);   pfc.sleep(stepDelay);
        pfc.click(b1x, b1y);     pfc.sleep(stepDelay);
        pfc.click(b2x, b2y);     pfc.sleep(stepDelay);
        pfc.click(b3x, b3y);     pfc.sleep(stepDelay);
        pfc.click(b4x, b4y);     pfc.sleep(stepDelay);
        pfc.click(b5x, b5y);     pfc.sleep(stepDelay);
        pfc.click(b6x, b6y);     pfc.sleep(stepDelay);
        pfc.sleep(afterDelay);
        continue;
    }
    pfc.click(spamX, spamY);
    pfc.sleep(spamDelay);
}
"""

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val i = Intent(this, ScriptService::class.java)
                    .putExtra(ScriptService.EXTRA_CODE, res.resultCode)
                    .putExtra(ScriptService.EXTRA_DATA, res.data)
                    .putExtra(ScriptService.EXTRA_SCRIPT, editor.text.toString())
                startForegroundService(i)
                moveTaskToBack(true)   // свернуть, чтобы скрипт видел нужный экран
            } else toast("Доступ к записи экрана не выдан")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            setText(prefs.getString("script", defaultScript))
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        val row1 = LinearLayout(this)
        row1.addView(btn("1. Спец.возм.") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        row1.addView(btn("2. Поверх окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(row1, LinearLayout.LayoutParams(-1, -2))

        val row2 = LinearLayout(this)
        row2.addView(btn("Сохранить") { save(); toast("Сохранено") })
        row2.addView(btn("▶ СТАРТ") { start() })
        root.addView(row2, LinearLayout.LayoutParams(-1, -2))

        logView = TextView(this).apply { textSize = 12f }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 260))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        LogBus.listener = { s ->
            logView.append(s + "\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onPause() { LogBus.listener = null; save(); super.onPause() }

    private fun btn(t: String, a: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { a() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    private fun save() { prefs.edit().putString("script", editor.text.toString()).apply() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun start() {
        if (ScriptService.running) { toast("Уже запущено"); return }
        if (ClickService.instance == null) { toast("Включи службу «PFC Clone» в спец. возможностях"); return }
        if (!Settings.canDrawOverlays(this)) toast("Без разрешения «Поверх окон» не будет кнопки СТОП (есть стоп в шторке)")
        save()
        logView.text = ""
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
