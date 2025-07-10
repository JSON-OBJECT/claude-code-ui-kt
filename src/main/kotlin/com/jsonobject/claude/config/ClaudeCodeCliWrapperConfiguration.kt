package com.jsonobject.claude.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableConfigurationProperties(
    ClaudeConfigProperties::class
)
@EnableScheduling
class ClaudeCodeCliWrapperConfiguration(
    private val claudeConfig: ClaudeConfigProperties
) {

    @Bean
    fun claudeOptionsFactory(): ClaudeCodeCliWrapperOptionsFactory {
        return ClaudeCodeCliWrapperOptionsFactory(claudeConfig)
    }

    @Bean  
    fun claudeCommandBuilder(): ClaudeCodeCliWrapperCommandBuilder {
        val claudePath = claudeConfig.cli.path.replace("~", System.getProperty("user.home"))
        return ClaudeCodeCliWrapperCommandBuilder(claudePath)
    }
}

class ClaudeCodeCliWrapperOptionsFactory(
    private val claudeConfig: ClaudeConfigProperties
) {
    fun createDefaultOptions(): ClaudeCodeCliWrapperOptions {
        val defaultConfig = claudeConfig.cli.default
        
        return ClaudeCodeCliWrapperOptions(
            model = defaultConfig.model,
            outputFormat = defaultConfig.outputFormat,
            verbose = defaultConfig.verbose,
            maxTurns = defaultConfig.maxTurns,
            timeout = defaultConfig.timeoutMinutes?.let { it * 60 * 1000 },
            additionalEnvVars = buildEnvironmentVars()
        )
    }

    fun createPresetOptions(presetName: String): ClaudeCodeCliWrapperOptions? {
        val preset = claudeConfig.cli.presets[presetName] ?: return null
        val defaultConfig = claudeConfig.cli.default
        
        return ClaudeCodeCliWrapperOptions(
            model = defaultConfig.model,
            outputFormat = defaultConfig.outputFormat,
            verbose = defaultConfig.verbose,
            maxTurns = preset.maxTurns ?: defaultConfig.maxTurns,
            timeout = (preset.timeoutMinutes ?: defaultConfig.timeoutMinutes)?.let { it * 60 * 1000 },
            permissionMode = preset.permissionMode,
            additionalEnvVars = buildEnvironmentVars()
        )
    }

    fun createUserLevelOptions(level: String): ClaudeCodeCliWrapperOptions? {
        val presetName = level.lowercase()
        return createPresetOptions(presetName)
    }

    private fun buildEnvironmentVars(): Map<String, String> {
        val envVars = mutableMapOf<String, String>()

        val basicConfig = claudeConfig.environment.basic
        envVars["TERM"] = basicConfig.term

        val debugConfig = claudeConfig.environment.debug
        if (debugConfig.claudeDebug) {
            envVars["CLAUDE_DEBUG"] = "1"
        }
        if (debugConfig.claudeTrace) {
            envVars["CLAUDE_TRACE"] = "1"
        }
        envVars["LOG_LEVEL"] = debugConfig.logLevel
        
        return envVars
    }
}
