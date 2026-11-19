package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns CurBox's persistent ADB client identity.
 *
 * libadb-android's reference application uses a software RSA key pair for the
 * ADB identity. CurBox follows that model instead of passing an
 * AndroidKeyStore-backed RSA private key directly into libadb/Conscrypt.
 *
 * The software RSA private key is still protected at rest:
 *
 * - RSA signing key: normal software RSA PrivateKey, compatible with libadb.
 * - Stored PKCS#8 bytes: encrypted with AES/GCM.
 * - AES wrapping key: non-exportable and stored in AndroidKeyStore.
 * - X.509 certificate: stored as the public certificate bytes.
 *
 * The same RSA identity is reused after process and device restarts so an
 * existing ADB pairing can continue to trust CurBox.
 */
object AdbIdentityManager {

    private const val ANDROID_KEYSTORE =
        "AndroidKeyStore"

    /**
     * Old alias used by the first Direct ADB implementation.
     *
     * That implementation put the RSA private key itself in AndroidKeyStore.
     * The alias is removed during migration because that key type caused the
     * libadb/Conscrypt pairing path to fail on the tested device.
     */
    private const val LEGACY_RSA_ALIAS =
        "curbox_direct_adb_identity"

    /**
     * AndroidKeyStore contains only this AES wrapping key.
     *
     * The actual ADB RSA private key remains a normal software PrivateKey
     * after decryption.
     */
    private const val WRAPPING_KEY_ALIAS =
        "curbox_direct_adb_identity_wrapping_key"

    private const val PRIVATE_KEY_FILE =
        "curbox_direct_adb_private_key.bin"

    private const val CERTIFICATE_FILE =
        "curbox_direct_adb_certificate.der"

    private const val RSA_KEY_SIZE =
        2048

    private const val AES_KEY_SIZE =
        256

    private const val GCM_TAG_LENGTH_BITS =
        128

    private const val STORAGE_VERSION =
        1

    private const val CERTIFICATE_VALIDITY_YEARS =
        20L

    private const val CERTIFICATE_SUBJECT =
        "CN=CurBox Direct ADB"

    private const val CERTIFICATE_SIGNATURE_ALGORITHM =
        "SHA512withRSA"

    data class Identity(
        val privateKey: PrivateKey,
        val certificate: Certificate
    )

    /**
     * Returns CurBox's existing ADB identity or creates a new compatible
     * software-RSA identity when none has been stored yet.
     */
    @Synchronized
    fun getOrCreate(
        context: Context
    ): Identity {
        val appContext =
            context.applicationContext

        migrateLegacyIdentity()

        loadIdentity(
            appContext
        )?.let {
            return it
        }

        /*
         * Do not keep half of a previous/corrupted identity.
         */
        deleteIdentityFiles(
            appContext
        )

        val identity =
            generateIdentity()

        persistIdentity(
            context = appContext,
            identity = identity
        )

        return loadIdentity(
            appContext
        ) ?: throw IllegalStateException(
            "ADB identity was generated and stored but could not be reloaded."
        )
    }

    /**
     * Returns the stored identity without generating a new one.
     */
    @Synchronized
    fun getExisting(
        context: Context
    ): Identity? {
        val appContext =
            context.applicationContext

        migrateLegacyIdentity()

        return loadIdentity(
            appContext
        )
    }

    /**
     * Returns true only when both the private RSA key and matching certificate
     * can be loaded successfully.
     */
    @Synchronized
    fun exists(
        context: Context
    ): Boolean {
        return getExisting(
            context
        ) != null
    }

    /**
     * Deletes CurBox's ADB identity.
     *
     * Existing ADB trust for this identity becomes unusable after reset, so
     * this method belongs only to an explicit repair/reset flow.
     */
    @Synchronized
    fun reset(
        context: Context
    ) {
        val appContext =
            context.applicationContext

        deleteIdentityFiles(
            appContext
        )

        val keyStore =
            loadAndroidKeyStore()

        if (
            keyStore.containsAlias(
                WRAPPING_KEY_ALIAS
            )
        ) {
            keyStore.deleteEntry(
                WRAPPING_KEY_ALIAS
            )
        }

        if (
            keyStore.containsAlias(
                LEGACY_RSA_ALIAS
            )
        ) {
            keyStore.deleteEntry(
                LEGACY_RSA_ALIAS
            )
        }
    }

