package neth.iecal.curbox.ui.fragments.main.reducers

import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R

import android.content.Intent
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.ViewUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment

class ReducersFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reducers, container, false)
        val appBlockerCard = view.findViewById<MaterialCardView>(R.id.card_app_blocker)
        val reelBlockerCard = view.findViewById<MaterialCardView>(R.id.card_reels_blocker)
        val keywordBlockerCard = view.findViewById<MaterialCardView>(R.id.card_keyword_blocker)
        val autoDndCard = view.findViewById<MaterialCardView>(R.id.card_autodnd)
        
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(it, "Blocker tools and stimulation reducers help you regain focus.", "https://github.com/mood-ahmad/curbox-scrcpy-app#readme")
        }

        appBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AppBlockerGroupsFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        reelBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        keywordBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        autoDndCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.AutoDndFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val reelCounterCard = view.findViewById<MaterialCardView>(R.id.card_reel_counter)
        reelCounterCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        val grayscaleCard = view.findViewById<MaterialCardView>(R.id.card_grayscale)
        grayscaleCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", GrayscaleFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        val intentsCard = view.findViewById<MaterialCardView>(R.id.card_intents)
        intentsCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val uiHiderCard = view.findViewById<MaterialCardView>(R.id.card_ui_hider)
        uiHiderCard.isVisible = BuildConfig.SUPPORTS_UI_HIDER
        uiHiderCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val intentsLogCard = view.findViewById<MaterialCardView>(R.id.card_logged_intents)
        intentsLogCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val apiCard = view.findViewById<MaterialCardView>(R.id.card_api)
        apiCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.api.ApiFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val antiUninstallCard = view.findViewById<MaterialCardView>(R.id.card_anti_uninstall)
        antiUninstallCard.isVisible = BuildConfig.SUPPORTS_ANTI_UNINSTALL
        antiUninstallCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.AntiUninstallFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val settingsChangeDelayCard = view.findViewById<MaterialCardView>(R.id.card_settings_change_delay)
        settingsChangeDelayCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.SettingsChangeDelayFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val removalRequestCard = view.findViewById<MaterialCardView>(R.id.card_removal_request)
        removalRequestCard.isVisible = neth.iecal.curbox.BuildConfig.SUPPORTS_ANTI_UNINSTALL
        removalRequestCard.setOnClickListener {
            val appContext = requireContext().applicationContext
            val deviceId = neth.iecal.curbox.remotecontrol.ProtectionRemoval.deviceId(appContext)
            val requestId = neth.iecal.curbox.remotecontrol.ProtectionRemoval.newRequest(appContext)
            viewLifecycleOwner.lifecycleScope.launch {
                val sent = try {
                    neth.iecal.curbox.remotecontrol.EmailSender
                        .sendProtectionRemovalRequest(deviceId, requestId)
                } catch (_: Throwable) {
                    false
                }
                android.widget.Toast.makeText(
                    appContext,
                    if (sent) R.string.removal_request_sent else R.string.removal_request_failed,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        val advancedHeader = view.findViewById<View>(R.id.advanced_header)
        val advancedContent = view.findViewById<View>(R.id.advanced_content)
        val advancedArrow = view.findViewById<ImageView>(R.id.advanced_arrow)
        advancedHeader.setOnClickListener {
            val isVisible = advancedContent.isVisible
            TransitionManager.beginDelayedTransition(view.findViewById(R.id.reducers_root), AutoTransition())
            advancedContent.isVisible = !isVisible
            advancedArrow.animate().rotation(if (isVisible) 0f else 90f).start()
        }

        return view
    }
}
