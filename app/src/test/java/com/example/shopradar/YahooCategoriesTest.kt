package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class YahooCategoriesTest {
    @Test fun suppliedCategoriesKeepTheirSearchCids() {
        fun child(group: String, name: String) = YahooCategories.groups.first { it.name == group }.children.first { it.name == name }
        assertEquals("23960", child("手機、配件與通訊", "手機空機").cid)
        assertEquals("27034937", child("相機、攝影與周邊", "空拍機").cid)
        assertEquals("40222667", child("電玩遊戲與主機", "PS5").cid)
        assertEquals("24230", child("居家、家具與園藝", "傢俱/床墊").cid)
        assertTrue(YahooCategories.groups.size >= 13)
    }
}
