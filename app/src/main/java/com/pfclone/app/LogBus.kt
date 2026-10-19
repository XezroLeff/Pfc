package com.pfclone.app

import android.os.Handler
import android.os.Looper

object LogBus {
    private val main = Handler(Looper.getMainLooper())
    val buffer = StringBuilder()
    @Volatile var listener: ((String) -> Unit)? = null

    fun log(s: String) {
        main.post {
            buffer.append(s).append("\n")
            listener?.invoke(s)
        }
    }

    fun clear() { buffer.setLength(0) }
}
