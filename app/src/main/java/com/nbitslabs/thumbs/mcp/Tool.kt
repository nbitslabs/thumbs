package com.nbitslabs.thumbs.mcp

import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.auth.Scope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val base64: String, val mimeType: String) : Content
}

data class ToolResult(val content: List<Content>, val isError: Boolean = false) {
    companion object {
        fun text(text: String) = ToolResult(listOf(Content.Text(text)))
        fun error(text: String) = ToolResult(listOf(Content.Text(text)), isError = true)
    }
}

/** Thrown by tool handlers for expected failures; reported to the agent as an `isError` result. */
class ToolException(message: String) : Exception(message)

class ToolCall(val args: JsonObject, val key: ApiKey) {
    fun has(name: String) = args[name] != null && args[name] !is kotlinx.serialization.json.JsonNull

    fun string(name: String): String? = (args[name] as? JsonPrimitive)?.contentOrNull
    fun requireString(name: String): String = string(name) ?: throw ToolException("Missing required argument '$name'")

    fun double(name: String): Double? = (args[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    fun requireDouble(name: String): Double = double(name) ?: throw ToolException("Missing required number '$name'")
    fun int(name: String): Int? = double(name)?.toInt()

    fun bool(name: String): Boolean? = (args[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    fun stringList(name: String): List<String>? = (args[name] as? JsonArray)?.map { it.jsonPrimitive.content }
    fun obj(name: String): JsonObject? = args[name] as? JsonObject
}

class Tool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val scope: Scope,
    val readOnly: Boolean = scope == Scope.READ,
    val destructive: Boolean = false,
    /** Whether this tool drives the UI; such calls are serialised so agents can't interleave gestures. */
    val exclusive: Boolean = !readOnly,
    /** Whether audit logs may record argument values (false for tools that carry typed text etc). */
    val logArgs: Boolean = true,
    val handler: suspend (ToolCall) -> ToolResult,
) {
    fun descriptor(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
        put("annotations", buildJsonObject {
            put("readOnlyHint", readOnly)
            put("destructiveHint", destructive)
            put("openWorldHint", false)
        })
    }
}

/** Small builder for JSON Schema objects used as tool input schemas. */
class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonElement>()
    private val required = mutableListOf<String>()

    private fun prop(name: String, type: String, description: String, required: Boolean, extra: Map<String, JsonElement> = emptyMap()) {
        props[name] = buildJsonObject {
            put("type", type)
            put("description", description)
            extra.forEach { (k, v) -> put(k, v) }
        }
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(name, "string", description, required, enum?.let { mapOf("enum" to JsonArray(it.map(::JsonPrimitive))) } ?: emptyMap())

    fun number(name: String, description: String, required: Boolean = false) = prop(name, "number", description, required)
    fun integer(name: String, description: String, required: Boolean = false) = prop(name, "integer", description, required)
    fun boolean(name: String, description: String, required: Boolean = false) = prop(name, "boolean", description, required)
    fun stringArray(name: String, description: String, required: Boolean = false) =
        prop(name, "array", description, required, mapOf("items" to buildJsonObject { put("type", "string") }))

    fun obj(name: String, description: String, required: Boolean = false) =
        prop(name, "object", description, required, mapOf("additionalProperties" to buildJsonObject { put("type", "string") }))

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}

fun schema(block: SchemaBuilder.() -> Unit = {}): JsonObject = SchemaBuilder().apply(block).build()
