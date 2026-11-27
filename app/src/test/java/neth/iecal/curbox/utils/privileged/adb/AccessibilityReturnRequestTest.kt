package neth.iecal.curbox.utils.privileged.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityReturnRequestTest {
    @Test
    fun consumeWithoutRequestIsFalse() {
        AccessibilityReturnRequest.consume(1_000L)
        assertFalse(AccessibilityReturnRequest.consume(2_000L))
    }

    @Test
    fun recentRequestIsConsumedOnce() {
        AccessibilityReturnRequest.request(10_000L)
        assertTrue(AccessibilityReturnRequest.consume(12_000L))
        assertFalse(AccessibilityReturnRequest.consume(12_500L))
    }

    @Test
    fun staleRequestIsIgnored() {
        AccessibilityReturnRequest.request(10_000L)
        assertFalse(AccessibilityReturnRequest.consume(10_000L + 11 * 60 * 1000L))
    }
}
