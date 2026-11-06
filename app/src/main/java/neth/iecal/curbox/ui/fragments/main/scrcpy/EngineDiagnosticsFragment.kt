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
import java.util.concurrent.atomic.AtomicLong

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

    private val diagnosticGeneration =
        AtomicLong(0L)

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

                    HAVE_GETENT=0
                    HAVE_NSLOOKUP=0
                    HAVE_PING=0
                    HAVE_CURL=0
                    HAVE_WGET=0
                    HAVE_TIMEOUT=0
                    HAVE_SS=0
                    HAVE_NETSTAT=0
                    HAVE_TOYBOX=0
                    HAVE_TOYBOX_NSLOOKUP=0

                    echo ""
                    echo "=== DNS TOOL AVAILABILITY ==="

                    for TOOL in getent nslookup ping curl wget timeout ss netstat toybox; do
                        TOOL_PATH=${'$'}(command -v "${'$'}TOOL" 2>/dev/null)
                        if [ -n "${'$'}TOOL_PATH" ]; then
                            echo "${'$'}TOOL: AVAILABLE (${'$'}TOOL_PATH)"
                            case "${'$'}TOOL" in
                                getent) HAVE_GETENT=1 ;;
                                nslookup) HAVE_NSLOOKUP=1 ;;
                                ping) HAVE_PING=1 ;;
                                curl) HAVE_CURL=1 ;;
                                wget) HAVE_WGET=1 ;;
                                timeout) HAVE_TIMEOUT=1 ;;
                                ss) HAVE_SS=1 ;;
                                netstat) HAVE_NETSTAT=1 ;;
                                toybox) HAVE_TOYBOX=1 ;;
                            esac
                        else
                            echo "${'$'}TOOL: MISSING"
                        fi
                    done

                    if [ "${'$'}HAVE_TOYBOX" -eq 1 ]; then
                        if toybox --long 2>/dev/null \
                            | tr ' \t' '\n\n' \
                            | sed 's#.*/##' \
                            | grep -qx 'nslookup'
                        then
                            HAVE_TOYBOX_NSLOOKUP=1
                        elif toybox 2>/dev/null \
                            | tr ' ,\t' '\n\n\n' \
                            | grep -qx 'nslookup'
                        then
                            HAVE_TOYBOX_NSLOOKUP=1
                        fi

                        if [ "${'$'}HAVE_TOYBOX_NSLOOKUP" -eq 1 ]; then
                            echo "toybox nslookup: AVAILABLE (applet present)"
                        else
                            echo "toybox nslookup: MISSING (applet not available)"
                        fi
                    else
                        echo "toybox nslookup: MISSING (toybox binary not found)"
                    fi

                    echo ""
                    echo "=== DNS RESOLUTION TEST ==="
                    echo "TARGET=api.trycloudflare.com"
                    echo "TARGET_TYPE=Cloudflare_API_endpoint"
                    echo "NOTE=This is a DNS resolution probe only. Success does NOT mean a Quick Tunnel URL exists."

                    DNS_TEST_ATTEMPTED=0
                    DNS_TEST_RESULT=NOT_RUN
                    DNS_TEST_RC=NONE

                    if [ "${'$'}DNS_TEST_ATTEMPTED" -eq 0 ] &&
                       [ "${'$'}HAVE_GETENT" -eq 1 ] &&
                       [ "${'$'}HAVE_TIMEOUT" -eq 1 ]; then
                        echo "Trying: timeout 10 getent hosts api.trycloudflare.com"
                        timeout 10 getent hosts api.trycloudflare.com 2>&1
                        DNS_TEST_RC=${'$'}?
                        DNS_TEST_ATTEMPTED=1
                        if [ "${'$'}DNS_TEST_RC" -eq 0 ]; then
                            DNS_TEST_RESULT=SUCCESS
                        else
                            DNS_TEST_RESULT=FAILED
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_ATTEMPTED" -eq 0 ] &&
                       [ "${'$'}HAVE_NSLOOKUP" -eq 1 ] &&
                       [ "${'$'}HAVE_TIMEOUT" -eq 1 ]; then
                        echo "Trying: timeout 10 nslookup api.trycloudflare.com"
                        timeout 10 nslookup api.trycloudflare.com 2>&1
                        DNS_TEST_RC=${'$'}?
                        DNS_TEST_ATTEMPTED=1
                        if [ "${'$'}DNS_TEST_RC" -eq 0 ]; then
                            DNS_TEST_RESULT=SUCCESS
                        else
                            DNS_TEST_RESULT=FAILED
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_ATTEMPTED" -eq 0 ] &&
                       [ "${'$'}HAVE_TOYBOX_NSLOOKUP" -eq 1 ] &&
                       [ "${'$'}HAVE_TIMEOUT" -eq 1 ]; then
                        echo "Trying: timeout 10 toybox nslookup api.trycloudflare.com"
                        timeout 10 toybox nslookup api.trycloudflare.com 2>&1
                        DNS_TEST_RC=${'$'}?
                        DNS_TEST_ATTEMPTED=1
                        if [ "${'$'}DNS_TEST_RC" -eq 0 ]; then
                            DNS_TEST_RESULT=SUCCESS
                        else
                            DNS_TEST_RESULT=FAILED
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_ATTEMPTED" -eq 0 ] &&
                       [ "${'$'}HAVE_PING" -eq 1 ]; then
                        echo "Trying: ping -c 1 -W 3 api.trycloudflare.com"
                        ping -c 1 -W 3 api.trycloudflare.com 2>&1
                        DNS_TEST_RC=${'$'}?
                        DNS_TEST_ATTEMPTED=1
                        if [ "${'$'}DNS_TEST_RC" -eq 0 ]; then
                            DNS_TEST_RESULT=SUCCESS
                        else
                            DNS_TEST_RESULT=FAILED
                        fi
                    fi

                    if [ "${'$'}DNS_TEST_ATTEMPTED" -eq 0 ]; then
                        echo "No bounded DNS diagnostic tool is available"
                    fi

                    echo "DNS_TEST_ATTEMPTED=${'$'}DNS_TEST_ATTEMPTED"
                    echo "DNS_TEST_RESULT=${'$'}DNS_TEST_RESULT"
                    echo "DNS_TEST_RC=${'$'}DNS_TEST_RC"

                    echo ""
                    echo "=== HTTPS CONNECTIVITY TEST ==="
                    echo "HTTPS_TARGET_TYPE=Cloudflare_API_endpoint"
                    echo "HTTPS_TARGET_URL=https://api.trycloudflare.com/"
                    echo "NOTE=This tests connectivity to the Cloudflare API endpoint. It does NOT prove a Quick Tunnel URL is active."

                    HTTPS_TEST_DONE=0
                    HTTPS_TEST_RC=NONE

                    if [ "${'$'}HAVE_CURL" -eq 1 ]; then
                        echo "Trying: curl -I -sS --connect-timeout 5 --max-time 10 https://api.trycloudflare.com/"
                        curl -I -sS --connect-timeout 5 --max-time 10 https://api.trycloudflare.com/ 2>&1
                        HTTPS_TEST_RC=${'$'}?
                        HTTPS_TEST_DONE=1
                    fi

                    if [ "${'$'}HTTPS_TEST_DONE" -eq 0 ] &&
                       [ "${'$'}HAVE_WGET" -eq 1 ]; then
                        echo "Trying: wget bounded"
                        if [ "${'$'}HAVE_TIMEOUT" -eq 1 ]; then
                            timeout 10 wget -q -S -O /dev/null --timeout=5 https://api.trycloudflare.com/ 2>&1
                        else
                            wget -q -S -O /dev/null --timeout=5 https://api.trycloudflare.com/ 2>&1
                        fi
                        HTTPS_TEST_RC=${'$'}?
                        HTTPS_TEST_DONE=1
                    fi

                    if [ "${'$'}HTTPS_TEST_DONE" -eq 0 ]; then
                        echo "No HTTPS diagnostic tool is available"
                        HTTPS_TEST_RC=NOT_RUN
                    fi

                    echo "HTTPS_TEST_RC=${'$'}HTTPS_TEST_RC"

                    echo ""
                    echo "=== LOOPBACK DNS PORT 53 ==="

                    PORT53_REPORTED=0

                    if [ "${'$'}HAVE_SS" -eq 1 ]; then
                        ss -ltnup 2>/dev/null | grep ':53 ' && PORT53_REPORTED=1 || true
                        if [ "${'$'}PORT53_REPORTED" -eq 0 ]; then
                            echo "ss: no listener found on port 53"
                        fi
                    fi

                    if [ "${'$'}PORT53_REPORTED" -eq 0 ] &&
                       [ "${'$'}HAVE_NETSTAT" -eq 1 ]; then
                        netstat -ltnup 2>/dev/null | grep ':53 ' && PORT53_REPORTED=1 || true
                        if [ "${'$'}PORT53_REPORTED" -eq 0 ]; then
                            echo "netstat: no listener found on port 53"
                        fi
                    fi

                    if [ "${'$'}HAVE_SS" -eq 0 ] &&
                       [ "${'$'}HAVE_NETSTAT" -eq 0 ]; then
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

                case "${'$'}PID" in
                    ""|*[!0-9]*)
                        echo \
                            "${'$'}NAME: STOPPED " \
                            "(invalid non-numeric PID='${'$'}PID')"

                        return 1
                        ;;
                esac

                if [ "${'$'}PID" -le 1 ]; then
                    echo \
                        "${'$'}NAME: STOPPED " \
                        "(invalid PID=${'$'}PID, must be > 1)"

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
            CORE_RUNNING=0

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

            if [ "${'$'}SIGNALING_RUNNING" -eq 1 ] &&
               [ "${'$'}AGENT_RUNNING" -eq 1 ]; then
                CORE_RUNNING=1
            fi

            echo ""
            echo "Health summary:"

            if [ "${'$'}CORE_RUNNING" -eq 1 ]; then
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
            echo "=== ENGINE DIAGNOSTIC SUMMARY ==="

            if [ "${'$'}SIGNALING_RUNNING" -eq 1 ]; then
                echo "SIGNALING_STATUS=RUNNING"
            else
                echo "SIGNALING_STATUS=STOPPED"
            fi

            if [ "${'$'}AGENT_RUNNING" -eq 1 ]; then
                echo "AGENT_STATUS=RUNNING"
            else
                echo "AGENT_STATUS=STOPPED"
            fi

            if [ "${'$'}CORE_RUNNING" -eq 1 ]; then
                echo "CORE_STATUS=RUNNING"
            else
                echo "CORE_STATUS=STOPPED_OR_INCOMPLETE"
            fi

            if [ "${'$'}CLOUDFLARED_RUNNING" -eq 1 ]; then
                echo "CLOUDFLARED_STATUS=RUNNING"
            else
                echo "CLOUDFLARED_STATUS=STOPPED"
            fi

            if [ "${'$'}CORE_RUNNING" -eq 1 ]; then
                echo "LOG_FRESHNESS=CURRENT"
            else
                echo "LOG_FRESHNESS=STALE"
            fi

            echo ""
            echo "=== LOG FRESHNESS ==="

            if [ "${'$'}CORE_RUNNING" -eq 1 ]; then
                echo "launch.log=CURRENT"
                echo "signaling.log=CURRENT"
                echo "agent.log=CURRENT"
            else
                echo "launch.log=STALE"
                echo "signaling.log=STALE"
                echo "agent.log=STALE"
            fi

            if [ "${'$'}CLOUDFLARED_RUNNING" -eq 1 ]; then
                echo "cloudflared.log=CURRENT"
            else
                echo "cloudflared.log=STALE"
            fi

            echo ""

            if [ "${'$'}CORE_RUNNING" -eq 1 ]; then
                echo "=== CURRENT LAUNCH LOG ==="
                echo "LOG_FRESHNESS=CURRENT"
            else
                echo "=== STORED LAUNCH LOG ==="
                echo "LOG_FRESHNESS=STALE"
                echo "WARNING=engine_not_currently_running"
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

    private fun appendGeneralEventLog(
        runGeneration: Long
    ) {
        appendOutput(
            "\n══════ General Event Log ══════\n",
            runGeneration
        )

        val events =
            EventLogger.getAll()

        if (events.isBlank()) {
            appendOutput(
                "[No general remote-control events recorded yet]\n",
                runGeneration
            )
        } else {
            appendOutput(
                events,
                runGeneration
            )

            if (!events.endsWith("\n")) {
                appendOutput(
                    "\n",
                    runGeneration
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

        val runGeneration =
            diagnosticGeneration.incrementAndGet()

        val doneText =
            getString(
                R.string.engine_done
            )

        appendOutput(
            "\n══════ $label ══════\n",
            runGeneration
        )

        ShizukuRunner.executeCommand(
            command,
            object :
                ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (runGeneration != diagnosticGeneration.get()) {
                        return
                    }

                    if (output.isNotEmpty()) {
                        appendOutput(
                            output,
                            runGeneration
                        )
                    }

                    if (done) {
                        if (includeGeneralEvents) {
                            appendGeneralEventLog(
                                runGeneration
                            )
                        }

                        appendOutput(
                            "\n[$doneText]\n",
                            runGeneration
                        )

                        finishRun(
                            runGeneration
                        )
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    if (runGeneration != diagnosticGeneration.get()) {
                        return
                    }

                    appendOutput(
                        "\n[ERROR]\n$error\n",
                        runGeneration
                    )

                    if (includeGeneralEvents) {
                        appendGeneralEventLog(
                            runGeneration
                        )
                    }

                    appendOutput(
                        "\n[$doneText]\n",
                        runGeneration
                    )

                    finishRun(
                        runGeneration
                    )
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

    private fun finishRun(
        runGeneration: Long
    ) {
        view?.post {
            if (runGeneration != diagnosticGeneration.get()) {
                return@post
            }

            isRunning =
                false

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

    private fun appendOutput(
        text: String,
        runGeneration: Long
    ) {
        view?.post {
            if (runGeneration != diagnosticGeneration.get()) {
                return@post
            }

            val b =
                _binding ?: return@post

            b.outputText.append(
                text
            )

            b.outputScroll.post {
                if (runGeneration != diagnosticGeneration.get()) {
                    return@post
                }

                val currentBinding =
                    _binding ?: return@post

                currentBinding.outputScroll.fullScroll(
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
        diagnosticGeneration.incrementAndGet()

        isRunning =
            false

        _binding =
            null

        super.onDestroyView()
    }
}
