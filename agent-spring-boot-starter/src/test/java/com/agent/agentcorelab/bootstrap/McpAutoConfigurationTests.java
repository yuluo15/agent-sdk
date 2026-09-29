package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.bootstrap.mcp.AgentMcpProperties;
import com.agent.agentcorelab.bootstrap.mcp.McpAutoConfiguration;
import com.agent.agentcorelab.mcp.adapter.McpServerManager;
import com.agent.agentcorelab.mcp.skill.SkillConsultantTool;
import com.agent.agentcorelab.mcp.skill.SkillDefinition;
import com.agent.agentcorelab.mcp.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpAutoConfiguration Spring Boot Tests")
class McpAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentConfiguration.class, McpAutoConfiguration.class));

    @Test
    @DisplayName("Defaults start with empty SkillRegistry and McpServerManager")
    void testDefaults() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean("skillRegistry"));
            assertTrue(context.containsBean("skillConsultantTool"));
            assertTrue(context.containsBean("mcpServerManager"));

            SkillRegistry registry = context.getBean(SkillRegistry.class);
            assertTrue(registry.listAll().isEmpty());

            SkillConsultantTool tool = context.getBean(SkillConsultantTool.class);
            assertEquals("consult_skill", tool.definition().name());
        });
    }

    @Test
    @DisplayName("Automatically picks up custom SkillDefinition beans")
    void testCustomSkillBeans() {
        runner.withUserConfiguration(SkillTestConfig.class).run(context -> {
            assertNull(context.getStartupFailure());
            SkillRegistry registry = context.getBean(SkillRegistry.class);
            assertEquals(1, registry.listAll().size());
            assertTrue(registry.hasSkill("demo-skill"));
        });
    }

    @Test
    @DisplayName("Loads in-memory MCP server from application properties")
    void testConfiguredMcpServer() {
        runner.withPropertyValues(
                "agent.mcp.servers.test-server.transport=in_memory",
                "agent.mcp.servers.test-server.tool-prefix=t_"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            McpServerManager manager = context.getBean(McpServerManager.class);
            assertNotNull(manager);
            assertTrue(manager.getBridge("test-server").isPresent());
        });
    }

    @Test
    @DisplayName("Disables MCP and Skills when agent.mcp.enabled is false")
    void testDisabled() {
        runner.withPropertyValues("agent.mcp.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("skillRegistry"));
            assertFalse(context.containsBean("mcpServerManager"));
        });
    }

    @Configuration
    static class SkillTestConfig {
        @Bean
        public SkillDefinition demoSkill() {
            return SkillDefinition.builder("demo-skill")
                    .name("Demo Skill")
                    .description("Test skill")
                    .sop("Follow demo steps")
                    .build();
        }
    }
}
