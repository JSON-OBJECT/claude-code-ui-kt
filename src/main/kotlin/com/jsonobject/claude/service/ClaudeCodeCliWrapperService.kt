package com.jsonobject.claude.service

import com.fasterxml.jackson.databind.JsonNode
import com.jsonobject.claude.model.ClaudeCodeCliWrapperMessage
import com.jsonobject.claude.model.MessageType
import com.jsonobject.claude.config.ClaudeCodeCliWrapperOptions
import com.jsonobject.claude.config.ClaudeCodeCliWrapperCommandBuilder
import com.jsonobject.claude.config.ClaudeCodeCliWrapperPresets
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@Service
class ClaudeCodeCliWrapperService(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ClaudeCodeCliWrapperService::class.java)
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val sessionToolMappings = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    fun startSession(
        sseSessionId: String,
        prompt: String,
        claudeCodeSessionId: String? = null,
        isFirstMessage: Boolean = true,
        projectPath: String? = null,
        customOptions: ClaudeCodeCliWrapperOptions? = null,
        messageConsumer: Consumer<ClaudeCodeCliWrapperMessage>
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                logger.info("===== startSession Debug =====")
                logger.info("Session $sseSessionId: Starting Claude CLI directly")
                logger.info("Received prompt: $prompt")
                logger.info("Received claudeCodeSessionId: [$claudeCodeSessionId]")
                logger.info("Received isFirstMessage: $isFirstMessage")
                logger.info("Received projectPath: $projectPath")

                val effectiveOptions = customOptions ?: ClaudeCodeCliWrapperPresets.DEFAULT
                
                val commandArray = buildClaudeCodeCliWrapperCommand(prompt, claudeCodeSessionId, isFirstMessage, effectiveOptions)
                logger.info("=== Command Building Debug ===")
                logger.info("Starting ClaudeCodeCliWrapper session: $sseSessionId with command: ${commandArray.joinToString(" ")}")
                logger.info("Session $sseSessionId using options: max turns=${effectiveOptions.maxTurns}")
                logger.info("Resume session ID: ${effectiveOptions.resumeSessionId}")
                logger.info("Is first message: $isFirstMessage")
                logger.info("Claude Code session ID: $claudeCodeSessionId")
                logger.info("=== Permission Settings Debug ===")
                logger.info("Permission Mode: ${effectiveOptions.permissionMode ?: "null"}")
                logger.info("Dangerously Skip Permissions: ${effectiveOptions.dangerouslySkipPermissions}")
                logger.info("Permission Prompt Tool: ${effectiveOptions.permissionPromptTool ?: "null"}")
                val hasSkipPermissions = commandArray.contains("--dangerously-skip-permissions")
                val hasPermissionMode = commandArray.any { it.startsWith("--permission") }
                logger.info("Command contains --dangerously-skip-permissions: $hasSkipPermissions")
                logger.info("Command contains permission flags: $hasPermissionMode")

                val envArray = buildEnvironment(effectiveOptions)
                val workingDir = determineWorkingDirectory(projectPath)
                
                logger.info("Session $sseSessionId executing in directory: ${workingDir.absolutePath}")
                logger.info("Session $sseSessionId environment: ${envArray.filter { it.startsWith("TERM=") || it.startsWith("HOME=") }.joinToString(", ")}")
                
                val process = Runtime.getRuntime().exec(commandArray, envArray, workingDir)
                activeProcesses[sseSessionId] = process

                process.outputStream.close()
                logger.debug("Session $sseSessionId - stdin closed")

                messageConsumer.accept(
                    ClaudeCodeCliWrapperMessage(
                        type = MessageType.SESSION_CREATED.value,
                        content = "ClaudeCodeCliWrapper session started",
                        sseSessionId = sseSessionId
                    )
                )

                CompletableFuture.runAsync {
                    try {
                        process.errorStream.bufferedReader().use { errorReader ->
                            errorReader.lineSequence().forEach { errorLine ->
                                logger.warn("Session $sseSessionId stderr: $errorLine")
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug("Error reading stderr for session $sseSessionId", e)
                    }
                }

                logger.info("Starting to read ClaudeCodeCliWrapper stdout for session: $sseSessionId")
                logger.info("Session $sseSessionId: Starting stream processing")
                
                val stdoutFuture = CompletableFuture.runAsync {
                    try {
                        val inputStream = process.inputStream
                        val buffer = ByteArray(4096)
                        val lineBuffer = StringBuilder()
                        var lineCount = 0
                        
                        logger.debug("Session $sseSessionId - Starting byte-level reading")
                        
                        while (true) {
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) {
                                logger.debug("Session $sseSessionId - EOF reached")
                                break
                            }
                            
                            if (bytesRead > 0) {
                                val chunk = String(buffer, 0, bytesRead)
                                logger.debug("Session $sseSessionId - Read $bytesRead bytes: ${chunk.take(100)}...")
                                lineBuffer.append(chunk)

                                while (lineBuffer.contains('\n')) {
                                    val newlineIndex = lineBuffer.indexOf('\n')
                                    val line = lineBuffer.substring(0, newlineIndex).trim()
                                    lineBuffer.delete(0, newlineIndex + 1)
                                    
                                    if (line.isNotBlank()) {
                                        lineCount++
                                        logger.info("Session $sseSessionId - Line $lineCount: $line")
                                        val message = parseClaudeLine(line, sseSessionId)
                                        messageConsumer.accept(message)
                                    }
                                }
                            }
                        }

                        if (lineBuffer.isNotBlank()) {
                            val line = lineBuffer.toString().trim()
                            lineCount++
                            logger.info("Session $sseSessionId - Final line $lineCount: $line")
                            val message = parseClaudeLine(line, sseSessionId)
                            messageConsumer.accept(message)
                        }
                        
                        logger.info("Session $sseSessionId finished reading $lineCount lines")
                        
                    } catch (e: Exception) {
                        logger.error("Error reading stdout for session $sseSessionId", e)

                        messageConsumer.accept(
                            ClaudeCodeCliWrapperMessage(
                                type = MessageType.CLAUDE_ERROR.value,
                                error = "Stream processing error: ${e.message}",
                                sseSessionId = sseSessionId
                            )
                        )
                        throw e
                    }
                }

                try {
                    stdoutFuture.get(600, TimeUnit.SECONDS)
                    logger.info("Session $sseSessionId stdout reading completed")
                } catch (e: Exception) {
                    logger.warn("Stdout reading timeout for session $sseSessionId (after 10 minutes)", e)
                }

                val finished = process.waitFor(30, TimeUnit.SECONDS)
                
                if (!finished) {
                    logger.warn("Session $sseSessionId process still running, killing")
                    process.destroyForcibly()
                }
                
                val exitCode = if (finished) process.exitValue() else -1
                logger.info("ClaudeCodeCliWrapper process finished with exit code: $exitCode")

                cleanupSession(sseSessionId)
                
                messageConsumer.accept(
                    ClaudeCodeCliWrapperMessage(
                        type = MessageType.CLAUDE_COMPLETE.value,
                        content = "Process completed with exit code: $exitCode",
                        sseSessionId = sseSessionId
                    )
                )
                
            } catch (e: Exception) {
                logger.error("Error in Claude session $sseSessionId", e)
                val errorMessage = when {
                    e.message?.contains("No such file") == true -> "Claude CLI not found. Please ensure Claude Code is installed."
                    e.message?.contains("Permission denied") == true -> "Permission denied. Please check file permissions."
                    e.message?.contains("Invalid session") == true -> "Session expired. Please start a new conversation."
                    else -> "Claude CLI error: ${e.message ?: "Unknown error"}"
                }
                messageConsumer.accept(
                    ClaudeCodeCliWrapperMessage(
                        type = MessageType.CLAUDE_ERROR.value,
                        error = errorMessage,
                        sseSessionId = sseSessionId
                    )
                )
            } finally {
                activeProcesses.remove(sseSessionId)
                logger.debug("Session $sseSessionId: Resources cleaned up")
            }
        }
    }
    
    
    private fun parseClaudeLine(line: String, sessionId: String): ClaudeCodeCliWrapperMessage {
        return try {

            val jsonNode = objectMapper.readTree(line)
            val type = jsonNode.get("type")?.asText() ?: "unknown"
            val subtype = jsonNode.get("subtype")?.asText()
            val claudeCodeSessionId = jsonNode.get("session_id")?.asText()
            val model = jsonNode.get("model")?.asText()
            val cost = jsonNode.get("cost")?.asDouble()
            val duration = jsonNode.get("duration")?.asLong()
            val turns = jsonNode.get("turns")?.asInt()
            val content = when (type) {
                "assistant" -> {
                    extractAssistantContent(jsonNode, sessionId)
                }
                "user" -> {
                    extractUserContent(jsonNode, sessionId, line)
                }
                "result" -> {
                    extractResultContent(jsonNode, subtype)
                }
                "system" -> {
                    extractSystemContent(jsonNode, subtype)
                }
                else -> {
                    logger.debug("Unknown message type '$type' in session $sessionId")
                    line
                }
            }

            val metadata = extractMetadata(jsonNode)

            var toolUseId: String? = null
            var actualToolName: String? = null
            
            if (type == "user") {
                val message = jsonNode.get("message")
                val contentArray = message?.get("content")
                if (contentArray?.isArray == true) {
                    for (i in 0 until contentArray.size()) {
                        val contentItem = contentArray.get(i)
                        if (contentItem?.get("type")?.asText() == "tool_result") {
                            toolUseId = contentItem.get("tool_use_id")?.asText()
                            actualToolName = sessionToolMappings[sessionId]?.get(toolUseId)
                            break
                        }
                    }
                }
            }
            
            logger.debug("Parsed message type='$type', subtype='$subtype', toolUseId='$toolUseId', toolName='$actualToolName', content length=${content?.length ?: 0} for session $sessionId")
            
            ClaudeCodeCliWrapperMessage(
                type = type,
                content = content,
                sseSessionId = sessionId,
                claudeCodeSessionId = claudeCodeSessionId,
                subtype = subtype,
                model = model,
                cost = cost,
                duration = duration,
                turns = turns,
                rawMessage = line,
                metadata = metadata,
                toolUseId = toolUseId,
                toolName = actualToolName
            )
            
        } catch (e: Exception) {
            logger.warn("Failed to parse Claude line: $line", e)
            ClaudeCodeCliWrapperMessage(
                type = MessageType.RAW_OUTPUT.value,
                content = line,
                sseSessionId = sessionId,
                rawMessage = line
            )
        }
    }

    private fun extractToolNameFromResult(toolCallId: String, content: String): String {
        logger.debug("Extracting tool name for $toolCallId")

        return when {
            content.contains("Tool ran without output") || 
            content.contains("Command timed out") ||
            content.contains("exit code") -> "Bash"
            
            content.contains("→") && content.matches(Regex(".*\\d+→.*")) -> "Read"
            
            content.contains("File updated") || 
            content.contains("edited successfully") -> "Edit"
            
            content.contains("File written") || 
            content.contains("created successfully") -> "Write"
            
            content.contains("files found") || 
            content.contains("Found") -> "Glob"
            
            content.contains("matches found") -> "Grep"
            
            content.contains("Todos have been modified successfully") -> "TodoWrite"

            (toolCallId.startsWith("toolu_") || toolCallId.startsWith("call_")) && 
            (content.contains("search results") || content.contains("Title:") || content.contains("URL:")) -> 
                "mcp__brave-search__brave_web_search"
            
            (toolCallId.startsWith("toolu_") || toolCallId.startsWith("call_")) && 
            (content.contains("<!DOCTYPE html") || content.contains("Page loaded")) -> 
                "mcp__playwright__playwright_navigate"

            toolCallId.startsWith("toolu_") || toolCallId.startsWith("call_") -> "mcp_tool"
            
            else -> "unknown"
        }
    }

    private fun cleanupSession(sessionId: String) {
        activeProcesses.remove(sessionId)
        sessionToolMappings.remove(sessionId)
        logger.debug("Session $sessionId: Cleaned up process references and tool mappings")
    }

    private fun tryParseMcpResponse(text: String): String {
        return try {
            if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
                val jsonNode = objectMapper.readTree(text)

                if (jsonNode.has("results") && jsonNode.get("results").isArray) {
                    formatBraveSearchResults(jsonNode)
                } else if (jsonNode.has("query") || jsonNode.has("web")) {
                    text
                } else {
                    jsonNode.toPrettyString()
                }
            } else {
                text
            }
        } catch (e: Exception) {
            logger.debug("Failed to parse MCP response as JSON: ${e.message}")
            text
        }
    }

    private fun formatBraveSearchResults(jsonNode: JsonNode): String {
        val results = jsonNode.get("results")
        val query = jsonNode.get("query")?.asText() ?: "unknown"
        
        val formattedResults = mutableListOf<String>()
        formattedResults.add("Search Query: $query")
        
        if (results.isArray && results.size() > 0) {
            formattedResults.add("Results found: ${results.size()}")
            
            for (i in 0 until minOf(results.size(), 5)) { // 최대 5개 결과만 표시
                val result = results.get(i)
                val title = result.get("title")?.asText() ?: "No title"
                val url = result.get("url")?.asText() ?: "No URL"
                val snippet = result.get("snippet")?.asText() ?: result.get("description")?.asText() ?: "No description"
                
                formattedResults.add("${i + 1}. $title")
                formattedResults.add("   URL: $url")
                formattedResults.add("   ${snippet.take(200)}${if (snippet.length > 200) "..." else ""}")
            }
        } else {
            formattedResults.add("No results found")
        }
        
        return formattedResults.joinToString("\n")
    }

    private fun formatMcpResult(toolName: String, result: String): String {
        return when {
            toolName.contains("brave-search") -> {
                result
            }
            toolName.startsWith("mcp__") -> {
                if (result.length > 1000) {
                    "${result.take(1000)}...\n[Result truncated - ${result.length} total characters]"
                } else {
                    result
                }
            }
            else -> {
                result
            }
        }
    }

    private fun extractAssistantContent(jsonNode: JsonNode, sessionId: String): String? {
        val message = jsonNode.get("message") ?: return null
        val contentArray = message.get("content")
        
        if (contentArray?.isArray == true && contentArray.size() > 0) {
            val contents = mutableListOf<String>()
            
            for (i in 0 until contentArray.size()) {
                val contentItem = contentArray.get(i)
                val contentType = contentItem?.get("type")?.asText()
                
                when (contentType) {
                    "text" -> {
                        contentItem.get("text")?.asText()?.let { contents.add(it) }
                    }
                    "tool_use" -> {
                        val toolCallId = contentItem.get("id")?.asText()
                        val toolName = contentItem.get("name")?.asText() ?: "unknown_tool"

                        if (toolCallId != null && toolName != "unknown_tool") {
                            val sessionMappings = sessionToolMappings.getOrPut(sessionId) { 
                                ConcurrentHashMap<String, String>() 
                            }
                            sessionMappings[toolCallId] = toolName
                            logger.info("Session $sessionId: Tool mapping stored - $toolCallId -> $toolName")
                        }
                        
                        contents.add("[Tool used: $toolName]")
                    }
                    else -> {
                        logger.debug("Unknown assistant content type: $contentType")
                    }
                }
            }
            
            return if (contents.isNotEmpty()) contents.joinToString("\n") else null
        }
        
        return null
    }
    
    private fun extractUserContent(jsonNode: JsonNode, sessionId: String, rawMessage: String): String? {
        val message = jsonNode.get("message") ?: return null
        val contentArray = message.get("content")
        
        if (contentArray?.isArray == true && contentArray.size() > 0) {
            val contents = mutableListOf<String>()
            
            for (i in 0 until contentArray.size()) {
                val contentItem = contentArray.get(i)
                val contentType = contentItem?.get("type")?.asText()
                
                when (contentType) {
                    "text" -> {
                        contentItem.get("text")?.asText()?.let { contents.add(it) }
                    }
                    "tool_result" -> {
                        val toolUseId = contentItem.get("tool_use_id")?.asText()
                        
                        val contentArray = contentItem.get("content")
                        val resultTexts = mutableListOf<String>()

                        if (contentArray?.isArray == true) {
                            for (i in 0 until contentArray.size()) {
                                val contentObj = contentArray.get(i)
                                when (contentObj?.get("type")?.asText()) {
                                    "text" -> {
                                        contentObj.get("text")?.asText()?.let { text ->
                                            val parsedText = tryParseMcpResponse(text)
                                            resultTexts.add(parsedText)
                                        }
                                    }
                                    "json" -> {
                                        contentObj.toString().let { resultTexts.add(it) }
                                    }
                                    else -> {
                                        contentObj?.toString()?.let { resultTexts.add(it) }
                                    }
                                }
                            }
                        } else {
                            contentItem.get("content")?.asText()?.let { text ->
                                val parsedText = tryParseMcpResponse(text)
                                resultTexts.add(parsedText)
                            }
                        }
                        
                        val result = if (resultTexts.isNotEmpty()) resultTexts.joinToString("\n") else "No result"
                        val toolName = if (toolUseId != null) {
                            sessionToolMappings[sessionId]?.get(toolUseId) 
                                ?: extractToolNameFromResult(toolUseId, result)
                        } else {
                            extractToolNameFromResult("", result)
                        }
                        
                        logger.info("Session $sessionId: Tool result matched - ${toolUseId ?: "null"} -> $toolName")
                        
                        val formattedResult = formatMcpResult(toolName, result)
                        contents.add("[Tool result for $toolName: $formattedResult]")
                    }
                    else -> {
                        logger.debug("Unknown user content type: $contentType")
                    }
                }
            }
            
            return if (contents.isNotEmpty()) contents.joinToString("\n") else null
        }
        
        return null
    }
    
    private fun extractResultContent(jsonNode: JsonNode, subtype: String?): String? {
        return when (subtype) {
            "success" -> {
                val cost = jsonNode.get("cost")?.asDouble()
                val duration = jsonNode.get("duration")?.asLong()
                val turns = jsonNode.get("turns")?.asInt()
                
                buildString {
                    append("Session completed successfully")
                    cost?.let { append(", cost: $it") }
                    duration?.let { append(", duration: ${it}ms") }
                    turns?.let { append(", turns: $it") }
                }
            }
            "error" -> {
                val error = jsonNode.get("error")?.asText()
                val message = jsonNode.get("message")?.asText()
                "Error: ${error ?: message ?: "Unknown error"}"
            }
            else -> {
                jsonNode.get("result")?.asText() ?: "Result: $subtype"
            }
        }
    }
    
    private fun extractSystemContent(jsonNode: JsonNode, subtype: String?): String? {
        return when (subtype) {
            "init" -> {
                val model = jsonNode.get("model")?.asText()
                val workingDir = jsonNode.get("working_directory")?.asText()
                val tools = jsonNode.get("available_tools")?.let { toolsNode ->
                    if (toolsNode.isArray) {
                        (0 until toolsNode.size()).joinToString(", ") { toolsNode.get(it).asText() }
                    } else null
                }
                
                buildString {
                    append("System initialized")
                    model?.let { append(", model: $it") }
                    workingDir?.let { append(", working directory: $it") }
                    tools?.let { append(", tools: $it") }
                }
            }
            else -> {
                jsonNode.get("model")?.asText() ?: "System message: $subtype"
            }
        }
    }
    
    private fun extractMetadata(jsonNode: JsonNode): Map<String, Any>? {
        val metadata = mutableMapOf<String, Any>()

        jsonNode.get("working_directory")?.asText()?.let { metadata["working_directory"] = it }
        jsonNode.get("available_tools")?.let { toolsNode ->
            if (toolsNode.isArray) {
                val tools = (0 until toolsNode.size()).map { toolsNode.get(it).asText() }
                metadata["available_tools"] = tools
            }
        }
        jsonNode.get("api_key_source")?.asText()?.let { metadata["api_key_source"] = it }
        
        return metadata.ifEmpty { null }
    }
    
    private fun buildClaudeCodeCliWrapperCommand(
        prompt: String,
        claudeCodeSessionId: String? = null,
        isFirstMessage: Boolean = true,
        customOptions: ClaudeCodeCliWrapperOptions? = null
    ): Array<String> {
        val effectiveResumeSessionId = if (!isFirstMessage && !claudeCodeSessionId.isNullOrBlank()) {
            claudeCodeSessionId
        } else {
            null
        }

        val options = if (customOptions != null) {
            customOptions.copy(resumeSessionId = effectiveResumeSessionId)
        } else {
            ClaudeCodeCliWrapperPresets.DEFAULT.copy(resumeSessionId = effectiveResumeSessionId)
        }
        
        val commandBuilder = ClaudeCodeCliWrapperCommandBuilder()
        val commands = commandBuilder.buildCommand(prompt, options)
        
        // 로깅 개선
        logger.info("===== buildClaudeCodeCliWrapperCommand Debug =====")
        logger.info("Input claudeCodeSessionId: [${claudeCodeSessionId ?: "null"}]")
        logger.info("Input isFirstMessage: $isFirstMessage")
        logger.info("claudeCodeSessionId.isNullOrBlank(): ${claudeCodeSessionId.isNullOrBlank()}")
        logger.info("!isFirstMessage: ${!isFirstMessage}")
        logger.info("Resume condition (!isFirstMessage && !claudeCodeSessionId.isNullOrBlank()): ${!isFirstMessage && !claudeCodeSessionId.isNullOrBlank()}")
        logger.info("Calculated effectiveResumeSessionId: [${effectiveResumeSessionId ?: "null"}]")
        logger.info("Final options.resumeSessionId: [${options.resumeSessionId ?: "null"}]")
        logger.info("Generated command: ${commands.joinToString(" ")}")
        
        when {
            options.resumeSessionId != null -> {
                logger.info("✅ Resuming Claude session: ${options.resumeSessionId}")
                logger.info("Resume command includes --model: ${commands.contains("--model")}")
                logger.info("Resume command includes --print: ${commands.contains("--print")}")
            }
            options.continueSession -> {
                logger.info("Continuing recent Claude session")
            }
            isFirstMessage -> {
                logger.info("Starting new Claude session with model: ${options.model}")
            }
            else -> {
                logger.info("⚠️ Fallback: Starting new session (no resume available)")
            }
        }
        
        return commands
    }
    
    fun extractClaudeCodeCliWrapperSessionId(message: ClaudeCodeCliWrapperMessage): String? {
        logger.debug("=== 세션 ID 추출 시도 ====")
        logger.debug("메시지 타입: ${message.type}")

        message.claudeCodeSessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
            if (isValidSessionId(sessionId)) {
                logger.debug("✅ 방법 1 성공: claudeCodeSessionId = $sessionId")
                return sessionId
            }
        }

        message.sseSessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
            if (isValidSessionId(sessionId)) {
                logger.debug("✅ 방법 2 성공: sseSessionId = $sessionId")
                return sessionId
            }
        }

        message.rawMessage?.let { rawJson ->
            extractSessionIdFromJson(rawJson)?.let { sessionId ->
                logger.debug("✅ 방법 3 성공: rawMessage JSON = $sessionId")
                return sessionId
            }
        }

        message.content?.let { content ->
            extractSessionIdFromText(content)?.let { sessionId ->
                logger.debug("✅ 방법 4 성공: content 정규식 = $sessionId")
                return sessionId
            }
        }

        try {
            val messageJson = objectMapper.writeValueAsString(message)
            extractSessionIdFromText(messageJson)?.let { sessionId ->
                logger.debug("✅ 방법 5 성공: 전체 JSON = $sessionId")
                return sessionId
            }
        } catch (e: Exception) {
            logger.debug("❌ 방법 5 실패: ${e.message}")
        }
        
        logger.debug("⚠️ 모든 방법 실패: 세션 ID를 찾을 수 없음")
        return null
    }

    private fun extractSessionIdFromJson(jsonString: String): String? {
        return try {
            val jsonNode = objectMapper.readTree(jsonString)

            val sessionFields = listOf("session_id", "sessionId", "claude_session_id", "claudeCodeSessionId")
            
            for (field in sessionFields) {
                jsonNode.get(field)?.asText()?.takeIf { it.isNotBlank() && isValidSessionId(it) }?.let {
                    return it
                }
            }
            
            null
        } catch (e: Exception) {
            logger.debug("JSON 파싱 실패: ${e.message}")
            null
        }
    }

    private fun extractSessionIdFromText(text: String): String? {
        val uuidPattern = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        val regex = uuidPattern.toRegex(RegexOption.IGNORE_CASE)
        
        return regex.find(text)?.value?.let { uuid ->
            if (isValidSessionId(uuid)) uuid else null
        }
    }

    private fun isValidSessionId(sessionId: String): Boolean {
        return sessionId.isNotBlank() && 
               sessionId.length == 36 && 
               sessionId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}".toRegex(RegexOption.IGNORE_CASE))
    }
    
    private fun buildEnvironment(customOptions: ClaudeCodeCliWrapperOptions? = null): Array<String> {
        val options = customOptions ?: ClaudeCodeCliWrapperPresets.DEFAULT
        val commandBuilder = ClaudeCodeCliWrapperCommandBuilder()
        return commandBuilder.buildEnvironment(options)
    }
    
    private fun determineWorkingDirectory(projectPath: String?): File {
        return when {
            !projectPath.isNullOrBlank() -> {
                val projectDir = File(projectPath)
                if (projectDir.exists() && projectDir.isDirectory && projectDir.canRead()) {
                    logger.info("Using specified project directory: ${projectDir.absolutePath}")
                    projectDir
                } else {
                    logger.warn("Invalid project path '$projectPath', falling back to current directory")
                    File(System.getProperty("user.dir"))
                }
            }
            else -> {
                val currentDir = File(System.getProperty("user.dir"))
                logger.info("Using current directory: ${currentDir.absolutePath}")
                currentDir
            }
        }
    }
    
    fun killSession(sessionId: String): Boolean {
        return activeProcesses[sessionId]?.let { process ->
            process.destroyForcibly()
            activeProcesses.remove(sessionId)
            true
        } ?: false
    }
    
    fun getActiveSessions(): Set<String> = activeProcesses.keys.toSet()



    /**
     * 기존 Claude Code CLI 세션에 메시지를 전송하여 resume 기능을 수행합니다.
     */
    fun resumeSessionWithMessage(
        sseSessionId: String,
        message: String,
        claudeCodeSessionId: String,
        messageConsumer: Consumer<ClaudeCodeCliWrapperMessage>
    ): CompletableFuture<Void> {
        return startSession(
            sseSessionId = sseSessionId,
            prompt = message,
            claudeCodeSessionId = claudeCodeSessionId,
            isFirstMessage = false,
            projectPath = null,
            customOptions = null,
            messageConsumer = messageConsumer
        )
    }
    
    
    
}
