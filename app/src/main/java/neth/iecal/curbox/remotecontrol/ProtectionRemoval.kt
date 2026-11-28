package neth.iecal.curbox.remotecontrol

import android.content.Context
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Signed remote approval for lifting the uninstall (tamper) protection.
 *
 * The device asks by email. The owner's side replies with a message whose body
 * carries the device ID, the request number, an expiry time and an RSA
 * signature over exactly those. The private key never leaves the owner's side;
 * only the public key is compiled into the app.
 */
object ProtectionRemoval {

    const val UNLOCK_SUBJECT = "CURBOX UNLOCK"
    const val REQUEST_MAX_AGE_SEC = 24L * 60L * 60L

    const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtxWFNqEZpo1cEjpxm8xEVSSBxdWCthnwQ7Mx2fW+cLU+vnZKTukp9XYOgfHCJSkMM9uuwpQ53RPqq0W/sVUeddRpJ9Pgr6nFwGbwowfyw61wlyFco6xZkudqEil77hLmuVUCRqwogpvMMVZBOKLmElyxy2vVVtEA3Ds2idIJC6YCt6qJmfPNrcbVnZr2ynGWn29fRItGwm/yYYsP6iX6mjtr7UmMaheDs3+cDm/K+GSWYQVeAR3Pfr2OrWtC1iT7EbXqS6RyWw1TWNLzLu6YcwlSq5gqizGlUnd5CAPlWI6k9CKkuX2mTI4wt5KjZ3Xd3VNX2dymMm8YzdPeOx04bwIDAQAB"

    enum class Verdict {
        VALID,
        MALFORMED,
        WRONG_DEVICE,
        NO_PENDING_REQUEST,
        WRONG_REQUEST,
        EXPIRED,
        BAD_SIGNATURE
    }

    data class Approval(
        val deviceId: String,
        val requestId: String,
        val expiresAtSec: Long,
        val signature: ByteArray
    )

    fun canonical(deviceId: String, requestId: String, expiresAtSec: Long): String =
        "CURBOX-UNLOCK-V1|$deviceId|$requestId|$expiresAtSec"

    /** Reads the "Device:", "Request:", "Expires:" and "Signature:" fields. */
    fun parseApproval(body: String?): Approval? {
        if (body.isNullOrBlank()) return null
        var device: String? = null
        var request: String? = null
        var expires: Long? = null
        val sig = StringBuilder()
        var inSignature = false
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("Device:", ignoreCase = true) -> {
                    inSignature = false
                    device = line.substringAfter(':').trim()
                }
                line.startsWith("Request:", ignoreCase = true) -> {
                    inSignature = false
                    request = line.substringAfter(':').trim()
                }
                line.startsWith("Expires:", ignoreCase = true) -> {
                    inSignature = false
                    expires = line.substringAfter(':').trim().toLongOrNull()
                }
                line.startsWith("Signature:", ignoreCase = true) -> {
                    inSignature = true
                    sig.append(line.substringAfter(':').trim())
                }
                inSignature && line.isNotEmpty() &&
                    line.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } ->
                    sig.append(line)
                else -> inSignature = false
            }
        }
        if (device.isNullOrEmpty() || request.isNullOrEmpty() || expires == null || sig.isEmpty()) {
            return null
        }
        val bytes = try {
            Base64.getDecoder().decode(sig.toString())
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Approval(device, request, expires, bytes)
    }

    /**
     * Checks an approval against this device. [pendingRequestId] is the
     * request this device issued and has not used yet; [issuedAtSec] is when.
     */
    fun verify(
        approval: Approval?,
        ownDeviceId: String,
        pendingRequestId: String?,
        issuedAtSec: Long,
        nowSec: Long,
        publicKeyB64: String = PUBLIC_KEY_B64
    ): Verdict {
        if (approval == null) return Verdict.MALFORMED
        if (approval.deviceId != ownDeviceId) return Verdict.WRONG_DEVICE
        if (pendingRequestId.isNullOrEmpty()) return Verdict.NO_PENDING_REQUEST
        if (approval.requestId != pendingRequestId) return Verdict.WRONG_REQUEST
        if (nowSec > approval.expiresAtSec) return Verdict.EXPIRED
        if (nowSec - issuedAtSec > REQUEST_MAX_AGE_SEC) return Verdict.EXPIRED
        val valid = try {
            val key = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64))
            )
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(
                    canonical(approval.deviceId, approval.requestId, approval.expiresAtSec)
                        .toByteArray(Charsets.UTF_8)
                )
                verify(approval.signature)
            }
        } catch (_: Throwable) {
            false
        }
        return if (valid) Verdict.VALID else Verdict.BAD_SIGNATURE
    }

    // ---- Per-install state -------------------------------------------------

    private const val PREFS = "protection_removal"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_REQUEST_ID = "pending_request_id"
    private const val KEY_ISSUED_AT = "pending_issued_at"

    /** Random per-install ID, stable until the app data is cleared. */
    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID, id).commit()
        return id
    }

    fun shortId(deviceId: String): String = deviceId.takeLast(6)

    /** Starts a new request, replacing any earlier pending one. */
    fun newRequest(context: Context, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(10)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_REQUEST_ID, id).putLong(KEY_ISSUED_AT, nowSec).commit()
        return id
    }

    fun pendingRequestId(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REQUEST_ID, null)

    fun pendingIssuedAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ISSUED_AT, 0L)

    fun clearPending(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_REQUEST_ID).remove(KEY_ISSUED_AT).commit()
    }
}
