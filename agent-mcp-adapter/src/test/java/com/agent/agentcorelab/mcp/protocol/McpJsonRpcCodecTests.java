package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpJsonRpcCodec Specification Tests")
class McpJsonRpcCodecTests {

    @Test
    @DisplayName("Encode and decode standard JSON-RPC Request")
    void testRequestEncodeDecode() throws Exception {
        JsonNode params = McpJsonRpcCodec.mapper().createObjectNode().put("query", "SELECT 1");
        JsonRpcRequest request = JsonRpcRequest.of("req-1", "tools/call", params);

        String json = McpJsonRpcCodec.encodeMessage(request);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"method\":\"tools/call\""));
        assertTrue(json.contains("\"id\":\"req-1\""));

        JsonRpcMessage decoded = McpJsonRpcCodec.decodeMessage(json);
        assertInstanceOf(JsonRpcRequest.class, decoded);
        JsonRpcRequest req = (JsonRpcRequest) decoded;
        assertEquals("req-1", req.id());
        assertEquals("tools/call", req.method());
        assertEquals("SELECT 1", req.params().get("query").asText());
    }

    @Test
    @DisplayName("Encode and decode JSON-RPC Response (Success and Error)")
    void testResponseEncodeDecode() throws Exception {
        JsonNode resData = McpJsonRpcCodec.mapper().createObjectNode().put("status", "ok");
        JsonRpcResponse successResp = JsonRpcResponse.success("resp-1", resData);

        String json = McpJsonRpcCodec.encodeMessage(successResp);
        JsonRpcMessage decoded = McpJsonRpcCodec.decodeMessage(json);
        assertInstanceOf(JsonRpcResponse.class, decoded);
        JsonRpcResponse resp = (JsonRpcResponse) decoded;
        assertTrue(resp.isSuccess());
        assertEquals("ok", resp.result().get("status").asText());

        JsonRpcResponse errorResp = JsonRpcResponse.error("resp-2", JsonRpcError.invalidParams("Param missing"));
        String errJson = McpJsonRpcCodec.encodeMessage(errorResp);
        JsonRpcResponse decodedErr = (JsonRpcResponse) McpJsonRpcCodec.decodeMessage(errJson);
        assertFalse(decodedErr.isSuccess());
        assertEquals(McpProtocolConstants.ERROR_INVALID_PARAMS, decodedErr.error().code());
        assertEquals("Param missing", decodedErr.error().message());
    }

    @Test
    @DisplayName("Encode and decode Initialize params and result")
    void testInitializeCodec() {
        JsonNode params = McpJsonRpcCodec.createInitializeParams("my-client", "1.2.3");
        assertEquals("my-client", params.get("clientInfo").get("name").asText());
        assertEquals(McpProtocolConstants.PROTOCOL_VERSION, params.get("protocolVersion").asText());

        String rawResult = """
                {
                  "protocolVersion": "2024-11-05",
                  "serverInfo": {
                    "name": "sqlite-server",
                    "version": "0.1.0"
                  },
                  "capabilities": {
                    "tools": {}
                  }
                }
                """;
        JsonNode resNode = McpJsonRpcCodec.parseJson(rawResult);
        McpInitializeResult initResult = McpJsonRpcCodec.decodeInitializeResult(resNode);
        assertEquals("2024-11-05", initResult.protocolVersion());
        assertEquals("sqlite-server", initResult.serverName());
        assertEquals("0.1.0", initResult.serverVersion());
    }

    @Test
    @DisplayName("Decode tools/list payload containing multiple tools")
    void testToolsListCodec() {
        String toolsJson = """
                {
                  "tools": [
                    {
                      "name": "query_db",
                      "description": "Executes SQL query",
                      "inputSchema": {
                        "type": "object",
                        "properties": {
                          "sql": { "type": "string" }
                        },
                        "required": ["sql"]
                      }
                    },
                    {
                      "name": "get_table_schema",
                      "description": "Gets table DDL schema",
                      "inputSchema": {
                        "type": "object",
                        "properties": {
                          "tableName": { "type": "string" }
                        }
                      }
                    }
                  ]
                }
                """;
        List<McpTool> tools = McpJsonRpcCodec.decodeToolsList(McpJsonRpcCodec.parseJson(toolsJson));
        assertEquals(2, tools.size());
        assertEquals("query_db", tools.get(0).name());
        assertEquals("Executes SQL query", tools.get(0).description());
        assertEquals("string", tools.get(0).inputSchema().get("properties").get("sql").get("type").asText());

        assertEquals("get_table_schema", tools.get(1).name());
    }

    @Test
    @DisplayName("Decode tools/call result payload")
    void testToolResultCodec() {
        String toolResultJson = """
                {
                  "content": [
                    {
                      "type": "text",
                      "text": "10 rows returned."
                    }
                  ],
                  "isError": false
                }
                """;
        McpToolResult result = McpJsonRpcCodec.decodeToolResult(McpJsonRpcCodec.parseJson(toolResultJson));
        assertFalse(result.isError());
        assertEquals("10 rows returned.", result.asText());
        assertEquals(1, result.content().size());
        assertEquals("text", result.content().get(0).type());
    }
}
