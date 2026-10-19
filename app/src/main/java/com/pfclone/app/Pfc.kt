package com.pfclone.app

class ScriptStopped : RuntimeException("stopped")

/** Объект `pfc`, доступный из скрипта. */
class Pfc(private val svc: ScriptService) {

    private fun check() { if (svc.stopped) throw ScriptStopped() }

    fun log(s: Any?) { LogBus.log(s?.toString() ?: "null") }

    fun sleep(ms: Int) {
        check()
        try { if (ms > 0) Thread.sleep(ms.toLong()) } catch (e: InterruptedException) { throw ScriptStopped() }
        check()
    }

    fun click(x: Int, y: Int) {
        check()
        ClickService.instance?.stroke(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 1)
    }

    fun longClick(x: Int, y: Int, ms: Int) {
        check()
        ClickService.instance?.stroke(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), ms.toLong())
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        check()
        ClickService.instance?.stroke(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms.toLong())
    }

    fun startScreenCapture(mode: Int) { check(); svc.capture?.start() }
    fun stopScreenCapture() {}

    fun getColor(x: Int, y: Int): Int { check(); return svc.capture?.pixel(x, y) ?: 0 }

    fun getContoursCount(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        check(); return svc.capture?.contours(x1, y1, x2, y2) ?: 0
    }
}

class TimeApi {
    fun getMillis(): Double = System.currentTimeMillis().toDouble()
}
