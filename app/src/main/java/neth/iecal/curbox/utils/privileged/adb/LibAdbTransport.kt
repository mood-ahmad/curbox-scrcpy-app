package neth.iecal.curbox.utils.privileged.adb

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Direct ADB transport backed by libadb-android 3.1.1.
 *
 * This is intentionally the only layer that exposes libadb-android types.
 * The rest of CurBox communicates through [AdbTransport] and
 * [AdbTransportStream].
 */
class LibAdbTransport : AdbTransport {

    companion object {
        private const val DEVICE_NAME = "CurBox"
        private const val READ_BUFFER_SIZE = 8 * 1024
    }

    private val lock = Any()

    @Volatile
    private var currentState =
        AdbTransport.State.DISCONNECTED

    override val state: AdbTransport.State
        get() = currentState

    private val identity: AdbIdentityManager.Identity =
        AdbIdentityManager.getOrCreate()

    /**
     * Concrete libadb connection manager using CurBox's persistent identity.
     */
    private val manager =
        object : AbsAdbConnectionManager() {

            override fun getPrivateKey(): PrivateKey {
                return identity.privateKey
            }

            override fun getCertificate(): Certificate {
                return identity.certificate
            }

            override fun getDeviceName(): String {
                return DEVICE_NAME
            }
        }.apply {
            setApi(Build.VERSION.SDK_INT)
        }

    override fun isConnected(): Boolean {
        return try {
            val connected =
                manager.isConnected

            if (!connected) {
                currentState =
                    AdbTransport.State.DISCONNECTED
            }

            connected
        } catch (_: Exception) {
            currentState =
                AdbTransport.State.DISCONNECTED

            false
        }
    }

    override fun pair(
        host: String,
        port: Int,
        pairingCode: String
    ): Result<Unit> {
        synchronized(lock) {
            currentState =
                AdbTransport.State.PAIRING

            return try {
                val paired =
                    manager.pair(
                        host,
                        port,
                        pairingCode
                    )

                currentState =
                    AdbTransport.State.DISCONNECTED

                if (paired) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        IllegalStateException(
                            "ADB pairing was rejected or did not complete."
                        )
                    )
                }
            } catch (t: Throwable) {
                currentState =
                    AdbTransport.State.DISCONNECTED

                Result.failure(t)
            }
        }
    }

    override fun connect(
        host: String,
        port: Int
    ): Result<Unit> {
        synchronized(lock) {
            if (isConnected()) {
                currentState =
                    AdbTransport.State.CONNECTED

                return Result.success(Unit)
            }

            currentState =
                AdbTransport.State.CONNECTING

            return try {
                val connected =
                    manager.connect(
                        host,
                        port
                    )

                if (!connected) {
                    currentState =
                        AdbTransport.State.DISCONNECTED

                    return Result.failure(
                        IllegalStateException(
                            "libadb-android did not establish an ADB connection."
                        )
                    )
                }

                /*
                 * Do not trust connect() alone.
                 *
                 * Verify that the freshly connected channel can actually open
                 * an ADB service. This also protects CurBox from treating a
                 * stale/broken connection as ready.
                 */
                verifyConnection()

                currentState =
                    AdbTransport.State.CONNECTED

                Result.success(Unit)
            } catch (t: Throwable) {
                try {
                    manager.disconnect()
                } catch (_: Exception) {
                    // Preserve the original connection/verification failure.
                }

                currentState =
                    AdbTransport.State.DISCONNECTED

                Result.failure(t)
            }
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            try {
                manager.disconnect()
            } catch (_: Exception) {
                // Disconnect is idempotent from CurBox's point of view.
            } finally {
                currentState =
                    AdbTransport.State.DISCONNECTED
            }
        }
    }

    override fun openService(
        service: String
    ): Result<AdbTransportStream> {
        return try {
            if (!isConnected()) {
                return Result.failure(
                    IllegalStateException(
                        "Direct ADB is not connected."
                    )
                )
            }

            val stream =
                manager.openStream(service)

            Result.success(
                LibAdbTransportStream(stream)
            )
        } catch (t: Throwable) {
            currentState =
                AdbTransport.State.DISCONNECTED

            Result.failure(t)
        }
    }

    /**
     * Verifies the connection by opening a tiny shell command and reading its
     * response.
     *
     * A successful manager.connect() is not considered sufficient on its own.
     */
    private fun verifyConnection() {
        val stream =
            manager.openStream(
                "shell:echo curbox_adb_ready"
            )

        try {
            val input =
                stream.openInputStream()

            val buffer =
                ByteArray(READ_BUFFER_SIZE)

            val output =
                StringBuilder()

            while (true) {
                val count =
                    input.read(buffer)

                if (count < 0) {
                    break
                }

                if (count == 0) {
                    continue
                }

                output.append(
                    String(
                        buffer,
                        0,
                        count,
                        Charsets.UTF_8
                    )
                )
            }

            if (
                !output
                    .toString()
                    .contains("curbox_adb_ready")
            ) {
                throw IllegalStateException(
                    "ADB connection health check returned an unexpected response."
                )
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
                // The health-check result has already been determined.
            }
        }
    }

    private class LibAdbTransportStream(
        private val stream: AdbStream
    ) : AdbTransportStream {

        override fun write(
            data: ByteArray
        ) {
            stream.write(
                data,
                0,
                data.size
            )

            stream.flush()
        }

        override fun read(): ByteArray {
            val available =
                stream.available()

            if (available <= 0) {
                return ByteArray(0)
            }

            val buffer =
                ByteArray(available)

            val count =
                stream.read(
                    buffer,
                    0,
                    buffer.size
                )

            if (count <= 0) {
                return ByteArray(0)
            }

            return if (count == buffer.size) {
                buffer
            } else {
                buffer.copyOf(count)
            }
        }

        override fun close() {
            stream.close()
        }
    }
}
