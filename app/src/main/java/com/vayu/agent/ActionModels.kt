package com.vayu.agent

import com.google.gson.JsonObject

// ─── Agent Action (sent via MCP) ───
data class AgentAction(
    val action: String = "FAIL",
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = "",
    val direction: String = "",
    val app: String = "",
    val reason: String = "",
    val delay: Long = 0L,
    val duration: Long = 0L
)

// ─── Direct UI Selection Result ───
data class UiActionResult(
    val success: Boolean,
    val message: String = "",
    val x: Int = 0,
    val y: Int = 0
)

/** Decode the wire command once, preserving app and text as separate fields. */
fun decodeDirectAction(cmd: JsonObject): AgentAction {
    val type = cmd.get("type")?.asString ?: error("Missing command type")
    require(type in setOf("tap", "long_press", "swipe", "type_text", "press_back", "press_home", "press_recents", "open_app")) {
        "Unsupported direct command: $type"
    }
    val app = cmd.get("app")?.asString ?: ""
    require(type != "open_app" || app.isNotBlank()) { "App name or package is required" }
    return AgentAction(
        action = if (type == "type_text") "TYPE" else type.uppercase(),
        x = cmd.get("x")?.asInt ?: 0,
        y = cmd.get("y")?.asInt ?: 0,
        x2 = cmd.get("x2")?.asInt ?: 0,
        y2 = cmd.get("y2")?.asInt ?: 0,
        text = cmd.get("text")?.asString ?: "",
        app = app,
        duration = cmd.get("duration")?.asLong ?: 400L
    )
}
