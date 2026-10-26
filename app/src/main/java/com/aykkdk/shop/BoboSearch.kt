package com.aykkdk.shop

import java.net.URLEncoder

internal object BoboSearch {
    val malls = listOf("0" to "二手商城", "0_1" to "特價商城")
    // Public category labels from the mall menu. This retains the existing per-page category filter.
    val categories = listOf("其他未分類", "足部用品", "頭髮用品", "香水精油", "彩妝品", "保養品", "美容用品",
        "戶外休閒", "健身類", "球類", "電腦零件", "電腦軟體", "網路設備", "電腦週邊", "鍵盤．滑鼠", "主機．螢幕",
        "簾類．墊子", "布類．桌巾", "布料", "相機相關", "電玩數位", "手機通訊", "季節家電", "生活家電", "廚房家電", "影音視聽",
        "工業用品", "各式線材", "車用百貨", "燈具電料", "五金工具", "五金材料", "寢具寢飾", "浴室用品", "寵物園藝", "育嬰用品",
        "收納用品", "生活百貨", "盥洗用品", "清潔用品", "保溫系列", "保鮮餐盒", "廚房用品", "餐具．鍋子", "茶壺．杯子", "碗盤",
        "牌棋益智", "盒裝玩具", "娃娃玩偶", "模型公仔", "收納．小包", "背包．旅行箱", "錢包．皮夾", "包包．提袋",
        "DIY", "布/紙膠帶", "文具用品", "樂器", "紙製用品", "辦公用具", "文件收納", "記事手札", "書寫用具",
        "其他飾品", "襪子．襪類", "耳環．墜子", "鐘錶", "圍巾．絲巾", "皮帶．領帶", "手套．帽子", "吊飾．鑰匙圈",
        "手鍊．戒指", "項鍊．別針", "髮飾", "其他光碟", "影集", "DVD", "VCD", "音樂CD", "紀念收藏", "DIY用品",
        "季節飾品", "框畫", "陳列品", "童書", "小說", "漫畫", "文叢")
    fun category(value: String) = value.trim().takeIf { it in categories }.orEmpty()
    fun matchesCategory(product: Product, selected: String) = product.platform != Market.BBOBO.label ||
        selected.isBlank() || product.category.trim() == selected
    fun zone(value: String) = value.takeIf { candidate -> malls.any { it.first == candidate } } ?: "0"
    fun url(keyword: String, min: String, max: String, page: Int, mall: String): String {
        val selected = zone(mall)
        val parameters = buildList {
            add("KeyWord" to keyword); add("SearchZone" to selected)
            add("SelectArea" to "0"); add("SelectAgent" to "0")
            add("Page" to page.coerceIn(1, 500).toString())
            if (min.isNotBlank()) add("MinM" to min)
            if (max.isNotBlank()) add("MaxM" to max)
        }
        return "https://www.bbbobo.com.tw/shop/$selected/Goods_List.asp?" + parameters.joinToString("&") {
            "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
    }
}
