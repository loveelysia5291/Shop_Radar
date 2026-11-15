package com.aykkdk.shop

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端隱私加密傳輸與儲存模組 (AES-256-GCM + PBKDF2)
 * 用於加密商品追蹤、賣場與競標項目，確保通訊過程與伺服器均無法窺視用戶隱私數據
 */
object VaultCrypto {

    private const val SALT = "shop-radar-vault-salt-2026"
    private const val ITERATIONS = 100000
    private const val KEY_LENGTH = 256
    private const val DEFAULT_SECRET = "shop_radar_vault_encryption_secret_2026"

    private fun deriveKey(secret: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(secret.toCharArray(), SALT.toByteArray(StandardCharsets.UTF_8), ITERATIONS, KEY_LENGTH)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * 加密字串，回傳格式為 ivHex:cipherTextHex
     */
    fun encrypt(plainText: String, secret: String = DEFAULT_SECRET): String? = runCatching {
        val key = deriveKey(secret)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        val ivHex = bytesToHex(iv)
        val ctHex = bytesToHex(cipherText)
        "$ivHex:$ctHex"
    }.getOrNull()

    /**
     * 解密 ivHex:cipherTextHex 格式之密文
     */
    fun decrypt(cipherTextWithIv: String, secret: String = DEFAULT_SECRET): String? = runCatching {
        if (!cipherTextWithIv.contains(":")) return null
        val parts = cipherTextWithIv.split(":")
        if (parts.size != 2) return null
        val ivHex = parts[0]
        val ctHex = parts[1]
        val iv = hexToBytes(ivHex)
        val ct = hexToBytes(ctHex)
        val key = deriveKey(secret)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val plainBytes = cipher.doFinal(ct)
        String(plainBytes, StandardCharsets.UTF_8)
    }.getOrNull()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i ushr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
