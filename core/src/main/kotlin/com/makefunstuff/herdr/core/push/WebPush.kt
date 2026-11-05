package com.makefunstuff.herdr.core.push

import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What the app hands a Shepherdr 3 service to subscribe, as a web page's push subscription has it: a P-256
 * key pair and an auth secret, made once per machine. [private] is PKCS#8, [public] the raw uncompressed
 * point, [auth] 16 bytes; all base64url, the way the service reads `keys.p256dh` and `keys.auth`.
 */
@Serializable
data class WebPushKeys(val private: String, val public: String, val auth: String)

/**
 * Web Push as the 3.1 service sends it (service/push.ts): the alert encrypted for one subscription by RFC 8291,
 * the `aes128gcm` body of RFC 8188 (`salt(16) ‖ rs(4) ‖ idlen(1) ‖ the sender's public key ‖ one record`). The
 * push app hands the body over as it came, so the app opens it here with the subscription's own keys.
 */
object WebPush {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s.trimEnd('='))

    fun newKeys(): WebPushKeys {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return WebPushKeys(b64.encodeToString(pair.private.encoded), b64.encodeToString(raw(pair.public as ECPublicKey)), b64.encodeToString(auth))
    }

    /** The plain payload, or null when [body] isn't one encrypted for [keys] (another machine's, a herdr-push message, damaged). */
    fun open(body: ByteArray, keys: WebPushKeys): ByteArray? = runCatching { decrypt(body, keys) }.getOrNull()

    private fun decrypt(body: ByteArray, keys: WebPushKeys): ByteArray? {
        if (body.size < 21) return null
        val salt = body.copyOfRange(0, 16)
        val idLen = body[20].toInt() and 0xff
        if (idLen != 65 || body.size < 21 + idLen + 17) return null
        val asPublic = body.copyOfRange(21, 21 + idLen)
        val record = body.copyOfRange(21 + idLen, body.size)

        val ua = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(keys.private))) as ECPrivateKey
        val uaPublic = unb64(keys.public)
        val sender = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point(asPublic), ua.params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(ua); doPhase(sender, true); generateSecret() }

        val prkKey = hmac(unb64(keys.auth), shared)
        val ikm = hmac(prkKey, "WebPush: info\u0000".toByteArray() + uaPublic + asPublic + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + byteArrayOf(1)).copyOf(12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(record)
        }
        // One record, the last: its padding is a 2 after the payload, then zeros.
        var end = plain.size - 1
        while (end >= 0 && plain[end] == 0.toByte()) end--
        if (end < 0 || plain[end] != 2.toByte()) return null
        return plain.copyOf(end)
    }

    private fun raw(k: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY)

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - b.size) + b
    }

    private fun point(raw: ByteArray): ECPoint {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "Not an uncompressed P-256 point" }
        return ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
}
