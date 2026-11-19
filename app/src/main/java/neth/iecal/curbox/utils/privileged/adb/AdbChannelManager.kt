package neth.iecal.curbox.utils.privileged.adb

/**
 * Central coordinator for CurBox's Direct ADB channel.
 *
 * This class deliberately depends only on [AdbTransport]. The concrete
 * libadb-android implementation will be attached later, after ADB identity
 * generation and persistence are implemented.
 */
object AdbChannelManager {

    @Volatile
    private var transport: AdbTransport? = null

    /**
     * Installs the transport implementation used by Direct ADB.
     *
     * Replacing the transport first disconnects the previous transport so
     * CurBox does not accidentally keep two ADB sessions alive.
     */
    @Synchronized
    fun installTransport(
        newTransport: AdbTransport
    ) {
        if (transport === newTransport) {
            return
        }

        transport?.let { current ->
            try {
                current.disconnect()
            } catch (_: Exception) {
                // Replacing the transport must still be possible even when
                // cleanup of an old or broken connection fails.
            }
        }

        transport = newTransport
    }

    /**
     * Removes the currently installed transport.
     */
    @Synchronized
    fun clearTransport() {
        val current = transport

        transport = null

        if (current != null) {
            try {
                current.disconnect()
            } catch (_: Exception) {
                // The manager is already detached from this transport.
            }
        }
    }

    /**
     * Returns true only when an installed transport reports an authenticated
     * ADB connection ready for use.
     */
    fun isConnected(): Boolean {
        val current = transport
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
        val current = transport
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
        val current = transport
            ?: return Result.failure(
                IllegalStateException(
                    "Direct ADB transport is not installed."
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

        return current.pair(
            host = host,
            port = port,
            pairingCode = pairingCode
        )
    }

    /**
     * Connects using an endpoint already discovered by the connection layer.
     */
    fun connect(
        host: String,
        port: Int
    ): Result<Unit> {
        val current = transport
            ?: return Result.failure(
                IllegalStateException(
                    "Direct ADB transport is not installed."
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

        return current.connect(
            host = host,
            port = port
        )
    }

    /**
     * Opens an ADB service through the active transport.
     *
     * Command execution will later use this with "shell:".
     */
    fun openService(
        service: String
    ): Result<AdbTransportStream> {
        val current = transport
            ?: return Result.failure(
                IllegalStateException(
                    "Direct ADB transport is not installed."
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

        return current.openService(service)
    }

    /**
     * Disconnects the current ADB session without removing the transport.
     *
     * This allows the same transport and stored identity to be reused during
     * automatic reconnection.
     */
    fun disconnect() {
        val current = transport
            ?: return

        try {
            current.disconnect()
        } catch (_: Exception) {
            // Disconnect is intentionally idempotent from the caller's point
            // of view.
        }
    }
}
