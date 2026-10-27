package com.aykkdk.shop

import java.net.URLEncoder

internal data class BoboStore(val areaId: String, val agentId: String, val name: String)

internal object BoboSearch {
    val malls = listOf("0" to "二手商城", "0_1" to "特價商城")

    val areas = listOf(
        "0" to "所有地區",
        "1" to "北北基",
        "2" to "桃竹苗",
        "3" to "中彰投",
        "4" to "雲嘉南",
        "5" to "宜花東",
        "6" to "高屏"
    )

    val stores = listOf(
        // 北北基 (1)
        BoboStore("1", "1", "土城總舖"),
        BoboStore("1", "3", "三峽大同舖"),
        BoboStore("1", "53", "三重信義舖"),
        BoboStore("1", "62", "北市延平舖"),
        BoboStore("1", "104", "電商商城"),

        // 桃竹苗 (2)
        BoboStore("2", "11", "中壢平鎮舖"),
        BoboStore("2", "12", "新竹西大舖"),
        BoboStore("2", "65", "新豐康樂舖"),
        BoboStore("2", "67", "新竹竹北舖"),
        BoboStore("2", "70", "苗栗竹南舖"),
        BoboStore("2", "71", "中壢新興舖"),
        BoboStore("2", "96", "湖口大智舖"),
        BoboStore("2", "103", "新竹香山舖"),

        // 中彰投 (3)
        BoboStore("3", "5", "台中豐原舖"),
        BoboStore("3", "6", "台中西屯舖"),
        BoboStore("3", "15", "太平宜昌舖"),
        BoboStore("3", "22", "彰化員林舖"),
        BoboStore("3", "54", "彰化中興舖"),
        BoboStore("3", "73", "彰化中正舖"),
        BoboStore("3", "80", "台中北屯舖"),
        BoboStore("3", "87", "台中潭子舖"),

        // 雲嘉南 (4)
        BoboStore("4", "37", "台南大同舖"),
        BoboStore("4", "86", "嘉義玉山舖"),
        BoboStore("4", "101", "台南民族舖"),

        // 宜花東 (5)
        BoboStore("5", "7", "花蓮和平舖"),
        BoboStore("5", "91", "花蓮建國舖"),

        // 高屏 (6)
        BoboStore("6", "20", "鳳山五甲舖"),
        BoboStore("6", "21", "屏東大連舖"),
        BoboStore("6", "31", "屏東民族舖"),
        BoboStore("6", "33", "高雄建德舖"),
        BoboStore("6", "35", "高雄大寮舖"),
        BoboStore("6", "55", "鳳山西站舖"),
        BoboStore("6", "63", "高雄左營舖"),
        BoboStore("6", "81", "苓雅福德舖"),
        BoboStore("6", "82", "高雄二聖舖"),
        BoboStore("6", "85", "屏東東港舖"),
        BoboStore("6", "94", "仁武中正舖"),
        BoboStore("6", "95", "岡山文賢舖"),
        BoboStore("6", "98", "屏東復興舖"),
        BoboStore("6", "99", "鼓山中華舖"),
        BoboStore("6", "107", "高雄博愛舖"),
        BoboStore("6", "108", "高雄楠梓舖"),
        BoboStore("6", "109", "大寮鳳屏舖"),
        BoboStore("6", "111", "小港孔鳳舖")
    )

    fun validArea(area: String): String =
        area.takeIf { a -> areas.any { it.first == a } } ?: "0"

    fun areaName(area: String): String =
        areas.firstOrNull { it.first == validArea(area) }?.second ?: "所有地區"

    fun agentOptions(area: String): List<Pair<String, String>> {
        val validA = validArea(area)
        return if (validA == "0") {
            listOf("0" to "所有店鋪") + stores.map { it.agentId to it.name }
        } else {
            val prefix = areaName(validA)
            listOf("0" to "${prefix}所有店鋪") + stores.filter { it.areaId == validA }.map { it.agentId to it.name }
        }
    }

