package com.nbitslabs.thumbs.mcp

import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.auth.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class AuditEvent(
    val time: Long,
    val keyName: String,
    val tool: String,
    val args: String,
    val ok: Boolean,
    val durationMs: Long,
    val detail: String?,
)

/**
 * Transport-independent MCP (JSON-RPC 2.0) request handling. The HTTP layer authenticates the
 * caller and passes the request body plus the resolved [ApiKey].
 */
class McpServer(
    private val tools: () -> List<Tool>,
    private val serverVersion: String,
    private val instructions: String,
    private val onAudit: (AuditEvent) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val uiLock = Mutex()

    /** Returns the response body, or null when the request contained only notifications/responses. */
    suspend fun handle(body: String, key: ApiKey): String? {
        val parsed = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return json.encodeToString(JsonElement.serializer(), errorResponse(JsonNull, PARSE_ERROR, "Parse error"))
        }
        return when (parsed) {
            is JsonArray -> {
                if (parsed.isEmpty()) return encode(errorResponse(JsonNull, INVALID_REQUEST, "Empty batch"))
                val responses = parsed.mapNotNull { handleMessage(it, key) }
                if (responses.isEmpty()) null else encode(JsonArray(responses))
            }
            else -> handleMessage(parsed, key)?.let(::encode)
        }
    }

    private fun encode(e: JsonElement) = json.encodeToString(JsonElement.serializer(), e)

    private suspend fun handleMessage(msg: JsonElement, key: ApiKey): JsonElement? {
        val obj = msg as? JsonObject ?: return errorResponse(JsonNull, INVALID_REQUEST, "Invalid request")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        // Responses from the client (we never send requests) and notifications get no reply
        if (method == null) return if (id == null) errorResponse(JsonNull, INVALID_REQUEST, "Invalid request") else null
        if (id == null) return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            val result = when (method) {
                "initialize" -> initialize(params)
                "ping" -> JsonObject(emptyMap())
                "tools/list" -> listTools(key)
                "tools/call" -> callTool(params, key)
                "resources/list" -> buildJsonObject { put("resources", JsonArray(emptyList())) }
                "resources/templates/list" -> buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) }
                "prompts/list" -> buildJsonObject { put("prompts", JsonArray(emptyList())) }
                "logging/setLevel" -> JsonObject(emptyMap())
                else -> return errorResponse(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
        } catch (e: RpcException) {
            errorResponse(id, e.code, e.message ?: "Error")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", "thumbs")
                put("title", "Thumbs (Android phone control)")
                put("version", serverVersion)
            })
            put("instructions", instructions)
        }
    }

    private fun visibleTools(key: ApiKey) = tools().filter { key.scope == Scope.CONTROL || it.scope == Scope.READ }

    private fun listTools(key: ApiKey) = buildJsonObject {
        put("tools", buildJsonArray { visibleTools(key).forEach { add(it.descriptor()) } })
    }

    private suspend fun callTool(params: JsonObject, key: ApiKey): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: throw RpcException(INVALID_PARAMS, "Missing tool name")
        val args = when (val a = params["arguments"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> throw RpcException(INVALID_PARAMS, "Tool arguments must be an object")
        }
        val tool = tools().firstOrNull { it.name == name } ?: throw RpcException(INVALID_PARAMS, "Unknown tool: $name")
        val start = clock()
        val result = if (tool.scope == Scope.CONTROL && key.scope != Scope.CONTROL) {
            ToolResult.error("The API key '${key.name}' is read-only and can't call '$name'.")
        } else {
            try {
                val call = ToolCall(args, key)
                if (tool.exclusive) uiLock.withLock { tool.handler(call) } else tool.handler(call)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolException) {
                ToolResult.error(e.message ?: "Tool failed")
            } catch (e: Exception) {
                ToolResult.error("Internal error in $name: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        onAudit(
            AuditEvent(
                time = start,
                keyName = key.name,
                tool = name,
                args = if (tool.logArgs) summarize(args) else args.keys.joinToString(", ") { "$it=…" },
                ok = !result.isError,
                durationMs = clock() - start,
                detail = if (result.isError) (result.content.firstOrNull() as? Content.Text)?.text?.take(200) else null,
            )
        )
        return encodeResult(result)
    }

    private fun summarize(args: JsonObject): String =
        args.entries.joinToString(", ") { (k, v) -> "$k=${v.toString().take(60)}" }.take(300)

    private fun encodeResult(result: ToolResult) = buildJsonObject {
        put("content", buildJsonArray {
            result.content.forEach { c ->
                add(
                    when (c) {
                        is Content.Text -> buildJsonObject {
                            put("type", "text")
                            put("text", c.text)
                        }
                        is Content.Image -> buildJsonObject {
                            put("type", "image")
                            put("data", c.base64)
                            put("mimeType", c.mimeType)
                        }
                    }
                )
            }
        })
        put("isError", result.isError)
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private class RpcException(val code: Int, message: String) : Exception(message)

    companion object {
        /** Newest first; we answer with the client's version when we support it. */
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
    }
}

