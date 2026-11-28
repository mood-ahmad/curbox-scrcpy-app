package neth.iecal.curbox.utils.privileged.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessEnableRequestTest {
    @Test
    fun freshRequestIsAccepted() {
        assertTrue(WirelessEnableRequest.isFresh(1_000L, 1_000L + 10_000L))
    }

    @Test
    fun staleRequestIsRejected() {
        assertFalse(
            WirelessEnableRequest.isFresh(
                1_000L,
                1_000L + WirelessEnableRequest.MAX_AGE_MS + 1
            )
        )
    }

    @Test
    fun futureTimestampIsRejected() {
        assertFalse(WirelessEnableRequest.isFresh(5_000L, 1_000L))
    }
}
