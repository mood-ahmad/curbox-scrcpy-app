package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
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
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Owns CurBox's persistent ADB client identity.
 *
 * libadb-android's pairing path requires an RSA private key that its crypto
 * provider can use directly. Therefore CurBox uses a normal software RSA key
 * pair here instead of passing an AndroidKeyStore-backed PrivateKey object to
 * Conscrypt.
 *
 * The identity is generated once, stored in CurBox's private app storage, and
 * reused across process restarts. Replacing the identity requires pairing
 * again, so normal recovery must never regenerate it.
 */
object AdbIdentityManager {

    private const val PRIVATE_KEY_FILE =
        "curbox_adb_private.key"

    private const val CERTIFICATE_FILE =
        "curbox_adb_certificate.der"

    private const val RSA_KEY_SIZE =
        2048

    private const val CERTIFICATE_SUBJECT =
        "CN=CurBox Direct ADB"

    private const val CERTIFICATE_ALGORITHM =
        "SHA512withRSA"

    /**
     * Keep the certificate well beyond normal app-update/restart cycles.
     *
     * This does not control Android's pairing trust lifetime. It only avoids
     * replacing CurBox's own identity because its local certificate expired.
     */
    private val CERTIFICATE_VALIDITY_MS =
        TimeUnit.DAYS.toMillis(
            3650L
        )

    data class Identity(
        val privateKey: PrivateKey,
        val certificate: Certificate
    )

    /**
     * Returns the existing persistent identity or creates it once.
     *
     * This operation is synchronized so startup, recovery, and explicit setup
     * cannot race while the initial identity files are being created.
     */
    @Synchronized
    fun getOrCreate(
        context: Context
    ): Identity {
        val appContext =
            context.applicationContext

        loadIdentity(
            appContext
        )?.let {
            return it
        }

        /*
         * If only one identity file exists, the previous write was incomplete
         * or the stored identity is unusable. Remove both before generating a
         * replacement so key and certificate can never belong to different
         * key pairs.
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
     * Returns the stored software ADB identity without creating a new one.
     */
    @Synchronized
    fun getExisting(
        context: Context
    ): Identity? {
        return loadIdentity(
            context.applicationContext
        )
    }

    /**
     * Returns true only when both identity files can be decoded successfully.
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
     * Explicitly deletes CurBox's Direct ADB identity.
     *
     * Existing Android Wireless Debugging trust refers to the old public key,
     * so the next Direct Access setup will require pairing again.
     *
     * Normal reconnect/recovery logic must not call this.
     */
    @Synchronized
    fun reset(
        context: Context
    ) {
        deleteIdentityFiles(
            context.applicationContext
        )
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
            val privateKeyBytes =
                privateKeyFile.readBytes()

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

            val certificate =
                certificateFile
                    .inputStream()
                    .use { input ->
                        CertificateFactory
                            .getInstance(
                                "X.509"
                            )
                            .generateCertificate(
                                input
                            )
                    }

            /*
             * Verify that the persisted key and certificate still form the
             * same RSA identity before handing them to libadb.
             */
            val certificatePublicKey =
                certificate.publicKey

            if (
                certificatePublicKey.algorithm !=
                "RSA" ||
                privateKey.algorithm !=
                "RSA"
            ) {
                return null
            }

            Identity(
                privateKey = privateKey,
                certificate = certificate
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun generateIdentity():
        Identity {

        /*
         * Match libadb-android's test application: generate an ordinary RSA
         * software key pair rather than an AndroidKeyStore-backed key.
         */
        val keyPairGenerator =
            KeyPairGenerator.getInstance(
                "RSA"
            )

        keyPairGenerator.initialize(
            RSA_KEY_SIZE,
            SecureRandom.getInstance(
                "SHA1PRNG"
            )
        )

        val keyPair =
            keyPairGenerator
                .generateKeyPair()

        val privateKey =
            keyPair.private

        val publicKey =
            keyPair.public

        val notBefore =
            Date()

        val notAfter =
            Date(
                System.currentTimeMillis() +
                    CERTIFICATE_VALIDITY_MS
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
                Random.nextInt(
                    1,
                    Int.MAX_VALUE
                )
            )
        )

        certificateInfo.set(
            "algorithmID",
            CertificateAlgorithmId(
                AlgorithmId.get(
                    CERTIFICATE_ALGORITHM
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
            CERTIFICATE_ALGORITHM
        )

        return Identity(
            privateKey = privateKey,
            certificate = certificate
        )
    }

    private fun persistIdentity(
        context: Context,
        identity: Identity
    ) {
        val encodedPrivateKey =
            identity.privateKey.encoded
                ?: throw IllegalStateException(
                    "Generated ADB private key cannot be encoded as PKCS#8."
                )

        val encodedCertificate =
            identity.certificate.encoded
                ?: throw IllegalStateException(
                    "Generated ADB certificate cannot be encoded."
                )

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

        val temporaryPrivateKeyFile =
            File(
                context.filesDir,
                "$PRIVATE_KEY_FILE.tmp"
            )

        val temporaryCertificateFile =
            File(
                context.filesDir,
                "$CERTIFICATE_FILE.tmp"
            )

        try {
            temporaryPrivateKeyFile.writeBytes(
                encodedPrivateKey
            )

            temporaryCertificateFile.writeBytes(
                encodedCertificate
            )

            if (
                !temporaryPrivateKeyFile.renameTo(
                    privateKeyFile
                )
            ) {
                throw IllegalStateException(
                    "Could not persist the Direct ADB private key."
                )
            }

            if (
                !temporaryCertificateFile.renameTo(
                    certificateFile
                )
            ) {
                privateKeyFile.delete()

                throw IllegalStateException(
                    "Could not persist the Direct ADB certificate."
                )
            }
        } finally {
            temporaryPrivateKeyFile.delete()
            temporaryCertificateFile.delete()
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

        File(
            context.filesDir,
            "$PRIVATE_KEY_FILE.tmp"
        ).delete()

        File(
            context.filesDir,
            "$CERTIFICATE_FILE.tmp"
        ).delete()

        /*
         * Remove the incompatible identity used by CurBox's earlier
         * AndroidKeyStore-based implementation.
         *
         * Failure to remove it is harmless because the new implementation no
         * longer reads it.
         */
        try {
            val keyStore =
                java.security.KeyStore.getInstance(
                    "AndroidKeyStore"
                ).apply {
                    load(null)
                }

            val oldAlias =
                "curbox_direct_adb_identity"

            if (
                keyStore.containsAlias(
                    oldAlias
                )
            ) {
                keyStore.deleteEntry(
                    oldAlias
                )
            }
        } catch (_: Exception) {
            // The software identity does not depend on this cleanup.
        }
    }
}
