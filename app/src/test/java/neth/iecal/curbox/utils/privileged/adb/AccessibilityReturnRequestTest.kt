package neth.iecal.curbox.utils.privileged.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityReturnRequestTest {
    @Test
    fun recentRequestIsFresh() {
        assertTrue(AccessibilityReturnRequest.isFresh(10_000L, 12_000L))
    }

    @Test
    fun missingRequestIsNotFresh() {
        assertFalse(AccessibilityReturnRequest.isFresh(0L, 12_000L))
    }

    @Test
    fun staleRequestIsNotFresh() {
        assertFalse(
            AccessibilityReturnRequest.isFresh(
                10_000L,
                10_000L + AccessibilityReturnRequest.MAX_AGE_MS + 1
            )
        )
    }

    @Test
    fun futureRequestIsNotFresh() {
        assertFalse(AccessibilityReturnRequest.isFresh(20_000L, 10_000L))
    }
}
