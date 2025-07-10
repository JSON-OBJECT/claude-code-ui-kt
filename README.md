# 🚀 Claude Code UI for Kotlin

[![JVM](https://img.shields.io/badge/JVM-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue.svg)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.6-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> A modern, high-performance wrapper for Claude Code CLI with real-time streaming capabilities, built with cutting-edge JVM technology.

## ✨ Key Features

- 🔥 **JVM 21 Virtual Threads** - Ultra-high concurrency without reactive complexity
- ⚡ **Server-Sent Events (SSE)** - Real-time streaming simpler than WebSocket
- 🎯 **Ultra-Minimal Architecture** - Clean, maintainable, and intuitive design  
- 🔄 **Real-time Tool Execution** - Live Claude Code CLI stdout streaming with tool result rendering
- 📱 **Mobile-First UI** - ChatGPT-inspired responsive interface
- 🌙 **Dark/Light Theme** - Automatic theme switching with user preferences
- 🐛 **Debug Mode** - Complete JSON message inspection for development
- 📊 **Session Management** - Persistent conversation history with resume capability

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Frontend: HTML5 + Vanilla JavaScript + Tailwind CSS        │
│     ↓ SSE Connection (/claude/start, /claude/send)          │
│  Backend: JVM 21 + Virtual Thread + Kotlin + Spring MVC     │
│     ↓ Direct Process Execution                              │
│  Claude Code CLI: --output-format stream-json --verbose     │
│     ↓ Real-time stdout → JSON parsing → SSE streaming       │
│  Live Tool Execution & Result Rendering                     │
└─────────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites

- **JDK 21** or later (with Virtual Thread support)
- **Claude Code CLI** installed and accessible in PATH
- **Unix-like environment** (Linux, macOS, WSL on Windows)

### Installation & Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/JSON-OBJECT/claude-code-ui-kt.git
   cd claude-code-ui-kt
   ```

2. **Verify Claude Code CLI installation**
   ```bash
   claude --version
   # Ensure Claude Code CLI is properly installed and configured
   ```

3. **Build and run**
   ```bash
   # Using Gradle wrapper (recommended)
   ./gradlew bootRun
   
   # Or build JAR and run
   ./gradlew build
   java -jar build/libs/claude-code-ui-kt-0.0.1-SNAPSHOT.jar
   ```

4. **Access the application**
   ```
   http://localhost:8080
   ```

### First-Time Setup

1. Open your browser and navigate to `http://localhost:8080`
2. You'll see a ChatGPT-like interface ready for interaction
3. Type your first message to Claude and press Enter
4. Watch real-time tool execution and streaming responses!

## 📡 API Endpoints

### Core SSE Endpoints

#### Start New Session
```http
GET /claude/start?prompt={message}&projectPath={optional}
Content-Type: text/event-stream
```

**Response Events:**
- `connection` - Initial connection confirmation
- `session-confirmed` - Claude session ID established  
- `claude-message` - Real-time Claude responses and tool executions

#### Send Message to Existing Session
```http
POST /claude/send/{sessionId}
Content-Type: application/json

{
  "message": "Your message to Claude"
}
```

### Session Management

#### List Available Sessions
```http
GET /claude/sessions
```

**Response:**
```json
{
  "sessions": [
    {
      "sessionId": "uuid",
      "summary": "Session summary",
      "messageCount": 15,
      "lastMessageTime": "2024-01-15T10:30:00Z",
      "lastMessage": "Last message preview..."
    }
  ],
  "totalCount": 5,
  "currentProject": "/path/to/project"
}
```

#### Get Session History
```http
GET /claude/sessions/{sessionId}
```

#### Resume Session with New Message
```http
POST /claude/sessions/{sessionId}/resume
Content-Type: text/event-stream

{
  "message": "Continue our conversation..."
}
```

### Utility Endpoints

#### Health Check
```http
GET /claude/health
```

#### Stop Active Session
```http
POST /claude/stop/{sessionId}
```

#### Get Active Sessions Status
```http
GET /claude/active-sessions
```

## 💡 Frontend Features

### Chat Interface
- **Real-time streaming** - See Claude's responses as they're generated
- **Tool execution visualization** - Live display of Read, Write, Edit, Bash, and other tool operations
- **Syntax highlighting** - Code blocks with proper language detection
- **Message history** - Persistent conversation tracking

### Display Modes
- **Normal Mode** - Clean, user-friendly chat interface
- **Debug Mode** - Complete JSON message inspection for development
- **Auto-expanding Tool Results** - Collapsible detailed tool outputs

### Responsive Design
- **Mobile-optimized** - Touch-friendly interface with proper sizing
- **Theme support** - Dark/light mode with system preference detection
- **Keyboard shortcuts** - Enter to send, Shift+Enter for new line
- **Accessibility** - Screen reader support and keyboard navigation

## 🛠️ Development

### Local Development Setup

1. **Configure IDE for Kotlin**
   ```bash
   # For IntelliJ IDEA (recommended)
   # Import as Gradle project with Kotlin support
   ```

2. **Environment Variables** (optional)
   ```bash
   export CLAUDE_CLI_PATH=/custom/path/to/claude
   export SERVER_PORT=8080
   ```

3. **Development Mode**
   ```bash
   # Run with hot reload
   ./gradlew bootRun --args='--spring.profiles.active=dev'
   ```

### Configuration

The application uses `application.yml` for configuration:

```yaml
server:
  port: 8080

claude:
  cli:
    path: "~/.claude/local/claude"  # Claude Code CLI path
    default:
      model: "sonnet"               # Default Claude model
      output-format: "stream-json"  # Required for streaming
      verbose: true                 # Enable detailed output
      max-turns: 20                 # Conversation limit
      timeout-minutes: 30           # Process timeout
```

## 🌟 Technical Highlights

### JVM 21 & Virtual Threads
- **Massive Concurrency** - Handle thousands of concurrent SSE connections
- **No Reactive Complexity** - Simple blocking I/O with virtual thread efficiency
- **Memory Efficient** - Lightweight threads with minimal overhead

### Real-Time Architecture
- **Stream Processing** - Line-by-line JSON parsing for immediate response
- **SSE over WebSocket** - Simpler implementation with built-in reconnection
- **Tool Result Rendering** - Specialized UI for each Claude tool type

### Performance Optimizations
- **Mobile Battery Saving** - Reduced animations and background processing
- **Efficient Parsing** - Optimized JSON processing for Claude Code CLI output
- **Memory Management** - Automatic cleanup of completed sessions

### Development Philosophy

- **Simplicity over Complexity** - Virtual threads eliminate reactive complexity
- **Performance by Design** - JVM 21 features for maximum efficiency  
- **User Experience First** - ChatGPT-quality interface standards
- **Developer Friendly** - Clear APIs and comprehensive debugging tools

## 📋 Roadmap

- [ ] **Authentication & Authorization** - Multi-user support
- [ ] **Session Persistence** - Redis/Database backend
- [ ] **Metrics & Monitoring** - Micrometer integration
- [ ] **Plugin System** - Custom tool integrations
- [ ] **API Rate Limiting** - Production-ready controls
- [ ] **WebSocket Fallback** - Enhanced compatibility
- [ ] **Multi-Model Support** - Support for different Claude models

## ⚠️ Important Notes

- **Claude Code CLI Required** - This is a wrapper, not a replacement for Claude Code CLI
- **API Keys** - Ensure your Claude Code CLI is properly configured with valid credentials
- **Resource Usage** - Virtual threads are efficient but monitor memory with high concurrency
- **Security** - Do not expose this directly to the internet without proper authentication

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙋‍♂️ Support

- **Issues** - Please use GitHub Issues for bug reports and feature requests
- **Discussions** - Join our GitHub Discussions for questions and community support
- **Documentation** - Check our [Wiki](../../wiki) for detailed guides

---

**Built with ❤️ using JVM 21 Virtual Threads, Kotlin, and Spring Boot**

*Inspired by the Claude Code UI project, reimagined for the JVM ecosystem with cutting-edge technology.*
