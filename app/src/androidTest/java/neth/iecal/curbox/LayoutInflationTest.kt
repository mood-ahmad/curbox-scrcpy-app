package neth.iecal.curbox

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inflates every layout in res/layout with the app theme. This catches broken
 * XML, missing ids/attributes and bad custom views before they crash on a phone
 * (for example a layout file that does not contain valid XML).
 */
@RunWith(AndroidJUnit4::class)
class LayoutInflationTest {

    // Layouts that cannot be inflated standalone (add the name here with a reason).
    private val skip = emptySet<String>()

    @Test
    fun everyLayoutInflates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext: Context = instrumentation.targetContext
        val themed = ContextThemeWrapper(appContext, R.style.Theme_Curbox)

        val failures = mutableListOf<String>()
        var count = 0

        instrumentation.runOnMainSync {
            val inflater = LayoutInflater.from(themed)
            for (field in R.layout::class.java.fields) {
                if (field.name in skip) continue
                count++
                try {
                    inflater.inflate(field.getInt(null), FrameLayout(themed), false)
                } catch (t: Throwable) {
                    failures += "${field.name}: ${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}"
                }
            }
        }

        assertTrue("Inflated $count layouts, ${failures.size} failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
