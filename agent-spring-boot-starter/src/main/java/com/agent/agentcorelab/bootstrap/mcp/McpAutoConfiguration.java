package com.agent.agentcorelab.bootstrap.mcp;

import com.agent.agentcorelab.bootstrap.AgentConfiguration;
import com.agent.agentcorelab.mcp.adapter.McpServerBridge;
import com.agent.agentcorelab.mcp.adapter.McpServerManager;
import com.agent.agentcorelab.mcp.client.DefaultMcpClient;
import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.skill.DefaultSkillRegistry;
import com.agent.agentcorelab.mcp.skill.SkillConsultantTool;
import com.agent.agentcorelab.mcp.skill.SkillDefinition;
import com.agent.agentcorelab.mcp.skill.SkillRegistry;
import com.agent.agentcorelab.mcp.transport.InMemoryMcpTransport;
import com.agent.agentcorelab.mcp.transport.McpTransport;
import com.agent.agentcorelab.mcp.transport.StdioProcessTransport;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot Auto-Configuration for Model Context Protocol (MCP) clients, tool adapters,
 * and Enterprise Skills ecosystem.
 */
@AutoConfiguration(before = AgentConfiguration.class)
@ConditionalOnClass(McpClient.class)
@ConditionalOnProperty(prefix = "agent.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentMcpProperties.class)
public class McpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SkillRegistry.class)
    public SkillRegistry skillRegistry(ObjectProvider<SkillDefinition> skillDefinitions) {
        List<SkillDefinition> initial = skillDefinitions.orderedStream().toList();
        log.info("Initialized Enterprise SkillRegistry with {} skill definition(s)", initial.size());
        return new DefaultSkillRegistry(initial);
    }

    @Bean
    @ConditionalOnMissingBean(SkillConsultantTool.class)
    public SkillConsultantTool skillConsultantTool(SkillRegistry skillRegistry, ObjectProvider<ToolCatalog> catalogProvider) {
        return new SkillConsultantTool(skillRegistry, catalogProvider::getIfAvailable);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(McpServerManager.class)
    public McpServerManager mcpServerManager(AgentMcpProperties properties) {
        McpServerManager manager = new McpServerManager();
        Map<String, AgentMcpProperties.ServerConfig> servers = properties.getServers();

        for (Map.Entry<String, AgentMcpProperties.ServerConfig> entry : servers.entrySet()) {
            String serverName = entry.getKey();
            AgentMcpProperties.ServerConfig config = entry.getValue();

            try {
                McpTransport transport;
                if ("in_memory".equalsIgnoreCase(config.getTransport())) {
                    transport = new InMemoryMcpTransport();
                } else {
                    if (config.getCommand() == null || config.getCommand().isBlank()) {
                        log.warn("MCP server [{}] configured with stdio transport but no executable command specified. Skipping.", serverName);
                        continue;
                    }
                    var builder = StdioProcessTransport.builder(config.getCommand())
                            .args(config.getArgs())
                            .environment(config.getEnv())
                            .startupTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));
                    if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().isBlank()) {
                        builder.workingDirectory(new File(config.getWorkingDirectory()));
                    }
                    transport = builder.build();
                }

                DefaultMcpClient client = new DefaultMcpClient(serverName, transport);
                var bridgeBuilder = McpServerBridge.builder(client);

                if (config.getToolPrefix() != null && !config.getToolPrefix().isBlank()) {
                    bridgeBuilder.toolPrefix(config.getToolPrefix());
                }
                String prefix = config.getToolPrefix() != null ? config.getToolPrefix() : "";
                if (!config.getIncludeTools().isEmpty()) {
                    Set<String> include = Set.copyOf(config.getIncludeTools());
                    bridgeBuilder.filter(name -> include.contains(name) || include.contains(prefix + name));
                }
                if (!config.getExcludeTools().isEmpty()) {
                    Set<String> exclude = Set.copyOf(config.getExcludeTools());
                    bridgeBuilder.filter(name -> !exclude.contains(name) && !exclude.contains(prefix + name));
                }
                if (!config.getHighRiskTools().isEmpty()) {
                    Set<String> highRisk = Set.copyOf(config.getHighRiskTools());
                    bridgeBuilder.riskResolver(name -> (highRisk.contains(name) || highRisk.contains(prefix + name)) ? RiskLevel.HIGH_RISK : RiskLevel.READ_ONLY);
                }

                McpServerBridge bridge = bridgeBuilder.build();
                bridge.initialize();
                manager.registerBridge(bridge);
                log.info("Successfully connected to MCP Server [{}] - discovered {} tool(s)", serverName, bridge.getTools().size());
            } catch (Exception e) {
                log.error("Failed to initialize MCP Server [{}]: {}", serverName, e.getMessage(), e);
            }
        }

        return manager;
    }

    @Bean
    @ConditionalOnMissingBean(name = "mcpTools")
    public List<Tool> mcpTools(McpServerManager mcpServerManager) {
        return mcpServerManager.allTools();
    }
}
