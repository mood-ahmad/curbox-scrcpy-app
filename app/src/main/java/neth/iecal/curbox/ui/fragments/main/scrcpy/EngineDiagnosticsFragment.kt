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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentEngineDiagnosticsBinding
import neth.iecal.curbox.kadb.KadbManager
import neth.iecal.curbox.utils.ShizukuRunner

/**
 * Diagnostics for the Scrcpy engine and the isolated Kadb proof of concept.
 *
 * Shizuku remains the active execution backend.
 * Kadb is tested independently and does not control the engine yet.
 */
class EngineDiagnosticsFragment : Fragment() {

    private var _binding: FragmentEngineDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private var kadbManager: KadbManager? = null
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

        kadbManager = KadbManager(requireContext())

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

        binding.btnKadbDiscover.setOnClickListener {
            runKadbOperation(
                "Kadb: Discover Pairing Endpoint"
            ) { manager ->

                val endpoint =
                    manager.awaitPairingEndpoint()

                buildString {
                    appendLine("Pairing endpoint discovered")
                    appendLine("Name: ${endpoint.name}")
                    appendLine("Host: ${endpoint.host}")
                    appendLine("Port: ${endpoint.port}")
                    appendLine("Service: ${endpoint.serviceType}")
                }
            }
        }

        binding.btnKadbPairTest.setOnClickListener {
            val pairingCode =
                binding.kadbPairingCode.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()

            if (!pairingCode.matches(Regex("^[0-9]{6}$"))) {
                binding.kadbPairingCode.error =
                    getString(
                        R.string.engine_kadb_invalid_code
                    )

                return@setOnClickListener
            }

            binding.kadbPairingCode.error = null

            runKadbOperation(
                "Kadb: Pair & Run ID"
            ) { manager ->

                val result =
                    manager.pairAndRunId(pairingCode)

                buildString {
                    appendLine("Pairing completed")
                    appendLine()

                    appendLine("Pairing endpoint:")
                    appendLine(
                        "${result.pairingEndpoint.host}:${result.pairingEndpoint.port}"
                    )

                    appendLine()

                    appendLine("Connection endpoint:")
                    appendLine(
                        "${result.connectEndpoint.host}:${result.connectEndpoint.port}"
                    )

                    appendLine()

                    appendLine("Shell output:")
                    appendLine(
                        result.commandResult.allOutput.trim()
                    )

                    appendLine()

                    appendLine(
                        "Exit code: ${result.commandResult.exitCode}"
                    )

                    appendLine(
                        if (result.shellAccessConfirmed) {
                            "RESULT: uid=2000(shell) confirmed"
                        } else {
                            "RESULT: shell UID was not confirmed"
                        }
                    )
                }
            }
        }

        binding.btnKadbConnectId.setOnClickListener {
            runKadbOperation(
                "Kadb: Connect & Run ID"
            ) { manager ->

                val result =
                    manager.connectAndRunId()

                buildString {
                    appendLine("Connection endpoint:")
                    appendLine(
                        "${result.endpoint.host}:${result.endpoint.port}"
                    )

                    appendLine()

                    appendLine("Shell output:")
                    appendLine(
                        result.allOutput.trim()
                    )

                    appendLine()

                    appendLine(
                        "Exit code: ${result.exitCode}"
                    )

                    appendLine(
                        if (
                            result.output.contains(
                                "uid=2000(shell)",
                                ignoreCase = true
                            )
                        ) {
                            "RESULT: uid=2000(shell) confirmed"
                        } else {
                            "RESULT: shell UID was not confirmed"
                        }
                    )
                }
            }
        }

        binding.btnCopy.setOnClickListener {
            copyOutput()
        }

        binding.btnClear.setOnClickListener {
            binding.outputText.text = ""
        }
    }

    private fun runKadbOperation(
        label: String,
        operation: suspend (KadbManager) -> String
    ) {
        if (isRunning) {
            showAlreadyRunningToast()
            return
        }

        val manager = kadbManager ?: run {
            appendOutput(
                "\n[ERROR]\nKadb manager is unavailable\n"
            )
            return
        }

        isRunning = true
        setButtonsEnabled(false)

        appendOutput(
            "\n══════ $label ══════\n"
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val output =
                    operation(manager)

                appendOutput(output)

                appendOutput(
                    "\n[${getString(R.string.engine_done)}]\n"
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                appendOutput(
                    "\n[ERROR]\n" +
                        (error.message ?: error.javaClass.simpleName) +
                        "\n"
                )
            } finally {
                finishRun()
            }
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
            getString(
                R.string.engine_test_running
            ),
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

        b.btnKadbDiscover.isEnabled = enabled
        b.btnKadbPairTest.isEnabled = enabled
        b.btnKadbConnectId.isEnabled = enabled
        b.kadbPairingCode.isEnabled = enabled
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
            getString(
                R.string.engine_copied
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        kadbManager?.close()
        kadbManager = null

        _binding = null

        super.onDestroyView()
    }
}
