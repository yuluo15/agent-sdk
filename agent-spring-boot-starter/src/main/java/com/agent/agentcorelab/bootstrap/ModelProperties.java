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
    private TokenEstimatorProperties tokenEstimator = new TokenEstimatorProperties();

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
    public TokenEstimatorProperties getTokenEstimator() { return tokenEstimator; }
    public void setTokenEstimator(TokenEstimatorProperties tokenEstimator) { this.tokenEstimator = tokenEstimator; }

    public static class TokenEstimatorProperties {
        /**
         * Estimator algorithm: "character-weighted" (default, smart CJK/ASCII weighted) or "conservative-utf8".
         */
        private String type = "character-weighted";
        private double asciiWeight = 0.25;
        private double nonAsciiWeight = 0.70;
        private double safetyBuffer = 1.15;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public double getAsciiWeight() { return asciiWeight; }
        public void setAsciiWeight(double asciiWeight) { this.asciiWeight = asciiWeight; }
        public double getNonAsciiWeight() { return nonAsciiWeight; }
        public void setNonAsciiWeight(double nonAsciiWeight) { this.nonAsciiWeight = nonAsciiWeight; }
        public double getSafetyBuffer() { return safetyBuffer; }
        public void setSafetyBuffer(double safetyBuffer) { this.safetyBuffer = safetyBuffer; }
    }
}
