package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R

/**
 * Periodic donation prompt.
 *
 * The timing and copy lean on a few well known, non manipulative persuasion ideas:
 *  - Peak end / reciprocity: we only ask after the user has opened the app several
 *    times, so the request lands after they have already gotten value, not before.
 *  - Reduced reactance: every dialog offers an easy, honest way out ("Maybe later")
 *    and a free alternative ("share instead"), so saying no never feels trapped.
 *  - Decreasing frequency: the cooldown between asks doubles each time it is shown,
 *    so a user who keeps declining is bothered less and less instead of more.
 *
 * State lives in the existing "AppPreferences" SharedPreferences, matching the rest
 * of the app's lightweight flags.
 */
object DonationPrompt {

    private const val PREFS = "AppPreferences"
    private const val KEY_LAUNCH_COUNT = "donate_launch_count"
    private const val KEY_LAST_SHOWN = "donate_last_shown"
    private const val KEY_TIMES_SHOWN = "donate_times_shown"
    private const val KEY_OPTED_OUT = "donate_opted_out"

    private const val DONATE_URL = "https://github.com/mood-ahmad/curbox-scrcpy-app#readme"

    // CHANGED: the prompt is switched off in this build.
    private val promptEnabled = false

    // Only start asking once the app has clearly become part of the user's routine.
    private const val MIN_LAUNCHES = 4

    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    private const val BASE_COOLDOWN_DAYS = 4L
    private const val MAX_COOLDOWN_DAYS = 60L

    /**
     * Records this app open and, when the timing is right, shows the prompt.
     * Safe to call on every main screen launch.
     */
    fun maybeShow(activity: AppCompatActivity) {
        if (!promptEnabled) return // CHANGED

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_OPTED_OUT, false)) return

        val launchCount = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, launchCount).apply()

        if (launchCount < MIN_LAUNCHES) return

        val timesShown = prefs.getInt(KEY_TIMES_SHOWN, 0)
        val lastShown = prefs.getLong(KEY_LAST_SHOWN, 0L)
        val now = System.currentTimeMillis()

        if (now - lastShown < cooldownMs(timesShown)) return

        show(activity, prefs, timesShown, now)
    }

    private fun cooldownMs(timesShown: Int): Long {
        val days = (BASE_COOLDOWN_DAYS shl timesShown).coerceAtMost(MAX_COOLDOWN_DAYS)
        return days * ONE_DAY_MS
    }

    private fun show(activity: AppCompatActivity, prefs: android.content.SharedPreferences, timesShown: Int, now: Long) {
        prefs.edit()
            .putLong(KEY_LAST_SHOWN, now)
            .putInt(KEY_TIMES_SHOWN, timesShown + 1)
            .apply()

        val view = activity.layoutInflater.inflate(R.layout.dialog_donation, null)

        MaterialAlertDialogBuilder(activity)
            .setView(view)
            // Acting once is enough. Once the user has helped, we stop asking and they
            // can still donate any time from the Info screen.
            .setPositiveButton(R.string.donate_prompt_give) { _, _ ->
                prefs.edit().putBoolean(KEY_OPTED_OUT, true).apply()
                openUrl(activity, DONATE_URL)
            }
            .setNeutralButton(R.string.donate_prompt_share) { _, _ ->
                prefs.edit().putBoolean(KEY_OPTED_OUT, true).apply()
                shareProject(activity)
            }
            .setNegativeButton(R.string.donate_prompt_later, null)
            .show()
    }

    private fun openUrl(activity: AppCompatActivity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareProject(activity: AppCompatActivity) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_curbox_message))
            }
            activity.startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
