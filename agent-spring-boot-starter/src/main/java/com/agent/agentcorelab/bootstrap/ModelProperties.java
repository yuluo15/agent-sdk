package com.agent.agentcorelab.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-backed configuration; deliberately no generated toString containing the key. */
@ConfigurationProperties("agent.model")
public class ModelProperties {
    private boolean enabled;
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String model;
    private int maxOutputTokens = 4096;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
}
