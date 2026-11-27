package neth.iecal.curbox

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.screens.DirectAccessSetupFragment
import neth.iecal.curbox.ui.fragments.main.scrcpy.ScrcpyFragment
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shows the scrcpy screen and the Direct Access setup screen in the real
 * activity and checks their initial state. Nothing is started: no pairing, no
 * remote-control service, no network.
 */
@RunWith(AndroidJUnit4::class)
class ScrcpyScreensTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun skipOnboarding() {
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("isFirstLaunchComplete", true)
            .putBoolean("hasAcceptedTerms", true)
            .commit()
    }

    private fun show(fragment: Fragment, block: () -> Unit) {
        ActivityScenario.launch<FragmentActivity>(
            Intent(context, FragmentActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, fragment)
                    .commitNow()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            block()
        }
    }

    @Test
    fun scrcpyScreenShowsStoppedStateAndHidesLink() {
        show(ScrcpyFragment()) {
            onView(withId(R.id.text_status))
                .check(matches(withText(R.string.scrcpy_status_stopped)))
            onView(withId(R.id.button_start_stop))
                .check(matches(isDisplayed()))
                .check(matches(withText(R.string.scrcpy_button_start)))
                .check(matches(isEnabled()))
            onView(withId(R.id.button_refresh)).check(matches(isNotEnabled()))
            // The access link must never be shown in the app.
            onView(withId(R.id.text_link))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun directAccessSetupScreenRenders() {
        show(DirectAccessSetupFragment()) {
            onView(withId(R.id.statusText)).check(matches(isDisplayed()))
            onView(withId(R.id.pairButton)).check(matches(isDisplayed()))
            onView(withId(R.id.continueButton)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun scrcpyStringsExist() {
        for (id in listOf(
            R.string.scrcpy_status_stopped,
            R.string.scrcpy_button_start,
            R.string.scrcpy_button_stop
        )) {
            assertTrue(context.getString(id).isNotBlank())
        }
    }
}
