package neth.iecal.curbox.utils.privileged.adb

/**
 * CurBox-owned abstraction for communicating with an ADB daemon.
 *
 * Higher-level CurBox components must depend on this interface instead of
 * depending directly on libadb-android. This keeps pairing, connection,
 * discovery, and command execution replaceable independently of the library
 * used underneath.
 */
interface AdbTransport {

    /**
     * Current lifecycle state of this transport.
     */
    val state: State

    /**
     * Returns true only when this transport currently has an authenticated
     * ADB connection that is ready to open services such as "shell:".
     */
    fun isConnected(): Boolean

    /**
     * Performs ADB Wireless Debugging pairing against a previously discovered
     * pairing endpoint.
     *
     * Pairing and normal ADB connection are deliberately separate operations.
     */
    fun pair(
        host: String,
        port: Int,
        pairingCode: String
    ): Result<Unit>

    /**
     * Connects to a previously discovered ADB endpoint using the stored ADB
     * identity.
     */
    fun connect(
        host: String,
        port: Int
    ): Result<Unit>

    /**
     * Disconnects the current ADB session, if one exists.
     *
     * Implementations should make repeated calls safe.
     */
    fun disconnect()

    /**
     * Opens an ADB service.
     *
     * Initial Direct Access work will mainly use "shell:", but keeping this
     * method service-based prevents the transport from being unnecessarily
     * tied to shell commands.
     */
    fun openService(
        service: String
    ): Result<AdbTransportStream>

    enum class State {
        DISCONNECTED,
        PAIRING,
        CONNECTING,
        CONNECTED
    }
}

/**
 * Backend-independent wrapper around an opened ADB service stream.
 *
 * This avoids exposing libadb-android's AdbStream to the rest of CurBox.
 */
interface AdbTransportStream : AutoCloseable {

    /**
     * Writes raw bytes to the opened ADB service.
     */
    fun write(
        data: ByteArray
    )

    /**
     * Reads currently available data from the opened ADB service.
     *
     * An empty array means that no payload was returned by this read.
     */
    fun read(): ByteArray

    /**
     * Releases the opened service stream.
     */
    override fun close()
}
