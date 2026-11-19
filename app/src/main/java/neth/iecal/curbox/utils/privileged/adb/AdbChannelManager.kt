package neth.iecal.curbox.utils.privileged.adb

import android.content.Context

/**
 * Central coordinator for CurBox's Direct ADB channel.
 *
 * The manager owns the currently active [AdbTransport] implementation and
 * provides a backend-independent entry point for pairing, connecting,
 * opening ADB services, and disconnecting.
 *
 * The transport is initialized explicitly instead of during object loading.
 * This avoids creating CurBox's persistent ADB identity until Direct Access
 * is actually being prepared for use.
 */
object AdbChannelManager {

    private val transportLock = Any()

    @Volatile
    private var transport: AdbTransport? = null

    /**
     * Ensures that CurBox's default Direct ADB transport is installed.
     *
     * Calling this method multiple times is safe. Once a transport is already
     * installed, the existing instance is preserved so its connection state
     * and persistent ADB identity are not disturbed.
     *
     * The application context is passed to the concrete transport because the
     * persistent ADB identity is stored in CurBox's private application
     * storage.
     */
    fun initialize(
        context: Context
    ): Result<Unit> {
        if (transport != null) {
            return Result.success(
                Unit
            )
        }

        val appContext =
            context.applicationContext

        synchronized(transportLock) {
            if (transport != null) {
                return Result.success(
                    Unit
                )
            }

            return try {
                transport =
                    LibAdbTransport(
                        appContext
                    )

                Result.success(
                    Unit
                )
            } catch (t: Throwable) {
                transport =
                    null

                Result.failure(
                    t
                )
            }
        }
    }

    /**
     * Returns true when a transport implementation is currently installed.
     *
     * This does not mean that the ADB connection itself is active.
     */
    fun isInitialized(): Boolean {
        return transport != null
    }

    /**
     * Installs a transport implementation used by Direct ADB.
     *
     * This remains available so tests or future alternative transports can be
     * installed without changing the rest of CurBox.
     *
     * Replacing the transport first disconnects the previous transport so
     * CurBox does not accidentally keep two ADB sessions alive.
     */
    fun installTransport(
        newTransport: AdbTransport
    ) {
        synchronized(transportLock) {
            if (transport === newTransport) {
                return
            }

            val previous =
                transport

            transport =
                newTransport

            if (previous != null) {
                try {
                    previous.disconnect()
                } catch (_: Exception) {
                    // The new transport is already installed. Failure to clean
                    // up an old broken connection must not undo replacement.
                }
            }
        }
    }

    /**
     * Removes the currently installed transport.
     *
     * This is different from [disconnect]. clearTransport() removes the
     * transport object itself, while disconnect() keeps it available for a
     * later reconnect.
     */
    fun clearTransport() {
        val previous:
            AdbTransport?

        synchronized(transportLock) {
            previous =
                transport

            transport =
                null
        }

        if (previous != null) {
            try {
                previous.disconnect()
            } catch (_: Exception) {
                // The manager has already detached from this transport.
            }
        }
    }

    /**
     * Returns true only when an installed transport reports an authenticated
     * ADB connection ready for use.
     */
    fun isConnected(): Boolean {
        val current =
            transport
                ?: return false

        return try {
            current.isConnected()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the current Direct ADB transport state.
     */
    fun state(): AdbTransport.State {
        val current =
            transport
                ?: return AdbTransport.State.DISCONNECTED

        return try {
            current.state
        } catch (_: Exception) {
            AdbTransport.State.DISCONNECTED
        }
    }

    /**
     * Pairs using an endpoint already discovered by the pairing layer.
     */
    fun pair(
        host: String,
        port: Int,
        pairingCode: String
    ): Result<Unit> {
        val current =
            transport
                ?: return Result.failure(
                    IllegalStateException(
                        "Direct ADB transport is not initialized."
                    )
                )

        if (host.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "ADB pairing host must not be blank."
                )
            )
        }

        if (port !in 1..65535) {
            return Result.failure(
                IllegalArgumentException(
                    "ADB pairing port is invalid: $port"
                )
            )
        }

        if (pairingCode.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "ADB pairing code must not be blank."
                )
            )
        }

        return try {
            current.pair(
                host = host,
                port = port,
                pairingCode = pairingCode
            )
        } catch (t: Throwable) {
            Result.failure(
                t
            )
        }
    }

    /**
     * Connects using an endpoint already discovered by the connection layer.
     */
    fun connect(
        host: String,
        port: Int
    ): Result<Unit> {
        val current =
            transport
                ?: return Result.failure(
                    IllegalStateException(
                        "Direct ADB transport is not initialized."
                    )
                )

        if (host.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "ADB host must not be blank."
                )
            )
        }

        if (port !in 1..65535) {
            return Result.failure(
                IllegalArgumentException(
                    "ADB port is invalid: $port"
                )
            )
        }

        return try {
            current.connect(
                host = host,
                port = port
            )
        } catch (t: Throwable) {
            Result.failure(
                t
            )
        }
    }

    /**
     * Opens an ADB service through the active transport.
     *
     * Command execution uses this with services such as "shell:".
     */
    fun openService(
        service: String
    ): Result<AdbTransportStream> {
        val current =
            transport
                ?: return Result.failure(
                    IllegalStateException(
                        "Direct ADB transport is not initialized."
                    )
                )

        if (service.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "ADB service must not be blank."
                )
            )
        }

        if (!isConnected()) {
            return Result.failure(
                IllegalStateException(
                    "Direct ADB is not connected."
                )
            )
        }

        return try {
            current.openService(
                service
            )
        } catch (t: Throwable) {
            Result.failure(
                t
            )
        }
    }

    /**
     * Disconnects the current ADB session without removing the transport.
     *
     * This allows the same transport and stored identity to be reused during
     * automatic reconnection.
     */
    fun disconnect() {
        val current =
            transport
                ?: return

        try {
            current.disconnect()
        } catch (_: Exception) {
            // Disconnect is intentionally idempotent from the caller's point
            // of view.
        }
    }
}
