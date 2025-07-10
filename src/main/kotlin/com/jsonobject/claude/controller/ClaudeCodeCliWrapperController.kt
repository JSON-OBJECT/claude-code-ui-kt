package com.jsonobject.claude.controller

import com.jsonobject.claude.model.ClaudeCodeCliWrapperSessionListResponse
import com.jsonobject.claude.model.ClaudeCodeCliWrapperResumeSessionRequest
import com.jsonobject.claude.model.ClaudeCodeCliWrapperMessage
import com.jsonobject.claude.service.ClaudeCodeCliWrapperService
import com.jsonobject.claude.service.ClaudeCodeCliWrapperSessionService
import com.jsonobject.claude.config.ClaudeCodeCliWrapperOptions
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import jakarta.servlet.http.HttpServletResponse

@RestController
@RequestMapping("/claude")
@CrossOrigin(origins = ["*"])
class ClaudeCodeCliWrapperController(
    private val objectMapper: ObjectMapper,
    private val claudeCodeService: ClaudeCodeCliWrapperService,
    private val claudeSessionService: ClaudeCodeCliWrapperSessionService
) {
    private val logger = LoggerFactory.getLogger(ClaudeCodeCliWrapperController::class.java)

    private val activeEmitters = ConcurrentHashMap<String, SseEmitter>()
    private val claudeCodeSessions = ConcurrentHashMap<String, String>()
    
    @GetMapping("/start", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun startSession(
        @RequestParam prompt: String,
        @RequestParam(required = false) projectPath: String?,
        response: HttpServletResponse
    ): SseEmitter {
        val tempSseSessionId = UUID.randomUUID().toString()
        logger.info("Creating new Claude session: $tempSseSessionId")

        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Headers", "*")
        
        val emitter = SseEmitter(3600_000L)
        activeEmitters[tempSseSessionId] = emitter

        emitter.onCompletion { 
            logger.info("SSE connection completed for session: $tempSseSessionId")
            cleanup(tempSseSessionId)
        }
        emitter.onTimeout { 
            logger.warn("SSE connection timeout for session: $tempSseSessionId")
            cleanup(tempSseSessionId)
        }
        emitter.onError { throwable ->
            logger.error("SSE connection error for session: $tempSseSessionId", throwable)
            cleanup(tempSseSessionId)
        }

        try {
            emitter.send(
                SseEmitter.event()
                    .id("connection-$tempSseSessionId")
                    .name("connection")
                    .data(mapOf(
                        "status" to "connected",
                        "sessionId" to tempSseSessionId,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "SSE connection established successfully"
                    ))
            )
            logger.info("Connection confirmation sent for session: $tempSseSessionId")
        } catch (e: Exception) {
            logger.error("Failed to send connection confirmation for session: $tempSseSessionId", e)
            cleanup(tempSseSessionId)
            throw e
        }

        claudeCodeService.startSession(
            sseSessionId = tempSseSessionId,
            prompt = prompt,
            claudeCodeSessionId = null,
            isFirstMessage = true,
            projectPath = projectPath
        ) { message ->
            try {
                val newClaudeCodeSessionId = claudeCodeService.extractClaudeCodeCliWrapperSessionId(message)
                if (newClaudeCodeSessionId != null && !newClaudeCodeSessionId.isBlank()) {
                    claudeCodeSessions[tempSseSessionId] = newClaudeCodeSessionId
                }

                val json = objectMapper.writeValueAsString(message)
                emitter.send(
                    SseEmitter.event()
                        .id("msg-${UUID.randomUUID()}")
                        .name("claude-message")
                        .data(json)
                )
                logger.debug("Sent ${message.type} message for SSE session $tempSseSessionId")
            } catch (e: Exception) {
                logger.error("Error sending SSE message for session $tempSseSessionId", e)
                cleanup(tempSseSessionId)
            }
        }
        
        return emitter
    }
    
    @PostMapping("/send/{sessionId}")
    fun sendMessage(
        @PathVariable sessionId: String,
        @RequestBody request: Map<String, String>
    ): Map<String, Any> {
        val message = request["message"] ?: ""
        val claudeCodeSessionId = request["claudeCodeSessionId"]
        
        logger.info("===== sendMessage Debug =====")
        logger.info("Starting new Claude process for message: $message")
        logger.info("Received claudeCodeSessionId: [$claudeCodeSessionId]")
        logger.info("claudeCodeSessionId == null: ${claudeCodeSessionId == null}")
        logger.info("claudeCodeSessionId.isNullOrBlank(): ${claudeCodeSessionId.isNullOrBlank()}")
        logger.info("Will set isFirstMessage to: ${claudeCodeSessionId == null}")
        logger.info("Request body keys: ${request.keys}")
        logger.info("Full request body: $request")
        
        val emitter = activeEmitters[sessionId]
        if (emitter == null) {
            logger.error("Emitter not found for session: $sessionId")
            return mapOf(
                "success" to false,
                "error" to "Session not found",
                "sessionId" to sessionId
            )
        }

        val existingMappedClaudeCodeSessionId = claudeCodeSessions[sessionId]
        val finalClaudeCodeSessionId = claudeCodeSessionId ?: existingMappedClaudeCodeSessionId
        
        logger.info("claudeCodeSessions mapping for sessionId [$sessionId]: [$existingMappedClaudeCodeSessionId]")
        logger.info("Final claudeCodeSessionId to use: [$finalClaudeCodeSessionId]")
        logger.info("Will use resume: ${finalClaudeCodeSessionId != null}")
        
        // Resume vs 새 세션 로직 분기
        if (finalClaudeCodeSessionId != null && finalClaudeCodeSessionId.isNotBlank()) {
            // 기존 세션 Resume
            logger.info("🔄 Attempting to resume existing Claude Code session: ${finalClaudeCodeSessionId.substring(0, 8)}...")
            
            claudeCodeService.resumeSessionWithMessage(
                sseSessionId = sessionId,
                message = message,
                claudeCodeSessionId = finalClaudeCodeSessionId
            ) { claudeMessage ->
                try {
                    
                    // Resume 성공 시 새로운 Claude 세션 ID 추출 및 매핑 업데이트
                    val newClaudeCodeSessionId = claudeCodeService.extractClaudeCodeCliWrapperSessionId(claudeMessage)
                    if (newClaudeCodeSessionId != null && !newClaudeCodeSessionId.isBlank()) {
                        claudeCodeSessions[sessionId] = newClaudeCodeSessionId
                    }
                    
                    val json = objectMapper.writeValueAsString(claudeMessage)
                    emitter.send(
                        SseEmitter.event()
                            .id("msg-${UUID.randomUUID()}")
                            .name("claude-message")
                            .data(json)
                    )
                    logger.debug("Sent ${claudeMessage.type} resume message for session $sessionId")
                } catch (e: Exception) {
                    logger.error("Error sending SSE message for resume session $sessionId", e)
                    cleanup(sessionId)
                }
            }
        } else {
            // 새 세션 시작
            logger.info("🆕 Starting new Claude Code session for session $sessionId")
            
            claudeCodeService.startSession(
                sseSessionId = sessionId,
                prompt = message,
                claudeCodeSessionId = null,
                isFirstMessage = true,
                projectPath = null
            ) { claudeMessage ->
                try {
                    // 새 세션에서 Claude Code Session ID 추출 및 저장
                    val newClaudeCodeSessionId = claudeCodeService.extractClaudeCodeCliWrapperSessionId(claudeMessage)
                    if (newClaudeCodeSessionId != null && !newClaudeCodeSessionId.isBlank()) {
                        claudeCodeSessions[sessionId] = newClaudeCodeSessionId
                    }
                    
                    val json = objectMapper.writeValueAsString(claudeMessage)
                    emitter.send(
                        SseEmitter.event()
                            .id("msg-${UUID.randomUUID()}")
                            .name("claude-message")
                            .data(json)
                    )
                    logger.debug("Sent ${claudeMessage.type} new session message for session $sessionId")
                } catch (e: Exception) {
                    logger.error("Error sending SSE message for new session $sessionId", e)
                    cleanup(sessionId)
                }
            }
        }
        
        return mapOf(
            "success" to true,
            "sessionId" to sessionId,
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    @PostMapping("/stop/{sessionId}")
    fun stopSession(@PathVariable sessionId: String): Map<String, Any> {
        logger.info("Stopping session: $sessionId")

        claudeCodeService.killSession(sessionId)
        
        cleanup(sessionId)
        
        return mapOf(
            "success" to true,
            "sessionId" to sessionId,
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    @GetMapping("/active-sessions")
    fun getActiveSessions(): Map<String, Any> {
        return mapOf(
            "sessions" to claudeCodeService.getActiveSessions().toList(),
            "emitters" to activeEmitters.keys.toList(),
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    @GetMapping("/session/{sessionId}/status")
    fun getSessionStatus(@PathVariable sessionId: String): Map<String, Any> {
        val hasActiveProcess = claudeCodeService.getActiveSessions().contains(sessionId)
        val emitter = activeEmitters[sessionId]
        
        return mapOf(
            "sessionId" to sessionId,
            "sessionExists" to hasActiveProcess,
            "sessionActive" to hasActiveProcess,
            "emitterExists" to (emitter != null),
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * ClaudeCodeCliWrapper CLI에 저장된 모든 세션 목록을 조회합니다
     */
    @GetMapping("/sessions")
    fun getClaudeSessions(): ClaudeCodeCliWrapperSessionListResponse {
        logger.info("Retrieving ClaudeCodeCliWrapper session list")
        
        return try {
            val sessions = claudeSessionService.getAvailableSessions()
            val currentProject = claudeSessionService.getCurrentProjectPath()
            
            ClaudeCodeCliWrapperSessionListResponse(
                sessions = sessions,
                totalCount = sessions.size,
                currentProject = currentProject
            )
        } catch (e: Exception) {
            logger.error("Error retrieving ClaudeCodeCliWrapper sessions", e)
            ClaudeCodeCliWrapperSessionListResponse(
                sessions = emptyList(),
                totalCount = 0,
                currentProject = claudeSessionService.getCurrentProjectPath()
            )
        }
    }

    @GetMapping("/sessions/{sessionId}")
    fun getSessionHistory(@PathVariable sessionId: String): Map<String, Any> {
        logger.info("Retrieving session history: $sessionId")
        
        return try {
            if (!claudeSessionService.sessionExists(sessionId)) {
                return mapOf(
                    "success" to false,
                    "error" to "Session not found",
                    "sessionId" to sessionId
                )
            }
            
            val history = claudeSessionService.getSessionHistory(sessionId)
            
            mapOf(
                "success" to true,
                "sessionId" to sessionId,
                "messages" to history,
                "messageCount" to history.size,
                "timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.error("Error retrieving session history: $sessionId", e)
            mapOf(
                "success" to false,
                "error" to "Failed to retrieve session history: ${e.message}",
                "sessionId" to sessionId
            )
        }
    }

    @PostMapping("/sessions/{sessionId}/resume", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun resumeSession(
        @PathVariable sessionId: String,
        @RequestBody request: ClaudeCodeCliWrapperResumeSessionRequest,
        response: HttpServletResponse
    ): SseEmitter {
        logger.info("Resume session request: $sessionId")
        logger.info("Message: ${request.message}")

        if (!claudeSessionService.sessionExists(sessionId)) {
            logger.warn("Session $sessionId does not exist, will start new session")

            return startNewSessionForResume(sessionId, request.message, response)
        }

        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Headers", "*")

        val emitter = createNewEmitter(sessionId, response)
        val resumeOptions = ClaudeCodeCliWrapperOptions(resumeSessionId = sessionId)
        
        claudeCodeService.startSession(
            sseSessionId = sessionId,
            prompt = request.message,
            claudeCodeSessionId = sessionId,
            isFirstMessage = false,
            projectPath = null,
            customOptions = resumeOptions
        ) { message ->
            try {
                val json = objectMapper.writeValueAsString(message)
                emitter.send(
                    SseEmitter.event()
                        .id("msg-${UUID.randomUUID()}")
                        .name("claude-message")
                        .data(json)
                )
                logger.debug("Sent ${message.type} message for resumed session $sessionId")
            } catch (e: Exception) {
                logger.error("Error sending SSE message for resumed session $sessionId", e)
                cleanup(sessionId)
            }
        }
        
        return emitter
    }
    
    private fun startNewSessionForResume(
        sessionId: String,
        message: String,
        response: HttpServletResponse
    ): SseEmitter {
        logger.info("Starting new session for resume request: $sessionId")
        
        // Set SSE headers
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Headers", "*")
        
        val emitter = createNewEmitter(sessionId, response)

        claudeCodeService.startSession(
            sseSessionId = sessionId,
            prompt = message,
            claudeCodeSessionId = null,
            isFirstMessage = true,
            projectPath = null
        ) { claudeMessage ->
            try {
                val newClaudeCodeSessionId = claudeCodeService.extractClaudeCodeCliWrapperSessionId(claudeMessage)
                if (newClaudeCodeSessionId != null && !newClaudeCodeSessionId.isBlank()) {
                    claudeCodeSessions[sessionId] = newClaudeCodeSessionId
                }
                
                val json = objectMapper.writeValueAsString(claudeMessage)
                emitter.send(
                    SseEmitter.event()
                        .id("msg-${UUID.randomUUID()}")
                        .name("claude-message")
                        .data(json)
                )
                logger.debug("Sent ${claudeMessage.type} message for new resume session $sessionId")
            } catch (e: Exception) {
                logger.error("Error sending SSE message for new resume session $sessionId", e)
                cleanup(sessionId)
            }
        }
        
        return emitter
    }
    
    private fun createNewEmitter(sessionId: String, response: HttpServletResponse): SseEmitter {
        val emitter = SseEmitter(3600_000L)
        activeEmitters[sessionId] = emitter

        emitter.onCompletion { 
            logger.info("SSE connection completed for session: $sessionId")
            cleanup(sessionId)
        }
        emitter.onTimeout { 
            logger.warn("SSE connection timeout for session: $sessionId")
            cleanup(sessionId)
        }
        emitter.onError { throwable ->
            logger.error("SSE connection error for session: $sessionId", throwable)
            cleanup(sessionId)
        }

        try {
            emitter.send(
                SseEmitter.event()
                    .id("connection-$sessionId")
                    .name("connection")
                    .data(mapOf(
                        "status" to "connected",
                        "sessionId" to sessionId,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "SSE connection established for resume"
                    ))
            )
        } catch (e: Exception) {
            logger.error("Failed to send connection confirmation for session: $sessionId", e)
            cleanup(sessionId)
            throw e
        }
        
        return emitter
    }
    
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        return mapOf(
            "status" to "OK",
            "activeSessions" to claudeCodeService.getActiveSessions().size,
            "activeEmitters" to activeEmitters.size,
            "timestamp" to System.currentTimeMillis()
        )
    }
    

    private fun cleanup(sessionId: String) {
        logger.info("Cleaning up session: $sessionId")
        claudeCodeService.killSession(sessionId)
        claudeCodeSessions.remove(sessionId)
        activeEmitters.remove(sessionId)
    }
}
