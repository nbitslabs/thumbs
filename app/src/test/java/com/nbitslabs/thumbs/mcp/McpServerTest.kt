package com.nbitslabs.thumbs.mcp

import com.nbitslabs.thumbs.auth.ApiKey
import com.nbitslabs.thumbs.auth.Scope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val control = ApiKey("1", "ctl", "thumbs_x", "h", Scope.CONTROL, 0)
    private val reader = ApiKey("2", "ro", "thumbs_y", "h", Scope.READ, 0)
    private val audits = mutableListOf<AuditEvent>()

    private val tools = listOf(
        Tool("look", "Look", schema(), Scope.READ) { ToolResult.text("seen") },
        Tool("touch", "Touch", schema { number("x", "x", required = true) }, Scope.CONTROL) { call ->
            ToolResult.text("touched ${call.requireDouble("x")}")
        },
        Tool("secret", "Typed text", schema(), Scope.CONTROL, logArgs = false) { ToolResult.text("ok") },
        Tool("boom", "Fails", schema(), Scope.READ) { throw ToolException("nope") },
        Tool("pic", "Image", schema(), Scope.READ) { ToolResult(listOf(Content.Image("AAAA", "image/jpeg"))) },
    )
    private val server = McpServer({ tools }, "1.0", "be careful", audits::add)

    private suspend fun call(body: String, key: ApiKey = control): JsonObject =
        Json.parseToJsonElement(server.handle(body, key)!!).jsonObject

    @Test fun `initialize negotiates version and returns instructions`() = runTest {
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        val result = r["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("be careful", result["instructions"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))

        val unknown = call("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        assertEquals(McpServer.SUPPORTED_VERSIONS.first(), unknown["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test fun `notifications get no response`() = runTest {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", control))
    }

    @Test fun `read-only keys only see read tools`() = runTest {
        fun names(r: JsonObject) = r["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val all = names(call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        val ro = names(call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", reader))
        assertEquals(listOf("look", "touch", "secret", "boom", "pic"), all)
        assertEquals(listOf("look", "boom", "pic"), ro)
    }

    @Test fun `read-only key calling control tool gets an error result`() = runTest {
        val r = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"touch","arguments":{"x":1}}}""", reader)
        assertTrue(r["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test fun `tool call returns content and audits`() = runTest {
        val r = call("""{"jsonrpc":"2.0","id":"a","method":"tools/call","params":{"name":"touch","arguments":{"x":5}}}""")
        assertEquals("a", r["id"]!!.jsonPrimitive.content)
        val result = r["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("touched 5.0", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("touch", audits.single().tool)
        assertEquals("ctl", audits.single().keyName)
        assertTrue(audits.single().args.contains("x=5"))
    }

    @Test fun `sensitive tool arguments are not logged`() = runTest {
        call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"secret","arguments":{"text":"hunter2"}}}""")
        assertFalse(audits.single().args.contains("hunter2"))
    }

    @Test fun `tool exceptions become error results`() = runTest {
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"boom"}}""")
        val result = r["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("nope", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(audits.single().ok)
    }

    @Test fun `image content is encoded`() = runTest {
        val c = call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"pic"}}""")["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("image", c["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", c["mimeType"]!!.jsonPrimitive.content)
    }

    @Test fun `protocol errors`() = runTest {
        assertEquals(-32700, call("{not json")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, call("""{"jsonrpc":"2.0","id":1,"method":"nope"}""")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32602, call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"missing"}}""")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun `batches return one response per request`() = runTest {
        val out = Json.parseToJsonElement(
            server.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"ping"}]""", control)!!,
        ) as JsonArray
        assertEquals(2, out.size)
    }
}
