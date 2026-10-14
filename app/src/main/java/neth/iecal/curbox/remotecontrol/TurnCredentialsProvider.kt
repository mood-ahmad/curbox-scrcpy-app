package neth.iecal.curbox.remotecontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches TURN credentials from Hugging Face's free WebRTC service
 * (partnership with Cloudflare Realtime).
 *
 * Endpoint: POST https://huggingface.co/api/webrtc/turn-credentials
 * Auth: Bearer <HF_TOKEN>
 *
 * Returns a list of ICE servers ready for the webrtc-signaling binary.
 */
object TurnCredentialsProvider {

    private const val ENDPOINT = "https://huggingface.co/api/webrtc/turn-credentials"

    /**
     * Fetches ICE servers from Hugging Face.
     * Returns null if HF_TOKEN is missing or the request fails.
     */
    suspend fun fetchIceServers(): List<IceServer>? = withContext(Dispatchers.IO) {
        val token = BuildConfig.HF_TOKEN
        if (token.isBlank()) {
            EventLogger.log("TURN", "HF_TOKEN is empty, skipping TURN")
            return@withContext null
        }

        try {
            val url = URL(ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                // Empty JSON body
                outputStream.use { it.write("{}".toByteArray()) }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                EventLogger.log("TURN", "HF returned HTTP $code: ${err.take(200)}")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val servers = parseIceServers(body)
            if (servers.isNullOrEmpty()) {
                EventLogger.log("TURN", "HF response had no ICE servers")
                return@withContext null
            }
            EventLogger.log("TURN", "Fetched ${servers.size} ICE server(s) from HF")
            servers
        } catch (t: Throwable) {
            EventLogger.log("TURN", "Failed to fetch from HF: ${t.message}")
            null
        }
    }

    /**
     * Serializes the ICE servers into the JSON string that the
     * webrtc-signaling binary expects for -ice_servers.
     */
    fun serializeForSignaling(servers: List<IceServer>): String {
        val arr = JSONArray()
        for (s in servers) {
            val obj = JSONObject()
            val urlsArr = JSONArray()
            s.urls.forEach { urlsArr.put(it) }
            obj.put("urls", urlsArr)
            if (!s.username.isNullOrEmpty()) obj.put("username", s.username)
            if (!s.credential.isNullOrEmpty()) obj.put("credential", s.credential)
            arr.put(obj)
        }
        return arr.toString()
    }

    // -------------------------------------------------------------------------

    private fun parseIceServers(json: String): List<IceServer>? {
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("iceServers")
                ?: root.optJSONArray("ice_servers")
                ?: return null

            val result = mutableListOf<IceServer>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val urlsNode = item.opt("urls")
                val urls = mutableListOf<String>()
                when (urlsNode) {
                    is JSONArray -> {
                        for (j in 0 until urlsNode.length()) {
                            urls.add(urlsNode.getString(j))
                        }
                    }
                    is String -> urls.add(urlsNode)
                }
                if (urls.isEmpty()) continue
                result.add(
                    IceServer(
                        urls = urls,
                        username = item.optString("username").takeIf { it.isNotEmpty() },
                        credential = item.optString("credential").takeIf { it.isNotEmpty() }
                    )
                )
            }
            result
        } catch (t: Throwable) {
            EventLogger.log("TURN", "Parse failed: ${t.message}")
            null
        }
    }

    data class IceServer(
        val urls: List<String>,
        val username: String?,
        val credential: String?
    )
}
