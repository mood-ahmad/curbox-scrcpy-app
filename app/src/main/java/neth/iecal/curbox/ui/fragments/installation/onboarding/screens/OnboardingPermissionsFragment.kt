package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.color.MaterialColors
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.databinding.FragmentOnboardingPermissionsBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingViewModel
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerSettingViewModel
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.PermissionUtils
import java.util.UUID

/**
 * Final permissions review for onboarding.
 *
 * Privileged setup no longer belongs to this screen.
 *
 * DirectAccessSetupFragment has already prepared either Direct ADB or Shizuku
 * and PersistentPrivilegesManager has already attempted to configure the
 * persistent privileges CurBox can grant through that backend.
 *
 * This screen therefore:
 *
 * - displays the current Overlay permission state;
 * - displays the current Accessibility state;
 * - displays the Notification permission state;
 * - requests POST_NOTIFICATIONS through Android's normal runtime-permission
 *   flow when required;
 * - completes onboarding after the required state is ready.
 *
 * It deliberately does not:
 *
 * - configure Shizuku;
 * - request Shizuku permission;
 * - open Accessibility setup again;
 * - open Overlay setup again;
 * - execute privileged shell commands.
 */
class OnboardingPermissionsFragment : Fragment() {

    private var _binding:
        FragmentOnboardingPermissionsBinding? = null

    private val binding
        get() = _binding!!

    private val onboardingViewModel:
        OnboardingViewModel by activityViewModels()

    private val appBlockerViewModel:
        AppBlockerSettingViewModel by activityViewModels()

    /**
     * True when the final Continue action had to pause while Android requested
     * POST_NOTIFICATIONS.
     *
     * If the permission is granted, onboarding can continue immediately.
     */
    private var completeAfterNotificationPermission =
        false

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            granted ->

            if (_binding == null) {
                completeAfterNotificationPermission =
                    false

                return@registerForActivityResult
            }

            updatePermissionsState()

            val shouldFinish =
                completeAfterNotificationPermission &&
                    granted &&
                    arePersistentRequirementsReady()

            completeAfterNotificationPermission =
                false

