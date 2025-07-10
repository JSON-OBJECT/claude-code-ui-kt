package com.jsonobject.claude

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ClaudeCodeCliWrapperApplication

fun main(args: Array<String>) {
    runApplication<ClaudeCodeCliWrapperApplication>(*args)
}
