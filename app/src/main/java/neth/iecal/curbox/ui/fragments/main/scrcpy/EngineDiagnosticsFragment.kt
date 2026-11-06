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
 *
 * App-side general lifecycle events and email diagnostics are read
 * directly from EventLogger.
 */
class EngineDiagnosticsFragment : Fragment() {

    private var _binding:
        FragmentEngineDiagnosticsBinding? = null

    private val binding
        get() = _binding!!

    private var isRunning =
        false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentEngineDiagnosticsBinding.inflate(
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

        EventLogger.init(
            requireContext()
        )

        binding.btnEngineStatus.setOnClickListener {
            runDiagnostic(
                label = "Engine Status",
                command =
                    buildEngineStatusCommand(),
                includeGeneralEvents = true
            )
        }

        binding.btnCheckShizuku.setOnClickListener {
            runDiagnostic(
                label =
                    "Check Shizuku, Files & DNS",
                command = """
                    set +e

                    echo "=== IDENTITY ==="
                    id 2>&1

                    echo ""
                    echo "=== WORKDIR FILES ==="
                    ls -la /data/local/tmp/android/ 2>&1

                    echo ""
                    echo "=== ANDROID DNS PROPERTIES ==="
                    getprop 2>&1 | grep -iE 'dns|net\.|private_dns' || true

                    echo ""
                    echo "=== RESOLV.CONF ==="
                    if [ -r /etc/resolv.conf ]; then
                        cat /etc/resolv.conf 2>&1
                    else
                        echo "/etc/resolv.conf is unavailable"
                    fi

                    echo ""
                    echo "=== NETWORK INTERFACES ==="
                    ip addr 2>&1 || true

                    echo ""
                    echo "=== ROUTES ==="
                    ip route 2>&1 || true
                    ip -6 route 2>&1 || true

                    echo ""
                    echo "=== DNS TOOL AVAILABILITY ==="

                    for TOOL in getent nslookup ping curl wget toybox; do
                        TOOL_PATH=${'$'}(command -v "${'$'}TOOL" 2>/dev/null)
                        if [ -n "${'$'}TOOL_PATH" ]; then
                            echo "${'$'}TOOL: AVAILABLE (${'$'}TOOL_PATH)"
                        else
                            echo "${'$'}TOOL: MISSING"
                        fi
                    done

                    echo ""
                    echo "=== DNS RESOLUTION TEST ==="

                    HAS_TIMEOUT=0
                    if command -v timeout >/dev/null 2>&1; then
                        HAS_TIMEOUT=1
                    fi

                    DNS_TEST_DONE=0

                    if [ "${'$'}DNS_TEST_DONE" -eq 0 ] &&
                       command -v getent >/dev/null 2>&1; then
                        if [ "${'$'}HAS_TIMEOUT" -eq 1 ]; then
                            echo "Trying: timeout 10 getent hosts api.trycloudflare.com"
                            timeout 10 getent hosts api.trycloudflare.com 2>&1
                            DNS_TEST_DONE=1
                        else
                            echo "Skipping getent because the timeout command is unavailable"
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_DONE" -eq 0 ] &&
                       command -v nslookup >/dev/null 2>&1; then
                        if [ "${'$'}HAS_TIMEOUT" -eq 1 ]; then
                            echo "Trying: timeout 10 nslookup api.trycloudflare.com"
                            timeout 10 nslookup api.trycloudflare.com 2>&1
                            DNS_TEST_DONE=1
                        else
                            echo "Skipping nslookup because the timeout command is unavailable"
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_DONE" -eq 0 ] &&
                       command -v toybox >/dev/null 2>&1; then
                        if [ "${'$'}HAS_TIMEOUT" -eq 1 ]; then
                            echo "Trying: timeout 10 toybox nslookup api.trycloudflare.com"
                            timeout 10 toybox nslookup api.trycloudflare.com 2>&1
                            DNS_TEST_DONE=1
                        else
                            echo "Skipping toybox nslookup because the timeout command is unavailable"
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_DONE" -eq 0 ] &&
                       command -v ping >/dev/null 2>&1; then
                        echo "Trying: ping -c 1 -W 3 api.trycloudflare.com"
                        ping -c 1 -W 3 api.trycloudflare.com 2>&1
                        DNS_TEST_DONE=1
                    fi

                    if [ "${'$'}DNS_TEST_DONE" -eq 0 ]; then
                        echo "No bounded DNS diagnostic tool is available"
                    fi

                    echo ""
                    echo "=== HTTPS CONNECTIVITY TEST ==="

                    HTTPS_TEST_DONE=0

                    if command -v curl >/dev/null 2>&1; then
                        echo "Trying: curl -I -sS --connect-timeout 5 --max-time 10 https://api.trycloudflare.com/"
                        curl -I -sS --connect-timeout 5 --max-time 10 https://api.trycloudflare.com/ 2>&1
                        HTTPS_TEST_DONE=1
                    fi

                    if [ "${'$'}HTTPS_TEST_DONE" -eq 0 ] &&
                       command -v wget >/dev/null 2>&1; then
                        echo "Trying: wget bounded"
                        if [ "${'$'}HAS_TIMEOUT" -eq 1 ]; then
                            timeout 10 wget -q -S -O /dev/null --timeout=5 https://api.trycloudflare.com/ 2>&1
                        else
                            wget -q -S -O /dev/null --timeout=5 https://api.trycloudflare.com/ 2>&1
                        fi
                        HTTPS_TEST_DONE=1
                    fi

                    if [ "${'$'}HTTPS_TEST_DONE" -eq 0 ]; then
                        echo "No HTTPS diagnostic tool is available"
                    fi

                    echo ""
                    echo "=== LOOPBACK DNS PORT 53 ==="

                    PORT53_REPORTED=0

                    if command -v ss >/dev/null 2>&1; then
                        ss -ltnup 2>/dev/null | grep ':53 ' && PORT53_REPORTED=1 || true
                        if [ "${'$'}PORT53_REPORTED" -eq 0 ]; then
                            echo "ss: no listener found on port 53"
                        fi
                    fi

                    if [ "${'$'}PORT53_REPORTED" -eq 0 ] &&
                       command -v netstat >/dev/null 2>&1; then
                        netstat -ltnup 2>/dev/null | grep ':53 ' && PORT53_REPORTED=1 || true
                        if [ "${'$'}PORT53_REPORTED" -eq 0 ]; then
                            echo "netstat: no listener found on port 53"
                        fi
                    fi

                    if [ "${'$'}PORT53_REPORTED" -eq 0 ] &&
                       ! command -v ss >/dev/null 2>&1 &&
                       ! command -v netstat >/dev/null 2>&1; then
                        echo "Neither ss nor netstat is available"
                    fi

                    echo ""
                    echo "DONE"
                """.trimIndent()
            )
        }

        binding.btnListFiles.setOnClickListener {
            runDiagnostic(
                label =
                    "List Files",
                command =
                    "ls -la /data/local/tmp/android/ 2>&1"
            )
        }

        binding.btnSignalingHelp.setOnClickListener {
            runDiagnostic(
                label =
                    "Signaling --help",
                command =
                    "cd /data/local/tmp/android && " +
                        "timeout 5 ./webrtc-signaling --help 2>&1 ; " +
                        "echo '' ; echo 'DONE'"
            )
        }

        binding.btnAgentHelp.setOnClickListener {
            runDiagnostic(
                label =
                    "Agent Log (last 100 lines)",
                command =
                    "tail -n 100 " +
                        "/data/local/tmp/android/agent.log " +
                        "2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnCloudflaredLog.setOnClickListener {
            runDiagnostic(
                label =
                    "Cloudflared Log",
                command =
                    "cat " +
                        "/data/local/tmp/android/cloudflared.log " +
                        "2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnSignalingLog.setOnClickListener {
            runDiagnostic(
                label =
                    "Signaling Log (last 150 lines)",
                command =
                    "tail -n 150 " +
                        "/data/local/tmp/android/signaling.log " +
                        "2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnLaunchLog.setOnClickListener {
            runDiagnostic(
                label =
                    "Launch Log",
                command =
                    "cat " +
                        "/data/local/tmp/android/launch.log " +
                        "2>&1 ; echo '' ; echo 'DONE'"
            )
        }

        binding.btnEmailLog.setOnClickListener {
            showEmailLog()
        }

        binding.btnCopy.setOnClickListener {
            copyOutput()
        }

        binding.btnClear.setOnClickListener {
            binding.outputText.text =
                ""
        }
    }

    private fun buildEngineStatusCommand(): String {
        return """
            WORK=/data/local/tmp/android

            normalise_exe() {
                printf '%s' "${'$'}1" \
                    | sed 's/ (deleted)${'$'}//'
            }

            read_process_executable() {
                PID="${'$'}1"

                ACTUAL_EXE=${'$'}(
                    readlink \
                        "/proc/${'$'}PID/exe" \
                        2>/dev/null || true
                )

                normalise_exe \
                    "${'$'}ACTUAL_EXE"
            }

            check_pid() {
                NAME="${'$'}1"
                FILE="${'$'}2"
                EXPECTED_EXE="${'$'}3"

                if [ ! -s "${'$'}FILE" ]; then
                    echo \
                        "${'$'}NAME: STOPPED (PID file missing)"

                    return 1
                fi

                PID=${'$'}(
                    cat "${'$'}FILE" \
                        2>/dev/null || true
                )

                if [ -z "${'$'}PID" ]; then
                    echo \
                        "${'$'}NAME: STOPPED (PID empty)"

                    return 1
                fi

                if ! kill -0 \
                    "${'$'}PID" \
                    2>/dev/null; then

                    echo \
                        "${'$'}NAME: STOPPED (stale PID=${'$'}PID)"

                    return 1
                fi

                ACTUAL_EXE=${'$'}(
                    read_process_executable \
                        "${'$'}PID"
                )

                if [ -z "${'$'}ACTUAL_EXE" ]; then
                    echo \
                        "${'$'}NAME: UNKNOWN " \
                        "(PID=${'$'}PID, executable unavailable)"

                    return 1
                fi

                if [ "${'$'}ACTUAL_EXE" != "${'$'}EXPECTED_EXE" ]; then
                    echo \
                        "${'$'}NAME: STOPPED " \
                        "(PID=${'$'}PID belongs to ${'$'}ACTUAL_EXE)"

                    return 1
                fi

                echo \
                    "${'$'}NAME: RUNNING " \
                    "(PID=${'$'}PID, executable verified)"

                return 0
            }

            echo "User:"
            id 2>&1

            echo ""
            echo "Processes:"

            SIGNALING_RUNNING=0
            AGENT_RUNNING=0
            CLOUDFLARED_RUNNING=0

            if check_pid \
                "Signaling" \
                "${'$'}WORK/signaling.pid" \
                "${'$'}WORK/webrtc-signaling"
            then
                SIGNALING_RUNNING=1
            fi

            if check_pid \
                "Agent" \
                "${'$'}WORK/agent.pid" \
                "${'$'}WORK/cloudphone-agent"
            then
                AGENT_RUNNING=1
            fi

            if check_pid \
                "Cloudflared" \
                "${'$'}WORK/cloudflared.pid" \
                "${'$'}WORK/cloudflared"
            then
                CLOUDFLARED_RUNNING=1
            fi

            echo ""
            echo "Health summary:"

            if [ "${'$'}SIGNALING_RUNNING" -eq 1 ] &&
               [ "${'$'}AGENT_RUNNING" -eq 1 ]; then

                echo "Core engine: HEALTHY"
            else
                echo "Core engine: STOPPED OR INCOMPLETE"
            fi

            if [ "${'$'}CLOUDFLARED_RUNNING" -eq 1 ]; then
                echo "Public tunnel: RUNNING"
            else
                echo "Public tunnel: STOPPED"
            fi

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
                        wc -c \
                            < "${'$'}WORK/${'$'}FILE" \
                            2>/dev/null || echo 0
                    )

                    echo \
                        "${'$'}FILE: EXISTS (${'$'}SIZE bytes)"
                else
                    echo \
                        "${'$'}FILE: MISSING"
                fi
            done

            echo ""

            if [ "${'$'}SIGNALING_RUNNING" -eq 1 ] &&
               [ "${'$'}AGENT_RUNNING" -eq 1 ]; then

                echo "Current launch state:"
            else
                echo \
                    "Stored launch state " \
                    "(engine is not currently running):"
            fi

            if [ -s "${'$'}WORK/launch.log" ]; then
                tail -n 80 \
                    "${'$'}WORK/launch.log"
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
            appendOutput(
                emailLog
            )

            if (!emailLog.endsWith("\n")) {
                appendOutput(
                    "\n"
                )
            }
        }

        appendOutput(
            "\n[${getString(R.string.engine_done)}]\n"
        )
    }

    private fun appendGeneralEventLog() {
        appendOutput(
            "\n══════ General Event Log ══════\n"
        )

        val events =
            EventLogger.getAll()

        if (events.isBlank()) {
            appendOutput(
                "[No general remote-control events recorded yet]\n"
            )
        } else {
            appendOutput(
                events
            )

            if (!events.endsWith("\n")) {
                appendOutput(
                    "\n"
                )
            }
        }
    }

    private fun runDiagnostic(
        label: String,
        command: String,
        includeGeneralEvents: Boolean = false
    ) {
        if (isRunning) {
            showAlreadyRunningToast()
            return
        }

        isRunning =
            true

        setButtonsEnabled(
            false
        )

        appendOutput(
            "\n══════ $label ══════\n"
        )

        ShizukuRunner.executeCommand(
            command,
            object :
                ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (output.isNotEmpty()) {
                        appendOutput(
                            output
                        )
                    }

                    if (done) {
                        if (includeGeneralEvents) {
                            appendGeneralEventLog()
                        }

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

                    if (includeGeneralEvents) {
                        appendGeneralEventLog()
                    }

                    appendOutput(
                        "\n[${getString(R.string.engine_done)}]\n"
                    )

                    finishRun()
                }
            },
            lineBundle =
                Int.MAX_VALUE
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
        isRunning =
            false

        view?.post {
            setButtonsEnabled(
                true
            )
        }
    }

    private fun setButtonsEnabled(
        enabled: Boolean
    ) {
        val b =
            _binding ?: return

        b.btnEngineStatus.isEnabled =
            enabled

        b.btnCheckShizuku.isEnabled =
            enabled

        b.btnListFiles.isEnabled =
            enabled

        b.btnSignalingHelp.isEnabled =
            enabled

        b.btnAgentHelp.isEnabled =
            enabled

        b.btnCloudflaredLog.isEnabled =
            enabled

        b.btnSignalingLog.isEnabled =
            enabled

        b.btnLaunchLog.isEnabled =
            enabled

        b.btnEmailLog.isEnabled =
            enabled
    }

    private fun appendOutput(
        text: String
    ) {
        view?.post {
            val b =
                _binding ?: return@post

            b.outputText.append(
                text
            )

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
            requireContext()
                .getSystemService(
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
        _binding =
            null

        super.onDestroyView()
    }
}
