package com.agent.agentcorelab.bootstrap.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.mcp")
public class AgentMcpProperties {

    /**
     * Whether MCP integration and Enterprise Skills support are enabled.
     */
    private boolean enabled = true;

    /**
     * Map of logical server names to server configurations.
     */
    private Map<String, ServerConfig> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, ServerConfig> getServers() {
        return servers;
    }

    public void setServers(Map<String, ServerConfig> servers) {
        this.servers = servers;
    }

    public static class ServerConfig {
        /**
         * Transport type: "stdio" (default) or "in_memory" (useful for testing).
         */
        private String transport = "stdio";

        /**
         * Executable command for stdio process (e.g. "npx", "python", "node", "uvx").
         */
        private String command;

        /**
         * Command line arguments.
         */
        private List<String> args = new ArrayList<>();

        /**
         * Environment variables passed to the child process.
         */
        private Map<String, String> env = new LinkedHashMap<>();

        /**
         * Working directory for the child process.
         */
        private String workingDirectory;

        /**
         * Optional prefix prepended to all tool names from this server (e.g. "github_", "sqlite_").
         */
        private String toolPrefix;

        /**
         * Whitelist of tool names to import. If empty, all tools are imported.
         */
        private List<String> includeTools = new ArrayList<>();

        /**
         * Blacklist of tool names to exclude.
         */
        private List<String> excludeTools = new ArrayList<>();

        /**
         * Tool names to mark as RiskLevel.HIGH_RISK.
         */
        private List<String> highRiskTools = new ArrayList<>();

        /**
         * Startup / handshake timeout in seconds (default 30).
         */
        private int timeoutSeconds = 30;

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public String getToolPrefix() {
            return toolPrefix;
        }

        public void setToolPrefix(String toolPrefix) {
            this.toolPrefix = toolPrefix;
        }

        public List<String> getIncludeTools() {
            return includeTools;
        }

        public void setIncludeTools(List<String> includeTools) {
            this.includeTools = includeTools;
        }

        public List<String> getExcludeTools() {
            return excludeTools;
        }

        public void setExcludeTools(List<String> excludeTools) {
            this.excludeTools = excludeTools;
        }

        public List<String> getHighRiskTools() {
            return highRiskTools;
        }

        public void setHighRiskTools(List<String> highRiskTools) {
            this.highRiskTools = highRiskTools;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
