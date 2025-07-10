# Claude Code UI for Kotlin - Technical Architecture & Development Guidelines

## 🏗️ Core Architecture Overview

### High-Level Architecture (Kotlin + Spring Boot + SSE)

```
┌─────────────────────────────────────────────────────────────────┐
│  Frontend: Single Session ID Management (SSE Pattern)           │
│      ↓ SSE Connection (/claude/start, /claude/send)             │
│  Backend: Simple Mapping (SSE_ID → Claude_ID) + Message Model   │
│      ↓ Direct CLI Execution                                     │
│  Claude Code CLI: --resume [ID] or --model sonnet               │
│      ↓ stream-json → ClaudeCodeCliWrapperMessage                │
│  Real-time Response Streaming                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Design Philosophy

This project was inspired by **Claude Code UI** project analysis, implementing the **real-time streaming architecture** using Kotlin + Spring Boot:

1. **Claude Code CLI Process Execution** - `ProcessBuilder` executes Claude Code CLI
2. **JSON Lines Streaming** - Line-by-line JSON message reception from stdout  
3. **Real-time Parsing & Delivery** - Parse each message and deliver via SSE to client
4. **Virtual Thread Utilization** - High-performance processing of blocking I/O

---

## 🚀 Key Implementation Requirements

### Critical System Requirements

- **Backend/Frontend Changes**: When changes occur, forcibly terminate port 8080 and restart before verification
- **Session Continuity**: When sending chat messages from the frontend, backend must use `--resume` command to provide session context to Claude Code CLI
- **Display Modes**: Frontend must distinguish between **[Debug]** mode (raw JSON SSE messages) and **[Normal]** mode (user-friendly processed display)
- **Tool Notification**: In [Normal] mode, all Tool `used` and `result` notifications must be pushed as single SSE messages from Claude Code
- **Tool Name Resolution**: In [Normal] mode, all Tool `result` should not display as "unknown". Especially Bash, TodoWrite, Playwright etc. must display correct tool names
- **UI Styling**: Frontend should match ChatGPT web version's font, font size, line spacing as closely as possible. Avoid excessively small/large text or wide line spacing

---

## 📡 SSE vs WebSocket Architecture Decision

### Why SSE Over WebSocket?

**SSE (Server-Sent Events) Advantages:**
- **Simpler Implementation** - Built-in browser reconnection
- **HTTP/2 Multiplexing** - Better performance over HTTP/2
- **Unidirectional Simplicity** - Perfect for Claude Code CLI streaming use case
- **Automatic Reconnection** - Browser handles connection recovery
- **Easier Debugging** - Standard HTTP requests/responses

**Implementation Pattern:**
```kotlin
// SSE Controller Pattern
@GetMapping("/claude/start", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun startSession(
    @RequestParam prompt: String,
    response: HttpServletResponse
): SseEmitter {
    val emitter = SseEmitter(3600_000L)
    
    // Configure SSE headers
    response.setHeader("Cache-Control", "no-cache")
    response.setHeader("Connection", "keep-alive")
    response.setHeader("X-Accel-Buffering", "no")
    
    // Start Claude Code CLI process
    claudeCodeService.startClaudeCodeCliWrapperSession(sessionId, prompt) { message ->
        emitter.send(
            SseEmitter.event()
                .id("msg-${UUID.randomUUID()}")
                .name("claude-message")
                .data(objectMapper.writeValueAsString(message))
        )
    }
    
    return emitter
}
```

---

## 🔄 Session Management Architecture

### Session ID Mapping Strategy

**Frontend Session Management:**
- Single session ID manages entire conversation flow
- SSE connection reuse handled automatically by backend
- Session ID consistency ensures reconnection works seamlessly

**Backend Session Tracking:**
```kotlin
private val activeEmitters = ConcurrentHashMap<String, SseEmitter>()
private val claudeSessions = ConcurrentHashMap<String, String>()

// Session ID remapping for Claude Code CLI integration
if (claudeSessionId != null && claudeSessionId != tempSessionId) {
    activeEmitters.remove(tempSessionId)
    activeEmitters[claudeSessionId] = emitter
    claudeSessions[claudeSessionId] = claudeSessionId
}
```

### Resume vs New Session Logic

**Command Building Strategy:**
```kotlin
private fun buildClaudeCodeCliWrapperCommand(
    prompt: String,
    claudeSessionId: String? = null,
    isFirstMessage: Boolean = true,
    customOptions: ClaudeCodeCliWrapperOptions? = null
): Array<String> {
    val effectiveResumeSessionId = if (!isFirstMessage && !claudeSessionId.isNullOrBlank()) {
        claudeSessionId
    } else {
        null
    }
    
    val commands = when {
        effectiveResumeSessionId != null -> {
            // Resume existing session
            arrayOf("claude", "--resume", effectiveResumeSessionId, 
                   "--output-format", "stream-json", "--verbose")
        }
        isFirstMessage -> {
            // Start new session
            arrayOf("claude", "--print", prompt, "--output-format", "stream-json", 
                   "--verbose", "--model", "sonnet")
        }
        else -> {
            // Fallback: start new session
            arrayOf("claude", "--print", prompt, "--output-format", "stream-json", 
                   "--verbose", "--model", "sonnet")
        }
    }
    
    return commands
}
```

---

## 🛠️ Claude Code CLI Integration Patterns

### Stream JSON Processing

**Real-time stdout Processing:**
```kotlin
CompletableFuture.runAsync {
    try {
        val inputStream = process.inputStream
        val buffer = ByteArray(4096)
        val lineBuffer = StringBuilder()
        
        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break
            
            if (bytesRead > 0) {
                val chunk = String(buffer, 0, bytesRead)
                lineBuffer.append(chunk)

                while (lineBuffer.contains('\n')) {
                    val newlineIndex = lineBuffer.indexOf('\n')
                    val line = lineBuffer.substring(0, newlineIndex).trim()
                    lineBuffer.delete(0, newlineIndex + 1)
                    
                    if (line.isNotBlank()) {
                        val message = parseClaudeLine(line, sessionId)
                        messageConsumer.accept(message)
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.error("Error reading stdout for session $sessionId", e)
    }
}
```

### Message Parsing Strategy

**JSON Message Processing:**
```kotlin
private fun parseClaudeLine(line: String, sessionId: String): ClaudeCodeCliWrapperMessage {
    return try {
        val jsonNode = objectMapper.readTree(line)
        val type = jsonNode.get("type")?.asText() ?: "unknown"
        val subtype = jsonNode.get("subtype")?.asText()
        val claudeSessionId = jsonNode.get("session_id")?.asText()
        
        val content = when (type) {
            "assistant" -> extractAssistantContent(jsonNode, sessionId)
            "user" -> extractUserContent(jsonNode, sessionId, line)
            "result" -> extractResultContent(jsonNode, subtype)
            "system" -> extractSystemContent(jsonNode, subtype)
            else -> line
        }
        
        ClaudeCodeCliWrapperMessage(
            type = type,
            content = content,
            sessionId = sessionId,
            claudeSessionId = claudeSessionId,
            subtype = subtype,
            rawMessage = line
        )
    } catch (e: Exception) {
        // Fallback to raw message
        ClaudeCodeCliWrapperMessage(
            type = MessageType.RAW_OUTPUT.value,
            content = line,
            sessionId = sessionId,
            rawMessage = line
        )
    }
}
```

---

## 🎨 Frontend Architecture Guidelines

### Message Display Modes

**Debug Mode vs Normal Mode:**
```javascript
function addMessage(messageObj, eventType = 'claude-message', isSentByUser = false) {
    const safeIsDebugMode = (typeof isDebugMode !== 'undefined') ? isDebugMode : false;
    
    if (safeIsDebugMode) {
        addDebugMessage(messageObj, eventType, isSentByUser);
    } else {
        addNormalMessage(messageObj, eventType, isSentByUser);
    }
}

function shouldShowInNormalMode(messageObj, eventType) {
    const type = messageObj.type || eventType;
    const allowedTypes = ['assistant', 'user', 'user_input', 'system'];
    
    // Filter out system initialization messages in normal mode
    if (type === 'system' && messageObj.content) {
        const content = messageObj.content.toLowerCase();
        if (content.includes('system initialized') || 
            content.includes('model:') || 
            content.match(/model.*claude/)) {
            return false;
        }
    }
    
    const hasContent = messageObj.content && messageObj.content.trim();
    return allowedTypes.includes(type) && hasContent;
}
```

### Tool Result Processing

**Tool Name Resolution:**
```javascript
// Tool name extraction from result content
function extractToolNameFromResult(toolCallId, content) {
    return when {
        content.contains("Tool ran without output") || 
        content.contains("exit code") -> "Bash"
        
        content.contains("→") && content.matches(Regex(".*\\d+→.*")) -> "Read"
        
        content.contains("File updated") || 
        content.contains("edited successfully") -> "Edit"
        
        content.contains("File written") || 
        content.contains("created successfully") -> "Write"
        
        content.contains("Todos have been modified successfully") -> "TodoWrite"
        
        else -> "unknown"
    }
}

// Tool result rendering with specialized UI
function renderToolResult(toolName, content) {
    const uniqueId = generateUniqueId();
    const cleanToolName = toolName.replace(/^mcp__[^_]+__/, '');
    
    switch (cleanToolName.toLowerCase()) {
        case 'edit':
        case 'multiedit':
            return renderEditToolResult(cleanToolName, content, uniqueId);
        case 'write':
            return renderWriteToolResult(cleanToolName, content, uniqueId);
        case 'bash':
            return renderBashToolResult(cleanToolName, content, uniqueId);
        case 'read':
            return renderReadToolResult(cleanToolName, content, uniqueId);
        default:
            return renderDefaultToolResult(cleanToolName, content, uniqueId);
    }
}
```

### Session Management Frontend

**Session State Management:**
```javascript
// Session persistence
function saveSession(sessionId) {
    const sessionData = {
        sessionId: sessionId,
        timestamp: Date.now(),
        lastActivity: Date.now()
    };
    localStorage.setItem('claudeSession', JSON.stringify(sessionData));
}

function loadSession() {
    try {
        const stored = localStorage.getItem('claudeSession');
        if (stored) {
            const sessionData = JSON.parse(stored);
            if (Date.now() - sessionData.timestamp < 3600000) { // 1 hour expiry
                currentSessionId = sessionData.sessionId;
                return sessionData;
            } else {
                clearSession();
            }
        }
    } catch (e) {
        clearSession();
    }
    return null;
}
```

---

## ⚡ Performance Optimization Guidelines

### JVM 21 Virtual Thread Configuration

**Spring Boot Configuration:**
```yaml
spring:
  threads:
    virtual:
      enabled: true  # Enable virtual threads
  mvc:
    async:
      request-timeout: 300000  # 5 minutes timeout for SSE
```

**Virtual Thread Usage Patterns:**
```kotlin
// Async processing with virtual threads
CompletableFuture.runAsync {
    // Long-running Claude Code CLI process
    processClaudeCommand(sessionId, prompt)
}.thenApply { result ->
    // Post-processing on virtual thread
    processResult(result)
}

// No need for reactive programming complexity
// Virtual threads handle blocking I/O efficiently
```

### Memory Management

**Process Cleanup:**
```kotlin
private fun cleanupSession(sessionId: String) {
    activeProcesses.remove(sessionId)
    sessionToolMappings.remove(sessionId)
    
    // Force garbage collection for large sessions
    if (activeProcesses.size % 100 == 0) {
        System.gc()
    }
}

// Process timeout handling
val finished = process.waitFor(30, TimeUnit.SECONDS)
if (!finished) {
    logger.warn("Session $sessionId process still running, killing")
    process.destroyForcibly()
}
```

### Frontend Performance

**Mobile Optimization:**
```javascript
// Battery saving for mobile devices
const optimizeForMobile = () => {
    const isMobile = window.innerWidth < 768;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    
    if (isMobile || reducedMotion) {
        // Disable animations
        document.documentElement.style.setProperty('--animation-duration', '0ms');
        
        // Reduce update frequency
        if (window.sessionCountInterval) {
            clearInterval(window.sessionCountInterval);
        }
        window.sessionCountInterval = setInterval(updateSessionCount, 
                                                isMobile ? 10000 : 5000);
    }
};
```

---

## 🧪 Testing Strategies

### Backend Testing

**Service Layer Testing:**
```kotlin
@Test
fun `should handle Claude Code CLI session creation`() {
    // Given
    val sessionId = "test-session-123"
    val prompt = "Hello Claude"
    val messages = mutableListOf<ClaudeCodeCliWrapperMessage>()
    
    // When
    claudeService.startClaudeCodeCliWrapperSession(
        sessionId = sessionId,
        prompt = prompt,
        claudeSessionId = null,
        isFirstMessage = true
    ) { message ->
        messages.add(message)
    }.get(10, TimeUnit.SECONDS)
    
    // Then
    assertThat(messages).isNotEmpty()
    assertThat(messages.first().type).isEqualTo("session-created")
}

@Test  
fun `should parse Claude Code CLI stream-json output correctly`() {
    // Given
    val jsonLine = """{"type":"assistant","message":{"content":"Hello!"}}"""
    
    // When
    val result = claudeService.parseClaudeLine(jsonLine, "test-session")
    
    // Then
    assertThat(result.type).isEqualTo("assistant")
    assertThat(result.content).isEqualTo("Hello!")
}
```

### Frontend Testing

**SSE Connection Testing:**
```javascript
// Mock SSE for testing
class MockEventSource {
    constructor(url) {
        this.url = url;
        this.readyState = EventSource.CONNECTING;
        setTimeout(() => {
            this.readyState = EventSource.OPEN;
            this.onopen && this.onopen();
        }, 100);
    }
    
    simulateMessage(data) {
        const event = { data: JSON.stringify(data) };
        this.onmessage && this.onmessage(event);
    }
    
    close() {
        this.readyState = EventSource.CLOSED;
        this.onclose && this.onclose();
    }
}

// Test message processing
describe('Message Processing', () => {
    test('should handle tool result messages correctly', () => {
        const toolMessage = {
            type: 'user',
            content: '[Tool result for Read: file content here]',
            toolName: 'Read',
            toolUseId: 'tool-123'
        };
        
        addMessage(toolMessage, 'claude-message', false);
        
        // Verify tool result is rendered correctly
        const toolElements = document.querySelectorAll('.tool-result-container');
        expect(toolElements.length).toBeGreaterThan(0);
    });
});
```

---

## 🔧 Development Guidelines

### Project Structure

```
src/main/kotlin/com/jsonobject/claude/
├── ClaudeCodeCliWrapperApplication.kt     # Main application
├── controller/
│   └── ClaudeCodeCliWrapperController.kt  # SSE endpoints
├── service/
│   ├── ClaudeCodeCliWrapperService.kt     # Core CLI integration
│   └── ClaudeCodeCliWrapperSessionService.kt # Session management
├── model/
│   ├── ClaudeCodeCliWrapperMessage.kt     # Message data model
│   └── ClaudeCodeCliWrapperSession.kt     # Session data model
└── config/
    ├── ClaudeCodeCliWrapperConfiguration.kt # App configuration
    ├── ClaudeCodeCliWrapperOptions.kt       # CLI options
    └── ClaudeConfigProperties.kt            # Properties binding
```

### Coding Standards

**Kotlin Style:**
```kotlin
// Use data classes for immutable messages
data class ClaudeCodeCliWrapperMessage(
    val type: String,
    val content: String? = null,
    val sessionId: String? = null,
    val claudeSessionId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// Prefer extension functions for utilities
fun String.extractSessionId(): String? {
    return Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        .find(this)?.value
}

// Use sealed classes for message types
sealed class MessageType(val value: String) {
    object SessionCreated : MessageType("session-created")
    object ClaudeError : MessageType("claude-error")
    object ClaudeComplete : MessageType("claude-complete")
    object RawOutput : MessageType("raw-output")
}
```

**Error Handling:**
```kotlin
// Comprehensive error handling with user-friendly messages
private fun handleClaudeError(e: Exception): String {
    return when {
        e.message?.contains("No such file") == true -> 
            "Claude Code CLI not found. Please ensure Claude Code is installed."
        
        e.message?.contains("Permission denied") == true -> 
            "Permission denied. Please check file permissions."
        
        e.message?.contains("Invalid session") == true -> 
            "Session expired. Please start a new conversation."
        
        else -> "Claude Code CLI error: ${e.message ?: "Unknown error"}"
    }
}
```

### Configuration Management

**Environment-specific Configuration:**
```yaml
# application.yml
claude:
  cli:
    path: "${CLAUDE_CLI_PATH:~/.claude/local/claude}"
    default:
      model: "${CLAUDE_MODEL:sonnet}"
      max-turns: "${CLAUDE_MAX_TURNS:20}"
      timeout-minutes: "${CLAUDE_TIMEOUT:30}"
  
  environment:
    term: "${TERM:dumb}"
    shell: "${SHELL:/bin/bash}"
    
  debug:
    claude-debug: "${CLAUDE_DEBUG:false}"
    log-level: "${LOG_LEVEL:INFO}"
```

---

## 🐛 Debugging Guidelines

### Backend Debugging

**Logging Configuration:**
```yaml
logging:
  level:
    com.jsonobject.claude: DEBUG
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

**Debug Output:**
```kotlin
logger.info("=== Command Building Debug ===")
logger.info("Starting Claude session: $sessionId with command: ${commandArray.joinToString(" ")}")
logger.info("Session $sessionId using options: max turns=${effectiveOptions.maxTurns}")
logger.info("Resume session ID: ${effectiveOptions.resumeSessionId}")
logger.info("Is first message: $isFirstMessage")
```

### Frontend Debugging

**Debug Mode Implementation:**
```javascript
// Debug mode toggle
function setDebugMode(debugMode) {
    isDebugMode = debugMode;
    debugToggle.textContent = debugMode ? '🐛' : '💬';
    debugToggle.title = debugMode ? 'Switch to Normal Mode' : 'Switch to Debug Mode';
    localStorage.setItem('debug-mode', debugMode);
    
    showToast(debugMode ? 'Debug Mode Activated' : 'Normal Mode Activated', 'info');
}

// Console debugging
console.log('=== Tool State Tracking Debug ===');
console.log('Message type:', messageObj.type);
console.log('Tool Use ID:', messageObj.toolUseId);  
console.log('Tool Name:', messageObj.toolName);
console.log('Content preview:', messageObj.content?.substring(0, 100));
```

---

## 🚨 Troubleshooting Common Issues

### Session Management Issues

**"No conversation found" Error:**
```kotlin
// Auto-recovery for failed resume
if (message.type === 'result' && message.subtype === 'error' && 
    message.content?.toLowerCase()?.includes('no conversation found') == true) {
    
    logger.info("Session $sessionId: Resume failure detected - starting new session")
    
    // Reset session state
    currentSessionId = null
    isFirstMessage = true
    
    // Notify user
    showToast('Previous conversation not found, starting new session', 'info')
}
```

### SSE Connection Issues

**Connection Recovery:**
```javascript
// Automatic reconnection with exponential backoff
function reconnectSSE(retryCount = 0) {
    const maxRetries = 5;
    const baseDelay = 1000;
    
    if (retryCount >= maxRetries) {
        updateStatus('Connection failed', 'red');
        return;
    }
    
    const delay = baseDelay * Math.pow(2, retryCount);
    setTimeout(() => {
        console.log(`Reconnection attempt ${retryCount + 1}/${maxRetries}`);
        startNewSession(lastMessage);
    }, delay);
}
```

### Tool Result Display Issues

**Tool Name Resolution:**
```javascript
// Enhanced tool name detection
function detectToolName(content, toolUseId) {
    // Priority 1: Explicit tool markers
    const explicitMatch = content.match(/\[(.*?) result for (.*?):/);
    if (explicitMatch) return explicitMatch[2];
    
    // Priority 2: Content pattern matching
    const patterns = {
        'Read': /→.*?(\d+|line|file)/i,
        'Write': /(created|written|file)/i,
        'Edit': /(updated|modified|changed)/i,
        'Bash': /(command|executed|exit code)/i,
        'TodoWrite': /todos.*modified/i
    };
    
    for (const [toolName, pattern] of Object.entries(patterns)) {
        if (pattern.test(content)) return toolName;
    }
    
    return 'Unknown';
}
```

### Performance Issues

**Memory Leak Prevention:**
```kotlin
// Cleanup inactive sessions
@Scheduled(fixedRate = 300000) // Every 5 minutes
fun cleanupInactiveSessions() {
    val cutoffTime = System.currentTimeMillis() - 1800000 // 30 minutes
    
    activeProcesses.entries.removeIf { (sessionId, process) ->
        if (!process.isAlive || lastActivity[sessionId]?.let { it < cutoffTime } == true) {
            logger.info("Cleaning up inactive session: $sessionId")
            process.destroyForcibly()
            sessionToolMappings.remove(sessionId)
            true
        } else {
            false
        }
    }
}
```

---

## 📚 Additional Resources

### Claude Code CLI Integration
- **Stream JSON Format** - `--output-format stream-json --verbose` is essential
- **Session Resume** - `--resume [session-id]` for conversation continuity  
- **Model Selection** - `--model sonnet` for new sessions
- **Working Directory** - Execute in project directory, not Claude metadata folder

### SSE Best Practices
- **Connection Headers** - Set proper cache control and connection headers
- **Event Naming** - Use descriptive event names for different message types
- **Error Handling** - Implement both client and server-side error recovery
- **Timeout Management** - Configure appropriate timeouts for long-running processes

### Frontend Architecture
- **Message State Management** - Immutable updates with proper correlation IDs
- **Tool Result Rendering** - Specialized UI components for each tool type
- **Responsive Design** - Mobile-first approach with touch-friendly interfaces
- **Accessibility** - Screen reader support and keyboard navigation

---

**Development Environment**: JVM 21, Kotlin 2.0.21, Spring Boot 3.3.6, SSE  
**Inspired by**: Claude Code UI project analysis and implementation patterns  
**Target Architecture**: High-performance, maintainable, developer-friendly Claude Code CLI wrapper
