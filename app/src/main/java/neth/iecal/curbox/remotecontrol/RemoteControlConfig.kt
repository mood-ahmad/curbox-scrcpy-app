package neth.iecal.curbox.remotecontrol

object RemoteControlConfig {

    const val ASSETS_ROOT = "cloudphone"

    const val WORK_DIR = "/data/local/tmp/android"

    const val SIGNALING_PORT = 8443

    const val AGENT_ID = "local-android"

    const val AGENT_SIGNALING_URL =
        "wss://127.0.0.1:$SIGNALING_PORT"

    const val AGENT_JAR = "./libsys_core.so"

    val REQUIRED_ITEMS = listOf(
        "cloudphone-agent",
        "webrtc-signaling",
        "cloudflared",
        "libsys_core.so",
        "certs",
        "assets"
    )
}
