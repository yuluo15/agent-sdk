package com.agent.agentcorelab;

import com.agent.agentcorelab.bootstrap.AgentProperties;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgentCoreLabApplicationTests {

    @Autowired
    private AgentProperties properties;

    @Autowired
    private ToolCatalog toolCatalog;

    @Autowired
    private ScenarioRegistry scenarios;

    @Test
    void contextLoads() {
        assertEquals(16, properties.limits().maxModelRequests());
        assertEquals(30, properties.limits().maxToolCalls());
        assertEquals(Duration.ofSeconds(60), properties.limits().modelTimeout());
        assertEquals(Path.of("workspace/input"), properties.workspace().inputRoot().normalize());
        assertEquals(32768, properties.limits().maxContextTokens());
        assertEquals(ClarificationTool.NAME, toolCatalog.require(ClarificationTool.NAME).definition().name());
        assertEquals(2, scenarios.entries().size());
        assertTrue(scenarios.require("file-inspection").spec().toolNames().contains("listWorkspaceFiles"));
        assertTrue(scenarios.require("data-analysis").spec().toolNames().contains("analyzeDataset"));
    }
}