            if (shouldFinish) {
                finishOnboarding()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentOnboardingPermissionsBinding.inflate(
                inflater,
                container,
                false
            )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        /*
         * Shizuku has moved to DirectAccessSetupFragment.
         *
         * Keep the existing layout view hidden for now so this Kotlin change
         * does not depend on modifying the permissions XML in the same step.
         * The obsolete layout element can be removed cleanly afterwards.
         */
        binding
            .btnShizukuGrantAll
            .visibility =
            View.GONE

        /*
         * The permission rows are status/review rows now.
         *
         * Overlay and Accessibility have already been handled by the
         * privileged setup performed on the previous screen.
         */
        binding
            .overlayPermRoot
            .setOnClickListener(
                null
            )

        binding
            .blockerAccPermRoot
            .setOnClickListener(
                null
            )

        /*
         * Notification permission is intentionally not granted through a
         * privileged backend. Android's runtime-permission dialog remains the
         * authoritative flow.
         *
         * The row itself is informational. The final action performs the
         * runtime request when needed.
         */
        binding
            .notifPermRoot
            .setOnClickListener(
                null
            )

        /*
         * Restricted-setting setup belonged to the old manual Accessibility
         * setup path.
         *
         * Automatic Direct Access prepares Accessibility before reaching this
         * screen, so the old tutorial is no longer part of this flow.
         */
        binding
            .restrictedSettingsWarning
            .visibility =
            View.GONE

        binding
            .btnShowRestrictedTutorial
            .visibility =
            View.GONE

        binding
            .btnAction
            .setOnClickListener {
                handleContinue()
            }

        setupDescText()
        updatePermissionsState()
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            updatePermissionsState()
        }
    }

    override fun onDestroyView() {
        completeAfterNotificationPermission =
            false

        _binding =
            null

        super.onDestroyView()
    }

    /**
     * Final onboarding action.
     *
     * Overlay and Accessibility should already be ready from the privileged
     * setup screen.
     *
     * POST_NOTIFICATIONS remains a normal Android runtime permission, so ask
     * Android for it here if this OS version requires it.
     */
    private fun handleContinue() {
        if (
            !arePersistentRequirementsReady()
        ) {
            updatePermissionsState()
            return
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtils
                .isNotificationPermissionGiven(
                    requireContext()
                )
        ) {
            completeAfterNotificationPermission =
                true

            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )

            return
        }

        finishOnboarding()
    }

    /**
     * These permissions are expected to have been prepared before this screen
     * through DirectAccessSetupFragment and PersistentPrivilegesManager.
     */
    private fun arePersistentRequirementsReady(): Boolean {
        val currentContext =
            context
                ?: return false

        val hasOverlay =
            Settings.canDrawOverlays(
                currentContext
            )

        val hasAccessibility =
            PermissionUtils
                .isAccessibilityServiceEnabled(
                    currentContext,
                    AppBlockerService::class.java
                )

        return hasOverlay &&
            hasAccessibility
    }

    /**
     * Performs the existing final onboarding work.
     *
     * This preserves the target-app configuration and first-launch-complete
     * behaviour from the old permissions screen.
     */
    private fun finishOnboarding() {
        if (_binding == null) {
            return
        }

        val targetApp =
            onboardingViewModel
                .targetAppPackage
                .value

        val limit =
            onboardingViewModel
                .dailyLimitMinutes
                .value ?: 30L

        val packageMap =
            mapOf(
                "Instagram" to
                    "com.instagram.android",
                "TikTok" to
                    "com.zhiliaoapp.musically",
                "YouTube" to
                    "com.google.android.youtube",
                "Facebook" to
                    "com.facebook.katana",
                "Reddit" to
                    "com.reddit.frontpage"
            )

        val pkg =
            packageMap[
                targetApp
            ]

        if (pkg != null) {
            val usageConfig =
                AppUsageConfig(
                    isDailyUniform =
                        true,
                    uniformLimit =
                        limit,
                    dailyLimits =
                        LongArray(
                            7
                        ) {
                            limit
                        }
                )

            val newGroup =
                AppGroup(
                    id =
                        UUID.randomUUID()
                            .toString(),
                    name =
                        getString(
                            R.string.onboarding_app_limit_group_name,
                            targetApp
                        ),
                    selectedPackages =
                        listOf(
                            pkg
                        ),
                    config =
                        AppGroupConfig(
                            schedule =
                                AppTimeConfig.allDay(),
                            usage =
                                usageConfig
                        ),
                    isActive =
                        true,
                    warningScreenConfig =
                        AppBlockerWarningScreenConfig()
                )

            appBlockerViewModel.addGroup(
                newGroup
            )
        }

        val currentContext =
            requireContext()

        currentContext
            .getSharedPreferences(
                "AppPreferences",
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                "isFirstLaunchComplete",
                true
            )
            .apply()

        val intent =
            Intent(
                currentContext,
                FragmentActivity::class.java
            ).apply {
                putExtra(
                    "fragment",
                    AllAppsUsageFragment.FRAGMENT_ID
                )
            }

        startActivity(
            intent
        )
    }

    private fun setupDescText() {
        val baseText =
            getString(
                R.string.to_create_friction_and_give_you
            )

        val actionText =
            getString(
                R.string.onboarding_read_documentation
            )

        val fullText =
            "$baseText $actionText"

        val spannableString =
            SpannableString(
                fullText
            )

        val clickableSpan =
            object :
                ClickableSpan() {

                override fun onClick(
                    view: View
                ) {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://curbox.app/docs"
                            )
                        )

                    startActivity(
                        intent
                    )
                }
            }

        val start =
            fullText.indexOf(
                actionText
            )

        val end =
            start +
                actionText.length

        spannableString.setSpan(
            clickableSpan,
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannableString.setSpan(
            ForegroundColorSpan(
                MaterialColors.getColor(
                    binding.desc,
                    com.google.android.material.R.attr.colorPrimary
                )
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannableString.setSpan(
            StyleSpan(
                Typeface.BOLD
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding
            .desc
            .text =
            spannableString

        binding
            .desc
            .movementMethod =
            LinkMovementMethod.getInstance()
    }

    /**
     * Refreshes the review screen without initiating any permission/setup
     * operation.
     */
    private fun updatePermissionsState() {
        val currentContext =
            context
                ?: return

        val hasOverlay =
            Settings.canDrawOverlays(
                currentContext
            )

        val hasNotification =
            PermissionUtils
                .isNotificationPermissionGiven(
                    currentContext
                )

        val hasAccessibility =
            PermissionUtils
                .isAccessibilityServiceEnabled(
                    currentContext,
                    AppBlockerService::class.java
                )

        setPermissionIcon(
            hasOverlay,
            binding.overlayPermIcon
        )

        setPermissionIcon(
            hasNotification,
            binding.notifPermIcon
        )

        setPermissionIcon(
            hasAccessibility,
            binding.blockerAccPermIcon
        )

        /*
         * These rows are intentionally informational now.
         */
        binding
            .overlayPermRoot
            .isEnabled =
            false

        binding
            .overlayPermRoot
            .alpha =
            if (hasOverlay) {
                0.5f
            } else {
                1.0f
            }

        binding
            .notifPermRoot
            .isEnabled =
            false

        binding
            .notifPermRoot
            .alpha =
            if (hasNotification) {
                0.5f
            } else {
                1.0f
            }

        binding
            .blockerAccPermRoot
            .isEnabled =
            false

        binding
            .blockerAccPermRoot
            .alpha =
            if (hasAccessibility) {
                0.5f
            } else {
                1.0f
            }

        /*
         * The user may continue when the privileges that should have been
         * prepared by the previous Direct Access screen are actually present.
         *
         * Notification does not disable the button because pressing Continue
         * is what triggers Android's runtime permission flow when needed.
         */
        val persistentRequirementsReady =
            hasOverlay &&
                hasAccessibility

        binding
            .btnAction
            .isEnabled =
            persistentRequirementsReady

        binding
            .btnAction
            .text =
            if (!persistentRequirementsReady) {
                getString(
                    R.string.onboarding_need_more_permissions
                )
            } else if (
                !hasNotification &&
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                getString(
                    R.string.proceed
                )
            } else {
                getString(
                    R.string.onboarding_curb_me
                )
            }
    }

    private fun setPermissionIcon(
        isEnabled: Boolean,
        icon: ImageView
    ) {
        if (isEnabled) {
            icon.setImageResource(
                R.drawable.baseline_done_24
            )

            icon.setColorFilter(
                resources.getColor(
                    R.color.md_theme_onSurface,
                    requireContext().theme
                )
            )
        } else {
            icon.setImageResource(
                R.drawable.baseline_close_24
            )

            icon.setColorFilter(
                resources.getColor(
                    R.color.error_color,
                    requireContext().theme
                )
            )
        }
    }
}
