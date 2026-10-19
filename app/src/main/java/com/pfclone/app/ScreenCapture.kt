package com.pfclone.app

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection

/** Держит последний кадр экрана и читает из него цвета пикселей. */
class ScreenCapture(
    private val projection: MediaProjection,
    val width: Int, val height: Int, private val dpi: Int
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var image: Image? = null

    @Synchronized fun start() {
        if (reader != null) return
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = r
        display = projection.createVirtualDisplay(
            "pfc", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    @Synchronized fun stop() {
        display?.release(); display = null
        image?.close(); image = null
        reader?.close(); reader = null
    }

    private fun refresh() {
        val img = reader?.acquireLatestImage() ?: return
        image?.close()
        image = img
    }

    /** Цвет в формате 0xRRGGBB (как возвращает getColor в Perfect Click). */
    @Synchronized fun pixel(x: Int, y: Int): Int {
        refresh()
        val img = image ?: return 0
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        val plane = img.planes[0]
        val buf = plane.buffer
        val i = y * plane.rowStride + x * plane.pixelStride
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    /** Приближённый подсчёт контуров в области (границы по яркости + связные компоненты). */
    @Synchronized fun contours(ax: Int, ay: Int, bx: Int, by: Int): Int {
        refresh()
        val img = image ?: return 0
        val x1 = maxOf(0, minOf(ax, bx)); val x2 = minOf(width - 2, maxOf(ax, bx))
        val y1 = maxOf(0, minOf(ay, by)); val y2 = minOf(height - 2, maxOf(ay, by))
        val w = x2 - x1 + 1; val h = y2 - y1 + 1
        if (w < 2 || h < 2) return 0
        val plane = img.planes[0]; val buf = plane.buffer
        val gray = IntArray((w + 1) * (h + 1))
        for (yy in 0..h) for (xx in 0..w) {
            val px = minOf(x1 + xx, width - 1); val py = minOf(y1 + yy, height - 1)
            val i = py * plane.rowStride + px * plane.pixelStride
            val r = buf.get(i).toInt() and 0xFF
            val g = buf.get(i + 1).toInt() and 0xFF
            val b = buf.get(i + 2).toInt() and 0xFF
            gray[yy * (w + 1) + xx] = (r * 30 + g * 59 + b * 11) / 100
        }
        val edge = BooleanArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            val c = gray[yy * (w + 1) + xx]
            val dx = Math.abs(c - gray[yy * (w + 1) + xx + 1])
            val dy = Math.abs(c - gray[(yy + 1) * (w + 1) + xx])
            edge[yy * w + xx] = dx + dy > 40
        }
        var count = 0
        val stack = IntArray(w * h)
        for (s in 0 until w * h) {
            if (!edge[s]) continue
            var sp = 0; stack[sp++] = s; edge[s] = false; var size = 0
            while (sp > 0) {
                val p = stack[--sp]; size++
                val px = p % w; val py = p / w
                for (ny in py - 1..py + 1) for (nx in px - 1..px + 1) {
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (edge[q]) { edge[q] = false; stack[sp++] = q }
                }
            }
            if (size >= 2) count++
        }
        return count
    }
}