    /**
     * Removes the original AndroidKeyStore-backed RSA identity.
     *
     * It is deliberately not converted because AndroidKeyStore private keys
     * are non-exportable. CurBox therefore creates a new software RSA
     * identity once and requires one fresh ADB pairing.
     */
    private fun migrateLegacyIdentity() {
        val keyStore =
            loadAndroidKeyStore()

        if (
            keyStore.containsAlias(
                LEGACY_RSA_ALIAS
            )
        ) {
            keyStore.deleteEntry(
                LEGACY_RSA_ALIAS
            )
        }
    }

    private fun loadIdentity(
        context: Context
    ): Identity? {
        val privateKeyFile =
            File(
                context.filesDir,
                PRIVATE_KEY_FILE
            )

        val certificateFile =
            File(
                context.filesDir,
                CERTIFICATE_FILE
            )

        if (
            !privateKeyFile.isFile ||
            !certificateFile.isFile
        ) {
            return null
        }

        return try {
            val encryptedPrivateKey =
                readEncryptedPrivateKey(
                    privateKeyFile
                )

            val privateKeyBytes =
                decryptPrivateKey(
                    encryptedPrivateKey
                )

            val privateKey =
                KeyFactory
                    .getInstance(
                        "RSA"
                    )
                    .generatePrivate(
                        PKCS8EncodedKeySpec(
                            privateKeyBytes
                        )
                    )

            /*
             * Clear the decrypted encoded bytes as soon as KeyFactory has
             * created the PrivateKey object.
             */
            privateKeyBytes.fill(
                0
            )

            val certificate =
                FileInputStream(
                    certificateFile
                ).use { input ->
                    CertificateFactory
                        .getInstance(
                            "X.509"
                        )
                        .generateCertificate(
                            input
                        )
                }

            Identity(
                privateKey = privateKey,
                certificate = certificate
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun generateIdentity(): Identity {
        val keyPairGenerator =
            KeyPairGenerator.getInstance(
                "RSA"
            )

        keyPairGenerator.initialize(
            RSA_KEY_SIZE,
            SecureRandom()
        )

        val keyPair =
            keyPairGenerator
                .generateKeyPair()

        val certificate =
            generateCertificate(
                publicKey =
                    keyPair.public,
                privateKey =
                    keyPair.private
            )

        return Identity(
            privateKey =
                keyPair.private,
            certificate =
                certificate
        )
    }

    /**
     * Generates the self-signed X.509 certificate expected by libadb's TLS
     * identity path.
     *
     * The structure follows the libadb-android reference application's
     * certificate-generation approach.
     */
    private fun generateCertificate(
        publicKey: PublicKey,
        privateKey: PrivateKey
    ): Certificate {
        val now =
            System.currentTimeMillis()

        val validityMillis =
            CERTIFICATE_VALIDITY_YEARS *
                365L *
                24L *
                60L *
                60L *
                1000L

        val notBefore =
            Date(
                now
            )

        val notAfter =
            Date(
                now + validityMillis
            )

        val extensions =
            CertificateExtensions()

        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(
                KeyIdentifier(
                    publicKey
                ).identifier
            )
        )

        extensions.set(
            "PrivateKeyUsage",
            PrivateKeyUsageExtension(
                notBefore,
                notAfter
            )
        )

        val x500Name =
            X500Name(
                CERTIFICATE_SUBJECT
            )

        val validity =
            CertificateValidity(
                notBefore,
                notAfter
            )

        val serialNumber =
            SecureRandom()
                .nextInt()
                .and(
                    Int.MAX_VALUE
                )

        val certificateInfo =
            X509CertInfo()

        certificateInfo.set(
            "version",
            CertificateVersion(
                2
            )
        )

        certificateInfo.set(
            "serialNumber",
            CertificateSerialNumber(
                serialNumber
            )
        )

        certificateInfo.set(
            "algorithmID",
            CertificateAlgorithmId(
                AlgorithmId.get(
                    CERTIFICATE_SIGNATURE_ALGORITHM
                )
            )
        )

        certificateInfo.set(
            "subject",
            CertificateSubjectName(
                x500Name
            )
        )

        certificateInfo.set(
            "key",
            CertificateX509Key(
                publicKey
            )
        )

        certificateInfo.set(
            "validity",
            validity
        )

        certificateInfo.set(
            "issuer",
            CertificateIssuerName(
                x500Name
            )
        )

        certificateInfo.set(
            "extensions",
            extensions
        )

        val certificate =
            X509CertImpl(
                certificateInfo
            )

        certificate.sign(
            privateKey,
            CERTIFICATE_SIGNATURE_ALGORITHM
        )

        return certificate
    }

    private fun persistIdentity(
        context: Context,
        identity: Identity
    ) {
        val privateKeyFile =
            File(
                context.filesDir,
                PRIVATE_KEY_FILE
            )

        val certificateFile =
            File(
                context.filesDir,
                CERTIFICATE_FILE
            )

        val encodedPrivateKey =
            identity
                .privateKey
                .encoded
                ?: throw IllegalStateException(
                    "Generated ADB private key does not expose PKCS#8 encoding."
                )

        try {
            val encryptedPrivateKey =
                encryptPrivateKey(
                    encodedPrivateKey
                )

            writeEncryptedPrivateKey(
                file =
                    privateKeyFile,
                encrypted =
                    encryptedPrivateKey
            )

            FileOutputStream(
                certificateFile
            ).use { output ->
                output.write(
                    identity
                        .certificate
                        .encoded
                )

                output.flush()
            }
        } catch (t: Throwable) {
            deleteIdentityFiles(
                context
            )

            throw t
        } finally {
            encodedPrivateKey.fill(
                0
            )
        }
    }

    private data class EncryptedPrivateKey(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    private fun encryptPrivateKey(
        privateKeyBytes: ByteArray
    ): EncryptedPrivateKey {
        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateWrappingKey()
        )

        return EncryptedPrivateKey(
            iv =
                cipher.iv,
            ciphertext =
                cipher.doFinal(
                    privateKeyBytes
                )
        )
    }

    private fun decryptPrivateKey(
        encrypted:
            EncryptedPrivateKey
    ): ByteArray {
        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(
                GCM_TAG_LENGTH_BITS,
                encrypted.iv
            )
        )

        return cipher.doFinal(
            encrypted.ciphertext
        )
    }

    private fun getOrCreateWrappingKey():
        SecretKey {

        val keyStore =
            loadAndroidKeyStore()

        val existing =
            keyStore.getKey(
                WRAPPING_KEY_ALIAS,
                null
            ) as? SecretKey

        if (existing != null) {
            return existing
        }

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

        val spec =
            KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(
                    AES_KEY_SIZE
                )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setUserAuthenticationRequired(
                    false
                )
                .build()

        keyGenerator.init(
            spec
        )

        return keyGenerator
            .generateKey()
    }

    private fun writeEncryptedPrivateKey(
        file: File,
        encrypted: EncryptedPrivateKey
    ) {
        DataOutputStream(
            FileOutputStream(
                file
            )
        ).use { output ->
            output.writeInt(
                STORAGE_VERSION
            )

            output.writeInt(
                encrypted.iv.size
            )

            output.write(
                encrypted.iv
            )

            output.writeInt(
                encrypted
                    .ciphertext
                    .size
            )

            output.write(
                encrypted.ciphertext
            )

            output.flush()
        }
    }

    private fun readEncryptedPrivateKey(
        file: File
    ): EncryptedPrivateKey {
        DataInputStream(
            FileInputStream(
                file
            )
        ).use { input ->
            val version =
                input.readInt()

            if (
                version !=
                STORAGE_VERSION
            ) {
                throw IllegalStateException(
                    "Unsupported Direct ADB identity storage version: $version"
                )
            }

            val ivSize =
                input.readInt()

            if (
                ivSize !in
                1..64
            ) {
                throw IllegalStateException(
                    "Invalid Direct ADB identity IV length."
                )
            }

            val iv =
                ByteArray(
                    ivSize
                )

            input.readFully(
                iv
            )

            val ciphertextSize =
                input.readInt()

            if (
                ciphertextSize !in
                1..65_536
            ) {
                throw IllegalStateException(
                    "Invalid Direct ADB private-key payload length."
                )
            }

            val ciphertext =
                ByteArray(
                    ciphertextSize
                )

            input.readFully(
                ciphertext
            )

            return EncryptedPrivateKey(
                iv =
                    iv,
                ciphertext =
                    ciphertext
            )
        }
    }

    private fun deleteIdentityFiles(
        context: Context
    ) {
        File(
            context.filesDir,
            PRIVATE_KEY_FILE
        ).delete()

        File(
            context.filesDir,
            CERTIFICATE_FILE
        ).delete()
    }

    private fun loadAndroidKeyStore():
        KeyStore {

        return KeyStore
            .getInstance(
                ANDROID_KEYSTORE
            )
            .apply {
                load(
                    null
                )
            }
    }
}
