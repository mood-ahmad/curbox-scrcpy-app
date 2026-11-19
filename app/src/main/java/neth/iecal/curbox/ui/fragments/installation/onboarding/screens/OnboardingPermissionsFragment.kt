package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.BuildConfig
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
import neth.iecal.curbox.utils.ZipUtils.unzipSharedPreferencesFromUri
import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import neth.iecal.curbox.utils.privileged.PrivilegedRunner
import java.util.UUID

class OnboardingPermissionsFragment : Fragment() {

    private var _binding: FragmentOnboardingPermissionsBinding? = null
    private val binding get() = _binding!!

    private val onboardingViewModel:
        OnboardingViewModel by activityViewModels()

    private val appBlockerViewModel:
        AppBlockerSettingViewModel by activityViewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            updatePermissionsState()
        }

    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                requestCode,
                grantResult ->

            if (
                requestCode == 1001 &&
                grantResult ==
                PackageManager.PERMISSION_GRANTED
            ) {
                activity?.runOnUiThread {
                    runShizukuGrantAllCommand()
                }
            }
        }

    private val restorePicker:
        ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            result.data?.data?.let { uri ->
                val takeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION

                activity
                    ?.contentResolver
                    ?.takePersistableUriPermission(
                        uri,
                        takeFlags
                    )

                unzipSharedPreferencesFromUri(
                    requireContext(),
                    uri
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        if (
            PermissionUtils.isShizukuAvailable()
        ) {
            try {
                rikka.shizuku.Shizuku
                    .addRequestPermissionResultListener(
                        shizukuPermissionListener
                    )
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

        binding.btnAction.setOnClickListener {

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
                packageMap[targetApp]

            if (pkg != null) {
                val usageConfig =
                    AppUsageConfig(
                        isDailyUniform = true,
                        uniformLimit = limit,
                        dailyLimits =
                            LongArray(7) {
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
                            listOf(pkg),
                        config =
                            AppGroupConfig(
                                schedule =
                                    AppTimeConfig.allDay(),
                                usage =
                                    usageConfig
                            ),
                        isActive = true,
                        warningScreenConfig =
                            AppBlockerWarningScreenConfig()
                    )

                appBlockerViewModel.addGroup(
                    newGroup
                )
            }

            val sharedPreferences =
                requireContext()
                    .getSharedPreferences(
                        "AppPreferences",
                        Context.MODE_PRIVATE
                    )

            sharedPreferences
                .edit()
                .putBoolean(
                    "isFirstLaunchComplete",
                    true
                )
                .apply()

            val intent =
                Intent(
                    requireContext(),
                    FragmentActivity::class.java
                ).apply {
                    putExtra(
                        "fragment",
                        AllAppsUsageFragment.FRAGMENT_ID
                    )
                }

            startActivity(intent)
        }

        binding.overlayPermRoot
            .setOnClickListener {

                if (
                    Settings.canDrawOverlays(
                        requireContext()
                    )
                ) {
                    return@setOnClickListener
                }

                showExplanationDialog(
                    title =
                        getString(
                            R.string.onboarding_perm_overlay_title
                        ),
                    rationale =
                        getString(
                            R.string.onboarding_perm_overlay_rationale
                        ),
                    openSourceExplanation =
                        getString(
                            R.string.onboarding_perm_overlay_opensource
                        )
                ) {
                    val intent =
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                        ).apply {
                            data =
                                Uri.parse(
                                    "package:${requireContext().packageName}"
                                )
                        }

                    startActivity(intent)
                }
            }

        binding.notifPermRoot
            .setOnClickListener {

                if (
                    PermissionUtils
                        .isNotificationPermissionGiven(
                            requireContext()
                        )
                ) {
                    return@setOnClickListener
                }

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    showExplanationDialog(
                        title =
                            getString(
                                R.string.onboarding_perm_notif_title
                            ),
                        rationale =
                            getString(
                                R.string.onboarding_perm_notif_rationale
                            ),
                        openSourceExplanation =
                            getString(
                                R.string.onboarding_perm_notif_opensource
                            )
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                }
            }

        binding.blockerAccPermRoot
            .setOnClickListener {

                if (
                    PermissionUtils
                        .isAccessibilityServiceEnabled(
                            requireContext(),
                            AppBlockerService::class.java
                        )
                ) {
                    return@setOnClickListener
                }

                showExplanationDialog(
                    title =
                        getString(
                            R.string.onboarding_perm_accessibility_title
                        ),
                    rationale =
                        getString(
                            R.string.onboarding_perm_accessibility_rationale
                        ),
                    openSourceExplanation =
                        getString(
                            R.string.onboarding_perm_accessibility_opensource
                        )
                ) {
                    PermissionUtils
                        .openAccessibilityServiceScreen(
                            requireContext(),
                            AppBlockerService::class.java
                        )
                }
            }

        binding.btnShowRestrictedTutorial
            .setOnClickListener {

                val manufacturer =
                    Build.MANUFACTURER

                val query =
                    Uri.encode(
                        "How to enable restricted setting on " +
                            "$manufacturer android 13"
                    )

                val intent =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://www.youtube.com/results" +
                                "?search_query=$query"
                        )
                    )

                startActivity(intent)
            }

        binding.btnShizukuGrantAll
            .setOnClickListener {

                if (
                    !PermissionUtils
                        .hasShizukuPermission()
                ) {
                    try {
                        rikka.shizuku.Shizuku
                            .requestPermission(
                                1001
                            )
                    } catch (
                        e: Exception
                    ) {
                        e.printStackTrace()
                    }
                } else {
                    runShizukuGrantAllCommand()
                }
            }

        setupDescText()
        updatePermissionsState()
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
            object : ClickableSpan() {
                override fun onClick(
                    view: View
                ) {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://curbox.app/docs"
                            )
                        )

                    startActivity(intent)
                }
            }

        val start =
            fullText.indexOf(
                actionText
            )

        val end =
            start + actionText.length

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

        binding.desc.text =
            spannableString

        binding.desc.movementMethod =
            LinkMovementMethod.getInstance()
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            updatePermissionsState()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()

        if (
            PermissionUtils.isShizukuAvailable()
        ) {
            try {
                rikka.shizuku.Shizuku
                    .removeRequestPermissionResultListener(
                        shizukuPermissionListener
                    )
            } catch (
                e: Exception
            ) {
                e.printStackTrace()
            }
        }
    }

    private fun showExplanationDialog(
        title: String,
        rationale: String,
        openSourceExplanation: String,
        onProceed: () -> Unit
    ) {
        val privacy =
            getString(
                R.string.onboarding_privacy_note
            )

        MaterialAlertDialogBuilder(
            requireContext()
        )
            .setTitle(title)
            .setMessage(
                rationale +
                    privacy +
                    openSourceExplanation
            )
            .setPositiveButton(
                R.string.proceed
            ) { _, _ ->
                onProceed()
            }
            .setNegativeButton(
                R.string.cancel,
                null
            )
            .show()
    }

    /**
     * This remains the Shizuku setup button and permission flow.
     *
     * The command execution itself goes through PrivilegedRunner so the
     * actual privileged operation is no longer coupled directly to
     * ShizukuRunner.
     */
    private fun runShizukuGrantAllCommand() {
        val currentBinding =
            _binding ?: return

        currentBinding
            .btnShizukuGrantAll
            .isEnabled = false

        currentBinding
            .btnShizukuGrantAll
            .text =
            getString(
                R.string.onboarding_granting_permissions
            )

        val pkg =
            requireContext()
                .packageName

        val svc1 =
            "$pkg/${AppBlockerService::class.java.name}"

        // Full and fdroid also take WRITE_SECURE_SETTINGS here so grayscale
        // keeps working without a privileged command backend later on.
        val secureSettingsGrant =
            if (
                BuildConfig
                    .SUPPORTS_WRITE_SECURE_SETTINGS
            ) {
                "pm grant $pkg " +
                    "android.permission.WRITE_SECURE_SETTINGS"
            } else {
                ""
            }

        val command =
            """
            appops set $pkg SYSTEM_ALERT_WINDOW allow
            pm grant $pkg android.permission.POST_NOTIFICATIONS
            $secureSettingsGrant
            cmd notification allow_dnd $pkg

            CURRENT_ACC_SVCS=${'$'}(settings get secure enabled_accessibility_services)
            if [ "${'$'}CURRENT_ACC_SVCS" = "null" ] || [ -z "${'$'}CURRENT_ACC_SVCS" ]; then
                settings put secure enabled_accessibility_services "$svc1"
            else
                NEW_SVCS="${'$'}CURRENT_ACC_SVCS"
                case "${'$'}CURRENT_ACC_SVCS" in
                    *"$svc1"*) ;;
                    *) NEW_SVCS="${'$'}NEW_SVCS:$svc1" ;;
                esac
                settings put secure enabled_accessibility_services "${'$'}NEW_SVCS"
            fi
            settings put secure accessibility_enabled 1
            """.trimIndent()

        PrivilegedRunner.executeCommand(
            command = command,
            listener =
                object :
                    PrivilegedBackend.CommandResultListener {

                    override fun onCommandResult(
                        output: String,
                        done: Boolean
                    ) {
                        if (!done) {
                            return
                        }

                        activity?.runOnUiThread {
                            val binding =
                                _binding
                                    ?: return@runOnUiThread

                            binding
                                .btnShizukuGrantAll
                                .text =
                                getString(
                                    R.string.onboarding_permissions_granted
                                )

                            binding
                                .btnShizukuGrantAll
                                .isEnabled =
                                true

                            updatePermissionsState()
                        }
                    }

                    override fun onCommandError(
                        error: String
                    ) {
                        activity?.runOnUiThread {
                            val binding =
                                _binding
                                    ?: return@runOnUiThread

                            binding
                                .btnShizukuGrantAll
                                .isEnabled =
                                true

                            binding
                                .btnShizukuGrantAll
                                .text =
                                getString(
                                    R.string.onboarding_error_tap_retry
                                )

                            updatePermissionsState()
                        }
                    }
                }
        )
    }

    private fun updatePermissionsState() {
        val hasOverlay =
            Settings.canDrawOverlays(
                requireContext()
            )

        val hasNotif =
            PermissionUtils
                .isNotificationPermissionGiven(
                    requireContext()
                )

        val hasBlocker =
            PermissionUtils
                .isAccessibilityServiceEnabled(
                    requireContext(),
                    AppBlockerService::class.java
                )

        val isNonSession =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.R
            ) {
                try {
                    val info =
                        requireContext()
                            .packageManager
                            .getInstallSourceInfo(
                                requireContext()
                                    .packageName
                            )

                    val initiatingPackage =
                        info.initiatingPackageName

                    initiatingPackage !=
                        "com.android.vending" &&
                        initiatingPackage !=
                        "org.fdroid.fdroid"
                } catch (
                    e: Exception
                ) {
                    false
                }
            } else {
                false
            }

        val showRestrictedWarning =
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU &&
                !hasBlocker &&
                isNonSession

        binding
            .restrictedSettingsWarning
            .visibility =
            if (
                showRestrictedWarning
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        binding
            .btnShizukuGrantAll
            .visibility =
            if (
                PermissionUtils
                    .isShizukuAvailable()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        setPermissionIcon(
            hasOverlay,
            binding.overlayPermIcon
        )

        setPermissionIcon(
            hasNotif,
            binding.notifPermIcon
        )

        setPermissionIcon(
            hasBlocker,
            binding.blockerAccPermIcon
        )

        // Enforce sequence.
        binding.overlayPermRoot.isEnabled =
            !hasOverlay

        binding.overlayPermRoot.alpha =
            if (hasOverlay) {
                0.5f
            } else {
                1.0f
            }

        val canDoNotif =
            hasOverlay

        binding.notifPermRoot.isEnabled =
            canDoNotif &&
                !hasNotif

        binding.notifPermRoot.alpha =
            if (canDoNotif) {
                if (hasNotif) {
                    0.5f
                } else {
                    1.0f
                }
            } else {
                0.3f
            }

        val canDoBlocker =
            canDoNotif &&
                hasNotif

        binding.blockerAccPermRoot.isEnabled =
            canDoBlocker &&
                !hasBlocker

        binding.blockerAccPermRoot.alpha =
            if (canDoBlocker) {
                if (hasBlocker) {
                    0.5f
                } else {
                    1.0f
                }
            } else {
                0.3f
            }

        val allGranted =
            hasOverlay &&
                hasNotif &&
                hasBlocker

        binding.btnAction.isEnabled =
            allGranted

        binding.btnAction.text =
            if (allGranted) {
                getString(
                    R.string.onboarding_curb_me
                )
            } else {
                getString(
                    R.string.onboarding_need_more_permissions
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
