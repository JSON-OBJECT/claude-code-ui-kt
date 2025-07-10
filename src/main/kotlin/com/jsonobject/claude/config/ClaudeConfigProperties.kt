package com.jsonobject.claude.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "claude")
data class ClaudeConfigProperties(
    val cli: CliConfig,
    val environment: EnvironmentConfig
) {

    data class CliConfig(
        val path: String = "~/.claude/local/claude",
        val default: DefaultConfig,
        val presets: Map<String, PresetConfig>
    )

    data class DefaultConfig(
        val model: String = "sonnet",
        val outputFormat: String = "stream-json",
        val verbose: Boolean = true,
        val maxTurns: Int? = 20,
        val timeoutMinutes: Long? = 30
    )

    data class PresetConfig(
        val maxTurns: Int? = null,
        val permissionMode: String? = null,
        val timeoutMinutes: Long? = null
    )

    data class EnvironmentConfig(
        val basic: BasicConfig,
        val debug: DebugConfig
    )


    data class BasicConfig(
        val term: String = "dumb",
        val shell: String = "/bin/bash"
    )

    data class DebugConfig(
        val claudeDebug: Boolean = false,
        val claudeTrace: Boolean = false,
        val logLevel: String = "INFO"
    )
}
