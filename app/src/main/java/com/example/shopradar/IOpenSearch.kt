package com.example.shopradar

import java.net.URLEncoder

/** Public anonymous search route supplied by the user; prices are filtered locally. */
internal object IOpenSearch {
    fun url(keyword: String): String = "https://mall.iopenmall.tw/iopen/index.php?action=store_product_search&prod_keyword=" +
        URLEncoder.encode(keyword, "UTF-8")
}
