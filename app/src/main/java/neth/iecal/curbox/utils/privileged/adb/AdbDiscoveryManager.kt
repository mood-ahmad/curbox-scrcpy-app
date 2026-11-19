package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers Wireless ADB endpoints through Android's Network Service
 * Discovery (DNS-SD / mDNS) APIs.
 *
 * CurBox deliberately keeps discovery separate from pairing and connection.
 * This class only converts an advertised ADB service into a host and port.
 */
class AdbDiscoveryManager(
    context: Context
) {

    companion object {
        const val SERVICE_TYPE_TLS_PAIRING =
            "_adb-tls-pairing._tcp."

        const val SERVICE_TYPE_TLS_CONNECT =
            "_adb-tls-connect._tcp."

        private const val MULTICAST_LOCK_TAG =
            "CurBoxAdbDiscovery"
    }

    private val appContext =
        context.applicationContext

    private val nsdManager =
        appContext.getSystemService(
            Context.NSD_SERVICE
        ) as NsdManager

    private val wifiManager =
        appContext.getSystemService(
            Context.WIFI_SERVICE
        ) as? WifiManager

    private val lock =
        Any()

    private var activeSession:
        DiscoverySession? = null

    /**
     * Fully resolved ADB endpoint.
     *
     * Ports advertised by Wireless Debugging are treated as temporary.
     * CurBox should rediscover them rather than considering them permanent.
     */
    data class Endpoint(
        val serviceName: String,
        val serviceType: String,
        val host: String,
        val port: Int
    )

    interface Listener {

        /**
         * Called once when a valid endpoint has been resolved.
         */
        fun onEndpointFound(
            endpoint: Endpoint
        )

        /**
         * Called when discovery or resolution fails.
         */
        fun onError(
            error: Throwable
        )
    }

    /**
     * Starts discovery for the Wireless ADB pairing service.
     */
    fun discoverPairing(
        listener: Listener
    ) {
        startDiscovery(
            serviceType =
                SERVICE_TYPE_TLS_PAIRING,
            listener =
                listener
        )
    }

    /**
     * Starts discovery for the normal Wireless ADB TLS connection service.
     */
    fun discoverConnection(
        listener: Listener
    ) {
        startDiscovery(
            serviceType =
                SERVICE_TYPE_TLS_CONNECT,
            listener =
                listener
        )
    }

    /**
     * Stops the active discovery attempt, if any.
     *
     * Calling this repeatedly is safe.
     */
    fun stop() {
        val session =
            synchronized(lock) {
                val current =
                    activeSession

                activeSession =
                    null

                current
            }

        session?.stop()
    }

    /**
     * Only one discovery operation is kept active at a time.
     *
     * Starting another operation stops the previous one first.
     */
    private fun startDiscovery(
        serviceType: String,
        listener: Listener
    ) {
        stop()

        val session =
            DiscoverySession(
                serviceType =
                    serviceType,
                listener =
                    listener
            )

        synchronized(lock) {
            activeSession =
                session
        }

        session.start()
    }

    private inner class DiscoverySession(
        private val serviceType: String,
        private val listener: Listener
    ) {

        private val finished =
            AtomicBoolean(false)

        private val resolving =
            AtomicBoolean(false)

        private var discoveryStarted =
            false

        private var multicastLock:
            WifiManager.MulticastLock? = null

        private val discoveryListener =
            object : NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    regType: String
                ) {
                    discoveryStarted =
                        true
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {
                    if (finished.get()) {
                        return
                    }

                    /*
                     * Android may report several services. The first service
                     * successfully claimed here is resolved; a later retry can
                     * rediscover if resolution fails.
                     */
                    if (
                        !resolving.compareAndSet(
                            false,
                            true
                        )
                    ) {
                        return
                    }

                    stopDiscoveryOnly()

                    resolve(
                        serviceInfo
                    )
                }

                override fun onServiceLost(
                    serviceInfo: NsdServiceInfo
                ) {
                    // No action is required here. CurBox validates the
                    // connection itself and can rediscover when necessary.
                }

                override fun onDiscoveryStopped(
                    serviceType: String
                ) {
                    discoveryStarted =
                        false
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                    finishWithError(
                        IllegalStateException(
                            "ADB mDNS discovery failed to start. " +
                                "errorCode=$errorCode, " +
                                "serviceType=$serviceType"
                        )
                    )
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int
                ) {
                    discoveryStarted =
                        false
                }
            }

        fun start() {
            try {
                acquireMulticastLock()

                nsdManager.discoverServices(
                    serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (t: Throwable) {
                finishWithError(t)
            }
        }

        fun stop() {
            if (
                !finished.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            stopDiscoveryOnly()
            releaseMulticastLock()
            detach()
        }

        @Suppress("DEPRECATION")
        private fun resolve(
            serviceInfo: NsdServiceInfo
        ) {
            try {
                nsdManager.resolveService(
                    serviceInfo,
                    object :
                        NsdManager.ResolveListener {

                        override fun onServiceResolved(
                            resolvedInfo: NsdServiceInfo
                        ) {
                            val host =
                                resolvedInfo.host

                            val port =
                                resolvedInfo.port

                            if (
                                host == null ||
                                port !in 1..65535
                            ) {
                                finishWithError(
                                    IllegalStateException(
                                        "Resolved ADB mDNS service " +
                                            "does not contain a valid " +
                                            "host and port."
                                    )
                                )

                                return
                            }

                            finishWithEndpoint(
                                resolvedInfo =
                                    resolvedInfo,
                                host =
                                    host,
                                port =
                                    port
                            )
                        }

                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int
                        ) {
                            finishWithError(
                                IllegalStateException(
                                    "ADB mDNS service resolution " +
                                        "failed. errorCode=$errorCode, " +
                                        "serviceName=" +
                                        serviceInfo.serviceName
                                )
                            )
                        }
                    }
                )
            } catch (t: Throwable) {
                finishWithError(t)
            }
        }

        private fun finishWithEndpoint(
            resolvedInfo: NsdServiceInfo,
            host: InetAddress,
            port: Int
        ) {
            if (
                !finished.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            stopDiscoveryOnly()
            releaseMulticastLock()
            detach()

            val hostAddress =
                host.hostAddress

            if (hostAddress.isNullOrBlank()) {
                listener.onError(
                    IllegalStateException(
                        "Resolved ADB mDNS service has no usable host address."
                    )
                )

                return
            }

            listener.onEndpointFound(
                Endpoint(
                    serviceName =
                        resolvedInfo.serviceName,
                    serviceType =
                        serviceType,
                    host =
                        hostAddress,
                    port =
                        port
                )
            )
        }

        private fun finishWithError(
            error: Throwable
        ) {
            if (
                !finished.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            stopDiscoveryOnly()
            releaseMulticastLock()
            detach()

            listener.onError(
                error
            )
        }

        /**
         * Stops only Android's discovery registration.
         *
         * Resolution can continue after discovery itself has stopped.
         */
        private fun stopDiscoveryOnly() {
            if (!discoveryStarted) {
                return
            }

            try {
                nsdManager.stopServiceDiscovery(
                    discoveryListener
                )
            } catch (_: IllegalArgumentException) {
                // Android may report that discovery has already stopped.
            } catch (_: Exception) {
                // Cleanup must not replace the original result/error.
            } finally {
                discoveryStarted =
                    false
            }
        }

        /**
         * Hold multicast reception for the short discovery operation.
         *
         * This keeps the legacy path usable on Android versions where NSD did
         * not yet manage foreground multicast reception automatically.
         */
        @Suppress("DEPRECATION")
        private fun acquireMulticastLock() {
            val manager =
                wifiManager
                    ?: return

            try {
                multicastLock =
                    manager.createMulticastLock(
                        MULTICAST_LOCK_TAG
                    ).apply {
                        setReferenceCounted(
                            false
                        )

                        acquire()
                    }
            } catch (_: Exception) {
                multicastLock =
                    null
            }
        }

        private fun releaseMulticastLock() {
            val current =
                multicastLock

            multicastLock =
                null

            if (current != null) {
                try {
                    if (current.isHeld) {
                        current.release()
                    }
                } catch (_: Exception) {
                    // Cleanup failure does not change discovery result.
                }
            }
        }

        private fun detach() {
            synchronized(lock) {
                if (
                    activeSession ===
                    this
                ) {
                    activeSession =
                        null
                }
            }
        }
    }
}