    fun validAgent(area: String, agent: String): String {
        if (agent == "0" || agent.isBlank()) return "0"
        val validA = validArea(area)
        return if (validA == "0") {
            agent.takeIf { id -> stores.any { it.agentId == id } } ?: "0"
        } else {
            agent.takeIf { id -> stores.any { it.areaId == validA && it.agentId == id } } ?: "0"
        }
    }

    fun agentName(area: String, agent: String): String {
        val validAg = validAgent(area, agent)
        if (validAg == "0") {
            val validA = validArea(area)
            return if (validA == "0") "所有店鋪" else "${areaName(validA)}所有店鋪"
        }
        return stores.firstOrNull { it.agentId == validAg }?.name ?: "所有店鋪"
    }

    fun agentBelongsToArea(area: String, agent: String): Boolean {
        if (agent == "0" || agent.isBlank()) return true
        val validA = validArea(area)
        if (validA == "0") return true
        return stores.any { it.areaId == validA && it.agentId == agent }
    }

    // Public category labels from the mall menu. This retains the existing per-page category filter.
    val categories = listOf("其他未分類", "足部用品", "頭髮用品", "香水精油", "彩妝品", "保養品", "美容用品",
        "戶外休閒", "健身類", "球類", "電腦零件", "電腦軟體", "網路設備", "電腦週邊", "鍵盤．滑鼠", "主機．螢幕",
        "簾類．墊子", "布類．桌巾", "布料", "相機相關", "電玩數位", "手機通訊", "季節家電", "生活家電", "廚房家電", "影音視聽",
        "工業用品", "各式線材", "車用百貨", "燈具電料", "五金工具", "五金材料", "寢具寢飾", "浴室用品", "寵物園藝", "育嬰用品",
        "收納用品", "生活百貨", "盥洗用品", "清潔用品", "保溫系列", "保鮮餐盒", "廚房用品", "餐具．鍋子", "茶壺．杯子", "碗盤",
        "牌棋益智", "盒裝玩具", "娃娃玩偶", "模型公仔", "收納．小包", "背包．旅行箱", "錢包．皮夾", "包包．提袋",
        "DIY", "布/紙膠帶", "文具用品", "樂器", "紙製用品", "辦公用具", "文件收納", "記事手札", "書寫用具",
        "其他飾品", "襪子．襪類", "耳環．墜子", "鐘錶", "圍巾．絲巾", "皮帶．領北", "手套．帽子", "吊飾．鑰匙圈",
        "手鍊．戒指", "項鍊．別針", "髮飾", "其他光碟", "影集", "DVD", "VCD", "音樂CD", "紀念收藏", "DIY用品",
        "季節飾品", "框畫", "陳列品", "童書", "小說", "漫畫", "文叢")

    fun category(value: String) = value.trim().takeIf { it in categories }.orEmpty()

    fun matchesCategory(product: Product, selected: String) = product.platform != Market.BBOBO.label ||
        selected.isBlank() || product.category.trim() == selected

    fun zone(value: String) = value.takeIf { candidate -> malls.any { it.first == candidate } } ?: "0"

    fun url(keyword: String, min: String, max: String, page: Int, mall: String, area: String = "0", agent: String = "0"): String {
        val selected = zone(mall)
        val selectedArea = validArea(area)
        val selectedAgent = validAgent(selectedArea, agent)
        val parameters = buildList {
            add("KeyWord" to keyword)
            add("SearchZone" to selected)
            add("SelectArea" to selectedArea)
            add("SelectAgent" to selectedAgent)
            add("Page" to page.coerceIn(1, 500).toString())
            if (min.isNotBlank()) add("MinM" to min)
            if (max.isNotBlank()) add("MaxM" to max)
        }
        return "https://www.bbbobo.com.tw/shop/$selected/Goods_List.asp?" + parameters.joinToString("&") {
            "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
    }
}
