package neth.iecal.curbox.ui.fragments.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.data.sync.SyncGateway
import neth.iecal.curbox.databinding.FragmentInfoBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.sync.SyncFragment
import neth.iecal.curbox.utils.CrashLogPreview
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.LanguageUtils
import neth.iecal.curbox.utils.privileged.adb.AdbChannelManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessLifecycleManager
import java.io.File

class InfoFragment : Fragment() {

    private var _binding:
        FragmentInfoBinding? = null

    private val binding
        get() = _binding!!

    private val dataStore by lazy {
        DataStoreManager(
            requireContext()
        )
    }

    private var renderingTrackingSettings =
        false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentInfoBinding.inflate(
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

        setupCreatorHeader()
        setupAccountSection()
        setupUsageTrackingSettings()
        setupClickListeners()
        updateDirectAccessStatus()

        LanguageUtils.bindLanguageSelector(
            binding.languageSelector,
            binding.textCurrentLanguage
        )
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            updateDirectAccessStatus()
        }
    }

    /**
     * Collapsing creator card. The card is pinned over the scroll view and its
     * size is a pure function of the scroll offset, so it moves exactly with
     * the content in both directions. The scroll content starts with a spacer
     * as tall as the expanded card, which keeps the card's bottom edge and the
     * list moving at the same speed.
     */
    private fun setupCreatorHeader() {
        val density = resources.displayMetrics.density
        val maxCard = 176f * density
        val minCard = 72f * density
        val maxPhoto = 120f * density
        val minPhoto = 48f * density
        val range = maxCard - minCard

        val card = binding.cardCredit
        val photo = binding.imageCreator

        var radius = maxPhoto * 0.2f
        photo.clipToOutline = true
        photo.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }

        var lastCard = -1
        var lastPhoto = -1

        fun apply(scrollY: Int) {
            val t = (scrollY / range).coerceIn(0f, 1f)
            val cardPx = (maxCard - range * t).toInt()
            val photoPx = (maxPhoto + (minPhoto - maxPhoto) * t).toInt()
            radius = (maxPhoto * 0.2f) + (photoPx / 2f - maxPhoto * 0.2f) * t

            if (cardPx != lastCard) {
                lastCard = cardPx
                val lp = card.layoutParams
                lp.height = cardPx
                card.layoutParams = lp
            }
            if (photoPx != lastPhoto) {
                lastPhoto = photoPx
                val lp = photo.layoutParams
                lp.width = photoPx
                lp.height = photoPx
                photo.layoutParams = lp
            }
            photo.invalidateOutline()

            // The email line fades out before the bar gets too short for it,
            // so the name line always fits whole and centred.
            val fade = (1f - t * 2.2f).coerceIn(0f, 1f)
            binding.textCreditEmail.alpha = fade
            binding.textCreditEmail.visibility = if (fade <= 0f) View.GONE else View.VISIBLE
        }

        binding.scrollInfo.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener {
                _, _, y, _, _ -> apply(y)
            }
        )
        apply(binding.scrollInfo.scrollY)
    }

    private fun setupUsageTrackingSettings() {
        binding
            .switchAppUsageTracking
            .setOnCheckedChangeListener {
                    _,
                    checked ->

                if (
                    !renderingTrackingSettings
                ) {
                    viewLifecycleOwner
                        .lifecycleScope
                        .launch {
                            dataStore
                                .updateAppUsageTrackingEnabled(
                                    checked
                                )
                        }
                }
            }

        binding
            .switchWebsiteUsageTracking
            .setOnCheckedChangeListener {
                    _,
                    checked ->

                if (
                    !renderingTrackingSettings
                ) {
                    viewLifecycleOwner
                        .lifecycleScope
                        .launch {
                            dataStore
                                .updateWebsiteUsageTrackingEnabled(
                                    checked
                                )
                        }
                }
            }

        viewLifecycleOwner
            .lifecycleScope
            .launch {
                viewLifecycleOwner
                    .repeatOnLifecycle(
                        Lifecycle.State.STARTED
                    ) {
                        dataStore
                            .settings
                            .collect {
                                    settings ->

                                renderingTrackingSettings =
                                    true

                                binding
                                    .switchAppUsageTracking
                                    .isChecked =
                                    settings
                                        .isAppUsageTrackingEnabled

                                binding
                                    .switchWebsiteUsageTracking
                                    .isChecked =
                                    settings
                                        .isWebsiteUsageTrackingEnabled

                                renderingTrackingSettings =
                                    false
                            }
                    }
            }
    }

    // Account and sync live here in the Play Store build only. F-Droid stays
    // offline, so the card never appears and there is no login to be seen.
    private fun setupAccountSection() {
        if (
            BuildConfig
                .FDROID_VARIANT
        ) {
            return
        }

        binding
            .cardAccount
            .visibility =
            View.VISIBLE

        binding
            .btnLogin
            .setOnClickListener {
                val intent =
                    Intent(
                        requireContext(),
                        FragmentActivity::class.java
                    ).apply {
                        putExtra(
                            "fragment",
                            SyncFragment.FRAGMENT_ID
                        )
                    }

                startActivity(
                    intent
                )
            }

        viewLifecycleOwner
            .lifecycleScope
            .launch {
                viewLifecycleOwner
                    .repeatOnLifecycle(
                        Lifecycle.State.STARTED
                    ) {
                        SyncGateway
                            .provider
                            .status
                            .collect {
                                    s ->

                                val titleRes:
                                    Int

                                val pitchRes:
                                    Int

                                val buttonRes:
                                    Int

                                when {
                                    s.unlocked -> {
                                        titleRes =
                                            R.string.sync_is_on

                                        pitchRes =
                                            R.string.sync_is_on_pitch

                                        buttonRes =
                                            R.string.manage_sync
                                    }

                                    s.signedIn -> {
                                        titleRes =
                                            R.string.sync_across_devices

                                        pitchRes =
                                            R.string.sync_finish_setup_pitch

                                        buttonRes =
                                            R.string.finish_sync_setup
                                    }

                                    else -> {
                                        titleRes =
                                            R.string.sync_across_devices

                                        pitchRes =
                                            R.string.sync_across_devices_pitch

                                        buttonRes =
                                            R.string.log_in
                                    }
                                }

                                binding
                                    .textAccountTitle
                                    .setText(
                                        titleRes
                                    )

                                binding
                                    .textAccountPitch
                                    .setText(
                                        pitchRes
                                    )

                                binding
                                    .btnLogin
                                    .setText(
                                        buttonRes
                                    )
                            }
                    }
            }
    }

    private fun setupClickListeners() {
        binding
            .cardDirectAccess
            .setOnClickListener {
                val intent =
                    Intent(
                        requireContext(),
                        FragmentActivity::class.java
                    ).apply {
                        putExtra(
                            "fragment",
                            DirectAccessFragment.FRAGMENT_ID
                        )
                    }

                startActivity(
                    intent
                )
            }

        binding
            .textCreditEmail
            .setOnClickListener {
                openUrl(
                    "mailto:mhmdahmdofficial@gmail.com"
                )
            }

        binding
            .btnDocs
            .setOnClickListener {
                openUrl(
                    "https://curbox.app/docs/"
                )
            }

        binding
            .cardBrowserExtension
            .setOnClickListener {
                openUrl(
                    "https://github.com/curbox-app/curbox-extension"
                )
            }

        binding
            .btnActionCrashLogs
            .setOnClickListener {
                showCrashLogs()
            }
    }

    /**
     * Shows a lightweight summary in Settings/Info without initializing the
     * ADB transport solely for the purpose of rendering this card.
     */
    private fun updateDirectAccessStatus() {
        val context =
            context
                ?.applicationContext
                ?: return

        val connected =
            AdbChannelManager
                .isConnected()

        val recoveryEnabled =
            DirectAccessLifecycleManager
                .isRecoveryEnabled(
                    context
                )

        binding
            .textDirectAccessStatus
            .text =
            when {
                connected ->
                    "Connected. Direct ADB is ready."

                recoveryEnabled ->
                    "Configured. Automatic recovery is enabled."

                else ->
                    "Not configured. Tap to set up Direct Access."
            }
    }

    private fun shareProject() {
        val intent =
            Intent(
                Intent.ACTION_SEND
            ).apply {
                type =
                    "text/plain"

                putExtra(
                    Intent.EXTRA_TEXT,
                    getString(
                        R.string.share_curbox_message
                    )
                )
            }

        startActivity(
            Intent.createChooser(
                intent,
                null
            )
        )
    }

    private fun openUrl(
        url: String
    ) {
        try {
            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        url
                    )
                )

            startActivity(
                intent
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCrashLogs() {
        val logFile =
            File(
                requireContext()
                    .filesDir,
                "crash_log.txt"
            )

        viewLifecycleOwner
            .lifecycleScope
            .launch {
                val preview =
                    withContext(
                        Dispatchers.IO
                    ) {
                        runCatching {
                            if (
                                logFile.exists()
                            ) {
                                CrashLogPreview.read(
                                    logFile
                                )
                            } else {
                                null
                            }
                        }
                    }

                val content =
                    preview.fold(
                        onSuccess = {
                            it
                                ?: getString(
                                    R.string.crash_logs_none_available
                                )
                        },
                        onFailure = {
                            getString(
                                R.string.crash_logs_read_error
                            )
                        }
                    )

                MaterialAlertDialogBuilder(
                    requireContext()
                )
                    .setTitle(
                        R.string.crash_logs_title
                    )
                    .setMessage(
                        content
                    )
                    .setPositiveButton(
                        R.string.share
                    ) { _, _ ->
                        shareCrashLogs(
                            logFile
                        )
                    }
                    .setNegativeButton(
                        R.string.close,
                        null
                    )
                    .setNeutralButton(
                        R.string.clear
                    ) { _, _ ->
                        if (
                            logFile.exists() &&
                            logFile.delete()
                        ) {
                            Toast.makeText(
                                requireContext(),
                                getString(
                                    R.string.crash_logs_cleared
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .show()
            }
    }

    private fun shareCrashLogs(
        logFile: File
    ) {
        if (
            !logFile.exists() ||
            logFile.length() ==
            0L
        ) {
            Toast.makeText(
                requireContext(),
                getString(
                    R.string.nothing_to_share
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        try {
            val context =
                requireContext()

            val logUri =
                FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.provider",
                    logFile
                )

            val intent =
                Intent(
                    Intent.ACTION_SEND
                ).apply {
                    type =
                        "text/plain"

                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "Horus Crash Logs"
                    )

                    putExtra(
                        Intent.EXTRA_STREAM,
                        logUri
                    )

                    clipData =
                        ClipData.newUri(
                            context.contentResolver,
                            "Horus Crash Logs",
                            logUri
                        )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            startActivity(
                Intent.createChooser(
                    intent,
                    "Share Crash Logs"
                )
            )
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.unable_to_share_crash_logs,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        _binding =
            null
    }
}
