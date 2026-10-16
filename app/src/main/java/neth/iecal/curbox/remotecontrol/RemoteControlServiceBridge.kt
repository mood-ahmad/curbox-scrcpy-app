private fun buildLaunchScript(): String {
    val target = RemoteControlConfig.WORK_DIR
    val port = RemoteControlConfig.SIGNALING_PORT
    val agentId = RemoteControlConfig.AGENT_ID
    val signalingUrl = "wss://127.0.0.1:$port"
    val agentJar = RemoteControlConfig.AGENT_JAR

    return """
        cd $target
        export CLASSPATH=$target/libsys_core.so

        # --- 1) Start signaling server (HTTPS on port 8443)
        chmod +x webrtc-signaling
        nohup ./webrtc-signaling -port $port -assets ./assets -cert ./certs/server.crt -key ./certs/server.key > signaling.log 2>&1 &
        sleep 3

        # --- 2) Start agent
        chmod +x cloudphone-agent
        export CP_AGENT_ID="$agentId"
        export CP_AGENT_SIGNALING="$signalingUrl"
        export CP_AGENT_JAR="$agentJar"
        setsid nohup env GODEBUG=asyncpreemptoff=1 ./cloudphone-agent -expires-at "" > agent.log 2>&1 &
        sleep 6

        # --- 3) Detect WiFi (multiple methods for Samsung + Android)
        WIFI_IF=""

        # Method 1: getprop (most reliable on Samsung)
        PROP_IF=${'$'}(getprop wifi.interface 2>/dev/null)
        if [ -n "${'$'}PROP_IF" ]; then
            WIFI_IF="${'$'}PROP_IF"
        fi

        # Method 2: scan /sys/class/net for wlan interfaces
        if [ -z "${'$'}WIFI_IF" ]; then
            for f in /sys/class/net/*; do
                n=${'$'}(basename "${'$'}f")
                case "${'$'}n" in
                    wlan*|swlan*) WIFI_IF="${'$'}n"; break ;;
                esac
            done
        fi

        # Method 3: ip link
        if [ -z "${'$'}WIFI_IF" ]; then
            WIFI_IF=${'$'}(ip -o link 2>/dev/null | grep -oE '(wlan|swlan)[0-9]+' | head -n1)
        fi

        # Get WiFi IP
        WIFI_IP=""
        if [ -n "${'$'}WIFI_IF" ]; then
            WIFI_IP=${'$'}(ip -4 addr show "${'$'}WIFI_IF" 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print ${'$'}2}' | head -n1)
            if [ -z "${'$'}WIFI_IP" ]; then
                WIFI_IP=${'$'}(ifconfig "${'$'}WIFI_IF" 2>/dev/null | grep 'inet ' | awk '{print ${'$'}2}' | sed 's/addr://' | head -n1)
            fi
        fi

        echo "DEBUG: WIFI_IF=${'$'}WIFI_IF WIFI_IP=${'$'}WIFI_IP"

        if [ -n "${'$'}WIFI_IP" ]; then
            # --- WiFi: local IP directly
            echo "NETWORK=WIFI"
            echo "LINK=https://${'$'}WIFI_IP:$port"
        else
            # --- Mobile: cloudflared Quick Tunnel
            chmod +x cloudflared
            rm -f cloudflared.log
            nohup ./cloudflared tunnel --url https://127.0.0.1:$port --no-tls-verify > cloudflared.log 2>&1 &
            # Wait for the tunnel URL (up to 25s)
            for i in ${'$'}(seq 1 25); do
                if grep -qE 'https://[a-z0-9-]+-[a-z0-9-]+-[a-z0-9-]+\.trycloudflare\.com' cloudflared.log 2>/dev/null; then
                    break
                fi
                sleep 1
            done
            # Extract URL: must have at least 2 dashes in subdomain, exclude api.*
            URL=${'$'}(grep -oE 'https://[a-z0-9-]+-[a-z0-9-]+-[a-z0-9-]+\.trycloudflare\.com' cloudflared.log | head -n1)
            if [ -z "${'$'}URL" ]; then
                echo "NETWORK=MOBILE"
                echo "LINK_ERROR=cloudflared URL not found"
                echo "--- cloudflared.log (tail) ---"
                tail -n 30 cloudflared.log 2>/dev/null
            else
                echo "NETWORK=MOBILE"
                echo "LINK=${'$'}URL"
            fi
        fi
    """.trimIndent()
}
