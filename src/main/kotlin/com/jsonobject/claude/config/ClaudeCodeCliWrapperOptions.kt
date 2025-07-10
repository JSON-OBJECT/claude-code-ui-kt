package com.jsonobject.claude.config

data class ClaudeCodeCliWrapperOptions(

    val model: String = "sonnet",
    val outputFormat: String = "stream-json",
    val inputFormat: String? = null,
    val verbose: Boolean = true,
    val continueSession: Boolean = false,
    val resumeSessionId: String? = null,
    val permissionMode: String? = null,
    val permissionPromptTool: String? = null,
    val dangerouslySkipPermissions: Boolean = true,
    val maxTurns: Int? = null,
    val timeout: Long? = null,
    val workingDirectories: List<String> = emptyList(),
    val additionalEnvVars: Map<String, String> = emptyMap(),
    val enableDebugLogging: Boolean = false,
    val logLevel: String = "INFO"
)

class ClaudeCodeCliWrapperCommandBuilder(
    private val claudePath: String = System.getProperty("user.home") + "/.claude/local/claude"
) {
    fun buildCommand(prompt: String, options: ClaudeCodeCliWrapperOptions): Array<String> {
        val commands = mutableListOf<String>()

        commands.add(claudePath)
        commands.add("--print")
        commands.add(prompt)

        when {
            options.resumeSessionId != null -> {
                commands.add("--resume")
                commands.add(options.resumeSessionId)
                commands.add("--model")
                commands.add(options.model)
            }
            options.continueSession -> {
                commands.add("--continue")
                commands.add("--model")
                commands.add(options.model)
            }
            else -> {
                commands.add("--model")
                commands.add(options.model)
            }
        }

        commands.add("--output-format")
        commands.add(options.outputFormat)

        options.inputFormat?.takeIf { it.isNotBlank() }?.let {
            commands.add("--input-format")
            commands.add(it)
        }

        options.permissionMode?.takeIf { it.isNotBlank() }?.let {
            commands.add("--permission-mode")
            commands.add(it)
        }
        
        options.permissionPromptTool?.takeIf { it.isNotBlank() }?.let {
            commands.add("--permission-prompt-tool")
            commands.add(it)
        }
        
        if (options.dangerouslySkipPermissions) {
            commands.add("--dangerously-skip-permissions")
        }

        options.maxTurns?.let {
            commands.add("--max-turns")
            commands.add(it.toString())
        }

        options.workingDirectories.forEach { dir ->
            commands.add("--add-dir")
            commands.add(dir)
        }

        if (options.verbose) {
            commands.add("--verbose")
        }
        
        return commands.toTypedArray()
    }

    fun buildEnvironment(options: ClaudeCodeCliWrapperOptions): Array<String> {
        val env = mutableListOf<String>()

        System.getenv().forEach { (key, value) ->
            env.add("$key=$value")
        }

        if (!System.getenv().containsKey("HOME")) {
            env.add("HOME=${System.getProperty("user.home")}")
        }
        if (!System.getenv().containsKey("USER")) {
            env.add("USER=${System.getProperty("user.name") ?: "user"}")
        }
        if (!System.getenv().containsKey("TERM")) {
            env.add("TERM=dumb")
        }
        if (!System.getenv().containsKey("SHELL")) {
            env.add("SHELL=/bin/bash")
        }


        if (options.enableDebugLogging) {
            env.add("CLAUDE_DEBUG=1")
            env.add("LOG_LEVEL=${options.logLevel}")
        }

        options.additionalEnvVars.forEach { (key, value) ->
            env.add("$key=$value")
        }
        
        return env.toTypedArray()
    }
}

object ClaudeCodeCliWrapperPresets {

    val DEFAULT = ClaudeCodeCliWrapperOptions()
}
