package neth.iecal.curbox

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import neth.iecal.curbox.ui.activity.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity and walks the app's main screens: no crash, screens show. */
@RunWith(AndroidJUnit4::class)
class MainScreensSmokeTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun setPrefs(firstLaunchComplete: Boolean) {
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("isFirstLaunchComplete", firstLaunchComplete)
            .putBoolean("hasAcceptedTerms", true)
            .commit()
    }

    private fun launch(): ActivityScenario<FragmentActivity> =
        ActivityScenario.launch(Intent(context, FragmentActivity::class.java))

    @Test
    fun firstLaunchShowsOnboardingWithoutCrashing() {
        setPrefs(firstLaunchComplete = false)
        launch().use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            onView(withId(R.id.main)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun mainTabsOpenWithoutCrashing() {
        setPrefs(firstLaunchComplete = true)
        launch().use { scenario ->
            onView(withId(R.id.bottom_nav)).check(matches(isDisplayed()))
            for (tab in listOf(R.id.nav_focus, R.id.nav_direct_access, R.id.nav_reducers, R.id.nav_info, R.id.nav_usage)) {
                onView(withId(tab)).perform(click())
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                onView(withId(R.id.bottom_nav)).check(matches(isDisplayed()))
            }
        }
    }
}
