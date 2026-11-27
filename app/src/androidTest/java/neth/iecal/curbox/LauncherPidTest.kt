package neth.iecal.curbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The remote-control launcher records the agent pid from inside a wrapper that
 * execs the agent, so the pid is right whether or not setsid forks. This runs
 * the same pattern with `sleep` standing in for the agent, with job control off
 * and on, and checks the recorded pid is the process running the target.
 */
@RunWith(AndroidJUnit4::class)
class LauncherPidTest {

    private fun runScenario(jobControl: Boolean) {
        val dir = File(
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext.cacheDir,
            "pidtest-${System.nanoTime()}"
        ).apply { mkdirs() }

        val script = """
            cd '${dir.absolutePath}'
            ${if (jobControl) "set -m" else "set +m"} 2>/dev/null
            setsid nohup sh -c 'echo $$ > agent.pid.self; exec sleep 30' >> out.log 2>&1 &
            for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
                [ -s agent.pid.self ] && break
                sleep 0.2
            done
            PID=${'$'}(cat agent.pid.self)
            for i in 1 2 3 4 5 6 7 8 9 10; do
                case "${'$'}(readlink /proc/${'$'}PID/exe 2>/dev/null)" in
                    *sleep*|*toybox*|*busybox*) break ;;
                esac
                sleep 0.2
            done
            kill -0 "${'$'}PID" 2>/dev/null && echo ALIVE || echo DEAD
            echo "PID=${'$'}PID"
            kill -9 "${'$'}PID" 2>/dev/null
        """.trimIndent()

        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertTrue("output: $output", output.contains("ALIVE"))
        val pid = Regex("PID=(\\d+)").find(output)?.groupValues?.get(1)
        assertEquals(
            "recorded pid must match the wrapper pid file",
            File(dir, "agent.pid.self").readText().trim(),
            pid
        )
    }

    @Test
    fun recordedPidIsRealWithoutJobControl() = runScenario(false)

    @Test
    fun recordedPidIsRealWithJobControl() = runScenario(true)
}
