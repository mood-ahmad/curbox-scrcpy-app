package neth.iecal.curbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import neth.iecal.curbox.utils.privileged.PrivilegedRunner
import neth.iecal.curbox.utils.privileged.adb.AdbWirelessDebuggingAccessibilityNavigator
import neth.iecal.curbox.utils.privileged.adb.DirectAdbBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks the privileged-access layer with no Shizuku and no ADB pairing, which
 * is the state on a fresh emulator. It cannot test real pairing.
 */
@RunWith(AndroidJUnit4::class)
class PrivilegedFlowTest {

    @Test
    fun noBackendIsAvailableWithoutPairing() {
        assertFalse(DirectAdbBackend.isAvailable())
        assertFalse(PrivilegedRunner.isAvailable())
        assertNull(PrivilegedRunner.getAvailableBackend())
    }

    @Test
    fun commandsFailCleanlyWithoutBackend() {
        val latch = CountDownLatch(1)
        var error: String? = null
        PrivilegedRunner.executeCommand(
            "echo hi",
            object : PrivilegedBackend.CommandResultListener {
                override fun onCommandError(error0: String) {
                    error = error0
                    latch.countDown()
                }
            }
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(error!!.isNotBlank())
    }

    @Test
    fun directAdbBackendRejectsEmptyAndDisconnectedCommands() {
        val latch = CountDownLatch(2)
        val errors = mutableListOf<String>()
        val listener = object : PrivilegedBackend.CommandResultListener {
            override fun onCommandError(error: String) {
                synchronized(errors) { errors.add(error) }
                latch.countDown()
            }
        }
        DirectAdbBackend.executeCommand("   ", listener)
        DirectAdbBackend.executeCommand("id", listener)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, errors.size)
    }

    @Test
    fun wirelessDebuggingNavigatorIsDisarmedByDefaultAndIgnoresNullRoot() {
        AdbWirelessDebuggingAccessibilityNavigator.disarm()
        assertFalse(AdbWirelessDebuggingAccessibilityNavigator.isArmed())
        assertFalse(
            AdbWirelessDebuggingAccessibilityNavigator.navigate("com.android.settings", null)
        )
        AdbWirelessDebuggingAccessibilityNavigator.arm()
        assertTrue(AdbWirelessDebuggingAccessibilityNavigator.isArmed())
        assertFalse(
            AdbWirelessDebuggingAccessibilityNavigator.navigate("com.example.other", null)
        )
        AdbWirelessDebuggingAccessibilityNavigator.disarm()
    }
}
