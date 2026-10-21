package com.nbitslabs.thumbs.server

import com.nbitslabs.thumbs.auth.ApiKeyStore
import com.nbitslabs.thumbs.mcp.McpServer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * MCP Streamable HTTP transport (stateless, JSON responses only).
 *
 * Every request passes, in order: network policy (Tailscale/loopback by default), lockout check,
 * Origin check (rejects browsers to defeat DNS rebinding), then Bearer token authentication.
 */
class HttpServer(
    private val port: Int,
    private val networkMode: () -> NetworkMode,
    private val keys: ApiKeyStore,
    private val mcp: McpServer,
    private val onRequest: (keyName: String) -> Unit = {},
) {
    private val limiter = AuthFailureLimiter()
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                post("/mcp") { handleMcp(call) }
                get("/mcp") {
                    // We never push server-initiated messages, so there is no SSE stream to open
                    if (!gate(call)) return@get
                    call.response.header(HttpHeaders.Allow, "POST")
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }
                delete("/mcp") {
                    if (!gate(call)) return@delete
                    call.respond(HttpStatusCode.MethodNotAllowed)
                }
                get("/health") {
                    if (!AccessPolicy.isAllowed(call.request.local.remoteAddress, networkMode())) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    /** Applies network, origin and auth checks. Responds and returns null on failure. */
    private suspend fun authenticate(call: ApplicationCall): com.nbitslabs.thumbs.auth.ApiKey? {
        val remote = call.request.local.remoteAddress
        if (!AccessPolicy.isAllowed(remote, networkMode())) {
            call.respondText(
                "Connections are only accepted over Tailscale. Change the network setting in the Thumbs app to allow other networks.",
                status = HttpStatusCode.Forbidden,
            )
            return null
        }
        if (limiter.isLockedOut(remote)) {
            call.respondText("Too many failed authentication attempts; try again later.", status = HttpStatusCode.TooManyRequests)
            return null
        }
        if (call.request.header(HttpHeaders.Origin) != null) {
            call.respondText("Browser origins are not allowed.", status = HttpStatusCode.Forbidden)
            return null
        }
        val auth = call.request.header(HttpHeaders.Authorization)
        val token = auth?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val key = token?.let(keys::authenticate)
        if (key == null) {
            limiter.recordFailure(remote)
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"thumbs\"")
            call.respondText(
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Missing or invalid API key. Send 'Authorization: Bearer <key>' with a key created in the Thumbs app."}}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return null
        }
        limiter.recordSuccess(remote)
        return key
    }

    private suspend fun gate(call: ApplicationCall): Boolean = authenticate(call) != null

    private suspend fun handleMcp(call: ApplicationCall) {
        val key = authenticate(call) ?: return
        val bytes = call.receiveChannel().readRemaining(MAX_BODY + 1).readByteArray()
        if (bytes.size > MAX_BODY) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        onRequest(key.name)
        val response = mcp.handle(bytes.decodeToString(), key)
        if (response == null) {
            call.respond(HttpStatusCode.Accepted)
        } else {
            call.respondText(response, ContentType.Application.Json)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_BODY = 1L shl 20
    }
}
