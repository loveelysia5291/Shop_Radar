package com.aykkdk.shop

internal object BackgroundProbeTiming {
    fun text(due: Long, started: Long, now: Long): String = when {
        due <= 0 -> "尚未排程"
        started > 0 -> "實際執行比預定晚 ${((started - due).coerceAtLeast(0) / 1000)} 秒"
        now < due -> "尚未到預定時間"
        else -> "已到期 ${((now - due) / 1000)} 秒，背景工作尚未啟動"
    }
}
