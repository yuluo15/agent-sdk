package com.agent.agentcorelab.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Mock MCP Stdio child process server for testing real OS-level inter-process communication.
 */
public class MockMcpStdioServerMain {

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                JsonNode reqNode = mapper.readTree(line);
                JsonNode idNode = reqNode.get("id");
                if (idNode == null || idNode.isNull()) {
                    // Notification frame (no response)
                    continue;
                }

                String idVal = idNode.isNumber() ? idNode.toString() : "\"" + idNode.asText() + "\"";
                String method = reqNode.path("method").asText();

                String resultJson;
                if ("initialize".equals(method)) {
                    resultJson = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"stdio-mock-server\",\"version\":\"3.0.0\"}}";
                } else if ("tools/list".equals(method)) {
                    resultJson = "{\"tools\":[{\"name\":\"calculate\",\"description\":\"Math calculator\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"expr\":{\"type\":\"string\"}},\"required\":[\"expr\"]}}]}";
                } else if ("tools/call".equals(method)) {
                    String toolName = reqNode.path("params").path("name").asText();
                    if ("calculate".equals(toolName)) {
                        resultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"Calculation result: 84\"}],\"isError\":false}";
                    } else {
                        resultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"Unknown tool\"}],\"isError\":true}";
                    }
                } else if ("ping".equals(method)) {
                    resultJson = "{}";
                } else {
                    resultJson = "{}";
                }

                String response = "{\"jsonrpc\":\"2.0\",\"id\":" + idVal + ",\"result\":" + resultJson + "}";
                System.out.println(response);
                System.out.flush();
            } catch (Exception ignored) {
            }
        }
    }
}
