package com.jsonobject.claude.model

import com.jsonobject.claude.config.ClaudeCodeCliWrapperOptions

data class ClaudeCodeCliWrapperSession(
    val sessionId: String,
    val summary: String?,
    val lastMessage: String?,
    val lastMessageTime: String,
    val messageCount: Int,
    val workingDirectory: String?,
    val fileSizeBytes: Long? = null,
    val lastModifiedTime: Long? = null
)

data class ClaudeCodeCliWrapperSessionHistoryMessage(
    val uuid: String,
    val type: String,
    val content: String,
    val timestamp: String,
    val parentUuid: String?,
    val sessionId: String,
    val messageId: String? = null,
    val model: String? = null,
    val cost: Double? = null,
    val usage: Map<String, Any>? = null
)

data class ClaudeCodeCliWrapperResumeSessionRequest(
    val message: String,
    val customOptions: ClaudeCodeCliWrapperOptions? = null
)

data class ClaudeCodeCliWrapperSessionListResponse(
    val sessions: List<ClaudeCodeCliWrapperSession>,
    val totalCount: Int,
    val currentProject: String
)
