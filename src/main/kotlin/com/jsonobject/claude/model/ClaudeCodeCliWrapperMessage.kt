package com.jsonobject.claude.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeCodeCliWrapperMessage(
    val type: String,
    val content: String? = null,
    val sseSessionId: String? = null,
    val claudeCodeSessionId: String? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val subtype: String? = null,
    val model: String? = null,
    val cost: Double? = null,
    val duration: Long? = null,
    val turns: Int? = null,
    val rawMessage: String? = null,
    val metadata: Map<String, Any>? = null,
    val toolUseId: String? = null,
    val toolName: String? = null
)

enum class MessageType(val value: String) {

    SESSION_CREATED("session-created"),
    CLAUDE_ERROR("claude-error"),
    CLAUDE_COMPLETE("claude-complete"),
    RAW_OUTPUT("raw-output"),
}
