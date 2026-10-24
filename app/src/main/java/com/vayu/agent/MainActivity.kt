package com.vayu.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local opt-in and an always available disconnect button. No API-key settings. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("vayu_settings", MODE_PRIVATE)
        // Remove previously stored model credentials on upgrade.
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("api_key_") || it.startsWith("model_") ||
                it in listOf("api_provider", "base_url_custom", "agent_memory", "last_task", "task_count") }.forEach { remove(it) }
        }.apply()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 18f; content.addView(this) }
        label("V.A.Y.U • Direct Phone Control").textSize = 26f
        label("Your connected MCP client makes decisions. This app only runs explicit phone commands.")
        val status = label("")
        label("Relay URL")
        val url = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("relay_url", "http://127.0.0.1:8787"))
        }; content.addView(url)
        label("Connection token (from Termux)")
        val token = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("relay_token", ""))
        }; content.addView(token)
        fun button(text: String, action: () -> Unit) {
            content.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        }
        button("1. Enable accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("2. Connect") {
            if (VayuService.instance == null) {
                Toast.makeText(this, "Enable V.A.Y.U in Accessibility first", Toast.LENGTH_LONG).show()
            } else if (token.text.length < 32) {
                token.error = "Use the connection token from Termux (32+ characters)"
            } else {
                prefs.edit().putString("relay_url", url.text.toString().trim().trimEnd('/'))
                    .putString("relay_token", token.text.toString().trim()).putBoolean("relay_enabled", true).apply()
                RelayClient.connect()
            }
        }
        button("Disconnect phone control") {
            prefs.edit().putBoolean("relay_enabled", false).apply()
            RelayClient.disconnect()
        }
        label("Keep the phone unlocked during testing. Disconnect here whenever you want to stop remote control.")
        setContentView(ScrollView(this).apply { addView(content) })
        lifecycleScope.launch {
            while (true) {
                status.text = if (VayuService.instance == null) "Accessibility is off" else RelayClient.relayStatus.value
                delay(500)
            }
        }
    }
}
