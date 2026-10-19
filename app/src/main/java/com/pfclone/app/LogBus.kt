package com.pfclone.app

import android.os.Handler
import android.os.Looper

object LogBus {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: ((String) -> Unit)? = null

    fun log(s: String) {
        main.post { listener?.invoke(s) }
    }
}
