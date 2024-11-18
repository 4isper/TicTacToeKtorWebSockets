package com.example

import com.example.plugins.configureMonitoring
import com.example.plugins.configureRouting
import com.example.plugins.configureSerialization
import com.example.plugins.configureSockets
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting()
}
