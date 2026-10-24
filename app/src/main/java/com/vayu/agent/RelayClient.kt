package com.vayu.agent

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One serialized control loop; no model calls and no autonomous task execution. */
object RelayClient {
    val isConnected = MutableStateFlow(false)
    val relayStatus = MutableStateFlow("Disconnected")
    private val gson = Gson()
    private var prefs: SharedPreferences? = null
    private var service: VayuService? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var activeCall: Call? = null
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()

    fun init(prefs: SharedPreferences, service: VayuService) {
        this.prefs = prefs
        this.service = service
    }
    fun getRelayUrl() = prefs?.getString("relay_url", "http://127.0.0.1:8787") ?: "http://127.0.0.1:8787"
    fun isEnabled() = prefs?.getBoolean("relay_enabled", false) ?: false
    fun disconnect() {
        job?.cancel(); job = null
        activeCall?.cancel(); activeCall = null
        isConnected.value = false
        relayStatus.value = "Disconnected"
    }
    fun connect() {
        disconnect()
        val p = prefs ?: return
        val svc = service ?: return
        val url = getRelayUrl().trimEnd('/')
        val token = p.getString("relay_token", "") ?: ""
        if (token.length < 32) { relayStatus.value = "Enter a connection token (32+ characters)"; return }
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost"))) {
            relayStatus.value = "Use HTTPS or local http://127.0.0.1:8787"; return
        }
        val id = p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("device_id", it).apply()
        }
        job = scope.launch {
            var failures = 0
            while (isActive && isEnabled()) {
                try {
                    relayStatus.value = "Connecting…"
                    request(url, token, "/api/register", mapOf("deviceId" to id,
                        "model" to "${Build.MANUFACTURER} ${Build.MODEL}", "androidVersion" to Build.VERSION.RELEASE))
                    isConnected.value = true; relayStatus.value = "Connected — direct controls"
                    while (isActive && isEnabled()) {
                        val poll = request(url, token, "/api/poll?deviceId=$id")
                        failures = 0
                        for (element in poll.getAsJsonArray("commands")) {
                            ensureActive()
                            val cmd = element.asJsonObject
                            val requestId = cmd.get("requestId").asString
                            val result = try {
                                withTimeout(25_000) { execute(svc, cmd) }
                            } catch (e: CancellationException) {
                                if (e !is TimeoutCancellationException) throw e
                                mapOf("success" to false, "message" to "Phone command timed out")
                            } catch (e: Exception) {
                                mapOf("success" to false, "message" to (e.message ?: "Phone command failed"))
                            }
                            ensureActive()
                            request(url, token, "/api/response", result + mapOf("deviceId" to id, "requestId" to requestId))
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    isConnected.value = false
                    relayStatus.value = "Disconnected: ${e.message}. Retrying…"
                    failures = (failures + 1).coerceAtMost(5)
                    delay((1000L shl failures).coerceAtMost(30_000))
                }
            }
        }
    }
    private suspend fun request(url: String, token: String, path: String, data: Map<String, Any?>? = null): JsonObject {
        currentCoroutineContext().ensureActive()
        val builder = Request.Builder().url(url + path).header("Authorization", "Bearer $token")
        if (data != null) builder.post(gson.toJson(data).toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        activeCall = call
        return try {
            call.execute().use {
                check(it.isSuccessful) { "Relay HTTP ${it.code}" }
                gson.fromJson(it.body?.string() ?: "{}", JsonObject::class.java)
            }
        } finally { if (activeCall === call) activeCall = null }
    }
    private suspend fun execute(svc: VayuService, cmd: JsonObject): Map<String, Any?> {
        fun str(key: String) = cmd.get(key)?.asString ?: ""
        val type = str("type")
        if (type == "status") return mapOf("success" to true, "mode" to "direct-control", "androidVersion" to Build.VERSION.RELEASE)
        if (type == "list_apps") {
            val pm = svc.packageManager
            return mapOf("success" to true, "apps" to pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { mapOf("name" to pm.getApplicationLabel(it).toString(), "packageName" to it.packageName) })
        }
        withContext(Dispatchers.Main) {
            when (type) {
                "tap", "long_press", "swipe", "type_text", "press_back", "press_home", "press_recents", "open_app" -> {
                    svc.executeDirectAction(decodeDirectAction(cmd))
                }
                "open_url" -> {
                    val uri = Uri.parse(str("url"))
                    require(uri.scheme in listOf("https", "http")) { "Only web URLs are allowed" }
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setPackage("com.android.chrome")
                    svc.startActivity(intent)
                    delay(1200)
                }
                "find_and_tap" -> {
                    val result = svc.findAndTapTextPublic(str("text"))
                    check(result.success) { result.message }
                }
                "type_in_field" -> {
                    val result = svc.typeInFieldPublic(str("text"), str("fieldHint"))
                    check(result.success) { result.message }
                }
                "screenshot", "screenshot_and_ui", "ui_tree" -> Unit
                else -> error("Unsupported command: $type")
            }
        }
        if (type == "ui_tree") return mapOf("success" to true, "uiTree" to withContext(Dispatchers.Main) { svc.getUiTreePublic() })
        val (b64, tree) = svc.autoCapture()
        return mapOf("success" to (type != "screenshot" || b64 != null), "type" to type,
            "base64" to (b64 ?: ""), "mimeType" to "image/jpeg", "uiTree" to (tree ?: ""),
            "screenWidth" to svc.resources.displayMetrics.widthPixels,
            "screenHeight" to svc.resources.displayMetrics.heightPixels,
            "captureError" to if (b64 == null) "Screenshot unavailable; inspect UI tree or retry" else null)
    }
}
