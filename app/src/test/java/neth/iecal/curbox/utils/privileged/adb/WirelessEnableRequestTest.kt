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

class ArabicDigitsTest {
    @Test
    fun arabicIndicCodeIsNormalised() {
        org.junit.Assert.assertEquals(
            "123 456",
            WirelessDebuggingNodeMatcher.normalizeDigits("\u0661\u0662\u0663\u00A0\u0664\u0665\u0666")
        )
    }

    @Test
    fun directionMarksAreRemoved() {
        org.junit.Assert.assertEquals(
            "192.168.1.5:37000",
            WirelessDebuggingNodeMatcher.normalizeDigits("\u200E192.168.1.5:37000\u200F")
        )
    }

    @Test
    fun persianDigitsAreNormalised() {
        org.junit.Assert.assertEquals("0123456789", WirelessDebuggingNodeMatcher.normalizeDigits("\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9"))
    }
}
