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
        sleep 5

        # --- 2) Start agent
        chmod +x cloudphone-agent
        export CP_AGENT_ID="$agentId"
        export CP_AGENT_SIGNALING="$signalingUrl"
        export CP_AGENT_JAR="$agentJar"
        setsid nohup env GODEBUG=asyncpreemptoff=1 ./cloudphone-agent -expires-at "" > agent.log 2>&1 &
        sleep 8

        # --- 3) Detect WiFi robustly
        # We scan every interface in /sys/class/net and check if it's
        # wireless by looking for the "wireless" subdir or "phy80211" link.
        # This is the most reliable method on Android (Samsung included).
        WIFI_IF=""
        WIFI_IP=""

        echo "DEBUG: all interfaces:"
        ls /sys/class/net/

        for iface_dir in /sys/class/net/*; do
            iface_name=${'$'}(basename "${'$'}iface_dir")

            # Skip loopback
            if [ "${'$'}iface_name" = "lo" ]; then continue; fi

            # Check if it's wireless
            IS_WIRELESS=0
            if [ -d "${'$'}iface_dir/wireless" ]; then
                IS_WIRELESS=1
            elif [ -e "${'$'}iface_dir/phy80211" ]; then
                IS_WIRELESS=1
            fi

            if [ "${'$'}IS_WIRELESS" = "1" ]; then
                echo "DEBUG: found wireless iface: ${'$'}iface_name"

                # Try to get an IPv4 address from this interface
                CANDIDATE_IP=${'$'}(ip -4 addr show "${'$'}iface_name" 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print ${'$'}2}' | head -n1)

                if [ -z "${'$'}CANDIDATE_IP" ]; then
                    CANDIDATE_IP=${'$'}(ifconfig "${'$'}iface_name" 2>/dev/null | grep 'inet ' | awk '{print ${'$'}2}' | sed 's/addr://' | head -n1)
                fi

                if [ -n "${'$'}CANDIDATE_IP" ]; then
                    WIFI_IF="${'$'}iface_name"
                    WIFI_IP="${'$'}CANDIDATE_IP"
                    break
                fi
            fi
        done

        echo "DEBUG: WIFI_IF=${'$'}WIFI_IF WIFI_IP=${'$'}WIFI_IP"

        if [ -n "${'$'}WIFI_IP" ]; then
            # --- WiFi: use local IP directly
            echo "NETWORK=WiFi"
            echo "LINK=https://${'$'}WIFI_IP:$port"
        else
            # --- Mobile: cloudflared Quick Tunnel
            chmod +x cloudflared
            rm -f cloudflared.log
            nohup ./cloudflared tunnel --url https://127.0.0.1:$port --no-tls-verify > cloudflared.log 2>&1 &
            for i in ${'$'}(seq 1 25); do
                if grep -qE 'https://[a-z0-9-]+-[a-z0-9-]+-[a-z0-9-]+\.trycloudflare\.com' cloudflared.log 2>/dev/null; then
                    break
                fi
                sleep 1
            done
            URL=${'$'}(grep -oE 'https://[a-z0-9-]+-[a-z0-9-]+-[a-z0-9-]+\.trycloudflare\.com' cloudflared.log | head -n1)
            if [ -z "${'$'}URL" ]; then
                echo "NETWORK=Mobile"
                echo "LINK_ERROR=cloudflared URL not found"
                echo "--- cloudflared.log (tail) ---"
                tail -n 30 cloudflared.log 2>/dev/null
            else
                echo "NETWORK=Mobile"
                echo "LINK=${'$'}URL"
            fi
        fi
    """.trimIndent()
}
