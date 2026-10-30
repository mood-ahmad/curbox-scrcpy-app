package neth.iecal.curbox.ui.fragments.main.scrcpy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentEngineDiagnosticsBinding
import neth.iecal.curbox.utils.ShizukuRunner

/**
 * Read-only diagnostics for the Scrcpy engine.
 *
 * Runs safe shell commands through Shizuku and shows the output.
 */
class EngineDiagnosticsFragment : Fragment() {

    private var _binding: FragmentEngineDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private var isRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEngineDiagnosticsBinding.inflate(
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
        super.onViewCreated(view, savedInstanceState)

        binding.btnCheckShizuku.setOnClickListener {
            runDiagnostic(
                "Check Shizuku & Files",
                "id 2>&1 ; echo '---' ; ls -la /data/local/tmp/android/ 2>&1"
            )
        }

        binding.btnListFiles.setOnClickListener {
            runDiagnostic(
                "List Files",
                "ls -la /data/local/tmp/android/ 2>&1"
            )
        }

        binding.btnSignalingHelp.setOnClickListener {
            runDiagnostic(
                "Signaling --help",
                "cd /data/local/tmp/android && timeout 5 ./webrtc-signaling --help 2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnAgentHelp.setOnClickListener {
            runDiagnostic(
                "Agent Log (last 100 lines)",
                "tail -n 100 /data/local/tmp/android/agent.log 2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnCloudflaredLog.setOnClickListener {
            runDiagnostic(
                "Cloudflared Log",
                "cat /data/local/tmp/android/cloudflared.log 2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnSignalingLog.setOnClickListener {
            runDiagnostic(
                "Signaling Log (last 150 lines)",
                "tail -n 150 /data/local/tmp/android/signaling.log 2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnCopy.setOnClickListener {
            copyOutput()
        }

        binding.btnClear.setOnClickListener {
            binding.outputText.text = ""
        }
    }

    private fun runDiagnostic(
        label: String,
        command: String
    ) {
        if (isRunning) {
            showAlreadyRunningToast()
            return
        }

        isRunning = true
        setButtonsEnabled(false)

        appendOutput(
            "\n══════ $label ══════\n"
        )

        ShizukuRunner.executeCommand(
            command,
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (output.isNotEmpty()) {
                        appendOutput(output)
                    }

                    if (done) {
                        appendOutput(
                            "\n[${getString(R.string.engine_done)}]\n"
                        )

                        finishRun()
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    appendOutput(
                        "\n[ERROR]\n$error\n"
                    )

                    finishRun()
                }
            },
            lineBundle = Int.MAX_VALUE
        )
    }

    private fun showAlreadyRunningToast() {
        Toast.makeText(
            requireContext(),
            getString(R.string.engine_test_running),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun finishRun() {
        isRunning = false

        view?.post {
            setButtonsEnabled(true)
        }
    }

    private fun setButtonsEnabled(
        enabled: Boolean
    ) {
        val b = _binding ?: return

        b.btnCheckShizuku.isEnabled = enabled
        b.btnListFiles.isEnabled = enabled
        b.btnSignalingHelp.isEnabled = enabled
        b.btnAgentHelp.isEnabled = enabled
        b.btnCloudflaredLog.isEnabled = enabled
        b.btnSignalingLog.isEnabled = enabled
    }

    private fun appendOutput(
        text: String
    ) {
        view?.post {
            val b = _binding ?: return@post

            b.outputText.append(text)

            b.outputScroll.post {
                b.outputScroll.fullScroll(
                    View.FOCUS_DOWN
                )
            }
        }
    }

    private fun copyOutput() {
        val b = _binding ?: return

        val clipboard =
            requireContext().getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "CurBox Engine Diagnostics",
                b.outputText.text
            )
        )

        Toast.makeText(
            requireContext(),
            getString(R.string.engine_copied),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
