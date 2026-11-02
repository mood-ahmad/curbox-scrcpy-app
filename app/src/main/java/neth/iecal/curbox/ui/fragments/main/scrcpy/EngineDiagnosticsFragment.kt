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
import neth.iecal.curbox.remotecontrol.EventLogger
import neth.iecal.curbox.utils.ShizukuRunner

/**
 * Diagnostics for the CurBox remote-control engine.
 *
 * Shell-based diagnostics run through Shizuku.
 * App-side email diagnostics are read directly from EventLogger.
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

        EventLogger.init(requireContext())

        binding.btnEngineStatus.setOnClickListener {
            runDiagnostic(
                "Engine Status",
                buildEngineStatusCommand()
            )
        }

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

        binding.btnLaunchLog.setOnClickListener {
            runDiagnostic(
                "Launch Log",
                "cat /data/local/tmp/android/launch.log 2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnEmailLog.setOnClickListener {
            showEmailLog()
        }

        binding.btnCopy.setOnClickListener {
            copyOutput()
        }

        binding.btnClear.setOnClickListener {
            binding.outputText.text = ""
        }
    }

    private fun buildEngineStatusCommand(): String {
        return """
            WORK=/data/local/tmp/android

            check_pid() {
                NAME="${'$'}1"
                FILE="${'$'}2"

                if [ ! -s "${'$'}FILE" ]; then
                    echo "${'$'}NAME: STOPPED (PID file missing)"
                    return
                fi

                PID=${'$'}(cat "${'$'}FILE" 2>/dev/null)

                if [ -z "${'$'}PID" ]; then
                    echo "${'$'}NAME: STOPPED (PID empty)"
                    return
                fi

                if kill -0 "${'$'}PID" 2>/dev/null; then
                    echo "${'$'}NAME: RUNNING (PID=${'$'}PID)"
                else
                    echo "${'$'}NAME: STOPPED (stale PID=${'$'}PID)"
                fi
            }

            echo "User:"
            id 2>&1

            echo ""
            echo "Processes:"

            check_pid \
                "Signaling" \
                "${'$'}WORK/signaling.pid"

            check_pid \
                "Agent" \
                "${'$'}WORK/agent.pid"

            check_pid \
                "Cloudflared" \
                "${'$'}WORK/cloudflared.pid"

            echo ""
            echo "Diagnostic files:"

            for FILE in \
                launch.log \
                signaling.log \
                agent.log \
                cloudflared.log
            do
                if [ -e "${'$'}WORK/${'$'}FILE" ]; then
                    SIZE=${'$'}(
                        wc -c < "${'$'}WORK/${'$'}FILE" \
                            2>/dev/null
                    )

                    echo "${'$'}FILE: EXISTS (${ '$' }SIZE bytes)"
                else
                    echo "${'$'}FILE: MISSING"
                fi
            done

            echo ""
            echo "Latest launch state:"

            if [ -s "${'$'}WORK/launch.log" ]; then
                tail -n 40 "${'$'}WORK/launch.log"
            else
                echo "launch.log unavailable"
            fi

            echo ""
            echo "DONE"
        """.trimIndent()
    }

    private fun showEmailLog() {
        if (isRunning) {
            showAlreadyRunningToast()
            return
        }

        appendOutput(
            "\n══════ Email Log ══════\n"
        )

        val emailLog =
            EventLogger.getEmailLog()

        if (emailLog.isBlank()) {
            appendOutput(
                "[No email diagnostics recorded yet]\n"
            )
        } else {
            appendOutput(emailLog)

            if (!emailLog.endsWith("\n")) {
                appendOutput("\n")
            }
        }

        appendOutput(
            "\n[${getString(R.string.engine_done)}]\n"
        )
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

        b.btnEngineStatus.isEnabled = enabled
        b.btnCheckShizuku.isEnabled = enabled
        b.btnListFiles.isEnabled = enabled
        b.btnSignalingHelp.isEnabled = enabled
        b.btnAgentHelp.isEnabled = enabled
        b.btnCloudflaredLog.isEnabled = enabled
        b.btnSignalingLog.isEnabled = enabled
        b.btnLaunchLog.isEnabled = enabled
        b.btnEmailLog.isEnabled = enabled
    }

    private fun appendOutput(
        text: String
    ) {
        view?.post {
            val b =
                _binding ?: return@post

            b.outputText.append(text)

            b.outputScroll.post {
                b.outputScroll.fullScroll(
                    View.FOCUS_DOWN
                )
            }
        }
    }

    private fun copyOutput() {
        val b =
            _binding ?: return

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
