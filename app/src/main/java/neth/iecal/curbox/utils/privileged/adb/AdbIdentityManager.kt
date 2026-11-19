package neth.iecal.curbox.utils.privileged.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * Owns CurBox's persistent ADB client identity.
 *
 * The RSA private key is generated inside AndroidKeyStore and is reused across
 * process restarts and device reboots while the keystore entry remains intact.
 *
 * Keeping the same identity is important because an ADB pairing establishes
 * trust in that identity. Recreating it unnecessarily would make an otherwise
 * valid pairing unusable.
 */
object AdbIdentityManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "curbox_direct_adb_identity"

    private const val RSA_KEY_SIZE = 2048

    /**
     * A long-lived validity window avoids replacing the identity during normal
     * CurBox use. Losing or explicitly resetting the identity is handled as a
     * separate repair/re-pair event.
     */
    private const val CERTIFICATE_VALIDITY_YEARS = 20

    data class Identity(
        val privateKey: PrivateKey,
        val certificate: Certificate
    )

    /**
     * Returns the current identity or creates it when CurBox has never created
     * one before.
     *
     * This method is synchronized so two startup/recovery paths cannot race
     * while creating the first identity.
     */
    @Synchronized
    fun getOrCreate(): Identity {
        val keyStore = loadKeyStore()

        loadIdentity(keyStore)?.let {
            return it
        }

        generateIdentity()

        val reloadedKeyStore =
            loadKeyStore()

        return loadIdentity(reloadedKeyStore)
            ?: throw IllegalStateException(
                "ADB identity generation completed but the identity " +
                    "could not be loaded from AndroidKeyStore."
            )
    }

    /**
     * Returns the stored identity without creating one.
     */
    @Synchronized
    fun getExisting(): Identity? {
        return loadIdentity(
            loadKeyStore()
        )
    }

    /**
     * Returns true when both the private key and certificate can be loaded.
     */
    @Synchronized
    fun exists(): Boolean {
        return getExisting() != null
    }

    /**
     * Deletes CurBox's ADB identity.
     *
     * This must not be part of normal reconnect logic. Deleting the identity
     * means an existing ADB pairing can no longer authenticate this client,
     * so a fresh identity will require pairing again.
     */
    @Synchronized
    fun reset() {
        val keyStore =
            loadKeyStore()

        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(
            KEYSTORE_PROVIDER
        ).apply {
            load(null)
        }
    }

    private fun loadIdentity(
        keyStore: KeyStore
    ): Identity? {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return null
        }

        val privateKey =
            keyStore.getKey(
                KEY_ALIAS,
                null
            ) as? PrivateKey
                ?: return null

        val certificate =
            keyStore.getCertificate(
                KEY_ALIAS
            ) ?: return null

        return Identity(
            privateKey = privateKey,
            certificate = certificate
        )
    }

    private fun generateIdentity() {
        val now =
            Calendar.getInstance()

        val notBefore =
            now.time

        val expiry =
            Calendar.getInstance().apply {
                add(
                    Calendar.YEAR,
                    CERTIFICATE_VALIDITY_YEARS
                )
            }.time

        val spec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or
                    KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(
                    RSA_KEY_SIZE
                )
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                )
                .setCertificateSubject(
                    X500Principal(
                        "CN=CurBox Direct ADB"
                    )
                )
                .setCertificateSerialNumber(
                    BigInteger.ONE
                )
                .setCertificateNotBefore(
                    notBefore
                )
                .setCertificateNotAfter(
                    expiry
                )
                .setUserAuthenticationRequired(
                    false
                )
                .build()

        val generator =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                KEYSTORE_PROVIDER
            )

        generator.initialize(spec)
        generator.generateKeyPair()
    }
}
