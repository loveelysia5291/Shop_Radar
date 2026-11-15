package com.aykkdk.shop

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * 雲端伺服器與後台位址混淆防護模組
 * 防止 APK 被反編譯工具 (如 Jadx、Apktool、strings) 靜態檢索到敏感網域名稱與後台地址
 */
object CloudEndpointSecurity {

    // 混淆編碼之預設雲端節點位址 (反編譯無法提取明文字串)
    private val OBFUSCATED_ENDPOINT = byteArrayOf(
        (63).toByte(), (92).toByte(), (17).toByte(), (77).toByte(), (-63).toByte(), (71).toByte(), (-102).toByte(), (112).toByte(),
        (108).toByte(), (24).toByte(), (66).toByte(), (53).toByte(), (-41).toByte(), (87).toByte(), (-116).toByte(), (115).toByte(),
        (70).toByte(), (-54).toByte(), (-40).toByte(), (-6).toByte(), (109).toByte(), (-97).toByte(), (78).toByte(), (-86).toByte(),
        (-99).toByte(), (-82).toByte(), (-36).toByte(), (-79).toByte(), (37).toByte(), (-9).toByte(), (56).toByte(), (-26).toByte(),
        (-45).toByte(), (-89).toByte(), (-68).toByte(), (45).toByte(), (-85).toByte(), (108).toByte(), (-89).toByte(), (76).toByte(),
        (81).toByte(), (103).toByte(), (34).toByte(), (87).toByte(), (-79).toByte(), (96).toByte(), (-65).toByte(), (68).toByte(),
        (41).toByte(), (60).toByte(), (112).toByte(), (27).toByte()
    )

    private val OBFUSCATED_KEY = byteArrayOf(
        (90).toByte(), (60).toByte(), (126).toByte(), (31).toByte(), (-101).toByte(), (77).toByte(), (-126).toByte(), (97).toByte()
    )

    /**
     * 於記憶體中動態解密還原預設伺服器網址
     */
    fun getServerUrl(): String {
        return try {
            val decryptedBytes = ByteArray(OBFUSCATED_ENDPOINT.size)
            for (i in OBFUSCATED_ENDPOINT.indices) {
                val b = OBFUSCATED_ENDPOINT[i].toInt() and 0xFF
                val k = OBFUSCATED_KEY[i % OBFUSCATED_KEY.size].toInt() and 0xFF
                val shift = (i * 7 + 13) and 0xFF
                decryptedBytes[i] = (b xor k xor shift).toByte()
            }
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 自訂網址持久化前之動態加密儲存
     */
    fun encryptCustomUrl(url: String): String {
        return try {
            val raw = url.toByteArray(StandardCharsets.UTF_8)
            val enc = ByteArray(raw.size)
            for (i in raw.indices) {
                val b = raw[i].toInt() and 0xFF
                val k = OBFUSCATED_KEY[i % OBFUSCATED_KEY.size].toInt() and 0xFF
                enc[i] = (b xor k xor 0xA5).toByte()
            }
            Base64.encodeToString(enc, Base64.NO_WRAP)
        } catch (_: Exception) {
            url
        }
    }

    /**
     * 自訂網址解密讀取
     */
    fun decryptCustomUrl(encryptedBase64: String): String? {
        return try {
            val enc = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val dec = ByteArray(enc.size)
            for (i in enc.indices) {
                val b = enc[i].toInt() and 0xFF
                val k = OBFUSCATED_KEY[i % OBFUSCATED_KEY.size].toInt() and 0xFF
                dec[i] = (b xor k xor 0xA5).toByte()
            }
            String(dec, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
