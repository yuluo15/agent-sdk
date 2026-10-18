package com.agent.agentcorelab.eval;

import com.agent.agentcorelab.eval.replay.CanonicalFingerprint;
import com.agent.agentcorelab.eval.replay.ReplayMissException;
import com.agent.agentcorelab.eval.replay.ReplayModelGateway;
import com.agent.agentcorelab.eval.replay.ReplayRecord;
import com.agent.agentcorelab.eval.replay.ReplayRecorder;
import com.agent.agentcorelab.memory.ChatMessage;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayProtocolTests {

    @Test
    void testCanonicalFingerprintDeterminism() {
        String sysPrompt = "You are a helpful assistant.\r\n";
        List<ChatMessage> msgs = List.of(ChatMessage.user("Hello world"));
        List<ToolDefinition> tools = List.of(
                new ToolDefinition("bTool", "desc B", "{}", RiskLevel.READ_ONLY),
                new ToolDefinition("aTool", "desc A", "{}", RiskLevel.READ_ONLY)
        );

        String fp1 = CanonicalFingerprint.compute(sysPrompt, msgs, tools, "deepseek-chat", 0.0, 4096);

        // Same with \n instead of \r\n and different tools order
        List<ToolDefinition> toolsReversed = List.of(tools.get(1), tools.get(0));
        String fp2 = CanonicalFingerprint.compute("You are a helpful assistant.\n", msgs, toolsReversed, "deepseek-chat", 0.0, 4096);

        assertEquals(fp1, fp2, "Fingerprints must match regardless of tool list order or newline format");

        // Change prompt
        String fpDifferent = CanonicalFingerprint.compute("Different prompt", msgs, tools, "deepseek-chat", 0.0, 4096);
        assertNotEquals(fp1, fpDifferent);
    }

    @Test
    void testReplayModelGatewayHitAndMiss() {
        ReplayRecorder recorder = new ReplayRecorder();
        ReplayModelGateway gateway = new ReplayModelGateway(recorder, "deepseek-chat", 0.0, 4096);

        String sys = "System instructions";
        String goal = "Investigate ticket TCK-1";
        List<ToolDefinition> tools = List.of(new ToolDefinition("lookupTicket", "desc", "{}", RiskLevel.READ_ONLY));

        String fp = CanonicalFingerprint.compute(
                sys,
                List.of(ChatMessage.user(goal)),
                tools,
                "deepseek-chat",
                0.0,
                4096
        );

        // Pre-record response
        ModelResponse recordedResponse = new ModelResponse(
                "Ticket details found",
                List.of(new ToolCall("call-1", "lookupTicket", "{\"ticketId\":\"TCK-1\"}")),
                ModelResponse.FinishReason.TOOL_CALLS,
                java.util.Optional.empty()
        );
        recorder.put(ReplayRecord.fromModelResponse(fp, recordedResponse));

        // Test Hit
        try (ModelSession session = gateway.openSession(sys, goal, tools)) {
            ModelResponse response = session.nextResponse(Duration.ofSeconds(5));
            assertNotNull(response);
            assertEquals("Ticket details found", response.text());
            assertEquals(1, response.toolCalls().size());
        }

        // Test Miss on different goal
        try (ModelSession session = gateway.openSession(sys, "Different goal entirely", tools)) {
            assertThrows(ReplayMissException.class, () -> session.nextResponse(Duration.ofSeconds(5)));
        }
    }
}
