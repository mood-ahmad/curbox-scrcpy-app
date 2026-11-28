package neth.iecal.curbox.remotecontrol

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectionRemovalTest {

    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun body(device: String, request: String, expires: Long, wrap: Boolean = false): String {
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(ProtectionRemoval.canonical(device, request, expires).toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        val sigText = if (wrap) sig.chunked(60).joinToString("\r\n") else sig
        return "CurBox protection removal approved.\r\nDevice: $device\r\nRequest: $request\r\nExpires: $expires\r\nSignature: $sigText\r\n\r\nInstinct"
    }

    private fun verify(text: String, device: String = "dev1", pending: String? = "req1", now: Long = 1_000L, issued: Long = 900L) =
        ProtectionRemoval.verify(ProtectionRemoval.parseApproval(text), device, pending, issued, now, pub)

    @Test fun validApproval() =
        assertEquals(ProtectionRemoval.Verdict.VALID, verify(body("dev1", "req1", 2_000L)))

    @Test fun wrappedSignatureStillParses() =
        assertEquals(ProtectionRemoval.Verdict.VALID, verify(body("dev1", "req1", 2_000L, wrap = true)))

    @Test fun otherDeviceIsRejected() =
        assertEquals(ProtectionRemoval.Verdict.WRONG_DEVICE, verify(body("dev2", "req1", 2_000L)))

    @Test fun wrongRequestIsRejected() =
        assertEquals(ProtectionRemoval.Verdict.WRONG_REQUEST, verify(body("dev1", "req9", 2_000L)))

    @Test fun noPendingRequestIsRejected() =
        assertEquals(ProtectionRemoval.Verdict.NO_PENDING_REQUEST, verify(body("dev1", "req1", 2_000L), pending = null))

    @Test fun expiredIsRejected() =
        assertEquals(ProtectionRemoval.Verdict.EXPIRED, verify(body("dev1", "req1", 2_000L), now = 2_001L))

    @Test fun tamperedFieldsFailTheSignature() {
        val text = body("dev1", "req1", 2_000L).replace("Expires: 2000", "Expires: 9999")
        assertEquals(ProtectionRemoval.Verdict.BAD_SIGNATURE, verify(text, now = 1_000L))
    }

    @Test fun signatureFromAnotherKeyFails() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(other.private)
            update(ProtectionRemoval.canonical("dev1", "req1", 2_000L).toByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
        val text = "Device: dev1\nRequest: req1\nExpires: 2000\nSignature: $sig"
        assertEquals(ProtectionRemoval.Verdict.BAD_SIGNATURE, verify(text))
    }

    @Test fun malformedBodiesAreRejected() {
        assertNull(ProtectionRemoval.parseApproval(null))
        assertNull(ProtectionRemoval.parseApproval("hello"))
        assertNull(ProtectionRemoval.parseApproval("Device: a\nRequest: b\nExpires: x\nSignature: AAAA"))
        assertNotNull(ProtectionRemoval.parseApproval("Device: a\nRequest: b\nExpires: 5\nSignature: AAAA"))
    }

    @Test fun embeddedPublicKeyIsAValidRsaKey() {
        val key = java.security.KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(ProtectionRemoval.PUBLIC_KEY_B64))
        )
        assertEquals("RSA", key.algorithm)
    }
}
