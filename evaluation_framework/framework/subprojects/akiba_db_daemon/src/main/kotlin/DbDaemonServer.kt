package org.iotsplab.akiba.dbDaemon

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.serialization.jackson.JacksonWebsocketContentConverter
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.config
import org.iotsplab.akiba.dbDaemon.DatabaseDaemon.Companion.globalLogger
import kotlin.time.Duration.Companion.seconds

object DbDaemonServer {
    lateinit var server: EmbeddedServer<*, *>

    @Throws(IllegalStateException::class)
    fun startServer() {
        try {
            server = embeddedServer(Netty, config.serverPort, "0.0.0.0") {
                install(ContentNegotiation) {
                    jackson {
                        factory.setStreamReadConstraints(
                            StreamReadConstraints.builder().maxStringLength(200 * 1024 * 1024).build()
                        )
                    }
                }
                install(WebSockets) {
                    pingPeriod = 15.seconds
                    timeout = 120.seconds
                    contentConverter = JacksonWebsocketContentConverter(
                        jacksonObjectMapper()
                            .registerKotlinModule()
                            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    )
                }

                routing {
                    RouteRegistry.registerAll(this)
                }
            }
            server.start(wait = false)
            globalLogger.info("Server started, listening ${config.serverPort}")
        } catch (e: Exception) {
            throw IllegalStateException(
                "HTTP Server failed to start: ${e.message}")
        }
    }

    fun stopServer() {
        server.stop(1000, 3000)
    }
}