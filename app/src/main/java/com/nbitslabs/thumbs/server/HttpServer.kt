package com.nbitslabs.thumbs.server

import com.nbitslabs.thumbs.auth.ApiKeyStore
import com.nbitslabs.thumbs.mcp.AuditEvent
import com.nbitslabs.thumbs.mcp.McpServer
import com.nbitslabs.thumbs.view.LiveView
import com.nbitslabs.thumbs.view.ViewPage
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
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/**
 * MCP Streamable HTTP transport (stateless, JSON responses only).
 *
 * Every request passes, in order: network policy (Tailscale/loopback by default), lockout check,
 * Origin check (rejects browsers to defeat DNS rebinding), then Bearer token authentication.
 *
 * The live view (/view) is meant for browsers, so instead of an API key and the Origin check
 * it takes a short-lived view-only link token, which a DNS-rebinding page can't know.
 */
class HttpServer(
    private val port: Int,
    private val networkMode: () -> NetworkMode,
    private val keys: ApiKeyStore,
    private val mcp: McpServer,
    private val onRequest: (keyName: String) -> Unit = {},
    private val liveView: LiveView? = null,
    private val auditEvents: SharedFlow<AuditEvent>? = null,
    private val viewStatus: () -> JsonObject = { JsonObject(emptyMap()) },
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
                get("/view") { handleViewPage(call) }
                get("/view/stream") { handleViewStream(call) }
                get("/view/events") { handleViewEvents(call) }
                // Browsers ask for this on every page; answer without touching auth or the limiter
                get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
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

    // --- Live view ---------------------------------------------------------------------------

    /** Network policy, lockout and view token checks. Responds and returns null on failure. */
    private suspend fun viewToken(call: ApplicationCall): String? {
        val remote = call.request.local.remoteAddress
        val live = liveView
        if (live == null || !AccessPolicy.isAllowed(remote, networkMode())) {
            call.respond(HttpStatusCode.Forbidden)
            return null
        }
        if (limiter.isLockedOut(remote)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return null
        }
        val token = call.request.queryParameters["t"]
        if (live.validate(token) == null) {
            limiter.recordFailure(remote)
            call.respondText(
                "This live view link is invalid or has expired. Ask for a new one (get_view_link, or Live view in the Thumbs app).",
                status = HttpStatusCode.Unauthorized,
            )
            return null
        }
        limiter.recordSuccess(remote)
        noStore(call)
        return token
    }

    private fun noStore(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Content-Type-Options", "nosniff")
    }

    private suspend fun handleViewPage(call: ApplicationCall) {
        val token = viewToken(call) ?: return
        val info = liveView?.validate(token) ?: return
        val nonce = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        call.response.header(
            "Content-Security-Policy",
            "default-src 'none'; img-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; " +
                "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )
        call.respondText(ViewPage.html(token, nonce, info.expiresAt), ContentType.Text.Html)
    }

    private suspend fun handleViewStream(call: ApplicationCall) {
        val token = viewToken(call) ?: return
        val live = liveView ?: return
        if (live.viewers.value >= LiveView.MAX_VIEWERS) {
            call.respondText("Too many live viewers; close another view first.", status = HttpStatusCode.TooManyRequests)
            return
        }
        call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")) {
            live.stream(token) { frame ->
                writeStringUtf8("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\n\r\n")
                writeFully(frame.jpeg)
                writeStringUtf8("\r\n")
                flush()
            }
        }
    }

    private suspend fun handleViewEvents(call: ApplicationCall) {
        val token = viewToken(call) ?: return
        val live = liveView ?: return
        val audit: Flow<String> = auditEvents?.map { e ->
            sse("audit", buildJsonObject {
                put("time", e.time)
                put("key", e.keyName)
                put("tool", e.tool)
                put("args", e.args)
                put("ok", e.ok)
                put("ms", e.durationMs)
                e.detail?.let { put("detail", it) }
            })
        } ?: flow { }
        val status = flow {
            while (live.validate(token) != null) {
                emit(sse("status", viewStatus()))
                delay(STATUS_INTERVAL_MS)
            }
            emit("event: ended\ndata: {}\n\n")
        }
        call.respondBytesWriter(ContentType.Text.EventStream) {
            writeStringUtf8("retry: 3000\n\n")
            flush()
            // The status ticker notices when the link ends and emits "ended" as its last event
            merge(status, audit)
                .transformWhile { emit(it); !it.startsWith("event: ended") }
                .collect { send(it) }
        }
    }

    private suspend fun ByteWriteChannel.send(text: String) {
        writeStringUtf8(text)
        flush()
    }

    private fun sse(event: String, data: JsonObject) = "event: $event\ndata: $data\n\n"

    companion object {
        private const val BOUNDARY = "thumbsframe"
        private const val STATUS_INTERVAL_MS = 3000L
        private val random = SecureRandom()
        const val DEFAULT_PORT = 8765
        private const val MAX_BODY = 1L shl 20
    }
}
