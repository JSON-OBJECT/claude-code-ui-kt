package com.jsonobject.claude.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jsonobject.claude.model.ClaudeCodeCliWrapperSession
import com.jsonobject.claude.model.ClaudeCodeCliWrapperSessionHistoryMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class ClaudeCodeCliWrapperSessionService(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ClaudeCodeCliWrapperSessionService::class.java)
    
    companion object {
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }

    fun getAvailableSessions(): List<ClaudeCodeCliWrapperSession> {
        return try {
            val projectPath = getCurrentProjectPath()
            val sessionDir = getClaudeSessionDirectory(projectPath)
            
            if (!sessionDir.exists()) {
                logger.warn("Claude session directory does not exist: ${sessionDir.absolutePath}")
                return emptyList()
            }
            
            logger.info("Scanning Claude sessions in: ${sessionDir.absolutePath}")
            
            val sessionFiles = sessionDir.listFiles { file ->
                file.isFile && file.name.endsWith(".jsonl") && file.length() > 0
            } ?: return emptyList()
            
            val sessions = sessionFiles.mapNotNull { file ->
                try {
                    parseSessionMetadata(file)
                } catch (e: Exception) {
                    logger.warn("Failed to parse session file: ${file.name}", e)
                    null
                }
            }.sortedByDescending { it.lastModifiedTime }
            
            logger.info("Found ${sessions.size} Claude sessions")
            sessions
            
        } catch (e: Exception) {
            logger.error("Error retrieving Claude sessions", e)
            emptyList()
        }
    }

    fun getSessionHistory(sessionId: String): List<ClaudeCodeCliWrapperSessionHistoryMessage> {
        return try {
            val projectPath = getCurrentProjectPath()
            val sessionDir = getClaudeSessionDirectory(projectPath)
            val sessionFile = File(sessionDir, "$sessionId.jsonl")
            
            if (!sessionFile.exists()) {
                logger.warn("Session file not found: $sessionId")
                return emptyList()
            }
            
            logger.info("Reading session history: $sessionId")
            parseJsonlFile(sessionFile)
            
        } catch (e: Exception) {
            logger.error("Error reading session history for $sessionId", e)
            emptyList()
        }
    }

    private fun parseJsonlFile(sessionFile: File): List<ClaudeCodeCliWrapperSessionHistoryMessage> {
        val messages = mutableListOf<ClaudeCodeCliWrapperSessionHistoryMessage>()
        
        sessionFile.readLines().forEach { line ->
            if (line.trim().isEmpty()) return@forEach
            
            try {
                val jsonNode = objectMapper.readTree(line)
                val type = jsonNode.get("type")?.asText()
                
                // Skip summary entries as they are metadata, not conversation messages
                if (type == "summary") return@forEach
                
                // Process only user and assistant messages
                if (type == "user" || type == "assistant") {
                    val message = parseMessage(jsonNode, type)
                    if (message != null) {
                        messages.add(message)
                    }
                }
                
            } catch (e: Exception) {
                logger.debug("Failed to parse line in JSONL: $line", e)
            }
        }

        return messages.sortedBy { it.timestamp }
    }

    private fun parseMessage(jsonNode: JsonNode, type: String): ClaudeCodeCliWrapperSessionHistoryMessage? {
        return try {
            val uuid = jsonNode.get("uuid")?.asText() ?: return null
            val sessionId = jsonNode.get("sessionId")?.asText() ?: return null
            val timestamp = jsonNode.get("timestamp")?.asText() ?: return null
            val parentUuid = jsonNode.get("parentUuid")?.asText()
            
            val content = when (type) {
                "user" -> {
                    val messageNode = jsonNode.get("message")
                    messageNode?.get("content")?.asText() ?: "No content"
                }
                "assistant" -> {
                    val messageNode = jsonNode.get("message")
                    val contentArray = messageNode?.get("content")
                    
                    if (contentArray?.isArray == true && contentArray.size() > 0) {
                        val textContents = mutableListOf<String>()
                        for (i in 0 until contentArray.size()) {
                            val contentItem = contentArray.get(i)
                            when (contentItem?.get("type")?.asText()) {
                                "text" -> {
                                    contentItem.get("text")?.asText()?.let { textContents.add(it) }
                                }
                                "thinking" -> {
                                }
                            }
                        }
                        textContents.joinToString("\n\n")
                    } else {
                        "No content"
                    }
                }
                else -> "Unknown message type"
            }

            val messageNode = jsonNode.get("message")
            val model = messageNode?.get("model")?.asText()
            val cost = messageNode?.get("usage")?.get("total_cost_usd")?.asDouble()
            val usage = messageNode?.get("usage")?.let { usageNode ->
                objectMapper.convertValue(usageNode, Map::class.java) as? Map<String, Any>
            }

            ClaudeCodeCliWrapperSessionHistoryMessage(
                uuid = uuid,
                type = type,
                content = content,
                timestamp = timestamp,
                parentUuid = parentUuid,
                sessionId = sessionId,
                model = model,
                cost = cost,
                usage = usage
            )
            
        } catch (e: Exception) {
            logger.warn("Failed to parse message from JSON node", e)
            null
        }
    }

    private fun parseSessionMetadata(sessionFile: File): ClaudeCodeCliWrapperSession? {
        return try {
            val sessionId = sessionFile.nameWithoutExtension
            val lines = sessionFile.readLines()
            
            var summary: String? = null
            var lastMessage: String? = null
            var lastMessageTime: String? = null
            var workingDirectory: String? = null
            var messageCount = 0

            if (lines.isNotEmpty()) {
                try {
                    val firstLineJson = objectMapper.readTree(lines[0])
                    if (firstLineJson.get("type")?.asText() == "summary") {
                        summary = firstLineJson.get("summary")?.asText()
                    }
                } catch (e: Exception) {
                }
            }

            lines.forEach { line ->
                if (line.trim().isEmpty()) return@forEach
                
                try {
                    val jsonNode = objectMapper.readTree(line)
                    val type = jsonNode.get("type")?.asText()
                    
                    if (type == "user" || type == "assistant") {
                        messageCount++
                        lastMessageTime = jsonNode.get("timestamp")?.asText()
                        workingDirectory = jsonNode.get("cwd")?.asText()

                        lastMessage = when (type) {
                            "user" -> {
                                val content = jsonNode.get("message")?.get("content")?.asText()
                                "User: ${content?.take(100) ?: "No content"}"
                            }
                            "assistant" -> {
                                val messageNode = jsonNode.get("message")
                                val contentArray = messageNode?.get("content")
                                
                                if (contentArray?.isArray == true && contentArray.size() > 0) {
                                    val firstTextContent = contentArray.find { 
                                        it.get("type")?.asText() == "text" 
                                    }?.get("text")?.asText()
                                    "Claude: ${firstTextContent?.take(100) ?: "No content"}"
                                } else {
                                    "Claude: No content"
                                }
                            }
                            else -> "Unknown message"
                        }
                    }
                    
                } catch (e: Exception) {
                }
            }

            if (summary.isNullOrBlank()) {
                summary = when {
                    lastMessage?.contains("User:") == true -> {
                        lastMessage?.substringAfter("User: ")?.take(50) ?: "No title"
                    }
                    messageCount > 0 -> "Claude conversation ($messageCount messages)"
                    else -> "Empty session"
                }
            }
            
            val formattedTime = lastMessageTime?.let { timestamp ->
                try {
                    val instant = Instant.parse(timestamp)
                    DATE_TIME_FORMATTER.format(instant)
                } catch (e: Exception) {
                    timestamp
                }
            } ?: DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(sessionFile.lastModified()))

            ClaudeCodeCliWrapperSession(
                sessionId = sessionId,
                summary = summary,
                lastMessage = lastMessage,
                lastMessageTime = formattedTime,
                messageCount = messageCount,
                workingDirectory = workingDirectory,
                fileSizeBytes = sessionFile.length(),
                lastModifiedTime = sessionFile.lastModified()
            )
            
        } catch (e: Exception) {
            logger.warn("Failed to parse session metadata for ${sessionFile.name}", e)
            null
        }
    }

    fun getCurrentProjectPath(): String {
        return System.getProperty("user.dir")
    }

    private fun getClaudeSessionDirectory(projectPath: String): File {
        val userHome = System.getProperty("user.home")
        val normalizedPath = projectPath.replace("/", "-")
        val sessionDirPath = "$userHome/.claude/projects/$normalizedPath"
        return File(sessionDirPath)
    }

    fun sessionExists(sessionId: String): Boolean {
        val projectPath = getCurrentProjectPath()
        val sessionDir = getClaudeSessionDirectory(projectPath)
        val sessionFile = File(sessionDir, "$sessionId.jsonl")
        return sessionFile.exists() && sessionFile.length() > 0
    }
}
