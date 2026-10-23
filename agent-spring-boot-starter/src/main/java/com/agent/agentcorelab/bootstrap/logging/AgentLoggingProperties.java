package com.agent.agentcorelab.bootstrap.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for structured SLF4J / Console logging of Agent executions.
 */
@ConfigurationProperties(prefix = "agent.logging")
public class AgentLoggingProperties {

    /**
     * Whether structured SLF4J / console logging for Agent runs is enabled.
     */
    private boolean enabled = true;

    /**
     * Whether to propagate {@code agent.runId} and {@code agent.id} into SLF4J MDC (Mapped Diagnostic Context).
     */
    private boolean mdcEnabled = true;

    /**
     * Maximum length of user goal or parameter text shown in logs (to prevent log flooding).
     */
    private int maxTextLength = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMdcEnabled() {
        return mdcEnabled;
    }

    public void setMdcEnabled(boolean mdcEnabled) {
        this.mdcEnabled = mdcEnabled;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }
}
