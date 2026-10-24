package com.vayu.agent

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
