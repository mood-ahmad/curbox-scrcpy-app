package neth.iecal.curbox.data.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * End to end encryption primitives. This is the byte for byte mirror of the
 * browser extension's crypto.ts. The shared vectors in
 * app/src/test/resources/crypto.vectors.json keep the two honest.
 *
 * Blob layout (also used for the wrapped data key):
 *   byte 0      format version (0x01)
 *   bytes 1..12 96 bit nonce
 *   bytes 13..  ciphertext || 128 bit tag
 */
object CryptoBox {
    private const val FORMAT_VERSION: Byte = 0x01
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    data class KdfParams(
        val alg: String = "PBKDF2-HMAC-SHA256",
        val iterations: Int = 600000,
        val hash: String = "SHA-256",
        val dkLenBits: Int = 256,
    )

    val DEFAULT_KDF_PARAMS = KdfParams()

    fun randomBytes(len: Int): ByteArray = ByteArray(len).also { random.nextBytes(it) }
    fun randomDek(): ByteArray = randomBytes(32)
    fun randomSalt(): ByteArray = randomBytes(16)

    fun toBase64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64Url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun deriveKekBytes(passphrase: String, salt: ByteArray, params: KdfParams = DEFAULT_KDF_PARAMS): ByteArray {
        require(params.alg == "PBKDF2-HMAC-SHA256" && params.hash == "SHA-256") { "unsupported key derivation" }
        require(params.iterations in 1..2_000_000) { "invalid key derivation work factor" }
        require(params.dkLenBits == 256) { "invalid key length" }
        require(salt.size in 8..64) { "invalid salt length" }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, params.iterations, params.dkLenBits)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size in setOf(16, 24, 32)) { "invalid encryption key length" }
        require(nonce.size == NONCE_LEN) { "invalid nonce length" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ctWithTag = cipher.doFinal(plaintext)
        return ByteArray(1 + nonce.size + ctWithTag.size).also {
            it[0] = FORMAT_VERSION
            System.arraycopy(nonce, 0, it, 1, nonce.size)
            System.arraycopy(ctWithTag, 0, it, 1 + nonce.size, ctWithTag.size)
        }
    }

    private fun open(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray {
        require(key.size in setOf(16, 24, 32)) { "invalid encryption key length" }
        require(blob.size >= 1 + NONCE_LEN + TAG_BITS / 8) { "encrypted blob is too short" }
        require(blob[0] == FORMAT_VERSION) { "unsupported blob version ${blob[0]}" }
        val nonce = blob.copyOfRange(1, 1 + NONCE_LEN)
        val ctWithTag = blob.copyOfRange(1 + NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ctWithTag)
    }

    private fun vaultAad(userId: String) = "$userId|vault".toByteArray(Charsets.UTF_8)
    fun recordAad(userId: String, namespace: String, recordKey: String) =
        "$userId|$namespace|$recordKey".toByteArray(Charsets.UTF_8)

    fun wrapDek(kek: ByteArray, dek: ByteArray, userId: String, nonce: ByteArray = randomBytes(NONCE_LEN)): ByteArray =
        seal(kek, vaultAad(userId), dek, nonce)

    fun unwrapDek(kek: ByteArray, blob: ByteArray, userId: String): ByteArray =
        open(kek, vaultAad(userId), blob)

    fun encryptRecord(dek: ByteArray, aad: ByteArray, plaintext: String, nonce: ByteArray = randomBytes(NONCE_LEN)): ByteArray =
        seal(dek, aad, plaintext.toByteArray(Charsets.UTF_8), nonce)

    fun decryptRecord(dek: ByteArray, aad: ByteArray, blob: ByteArray): String =
        String(open(dek, aad, blob), Charsets.UTF_8)

    // QR pairing payload, identical shape to the extension.
    fun buildPairingPayload(userId: String, dek: ByteArray): String =
        JSONObject()
            .put("v", 1)
            .put("t", "curbox-dek")
            .put("uid", userId)
            .put("dek", toBase64Url(dek))
            .toString()

    data class Pairing(val userId: String, val dek: ByteArray)

    fun parsePairingPayload(raw: String): Pairing {
        require(raw.length <= 2048) { "pairing code is too large" }
        val obj = JSONObject(raw)
        require(obj.optString("t") == "curbox-dek" && obj.optInt("v") == 1) { "not a Horus pairing code" }
        val userId = obj.getString("uid")
        require(userId.isNotBlank() && userId.length <= 128) { "bad pairing account" }
        val dek = fromBase64Url(obj.getString("dek"))
        require(dek.size == 32) { "bad pairing key length" }
        return Pairing(userId, dek)
    }
}
