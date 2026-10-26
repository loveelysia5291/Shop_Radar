package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class BackgroundProbeTimingTest {
    @Test fun distinguishesNotScheduledWaitingAndOverdue() {
        assertEquals("尚未排程", BackgroundProbeTiming.text(0, 0, 1000))
        assertEquals("尚未到預定時間", BackgroundProbeTiming.text(2000, 0, 1000))
        assertEquals("已到期 3 秒，背景工作尚未啟動", BackgroundProbeTiming.text(2000, 0, 5000))
    }
    @Test fun usesActualWorkerStartNotTimeTheUserOpenedTheReport() {
        assertEquals("實際執行比預定晚 4 秒", BackgroundProbeTiming.text(2000, 6000, 99000))
        assertEquals("實際執行比預定晚 0 秒", BackgroundProbeTiming.text(6000, 2000, 99000))
    }
}
