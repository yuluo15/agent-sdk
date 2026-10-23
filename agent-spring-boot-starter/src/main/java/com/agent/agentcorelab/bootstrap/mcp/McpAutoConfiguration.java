package com.agent.agentcorelab.bootstrap.mcp;

import com.agent.agentcorelab.bootstrap.AgentConfiguration;
import com.agent.agentcorelab.mcp.adapter.McpServerBridge;
import com.agent.agentcorelab.mcp.adapter.McpServerManager;
import com.agent.agentcorelab.mcp.client.DefaultMcpClient;
import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.client.OfficialMcpClient;
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
import com.agent.agentcorelab.tool.ToolSemantics;
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
import java.util.LinkedHashMap;
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
    public SkillRegistry skillRegistry(ObjectProvider<SkillDefinition> skillDefinitions, AgentMcpProperties properties) {
        List<SkillDefinition> initial = skillDefinitions.orderedStream().toList();
        DefaultSkillRegistry registry = new DefaultSkillRegistry(initial);

        String skillsDir = properties.getSkillsDirectory();
        if (skillsDir != null && !skillsDir.isBlank()) {
            java.nio.file.Path path = java.nio.file.Paths.get(skillsDir.trim());
            if (java.nio.file.Files.isDirectory(path)) {
                List<SkillDefinition> loaded = registry.loadFromDirectory(path);
                log.info("Loaded {} enterprise skill(s) from directory: {}", loaded.size(), path.toAbsolutePath());
            } else if (java.nio.file.Files.isRegularFile(path)) {
                SkillDefinition loaded = registry.loadFromFile(path);
                log.info("Loaded enterprise skill '{}' from file: {}", loaded.id(), path.toAbsolutePath());
            } else {
                log.warn("Configured skillsDirectory does not exist: {}", path.toAbsolutePath());
            }
        }

        log.info("Initialized Enterprise SkillRegistry with {} skill definition(s)", registry.listAll().size());
        return registry;
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
                McpClient client;
                if ("in_memory".equalsIgnoreCase(config.getTransport())) {
                    McpTransport transport = new InMemoryMcpTransport();
                    client = new DefaultMcpClient(serverName, transport);
                } else if ("sse".equalsIgnoreCase(config.getTransport()) || "http".equalsIgnoreCase(config.getTransport())) {
                    if (config.getUrl() == null || config.getUrl().isBlank()) {
                        log.warn("MCP server [{}] configured with SSE transport but no URL specified. Skipping.", serverName);
                        continue;
                    }
                    var sseBuilder = OfficialMcpClient.builder(serverName)
                            .sse(config.getUrl(), config.getSseEndpoint())
                            .authorization(config.getToken())
                            .headers(config.getHeaders())
                            .requestTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));
                    client = sseBuilder.build();
                } else {
                    if (config.getCommand() == null || config.getCommand().isBlank()) {
                        log.warn("MCP server [{}] configured with stdio transport but no executable command specified. Skipping.", serverName);
                        continue;
                    }
                    if (config.isUseOfficialSdk()) {
                        var stdioBuilder = OfficialMcpClient.builder(serverName)
                                .stdio(config.getCommand(), config.getArgs(), config.getEnv())
                                .requestTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));
                        client = stdioBuilder.build();
                    } else {
                        var builder = StdioProcessTransport.builder(config.getCommand())
                                .args(config.getArgs())
                                .environment(config.getEnv())
                                .startupTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));
                        if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().isBlank()) {
                            builder.workingDirectory(new File(config.getWorkingDirectory()));
                        }
                        McpTransport transport = builder.build();
                        client = new DefaultMcpClient(serverName, transport);
                    }
                }

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
                if (!config.getToolSemantics().isEmpty()) {
                    Map<String, ToolSemantics> semanticsMap = new LinkedHashMap<>();
                    config.getToolSemantics().forEach((k, v) -> semanticsMap.put(k, v.toToolSemantics()));
                    bridgeBuilder.semanticsResolver(name -> {
                        ToolSemantics s = semanticsMap.get(name);
                        if (s == null && !prefix.isEmpty()) {
                            s = semanticsMap.get(prefix + name);
                        }
                        return s;
                    });
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
