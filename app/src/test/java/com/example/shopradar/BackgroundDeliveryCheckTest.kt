package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class BackgroundDeliveryCheckTest {
    @Test fun failedAndOfflineInventoryAreNotConfusedWithBlockedNotifications() {
        assertTrue(BackgroundDeliveryCheck.checkText("network").contains("網路"))
        assertTrue(BackgroundDeliveryCheck.checkText("failed").contains("待送提醒仍保留"))
        assertEquals("已完成", BackgroundDeliveryCheck.checkText("complete"))
        assertTrue(BackgroundDeliveryCheck.checkText("sleeping").contains("暫停"))
    }
    @Test fun statesDistinguishOsSchedulingFromPermissionAndDelivery() {
        assertTrue(BackgroundDeliveryCheck.deliveryText("queued").contains("系統可能延後"))
        assertTrue(BackgroundDeliveryCheck.deliveryText("notification_blocked").contains("權限或頻道"))
        assertTrue(BackgroundDeliveryCheck.deliveryText("notification_sent").contains("Android"))
        assertEquals("尚無紀錄", BackgroundDeliveryCheck.deliveryText(null))
        assertEquals("尚無紀錄", BackgroundDeliveryCheck.deliveryText("no_updates"))
    }
}
