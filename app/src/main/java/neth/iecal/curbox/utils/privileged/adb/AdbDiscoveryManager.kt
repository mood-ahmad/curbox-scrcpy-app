package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers Wireless ADB endpoints through Android's Network Service
 * Discovery (DNS-SD / mDNS) APIs.
 *
 * CurBox deliberately keeps discovery separate from pairing and connection.
 *
 * Discovery remains active while candidate services are resolved. A failed
 * resolution therefore does not terminate the whole Direct Access attempt.
 * CurBox continues trying other advertised services until it obtains a valid
 * endpoint or the bounded discovery operation times out.
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

        /**
         * CurBox bounds each mDNS discovery attempt rather than waiting
         * indefinitely when no usable endpoint can be resolved.
         *
         * This is a CurBox design timeout, not an Android NSD constant.
         */
        private const val DISCOVERY_TIMEOUT_MS =
            15_000L
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

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val lock =
        Any()

    private var activeSession:
        DiscoverySession? = null

    /**
     * Fully resolved ADB endpoint.
     *
     * Ports advertised by Wireless Debugging are temporary. CurBox therefore
     * rediscovers them when pairing or reconnecting instead of persisting the
     * advertised port as a permanent endpoint.
     */
    data class Endpoint(
        val serviceName: String,
        val serviceType: String,
        val host: String,
        val port: Int
    )

    interface Listener {

        fun onEndpointFound(
            endpoint: Endpoint
        )

        fun onError(
            error: Throwable
        )
    }

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
     * Only one CurBox discovery operation is active at a time.
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
            AtomicBoolean(
                false
            )

        /**
         * Legacy resolveService should be driven sequentially.
         *
         * Services discovered while another resolution is running are queued
         * and tried afterwards.
         */
        private val candidateLock =
            Any()

        private val pendingCandidates =
            ArrayDeque<NsdServiceInfo>()

        private val seenCandidates =
            mutableSetOf<String>()

        private var resolving =
            false

        @Volatile
        private var discoveryStarted =
            false

        private var multicastLock:
            WifiManager.MulticastLock? = null

        private var discoveryTimeout:
            Runnable? = null

        /**
         * Kept for diagnostics if every candidate seen during the bounded
         * discovery window fails resolution.
         */
        @Volatile
        private var lastResolutionError:
            Throwable? = null

        private val discoveryListener =
            object :
                NsdManager.DiscoveryListener {

                override fun onDiscoveryStarted(
                    regType: String
                ) {
                    if (finished.get()) {
                        return
                    }

                    discoveryStarted =
                        true
                }

                override fun onServiceFound(
                    serviceInfo: NsdServiceInfo
                ) {
                    if (finished.get()) {
                        return
                    }

                    enqueueCandidate(
                        serviceInfo
                    )
                }

                override fun onServiceLost(
                    serviceInfo: NsdServiceInfo
                ) {
                    /*
                     * Do not fail the entire operation because one advertised
                     * candidate disappeared. Other services may still provide
                     * a usable endpoint.
                     */
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

                scheduleDiscoveryTimeout()

                nsdManager.discoverServices(
                    serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            } catch (t: Throwable) {
                finishWithError(
                    t
                )
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

            cancelDiscoveryTimeout()

            synchronized(candidateLock) {
                pendingCandidates.clear()

                resolving =
                    false
            }

            stopDiscoveryOnly()
            releaseMulticastLock()
            detach()
        }

        /**
         * Adds a newly advertised service to the sequential resolution queue.
         *
         * Discovery deliberately remains active here.
         */
        private fun enqueueCandidate(
            serviceInfo: NsdServiceInfo
        ) {
            val shouldResolve =
                synchronized(candidateLock) {
                    if (finished.get()) {
                        return
                    }

                    val key =
                        candidateKey(
                            serviceInfo
                        )

                    if (
                        !seenCandidates.add(
                            key
                        )
                    ) {
                        return
                    }

                    pendingCandidates.addLast(
                        serviceInfo
                    )

                    !resolving
                }

            if (shouldResolve) {
                resolveNextCandidate()
            }
        }

        /**
         * Resolves candidates one at a time.
         *
         * A candidate failure releases the resolver slot and immediately tries
         * the next service already queued by ongoing discovery.
         */
        private fun resolveNextCandidate() {
            if (finished.get()) {
                return
            }

            val nextCandidate =
                synchronized(candidateLock) {
                    if (
                        finished.get() ||
                        resolving
                    ) {
                        return
                    }

                    val next =
                        if (
                            pendingCandidates.isEmpty()
                        ) {
                            null
                        } else {
                            pendingCandidates.removeFirst()
                        }

                    if (next != null) {
                        resolving =
                            true
                    }

                    next
                }
                    ?: return

            resolve(
                nextCandidate
            )
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
                            if (finished.get()) {
                                releaseResolverAndContinue()
                                return
                            }

                            val host =
                                resolvedInfo.host

                            val port =
                                resolvedInfo.port

                            if (
                                host == null ||
                                port !in 1..65535
                            ) {
                                lastResolutionError =
                                    IllegalStateException(
                                        "Resolved ADB mDNS service " +
                                            "does not contain a valid host " +
                                            "and port. serviceName=" +
                                            resolvedInfo.serviceName
                                    )

                                releaseResolverAndContinue()

                                return
                            }

                            val hostAddress =
                                host.hostAddress

                            if (
                                hostAddress.isNullOrBlank()
                            ) {
                                lastResolutionError =
                                    IllegalStateException(
                                        "Resolved ADB mDNS service has no " +
                                            "usable host address. " +
                                            "serviceName=" +
                                            resolvedInfo.serviceName
                                    )

                                releaseResolverAndContinue()

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
                            if (finished.get()) {
                                releaseResolverAndContinue()
                                return
                            }

                            lastResolutionError =
                                IllegalStateException(
                                    "ADB mDNS service resolution failed. " +
                                        "errorCode=$errorCode, " +
                                        "serviceName=" +
                                        serviceInfo.serviceName
                                )

                            releaseResolverAndContinue()
                        }
                    }
                )
            } catch (t: Throwable) {
                if (finished.get()) {
                    return
                }

                lastResolutionError =
                    t

                releaseResolverAndContinue()
            }
        }

        /**
         * Marks the current resolution complete and tries another service that
         * may have arrived while resolution was running.
         *
         * If the queue is empty, discovery simply remains active until Android
         * announces another candidate or CurBox's discovery timeout expires.
         */
        private fun releaseResolverAndContinue() {
            synchronized(candidateLock) {
                resolving =
                    false
            }

            if (!finished.get()) {
                resolveNextCandidate()
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

            cancelDiscoveryTimeout()

            synchronized(candidateLock) {
                pendingCandidates.clear()

                resolving =
                    false
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

        private fun scheduleDiscoveryTimeout() {
            cancelDiscoveryTimeout()

            val timeout =
                Runnable {
                    if (finished.get()) {
                        return@Runnable
                    }

                    val lastError =
                        lastResolutionError

                    finishWithError(
                        IllegalStateException(
                            buildString {
                                append(
                                    "ADB mDNS discovery timed out before a usable endpoint was resolved."
                                )

                                append(
                                    " serviceType="
                                )

                                append(
                                    serviceType
                                )

                                lastError
                                    ?.message
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                    ?.let {
                                        append(
                                            " Last resolution error: "
                                        )

                                        append(
                                            it
                                        )
                                    }
                            },
                            lastError
                        )
                    )
                }

            discoveryTimeout =
                timeout

            mainHandler.postDelayed(
                timeout,
                DISCOVERY_TIMEOUT_MS
            )
        }

        private fun cancelDiscoveryTimeout() {
            val timeout =
                discoveryTimeout

            discoveryTimeout =
                null

            if (timeout != null) {
                mainHandler.removeCallbacks(
                    timeout
                )
            }
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

            cancelDiscoveryTimeout()

            synchronized(candidateLock) {
                pendingCandidates.clear()

                resolving =
                    false
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
         * Unlike the old implementation, this is not called merely because a
         * service was found. CurBox keeps discovery alive while candidate
         * resolution is in progress.
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
                // Android may report that discovery already stopped.
            } catch (_: Exception) {
                // Cleanup must not replace the original result or error.
            } finally {
                discoveryStarted =
                    false
            }
        }

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
                    // Cleanup failure does not change the discovery result.
                }
            }
        }

        /**
         * Prevent the same service announcement from repeatedly filling the
         * queue during one bounded discovery attempt.
         */
        private fun candidateKey(
            serviceInfo: NsdServiceInfo
        ): String {
            return buildString {
                append(
                    serviceInfo.serviceName
                )

                append(
                    '|'
                )

                append(
                    serviceInfo.serviceType
                )
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
